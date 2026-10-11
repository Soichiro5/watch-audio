package app.watchaudio.android

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.core.db.ChunkState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #20: the periodic safety-net job of [TranscriptionScheduler] only runs while charging, and when
 * it runs it transcribes the chunks that piled up. WorkManager's test driver plays the role of the
 * charger ([androidx.work.testing.TestDriver.setAllConstraintsMet]); the job itself is the
 * production [app.watchaudio.android.work.TranscriptionWorker] on the app's real database.
 */
@RunWith(AndroidJUnit4::class)
class ChargingWorkTest {
    @SuppressLint("RestrictedApi") // WorkManagerImpl.setDelegate: hands WorkManager back to the app afterwards.
    @Test
    fun periodicJobTranscribesPendingChunksOnceCharging() {
        SpeechFixtures.ensureModels()
        SpeechFixtures.awaitTranscriptionIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val graph = PlaybackFixtures.graph
        // Chunks that arrived while the immediate run could not happen (no trigger is sent here).
        val ids = listOf(1, 5).map { wav ->
            val id = "charging-" + UUID.randomUUID()
            val startedAt = System.currentTimeMillis() - 3 * 86_400_000L + wav * 120_000L
            SpeechFixtures.ingest(graph.ingestor, id, "charging", startedAt, SpeechFixtures.speech(wav))
            id to wav
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setMinimumLoggingLevel(Log.DEBUG).build(),
        )
        try {
            // The production scheduling code, against the test WorkManager.
            TranscriptionScheduler(context, scope).schedulePeriodic()
            val workManager = WorkManager.getInstance(context)
            val periodic = workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.PERIODIC).get().single()
            assertTrue(periodic.constraints.requiresCharging(), "the periodic job must require charging")
            assertNotNull(periodic.periodicityInfo)

            // Not charging: the job stays queued and the chunks stay pending.
            SystemClock.sleep(3_000)
            val beforeCharging = workManager.getWorkInfoById(periodic.id).get()!!
            val pendingBefore = runBlocking { graph.store.read { ids.map { (id, _) -> chunk(id)!!.state } } }
            Evidence.log("CHARGING_CHECK phase=not_charging job=${beforeCharging.state} runs=${beforeCharging.runAttemptCount} chunks=$pendingBefore")
            assertEquals(WorkInfo.State.ENQUEUED, beforeCharging.state)
            assertEquals(0, beforeCharging.runAttemptCount)
            assertTrue(pendingBefore.all { it == ChunkState.RECEIVED }, "chunks were processed before charging: $pendingBefore")

            // Plugged in.
            val started = SystemClock.uptimeMillis()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(periodic.id)
            PlaybackFixtures.waitFor(300_000, "the charging job to transcribe the pending chunks") {
                runBlocking { graph.store.read { ids.all { (id, _) -> chunk(id)!!.state == ChunkState.TRANSCRIBED } } }.takeIf { it }
            }
            val elapsedMs = SystemClock.uptimeMillis() - started
            val afterRun = PlaybackFixtures.waitFor(60_000, "the periodic job to be re-armed") {
                workManager.getWorkInfoById(periodic.id).get()?.takeIf { it.state == WorkInfo.State.ENQUEUED }
            }
            for ((id, wav) in ids) {
                val text = SpeechFixtures.transcriptOf(graph.store, id).joinToString("")
                val keyword = SpeechFixtures.KEYWORDS.getValue(wav)
                Evidence.log("CHARGING_CHECK phase=charging chunk=$id state=TRANSCRIBED text=$text")
                assertTrue(keyword in text, "missing '$keyword' in: $text")
            }
            Evidence.log("CHARGING_CHECK phase=done chunks=${ids.size} transcribed=${ids.size} elapsed_ms=$elapsedMs job_after=${afterRun.state}")
        } finally {
            scope.cancel()
            WorkManagerImpl.setDelegate(null)
        }
    }
}
