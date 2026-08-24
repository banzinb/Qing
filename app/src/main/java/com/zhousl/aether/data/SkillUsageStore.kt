package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.skillUsageDataStore by preferencesDataStore(name = "aether_skill_usage")

data class SkillUsageEntry(
    val calls: Int = 0,
    val lastUsedAtMillis: Long = 0L,
)

/**
 * Tracks how many times each skill was actually used by the agent, so the
 * "My Data" panel can show real skill-call numbers.
 */
class SkillUsageStore(
    private val context: Context,
) {
    private val dataStore = context.skillUsageDataStore

    val usage: Flow<Map<String, SkillUsageEntry>> = dataStore.data.map { preferences ->
        parse(preferences[USAGE_JSON].orEmpty())
    }

    suspend fun recordUsage(skillIds: List<String>) {
        if (skillIds.isEmpty()) return
        dataStore.edit { preferences ->
            val current = parse(preferences[USAGE_JSON].orEmpty())
            val now = System.currentTimeMillis()
            skillIds.distinct().forEach { id ->
                val entry = current[id] ?: SkillUsageEntry()
                current[id] = SkillUsageEntry(
                    calls = entry.calls + 1,
                    lastUsedAtMillis = now,
                )
            }
            preferences[USAGE_JSON] = serialize(current)
        }
    }

    private fun parse(raw: String): MutableMap<String, SkillUsageEntry> {
        val result = mutableMapOf<String, SkillUsageEntry>()
        if (raw.isBlank()) return result
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { key ->
                val entry = root.optJSONObject(key) ?: return@forEach
                result[key] = SkillUsageEntry(
                    calls = entry.optInt("calls", 0),
                    lastUsedAtMillis = entry.optLong("last_used_at", 0L),
                )
            }
        }
        return result
    }

    private fun serialize(usage: Map<String, SkillUsageEntry>): String {
        val root = JSONObject()
        usage.forEach { (id, entry) ->
            root.put(
                id,
                JSONObject()
                    .put("calls", entry.calls)
                    .put("last_used_at", entry.lastUsedAtMillis),
            )
        }
        return root.toString()
    }

    companion object {
        private val USAGE_JSON = stringPreferencesKey("skill_usage_json")
    }
}
