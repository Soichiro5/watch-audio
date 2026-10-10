import AVFoundation
import Foundation

/// Encoding used for every chunk (AAC-LC, 16 kHz mono, 24 kbps ≈ 10.8 MB/hour).
enum ChunkAudioFormat {
    static let sampleRateHz = 16_000
    static let channels = 1
    static let bitrateBps = 24_000
    static let fileExtension = "m4a"

    /// AVAudioRecorder settings (built on demand: `[String: Any]` is not Sendable).
    static var recorderSettings: [String: Any] {
        [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRateHz,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: bitrateBps,
        ]
    }
}

/// The chunk currently being recorded, persisted next to its audio so it survives the app
/// being terminated (it is then finalized with endReason `terminated` on the next launch).
struct InProgressChunk: Codable, Sendable {
    var chunkId: String
    var sessionId: String
    var startedAtEpochMs: Int64
    var timeZone: String
    var markOffsetsMs: [Int64]
}

/// A closed chunk waiting for the iPhone's final ACK.
struct OutboxItem: Sendable {
    let manifest: ChunkManifest
    let manifestJSON: String
    /// nil for a silent chunk (manifest only).
    let audioURL: URL?

    var chunkId: String { manifest.chunkId }
}

/// Persistent outbox under Application Support/WatchAudio:
///
///     recording/<id>.m4a, recording/<id>.json   chunk being recorded (InProgressChunk)
///     outbox/<id>.m4a, outbox/<id>.json         closed chunk + manifest; deleted on final ACK
///
/// Everything is plain files, so the queue survives relaunches and reboots.
struct ChunkOutbox: Sendable {
    let root: URL
    let recordingDirectory: URL
    let outboxDirectory: URL

