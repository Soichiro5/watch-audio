package app.watchaudio.app

import app.watchaudio.core.embedding.EncoderRuntime
import app.watchaudio.core.embedding.ModelFetchException
import app.watchaudio.core.embedding.ModelFetcher
import app.watchaudio.core.embedding.ModelInstaller
import app.watchaudio.core.embedding.OnnxEmbedder
import app.watchaudio.core.embedding.RuriV3
import app.watchaudio.core.embedding.UnigramTokenizer
import app.watchaudio.core.search.SemanticIndex
import app.watchaudio.core.search.SemanticSearch
import app.watchaudio.core.search.SemanticStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The embedding model of semantic search on iOS (#178): the same files as Android under
 * `Application Support/WatchAudio/models/ruri-v3-30m` ([RuriV3.FILES]), the shared pure-Kotlin
 * tokenizer and pooling ([OnnxEmbedder]), and ONNX Runtime from Swift ([IosEmbeddingPlatform]).
 * Everything runs on the device; nothing is sent.
 *
 * One intra-op thread, as on Android: passages are short, and a spinning thread pool costs battery
 * in the background.
 */
object IosEmbedding {
    fun embedder(platform: IosEmbeddingPlatform, modelsRoot: String): OnnxEmbedder {
        val dir = Path(modelsRoot, RuriV3.DIRECTORY)
        return OnnxEmbedder(
            spec = RuriV3.SPEC,
            loadTokenizer = { UnigramTokenizer.fromJson(SystemFileSystem.source(Path(dir, RuriV3.TOKENIZER_FILE)).buffered().use { it.readString() }) },
            openRuntime = { IosEncoderRuntime.open(platform, Path(dir, RuriV3.MODEL_FILE).toString()) },
        )
    }
}

/** The model could not be loaded or run; [message] is ONNX Runtime's description. */
class IosEmbeddingException(message: String) : Exception(message)

/** [EncoderRuntime] over a Swift [IosEncoderSession]: int64 tensors in, `last_hidden_state` out. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosEncoderRuntime(private val session: IosEncoderSession) : EncoderRuntime {
    override fun lastHiddenState(inputIds: LongArray, attentionMask: LongArray, batch: Int, length: Int): FloatArray {
        val output = session.run(inputIds.toNSData(), attentionMask.toNSData(), batch, length)
        return output.hiddenState?.toFloatArray() ?: throw IosEmbeddingException(output.error ?: "the model returned nothing")
    }

    override fun close() = session.close()

    companion object {
        fun open(platform: IosEmbeddingPlatform, modelPath: String, threads: Int = 1): IosEncoderRuntime {
            val opened = platform.openEncoder(modelPath, threads)
            return IosEncoderRuntime(opened.session ?: throw IosEmbeddingException(opened.error ?: "cannot open the model"))
        }

        private fun LongArray.toNSData(): NSData {
            if (isEmpty()) return NSData()
            return usePinned { NSData.create(bytes = it.addressOf(0), length = (size * Long.SIZE_BYTES).toULong()) }
        }

        private fun NSData.toFloatArray(): FloatArray {
            val out = FloatArray((length / Float.SIZE_BYTES.toULong()).toInt())
            if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, (out.size * Float.SIZE_BYTES).toULong()) }
            return out
        }
    }
}

/** [ModelFetcher] over the Swift `URLSession` download ([IosEmbeddingPlatform.download], Wi-Fi only). */
internal class IosModelFetcher(private val platform: IosEmbeddingPlatform) : ModelFetcher {
    override suspend fun fetch(url: String, part: Path, offset: Long, onProgress: (Long) -> Unit, onWaiting: () -> Unit) =
        suspendCancellableCoroutine<Unit> { cont ->
            val handle = platform.download(url, part.toString(), offset, onProgress, onWaiting) { error ->
                // Resuming an already-cancelled continuation is a no-op.
                if (error == null) cont.resume(Unit) else cont.resumeWithException(ModelFetchException(error))
            }
            cont.invokeOnCancellation { handle.cancel() }
        }
}

/**
 * Semantic search as the iOS search screen sees it (#178), like Android's: the [index], plus the
 * model's download ([ModelInstaller], Wi-Fi only, ~44 MB), which starts the first time the screen
 * asks for the status, i.e. when the user opens the search tab. People who never search download
 * nothing.
 */
internal class IosSemanticSearch(private val index: SemanticIndex, private val model: ModelInstaller) : SemanticSearch by index {
    override suspend fun status(): SemanticStatus {
        model.requestDownload()
        return index.status()
    }
}
