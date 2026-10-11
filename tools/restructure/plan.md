# フォルダ構成の整理（#49）の実行計画

#49 で合意した構成（[合意コメント](https://github.com/Soichiro5/watch-audio/issues/49#issuecomment-6105718752)）への移動を、**1 本の PR で短時間に安全に**行うための計画。移動とパスの書き換えは [`migrate.sh`](migrate.sh)（中で [`rewrite.py`](rewrite.py) を実行）が機械的に行う。この文書は、何をどう変えるか・どこを人が見るか・何で確かめるかをまとめる。

- 作成時点の基準: `main` の db885c9（2026-10-11）。実行するときの `main` で `--dry-run` をやり直して差分を確かめる（書き換えは行番号ではなくパターンで行うので、その間に増えた記述にも効く）。
- 着手条件（#49 の「依存」）: v0.1 の P0 実装 Issue がクローズし、進行中の PR がすべてマージされていること。移動の PR の間は新しいブランチを切らない。

## 1. 決めたこと

| 項目 | 決定 | 理由 |
| --- | --- | --- |
| Kotlin のパッケージ名 | **変えない**（`app.watchaudio.core.db`、`app.watchaudio.app`、`app.watchaudio.android` など） | 変えるとほぼ全ソースの `package` / `import`、R8 の keep ルール、Swift から見える名前、DB のテストベクタ以外のあらゆる箇所に波及する。フォルダとパッケージの対応がずれるのは `shared/ui`（`app.watchaudio.app`）と `shared/*`（`app.watchaudio.core.*`）だけで、KDoc とこの表で説明できる |
| Android の namespace | **変えない**（`app.watchaudio.core.<名前>`、`shared/ui` は `app.watchaudio.composeapp`） | build-logic の `KmpLibraryConventionPlugin` はモジュールパスから namespace を作るので、そのままだと `app.watchaudio.db` などに変わる。R クラス・マージ後マニフェスト・R8 の出力を変えないよう、新しいパスから従来の名前を作る形に書き換える（`rewrite.py` の `kmp_plugin`） |
| Gradle のプロジェクトパス | フォルダと同じにする: `:shared:protocol`・`:shared:db`・`:shared:pipeline`・`:shared:platform`・`:shared:model`・`:shared:ui`・`:apps:android`・`:apps:wear`（`:tools:harness` は据え置き） | 合意どおり。型安全アクセサは `projects.shared.db`・`projects.shared.ui` などになる |
| iOS の framework 名 | **`ComposeApp` のまま** | Swift の `import ComposeApp`、`OTHER_LDFLAGS`、`-disable-autolink-framework` を変えずに済む |
| XcodeGen のターゲット名・スキーム名 | **変えない**（`iosApp`・`WatchApp`・`WatchWidget`・`ProtocolTests`・`WatchLogicTests`・`SpeechTests`・`PlaybackTests`、スキーム `SwiftUnitTests` など） | CI の `-scheme`、`tools/privacy-check/policy.py` の `IOS_TARGETS`、docs の記述がそのまま使える。変えるのはフォルダだけ |
| docs のファイル名 | 番号付きの名前を残す（`docs/product/03-roadmap.md` など） | コードのコメントや Issue の「docs/04 §6.3」のような略記がそのまま通じる。略記はフォルダ付き（`docs/dev/04 §6.3`）に書き換える |
| APK のファイル名 | Gradle のプロジェクト名に従って `android-debug.apk` / `wear-debug.apk` などに変わる | `archivesName` で旧名を残すより、CI とスクリプトを書き換える方が後に残るものが少ない。`deliver.yml` の配布名（`watch-audio-phone-<sha>.apk`）は変わらない |
| 空のモジュール | `shared/core-search`・`shared/core-ai` を削除（依存と `include` も外す） | 合意どおり。AI・ハイブリッド検索を作るときに改めて追加する |
| `tools/restructure/` | 移動の PR の最後に削除する（`git rm -r tools/restructure`） | 一度きりの道具。履歴には残る。docs/04 §6.3 からのリンクは `rewrite.py` が外す |