    init(root: URL = URL.applicationSupportDirectory.appending(path: "WatchAudio")) {
        self.root = root
        recordingDirectory = root.appending(path: "recording")
        outboxDirectory = root.appending(path: "outbox")
        for directory in [recordingDirectory, outboxDirectory] {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    // MARK: - Recording

    func recordingAudioURL(_ chunkId: String) -> URL {
        recordingDirectory.appending(path: "\(chunkId).\(ChunkAudioFormat.fileExtension)")
    }

    func saveInProgress(_ chunk: InProgressChunk) {
        guard let data = try? JSONEncoder().encode(chunk) else { return }
        try? data.write(to: inProgressURL(chunk.chunkId), options: .atomic)
    }

    /// Closes a chunk: moves (or, if silent, deletes) its audio, hashes it and writes the manifest.
    func commit(
        _ chunk: InProgressChunk,
        deviceId: String,
        durationMs: Int64,
        endReason: ChunkEndReason,
        keepAudio: Bool
    ) throws -> OutboxItem {
        let fileManager = FileManager.default
        let id = chunk.chunkId
        let source = recordingAudioURL(id)
        let duration = max(0, durationMs)
        var audio: AudioFile?
        var audioURL: URL?
        if keepAudio, let size = fileSize(source), size > 0 {
            let fileName = source.lastPathComponent
            let target = outboxDirectory.appending(path: fileName)
            try? fileManager.removeItem(at: target)
            try fileManager.moveItem(at: source, to: target)
            audio = AudioFile(
                fileName: fileName,
                codec: .aac,
                sampleRateHz: ChunkAudioFormat.sampleRateHz,
                channels: ChunkAudioFormat.channels,
                bitrateBps: ChunkAudioFormat.bitrateBps,
                sizeBytes: size,
                sha256: try FileDigest.sha256Hex(of: target)
            )
            audioURL = target
        } else {
            try? fileManager.removeItem(at: source)
        }
        let manifest = ChunkManifest(
            chunkId: id,
            sessionId: chunk.sessionId,
            deviceId: deviceId,
            source: .watchos,
            startedAtEpochMs: chunk.startedAtEpochMs,
            durationMs: duration,
            timeZone: chunk.timeZone,
            audio: audio,
            markOffsetsMs: chunk.markOffsetsMs.map { min(max($0, 0), duration) },
            endReason: endReason
        )
        let json = try ChunkProtocol.encode(manifest)
        try Data(json.utf8).write(to: manifestURL(id), options: .atomic)
        try? fileManager.removeItem(at: inProgressURL(id))
        return OutboxItem(manifest: manifest, manifestJSON: json, audioURL: audioURL)
    }

    /// Finalizes chunks that were still recording when the app was terminated.
    func recoverAbandoned(deviceId: String) -> [OutboxItem] {
        let fileManager = FileManager.default
        let files = (try? fileManager.contentsOfDirectory(at: recordingDirectory, includingPropertiesForKeys: nil)) ?? []
        var recovered: [OutboxItem] = []
        for url in files where url.pathExtension == "json" {
            guard let data = try? Data(contentsOf: url),
                  let chunk = try? JSONDecoder().decode(InProgressChunk.self, from: data)
            else {
                try? fileManager.removeItem(at: url)
                continue
            }
            let id = chunk.chunkId
            if fileManager.fileExists(atPath: manifestURL(id).path(percentEncoded: false)) {
                // Crashed right after committing: the outbox already has it.
                try? fileManager.removeItem(at: url)
                try? fileManager.removeItem(at: recordingAudioURL(id))
                continue
            }
            let audioURL = recordingAudioURL(id)
            let movedAudio = outboxDirectory.appending(path: audioURL.lastPathComponent)
            if !fileManager.fileExists(atPath: audioURL.path(percentEncoded: false)),
               fileManager.fileExists(atPath: movedAudio.path(percentEncoded: false)) {
                try? fileManager.moveItem(at: movedAudio, to: audioURL)
            }
            // AVAudioRecorder may not have finalized the file; keep it only if it is readable.
            var durationMs: Int64 = 0
            var readable = false
            if let file = try? AVAudioFile(forReading: audioURL), file.length > 0, file.fileFormat.sampleRate > 0 {
                durationMs = Int64(Double(file.length) / file.fileFormat.sampleRate * 1000)
                readable = true
            } else if let modified = modificationDate(audioURL) {
                durationMs = Int64(modified.timeIntervalSince1970 * 1000) - chunk.startedAtEpochMs
            }
            if let item = try? commit(chunk, deviceId: deviceId, durationMs: durationMs, endReason: .terminated, keepAudio: readable) {
                recovered.append(item)
            }
        }
        // Audio without a state file cannot be described; drop it.
        for url in files where url.pathExtension == ChunkAudioFormat.fileExtension {
            let state = recordingDirectory.appending(path: url.deletingPathExtension().lastPathComponent + ".json")
            if !fileManager.fileExists(atPath: state.path(percentEncoded: false)) {
                try? fileManager.removeItem(at: url)
            }
        }
        return recovered
    }

    // MARK: - Outbox

    /// Closed chunks waiting for a final ACK, oldest first.
    func pendingItems() -> [OutboxItem] {
        let files = (try? FileManager.default.contentsOfDirectory(at: outboxDirectory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { item(manifestURL: $0) }
            .sorted { $0.manifest.startedAtEpochMs < $1.manifest.startedAtEpochMs }
    }

    func item(chunkId: String) -> OutboxItem? {
        item(manifestURL: manifestURL(chunkId))
    }

    /// Deletes a chunk after the iPhone confirmed it (stored or duplicate).
    func remove(chunkId: String) {
        try? FileManager.default.removeItem(at: manifestURL(chunkId))
        try? FileManager.default.removeItem(at: outboxDirectory.appending(path: "\(chunkId).\(ChunkAudioFormat.fileExtension)"))
    }

    /// Free space on the watch's data volume, in bytes.
    func freeBytes() -> Int64? {
        let attributes = try? FileManager.default.attributesOfFileSystem(forPath: root.path(percentEncoded: false))
        return (attributes?[.systemFreeSize] as? NSNumber)?.int64Value
    }

    // MARK: - Helpers

    private func item(manifestURL url: URL) -> OutboxItem? {
        guard let data = try? Data(contentsOf: url),
              let json = String(data: data, encoding: .utf8),
              let manifest = try? ChunkProtocol.decode(ChunkManifest.self, from: json)
        else {
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        guard let audio = manifest.audio else {
            return OutboxItem(manifest: manifest, manifestJSON: json, audioURL: nil)
        }
        let audioURL = outboxDirectory.appending(path: audio.fileName)
        guard FileManager.default.fileExists(atPath: audioURL.path(percentEncoded: false)) else {
            // Audio lost (should not happen): the phone could never verify it, so drop the entry.
            try? FileManager.default.removeItem(at: url)
            return nil
        }
        return OutboxItem(manifest: manifest, manifestJSON: json, audioURL: audioURL)
    }

    private func manifestURL(_ chunkId: String) -> URL {
        outboxDirectory.appending(path: "\(chunkId).json")
    }

    private func inProgressURL(_ chunkId: String) -> URL {
        recordingDirectory.appending(path: "\(chunkId).json")
    }

    private func fileSize(_ url: URL) -> Int64? {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path(percentEncoded: false))
        return (attributes?[.size] as? NSNumber)?.int64Value
    }

    private func modificationDate(_ url: URL) -> Date? {
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path(percentEncoded: false))
        return attributes?[.modificationDate] as? Date
    }
}
