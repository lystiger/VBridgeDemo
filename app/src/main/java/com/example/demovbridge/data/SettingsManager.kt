package com.example.demovbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ParticipantConfig(
    val participantId: String,
    val displayName: String,
    val roomId: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

class SettingsManager(private val context: Context) {
    companion object {
        val PARTICIPANT_ID = stringPreferencesKey("participant_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ROOM_ID = stringPreferencesKey("room_id")
        val SOURCE_LANG = stringPreferencesKey("source_lang")
        val TARGET_LANG = stringPreferencesKey("target_lang")
    }

    val config: Flow<ParticipantConfig?> = context.dataStore.data.map { preferences ->
        val id = preferences[PARTICIPANT_ID] ?: UUID.randomUUID().toString().also { newId ->
            // Save the generated ID
            saveParticipantId(newId)
        }
        val name = preferences[DISPLAY_NAME]
        val room = preferences[ROOM_ID]
        val source = preferences[SOURCE_LANG]
        val target = preferences[TARGET_LANG]

        if (name != null && room != null && source != null && target != null) {
            ParticipantConfig(id, name, room, source, target)
        } else {
            null
        }
    }

    private suspend fun saveParticipantId(id: String) {
        context.dataStore.edit { it[PARTICIPANT_ID] = id }
    }

    suspend fun saveConfig(config: ParticipantConfig) {
        context.dataStore.edit { preferences ->
            preferences[PARTICIPANT_ID] = config.participantId
            preferences[DISPLAY_NAME] = config.displayName
            preferences[ROOM_ID] = config.roomId
            preferences[SOURCE_LANG] = config.sourceLanguage
            preferences[TARGET_LANG] = config.targetLanguage
        }
    }
}
