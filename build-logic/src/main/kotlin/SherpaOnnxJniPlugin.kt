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
 * Replaces the JitPack AAR's `libsherpa-onnx-jni.so` with the build without text-to-speech
 * (eSpeak NG, GPL-3.0) and speaker diarization pinned in `gradle/sherpa-onnx-jni.properties` (#173).
 * The AAR still provides the Kotlin API and `libonnxruntime.so`.
 *
 * Per variant:
 * - `fetch<Variant>SherpaOnnxJni` ([FetchSherpaOnnxJniTask]) provides the pinned libraries, checked
 *   by SHA-256, as a generated jniLibs directory. Native libraries of the app's own sources take
 *   precedence over a dependency's with the same path, so the AAR's copy is not packaged.
 * - `verify<Variant>SherpaOnnxJni` ([VerifySherpaOnnxJniTask]) runs with `assemble<Variant>` and
 *   fails if the APK's library still contains eSpeak NG / TTS code or lacks a pinned ABI.
 *
 * The build fails when the AAR version in the version catalog differs from the pinned `version`:
 * the JNI library must be built from the same sherpa-onnx as the Kotlin API.
 * `-P`[LOCAL_DIR_PROPERTY]`=<dir>` takes `libsherpa-onnx-jni-<abi>.so` from a local directory (e.g.
 * the artifacts of `sherpa-onnx-android.yml`) instead of the release; the SHA-256 is checked either way.
 */
class SherpaOnnxJniPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val pinsFile = rootProject.layout.projectDirectory.file(PINS_PATH)
        val pins = SherpaOnnxJniPins.parse(providers.fileContents(pinsFile).asText.get(), PINS_PATH)
        val aarVersion = extensions.getByType<VersionCatalogsExtension>().named("libs")
            .findVersion("sherpa-onnx").get().requiredVersion
        if (aarVersion != pins.version) {
            throw GradleException(
                "sherpa-onnx is $aarVersion in gradle/libs.versions.toml but $PINS_PATH pins the JNI library of " +
                    "${pins.version}: rebuild it for $aarVersion (docs/dev/04-dev-environment.md §7.3)",
            )
        }
        val localDir = providers.gradleProperty(LOCAL_DIR_PROPERTY).map { rootProject.file(it).absolutePath }
        val cacheDir = File(gradle.gradleUserHomeDir, "caches/watchaudio/sherpa-onnx-jni")

        pluginManager.withPlugin("com.android.application") {
            extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val fetch = tasks.register<FetchSherpaOnnxJniTask>("fetch${name}SherpaOnnxJni") {
                    sha256ByAbi.set(pins.sha256ByAbi)
                    baseUrl.set(pins.baseUrl)
                    localDirectory.set(localDir)
                    cacheDirectory.set(cacheDir.absolutePath)
                }
                variant.sources.jniLibs?.addGeneratedSourceDirectory(fetch, FetchSherpaOnnxJniTask::outputDir)
                val verify = tasks.register<VerifySherpaOnnxJniTask>("verify${name}SherpaOnnxJni") {
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    abis.set(pins.sha256ByAbi.keys.toList())
                    report.set(layout.buildDirectory.file("reports/sherpa-onnx-jni/${variant.name}.txt"))
                }
                tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(verify) }
            }
        }
    }

    companion object {
        const val PINS_PATH = "gradle/sherpa-onnx-jni.properties"
        const val LOCAL_DIR_PROPERTY = "watchaudio.sherpaOnnxJniDir"
    }
}

/** The pinned JNI libraries of `gradle/sherpa-onnx-jni.properties` (its comments describe the keys). */
data class SherpaOnnxJniPins(
    val version: String,
    /** `https://github.com/<repository>/releases/download/<release>`, without a trailing slash. */
    val baseUrl: String,
    /** ABI -> SHA-256 (lowercase hex) of `libsherpa-onnx-jni-<abi>.so`, in the order of `abis`. */
    val sha256ByAbi: Map<String, String>,
) {
    companion object {
        fun parse(text: String, source: String): SherpaOnnxJniPins {
            val properties = Properties().apply { load(text.reader()) }
            fun value(key: String): String = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("$source: $key is missing")
            val abis = value("abis").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val hashes = abis.associateWith { abi ->
                value("jni.sha256.$abi").lowercase().also {
                    if (!Regex("[0-9a-f]{64}").matches(it)) throw GradleException("$source: jni.sha256.$abi is not a SHA-256")
                }
            }
            return SherpaOnnxJniPins(
                version = value("version"),
                baseUrl = "https://github.com/${value("repository")}/releases/download/${value("release")}",
                sha256ByAbi = hashes,
            )
        }
    }
}
