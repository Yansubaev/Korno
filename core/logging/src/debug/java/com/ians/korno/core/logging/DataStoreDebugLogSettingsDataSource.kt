package com.ians.korno.core.logging

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.debugLogSettingsDataStore by preferencesDataStore(
    name = "debug_log_preferences",
)

internal class DataStoreDebugLogSettingsDataSource(
    private val context: Context,
) {
    fun observeSettings(): Flow<LogSettings> = context.debugLogSettingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toSettings)

    suspend fun setEnabled(enabled: Boolean) = update(Keys.ENABLED, enabled)

    suspend fun setMinimumLevel(level: LogLevel) = update(Keys.MINIMUM_LEVEL, level.name)

    suspend fun setIncludeLocationSamples(enabled: Boolean) {
        context.debugLogSettingsDataStore.edit { preferences ->
            preferences[Keys.INCLUDE_LOCATION_SAMPLES] = enabled
            // Location samples are emitted at VERBOSE by design. Moving the level together
            // with this explicit privacy opt-in prevents an apparently enabled switch that
            // silently records no samples.
            if (enabled) preferences[Keys.MINIMUM_LEVEL] = LogLevel.VERBOSE.name
        }
    }

    suspend fun setIgnoreActivityRecognition(enabled: Boolean) =
        update(Keys.IGNORE_ACTIVITY_RECOGNITION, enabled)

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        context.debugLogSettingsDataStore.edit { preferences -> preferences[key] = value }
    }

    private fun toSettings(preferences: Preferences) = LogSettings(
        enabled = preferences[Keys.ENABLED] ?: true,
        minimumLevel = preferences[Keys.MINIMUM_LEVEL]
            ?.let { stored -> runCatching { LogLevel.valueOf(stored) }.getOrNull() }
            ?: LogLevel.DEBUG,
        includeLocationSamples = preferences[Keys.INCLUDE_LOCATION_SAMPLES] ?: false,
        ignoreActivityRecognition = preferences[Keys.IGNORE_ACTIVITY_RECOGNITION] ?: false,
    )

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val MINIMUM_LEVEL = stringPreferencesKey("minimum_level")
        val INCLUDE_LOCATION_SAMPLES = booleanPreferencesKey("include_location_samples")
        val IGNORE_ACTIVITY_RECOGNITION = booleanPreferencesKey("ignore_activity_recognition")
    }
}
