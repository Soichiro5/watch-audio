package app.watchaudio.app

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
import platform.Speech.SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The iOS permissions the onboarding asks for: local notifications (gap alert) and speech
 * recognition. Whether SpeechAnalyzer/SpeechTranscriber needs the latter is unconfirmed; it is
 * requested as a precaution (`NSSpeechRecognitionUsageDescription` is in Info.plist).
 * System callbacks arrive on arbitrary threads; the suspending wrappers resume the caller.
 */
internal object IosPermissions {
    val all = listOf(AppPermission.NOTIFICATIONS, AppPermission.SPEECH_RECOGNITION)

    suspend fun status(permission: AppPermission): PermissionStatus = when (permission) {
        AppPermission.NOTIFICATIONS -> notificationStatus()
        AppPermission.SPEECH_RECOGNITION -> speechStatus(SFSpeechRecognizer.authorizationStatus())
    }

    suspend fun request(permission: AppPermission): PermissionStatus {
        if (status(permission) != PermissionStatus.NOT_REQUESTED) return status(permission)
        when (permission) {
            AppPermission.NOTIFICATIONS -> suspendCancellableCoroutine { cont ->
                UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                    UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
                ) { _, _ -> cont.resume(Unit) }
            }
            AppPermission.SPEECH_RECOGNITION -> suspendCancellableCoroutine { cont ->
                SFSpeechRecognizer.requestAuthorization { _ -> cont.resume(Unit) }
            }
        }
        return status(permission)
    }

    private suspend fun notificationStatus(): PermissionStatus = suspendCancellableCoroutine { cont ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> PermissionStatus.GRANTED
                UNAuthorizationStatusNotDetermined -> PermissionStatus.NOT_REQUESTED
                else -> PermissionStatus.DENIED // denied
            }
            cont.resume(status)
        }
    }

    private fun speechStatus(status: SFSpeechRecognizerAuthorizationStatus): PermissionStatus = when (status) {
        SFSpeechRecognizerAuthorizationStatusAuthorized -> PermissionStatus.GRANTED
        SFSpeechRecognizerAuthorizationStatusNotDetermined -> PermissionStatus.NOT_REQUESTED
        else -> PermissionStatus.DENIED // denied or restricted
    }
}
