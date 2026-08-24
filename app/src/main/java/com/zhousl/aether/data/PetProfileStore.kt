package com.zhousl.aether.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.petDataStore by preferencesDataStore(name = "aether_pet")

data class PetProfile(
    val level: Int = 1,
    val exp: Int = 0,
    val petCount: Long = 0,
    val lastPettedAtMillis: Long = 0L,
) {
    val expForCurrentLevel: Int get() = petExpNeededForLevel(level)

    val expProgress: Float
        get() = if (expForCurrentLevel > 0) {
            (exp.toFloat() / expForCurrentLevel).coerceIn(0f, 1f)
        } else {
            0f
        }

    val expToNextLevel: Int
        get() = (expForCurrentLevel - exp).coerceAtLeast(0)
}

private const val PET_EXP_GAIN = 10
private const val PET_BASE_EXP = 100
private const val PET_EXP_GROWTH = 50

internal fun petExpNeededForLevel(level: Int): Int = PET_BASE_EXP + (level - 1) * PET_EXP_GROWTH

class PetProfileStore(
    private val context: Context,
) {
    private val dataStore = context.petDataStore

    val profile: Flow<PetProfile> = dataStore.data.map { preferences ->
        PetProfile(
            level = preferences[LEVEL] ?: 1,
            exp = preferences[EXP] ?: 0,
            petCount = preferences[PET_COUNT] ?: 0L,
            lastPettedAtMillis = preferences[LAST_PETTED_AT] ?: 0L,
        )
    }

    suspend fun recordPet() {
        dataStore.edit { preferences ->
            val level = preferences[LEVEL] ?: 1
            val exp = (preferences[EXP] ?: 0) + PET_EXP_GAIN
            val required = petExpNeededForLevel(level)
            val nextLevel = if (exp >= required) level + 1 else level
            val nextExp = if (exp >= required) exp - required else exp
            preferences[LEVEL] = nextLevel
            preferences[EXP] = nextExp
            preferences[PET_COUNT] = (preferences[PET_COUNT] ?: 0L) + 1
            preferences[LAST_PETTED_AT] = System.currentTimeMillis()
        }
    }

    companion object {
        private val LEVEL = intPreferencesKey("pet_level")
        private val EXP = intPreferencesKey("pet_exp")
        private val PET_COUNT = longPreferencesKey("pet_count")
        private val LAST_PETTED_AT = longPreferencesKey("pet_last_petted_at")
    }
}
