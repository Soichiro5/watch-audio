#!/usr/bin/env python3
"""Path rewrites for the folder restructure (#49); run by migrate.sh after (or, with --dry-run,
instead of) the moves.

Works on the tracked files in either layout: each file's new path and old path are derived from
MOVES, so the same run gives the same result before the moves (--dry-run) and after them, and a
second run changes nothing (every rule matches old paths only).

1. File-specific rewrites (FILE_RULES): structure blocks (CLAUDE.md Repo map, README, docs/02 tree),
   XcodeGen paths relative to apps/ios, Gradle settings, the CI matrix, depth-dependent code.
2. Markdown links: each relative link is resolved from the file's old location and pointed at the
   target's new location (links already valid in the new layout are left alone).
3. Ordered regex rules (RULES) for paths written in code, comments, CI and prose.
4. Workflow path filters: duplicates and entries covered by a parent `dir/**` are dropped.

Finally lists remaining mentions of old names for a manual look (bare `iosApp` / `WatchApp` are
XcodeGen target and scheme names, which stay).
"""

from __future__ import annotations

import argparse
import difflib
import posixpath
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "check-links"))
import check_links  # noqa: E402  (same Markdown link grammar as the checker)

# (old, new) path prefixes, most specific first. Mirrors the moves in migrate.sh.
MOVES = [
    ("iosApp/iosApp", "apps/ios/App"),
    ("iosApp/WatchApp", "apps/ios/Watch"),
    ("iosApp/Shared", "apps/ios/SharedSwift"),
    *[(f"iosApp/{t}", f"apps/ios/Tests/{t}")
      for t in ("ProtocolTests", "WatchLogicTests", "SpeechTests", "PlaybackTests", "TestSupport")],
    ("iosApp", "apps/ios"),
    ("androidApp", "apps/android"),
    ("wearApp", "apps/wear"),
    ("composeApp", "shared/ui"),
    *[(f"shared/core-{m}", f"shared/{m}") for m in ("protocol", "db", "pipeline", "platform", "model")],
    ("spikes", "research"),
    ("docs/01-research.md", "docs/product/01-research.md"),
    ("docs/03-roadmap.md", "docs/product/03-roadmap.md"),
    ("docs/02-architecture.md", "docs/architecture/02-architecture.md"),
    ("docs/04-dev-environment.md", "docs/dev/04-dev-environment.md"),
    ("docs/test-audio.md", "docs/dev/test-audio.md"),
    ("docs/impl", "docs/platforms"),
    ("docs/spikes", "docs/research"),
]
DELETED = ("shared/core-search", "shared/core-ai")

# Files the rewrites never touch: this tool describes the old layout on purpose, and the link
# check's test fixtures use old paths as examples of broken ones.
EXCLUDED_PREFIXES = ("tools/restructure/", "tools/check-links/")
TEXT_SUFFIXES = {".md", ".kt", ".kts", ".swift", ".py", ".sh", ".yml", ".yaml", ".toml", ".pro", ".xml",
                 ".xcconfig", ".html", ".properties"}
TEXT_NAMES = {".gitignore", "CLAUDE.md", "gradlew"}

TEST_DIRS = "ProtocolTests|WatchLogicTests|SpeechTests|PlaybackTests|TestSupport"
SHARED_MODULES = "protocol|db|pipeline|platform|model"
NOT_IN_PATH = r"(?<![\w/.-])"  # not inside a longer path or word


def _prefix_map(path: str, pairs) -> str:
    for old, new in pairs:
        if path == old or path.startswith(old + "/"):
            return new + path[len(old):]
    return path


def new_path_of(path: str) -> str:
    return _prefix_map(path, MOVES)


def old_path_of(path: str) -> str:
    return _prefix_map(path, [(new, old) for old, new in MOVES])


# --------------------------------------------------------------------------------------------
# 3. Ordered rules: (pattern, replacement). Earlier rules handle the specific forms.

