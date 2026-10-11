package app.watchaudio.core.db

import app.watchaudio.testing.testAudio
import app.watchaudio.testing.testManifest

/**
 * Stores a [testManifest] chunk [id] starting at [startedAt], with its audio at `<id>.m4a`, or
 * as a silent chunk if [audio] is false. @return true if inserted.
 */
internal fun StoreTransaction.registerChunk(
    id: String,
    startedAt: Long,
    audio: Boolean = true,
    receivedAt: Long = 0,
    marks: List<Long> = emptyList(),
): Boolean {
    val manifest = testManifest(id, startedAt, audio = if (audio) testAudio(id) else null, marks = marks)
    return insertChunk(manifest, manifest.audio?.fileName, receivedAt)
}

/** [StoreTransaction.registerChunk] in its own transaction. */
internal suspend fun LocalStore.registerChunk(
    id: String,
    startedAt: Long,
    audio: Boolean = true,
    receivedAt: Long = 0,
    marks: List<Long> = emptyList(),
): Boolean = transaction { registerChunk(id, startedAt, audio, receivedAt, marks) }
