package app.watchaudio.core.embedding

import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.testing.testManifest

/**
 * A deterministic stand-in for the embedding model: the normalized counts of character bigrams
 * hashed into [dimension] buckets, so texts sharing words are close. One token per character.
 */
class FakeEmbedder(
    override val spec: EmbeddingModelSpec = EmbeddingModelSpec("fake-model", 32, "q:", "d:", 512),
) : Embedder {
    val documents = mutableListOf<String>()
    var releases = 0

    /** Texts whose embedding throws (a broken passage). */
    val failing = mutableSetOf<String>()

    override suspend fun countTokens(text: String): Int = text.length

    override suspend fun embedDocuments(texts: List<String>): List<FloatArray> = texts.map { text ->
        if (failing.any { it in text }) error("cannot embed")
        documents += text
        vector(text)
    }

    override suspend fun embedQuery(text: String): FloatArray = vector(text)

    override suspend fun release() {
        releases++
    }

    fun vector(text: String): FloatArray {
        val v = FloatArray(spec.dimension)
        for (i in 0 until text.length - 1) v[((text[i].code * 31 + text[i + 1].code) and 0x7fffffff) % spec.dimension] += 1f
        if (text.length == 1) v[text[0].code % spec.dimension] += 1f
        return OnnxEmbedder.normalize(v)
    }
}

/** Stores a transcribed chunk [id] at [startedAt] with one segment per text, 2 s apart. */
suspend fun LocalStore.transcribedChunk(id: String, startedAt: Long, vararg texts: String, receivedAt: Long = startedAt) {
    transaction {
        val manifest = testManifest(id, startedAt)
        insertChunk(manifest, manifest.audio?.fileName, receivedAt)
        saveTranscript(id, texts.mapIndexed { i, text -> NewSegment(startedAt + i * 2_000L, startedAt + i * 2_000L + 1_500, text, "ja") }, "fake")
    }
}
