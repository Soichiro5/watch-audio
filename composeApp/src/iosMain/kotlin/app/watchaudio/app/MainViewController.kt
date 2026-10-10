package app.watchaudio.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Entry point for the Compose UI, called from Swift (`MainViewControllerKt.MainViewController()`)
 * after `IosAppGraph.shared.start(...)`. Kept parameterless so the Swift side does not change
 * when `App()` starts taking [AppServices]: only the call below needs `App(services)`.
 */
@Suppress("FunctionName", "unused") // Called from Swift
fun MainViewController(): UIViewController {
    @Suppress("UNUSED_VARIABLE")
    val services: AppServices = IosAppGraph.services
    return ComposeUIViewController { App() }
}
