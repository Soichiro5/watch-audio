import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Guards #57: the audio file input of the Wear OS app (package `app.watchaudio.wear.debug`,
 * `src/debug`) must be in debug builds and never in release builds. Scans the APK's dex files for
 * [debugPackage] and the merged manifest for its activity. The debug variant expects them present,
 * which also proves that the scan finds them. For minified variants (release, #78) it also scans
 * the R8 mapping, which lists original class names even when R8 renamed them.
 *
 * Registered per variant by [DebugInputGuardPlugin] and run as part of `assemble<Variant>`.
 */
abstract class VerifyDebugInputTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val r8Mapping: RegularFileProperty

    /** Java package of the debug-only input, e.g. `app.watchaudio.wear.debug`. */
    @get:Input
    abstract val debugPackage: Property<String>

    @get:Input
    abstract val expectPresent: Property<Boolean>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = apkDirectory.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "no APK in ${apkDirectory.get().asFile}" }
        val classPrefix = debugPackage.get() + "."
        val dexMarker = "L${debugPackage.get().replace('.', '/')}/".toByteArray()
        val findings = apks.map { apk ->
            ZipFile(apk).use { zip ->
                val dexHits = zip.entries().toList()
                    .filter { Regex("classes\\d*\\.dex").matches(it.name) }
                    .filter { entry -> zip.getInputStream(entry).use { it.readBytes() }.contains(dexMarker) }
                    .map { it.name }
                "${apk.name}: debug input classes in ${dexHits.ifEmpty { listOf("none") }}" to dexHits.isNotEmpty()
            }
        }
        val inManifest = mergedManifest.get().asFile.readText().contains(classPrefix)
        val inMapping = r8Mapping.orNull?.asFile?.let { mapping ->
            mapping.useLines { lines -> lines.any { it.startsWith(classPrefix) } }
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
