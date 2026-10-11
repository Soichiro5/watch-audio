package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.TranscriptionRun.FollowUp
import app.watchaudio.core.settings.updateSettings
import app.watchaudio.testing.TempDir
import app.watchaudio.testing.testAudio
import app.watchaudio.testing.testManifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** #134: one transcription run, as Android's worker and iOS's foreground/BGTask runs use it. */
class TranscriptionRunTest {
    private val dir = TempDir("run")
    private val store = LocalStore.open(dir.resolve(LocalStore.FILE_NAME).toString())
    private val minute = 60_000L
    private val now = 1_791_622_800_000L // 2026-10-10T09:00:00Z

    private var conditions = DeviceConditions(charging = true, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.NONE)
    private val log = mutableListOf<Pair<String, String>>()
    private val diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            log += event to detail
        }
    }

    /** Transcribes every chunk, except those in [failing]. */
    private val transcriber = object : Transcriber {
        override val engineId = "fake"
        val failing = mutableSetOf<String>()
        val calls = mutableListOf<String>()

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            val id = parts.single().file.name.removeSuffix(".m4a")
            calls += id
            if (id in failing) error("cannot decode $id")
            return listOf(TimedText(1_000, 2_000, "発話 $id", "ja"))
        }
    }

    private val model = object : SpeechModel {
        var state: ModelState = ModelState.Ready
        var rechecks = 0

        override suspend fun ensureReady(): ModelState = state

        override fun recheck() {
            rechecks++
        }
    }

    private var releases = 0
    private val pipeline = TranscriptionPipeline(store, dir.path, transcriber)
    private val storage = StorageManager(
        store,
        dir.path,
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(now)
        },
    )
    private val run = TranscriptionRun(
        pipeline,
        store,
        storage,
        model,
        diagnostics,
        gate = { PolicyGatedTranscription(pipeline, store, ProcessingPolicy(), { conditions }, { now }, diagnostics) },
        releaseTranscriber = { releases++ },
    )

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    /** Five-minute chunks recorded [daysAgo] days ago, 10 minutes apart (no boundary joins). */
    private suspend fun addChunks(vararg ids: String, daysAgo: Int = 0) = ids.forEachIndexed { index, id ->
        SystemFileSystem.sink(Path(dir.path, "$id.m4a")).buffered().use { it.write(ByteArray(100)) }
        store.transaction {
            insertChunk(
                testManifest(
                    id,
                    startedAt = now - daysAgo * 86_400_000L - 3_600_000L + index * 10 * minute,
                    durationMs = 5 * minute,
                    audio = testAudio(id, sizeBytes = 100),
                ),
                "$id.m4a",
                0,
            )
        }
    }

    private suspend fun states(vararg ids: String) = store.read { ids.map { chunk(it)!!.state } }

    private fun events(name: String) = log.filter { it.first == name }.map { it.second }

    @Test
    fun recoversInterruptedChunksAtTheStartOfEveryRun() = runTest {
        addChunks("a", "b")
        // A run that was killed in the middle of "a" (this process never ran before).
        store.transaction { markTranscribing("a") }

        val report = run.run("test")

        assertEquals(1, report.recovered)
        assertEquals(listOf(ChunkState.TRANSCRIBED, ChunkState.TRANSCRIBED), states("a", "b"))
        assertEquals(listOf(2, 1), store.read { listOf("a", "b").map { chunk(it)!!.attempts } })
        assertEquals(FollowUp.None, report.followUp)
        assertEquals(1, releases)
    }

    @Test
    fun logsTheRunAndEachChunk() = runTest {
        addChunks("a")
        run.run("foreground", startDetail = " attempt=1", stopDetail = { " stopped=false" })

        assertEquals(listOf("worker=foreground attempt=1"), events(DiagnosticEvents.WORKER_START))
        assertEquals(listOf("worker=foreground transcribed=1 failed=0 stopped=false"), events(DiagnosticEvents.WORKER_STOP))
        assertEquals(listOf("chunk=a"), events(DiagnosticEvents.TRANSCRIPTION_STARTED))
        assertTrue(events(DiagnosticEvents.TRANSCRIPTION_FINISHED).single().startsWith("chunk=a audio_ms=300000 elapsed_ms="))
    }

    @Test
    fun withoutTheModelNothingIsTranscribedButRecoveryAndRetentionStillRun() = runTest {
        addChunks("old", daysAgo = 40)
        store.transaction { saveTranscript("old", listOf(NewSegment(0, 1, "x", "ja")), "fake") }
        storage.setRetention(AudioRetention.DAYS_30)
        addChunks("a")
        store.transaction { markTranscribing("a") }
        model.state = ModelState.Downloading(0.5f)

        val report = run.run("test")

        assertNull(report.transcription)
        assertEquals(1, report.recovered)
        assertEquals(listOf(ChunkState.RECEIVED), states("a"))
        assertEquals(TranscriptionRun.Retention.Purged(StorageManager.PurgeResult(1, 100)), report.retention)
        assertEquals(FollowUp.WaitForModel(downloading = true), report.followUp)
        assertEquals(listOf("worker=test skipped=model_not_ready"), events(DiagnosticEvents.WORKER_STOP))
        assertEquals(0, releases)
        assertTrue(transcriber.calls.isEmpty())

        model.state = ModelState.Missing
        assertEquals(FollowUp.WaitForModel(downloading = false), run.run("test").followUp)
    }

    @Test
    fun failuresRecheckTheModelAndRetryUntilTheAttemptsAreUsedUp() = runTest {
        addChunks("bad", "good")
        transcriber.failing += "bad"

        val first = run.run("test")
        assertEquals(TranscriptionPipeline.RunResult(transcribed = 1, failed = 1), first.transcription?.result)
        assertEquals(1, model.rechecks)
        assertEquals(FollowUp.RunAgain, first.followUp)

        assertEquals(FollowUp.RunAgain, run.run("test").followUp)
        // The third failure is the last attempt (TranscriptionPipeline.DEFAULT_MAX_ATTEMPTS).
        assertEquals(FollowUp.None, run.run("test").followUp)
        assertEquals(3, model.rechecks)
        assertEquals(listOf(ChunkState.FAILED, ChunkState.TRANSCRIBED), states("bad", "good"))
        assertEquals(TranscriptionPipeline.DEFAULT_MAX_ATTEMPTS, store.read { chunk("bad")!!.attempts })
    }

    @Test
    fun aPolicyPauseNamesTheRetryAndTheCharger() = runTest {
        addChunks("a")
        conditions = conditions.copy(charging = false, batteryPercent = 30)
        val lowBattery = run.run("test")
        assertEquals(FollowUp.Paused(PauseReason.LOW_BATTERY, retryAfterMs = null, onCharge = true), lowBattery.followUp)
        assertEquals(PauseReason.LOW_BATTERY, lowBattery.transcription?.pause?.reason)

        conditions = conditions.copy(charging = true, thermal = ThermalLevel.SEVERE)
        assertEquals(FollowUp.Paused(PauseReason.THERMAL, retryAfterMs = 15 * minute, onCharge = false), run.run("test").followUp)
        assertEquals(listOf(ChunkState.RECEIVED), states("a"))
    }

    @Test
    fun honoursTheChargingOnlySettingAndTheUsersRequest() = runTest {
        addChunks("a")
        store.updateSettings { it.copy(transcribeOnlyWhileCharging = true) }
        conditions = conditions.copy(charging = false)
        assertEquals(PauseReason.CHARGING_ONLY, run.run("test").transcription?.pause?.reason)

        // The user's tap waives the battery rules.
        assertEquals(FollowUp.None, run.run("test", userRequested = true).followUp)
        assertEquals(listOf(ChunkState.TRANSCRIBED), states("a"))
    }

    @Test
    fun aRunStoppedByThePlatformIsInterrupted() = runTest {
        addChunks("a", "b")
        var checks = 0
        val transcribing = mutableListOf<Boolean>()
        // The policy asks before every chunk: let the first one through, then stop.
        val report = run.run("test", shouldContinue = { checks++ < 1 }, onTranscribing = { transcribing += it })

        assertEquals(FollowUp.Interrupted, report.followUp)
        assertEquals(listOf(ChunkState.TRANSCRIBED, ChunkState.RECEIVED), states("a", "b"))
        assertEquals(listOf(true, false), transcribing)
    }

    @Test
    fun aFailedRetentionIsReportedNotThrown() = runTest {
        // Retention over a database that is closed: every call fails.
        val closed = LocalStore.open(dir.resolve("closed.db").toString()).also { it.close() }
        val failing = TranscriptionRun(
            pipeline,
            store,
            StorageManager(closed, dir.path),
            model,
            diagnostics,
            gate = { PolicyGatedTranscription(pipeline, store, ProcessingPolicy(), { conditions }, { now }, diagnostics) },
        )
        addChunks("a")

        val report = failing.run("test")

        assertIs<TranscriptionRun.Retention.Failed>(report.retention)
        assertTrue(events(DiagnosticEvents.RETENTION_PURGE).single().startsWith("failed=true error="))
        // The transcription itself was not affected.
        assertEquals(listOf(ChunkState.TRANSCRIBED), states("a"))
        assertEquals(FollowUp.None, report.followUp)
    }
}