RULES: list[tuple[str, object]] = [
    # iOS: folders inside the XcodeGen project, written from the repository root.
    (NOT_IN_PATH + r"iosApp/iosApp(?!\w)", "apps/ios/App"),
    (NOT_IN_PATH + r"iosApp/WatchApp(?!\w)", "apps/ios/Watch"),
    (NOT_IN_PATH + r"iosApp/Shared(?!\w)", "apps/ios/SharedSwift"),
    (NOT_IN_PATH + rf"iosApp/({TEST_DIRS})(?!\w)", r"apps/ios/Tests/\1"),
    (NOT_IN_PATH + r"iosApp/", "apps/ios/"),
    (r"(\bcd iosApp\b[^\n]*?&& cd )\.\.(?![./\w])", r"\1../.."),
    (r"\bcd iosApp(?![\w/])", "cd apps/ios"),
    # ... and relative to the project (docs about the iOS code).
    (NOT_IN_PATH + r"WatchApp/", "Watch/"),
    (NOT_IN_PATH + r"Shared/(?=\w+\.swift)", "SharedSwift/"),
    (NOT_IN_PATH + rf"({TEST_DIRS})/", r"Tests/\1/"),
    # Android apps: APK and report names follow the Gradle project name (android, wear).
    (r"(?<![\w.-])androidApp-(debug|release)", r"android-\1"),
    (r"(?<![\w.-])wearApp-(debug|release)", r"wear-\1"),
    (r"\bdeps-androidApp\b", "deps-android"),
    (r"\bdeps-wearApp\b", "deps-wear"),
    # Kotlin `internal` names carry the module name (R8 keep rules, generated).
    (r"\$androidApp\b", "$android"),
    (r"\$wearApp\b", "$wear"),
    (NOT_IN_PATH + r":androidApp\b", ":apps:android"),
    (NOT_IN_PATH + r":wearApp\b", ":apps:wear"),
    (r"\bprojects\.androidApp\b", "projects.apps.android"),
    (r"\bprojects\.wearApp\b", "projects.apps.wear"),
    (NOT_IN_PATH + r"androidApp/", "apps/android/"),
    (NOT_IN_PATH + r"wearApp/", "apps/wear/"),
    (r"(?<![\w/.$-])androidApp(?![\w-])", "apps/android"),
    (r"(?<![\w/.$-])wearApp(?![\w-])", "apps/wear"),
    # Shared UI (the iOS framework keeps its name ComposeApp: case-sensitive rules).
    (NOT_IN_PATH + r":composeApp\b", ":shared:ui"),
    (r"\bprojects\.composeApp\b", "projects.shared.ui"),
    (NOT_IN_PATH + r"composeApp/", "shared/ui/"),
    (r"(?<![\w/.$-])composeApp(?![\w-])", "shared/ui"),
    # Shared KMP modules (Kotlin packages app.watchaudio.core.* stay).
    (rf"\bprojects\.shared\.core(Protocol|Db|Pipeline|Platform|Model)\b",
     lambda m: "projects.shared." + m.group(1).lower()),
    (rf":shared:core-({SHARED_MODULES})\b", r":shared:\1"),
    (rf"(?<![\w.-])shared/core-({SHARED_MODULES})\b", r"shared/\1"),
    (r"(?<![\w.-])shared/core-\*", "shared/*"),
    (rf"(?<![\w/.:-])core-({SHARED_MODULES})(?![\w-])", r"shared/\1"),
    # research/ and docs/
    (r"`research/\*\*`、`spikes/\*\*`", "`research/**`"),
    (NOT_IN_PATH + r"docs/spikes(?=/|\b)", "docs/research"),
    (NOT_IN_PATH + r"spikes/", "research/"),
    (NOT_IN_PATH + r"docs/impl(?=/|\b)", "docs/platforms"),
    (NOT_IN_PATH + r"docs/01-research\.md", "docs/product/01-research.md"),
    (NOT_IN_PATH + r"docs/03-roadmap\.md", "docs/product/03-roadmap.md"),
    (NOT_IN_PATH + r"docs/02-architecture\.md", "docs/architecture/02-architecture.md"),
    (NOT_IN_PATH + r"docs/04-dev-environment\.md", "docs/dev/04-dev-environment.md"),
    (NOT_IN_PATH + r"docs/test-audio\.md", "docs/dev/test-audio.md"),
    # Shorthand for the numbered docs in comments and prose ("docs/02 §7").
    (NOT_IN_PATH + r"docs/0([13])(?![\w-])", r"docs/product/0\1"),
    (NOT_IN_PATH + r"docs/02(?![\w-])", "docs/architecture/02"),
    (NOT_IN_PATH + r"docs/04(?![\w-])", "docs/dev/04"),
]
COMPILED_RULES = [(re.compile(p), r) for p, r in RULES]