## 2. 移動の対応表（old → new、すべて `git mv`）

### 2.1 トップレベルとモジュール

| 今 | 移動後 | Gradle | 備考 |
| --- | --- | --- | --- |
| `androidApp/` | `apps/android/` | `:androidApp` → `:apps:android` | APK: `android-<variant>.apk`、`proguard-*.pro` も一緒に移る |
| `wearApp/` | `apps/wear/` | `:wearApp` → `:apps:wear` | APK: `wear-<variant>.apk` |
| `iosApp/` | `apps/ios/` | （Gradle 外） | 中のフォルダは 2.2 |
| `composeApp/` | `shared/ui/` | `:composeApp` → `:shared:ui` | framework 名 `ComposeApp` は維持 |
| `shared/core-protocol/` | `shared/protocol/` | `:shared:core-protocol` → `:shared:protocol` | `testdata/` も移る（Swift のテストが参照） |
| `shared/core-db/` | `shared/db/` | `:shared:core-db` → `:shared:db` | `jvmBenchmark` タスクも同じ |
| `shared/core-pipeline/` | `shared/pipeline/` | `:shared:core-pipeline` → `:shared:pipeline` | |
| `shared/core-platform/` | `shared/platform/` | `:shared:core-platform` → `:shared:platform` | |
| `shared/core-model/` | `shared/model/` | `:shared:core-model` → `:shared:model` | |
| `shared/core-search/` | （削除） | `include` と `shared/ui` の依存を削除 | 中身は空の `Module.kt` だけ |
| `shared/core-ai/` | （削除） | 同上 | 同上 |
| `spikes/` | `research/` | — | 中の `asr-benchmark/`・`embedding-benchmark/`（Git 管理外のデータも一緒に移る） |
| `tools/harness/`・`tools/test-audio/`・`tools/privacy-check/`・`tools/release-test-keep/`・`tools/roadmap-view/`・`tools/check-links/` | 変わらない | `:tools:harness` のまま | モデル置き場 `tools/harness/models` も変わらない |
| `build-logic/`・`gradle/`・`.github/` | 変わらない | | |

### 2.2 iOS（`apps/ios/` の中、XcodeGen）

| 今 | 移動後 | 使うターゲット（名前は変えない） |
| --- | --- | --- |
| `iosApp/iosApp/` | `apps/ios/App/` | `iosApp`（`Info.plist` も `App/Info.plist` に生成） |
| `iosApp/WatchApp/` | `apps/ios/Watch/` | `WatchApp`（`Watch/Info.plist`・`Watch/WatchApp.entitlements`） |
| `iosApp/WatchWidget/` | `apps/ios/WatchWidget/`（名前そのまま） | `WatchWidget` |
| `iosApp/Shared/` | `apps/ios/SharedSwift/` | `iosApp`・`WatchApp`・`WatchWidget`・`ProtocolTests`・`WatchLogicTests` |
| `iosApp/ProtocolTests/` | `apps/ios/Tests/ProtocolTests/` | `ProtocolTests` |
| `iosApp/WatchLogicTests/` | `apps/ios/Tests/WatchLogicTests/` | `WatchLogicTests` |
| `iosApp/SpeechTests/` | `apps/ios/Tests/SpeechTests/` | `SpeechTests` |
| `iosApp/PlaybackTests/` | `apps/ios/Tests/PlaybackTests/` | `PlaybackTests` |
| `iosApp/TestSupport/` | `apps/ios/Tests/TestSupport/` | `SpeechTests`・`PlaybackTests` |
| `iosApp/Configuration/` | `apps/ios/Configuration/` | `configFiles`（Git 管理外の `Local.xcconfig` もディレクトリごと移る） |
| `iosApp/project.yml` | `apps/ios/project.yml` | Xcode プロジェクトは `apps/ios/WatchAudio.xcodeproj` に生成 |

