package app.watchaudio.app.logic

import app.watchaudio.core.db.ChunkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TranscriptEntriesTest {
    @Test
    fun segmentsGetPlaybackOffsetsWithinTheirChunk() {
        val chunks = listOf(chunk("a", at(9)))
        val entries = buildTranscriptEntries(chunks, listOf(segment(1, "a", at(9) + 12_000)))
        val utterance = assertIs<TranscriptEntry.Utterance>(entries.single())
        assertEquals("a.m4a", utterance.audioPath)
        assertEquals(12_000, utterance.offsetMs)
    }

    @Test
    fun purgedAudioKeepsTheTranscriptButCannotBePlayed() {
        val chunks = listOf(chunk("a", at(9)).copy(audioPurgedAt = at(12)))
        val entries = buildTranscriptEntries(chunks, listOf(segment(1, "a", at(9) + 12_000, "残っている発話")))
        val utterance = assertIs<TranscriptEntry.Utterance>(entries.single())
        assertEquals("残っている発話", utterance.segment.text)
        assertNull(utterance.audioPath)
    }

    @Test
    fun untranscribedChunksAreGroupedAndInterleaved() {
        val chunks = listOf(
            chunk("t1", at(9)),
            chunk("p1", at(9, 5), state = ChunkState.RECEIVED),
            chunk("s", at(9, 10), audio = false),
            chunk("p2", at(9, 15), state = ChunkState.RECEIVED),
            chunk("f1", at(9, 20), state = ChunkState.FAILED, lastError = "decode"),
            chunk("t2", at(9, 25)),
            chunk("p3", at(9, 30), state = ChunkState.TRANSCRIBING),
        )
        val segments = listOf(segment(1, "t1", at(9, 1)), segment(2, "t2", at(9, 26)))

        val entries = buildTranscriptEntries(chunks, segments)

        assertEquals(listOf("s1", "cp1", "cf1", "s2", "cp3"), entries.map { it.key })
        val pending = entries[1] as TranscriptEntry.Untranscribed
        assertEquals(TranscriptEntry.Untranscribed.Kind.PENDING, pending.kind)
        // The silent chunk in between does not split the group.
        assertEquals(listOf("p1", "p2"), pending.chunkIds)
        assertEquals(at(9, 5), pending.from)
        assertEquals(at(9, 20), pending.to)
        val failed = entries[2] as TranscriptEntry.Untranscribed
        assertEquals(TranscriptEntry.Untranscribed.Kind.FAILED, failed.kind)
        assertEquals("decode", failed.error)
        assertEquals(TranscriptEntry.Untranscribed.Kind.TRANSCRIBING, (entries[4] as TranscriptEntry.Untranscribed).kind)
    }

    @Test
    fun transcribedChunkSplitsPendingGroups() {
        val chunks = listOf(
            chunk("p1", at(9), state = ChunkState.RECEIVED),
            chunk("t", at(9, 5)),
            chunk("p2", at(9, 10), state = ChunkState.RECEIVED),
        )
        val entries = buildTranscriptEntries(chunks, emptyList())
        assertEquals(listOf("cp1", "cp2"), entries.map { it.key })
    }
}
