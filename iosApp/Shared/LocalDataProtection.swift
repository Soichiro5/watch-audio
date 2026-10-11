import Foundation

/// On-device protection of app data (#76): recordings, manifests and logs never go into
/// iCloud / computer backups, and are encrypted with a class that still lets background work
/// run after the first unlock (recording with the screen off, sending, transcription).
/// The iPhone's Kotlin side applies the same policy (`LocalDataDirectory` in composeApp/iosMain).
enum LocalDataProtection {
    /// Readable once the device has been unlocked after boot; locked again only by a reboot.
    static var fileProtection: FileProtectionType { .completeUntilFirstUserAuthentication }

    /// Creates `directory` if needed, excludes it from backups (the flag covers everything
    /// inside) and sets its protection class, which files created inside it inherit.
    static func prepare(directory: URL) throws {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        var url = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
        try fileManager.setAttributes(
            [.protectionKey: fileProtection],
            ofItemAtPath: directory.path(percentEncoded: false)
        )
    }
}
