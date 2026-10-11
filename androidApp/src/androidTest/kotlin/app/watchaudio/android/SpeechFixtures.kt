package app.watchaudio.android

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.watchaudio.android.asr.ModelFiles
import app.watchaudio.android.asr.ModelManager
import app.watchaudio.android.work.TranscriptionScheduler
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.AsrSettings
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue

/**
 * Real Japanese speech for the emulator checks: the sherpa-onnx models and the reference
 * recordings of the ReazonSpeech model (`tools/harness/download-models.sh`), pushed by the
 * workflow to [DEVICE_DIR] (`adb push tools/harness/models /data/local/tmp/watchaudio-test/`).
 *
 * The models are copied into the app's own model directory (`files/models`, the same place
 * [ModelManager] downloads to) before a test needs them. Without a push the tests are skipped,
 * unless the run requires them (`-e requireModels true`, set by emulator.yml).
 */
object SpeechFixtures {
    const val DEVICE_DIR = "/data/local/tmp/watchaudio-test/models"
    private const val SAMPLE_RATE = 16_000

    /** Words the ASR must find in the reference recordings (also checked by the JVM harness). */
    val KEYWORDS = mapOf(1 to "気象庁", 3 to "ヤンバルクイナ", 5 to "傘")

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = PlaybackFixtures.graph

    private val required: Boolean get() = Evidence.argument("requireModels") == "true"

    /** Where [ModelWaitTest] parks the models while it checks the "no model" state. */
    val hiddenModels: File get() = File(context.filesDir, "models-hidden-by-test")

    /**
     * Makes sure the app has the speech models: restores a copy a crashed [ModelWaitTest] left
     * aside, or installs the pushed models, then stops any automatic model download (it would
     * replace the files under a running test). Skips the test when no models were pushed.
     */
    fun ensureModels(): ModelFiles {
        runBlocking { graph.startup.join() }
        val files = graph.models.files
        if (!files.isComplete() && hiddenModels.isDirectory) {
            files.root.deleteRecursively()
            check(hiddenModels.renameTo(files.root)) { "cannot restore the models" }
        }
        if (!files.isComplete() && Evidence.deviceFileSize("$DEVICE_DIR/${ModelFiles.VAD}") != null) {
            installPushedModels(files)
        }
        runBlocking { graph.models.refresh() }
        stopModelDownload()
        if (!files.isComplete()) {
            if (required) fail("speech models were not pushed to $DEVICE_DIR")
            assumeTrue("speech models not pushed to $DEVICE_DIR (see SpeechFixtures)", false)
        }
        return files
    }

    private fun installPushedModels(files: ModelFiles) {
        val staging = File(files.root, ".tmp-test-install").apply { deleteRecursively() }
        val started = SystemClock.uptimeMillis()
        var bytes = 0L
        for (file in files.all) {
            val relative = file.relativeTo(files.root).path
            bytes += Evidence.copyFromDevice("$DEVICE_DIR/$relative", File(staging, relative))
        }
        for (file in files.all) {
            val staged = File(staging, file.relativeTo(files.root).path)
            file.parentFile?.mkdirs()
            check(staged.renameTo(file)) { "cannot move ${staged.name} into place" }
        }
        staging.deleteRecursively()
        Evidence.log("MODEL_INSTALL bytes=$bytes elapsed_ms=${SystemClock.uptimeMillis() - started} from=$DEVICE_DIR")
    }

    private fun stopModelDownload() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(ModelManager.WORK_NAME).result.get()
        PlaybackFixtures.waitFor(60_000, "the model download to stop") {
            workManager.getWorkInfosForUniqueWork(ModelManager.WORK_NAME).get()
                .none { it.state == WorkInfo.State.RUNNING }
                .takeIf { it }
        }
    }

    /**
     * Waits until no transcription run is queued or running in the app (e.g. the one
     * [ensureModels] triggers when the model becomes ready), so a test can store chunks that stay
     * pending until it asks for a run itself.
     */
    fun awaitTranscriptionIdle() {
        val workManager = WorkManager.getInstance(context)
        SystemClock.sleep(3_000) // requestNow() enqueues asynchronously
        PlaybackFixtures.waitFor(300_000, "the app's transcription runs to finish") {
            workManager.getWorkInfosForUniqueWork(TranscriptionScheduler.NOW).get().all { it.state.isFinished }.takeIf { it }
        }
    }

    /** 16 kHz mono PCM of the reference recording `test_wavs/<n>.wav`. */
    fun wav(n: Int): ShortArray {
        val local = File(context.cacheDir, "test_wavs/$n.wav")
        if (!local.isFile) Evidence.copyFromDevice("$DEVICE_DIR/${AsrSettings.JA_MODEL_NAME}/test_wavs/$n.wav", local)
        return readWav(local.readBytes())
    }

    /** The recordings [wavs] one after another with [gapMs] of silence between and after them. */
    fun speech(vararg wavs: Int, gapMs: Int = 2_000): ShortArray {
        val gap = ShortArray(gapMs * SAMPLE_RATE / 1000)
        return wavs.map { wav(it) + gap }.reduce { a, b -> a + b }
    }

    /**
     * Stores [samples] as a watch chunk (AAC in .m4a) through [ingestor], like the phone does on
     * receipt, without asking for transcription.
     */
    fun ingest(
        ingestor: Ingestor,
        chunkId: String,
        sessionId: String,
        startedAt: Long,
        samples: ShortArray,
    ): ChunkManifest {
        val file = File(context.cacheDir, "$chunkId.m4a")
        TestAudio.writeM4a(file, samples, SAMPLE_RATE)
        val manifest = ChunkManifest(
            chunkId = chunkId,
            sessionId = sessionId,
            deviceId = "emulator-test",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = startedAt,
            durationMs = samples.size * 1000L / SAMPLE_RATE,
            timeZone = ZoneId.systemDefault().id,
            audio = AudioFile(file.name, AudioCodec.AAC, SAMPLE_RATE, 1, 32_000, file.length(), sha256Of(Path(file.path))),
            endReason = ChunkEndReason.ROTATION,
        )
        assertEquals(AckStatus.STORED, runBlocking { ingestor.ingest(manifest, Path(file.path)) }.status)
        return manifest
    }

    /** The chunk's transcript, oldest first. */
    fun transcriptOf(store: LocalStore, chunkId: String): List<String> = runBlocking {
        store.read {
            val chunk = chunk(chunkId) ?: return@read emptyList()
            segmentsBetween(chunk.startedAt, chunk.endedAt + 60_000).filter { it.chunkId == chunkId }.map { it.text }
        }
    }

    /** Minimal RIFF/WAVE reader for the 16-bit mono 16 kHz reference recordings. */
    private fun readWav(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            when (id) {
                "fmt " -> {
                    check(buffer.getShort(body).toInt() == 1) { "not PCM" }
                    check(buffer.getShort(body + 2).toInt() == 1) { "not mono" }
                    check(buffer.getInt(body + 4) == SAMPLE_RATE) { "not 16 kHz" }
                    check(buffer.getShort(body + 14).toInt() == 16) { "not 16-bit" }
                }
                "data" -> {
                    val count = minOf(size, bytes.size - body) / 2
                    return ShortArray(count) { buffer.getShort(body + it * 2) }
                }
            }
            offset = body + size + (size and 1)
        }
        error("no data chunk")
    }
}
