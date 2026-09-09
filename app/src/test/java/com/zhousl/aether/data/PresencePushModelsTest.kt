package com.zhousl.aether.data

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresencePushModelsTest {
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun disabledPresenceReturnsNull() {
        val settings = PresencePushSettings(enabled = false)

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
        )

        assertNull(next)
    }

    @Test
    fun intervalAdvancesWithinActiveWindow() {
        val settings = PresencePushSettings(
            enabled = true,
            intervalMinutes = 120,
            jitterEnabled = false,
        )

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
        )

        assertEquals(millis("2026-05-16T12:00:00"), next)
    }

    @Test
    fun quietHoursRollPushToNextMorning() {
        val settings = PresencePushSettings(
            enabled = true,
            intervalMinutes = 120,
            jitterEnabled = false,
        )

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T21:30:00"),
            zoneId = zoneId,
        )

        assertEquals(millis("2026-05-17T08:00:00"), next)
    }

    @Test
    fun crossingActiveWindowAdvancesToEveningStart() {
        val settings = PresencePushSettings(
            enabled = true,
            intervalMinutes = 120,
            activeStartMinuteOfDay = 22 * 60,
            activeEndMinuteOfDay = 8 * 60,
            quietStartMinuteOfDay = 23 * 60,
            quietEndMinuteOfDay = 7 * 60,
            jitterEnabled = false,
        )

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T09:00:00"),
            zoneId = zoneId,
        )

        assertEquals(millis("2026-05-16T22:00:00"), next)
    }

    @Test
    fun jitterStaysWithinConfiguredBound() {
        val settings = PresencePushSettings(
            enabled = true,
            intervalMinutes = 120,
            jitterEnabled = true,
        )

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
            random = Random(7),
        )

        val expectedBase = millis("2026-05-16T12:00:00")
        assertTrue(next != null)
        assertTrue(next!! >= expectedBase)
        assertTrue(next!! <= expectedBase + PresenceDefaultJitterWindowMillis)
    }

    @Test
    fun lastTriggerAnchorsNextInterval() {
        val settings = PresencePushSettings(
            enabled = true,
            intervalMinutes = 120,
            jitterEnabled = false,
            lastTriggerAtMillis = millis("2026-05-16T09:00:00"),
        )

        val next = settings.nextTriggerAfter(
            afterMillis = millis("2026-05-16T10:00:00"),
            zoneId = zoneId,
        )

        assertEquals(millis("2026-05-16T11:00:00"), next)
    }

    private fun millis(value: String): Long =
        LocalDateTime.parse(value)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
}
