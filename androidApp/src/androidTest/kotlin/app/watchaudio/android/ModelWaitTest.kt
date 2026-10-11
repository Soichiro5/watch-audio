package app.watchaudio.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.app.ModelState
import app.watchaudio.app.Strings
import app.watchaudio.app.settings.updateSettings
import app.watchaudio.core.db.ChunkState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #22: without the speech model a received chunk is stored and playable, and transcription waits
 * (the chunk stays pending, nothing is marked failed); once the model is in place the app
 * transcribes it on its own. Runs on the app's real graph and WorkManager.
 */
@RunWith(AndroidJUnit4::class)
class ModelWaitTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph get() = PlaybackFixtures.graph

    @After
    fun tearDown() {
        PlaybackFixtures.stopPlayback()
        restoreModels()
    }

    @Test
    fun transcriptionWaitsForTheModelAndResumesOnceItIsInstalled() {
        val files = SpeechFixtures.ensureModels()
        SpeechFixtures.awaitTranscriptionIdle()
        // Park the models aside: the app now looks like a fresh install before the download.
        SpeechFixtures.hiddenModels.deleteRecursively()
        check(files.root.renameTo(SpeechFixtures.hiddenModels)) { "cannot move the models aside" }
        runBlocking { graph.models.refresh() }
        assertEquals(ModelState.Missing, graph.models.state.value)

        // A chunk arrives from the watch (ChunkReceiver stores it and asks for a run).
        val chunkId = "model-wait-" + UUID.randomUUID()
        val startedAt = System.currentTimeMillis() - 2 * 86_400_000L
        SpeechFixtures.ingest(graph.ingestor, chunkId, "model-wait", startedAt, SpeechFixtures.speech(3))
        val workerState = runAndAwaitTranscriptionWork()
        val waiting = runBlocking { graph.store.read { chunk(chunkId) } }!!
        Evidence.log(
            "MODEL_CHECK phase=no_model model=${graph.models.state.value::class.simpleName} worker=$workerState " +
                "chunk_state=${waiting.state} attempts=${waiting.attempts}",
        )
        assertEquals(WorkInfo.State.SUCCEEDED, workerState)
        assertEquals(ChunkState.RECEIVED, waiting.state)
        assertEquals(0, waiting.attempts)

        // Received audio plays without the model.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { graph.services.play(waiting.audioPath!!, 1_000) }
        PlaybackFixtures.assertPlaysFrom(requestedMs = 1_000, check = "without_model")
        PlaybackFixtures.stopPlayback()

        runBlocking { PlaybackFixtures.graph.store.updateSettings { it.copy(onboardingCompleted = true) } }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openTab(Strings.TAB_STATUS)
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodes(hasText(Strings.MODEL_MISSING)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasText(Strings.STAT_PENDING)).fetchSemanticsNodes().isNotEmpty()
            }
            Evidence.screenshot("status-model-missing")

            // The model arrives (download finished / sideloaded): the app picks the chunk up by itself.
            restoreModels()
            runBlocking { graph.models.refresh() }
            val transcribed = PlaybackFixtures.waitFor(300_000, "the chunk to be transcribed after the model arrived") {
                runBlocking { graph.store.read { chunk(chunkId) } }?.takeIf { it.state == ChunkState.TRANSCRIBED }
            }
            val text = SpeechFixtures.transcriptOf(graph.store, chunkId).joinToString("")
            Evidence.log("MODEL_CHECK phase=model_installed model=${graph.models.state.value::class.simpleName} chunk_state=${transcribed.state} text=$text")
            assertTrue("ヤンバルクイナ" in text, "missing 'ヤンバルクイナ' in: $text")
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodes(hasText(Strings.MODEL_READY)).fetchSemanticsNodes().isNotEmpty()
            }
            Evidence.screenshot("status-model-ready")
        }
    }

    /** Asks for a transcription run like a received chunk does and waits for that run to end. */
    private fun runAndAwaitTranscriptionWork(): WorkInfo.State {
        val workManager = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        // A run that is already queued counts too (requestNow then adds nothing): it starts after the chunk was stored.
        val finishedBefore = workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get()
            .filter { it.state.isFinished }.map { it.id }.toSet()
        graph.scheduler.requestNow()
        return PlaybackFixtures.waitFor(120_000, "a transcription run") {
            workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get()
                .firstOrNull { it.id !in finishedBefore && it.state.isFinished }?.state
        }
    }

    private fun restoreModels() {
        val files = graph.models.files
        val hidden = SpeechFixtures.hiddenModels
        if (hidden.isDirectory) {
            files.root.deleteRecursively()
            check(hidden.renameTo(files.root)) { "cannot restore the models" }
        }
    }
}
