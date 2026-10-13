package app.watchaudio.android.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.watchaudio.android.AppGraph
import app.watchaudio.android.MainActivity
import app.watchaudio.app.Strings
import app.watchaudio.app.logic.formatClock
import app.watchaudio.core.agent.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * Keeps agent access on (#184): a foreground service of type `connectedDevice` (it talks to the
 * user's own PC on the local network) that runs [app.watchaudio.core.agent.AgentConnector] and
 * shows an ongoing notification with the PCs connected, the auto-off time and an 「OFF にする」
 * button. It stops at the deadline (a coroutine timer, backed by an inexact alarm in case the CPU
 * sleeps through it; the connector itself refuses messages past the deadline), on the button, when
 * the user turns access off in the app, or when the system stops it. It is never restarted by the
 * system (START_NOT_STICKY): access stays off until the user turns it on again.
 */
class AgentAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running: Job? = null

    private val access get() = AppGraph.get(this).agentAccess

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val offAt = access.offAt
        if (intent?.action == ACTION_STOP || offAt == null || offAt <= System.currentTimeMillis()) {
            stopEverything()
            return START_NOT_STICKY
        }
        ensureChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(offAt, emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scheduleAlarm(offAt)
        if (running?.isActive != true) {
            running = scope.launch { serve() }
        }
        return START_NOT_STICKY
    }

    private suspend fun serve() {
        val transport = access.transport()
        val connector = access.connector(transport)
        val links = scope.launch {
            connector.links.collect { links ->
                access.publishLinks(links)
                val offAt = access.offAt ?: return@collect
                val names = AppGraph.get(this@AgentAccessService).store.read { agentPairings() }
                    .filter { links[it.id] == LinkState.CONNECTED }.map { it.name }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(offAt, names))
            }
        }
        val timer = scope.launch {
            // Re-read each round: turning access on again while it is on moves the deadline.
            while (true) {
                val left = (access.offAt ?: break) - System.currentTimeMillis()
                if (left <= 0) {
                    Log.i(TAG, "agent access turned itself off")
                    access.turnOff()
                    break
                }
                delay(left.coerceAtMost(TIMER_STEP_MS))
            }
        }
        try {
            connector.run()
        } finally {
            links.cancel()
            timer.cancel()
        }
    }

    private fun stopEverything() {
        running?.cancel()
        running = null
        cancelAlarm()
        access.markOff()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cancelAlarm()
        access.markOff()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(offAt: Long, connected: List<String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(Strings.AGENT_NOTIFICATION_TITLE)
            .setContentText(Strings.agentNotificationText(connected, formatClock(offAt, TimeZone.currentSystemDefault())))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, Strings.AGENT_TURN_OFF, stopIntent(this))
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, Strings.AGENT_NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** Wakes the device near the deadline even in Doze (inexact: no exact-alarm permission needed). */
    private fun scheduleAlarm(offAt: Long) {
        val alarms = getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + (offAt - System.currentTimeMillis()).coerceAtLeast(0)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, stopIntent(this))
    }

    private fun cancelAlarm() {
        getSystemService(AlarmManager::class.java)?.cancel(stopIntent(this))
    }

    companion object {
        private const val TAG = "AgentAccessService"
        private const val CHANNEL_ID = "agent-access"
        private const val NOTIFICATION_ID = 184
        private const val TIMER_STEP_MS = 60_000L
        private const val ACTION_START = "app.watchaudio.agent.START"
        private const val ACTION_STOP = "app.watchaudio.agent.STOP"

        /** Starts (or refreshes) the service from the user's tap, with the app in the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AgentAccessService::class.java).setAction(ACTION_START))
        }

        /** Stops the service; harmless if it is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentAccessService::class.java))
        }

        private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 0, Intent(context, AgentAccessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