def apply_rules(text: str) -> str:
    for pattern, replacement in COMPILED_RULES:
        text = pattern.sub(replacement, text)
    return text


# --------------------------------------------------------------------------------------------
# 1. File-specific rewrites, keyed by the file's new path (or a predicate).

class Notes:
    """Warnings for the person running the migration (a pattern was expected but not found)."""
    items: list[str] = []

    @classmethod
    def warn(cls, path: str, message: str) -> None:
        cls.items.append(f"{path}: {message}")


def replace_once(text: str, path: str, old: str, new: str, done_marker: str | None = None) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if done_marker is None or done_marker not in text:
        Notes.warn(path, f"expected text not found (edit by hand): {old.splitlines()[0][:80]}")
    return text


def sub_block(text: str, path: str, pattern: str, new: str, done_marker: str) -> str:
    """Replaces a block found by regex (DOTALL); warns when neither the block nor the new text is there."""
    match = re.search(pattern, text, flags=re.S | re.M)
    if match:
        return text[:match.start()] + new + text[match.end():]
    if done_marker not in text:
        Notes.warn(path, f"block not found (edit by hand): /{pattern[:60]}/")
    return text


def project_yml(text: str, path: str) -> str:
    """apps/ios/project.yml: source paths are relative to the project folder."""
    text = text.replace("sources: [iosApp, Shared]", "sources: [App, SharedSwift]")
    text = text.replace("sources: [WatchApp, Shared]", "sources: [Watch, SharedSwift]")
    text = re.sub(r"(path: )iosApp/", r"\1App/", text)
    text = re.sub(r"(path: )WatchApp/", r"\1Watch/", text)
    text = re.sub(r"(path: )Shared/", r"\1SharedSwift/", text)
    text = re.sub(r"^(\s+- )Shared\s*$", r"\1SharedSwift", text, flags=re.M)
    text = re.sub(rf"^(\s+- )({TEST_DIRS})\s*$", r"\1Tests/\2", text, flags=re.M)
    text = re.sub(rf"(path: )\.\./shared/core-({SHARED_MODULES})/", r"\1../../shared/\2/", text)
    text = text.replace("$(SRCROOT)/../composeApp/build/", "$(SRCROOT)/../../shared/ui/build/")
    text = text.replace('cd "$SRCROOT/.."\n', 'cd "$SRCROOT/../.."\n')
    text = text.replace("./gradlew :composeApp:embedAndSignAppleFrameworkForXcode",
                        "./gradlew :shared:ui:embedAndSignAppleFrameworkForXcode")
    return text


def settings_gradle(text: str, path: str) -> str:
    text = re.sub(r'^include\(":shared:core-(search|ai)"\)\n', "", text, flags=re.M)
    return text.replace("core-model/core-protocol also used by Wear OS", "model and protocol also used by Wear OS")


def gradle_build(text: str, path: str) -> str:
    return re.sub(r"^[ \t]*\w+\(projects\.shared\.core(Search|Ai)\)\n", "", text, flags=re.M)


