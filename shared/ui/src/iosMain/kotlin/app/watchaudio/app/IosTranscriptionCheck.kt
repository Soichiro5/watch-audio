package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.Ingestor
import app.watchaudio.core.pipeline.TranscriptionPipeline
import app.watchaudio.core.platform.sha256Of
import app.watchaudio.core.protocol.AckStatus
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory

/** A segment stored by [IosTranscriptionCheck]; times in ms from the start of the chunk. */
class IosStoredSegment(val startMs: Long, val endMs: Long, val text: String, val lang: String?, val engine: String)

/**
 * The iOS transcription path end to end, for the simulator checks in the app process
 * (`apps/ios/Tests/SpeechTests`, #170): stores an audio file as one watch chunk in a temporary
 * database (through the production [Ingestor]), transcribes it with the shared
 * [TranscriptionPipeline] over [IosTranscriberAdapter] (Japanese, plus the English routing when
 * asked) and reports the stored segments. The app's own database is not touched; the temporary one
 * is deleted afterwards.
 */
object IosTranscriptionCheck {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Calls back (on a background thread) with the segments stored for [audioPath] (an AAC chunk
     * of [durationMs]) transcribed by [transcriber] with 「英語も文字起こしする」 [english], or
     * (null, error message).
     */
    fun transcribe(
        transcriber: IosSpeechTranscriber,
        audioPath: String,
        durationMs: Long,
        english: Boolean,
        callback: (List<IosStoredSegment>?, String?) -> Unit,
    ) {
        scope.launch {
            val dir = Path(NSTemporaryDirectory(), "transcription-check-${Random.nextLong().toULong().toString(16)}")
            try {
                callback(run(dir, transcriber, Path(audioPath), durationMs, english), null)
            } catch (e: Exception) {
                callback(null, e.message ?: e.toString())
            } finally {
                deleteRecursively(dir)
            }
        }
    }

    private suspend fun run(dir: Path, transcriber: IosSpeechTranscriber, audio: Path, durationMs: Long, english: Boolean): List<IosStoredSegment> {
        val audioRoot = Path(dir, "audio")
        SystemFileSystem.createDirectories(audioRoot)
        val chunkId = "check-" + Random.nextLong().toULong().toString(16)
        val file = Path(dir, "$chunkId.m4a")
        SystemFileSystem.sink(file).use { sink -> SystemFileSystem.source(audio).buffered().use { it.transferTo(sink) } }
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: error("cannot read $audio")
        val startedAt = Clock.System.now().toEpochMilliseconds() - 24 * 3_600_000L
        val manifest = ChunkManifest(
            chunkId = chunkId,
            sessionId = "transcription-check",
            deviceId = "simulator",
            source = SourcePlatform.WATCHOS,
            startedAtEpochMs = startedAt,
            durationMs = durationMs,
            timeZone = "Asia/Tokyo",
            audio = AudioFile("$chunkId.m4a", AudioCodec.AAC, 16_000, 1, 24_000, size, sha256Of(file)),
            endReason = ChunkEndReason.ROTATION,
        )
        val store = LocalStore.open(Path(dir, LocalStore.FILE_NAME).toString())
        try {
            val ack = Ingestor(store, audioRoot).ingest(manifest, file)
            check(ack.status == AckStatus.STORED) { "the chunk was not stored: ${ack.status}" }
            val pipeline = TranscriptionPipeline(store, audioRoot, IosTranscriberAdapter(transcriber, english = { english }))
            val result = pipeline.processPending()
            if (result.transcribed != 1) {
                error("not transcribed: ${store.read { chunk(chunkId) }?.lastError ?: result.toString()}")
            }
            return store.read { segmentsBetween(startedAt, startedAt + durationMs + 60_000) }
                .map { IosStoredSegment(it.startAt - startedAt, it.endAt - startedAt, it.text, it.lang, it.engine) }
        } finally {
            store.close()
        }
    }

    private fun deleteRecursively(path: Path) {
        runCatching {
            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::deleteRecursively)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}
