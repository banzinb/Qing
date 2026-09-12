package com.zhousl.aether.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuditStoreTest {
    @Test
    fun `round trips entries through json`() {
        val entries = listOf(
            ToolAuditEntry(
                atMillis = 1_760_000_000_000L,
                toolName = "bash",
                scopeKey = "runtime:bash",
                summary = "bash rm -rf build",
                decision = "approved",
            ),
            ToolAuditEntry(
                atMillis = 1_760_000_010_000L,
                toolName = "aether_device_manage",
                scopeKey = "host_tool:aether_device_manage:open",
                summary = "aether_device_manage open 微信",
                decision = "denied",
            ),
        )

        assertEquals(entries, ToolAuditLog.parse(ToolAuditLog.serialize(entries)))
    }

    @Test
    fun `parse tolerates garbage and incomplete rows`() {
        assertTrue(ToolAuditLog.parse("").isEmpty())
        assertTrue(ToolAuditLog.parse("not json").isEmpty())
        assertTrue(ToolAuditLog.parse("""[{"tool":"bash"},{"at":5},"nope"]""").isEmpty())
    }

    @Test
    fun `parse keeps the rows it can read`() {
        val raw = """[{"at":7,"tool":"bash","scope_key":"runtime:bash","summary":"bash ls","decision":"approved"}]"""

        val parsed = ToolAuditLog.parse(raw)

        assertEquals(1, parsed.size)
        assertEquals("bash", parsed.first().toolName)
        assertEquals("approved", parsed.first().decision)
    }

    @Test
    fun `masks json style secrets`() {
        val summary = summarizeToolAuditEntry(
            toolName = "aether_config_set",
            argumentsJson = """{"category":"model","token":"sk-live-123"}""",
            preview = """aether_config_set {"api_key":"sk-live-123","note":"ok"}""",
        )

        assertFalse(summary.contains("sk-live-123"))
        assertTrue(summary.contains(ToolAuditMaskedToken))
        assertTrue(summary.contains("note"))
    }

    @Test
    fun `masks inline secrets in shell commands`() {
        val summary = summarizeToolAuditEntry(
            toolName = "bash",
            argumentsJson = "",
            preview = "bash mysql -u root --password=hunter2 -e 'select 1'",
        )

        assertFalse(summary.contains("hunter2"))
        assertTrue(summary.contains("--password="))
    }

    @Test
    fun `never records clipboard contents`() {
        val read = summarizeToolAuditEntry(
            toolName = "aether_device_manage",
            argumentsJson = """{"action":"clipboard_get"}""",
            preview = "aether_device_manage clipboard_get",
        )
        val write = summarizeToolAuditEntry(
            toolName = "aether_device_manage",
            argumentsJson = """{"action":"clipboard_set","value":"id card 110101"}""",
            preview = "aether_device_manage clipboard_set · value=id card 110101",
        )

        assertEquals("aether_device_manage clipboard_get $ToolAuditMaskedToken", read)
        assertEquals("aether_device_manage clipboard_set $ToolAuditMaskedToken", write)
        assertFalse(write.contains("110101"))
    }

    @Test
    fun `collapses newlines and long previews`() {
        val summary = summarizeToolAuditEntry(
            toolName = "bash",
            argumentsJson = "",
            preview = "bash line one\nline two\n" + "x".repeat(400),
        )

        assertFalse(summary.contains("\n"))
        assertEquals(ToolAuditSummaryLimit + 1, summary.length)
    }

    @Test
    fun `falls back to the tool name when the preview is empty`() {
        assertEquals(
            "aether_skill_manage",
            summarizeToolAuditEntry("aether_skill_manage", "", "   "),
        )
        assertEquals(
            "tool",
            summarizeToolAuditEntry("", "", ""),
        )
    }
}
