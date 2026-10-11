# M0-6 スパイク結果（デスクトップ編）: 多言語埋め込みモデルとブルートフォース検索

- Issue: [#12 [M0-6] [Spike] 多言語埋め込みモデルの KMP 実行](https://github.com/Soichiro5/watch-audio/issues/12)
- 実施日: 2026-10-10
- 再現用スクリプト: [`research/embedding-benchmark/`](../../research/embedding-benchmark/README.md)
- 生データ: `research/embedding-benchmark/results/*.json`、自動生成の表: [`results/summary.md`](../../research/embedding-benchmark/results/summary.md)

実機は使っていない。デスクトップの CPU で品質（モデル間の相対比較）と速度（スマホ性能の推定のもと）を測った。実機で確認する項目は末尾のチェックリストにまとめた。

## TL;DR

1. **推奨モデルは `cl-nagoya/ruri-v3-30m`（ONNX int8、256 次元、Apache-2.0）**。日本語の検索品質は e5-small を上回った（会話セット R@1 0.74 vs 0.54、JaGovFAQs MRR@10 0.89 vs 0.85）。サイズは 1/3（int8 で 37 MB）、速度は約 1.5 倍、ベクトルは 2/3 の大きさで済む。トークナイザは正規化なしの SentencePiece Unigram なので、純 Kotlin 実装で tokenizer.json と完全に一致させられる。
   - 弱点: 英語クエリ → 日本語発話のクロスリンガル検索（5 問中 2 問）。英語→英語は e5 と同等（0.9）。英語の比重が高いと分かったら `multilingual-e5-small` int8 に切り替える（`model_id` 列があるので再埋め込みで移行できる）。
   - 品質の上限は `embeddinggemma-300m`（会話 R@5 0.97）。ただし 300M パラメータで、int8 でも 309 MB、速度は ruri の 10〜20 倍遅い。ライセンスは Gemma Terms で、今回はモバイルの既定から外した。
2. **int8 量子化は 2 種類とも採用してよい**。int8 ベクトル（ベクトルごとの scale による対称スカラー量子化）では、どのモデルでも Recall/MRR の差が ±0.01 以内だった。int8 の ONNX モデル（動的量子化）は R@1/MRR が 0〜6pt 下がるが、R@5/R@10 はほぼ変わらない。速度は fp32 の 2〜4 倍出る。
3. **1 日分（3,000 セグメント）の埋め込みは目標の 5 分を大きく下回る**。ruri int8 はコンテナの 1 スレッドで約 7 秒、スマホ換算（2〜3 倍遅いと仮定）で約 15〜25 秒。e5-small int8 は約 10 秒、スマホ換算で 20〜35 秒。
4. **ブルートフォース検索は 50 万件なら素朴な Kotlin のループで目標の 300 ms に届く見込み**。ただし年間の件数（3,000/日 × 365 ≒ 110 万件）では、マルチスレッド化か NEON（SDOT）のネイティブカーネルが要る。ANN はまだ要らない。必要なのは次の 3 点。
   - SQLite から毎回 BLOB を読み出さず、mmap したフラットなベクトル配列を走査する（SQLite から 50 万行を全読みすると数秒かかる）。
   - 4 スレッドで分割して走査する。
   - 100 万件を超えたらネイティブの SIMD カーネル、または 2 段構成（1-bit 粗選別 → int8 で再スコア）にする。

## 1. 環境

| 項目 | 値 |
|---|---|
| CPU | Intel Xeon (Sapphire Rapids) @ 2.1 GHz、4 vCPU（AVX-512 VNNI / AMX あり）、RAM 15 GB |
| ソフトウェア | Python 3.13 / onnxruntime 1.31.0 (CPU EP) / tokenizers 0.23.3 / numpy 2.5.3 / OpenJDK 21.0.12 |
| 注意 | 同じコンテナで Gradle ビルドと ASR ベンチマークが並行して動いており、load average は 8〜17 だった。速度はすべて「N 回中の最良値」で記録した。**4 スレッドの値は CPU の取り合いで 1 スレッドより遅く出ており、信頼できない**。下の推定には 1 スレッドの値を使う |

## 2. 候補モデル

| モデル | ONNX の出典 | パラメータ数 | 次元 | 最大トークン | ONNX fp32 / int8 | トークナイザ | ライセンス |
|---|---|---|---|---|---|---|---|
| **ruri-v3-30m** (`cl-nagoya/ruri-v3-30m`) | `onnx-community/ruri-v3-30m-ONNX` | 37M（うち語彙埋め込み 26M） | 256 | 8192 | 147 / **37 MB** | SentencePiece **Unigram**、102k 語彙、**normalizer なし**、Metaspace | Apache-2.0 |
| multilingual-e5-small (`intfloat/…`) | `Xenova/multilingual-e5-small` | 118M（うち語彙埋め込み 96M） | 384 | 512 | 470 / 118 MB | SentencePiece Unigram（XLM-R、250k 語彙）、Precompiled(NFKC) と連続空白の置換 | MIT |
| paraphrase-multilingual-MiniLM-L12-v2 | `Xenova/…` | 118M | 384 | 128 | 470 / 118 MB | XLM-R と同じ | Apache-2.0 |
| embeddinggemma-300m (`google/…`) | `onnx-community/embeddinggemma-300m-ONNX` | 308M（うち語彙埋め込み 約 200M） | 768（MRL で 512/256/128 に切り詰め可） | 2048 | 1235 / 309 MB（q4 なら 197 MB） | SentencePiece **BPE**（Gemma3、262k 語彙、byte fallback） | Gemma Terms of Use（禁止用途ポリシーあり。HF 本家はゲート付き） |
| bge-m3（品質の上限としてのみ） | `Xenova/bge-m3` | 568M | 1024 | 8192 | 2267 / 570 MB | XLM-R と同じ | MIT |

- int8 は HF リポジトリにある `onnx/model_quantized.onnx`（動的量子化）を使った。e5 は「query: 」「passage: 」、ruri は「検索クエリ: 」「検索文書: 」、gemma は「task: search result | query: 」「title: none | text: 」のプレフィックスを付けた。
- 小型の XLM-R 系モデルは、パラメータの 8 割が 250k 語彙の埋め込み行列。日英だけに語彙を絞れば ONNX を大幅に小さくできる余地がある（未検証）。

## 3. 品質評価

### 評価セット

| セット | 内容 |
|---|---|
| **会話セット（自作）** `data/conv_eval.json` | クエリ 50 件。内訳は日本語キーワード 11、日本語言い換え 19、英語 10、日→英 5、英→日 5。発話セグメント 2,136 件のうち、正解 46、ハードネガティブ 90（同じ語彙で別の話題。例「歯医者ってほんと苦手」）、テンプレートで作った雑談フィラー 2,000（クエリと重ならない話題で、ASR 風のフィラーや句読点の欠落を含む）。クエリは「歯医者の予約いつだっけ」「先週話した旅行の件」「サーバー移行の日程（→英語の発話）」など |
| JaGovFAQs-22k のサブセット | JMTEB から取得。クエリ 150 件、文書 1,000 件（200 字で切り詰め） |
| JSTS（検証セット 500 ペア） | コサイン類似度とスコアの Spearman 相関 |

自作セットはクエリが 50 件しかないので、1 問の差が R@k で 0.02 動く。クロスリンガルは各 5 問だけで、傾向を見る程度の精度しかない。

### 結果（fp32 ベクトル。int8 ベクトルとの差は表の下に記載）

| モデル | ONNX | 会話 R@1 | 会話 R@5 | 会話 R@10 | 会話 MRR@10 | Gov R@1 | Gov R@10 | Gov MRR@10 | JSTS ρ |
|---|---|---|---|---|---|---|---|---|---|
| embeddinggemma-300m | fp32 | **0.84** | **0.97** | **0.97** | **0.897** | 0.827 | 0.973 | 0.878 | **0.833** |
| bge-m3（上限） | fp32 | 0.70 | 0.92 | 0.92 | 0.791 | 未計測 | | | 未計測 |
| **ruri-v3-30m** | fp32 | 0.76 | 0.84 | 0.86 | 0.797 | **0.860** | 0.967 | **0.904** | 0.797 |
| **ruri-v3-30m** | **int8** | 0.74 | 0.82 | 0.84 | 0.783 | 0.840 | 0.973 | 0.887 | 0.785 |
| multilingual-e5-small | fp32 | 0.60 | 0.78 | 0.86 | 0.690 | 0.800 | 0.940 | 0.854 | 0.780 |
| multilingual-e5-small | int8 | 0.54 | 0.80 | 0.84 | 0.657 | 0.787 | 0.953 | 0.845 | 0.779 |
| mMiniLM-L12 | fp32 | 0.52 | 0.70 | 0.80 | 0.596 | 0.613 | 0.813 | 0.688 | 0.753 |
| mMiniLM-L12 | int8 | 0.48 | 0.72 | 0.80 | 0.570 | 0.593 | 0.820 | 0.673 | 0.750 |

（bge-m3 は CPU の取り合いで時間がかかりすぎたため、会話セットだけを評価した。embeddinggemma の int8 は品質を評価していない。）

**int8 ベクトルの影響**: 全モデル・全セットで、int8 ベクトルと fp32 ベクトルの差は R@k で ±0.02、MRR で ±0.01 以内。上下どちらにも振れており、劣化はない。たとえば ruri fp32 の Gov MRR は 0.904 から 0.907、e5 fp32 の会話 R@5 は 0.78 から 0.80 になった。保存形式は計画どおり **int8 BLOB とベクトルごとの scale** でよい。

**クエリ種別ごとの会話 R@5**

| モデル | 日本語キーワード | 日本語言い換え | 英語 | 日→英 | 英→日 |
|---|---|---|---|---|---|
| embeddinggemma-300m | 1.00 | 0.92 | 1.00 | 1.00 | 1.00 |
| bge-m3 | 1.00 | 0.79 | 1.00 | 1.00 | 1.00 |
| ruri-v3-30m fp32 / int8 | 1.00 / 1.00 | 0.79 / 0.74 | 0.90 / 0.90 | 1.00 / 1.00 | **0.40 / 0.40** |
| e5-small fp32 / int8 | 1.00 / 1.00 | 0.79 / 0.79 | 0.90 / 0.90 | **0.00 / 0.20** | 0.80 / 0.80 |
| mMiniLM-L12 fp32 | 0.73 | 0.53 | 0.90 | 1.00 | 0.60 |

- e5-small は同じ言語の文書を強く優先する。日本語クエリに対して、英語の正解より語彙が近い日本語の別件を上位に出した（例: 「契約書のチェック」→「契約社員の更新って来年だっけ」）。
- ruri-v3 は、英語クエリを投げると英語のフィラーが上位に来やすい。
- 外した例はすべて `results/quality_*.json` の `conv_misses` に入っている。

## 4. 速度

### 4.1 埋め込みのスループット（20〜60 字の日本語セグメント、平均 29 字。ruri・e5・mMiniLM は 96 件、gemma は 48 件）

| モデル | ONNX | 1 スレッド、バッチ 1 (ms/件) | 1 スレッド、バッチ 16 (ms/件) | 3,000 件（コンテナ、最良構成） | 3,000 件（スマホ推定 ×2〜3） |
|---|---|---|---|---|---|
| **ruri-v3-30m** | **int8** | 2.4 | 2.1 | **約 6.4 秒** | **約 13〜20 秒** |
| ruri-v3-30m | fp32 | 4.5 | 4.5 | 約 13 秒 | 約 30〜40 秒 |
| e5-small | int8 | 3.5 | 4.1 | 約 10.5 秒 | 約 20〜35 秒 |
| e5-small | fp32 | 15.5 | 10.6 | 約 32 秒 | 約 65〜100 秒 |
| mMiniLM-L12 | int8 | 3.5 | 3.8 | 約 10.5 秒 | 約 20〜35 秒 |
| embeddinggemma-300m | fp32 | 77.5 | 49.8 | 約 150 秒 | **約 5〜7.5 分（目標超過）** |
| embeddinggemma-300m | int8 | 292 | 85 | 約 254 秒 | x86 では int8 の方が遅い（量子化グラフの演算子構成による） |

- 平均トークン長は ruri が 16.9、e5 が 22.7、gemma が 26.0（プレフィックスを含む）。ruri の語彙は日本語向けなので、同じ文でもトークン数が少ない。
- スマホの推定は「スマホ 1 コアはこのコンテナの 1 コアより 2〜3 倍遅い」と仮定した。実際には big コアを 2〜4 スレッド使えるので、さらに速くなる余地がある。トークナイズの時間（Python の Rust 実装）も含めている。
- 結論として、1 日分の埋め込みは充電中の夜間ジョブに限らず、数分おきの増分処理でも十分間に合う。

### 4.2 ブルートフォース検索（int8 × int8 → int32 で累積し、scale を掛けて top-50）

**Java 21（HotSpot）、`java/SearchBench.java`。単位 ms、min / median**

| N（384 次元） | スカラーのループ 1 スレッド | 4 アンロール 1 スレッド | スカラー 4 スレッド | Vector API 1 スレッド | Vector API 4 スレッド |
|---|---|---|---|---|---|
| 100k | 23.5 / 25.4 | 15.8 / 20.6 | 14.0 / 21.1 | 5.8 / 9.4 | 2.5 / 2.8 |
| **500k** | **96.9 / 125.5** | 89.5 / 95.1 | 94.4 / 136.4 | 34.9 / 35.9 | 19.2 / 27.2 |
| 1M | 177.7 / 242.3 | 173.4 / 181.5 | 214.3 / 247.2 | 68.9 / 72.9 | 48.6 / 53.1 |

**numpy（参考）**: 500k では、int8 を int32 に変換して matmul するとチャンク単位で 206 ms。float32 に変換して BLAS の sgemv を使うと 104 ms。1M ではそれぞれ 415 ms と 222 ms（1 スレッド）。

**スマホでの推定（500k × 384、1 クエリ）**

| 実装 | 推定 | 根拠 |
|---|---|---|
| Kotlin/JVM on ART、素朴なループ 1 スレッド | 200〜450 ms | HotSpot の 97〜125 ms × CPU 差 2〜3 倍 × ART と HotSpot の差 1〜1.5 倍。ART には Vector API がなく、自動ベクトル化も限定的 |
| 同じ実装を 4 スレッドに分割 | 70〜150 ms | big と mid のコアに行範囲を分割する |
| Kotlin/Native (iOS)、素朴なループ | 100〜250 ms | LLVM が NEON に自動ベクトル化する可能性がある。Apple の P コアは速い |
| C/NEON の SDOT カーネル（JNI / cinterop） | 20〜40 ms | Vector API で 35 ms（1 スレッド）出ることから類推 |
| ruri（256 次元）の場合 | 上記の約 2/3 | 計算量は次元に比例する |

**目標「50 万件で 300 ms 未満」は、マルチスレッドの素朴な Kotlin 実装で満たせる見込み**。1 年分（約 110 万件）ではネイティブカーネルか 256 次元のモデルが必要。

### 4.3 SQLite から読む場合のコスト

50 万行（384 B の BLOB）を `SELECT vector FROM segment_embedding` で全件読むと 3.1 秒かかった（Python sqlite3。オーバーヘッド込み）。DB は 205 MB で、1 行あたり 411 B。mmap したフラットファイルなら、開いて触るまで 6 ms。**クエリのたびに SQLite から全件読むのは不可**なので、メモリ上の配列か mmap のキャッシュが必須。50 万件 × 384 B = 192 MB をヒープに載せるのは iOS のメモリ制限（jetsam）上も危ない。**mmap（クリーンページなので OS が回収できる）を推奨**する。

## 5. ストレージの見積もり（3,000 セグメント/日 × 365 日 ≒ 110 万件/年）

| 形式 | 1 件あたり | 1 年 | 50 万件 |
|---|---|---|---|
| fp32、384 次元 | 1,536 B | 1.68 GB | 768 MB |
| **int8、384 次元 + scale（e5）** | 388 B | **425 MB** | 194 MB |
| **int8、256 次元 + scale（ruri）** | 260 B | **285 MB** | 130 MB |
| 1-bit、384 次元（粗選別用） | 48 B | 53 MB | 24 MB |
| SQLite の行オーバーヘッド | 約 25 B | +27 MB | |

ベクトルは本文から再計算できる派生データなので、正本を次の 2 通りから選べる。

- (a) SQLite の BLOB を正本とし、起動時か増分でフラットファイル（mmap 用）を作る。ストレージは 2 倍になる。
- (b) モデルごとの追記専用フラットファイルを正本とし、SQLite には (segment_id, model_id, row_index, scale) だけを持つ。

**(b) を推奨**する。破損したら再埋め込みで復旧すればよい。

## 6. オンデバイス統合の調査メモ（実機検証なし）

### 6.1 トークナイザ

`scripts/check_tokenizer.py` で、簡略化した実装が tokenizer.json の結果とどれだけずれるかを、3,340 テキストについて測った。

| モデル | HF の normalizer | NFKC だけ | NFKC + 空白正規化（\s→空白、連続空白を 1 つに） | normalizer なし | sentencepiece ライブラリ（.model） |
|---|---|---|---|---|---|
| ruri-v3-30m | なし | 17.6% | 21.1% | **0.00%（完全一致）** | 0.00% |
| e5-small / bge-m3 | Precompiled + Replace | 9.6% | **0.39%** | 21.1% | 0.09%（連続空白の置換分）。id は fairseq 流に +1 ずらす |
| mMiniLM-L12 | Precompiled | 0.39% | 0.39% | 16.5% | 0.00% |
| embeddinggemma | Replace(" "→"▁") | 43.8% | 43.8% | 31.2% | 0.00% |

選択肢は次の 4 つ。

1. **純 Kotlin の Unigram（Viterbi）実装を commonMain に置く**（推奨）。tokenizer.json から語彙とスコアを取り出したアセット（ruri は 102k 語彙で約 2 MB）を同梱し、Metaspace の前処理と Viterbi を 200 行程度で書く。Android と iOS で同じコードになり、ネイティブ依存も増えない。
   - ruri は正規化がないので完全一致させられる。Python で作ったゴールデン（テキスト → id 列）を共通テストに入れる。
   - e5 の場合は NFKC が要る。Android は `java.text.Normalizer`、iOS は `precomposedStringWithCompatibilityMapping` を expect/actual で呼ぶ。今回の測定では 0.4% の文でずれ、残りは機種依存文字などの稀なケースだった。
2. **onnxruntime-extensions の `SentencepieceTokenizer` 演算子をグラフに埋め込む**。gen_processing_models で tokenizer グラフを作り、onnx.compose で結合する。sentencepiece の C++ 実装と同じ結果になる。ただし Android は `onnxruntime-extensions-android` の AAR、iOS は `onnxruntime-extensions-c` の pod が追加で要り、カスタム op の登録も要る。XLM-R の id オフセット（+1）や BOS/EOS もグラフ側で処理する必要がある。
3. **HF tokenizers（Rust）を JNI や uniffi 経由で使う**。tokenizer.json との一致は確実だが、Rust のクロスビルド（arm64-v8a と iOS の xcframework）とバイナリサイズのコストがかかる。
4. **iOS だけ swift-transformers の `Tokenizers` を使う**。tokenizer.json を読める。Unigram への対応状況はバージョンで要確認。Kotlin と Swift で実装が二重になるので、1 より優先度は低い。

### 6.2 ONNX Runtime の KMP 組み込み

- **Android**: `com.microsoft.onnxruntime:onnxruntime-android`（フル版の AAR）を androidMain の `actual class Embedder` から Java API（`OrtEnvironment` / `OrtSession`）で呼ぶ。ruri は ModernBERT 構成（RoPE、局所と大域のアテンション）なので、op を削った Mobile パッケージではなくフル版で動作確認する。
- **iOS**: 公式は CocoaPods の `onnxruntime-objc` / `onnxruntime-c`、または SPM の `onnxruntime-swift-package-manager`。KMP から使う方法は 2 つある。
  - (A) iosApp 側に Swift で Embedder を実装し、Kotlin の interface として shared に注入する（Transcriber と同じパターン）。SPM をそのまま使えるので、**MVP はこちらを推奨**。
  - (B) `onnxruntime-c` の C API を cinterop で直接呼ぶ。.def ファイルとヘッダ、xcframework のリンク設定が要る。SPM のパッケージは cinterop から直接読めないので、CocoaPods プラグインか、手動で配置した xcframework が要る。
- 量子化: HF の `model_quantized.onnx` は x86 向けの設定のことがある。ARM 向けには `onnxruntime.quantization.quantize_dynamic(weight_type=QInt8, per_channel=True)` で作り直したものと実機で比べる。embeddinggemma の int8 が x86 で fp32 より遅かったことからも、量子化版の速度は実機で必ず測る。
- EP は CPU（XNNPACK / 既定）で十分。モデルが小さいので NNAPI や CoreML EP は不要。

### 6.3 iOS の `NLContextualEmbedding` を代わりに使う案

- iOS 17 以降の Apple 標準モデル。モデルはスクリプト単位（Latin / CJK など）に分かれ、アセットはオンデマンドでダウンロードされる。iOS では 512 次元とされるが、実行時に `dimension` で確認する。
- 返るのはトークンごとの文脈ベクトルで、文ベクトルは mean pooling で自前で作る。検索用に対照学習されたモデルではないので、e5 や ruri より検索品質が大きく劣る可能性が高い（Mac がなく、今回は評価できなかった）。
- 結論: プラットフォームごとにベクトルが違うこと自体は、インデックスが端末ごとなので問題ない。ただし品質の根拠がないため**主案にはしない**。iOS 実機で同じ会話セットを評価し、ruri との差が小さければアプリサイズを削る案として再検討する。

## 7. 推奨

| 項目 | 推奨 |
|---|---|
| モデル | **ruri-v3-30m**（int8 ONNX 37 MB、256 次元）。プレフィックスは「検索クエリ: 」「検索文書: 」。代替は multilingual-e5-small の int8。将来の高品質オプションは embeddinggemma-300m（q4 を実機で評価してから） |
| 量子化 | モデルは int8 の動的量子化（ARM 向けに作り直したものと比較）。保存はベクトルごとの scale を付けた int8 |
| トークナイザ | commonMain に純 Kotlin の SentencePiece Unigram（Metaspace + Viterbi）を書き、Python で作ったゴールデンで tokenizer.json との完全一致をテストする |
| ランタイム | Android は onnxruntime-android の AAR。iOS は onnxruntime の SPM / pod を Swift で包んで Kotlin の interface に注入する |
| 検索 | フラットな int8 配列を mmap し、Kotlin の 4 スレッド・ブルートフォースで走査して top-K を取り、FTS5 と RRF で統合する。期間フィルタ（直近 N か月など）があれば行範囲で絞る |
| 切り替える条件 | 実機の p95 が 300 ms を超えるか、ベクトルが 100 万件を超えたら、次の順で対処する: ① C/NEON の SDOT カーネル（JNI / cinterop）、② 1-bit で粗選別して int8 で再スコア、③ sqlite-vec など。sqlite-vec も基本はブルートフォースで、利点は SIMD と管理のしやすさ。iOS のシステム SQLite は拡張を読み込めないので、SQLite の同梱が必要になる。ANN（HNSW / IVF）は数百万件規模になるまで不要 |

`docs/architecture/02-architecture.md` は埋め込みモデルを「multilingual-e5-small」と書いているので、実機検証のあとで更新を提案したい。データモデルの `segment_embedding.vector` についても、§5 の (b)（フラットファイルを正本にする案）を検討してほしい。

## 8. 実機検証チェックリスト

- [ ] Android（ミドルレンジ、例 Pixel 7a 相当）と iPhone（A15 以降）で ruri-v3-30m int8 と e5-small int8 の ONNX をロードし、ロード時間とメモリのピーク（RSS / phys_footprint）を測る
- [ ] 3,000 件（20〜60 字）の埋め込み時間を 1/2/4 スレッドとバッチ 1/16 で測り、目標 < 5 分を確認する。発熱とバッテリー消費（Android は Battery Historian、iOS は Instruments の Energy）、バックグラウンド実行（WorkManager / BGProcessingTask）で完走するかも見る
- [ ] HF の `model_quantized.onnx` と、ARM 向けに作り直した int8（QInt8、per-channel）を比べる。速度に加え、Python fp32 とのコサイン類似度が 0.99 以上であることを確認する
- [ ] 純 Kotlin のトークナイザのゴールデンテスト（会話セットと JaGovFAQs の全テキストで id が完全一致すること）を Android と iOS の両方で通す。全角・半角や絵文字の扱いも見る
- [ ] Kotlin の埋め込み結果と Python の結果を突き合わせる（同じ文でコサイン類似度 0.999 以上）。デバイス上で会話セットの R@5 を測り、デスクトップと同じ値になるか確認する
- [ ] ブルートフォース検索を 100k / 500k / 1M × 256 / 384 次元で測る（Kotlin 1 スレッドと 4 スレッド、mmap 経由で p50/p95）。目標は 500k で < 300 ms。ART と Kotlin/Native の差も記録する
- [ ] （必要なら）JNI / cinterop で NEON SDOT カーネルを書き、Kotlin のループと比べる
- [ ] mmap したベクトルファイルの増分追記、クラッシュ後の整合性（SQLite との row_index の対応）、再埋め込みによる復旧を確認する
- [ ] iOS の `NLContextualEmbedding`（mean pooling）で会話セットの R@5 を測り、ruri と比べる
- [ ] アプリサイズへの影響を測る（ORT の AAR / xcframework とモデルとトークナイザのアセット）。e5 を選ぶ場合は語彙の刈り込みを検討する
- [ ] 英語の利用比率が高いユーザーを想定し、英→日のクロスリンガル検索（ruri の弱点）がハイブリッド検索（FTS5 trigram と RRF）で十分補えるかを確認する
