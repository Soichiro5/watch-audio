package app.watchaudio.android

import android.annotation.SuppressLint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import app.watchaudio.android.work.AndroidDeviceConditions
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.app.settings.updateSettings
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.pipeline.DeviceConditions
import app.watchaudio.core.pipeline.DiagnosticEvents
import app.watchaudio.core.pipeline.PolicyGatedTranscription
import app.watchaudio.core.pipeline.ProcessingPolicy
import app.watchaudio.core.pipeline.ThermalLevel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #75: the battery/thermal policy gates the production [app.watchaudio.android.work.TranscriptionWorker]
 * on the app's real database and ASR, scheduled by the app's [TranscriptionScheduler] on WorkManager's
 * test driver (as in [ChargingWorkTest]). The device state, the policy's limits and the budget's
 * clock are substituted through [AppGraph]'s test hooks:
 *
 * 1. on battery below 50 %: the run transcribes nothing and a run on the charger is queued;
 * 2. on battery at 80 % with an hourly cap of one chunk's audio: one chunk, then a run an hour later;
 * 3. plugged in, the phone warms up while the next chunk is transcribed: the run stops after that
 *    chunk (the third is never started) and a run in 15 minutes is queued;
 * 4. cooled down: that run transcribes the third chunk; every chunk was transcribed exactly once.
 */
