import CryptoKit
import Foundation

enum FileDigest {
    /// Lowercase hex SHA-256 of a file's contents, streamed in 64 KiB blocks
    /// (same format as `sha256Of` in shared/platform, which the phone verifies with).
    static func sha256Hex(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 64 * 1024), !data.isEmpty {
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Keys used in WatchConnectivity metadata / userInfo dictionaries (watch ↔ iPhone).
enum WatchTransferKeys {
    /// ChunkManifest JSON (file metadata, or userInfo for a chunk without audio).
    static let manifest = "manifest"
    /// Chunk id, duplicated outside the JSON so outstanding transfers can be matched cheaply.
    static let chunkId = "chunkId"
    /// ChunkAck JSON (iPhone → watch userInfo).
    static let ack = "ack"
}
