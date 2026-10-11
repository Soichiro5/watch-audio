package app.watchaudio.wear.recording

/**
 * Bookkeeping for the chunks of one recorder run: the chunk being recorded (its [ChunkJournal],
 * marks and silence statistics) and the hand-over to the next chunk at a rotation. Pure;
 * [RecordingService] drives it with the [RecorderInput.elapsedRealtime] clock and finalizes the
 * chunks it closes.
 *
 * Consecutive chunks tile the timeline exactly: the next chunk starts at the previous start plus
 * the measured duration, independent of wall-clock corrections.
 */
class ChunkTracker(first: ChunkJournal, startElapsedMs: Long) {
    /** A chunk that was just closed, ready for [ChunkFinalizer.finalize]. */
    data class Closed(val journal: ChunkJournal, val durationMs: Long)

    private var journal = first
    private var startElapsedMs = startElapsedMs
    private var silence = first.silenceDetector()

    val chunkId: String get() = journal.chunkId

    /** The current chunk's journal including the latest silence statistics. */
    fun snapshot(): ChunkJournal = journal.withSilence(silence)

    /** Records a mark at [atElapsedMs]; returns its offset from the start of the chunk. */
    fun mark(atElapsedMs: Long): Long {
        val offset = atElapsedMs - startElapsedMs
        journal = journal.copy(markOffsetsMs = journal.markOffsetsMs + offset)
        return offset
    }

    fun addAmplitude(amplitude: Int) = silence.add(amplitude)

    /** Notes that the recorder is alive at [nowEpochMs] (the end estimate after a crash). */
    fun heartbeat(nowEpochMs: Long): ChunkJournal {
        journal = journal.withSilence(silence).copy(lastAliveAtEpochMs = nowEpochMs)
        return journal
    }

    /** Closes the current chunk at [atElapsedMs] and continues with [nextChunkId] in the same session. */
    fun rotate(nextChunkId: String, atElapsedMs: Long, timeZone: String): Closed {
        val closed = close(atElapsedMs)
        journal = ChunkJournal(
            chunkId = nextChunkId,
            sessionId = closed.journal.sessionId,
            deviceId = closed.journal.deviceId,
            startedAtEpochMs = closed.journal.startedAtEpochMs + closed.durationMs,
            timeZone = timeZone,
        )
        startElapsedMs = atElapsedMs
        silence = journal.silenceDetector()
        return closed
    }

    /** Closes the current chunk at [atElapsedMs] (the recorder stopped). */
    fun end(atElapsedMs: Long): Closed = close(atElapsedMs)

    private fun close(atElapsedMs: Long): Closed {
        val duration = atElapsedMs - startElapsedMs
        val closed = journal.withSilence(silence).copy(lastAliveAtEpochMs = journal.startedAtEpochMs + duration)
        return Closed(closed, duration)
    }
}
