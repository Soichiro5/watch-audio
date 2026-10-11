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

MODEL_DOWNLOADER = "androidApp/src/main/kotlin/app/watchaudio/android/asr/ModelDownloader.kt"
MODEL_MANIFEST = "androidApp/src/main/kotlin/app/watchaudio/android/asr/ModelManifest.kt"

PROJECT_YML = """\
name: WatchAudio
targets:
  iosApp:
    type: application
    sources: [iosApp, Shared]
    info:
      path: iosApp/Info.plist
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
    info:
      path: WatchApp/Info.plist
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
        self.write("androidApp/src/main/AndroidManifest.xml", PHONE_MANIFEST)
        self.write("androidApp/src/main/res/xml/data_extraction_rules.xml", PHONE_RULES)
        self.write("androidApp/src/main/res/xml/backup_rules.xml", PHONE_BACKUP)
        self.write("wearApp/src/main/AndroidManifest.xml", WEAR_MANIFEST)
        self.write("wearApp/src/main/res/xml/data_extraction_rules.xml", WEAR_RULES)
        self.write(MODEL_DOWNLOADER, """\
            import java.net.HttpURLConnection
            fun open(url: String) = URL(url).openConnection() as HttpURLConnection
            """)
        self.write(MODEL_MANIFEST, """\
            const val HF = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2/resolve/abc"
            const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
            """)
        # Test code is not shipped: a local fake server is fine there.
        self.write("androidApp/src/test/kotlin/DownloaderTest.kt", """\
            val server = java.net.ServerSocket(0)
            val url = "http://127.0.0.1:${server.localPort}/model.onnx"
            """)
        self.write("wearApp/src/main/kotlin/Uploader.kt", """\
            // DataClient is never used: items may sync through the cloud (see https://example.com).
            /* PutDataRequest /* nested */ HttpURLConnection */
            val channel = Wearable.getChannelClient(context)
            val path = "/watch-audio/chunk/" // "https://example.com" in a comment after a string
            """)
        self.write("iosApp/WatchApp/Outbox.swift", """\
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
        self.write("androidApp/build.gradle.kts", """\
            dependencies {
                // firebase must never be added
                implementation(libs.kotlinx.coroutines.core)
            }
            """)
        self.write("spikes/bench/build.gradle.kts", 'dependencies { implementation("com.squareup.okhttp3:okhttp:4") }\n')
        self.write("iosApp/project.yml", PROJECT_YML)

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
        # The model download is the only network call site.
        self.assertEqual({MODEL_DOWNLOADER}, {site[0] for site in report.network_sites})


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
        self.assertEqual({(MODEL_DOWNLOADER, "URLConnection"), (MODEL_DOWNLOADER, "openConnection")},
                         {(path, api) for path, _, api, _ in report.network_sites})

    def test_stale_allow_list_entries_fail(self):
        # The list of network call sites must stay accurate (docs/02 §7.2).
        self.repo.write(MODEL_DOWNLOADER, "import java.net.HttpURLConnection\n")
        self.repo.replace(MODEL_MANIFEST, "huggingface.co", "example.org")
        report = self.repo.run()
        self.assertViolation(report, "network", "stale allow-list entry: openConnection")
        self.assertViolation(report, "network", "no URL starts with https://huggingface.co")


