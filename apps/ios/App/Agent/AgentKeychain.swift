import CryptoKit
import Foundation
import Security

/// The phone's identity for agent access (#185): an ECDSA P-256 private key and its self-signed
/// certificate in the Keychain, together the `SecIdentity` that Network.framework presents in the
/// mutual TLS handshake. The bridge on the PC pins the key by its fingerprint.
///
/// - The key is generated in the Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`:
///   it never syncs (iCloud Keychain) or moves to another device in a backup, and it is usable while
///   the phone is locked after the first unlock. It is not exported; only signatures leave it.
/// - Not the Secure Enclave: the simulator has none (CI could not test the path the app takes), and
///   Network.framework needs the key as a Keychain `SecIdentity` either way. The data the key
///   protects (transcripts) lives in the app's files with the same protection class.
/// - The certificate is public; it is built in Kotlin (`SelfSignedCertificate`, the shape the bridge
///   expects) and signed here.
enum AgentKeychain {
    /// Tag of the private key; label of the certificate.
    static let tag = Data("app.watchaudio.agent.identity".utf8)
    static let label = "app.watchaudio.agent.identity"

    struct Failure: LocalizedError {
        let errorDescription: String?
        init(_ what: String, _ status: OSStatus? = nil) {
            errorDescription = status.map { "\(what) (OSStatus \($0))" } ?? what
        }
    }

    /// DER SubjectPublicKeyInfo prefix of an uncompressed P-256 public key (id-ecPublicKey, prime256v1).
    static let p256SubjectPublicKeyInfoPrefix = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ])

    /// The private key, created on first use.
    static func privateKey() throws -> SecKey {
        let query: [CFString: Any] = [
            kSecClass: kSecClassKey,
            kSecAttrApplicationTag: tag,
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let item, CFGetTypeID(item) == SecKeyGetTypeID() {
            return item as! SecKey // swiftlint:disable:this force_cast
        }
        guard status == errSecItemNotFound else { throw Failure("reading the key", status) }
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: tag,
                kSecAttrLabel: label,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ] as [CFString: Any],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw Failure("creating the key: \(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }
        // A new key needs a new certificate.
        SecItemDelete(certificateQuery as CFDictionary)
        return key
    }

    /// The DER SubjectPublicKeyInfo of an EC P-256 key, or nil for any other key.
    static func subjectPublicKeyInfo(of key: SecKey) -> Data? {
        let publicKey = SecKeyCopyPublicKey(key) ?? key
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
              raw.count == 65, raw.first == 0x04 else { return nil }
        return p256SubjectPublicKeyInfoPrefix + raw
    }

    /// `keyFingerprint` of `shared/agent`: base64url (no padding) of SHA-256 of the SubjectPublicKeyInfo.
    static func fingerprint(ofSubjectPublicKeyInfo spki: Data) -> String {
        Data(SHA256.hash(data: spki)).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// The fingerprint of a certificate's EC P-256 key, or nil for any other key.
    static func fingerprint(of certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate), let spki = subjectPublicKeyInfo(of: key) else { return nil }
        return fingerprint(ofSubjectPublicKeyInfo: spki)
    }

    /// ECDSA with SHA-256 over `data`, DER-encoded.
    static func sign(_ data: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(try privateKey(), .ecdsaSignatureMessageX962SHA256, data as CFData, &error) as Data? else {
            throw Failure("signing: \(error?.takeRetainedValue().localizedDescription ?? "unknown")")
        }
        return signature
    }

    /// Stores `der` as the certificate of the key, replacing an earlier one.
    static func installCertificate(_ der: Data) throws {
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { throw Failure("not a certificate") }
        SecItemDelete(certificateQuery as CFDictionary)
        let add: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecValueRef: certificate,
            kSecAttrLabel: label,
            kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else { throw Failure("storing the certificate", status) }
    }

    /// Matches the stored certificate.
    private static var certificateQuery: [CFString: Any] {
        [kSecClass: kSecClassCertificate, kSecAttrLabel: label]
    }

    static func storedCertificate() -> SecCertificate? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassCertificate,
            kSecAttrLabel: label,
            kSecReturnRef: true,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item,
              CFGetTypeID(item) == SecCertificateGetTypeID() else { return nil }
        return (item as! SecCertificate) // swiftlint:disable:this force_cast
    }

    /// The key and its stored certificate as one identity (the Keychain pairs them by public key).
    static func identity() throws -> SecIdentity {
        guard let certificate = storedCertificate() else { throw Failure("no certificate") }
        let wanted = SecCertificateCopyData(certificate) as Data
        let query: [CFString: Any] = [
            kSecClass: kSecClassIdentity,
            kSecReturnRef: true,
            kSecMatchLimit: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &items)
        guard status == errSecSuccess, let identities = items as? [SecIdentity] else { throw Failure("finding the identity", status) }
        for identity in identities {
            var found: SecCertificate?
            if SecIdentityCopyCertificate(identity, &found) == errSecSuccess, let found,
               SecCertificateCopyData(found) as Data == wanted {
                return identity
            }
        }
        throw Failure("the certificate and the key do not form an identity")
    }
}
