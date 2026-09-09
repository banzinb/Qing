package com.zhousl.aether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PresencePushAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ActionRunPresencePush -> {
                val pendingResult = goAsync()
                context.aetherRuntime.handlePresencePushAlarm(pendingResult)
            }

            ActionDismissPresencePush -> {
                context.aetherRuntime.dismissPresencePushNotification()
            }

            ActionDisablePresencePush -> {
                val pendingResult = goAsync()
                context.aetherRuntime.disablePresencePush(pendingResult)
            }
        }
    }

    companion object {
        const val ActionRunPresencePush = "com.zhousl.aether.action.RUN_PRESENCE_PUSH"
        const val ActionDismissPresencePush = "com.zhousl.aether.action.DISMISS_PRESENCE_PUSH"
        const val ActionDisablePresencePush = "com.zhousl.aether.action.DISABLE_PRESENCE_PUSH"
    }
}
