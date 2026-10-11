package app.watchaudio.app

import app.watchaudio.core.pipeline.StorageEnvironment
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

/**
 * Storage facts for the iOS app (#33). The Speech framework's on-device models are managed by
 * the OS (shared between apps, not in our container), so their size is unknown here.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosStorageEnvironment(private val dataDirectory: String) : StorageEnvironment {
    override fun modelBytes(): Long? = null

    /** "Important usage" capacity: what iOS would free up for a user-initiated save. */
    override fun freeBytes(): Long? {
        val url = NSURL.fileURLWithPath(dataDirectory)
        val important = url.resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
            ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber
        if (important != null) return important.longLongValue
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(dataDirectory, null)
        return (attributes?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
    }
}
