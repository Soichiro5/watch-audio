package app.watchaudio.wear

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** The start flow of #161: explanation before the body sensor request, recording never blocked by it. */
class PermissionPlanTest {
    private val fresh = PermissionState(
        microphone = false,
        notifications = false,
        bodySensorsDeclared = true,
        bodySensors = false,
        bodySensorsAnswered = false,
        notificationsAsked = false,
    )

    @Test
    fun microphoneComesFirstAndAloneDecidesRecording() {
        assertEquals(PermissionStep.RequestMicrophone, nextPermissionStep(fresh))
        // Even with every optional permission granted, nothing else happens before the microphone.
        assertEquals(
            PermissionStep.RequestMicrophone,
            nextPermissionStep(fresh.copy(notifications = true, bodySensors = true, bodySensorsAnswered = true)),
        )
    }

    @Test
    fun bodySensorExplanationComesBeforeItsRequest() {
        val micGranted = fresh.copy(microphone = true, notificationsAsked = true)
        // The flow has no step that requests BODY_SENSORS: only "continue" on the explanation does.
        assertEquals(PermissionStep.ExplainBodySensors, nextPermissionStep(micGranted))
    }

    @Test
    fun explanationIsShownOnlyOnce() {
        val answered = fresh.copy(microphone = true, notificationsAsked = true, bodySensorsAnswered = true)
        assertEquals(PermissionStep.Done, nextPermissionStep(answered))
    }

    @Test
    fun noExplanationWhenGrantedOrNotDeclared() {
        val micGranted = fresh.copy(microphone = true, notifications = true)
        assertEquals(PermissionStep.Done, nextPermissionStep(micGranted.copy(bodySensors = true)))
        // Wear OS 6 / API 36+: BODY_SENSORS is not declared (maxSdkVersion=35), nothing to explain.
        assertEquals(PermissionStep.Done, nextPermissionStep(micGranted.copy(bodySensorsDeclared = false)))
    }

    @Test
    fun notificationsAreAskedOnceAfterTheExplanation() {
        val afterExplanation = fresh.copy(microphone = true, bodySensorsAnswered = true)
        assertEquals(PermissionStep.RequestNotifications, nextPermissionStep(afterExplanation))
        assertEquals(PermissionStep.Done, nextPermissionStep(afterExplanation.copy(notificationsAsked = true)))
    }

    @Test
    fun declinedBodySensorsShowTheNoteButRecordingGoesOn() {
        val declined = fresh.copy(microphone = true, notifications = true, bodySensorsAnswered = true)
        assertTrue(offBodyDetectionDeclined(declined))
        assertEquals(PermissionStep.Done, nextPermissionStep(declined))
        assertFalse(offBodyDetectionDeclined(declined.copy(bodySensors = true)), "granted later in the settings")
        assertFalse(offBodyDetectionDeclined(declined.copy(bodySensorsDeclared = false)), "API 36+: no BODY_SENSORS")
        assertFalse(offBodyDetectionDeclined(fresh.copy(microphone = true)), "not asked yet")
    }
}
