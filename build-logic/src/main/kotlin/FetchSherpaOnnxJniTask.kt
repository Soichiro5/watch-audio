import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Provides the pinned `libsherpa-onnx-jni.so` per ABI as `<outputDir>/<abi>/libsherpa-onnx-jni.so`
 * (#173, registered by [SherpaOnnxJniPlugin]). Each file is taken from the shared cache
 * ([cacheDirectory], keyed by SHA-256, so a download happens once per machine), else from
 * [localDirectory] when set, else downloaded from `<baseUrl>/libsherpa-onnx-jni-<abi>.so`. A file is
 * used only when its SHA-256 equals the pin. Build time only: the app itself never downloads code.
 */
abstract class FetchSherpaOnnxJniTask : DefaultTask() {
    /** ABI -> pinned SHA-256 of `libsherpa-onnx-jni-<abi>.so`. */
    @get:Input
    abstract val sha256ByAbi: MapProperty<String, String>

    @get:Input
    abstract val baseUrl: Property<String>

    /** Optional directory holding `libsherpa-onnx-jni-<abi>.so` (same pins). */
    @get:Internal
    abstract val localDirectory: Property<String>

    @get:Internal
    abstract val cacheDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for ((abi, sha256) in sha256ByAbi.get()) {
            val cached = File(cacheDirectory.get(), "$sha256/libsherpa-onnx-jni.so")
            if (!cached.isFile || sha256(cached) != sha256) provide(abi, sha256, cached)
            cached.copyTo(File(out, "$abi/libsherpa-onnx-jni.so"), overwrite = true)
            logger.info("sherpa-onnx JNI ($abi): $sha256")
        }
    }

    /** Fills [cached] from the local directory or the release and checks [sha256]. */
    private fun provide(abi: String, sha256: String, cached: File) {
        val fileName = "libsherpa-onnx-jni-$abi.so"
        cached.parentFile.mkdirs()
        val part = File(cached.parentFile, "$fileName.part")
        val local = localDirectory.orNull?.let { File(it, fileName) }
        val source = if (local != null) {
            if (!local.isFile) throw GradleException("$local does not exist (-P${SherpaOnnxJniPlugin.LOCAL_DIR_PROPERTY})")
            local.copyTo(part, overwrite = true)
            local.path
        } else {
            val url = "${baseUrl.get()}/$fileName"
            logger.lifecycle("Downloading $url")
            try {
                download(url, part)
            } catch (e: IOException) {
                part.delete()
                throw GradleException(
                    "Cannot download $url (${e.message}). The release named in ${SherpaOnnxJniPlugin.PINS_PATH} must be " +
                        "published; offline, pass -P${SherpaOnnxJniPlugin.LOCAL_DIR_PROPERTY}=<dir with $fileName> " +
                        "(docs/dev/04-dev-environment.md §7.3)",
                    e,
                )
            }
            url
        }
        val actual = sha256(part)
        if (actual != sha256) {
            part.delete()
            throw GradleException("SHA-256 of $source is $actual, but ${SherpaOnnxJniPlugin.PINS_PATH} pins $sha256")
        }
        Files.move(part.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun download(url: String, target: File) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
