package app.watchaudio.app

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.pipeline.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path

/**
 * [AppServices] over a real [LocalStore] with no platform behind it: playback, transcription
 * and model downloads are only recorded, while [storage] really deletes (audio under [audioRoot]).
 * For tests, previews and as a stand-in host until a platform implementation is wired in.
 */
class FakeAppServices(
    override val store: LocalStore,
    override val audioRoot: String = "",
    initialStatus: ProcessingStatus = ProcessingStatus(),
    override val storage: StorageManager = StorageManager(store, Path(audioRoot)),
) : AppServices {
    val mutableStatus = MutableStateFlow(initialStatus)
    override val status: StateFlow<ProcessingStatus> = mutableStatus.asStateFlow()

    val played = mutableListOf<Pair<String, Long>>()
    var stopRequests = 0
        private set
    var transcriptionRequests = 0
        private set
    val downloadRequests = mutableListOf<Boolean>()

    override fun requestTranscription() {
        transcriptionRequests++
    }

    override fun play(audioPath: String, offsetMs: Long) {
        played += audioPath to offsetMs
    }

    override fun stopPlayback() {
        stopRequests++
    }

    override fun downloadModels(allowMetered: Boolean) {
        downloadRequests += allowMetered
    }

    companion object {
        /** Opens (or creates) the regular database in [dataDir]. */
        fun open(dataDir: String): FakeAppServices =
            FakeAppServices(LocalStore.open("$dataDir/${LocalStore.FILE_NAME}"), audioRoot = dataDir)
    }
}
