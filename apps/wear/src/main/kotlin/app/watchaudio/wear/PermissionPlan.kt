package app.watchaudio.wear

/**
 * Permission state as the start button sees it. [bodySensorsDeclared] is false where the manifest
 * does not declare `BODY_SENSORS` (`maxSdkVersion=35`: Wear OS 6 / API 36 and later).
 */
data class PermissionState(
    val microphone: Boolean,
    val notifications: Boolean,
    val bodySensorsDeclared: Boolean,
    val bodySensors: Boolean,
    /** The wearer has answered the body sensor explanation once (persisted, [WearPrefs]). */
    val bodySensorsAnswered: Boolean,
    /** Notifications were already requested while this activity is alive. */
    val notificationsAsked: Boolean,
)

/** The next thing the start flow does about permissions. */
enum class PermissionStep {
    /** Ask for the microphone (and notifications with it, if missing); recording waits for it. */
    RequestMicrophone,

    /**
     * Show the in-app explanation of `BODY_SENSORS` (#161): used only to pause when the watch is
     * taken off. Only "continue" there leads to the system dialog.
     */
    ExplainBodySensors,

    /** Ask for notifications alone (granted microphone, notifications still missing). */
    RequestNotifications,

    /** Nothing more to ask. */
    Done,
}

/**
 * Order of the start flow: the microphone first (recording starts as soon as it is granted), then
 * the body sensor explanation right before its own request, then a missing notification permission.
 * Optional permissions never block recording and are not asked again once answered.
 */
fun nextPermissionStep(state: PermissionState): PermissionStep = when {
    !state.microphone -> PermissionStep.RequestMicrophone
    needsBodySensorExplanation(state) -> PermissionStep.ExplainBodySensors
    !state.notifications && !state.notificationsAsked -> PermissionStep.RequestNotifications
    else -> PermissionStep.Done
}

private fun needsBodySensorExplanation(state: PermissionState) =
    state.bodySensorsDeclared && !state.bodySensors && !state.bodySensorsAnswered

/**
 * True when the wearer declined `BODY_SENSORS` (on the explanation or in the system dialog): the
 * recorder still records but cannot pause on its own when the watch is taken off, so the app says so.
 */
fun offBodyDetectionDeclined(state: PermissionState): Boolean =
    state.bodySensorsDeclared && !state.bodySensors && state.bodySensorsAnswered
