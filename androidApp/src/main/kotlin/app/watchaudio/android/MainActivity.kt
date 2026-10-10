package app.watchaudio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.watchaudio.app.App
import app.watchaudio.app.AppServices
import app.watchaudio.app.FakeAppServices

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // TODO(androidApp): replace this placeholder with AndroidAppServices (ingest, transcription,
        //  playback, model download). It only shows what is already in the local database.
        val services = placeholder ?: FakeAppServices.open(filesDir.absolutePath).also { placeholder = it }
        setContent { App(services) }
    }

    private companion object {
        var placeholder: AppServices? = null
    }
}
