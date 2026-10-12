"""Allow-lists for check.py: what the apps may request, link and contact.

Every entry carries the reason it is allowed. Adding an entry is a privacy decision: state the
reason here and in the PR description, and update docs/architecture/02 §7 (network call sites and storage)
and docs/dev/04 (how the check works). See docs/dev/04-dev-environment.md "Local-only チェック".
"""

# Whole-domain exclusions every backup rule file must contain (Android backup domains).
BACKUP_DOMAINS = ("root", "file", "database", "sharedpref", "external")

ANDROID_APPS = {
    "apps/android": {
        # <uses-permission> in the module's own manifests (src/*/AndroidManifest.xml).
        "permissions": {
            "android.permission.INTERNET": "model downloads only: ASR and the semantic search's embedding model (asr/ModelManager.kt)",
            "android.permission.ACCESS_NETWORK_STATE": "WorkManager network constraint for the model download",
            "android.permission.FOREGROUND_SERVICE": "model download runs as a foreground worker",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC": "foreground service type of the model download",
            "android.permission.POST_NOTIFICATIONS": "gap alert (#43) and download progress",
        },
        # Extra permissions libraries add to the merged manifest (checked with --merged-manifest).
        "library_permissions": {
            "android.permission.WAKE_LOCK": "WorkManager",
            "android.permission.RECEIVE_BOOT_COMPLETED": "WorkManager reschedules work after reboot",
            "app.watchaudio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "androidx.core (signature, app-internal)",
        },
        # <data-extraction-rules> sections that must exclude every domain. Device-to-device
        # transfer (cable / local Wi-Fi) stays allowed on the phone so history can move.
        "excluded_backup_sections": ("cloud-backup",),
    },
    "apps/wear": {
        "permissions": {
            "android.permission.RECORD_AUDIO": "the recorder",
            "android.permission.FOREGROUND_SERVICE": "recording service",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE": "foreground service type of the recorder",
            "android.permission.POST_NOTIFICATIONS": "ongoing recording notification",
            "android.permission.VIBRATE": "haptic feedback on start/stop/mark",
            "android.permission.RECEIVE_BOOT_COMPLETED": "resume recording after reboot (BootReceiver)",
            "android.permission.BODY_SENSORS": "off-body detection on Wear OS 4/5 (optional)",
        },
        "library_permissions": {
            "android.permission.WAKE_LOCK": "WorkManager / play-services",
            "android.permission.ACCESS_NETWORK_STATE": "WorkManager constraint tracking (no INTERNET on the watch)",
            "app.watchaudio.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION": "androidx.core (signature, app-internal)",
        },
        "excluded_backup_sections": ("cloud-backup", "device-transfer"),
    },
}

# Directories whose .kt/.java/.swift files are scanned (relative to the repository root).
# Test code is not shipped and is skipped: directories named "test" or containing "Test"
# (src/test, src/jvmTest, src/androidTest, apps/ios/Tests/ProtocolTests, apps/ios/Tests/TestSupport, ...).
SOURCE_ROOTS = ("apps/android/src", "apps/wear/src", "shared", "apps/ios")
SOURCE_EXTENSIONS = (".kt", ".java", ".swift")

