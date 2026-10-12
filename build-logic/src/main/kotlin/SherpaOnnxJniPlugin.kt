import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Packs sherpa-onnx's `libsherpa-onnx-jni.so` built without text-to-speech (eSpeak NG, GPL-3.0) and
 * speaker diarization instead of the JitPack AAR's (#173), when a directory with it is given by
 * `-P`[DIR_PROPERTY]`=<dir>` or the environment variable [DIR_ENV]. CI sets the variable in every job
 * that builds a distributable or release-test APK (`.github/actions/sherpa-onnx-jni`, which builds the
 * directory with `tools/sherpa-onnx-android/build-jni.sh`). Without it (local development builds,
 * which are never distributed) the AAR's library is packed unchanged and this plugin does nothing.
 *
 * The directory holds `<abi>/libsherpa-onnx-jni.so` and `sherpa-onnx-jni.properties` (written by
 * build-jni.sh). The AAR still provides the Kotlin API and `libonnxruntime.so`. Per variant:
 * - `prepare<Variant>SherpaOnnxJni` ([PrepareSherpaOnnxJniTask]) adds the libraries as a generated
 *   jniLibs directory. Native libraries of the app's own sources take precedence over a dependency's
 *   with the same path, so the AAR's copy is not packaged.
 * - `verify<Variant>SherpaOnnxJni` ([VerifySherpaOnnxJniTask]) runs with `assemble<Variant>` and fails
 *   unless every packed `libsherpa-onnx-jni.so` is the given one, free of eSpeak NG / TTS code, and
 *   next to the `libonnxruntime.so` it was linked against.
 *
 * The build fails when the directory was built from another sherpa-onnx version than the AAR in the
 * version catalog: the JNI library must match the Kotlin API.
 */
class SherpaOnnxJniPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val setting = providers.gradleProperty(DIR_PROPERTY).orElse(providers.environmentVariable(DIR_ENV))
            .orNull?.takeIf { it.isNotBlank() } ?: return
        val dir = rootProject.projectDir.resolve(setting)
        val infoFile = File(dir, SherpaOnnxJniInfo.FILE_NAME)
        val infoText = providers.fileContents(objects.fileProperty().fileValue(infoFile)).asText.orNull
            ?: throw GradleException(
                "$infoFile does not exist: -P$DIR_PROPERTY / $DIR_ENV must name the output of " +
                    "tools/sherpa-onnx-android/build-jni.sh (docs/dev/04-dev-environment.md §7.3)",
            )
        val info = SherpaOnnxJniInfo.parse(infoText, infoFile.path)
        val aarVersion = extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("sherpa-onnx").get().requiredVersion
        if (aarVersion != info.version) {
            throw GradleException(
                "$dir holds the sherpa-onnx JNI library of ${info.version}, but gradle/libs.versions.toml uses " +
                    "$aarVersion: build it for $aarVersion (docs/dev/04-dev-environment.md §7.3)",
            )
        }

        pluginManager.withPlugin("com.android.application") {
            extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val prepare = tasks.register<PrepareSherpaOnnxJniTask>("prepare${name}SherpaOnnxJni") {
                    jniDirectory.set(dir)
                    abis.set(info.abis)
                }
                variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare, PrepareSherpaOnnxJniTask::outputDir)
                val verify = tasks.register<VerifySherpaOnnxJniTask>("verify${name}SherpaOnnxJni") {
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    jniDirectory.set(dir)
                    onnxruntimeSha256ByAbi.set(info.onnxruntimeSha256ByAbi)
                    report.set(layout.buildDirectory.file("reports/sherpa-onnx-jni/${variant.name}.txt"))
                }
                tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(verify) }
            }
        }
    }

    companion object {
        const val DIR_PROPERTY = "watchaudio.sherpaOnnxJniDir"
        const val DIR_ENV = "WATCHAUDIO_SHERPA_ONNX_JNI_DIR"
        const val LIBRARY = "libsherpa-onnx-jni.so"
    }
}

/** `sherpa-onnx-jni.properties` of a directory built by `tools/sherpa-onnx-android/build-jni.sh`. */
data class SherpaOnnxJniInfo(
    /** The upstream tag it was built from (= the AAR version it must be used with). */
    val version: String,
    val abis: List<String>,
    /** ABI -> SHA-256 of the `libonnxruntime.so` it was linked against (the AAR's). */
    val onnxruntimeSha256ByAbi: Map<String, String>,
) {
    companion object {
        const val FILE_NAME = "sherpa-onnx-jni.properties"

        fun parse(text: String, source: String): SherpaOnnxJniInfo {
            val properties = Properties().apply { load(text.reader()) }
            fun value(key: String): String = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("$source: $key is missing")
            val abis = value("abis").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return SherpaOnnxJniInfo(
                version = value("version"),
                abis = abis,
                onnxruntimeSha256ByAbi = abis.associateWith { value("onnxruntime.sha256.$it").lowercase() },
            )
        }
    }
}