@RunWith(AndroidJUnit4::class)
class BatteryPolicyWorkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = PlaybackFixtures.graph

    @SuppressLint("RestrictedApi") // WorkManagerImpl.setDelegate: hands WorkManager back to the app afterwards.
    @Test
    fun batteryAndThermalPolicyGateTheTranscriptionJob() {
        SpeechFixtures.ensureModels()
        SpeechFixtures.awaitTranscriptionIdle()
        // The real readings on this device, for the record (the test itself uses fixed ones).
        Evidence.log("POLICY_CHECK phase=device ${AndroidDeviceConditions(context).read().describe()}")

        // Three chunks older than anything else in the database, so they are the next ones in line.
        val wavs = listOf(1, 3, 5)
        val base = System.currentTimeMillis() - 10 * 86_400_000L
        val chunks = wavs.mapIndexed { index, wav ->
            val id = "policy-" + UUID.randomUUID()
            val manifest = SpeechFixtures.ingest(graph.ingestor, id, "policy", base + index * 600_000L, SpeechFixtures.speech(wav))
            Triple(id, wav, manifest.durationMs)
        }
        val ids = chunks.map { it.first }

        val now = System.currentTimeMillis()
        val conditions = Conditions()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        try {
            graph.deviceConditions = conditions::read
            // An hourly cap of exactly the first chunk's audio.
            graph.processingPolicy = ProcessingPolicy(ProcessingPolicy.Limits(batteryAudioMsPerWindow = chunks[0].third))
            graph.clock = { now }
            runBlocking {
                graph.store.transaction {
                    putSetting(PolicyGatedTranscription.KEY_BUDGET_WINDOW_START, null)
                    putSetting(PolicyGatedTranscription.KEY_BUDGET_USED_MS, null)
                }
                graph.store.updateSettings { it.copy(transcribeOnlyWhileCharging = false) }
            }
            val workManager = WorkManager.getInstance(context)
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

            // 1. On battery at 30 %.
            conditions.set(DeviceConditions(charging = false, batteryPercent = 30, powerSaveMode = false, thermal = ThermalLevel.NONE))
            val lowBattery = runNow(workManager, driver)
            assertTrue(lowBattery.constraints.requiresBatteryNotLow(), "the one-off job must require a battery that is not low")
            val onCharge = PlaybackFixtures.waitFor(60_000, "a run on the charger to be queued") {
                workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.ON_CHARGE).get()
                    .singleOrNull { it.state == WorkInfo.State.ENQUEUED }
            }
            val afterLow = states(ids)
            Evidence.log(
                "POLICY_CHECK phase=low_battery battery=30 charging=false job=${workManager.getWorkInfoById(lowBattery.id).get()?.state} " +
                    "chunks=$afterLow on_charge_job=${onCharge.state} requires_charging=${onCharge.constraints.requiresCharging()} " +
                    "log='${lastPolicyLine()}'",
            )
            assertEquals(List(3) { ChunkState.RECEIVED }, afterLow)
            assertTrue(onCharge.constraints.requiresCharging())
            assertTrue("decision=pause reason=low_battery" in lastPolicyLine())

            // 2. On battery at 80 %: one chunk fits the hourly cap.
            conditions.set(DeviceConditions(charging = false, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.NONE))
            val capped = runNow(workManager, driver)
            val later = PlaybackFixtures.waitFor(300_000, "the capped run to queue its next run") {
                workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get()
                    .singleOrNull { it.state == WorkInfo.State.ENQUEUED && it.id != capped.id }
            }
            val afterCap = states(ids)
            val budget = runBlocking {
                graph.store.read {
                    setting(PolicyGatedTranscription.KEY_BUDGET_USED_MS)?.toLong() to setting(PolicyGatedTranscription.KEY_BUDGET_WINDOW_START)?.toLong()
                }
            }
            Evidence.log(
                "POLICY_CHECK phase=hourly_cap battery=80 charging=false cap_ms=${chunks[0].third} chunks=$afterCap " +
                    "budget_used_ms=${budget.first} next_run_delay_ms=${later.initialDelayMillis} log='${lastPolicyLine()}'",
            )
            assertEquals(listOf(ChunkState.TRANSCRIBED, ChunkState.RECEIVED, ChunkState.RECEIVED), afterCap)
            assertEquals(chunks[0].third, budget.first)
            assertEquals(now, budget.second)
            assertEquals(3_600_000L, later.initialDelayMillis)

            // 3. Plugged in; the phone gets warm while the second chunk is transcribed.
            conditions.set(DeviceConditions(charging = true, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.NONE))
            conditions.warmAfterChecks(1)
            driver.setAllConstraintsMet(onCharge.id)
            val retry = PlaybackFixtures.waitFor(300_000, "the thermal pause to queue a retry") {
                workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get()
                    .singleOrNull { it.state == WorkInfo.State.ENQUEUED && it.initialDelayMillis == 15 * 60_000L }
            }
            PlaybackFixtures.waitFor(60_000, "the charging run to end") {
                workManager.getWorkInfoById(onCharge.id).get()?.takeIf { it.state.isFinished }
            }
            val afterHeat = states(ids)
            Evidence.log(
                "POLICY_CHECK phase=thermal charging=true thermal=moderate chunks=$afterHeat " +
                    "retry_delay_ms=${retry.initialDelayMillis} replaced_hourly_retry=${workManager.getWorkInfoById(later.id).get()?.state} " +
                    "log='${lastPolicyLine()}'",
            )
            assertEquals(listOf(ChunkState.TRANSCRIBED, ChunkState.TRANSCRIBED, ChunkState.RECEIVED), afterHeat)
            assertTrue("decision=pause reason=thermal" in lastPolicyLine())

            // 4. Cooled down: the retry finishes the rest.
            conditions.set(DeviceConditions(charging = true, batteryPercent = 80, powerSaveMode = false, thermal = ThermalLevel.LIGHT))
            driver.setInitialDelayMet(retry.id)
            driver.setAllConstraintsMet(retry.id)
            PlaybackFixtures.waitFor(300_000, "the retry to transcribe the last chunk") {
                states(ids).takeIf { it.all { state -> state == ChunkState.TRANSCRIBED } }
            }
            val attempts = runBlocking { graph.store.read { ids.map { chunk(it)!!.attempts } } }
            val starts = ids.map { id -> readLog().count { it.event == DiagnosticEvents.TRANSCRIPTION_STARTED && it.value("chunk") == id } }
            for ((id, wav) in chunks.map { it.first to it.second }) {
                val text = SpeechFixtures.transcriptOf(graph.store, id).joinToString("")
                Evidence.log("POLICY_CHECK phase=cooled chunk=$id text=$text")
                assertTrue(SpeechFixtures.KEYWORDS.getValue(wav) in text, "missing '${SpeechFixtures.KEYWORDS.getValue(wav)}' in: $text")
            }
            Evidence.log("POLICY_CHECK phase=done chunks=${ids.size} attempts=$attempts transcription_started=$starts")
            assertEquals(listOf(1, 1, 1), attempts)
            assertEquals(listOf(1, 1, 1), starts, "each chunk must be transcribed exactly once")
        } finally {
            graph.deviceConditions = AndroidDeviceConditions(context)::read
            graph.processingPolicy = ProcessingPolicy()
            graph.clock = System::currentTimeMillis
            graph.transcriptionPause.value = null
            runBlocking {
                graph.store.transaction {
                    putSetting(PolicyGatedTranscription.KEY_BUDGET_WINDOW_START, null)
                    putSetting(PolicyGatedTranscription.KEY_BUDGET_USED_MS, null)
                }
            }
            WorkManagerImpl.setDelegate(null)
        }
    }

    /** Asks for a run like a received chunk does, lets it start, and waits until it is over. */
    private fun runNow(workManager: WorkManager, driver: TestDriver): WorkInfo {
        graph.scheduler.requestNow()
        val queued = PlaybackFixtures.waitFor(60_000, "the run to be queued") {
            workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get().singleOrNull { it.state == WorkInfo.State.ENQUEUED }
        }
        driver.setAllConstraintsMet(queued.id)
        PlaybackFixtures.waitFor(300_000, "the run to finish") {
            workManager.getWorkInfoById(queued.id).get()?.takeIf { it.state.isFinished }
        }
        return queued
    }

    private fun states(ids: List<String>) = runBlocking { graph.store.read { ids.map { chunk(it)!!.state } } }

    /** The last `transcription_policy` line: the pause of the run that just ended (runs are sequential here). */
    private fun lastPolicyLine(): String {
        val line = readLog().lastOrNull { it.event == DiagnosticEvents.TRANSCRIPTION_POLICY }
        assertNotNull(line, "no transcription_policy line")
        return line.detail
    }

    private fun readLog(): List<LogLine> =
        listOf(graph.diagnostics.previousFile, graph.diagnostics.file).map { File(it.toString()) }.filter { it.isFile }
            .flatMap { file -> file.readLines().drop(1).mapNotNull(LogLine::parse) }

    private data class LogLine(val event: String, val detail: String) {
        fun value(key: String): String? = Regex("(?:^| )$key=(\\S+)").find(detail)?.groupValues?.get(1)

        companion object {
            fun parse(row: String): LogLine? = row.split(',', limit = 3).takeIf { it.size == 3 }?.let { LogLine(it[1], it[2]) }
        }
    }

    /** Fixed device conditions; optionally turns warm (thermal MODERATE) after a number of policy checks. */
    private class Conditions {
        @Volatile
        private var current: DeviceConditions? = null
        private val checks = AtomicInteger()

        @Volatile
        private var warmAfter = Int.MAX_VALUE

        fun set(conditions: DeviceConditions) {
            current = conditions
            warmAfter = Int.MAX_VALUE
            checks.set(0)
        }

        /**
         * The worker asks before every chunk: with [checks] = 1 the first chunk of the next run starts
         * cool and the check before the second one finds the phone warm.
         */
        fun warmAfterChecks(checks: Int) {
            this.checks.set(0)
            warmAfter = checks
        }

        fun read(): DeviceConditions {
            val base = checkNotNull(current)
            return if (checks.incrementAndGet() > warmAfter) base.copy(thermal = ThermalLevel.MODERATE) else base
        }
    }
}
