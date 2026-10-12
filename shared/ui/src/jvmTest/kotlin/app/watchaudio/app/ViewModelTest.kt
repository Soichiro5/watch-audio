package app.watchaudio.app

import app.watchaudio.app.logic.ArrivalState
import app.watchaudio.app.logic.GapCause
import app.watchaudio.app.logic.SnippetPart
import app.watchaudio.app.logic.TimelineSpan
import app.watchaudio.app.logic.TranscriptEntry
import app.watchaudio.app.logic.dayWindow
import app.watchaudio.app.state.DayDeletion
import app.watchaudio.app.state.DayViewModel
import app.watchaudio.app.state.DiagnosticsExport
import app.watchaudio.app.state.NowProvider
import app.watchaudio.app.state.OnboardingStep
import app.watchaudio.app.state.OnboardingViewModel
import app.watchaudio.app.state.PendingRetention
import app.watchaudio.app.state.SearchViewModel
import app.watchaudio.app.state.SettingsViewModel
import app.watchaudio.app.state.StatusViewModel
import app.watchaudio.core.alert.GapAlertSettings
import app.watchaudio.core.alert.QuietHours
import app.watchaudio.core.db.AudioUsage
import app.watchaudio.core.db.ChunkState
import app.watchaudio.core.db.LocalStore
import app.watchaudio.core.db.NewSegment
import app.watchaudio.core.pipeline.AudioRetention
import app.watchaudio.core.pipeline.ModelState
import app.watchaudio.core.pipeline.ProcessingStatus
import app.watchaudio.core.pipeline.StorageEnvironment
import app.watchaudio.core.pipeline.StorageManager
import app.watchaudio.core.protocol.AudioCodec
import app.watchaudio.core.protocol.AudioFile
import app.watchaudio.core.protocol.ChunkEndReason
import app.watchaudio.core.protocol.ChunkManifest
import app.watchaudio.core.protocol.SourcePlatform
import app.watchaudio.core.settings.AppSettings
import app.watchaudio.core.settings.loadSettings
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val dbFile = File.createTempFile("watch-audio-ui", ".db").also { it.delete() }
    // Database work runs on the test scheduler, so the tests control time completely.
    private val store = LocalStore.open(dbFile.absolutePath, dispatcher = dispatcher)
    private val tokyo = TimeZone.of("Asia/Tokyo")
    private val day = LocalDate(2026, 10, 10)
    private val window = dayWindow(day, tokyo)
    private var now = at(15)
    private val clock = NowProvider { now }
    private val audioDir = Files.createTempDirectory("watch-audio-ui-audio").toFile()
    private val storageEnvironment = object : StorageEnvironment {
        var free: Long? = 10L shl 30
        override fun modelBytes(): Long = 400L shl 20
        override fun freeBytes(): Long? = free
    }
    private val storage = StorageManager(
        store,
        Path(audioDir.path),
        storageEnvironment,
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(now)
        },
        dispatcher = dispatcher,
    )
    private val services = FakeAppServices(store, audioDir.path, storage = storage)

    @AfterTest
    fun tearDown() {
        store.close()
        dbFile.delete()
        audioDir.deleteRecursively()
    }

    private fun audioFile(id: String) = File(audioDir, "2026/10/10/$id.m4a")

    private fun at(hour: Int, minute: Int = 0) = window.start + hour * 3_600_000L + minute * 60_000L

    private suspend fun addChunk(
        id: String,
        start: Long,
        endReason: ChunkEndReason = ChunkEndReason.ROTATION,
        receivedAt: Long = start + 300_000,
        audio: Boolean = true,
    ): Boolean {
        if (audio) audioFile(id).apply { parentFile.mkdirs() }.writeBytes(ByteArray(10))
        val manifest = ChunkManifest(
            chunkId = id,
            sessionId = "s",
            deviceId = "watch",
            source = SourcePlatform.WEAROS,
            startedAtEpochMs = start,
            durationMs = 300_000,
            timeZone = "Asia/Tokyo",
            audio = if (audio) AudioFile("$id.m4a", AudioCodec.AAC, 16_000, 1, 24_000, 100_000, "00") else null,
            endReason = endReason,
        )
        return store.transaction {
            insertChunk(manifest, audioPath = if (audio) "2026/10/10/$id.m4a" else null, receivedAt = receivedAt)
        }
    }

    private suspend fun transcribe(id: String, vararg segments: NewSegment) = store.transaction {
        markTranscribing(id)
        saveTranscript(id, segments.toList(), "test")
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test
    fun dayShowsTimelineTranscriptAndPendingChunks() = runVmTest {
        addChunk("a", at(9))
        addChunk("b", at(9, 5), endReason = ChunkEndReason.CHARGING)
        transcribe("a", NewSegment(at(9) + 10_000, at(9) + 13_000, "おはようございます", "ja"))
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val state = vm.state.value
        assertEquals(day, state.date)
        assertTrue(state.isToday)
        val content = assertNotNull(state.content)
        assertEquals(10 * 60_000L, content.timeline.audioMs)
        assertTrue(TimelineSpan.Gap(at(9, 10), now, GapCause.Stopped(ChunkEndReason.CHARGING)) in content.timeline.spans)
        assertEquals(listOf("s1", "cb"), content.entries.map { it.key })

        // A new chunk shows up without waiting for the periodic refresh.
        addChunk("c", at(14))
        runCurrent()
        val entries = vm.state.value.content!!.entries
        assertEquals(listOf("s1", "cb"), entries.map { it.key }) // b and c are separated by nothing transcribed
        assertEquals(listOf("b", "c"), (entries[1] as TranscriptEntry.Untranscribed).chunkIds)

        // Playing an utterance starts at its offset inside the chunk.
        vm.play(entries[0] as TranscriptEntry.Utterance)
        runCurrent()
        assertEquals(listOf("2026/10/10/a.m4a" to 10_000L), services.played)
        assertEquals(at(9) + 10_000, vm.state.value.nowPlaying?.startsAt)
        vm.stopPlayback()
        runCurrent()
        assertNull(vm.state.value.nowPlaying)
        assertEquals(1, services.stopRequests)
    }

    @Test
    fun navigatingDaysNeverGoesPastToday() = runVmTest {
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertTrue(!vm.state.value.canGoForward)
        vm.nextDay()
        runCurrent()
        assertEquals(day, vm.state.value.date)

        vm.previousDay()
        runCurrent()
        assertEquals(LocalDate(2026, 10, 9), vm.state.value.date)
        assertTrue(vm.state.value.canGoForward)
        val content = assertNotNull(vm.state.value.content)
        assertEquals(LocalDate(2026, 10, 9), content.date)
        assertEquals(24 * 3_600_000L, content.timeline.window.lengthMs)

        vm.goToToday()
        runCurrent()
        assertEquals(day, vm.state.value.date)
    }

    @Test
    fun periodicRefreshMovesNow() = runVmTest {
        addChunk("a", at(14, 50))
        val vm = DayViewModel(services, tokyo, clock, refreshPeriodMs = 20_000, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(GapCause.Awaiting, (vm.state.value.content!!.timeline.spans.last { it is TimelineSpan.Gap } as TimelineSpan.Gap).cause)

        now = at(16)
        advanceTimeBy(20_001)
        assertEquals(GapCause.Missing, (vm.state.value.content!!.timeline.spans.last { it is TimelineSpan.Gap } as TimelineSpan.Gap).cause)
    }

    @Test
    fun retryRequeuesFailedChunks() = runVmTest {
        addChunk("f", at(9))
        store.transaction {
            repeat(3) {
                markTranscribing("f")
                markFailed("f", "boom")
            }
        }
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        val failed = vm.state.value.content!!.entries.single() as TranscriptEntry.Untranscribed
        assertEquals(TranscriptEntry.Untranscribed.Kind.FAILED, failed.kind)

        vm.transcribe(failed)
        runCurrent()
        assertEquals(ChunkState.RECEIVED, store.read { chunk("f") }!!.state)
        assertEquals(1, services.transcriptionRequests)
        assertEquals(
            TranscriptEntry.Untranscribed.Kind.PENDING,
            (vm.state.value.content!!.entries.single() as TranscriptEntry.Untranscribed).kind,
        )
    }

    @Test
    fun searchHighlightsAndOpensTheDayOfAHit() = runVmTest {
        val yesterday = at(-14) // 10:00 on Oct 9
        addChunk("y", yesterday)
        transcribe("y", NewSegment(yesterday + 60_000, yesterday + 64_000, "明日の会議は十時からです", "ja"))

        val search = SearchViewModel(services, tokyo, scope = backgroundScope)
        backgroundScope.launch { search.results.collect {} }
        backgroundScope.launch { search.searching.collect {} }
        runCurrent()
        assertNull(search.results.value)

        search.setQuery("会議は")
        runCurrent()
        assertTrue(search.searching.value)
        advanceTimeBy(300)
        val results = assertNotNull(search.results.value)
        assertEquals("会議は", results.query)
        val hit = results.results.single()
        assertEquals("10月9日(金) 10:01", hit.whenLabel)
        assertEquals(SnippetPart("会議は", true), hit.parts[1])
        assertTrue(!search.searching.value)

        // New transcripts are found without retyping.
        addChunk("t", at(11))
        transcribe("t", NewSegment(at(11) + 1_000, at(11) + 2_000, "会議は延期", "ja"))
        runCurrent()
        assertEquals(2, search.results.value!!.results.size)

        // Opening the old hit jumps to its day, focuses it and plays from its offset.
        val day = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { day.state.collect {} }
        day.openSegment(hit.chunkId, hit.segmentId, hit.startAt)
        runCurrent()
        assertEquals(LocalDate(2026, 10, 9), day.state.value.date)
        assertEquals(hit.segmentId, day.state.value.focusSegmentId)
        assertEquals(listOf("2026/10/10/y.m4a" to 60_000L), services.played)
        day.focusHandled()
        runCurrent()
        assertNull(day.state.value.focusSegmentId)

        search.setQuery("")
        advanceTimeBy(1)
        assertNull(search.results.value)
    }

    @Test
    fun statusWarnsWhenChunksStopArrivingWhileRecording() = runVmTest {
        val vm = StatusViewModel(services, clock, refreshPeriodMs = 20_000, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(ArrivalState.NoData, vm.state.value.arrival)
        assertEquals(0, vm.state.value.stats!!.chunks)

        addChunk("a", at(14, 50), receivedAt = at(14, 56))
        transcribe("a", NewSegment(at(14, 51), at(14, 52), "テスト", "ja"))
        addChunk("b", at(14, 55), receivedAt = at(14, 58), audio = false)
        runCurrent()
        val state = vm.state.value
        assertEquals(ArrivalState.Receiving(at(14, 58)), state.arrival)
        val stats = assertNotNull(state.stats)
        assertEquals(2, stats.chunks)
        assertEquals(1, stats.segments)
        assertEquals(300_000, stats.recordedMs)

        // Nothing for 12 minutes while the last chunk said "keep going".
        now = at(15, 10)
        advanceTimeBy(20_001)
        val late = assertIs<ArrivalState.Late>(vm.state.value.arrival)
        assertEquals(12 * 60_000L, late.silentForMs)

        // The platform heard from the watch more recently than the database.
        services.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Ready, lastChunkReceivedAt = at(15, 9))
        runCurrent()
        assertIs<ArrivalState.Receiving>(vm.state.value.arrival)
        assertEquals(ModelState.Ready, vm.state.value.status.asrModel)

        vm.downloadModels(allowMetered = true)
        vm.requestTranscription()
        assertEquals(listOf(true), services.downloadRequests)
        assertEquals(1, services.transcriptionRequests)
    }

    @Test
    fun statusShowsWhyRecordingStopped() = runVmTest {
        addChunk("a", at(9), endReason = ChunkEndReason.OFF_BODY, receivedAt = at(9, 6))
        val vm = StatusViewModel(services, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(ArrivalState.Stopped(at(9, 6), ChunkEndReason.OFF_BODY), vm.state.value.arrival)
    }

    @Test
    fun settingsLoadAndPersist() = runVmTest {
        val vm = SettingsViewModel(services, scope = backgroundScope)
        backgroundScope.launch { vm.settings.collect {} }
        runCurrent()
        assertEquals(AppSettings(), vm.settings.value)

        vm.setQuietHours(QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 6 * 60))
        vm.completeOnboarding()
        runCurrent()
        val expected = AppSettings(
            onboardingCompleted = true,
            gapAlert = GapAlertSettings(quietHours = QuietHours(true, 22 * 60, 6 * 60)),
        )
        assertEquals(expected, vm.settings.value)
        assertEquals(expected, store.loadSettings())
        assertEquals(1, services.gapAlertSettingsChanges)

        vm.setGapAlertsEnabled(false)
        runCurrent()
        assertEquals(false, vm.settings.value!!.gapAlert.enabled)
        assertEquals(2, services.gapAlertSettingsChanges)
        assertEquals(0, services.transcriptionSettingsChanges)

        // #75: the charging-only switch is stored and reported to the platform's scheduler.
        vm.setTranscribeOnlyWhileCharging(true)
        runCurrent()
        assertEquals(true, vm.settings.value!!.transcribeOnlyWhileCharging)
        assertEquals(true, store.loadSettings().transcribeOnlyWhileCharging)
        assertEquals(1, services.transcriptionSettingsChanges)
        assertEquals(2, services.gapAlertSettingsChanges)

        // #81: the English switch is stored and reported, so the platform fetches or removes its model.
        vm.setTranscribeEnglish(true)
        runCurrent()
        assertEquals(true, vm.settings.value!!.transcribeEnglish)
        assertEquals(true, store.loadSettings().transcribeEnglish)
        assertEquals(2, services.transcriptionSettingsChanges)
    }

    @Test
    fun onboardingRequiresConsentAndSkipsPermissionsWhenNoneAreNeeded() = runVmTest {
        val vm = OnboardingViewModel(services, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.CONSENT, OnboardingStep.MODEL, OnboardingStep.WATCH),
            vm.state.value.steps,
        )
        vm.next()
        vm.next() // blocked: no consent yet
        runCurrent()
        assertEquals(OnboardingStep.CONSENT, vm.state.value.step)
        vm.setConsent(true)
        vm.next()
        vm.next()
        vm.next() // already last
        runCurrent()
        assertEquals(OnboardingStep.WATCH, vm.state.value.step)
        assertTrue(vm.state.value.isLast)
        vm.back()
        runCurrent()
        assertEquals(OnboardingStep.MODEL, vm.state.value.step)
        vm.restart()
        runCurrent()
        assertEquals(OnboardingStep.WELCOME, vm.state.value.step)
    }

    @Test
    fun onboardingRequestsPermissionsAndReflectsTheModel() = runVmTest {
        val platform = FakeAppServices(store, permissions = listOf(AppPermission.NOTIFICATIONS, AppPermission.SPEECH_RECOGNITION))
        platform.deniesOnRequest = true
        val vm = OnboardingViewModel(platform, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertTrue(OnboardingStep.PERMISSIONS in vm.state.value.steps)
        assertEquals(mapOf(AppPermission.NOTIFICATIONS to null, AppPermission.SPEECH_RECOGNITION to null), vm.state.value.permissions)

        vm.refreshPermissions()
        runCurrent()
        assertEquals(PermissionStatus.NOT_REQUESTED, vm.state.value.permissions[AppPermission.NOTIFICATIONS])

        vm.requestPermission(AppPermission.NOTIFICATIONS)
        runCurrent()
        assertEquals(PermissionStatus.DENIED, vm.state.value.permissions[AppPermission.NOTIFICATIONS])
        assertEquals(PermissionStatus.NOT_REQUESTED, vm.state.value.permissions[AppPermission.SPEECH_RECOGNITION])

        platform.mutableStatus.value = ProcessingStatus(asrModel = ModelState.Downloading(0.5f))
        runCurrent()
        assertEquals(ModelState.Downloading(0.5f), vm.state.value.model)
        vm.downloadModels(allowMetered = false)
        assertEquals(listOf(false), platform.downloadRequests)
    }

    @Test
    fun refusedPermissionLeadsToTheSystemSettingsAndIsReReadOnReturn() = runVmTest {
        val platform = FakeAppServices(store, permissions = listOf(AppPermission.NOTIFICATIONS))
        platform.deniesOnRequest = true
        val vm = OnboardingViewModel(platform, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        vm.refreshPermissions()
        runCurrent()

        // Nothing refused yet: the system prompt is still available, settings are not opened.
        vm.openAppSettings()
        assertEquals(0, platform.appSettingsOpened)

        vm.requestPermission(AppPermission.NOTIFICATIONS)
        runCurrent()
        assertEquals(PermissionStatus.DENIED, vm.state.value.permissions[AppPermission.NOTIFICATIONS])
        vm.openAppSettings()
        assertEquals(1, platform.appSettingsOpened)

        // The user allows notifications in the settings app; the screen re-reads them on resume.
        platform.permissionStates[AppPermission.NOTIFICATIONS] = PermissionStatus.GRANTED
        vm.refreshPermissions()
        runCurrent()
        assertEquals(PermissionStatus.GRANTED, vm.state.value.permissions[AppPermission.NOTIFICATIONS])
        vm.openAppSettings()
        assertEquals(1, platform.appSettingsOpened)
    }

    @Test
    fun statusShowsStorageAndConfirmsAShorterRetention() = runVmTest {
        val fortyDaysAgo = at(9) - 40 * 24 * 3_600_000L
        addChunk("old", fortyDaysAgo)
        transcribe("old", NewSegment(fortyDaysAgo + 1_000, fortyDaysAgo + 2_000, "昔の会話", "ja"))
        addChunk("oldPending", fortyDaysAgo + 300_000)
        addChunk("today", at(9))
        transcribe("today")
        val vm = StatusViewModel(services, clock, refreshPeriodMs = 20_000, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val storage = assertNotNull(vm.state.value.storage)
        assertEquals(AudioRetention.UNLIMITED, storage.retention)
        assertEquals(300_000, storage.breakdown.audioBytes)
        assertEquals(400L shl 20, storage.breakdown.modelBytes)
        assertTrue(storage.breakdown.databaseBytes > 0)
        assertFalse(storage.breakdown.isLowOnSpace)

        // Shorter retention: nothing happens until the user confirms.
        vm.selectRetention(AudioRetention.DAYS_30)
        runCurrent()
        assertEquals(PendingRetention(AudioRetention.DAYS_30, AudioUsage(1, 100_000)), vm.state.value.storageActions.pendingRetention)
        assertEquals(AudioRetention.UNLIMITED, vm.state.value.storage!!.retention)
        vm.cancelRetention()
        runCurrent()
        assertNull(vm.state.value.storageActions.pendingRetention)
        assertTrue(audioFile("old").exists())

        vm.selectRetention(AudioRetention.DAYS_30)
        runCurrent()
        vm.confirmRetention()
        runCurrent()
        val after = vm.state.value
        assertEquals(AudioRetention.DAYS_30, after.storage!!.retention)
        assertEquals(Strings.audioPurged(1, "0.1 MB"), after.storageActions.message)
        assertEquals(1, after.storage.breakdown.audioPurgedChunks)
        assertEquals(200_000, after.storage.breakdown.audioBytes)
        assertFalse(audioFile("old").exists())
        assertTrue(audioFile("oldPending").exists(), "untranscribed audio is kept")
        assertEquals("昔の会話", store.read { search("昔の会話") }.single().segment.text)

        // Keeping audio longer needs no confirmation.
        vm.selectRetention(AudioRetention.UNLIMITED)
        runCurrent()
        assertEquals(AudioRetention.UNLIMITED, vm.state.value.storage!!.retention)
        assertNull(vm.state.value.storageActions.pendingRetention)

        // Low free space shows up on the next refresh.
        storageEnvironment.free = 300L shl 20
        advanceTimeBy(20_001)
        assertTrue(vm.state.value.storage!!.breakdown.isLowOnSpace)
    }

    @Test
    fun deleteAllNeedsTheTypedConfirmation() = runVmTest {
        addChunk("a", at(9))
        transcribe("a", NewSegment(at(9) + 1_000, at(9) + 2_000, "消える発話", "ja"))
        val vm = StatusViewModel(services, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        assertFalse(vm.confirmDeleteAll()) // not even opened
        vm.openDeleteAll()
        vm.updateDeleteAllInput("さくじょ")
        runCurrent()
        assertEquals("さくじょ", vm.state.value.storageActions.deleteAllInput)
        assertFalse(vm.state.value.storageActions.canConfirmDeleteAll)
        assertFalse(vm.confirmDeleteAll())
        runCurrent()
        assertEquals(1, vm.state.value.stats!!.chunks)

        vm.cancelDeleteAll()
        runCurrent()
        assertNull(vm.state.value.storageActions.deleteAllInput)

        vm.openDeleteAll()
        vm.updateDeleteAllInput("削除")
        runCurrent()
        assertTrue(vm.state.value.storageActions.canConfirmDeleteAll)
        assertTrue(vm.confirmDeleteAll())
        runCurrent()
        val state = vm.state.value
        assertEquals(0, state.stats!!.chunks)
        assertEquals(Strings.DELETED_ALL, state.storageActions.message)
        assertNull(state.storageActions.deleteAllInput)
        assertFalse(audioFile("a").exists())
        assertTrue(store.read { search("消える発話") }.isEmpty())
        assertEquals(1, services.stopRequests)
    }

    @Test
    fun exportingDiagnosticsAsksThePlatformOnlyOnTapAndReportsTheOutcome() = runVmTest {
        val vm = StatusViewModel(services, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(0, services.diagnosticsExports) // never on its own
        assertEquals(DiagnosticsExport(), vm.state.value.diagnosticsExport)

        // Shared: the share sheet is the feedback, no message.
        vm.exportDiagnostics()
        runCurrent()
        assertEquals(1, services.diagnosticsExports)
        assertEquals(DiagnosticsExport(), vm.state.value.diagnosticsExport)

        services.diagnosticsExportResult = false
        vm.exportDiagnostics()
        runCurrent()
        assertEquals(DiagnosticsExport(message = Strings.DIAGNOSTICS_EMPTY), vm.state.value.diagnosticsExport)

        services.diagnosticsExportFailure = IllegalStateException("disk full")
        vm.exportDiagnostics()
        runCurrent()
        // A fixed text, not the (possibly English) exception message; the log gets the type only (#135).
        assertEquals(DiagnosticsExport(message = Strings.DIAGNOSTICS_EXPORT_FAILED), vm.state.value.diagnosticsExport)
        assertEquals(listOf("diagnostics_export_failed error=java.lang.IllegalStateException"), services.diagnosticsLines)
        assertEquals(3, services.diagnosticsExports)
    }

    @Test
    fun aFailedDeletionShowsAFixedTextAndLogsOnlyTheErrorType() = runVmTest {
        addChunk("a", at(9))
        // An audio root that is a file: sweeping it fails after the rows are gone.
        val notADirectory = File.createTempFile("watch-audio-ui-root", ".bin").apply { deleteOnExit() }
        val services = FakeAppServices(
            store,
            notADirectory.path,
            storage = StorageManager(store, Path(notADirectory.path), dispatcher = dispatcher),
        )
        val vm = StatusViewModel(services, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        vm.openDeleteAll()
        vm.updateDeleteAllInput(Strings.DELETE_ALL_WORD)
        runCurrent()
        assertTrue(vm.confirmDeleteAll())
        runCurrent()

        assertEquals(Strings.STORAGE_ACTION_FAILED, vm.state.value.storageActions.message)
        val line = services.diagnosticsLines.single()
        assertTrue(line.startsWith("storage_action_failed action=delete_all error="), line)
        notADirectory.delete()
    }

    @Test
    fun deletingADayNeedsConfirmationAndKeepsOtherDays() = runVmTest {
        addChunk("a", at(9))
        transcribe("a", NewSegment(at(9) + 1_000, at(9) + 2_000, "今日の発話", "ja"))
        addChunk("y", at(-14)) // yesterday 10:00
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(1, vm.state.value.content!!.chunksStartedThisDay)

        vm.confirmDeleteDay() // without asking first: nothing happens
        runCurrent()
        assertNotNull(store.read { chunk("a") })

        vm.requestDeleteDay()
        runCurrent()
        assertEquals(DayDeletion.CONFIRMING, vm.state.value.deletion)
        vm.cancelDeleteDay()
        runCurrent()
        assertEquals(DayDeletion.NONE, vm.state.value.deletion)

        // Moving to another day drops a pending confirmation.
        vm.requestDeleteDay()
        vm.previousDay()
        runCurrent()
        assertEquals(DayDeletion.NONE, vm.state.value.deletion)
        vm.nextDay()
        runCurrent()

        vm.play(vm.state.value.content!!.entries.single() as TranscriptEntry.Utterance)
        vm.requestDeleteDay()
        vm.confirmDeleteDay()
        runCurrent()
        assertNull(store.read { chunk("a") })
        assertFalse(audioFile("a").exists())
        assertNotNull(store.read { chunk("y") })
        assertTrue(audioFile("y").exists())
        val state = vm.state.value
        assertEquals(DayDeletion.NONE, state.deletion)
        assertNull(state.nowPlaying)
        assertTrue(state.content!!.entries.isEmpty())
        assertEquals(0, state.content.chunksStartedThisDay)
    }

    @Test
    fun purgedAudioShowsTheTranscriptWithoutPlayback() = runVmTest {
        addChunk("a", at(9))
        transcribe("a", NewSegment(at(9) + 1_000, at(9) + 2_000, "音声は消えた", "ja"))
        store.transaction { markAudioPurged(listOf("a"), at(12)) }
        val vm = DayViewModel(services, tokyo, clock, scope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()

        val content = vm.state.value.content!!
        assertEquals(1, content.audioPurgedChunks)
        val utterance = content.entries.single() as TranscriptEntry.Utterance
        assertNull(utterance.audioPath)
        vm.play(utterance)
        vm.openSegment("a", utterance.segment.id, utterance.segment.startAt)
        runCurrent()
        assertTrue(services.played.isEmpty())
        assertNull(vm.state.value.nowPlaying)
    }
}
