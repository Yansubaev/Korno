package com.ians.korno.feature.tracking.data.session

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isBetween
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.ians.korno.core.domain.error.DataError
import com.ians.korno.core.domain.error.EmptyResult
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RoutePoint
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GpsAccuracy
import com.ians.korno.core.domain.model.MeasurementSystem
import com.ians.korno.core.domain.model.PrivacyZone
import com.ians.korno.core.domain.model.UserPreferences
import com.ians.korno.core.domain.repository.TrackRepository
import com.ians.korno.core.domain.repository.UserPreferencesRepository
import com.ians.korno.core.logging.AppLogger
import com.ians.korno.core.logging.LogCategory
import com.ians.korno.core.logging.LogLevel
import com.ians.korno.core.logging.NoOpActivityLogScope
import com.ians.korno.core.trackprocessing.SegmentDetector
import com.ians.korno.core.trackprocessing.SegmentDetectorConfig
import com.ians.korno.core.trackprocessing.TrackProcessor
import com.ians.korno.data.locationtracking.diagnostics.TrackingSignal
import com.ians.korno.data.locationtracking.location.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TrackingSessionCoordinatorTest {
    private val repository = FakeTrackRepository()
    private val preferencesRepository = FakeUserPreferencesRepository()
    private var clockMillis = 1_000_000L
    private val coordinator = TrackingSessionCoordinator(
        repository,
        TrackProcessor(),
        preferencesRepository,
    ) { clockMillis }

    @Test
    fun `sample before session started is ignored`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.IDLE)
        assertThat(coordinator.session.value.distanceMeters).isEqualTo(0.0)
    }

    @Test
    fun `begin session creates activity and waits for a gps fix`() = runTest {
        val activityId = coordinator.beginSession(ActivityType.RUNNING)

        assertThat(activityId).isEqualTo(1L)
        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(coordinator.session.value.activityId).isEqualTo(1L)
        assertThat(repository.createdActivities.first().activityType).isEqualTo(ActivityType.RUNNING)
    }

    @Test
    fun `a rough sample does not count as a fix but a precise one starts the recording`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        // Первые сэмплы приходят от сети с погрешностью в сотни метров.
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 400f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(repository.insertedPoints).hasSize(0)

        clockMillis += 8_000L
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 8_000L, accuracyMeters = 8f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `the activity clock starts from the fix, not from the button`() = runTest {
        val pressedAt = clockMillis
        coordinator.beginSession(ActivityType.RUNNING)

        clockMillis += 12_000L
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        assertThat(coordinator.session.value.startTimeEpochMillis).isEqualTo(pressedAt + 12_000L)
        assertThat(repository.activityUpdates.single().startTimeEpochMillis).isEqualTo(pressedAt + 12_000L)
    }

    @Test
    fun `fix caught while warming up starts the recording right away`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        coordinator.beginSession(ActivityType.RUNNING)

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(repository.activityUpdates).hasSize(0)
    }

    @Test
    fun `a stale warm-up fix is not trusted`() = runTest {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        clockMillis += 60_000L

        coordinator.beginSession(ActivityType.RUNNING)

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
    }

    @Test
    fun `a cached fix neither starts the recording nor lands in the track`() = runTest {
        // Первой эмиссией Fused Location Provider почти всегда отдаёт последнюю известную
        // координату из кэша. По времени получения она неотличима от свежей, а относиться
        // может к тому месту, где телефон лежал час назад.
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.applySample(
            sample(
                latitude = 55.0,
                longitude = 37.0,
                timestamp = 0L,
                accuracyMeters = 5f,
                ageMillis = 120_000L,
            ),
        )

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
        assertThat(repository.insertedPoints).hasSize(0)
    }

    @Test
    fun `a network fix is not treated as a position measurement`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.applySample(
            sample(
                latitude = 55.0,
                longitude = 37.0,
                timestamp = 0L,
                accuracyMeters = 5f,
                provider = "network",
            ),
        )

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.WAITING_FOR_GPS)
    }

    @Test
    fun `after a long wait even a rough point starts the recording`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        clockMillis += 31_000L

        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 400f))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `stopping before the fix arrives leaves an activity of zero length`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        clockMillis += 20_000L

        coordinator.endSession()

        assertThat(repository.updatedActivities.single().elapsedTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `consecutive samples accumulate distance and moving time`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        // Живые значения отстают на нестабильный хвост: вердикт по точке может ещё
        // перевернуться — детектор остановок помечает стоянкой всё своё окно задним
        // числом, — и засчитывать такую точку в дистанцию рано. Хвост отмеряется
        // временем, а не числом точек, поэтому сэмплов здесь пять, а проверяется вклад
        // первых двух: только они старше окна детектора.
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0005, longitude = 0.0, timestamp = 10_000L))
        coordinator.applySample(sample(latitude = 0.0010, longitude = 0.0, timestamp = 20_000L))
        coordinator.applySample(sample(latitude = 0.0015, longitude = 0.0, timestamp = 30_000L))
        coordinator.applySample(sample(latitude = 0.0020, longitude = 0.0, timestamp = 40_000L))

        val session = coordinator.session.value
        // ~0.0005 degree of latitude is ~56 meters.
        assertThat(session.distanceMeters).isGreaterThan(50.0)
        assertThat(session.distanceMeters).isLessThan(60.0)
        assertThat(session.movingTimeMillis).isEqualTo(10_000L)
    }

    @Test
    fun `live distance never goes backwards between samples`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        var previousDistance = 0.0
        repeat(20) { index ->
            // Дрожание в пределах погрешности: именно на нём полный пересчёт трека
            // и разворачивал дистанцию назад.
            val jitter = if (index % 2 == 0) 0.000_02 else -0.000_02
            coordinator.applySample(
                sample(
                    latitude = index * 0.0002 + jitter,
                    longitude = 0.0,
                    timestamp = index * 5_000L,
                    accuracyMeters = 12f,
                ),
            )
            val distance = coordinator.session.value.distanceMeters
            assertThat(distance >= previousDistance).isEqualTo(true)
            previousDistance = distance
        }

        assertThat(previousDistance).isGreaterThan(0.0)
    }

    @Test
    fun `a long manual pause does not leak into moving time`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        repeat(4) { index ->
            coordinator.applySample(
                sample(latitude = index * 0.0001, longitude = 0.0, timestamp = index * 5_000L),
            )
        }

        coordinator.pause()
        coordinator.applySample(sample(latitude = 0.0003, longitude = 0.0, timestamp = 900_000L))
        coordinator.applySample(sample(latitude = 0.0003, longitude = 0.0, timestamp = 1_800_000L))
        coordinator.resume()
        repeat(4) { index ->
            coordinator.applySample(
                sample(
                    latitude = 0.0004 + index * 0.0001,
                    longitude = 0.0,
                    timestamp = 1_805_000L + index * 5_000L,
                ),
            )
        }

        // Полчаса паузы не должны попасть во время движения: обработчик обязан видеть
        // паузные точки, иначе возобновление выглядит для него разрывом записи.
        assertThat(coordinator.session.value.movingTimeMillis).isLessThan(60_000L)
    }

    @Test
    fun `samples while paused do not change distance or moving time`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.pause()

        coordinator.applySample(sample(latitude = 1.0, longitude = 1.0, timestamp = 60_000L))

        val session = coordinator.session.value
        assertThat(session.status).isEqualTo(RecordingStatus.MANUAL_PAUSE)
        assertThat(session.distanceMeters).isEqualTo(0.0)
        assertThat(session.movingTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `samples while manually paused are stored with pause status`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.pause()
        coordinator.applySample(sample(latitude = 1.0, longitude = 1.0, timestamp = 60_000L))

        coordinator.endSession()

        val pausedPoint = repository.replacedPoints.single {
            it.recordingStatus == RecordingStatus.MANUAL_PAUSE
        }
        assertThat(pausedPoint.latitude).isEqualTo(1.0)
        assertThat(pausedPoint.longitude).isEqualTo(1.0)
        assertThat(repository.updatedActivities.single().distanceMeters).isEqualTo(0.0)
        assertThat(repository.updatedActivities.single().movingTimeMillis).isEqualTo(0L)
    }

    @Test
    fun `ending while paused excludes the ongoing pause from elapsed time`() = runTest {
        beginWarmedSession()
        clockMillis += 10_000L
        coordinator.pause()
        clockMillis += 30_000L

        coordinator.endSession()

        assertThat(repository.updatedActivities.single().elapsedTimeMillis).isEqualTo(10_000L)
    }

    @Test
    fun `stationary samples switch session to auto pause and movement resumes it`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.AUTO_PAUSE)
        assertThat(coordinator.session.value.movingTimeMillis).isEqualTo(0L)

        coordinator.applySample(
            sample(
                latitude = 0.0001,
                longitude = 0.0,
                timestamp = 15_000L,
                speedMetersPerSecond = 1.4f,
            ),
        )

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)

        // Решение об автопаузе принимается мгновенно, а накопленное время движения
        // отстаёт на нестабильный хвост длиной в окно детектора остановок — отсюда
        // сэмплы до сорок пятой секунды: раньше точка возобновления не запечатывается.
        listOf(20_000L, 25_000L, 30_000L, 35_000L, 40_000L, 45_000L).forEachIndexed { index, timestamp ->
            coordinator.applySample(
                sample(
                    latitude = 0.0002 + index * 0.0001,
                    longitude = 0.0,
                    timestamp = timestamp,
                    speedMetersPerSecond = 1.4f,
                ),
            )
        }

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(coordinator.session.value.movingTimeMillis).isEqualTo(5_000L)
    }

    @Test
    fun `stationary samples remain recording when auto pause is disabled`() = runTest {
        preferencesRepository.preferences.value = preferencesRepository.preferences.value.copy(
            autoPauseEnabled = false,
        )
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `manual pause can override auto pause`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 5_000L))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 10_000L))

        coordinator.pause()

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.MANUAL_PAUSE)
    }

    @Test
    fun `resume clears pause state and recording continues accumulating`() = runTest {
        beginWarmedSession()
        coordinator.pause()
        coordinator.resume()

        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.RECORDING)
        assertThat(coordinator.session.value.currentPauseStartMillis).isNull()
    }

    @Test
    fun `buffer is flushed automatically once threshold is reached`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        repeat(10) { index ->
            coordinator.applySample(sample(latitude = index * 0.0001, longitude = 0.0, timestamp = index * 1_000L))
        }

        assertThat(repository.insertedPoints).hasSize(10)
    }

    @Test
    fun `buffer is flushed by time even below the threshold`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        assertThat(repository.insertedPoints).hasSize(0)

        clockMillis += 5_000L
        coordinator.applySample(sample(latitude = 0.0001, longitude = 0.0, timestamp = 5_000L))

        assertThat(repository.insertedPoints).hasSize(2)
    }

    @Test
    fun `end session finalizes activity and resets state`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L))
        coordinator.applySample(sample(latitude = 0.0005, longitude = 0.0, timestamp = 10_000L))

        val finishedActivityId = coordinator.endSession()

        assertThat(finishedActivityId).isEqualTo(1L)
        assertThat(repository.updatedActivities.first().endTimeEpochMillis).isNotNull()
        assertThat(repository.updatedActivities.first().distanceMeters).isGreaterThan(0.0)
        assertThat(coordinator.session.value.status).isEqualTo(RecordingStatus.STOPPED)
        assertThat(coordinator.session.value.activityId).isNull()
    }

    @Test
    fun `the activity keeps the sensitivity it was recorded with, even if the setting changes`() = runTest {
        preferencesRepository.preferences.value =
            preferencesRepository.preferences.value.copy(autoPauseSensitivity = AutoPauseSensitivity.HIGH)
        coordinator.beginSession(ActivityType.RUNNING)
        preferencesRepository.preferences.value =
            preferencesRepository.preferences.value.copy(autoPauseSensitivity = AutoPauseSensitivity.LOW)

        coordinator.endSession()

        assertThat(repository.createdActivities.single().autoPauseSensitivity).isEqualTo(AutoPauseSensitivity.HIGH)
        assertThat(repository.updatedActivities.single().autoPauseSensitivity).isEqualTo(AutoPauseSensitivity.HIGH)
    }

    @Test
    fun `a recovered session continues with the sensitivity of its activity`() = runTest {
        repository.activeActivity = Activity(
            id = 5L,
            startTimeEpochMillis = clockMillis - 600_000L,
            activityType = ActivityType.RUNNING,
            autoPauseSensitivity = AutoPauseSensitivity.LOW,
        )

        coordinator.recoverActiveSessionIfNeeded()
        coordinator.endSession()

        assertThat(repository.updatedActivities.single().autoPauseSensitivity).isEqualTo(AutoPauseSensitivity.LOW)
    }

    @Test
    fun `end session from app confirms activity right away`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.endSession()

        assertThat(repository.updatedActivities.first().isPendingSummary).isEqualTo(false)
    }

    @Test
    fun `end session from notification leaves activity waiting for summary screen`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING)

        coordinator.endSession(pendingSummary = true)

        assertThat(repository.updatedActivities.first().isPendingSummary).isEqualTo(true)
    }

    @Test
    fun `first step counter reading only sets the base, without counting steps`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        coordinator.applyStepCounter(1_000L)

        assertThat(coordinator.session.value.stepCount).isEqualTo(0)
    }

    @Test
    fun `steps accumulate while recording`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        coordinator.applyStepCounter(1_000L)
        coordinator.applyStepCounter(1_050L)
        coordinator.applyStepCounter(1_120L)

        assertThat(coordinator.session.value.stepCount).isEqualTo(120)
    }

    @Test
    fun `steps do not accumulate during manual pause`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        coordinator.applyStepCounter(1_000L)

        coordinator.pause()
        coordinator.applyStepCounter(1_050L)
        coordinator.resume()
        coordinator.applyStepCounter(1_060L)

        // Разница за время паузы (1000 -> 1050) отброшена; засчитан только шаг после
        // возобновления (1050 -> 1060).
        assertThat(coordinator.session.value.stepCount).isEqualTo(10)
    }

    @Test
    fun `a sensor reset does not produce a negative step count`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        coordinator.applyStepCounter(1_000L)
        coordinator.applyStepCounter(1_050L)

        // Перезагрузка устройства обнуляет TYPE_STEP_COUNTER.
        coordinator.applyStepCounter(5L)
        coordinator.applyStepCounter(20L)

        assertThat(coordinator.session.value.stepCount).isEqualTo(65)
    }

    @Test
    fun `activities that do not count steps ignore the sensor`() = runTest {
        coordinator.beginSession(ActivityType.DRIVING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))

        coordinator.applyStepCounter(1_000L)
        coordinator.applyStepCounter(1_050L)

        assertThat(coordinator.session.value.stepCount).isNull()
    }

    @Test
    fun `step readings are logged for every activity type, thinned out`() = runTest {
        val logged = mutableListOf<Map<String, String>>()
        val logger = object : AppLogger {
            override fun log(
                level: LogLevel,
                category: LogCategory,
                tag: String,
                throwable: Throwable?,
                fields: Map<String, String>,
                containsSensitiveLocation: Boolean,
                message: () -> String,
            ) {
                if (message() == "Step counter reading") logged += fields
            }
        }
        val loggingCoordinator = TrackingSessionCoordinator(
            repository,
            TrackProcessor(),
            preferencesRepository,
            logger,
            NoOpActivityLogScope,
        ) { clockMillis }
        loggingCoordinator.beginSession(ActivityType.CYCLING)

        loggingCoordinator.applyStepCounter(1_000L)
        clockMillis += 1_000L
        loggingCoordinator.applyStepCounter(1_002L)
        clockMillis += 5_000L
        loggingCoordinator.applyStepCounter(1_010L)

        // Контракт с `:tools:track-calibration` — см. LogTrackReaderTest.
        assertThat(logged.map { it["stepCounter"] }).isEqualTo(listOf("1000", "1010"))
        assertThat(logged.last()["readingTimestamp"]).isEqualTo(clockMillis.toString())
    }

    @Test
    fun `ending the session stores the accumulated step count on the activity`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        coordinator.applyStepCounter(1_000L)
        coordinator.applyStepCounter(1_042L)

        coordinator.endSession()

        assertThat(repository.updatedActivities.first().stepCount).isEqualTo(42)
    }

    @Test
    fun `begin session creates the activity with one open segment of its kind`() = runTest {
        coordinator.beginSession(ActivityType.HIKING)

        val segment = repository.createdSegments.single()
        assertThat(segment.kind).isEqualTo(SegmentKind.ON_FOOT)
        assertThat(segment.startTimeEpochMillis).isEqualTo(clockMillis)
        assertThat(segment.endTimeEpochMillis).isNull()
    }

    @Test
    fun `ending the session closes the segment over the whole recording with its steps and distance`() = runTest {
        coordinator.beginSession(ActivityType.WALKING)
        clockMillis += 5_000L
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        coordinator.applySample(sample(latitude = 0.0005, longitude = 0.0, timestamp = 10_000L, accuracyMeters = 5f))
        coordinator.applyStepCounter(1_000L)
        coordinator.applyStepCounter(1_042L)

        coordinator.endSession()

        val activity = repository.updatedActivities.single()
        val segment = repository.replacedSegments!!.single()
        assertThat(segment.kind).isEqualTo(SegmentKind.ON_FOOT)
        // Старт сегмента — момент фикса, а не нажатия «Начать»: как и у активности.
        assertThat(segment.startTimeEpochMillis).isEqualTo(activity.startTimeEpochMillis)
        assertThat(segment.endTimeEpochMillis).isEqualTo(activity.endTimeEpochMillis)
        assertThat(segment.stepCount).isEqualTo(42)
        assertThat(segment.distanceMeters).isEqualTo(activity.distanceMeters)
        assertThat(segment.distanceMeters).isGreaterThan(0.0)
    }

    @Test
    fun `auto mode starts with an unknown segment regardless of the chosen type`() = runTest {
        coordinator.beginSession(ActivityType.RUNNING, autoMode = true)

        val activity = repository.createdActivities.single()
        assertThat(activity.isAutoMode).isEqualTo(true)
        assertThat(activity.activityType).isEqualTo(ActivityType.OTHER)
        assertThat(repository.createdSegments.single().kind).isEqualTo(SegmentKind.UNKNOWN)
        assertThat(coordinator.session.value.segmentKind).isEqualTo(SegmentKind.UNKNOWN)
    }

    @Test
    fun `auto mode names the first segment, then cuts at the first sign and splits the steps`() = runTest {
        // Слияние коротких выключено: точек в тесте нет, и оба сегмента иначе были бы «короткими».
        val coordinator = autoCoordinator(SegmentDetectorConfig(minSegmentDurationMillis = 0L, minSegmentDistanceMeters = 0.0))
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        coordinator.beginSession(ActivityType.RUNNING, autoMode = true)
        val startedAt = clockMillis

        coordinator.applyActivityRecognition(recognized("ON_BICYCLE"))
        coordinator.applyStepCounter(1_000L)
        clockMillis += 40_000L
        coordinator.applyStepCounter(1_010L)
        // Первое подтверждение не режет запись: весь открытый сегмент оказывается велосипедом.
        assertThat(repository.savedOpenSegments.single()).isEqualTo(
            ActivitySegment(activityId = 1L, kind = SegmentKind.CYCLING, startTimeEpochMillis = startedAt) to null,
        )
        assertThat(coordinator.session.value.activityType).isEqualTo(ActivityType.CYCLING)

        clockMillis += 20_000L
        coordinator.applyStepCounter(1_030L)
        clockMillis += 10_000L
        val walkingSince = clockMillis
        coordinator.applyActivityRecognition(recognized("WALKING"))
        clockMillis += 10_000L
        coordinator.applyStepCounter(1_050L)
        clockMillis += 30_000L
        coordinator.applyStepCounter(1_090L)

        val (closed, opened) = repository.savedOpenSegments.last()
        assertThat(closed.kind).isEqualTo(SegmentKind.CYCLING)
        assertThat(closed.endTimeEpochMillis).isEqualTo(walkingSince)
        assertThat(closed.stepCount).isEqualTo(30)
        assertThat(opened?.kind).isEqualTo(SegmentKind.ON_FOOT)
        assertThat(opened?.startTimeEpochMillis).isEqualTo(walkingSince)
        assertThat(coordinator.session.value.segmentKind).isEqualTo(SegmentKind.ON_FOOT)

        // Пешком ещё минуту: одного вердикта на полминуты декодеру мало, чтобы сменить вид.
        coordinator.applyActivityRecognition(recognized("WALKING", confidence = 90))
        clockMillis += 30_000L
        coordinator.applyStepCounter(1_120L)
        coordinator.applyActivityRecognition(recognized("WALKING"))
        clockMillis += 30_000L
        coordinator.applyStepCounter(1_150L)
        coordinator.endSession()

        val segments = repository.replacedSegments!!
        assertThat(segments.map { it.kind }).isEqualTo(listOf(SegmentKind.CYCLING, SegmentKind.ON_FOOT))
        // Велосипедный вердикт старше минуты уже ничего не говорит: граница — где-то в этом зазоре.
        assertThat(segments.last().startTimeEpochMillis).isBetween(walkingSince - 10_000L, walkingSince)
        assertThat(segments.last().endTimeEpochMillis).isEqualTo(clockMillis)
        assertThat(segments.last().stepCount).isEqualTo(120)
        val activity = repository.updatedActivities.single()
        assertThat(activity.isAutoMode).isEqualTo(true)
        // В сводку — только шаги на ногах.
        assertThat(activity.stepCount).isEqualTo(120)
    }

    @Test
    fun `on save a vehicle verdict amid a ride without stops is decoded back into cycling`() = runTest {
        beginWarmedSession(autoMode = true)
        var latitude = 0.0
        suspend fun ride(seconds: Int, activity: String) {
            repeat(seconds / 4) { index ->
                if (index % 5 == 0) coordinator.applyActivityRecognition(recognized(activity, confidence = 90))
                coordinator.applySample(
                    sample(latitude, 0.0, timestamp = clockMillis, speedMetersPerSecond = 4.5f, accuracyMeters = 5f),
                )
                latitude += 4.5 * 4 / 111_000.0
                clockMillis += 4_000L
            }
        }

        ride(seconds = 300, activity = "ON_BICYCLE")
        ride(seconds = 120, activity = "IN_VEHICLE")
        ride(seconds = 300, activity = "ON_BICYCLE")
        // Живой детектор ошибку распознавания принял: сегмент «машины» записан.
        assertThat(repository.savedOpenSegments.mapNotNull { it.second?.kind }).contains(SegmentKind.VEHICLE)

        coordinator.endSession()

        // Пересесть в машину и обратно без остановки нельзя — при сохранении это велосипед.
        assertThat(repository.replacedSegments!!.map { it.kind }).isEqualTo(listOf(SegmentKind.CYCLING))
        assertThat(repository.recognitionSamples.size).isEqualTo(36)
    }

    @Test
    fun `short segments are merged on save and the type comes from the dominant kind`() = runTest {
        beginWarmedSession(autoMode = true)
        coordinator.applyActivityRecognition(recognized("ON_BICYCLE"))
        clockMillis += 40_000L
        coordinator.applyActivityRecognition(recognized("ON_BICYCLE", confidence = 90))
        clockMillis += 60_000L
        coordinator.applyActivityRecognition(recognized("WALKING"))
        clockMillis += 40_000L
        coordinator.applyActivityRecognition(recognized("WALKING", confidence = 90))
        assertThat(repository.savedOpenSegments.last().second?.kind).isEqualTo(SegmentKind.ON_FOOT)

        // Пешком — 40 секунд без единого метра: сегмент поглощается велосипедом.
        coordinator.endSession()

        assertThat(repository.replacedSegments!!.map { it.kind }).isEqualTo(listOf(SegmentKind.CYCLING))
        assertThat(repository.updatedActivities.single().activityType).isEqualTo(ActivityType.CYCLING)
    }

    private fun autoCoordinator(config: SegmentDetectorConfig) = TrackingSessionCoordinator(
        trackRepository = repository,
        trackProcessor = TrackProcessor(),
        preferencesRepository = preferencesRepository,
        nowMillis = { clockMillis },
        segmentDetector = SegmentDetector(config),
    )

    @Test
    fun `a manual kind names the segment right away and holds off the detector`() = runTest {
        beginWarmedSession(autoMode = true)

        coordinator.changeSegmentKind(SegmentKind.CYCLING)
        coordinator.applyActivityRecognition(recognized("WALKING"))
        clockMillis += 60_000L
        coordinator.applyActivityRecognition(recognized("WALKING", confidence = 90))

        val (named, next) = repository.savedOpenSegments.single()
        assertThat(named.kind).isEqualTo(SegmentKind.CYCLING)
        assertThat(named.isManuallyEdited).isEqualTo(true)
        assertThat(next).isNull()
        assertThat(coordinator.session.value.segmentKind).isEqualTo(SegmentKind.CYCLING)
    }

    @Test
    fun `a recovered auto session continues its open segment`() = runTest {
        repository.activeActivity = Activity(
            id = 5L,
            startTimeEpochMillis = clockMillis - 600_000L,
            activityType = ActivityType.OTHER,
            isAutoMode = true,
        )
        repository.storedSegments = listOf(
            ActivitySegment(5L, 5L, SegmentKind.CYCLING, clockMillis - 600_000L, clockMillis - 300_000L),
            ActivitySegment(6L, 5L, SegmentKind.ON_FOOT, clockMillis - 300_000L),
        )

        coordinator.recoverActiveSessionIfNeeded()
        assertThat(coordinator.session.value.isAutoMode).isEqualTo(true)
        assertThat(coordinator.session.value.segmentKind).isEqualTo(SegmentKind.ON_FOOT)

        coordinator.applyActivityRecognition(recognized("IN_VEHICLE"))
        clockMillis += 40_000L
        coordinator.applyActivityRecognition(recognized("IN_VEHICLE", confidence = 90))

        val (closed, opened) = repository.savedOpenSegments.single()
        assertThat(closed.kind).isEqualTo(SegmentKind.ON_FOOT)
        assertThat(opened?.kind).isEqualTo(SegmentKind.VEHICLE)
    }

    private fun recognized(activity: String, confidence: Int = 80) =
        TrackingSignal.ActivityRecognized(activity, confidence, emptyMap())

    /**
     * Прогрев перед стартом: пойманный сигнал позволяет начать запись сразу,
     * без ожидания на экране записи.
     */
    private suspend fun beginWarmedSession(
        activityType: ActivityType = ActivityType.RUNNING,
        autoMode: Boolean = false,
    ): Long? {
        coordinator.applySample(sample(latitude = 0.0, longitude = 0.0, timestamp = 0L, accuracyMeters = 5f))
        return coordinator.beginSession(activityType, autoMode)
    }

    private fun sample(
        latitude: Double,
        longitude: Double,
        timestamp: Long,
        speedMetersPerSecond: Float? = null,
        accuracyMeters: Float? = null,
        provider: String? = null,
        ageMillis: Long? = null,
    ) = LocationSample(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = null,
        timestampEpochMillis = timestamp,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        provider = provider,
        ageMillis = ageMillis,
    )

    private class FakeTrackRepository : TrackRepository {
        var nextActivityId = 1L
        val createdActivities = mutableListOf<Activity>()
        val createdSegments = mutableListOf<ActivitySegment>()
        var replacedSegments: List<ActivitySegment>? = null
        val savedOpenSegments = mutableListOf<Pair<ActivitySegment, ActivitySegment?>>()
        var activeActivity: Activity? = null
        var storedSegments: List<ActivitySegment> = emptyList()

        /** Правки уже созданной активности по ходу записи (сдвиг времени старта). */
        val activityUpdates = mutableListOf<Activity>()

        /** Финализированные записи: то, что легло в БД через replaceTrack. */
        val updatedActivities = mutableListOf<Activity>()
        val insertedPoints = mutableListOf<GeoPoint>()
        val replacedPoints = mutableListOf<GeoPoint>()

        override fun observeActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override fun observeActivity(activityId: Long): Flow<Result<Activity, DataError.Local>> =
            flowOf(Result.Error(DataError.Local.NOT_FOUND))

        override fun observeGeoPoints(activityId: Long): Flow<Result<List<GeoPoint>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun findActiveActivity(): Result<Activity?, DataError.Local> = Result.Success(activeActivity)

        override fun observePendingSummaryActivities(): Flow<Result<List<Activity>, DataError.Local>> =
            flowOf(Result.Success(emptyList()))

        override suspend fun getSegments(activityId: Long): Result<List<ActivitySegment>, DataError.Local> =
            Result.Success(storedSegments)

        override suspend fun saveOpenSegment(
            current: ActivitySegment,
            next: ActivitySegment?,
        ): EmptyResult<DataError.Local> {
            savedOpenSegments += current to next
            return Result.Success(Unit)
        }

        override suspend fun createActivity(
            activity: Activity,
            segments: List<ActivitySegment>,
        ): Result<Long, DataError.Local> {
            createdActivities += activity
            createdSegments += segments
            return Result.Success(nextActivityId)
        }

        override suspend fun updateActivity(activity: Activity): EmptyResult<DataError.Local> {
            activityUpdates += activity
            return Result.Success(Unit)
        }

        override suspend fun addGeoPoints(points: List<GeoPoint>): EmptyResult<DataError.Local> {
            insertedPoints += points
            return Result.Success(Unit)
        }

        val recognitionSamples = mutableListOf<RecognitionSample>()

        override suspend fun addRecognitionSample(sample: RecognitionSample): EmptyResult<DataError.Local> {
            recognitionSamples += sample
            return Result.Success(Unit)
        }

        override suspend fun getRecognitionSamples(activityId: Long): Result<List<RecognitionSample>, DataError.Local> =
            Result.Success(recognitionSamples.filter { it.activityId == activityId })

        override suspend fun getRoutePreview(
            activityId: Long,
            maxPoints: Int,
        ): Result<List<RoutePoint>, DataError.Local> = Result.Success(emptyList())

        override suspend fun insertTrack(
            activity: Activity,
            points: List<GeoPoint>,
            segments: List<ActivitySegment>,
        ): Result<Long, DataError.Local> = Result.Success(activity.id)

        override suspend fun replaceTrack(
            activity: Activity,
            points: List<GeoPoint>,
            segments: List<ActivitySegment>?,
        ): EmptyResult<DataError.Local> {
            updatedActivities += activity
            replacedSegments = segments
            replacedPoints.clear()
            replacedPoints += points
            return Result.Success(Unit)
        }

        override suspend fun deleteActivity(activityId: Long): EmptyResult<DataError.Local> =
            Result.Success(Unit)
    }

    private class FakeUserPreferencesRepository : UserPreferencesRepository {
        val preferences = MutableStateFlow(
            UserPreferences(
                measurementSystem = MeasurementSystem.METRIC,
                autoPauseEnabled = true,
                gpsAccuracy = GpsAccuracy.BALANCED,
                autoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
                dynamicColorEnabled = true,
                privacyZone = PrivacyZone.OFF,
                onboardingCompleted = true,
                lastActivityType = ActivityType.RUNNING,
            ),
        )

        override fun observePreferences(): Flow<UserPreferences> = preferences
        override suspend fun initialize(): EmptyResult<DataError.Local> = success()
        override suspend fun setMeasurementSystem(value: MeasurementSystem): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setGpsAccuracy(value: GpsAccuracy): EmptyResult<DataError.Local> = success()
        override suspend fun setAutoPauseSensitivity(value: AutoPauseSensitivity): EmptyResult<DataError.Local> = success()
        override suspend fun setPrivacyZone(value: PrivacyZone): EmptyResult<DataError.Local> = success()
        override suspend fun setDynamicColorEnabled(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setOnboardingCompleted(value: Boolean): EmptyResult<DataError.Local> = success()
        override suspend fun setLastActivityType(value: ActivityType): EmptyResult<DataError.Local> = success()

        override suspend fun setLastRecordingAutoMode(value: Boolean): EmptyResult<DataError.Local> = success()

        private fun success(): EmptyResult<DataError.Local> = Result.Success(Unit)
    }
}
