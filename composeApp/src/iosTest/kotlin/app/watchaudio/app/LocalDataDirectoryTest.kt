package app.watchaudio.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

/** #76: the iPhone's database, audio and inbox directories are excluded from backup and protected. */
@OptIn(ExperimentalForeignApi::class)
class LocalDataDirectoryTest {
    @Test
    fun prepareExcludesFromBackupAndSetsProtectionClass() {
        val dir = NSTemporaryDirectory() + "local-data-" + NSUUID().UUIDString
        try {
            assertTrue(LocalDataDirectory.prepare(dir))
            assertTrue(LocalDataDirectory.prepare(dir), "idempotent")
            assertProtected(dir)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dir, null)
        }
    }

    @Test
    fun appDirectoriesAreProtected() {
        // Application Support/WatchAudio holds the database; audio/ the archive; inbox/ the
        // files just received from the watch.
        assertProtected(IosAppGraph.rootDirectory)
        assertProtected(IosAppGraph.audioDirectory)
        val dbPath = IosAppGraph.rootDirectory + "/protection-probe.db"
        try {
            assertTrue(NSFileManager.defaultManager.createFileAtPath(dbPath, null, null))
            assertEquals(NSFileProtectionCompleteUntilFirstUserAuthentication, protectionOf(dbPath), "inherited by new files")
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dbPath, null)
        }
    }

    private fun assertProtected(path: String) {
        val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
        assertEquals(true, (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue, "$path excluded from backup")
        assertEquals(NSFileProtectionCompleteUntilFirstUserAuthentication, protectionOf(path), "$path protection class")
    }

    private fun protectionOf(path: String): Any? =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileProtectionKey)
}
