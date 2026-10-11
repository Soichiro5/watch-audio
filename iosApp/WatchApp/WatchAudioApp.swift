import SwiftUI

@main
struct WatchAudioApp: App {
    @State private var recorder = RecorderController()
    @State private var statusPublisher = RecorderStatusPublisher()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(recorder: recorder)
                .onChange(of: scenePhase, initial: true) { _, phase in
                    if phase == .active {
                        recorder.appBecameActive()
                    }
                }
                .task { statusPublisher.observe(recorder) }
                // Tapping the complication (#41): open the app and start or resume recording.
                .onOpenURL { url in
                    guard ComplicationLink.isRecordRequest(url) else { return }
                    Task { await recorder.start() }
                }
        }
        // The system wakes the app in the background to deliver WatchConnectivity content.
        .backgroundTask(.watchConnectivity) { [recorder] in
            await recorder.receivePendingTransfers()
        }
    }
}
