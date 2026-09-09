package com.zhousl.aether.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhousl.aether.PresencePushAlarmReceiver
import kotlin.math.max

class PresencePushScheduler(
    private val context: Context,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtMillis: Long) {
        cancel()
        val pendingIntent = presencePendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            val normalized = max(triggerAtMillis, System.currentTimeMillis() + 1_000L)
            if (canScheduleExactAlarms()) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            normalized,
                            pendingIntent,
                        )
                    } else {
                        alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            normalized,
                            pendingIntent,
                        )
                    }
                    diagnosticLogger.event(
                        category = "presence_push",
                        event = "alarm_scheduled",
                        details = mapOf(
                            "trigger_at_millis" to normalized,
                            "schedule_mode" to "exact",
                            "can_schedule_exact" to true,
                        ),
                    )
                    return
                } catch (securityException: SecurityException) {
                    diagnosticLogger.exception(
                        category = "presence_push",
                        event = "exact_alarm_denied",
                        throwable = securityException,
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    normalized,
                    pendingIntent,
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    normalized,
                    pendingIntent,
                )
            }
            diagnosticLogger.event(
                category = "presence_push",
                event = "alarm_scheduled",
                details = mapOf(
                    "trigger_at_millis" to normalized,
                    "schedule_mode" to "allow_while_idle",
                    "can_schedule_exact" to false,
                ),
            )
        } catch (throwable: Throwable) {
            diagnosticLogger.exception(
                category = "presence_push",
                event = "alarm_schedule_failed",
                throwable = throwable,
                details = mapOf("trigger_at_millis" to triggerAtMillis),
            )
        }
    }

    fun cancel() {
        val pendingIntent = presencePendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(pendingIntent)
    }

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun presencePendingIntent(updateFlag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        PresencePushAlarmRequestCode,
        Intent(context, PresencePushAlarmReceiver::class.java).apply {
            action = PresencePushAlarmReceiver.ActionRunPresencePush
        },
        PendingIntent.FLAG_IMMUTABLE or updateFlag,
    )

    private companion object {
        const val PresencePushAlarmRequestCode = 8_741
    }
}
