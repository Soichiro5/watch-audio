package app.watchaudio.core.pipeline

import app.watchaudio.core.pipeline.TranscriptionRun.Companion.followUp
import app.watchaudio.core.pipeline.TranscriptionRun.FollowUp
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the platforms schedule after a run (#134), the rules both Android and iOS now share. */
class TranscriptionRunFollowUpTest {
    private val ready = ModelState.Ready
    private val thermal = ProcessingDecision.Pause(PauseReason.THERMAL, 900_000)
    private val hourlyCap = ProcessingDecision.Pause(PauseReason.HOURLY_CAP, 1_000)
    private val lowBattery = ProcessingDecision.Pause(PauseReason.LOW_BATTERY, null)

    @Test
    fun nothingWhenTheQueueIsEmpty() {
        assertEquals(FollowUp.None, followUp(ready, pause = null, stopped = false, pending = false))
        // A pause with nothing left to transcribe schedules nothing either.
        assertEquals(FollowUp.None, followUp(ready, pause = lowBattery, stopped = false, pending = false))
    }

    @Test
    fun runAgainWhileChunksWaitAndNothingHoldsThemBack() {
        assertEquals(FollowUp.RunAgain, followUp(ready, pause = null, stopped = false, pending = true))
    }

    @Test
    fun pausesRetryLaterAndOnChargeExceptWhenWarm() {
        assertEquals(FollowUp.Paused(PauseReason.HOURLY_CAP, 1_000, onCharge = true), followUp(ready, hourlyCap, stopped = false, pending = true))
        assertEquals(FollowUp.Paused(PauseReason.LOW_BATTERY, null, onCharge = true), followUp(ready, lowBattery, stopped = false, pending = true))
        assertEquals(FollowUp.Paused(PauseReason.THERMAL, 900_000, onCharge = false), followUp(ready, thermal, stopped = false, pending = true))
    }

    @Test
    fun aStoppedRunIsLeftToThePlatform() {
        assertEquals(FollowUp.Interrupted, followUp(ready, pause = null, stopped = true, pending = true))
        assertEquals(FollowUp.Interrupted, followUp(ready, pause = thermal, stopped = true, pending = true))
    }

    @Test
    fun withoutTheModelWaitForIt() {
        assertEquals(FollowUp.WaitForModel(downloading = true), followUp(ModelState.Downloading(0.3f), null, stopped = false, pending = true))
        assertEquals(FollowUp.WaitForModel(downloading = false), followUp(ModelState.Missing, null, stopped = true, pending = true))
        assertEquals(FollowUp.WaitForModel(downloading = false), followUp(ModelState.Failed("x"), null, stopped = false, pending = false))
    }
}