### 2.3 docs

| 今 | 移動後 |
| --- | --- |
| `docs/01-research.md` | `docs/product/01-research.md` |
| `docs/03-roadmap.md` | `docs/product/03-roadmap.md` |
| `docs/02-architecture.md` | `docs/architecture/02-architecture.md` |
| `docs/04-dev-environment.md` | `docs/dev/04-dev-environment.md` |
| `docs/test-audio.md` | `docs/dev/test-audio.md` |
| `docs/impl/*.md` | `docs/platforms/*.md` |
| `docs/spikes/*.md` | `docs/research/*.md` |

## 3. 書き換える箇所の一覧（`git grep` で全件を洗い出した結果）

`rewrite.py` が行う書き換えは 3 種類: (a) ファイルごとの専用ルール、(b) Markdown の相対リンクの付け替え（移動前の位置で解決 → 移動後の位置からの相対パスに直す。アンカーは見出しが変わらないのでそのまま）、(c) 全ファイル共通の順序付き置換（`RULES`。パスの一部・単語の一部には当たらないよう前後を制限）。db885c9 では 87 ファイル（約 520 行）が変わる。一覧は `tools/restructure/migrate.sh --dry-run --diff` で出る。

### 3.1 Gradle

| ファイル | 変更 | 方法 |
| --- | --- | --- |
| `settings.gradle.kts` | `include` 8 行（`:shared:*`、`:shared:ui`、`:apps:android`、`:apps:wear`）、`core-search`・`core-ai` の 2 行を削除、コメント | 専用 + 共通 |
| `build-logic/src/main/kotlin/KmpLibraryConventionPlugin.kt` | namespace の導出を「`:shared:ui` → `app.watchaudio.composeapp`、他は `app.watchaudio.core.<名前>`」に。KDoc | 専用 `kmp_plugin` |
| `build-logic/src/main/kotlin/AndroidReleaseConventionPlugin.kt`・`SqliteTestsConventionPlugin.kt` | KDoc のパス・モジュール名のみ | 共通 |
| `build-logic/settings.gradle.kts` | 変更なし（`../gradle/libs.versions.toml` は位置が変わらない） | — |
| `shared/*/build.gradle.kts`（db・pipeline・platform・protocol） | `projects.shared.coreX` → `projects.shared.x` | 共通 |
| `shared/ui/build.gradle.kts` | 同上、`coreSearch`・`coreAi` の依存行を削除 | 専用 + 共通 |
| `apps/android/build.gradle.kts`・`apps/wear/build.gradle.kts`・`tools/harness/build.gradle.kts` | `projects.composeApp` → `projects.shared.ui` など。コメント | 共通 |
| `tools/harness/build.gradle.kts` の `rootProject.file("tools/harness/models")` など | 変更なし | — |
| `gradle/libs.versions.toml` | コメント（`(wearApp)`）のみ | 共通 |

### 3.2 Xcode（XcodeGen）と Swift

| ファイル | 変更 | 方法 |
| --- | --- | --- |
| `apps/ios/project.yml` | `sources`（`[App, SharedSwift]`・`[Watch, SharedSwift]`・`Tests/...`）、`info.path`・`entitlements.path`、個別ファイルの `path:`、`../shared/core-protocol/testdata` → `../../shared/protocol/testdata`、`FRAMEWORK_SEARCH_PATHS`（`$(SRCROOT)/../../shared/ui/build/xcode-frameworks/...`、2 か所）、ビルドフェーズの `cd "$SRCROOT/../.."` と `./gradlew :shared:ui:embedAndSignAppleFrameworkForXcode` | 専用 `project_yml`（パスは project.yml からの相対なので共通ルールは使わない） |
| `apps/ios/Tests/SpeechTests/SpeechTranscriptionTests.swift`・`Tests/ProtocolTests/ComplicationDisplayTests.swift`・`Tests/TestSupport/EvidenceReport.swift` | `#filePath` からリポジトリのルートへ上がる `.deletingLastPathComponent()` を 3 回 → 5 回（`build/ja-speech`・`build/complication-previews`・`build/swift-evidence` の場所） | 専用 `swift_repository_root`（深さから回数を計算） |
| その他の Swift（`SharedSwift/*.swift`、`Watch/Recording/*.swift`、`App/iOSApp.swift`、`Tests/*`） | コメントのパスのみ | 共通 |
| `.gitignore` | `iosApp/*.xcodeproj`・`*.xcworkspace`・`Configuration/Local.xcconfig` → `apps/ios/...` | 共通 |

