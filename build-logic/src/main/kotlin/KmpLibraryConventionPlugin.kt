import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Shared setup for every `shared/` module: Android (phone + Wear OS), iOS and JVM targets.
 * The JVM target lets the whole ingest/transcription pipeline run on Linux (CI and the
 * cloud dev environment) without devices; see #44.
 *
 * The Android namespace is derived from the module path, e.g. `:shared:core-protocol`
 * becomes `app.watchaudio.core.protocol` and `:composeApp` becomes `app.watchaudio.composeapp`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(21)

            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "app.watchaudio." + path.removePrefix(":").removePrefix("shared:")
                    .replace(':', '.').replace('-', '.').lowercase()
                compileSdk = version("android-compileSdk").toInt()
                minSdk = version("android-minSdk").toInt()
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                withHostTest {}
            }

            iosArm64()
            iosSimulatorArm64()
            jvm()

            sourceSets.getByName("commonTest").dependencies {
                implementation(libs.findLibrary("kotlin-test").get())
            }
        }
    }
}
