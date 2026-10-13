package app.watchaudio.core.embedding

import app.watchaudio.core.pipeline.Diagnostics
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.platform.Sha256
import app.watchaudio.testing.TempDir
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** The iOS model download (#178): resumable `.part` files, size and SHA-256 checks, retries. */
class ModelInstallerTest {
    private val dir = TempDir("model-installer")
    private val root = dir.path
    private val model = ByteArray(3_000) { (it * 7).toByte() }
    private val tokenizer = ByteArray(500) { (it * 3 + 1).toByte() }
    private val files = listOf(download("https://example.invalid/model", "m/model.onnx", model), download("https://example.invalid/tok", "m/tok.json", tokenizer))

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    private fun download(url: String, path: String, bytes: ByteArray) = ModelDownload(url, path, bytes.size.toLong(), sha256(bytes))

    private fun sha256(bytes: ByteArray): String = Sha256().apply { update(bytes) }.digestHex()

    private fun read(path: Path): ByteArray = SystemFileSystem.source(path).buffered().use { it.readByteArray() }

    /** Serves [content] by URL; can stop after [failAfter] bytes and can ignore ranges. */
    private inner class FakeFetcher(
        private val content: Map<String, ByteArray> = mapOf(files[0].url to model, files[1].url to tokenizer),
        var failAfter: Int? = null,
        var ignoreRanges: Boolean = false,
    ) : ModelFetcher {
        val offsets = mutableListOf<Pair<String, Long>>()
        var waited = false

        override suspend fun fetch(url: String, part: Path, offset: Long, onProgress: (Long) -> Unit, onWaiting: () -> Unit) {
            offsets += url to offset
            onWaiting()
            waited = true
            val bytes = content.getValue(url)
            val from = if (ignoreRanges) 0 else offset.toInt()
            val existing = if (from == 0) ByteArray(0) else read(part).copyOf(from)
            val end = failAfter?.let { minOf(bytes.size, from + it) } ?: bytes.size
            SystemFileSystem.sink(part).buffered().use { sink ->
                sink.write(existing)
                sink.write(bytes, from, end)
            }
            onProgress(end.toLong())
            if (end < bytes.size) throw ModelFetchException("url_error_-1005")
        }
    }

    private fun TestScope.installer(fetcher: ModelFetcher, now: () -> Long = { 0L }, scope: CoroutineScope = backgroundScope, onReady: () -> Unit = {}) =
        ModelInstaller(files, root, fetcher, scope, Diagnostics.NONE, clock = now, dispatcher = StandardTestDispatcher(testScheduler), onReady = onReady)

    @Test
    fun downloadsChecksAndInstallsEveryFile() = runTest {
        var ready = 0
        val installer = installer(FakeFetcher(), onReady = { ready++ })
        assertFalse(installer.isReady())
        assertEquals(ModelState.Missing, installer.currentState())
        assertTrue(installer.install())
        assertTrue(installer.isReady())
        assertEquals(ModelState.Ready, installer.currentState())
        assertContentEquals(model, read(installer.fileOf(files[0])))
        assertContentEquals(tokenizer, read(installer.fileOf(files[1])))
        assertNull(SystemFileSystem.metadataOrNull(Path(root, "m/model.onnx.part")), "no part file left")
        assertEquals(1, ready)
        assertEquals(3_500L, installer.totalBytes)
    }

    @Test
    fun anInterruptedDownloadContinuesFromItsPartFile() = runTest {
        val fetcher = FakeFetcher(failAfter = 1_000)
        val installer = installer(fetcher)
        assertFalse(installer.install())
        assertIs<ModelState.Failed>(installer.state.value)
        assertEquals(1_000L, SystemFileSystem.metadataOrNull(Path(root, "m/model.onnx.part"))?.size)
        fetcher.failAfter = null
        assertTrue(installer.install())
        assertEquals(listOf(files[0].url to 0L, files[0].url to 1_000L, files[1].url to 0L), fetcher.offsets)
        assertContentEquals(model, read(installer.fileOf(files[0])))
    }

    @Test
    fun aServerWithoutRangesStartsTheFileOver() = runTest {
        val fetcher = FakeFetcher(failAfter = 1_000)
        val installer = installer(fetcher)
        assertFalse(installer.install())
        fetcher.failAfter = null
        fetcher.ignoreRanges = true
        assertTrue(installer.install())
        assertContentEquals(model, read(installer.fileOf(files[0])))
    }

    @Test
    fun wrongBytesAreNotInstalledAndStartOver() = runTest {
        val corrupt = model.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val fetcher = FakeFetcher(content = mapOf(files[0].url to corrupt, files[1].url to tokenizer))
        val installer = installer(fetcher)
        assertFalse(installer.install())
        assertEquals(ModelState.Failed("sha256_mismatch"), installer.state.value)
        assertFalse(SystemFileSystem.exists(installer.fileOf(files[0])))
        assertFalse(SystemFileSystem.exists(Path(root, "m/model.onnx.part")), "a wrong file is not continued")
    }

    @Test
    fun aCompletePartFileIsCheckedWithoutDownloadingAgain() = runTest {
        SystemFileSystem.createDirectories(Path(root, "m"))
        SystemFileSystem.sink(Path(root, "m/model.onnx.part")).buffered().use { it.write(model) }
        val fetcher = FakeFetcher()
        assertTrue(installer(fetcher).install())
        assertEquals(listOf(files[1].url to 0L), fetcher.offsets)
    }

    @Test
    fun requestDownloadWaitsBeforeRetryingAFailure() = runTest {
        var now = 0L
        val fetcher = FakeFetcher(failAfter = 100)
        // The test's own scope: advanceUntilIdle does not wait for background coroutines.
        val installer = installer(fetcher, now = { now }, scope = this)
        installer.requestDownload()
        testScheduler.advanceUntilIdle()
        assertEquals(1, fetcher.offsets.size)
        now = 30_000L
        installer.requestDownload()
        testScheduler.advanceUntilIdle()
        assertEquals(1, fetcher.offsets.size, "no retry within a minute")
        fetcher.failAfter = null
        now = 61_000L
        installer.requestDownload()
        testScheduler.advanceUntilIdle()
        assertTrue(installer.isReady())
        assertEquals(ModelState.Ready, installer.state.value)
        installer.requestDownload()
        testScheduler.advanceUntilIdle()
        assertEquals(3, fetcher.offsets.size, "nothing to do once installed")
    }

    @Test
    fun theStateFollowsTheDownload() = runTest {
        val states = mutableListOf<ModelState>()
        val fetcher = object : ModelFetcher {
            override suspend fun fetch(url: String, part: Path, offset: Long, onProgress: (Long) -> Unit, onWaiting: () -> Unit) {
                onWaiting()
                states += installerRef!!.currentState()
                val bytes = if (url == files[0].url) model else tokenizer
                SystemFileSystem.sink(part).buffered().use { it.write(bytes) }
                onProgress(bytes.size.toLong())
                states += installerRef!!.currentState()
            }
        }
        val installer = installer(fetcher)
        installerRef = installer
        assertTrue(installer.install())
        assertEquals(ModelState.Missing, states[0], "waiting for Wi-Fi")
        assertEquals(ModelState.Downloading(3_000f / 3_500f), states[1])
        assertEquals(ModelState.Downloading(1f), states[3])
    }

    private var installerRef: ModelInstaller? = null

    @Test
    fun theRuriFilesArePinnedAndUnderTheModelDirectory() {
        assertEquals(2, RuriV3.FILES.size)
        for (file in RuriV3.FILES) {
            assertTrue(file.url.startsWith("https://huggingface.co/"), file.url)
            assertTrue(Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(file.url), "pinned to a commit: ${file.url}")
            assertTrue(file.path.startsWith(RuriV3.DIRECTORY + "/"), file.path)
            assertTrue(Regex("[0-9a-f]{64}").matches(file.sha256), file.sha256)
        }
        assertEquals(listOf(RuriV3.MODEL_FILE, RuriV3.TOKENIZER_FILE), RuriV3.FILES.map { it.path.substringAfterLast('/') })
        assertEquals(43_773_448L, RuriV3.FILES.sumOf { it.size }, "~44 MB, as the docs say")
    }
}