### 3.3 CI（`.github/`）

| ファイル | 変更 | 方法 |
| --- | --- | --- |
| `workflows/android.yml` | `paths-ignore` の `spikes/**` を削除（`research/**` は既にある）、Gradle タスクのパス（`:apps:android:…`・`:shared:db:jvmBenchmark`）、`shared/db/build/reports/...`、Local-only チェックのループ（`for app in android wear` と `:apps:$app:dependencies`、`deps-android.txt`）と `--merged-manifest apps/android=…`・`--resolved-deps apps/android=…`、成果物（`apps/*/build/outputs/apk/...`・`shared/ui/build/screenshots/`）、`apps/wear/build/reports/debug-input/release.txt`。キャッシュ `tools/harness/models` と `hashFiles('tools/harness/download-models.sh')` は変更なし | 専用 `android_yml` + 共通 + 重複除去 |
| `workflows/ios.yml` | `paths`: `iosApp/**` → `apps/ios/**`、`composeApp/**` は `shared/**` に含まれるので削除（`!` の除外はそのまま有効）。`cd apps/ios && xcodegen generate`、`-project apps/ios/WatchAudio.xcodeproj`（5 か所）、`shared/ui/build/test-results/...`（テスト一覧・`PLAYBACK_CHECK`・成果物）。`-derivedDataPath build/DerivedData` と konan のキャッシュキーは変更なし | 共通 + 重複除去 |
| `workflows/emulator.yml` | `paths`: `androidApp/**`・`wearApp/**` → `apps/android/**`・`apps/wear/**`、`composeApp/**` は削除（`shared/**` に含まれる）。matrix の `module` を「フォルダ」「Gradle パス」「APK 名」に分ける（`module: apps/android` + `gradle-path: :apps:android` + `apk: android`）、`${{ matrix.gradle-path }}:assembleRelease`、`release-apk/${{ matrix.apk }}-release.apk`、`device-test-apks`・`device-tests`・`device-test-modules`、テスト結果・レポートのパス。ASR モデルのキャッシュは変更なし | 専用 `emulator_yml` + 共通 + 重複除去 |
| `workflows/deliver.yml` | `paths-ignore` の `spikes/**` を削除、`:apps:*:assembleDebug`、`cp apps/*/build/outputs/apk/debug/*.apk` | 共通 + 重複除去 |
| `workflows/links.yml` | 変更なし（絞り込みなし） | — |
| `scripts/phone-scenarios.sh`・`release-asr.sh` | `apps/android/build/outputs/apk/.../android-{debug,release}[-androidTest].apk` | 共通 |
| `scripts/xcode-test.sh` | `-project apps/ios/WatchAudio.xcodeproj`、コメント | 共通 |
| その他の `scripts/*`（`launch-smoke.sh`・`push-models.sh`・`simulator-*.sh`・`junit-summary.py` など） | 変更なし（引数で受け取る、または `tools/harness/models` のまま）。スクリプトの名前は変えないので、各 workflow の `!.github/scripts/...` の除外も変更なし | — |
| `dependabot.yml` | 変更なし（`directory: /` の github-actions だけ） | — |
| `pull_request_template.md` | `docs/impl/*` → `docs/platforms/*`、`docs/02`・`docs/03` の略記 | 共通 |

`#92` などで実行時点の workflow やスクリプトが変わっていても、置換はパターンで行うので効く。新しく増えたパスは `--dry-run` の最後の「Old names still mentioned」に出る。

