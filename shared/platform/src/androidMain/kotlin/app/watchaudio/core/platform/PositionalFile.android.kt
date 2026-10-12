package app.watchaudio.core.platform

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

actual fun openPositionalFile(path: String): PositionalFile = ChannelPositionalFile(RandomAccessFile(path, "rw").channel)

/** [PositionalFile] over a [FileChannel], whose positional reads are safe from several threads. */
internal class ChannelPositionalFile(private val channel: FileChannel) : PositionalFile {
    override val size: Long get() = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val target = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (target.hasRemaining()) {
            val read = channel.read(target, at)
            if (read <= 0) break
            at += read
        }
        return (at - position).toInt()
    }

    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        val source = ByteBuffer.wrap(buffer, offset, length)
        var at = position
        while (source.hasRemaining()) at += channel.write(source, at)
    }

    override fun truncate(size: Long) {
        if (channel.size() > size) channel.truncate(size)
    }

    override fun sync() = channel.force(false)

    override fun close() = channel.close()
}
