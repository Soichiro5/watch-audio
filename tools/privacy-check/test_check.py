"""Tests for check.py: the real repository passes, and each kind of violation fails.

Run: python3 -m unittest discover -s tools/privacy-check -v
"""

from __future__ import annotations

import tempfile
import textwrap
import unittest
from pathlib import Path

import check

REPO_ROOT = Path(__file__).resolve().parents[2]

PHONE_MANIFEST = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_CONTACTS" tools:node="remove" />
    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules" />
</manifest>
"""

WEAR_MANIFEST = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <application
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules" />
</manifest>
"""

ALL_DOMAINS = "".join(f'<exclude domain="{d}" path="." />' for d in ("root", "file", "database", "sharedpref",
                                                                     "external"))
PHONE_RULES = f"<data-extraction-rules><cloud-backup>{ALL_DOMAINS}</cloud-backup></data-extraction-rules>"
PHONE_BACKUP = f"<full-backup-content>{ALL_DOMAINS}</full-backup-content>"
WEAR_RULES = (f"<data-extraction-rules><cloud-backup>{ALL_DOMAINS}</cloud-backup>"
              f"<device-transfer>{ALL_DOMAINS}</device-transfer></data-extraction-rules>")

MODEL_DOWNLOADER = "apps/android/src/main/kotlin/app/watchaudio/android/asr/ModelDownloader.kt"
MODEL_MANIFEST = "apps/android/src/main/kotlin/app/watchaudio/android/asr/ModelManifest.kt"
AGENT_TLS = "apps/android/src/main/kotlin/app/watchaudio/android/agent/TlsAgentTransport.kt"
AGENT_NSD = "apps/android/src/main/kotlin/app/watchaudio/android/agent/NsdBridgeDiscovery.kt"
AGENT_IOS = "apps/ios/App/Agent/AgentNetwork.swift"
AGENT_IOS_TRANSPORT = "shared/ui/src/iosMain/kotlin/app/watchaudio/app/IosAgentTransport.kt"
NOTICES = "shared/ui/src/commonMain/kotlin/app/watchaudio/app/licenses/OpenSourceNotices.kt"
LICENSE_TEXTS = "shared/ui/src/commonMain/kotlin/app/watchaudio/app/licenses/LicenseTexts.kt"

PRIVACY_MANIFEST = """\
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>NSPrivacyTracking</key>
	<false/>
	<key>NSPrivacyTrackingDomains</key>
	<array/>
	<key>NSPrivacyCollectedDataTypes</key>
	<array/>
	<key>NSPrivacyAccessedAPITypes</key>
	<array>
		<dict>
			<key>NSPrivacyAccessedAPIType</key>
			<string>NSPrivacyAccessedAPICategoryUserDefaults</string>
			<key>NSPrivacyAccessedAPITypeReasons</key>
			<array>
				<string>CA92.1</string>
			</array>
		</dict>
	</array>
</dict>
</plist>
"""

PROJECT_YML = """\
name: WatchAudio
targets:
  iosApp:
    type: application
    sources:
      - iosApp
      - Shared
      # the privacy manifest is copied as a resource
      - path: Privacy/App/PrivacyInfo.xcprivacy
        buildPhase: resources
    info:
      path: apps/ios/Info.plist
      properties:
        CFBundleDisplayName: watch-audio  # trailing comment
        UIBackgroundModes: [processing]
    preBuildScripts:
      - name: Compile
        script: |
          cd "$SRCROOT/.."  # NSLocationUsageDescription: not a key
          ./gradlew build
  WatchApp:
    type: application
    sources:
      - path: Privacy/Watch/PrivacyInfo.xcprivacy
        buildPhase: resources
    info:
      path: Watch/Info.plist
      properties:
        WKApplication: true
        UIBackgroundModes:
          - audio
"""