### 3.4 tools

| ファイル | 変更 | 方法 |
| --- | --- | --- |
| `tools/privacy-check/policy.py` | `ANDROID_APPS` のキー（モジュールのフォルダ）`"apps/android"`・`"apps/wear"`、`SOURCE_ROOTS = ("apps/android/src", "apps/wear/src", "shared", "apps/ios")`（`shared/ui` は `shared` に含まれるので外す）、`ALLOWED_API_USES` のファイルパス、`DEPENDENCY_SCAN_EXCLUDES = ("research/",)`、`IOS_PROJECT = "apps/ios/project.yml"`。`IOS_TARGETS` のキー（ターゲット名 `iosApp` など）は変えない | 専用 `privacy_policy` + 共通 |
| `tools/privacy-check/check.py` | entitlements を探す `os.walk(root / "apps" / "ios")` | 専用 `privacy_check` |
| `tools/privacy-check/test_check.py` | フィクスチャのパス（`apps/android/src/...`・`apps/ios/App/...`・`research/bench/...`）と `merged={"apps/wear": …}` | 共通 |
| `tools/release-test-keep/generate.sh` | `OUT=apps/android/proguard-release-test.pro`、`path == ":apps:android"`、`:apps:android:compile…`、`KOTLINC=apps/android/build/...` | 共通 |
| `apps/android/proguard-release-test.pro`（生成物） | Kotlin の `internal` メンバー名に付くモジュール名が変わる: `playingPositionMs$androidApp` → `playingPositionMs$android` | 共通（`$androidApp`）。確実にするなら移動後に `tools/release-test-keep/generate.sh` で作り直す |
| `apps/android/src/test/.../ModelManifestTest.kt` | モジュールのフォルダが 1 段深くなるので `File("").absoluteFile.parentFile.resolveSibling("tools/harness/download-models.sh")` | 専用 `module_dir_tests` |
| `tools/harness/*`・`tools/test-audio/generate.py` | コメント・ヘルプの `docs/dev/test-audio.md`・`research/asr-benchmark/...` | 共通 |
| `tools/roadmap-view/*` | 変更なし（リポジトリのパスを参照していない） | — |
| `tools/check-links/*` | 変更なし（テストのフィクスチャは古いパスを「壊れた例」として使うので書き換え対象外） | — |

### 3.5 ドキュメント

| ファイル | 変更 | 方法 |
| --- | --- | --- |
| `CLAUDE.md` | Repo map の表を新しい構成で置き換え（未知の行は残して警告）、Wire contract・DB migrations のパス、Commands の Gradle パスと `cd apps/ios`、`docs/product/03-roadmap.md`、`docs/platforms/*` | 専用 `claude_md` + 共通 |
| `README.md` | ドキュメント表のリンク、「リポジトリ構成」のブロックを置き換え | 専用 `readme` + リンク + 共通 |
| `docs/architecture/02-architecture.md` | §4 のツリーと前置きを置き換え、本文のパス・リンク | 専用 `architecture_md` + リンク + 共通 |
| `docs/dev/04-dev-environment.md` | ビルド手順・§6 の表・§6.3 の起動条件の表（`apps/ios/**` など）・§6.4 の対象と手元のコマンド・§7.1、`tools/restructure/plan.md` へのリンクを外す | 専用 `dev_environment_md` + リンク + 共通 |
| その他の docs（`product/*`・`platforms/*`・`research/*`・`dev/test-audio.md`）、`research/*/README.md` | パス・リンク・略記（`docs/02 §7` → `docs/architecture/02 §7`） | リンク + 共通 |
| ソースのコメント（Kotlin・Swift・XML・シェル） | `docs/platforms/wearos.md`・`shared/db`・`apps/ios/...` など | 共通 |

### 3.6 書き換えないもの

