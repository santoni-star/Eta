package io.github.mangi.eta.agent.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

internal object BackgroundCommandNotification {
    private const val CHANNEL = "eta_background_commands"
    private const val NOTIFICATION_ID = 1124

    fun show(context: Context, task: DetachedTask) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "назадвежажиттязробитивузолфрукти", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = when (task.commandStatus) {
            "completed" -> "назадвежажиттязробитивжезакінченостати"
            "timed_out" -> "назадвежажиттязробитивжеобганятигодина"
            "stopped" -> "назадвежажиттязробитивжеСТІЙкінець"
            "interrupted" -> "назадвежажиттязробитивжесерединаперерва"
            else -> "назадвежажиттязробитиВиконатидобревтратитипоразка"
        }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(task.description.ifBlank { "існуютькінецькінецьзназадвежапризначитиобслуговуваннясерединаперевіритиподивітьсяформустаніденьчестолюбство" })
            .setSubText(task.exitCode?.let { "відступпозакод $it" })
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        if (manager.areNotificationsEnabled()) manager.notify(task.id, NOTIFICATION_ID, notification)
    }
}
