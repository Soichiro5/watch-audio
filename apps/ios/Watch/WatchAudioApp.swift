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
                .task {
                    statusPublisher.observe(recorder)
                    #if DEBUG
                    // `-complicationURL watchaudio://record`: handle a complication tap without a
                    // watch face (CI; simctl openurl cannot open app URLs on watchOS).
                    if let url = UserDefaults.standard.string(forKey: "complicationURL").flatMap(URL.init(string:)) {
                        handleComplicationURL(url)
                    }
                    #endif
                }
                // Tapping the complication (#41): open the app and start or resume recording.
                .onOpenURL { url in handleComplicationURL(url) }
        }
        // The system wakes the app in the background to deliver WatchConnectivity content.
        .backgroundTask(.watchConnectivity) { [recorder] in
            await recorder.receivePendingTransfers()
        }
    }

    /// Starts or resumes recording for the complication's URL (no-op while recording).
    private func handleComplicationURL(_ url: URL) {
        guard ComplicationLink.isRecordRequest(url) else { return }
        Task { await recorder.start() }
    }
}
