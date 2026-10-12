import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Guards #173: every `lib/<abi>/libsherpa-onnx-jni.so` in the APK is free of text-to-speech and
 * speaker diarization code ([MARKERS]: eSpeak NG's data file names and the JNI entry points of the
 * Kotlin TTS / diarization classes, all present in the JitPack AAR's library), and each of [abis]
 * has one. Registered per variant by [SherpaOnnxJniPlugin] and run as part of `assemble<Variant>`;
 * the same markers as `tools/sherpa-onnx-android/check-no-tts.sh` (used by CI on the release APK).
 */
abstract class VerifySherpaOnnxJniTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        if (apks.isEmpty()) throw GradleException("no APK in ${apkDirectory.get().asFile}")
        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val libs = zip.entries().toList().mapNotNull { entry ->
                    LIB_PATH.matchEntire(entry.name)?.let { it.groupValues[1] to entry }
                }.toMap()
                abis.get().filter { it !in libs }.forEach { problems += "${apk.name}: no lib/$it/libsherpa-onnx-jni.so" }
                for ((abi, entry) in libs) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val found = MARKERS.filter { bytes.contains(it.toByteArray()) }
                    lines += "${apk.name} lib/$abi/libsherpa-onnx-jni.so (${bytes.size} bytes): " +
                        if (found.isEmpty()) "no TTS / diarization markers" else "contains $found"
                    if (found.isNotEmpty()) problems += "${apk.name}: lib/$abi/libsherpa-onnx-jni.so contains $found"
                }
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "sherpa-onnx JNI library with TTS (eSpeak NG, GPL-3.0) or missing (#173):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    companion object {
        private val LIB_PATH = Regex("lib/([^/]+)/libsherpa-onnx-jni\\.so")
        val MARKERS = listOf(
            "espeak-ng-data", "phontab", "phonindex",
            "Java_com_k2fsa_sherpa_onnx_OfflineTts", "Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization",
        )
    }
}
