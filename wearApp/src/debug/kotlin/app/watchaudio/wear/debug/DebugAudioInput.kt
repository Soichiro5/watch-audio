package app.watchaudio.wear.debug

import android.content.Context
import app.watchaudio.wear.AppGraph
import app.watchaudio.wear.recording.RecorderInput
import app.watchaudio.wear.recording.RecordingService
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug builds only: switches the recorder between the microphone and an audio file
 * ([FileRecorderInput]). See docs/impl/wearos.md 「音声ファイル入力（開発用）」.
 */
object DebugAudioInput {
    const val DIR_NAME = "debug-input"
    const val MIN_SPEED = 1.0
    const val MAX_SPEED = 120.0

    private val _playback = MutableStateFlow<FilePlayback?>(null)

    /** The file the recorder plays instead of the microphone, or null for the microphone. */
    val playback: StateFlow<FilePlayback?> = _playback.asStateFlow()

    /** Where input files are looked up: `files/debug-input` (run-as) and the adb-pushable external dir. */
    fun inputDirs(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, DIR_NAME),
        context.getExternalFilesDir(DIR_NAME),
    ).onEach { it.mkdirs() }

    fun availableFiles(context: Context): List<File> =
        inputDirs(context).flatMap { dir -> dir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name } }

    /** An absolute path, or a file name in one of the [inputDirs]. */
    fun resolve(context: Context, name: String): File? {
        val direct = File(name)
        if (direct.isAbsolute) return direct.takeIf { it.isFile }
        return inputDirs(context).map { File(it, name) }.firstOrNull { it.isFile }
    }

    /** Makes the next recorder run play [file]. The caller (re)starts the recording service. */
    fun use(context: Context, file: File, speed: Double, loop: Boolean) {
        val app = context.applicationContext
        val graph = AppGraph.of(app)
        var created: FilePlayback? = null
        val playback = FilePlayback(file, speed.coerceIn(MIN_SPEED, MAX_SPEED), loop) { created?.let { finished(app, it) } }
        created = playback
        graph.recorderInputFactory = RecorderInput.Factory { _, listener -> FileRecorderInput(listener, playback) }
        _playback.value = playback
        graph.log("debug_input", "file=${file.name} speed=${playback.speed} loop=$loop")
    }

    /** Back to the microphone for the next recorder run. */
    fun useMicrophone(context: Context) {
        AppGraph.of(context).recorderInputFactory = RecorderInput.Microphone
        _playback.value = null
    }

    private fun finished(context: Context, playback: FilePlayback) {
        if (_playback.value !== playback) return // replaced in the meantime
        AppGraph.of(context).log("debug_input_finished", playback.file.name)
        useMicrophone(context)
        RecordingService.stop(context)
    }
}
