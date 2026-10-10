package app.watchaudio.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Hosts the shared UI; the iOS app passes its `IosAppServices`. */
@Suppress("FunctionName", "unused") // Called from Swift
fun MainViewController(services: AppServices): UIViewController = ComposeUIViewController { App(services) }