class FixtureRepo:
    """A minimal repository that passes every check; tests break one thing at a time."""

    def __init__(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.write("apps/android/src/main/AndroidManifest.xml", PHONE_MANIFEST)
        self.write("apps/android/src/main/res/xml/data_extraction_rules.xml", PHONE_RULES)
        self.write("apps/android/src/main/res/xml/backup_rules.xml", PHONE_BACKUP)
        self.write("apps/wear/src/main/AndroidManifest.xml", WEAR_MANIFEST)
        self.write("apps/wear/src/main/res/xml/data_extraction_rules.xml", WEAR_RULES)
        self.write(MODEL_DOWNLOADER, """\
            import java.net.HttpURLConnection
            fun open(url: String) = URL(url).openConnection() as HttpURLConnection
            """)
        self.write(MODEL_MANIFEST, """\
            const val HF = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2/resolve/abc"
            const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
            const val SENSE_VOICE = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/abc"
            const val RURI_ONNX_HF = "https://huggingface.co/onnx-community/ruri-v3-30m-ONNX/resolve/abc"
            const val RURI_HF = "https://huggingface.co/cl-nagoya/ruri-v3-30m/resolve/abc"
            """)
        self.write(AGENT_TLS, """\
            fun connect(host: String, port: Int, fingerprint: String) {
                if (!isLanHost(host)) throw IOException("not a local network address")
                val address = InetAddresses.parseNumericAddress(host)
                val context = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(PinningTrustManager(fingerprint)), null) }
                val socket = context.socketFactory.createSocket() as SSLSocket
                socket.enabledProtocols = arrayOf(TLS_1_3)
            }
            """)
        self.write(AGENT_NSD, "val nsd = context.getSystemService(NsdManager::class.java)\n")
        self.write(AGENT_IOS, """\
            import Network
            static let serviceType = "_watchaudio-mcp._tcp"
            func connect(host: String, fingerprint: String) {
                let endpoint: NWEndpoint.Host = IPv4Address(host).map { .ipv4($0) } ?? IPv6Address(host).map { .ipv6($0) }!
                sec_protocol_options_set_min_tls_protocol_version(security, .TLSv13)
                sec_protocol_options_set_max_tls_protocol_version(security, .TLSv13)
                sec_protocol_options_set_local_identity(security, identity)
                sec_protocol_options_set_verify_block(security, { _, trust, complete in
                    complete(chain.first.flatMap(AgentKeychain.fingerprint(of:)) == fingerprint)
                }, queue)
                let connection = NWConnection(host: endpoint, port: 1, using: parameters)
            }
            """)
        self.write(AGENT_IOS_TRANSPORT, "fun check(host: String) = require(isLanHost(host))\n")
        self.write(NOTICES, 'val url = "https://github.com/k2-fsa/sherpa-onnx"\n')
        self.write(LICENSE_TEXTS, 'const val APACHE = """http://www.apache.org/licenses/"""\n')
        # Test code is not shipped: a local fake server is fine there.
        self.write("apps/android/src/test/kotlin/DownloaderTest.kt", """\
            val server = java.net.ServerSocket(0)
            val url = "http://127.0.0.1:${server.localPort}/model.onnx"
            """)
        self.write("apps/wear/src/main/kotlin/Uploader.kt", """\
            // DataClient is never used: items may sync through the cloud (see https://example.com).
            /* PutDataRequest /* nested */ HttpURLConnection */
            val channel = Wearable.getChannelClient(context)
            val path = "/watch-audio/chunk/" // "https://example.com" in a comment after a string
            """)
        self.write("apps/ios/Watch/Outbox.swift", """\
            import Foundation
            let s = "a \\" quote" // URLSession in a comment
            """)
        self.write("gradle/libs.versions.toml", """\
            [versions]
            # okhttp is not used
            coroutines = "1.11.0"
            [libraries]
            kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
            """)
        self.write("apps/android/build.gradle.kts", """\
            dependencies {
                // firebase must never be added
                implementation(libs.kotlinx.coroutines.core)
            }
            """)
        self.write("research/bench/build.gradle.kts", 'dependencies { implementation("com.squareup.okhttp3:okhttp:4") }\n')
        self.write("apps/ios/project.yml", PROJECT_YML)
        self.write("apps/ios/Privacy/App/PrivacyInfo.xcprivacy", PRIVACY_MANIFEST)
        self.write("apps/ios/Privacy/Watch/PrivacyInfo.xcprivacy", PRIVACY_MANIFEST)

    def write(self, rel: str, content: str) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content), encoding="utf-8")
        return path

    def replace(self, rel: str, old: str, new: str) -> None:
        path = self.root / rel
        text = path.read_text(encoding="utf-8")
        assert old in text, f"{old!r} not in {rel}"
        path.write_text(text.replace(old, new), encoding="utf-8")

    def run(self, **kwargs) -> check.Report:
        return check.run(self.root, **kwargs)

    def cleanup(self) -> None:
        self._tmp.cleanup()


