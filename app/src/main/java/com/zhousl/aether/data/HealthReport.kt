package com.zhousl.aether.data

/**
 * One row of the self-check page: something that routinely breaks, and whether
 * it currently works.
 *
 * [HealthStatus.Attention] means the user can do something about it.
 * [HealthStatus.Ok] also covers "optional and switched off" — Agent mode, for
 * instance, is not a problem when it is disabled.
 */
enum class HealthStatus {
    Ok,
    Attention,
}

enum class HealthCheckId {
    Model,
    Runtime,
    AgentMode,
    Notifications,
    Battery,
    ExactAlarm,
    PhonePermissions,
    LastCrash,
}

/**
 * A single check result.
 *
 * [detail] carries facts only, because the wording lives in the string
 * resources and has to follow the current locale:
 *
 * - [HealthCheckId.Model]: the default model name, empty when unset
 * - [HealthCheckId.Runtime]: `alpine`, `termux`, `both`, or empty when neither is ready
 * - [HealthCheckId.AgentMode]: `disabled` or `not_authorized`, empty when ready
 * - [HealthCheckId.PhonePermissions]: the missing ones, comma-separated, from
 *   `location`, `contacts`, `calendar`
 * - [HealthCheckId.LastCrash]: the crash time as epoch millis, empty when there is none
 */
data class HealthCheckItem(
    val id: HealthCheckId,
    val status: HealthStatus,
    val detail: String = "",
)

/** The raw facts the self-check reads. Everything here is cheap to obtain. */
data class HealthSnapshot(
    val providerCount: Int = 0,
    val defaultModel: String = "",
    val alpineReady: Boolean = false,
    val termuxReady: Boolean = false,
    val agentModeEnabled: Boolean = false,
    val agentModeReady: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    val exactAlarmAllowed: Boolean = true,
    val locationGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val calendarGranted: Boolean = false,
    val lastCrashAtMillis: Long = 0L,
)

internal const val HealthRuntimeAlpine = "alpine"
internal const val HealthRuntimeTermux = "termux"
internal const val HealthRuntimeBoth = "both"
internal const val HealthAgentModeDisabled = "disabled"
internal const val HealthAgentModeNotAuthorized = "not_authorized"
internal const val HealthPermissionLocation = "location"
internal const val HealthPermissionContacts = "contacts"
internal const val HealthPermissionCalendar = "calendar"

/**
 * Turns the snapshot into the list the page renders. Kept pure so the ordering
 * and the "is this actually a problem" judgement can be unit tested without a
 * device.
 */
fun buildHealthReport(snapshot: HealthSnapshot): List<HealthCheckItem> {
    val modelReady = snapshot.providerCount > 0 && snapshot.defaultModel.isNotBlank()
    val runtimeDetail = when {
        snapshot.alpineReady && snapshot.termuxReady -> HealthRuntimeBoth
        snapshot.alpineReady -> HealthRuntimeAlpine
        snapshot.termuxReady -> HealthRuntimeTermux
        else -> ""
    }
    val agentModeDetail = when {
        snapshot.agentModeReady -> ""
        snapshot.agentModeEnabled -> HealthAgentModeNotAuthorized
        else -> HealthAgentModeDisabled
    }
    val missingPermissions = buildList {
        if (!snapshot.locationGranted) add(HealthPermissionLocation)
        if (!snapshot.contactsGranted) add(HealthPermissionContacts)
        if (!snapshot.calendarGranted) add(HealthPermissionCalendar)
    }
    return listOf(
        HealthCheckItem(
            id = HealthCheckId.Model,
            status = if (modelReady) HealthStatus.Ok else HealthStatus.Attention,
            detail = snapshot.defaultModel,
        ),
        HealthCheckItem(
            id = HealthCheckId.Runtime,
            status = if (runtimeDetail.isNotEmpty()) HealthStatus.Ok else HealthStatus.Attention,
            detail = runtimeDetail,
        ),
        HealthCheckItem(
            id = HealthCheckId.AgentMode,
            status = if (agentModeDetail == HealthAgentModeNotAuthorized) {
                HealthStatus.Attention
            } else {
                HealthStatus.Ok
            },
            detail = agentModeDetail,
        ),
        HealthCheckItem(
            id = HealthCheckId.Notifications,
            status = if (snapshot.notificationsAllowed) HealthStatus.Ok else HealthStatus.Attention,
        ),
        HealthCheckItem(
            id = HealthCheckId.Battery,
            status = if (snapshot.batteryUnrestricted) HealthStatus.Ok else HealthStatus.Attention,
        ),
        HealthCheckItem(
            id = HealthCheckId.ExactAlarm,
            status = if (snapshot.exactAlarmAllowed) HealthStatus.Ok else HealthStatus.Attention,
        ),
        HealthCheckItem(
            id = HealthCheckId.PhonePermissions,
            status = if (missingPermissions.isEmpty()) HealthStatus.Ok else HealthStatus.Attention,
            detail = missingPermissions.joinToString(","),
        ),
        HealthCheckItem(
            id = HealthCheckId.LastCrash,
            status = if (snapshot.lastCrashAtMillis > 0L) HealthStatus.Attention else HealthStatus.Ok,
            detail = snapshot.lastCrashAtMillis.takeIf { it > 0L }?.toString().orEmpty(),
        ),
    )
}
