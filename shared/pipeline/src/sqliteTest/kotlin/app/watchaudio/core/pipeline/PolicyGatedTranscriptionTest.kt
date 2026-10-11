package app.watchaudio.core.pipeline

import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.PolicyGatedTranscription.Companion.batteryBudget
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** #75: the battery/thermal policy stops runs between chunks; the next run picks up exactly the rest. */
class PolicyGatedTranscriptionTest {
    private val dir = TempDir("policy")
    private val store = LocalStore.open(dir.resolve("db.sqlite").toString())
    private val minute = 60_000L
    private val hour = 60 * minute

    private var conditions = DeviceConditions(charging = true, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.NONE)
    private var now = 100 * hour
    private val log = mutableListOf<Pair<String, String>>()
    private val diagnostics = object : Diagnostics {
        override fun log(event: String, detail: String) {
            log += event to detail
        }
    }

    /** Records each call; [onTranscribe] lets a test change the device state while a chunk is being transcribed. */
    private inner class FakeTranscriber(val onTranscribe: (String) -> Unit = {}) : Transcriber {
        override val engineId = "fake"
        val calls = mutableListOf<String>()

        override suspend fun transcribe(parts: List<AudioPart>): List<TimedText> {
            val id = parts.single().file.name.removeSuffix(".m4a")
            calls += id
            onTranscribe(id)
            now += minute // transcription takes time
            return listOf(TimedText(1_000, 2_000, "発話 $id", "ja"))
        }
    }

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    /** Five-minute chunks with gaps between them (no boundary joins). */
    private suspend fun addChunks(vararg ids: String) = ids.forEachIndexed { index, id ->
        store.transaction {
            insertChunk(
                ChunkManifest(
                    chunkId = id, sessionId = "s", deviceId = "w", source = SourcePlatform.WEAROS,
                    startedAtEpochMs = index * 10 * minute, durationMs = 5 * minute, timeZone = "UTC",
                    audio = AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 1, "00"),
                    endReason = ChunkEndReason.ROTATION,
                ),
                "$id.m4a",
                0,
            )
        }
    }

    private fun gated(transcriber: Transcriber, policy: ProcessingPolicy = ProcessingPolicy()) = PolicyGatedTranscription(
        TranscriptionPipeline(store, dir.path, transcriber),
        store,
        policy,
        conditions = { conditions },
        clock = { now },
        diagnostics = diagnostics,
    )

    private suspend fun states(vararg ids: String) = store.read { ids.map { chunk(it)!!.state } }

    private suspend fun attempts(vararg ids: String) = store.read { ids.map { chunk(it)!!.attempts } }

    private suspend fun segmentsOf(id: String) = store.read { segmentsBetween(0, Long.MAX_VALUE) }.filter { it.chunkId == id }

    @Test
    fun thermalPauseStopsBetweenChunksAndTheNextRunFinishesTheRestExactlyOnce() = runTest {
        addChunks("a", "b", "c")
        // The phone warms up while "a" is being transcribed.
        val hot = FakeTranscriber { id -> if (id == "a") conditions = conditions.copy(thermal = ThermalLevel.MODERATE) }
        val first = gated(hot).run(chargingOnly = false)

        assertEquals(TranscriptionPipeline.RunResult(transcribed = 1, failed = 0), first.result)
        assertEquals(ProcessingDecision.Pause(PauseReason.THERMAL, 15 * minute), first.pause)
        assertEquals(listOf("a"), hot.calls)
        // "a" finished; "b" was never started (not left TRANSCRIBING, no attempt used).
        assertEquals(listOf(ChunkState.TRANSCRIBED, ChunkState.RECEIVED, ChunkState.RECEIVED), states("a", "b", "c"))
        assertEquals(listOf(1, 0, 0), attempts("a", "b", "c"))

        // Still warm: the retry does nothing.
        val stillHot = FakeTranscriber()
        assertEquals(PauseReason.THERMAL, gated(stillHot).run(chargingOnly = false).pause?.reason)
        assertEquals(emptyList(), stillHot.calls)

        // Cooled down: the rest, each once.
        conditions = conditions.copy(thermal = ThermalLevel.LIGHT)
        val cool = FakeTranscriber()
        val second = gated(cool).run(chargingOnly = false)
        assertNull(second.pause)
        assertEquals(TranscriptionPipeline.RunResult(transcribed = 2, failed = 0), second.result)
        assertEquals(listOf("b", "c"), cool.calls)
        assertEquals(List(3) { ChunkState.TRANSCRIBED }, states("a", "b", "c"))
        assertEquals(listOf(1, 1, 1), attempts("a", "b", "c"))
        for (id in listOf("a", "b", "c")) assertEquals(listOf("発話 $id"), segmentsOf(id).map { it.text })

        val policyLines = log.filter { it.first == DiagnosticEvents.TRANSCRIPTION_POLICY }.map { it.second }
        assertTrue(policyLines[0].startsWith("decision=run on_battery=false charging=true battery=80"), policyLines[0])
        assertTrue(
            policyLines[1].startsWith("decision=pause reason=thermal charging=true") &&
                policyLines[1].endsWith("thermal=moderate retry_ms=900000 after_chunks=1"),
            policyLines[1],
        )
    }

    @Test
    fun onBatteryBelowTheThresholdNothingRuns() = runTest {
        addChunks("a", "b")
        conditions = conditions.copy(charging = false, batteryPercent = 49)
        val fake = FakeTranscriber()
        val outcome = gated(fake).run(chargingOnly = false)
        assertEquals(ProcessingDecision.Pause(PauseReason.LOW_BATTERY, null), outcome.pause)
        assertEquals(emptyList(), fake.calls)
        assertEquals(listOf(ChunkState.RECEIVED, ChunkState.RECEIVED), states("a", "b"))
        assertTrue(log.single().second.startsWith("decision=pause reason=low_battery charging=false battery=49"))

        // The user's explicit request still runs.
        assertNull(gated(fake).run(chargingOnly = false, userRequested = true).pause)
        assertEquals(listOf("a", "b"), fake.calls)
    }

    @Test
    fun chargingOnlySettingWaitsForTheCharger() = runTest {
        addChunks("a")
        conditions = conditions.copy(charging = false, batteryPercent = 100)
        val fake = FakeTranscriber()
        assertEquals(PauseReason.CHARGING_ONLY, gated(fake).run(chargingOnly = true).pause?.reason)
        conditions = conditions.copy(charging = true)
        assertNull(gated(fake).run(chargingOnly = true).pause)
        assertEquals(listOf("a"), fake.calls)
    }

    @Test
    fun onBatteryTheHourlyCapStopsBetweenChunksAndSurvivesTheProcess() = runTest {
        addChunks("a", "b", "c", "d", "e")
        conditions = conditions.copy(charging = false, batteryPercent = 80)
        val start = now
        val first = FakeTranscriber()
        val outcome = gated(first).run(chargingOnly = false)
        // Ten minutes of audio per hour: two five-minute chunks.
        assertEquals(listOf("a", "b"), first.calls)
        // The window opened when "a" finished (one minute in) and two minutes have passed since.
        assertEquals(ProcessingDecision.Pause(PauseReason.HOURLY_CAP, hour - minute), outcome.pause)
        assertEquals(BatteryBudget(start + minute, 10 * minute), store.read { batteryBudget() })

        // A new run (e.g. after the process was killed) still sees the used-up budget.
        val again = FakeTranscriber()
        assertEquals(PauseReason.HOURLY_CAP, gated(again).run(chargingOnly = false).pause?.reason)
        assertEquals(emptyList(), again.calls)

        // An hour later: the next two.
        now = start + minute + hour
        val later = FakeTranscriber()
        assertEquals(PauseReason.HOURLY_CAP, gated(later).run(chargingOnly = false).pause?.reason)
        assertEquals(listOf("c", "d"), later.calls)

        // Plugged in: the rest, and charging does not use the budget.
        conditions = conditions.copy(charging = true)
        val budgetBefore = store.read { batteryBudget() }
        val charging = FakeTranscriber()
        assertNull(gated(charging).run(chargingOnly = false).pause)
        assertEquals(listOf("e"), charging.calls)
        assertEquals(budgetBefore, store.read { batteryBudget() })
        assertEquals(List(5) { ChunkState.TRANSCRIBED }, states("a", "b", "c", "d", "e"))
        assertEquals(List(5) { 1 }, attempts("a", "b", "c", "d", "e"))
    }

    @Test
    fun powerSaveAndUnpluggingMidRunStopAfterTheCurrentChunk() = runTest {
        addChunks("a", "b")
        val fake = FakeTranscriber { conditions = conditions.copy(charging = false, powerSaveMode = true) }
        val outcome = gated(fake).run(chargingOnly = false)
        assertEquals(PauseReason.POWER_SAVE, outcome.pause?.reason)
        assertEquals(listOf("a"), fake.calls)
        // Charged while transcribing "a": nothing was taken from the budget.
        assertEquals(BatteryBudget(), store.read { batteryBudget() })
    }
}
