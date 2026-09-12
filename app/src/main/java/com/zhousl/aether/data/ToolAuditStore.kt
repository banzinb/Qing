package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.toolAuditDataStore by preferencesDataStore(name = "aether_tool_audit")

/**
 * One line of the approval trail: what Qing asked to do, and how the user
 * answered.
 *
 * [summary] is already redacted by [summarizeToolAuditEntry] before it reaches
 * here, so the log never keeps secrets at rest.
 */
data class ToolAuditEntry(
    val atMillis: Long,
    val toolName: String,
    val scopeKey: String,
    val summary: String,
    val decision: String,
)

/**
 * Keeps the last [MaxEntries] approval decisions on disk so the user can look
 * back at what Qing asked for and when.
 */
class ToolAuditStore(
    private val context: Context,
) {
    private val dataStore = context.toolAuditDataStore

    val entries: Flow<List<ToolAuditEntry>> = dataStore.data.map { preferences ->
        ToolAuditLog.parse(preferences[LOG_JSON].orEmpty())
    }

    suspend fun record(entry: ToolAuditEntry) {
        dataStore.edit { preferences ->
            val current = ToolAuditLog.parse(preferences[LOG_JSON].orEmpty())
            val next = (current + entry).takeLast(MaxEntries)
            preferences[LOG_JSON] = ToolAuditLog.serialize(next)
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(LOG_JSON) }
    }

    companion object {
        const val MaxEntries = 200
        private val LOG_JSON = stringPreferencesKey("tool_audit_json")
    }
}

internal object ToolAuditLog {
    fun parse(raw: String): List<ToolAuditEntry> {
        if (raw.isBlank()) return emptyList()
        val root = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<ToolAuditEntry>()
        for (index in 0 until root.length()) {
            val item = root.optJSONObject(index) ?: continue
            val at = item.optLong("at", 0L)
            val tool = item.optString("tool").trim()
            if (at <= 0L || tool.isBlank()) continue
            result += ToolAuditEntry(
                atMillis = at,
                toolName = tool,
                scopeKey = item.optString("scope_key"),
                summary = item.optString("summary"),
                decision = item.optString("decision"),
            )
        }
        return result
    }

    fun serialize(entries: List<ToolAuditEntry>): String {
        val root = JSONArray()
        entries.forEach { entry ->
            root.put(
                JSONObject()
                    .put("at", entry.atMillis)
                    .put("tool", entry.toolName)
                    .put("scope_key", entry.scopeKey)
                    .put("summary", entry.summary)
                    .put("decision", entry.decision),
            )
        }
        return root.toString()
    }
}

/** Stand-in for anything the log is not allowed to keep. */
internal const val ToolAuditMaskedToken = "•••"

internal const val ToolAuditSummaryLimit = 160

private val JsonSecretPattern = Regex(
    """"(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization|passcode|pin)"\s*:\s*"[^"]*"""",
    RegexOption.IGNORE_CASE,
)

private val InlineSecretPattern = Regex(
    """(?i)\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization|passcode|pin)\b(\s*[:=]\s*)\S+""",
)

private val WhitespaceRun = Regex("""\s+""")

/**
 * Builds the one-line record of a parked tool call.
 *
 * The kernel already renders a readable preview, so this only has to do two
 * things: strip anything that looks like a credential, and never keep clipboard
 * contents. The result stays language-neutral because it is written to disk
 * once and read back under whatever locale is active later.
 */
internal fun summarizeToolAuditEntry(
    toolName: String,
    argumentsJson: String,
    preview: String,
): String {
    val tool = toolName.trim().ifBlank { "tool" }
    val action = toolAuditAction(argumentsJson)
    if (action.startsWith("clipboard")) {
        return "$tool $action $ToolAuditMaskedToken"
    }
    val base = preview.trim().ifBlank { tool }
    val redacted = base
        .replace(JsonSecretPattern) { match -> """"${match.groupValues[1]}":"$ToolAuditMaskedToken"""" }
        .replace(InlineSecretPattern) { match -> "${match.groupValues[1]}=$ToolAuditMaskedToken" }
        .replace(WhitespaceRun, " ")
    if (redacted.length <= ToolAuditSummaryLimit) return redacted
    return redacted.take(ToolAuditSummaryLimit) + "…"
}

private fun toolAuditAction(argumentsJson: String): String {
    if (argumentsJson.isBlank()) return ""
    val parsed = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""
    return parsed.optString("action").trim().lowercase()
}
