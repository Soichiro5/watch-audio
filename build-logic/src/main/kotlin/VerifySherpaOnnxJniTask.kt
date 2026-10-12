import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Guards #173 on the APK when [SherpaOnnxJniPlugin] packs the JNI library without text-to-speech.
 * For every `lib/<abi>/libsherpa-onnx-jni.so` in the APK (there must be at least one):
 * - it is byte for byte `<jniDirectory>/<abi>/libsherpa-onnx-jni.so` (not the AAR's);
 * - it contains none of [MARKERS] (eSpeak NG's data file names and the JNI entry points of the Kotlin
 *   TTS / diarization classes, all present in the JitPack AAR's library; the same markers as
 *   `tools/sherpa-onnx-android/check-no-tts.sh`);
 * - the APK's `lib/<abi>/libonnxruntime.so` (the AAR's) is the one it was linked against
 *   ([onnxruntimeSha256ByAbi], from the directory's `sherpa-onnx-jni.properties`).
 * Registered per variant and run as part of `assemble<Variant>`; writes a report.
 */
abstract class VerifySherpaOnnxJniTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniDirectory: DirectoryProperty

    @get:Input
    abstract val onnxruntimeSha256ByAbi: MapProperty<String, String>

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
                fun bytes(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                val abis = zip.entries().toList().mapNotNull { LIB_PATH.matchEntire(it.name)?.groupValues?.get(1) }
                if (abis.isEmpty()) problems += "${apk.name}: no lib/<abi>/${SherpaOnnxJniPlugin.LIBRARY}"
                for (abi in abis) {
                    val path = "lib/$abi/${SherpaOnnxJniPlugin.LIBRARY}"
                    val library = bytes(path)!!
                    val sha256 = sha256(library)
                    val expected = jniDirectory.get().asFile.resolve("$abi/${SherpaOnnxJniPlugin.LIBRARY}")
                    val found = MARKERS.filter { library.contains(it.toByteArray()) }
                    val onnxruntime = bytes("lib/$abi/libonnxruntime.so")?.let(::sha256)
                    val linkedOnnxruntime = onnxruntimeSha256ByAbi.get()[abi]
                    val result = buildList {
                        if (!expected.isFile) add("$expected does not exist")
                        else if (sha256(expected.readBytes()) != sha256) add("is not $expected (the AAR's?)")
                        if (found.isNotEmpty()) add("contains $found")
                        if (onnxruntime == null) add("no lib/$abi/libonnxruntime.so")
                        else if (onnxruntime != linkedOnnxruntime) {
                            add("lib/$abi/libonnxruntime.so is $onnxruntime, but it was linked against $linkedOnnxruntime")
                        }
                    }
                    lines += "${apk.name} $path (${library.size} bytes, sha256 $sha256): " +
                        result.ifEmpty { listOf("built without TTS, no eSpeak NG / TTS / diarization markers, linked against the packed libonnxruntime.so $onnxruntime") }
                            .joinToString("; ")
                    result.forEach { problems += "${apk.name}: $path $it" }
                }
            }
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "sherpa-onnx JNI library without TTS (eSpeak NG, GPL-3.0) not packed as expected (#173):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
