package com.ians.korno.tools.trackcalibration

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.domain.model.RecognizedActivity
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Test

/**
 * Строки здесь скопированы по форме с того, что пишет
 * `TrackingSessionCoordinator`: имена сообщений и полей — это контракт между приложением
 * и калибровкой, и переименование на той стороне обязано ломать именно этот тест, а не
 * молча оставлять инструмент без данных.
 */
class LogTrackReaderTest {

    @Test
    fun `a sample and its coordinates become one point`() {
        val activity = readSingleActivity(
            sessionStarted(),
            sampleReceived(timestamp = 1_000L, speed = "1.4000", accuracy = "12.500"),
            sampleCoordinates(timestamp = 1_000L, latitude = 55.751244, longitude = 37.618423),
        )

        assertThat(activity.points.size).isEqualTo(1)
        val point = activity.points.single()
        assertThat(point.timestampEpochMillis).isEqualTo(1_000L)
        assertThat(point.latitude).isCloseTo(55.751244, 1e-9)
        assertThat(point.longitude).isCloseTo(37.618423, 1e-9)
        assertThat(point.altitudeMeters!!).isCloseTo(142.0, 1e-9)
        assertThat(point.accuracyMeters!!.toDouble()).isCloseTo(12.5, 1e-6)
        assertThat(point.speedMetersPerSecond!!.toDouble()).isCloseTo(1.4, 1e-6)
        assertThat(point.speedAccuracyMetersPerSecond!!.toDouble()).isCloseTo(0.3, 1e-6)
        assertThat(point.bearingDegrees!!.toDouble()).isCloseTo(12.0, 1e-6)
        assertThat(point.bearingAccuracyDegrees!!.toDouble()).isCloseTo(9.0, 1e-6)
        assertThat(point.recordingStatus).isEqualTo(RecordingStatus.RECORDING)
    }

    @Test
    fun `session settings are taken from the start record`() {
        val activity = readSingleActivity(
            sessionStarted(),
            sampleReceived(timestamp = 1_000L),
            sampleCoordinates(timestamp = 1_000L),
        )

        assertThat(activity.activityType).isEqualTo(ActivityType.WALKING)
        assertThat(activity.sensitivity).isEqualTo(AutoPauseSensitivity.MEDIUM)
    }

    @Test
    fun `a sample without coordinates is counted rather than guessed`() {
        // Обычный лог: запись GPS-сэмплов выключена, координат нет. Инструмент обязан
        // отличить это от повреждённого файла, чтобы подсказать, что именно включить.
        val activity = readSingleActivity(
            sessionStarted(),
            sampleReceived(timestamp = 1_000L),
            sampleReceived(timestamp = 2_000L),
        )

        assertThat(activity.points.isEmpty()).isTrue()
        assertThat(activity.samplesWithoutCoordinates).isEqualTo(2)
        assertThat(activity.sawCoordinateRecords).isFalse()
        assertThat(activity.missingSampleTimestamp).isFalse()
    }

    @Test
    fun `coordinates are not glued to a sample they do not belong to`() {
        // Одна из двух записей потерялась — обрезан лог, менялись настройки по ходу.
        // Ключ по времени фикса не даёт приклеить координаты к чужому сэмплу.
        val activity = readSingleActivity(
            sessionStarted(),
            sampleReceived(timestamp = 1_000L),
            sampleCoordinates(timestamp = 9_000L),
        )

        assertThat(activity.points.isEmpty()).isTrue()
        assertThat(activity.sawCoordinateRecords).isTrue()
    }

    @Test
    fun `a log without the fix time is reported as too old to replay`() {
        val activity = readSingleActivity(
            sessionStarted(),
            """{"message":"Location sample received","activityId":"7","fields":""" +
                """{"reportedSpeed":"1.4","accuracyMeters":"12.5","status":"RECORDING"}}""",
        )

        assertThat(activity.missingSampleTimestamp).isTrue()
        assertThat(activity.points.isEmpty()).isTrue()
    }

    @Test
    fun `verdicts recorded on the phone are counted for comparison`() {
        val activity = readSingleActivity(
            sessionStarted(),
            pointProcessed(isOutlier = true, isStop = false),
            pointProcessed(isOutlier = false, isStop = true),
            pointProcessed(isOutlier = false, isStop = true),
            pointProcessed(isOutlier = false, isStop = false),
            pointProcessed(isOutlier = false, isStop = true),
        )

        assertThat(activity.recordedOutliers).isEqualTo(1)
        // Две серии подряд идущих стоянок, а не три помеченные точки.
        assertThat(activity.recordedStopRuns).isEqualTo(2)
    }

