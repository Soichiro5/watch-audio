"""Tests for check_links.py: the real repository passes, and each kind of broken link fails.

Run: python3 -m unittest discover -s tools/check-links -v
"""

from __future__ import annotations

import tempfile
import textwrap
import unittest
from pathlib import Path

import check_links

REPO_ROOT = Path(__file__).resolve().parents[2]


class SlugTest(unittest.TestCase):
    def test_github_anchors(self):
        self.assertEqual("63-どの変更でどのワークフローが動くか92", check_links.github_slug("6.3 どの変更でどのワークフローが動くか（#92）"))
        self.assertEqual("7-セキュリティプライバシー", check_links.github_slug("7. セキュリティ・プライバシー"))
        self.assertEqual("local-only-チェックtoolsprivacy-check76",
                         check_links.github_slug("Local-only チェック（`tools/privacy-check`、#76）"))
        self.assertEqual("repo-map", check_links.github_slug("Repo [map](x.md)"))


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.write("settings.gradle.kts", 'include(":shared:db")\ninclude(":apps:android")\n')
        self.write("shared/db/Schema.kt", "")
        self.write("docs/dev/guide.md", """\
            # Guide

            ## 1. 準備（#12）

            ## Setup
            ## Setup

            <a id="custom"></a>
            """)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel: str, text: str) -> Path:
        path = self.root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text), encoding="utf-8")
        return path

    def problems(self, rel: str, text: str) -> list[str]:
        source = self.write(rel, text)
        return [p.message for p in check_links.run(self.root, [source])]

    def test_valid_links_pass(self):
        self.assertEqual([], self.problems("README.md", """\
            [guide](docs/dev/guide.md) [anchor](docs/dev/guide.md#1-準備12) [dir](shared/db/)
            [repeat](docs/dev/guide.md#setup-1) [html](docs/dev/guide.md#custom) [web](https://example.com/x)
            [self](#readme) ![img](shared/db/Schema.kt "title")

            # README
            [ref]: docs/dev/guide.md#setup
            """))

    def test_missing_file_and_anchor_fail(self):
        problems = self.problems("docs/index.md", """\
            [gone](dev/missing.md) [bad anchor](dev/guide.md#2-後片付け) [up](../../outside.md)
            <a href="dev/nowhere.md">x</a>
            """)
        self.assertEqual(4, len(problems), problems)
        self.assertTrue(problems[0].startswith("missing link target"))
        self.assertTrue(problems[1].startswith("missing anchor"))
        self.assertTrue(problems[2].startswith("link leaves the repository"))

    def test_code_is_not_a_link(self):
        self.assertEqual([], self.problems("README.md", """\
            `[not](a/link.md)`

            ```
            [not](a/link.md)
            ```
            """))

    def test_claude_md_paths(self):
        problems = self.problems("CLAUDE.md", """\
            Schema lives in `shared/db/.../Schema.kt`; the old `shared/core-db/Schema.kt` is gone.

            | Path | What |
            | --- | --- |
            | `shared/db` | ok |
            | `composeApp` | moved |

            ```bash
            ./gradlew :apps:android:assembleDebug :androidApp:lintDebug   # comment: androidApp/
            cd apps/ios && xcodegen generate
            ```
            """)
        self.assertEqual([
            "missing repository path: shared/core-db/Schema.kt",
            "missing repository path: composeApp",
            "Gradle project not in settings.gradle.kts: :androidApp:lintDebug",
            "missing directory: cd apps/ios",
        ], problems)

    def test_repository_passes(self):
        problems = check_links.run(REPO_ROOT)
        self.assertEqual([], [str(p) for p in problems])


if __name__ == "__main__":
    unittest.main()
