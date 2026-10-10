import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Tests that open a real database through the bundled SQLite driver live in `src/sqliteTest` and
 * run on every target that can load its native library (#14):
 *
 * - `jvmTest` (Linux/macOS host, `./gradlew jvmTest`)
 * - `iosSimulatorArm64Test` (CI `ios.yml`)
 * - `androidDeviceTest` on an emulator (`connectedAndroidDeviceTest`, CI `emulator.yml`)
 *
 * They are deliberately not part of `commonTest`: Android host tests run on the JVM with the
 * Android variant of `sqlite-bundled`, whose native library is built for Android ABIs only and
 * cannot be loaded on the host.
 */
class SqliteTestsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("watchaudio.kmp.library")
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun library(alias: String) = libs.findLibrary(alias).get()

        extensions.configure<KotlinMultiplatformExtension> {
            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                    instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
            }

            // Default template plus a "sqlite" group: sqliteTest <- jvmTest, iosTest, androidDeviceTest.
            // (Explicit dependsOn() calls would switch the default template off.)
            @OptIn(ExperimentalKotlinGradlePluginApi::class)
            applyDefaultHierarchyTemplate {
                common {
                    group("sqlite") {
                        withJvm()
                        group("ios")
                        withCompilations {
                            it.platformType == KotlinPlatformType.androidJvm && it.name == DEVICE_TEST_COMPILATION
                        }
                    }
                }
            }

            sourceSets.getByName("androidDeviceTest").dependencies {
                implementation(library("kotlin-test-junit"))
                implementation(library("androidx-test-runner"))
            }
        }
    }

    private companion object {
        const val DEVICE_TEST_COMPILATION = "deviceTest"
    }
}
