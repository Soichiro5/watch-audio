package app.watchaudio.core.backup

import app.watchaudio.core.backup.BackupTestData.PASSPHRASE
import app.watchaudio.core.backup.BackupTestData.createdAt
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
 * Android emulator (CI), and on each it must write exactly the bytes of the checked-in backup
 * (`testdata/backup/v1-with-audio.wabackup`, embedded as [BACKUP_FIXTURE_V1_BASE64]) and restore
 * that file completely. So a backup made on any of them restores on any other.
 *
 * If the writer's output changes on purpose (e.g. the export's README), regenerate the fixture
 * with `UPDATE_BACKUP_FIXTURE=1 ./gradlew :shared:pipeline:jvmTest --tests '*BackupFixtureFileTest*'`
 * and run the tests again. A change of the format itself also keeps the old fixture restorable.
 */
class BackupFixtureTest {
    private val dir = TempDir("backup-fixture")

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    @Test
    fun thisPlatformWritesTheFixture() = runTest {
        assertContentEquals(BackupFixture.bytes, BackupFixture.write(dir))
    }

    @Test
    fun thisPlatformRestoresTheFixture() = runTest {
        val sourceAudio = dir.resolve("source-audio")
        val targetAudio = dir.resolve("target-audio")
        LocalStore.open(dir.resolve("source.db").toString()).use { source ->
            LocalStore.open(dir.resolve("target.db").toString()).use { target ->
                BackupTestData.seed(source, sourceAudio)
                val restorer = BackupRestorer(target, targetAudio, BackupTestData.clockAt(createdAt + 1))
                val summary = restorer.restore({ Buffer().apply { write(BackupFixture.bytes) } }, PASSPHRASE)

                assertEquals(7, summary.chunksAdded)
                assertEquals(5, summary.audioFilesRestored)
                // The restored audio counts as in the backup (#156), as on the phone that saved it.
                source.transaction { markAudioBackedUp(listOf("a0", "b0", "b1", "b3", "b4"), createdAt) }
                assertEquals(BackupTestData.exportedDays(source, sourceAudio), BackupTestData.exportedDays(target, targetAudio))
                listOf("a0", "b0", "b1", "b3", "b4").forEach { id ->
                    val chunk = assertNotNull(target.read { chunk(id) })
                    assertContentEquals(BackupTestData.audioBytes(id), BackupTestData.readFile(Path(targetAudio, chunk.audioPath!!)))
                }
            }
        }
    }
}

/** The checked-in backup and how it was made. */
internal object BackupFixture {
    /** Path of the file, relative to the module directory. */
    const val FILE = "testdata/backup/v1-with-audio.wabackup"

    val bytes: ByteArray get() = Base64.decode(BACKUP_FIXTURE_V1_BASE64)

    /** Writes the backup of [BackupTestData] the way the fixture was made (low PBKDF2 rounds, small chunks). */
    suspend fun write(dir: TempDir): ByteArray {
        val audio = dir.resolve("fixture-audio")
        return LocalStore.open(dir.resolve("fixture.db").toString()).use { store ->
            BackupTestData.seed(store, audio)
            val writer = BackupWriter(store, audio, iterations = 1_000)
            val plan = assertNotNull(writer.plan(includeAudio = true, timeZone = tokyo, now = createdAt))
            val out = Buffer()
            writer.write(plan, PASSPHRASE, out, chunkSize = 1024, random = BackupTestData.fixedRandom)
            out.readByteArray()
        }
    }
}
