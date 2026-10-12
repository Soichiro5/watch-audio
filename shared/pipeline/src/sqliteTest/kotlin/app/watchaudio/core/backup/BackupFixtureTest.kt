package app.watchaudio.core.backup

import app.watchaudio.core.backup.BackupTestData.PASSPHRASE
import app.watchaudio.core.backup.BackupTestData.createdAt
import app.watchaudio.core.backup.BackupTestData.diffCreatedAt
import app.watchaudio.core.backup.BackupTestData.tokyo
import app.watchaudio.core.db.LocalStore
import app.watchaudio.testing.TempDir
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray

/**
 * A backup moves between platforms (#40): this test runs on the JVM, the iOS simulator and the
 * Android emulator (CI), and on each it must write exactly the bytes of the checked-in full backup
 * and diff (#164) (`testdata/backup/v2-*.wabackup`, embedded in `BackupFixtureV2Data.kt`) and
 * restore them completely, in either order. So a backup made on any of them restores on any other.
 * The backup written before diffs existed (`v1-with-audio.wabackup`, [BACKUP_FIXTURE_V1_BASE64])
 * must still restore everywhere.
 *
 * If the writer's output changes on purpose (e.g. the export's README), regenerate the v2 fixtures
 * with `UPDATE_BACKUP_FIXTURE=1 ./gradlew :shared:pipeline:jvmTest --tests '*BackupFixtureFileTest*'`
 * and run the tests again. A change of the format itself keeps the older fixtures restorable.
 */
class BackupFixtureTest {
    private val dir = TempDir("backup-fixture")

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    @Test
    fun thisPlatformWritesTheFixtures() = runTest {
        val written = BackupFixture.write(dir)
        assertContentEquals(BackupFixture.full, written.full)
        assertContentEquals(BackupFixture.diff, written.diff)
    }

    @Test
    fun thisPlatformRestoresTheFullBackupAndTheDiff() = runTest {
        restoresTheV2Fixtures(listOf(BackupFixture.full, BackupFixture.diff))
    }

    @Test
    fun thisPlatformRestoresTheDiffBeforeTheFullBackup() = runTest {
        restoresTheV2Fixtures(listOf(BackupFixture.diff, BackupFixture.full))
    }

    private suspend fun restoresTheV2Fixtures(files: List<ByteArray>) {
        val sourceAudio = dir.resolve("source-audio")
        val targetAudio = dir.resolve("target-audio")
        LocalStore.open(dir.resolve("source.db").toString()).use { source ->
            LocalStore.open(dir.resolve("target.db").toString()).use { target ->
                // The source as it was when the diff was made, including what its saved backups marked.
                BackupFixture.build(source, sourceAudio)
                val restorer = BackupRestorer(target, targetAudio, BackupTestData.clockAt(diffCreatedAt + 1))
                files.forEach { file -> restorer.restore({ Buffer().apply { write(file) } }, PASSPHRASE) }

                assertEquals(BackupTestData.exportedDays(source, sourceAudio, diffCreatedAt), BackupTestData.exportedDays(target, targetAudio, diffCreatedAt))
                (BackupTestData.seeded + listOf("d0", "d1")).forEach { id ->
                    val chunk = assertNotNull(target.read { chunk(id) })
                    val info = source.read { backupInfoOfChunksStartedBetween(chunk.startedAt, chunk.startedAt + 1) }
                    assertEquals(info, target.read { backupInfoOfChunksStartedBetween(chunk.startedAt, chunk.startedAt + 1) }, id)
                    if (chunk.hasAudioFile) {
                        assertContentEquals(BackupTestData.audioBytes(id), BackupTestData.readFile(Path(targetAudio, chunk.audioPath!!)), id)
                    }
                }
                assertEquals(listOf("d1"), target.read { pendingChunks(10, 3) }.map { it.id })
            }
        }
    }