    @Test
    fun `records without an activity are ignored`() {
        val activities = readActivities(
            """{"message":"Screen state changed","fields":{"screenInteractive":"true"}}""",
            sessionStarted(),
        )

        assertThat(activities.size).isEqualTo(1)
        assertThat(activities.single().activityId).isEqualTo(7L)
    }

    @Test
    fun `an unparseable line does not abort the whole log`() {
        val activity = readSingleActivity(
            sessionStarted(),
            "не json вовсе",
            sampleReceived(timestamp = 1_000L),
            sampleCoordinates(timestamp = 1_000L),
        )

        assertThat(activity.points.size).isEqualTo(1)
    }

    @Test
    fun `a missing activity type is left unset rather than defaulted`() {
        val activity = readSingleActivity(
            sampleReceived(timestamp = 1_000L),
            sampleCoordinates(timestamp = 1_000L),
        )

        assertThat(activity.activityType).isNull()
        assertThat(activity.points.size).isEqualTo(1)
    }

    @Test
    fun `detector signals are read from recognition, step and pause records`() {
        val activity = readSingleActivity(
            sessionStarted(),
            """{"timestampUtc":"2025-09-05T06:00:10.000Z","message":"Activity recognized","activityId":"7",""" +
                """"fields":{"detectedActivity":"ON_BICYCLE","confidencePercent":"72","alternatives":""}}""",
            """{"message":"Step counter reading","activityId":"7","fields":""" +
                """{"readingTimestamp":"1757052011000","stepCounter":"4200"}}""",
            """{"timestampUtc":"2025-09-05T06:00:12.000Z","message":"Tracking paused manually","activityId":"7"}""",
            """{"timestampUtc":"2025-09-05T06:00:13.000Z","message":"Tracking resumed manually","activityId":"7"}""",
        )

        assertThat(activity.detectorSignals).containsExactly(
            DetectorSignal.Recognized(1_757_052_010_000L, RecognizedActivity.ON_BICYCLE, 72),
            DetectorSignal.StepCounter(1_757_052_011_000L, 4_200L),
            DetectorSignal.Paused(1_757_052_012_000L),
            DetectorSignal.Resumed(1_757_052_013_000L),
        )
    }

    private fun sessionStarted() =
        """{"message":"Tracking session started","activityId":"7","fields":""" +
            """{"activityId":"7","activityType":"WALKING","autoPauseSensitivity":"MEDIUM"}}"""

    private fun sampleReceived(
        timestamp: Long,
        speed: String = "1.4000",
        accuracy: String = "12.500",
    ) = """{"message":"Location sample received","activityId":"7","fields":""" +
        """{"sampleTimestamp":"$timestamp","reportedSpeed":"$speed","accuracyMeters":"$accuracy",""" +
        """"provider":"fused","ageMillis":"120","verticalAccuracyMeters":"3.0",""" +
        """"speedAccuracyMetersPerSecond":"0.3","bearingDegrees":"12.0",""" +
        """"bearingAccuracyDegrees":"9.0","satelliteCount":"null","status":"RECORDING"}}"""

    private fun sampleCoordinates(
        timestamp: Long,
        latitude: Double = 55.751244,
        longitude: Double = 37.618423,
    ) = """{"message":"Location sample coordinates","activityId":"7","fields":""" +
        """{"sampleTimestamp":"$timestamp","latitude":"$latitude","longitude":"$longitude",""" +
        """"altitudeMeters":"142.0"}}"""

    private fun pointProcessed(isOutlier: Boolean, isStop: Boolean) =
        """{"message":"Track point processed","activityId":"7","fields":""" +
            """{"isOutlier":"$isOutlier","isStop":"$isStop"}}"""

    private fun readSingleActivity(vararg lines: String): LoggedActivity =
        readActivities(*lines).single()

    private fun readActivities(vararg lines: String): List<LoggedActivity> {
        val file = File(createTempDirectory("korno-log").toFile(), "activity-7.jsonl")
        file.writeText(lines.joinToString("\n"))
        return readLoggedActivities(file)
    }
}