class RepositoryTest(unittest.TestCase):
    def test_repository_passes(self):
        report = check.run(REPO_ROOT)
        self.assertEqual([], [str(v) for v in report.violations])
        # The model download and, for agent access (#184, #185), the LAN connection to the paired PC
        # and its mDNS discovery are the only network call sites.
        self.assertEqual({(MODEL_DOWNLOADER, "URLConnection"), (MODEL_DOWNLOADER, "openConnection"),
                          (AGENT_TLS, "Socket"), (AGENT_NSD, "NSD"), (AGENT_IOS, "Network.framework")},
                         {(path, api) for path, _, api, _ in report.network_sites})
        # The agent bridge on the PC only listens (TLS) and advertises (mDNS).
        self.assertEqual({("tools/agent-bridge/src/bridge.ts", "tls"), ("tools/agent-bridge/src/pairing.ts", "tls"),
                          ("tools/agent-bridge/src/mdns.ts", "mdns")},
                         {(path, api) for path, _, api, _ in report.bridge_sites})


class FixtureTestCase(unittest.TestCase):
    def setUp(self):
        self.repo = FixtureRepo()
        self.addCleanup(self.repo.cleanup)

    def assertViolation(self, report: check.Report, check_name: str, text: str):
        messages = [str(v) for v in report.violations]
        self.assertTrue(any(v.check == check_name and text in str(v) for v in report.violations),
                        f"no [{check_name}] violation containing {text!r} in {messages}")


class BaselineTest(FixtureTestCase):
    def test_fixture_passes(self):
        report = self.repo.run()
        self.assertEqual([], [str(v) for v in report.violations])
        self.assertEqual({(MODEL_DOWNLOADER, "URLConnection"), (MODEL_DOWNLOADER, "openConnection"),
                          (AGENT_TLS, "Socket"), (AGENT_NSD, "NSD"), (AGENT_IOS, "Network.framework")},
                         {(path, api) for path, _, api, _ in report.network_sites})

    def test_stale_allow_list_entries_fail(self):
        # The list of network call sites must stay accurate (docs/architecture/02 §7.2).
        self.repo.write(MODEL_DOWNLOADER, "import java.net.HttpURLConnection\n")
        self.repo.replace(MODEL_MANIFEST, "huggingface.co", "example.org")
        report = self.repo.run()
        self.assertViolation(report, "network", "stale allow-list entry: openConnection")
        self.assertViolation(report, "network", "no URL starts with https://huggingface.co")


