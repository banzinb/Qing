package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.presencePushDataStore by preferencesDataStore(name = "aether_presence_push")

class PresencePushRepository(
    private val context: Context,
) {
    val settings: Flow<PresencePushSettings> = context.presencePushDataStore.data.map { preferences ->
        PresencePushSettings(
            enabled = preferences[ENABLED] ?: false,
            intervalMinutes = preferences[INTERVAL_MINUTES] ?: 120,
            activeStartMinuteOfDay = preferences[ACTIVE_START] ?: 8 * 60,
            activeEndMinuteOfDay = preferences[ACTIVE_END] ?: 22 * 60,
            quietStartMinuteOfDay = preferences[QUIET_START] ?: 23 * 60,
            quietEndMinuteOfDay = preferences[QUIET_END] ?: 7 * 60,
            jitterEnabled = preferences[JITTER_ENABLED] ?: true,
            lastTriggerAtMillis = preferences[LAST_TRIGGER_AT],
        )
    }

    suspend fun snapshot(): PresencePushSettings = settings.first()

    suspend fun save(settings: PresencePushSettings) {
        context.presencePushDataStore.edit { preferences ->
            preferences.putAll(settings)
        }
    }

    suspend fun update(transform: (PresencePushSettings) -> PresencePushSettings): PresencePushSettings {
        var updated = snapshot()
        context.presencePushDataStore.edit { preferences ->
            updated = transform(
                PresencePushSettings(
                    enabled = preferences[ENABLED] ?: false,
                    intervalMinutes = preferences[INTERVAL_MINUTES] ?: 120,
                    activeStartMinuteOfDay = preferences[ACTIVE_START] ?: 8 * 60,
                    activeEndMinuteOfDay = preferences[ACTIVE_END] ?: 22 * 60,
                    quietStartMinuteOfDay = preferences[QUIET_START] ?: 23 * 60,
                    quietEndMinuteOfDay = preferences[QUIET_END] ?: 7 * 60,
                    jitterEnabled = preferences[JITTER_ENABLED] ?: true,
                    lastTriggerAtMillis = preferences[LAST_TRIGGER_AT],
                )
            )
            preferences.putAll(updated)
        }
        return updated
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putAll(settings: PresencePushSettings) {
        this[ENABLED] = settings.enabled
        this[INTERVAL_MINUTES] = settings.intervalMinutes
        this[ACTIVE_START] = settings.activeStartMinuteOfDay
        this[ACTIVE_END] = settings.activeEndMinuteOfDay
        this[QUIET_START] = settings.quietStartMinuteOfDay
        this[QUIET_END] = settings.quietEndMinuteOfDay
        this[JITTER_ENABLED] = settings.jitterEnabled
        settings.lastTriggerAtMillis?.let { this[LAST_TRIGGER_AT] = it }
            ?: remove(LAST_TRIGGER_AT)
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val INTERVAL_MINUTES = intPreferencesKey("interval_minutes")
        val ACTIVE_START = intPreferencesKey("active_start_minute_of_day")
        val ACTIVE_END = intPreferencesKey("active_end_minute_of_day")
        val QUIET_START = intPreferencesKey("quiet_start_minute_of_day")
        val QUIET_END = intPreferencesKey("quiet_end_minute_of_day")
        val JITTER_ENABLED = booleanPreferencesKey("jitter_enabled")
        val LAST_TRIGGER_AT = longPreferencesKey("last_trigger_at_millis")
    }
}
