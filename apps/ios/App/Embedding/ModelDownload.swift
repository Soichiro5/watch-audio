import ComposeApp
import Foundation

/// Downloads one file of the embedding model (#178): the iPhone app's only connection to the
/// internet. Kotlin decides what to fetch (`RuriV3.FILES`: pinned Hugging Face commits, checked
/// against their SHA-256 afterwards by `ModelInstaller`); this only moves bytes into the `.part`
/// file it is given. Nothing is sent but the GET request.
///
/// Wi-Fi only: no cellular, no expensive network (a phone's personal hotspot) and no constrained
/// one (Low Data Mode). On any other network the task waits for one (`waitsForConnectivity`),
/// which is reported through `onWaiting`. An ephemeral session keeps no cookies or cache.
///
/// With an offset, only the rest of the file is asked for (HTTP range, also across Hugging Face's
/// redirect to its CDN); a server that answers with the whole file makes the part file start over.
final class ModelDownload: NSObject, IosDownloadHandle, URLSessionDataDelegate, @unchecked Sendable {
    /// Progress is reported every this many bytes (and at the end).
    private static let progressStep: Int64 = 256 * 1024

    private let partURL: URL
    private let offset: Int64
    private let onProgress: (Int64) -> Void
    private let onWaiting: () -> Void
    private let callback: (String?) -> Void

    private let lock = NSLock()
    private var session: URLSession?
    private var file: FileHandle?
    private var written: Int64 = 0
    private var reported: Int64 = 0
    /// The error to report when the task completes (set when a response is refused).
    private var failure: String?
    private var done = false

    private init(partPath: String, offset: Int64, onProgress: @escaping (Int64) -> Void, onWaiting: @escaping () -> Void, callback: @escaping (String?) -> Void) {
        partURL = URL(fileURLWithPath: partPath)
        self.offset = offset
        self.onProgress = onProgress
        self.onWaiting = onWaiting
        self.callback = callback
    }

    static func start(
        url: String,
        partPath: String,
        offset: Int64,
        onProgress: @escaping (Int64) -> Void,
        onWaiting: @escaping () -> Void,
        callback: @escaping (String?) -> Void
    ) -> ModelDownload {
        let download = ModelDownload(partPath: partPath, offset: offset, onProgress: onProgress, onWaiting: onWaiting, callback: callback)
        download.begin(url)
        return download
    }

    /// Wi-Fi (or wired) only, nothing stored by the session itself.
    static func configuration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.allowsCellularAccess = false
        configuration.allowsExpensiveNetworkAccess = false
        configuration.allowsConstrainedNetworkAccess = false
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        configuration.httpCookieStorage = nil
        configuration.urlCache = nil
        return configuration
    }

    private func begin(_ urlString: String) {
        guard let url = URL(string: urlString), url.scheme == "https" else {
            finish("bad_url")
            return
        }
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        let session = URLSession(configuration: Self.configuration(), delegate: self, delegateQueue: queue)
        var request = URLRequest(url: url)
        if offset > 0 { request.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range") }
        lock.lock()
        self.session = session
        lock.unlock()
        session.dataTask(with: request).resume()
    }

    func cancel() {
        lock.lock()
        // No byte is written after this returns: the part file keeps what arrived so far.
        let wasDone = done
        if !done { failure = "cancelled" }
        closeFile()
        let session = self.session
        lock.unlock()
        if !wasDone { session?.invalidateAndCancel() }
    }

    func urlSession(_ session: URLSession, taskIsWaitingForConnectivity task: URLSessionTask) {
        onWaiting()
    }

    /// Hugging Face redirects to its CDN: keep the range, and only follow HTTPS.
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard request.url?.scheme == "https" else {
            completionHandler(nil)
            return
        }
        var redirected = request
        if let range = task.originalRequest?.value(forHTTPHeaderField: "Range") {
            redirected.setValue(range, forHTTPHeaderField: "Range")
        }
        completionHandler(redirected)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            refuse("no_http_response", completionHandler)
            return
        }
        let start: Int64
        switch http.statusCode {
        case 206 where offset > 0:
            // "bytes <first>-<last>/<total>": the rest must start where the part file ends.
            let range = http.value(forHTTPHeaderField: "Content-Range") ?? ""
            guard range.hasPrefix("bytes \(offset)-") else {
                truncatePart()
                refuse("bad_range", completionHandler)
                return
            }
            start = offset
        case 200:
            start = 0
        default:
            refuse("http_\(http.statusCode)", completionHandler)
            return
        }
        lock.lock()
        defer { lock.unlock() }
        guard !done, failure == nil else {
            completionHandler(.cancel)
            return
        }
        do {
            if !FileManager.default.fileExists(atPath: partURL.path) {
                FileManager.default.createFile(atPath: partURL.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: partURL)
            try handle.truncate(atOffset: UInt64(start))
            try handle.seekToEnd()
            file = handle
            written = start
            reported = start
            completionHandler(.allow)
        } catch {
            failure = "file_error"
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard let file, !done, failure == nil else {
            lock.unlock()
            return
        }
        var progress: Int64?
        do {
            try file.write(contentsOf: data)
            written += Int64(data.count)
            if written - reported >= Self.progressStep {
                reported = written
                progress = written
            }
        } catch {
            failure = "file_error"
            dataTask.cancel()
        }
        lock.unlock()
        if let progress { onProgress(progress) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        closeFile()
        let result: String?
        if let failure {
            result = failure
        } else if let error = error as? URLError {
            result = "url_error_\(error.code.rawValue)"
        } else if error != nil {
            result = "error"
        } else {
            result = nil
        }
        let total = written
        lock.unlock()
        session.finishTasksAndInvalidate()
        if result == nil { onProgress(total) }
        finish(result)
    }

    private func refuse(_ reason: String, _ completionHandler: (URLSession.ResponseDisposition) -> Void) {
        lock.lock()
        if failure == nil { failure = reason }
        lock.unlock()
        completionHandler(.cancel)
    }

    private func truncatePart() {
        if let handle = try? FileHandle(forWritingTo: partURL) {
            try? handle.truncate(atOffset: 0)
            try? handle.close()
        }
    }

    /// Call with the lock held.
    private func closeFile() {
        try? file?.close()
        file = nil
    }

    /// Calls back exactly once.
    private func finish(_ result: String?) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { callback(result) }
    }
}
