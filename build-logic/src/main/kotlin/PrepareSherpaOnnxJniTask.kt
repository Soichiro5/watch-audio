import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies `<jniDirectory>/<abi>/libsherpa-onnx-jni.so` for each of [abis] to a jniLibs directory,
 * `<outputDir>/<abi>/libsherpa-onnx-jni.so` (#173, registered by [SherpaOnnxJniPlugin]). Only the
 * libraries: the directory's other files (`sherpa-onnx-jni.properties`) are not packed.
 */
abstract class PrepareSherpaOnnxJniTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniDirectory: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (abi in abis.get()) {
            val library = jniDirectory.get().asFile.resolve("$abi/${SherpaOnnxJniPlugin.LIBRARY}")
            if (!library.isFile) throw GradleException("$library does not exist")
            library.copyTo(out.resolve("$abi/${SherpaOnnxJniPlugin.LIBRARY}"), overwrite = true)
        }
    }
}
