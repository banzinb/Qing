package com.zhousl.aether.ui

import com.zhousl.aether.data.ChatUsageStatisticsSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.round

/**
 * The numbers on the drawer's usage card: what was spent today, and how fast
 * the answers came back.
 */
data class UsageCardSummary(
    val todayTokens: Long,
    val todayTurnCount: Int,
    val todayAverageTokensPerSecond: Double?,
    val hasHistory: Boolean,
)

/**
 * Rolls the recorded turns up for one day. Turns without a timestamp are
 * ignored rather than counted into today, so the card never overstates usage.
 */
fun buildUsageCardSummary(
    snapshots: List<ChatUsageStatisticsSnapshot>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): UsageCardSummary {
    var tokens = 0L
    var turns = 0
    var speedTotal = 0.0
    var speedSamples = 0
    snapshots.forEach { snapshot ->
        val statistic = snapshot.statistics
        val millis = statistic.completedAtMillis.takeIf { it > 0L } ?: statistic.startedAtMillis
        if (millis <= 0L) return@forEach
        if (Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() != today) return@forEach
        tokens += statistic.totalTokens ?: 0L
        turns += 1
        statistic.outputTokensPerSecond?.let { speed ->
            speedTotal += speed
            speedSamples += 1
        }
    }
    return UsageCardSummary(
        todayTokens = tokens,
        todayTurnCount = turns,
        todayAverageTokensPerSecond = if (speedSamples > 0) speedTotal / speedSamples else null,
        hasHistory = snapshots.isNotEmpty(),
    )
}

/**
 * Short token counts that still fit one line in the drawer: 940, 12.4k, 1.3M.
 * Kept locale-independent on purpose, the way model dashboards do it.
 */
fun formatCompactTokens(value: Long): String = when {
    value < 1_000L -> value.toString()
    value < 1_000_000L -> compactUnit(value / 1_000.0, "k")
    else -> compactUnit(value / 1_000_000.0, "M")
}

/** One decimal is plenty for a "how fast did that feel" number. */
fun formatTokensPerSecond(value: Double): String =
    String.format(Locale.US, "%.1f", value)

private fun compactUnit(scaled: Double, suffix: String): String {
    val tenths = round(scaled * 10.0) / 10.0
    val text = if (tenths == round(tenths)) {
        tenths.toLong().toString()
    } else {
        tenths.toString()
    }
    return text + suffix
}
