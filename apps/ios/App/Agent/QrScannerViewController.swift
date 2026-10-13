import AVFoundation
import UIKit

/// Reads the pairing QR code from the PC's terminal (#185) with the camera, on the device:
/// AVFoundation's built-in machine-readable code detector (`AVCaptureMetadataOutput`, QR only). No
/// frame is stored or sent anywhere; the session stops as soon as a code is read or the user closes
/// the view.
///
/// Not VisionKit's `DataScannerViewController`: Apple's documentation does not say whether its
/// processing stays on the device (VisionKit also hosts Live Text and Visual Look Up), while the
/// capture pipeline's QR detector is a plain on-device feature. It also does not need the
/// A12-or-later check DataScanner has. Neither works on the simulator, where the typed string is
/// used instead.
@MainActor
final class QrScannerViewController: UIViewController, @preconcurrency AVCaptureMetadataOutputObjectsDelegate {
    /// Asks for the camera permission if it was never asked, then presents the scanner over the
    /// visible screen. `completion` gets the text, or nil with `unavailable` when there is no camera
    /// or the permission is refused (nil and false: the user closed the scanner).
    static func scan(completion: @escaping @MainActor (_ text: String?, _ unavailable: Bool) -> Void) {
        guard AVCaptureDevice.default(for: .video) != nil else { return completion(nil, true) }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            present(completion: completion)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    if granted { present(completion: completion) } else { completion(nil, true) }
                }
            }
        default:
            completion(nil, true)
        }
    }

    private static func present(completion: @escaping @MainActor (String?, Bool) -> Void) {
        guard let presenter = topViewController() else { return completion(nil, true) }
        let scanner = QrScannerViewController(completion: completion)
        guard scanner.configure() else { return completion(nil, true) }
        scanner.modalPresentationStyle = .fullScreen
        presenter.present(scanner, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        var top = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }

    private let session = AVCaptureSession()
    private var completion: (@MainActor (String?, Bool) -> Void)?

    private init(completion: @escaping @MainActor (String?, Bool) -> Void) {
        self.completion = completion
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not used") }

    /// Connects the camera to the QR detector; false if the camera cannot be used.
    private func configure() -> Bool {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return false }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return false }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        return true
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.layer.bounds
        view.layer.addSublayer(preview)

        let hint = UILabel()
        hint.text = "PC のターミナルに出た QR コードを映してください"
        hint.textColor = .white
        hint.numberOfLines = 0
        hint.textAlignment = .center
        hint.font = .preferredFont(forTextStyle: .headline)
        let cancel = UIButton(configuration: .filled(), primaryAction: UIAction(title: "やめる") { [weak self] _ in
            self?.finish(nil, unavailable: false)
        })
        let stack = UIStackView(arrangedSubviews: [hint, cancel])
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .center
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24),
        ])

        let running = UncheckedSendableBox(session)
        DispatchQueue.global(qos: .userInitiated).async { running.value.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.layer.bounds }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let text = metadataObjects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
        finish(text, unavailable: false)
    }

    private func finish(_ text: String?, unavailable: Bool) {
        guard let completion else { return }
        self.completion = nil
        let stopping = UncheckedSendableBox(session)
        DispatchQueue.global(qos: .userInitiated).async { stopping.value.stopRunning() }
        dismiss(animated: true) { completion(text, unavailable) }
    }
}
