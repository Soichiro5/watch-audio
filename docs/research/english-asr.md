# 英語・日英混在の文字起こし（Android）: 方式の比較と採用

- Issue: [#81 Android で英語・日英混在の会話を文字起こしできる](https://github.com/Soichiro5/watch-audio/issues/81)
- 実施日: 2026-10-12
- 環境: この開発環境（Linux、4 vCPU、ASR 2 スレッド。他の評価と並行したため速度は参考値）
- 前提: [M0-5 スパイク](m0-5-asr-desktop.md)（Zipformer ReazonSpeech を採用、SenseVoice Small は日英の言語自動判定ができるが日本語の会話調で誤りが多い）
- 評価データ・採点: [検証用音声セット](../dev/test-audio.md)（日本語 8 条件 + 英語・日英混在の 4 条件を追加）

## TL;DR

1. **採用: 方式 D「必要な区間だけ SenseVoice」を、設定「英語も文字起こしする」（既定オフ）として Android に実装**。オンにすると SenseVoice Small int8（約 240 MB）を Wi-Fi で追加ダウンロードし、区間ごとに日本語モデル（Zipformer）→ 出力が少ない / カタカナばかりの区間だけ SenseVoice → 英語なら SenseVoice の文、の順で振り分ける。
2. **日本語は悪化しない**: 日本語 8 条件の CER は変更前の main と比べてオフで完全一致（保存セグメントまで同一）、オンでも最大 +0.1 ポイント（基準 ±1 ポイント以内）。日本語の発話が英語として保存されたのは、日本語 8 条件で 3,484 発話中 2、日英混在で 228 発話中 0。
3. **英語が文字起こしされる**: 英語の会話の WER はオフ 100%（英語として何も残らない）→ オン 10.2%（近接）/ 11.7%（腕の位置）。日英が発話ごとに入れ替わる会話でも、日本語の CER はオフと同じまま英語 WER 11.0% / 14.1%、言語の取り違えは 174 発話中 0〜1。
4. **計算量**: 日本語の録音では SenseVoice が動くのは区間の 3〜17% で、ASR の計算時間は約 2 割増（全区間で SenseVoice を動かす方式 C は約 3 倍）。英語の録音では両方のモデルが動く。
5. **SenseVoice への置き換え（B）は不採用**: 合成音声の会話では Zipformer より CER が低い（clean 9.8% vs 14.7%）が、実音声の ReazonSpeech では 26.6% vs 9.7% と大きく悪い。合成音声の評価は SenseVoice に有利に偏っている（実録音で要確認、#61）。
6. **既知の限界**: 1 つの発話の中で日英が混ざる場合（「このPRをmergeして」）はどちらか一方の言語になる。iOS は対象外（SpeechAnalyzer の日本語ロケールのまま）。

## 1. 背景

Android の文字起こしは sherpa-onnx + Zipformer ReazonSpeech（int8、約 160 MB）で、日本語専用。英語の発話を聞かせると、英語として認識しないだけでなく、10 秒の英文に「はい」「そう」程度の 1 語しか出さないか、固有名詞などをカタカナの別の語にする（「JVキャンストレーション」）。英語の会議・英語を話す相手との会話は記録に残らない。

iOS は SpeechAnalyzer（OS の音声認識）で、本件の対象外（§7）。

## 2. 評価データ

| データ | 内容 | 使い方 |
| --- | --- | --- |
| 日本語 8 条件（#56） | 合成音声の会話 16 本 505 発話を clean・wrist・office・cafe・outdoor・rub に加工、1 時間の day、ReazonSpeech テスト 150 文（reazon_clean） | 日本語の精度が落ちないことの確認（`--english off` と `on`） |
| `english_clean` / `english_wrist`（新規） | FLEURS en テスト（実際の人の英語の読み上げ）60 文を、装着者と相手が交互に話す 6 文 × 10 会話に並べたもの。11.3 分 | 英語の WER、英語の発話が英語として保存されるか |
| `mixed_clean` / `mixed_wrist`（新規） | コーパスの日本語の発話 1〜3 つごとに英語の発話（FLEURS en の残り 60 文）を挟んだ日英の会話（日本語 114・英語 60 発話、18.6 分） | 話者・発話ごとに言語が変わる会話で、発話ごとに正しい言語・モデルに振り分けられるか |

- 生成は `tools/test-audio/generate.py --fleurs-manifest research/asr-benchmark/data/manifest.jsonl`（乱数は `--seed 56` で固定、再現可能）。FLEURS は CC BY 4.0 で、音声はローカルの評価にだけ使いコミットしない（[test-audio.md §5](../dev/test-audio.md#5-ライセンス)）。
- 採点: 日本語は CER（従来どおり）、英語は WER（M0-5 と同じ正規化）。保存されたセグメントの言語（`lang`）が発話の言語と違うものを「言語の誤り」として数える。

## 3. 候補

| 方式 | 内容 | 追加のダウンロード |
| --- | --- | --- |
| A. 日本語のみ（現在） | Zipformer だけ | なし |
| B. SenseVoice に置き換え | すべての区間を SenseVoice（言語自動判定）で | 240 MB（Zipformer は不要になる） |
| C. 全区間で SenseVoice を併用 | 区間ごとに SenseVoice と Zipformer の両方で認識し、SenseVoice が英語と判定した区間だけ SenseVoice の文を使う | 240 MB |
| **D. 必要な区間だけ SenseVoice（採用）** | まず Zipformer で認識し、出力が区間の長さに対して少ない（1.5 文字/秒未満）かカタカナ・ラテン文字ばかり（8 割以上）の区間だけ SenseVoice でも認識して、英語ならその文を使う | 240 MB |
| E. 言語識別モデル + 振り分け | Whisper の言語識別（sherpa-onnx の `SpokenLanguageIdentification`）で区間の言語を判定し、英語なら SenseVoice で認識 | 240 MB + Whisper（base で 160 MB） |

### 3.1 言語判定の部品比較（発話単位、AAC 24 kbps）

M0-5 のデータ（FLEURS ja 120・ReazonSpeech 150・FLEURS en 120 発話、1 発話ずつ）で、SenseVoice の言語タグと Whisper base の言語識別を比べた（`sherpa_onnx` 1.13.8、2 スレッド）。

| 判定器 | 日本語 270 発話のうち英語と判定 | 英語 120 発話のうち英語と判定 | 処理時間（RTF） | 追加サイズ |
| --- | ---: | ---: | ---: | ---: |
| SenseVoice Small の言語タグ（認識のついでに出る） | **0** | **120** | 0.098（認識込み） | 240 MB |
| Whisper base の言語識別 | 2 | 120 | 0.060（判定のみ。英語の文には別途認識が要る） | 160 MB |

SenseVoice の判定は認識と同時に出るので、英語の文を得るのに追加の計算がない。Whisper の言語識別は判定だけで SenseVoice（または Whisper の認識）をもう 1 回走らせる必要があり、日本語を英語と取り違える数も多く、ダウンロードも増える。**E は不採用**とし、言語判定は SenseVoice のタグを使う。ReazonSpeech の 4 発話は SenseVoice が韓国語・中国語と判定したが、英語以外はすべて Zipformer の文を使うので影響しない。

### 3.2 Zipformer の出力で英語らしさを見分けられるか

全区間で SenseVoice を動かす（C）と、日本語の録音でも計算量がおよそ倍になる（M0-5 で SenseVoice の RTF は Zipformer の約 2 倍）。そこで、Zipformer が英語に対してほとんど何も出さない性質を使い、SenseVoice を動かす区間を絞れるか調べた。

- 発話単位（上のデータ）: Zipformer の出力の文字数 ÷ 音声長は、英語 120 発話で最大 0.92 文字/秒（中央値 0.21）、日本語は FLEURS ja で最小 1.95・ReazonSpeech で 10 パーセンタイル 2.07 文字/秒。
- アプリと同じ区間の切り方（VAD + 余白 0.3 秒 + 話者交代での分割）で保存された区間（`--english off` の評価結果）では次のとおり。カタカナ率 = カタカナ（「ー」「・」を含む）とラテン文字が文字に占める割合。

| SenseVoice も動かす区間の条件 | 英語の区間のうち対象 | 日本語の区間のうち対象（余分な計算） |
| --- | ---: | ---: |
| 1.5 文字/秒未満 | 87% | 4% |
| 2.0 文字/秒未満 | 90% | 8% |
| **1.5 文字/秒未満 または カタカナ率 80% 以上** | **93%** | **4%** |
| 1.5 文字/秒未満 または カタカナ率 50% 以上 | 93% | 6% |
| すべて（C） | 100% | 100% |

（英語 400 区間・日本語 4,426 区間。対象から漏れる英語の区間は、英語の発話が細かく分かれた断片に Zipformer が「はい」「え」などを出したもので、短い。）

## 4. 結果（検証用音声セット、ハーネス）

`./gradlew :tools:harness:run --args="evaluate <manifest> <dir> --english …"` で、アプリと同じ区間の切り方・誤認識の除去（`FalsePositiveFilter.DEFAULT`）・Watch と同じ AAC 24 kbps のチャンクで評価した。A は変更前の main（`5681119`）と `--english off` の両方（結果は保存セグメントまで一致）。C・B は `LanguageRouting` の値を変えて再現した（C: `maxPrimaryCharsPerSecond=Infinity`、B: さらに `languages=en+ja+zh+ko+yue,minLatinShare=0,minWords=1` で SenseVoice の文をすべて採用。B でも Zipformer は動くので B の計算量は別に見積もる）。

### 4.1 方式の比較

| 方式 | 日本語 clean CER | 日本語 wrist CER | ReazonSpeech（実音声）CER | 英語 WER（近接 / 腕） | 日英混在: 日本語 CER / 英語 WER（近接） | 日本語の録音での ASR 計算量 | 追加ダウンロード |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| A. 日本語のみ（現在、オフ） | 14.7% | 19.4% | 9.7% | 100% / 100% | 9.9% / 100% | 1 | 0 |
| B. SenseVoice に置き換え | 9.8% | 10.9% | **26.6%** | 9.7% / 11.1%（C と同等） | 9.7% / 9.6% | 約 2.1 | 240 MB |
| C. 全区間で SenseVoice を併用 | 14.7% | 19.5% | （未測定。英語以外は A と同じ文） | 9.7% / 11.1% | 9.9% / 9.8% | 約 3.1 | 240 MB |
| **D. 必要な区間だけ SenseVoice（採用）** | **14.7%** | **19.5%** | **9.7%** | **10.2% / 11.7%** | **9.9% / 11.0%** | **約 1.2** | **240 MB** |
| E. Whisper の言語識別 + SenseVoice | （§3.1 で不採用） | | | | | 1 + 識別 + 英語区間 | 400 MB |

- 計算量は Zipformer だけの ASR 時間を 1 としたときの比（ハーネスが数えたモデルごとの認識時間。SenseVoice は同じ区間で Zipformer の約 2.1 倍かかった）。並行して他の評価を動かしたため絶対値は参考値で、比だけを使う。
- **B は合成音声では良く見えるが、実音声で大きく悪い**: 合成音声の会話では SenseVoice の方が CER が低い（clean 9.8% / wrist 10.9%、day 25.3%、cafe 41.1%）が、実際の人の声の ReazonSpeech（テレビ音声）では 26.6% と Zipformer（9.7%）の 2.7 倍で、M0-5（26.7% vs 7.1%）と同じ傾向。合成音声（読み上げ調・ITN で数字を算用数字で書く）が SenseVoice に有利に働いている。また日本語の発話を中国語・韓国語と判定して漢字だけ・ハングルの文を出すことがある（cafe で 73/495 発話、reazon_clean で 15/150）。日本語の既定エンジンを置き換える根拠にはならないので不採用。合成音声の評価の偏りは実録音（#61）で確かめる。
- C と D の差は英語の WER で 0.5〜1.2 ポイント（D では英語の区間の一部が Zipformer の出力のまま残る、§3.2 の 93%）。日本語の CER は同じ。計算量は C が日本語の録音でも約 3 倍になる。バッテリーを日本語の録音のために使わない D を採る。

### 4.2 日本語への影響（採用方式 D、CER 全体）

| 条件 | main | オフ | オン | 差 | 英語として保存された日本語の発話 | SenseVoice が動いた区間 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| clean | 14.7% | 14.7% | 14.7% | 0.0 | 0/505 | 28/902 |
| wrist | 19.4% | 19.4% | 19.5% | +0.1 | 1/505 | 62/821 |
| office | 24.9% | 24.9% | 24.9% | 0.0 | 0/504 | 79/975 |
| cafe | 40.9% | 40.9% | 40.9% | 0.0 | 1/494 | 60/804 |
| outdoor | 38.7% | 38.7% | 38.7% | 0.0 | 0/500 | 66/713 |
| rub | 26.7% | 26.7% | 26.7% | 0.0 | 0/505 | 86/772 |
| day | 28.0% | 28.0% | 28.1% | +0.1 | 0/321 | 89/595 |
| reazon_clean | 9.7% | 9.7% | 9.7% | 0.0 | 0/150 | 39/225 |

正解区間で切った場合の CER も outdoor の +0.2（27.7% → 27.9%）以外は同じ（clean 12.5%、wrist 15.7% …）。

### 4.3 英語・日英混在（採用方式 D）

| 条件 | 英語 WER（正解区間で切った場合） | 日本語 CER（オフ） | 言語の誤り |
| --- | ---: | ---: | ---: |
| english_clean | 10.2% (9.0%) | - | en 0/60 |
| english_wrist | 11.7% (11.4%) | - | en 1/60 |
| mixed_clean | 11.0% (7.9%) | 9.9%（9.9%） | ja 0/114, en 1/60 |
| mixed_wrist | 14.1% (10.0%) | 11.8%（11.8%） | ja 0/114, en 0/60 |

- 正解区間で切った WER（SenseVoice の英語の実力。M0-5 の FLEURS en 9.0% と一致）との差は、VAD による区間の切り方（英文が途中で分かれる、分かれた断片が Zipformer の出力のまま残る）による。
- オフでは英語の発話はすべて日本語として保存され、WER は 100%（Zipformer は英文に「はい」「そう」やカタカナの別の語しか出さない）。

### 4.4 調整の経緯（日本語の誤振り分けを減らす）

最初の版（1.5 文字/秒未満の区間だけ SenseVoice、英語と判定されラテン文字が半分以上なら採用）では、日本語条件で 14 区間が英語として保存された（day 28.0% → 28.4%）。中身は短い相づち・雑音が 1〜3 語の英語になったもの（「はーい」→「How are you.」、「うん」→「The.」）と、かなとラテン文字が混ざった出力（「どもお願 guys.」）だった。英語の条件で英語として保存された文はすべてラテン文字だけで、1〜2 語の文は英文の断片（「On August.」）か日英混在の相づち（「Yeah.」）だったので、**英語として採用するのは 3 語以上・ラテン文字 9 割以上**に絞った（`LanguageRouting.minWords`、`minLatinShare`）。また Zipformer が英語を密なカタカナで書く場合（「スピーザコニー・ロステリス」）を拾うため、**カタカナ・ラテン文字が 8 割以上の区間も SenseVoice で確認**する（`minForeignScriptShare`）ようにした。

## 5. 採用した方式（実装）

- 共通コード（`shared/pipeline`）: `AsrModelSpec.SENSE_VOICE`（`RecognizerKind.SENSE_VOICE`）、`LanguageRouting`（振り分けの規則、純粋関数・単体テストつき）、`senseVoiceLanguage`（`<|en|>` → `en`）。区間ごとの処理は Android（`AndroidSherpaTranscriber`）とハーネス（`SherpaTranscriber`）で同じ `LanguageRouting.route` を使う。保存するセグメントの `lang` が `en` / `ja` になり（DB は変更なし）、エンジン ID は `sherpa-zipformer-ja-reazonspeech-int8+sherpa-sensevoice-small-int8`。
- 誤認識の除去（`FalsePositiveFilter`、#105）は言語によらず全セグメントにかかる。英語の評価で除去により消えた英語の発話はなかった（検出漏れ 0）。
- 設定とダウンロード: 状態タブ「文字起こしの言語」の「英語も文字起こしする」（既定オフ）。オンで Hugging Face の固定コミットから 2 ファイル（239,233,841 + 315,894 バイト、SHA-256 検証、途中再開）を Wi-Fi 待ちでダウンロード（モバイル通信はボタンで明示的に）、オフで削除。詳細は [platforms/android-phone.md §3.3](../platforms/android-phone.md#33-英語設定英語も文字起こしする81)、画面は [platforms/phone-ui.md](../platforms/phone-ui.md)。
- CI: `tools/harness/download-models.sh` が SenseVoice とその `test_wavs/en.wav` も取得し、`EndToEndTest.englishIsTranscribedAsEnglishNextToJapanese` が日本語 → 英語 → 日本語の録音で言語の並び（`ja, en, ja`）と英文の内容を確かめる。モデルのキャッシュのキーはスクリプトのハッシュなので、この変更で一度だけ取り直す（追加 約 240 MB）。

## 6. 限界

- **1 つの発話の中の日英混在**（「このPRをmergeして」「Zoomで共有します」）は区間ごとの振り分けでは解けない。どちらか一方の言語として認識される（多くは日本語として、英単語はカタカナか別の語になる）。話者交代や 0.2 秒以上の間で区間が分かれていれば、区間ごとに言語を選べる。
- 英語の短い返事（「Yeah.」「Thank you.」など 1〜2 語）は英語として採用しない（日本語の相づちの誤認識を防ぐため）。日本語モデルの出力のまま残る。
- 評価の英語は FLEURS（読み上げ）で、日本語は合成音声。実際の英語の会話（くだけた話し方・訛り）での精度、実機での速度・電池・メモリ（2 つのモデルで合計約 400 MB）は #61 で確かめる。
- SenseVoice は中国語・韓国語・広東語も判定できるが、英語以外は日本語モデルの文を使う（`LanguageRouting.languages` で広げられる）。

## 7. iOS の状況

iOS は OS の SpeechAnalyzer / SpeechTranscriber を日本語ロケールで使っており、本件では変更していない（「英語も文字起こしする」の設定は `AppServices.supportsEnglish` が false なので表示されない）。SpeechTranscriber は英語のロケールもあるが、1 つの解析で使えるロケールは 1 つで、言語の自動判定はない。英語に対応するには、区間ごとの言語判定（例: 英語ロケールでも認識して信頼度で選ぶ）が要り、別の Issue で検討する。

## 8. 再現手順

```bash
# 評価データ（日本語 8 条件 + 英語・日英混在 4 条件）
tools/test-audio/download-models.sh
tools/test-audio/.venv/bin/python tools/test-audio/generate.py --reazon-manifest research/asr-benchmark/data/manifest.jsonl
tools/test-audio/.venv/bin/python tools/test-audio/generate.py --out tools/test-audio/cache/out-en \
  --fleurs-manifest research/asr-benchmark/data/manifest.jsonl --only english_clean,english_wrist,mixed_clean,mixed_wrist --no-day
# モデル（SenseVoice を含む）
tools/harness/download-models.sh
# 評価（off = A、on = D、C・B は §4 の値）
./gradlew --max-workers=2 :tools:harness:run --args="evaluate tools/test-audio/cache/out/manifest.json build/eval-on --english on"
./gradlew --max-workers=2 :tools:harness:run --args="evaluate tools/test-audio/cache/out-en/manifest.json build/eval-en-on --english on"
```

発話単位の言語判定（§3.1）と Zipformer の出力密度（§3.2）は `research/asr-benchmark` の venv（`sherpa_onnx` 1.13.8）で、M0-5 の `data/manifest.jsonl` の AAC 24 kbps 条件に対して測った。