def kmp_plugin(text: str, path: str) -> str:
    text = sub_block(
        text, path,
        r"^ \* The Android namespace is derived from the module path, e\.g\. `:shared:core-protocol`\n"
        r" \* becomes `app\.watchaudio\.core\.protocol` and `:composeApp` becomes `app\.watchaudio\.composeapp`\.\n",
        " * The Android namespace is derived from the module path and keeps the names from before the\n"
        " * folder restructure (#49), so R classes, merged manifests and R8 output do not change:\n"
        " * `:shared:protocol` becomes `app.watchaudio.core.protocol` and `:shared:ui` becomes\n"
        " * `app.watchaudio.composeapp`.\n",
        done_marker="keeps the names from before the")
    return sub_block(
        text, path,
        r'^(?P<indent>[ \t]*)namespace = "app\.watchaudio\." \+ path\.removePrefix\(":"\)\.removePrefix\("shared:"\)\s*\n'
        r"\s*\.replace\(':', '\.'\)\.replace\('-', '\.'\)\.lowercase\(\)\n",
        "                namespace = when (val module = path.removePrefix(\":\").removePrefix(\"shared:\")) {\n"
        "                    \"ui\" -> \"app.watchaudio.composeapp\"\n"
        "                    else -> \"app.watchaudio.core.\" + module.replace(':', '.').replace('-', '.').lowercase()\n"
        "                }\n",
        done_marker='"ui" -> "app.watchaudio.composeapp"')


def module_dir_tests(text: str, path: str) -> str:
    """Unit tests of the apps run in the module directory, now two levels below the root."""
    return text.replace('File("").absoluteFile.resolveSibling(', 'File("").absoluteFile.parentFile.resolveSibling(')


def swift_repository_root(text: str, path: str) -> str:
    """`#filePath` minus one component per directory level gives the repository root."""
    depth_new = len(path.split("/"))
    depth_old = len(old_path_of(path).split("/"))
    pattern = re.compile(r"(URL\(fileURLWithPath: #filePath\))((?:\s*\.deletingLastPathComponent\(\))+)")

    def fix(match: re.Match) -> str:
        steps = re.findall(r"\s*\.deletingLastPathComponent\(\)", match.group(2))
        if len(steps) == depth_new:
            return match.group(0)
        if len(steps) != depth_old:
            Notes.warn(path, f"#filePath goes up {len(steps)} levels, expected {depth_old}; check by hand")
            return match.group(0)
        if depth_new > len(steps):
            steps += [".deletingLastPathComponent()"] * (depth_new - len(steps))
        else:
            steps = steps[:depth_new]
        return match.group(1) + "".join(steps)

    return pattern.sub(fix, text)


def emulator_yml(text: str, path: str) -> str:
    """The matrix `module` was the directory, the Gradle project and the APK name at once."""
    def module(match: re.Match) -> str:
        indent, name = match.group(1), {"androidApp": "android", "wearApp": "wear"}[match.group(2)]
        return f"{indent}module: apps/{name}\n{indent}gradle-path: :apps:{name}\n{indent}apk: {name}"

    text = re.sub(r"^([ \t]+)module: (androidApp|wearApp)[ \t]*$", module, text, flags=re.M)
    text = text.replace(":${{ matrix.module }}:", "${{ matrix.gradle-path }}:")
    return text.replace("${{ matrix.module }}-", "${{ matrix.apk }}-")


def android_yml(text: str, path: str) -> str:
    text = text.replace("for app in androidApp wearApp; do", "for app in android wear; do")
    return re.sub(r"(?<!:apps):\$app:dependencies", ":apps:$app:dependencies", text)


def privacy_policy(text: str, path: str) -> str:
    def roots(match: re.Match) -> str:
        line = match.group(0).replace('"iosApp"', '"apps/ios"')
        return line.replace('"composeApp/src", ', "").replace('"shared/ui/src", ', "")
    return re.sub(r"^SOURCE_ROOTS = \(.*\)$", roots, text, flags=re.M)


