package app.watchaudio.core.platform

/**
 * A file read and written at explicit byte positions (`pread` / `pwrite`), for data that is
 * scanned in blocks and patched in place, such as the semantic search's vector cache (#24).
 * kotlinx-io only reads files sequentially.
 *
 * Reads at different positions may run on several threads at once; writes must not overlap
 * with other calls (the caller serializes them).
 */
interface PositionalFile : AutoCloseable {
    /** Current length of the file in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes at [position] into [buffer] from [offset]; fewer only at the end
     * of the file. @return the number of bytes read (0 at or past the end).
     */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    /** Writes [length] bytes of [buffer] from [offset] at [position], extending the file if needed. */
    fun write(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)

    /** Cuts the file to [size] bytes (no-op when it is not longer). */
    fun truncate(size: Long)

    /** Flushes written bytes to the storage device. */
    fun sync()
}

/** Opens [path] for reading and writing, creating an empty file if it does not exist. */
expect fun openPositionalFile(path: String): PositionalFile