    @Test
    fun thisPlatformRestoresTheV1Fixture() = runTest {
        val sourceAudio = dir.resolve("source-audio")
        val targetAudio = dir.resolve("target-audio")
        LocalStore.open(dir.resolve("source.db").toString()).use { source ->
            LocalStore.open(dir.resolve("target.db").toString()).use { target ->
                BackupTestData.seed(source, sourceAudio)
                val restorer = BackupRestorer(target, targetAudio, BackupTestData.clockAt(createdAt + 1))
                val summary = restorer.restore({ Buffer().apply { write(BackupFixture.v1) } }, PASSPHRASE)

                assertEquals(7, summary.chunksAdded)
                assertEquals(5, summary.audioFilesRestored)
                assertEquals(BackupKind.FULL, summary.kind)
                // The restored audio counts as in the backup (#156), as on the phone that saved it.
                source.transaction { markAudioBackedUp(listOf("a0", "b0", "b1", "b3", "b4"), createdAt) }
                assertEquals(BackupTestData.exportedDays(source, sourceAudio), BackupTestData.exportedDays(target, targetAudio))
                listOf("a0", "b0", "b1", "b3", "b4").forEach { id ->
                    val chunk = assertNotNull(target.read { chunk(id) })
                    assertContentEquals(BackupTestData.audioBytes(id), BackupTestData.readFile(Path(targetAudio, chunk.audioPath!!)))
                }
                // Written before #164: the records have no version, so any newer backup's transcript wins.
                assertEquals(null, target.read { backupInfoOfChunksStartedBetween(Long.MIN_VALUE, Long.MAX_VALUE) }.values.mapNotNull { it.updatedAt }.firstOrNull())
            }
        }
    }
}

/** The checked-in backups and how they were made. */
internal object BackupFixture {
    /** Paths of the files, relative to the module directory. */
    const val V1_FILE = "testdata/backup/v1-with-audio.wabackup"
    const val FULL_FILE = "testdata/backup/v2-full-with-audio.wabackup"
    const val DIFF_FILE = "testdata/backup/v2-diff-with-audio.wabackup"

    /** Written before diffs existed (#40); no longer written, only restored. */
    val v1: ByteArray get() = Base64.decode(BACKUP_FIXTURE_V1_BASE64)

    val full: ByteArray get() = Base64.decode(BACKUP_FIXTURE_V2_FULL_BASE64)

    val diff: ByteArray get() = Base64.decode(BACKUP_FIXTURE_V2_DIFF_BASE64)

    class Written(val full: ByteArray, val diff: ByteArray)

    /**
     * Makes [store] what the fixtures were made from: [BackupTestData.seed], a full backup with
     * audio at [createdAt] (saved), [BackupTestData.changeAfterBackup], and a diff with audio at
     * [diffCreatedAt] (saved). Versions are fixed so that the files are the same everywhere.
     * @return the two files (low PBKDF2 rounds, small chunks).
     */
    suspend fun build(store: LocalStore, audio: Path): Written {
        BackupTestData.seed(store, audio)
        BackupTestData.fixVersions(store, BackupTestData.seeded, BackupTestData.seedVersion)
        val writer = BackupWriter(store, audio, iterations = 1_000)
        val full = write(writer, assertNotNull(writer.plan(includeAudio = true, timeZone = tokyo, now = createdAt)))
        BackupTestData.fixVersions(store, BackupTestData.changeAfterBackup(store, audio), BackupTestData.changeVersion)
        val diff = write(writer, assertNotNull(writer.plan(includeAudio = true, timeZone = tokyo, now = diffCreatedAt, kind = BackupKind.DIFF)))
        return Written(full, diff)
    }

    private suspend fun write(writer: BackupWriter, plan: BackupPlan): ByteArray {
        val out = Buffer()
        val summary = writer.write(plan, PASSPHRASE, out, chunkSize = 1024, random = BackupTestData.fixedRandom)
        writer.recordSaved(plan, summary)
        return out.readByteArray()
    }

    /** Writes the fixtures the way they were made. */
    suspend fun write(dir: TempDir): Written = LocalStore.open(dir.resolve("fixture.db").toString()).use { store ->
        build(store, dir.resolve("fixture-audio"))
    }
}