# APIs that move data off the device or weaken on-device protection. Matched in code with
# comments removed. (id, regex, what it is)
GUARDED_APIS = (
    ("URLConnection", r"\b(?:Http|Https)?URLConnection\b", "java.net HTTP client"),
    ("openConnection", r"\.openConnection\s*\(", "java.net.URL.openConnection"),
    ("openStream", r"\.openStream\s*\(", "java.net.URL.openStream"),
    ("java.net.http", r"\bjava\.net\.http\b", "JDK HttpClient"),
    ("Socket", r"\b(?:Server|Datagram|SSL)?Socket\b|\bjava\.net\.Socket", "raw sockets"),
    ("WebSocket", r"\bWebSocket\b|\bURLSessionWebSocketTask\b", "WebSocket"),
    ("OkHttp", r"\bokhttp3?\b|\bOkHttpClient\b", "OkHttp"),
    ("Ktor", r"\bio\.ktor\b", "Ktor client"),
    ("Retrofit", r"\bretrofit2?\b", "Retrofit"),
    ("Cronet", r"\bCronet\w*", "Cronet"),
    ("DownloadManager", r"\bDownloadManager\b", "Android DownloadManager"),
    ("WebView", r"\b(?:WK)?WebView\b", "web view"),
    ("URLSession", r"\b(?:NS)?URLSession\b", "Apple URLSession"),
    ("NSURLConnection", r"\bNSURLConnection\b", "Apple NSURLConnection"),
    ("Network.framework", r"\bNW(?:Connection|Listener|Browser|Endpoint)\b|^\s*import\s+Network\b|\bplatform\.Network\b",
     "Apple Network.framework"),
    ("MultipeerConnectivity", r"\bMultipeerConnectivity\b|\bMCSession\b", "Apple peer-to-peer networking"),
    ("SpeechServerRecognition", r"\bSF\w*RecognitionRequest\b",
     "SFSpeechRecognizer requests may use Apple's servers; use SpeechAnalyzer (on-device)"),
    ("DataClient", r"\b(?:DataClient|PutDataRequest|PutDataMapRequest|getDataClient)\b",
     "Wear Data Layer DataClient (items may sync through the cloud); use MessageClient/ChannelClient"),
    ("WeakFileProtection", r"\bNSFileProtectionNone\b|\.noFileProtection\b|\bFileProtectionType\.none\b|"
     r"\bMODE_WORLD_(?:READABLE|WRITEABLE)\b", "files readable without protection / by other apps"),
)

# file (relative path) -> {api id: reason}. These are the app's only network call sites.
# An entry that no longer matches anything is reported too, so the list stays accurate.
ALLOWED_API_USES = {
    "apps/android/src/main/kotlin/app/watchaudio/android/asr/ModelDownloader.kt": {
        "URLConnection": "downloads the ASR / VAD / embedding model files listed in ModelManifest (resumable, SHA-256)",
        "openConnection": "same download",
    },
}

# URL literals in code must start with one of these prefixes (unused prefixes are reported).
ALLOWED_URL_PREFIXES = {
    "https://huggingface.co/csukuangfj/reazonspeech-k2-v2": "ASR model files, pinned commit (ModelManifest)",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/": "Silero VAD model (ModelManifest)",
    "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17":
        "English (SenseVoice) model files, pinned commit, opt-in download only (ModelManifest.englishFiles, #81)",
    "https://huggingface.co/onnx-community/ruri-v3-30m-ONNX":
        "embedding model of semantic search (ruri-v3-30m int8 ONNX), pinned commit, Wi-Fi only (ModelManifest.embeddingFiles, #24)",
    "https://huggingface.co/cl-nagoya/ruri-v3-30m":
        "tokenizer of the embedding model, pinned commit, Wi-Fi only (ModelManifest.embeddingFiles, #24)",
}

# Files whose URL literals are only shown as text and never opened or fetched: any URL is allowed
# there (the guarded network APIs above still are not). Unused entries are reported.
DISPLAY_ONLY_URL_FILES = {
    "shared/ui/src/commonMain/kotlin/app/watchaudio/app/licenses/OpenSourceNotices.kt":
        "licenses screen (#167): project, license and model source URLs shown as selectable text",
    "shared/ui/src/commonMain/kotlin/app/watchaudio/app/licenses/LicenseTexts.kt":
        "licenses screen (#167): URLs inside the license texts",
}

# Declared dependencies (gradle/libs.versions.toml, *.gradle.kts) must not match these.
DENIED_DEPENDENCIES = (
    ("analytics / ads", r"firebase|crashlytics|google-services|analytics|telemetry|"
     r"play-services-(?:ads|measurement|appset)|\b(?:com\.amplitude|com\.mixpanel|com\.segment|com\.posthog|"
     r"io\.heap|com\.datadoghq|com\.newrelic|com\.flurry|com\.appsflyer|com\.adjust|io\.branch|"
     r"com\.facebook|com\.onesignal|com\.braze|com\.clevertap|ly\.count)\b"),
    ("crash reporting", r"\b(?:io\.sentry|com\.bugsnag|com\.instabug|ch\.acra|com\.microsoft\.appcenter|"
     r"io\.embrace)\b|\bsentry\b|\bbugsnag\b"),
    ("network client", r"okhttp|retrofit|\bktor\b|volley|cronet|\bgrpc\b|apollographql|io\.socket|paho|mqtt"),
)
# Gradle files outside the shipped apps (benchmarks, the Linux harness).
DEPENDENCY_SCAN_EXCLUDES = ("research/",)