- 単独の `iosApp`・`WatchApp`（XcodeGen のターゲット名・スキーム名、`-scheme iosApp`、`IOS_TARGETS`、docs のターゲットの表）と、framework 名 `ComposeApp`（大文字小文字を区別）。
- Kotlin のパッケージ名（`app.watchaudio.core.*` はドット区切りなので置換に当たらない）。
- Git 管理外のファイル（`local.properties` はルートのまま、`tools/harness/models` は位置が変わらない）。

## 4. 手順

1. `main` を最新にし、作業ツリーがきれいなことを確認してブランチを切る（例: `chore/49-restructure`）。
2. `tools/restructure/migrate.sh --dry-run --diff | less` で移動と書き換えを確認する。最後の「Needs a look」（期待した記述が見つからなかった専用ルール）と「Old names still mentioned」（残った旧名）を読む。
3. `tools/restructure/migrate.sh` を実行し、`git diff -M --stat` で移動が rename として認識されていることを確認する。もう一度実行して「0 files changed」になる（冪等）ことも確かめられる。
4. 残った旧名を手で直す（db885c9 時点では 0 件）。docs/product/03-roadmap.md の進捗表に #49 の行を足す。
5. 手元の確認（§6 の「手元」）を通す。
6. `git rm -r tools/restructure` してコミットし、PR（本文に `Closes #49` か `Refs #49`）を出す。
7. CI（android.yml・ios.yml・emulator.yml・links.yml）がすべて起動して緑になることを確認する。
8. マージ後: Mac では `rm -rf apps/ios/WatchAudio.xcodeproj && (cd apps/ios && xcodegen generate)`。手元のクローンでは古い `build/` が `apps/*/build` に移るだけなので害はないが、IDE（Android Studio・Xcode）はプロジェクトを開き直す。ダッシュボードを再生成する。

## 5. リスクと対策

| リスク | 影響 | 対策 |
| --- | --- | --- |
| 進行中のブランチ・PR との衝突 | ほぼ全ファイルが rename になる | 着手条件（PR がすべてマージ済み、新しいブランチを止める）を守る。後から rebase するブランチは Git の rename 検出で多くは追従するが、旧フォルダに新しく足したファイルは手で `git mv` する |
| Xcode プロジェクトの再生成 | 古い `WatchAudio.xcodeproj` はディレクトリごと `apps/ios/` に移り、パスが古いまま | 手順 8 で削除して再生成。CI は毎回 `xcodegen generate` するので影響なし |
| `#filePath` の深さ | Swift のテストが証跡を書く場所（`build/swift-evidence` など）がずれる | `swift_repository_root` で段数を直す。ios.yml のジョブサマリーに `Speech check logs`・`hosted-playback.txt`・コンプリケーションの PNG が出ることで確認 |
| Kotlin のモジュール名の変更（`androidApp` → `android`、`composeApp` → `ui`、`core-db` → `db` など） | `internal` メンバーの JVM 名（`$androidApp` → `$android`）が変わり、R8 の keep ルール（`proguard-release-test.pro`）が外れる。`.kotlin_module` の名前も変わる | keep ルールは置換し、必要なら `generate.sh` で作り直す。emulator.yml の `ReleaseAsrTest`（`RELEASE_ASR_CHECK`）で確認 |
| Kotlin/Native の Swift 向けの名前 | export していない依存モジュールのクラスは、Swift でモジュール名を元にした接頭辞付きの名前になる | Swift が使っているのは `shared/ui` 自身のクラス（`IosAppGraph`・`IosAppServices`・`IosSpeechTranscriber`・`IosAudioPart`・`IosTimedText`）だけで影響なし。ios.yml のビルドで確認 |
| Android の namespace | 導出規則のままだと R クラス・マニフェストのパッケージが変わる | `kmp_plugin` で従来の名前を維持 |
| Compose のリソース（`Res` クラスのパッケージはプロジェクト名から作られる） | 生成パッケージが変わる | 現状 `composeResources` は使っていないので影響なし。今後使うときは `compose.resources { packageOfResClass = … }` で固定する |
| R8 の keep ルール・mapping | クラス名が変わると外れる | パッケージ名を変えないので `proguard-rules.pro` は変更不要 |
| Gradle の configuration cache・ビルドキャッシュ | 初回は作り直しで遅い | 問題なし（キャッシュキーは設定ファイルから作られる） |
| CI の起動条件の取りこぼし | 必要な workflow が動かない、または余計に動く | §6 のマージ後の確認（docs だけ・Android だけ・iOS だけの変更） |
| Issue・PR・ダッシュボードにある GitHub の旧パスへのリンク | `blob/main/androidApp/...` が 404 になる | 古いコミットのリンクは有効。必要なものだけ張り替える（自動では直さない） |
| 履歴の追跡 | `git log` がフォルダの移動で途切れて見える | `git log --follow`、`git blame -M` を使う（`git mv` なので履歴は残る） |
| `.claude/worktrees` などの手元の作業ツリー | 古い構成のまま残る | 移動前に片付けるか、移動後に作り直す |

