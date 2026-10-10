package app.watchaudio.core.protocol

// Mirrors shared/core-protocol/testdata/*.json; update both together.
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
}
