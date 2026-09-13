package com.zhousl.aether.data.accessibility

import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Three-state diagnosis of Qing's screen-reading service.
 *
 * The distinction matters: "the user never turned it on", "it is on but the system has not bound
 * it (or an OEM killed it)" and "ready" need different advice, and a single boolean would collapse
 * them into a useless "it doesn't work". Deliberately no automatic repair — the reference
 * implementation's recovery machinery is not being ported (see the V2.2 plan).
 */
object AccessibilityStatus {

    enum class State {
        /** Not enabled in system settings. */
        Disabled,

        /** Enabled in settings, but the service is not bound right now. */
        EnabledButNotConnected,

        /** Enabled and connected: tools can use it. */
        Ready,
    }

    fun state(context: Context): State {
        if (!isEnabledInSettings(context)) return State.Disabled
        return if (QingAccessibilityService.isRunning()) State.Ready else State.EnabledButNotConnected
    }

    /** Whether the user has Qing's service switched on in Settings → Accessibility. */
    fun isEnabledInSettings(context: Context): Boolean =
        runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val expectedName = QingAccessibilityService::class.java.name
            manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == expectedName
                }
        }.getOrDefault(false)

    /** Where the user has to go to switch it on. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** One sentence explaining the current state, for tool results and the settings row. */
    fun explain(state: State): String = when (state) {
        State.Disabled ->
            "Qing's screen-reading service is switched off. Ask the user to open Settings → Accessibility → " +
                "Installed apps and turn on \"Qing screen control\", then try again."
        State.EnabledButNotConnected ->
            "Qing's screen-reading service is switched on but is not connected right now. " +
                "Ask the user to toggle it off and on in Settings → Accessibility, then try again."
        State.Ready -> "Qing's screen-reading service is ready."
    }
}
