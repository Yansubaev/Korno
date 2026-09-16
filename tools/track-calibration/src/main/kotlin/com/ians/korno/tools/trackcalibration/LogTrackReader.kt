package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.domain.model.RecordingStatus
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.domain.model.RecognizedActivity
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Восстановление трека из диагностического лога.
 *
 * Лог удобнее файла трека тем, что в нём лежит именно то, что приложение получило от
 * провайдера, — до всякой обработки. Экспортированный GPX, наоборот, уже прошёл через
 * фильтр и сглаживание, и калибровать пороги по нему значит настраивать их по результату
 * их же работы. Плюс в логе есть то, чего в GPX нет вовсе: погрешность скорости, курс,
 * провайдер фикса и вердикты, которые приложение приняло во время самой записи.
 *
 * Координаты пишутся отдельной записью и только с явного согласия («Записывать
 * GPS-сэмплы» в настройках диагностики). Без него лог остаётся полезным для разбора
 * автопаузы, но трека в нём нет — см. [LoggedActivity.samplesWithoutCoordinates].
 */
class LoggedActivity(
    val activityId: Long,
    /** Тип активности из записи о старте; `null` — начала сессии в логе нет. */
    val activityType: ActivityType?,
    val sensitivity: AutoPauseSensitivity?,
    val points: List<GeoPoint>,
    /** Сэмплы, для которых координат в логе не оказалось: точку из них не собрать. */
    val samplesWithoutCoordinates: Int,
    /** Вердикты, вынесенные приложением во время самой записи, — с чем сравнивать. */
    val recordedOutliers: Int,
    val recordedStopRuns: Int,
    /**
     * Лог записан версией, которая ещё не писала время фикса. Сшить координаты с
     * остальными полями сэмпла по такому логу нельзя.
     */
    val missingSampleTimestamp: Boolean,
    /**
     * Записи с координатами в логе встречались вообще. Если нет — дело не в рассинхроне,
     * а в том, что запись GPS-сэмплов была выключена.
     */
    val sawCoordinateRecords: Boolean,
    /**
     * Сигналы детектора вида движения, кроме скорости: её калибровка считает сама по
     * точкам, тем же фильтром, что и приложение. По времени не отсортированы.
     */
    val detectorSignals: List<DetectorSignal> = emptyList(),
    /** Ответы распознавания целиком, с альтернативами, — вход декодера вида движения. */
    val recognitions: List<RecognitionSample> = emptyList(),
)

private const val SESSION_STARTED = "Tracking session started"
private const val SAMPLE_RECEIVED = "Location sample received"
private const val SAMPLE_COORDINATES = "Location sample coordinates"
private const val POINT_PROCESSED = "Track point processed"
private const val ACTIVITY_RECOGNIZED = "Activity recognized"
private const val STEP_COUNTER_READING = "Step counter reading"
private const val PAUSED_MANUALLY = "Tracking paused manually"
private const val RESUMED_MANUALLY = "Tracking resumed manually"

private val json = Json { ignoreUnknownKeys = true }

fun File.isLogFile(): Boolean =
    extension.equals("jsonl", ignoreCase = true) || extension.equals("zip", ignoreCase = true)

/**
 * Все сеансы записи, найденные в файле. Архив «поделиться диагностикой» содержит логи всех
 * активностей сразу, архив одной активности — только её.
 */
fun readLoggedActivities(file: File): List<LoggedActivity> {
    val lines = when {
        file.extension.equals("zip", ignoreCase = true) -> logLinesFromArchive(file)
        else -> file.readLines()
    }
    return buildActivities(lines)
}

private fun logLinesFromArchive(file: File): List<String> = ZipFile(file).use { archive ->
    archive.entries()
        .asSequence()
        .filter { !it.isDirectory && it.name.endsWith(".jsonl", ignoreCase = true) }
        .sortedBy { it.name }
        .flatMap { entry -> archive.getInputStream(entry).bufferedReader().readLines().asSequence() }
        .toList()
}

private fun buildActivities(lines: List<String>): List<LoggedActivity> {
    val builders = linkedMapOf<Long, ActivityBuilder>()

    lines.forEach { line ->
        if (line.isBlank()) return@forEach
        val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
        val activityId = record.string("activityId")?.toLongOrNull() ?: return@forEach
        val builder = builders.getOrPut(activityId) { ActivityBuilder(activityId) }
        builder.consume(record)
    }

    return builders.values.map(ActivityBuilder::build)
}

private class ActivityBuilder(private val activityId: Long) {
    private var activityType: ActivityType? = null
    private var sensitivity: AutoPauseSensitivity? = null
    private val points = mutableListOf<GeoPoint>()

    /** Поля последнего сэмпла, ждущие своей записи с координатами. */
    private var pendingSample: JsonObject? = null
    private var pendingTimestamp: Long? = null

    private var samplesWithoutCoordinates = 0
    private var recordedOutliers = 0
    private var recordedStopRuns = 0
    private var insideRecordedStop = false
    private var sawSampleWithoutTimestamp = false
    private var sawCoordinateRecords = false
    private val detectorSignals = mutableListOf<DetectorSignal>()
    private val recognitions = mutableListOf<RecognitionSample>()