def privacy_check(text: str, path: str) -> str:
    return text.replace('os.walk(root / "iosApp")', 'os.walk(root / "apps" / "ios")')


CLAUDE_REPO_MAP = """\
| Path | What |
| --- | --- |
| `shared/*` | KMP logic: `protocol` (manifest/ACK), `db` (bundled SQLite + FTS5), `pipeline` (Ingestor, TranscriptionPipeline, VAD settings), `platform` (expect/actual), `model` |
| `shared/ui` | Shared phone UI (Compose MP) + `AppServices` contract; `iosMain` has the iOS graph/bridge (framework `ComposeApp`) |
| `apps/android` / `apps/wear` | Android phone (receiver, sherpa-onnx ASR, WorkManager) / Wear OS recorder |
| `apps/ios` | XcodeGen project (`project.yml`): `App` (iOS host), `Watch` + `WatchWidget` (watchOS recorder, complication), `SharedSwift`, `Tests/*` |
| `tools/harness` | Device-free E2E on Linux: watch simulator → ingest → sherpa-onnx → SQLite |
| `tools/roadmap-view` | Builds the issue dashboard (`python3 tools/roadmap-view/build.py out.html`) |
| `tools/check-links` | Markdown link check (CI `links.yml`) |
| `research` | Python benchmarks from the spikes (ASR, embeddings) |
| `docs/` | `product` (research, roadmap), `architecture`, `platforms` (per platform), `dev` (environment, CI, test audio), `research` (spike reports) |
"""
CLAUDE_KNOWN_ROWS = {"`shared/core-*`", "`composeApp`", "`androidApp` / `wearApp`", "`iosApp`", "`tools/harness`",
                     "`tools/roadmap-view`", "`docs/`", "`tools/check-links`", "`research`"}


def claude_md(text: str, path: str) -> str:
    match = re.search(r"^\| Path \| What \|\n\| --- \| --- \|\n(?:\|.*\n)*", text, flags=re.M)
    if not match:
        Notes.warn(path, "Repo map table not found (edit by hand)")
        return text
    if "`apps/android` / `apps/wear`" in match.group(0):
        return text
    extra = []
    for row in match.group(0).splitlines()[2:]:
        first = row.strip().strip("|").split("|")[0].strip()
        if first not in CLAUDE_KNOWN_ROWS:
            extra.append(apply_rules(row))
            Notes.warn(path, f"Repo map row kept as is (check it): {row[:80]}")
    return text[:match.start()] + CLAUDE_REPO_MAP + "".join(r + "\n" for r in extra) + text[match.end():]


README_TREE = """\
```
apps/android/        Android スマホアプリ
apps/wear/           Wear OS アプリ
apps/ios/            iOS アプリ + watchOS アプリ（XcodeGen: project.yml）
shared/              Kotlin Multiplatform の共通ロジック（model, protocol, platform, db, pipeline）
shared/ui/           スマホ UI（Compose Multiplatform、Android/iOS 共通）
build-logic/         Gradle の共通設定（convention plugin）
tools/harness/       実機なしの通し検証（Watch シミュレータ + sherpa-onnx、Linux/CI）
tools/test-audio/    検証用音声セットの生成（オフライン TTS の日本語会話 + Watch 録音を模した加工）
tools/privacy-check/ 「データは端末内のみ」の CI チェック（権限・バックアップ・通信・依存の許可リスト）
tools/check-links/   Markdown のリンク切れ検査（CI: links.yml）
tools/roadmap-view/  Issue ダッシュボードの生成
research/            技術検証のベンチマーク（ASR・埋め込み、Python）
docs/                product（調査・ロードマップ）/ architecture / platforms / dev（開発環境）/ research（検証結果）
```
"""


