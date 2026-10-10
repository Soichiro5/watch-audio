import SwiftUI

@main
struct WatchAudioApp: App {
    @State private var recorder = RecorderController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(recorder: recorder)
                .onChange(of: scenePhase, initial: true) { _, phase in
                    if phase == .active {
                        recorder.appBecameActive()
                    }
                }
        }
        // The system wakes the app in the background to deliver WatchConnectivity content.
        .backgroundTask(.watchConnectivity) { [recorder] in
            await recorder.receivePendingTransfers()
        }
    }
}
