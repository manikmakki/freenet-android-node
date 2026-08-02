package org.freenet.androidnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

class NodeNotificationManager(private val context: Context) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.node_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.node_notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun build(state: NodeUiState): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getService(
            context,
            REQUEST_PAUSE,
            NodeService.pauseIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getService(
            context,
            REQUEST_RESUME,
            NodeService.startNetworkIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            NodeService.stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_node_notification)
            .setContentTitle(notificationTitle(state))
            .setContentText(notificationText(state))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        val primaryAction = if (state.state == "Paused") {
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_node_notification),
                context.getString(R.string.resume_node),
                resume,
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_node_notification),
                context.getString(R.string.pause_node),
                pause,
            ).build()
        }
        builder
            .addAction(primaryAction)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_node_notification),
                    context.getString(R.string.stop_node),
                    stop,
                ).build(),
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun notificationTitle(state: NodeUiState): String = when (state.state) {
        "Starting" -> context.getString(R.string.node_notification_starting)
        "Waiting" -> context.getString(R.string.node_notification_waiting)
        "Paused" -> context.getString(R.string.node_notification_paused)
        else -> context.getString(R.string.node_notification_running)
    }

    private fun notificationText(state: NodeUiState): String =
        if (state.state == "Waiting" || state.state == "Paused") {
            state.detail
        } else {
            context.getString(
                R.string.node_notification_status,
                state.mode,
                state.peers,
                formatUptime(state.uptimeMs),
            )
        }

    fun update(state: NodeUiState) {
        notificationManager.notify(NOTIFICATION_ID, build(state))
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun formatUptime(uptimeMs: Long): String {
        val totalSeconds = uptimeMs / 1_000
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    companion object {
        const val CHANNEL_ID = "freenet_node_status"
        const val NOTIFICATION_ID = 7509

        private const val REQUEST_OPEN_APP = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_RESUME = 4
    }
}
