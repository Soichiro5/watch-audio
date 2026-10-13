import ComposeApp
import Foundation
import Network
import Security

/// The iPhone's only network connections (#185, tools/privacy-check), both for agent access while the
/// user has it on and the app is on screen:
///
/// - **Mutual TLS 1.3** (Network.framework) to the paired PC's bridge: only to an IP literal on the
///   local network (the Kotlin side checks `isLanHost`; here the host must parse as an IPv4 / IPv6
///   address, so nothing is looked up in DNS), presenting the Keychain identity (`AgentKeychain`) and
///   accepting only the bridge key whose fingerprint was pinned at pairing (no certificate
///   authorities, no host names). The phone never listens.
/// - **mDNS browsing** for `_watchaudio-mcp._tcp` (announces nothing). A found service is resolved
///   to its address with a UDP `NWConnection`, which becomes ready once the name is resolved
///   without sending anything to the bridge.
final class AgentNetwork: @unchecked Sendable {
    static let serviceType = "_watchaudio-mcp._tcp"

    private let queue = DispatchQueue(label: "app.watchaudio.agent.network")

    /// Connects to `host`:`port` and completes the handshake; see the type's description.
    func connect(host: String, port: Int, fingerprint: String, timeoutMs: Int64, completion: @escaping @Sendable (IosAgentConnectResult) -> Void) {
        let endpointHost: NWEndpoint.Host
        if let v4 = IPv4Address(host) {
            endpointHost = .ipv4(v4)
        } else if let v6 = IPv6Address(host) {
            endpointHost = .ipv6(v6)
        } else {
            return completion(IosAgentConnectResult(connection: nil, error: "not an IP address", localNetworkDenied: false))
        }
        guard let endpointPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)), port > 0 else {
            return completion(IosAgentConnectResult(connection: nil, error: "bad port", localNetworkDenied: false))
        }
        let identity: SecIdentity
        do {
            identity = try AgentKeychain.identity()
        } catch {
            return completion(IosAgentConnectResult(connection: nil, error: error.localizedDescription, localNetworkDenied: false))
        }
        guard let localIdentity = sec_identity_create(identity) else {
            return completion(IosAgentConnectResult(connection: nil, error: "no TLS identity", localNetworkDenied: false))
        }

        let tls = NWProtocolTLS.Options()
        let security = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(security, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(security, .TLSv13)
        sec_protocol_options_set_local_identity(security, localIdentity)
        sec_protocol_options_set_peer_authentication_required(security, true)
        // Pinning replaces the system's trust evaluation: only the paired bridge key is accepted.
        sec_protocol_options_set_verify_block(security, { _, trust, complete in
            let chain = SecTrustCopyCertificateChain(sec_trust_copy_ref(trust).takeRetainedValue()) as? [SecCertificate]
            let presented = chain?.first.flatMap(AgentKeychain.fingerprint(of:))
            complete(presented == fingerprint)
        }, queue)
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = Int(max(1, timeoutMs / 1_000))
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.includePeerToPeer = false

        let connection = NWConnection(host: endpointHost, port: endpointPort, using: parameters)
        let link = AgentTlsLink(connection: connection)
        let once = Once(completion)
        connection.stateUpdateHandler = { [weak connection] state in
            switch state {
            case .ready:
                once.call(IosAgentConnectResult(connection: link, error: nil, localNetworkDenied: false))
            case .waiting(let error), .failed(let error):
                // `waiting` would retry on its own (e.g. connection refused); the caller retries instead.
                let denied = connection?.currentPath?.unsatisfiedReason == .localNetworkDenied || Self.isPolicyDenied(error)
                once.call(IosAgentConnectResult(connection: nil, error: "\(error)", localNetworkDenied: denied))
                connection?.cancel()
            case .cancelled:
                once.call(IosAgentConnectResult(connection: nil, error: "cancelled", localNetworkDenied: false))
            default:
                break
            }
        }
        connection.start(queue: queue)
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(timeoutMs))) { [weak connection] in
            if once.call(IosAgentConnectResult(connection: nil, error: "timed out", localNetworkDenied: false)) {
                connection?.cancel()
            }
        }
    }

    /// Browses for bridges until the handle is cancelled; see the type's description.
    func browse(onFound: @escaping @Sendable (IosBridgeFound) -> Void, onFailed: @escaping @Sendable (String) -> Void) -> AgentBrowse {
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil), using: parameters)
        let failed = Once(onFailed)
        let handle = AgentBrowse(browser: browser)
        browser.stateUpdateHandler = { [weak browser] state in
            switch state {
            case .failed(let error):
                failed.call("\(error)")
                browser?.cancel()
            case .waiting(let error):
                // E.g. the Local Network permission was refused; the last known address is still tried.
                failed.call("\(error)")
            default:
                break
            }
        }
        browser.browseResultsChangedHandler = { [weak self] _, changes in
            for change in changes {
                switch change {
                case .added(let result), .changed(_, let result, _):
                    self?.resolve(result, onFound: onFound, handle: handle)
                default:
                    break
                }
            }
        }
        browser.start(queue: queue)
        return handle
    }

    /// Resolves one browse result to an IPv4 address and port (no packet to the bridge).
    private func resolve(_ result: NWBrowser.Result, onFound: @escaping @Sendable (IosBridgeFound) -> Void, handle: AgentBrowse) {
        guard case .bonjour(let txt) = result.metadata, let nonce = txt["n"], let keyHash = txt["h"] else { return }
        let parameters = NWParameters.udp
        parameters.includePeerToPeer = false
        (parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options)?.version = .v4
        let probe = NWConnection(to: result.endpoint, using: parameters)
        handle.track(probe)
        probe.stateUpdateHandler = { [weak probe] state in
            switch state {
            case .ready:
                if case .hostPort(let host, let port)? = probe?.currentPath?.remoteEndpoint, case .ipv4(let address) = host {
                    onFound(IosBridgeFound(nonce: nonce, keyHash: keyHash, host: "\(address)", port: Int32(port.rawValue)))
                }
                probe?.cancel()
            case .waiting, .failed:
                probe?.cancel()
            default:
                break
            }
        }
        probe.start(queue: queue)
        queue.asyncAfter(deadline: .now() + .seconds(5)) { [weak probe] in probe?.cancel() }
    }

    /// `kDNSServiceErr_PolicyDenied`: what Network.framework reports when Local Network is refused.
    private static func isPolicyDenied(_ error: NWError) -> Bool {
        if case .dns(let code) = error { return code == -65570 }
        return false
    }
}

