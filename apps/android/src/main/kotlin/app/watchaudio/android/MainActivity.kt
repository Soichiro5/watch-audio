package app.watchaudio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.watchaudio.app.App
import app.watchaudio.app.BACKUP_MIME_TYPE

class MainActivity : ComponentActivity() {
    private val services: AndroidAppServices get() = (application as WatchAudioApp).services

    /** Runtime permission prompts requested by the shared onboarding through [AndroidAppServices]. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> services.onPermissionResult(granted) }

    /** The save dialog of the data export (#155): the user creates the document it is written to. */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(AndroidAppServices.EXPORT_MIME_TYPE)) { uri ->
            services.onExportDestination(uri)
        }

    /** The save dialog of an encrypted backup (#40). */
    private val backupSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri ->
            services.onExportDestination(uri)
        }

    /** The file picker for restoring a backup (#40): the user picks the document it is read from. */
    private val backupOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> services.onBackupPicked(uri) }

    /** The camera app taking the photo of the PC's pairing QR code (agent access, #184; no CAMERA permission). */
    private val pictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved -> services.agentAccess?.onPictureTaken(saved) }

    /** The local network permission (Android 17+) agent access asks for before connecting. */
    private val localNetworkLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> services.agentAccess?.onPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = services
        services.attachPermissionLauncher(permissionLauncher)
        services.attachSaveLauncher(AndroidAppServices.EXPORT_MIME_TYPE, exportLauncher)
        services.attachSaveLauncher(BACKUP_MIME_TYPE, backupSaveLauncher)
        services.attachOpenBackupLauncher(backupOpenLauncher)
        services.agentAccess?.attachLaunchers(pictureLauncher, localNetworkLauncher)
        setContent { App(services) }
    }

    override fun onDestroy() {
        services.detachPermissionLauncher(permissionLauncher)
        services.detachSaveLauncher(AndroidAppServices.EXPORT_MIME_TYPE, exportLauncher)
        services.detachSaveLauncher(BACKUP_MIME_TYPE, backupSaveLauncher)
        services.detachOpenBackupLauncher(backupOpenLauncher)
        services.agentAccess?.detachLaunchers(pictureLauncher, localNetworkLauncher)
        super.onDestroy()
    }
}
