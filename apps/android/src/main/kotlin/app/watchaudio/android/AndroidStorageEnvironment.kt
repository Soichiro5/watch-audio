package app.watchaudio.android

import android.os.StatFs
import app.watchaudio.core.pipeline.StorageEnvironment
import java.io.File

/** Model size and free space for the storage screen (#33); all data lives in internal storage. */
class AndroidStorageEnvironment(
    private val modelsDir: File,
    private val dataDir: File,
) : StorageEnvironment {
    override fun modelBytes(): Long = modelsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    override fun freeBytes(): Long? = runCatching { StatFs(dataDir.path).availableBytes }.getOrNull()
}
