import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * Shared setup of the Android apps, phone (`apps/android`) and Wear OS (`apps/wear`), #138:
 *
 * - the Android application and Compose compiler plugins, with Compose enabled;
 * - applicationId [APPLICATION_ID], identical in both apps because the Wearable Data Layer only
 *   pairs apps with the same package name and signing key;
 * - compileSdk / targetSdk from the version catalog and Java 17 bytecode;
 * - the app version from `gradle.properties` ([VERSION_NAME_PROPERTY], [VERSION_CODE_PROPERTY]),
 *   the single source of the version, which `tools/check-version` keeps in sync with
 *   `apps/ios/project.yml`;
 * - the release build: R8 and signing ([configureAndroidRelease], #78).
 *
 * Each app still sets its own namespace, minSdk (the watch needs a newer one) and dependencies.
 */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun sdk(alias: String) = libs.findVersion(alias).get().requiredVersion.toInt()
        fun property(name: String) = providers.gradleProperty(name).orNull
            ?: throw GradleException("$name is missing from gradle.properties (the app version, #138)")
        val appVersionName = property(VERSION_NAME_PROPERTY)
        val appVersionCode = property(VERSION_CODE_PROPERTY).let {
            it.toIntOrNull() ?: throw GradleException("$VERSION_CODE_PROPERTY=$it is not an integer")
        }

        extensions.configure<ApplicationExtension> {
            compileSdk = sdk("android-compileSdk")
            defaultConfig {
                applicationId = APPLICATION_ID
                targetSdk = sdk("android-targetSdk")
                versionName = appVersionName
                versionCode = appVersionCode
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures {
                compose = true
            }
        }

        configureAndroidRelease()
    }

    companion object {
        /** Shared by phone and Wear OS (Wearable Data Layer pairing). */
        const val APPLICATION_ID = "app.watchaudio"
        const val VERSION_NAME_PROPERTY = "watchaudio.versionName"
        const val VERSION_CODE_PROPERTY = "watchaudio.versionCode"
    }
}
