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

/**
 * #76: the iPhone's database, audio and inbox directories are excluded from backup and get the
 * `CompleteUntilFirstUserAuthentication` protection class.
 *
 * The simulator has no Data Protection: setting the class succeeds ([LocalDataDirectory.prepare]
 * returns true) but the class is not necessarily reported back, so it is compared only when the
 * file system reports one. The backup exclusion is checked strictly.
 */
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
        // Application Support/WatchAudio holds the database (and inbox/), audio/ the archive.
        for (path in listOf(IosAppGraph.rootDirectory, IosAppGraph.audioDirectory)) {
            assertTrue(LocalDataDirectory.prepare(path), "attributes can be set on $path")
            assertProtected(path)
        }
        val dbPath = IosAppGraph.rootDirectory + "/protection-probe.db"
        try {
            assertTrue(NSFileManager.defaultManager.createFileAtPath(dbPath, null, null))
            assertProtectionClass(dbPath)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dbPath, null)
        }
    }

    private fun assertProtected(path: String) {
        val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
        assertEquals(true, (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue, "$path excluded from backup")
        assertProtectionClass(path)
    }

    private fun assertProtectionClass(path: String) {
        val protection = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileProtectionKey)
        if (protection != null) {
            assertEquals<Any?>(NSFileProtectionCompleteUntilFirstUserAuthentication, protection, "$path protection class")
        }
    }
}