def readme(text: str, path: str) -> str:
    return sub_block(text, path, r"^```\nshared/ +Kotlin Multiplatform.*?^```\n", README_TREE,
                     done_marker="apps/android/        Android")


ARCHITECTURE_INTRO = (
    "`settings.gradle.kts` に含まれる Gradle モジュールと、その他のディレクトリ（#49 で整理）。"
    "Gradle のプロジェクトパスはフォルダと同じ（`:shared:db`・`:apps:android` など）。"
    "Kotlin のパッケージ名（`app.watchaudio.core.*` など）と iOS の framework 名（`ComposeApp`）は整理前のまま。\n"
)
ARCHITECTURE_TREE = """\
```
watch-audio/
├── apps/
│   ├── android/               # Android スマホアプリ（AppGraph、Data Layer 受信、sherpa-onnx 文字起こし、モデル配信、WorkManager）
│   ├── wear/                  # Wear OS アプリ（録音の前景サービス、Outbox、ChannelClient 送信、Tile・コンプリケーション）
│   └── ios/                   # XcodeGen プロジェクト（project.yml）
│       ├── App/               #   iOS ホスト（Compose 埋め込み、WatchConnectivity 受信、SpeechAnalyzer）
│       ├── Watch/             #   watchOS アプリ（SwiftUI、録音、WatchConnectivity 送信）
│       ├── WatchWidget/       #   コンプリケーション（WidgetKit）
│       ├── SharedSwift/       #   ChunkProtocol.swift など、Watch・iPhone・テストで共有する Swift コード
│       └── Tests/             #   Swift のテスト（ProtocolTests・WatchLogicTests・SpeechTests・PlaybackTests）
├── shared/                    # KMP: 共通ロジック（Gradle: :shared:<名前>）
│   ├── model/                 #   共通の値オブジェクト（TimeRange）
│   ├── protocol/              #   ChunkManifest / ChunkAck / ChunkFrame（Wear OS・スマホ共通。testdata/ は Swift 側と共有）
│   ├── platform/              #   expect/actual: SHA-256、IoDispatcher など
│   ├── db/                    #   LocalStore（手書き SQL）、Schema.kt のマイグレーション、FTS5 検索
│   ├── pipeline/              #   Ingestor、TranscriptionPipeline、Transcriber、発話区間の切り出し、境界の連結、StorageManager
│   └── ui/                    #   スマホ UI（Compose MP、Android/iOS 共通）、AppServices、ViewModel。iosMain に IosAppGraph と Swift とのブリッジ
├── build-logic/               # Gradle の convention plugin（KMP ライブラリ共通設定、SQLite テストを JVM/Android/iOS で動かす設定）
├── gradle/libs.versions.toml  # 依存バージョンのカタログ
├── tools/
│   ├── harness/               #   実機なしの通し検証（Watch シミュレータ → 取り込み → sherpa-onnx → SQLite、Gradle モジュール）
│   ├── test-audio/            #   検証用の日本語会話音声セットの生成（Python）
│   ├── privacy-check/         #   「データは端末内のみ」の CI チェック
│   ├── check-links/           #   Markdown のリンク切れ検査
│   └── roadmap-view/          #   Issue ダッシュボードの生成（Python）
├── research/                  # 技術検証のスクリプトと結果（ASR・埋め込みのベンチマーク）
└── docs/                      # product/（調査・ロードマップ）・architecture/・platforms/（プラットフォーム別の実装メモ）・dev/（開発環境）・research/（検証結果）
```
"""


def architecture_md(text: str, path: str) -> str:
    text = sub_block(text, path, r"^`settings\.gradle\.kts` に含まれる Gradle モジュールと、その他のディレクトリ。[^\n]*\n",
                     ARCHITECTURE_INTRO, done_marker="（#49 で整理）")
    return sub_block(text, path, r"^```\nwatch-audio/\n.*?^```\n", ARCHITECTURE_TREE, done_marker="│   ├── android/ ")


