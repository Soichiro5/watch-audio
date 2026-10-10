# 04. 開発・検証環境

## 1. 役割分担

| 作業 | どこで | 備考 |
| --- | --- | --- |
| 共通ロジック・Android・Wear OS のビルド/テスト | クラウド（Claude Code）・ローカル・CI | Linux で完結 |
| iOS / watchOS のビルド | **Mac** または CI（GitHub Actions macOS） | Xcode が必要なため Linux 不可 |
| 実機での録音・電池・転送の検証 | **手元の実機（人が操作）** | M0-3 / M0-4 / M1 以降の受け入れ |
| ASR・埋め込みのモデル比較（精度の当たり付け） | クラウド（Linux CPU） | 実機での速度/精度は別途確認 |

## 2. 必要なもの

### ハードウェア

| 用途 | 推奨 | 最低限 |
| --- | --- | --- |
| Mac | Apple Silicon（M1 以降）、メモリ 16GB 以上 | Xcode 26 以降が動く Mac |
| iPhone | iOS 26 以降（SpeechAnalyzer 利用）。将来のオンデバイス LLM を見据えるなら iPhone 15 Pro 以降 | iOS 26 対応機 |
| Apple Watch | Series 10 / 11 / Ultra 2 以降（watchOS 26） | watchOS 26 対応機 |
| Android スマホ | Pixel 8 以降（Android 15+） | Android 10 以降 |
| Wear OS ウォッチ | **Pixel Watch 3/4 と Galaxy Watch 7/8 の両方**（Samsung は省電力挙動が独特で、シェアも大きい） | どちらか 1 台（Wear OS 4 以降） |

> 電池計測は機種ごとの差が大きいため、可能なら「録音検証専用」に 1 日使える個体を用意する。

### アカウント・費用

| 項目 | 必要になる時期 | 費用 |
| --- | --- | --- |
| Apple Developer Program | M0-3（Watch 実機に長期インストール）、M4（TestFlight） | 年 $99（無料アカウントでも実機インストールは可能だが 7 日で失効） |
| Google Play Console | M4（内部テスト配信） | 初回 $25 |
| AI プロバイダの API キー（OpenAI / Anthropic / Gemini のいずれか） | M3 | 従量課金 |
| GitHub Actions | 常時 | 非公開リポジトリは無料枠 2,000 分/月。**macOS は 10 倍消費**（iOS ビルド 1 回 ≈ 10〜15 分 → 100〜150 分相当） |

### ソフトウェア

- JDK 21、Android Studio（最新安定版）
- Xcode 26 以降、[XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）

## 3. ビルド手順

```bash
# Android / Wear OS / 共通テスト
./gradlew testAndroidHostTest
./gradlew :androidApp:installDebug   # スマホ
./gradlew :wearApp:installDebug      # Wear OS（adb でウォッチを接続）

# iOS / watchOS（Mac）
cd iosApp
echo 'TEAM_ID = XXXXXXXXXX' > Configuration/Local.xcconfig   # 自分の Team ID
echo 'BUNDLE_ID_PREFIX = com.example.watchaudio' >> Configuration/Local.xcconfig  # 一意な Bundle ID
xcodegen generate
open WatchAudio.xcodeproj   # iosApp スキームを実機に Run（Watch アプリも一緒に入る）
```

Wear OS 実機への接続は「開発者向けオプション → ワイヤレスデバッグ」→ `adb pair` / `adb connect`。

## 4. 検証データの扱い

- 録音検証では、**参加者全員に録音・文字起こしの目的を説明し同意を得る**。
- 実際の会話音声はリポジトリにコミットしない（個人情報）。評価用の正解テキストと集計結果のみ共有する。