    fun consume(record: JsonObject) {
        when (record.string("message")) {
            SESSION_STARTED -> {
                activityType = record.field("activityType")?.let { enumOrNull<ActivityType>(it) }
                sensitivity = record.field("autoPauseSensitivity")
                    ?.let { enumOrNull<AutoPauseSensitivity>(it) }
            }

            SAMPLE_RECEIVED -> {
                // Предыдущий сэмпл так и не дождался координат: логирование GPS-сэмплов
                // было выключено, и восстановить точку не из чего.
                if (pendingSample != null) samplesWithoutCoordinates++
                val timestamp = record.field("sampleTimestamp")?.toLongOrNull()
                if (timestamp == null) {
                    sawSampleWithoutTimestamp = true
                    pendingSample = null
                    pendingTimestamp = null
                    samplesWithoutCoordinates++
                } else {
                    pendingSample = record
                    pendingTimestamp = timestamp
                }
            }

            SAMPLE_COORDINATES -> {
                sawCoordinateRecords = true
                val sample = pendingSample ?: return
                val timestamp = pendingTimestamp ?: return
                pendingSample = null
                pendingTimestamp = null
                // Ключ защищает от рассинхронизации: если одна из двух записей потерялась
                // (обрезан лог, менялись настройки), координаты не приклеятся к чужому сэмплу.
                if (record.field("sampleTimestamp")?.toLongOrNull() != timestamp) {
                    samplesWithoutCoordinates++
                    return
                }
                points += geoPoint(sample, record, timestamp)
            }

            // Время распознавания и паузы — время записи в лог: своего у этих событий нет.
            ACTIVITY_RECOGNIZED -> {
                val timestamp = record.loggedAtMillis() ?: return
                val activity = record.field("detectedActivity")?.let { enumOrNull<RecognizedActivity>(it) }
                    ?: RecognizedActivity.UNKNOWN
                val confidence = record.field("confidencePercent")?.toIntOrNull() ?: 0
                detectorSignals += DetectorSignal.Recognized(timestamp, activity, confidence)
                // Альтернативы записаны строкой «ON_FOOT=80, IN_VEHICLE=6».
                val alternatives = record.field("alternatives").orEmpty().split(",").mapNotNull { entry ->
                    val (name, value) = entry.split("=").takeIf { it.size == 2 } ?: return@mapNotNull null
                    val alternative = enumOrNull<RecognizedActivity>(name.trim()) ?: return@mapNotNull null
                    value.trim().toIntOrNull()?.let { alternative to it }
                }
                recognitions += RecognitionSample(
                    activityId = activityId,
                    timestampEpochMillis = timestamp,
                    confidences = (alternatives + (activity to confidence)).toMap(),
                )
            }

            STEP_COUNTER_READING -> {
                val timestamp = record.field("readingTimestamp")?.toLongOrNull() ?: return
                val counter = record.field("stepCounter")?.toLongOrNull() ?: return
                detectorSignals += DetectorSignal.StepCounter(timestamp, counter)
            }

            PAUSED_MANUALLY -> record.loggedAtMillis()?.let { detectorSignals += DetectorSignal.Paused(it) }

            RESUMED_MANUALLY -> record.loggedAtMillis()?.let { detectorSignals += DetectorSignal.Resumed(it) }

            POINT_PROCESSED -> {
                if (record.field("isOutlier") == "true") recordedOutliers++
                val isStop = record.field("isStop") == "true"
                if (isStop && !insideRecordedStop) recordedStopRuns++
                insideRecordedStop = isStop
            }
        }
    }

    fun build(): LoggedActivity {
        if (pendingSample != null) samplesWithoutCoordinates++
        return LoggedActivity(
            activityId = activityId,
            activityType = activityType,
            sensitivity = sensitivity,
            points = points.sortedBy(GeoPoint::timestampEpochMillis),
            samplesWithoutCoordinates = samplesWithoutCoordinates,
            recordedOutliers = recordedOutliers,
            recordedStopRuns = recordedStopRuns,
            missingSampleTimestamp = sawSampleWithoutTimestamp,
            sawCoordinateRecords = sawCoordinateRecords,
            detectorSignals = detectorSignals.toList(),
            recognitions = recognitions.toList(),
        )
    }

    private fun geoPoint(sample: JsonObject, coordinates: JsonObject, timestamp: Long) = GeoPoint(
        id = points.size.toLong(),
        activityId = activityId,
        latitude = coordinates.field("latitude")?.toDoubleOrNull() ?: 0.0,
        longitude = coordinates.field("longitude")?.toDoubleOrNull() ?: 0.0,
        altitudeMeters = coordinates.field("altitudeMeters")?.toDoubleOrNull(),
        timestampEpochMillis = timestamp,
        accuracyMeters = sample.field("accuracyMeters")?.toFloatOrNull(),
        speedMetersPerSecond = sample.field("reportedSpeed")?.toFloatOrNull(),
        speedAccuracyMetersPerSecond = sample.field("speedAccuracyMetersPerSecond")?.toFloatOrNull(),
        bearingDegrees = sample.field("bearingDegrees")?.toFloatOrNull(),
        bearingAccuracyDegrees = sample.field("bearingAccuracyDegrees")?.toFloatOrNull(),
        // Статус нужен конвейеру: на ручной паузе трек не рвётся, но и в статистику
        // не идёт, и делит запись на участки.
        recordingStatus = sample.field("status")?.let { enumOrNull<RecordingStatus>(it) }
            ?: RecordingStatus.RECORDING,
    )
}

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.loggedAtMillis(): Long? =
    string("timestampUtc")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Поля лога всегда строковые, и отсутствующее значение записано как `"null"`. */
private fun JsonObject.field(name: String): String? =
    (this["fields"] as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }

internal inline fun <reified E : Enum<E>> enumOrNull(value: String): E? =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