/// One TLS connection: a byte pipe for the Kotlin side, which does the framing.
final class AgentTlsLink: NSObject, IosAgentLink, @unchecked Sendable {
    private let connection: NWConnection

    init(connection: NWConnection) {
        self.connection = connection
    }

    func send(data: Data, callback: @escaping (String?) -> Void) {
        let reply = UncheckedSendableBox(callback)
        connection.send(content: data, completion: .contentProcessed { error in reply.value(error.map { "\($0)" }) })
    }

    func receive(length: Int32, callback: @escaping (Data?) -> Void) {
        receive(remaining: Int(length), into: Data(), reply: UncheckedSendableBox(callback))
    }

    private func receive(remaining: Int, into received: Data, reply: UncheckedSendableBox<(Data?) -> Void>) {
        guard remaining > 0 else { return reply.value(received) }
        connection.receive(minimumIncompleteLength: remaining, maximumLength: remaining) { [self] data, _, isComplete, error in
            var received = received
            if let data { received.append(data) }
            let left = remaining - (data?.count ?? 0)
            if left == 0 { return reply.value(received) }
            if error != nil || isComplete { return reply.value(nil) }
            receive(remaining: left, into: received, reply: reply)
        }
    }

    func close() {
        connection.cancel()
    }
}

/// The running browser and its resolution probes; cancelling stops them all.
final class AgentBrowse: NSObject, IosAgentCancellable, @unchecked Sendable {
    private let browser: NWBrowser
    private let lock = NSLock()
    private var probes: [NWConnection] = []
    private var cancelled = false

    init(browser: NWBrowser) {
        self.browser = browser
    }

    func track(_ probe: NWConnection) {
        lock.lock()
        defer { lock.unlock() }
        probes.removeAll { if case .cancelled = $0.state { true } else { false } }
        probes.append(probe)
        if cancelled { probe.cancel() }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let running = probes
        probes = []
        lock.unlock()
        browser.cancel()
        running.forEach { $0.cancel() }
    }
}

/// Calls the wrapped callback at most once; `call` returns whether this was the call.
final class Once<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: ((Value) -> Void)?

    init(_ callback: @escaping (Value) -> Void) {
        self.callback = callback
    }

    @discardableResult
    func call(_ value: Value) -> Bool {
        lock.lock()
        let pending = callback
        callback = nil
        lock.unlock()
        guard let pending else { return false }
        pending(value)
        return true
    }
}
