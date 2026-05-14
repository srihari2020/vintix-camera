package com.srihari.vintix.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.vintixSettingsDataStore by preferencesDataStore(name = "vintix_settings")

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.vintixSettingsDataStore

    val settings: Flow<VintixSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw exception
        }
        .map { preferences -> preferences.toVintixSettings() }

    suspend fun setTimestampEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TIMESTAMP_ENABLED] = enabled }
    }

    suspend fun setLightLeaksEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LIGHT_LEAKS_ENABLED] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setExportResolution(preset: ExportResolutionPreset) {
        dataStore.edit { it[Keys.EXPORT_RESOLUTION] = preset.name }
    }

    suspend fun setExportQuality(preset: ExportQualityPreset) {
        dataStore.edit { it[Keys.EXPORT_QUALITY] = preset.name }
    }

    private fun Preferences.toVintixSettings(): VintixSettings = VintixSettings(
        timestampEnabled = this[Keys.TIMESTAMP_ENABLED] ?: true,
        lightLeaksEnabled = this[Keys.LIGHT_LEAKS_ENABLED] ?: true,
        hapticsEnabled = this[Keys.HAPTICS_ENABLED] ?: true,
        soundEnabled = this[Keys.SOUND_ENABLED] ?: true,
        exportResolution = enumPreference(
            rawValue = this[Keys.EXPORT_RESOLUTION],
            fallback = ExportResolutionPreset.PROFILE
        ),
        exportQuality = enumPreference(
            rawValue = this[Keys.EXPORT_QUALITY],
            fallback = ExportQualityPreset.PROFILE
        )
    )

    private inline fun <reified T : Enum<T>> enumPreference(rawValue: String?, fallback: T): T =
        rawValue?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private object Keys {
        val TIMESTAMP_ENABLED = booleanPreferencesKey("timestamp_enabled")
        val LIGHT_LEAKS_ENABLED = booleanPreferencesKey("light_leaks_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val EXPORT_RESOLUTION = stringPreferencesKey("export_resolution")
        val EXPORT_QUALITY = stringPreferencesKey("export_quality")
    }
}