class ManifestTest(FixtureTestCase):
    def test_internet_on_the_watch_fails(self):
        self.repo.replace("apps/wear/src/main/AndroidManifest.xml", "<application",
                          '<uses-permission android:name="android.permission.INTERNET" />\n<application')
        self.assertViolation(self.repo.run(), "permissions", "android.permission.INTERNET is not in the apps/wear")

    def test_unexpected_phone_permission_in_any_source_set_fails(self):
        self.repo.write("apps/android/src/debug/AndroidManifest.xml", """\
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
            </manifest>
            """)
        self.assertViolation(self.repo.run(), "permissions", "AD_ID")

    def test_allow_backup_true_fails(self):
        self.repo.replace("apps/android/src/main/AndroidManifest.xml", 'allowBackup="false"', 'allowBackup="true"')
        self.assertViolation(self.repo.run(), "backup", "allowBackup")

    def test_missing_data_extraction_rules_fails(self):
        self.repo.replace("apps/wear/src/main/AndroidManifest.xml",
                          'android:dataExtractionRules="@xml/data_extraction_rules"', "")
        self.assertViolation(self.repo.run(), "backup", "dataExtractionRules")

    def test_rules_must_exclude_every_domain(self):
        self.repo.replace("apps/android/src/main/res/xml/data_extraction_rules.xml",
                          '<exclude domain="database" path="." />', "")
        self.assertViolation(self.repo.run(), "backup", "<cloud-backup> must exclude whole domains: database")

    def test_partial_path_exclusion_does_not_count(self):
        self.repo.replace("apps/android/src/main/res/xml/backup_rules.xml",
                          '<exclude domain="file" path="." />', '<exclude domain="file" path="cache" />')
        self.assertViolation(self.repo.run(), "backup", "<full-backup-content> must exclude whole domains: file")

    def test_watch_device_transfer_must_be_excluded(self):
        self.repo.write("apps/wear/src/main/res/xml/data_extraction_rules.xml", PHONE_RULES)
        self.assertViolation(self.repo.run(), "backup", "<device-transfer> section missing")

    def test_include_fails(self):
        self.repo.replace("apps/android/src/main/res/xml/data_extraction_rules.xml", "</cloud-backup>",
                          '<include domain="file" path="audio" /></cloud-backup>')
        self.assertViolation(self.repo.run(), "backup", "<include>")

    def test_cleartext_traffic_fails(self):
        self.repo.replace("apps/android/src/main/AndroidManifest.xml", "<application",
                          '<application android:usesCleartextTraffic="true"')
        self.assertViolation(self.repo.run(), "network", "usesCleartextTraffic")

    def test_merged_manifest_allows_library_permissions_only(self):
        merged = self.repo.write("merged.xml", """\
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.RECORD_AUDIO" />
                <uses-permission android:name="android.permission.WAKE_LOCK" />
                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                <application android:allowBackup="false" android:dataExtractionRules="@xml/rules" />
            </manifest>
            """)
        self.assertEqual([], self.repo.run(merged={"apps/wear": merged}).violations)
        self.repo.replace("merged.xml", "WAKE_LOCK", "INTERNET")
        self.assertViolation(self.repo.run(merged={"apps/wear": merged}), "permissions",
                             "merged manifest of apps/wear requests android.permission.INTERNET")


