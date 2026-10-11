import ComposeApp
import SwiftUI

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Order matters: the Kotlin graph (database, BGTask registration) must exist before
        // WatchConnectivity can deliver chunks, and BGTask registration must happen during launch.
        IosAppGraph.shared.start(transcriber: SpeechAnalyzerTranscriber())
        WatchReceiver.shared.activate()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .onChange(of: scenePhase, initial: true) { _, phase in
                    switch phase {
                    case .active: IosAppGraph.shared.setAppActive(active: true)
                    case .background: IosAppGraph.shared.setAppActive(active: false)
                    default: break // .inactive (e.g. Control Center): keep going
                    }
                }
        }
    }
}

/// Hosts the Compose Multiplatform UI (`shared/ui`) inside SwiftUI.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
