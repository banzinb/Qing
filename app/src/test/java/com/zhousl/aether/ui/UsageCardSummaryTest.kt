package com.zhousl.aether.ui

import com.zhousl.aether.data.ChatUsageStatisticsSnapshot
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCardSummaryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 9, 12)

    private fun millisAt(hour: Int, day: Int = 12): Long =
        ZonedDateTime.of(2026, 9, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun snapshot(
        totalTokens: Long?,
        outputTokens: Long? = null,
        outputWindowMillis: Long = 0L,
        completedAtMillis: Long = 0L,
        startedAtMillis: Long = 0L,
    ) = ChatUsageStatisticsSnapshot(
        sessionId = "session",
        statistics = ChatUsageStatistics(
            totalTokens = totalTokens,
            outputTokens = outputTokens,
            completedAtMillis = completedAtMillis,
            startedAtMillis = if (outputWindowMillis > 0L) {
                completedAtMillis - outputWindowMillis
            } else {
                startedAtMillis
            },
        ),
    )

    @Test
    fun `counts only today`() {
        val summary = buildUsageCardSummary(
            snapshots = listOf(
                snapshot(totalTokens = 1_000L, completedAtMillis = millisAt(hour = 9)),
                snapshot(totalTokens = 500L, completedAtMillis = millisAt(hour = 20)),
                snapshot(totalTokens = 7_000L, completedAtMillis = millisAt(hour = 9, day = 11)),
            ),
            zone = zone,
            today = today,
        )

        assertEquals(1_500L, summary.todayTokens)
        assertEquals(2, summary.todayTurnCount)
        assertTrue(summary.hasHistory)
    }

    @Test
    fun `averages the speed samples of today`() {
        val summary = buildUsageCardSummary(
            snapshots = listOf(
                snapshot(
                    totalTokens = 10L,
                    outputTokens = 200L,
                    outputWindowMillis = 10_000L,
                    completedAtMillis = millisAt(hour = 9),
                ),
                snapshot(
                    totalTokens = 10L,
                    outputTokens = 400L,
                    outputWindowMillis = 10_000L,
                    completedAtMillis = millisAt(hour = 10),
                ),
                snapshot(
                    totalTokens = 10L,
                    outputTokens = 990L,
                    outputWindowMillis = 10_000L,
                    completedAtMillis = millisAt(hour = 9, day = 10),
                ),
            ),
            zone = zone,
            today = today,
        )

        assertEquals(30.0, summary.todayAverageTokensPerSecond!!, 0.0001)
    }

    @Test
    fun `falls back to the start time and skips turns without a timestamp`() {
        val summary = buildUsageCardSummary(
            snapshots = listOf(
                snapshot(totalTokens = 300L, startedAtMillis = millisAt(hour = 8)),
                snapshot(totalTokens = 900L),
            ),
            zone = zone,
            today = today,
        )

        assertEquals(300L, summary.todayTokens)
        assertEquals(1, summary.todayTurnCount)
        assertNull(summary.todayAverageTokensPerSecond)
    }

    @Test
    fun `a fresh install has no history`() {
        val summary = buildUsageCardSummary(emptyList(), zone = zone, today = today)

        assertEquals(0L, summary.todayTokens)
        assertEquals(0, summary.todayTurnCount)
        assertFalse(summary.hasHistory)
    }

    @Test
    fun `token counts stay short enough for one line`() {
        assertEquals("0", formatCompactTokens(0L))
        assertEquals("940", formatCompactTokens(940L))
        assertEquals("999", formatCompactTokens(999L))
        assertEquals("1k", formatCompactTokens(1_000L))
        assertEquals("12.4k", formatCompactTokens(12_400L))
        assertEquals("1M", formatCompactTokens(1_000_000L))
        assertEquals("1.3M", formatCompactTokens(1_260_000L))
    }

    @Test
    fun `speed keeps one decimal`() {
        assertEquals("0.0", formatTokensPerSecond(0.0))
        assertEquals("12.3", formatTokensPerSecond(12.34))
        assertEquals("48.0", formatTokensPerSecond(47.98))
    }
}
