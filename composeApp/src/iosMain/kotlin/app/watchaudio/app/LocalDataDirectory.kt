package app.watchaudio.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * On-device protection of the iOS app's data (#76): the database, audio and inbox directories
 * are excluded from iCloud / computer backups (the flag covers everything inside) and use the
 * `NSFileProtectionCompleteUntilFirstUserAuthentication` class, which new files inherit. That
 * class keeps the data encrypted until the first unlock after boot while still letting the
 * WatchConnectivity ingest and the BGProcessingTask transcription run with the screen locked.
 * The watch applies the same policy (iosApp/Shared/LocalDataProtection.swift).
 */
@OptIn(ExperimentalForeignApi::class)
internal object LocalDataDirectory {
    /**
     * Creates [path] if needed, excludes it from backups and sets its protection class.
     * Returns false if an attribute could not be set (the directory still exists).
     */
    fun prepare(path: String): Boolean {
        SystemFileSystem.createDirectories(Path(path))
        val excluded = NSURL.fileURLWithPath(path).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null)
        val protected = NSFileManager.defaultManager.setAttributes(
            mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication),
            ofItemAtPath = path,
            error = null,
        )
        return excluded && protected
    }
}
