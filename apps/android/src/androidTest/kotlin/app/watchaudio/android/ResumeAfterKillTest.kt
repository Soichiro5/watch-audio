package app.watchaudio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.watchaudio.core.db.ChunkState
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #20: transcription survives the app being force-stopped in the middle of a run. Driven by
 * `.github/scripts/phone-scenarios.sh` in three steps, since the instrumentation runs inside the
 * process that gets killed:
 *
 * 1. [seed] (`-e scenario resume-seed`): store [CHUNKS] chunks of real speech, without starting a run.
 * 2. The script opens the app (its start-up asks for a run), waits until the diagnostics log has the
 *    first `transcription_finished` of the seeded session, and runs `adb shell am force-stop app.watchaudio`.
 * 3. [verify] (`-e scenario resume-verify`): the next process start recovers the interrupted chunk and
 *    finishes the rest; every chunk ends up transcribed exactly once (no missing chunk, no duplicate
 *    segment), and the chunk that was cut off was attempted twice.
 */
@RunWith(AndroidJUnit4::class)
class ResumeAfterKillTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = PlaybackFixtures.graph
    private val sessionFile get() = File(context.filesDir, "resume-check-session.txt")

    @Test
    fun seed() {
        Evidence.assumeScenario("resume-seed")
        SpeechFixtures.ensureModels()
        // No run may pick the chunks up in this process: the run under test starts with the app.
        SpeechFixtures.awaitTranscriptionIdle()
        val session = "resume-check-" + System.currentTimeMillis()
        val base = System.currentTimeMillis() - 4 * 86_400_000L
        repeat(CHUNKS) { i ->
            // Two reference recordings per chunk (~10-25 s of speech); chunks 2 min apart, so no boundary joins.
            val wavs = intArrayOf(i % 5 + 1, (i + 1) % 5 + 1)
            SpeechFixtures.ingest(graph.ingestor, "$session-$i", session, base + i * 120_000L, SpeechFixtures.speech(*wavs))
        }
        sessionFile.writeText(session)
        val states = runBlocking { graph.store.read { (0 until CHUNKS).map { chunk("$session-$it")!!.state } } }
        Evidence.log("RESUME_CHECK phase=seeded session=$session chunks=$CHUNKS states=${states.groupingBy { it }.eachCount()}")
        assertTrue(states.all { it == ChunkState.RECEIVED })
    }

    @Test
    fun verify() {
        Evidence.assumeScenario("resume-verify")
        val session = sessionFile.readText().trim()
        val transcribedBeforeKill = Evidence.argument("transcribedBeforeKill")?.toIntOrNull() ?: -1
        val interrupted = Evidence.argument("interruptedChunk")?.takeIf { it.isNotBlank() }
        val ids = (0 until CHUNKS).map { "$session-$it" }
        runBlocking { graph.startup.join() }

        // This process start re-queued the interrupted chunk and asked for a run; wait for it to finish.
        PlaybackFixtures.waitFor(600_000, "all chunks to be transcribed after the restart") {
            runBlocking { graph.store.read { ids.all { chunk(it)?.state == ChunkState.TRANSCRIBED } } }.takeIf { it }
        }
        val chunks = runBlocking { graph.store.read { ids.map { chunk(it)!! } } }
        var duplicates = 0
        var empty = 0
        for ((i, chunk) in chunks.withIndex()) {
            val segments = runBlocking {
                graph.store.read { segmentsBetween(chunk.startedAt, chunk.endedAt + 60_000).filter { it.chunkId == chunk.id } }
            }
            val dup = segments.size - segments.distinctBy { it.startAt }.size
            duplicates += dup
            if (segments.isEmpty()) empty++
            val text = segments.joinToString("") { it.text }
            Evidence.log("RESUME_CHECK phase=chunk index=$i state=${chunk.state} attempts=${chunk.attempts} segments=${segments.size} duplicates=$dup text=$text")
            listOf(i % 5 + 1, (i + 1) % 5 + 1).mapNotNull { SpeechFixtures.KEYWORDS[it] }.forEach { keyword ->
                assertTrue(keyword in text, "chunk $i: missing '$keyword' in: $text")
            }
        }
        val interruptedAttempts = interrupted?.let { id -> chunks.firstOrNull { it.id == id }?.attempts }
        Evidence.log(
            "RESUME_CHECK phase=verified chunks=${chunks.size} transcribed=${chunks.count { it.state == ChunkState.TRANSCRIBED }} " +
                "missing=$empty duplicates=$duplicates transcribed_before_kill=$transcribedBeforeKill " +
                "interrupted_chunk=${interrupted ?: "none"} interrupted_attempts=${interruptedAttempts ?: "-"}",
        )
        assertEquals(0, empty, "chunks without transcript")
        assertEquals(0, duplicates, "duplicated segments")
        // The kill really happened in the middle of the run.
        assertTrue(transcribedBeforeKill in 1 until CHUNKS, "transcribed before the kill: $transcribedBeforeKill of $CHUNKS")
        // A chunk cut off mid-transcription is re-queued and transcribed once more, not lost.
        // At least the attempt that was killed plus the one that finished. Starting this
        // instrumentation force-stops the app again, so a job that JobScheduler restarted right
        // after the kill may itself be interrupted and add one more attempt.
        if (interrupted != null) {
            assertTrue((interruptedAttempts ?: 0) >= 2, "attempts of the interrupted chunk: $interruptedAttempts")
        }
        // Every other chunk was transcribed in a single attempt.
        assertTrue(chunks.filter { it.id != interrupted }.all { it.attempts == 1 }, "attempts: ${chunks.map { it.attempts }}")
    }

    private companion object {
        const val CHUNKS = 8
    }
}
