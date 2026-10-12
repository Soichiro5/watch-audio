package app.watchaudio.core.protocol

// Mirrors shared/protocol/testdata/*.json; update both together.
// The watchOS app's tests decode the same files to guarantee wire compatibility.
internal object TestVectors {
    const val MANIFEST_V1: String = """{
  "schemaVersion": 1,
  "chunkId": "6F9619FF-8B86-D011-B42D-00C04FC964FF",
  "sessionId": "2B1A3C4D-0000-4000-8000-000000000001",
  "deviceId": "watch-device-01",
  "source": "watchos",
  "startedAtEpochMs": 1791622800000,
  "durationMs": 300000,
  "timeZone": "Asia/Tokyo",
  "audio": {
    "fileName": "6F9619FF-8B86-D011-B42D-00C04FC964FF.m4a",
    "codec": "aac",
    "sampleRateHz": 16000,
    "channels": 1,
    "bitrateBps": 24000,
    "sizeBytes": 901234,
    "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
  },
  "markOffsetsMs": [12000, 240500],
  "endReason": "rotation"
}
"""

    const val MANIFEST_V1_SILENT: String = """{
  "schemaVersion": 1,
  "chunkId": "0B3E8D0A-1C2B-4D5E-8F90-A1B2C3D4E5F6",
  "sessionId": "2B1A3C4D-0000-4000-8000-000000000001",
  "deviceId": "watch-device-01",
  "source": "wearos",
  "startedAtEpochMs": 1791623100000,
  "durationMs": 120000,
  "timeZone": "Asia/Tokyo",
  "endReason": "charging"
}
"""

    const val ACK_V1: String = """{
  "schemaVersion": 1,
  "chunkId": "6F9619FF-8B86-D011-B42D-00C04FC964FF",
  "status": "stored"
}
"""

    const val RECORDING_FORMAT: String = """{
  "codec": "aac",
  "sampleRateHz": 16000,
  "channels": 1,
  "bitrateBps": 24000,
  "chunkDurationMs": 300000
}
"""

    const val RECORDER_RULES: String = """{
  "lowBatteryStopPercent": 10,
  "lowBatteryResumePercent": 15,
  "silencePeakDbfs": -40,
  "silenceMinCoveragePercent": 80
}
"""

    /** Every constant above by its file name in testdata/ (checked against the files in jvmTest). */
    val FILES: Map<String, String> = mapOf(
        "manifest-v1.json" to MANIFEST_V1,
        "manifest-v1-silent.json" to MANIFEST_V1_SILENT,
        "ack-v1.json" to ACK_V1,
        "recording-format.json" to RECORDING_FORMAT,
        "recorder-rules.json" to RECORDER_RULES,
    )
}
