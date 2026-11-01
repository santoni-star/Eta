package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/**
 * Служба відтворення на передньому плані читання чату: існує лише під час фактичного періоду розмови, що дозволяє програмі повернутися у фоновий режим або читати вголос після блокування екрана, не зависаючи системою.
 * Саме відтворення все ще утримується контролером сторінки чату; "stop" у сповіщенні повертається до контролера через [onStopRequested].
 */
internal class ReadAloudPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            onStopRequested?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (failure: RuntimeException) {
            // Якщо кваліфікацію реєстрації відхилено, читання все одно триватиме на передньому плані, але може бути призупинено системою, якщо воно відійде у фоновий режим.
            AndroidAgentLogger.warn("Read-aloud foreground failed: type=${failure.safeLogType()}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.read_aloud_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ReadAloudPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.read_aloud_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, getString(R.string.speech_read_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_READ_ALOUD"
        private const val CHANNEL = "eta_read_aloud"
        private const val NOTIFICATION_ID = 4204

        /** Зупинка введення поточного показання; одночасно є лише одне читання, і розпочате пізніше замінить попереднє.*/
        @Volatile private var onStopRequested: (() -> Unit)? = null

        /** Викликається в момент, коли користувач клацає, щоб прочитати вголос. У цей час програма все ще працює на передньому плані, і службу переднього плану можна запустити.*/
        fun start(context: Context, onStop: () -> Unit) {
            onStopRequested = onStop
            try {
                context.startForegroundService(Intent(context, ReadAloudPlaybackService::class.java))
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn("Read-aloud service start rejected: type=${failure.safeLogType()}")
            }
        }

        fun stop(context: Context) {
            onStopRequested = null
            context.stopService(Intent(context, ReadAloudPlaybackService::class.java))
        }
    }
}
