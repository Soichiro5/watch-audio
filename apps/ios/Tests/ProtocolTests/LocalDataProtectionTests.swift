import Foundation
import XCTest

/// The watch's outbox directories (ChunkOutbox) are prepared with LocalDataProtection (#76).
final class LocalDataProtectionTests: XCTestCase {
    func testDirectoryIsExcludedFromBackupAndProtected() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "local-data-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: root) }
        let outbox = root.appending(path: "outbox")

        try LocalDataProtection.prepare(directory: root)
        try LocalDataProtection.prepare(directory: outbox)
        // Idempotent: ChunkOutbox prepares the same directories on every launch.
        try LocalDataProtection.prepare(directory: outbox)

        for directory in [root, outbox] {
            let values = try directory.resourceValues(forKeys: [.isExcludedFromBackupKey])
            XCTAssertEqual(values.isExcludedFromBackup, true, directory.lastPathComponent)
            // The simulator has no Data Protection: setting the class succeeds (prepare did not
            // throw) but it is not necessarily reported back. Compare it when it is.
            let attributes = try FileManager.default.attributesOfItem(atPath: directory.path(percentEncoded: false))
            if let protection = attributes[.protectionKey] as? FileProtectionType {
                XCTAssertEqual(protection, .completeUntilFirstUserAuthentication, directory.lastPathComponent)
            }
        }
    }
}
