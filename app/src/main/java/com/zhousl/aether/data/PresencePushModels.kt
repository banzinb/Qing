package com.zhousl.aether.data

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

private const val MinPresenceIntervalMinutes = 15
private const val MaxPresenceIntervalMinutes = 12 * 60
internal const val PresenceDefaultJitterWindowMillis = 15 * 60_000L

data class PresencePushSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 120,
    val activeStartMinuteOfDay: Int = 8 * 60,
    val activeEndMinuteOfDay: Int = 22 * 60,
    val quietStartMinuteOfDay: Int = 23 * 60,
    val quietEndMinuteOfDay: Int = 7 * 60,
    val jitterEnabled: Boolean = true,
    val lastTriggerAtMillis: Long? = null,
) {
    val effectiveIntervalMillis: Long
        get() = intervalMinutes.coerceIn(MinPresenceIntervalMinutes, MaxPresenceIntervalMinutes) * 60_000L
}

fun PresencePushSettings.nextTriggerAfter(
    afterMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    random: Random = Random.Default,
): Long? {
    if (!enabled) return null
    val intervalMillis = effectiveIntervalMillis
    val anchor = lastTriggerAtMillis ?: afterMillis
    val earliest = afterMillis + 1L
    var candidate = if (anchor >= earliest) {
        anchor
    } else {
        anchor + (((earliest - anchor - 1L) / intervalMillis) + 1L) * intervalMillis
    }
    candidate = candidate.advanceIntoActiveWindow(this, zoneId)
    candidate = candidate.advancePastQuietWindow(this, zoneId)
    if (jitterEnabled) {
        val maxJitter = PresenceDefaultJitterWindowMillis
            .coerceAtMost(intervalMillis / 2L)
            .coerceAtLeast(1_000L)
        candidate += random.nextLong(0L, maxJitter + 1L)
        candidate = candidate.advanceIntoActiveWindow(this, zoneId)
        candidate = candidate.advancePastQuietWindow(this, zoneId)
    }
    return candidate
}

private fun Long.advanceIntoActiveWindow(
    settings: PresencePushSettings,
    zoneId: ZoneId,
): Long {
    val start = settings.activeStartMinuteOfDay
    val end = settings.activeEndMinuteOfDay
    if (start == end) return this
    var candidate = this
    repeat(370) {
        val local = Instant.ofEpochMilli(candidate).atZone(zoneId)
        val date = local.toLocalDate()
        val minute = minuteOfDay(local.hour, local.minute)
        if (isMinuteInsideWindow(minute, start, end)) return candidate
        val nextStartDate = when {
            start <= end && minute < start -> date
            start <= end -> date.plusDays(1)
            else -> date
        }
        candidate = nextStartDate.atStartOfDay(zoneId).toInstant().toEpochMilli() + start * 60_000L
    }
    return candidate
}

private fun Long.advancePastQuietWindow(
    settings: PresencePushSettings,
    zoneId: ZoneId,
): Long {
    val start = settings.quietStartMinuteOfDay
    val end = settings.quietEndMinuteOfDay
    if (start == end) return this
    var candidate = this
    repeat(370) {
        val local = Instant.ofEpochMilli(candidate).atZone(zoneId)
        val date = local.toLocalDate()
        val minute = minuteOfDay(local.hour, local.minute)
        if (!isMinuteInsideWindow(minute, start, end)) return candidate
        val endDate = when {
            start <= end || minute < end -> date
            else -> date.plusDays(1)
        }
        candidate = endDate.atStartOfDay(zoneId).toInstant().toEpochMilli() + end * 60_000L
    }
    return candidate
}

private fun isMinuteInsideWindow(
    minute: Int,
    start: Int,
    end: Int,
): Boolean = if (start <= end) {
    minute in start until end
} else {
    minute >= start || minute < end
}

private fun minuteOfDay(
    hour: Int,
    minute: Int,
): Int = hour * 60 + minute