def dev_environment_md(text: str, path: str) -> str:
    # shared/ui is inside shared now, and apps/ios is a folder here (bare `iosApp` elsewhere is the Xcode target).
    text = text.replace("・`composeApp/src`・`shared`・`iosApp` の `.kt`", "・`shared`・`apps/ios` の `.kt`")
    # tools/restructure is removed at the end of the move (plan.md).
    return re.sub(r"移動の計画と書き換え箇所の一覧は \[`tools/restructure/plan\.md`\]\([^)]*\)。", "", text)


FILE_RULES = [
    (lambda p: p == "apps/ios/project.yml", project_yml),
    (lambda p: p == "settings.gradle.kts", settings_gradle),
    (lambda p: p.endswith(".gradle.kts"), gradle_build),
    (lambda p: p == "build-logic/src/main/kotlin/KmpLibraryConventionPlugin.kt", kmp_plugin),
    (lambda p: p.startswith("apps/") and p.endswith(".kt") and "/src/test/" in p, module_dir_tests),
    (lambda p: p.startswith("apps/ios/") and p.endswith(".swift"), swift_repository_root),
    (lambda p: p == ".github/workflows/emulator.yml", emulator_yml),
    (lambda p: p == ".github/workflows/android.yml", android_yml),
    (lambda p: p == "tools/privacy-check/policy.py", privacy_policy),
    (lambda p: p == "tools/privacy-check/check.py", privacy_check),
    (lambda p: p == "CLAUDE.md", claude_md),
    (lambda p: p == "README.md", readme),
    (lambda p: p == "docs/architecture/02-architecture.md", architecture_md),
    (lambda p: p == "docs/dev/04-dev-environment.md", dev_environment_md),
]


# --------------------------------------------------------------------------------------------
# 2. Markdown links

class Layout:
    """The repository after the moves: tracked files (and their directories) at their new paths."""

    def __init__(self, files: list[str]):
        self.files = {new_path_of(f) for f in files if not f.startswith(DELETED)}
        self.dirs = {posixpath.dirname(f) for f in self.files}
        for d in list(self.dirs):
            while d:
                d = posixpath.dirname(d)
                self.dirs.add(d)

    def exists(self, path: str) -> bool:
        path = path.rstrip("/")
        return path in self.files or path in self.dirs


def rewrite_links(text: str, new_path: str, layout: Layout) -> str:
    old_dir = posixpath.dirname(old_path_of(new_path))
    new_dir = posixpath.dirname(new_path)

    def fix_target(target: str) -> str:
        bare = target.strip("<>")
        if not bare or check_links.SCHEME.match(bare) or bare.startswith(("#", "//", "/")):
            return target
        path, sep, anchor = bare.partition("#")
        decoded = unquote(path)
        if layout.exists(posixpath.normpath(posixpath.join(new_dir, decoded))):
            return target  # valid in the new layout already
        old_target = posixpath.normpath(posixpath.join(old_dir, decoded))
        if old_target.startswith(".."):
            return target
        new_target = new_path_of(old_target)
        if not layout.exists(new_target):
            return target  # broken before the move too; the link check reports it
        relative = posixpath.relpath(new_target, new_dir) + ("/" if decoded.endswith("/") else "")
        return relative + sep + anchor

    out = []
    for _, line, in_fence in check_links._lines_outside_code(text):
        if not in_fence:
            spans = [m.span() for m in check_links.CODE_SPAN.finditer(line)]

            def link(match: re.Match) -> str:
                if any(s <= match.start() < e for s, e in spans):
                    return match.group(0)
                start, end = match.span(1)
                return (match.group(0)[:start - match.start()] + fix_target(match.group(1))
                        + match.group(0)[end - match.start():])

            line = check_links.INLINE_LINK.sub(link, line)
            line = check_links.REFERENCE_DEF.sub(
                lambda m: m.group(0)[:m.start(1) - m.start()] + fix_target(m.group(1)), line)
        out.append(line)
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


