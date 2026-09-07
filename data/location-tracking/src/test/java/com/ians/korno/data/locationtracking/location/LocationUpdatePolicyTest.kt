package com.ians.korno.data.locationtracking.location

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LocationUpdatePolicyTest {

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `recording keeps the interval chosen in settings`(accuracy: GpsAccuracy) {
        val config = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING)

        assertThat(config.intervalMillis).isEqualTo(
            when (accuracy) {
                GpsAccuracy.HIGH -> 2_000L
                GpsAccuracy.BALANCED -> 4_000L
                GpsAccuracy.BATTERY_SAVER -> 10_000L
            },
        )
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `recording never drops the priority below high accuracy`(accuracy: GpsAccuracy) {
        // Настройка точности управляет частотой опроса, но не тем, включён ли GNSS-чип.
        // На BALANCED и LOW_POWER система вправе обслуживать запрос сетевыми фиксами:
        // стометровая погрешность, отсутствие скорости и провалы опроса на минуты.
        listOf(RecordingStatus.RECORDING, RecordingStatus.WAITING_FOR_GPS, RecordingStatus.AUTO_PAUSE)
            .forEach { status ->
                assertThat(LocationUpdatePolicy.configFor(accuracy, status).accuracy)
                    .isEqualTo(GpsAccuracy.HIGH)
            }
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `preparing before the start uses the recording interval`(accuracy: GpsAccuracy) {
        assertThat(LocationUpdatePolicy.configFor(accuracy, RecordingStatus.IDLE))
            .isEqualTo(LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING))
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `auto pause polls less often but stays responsive`(accuracy: GpsAccuracy) {
        val recording = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING)
        val paused = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.AUTO_PAUSE)

        assertThat(paused.intervalMillis).isGreaterThan(recording.intervalMillis)
        // Возобновление движения должно замечаться в пределах окна детектора остановок.
        assertThat(paused.intervalMillis).isLessThanOrEqualTo(15_000L)
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `auto pause interval is pinned per accuracy`(accuracy: GpsAccuracy) {
        val paused = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.AUTO_PAUSE)

        // Значения важны сами по себе: при шаге около stopWindowMillis (10–25 с в
        // зависимости от чувствительности) окно детектора схлопывается до одной точки.
        // BATTERY_SAVER в эту зону попадает и держится на запасном пути
        // StopDetectionUseCase — остальные режимы набирают в окне несколько точек.
        assertThat(paused.intervalMillis).isEqualTo(
            when (accuracy) {
                GpsAccuracy.HIGH -> 4_000L
                GpsAccuracy.BALANCED -> 8_000L
                GpsAccuracy.BATTERY_SAVER -> 15_000L
            },
        )
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `manual pause drops to the cheapest mode regardless of the setting`(accuracy: GpsAccuracy) {
        val paused = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.MANUAL_PAUSE)

        assertThat(paused.accuracy).isEqualTo(GpsAccuracy.BATTERY_SAVER)
        assertThat(paused.intervalMillis).isEqualTo(60_000L)
    }

    @Test
    fun `the minimum interval never exceeds the requested one`() {
        GpsAccuracy.entries.forEach { accuracy ->
            RecordingStatus.entries.forEach { status ->
                val config = LocationUpdatePolicy.configFor(accuracy, status)
                assertThat(config.minIntervalMillis).isLessThanOrEqualTo(config.intervalMillis)
            }
        }
    }
}