class ManifestTest(FixtureTestCase):
    def test_internet_on_the_watch_fails(self):
        self.repo.replace("wearApp/src/main/AndroidManifest.xml", "<application",
                          '<uses-permission android:name="android.permission.INTERNET" />\n<application')
        self.assertViolation(self.repo.run(), "permissions", "android.permission.INTERNET is not in the wearApp")

    def test_unexpected_phone_permission_in_any_source_set_fails(self):
        self.repo.write("androidApp/src/debug/AndroidManifest.xml", """\
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="com.google.android.gms.permission.AD_ID" />
            </manifest>
            """)
        self.assertViolation(self.repo.run(), "permissions", "AD_ID")

    def test_allow_backup_true_fails(self):
        self.repo.replace("androidApp/src/main/AndroidManifest.xml", 'allowBackup="false"', 'allowBackup="true"')
        self.assertViolation(self.repo.run(), "backup", "allowBackup")

    def test_missing_data_extraction_rules_fails(self):
        self.repo.replace("wearApp/src/main/AndroidManifest.xml",
                          'android:dataExtractionRules="@xml/data_extraction_rules"', "")
        self.assertViolation(self.repo.run(), "backup", "dataExtractionRules")

    def test_rules_must_exclude_every_domain(self):
        self.repo.replace("androidApp/src/main/res/xml/data_extraction_rules.xml",
                          '<exclude domain="database" path="." />', "")
        self.assertViolation(self.repo.run(), "backup", "<cloud-backup> must exclude whole domains: database")

    def test_partial_path_exclusion_does_not_count(self):
        self.repo.replace("androidApp/src/main/res/xml/backup_rules.xml",
                          '<exclude domain="file" path="." />', '<exclude domain="file" path="cache" />')
        self.assertViolation(self.repo.run(), "backup", "<full-backup-content> must exclude whole domains: file")

    def test_watch_device_transfer_must_be_excluded(self):
        self.repo.write("wearApp/src/main/res/xml/data_extraction_rules.xml", PHONE_RULES)
        self.assertViolation(self.repo.run(), "backup", "<device-transfer> section missing")

    def test_include_fails(self):
        self.repo.replace("androidApp/src/main/res/xml/data_extraction_rules.xml", "</cloud-backup>",
                          '<include domain="file" path="audio" /></cloud-backup>')
        self.assertViolation(self.repo.run(), "backup", "<include>")

    def test_cleartext_traffic_fails(self):
        self.repo.replace("androidApp/src/main/AndroidManifest.xml", "<application",
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
        self.assertEqual([], self.repo.run(merged={"wearApp": merged}).violations)
        self.repo.replace("merged.xml", "WAKE_LOCK", "INTERNET")
        self.assertViolation(self.repo.run(merged={"wearApp": merged}), "permissions",
                             "merged manifest of wearApp requests android.permission.INTERNET")


class SourceTest(FixtureTestCase):
    def test_data_client_on_the_watch_fails(self):
        self.repo.write("wearApp/src/main/kotlin/Sync.kt", "val client = Wearable.getDataClient(context)\n")
        self.assertViolation(self.repo.run(), "network", "getDataClient")

    def test_network_api_outside_the_allow_list_fails(self):
        self.repo.write("shared/core-pipeline/src/commonMain/kotlin/Upload.kt", """\
            fun upload() {
                val c = URL(endpoint).openConnection()
            }
            """)
        report = self.repo.run()
        self.assertViolation(report, "network", "Upload.kt:2: .openConnection(")

    def test_swift_url_session_fails(self):
        self.repo.write("iosApp/iosApp/Sync.swift", "let task = URLSession.shared.dataTask(with: url)\n")
        self.assertViolation(self.repo.run(), "network", "URLSession")

    def test_server_speech_recognition_fails(self):
        self.repo.write("iosApp/iosApp/Asr.swift", "let r = SFSpeechURLRecognitionRequest(url: file)\n")
        self.assertViolation(self.repo.run(), "network", "SFSpeechURLRecognitionRequest")

    def test_weak_file_protection_fails(self):
        self.repo.write("iosApp/WatchApp/Files.swift", "try data.write(to: url, options: .noFileProtection)\n")
        self.assertViolation(self.repo.run(), "network", ".noFileProtection")

    def test_unknown_url_fails(self):
        self.repo.write("composeApp/src/commonMain/kotlin/Help.kt", 'const val HELP = "https://example.com/upload"\n')
        self.assertViolation(self.repo.run(), "network", "URL https://example.com/upload")

    def test_allowed_api_in_another_file_fails(self):
        self.repo.write("androidApp/src/main/kotlin/Other.kt", "import java.net.HttpURLConnection\n")
        self.assertViolation(self.repo.run(), "network", "Other.kt:1: HttpURLConnection")


class DependencyTest(FixtureTestCase):
    def test_declared_network_client_fails(self):
        self.repo.replace("androidApp/build.gradle.kts", "implementation(libs.kotlinx.coroutines.core)",
                          'implementation("com.squareup.okhttp3:okhttp:4.12.0")')
        self.assertViolation(self.repo.run(), "dependencies", "network client")

    def test_catalog_analytics_sdk_fails(self):
        self.repo.replace("gradle/libs.versions.toml", "[libraries]",
                          '[libraries]\nfirebase-bom = { module = "com.google.firebase:firebase-bom", version = "34.0.0" }')
        self.assertViolation(self.repo.run(), "dependencies", "libs.versions.toml:5")

    def test_crash_reporter_plugin_fails(self):
        self.repo.write("wearApp/build.gradle.kts", 'plugins { id("io.sentry.android.gradle") }\n')
        self.assertViolation(self.repo.run(), "dependencies", "crash reporting")

    def test_resolved_dependencies(self):
        report_file = self.repo.write("deps.txt", """\
            releaseRuntimeClasspath - Runtime classpath of '/release'.
            +--- project :composeApp
            |    +--- androidx.core:core-ktx:1.19.1
            |    \\--- org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0 -> 1.11.0 (*)
            \\--- com.google.android.gms:play-services-wearable:20.0.1
            """)
        self.assertEqual([], self.repo.run(resolved={"wearApp": report_file}).violations)
        self.repo.replace("deps.txt", "androidx.core:core-ktx:1.19.1", "io.sentry:sentry-android:8.0.0")
        self.assertViolation(self.repo.run(resolved={"wearApp": report_file}), "dependencies",
                             "io.sentry:sentry-android")

    def test_empty_dependency_report_fails(self):
        report_file = self.repo.write("deps.txt", "BUILD FAILED\n")
        self.assertViolation(self.repo.run(resolved={"androidApp": report_file}), "dependencies", "no dependencies")


class IosTest(FixtureTestCase):
    def test_unexpected_info_key_fails(self):
        self.repo.replace("iosApp/project.yml", "        WKApplication: true",
                          "        WKApplication: true\n        NSLocationWhenInUseUsageDescription: x")
        self.assertViolation(self.repo.run(), "ios", "NSLocationWhenInUseUsageDescription of WatchApp")

    def test_app_transport_security_exception_fails(self):
        self.repo.replace("iosApp/project.yml", "        UIBackgroundModes: [processing]",
                          "        UIBackgroundModes: [processing]\n        NSAppTransportSecurity:\n"
                          "          NSAllowsArbitraryLoads: true")
        self.assertViolation(self.repo.run(), "ios", "NSAppTransportSecurity")

    def test_unexpected_background_mode_fails(self):
        self.repo.replace("iosApp/project.yml", "          - audio", "          - audio\n          - location")
        self.assertViolation(self.repo.run(), "ios", "'location' of WatchApp")

    def test_entitlements_in_project_fail(self):
        self.repo.replace("iosApp/project.yml", "  WatchApp:\n",
                          "  WatchApp:\n    entitlements:\n      path: WatchApp/WatchApp.entitlements\n"
                          "      properties:\n        com.apple.developer.icloud-services: [CloudDocuments]\n")
        self.assertViolation(self.repo.run(), "ios", "com.apple.developer.icloud-services")

    def test_committed_entitlements_file_fails(self):
        self.repo.write("iosApp/iosApp/iosApp.entitlements", """\
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
                <key>com.apple.developer.ubiquity-kvstore-identifier</key><string>x</string>
            </dict></plist>
            """)
        self.assertViolation(self.repo.run(), "ios", "ubiquity-kvstore-identifier")


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
        self.assertEqual(["iosApp", "Shared"], ios["sources"])
        self.assertEqual({"CFBundleDisplayName": "watch-audio", "UIBackgroundModes": ["processing"]},
                         ios["info"]["properties"])
        self.assertEqual("Compile", ios["preBuildScripts"][0]["name"])
        self.assertEqual(["audio"], data["targets"]["WatchApp"]["info"]["properties"]["UIBackgroundModes"])

    def test_real_project_yml(self):
        data = check.parse_simple_yaml((REPO_ROOT / "iosApp/project.yml").read_text(encoding="utf-8"))
        self.assertLessEqual({"iosApp", "WatchApp", "ProtocolTests"}, set(data["targets"]))
        self.assertEqual(["audio"], data["targets"]["WatchApp"]["info"]["properties"]["UIBackgroundModes"])


if __name__ == "__main__":
    unittest.main()
