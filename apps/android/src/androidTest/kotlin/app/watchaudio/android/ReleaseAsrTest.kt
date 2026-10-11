package app.watchaudio.android

import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.core.db.ChunkState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #78: the R8-minified release build receives a known Japanese chunk and transcribes it into its
 * database through the production path: [AppGraph.ingestor] (what ChunkReceiver calls), the
 * app's WorkManager transcription run and the sherpa-onnx JNI. Catches keep rules that R8 needs
 * for WorkManager, sherpa-onnx and kotlinx.serialization.
 *
 * Runs only from emulator.yml (`-e scenario release-asr`, .github/scripts/release-asr.sh) against
 * the release APK, with the test APK built by `-Pwatchaudio.testBuildType=release`.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseAsrTest {
    private val graph get() = PlaybackFixtures.graph

    @Test
    fun releaseBuildTranscribesAJapaneseChunkIntoItsDatabase() {
        Evidence.assumeScenario("release-asr")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        val debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        assertFalse(debuggable, "expected the release build (not debuggable)")

        SpeechFixtures.ensureModels()
        SpeechFixtures.awaitTranscriptionIdle()
        val chunkId = "release-asr-" + UUID.randomUUID()
        val startedAt = System.currentTimeMillis() - 3_600_000
        val manifest = SpeechFixtures.ingest(graph.ingestor, chunkId, "release-asr", startedAt, SpeechFixtures.speech(1, 3, 5))
        val started = System.currentTimeMillis()
        graph.scheduler.requestNow()
        val record = PlaybackFixtures.waitFor(300_000, "the release app to transcribe the chunk") {
            runBlocking { graph.store.read { chunk(chunkId) } }?.takeIf { it.state == ChunkState.TRANSCRIBED }
        }
        val elapsedMs = System.currentTimeMillis() - started
        val text = SpeechFixtures.transcriptOf(graph.store, chunkId).joinToString("")
        val hits = runBlocking { graph.store.read { search("ヤンバルクイナ") } }.filter { it.segment.chunkId == chunkId }
        Evidence.log(
            "RELEASE_ASR_CHECK debuggable=$debuggable engine=${graph.transcriber.engineId} audio_ms=${manifest.durationMs} " +
                "elapsed_ms=$elapsedMs state=${record.state} search_hits=${hits.size} text=$text",
        )

        SpeechFixtures.KEYWORDS.values.forEach { assertTrue(it in text, "missing '$it' in: $text") }
        assertEquals(1, hits.size)
    }
}