# --------------------------------------------------------------------------------------------
# 4. Workflow path filters

def dedupe_path_filters(text: str) -> str:
    """Drops repeated `- "pattern"` entries and inclusions covered by a parent `dir/**` in the same list."""
    lines = text.split("\n")
    item = re.compile(r'^(\s+)- "([^"]+)"\s*$')
    out: list[str] = []
    i = 0
    while i < len(lines):
        match = item.match(lines[i])
        if not match:
            out.append(lines[i])
            i += 1
            continue
        block = []
        while i < len(lines) and (m := item.match(lines[i])) and m.group(1) == match.group(1):
            block.append((lines[i], m.group(2)))
            i += 1
        parents = [p[:-3] for _, p in block if p.endswith("/**") and not p.startswith("!")]
        seen = set()
        for line, pattern in block:
            covered = not pattern.startswith("!") and any(
                pattern != p + "/**" and pattern.startswith(p + "/") for p in parents)
            if pattern in seen or covered:
                continue
            seen.add(pattern)
            out.append(line)
    return "\n".join(out)


# --------------------------------------------------------------------------------------------

LEFTOVER = re.compile(r"\bandroidApp\b|\bwearApp\b|\bcomposeApp\b|iosApp/|\bcore-(?:search|ai|protocol|db|pipeline|"
                      r"platform|model)\b|(?<![\w/.-])spikes/|docs/impl\b|docs/0[1-4]-|docs/test-audio\b")


def tracked_files() -> list[str]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], check=True, capture_output=True, text=True).stdout
    return sorted(f for f in out.split("\0") if f)


def is_text(path: str) -> bool:
    name = posixpath.basename(path)
    return name in TEXT_NAMES or posixpath.splitext(name)[1] in TEXT_SUFFIXES


def rewrite(path: str, text: str, layout: Layout) -> str:
    new_path = new_path_of(path)
    for applies, function in FILE_RULES:
        if applies(new_path):
            text = function(text, new_path)
    if new_path.endswith(".md"):
        text = rewrite_links(text, new_path, layout)
    text = apply_rules(text)
    if new_path.startswith(".github/workflows/"):
        text = dedupe_path_filters(text)
    return text


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Path rewrites of the #49 restructure.")
    parser.add_argument("--dry-run", action="store_true", help="only report what would change")
    parser.add_argument("--diff", action="store_true", help="print the diff of every change")
    args = parser.parse_args(argv)

    files = tracked_files()
    layout = Layout(files)
    changed = 0
    leftovers = []
    for path in files:
        if path.startswith(EXCLUDED_PREFIXES) or path.startswith(DELETED) or not is_text(path):
            continue
        source = ROOT / path
        if not source.is_file():
            continue
        try:
            before = source.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        after = rewrite(path, before, layout)
        new_path = new_path_of(path)
        if after != before:
            changed += 1
            diff = list(difflib.unified_diff(before.splitlines(), after.splitlines(), f"a/{new_path}", f"b/{new_path}",
                                             lineterm="", n=0))
            print(f"  rewrite {new_path} ({sum(1 for d in diff if d.startswith('+') and not d.startswith('+++'))} lines)")
            if args.diff:
                print("\n".join(diff))
            if not args.dry_run:
                source.write_text(after, encoding="utf-8")
        for number, line in enumerate(after.splitlines(), start=1):
            if LEFTOVER.search(line):
                leftovers.append(f"  {new_path}:{number}: {line.strip()[:140]}")

    print(f"{changed} files {'would change' if args.dry_run else 'changed'}")
    if Notes.items:
        print("== Needs a look (a rule did not find what it expected):")
        print("\n".join(f"  {n}" for n in Notes.items))
    if leftovers:
        print("== Old names still mentioned (history in docs/research and the roadmap is fine; fix the rest by hand):")
        print("\n".join(leftovers))
    return 0


if __name__ == "__main__":
    sys.exit(main())
