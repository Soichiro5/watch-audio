import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("watchaudio.android.release")
}

android {
    namespace = "app.watchaudio.wear"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Same applicationId as androidApp (required by the Wearable Data Layer).
        applicationId = "app.watchaudio"
        minSdk = libs.versions.wear.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(projects.shared.coreProtocol)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.complications.data.source)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

/**
 * Guards #57: the audio file input (package `app.watchaudio.wear.debug`, `src/debug`) must be
 * in debug builds and never in release builds. Scans the APK's dex files for the package and
 * the merged manifest for its activity; runs as part of `assemble<Variant>`. The debug variant
 * expects them present, which also proves that the scan finds them. For minified variants (release,
 * #78) it also scans the R8 mapping, which lists original class names even when R8 renamed them.
 */
abstract class VerifyDebugInputTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val r8Mapping: RegularFileProperty

    @get:Input
    abstract val expectPresent: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "no APK in ${apkDirectory.get().asFile}" }
        val dexMarker = "Lapp/watchaudio/wear/debug/".toByteArray()
        val findings = apks.map { apk ->
            ZipFile(apk).use { zip ->
                val dexHits = zip.entries().toList()
                    .filter { Regex("classes\\d*\\.dex").matches(it.name) }
                    .filter { entry -> zip.getInputStream(entry).use { it.readBytes() }.contains(dexMarker) }
                    .map { it.name }
                "${apk.name}: debug input classes in ${dexHits.ifEmpty { listOf("none") }}" to dexHits.isNotEmpty()
            }
        }
        val inManifest = mergedManifest.get().asFile.readText().contains("app.watchaudio.wear.debug.")
        val inMapping = r8Mapping.orNull?.asFile?.let { mapping ->
            mapping.useLines { lines -> lines.any { it.startsWith("app.watchaudio.wear.debug.") } }
        }
        val lines = findings.map { it.first } + "merged manifest declares debug input: $inManifest" +
            listOfNotNull(inMapping?.let { "R8 mapping lists debug input classes: $it" })
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        val present = findings.all { it.second } && inManifest && inMapping != false
        val absent = findings.none { it.second } && !inManifest && inMapping != true
        if (expectPresent.get()) {
            check(present) { "debug audio file input missing from the debug build:\n${lines.joinToString("\n")}" }
        } else {
            check(absent) { "debug audio file input leaked into a release build:\n${lines.joinToString("\n")}" }
        }
    }

    private fun ByteArray.contains(marker: ByteArray): Boolean {
        outer@ for (i in 0..size - marker.size) {
            for (j in marker.indices) if (this[i + j] != marker[j]) continue@outer
            return true
        }
        return false
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyDebugInputTask>("verify${name}DebugInput") {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            if (variant.isMinifyEnabled) r8Mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
            expectPresent.set(variant.buildType == "debug")
            report.set(layout.buildDirectory.file("reports/debug-input/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(verify) }
    }
}
