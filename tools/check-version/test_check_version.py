"""Tests for check_version.py: the real repository passes, and each kind of mismatch fails.

Run: python3 -m unittest discover -s tools/check-version -v
"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_version

REPO_ROOT = Path(__file__).resolve().parents[2]

PROPERTIES = """\
# comment
org.gradle.caching=true
watchaudio.versionName=0.2.0
watchaudio.versionCode=2
"""

PROJECT_YML = """\
name: WatchAudio
settings:
  base:
    SWIFT_VERSION: "6.0"
    MARKETING_VERSION: "0.2.0"
    CURRENT_PROJECT_VERSION: "2"
targets:
  iosApp:
    info:
      properties:
        CFBundleDisplayName: watch-audio
"""

APP_BUILD = """\
plugins {
    id("watchaudio.android.app")
}

android {
    namespace = "app.watchaudio.android"
    defaultConfig {
        minSdk = 29
    }
}
"""


class CheckVersionTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.write("gradle.properties", PROPERTIES)
        self.write("apps/ios/project.yml", PROJECT_YML)
        for build_file in check_version.ANDROID_BUILD_FILES:
            self.write(build_file, APP_BUILD)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def replace(self, relative: str, old: str, new: str) -> None:
        path = self.root / relative
        text = path.read_text(encoding="utf-8")
        self.assertIn(old, text)
        path.write_text(text.replace(old, new), encoding="utf-8")

    def assertFails(self, *fragments: str) -> None:
        errors = check_version.check(self.root)
        self.assertTrue(errors, "expected the check to fail")
        joined = "\n".join(errors)
        for fragment in fragments:
            self.assertIn(fragment, joined)

    def test_repository_passes(self) -> None:
        self.assertEqual(check_version.check(REPO_ROOT), [])

    def test_consistent_fixture_passes(self) -> None:
        self.assertEqual(check_version.check(self.root), [])
        self.assertEqual(check_version.main(["--root", str(self.root)]), 0)

    def test_unquoted_and_commented_values_pass(self) -> None:
        self.replace("apps/ios/project.yml", 'MARKETING_VERSION: "0.2.0"', "MARKETING_VERSION: 0.2.0  # bump")
        self.replace("apps/ios/project.yml", 'CURRENT_PROJECT_VERSION: "2"', "CURRENT_PROJECT_VERSION: '2'")
        self.assertEqual(check_version.check(self.root), [])

    def test_ios_marketing_version_mismatch_fails(self) -> None:
        self.replace("apps/ios/project.yml", 'MARKETING_VERSION: "0.2.0"', 'MARKETING_VERSION: "0.1.0"')
        self.assertFails("apps/ios/project.yml:5: MARKETING_VERSION is '0.1.0'", "watchaudio.versionName=0.2.0")
        self.assertEqual(check_version.main(["--root", str(self.root)]), 1)

    def test_ios_build_number_mismatch_fails(self) -> None:
        self.replace("apps/ios/project.yml", 'CURRENT_PROJECT_VERSION: "2"', 'CURRENT_PROJECT_VERSION: "1"')
        self.assertFails("CURRENT_PROJECT_VERSION is '1'", "watchaudio.versionCode=2")

    def test_target_level_override_fails(self) -> None:
        self.replace("apps/ios/project.yml", "    info:\n",
                     "    settings:\n      base:\n        MARKETING_VERSION: 0.1.9\n    info:\n")
        self.assertFails("MARKETING_VERSION is '0.1.9'")

    def test_missing_ios_setting_fails(self) -> None:
        self.replace("apps/ios/project.yml", '    CURRENT_PROJECT_VERSION: "2"\n', "")
        self.assertFails("CURRENT_PROJECT_VERSION is not set")

    def test_literal_bundle_version_fails(self) -> None:
        self.replace("apps/ios/project.yml", "CFBundleDisplayName: watch-audio",
                     "CFBundleDisplayName: watch-audio\n        CFBundleShortVersionString: 0.2.0")
        self.assertFails("CFBundleShortVersionString must be $(MARKETING_VERSION)")

    def test_bundle_version_from_build_setting_passes(self) -> None:
        self.replace("apps/ios/project.yml", "CFBundleDisplayName: watch-audio",
                     "CFBundleDisplayName: watch-audio\n        CFBundleVersion: $(CURRENT_PROJECT_VERSION)")
        self.assertEqual(check_version.check(self.root), [])

    def test_missing_properties_fail(self) -> None:
        self.write("gradle.properties", "org.gradle.caching=true\n")
        self.assertFails("watchaudio.versionName is missing", "watchaudio.versionCode is missing")

    def test_malformed_values_fail(self) -> None:
        self.replace("gradle.properties", "versionName=0.2.0", "versionName=0.2.0-beta")
        self.replace("gradle.properties", "versionCode=2", "versionCode=0")
        self.assertFails("watchaudio.versionName=0.2.0-beta is not", "watchaudio.versionCode=0 is not")

    def test_app_setting_its_own_version_fails(self) -> None:
        self.replace("apps/wear/build.gradle.kts", "        minSdk = 29\n",
                     '        minSdk = 29\n        versionName = "0.1.0"\n')
        self.assertFails('apps/wear/build.gradle.kts:9: sets its own version (versionName = "0.1.0")')


if __name__ == "__main__":
    unittest.main()
