package app.watchaudio.core.db

import kotlin.random.Random
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

/** A fresh directory under the platform's temporary directory, for one test. */
class TempDir(prefix: String) {
    val path: Path = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong().toString(16)}")
        .also { SystemFileSystem.createDirectories(it) }

    fun resolve(name: String): Path = Path(path, name)

    fun deleteRecursively() = deleteTree(path)

    private fun deleteTree(target: Path) {
        val metadata = SystemFileSystem.metadataOrNull(target) ?: return
        if (metadata.isDirectory) SystemFileSystem.list(target).forEach(::deleteTree)
        SystemFileSystem.delete(target, mustExist = false)
    }
}
