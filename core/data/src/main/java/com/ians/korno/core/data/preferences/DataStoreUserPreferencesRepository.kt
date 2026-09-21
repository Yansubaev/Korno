package com.ians.korno.core.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ians.korno.core.data.local.safeLocalCall
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.userPreferencesDataStore by preferencesDataStore(name = "user_preferences")

class DataStoreUserPreferencesRepository(
    private val context: Context,
) : UserPreferencesRepository {

    override fun observePreferences(): Flow<UserPreferences> =
        context.userPreferencesDataStore.data
            .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
            .map(::toDomain)

    override suspend fun initialize(): EmptyResult<DataError.Local> = safeLocalCall {
        context.userPreferencesDataStore.edit { preferences ->
            if (preferences[Keys.INITIALIZED] != true) {
                preferences[Keys.MEASUREMENT_SYSTEM] = defaultMeasurementSystem().name
                preferences[Keys.AUTO_PAUSE_ENABLED] = true
                preferences[Keys.GPS_ACCURACY] = GpsAccuracy.BALANCED.name
                preferences[Keys.AUTO_PAUSE_SENSITIVITY] = AutoPauseSensitivity.MEDIUM.name
                preferences[Keys.DYNAMIC_COLOR_ENABLED] = true
                preferences[Keys.PRIVACY_ZONE] = PrivacyZone.OFF.name
                preferences[Keys.ONBOARDING_COMPLETED] = false
                preferences[Keys.LAST_SEGMENT_KIND] = SegmentKind.RUNNING.name
                preferences[Keys.INITIALIZED] = true
            }
        }
        Unit
    }

    override suspend fun setMeasurementSystem(value: MeasurementSystem) =
        update(Keys.MEASUREMENT_SYSTEM, value.name)

    override suspend fun setAutoPauseEnabled(value: Boolean) =
        update(Keys.AUTO_PAUSE_ENABLED, value)

    override suspend fun setGpsAccuracy(value: GpsAccuracy) =
        update(Keys.GPS_ACCURACY, value.name)

    override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity) =
        update(Keys.AUTO_PAUSE_SENSITIVITY, value.name)

    override suspend fun setDynamicColorEnabled(value: Boolean) =
        update(Keys.DYNAMIC_COLOR_ENABLED, value)

    override suspend fun setPrivacyZone(value: PrivacyZone) =
        update(Keys.PRIVACY_ZONE, value.name)

    override suspend fun setOnboardingCompleted(value: Boolean) =
        update(Keys.ONBOARDING_COMPLETED, value)

    override suspend fun setLastSegmentKind(value: SegmentKind) =
        update(Keys.LAST_SEGMENT_KIND, value.name)

    override suspend fun setLastRecordingAutoMode(value: Boolean) =
        update(Keys.LAST_RECORDING_AUTO_MODE, value)

    private suspend fun <T> update(
        key: Preferences.Key<T>,
        value: T,
    ): EmptyResult<DataError.Local> = safeLocalCall {
        context.userPreferencesDataStore.edit { it[key] = value }
        Unit
    }

    private fun toDomain(preferences: Preferences): UserPreferences = UserPreferences(
        measurementSystem = preferences[Keys.MEASUREMENT_SYSTEM]
            ?.let { runCatching { MeasurementSystem.valueOf(it) }.getOrNull() }
            ?: defaultMeasurementSystem(),
        autoPauseEnabled = preferences[Keys.AUTO_PAUSE_ENABLED] ?: true,
        gpsAccuracy = preferences[Keys.GPS_ACCURACY]
            ?.let { runCatching { GpsAccuracy.valueOf(it) }.getOrNull() }
            ?: GpsAccuracy.BALANCED,
        autoPauseSensitivity = preferences[Keys.AUTO_PAUSE_SENSITIVITY]
            ?.let { runCatching { AutoPauseSensitivity.valueOf(it) }.getOrNull() }
            ?: AutoPauseSensitivity.MEDIUM,
        // true и здесь тоже: у пользователей, поставивших приложение до появления настройки,
        // ключа в DataStore нет, и они должны получить то же поведение, что и новые.
        dynamicColorEnabled = preferences[Keys.DYNAMIC_COLOR_ENABLED] ?: true,
        // OFF по умолчанию: обрезка молча меняла бы содержимое файлов, а о таком
        // пользователь должен узнать, включив её сам.
        privacyZone = preferences[Keys.PRIVACY_ZONE]
            ?.let { runCatching { PrivacyZone.valueOf(it) }.getOrNull() }
            ?: PrivacyZone.OFF,
        onboardingCompleted = preferences[Keys.ONBOARDING_COMPLETED] ?: false,
        lastSegmentKind = preferences[Keys.LAST_SEGMENT_KIND]
            ?.let { runCatching { SegmentKind.valueOf(it) }.getOrNull() }
            ?: SegmentKind.RUNNING,
        lastRecordingAutoMode = preferences[Keys.LAST_RECORDING_AUTO_MODE] ?: true,
    )

    private fun defaultMeasurementSystem(): MeasurementSystem {
        val country = context.resources.configuration.locales.get(0)?.country
        return defaultMeasurementSystemForCountry(country)
    }

    private object Keys {
        val INITIALIZED = booleanPreferencesKey("initialized")
        val MEASUREMENT_SYSTEM = stringPreferencesKey("measurement_system")
        val AUTO_PAUSE_ENABLED = booleanPreferencesKey("auto_pause_enabled")
        val GPS_ACCURACY = stringPreferencesKey("gps_accuracy")
        val AUTO_PAUSE_SENSITIVITY = stringPreferencesKey("auto_pause_sensitivity")
        val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        val PRIVACY_ZONE = stringPreferencesKey("privacy_zone")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val LAST_SEGMENT_KIND = stringPreferencesKey("last_segment_kind")
        val LAST_RECORDING_AUTO_MODE = booleanPreferencesKey("last_recording_auto_mode")
    }

}

private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

internal fun defaultMeasurementSystemForCountry(country: String?): MeasurementSystem =
    if (country?.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) {
        MeasurementSystem.IMPERIAL
    } else {
        MeasurementSystem.METRIC
    }
