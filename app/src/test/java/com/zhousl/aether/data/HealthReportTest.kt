package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthReportTest {
    private fun item(snapshot: HealthSnapshot, id: HealthCheckId): HealthCheckItem =
        buildHealthReport(snapshot).first { it.id == id }

    @Test
    fun `a fresh install points at the model and the runtime`() {
        val report = buildHealthReport(HealthSnapshot())

        assertEquals(HealthStatus.Attention, item(HealthSnapshot(), HealthCheckId.Model).status)
        assertEquals(HealthStatus.Attention, item(HealthSnapshot(), HealthCheckId.Runtime).status)
        assertEquals(9, report.size)
        assertEquals(HealthCheckId.Model, report.first().id)
    }

    @Test
    fun `the context window is reported as a fact and never as a fault`() {
        val known = item(HealthSnapshot(contextWindowTokens = 1_000_000), HealthCheckId.ContextWindow)
        val unknown = item(HealthSnapshot(), HealthCheckId.ContextWindow)

        assertEquals(HealthStatus.Ok, known.status)
        assertEquals("1000000", known.detail)
        assertEquals(HealthStatus.Ok, unknown.status)
        assertEquals("", unknown.detail)
    }

    @Test
    fun `a configured install is quiet about the basics`() {
        val snapshot = HealthSnapshot(
            providerCount = 1,
            defaultModel = "deepseek-chat",
            alpineReady = true,
        )

        assertEquals(HealthStatus.Ok, item(snapshot, HealthCheckId.Model).status)
        assertEquals("deepseek-chat", item(snapshot, HealthCheckId.Model).detail)
        assertEquals(HealthStatus.Ok, item(snapshot, HealthCheckId.Runtime).status)
        assertEquals(HealthRuntimeAlpine, item(snapshot, HealthCheckId.Runtime).detail)
    }

    @Test
    fun `a provider without a default model still needs attention`() {
        val snapshot = HealthSnapshot(providerCount = 2, defaultModel = "")

        assertEquals(HealthStatus.Attention, item(snapshot, HealthCheckId.Model).status)
    }

    @Test
    fun `both runtimes ready reads as both`() {
        val snapshot = HealthSnapshot(alpineReady = true, termuxReady = true)

        assertEquals(HealthRuntimeBoth, item(snapshot, HealthCheckId.Runtime).detail)
    }

    @Test
    fun `agent mode is only a problem when it is on but not authorized`() {
        val off = HealthSnapshot(agentModeEnabled = false, agentModeReady = false)
        val broken = HealthSnapshot(agentModeEnabled = true, agentModeReady = false)
        val working = HealthSnapshot(agentModeEnabled = true, agentModeReady = true)

        assertEquals(HealthStatus.Ok, item(off, HealthCheckId.AgentMode).status)
        assertEquals(HealthAgentModeDisabled, item(off, HealthCheckId.AgentMode).detail)
        assertEquals(HealthStatus.Attention, item(broken, HealthCheckId.AgentMode).status)
        assertEquals(HealthAgentModeNotAuthorized, item(broken, HealthCheckId.AgentMode).detail)
        assertEquals(HealthStatus.Ok, item(working, HealthCheckId.AgentMode).status)
    }

    @Test
    fun `missing phone permissions are listed by name`() {
        val snapshot = HealthSnapshot(
            locationGranted = false,
            contactsGranted = true,
            calendarGranted = false,
        )

        val permissions = item(snapshot, HealthCheckId.PhonePermissions)

        assertEquals(HealthStatus.Attention, permissions.status)
        assertEquals("location,calendar", permissions.detail)
    }

    @Test
    fun `all phone permissions granted reads as ok`() {
        val snapshot = HealthSnapshot(
            locationGranted = true,
            contactsGranted = true,
            calendarGranted = true,
        )

        assertEquals(HealthStatus.Ok, item(snapshot, HealthCheckId.PhonePermissions).status)
        assertEquals("", item(snapshot, HealthCheckId.PhonePermissions).detail)
    }

    @Test
    fun `the keep alive switches each stand on their own`() {
        val snapshot = HealthSnapshot(
            notificationsAllowed = false,
            batteryUnrestricted = false,
            exactAlarmAllowed = false,
        )

        assertEquals(HealthStatus.Attention, item(snapshot, HealthCheckId.Notifications).status)
        assertEquals(HealthStatus.Attention, item(snapshot, HealthCheckId.Battery).status)
        assertEquals(HealthStatus.Attention, item(snapshot, HealthCheckId.ExactAlarm).status)
    }

    @Test
    fun `a crash breadcrumb is reported with its time`() {
        val snapshot = HealthSnapshot(lastCrashAtMillis = 1_760_000_000_000L)

        val crash = item(snapshot, HealthCheckId.LastCrash)

        assertEquals(HealthStatus.Attention, crash.status)
        assertEquals("1760000000000", crash.detail)
        assertTrue(buildHealthReport(HealthSnapshot()).last().detail.isEmpty())
    }
}