## 6. 確認（#49 の完了条件との対応）

| 完了条件 | 確認方法 |
| --- | --- |
| 移動後に CI（android.yml / ios.yml / emulator.yml）がすべて緑 | 移動の PR の CI 実行結果のリンク。workflow ファイル自体を変えるので 3 つとも必ず起動する。links.yml も緑 |
| 実機なし E2E（tools/harness）が通る | android.yml の「End-to-end without devices」ステップのログ（`-PrequireModels=true`） |
| リポジトリ内の Markdown のリンク切れが 0 件 | links.yml のログ（`python3 tools/check-links/check_links.py` の `0 broken link(s)`） |
| CLAUDE.md の Repo map が実際の構成と一致 | PR の差分。links.yml が CLAUDE.md のコード表記のパス・Repo map の 1 列目・`cd` 先・Gradle のプロジェクトパスの実在も確かめる |

手元（Linux、移動の PR を出す前）:

```bash
python3 tools/check-links/check_links.py
python3 -m unittest discover -s tools/privacy-check && python3 tools/privacy-check/check.py
./gradlew --max-workers=2 help
./gradlew --max-workers=2 jvmTest testAndroidHostTest :apps:android:testDebugUnitTest :apps:wear:testDebugUnitTest :tools:harness:test
./gradlew --max-workers=2 :apps:android:assembleDebug :apps:wear:assembleDebug
./gradlew --max-workers=2 compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true
```

CI でしか確かめられないこと: Xcode のプロジェクト生成とビルド・Swift のテスト（`#filePath` の深さ、`FRAMEWORK_SEARCH_PATHS`、ビルドフェーズの Gradle 呼び出し）、iOS シミュレータでの Kotlin テスト（`iosSimulatorArm64Test`）と framework のリンク、エミュレータでの計装テスト・`ReleaseAsrTest`（keep ルール）・起動確認、lint、release ビルド（R8）。

マージ後（#49 のフォローアップコメントの確認項目）:

- docs だけの変更では android.yml・ios.yml・emulator.yml・deliver.yml が起動しない（links.yml だけ動く）。
- Android だけの変更（例: `apps/android/src/main/...`）では ios.yml が起動しない。iOS だけの変更（例: `apps/ios/App/...`）では emulator.yml が起動しない。

## 7. 準備の時点で確かめたこと

db885c9 + この準備の PR の内容を使い捨てのクローンで移動し（`migrate.sh` を実行）、次を確認した。結果は準備の PR の本文に記録する。

- `--dry-run` と実行の結果が一致し、2 回目の実行で何も変わらない（冪等）。
- リンク検査 0 件、Local-only チェック（テスト 33 件と本体）が通る。
- Gradle の設定（`help`・`projects`）、JVM と Android ホストの単体テスト、アプリの単体テスト、ハーネスのテスト（モデルなしでスキップ）、`assembleDebug`（APK 名 `android-debug.apk` / `wear-debug.apk`）、iOS 向けのクロスコンパイルが通る。
