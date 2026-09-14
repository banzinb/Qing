package com.zhousl.aether

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zhousl.aether.data.SessionExecutionState
import com.zhousl.aether.ui.ChatSession

private const val ForegroundChannelId = "aether_background_runs"
private const val CompletionChannelId = "aether_completed_runs"
// Android refuses to raise an existing channel's importance, and MIUI drops
// IMPORTANCE_DEFAULT channels as "not priority", so a finished task gets a
// channel of its own that is allowed to show a heads-up banner. The old
// channel stays in place for installs that already created it.
private const val TaskDoneChannelId = "aether_task_done"
private const val PresenceChannelId = "aether_presence"
const val ForegroundNotificationId = 1001
const val PresenceNotificationId = 2002

/** Intent extra that carries the chat a completion alert (or a jump home) belongs to. */
const val SessionIdExtra = "com.zhousl.aether.extra.SESSION_ID"

class AetherNotificationController(
    private val context: Context,
) {
    private val notificationManager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val foregroundChannel = NotificationChannel(
            ForegroundChannelId,
            "Background tasks",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active Qing sessions running in the background."
            setShowBadge(false)
        }
        val completionChannel = NotificationChannel(
            CompletionChannelId,
            "Task completion",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts you when a background Qing session finishes."
        }
        val presenceChannel = NotificationChannel(
            PresenceChannelId,
            "Active pushes",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Shows proactive memory summaries from Qing."
            setShowBadge(true)
        }
        val taskDoneChannel = NotificationChannel(
            TaskDoneChannelId,
            context.getString(R.string.notification_channel_task_done),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_task_done_description)
            setShowBadge(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(foregroundChannel)
        manager.createNotificationChannel(completionChannel)
        manager.createNotificationChannel(presenceChannel)
        manager.createNotificationChannel(taskDoneChannel)
    }

    fun buildForegroundNotification(
        sessions: List<ChatSession>,
        executionStates: Map<String, SessionExecutionState>,
    ): Notification {
        val activeSessions = sessions.filter { executionStates[it.id]?.isRunning == true }
        val title = if (activeSessions.size == 1) {
            "Qing is running 1 task"
        } else {
            "Qing is running ${activeSessions.size} tasks"
        }
        val body = activeSessions
            .take(3)
            .joinToString(separator = ", ") { it.title.ifBlank { "Untitled chat" } }
            .ifBlank { "Keeping active sessions alive in the background." }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        return NotificationCompat.Builder(context, ForegroundChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        activeSessions.joinToString(separator = "\n") { session ->
                            "- ${session.title.ifBlank { "Untitled chat" }}"
                        }.ifBlank { body }
                    )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    fun notifyCompletion(
        sessionId: String,
        sessionTitle: String,
        summary: String,
        failed: Boolean,
    ) {
        if (!canPostUserNotifications()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(SessionIdExtra, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        val title = if (failed) {
            context.getString(R.string.notification_task_finished_issue_title)
        } else {
            context.getString(R.string.notification_task_finished_title)
        }

        val notification = NotificationCompat.Builder(context, TaskDoneChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(sessionTitle.ifBlank { "Untitled chat" })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        append(sessionTitle.ifBlank { "Untitled chat" })
                        if (summary.isNotBlank()) {
                            append("\n")
                            append(summary)
                        }
                    }
                )
            )
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .addAction(
                R.drawable.ic_notification_small,
                context.getString(R.string.presence_notification_open),
                contentIntent,
            )
            .build()

        try {
            notificationManager.notify(sessionId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the preflight check.
        }
    }

    /**
     * Brings the app back to the foreground after a background run ends.
     *
     * Qing targets API 28, so Android 10's background activity start
     * restriction does not apply to it — but a ROM can still gate the start on
     * its own "background popup" switch (MIUI/HyperOS does), in which case this
     * silently does nothing and the completion notification is the fallback.
     *
     * [sessionId] rides along so the window opens on the chat that just
     * finished instead of whatever was on screen when the user left.
     */
    fun returnToApp(sessionId: String? = null) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (!sessionId.isNullOrBlank()) putExtra(SessionIdExtra, sessionId)
                },
            )
        } catch (_: Exception) {
            // Some ROMs refuse background activity starts; the completion
            // notification is still there as a fallback.
        }
    }

    fun notifyPresencePush(summary: String) {
        if (!canPostUserNotifications()) return

        val contentIntent = PendingIntent.getActivity(
            context,
            PresenceNotificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )
        val dismissIntent = PendingIntent.getBroadcast(
            context,
            PresenceNotificationId,
            Intent(context, PresencePushAlarmReceiver::class.java).apply {
                action = PresencePushAlarmReceiver.ActionDismissPresencePush
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )
        val disableIntent = PendingIntent.getBroadcast(
            context,
            PresenceNotificationId + 1,
            Intent(context, PresencePushAlarmReceiver::class.java).apply {
                action = PresencePushAlarmReceiver.ActionDisablePresencePush
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlags(),
        )

        val notification = NotificationCompat.Builder(context, PresenceChannelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(context.getString(R.string.presence_notification_title))
            .setContentText(context.getString(R.string.presence_notification_body))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    summary.ifBlank { context.getString(R.string.presence_notification_body) }
                )
            )
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_notification_small,
                context.getString(R.string.presence_notification_open),
                contentIntent,
            )
            .addAction(
                R.drawable.ic_notification_small,
                context.getString(R.string.presence_notification_ignore),
                dismissIntent,
            )
            .addAction(
                R.drawable.ic_notification_small,
                context.getString(R.string.presence_notification_disable),
                disableIntent,
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            notificationManager.notify(PresenceNotificationId, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the preflight check.
        }
    }

    fun cancelPresencePush() {
        try {
            notificationManager.cancel(PresenceNotificationId)
        } catch (_: SecurityException) {
            // Notification permission can be revoked after the preflight check.
        }
    }

    private fun canPostUserNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pendingIntentMutabilityFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