# Resolved runtime classpath of the apps (--resolved-deps): every module must match one of
# these "group:artifact" patterns.
ALLOWED_RESOLVED_DEPENDENCIES = (
    (r"androidx\.[\w.-]+:[\w.-]+", "AndroidX / Jetpack"),
    (r"org\.jetbrains\.kotlinx?(?:\.[\w-]+)*:[\w.-]+", "Kotlin stdlib, coroutines, serialization, io"),
    (r"org\.jetbrains:annotations", "Kotlin annotations"),
    (r"org\.jetbrains\.(?:compose|androidx)\.[\w.-]+:[\w.-]+", "Compose Multiplatform"),
    (r"com\.google\.android\.gms:play-services-(?:wearable|base|basement|tasks)",
     "Wear OS Data Layer (MessageClient/ChannelClient to nearby nodes)"),
    (r"com\.google\.guava:listenablefuture", "AndroidX futures"),
    (r"org\.jspecify:jspecify", "nullness annotations"),
    (r"com\.github\.k2-fsa\.sherpa-onnx:[\w.-]+", "on-device ASR"),
    (r"net\.java\.dev\.jna:jna", "calls ONNX Runtime's C API for the on-device embedding model (shared/ort, #24); no network"),
)

IOS_PROJECT = "apps/ios/project.yml"

# Info.plist keys per XcodeGen target (info.properties) and allowed UIBackgroundModes.
IOS_TARGETS = {
    "iosApp": {
        "info": {
            "CFBundleDisplayName": "app name",
            "UILaunchScreen": "launch screen",
            "CADisableMinimumFrameDurationOnPhone": "ProMotion",
            "NSSpeechRecognitionUsageDescription": "on-device transcription permission",
            "UIBackgroundModes": "see background_modes",
            "BGTaskSchedulerPermittedIdentifiers": "transcription BGProcessingTask",
        },
        "background_modes": {"processing": "transcribe while charging (BGProcessingTask)"},
    },
    "WatchApp": {
        "info": {
            "CFBundleDisplayName": "app name",
            "WKApplication": "watchOS app",
            "WKCompanionAppBundleIdentifier": "paired iPhone app",
            "NSMicrophoneUsageDescription": "the recorder",
            "UIBackgroundModes": "see background_modes",
            "CFBundleURLTypes": "watchaudio:// URL the complication opens to start recording (#41); app-internal",
            "WatchAudioAppGroup": "App Group id shared with the complication (on-device container)",
        },
        "background_modes": {"audio": "keep recording with the screen off"},
    },
    "WatchWidget": {
        "info": {
            "CFBundleDisplayName": "complication name",
            "NSExtension": "WidgetKit extension point (#41)",
            "WatchAudioAppGroup": "App Group id to read the recorder state written by the app",
        },
        "background_modes": {},
    },
}

# Privacy manifests (PrivacyInfo.xcprivacy, #159). Every application / app-extension target must
# copy one into its bundle; tracking stays off and no data type is collected. Required-reason API
# categories and the Apple reason codes a manifest may declare (code locations:
# docs/legal/store-data-declarations.md §2.3).
IOS_PRIVACY_MANIFEST_NAME = "PrivacyInfo.xcprivacy"
IOS_PRIVACY_TARGET_TYPES = ("application", "app-extension")
IOS_REQUIRED_REASONS = {
    "NSPrivacyAccessedAPICategoryFileTimestamp": {
        "C617.1": "size / metadata of files in the app or App Group container (SQLite, skiko, watch outbox)",
    },
    "NSPrivacyAccessedAPICategoryDiskSpace": {
        "85F4.1": "free space shown on the iPhone status tab",
        "E174.1": "the watch stops recording when storage is low",
    },
    "NSPrivacyAccessedAPICategorySystemBootTime": {
        "35F9.1": "elapsed time within the app (Compose / skiko frame timing)",
    },
    "NSPrivacyAccessedAPICategoryUserDefaults": {
        "CA92.1": "the app's own defaults (watch recorder state)",
        "1C8F.1": "App Group defaults shared by the watch app and its complication",
    },
}

# Entitlement keys (project.yml `entitlements` or committed *.entitlements). WatchConnectivity
# and on-device Speech work without entitlements.
IOS_ENTITLEMENTS = {
    "com.apple.security.application-groups":
        "watch app <-> complication share the recorder state (#41) in an on-device App Group container; "
        "no iCloud / network",
}