class SourceTest(FixtureTestCase):
    def test_data_client_on_the_watch_fails(self):
        self.repo.write("apps/wear/src/main/kotlin/Sync.kt", "val client = Wearable.getDataClient(context)\n")
        self.assertViolation(self.repo.run(), "network", "getDataClient")

    def test_network_api_outside_the_allow_list_fails(self):
        self.repo.write("shared/pipeline/src/commonMain/kotlin/Upload.kt", """\
            fun upload() {
                val c = URL(endpoint).openConnection()
            }
            """)
        report = self.repo.run()
        self.assertViolation(report, "network", "Upload.kt:2: .openConnection(")

    def test_swift_url_session_fails(self):
        self.repo.write("apps/ios/App/Sync.swift", "let task = URLSession.shared.dataTask(with: url)\n")
        self.assertViolation(self.repo.run(), "network", "URLSession")

    def test_server_speech_recognition_fails(self):
        self.repo.write("apps/ios/App/Asr.swift", "let r = SFSpeechURLRecognitionRequest(url: file)\n")
        self.assertViolation(self.repo.run(), "network", "SFSpeechURLRecognitionRequest")

    def test_weak_file_protection_fails(self):
        self.repo.write("apps/ios/Watch/Files.swift", "try data.write(to: url, options: .noFileProtection)\n")
        self.assertViolation(self.repo.run(), "network", ".noFileProtection")

    def test_unknown_url_fails(self):
        self.repo.write("shared/ui/src/commonMain/kotlin/Help.kt", 'const val HELP = "https://example.com/upload"\n')
        self.assertViolation(self.repo.run(), "network", "URL https://example.com/upload")

    def test_display_only_file_allows_urls_but_not_network_apis(self):
        # License notices (#167) show any URL as text; they still must not open a connection.
        self.repo.write(NOTICES, 'val url = "https://example.com/project"\nval c = URL(url).openConnection()\n')
        report = self.repo.run()
        self.assertFalse(any("example.com" in str(v) for v in report.violations), [str(v) for v in report.violations])
        self.assertViolation(report, "network", "OpenSourceNotices.kt:2: .openConnection(")

    def test_display_only_file_without_urls_is_stale(self):
        self.repo.write(LICENSE_TEXTS, "const val MIT = \"Permission is hereby granted\"\n")
        self.assertViolation(self.repo.run(), "network", "LicenseTexts.kt has no URL")

    def test_sockets_and_mdns_outside_agent_access_fail(self):
        # #184: the LAN client and mDNS are allowed in their two files only.
        self.repo.write("shared/pipeline/src/commonMain/kotlin/Peer.kt", "val s = SSLSocket()\n")
        self.repo.write("apps/android/src/main/kotlin/Finder.kt", "val nsd = getSystemService(NsdManager::class.java)\n")
        self.repo.write("apps/android/src/main/kotlin/Lock.kt", "val lock = wifi.createMulticastLock(tag)\n")
        report = self.repo.run()
        self.assertViolation(report, "network", "Peer.kt:1: SSLSocket")
        self.assertViolation(report, "network", "Finder.kt:1: NsdManager")
        self.assertViolation(report, "network", "Lock.kt:1: createMulticastLock")

    def test_agent_connection_must_stay_on_the_lan(self):
        # The allow-list entry holds only while the transport refuses public addresses and DNS,
        # pins the key and speaks TLS 1.3 only.
        self.repo.replace(AGENT_TLS, "if (!isLanHost(host)) throw", "// isLanHost(host) dropped\n    if (false) throw")
        self.repo.replace(AGENT_TLS, "InetAddresses.parseNumericAddress(host)", "InetAddress.getByName(host)")
        report = self.repo.run()
        self.assertViolation(report, "network", "no longer connects only to LAN addresses")
        self.assertViolation(report, "network", "no longer parses the address without DNS")

    def test_ios_network_outside_agent_access_fails(self):
        # #185: Network.framework is allowed in AgentNetwork.swift only, and nowhere may listen.
        self.repo.write("apps/ios/App/Sync.swift", "import Network\nlet c = NWConnection(to: endpoint, using: .tcp)\n")
        self.repo.replace(AGENT_IOS, "let connection = NWConnection(", "let listener = try NWListener(using: parameters)\n    let connection = NWConnection(")
        report = self.repo.run()
        self.assertViolation(report, "network", "Sync.swift:1: import Network")
        self.assertViolation(report, "network", "Sync.swift:2: NWConnection")
        self.assertViolation(report, "network", "AgentNetwork.swift:11: NWListener")

    def test_ios_agent_connection_must_stay_on_the_lan(self):
        # The entry holds only while the iPhone connects to LAN IP literals with TLS 1.3, its key and
        # the pinned bridge key.
        self.repo.replace(AGENT_IOS, "IPv4Address(host).map { .ipv4($0) } ?? IPv6Address(host).map { .ipv6($0) }!",
                          "NWEndpoint.Host(host)")
        self.repo.replace(AGENT_IOS, "set_max_tls_protocol_version(security, .TLSv13)", "set_max_tls_protocol_version(security, .TLSv12)")
        self.repo.replace(AGENT_IOS, "== fingerprint", "!= nil")
        self.repo.write(AGENT_IOS_TRANSPORT, "fun check(host: String) = true\n")
        report = self.repo.run()
        self.assertViolation(report, "network", "AgentNetwork.swift: allowed network site no longer parses the host as an IP literal")
        self.assertViolation(report, "network", "no longer TLS 1.3 only (minimum and maximum)")
        self.assertViolation(report, "network", "no longer pins the bridge key in the verify block")
        self.assertViolation(report, "network", "IosAgentTransport.kt: allowed network site no longer connects only to LAN addresses")

    def test_allowed_api_in_another_file_fails(self):
        self.repo.write("apps/android/src/main/kotlin/Other.kt", "import java.net.HttpURLConnection\n")
        self.assertViolation(self.repo.run(), "network", "Other.kt:1: HttpURLConnection")


