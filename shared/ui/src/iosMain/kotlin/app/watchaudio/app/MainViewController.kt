package app.watchaudio.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point for the Compose UI, called from Swift (`MainViewControllerKt.MainViewController()`)
 * after `IosAppGraph.shared.start(...)`.
 */
@Suppress("FunctionName", "unused") // Called from Swift
fun MainViewController(): UIViewController = ComposeUIViewController { App(IosAppGraph.services) }
