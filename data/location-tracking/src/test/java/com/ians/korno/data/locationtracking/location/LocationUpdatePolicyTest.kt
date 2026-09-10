package com.ians.korno.data.locationtracking.location

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.RecordingStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class LocationUpdatePolicyTest {

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `recording keeps the interval chosen in settings`(accuracy: GpsAccuracy) {
        val config = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.WALKING)

        assertThat(config.intervalMillis).isEqualTo(
            when (accuracy) {
                GpsAccuracy.HIGH -> 1_000L
                GpsAccuracy.BALANCED -> 2_500L
                GpsAccuracy.BATTERY_SAVER -> 4_000L
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
                assertThat(LocationUpdatePolicy.configFor(accuracy, status, ActivityType.RUNNING).accuracy)
                    .isEqualTo(GpsAccuracy.HIGH)
            }
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `preparing before the start uses the recording interval`(accuracy: GpsAccuracy) {
        assertThat(LocationUpdatePolicy.configFor(accuracy, RecordingStatus.IDLE, ActivityType.CYCLING))
            .isEqualTo(LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.CYCLING))
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `walking is polled more often than driving`(accuracy: GpsAccuracy) {
        // Пешеход за десять секунд проходит меньше, чем городская погрешность фикса;
        // машина за то же время проезжает полтораста метров при вдвое более широком
        // пороге отбраковки.
        val onFoot = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.WALKING)
        val wheeled = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.CYCLING)
        val vehicle = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.DRIVING)

        assertThat(wheeled.intervalMillis).isGreaterThan(onFoot.intervalMillis)
        assertThat(vehicle.intervalMillis).isGreaterThan(wheeled.intervalMillis)
    }

    @ParameterizedTest
    @EnumSource(ActivityType::class)
    fun `battery saver stays usable for the activity it records`(activityType: ActivityType) {
        // Порог отбраковки по погрешности для ходьбы — 40 м, и при опросе раз в пять
        // секунд туда не проходила каждая третья точка. Шаг опроса обязан оставаться
        // таким, чтобы окно детектора остановок набирало несколько точек.
        val config = LocationUpdatePolicy.configFor(
            GpsAccuracy.BATTERY_SAVER,
            RecordingStatus.RECORDING,
            activityType,
        )

        assertThat(config.intervalMillis).isLessThanOrEqualTo(10_000L)
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `a short auto pause does not change the polling rate at all`(accuracy: GpsAccuracy) {
        // Светофор, переход, шнурки — это десятки секунд. Разредить опрос на них значит
        // решать выход из паузы по загрублённому сигналу и не сэкономить ничего.
        val recording = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.WALKING)
        val fresh = LocationUpdatePolicy.configFor(
            accuracy,
            RecordingStatus.AUTO_PAUSE,
            ActivityType.WALKING,
            autoPauseSettled = false,
        )

        assertThat(fresh).isEqualTo(recording)
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `a settled auto pause polls less often but stays responsive`(accuracy: GpsAccuracy) {
        val recording = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.RECORDING, ActivityType.WALKING)
        val settled = LocationUpdatePolicy.configFor(
            accuracy,
            RecordingStatus.AUTO_PAUSE,
            ActivityType.WALKING,
            autoPauseSettled = true,
        )

        assertThat(settled.intervalMillis).isGreaterThan(recording.intervalMillis)
        // Возобновление движения должно замечаться в пределах окна детектора остановок.
        assertThat(settled.intervalMillis).isLessThanOrEqualTo(15_000L)
    }

    @ParameterizedTest
    @EnumSource(GpsAccuracy::class)
    fun `manual pause drops to the cheapest mode regardless of the setting`(accuracy: GpsAccuracy) {
        val paused = LocationUpdatePolicy.configFor(accuracy, RecordingStatus.MANUAL_PAUSE, ActivityType.WALKING)

        assertThat(paused.accuracy).isEqualTo(GpsAccuracy.BATTERY_SAVER)
        assertThat(paused.intervalMillis).isEqualTo(60_000L)
    }

    @Test
    fun `the settle delay covers an ordinary street stop`() {
        assertThat(LocationUpdatePolicy.AUTO_PAUSE_SETTLE_MILLIS).isEqualTo(60_000L)
    }
}
