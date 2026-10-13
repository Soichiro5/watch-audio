import ComposeApp
import Foundation

/// Agent access on iOS (#185): implements the Kotlin `IosAgentPlatform` on top of the Keychain
/// (`AgentKeychain`), Network.framework (`AgentNetwork`), the camera (`QrScannerViewController`) and
/// AVFoundation (`AgentAudioClip`). The logic (pairing, the connection while on, the tools, the
/// foreground-only rule) is Kotlin's (`IosAgentAccess`).
final class AgentPlatform: NSObject, IosAgentPlatform, @unchecked Sendable {
    private let network = AgentNetwork()
    private let audioQueue = DispatchQueue(label: "app.watchaudio.agent.audio")

    func loadKey() -> IosAgentKey {
        do {
            guard let spki = AgentKeychain.subjectPublicKeyInfo(of: try AgentKeychain.privateKey()) else {
                return IosAgentKey(subjectPublicKeyInfo: nil, error: "the Keychain key is not EC P-256")
            }
            return IosAgentKey(subjectPublicKeyInfo: spki, error: nil)
        } catch {
            return IosAgentKey(subjectPublicKeyInfo: nil, error: error.localizedDescription)
        }
    }

    func sign(data: Data) -> Data? {
        try? AgentKeychain.sign(data)
    }

    func installCertificate(certificate: Data) -> String? {
        do {
            try AgentKeychain.installCertificate(certificate)
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    func storedCertificate() -> Data? {
        AgentKeychain.storedCertificate().map { SecCertificateCopyData($0) as Data }
    }

    func connect(host: String, port: Int32, fingerprint: String, timeoutMs: Int64, callback: @escaping (IosAgentConnectResult) -> Void) {
        let reply = UncheckedSendableBox(callback)
        network.connect(host: host, port: Int(port), fingerprint: fingerprint, timeoutMs: timeoutMs) { reply.value($0) }
    }

    func browse(onFound: @escaping (IosBridgeFound) -> Void, onFailed: @escaping (String) -> Void) -> any IosAgentCancellable {
        let found = UncheckedSendableBox(onFound)
        let failed = UncheckedSendableBox(onFailed)
        return network.browse(onFound: { found.value($0) }, onFailed: { failed.value($0) })
    }

    func scanQr(callback: @escaping (IosQrScanResult) -> Void) {
        let reply = UncheckedSendableBox(callback)
        Task { @MainActor in
            QrScannerViewController.scan { text, unavailable in
                reply.value(IosQrScanResult(text: text, unavailable: unavailable))
            }
        }
    }

    func readAudio(path: String, offsetMs: Int64, durationMs: Int64, callback: @escaping (IosAudioClipResult) -> Void) {
        let reply = UncheckedSendableBox(callback)
        audioQueue.async {
            do {
                let samples = try AgentAudioClip.read(path: path, offsetMs: offsetMs, durationMs: durationMs)
                reply.value(IosAudioClipResult(samples: samples, error: nil))
            } catch {
                reply.value(IosAudioClipResult(samples: nil, error: error.localizedDescription))
            }
        }
    }
}
