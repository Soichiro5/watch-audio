package app.watchaudio.core.export

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The export of a fixed day (#155), compared in full: what a user (Markdown) and a re-import
 * (JSON) get. A change here changes the export format: update docs/architecture/02 §6.5 and, for
 * the JSON, consider [EXPORT_FORMAT_VERSION].
 */
class ExportGoldenTest {
    @Test
    fun jsonOfAFixedDay() = assertEquals(EXPECTED_JSON, ExportFixtures.day().toJson())

    @Test
    fun markdownOfAFixedDay() = assertEquals(EXPECTED_MARKDOWN, renderDayMarkdown(ExportFixtures.day()))
}

private const val EXPECTED_JSON = """{
    "format": "watch-audio-export",
    "formatVersion": 1,
    "date": "2026-10-10",
    "timeZone": "Asia/Tokyo",
    "dayStart": 1791558000000,
    "dayEnd": 1791644400000,
    "chunks": [
        {
            "id": "c1",
            "sessionId": "session-1",
            "deviceId": "watch-1",
            "source": "wearos",
            "startedAt": 1791583200000,
            "endedAt": 1791583500000,
            "timeZone": "Asia/Tokyo",
            "endReason": "rotation",
            "transcription": "done",
            "audio": {
                "codec": "aac",
                "sizeBytes": 1000,
                "sha256": "ab12",
                "file": "audio/2026-10-10/070000_c1.m4a"
            }
        },
        {
            "id": "c2",
            "sessionId": "session-1",
            "deviceId": "watch-1",
            "source": "wearos",
            "startedAt": 1791583500000,
            "endedAt": 1791583800000,
            "timeZone": "Asia/Tokyo",
            "endReason": "charging",
            "transcription": "done",
            "audio": {
                "codec": "aac",
                "sizeBytes": 1000,
                "sha256": "cd34",
                "purgedAt": 1791673200000
            }
        },
        {
            "id": "c3",
            "sessionId": "session-1",
            "deviceId": "watch-1",
            "source": "wearos",
            "startedAt": 1791586800000,
            "endedAt": 1791587100000,
            "timeZone": "Asia/Tokyo",
            "endReason": "rotation",
            "transcription": "none"
        },
        {
            "id": "c4",
            "sessionId": "session-1",
            "deviceId": "watch-1",
            "source": "wearos",
            "startedAt": 1791601200000,
            "endedAt": 1791601500000,
            "timeZone": "Asia/Tokyo",
            "endReason": "rotation",
            "transcription": "failed",
            "audio": {
                "codec": "aac",
                "sizeBytes": 1000,
                "sha256": "ef56"
            }
        }
    ],
    "segments": [
        {
            "chunkId": "c1",
            "startAt": 1791583220000,
            "endAt": 1791583223000,
            "text": "おはようございます、今日もよろしくお願いします",
            "lang": "ja",
            "engine": "sherpa-onnx"
        },
        {
            "chunkId": "c1",
            "startAt": 1791583380000,
            "endAt": 1791583384000,
            "text": "会議は *10時* から [資料] #重要",
            "lang": "ja",
            "engine": "sherpa-onnx"
        },
        {
            "chunkId": "c2",
            "startAt": 1791583560000,
            "endAt": 1791583562000,
            "text": "了解です",
            "engine": "sherpa-onnx"
        }
    ],
    "marks": [
        {
            "chunkId": "c1",
            "at": 1791583320000
        },
        {
            "chunkId": "c2",
            "at": 1791583770000
        }
    ],
    "gaps": [
        {
            "start": 1791558000000,
            "end": 1791583200000,
            "cause": "stopped",
            "endReason": "user_stop"
        },
        {
            "start": 1791583800000,
            "end": 1791586800000,
            "cause": "stopped",
            "endReason": "charging"
        },
        {
            "start": 1791587100000,
            "end": 1791601200000,
            "cause": "missing"
        },
        {
            "start": 1791601500000,
            "end": 1791644400000,
            "cause": "missing"
        }
    ]
}"""

private const val EXPECTED_MARKDOWN = """---
date: 2026-10-10
time_zone: Asia/Tokyo
recorded_minutes: 20
utterances: 3
marks: 2
gaps: 4
tags:
  - watch-audio
---

# 2026年10月10日(土)

録音 20分（音声 15分 ・ 無音 5分）、発話 3件、マーク 2件

文字起こし失敗 1件のチャンクは、文字起こしがまだ含まれていません。

## 録音できていない時間

- 00:00–07:00 録音停止（手動で停止）
- 07:10–08:00 録音停止（充電中）
- 08:05–12:00 データ欠落（未受信）
- 12:05–24:00 データ欠落（未受信）

## 文字起こし

### 00:00

- 00:00:00–07:00:00 *録音停止（手動で停止）*

### 07:00

- 07:00:20 おはようございます、今日もよろしくお願いします
- 07:02:00 **マーク**
- 07:03:00 会議は \*10時\* から \[資料\] \#重要
- 07:06:00 了解です
- 07:09:30 **マーク**
- 07:10:00–08:00:00 *録音停止（充電中）*

### 08:00

- 08:05:00–12:00:00 *データ欠落（未受信）*

### 12:00

- 12:05:00–24:00:00 *データ欠落（未受信）*

## 音声ファイル

- 07:00–07:05 [070000_c1.m4a](../audio/2026-10-10/070000_c1.m4a)
- 07:05–07:10 保持期間を過ぎて削除済み
- 12:00–12:05 ファイルが見つかりませんでした
"""