class DependencyTest(FixtureTestCase):
    def test_declared_network_client_fails(self):
        self.repo.replace("apps/android/build.gradle.kts", "implementation(libs.kotlinx.coroutines.core)",
                          'implementation("com.squareup.okhttp3:okhttp:4.12.0")')
        self.assertViolation(self.repo.run(), "dependencies", "network client")

    def test_catalog_analytics_sdk_fails(self):
        self.repo.replace("gradle/libs.versions.toml", "[libraries]",
                          '[libraries]\nfirebase-bom = { module = "com.google.firebase:firebase-bom", version = "34.0.0" }')
        self.assertViolation(self.repo.run(), "dependencies", "libs.versions.toml:5")

    def test_crash_reporter_plugin_fails(self):
        self.repo.write("apps/wear/build.gradle.kts", 'plugins { id("io.sentry.android.gradle") }\n')
        self.assertViolation(self.repo.run(), "dependencies", "crash reporting")

    def test_resolved_dependencies(self):
        report_file = self.repo.write("deps.txt", """\
            releaseRuntimeClasspath - Runtime classpath of '/release'.
            +--- project :shared:ui
            |    +--- androidx.core:core-ktx:1.19.1
            |    \\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0 -> 1.11.0 (*)
            \\--- com.google.android.gms:play-services-wearable:20.0.1
            """)
        self.assertEqual([], self.repo.run(resolved={"apps/wear": report_file}).violations)
        self.repo.replace("deps.txt", "androidx.core:core-ktx:1.19.1", "io.sentry:sentry-android:8.0.0")
        self.assertViolation(self.repo.run(resolved={"apps/wear": report_file}), "dependencies",
                             "io.sentry:sentry-android")

    def test_empty_dependency_report_fails(self):
        report_file = self.repo.write("deps.txt", "BUILD FAILED\n")
        self.assertViolation(self.repo.run(resolved={"apps/android": report_file}), "dependencies", "no dependencies")


class IosTest(FixtureTestCase):
    def test_unexpected_info_key_fails(self):
        self.repo.replace("apps/ios/project.yml", "        WKApplication: true",
                          "        WKApplication: true\n        NSLocationWhenInUseUsageDescription: x")
        self.assertViolation(self.repo.run(), "ios", "NSLocationWhenInUseUsageDescription of WatchApp")

    def test_app_transport_security_exception_fails(self):
        self.repo.replace("apps/ios/project.yml", "        UIBackgroundModes: [processing]",
                          "        UIBackgroundModes: [processing]\n        NSAppTransportSecurity:\n"
                          "          NSAllowsArbitraryLoads: true")
        self.assertViolation(self.repo.run(), "ios", "NSAppTransportSecurity")

    def test_agent_access_info_keys_and_bonjour_services(self):
        # #185: the camera, local network and the bridge's Bonjour type are allowed; other types are not.
        self.repo.replace("apps/ios/project.yml", "        UIBackgroundModes: [processing]",
                          "        UIBackgroundModes: [processing]\n        NSCameraUsageDescription: QR\n"
                          "        NSLocalNetworkUsageDescription: PC\n        NSBonjourServices: [_watchaudio-mcp._tcp]")
        self.assertEqual([], [str(v) for v in self.repo.run().violations])
        self.repo.replace("apps/ios/project.yml", "[_watchaudio-mcp._tcp]", "[_watchaudio-mcp._tcp, _airplay._tcp]")
        self.assertViolation(self.repo.run(), "ios", "NSBonjourServices '_airplay._tcp' of iosApp")

    def test_unexpected_background_mode_fails(self):
        self.repo.replace("apps/ios/project.yml", "          - audio", "          - audio\n          - location")
        self.assertViolation(self.repo.run(), "ios", "'location' of WatchApp")

    def test_entitlements_in_project_fail(self):
        self.repo.replace("apps/ios/project.yml", "  WatchApp:\n",
                          "  WatchApp:\n    entitlements:\n      path: Watch/WatchApp.entitlements\n"
                          "      properties:\n        com.apple.developer.icloud-services: [CloudDocuments]\n")
        self.assertViolation(self.repo.run(), "ios", "com.apple.developer.icloud-services")

    def test_committed_entitlements_file_fails(self):
        self.repo.write("apps/ios/App/iosApp.entitlements", """\
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
                <key>com.apple.developer.ubiquity-kvstore-identifier</key><string>x</string>
            </dict></plist>
            """)
        self.assertViolation(self.repo.run(), "ios", "ubiquity-kvstore-identifier")

    def test_app_target_without_privacy_manifest_fails(self):
        self.repo.replace("apps/ios/project.yml", "    sources:\n      - path: Privacy/Watch/PrivacyInfo.xcprivacy\n"
                          "        buildPhase: resources\n", "")
        self.assertViolation(self.repo.run(), "ios", "target WatchApp must list one PrivacyInfo.xcprivacy")

    def test_privacy_manifest_must_be_a_resource_that_exists(self):
        self.repo.replace("apps/ios/project.yml", "Privacy/Watch/PrivacyInfo.xcprivacy\n        buildPhase: resources",
                          "Privacy/Watch/PrivacyInfo.xcprivacy\n        buildPhase: sources")
        self.assertViolation(self.repo.run(), "ios", "must use buildPhase: resources")
        (self.repo.root / "apps/ios/Privacy/App/PrivacyInfo.xcprivacy").unlink()
        self.assertViolation(self.repo.run(), "ios", "Privacy/App/PrivacyInfo.xcprivacy of iosApp does not exist")

    def test_privacy_manifest_with_tracking_fails(self):
        self.repo.replace("apps/ios/Privacy/App/PrivacyInfo.xcprivacy", "<key>NSPrivacyTracking</key>\n\t<false/>",
                          "<key>NSPrivacyTracking</key>\n\t<true/>")
        self.assertViolation(self.repo.run(), "ios", "NSPrivacyTracking must be false")

    def test_privacy_manifest_with_collected_data_fails(self):
        self.repo.replace("apps/ios/Privacy/App/PrivacyInfo.xcprivacy", "<key>NSPrivacyCollectedDataTypes</key>\n\t<array/>",
                          "<key>NSPrivacyCollectedDataTypes</key>\n\t<array><dict><key>NSPrivacyCollectedDataType</key>"
                          "<string>NSPrivacyCollectedDataTypeAudioData</string></dict></array>")
        self.assertViolation(self.repo.run(), "ios", "NSPrivacyCollectedDataTypes must be empty")

    def test_privacy_manifest_reason_outside_the_allow_list_fails(self):
        self.repo.replace("apps/ios/Privacy/Watch/PrivacyInfo.xcprivacy", "<string>CA92.1</string>",
                          "<string>AC6B.1</string>")
        self.assertViolation(self.repo.run(), "ios", "reason AC6B.1 for NSPrivacyAccessedAPICategoryUserDefaults")
        self.repo.replace("apps/ios/Privacy/Watch/PrivacyInfo.xcprivacy", "CategoryUserDefaults", "CategoryActiveKeyboards")
        self.assertViolation(self.repo.run(), "ios", "NSPrivacyAccessedAPICategoryActiveKeyboards is not in policy")

    def test_real_privacy_manifests_pass(self):
        report = check.Report()
        for path in sorted((REPO_ROOT / "apps/ios").rglob("*.xcprivacy")):
            check._check_privacy_manifest(path, str(path), report)
        self.assertEqual([], report.violations)
        self.assertEqual(3, len(list((REPO_ROOT / "apps/ios").rglob("PrivacyInfo.xcprivacy"))))


