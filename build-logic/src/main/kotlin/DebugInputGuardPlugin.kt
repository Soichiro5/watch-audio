import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * Registers [VerifyDebugInputTask] (`verify<Variant>DebugInput`, #57) for every variant of an
 * Android app and makes `assemble<Variant>` depend on it. The guarded package is the app's
 * namespace plus `.debug` (`app.watchaudio.wear.debug` for Wear OS). Reports go to
 * `build/reports/debug-input/<variant>.txt`.
 */
class DebugInputGuardPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.withPlugin("com.android.application") {
            extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val verify = tasks.register<VerifyDebugInputTask>("verify${name}DebugInput") {
                    apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
                    mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    if (variant.isMinifyEnabled) {
                        r8Mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                    }
                    debugPackage.set(variant.namespace.map { "$it.debug" })
                    expectPresent.set(variant.buildType == "debug")
                    report.set(layout.buildDirectory.file("reports/debug-input/${variant.name}.txt"))
                }
                tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(verify) }
            }
        }
    }
}
