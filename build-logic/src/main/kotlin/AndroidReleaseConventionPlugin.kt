import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Release build of the Android apps (phone and Wear OS, #78): R8 code and resource shrinking with
 * the module's `proguard-rules.pro`, and release signing when the key is provided.
 *
 * Both apps share the applicationId `app.watchaudio` and must be signed with the same key for the
 * Wearable Data Layer to pair them, so the signing setup lives here once.
 *
 * The key never lives in the repository. It is read from these environment variables (CI sets them
 * from GitHub Actions secrets, see docs/dev/04-dev-environment.md):
 *
 * - [STORE_FILE]: path to the keystore file
 * - [STORE_PASSWORD], [KEY_ALIAS], [KEY_PASSWORD]
 *
 * With none of them set, `assembleRelease` produces an unsigned APK (`*-release-unsigned.apk`).
 * Setting only some of them fails the build, so a missing secret cannot silently yield an unsigned
 * build where a signed one was expected. Values are never printed.
 */
class AndroidReleaseConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val env = SIGNING_VARIABLES.associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotEmpty) }
        val missing = env.filterValues { it == null }.keys
        if (missing.isNotEmpty() && missing.size < env.size) {
            throw GradleException("Release signing is partly configured; missing: ${missing.joinToString()}")
        }
        val signed = missing.isEmpty()

        extensions.configure<ApplicationExtension> {
            if (signed) {
                signingConfigs.create("release") {
                    storeFile = file(env.getValue(STORE_FILE)!!)
                    storePassword = env.getValue(STORE_PASSWORD)
                    keyAlias = env.getValue(KEY_ALIAS)
                    keyPassword = env.getValue(KEY_PASSWORD)
                }
            }
            buildTypes.getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                if (signed) signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    companion object {
        const val STORE_FILE = "WATCHAUDIO_RELEASE_STORE_FILE"
        const val STORE_PASSWORD = "WATCHAUDIO_RELEASE_STORE_PASSWORD"
        const val KEY_ALIAS = "WATCHAUDIO_RELEASE_KEY_ALIAS"
        const val KEY_PASSWORD = "WATCHAUDIO_RELEASE_KEY_PASSWORD"
        private val SIGNING_VARIABLES = listOf(STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
    }
}
