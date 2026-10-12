package app.watchaudio.core.platform

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

class PositionalFileTest {
    private val path = Path(SystemTemporaryDirectory, "positional-${Random.nextLong().toULong().toString(16)}.bin")

    @AfterTest
    fun tearDown() = SystemFileSystem.delete(path, mustExist = false)

    @Test
    fun readsAndWritesAtPositions() {
        openPositionalFile(path.toString()).use { file ->
            assertEquals(0, file.size)
            file.write(4, byteArrayOf(1, 2, 3))
            assertEquals(7, file.size)
            file.write(0, byteArrayOf(9, 8, 7, 6, 5), offset = 1, length = 2)
            val out = ByteArray(10) { -1 }
            assertEquals(7, file.read(0, out))
            assertContentEquals(byteArrayOf(8, 7, 0, 0, 1, 2, 3, -1, -1, -1), out)
            assertEquals(2, file.read(5, out, offset = 8, length = 2))
            assertEquals(0, file.read(7, out))
            file.sync()
            file.truncate(5)
            assertEquals(5, file.size)
            file.truncate(50)
            assertEquals(5, file.size)
        }
        // Reopening keeps the bytes.
        openPositionalFile(path.toString()).use { file ->
            val out = ByteArray(5)
            assertEquals(5, file.read(0, out))
            assertContentEquals(byteArrayOf(8, 7, 0, 0, 1), out)
        }
    }
}
