package app.watchaudio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.watchaudio.app.App

class MainActivity : ComponentActivity() {
    private val services: AndroidAppServices get() = (application as WatchAudioApp).services

    /** Runtime permission prompts requested by the shared onboarding through [AndroidAppServices]. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> services.onPermissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val services = services
        services.attachPermissionLauncher(permissionLauncher)
        setContent { App(services) }
    }

    override fun onDestroy() {
        services.detachPermissionLauncher(permissionLauncher)
        super.onDestroy()
    }
}