BRIDGE_PACKAGE = """\
{"name": "watch-audio-mcp", "dependencies": {"@modelcontextprotocol/server": "2.2.0", "multicast-dns": "7.2.5",
 "uqr": "0.1.3"}, "devDependencies": {"typescript": "6.0.3"}}
"""

BRIDGE_LOCK = """\
{"lockfileVersion": 3, "packages": {"": {}, "node_modules/@modelcontextprotocol/server": {}, "node_modules/zod": {},
 "node_modules/multicast-dns": {}, "node_modules/uqr": {}, "node_modules/typescript": {"dev": true}}}
"""


class AgentBridgeTest(FixtureTestCase):
    """tools/agent-bridge (#183): the CLI on the PC talks only to the paired phone."""

    def setUp(self):
        super().setUp()
        self.repo.write("tools/agent-bridge/src/bridge.ts", "import { createServer } from 'node:tls';\n")
        self.repo.write("tools/agent-bridge/src/pairing.ts", "import { createServer } from 'node:tls';\n")
        self.repo.write("tools/agent-bridge/src/mdns.ts", "import multicastDns from 'multicast-dns';\n")
        self.repo.write("tools/agent-bridge/src/cli.ts", "// talks to nothing: https://example.org in a comment is fine\n")
        self.repo.write("tools/agent-bridge/test/x.test.ts", "import { connect } from 'node:tls'; connect({});\n")
        self.repo.write("tools/agent-bridge/package.json", BRIDGE_PACKAGE)
        self.repo.write("tools/agent-bridge/package-lock.json", BRIDGE_LOCK)

    def test_fixture_passes_and_lists_the_sites(self):
        report = self.repo.run()
        self.assertEqual([], [str(v) for v in report.violations])
        self.assertEqual({"tls", "mdns"}, {api for _, _, api, _ in report.bridge_sites})

    def test_outbound_connection_fails(self):
        self.repo.write("tools/agent-bridge/src/bridge.ts", "import { createServer, connect } from 'node:tls';\nconnect({ port: 1 });\n")
        self.assertViolation(self.repo.run(), "agent-bridge", "outbound TCP / TLS connection")

    def test_http_and_fetch_fail(self):
        self.repo.write("tools/agent-bridge/src/cli.ts", "import https from 'node:https';\nawait fetch(u);\n")
        report = self.repo.run()
        self.assertViolation(report, "agent-bridge", "HTTP client / server modules")
        self.assertViolation(report, "agent-bridge", "fetch")

    def test_url_fails(self):
        self.repo.write("tools/agent-bridge/src/cli.ts", "const u = 'https://telemetry.example.org/v1';\n")
        self.assertViolation(self.repo.run(), "agent-bridge", "URL https://telemetry.example.org/v1")

    def test_tls_outside_the_allowed_files_fails(self):
        self.repo.write("tools/agent-bridge/src/cli.ts", "import tls from 'tls';\n")
        self.assertViolation(self.repo.run(), "agent-bridge", "only allowed in files")

    def test_unlisted_dependency_fails(self):
        self.repo.replace("tools/agent-bridge/package.json", '"uqr": "0.1.3"', '"uqr": "0.1.3", "posthog-node": "1"')
        self.repo.replace("tools/agent-bridge/package-lock.json", '"node_modules/uqr": {}',
                          '"node_modules/uqr": {}, "node_modules/posthog-node": {}, "node_modules/x": {"hasInstallScript": true}')
        report = self.repo.run()
        self.assertViolation(report, "agent-bridge", "dependency posthog-node is not in")
        self.assertViolation(report, "agent-bridge", "runtime package posthog-node is not in")
        self.assertViolation(report, "agent-bridge", "x has an install script")

    def test_stale_entries_fail(self):
        self.repo.write("tools/agent-bridge/src/mdns.ts", "export {};\n")
        self.assertViolation(self.repo.run(), "agent-bridge", "stale allow-list entry: mdns")


