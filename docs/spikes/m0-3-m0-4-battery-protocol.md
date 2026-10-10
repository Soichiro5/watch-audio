# M0-3 / M0-4 電池計測の手順（実機）

Watch での長時間録音が成立するかを判定するための計測手順。
対象 Issue: [#9 watchOS](https://github.com/Soichiro5/watch-audio/issues/9) / [#10 Wear OS](https://github.com/Soichiro5/watch-audio/issues/10)

## 計測用アプリの仕様

Wear OS は M1-4（#16）でスパイク版を本実装（[docs/impl/wearos.md](../impl/wearos.md)）に置き換えた。計測にもその本実装をそのまま使う（装着検出・充電での自動停止/再開も含めた実利用の消費になる）。

| 項目 | watchOS | Wear OS |
| --- | --- | --- |
| 録音形式 | AAC-LC 16kHz mono 32kbps | AAC-LC 16kHz mono **24kbps** |
| 録音方式 | `AVAudioRecorder` + background audio | `MediaRecorder` + 前景サービス（microphone）+ Ongoing Activity。非装着・充電中・電池 5% 以下は自動で一時停止し、条件が戻ると自動再開 |
| チャンク | 5 分ごと（新ファイル開始 → 旧ファイル停止の重ね合わせ方式）・Mark でも区切る | 約 5 分（ファイルサイズ基準・`setNextOutputFile` で無音ギャップなし）。全体が無音のチャンクは音声を捨てる |
| ログ | 5 分ごと + イベント時に CSV へ追記 | 同左（`battery` 行が 5 分ごと。列: `timestamp,battery_pct,charging,mode,pending_uploads,event,detail`。最大 約 1 MB でローテーション） |
| 転送 | ON の場合、閉じたチャンクを iPhone へ `transferFile`（送信成功後に Watch から削除） | 画面の「Send to phone」が ON の場合、閉じたチャンクをスマホへ `ChannelClient`（近接ノードのみ）。スマホの ACK 後に Watch から削除。OFF の間は Watch に溜まる |
| ログの取り出し | 停止時に log.csv を iPhone へ送信 → iPhone の「ファイル」アプリ › このiPhone内 › watch-audio › WatchInbox | `adb pull /sdcard/Android/data/app.watchaudio/files/diagnostics` |

watchOS は画面に「開始時/現在のバッテリー」「消費速度（%/時、15 分経過後に表示）」「チャンク数/送信数」が出る。Wear OS の画面は現在の電池残量・送信待ち件数・最終 ACK 時刻のみなので、消費速度は CSV の `battery` 行から計算する。

## 計測シナリオ

各機種で以下を 1 回ずつ（可能なら 2 回）。**同じ条件をそろえるため、前日にフル充電 → 朝 100% から開始**。

| # | 条件 | 時間 | 目的 |
| --- | --- | --- | --- |
| A | 転送 ON・普段どおり装着して生活（会話あり） | 8〜12 時間 | 実利用の電池消費 |
| B | 転送 OFF・同条件（Wear OS は画面の「Send to phone」を OFF） | 4 時間 | 転送のコスト切り分け |
| C | 録音なし・普段どおり装着 | 4 時間 | ベースライン（Watch 単体の消費） |
| D | 録音中に着信・Siri/アシスタント・アラーム・他アプリで録音 | 各 1 回 | 割り込み時の挙動 |
| E | 録音中にスマホとの接続を切る（機内モード 30 分）→ 戻す | 1 回 | 再送・転送の追従 |
| F | **24 時間チャレンジ**: 朝 100% から録音開始 → 電池が尽きる/止まるまで。充電器に置いたとき・外したときの挙動も記録 | 〜24 時間 | テーマ「24 時間録音」の成立性。1 日 1 回の短時間充電で回せるか、スマホへのハンドオフ（#42）が必要な時間はどれくらいか |

その他の設定（記録しておく）: 常時表示 ON/OFF、省電力モード、ワークアウト自動検出、Wi-Fi 接続、ウォッチフェイス。

## 記録してほしいもの

Issue にコメントで以下の表を貼る（CSV も添付）。

| 機種 | OS | シナリオ | 開始/終了時刻 | 開始/終了バッテリー | %/時 | チャンク欠落・ギャップ | 備考（割り込み・停止の有無） |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Apple Watch Series 11 | watchOS 26.x | A | 08:00–20:00 | 100 → 45 | 4.6 | なし | |

確認ポイント:
- CSV の `battery` 行が **5 分おきに途切れず** 記録されているか（間隔が伸びていれば OS にスリープさせられている）。
- `rotate_fallback` / `silenced` / `error`（watchOS）、`interrupted` / `alert` / `send_failed` / `terminated`（Wear OS）イベントの有無。Wear OS は `mode` 行で一時停止の理由（`paused_off_body` / `paused_charging` など）も分かる。
- 録音ファイルを数個聞いて、チャンクの境目で音が欠けていないか。
- 割り込み後に `interruption_ended resumed=true/false` のどちらになったか（watchOS）。

## 判定基準（Issue 記載の再掲）

- watchOS: Series 10 以降で **10 時間以上** 録音して残量 30% 以上 → Go
- Wear OS: 主要 2 機種で **8 時間以上** 録音して残量 20% 以上 → Go
  - 満たない場合は「対応機種の限定」「発話検出時のみ録音（VAD 起動）」「ビットレート低減（24kbps）」の代替案で判断

## インストール方法

[docs/04-dev-environment.md](../04-dev-environment.md#3-ビルド手順) を参照。CI（Android & shared）の成果物 `debug-apks` からも APK を取得できる。

```bash
adb -s <watch> install wearApp-debug.apk
adb -s <phone> install androidApp-debug.apk
```

> 録音する際は、周囲の人に録音していることを伝え、同意を得ること。録音ファイルはリポジトリにコミットしない。
