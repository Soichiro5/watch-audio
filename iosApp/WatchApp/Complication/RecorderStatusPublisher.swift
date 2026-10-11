import Foundation
import Observation
import WidgetKit

/// Mirrors `RecorderController`'s state into the App Group for the complication (#41) and asks
/// WidgetKit to redraw it whenever the state changes. Uses Observation tracking, so it also
/// follows changes made while the app runs in the background (interruptions, charging).
@MainActor
final class RecorderStatusPublisher {
    private weak var recorder: RecorderController?
    private var lastPublished: RecorderStatus?
    private let defaults: UserDefaults

    init(defaults: UserDefaults = RecorderStatusStore.sharedDefaults()) {
        self.defaults = defaults
    }

    /// Starts following `recorder` (idempotent for the same recorder).
    func observe(_ recorder: RecorderController) {
        guard self.recorder !== recorder else { return }
        self.recorder = recorder
        track()
    }

    private func track() {
        guard let recorder else { return }
        let status = withObservationTracking {
            RecorderStatus(phase: recorder.phase, since: recorder.recordingSince)
        } onChange: { [weak self] in
            // Called before the change is applied: read the new values on the next main-actor turn.
            Task { @MainActor [weak self] in self?.track() }
        }
        publish(status)
    }

    private func publish(_ status: RecorderStatus) {
        guard status != lastPublished else { return }
        lastPublished = status
        RecorderStatusStore.save(status, to: defaults)
        WidgetCenter.shared.reloadTimelines(ofKind: RecorderStatusStore.widgetKind)
    }
}

extension RecorderStatus {
    init(phase: RecorderPhase, since: Date?) {
        switch phase {
        case .recording: self = .recording(since: since)
        case .paused(let reason): self = .paused(reason)
        case .idle: self = .stopped
        }
    }
}