class ParserTest(unittest.TestCase):
    def test_strip_comments_keeps_strings_and_lines(self):
        code = 'val a = "http://x" // c\n/* b\n /* n */ */ val d = 1\nval e = """//not"""'
        stripped = check.strip_comments(code)
        self.assertEqual(code.count("\n"), stripped.count("\n"))
        self.assertIn('"http://x"', stripped)
        self.assertNotIn("c", stripped.split("\n")[0].replace('"http://x"', ""))
        self.assertIn("val d = 1", stripped)
        self.assertIn('"""//not"""', stripped)

    def test_simple_yaml(self):
        data = check.parse_simple_yaml(PROJECT_YML)
        ios = data["targets"]["iosApp"]
        self.assertEqual(["iosApp", "Shared"], ios["sources"][:2])
        self.assertEqual({"path": "Privacy/App/PrivacyInfo.xcprivacy", "buildPhase": "resources"}, ios["sources"][2])
        self.assertEqual({"CFBundleDisplayName": "watch-audio", "UIBackgroundModes": ["processing"]},
                         ios["info"]["properties"])
        self.assertEqual("Compile", ios["preBuildScripts"][0]["name"])
        self.assertEqual(["audio"], data["targets"]["WatchApp"]["info"]["properties"]["UIBackgroundModes"])

    def test_real_project_yml(self):
        data = check.parse_simple_yaml((REPO_ROOT / "apps/ios/project.yml").read_text(encoding="utf-8"))
        self.assertLessEqual({"iosApp", "WatchApp", "ProtocolTests"}, set(data["targets"]))
        self.assertEqual(["audio"], data["targets"]["WatchApp"]["info"]["properties"]["UIBackgroundModes"])


if __name__ == "__main__":
    unittest.main()
