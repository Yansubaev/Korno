package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.GeoPoint
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.RecognitionSample
import com.ians.korno.core.trackprocessing.DetectorEvent
import com.ians.korno.core.trackprocessing.DetectorSignal
import com.ians.korno.core.trackprocessing.ModeDecoderConfig
import com.ians.korno.core.trackprocessing.SegmentDetectorConfig
import com.ians.korno.core.trackprocessing.TrackProcessingConfig
import com.ians.korno.core.trackprocessing.withAutoPauseSensitivity
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import com.ians.korno.data.gpxexport.TrackFileCodec
import com.ians.korno.data.gpxexport.mapper.toGeoPoints
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """
Korno track calibration — прогон записанных треков через pipeline обработки.

  ./gradlew :tools:track-calibration:run --args="<файл|каталог>... [опции]"

Принимаются файлы GPX и TCX, а также диагностические логи Korno: .jsonl или .zip
из «Поделиться диагностикой». Лог предпочтительнее: в нём лежат сэмплы в том виде,
в каком их отдал провайдер, вместе с погрешностью скорости и курсом, — тогда как GPX
уже прошёл через фильтр, и калибровать пороги по нему значит настраивать их по
результату их же работы.

Чтобы в логе оказались координаты, в настройках диагностики нужно включить
«Записывать GPS-сэмплы» до начала записи. Без них лог годится для разбора автопаузы,
но трека в нём нет.

Опции:
  --kind=<SEGMENT_KIND>    вид движения вместо указанного в файле
                           (RUNNING, CYCLING, WALKING, HIKING, DRIVING, OTHER)
  --sensitivity=<LEVEL>    чувствительность автопаузы: LOW, MEDIUM, HIGH
  --no-sweep               только сводка по трекам, без перебора порогов

Для логов с распознаванием активности (включается вместе с диагностикой) выводятся
ещё сегменты по виду движения и перебор порогов детектора. Если в записи стоят
эталонные метки (дебажная кнопка-флажок на экране записи), в конце — доля времени,
где разметка совпала с ними.
"""

fun main(arguments: Array<String>) {
    val options = parseOptions(arguments)
    if (options == null || options.paths.isEmpty()) {
        println(USAGE.trim())
        exitProcess(if (options == null) 1 else 0)
    }

    val files = options.paths.flatMap(::trackFilesIn).sortedBy(File::getName)
    if (files.isEmpty()) {
        println("Файлов GPX, TCX или логов не найдено: ${options.paths.joinToString { it.path }}")
        exitProcess(1)
    }

    val tracks = files.flatMap { file -> loadTracks(file, options) }
    if (tracks.isEmpty()) exitProcess(1)

    printSummary(tracks)
    if (!options.noSweep) tracks.forEach(::printSweeps)
    tracks.forEach { track -> printSegments(track, sweep = !options.noSweep) }
    printGroundTruth(tracks)
}

private class Options(
    val paths: List<File>,
    val segmentKind: SegmentKind?,
    val sensitivity: AutoPauseSensitivity?,
    val noSweep: Boolean,
)

private class LoadedTrack(
    val name: String,
    val segmentKind: SegmentKind,
    val points: List<GeoPoint>,
    val config: TrackProcessingConfig,
    val isKornoExport: Boolean,
    /** Вердикты, записанные приложением во время самой записи; `null` — источник не лог. */
    val recorded: RecordedVerdicts? = null,
    /** Сигналы детектора вида движения из лога; у GPX и TCX их нет. */
    val detectorSignals: List<DetectorSignal> = emptyList(),
    val recognitions: List<RecognitionSample> = emptyList(),
    val groundTruthMarks: List<GroundTruthMark> = emptyList(),
    /** Чувствительность автопаузы записи: итоговый пересчёт сегментов идёт с ней. */
    val sensitivity: AutoPauseSensitivity = AutoPauseSensitivity.MEDIUM,
)

/**
 * Что приложение решило по этим же точкам на телефоне. Расхождение с текущим прогоном
 * и есть то, ради чего порог меняют: видно не только новое число, но и насколько оно
 * отличается от того, что пользователь уже видел на экране.
 */
private class RecordedVerdicts(val outlierCount: Int, val stopCount: Int)

private fun parseOptions(arguments: Array<String>): Options? {
    val paths = mutableListOf<File>()
    var segmentKind: SegmentKind? = null
    var sensitivity: AutoPauseSensitivity? = null
    var noSweep = false

    arguments.forEach { argument ->
        when {
            argument == "--help" || argument == "-h" -> return null
            argument == "--no-sweep" -> noSweep = true
            argument.startsWith("--kind=") -> {
                segmentKind = enumOrNull<SegmentKind>(argument.substringAfter('=')) ?: return null
            }
            argument.startsWith("--sensitivity=") -> {
                sensitivity = enumOrNull<AutoPauseSensitivity>(argument.substringAfter('=')) ?: return null
            }
            argument.startsWith("-") -> return null
            else -> paths += File(argument)
        }
    }
    return Options(paths, segmentKind, sensitivity, noSweep)
}

private fun trackFilesIn(path: File): List<File> = when {
    path.isDirectory -> path.walkTopDown().filter { it.isFile && it.isTrackFile() }.toList()
    path.isFile -> listOf(path)
    else -> {
        println("Не найдено: ${path.path}")
        emptyList()
    }
}

private fun File.isTrackFile(): Boolean =
    extension.equals("gpx", ignoreCase = true) ||
        extension.equals("tcx", ignoreCase = true) ||
        isLogFile()

private fun loadTracks(file: File, options: Options): List<LoadedTrack> =
    if (file.isLogFile()) loadLogTracks(file, options) else listOfNotNull(loadTrack(file, options))

/**
 * Один лог может содержать несколько активностей: общий архив диагностики собирает их все.
 */
private fun loadLogTracks(file: File, options: Options): List<LoadedTrack> {
    val activities = runCatching { readLoggedActivities(file) }.getOrElse { error ->
        println("${file.name}: не разобран (${error.javaClass.simpleName})")
        return emptyList()
    }
    if (activities.isEmpty()) {
        println("${file.name}: записей активностей в логе нет")
        return emptyList()
    }

    return activities.mapNotNull { activity ->
        val name = "${file.name}#${activity.activityId}"
        if (activity.points.isEmpty()) {
            println("$name: координат в логе нет ${activity.missingCoordinatesHint()}")
            return@mapNotNull null
        }
        if (activity.samplesWithoutCoordinates > 0) {
            println(
                "$name: пропущено сэмплов без координат — ${activity.samplesWithoutCoordinates} " +
                    activity.missingCoordinatesHint(),
            )
        }
        val segmentKind = options.segmentKind ?: activity.segmentKind ?: SegmentKind.UNKNOWN
        if (options.segmentKind == null && activity.segmentKind == null) {
            println("$name: вида сегмента в логе нет, взят UNKNOWN — задайте --kind")
        }
        val sensitivity = options.sensitivity ?: activity.sensitivity
        LoadedTrack(
            name = name,
            segmentKind = segmentKind,
            points = activity.points,
            config = TrackProcessingConfig.forSegmentKind(segmentKind)
                .let { base -> sensitivity?.let(base::withAutoPauseSensitivity) ?: base },
            sensitivity = sensitivity ?: AutoPauseSensitivity.MEDIUM,
            isKornoExport = false,
            recorded = RecordedVerdicts(activity.recordedOutliers, activity.recordedStopRuns),
            detectorSignals = activity.detectorSignals,
            recognitions = activity.recognitions,
            groundTruthMarks = activity.groundTruthMarks,
        )
    }
}

private fun LoggedActivity.missingCoordinatesHint(): String = when {
    !sawCoordinateRecords ->
        "(включите «Записывать GPS-сэмплы» в настройках диагностики до начала записи)"
    missingSampleTimestamp ->
        "(лог записан версией без sampleTimestamp — сшить координаты с сэмплами нельзя)"
    else ->
        "(записи сэмпла и координат разошлись: лог обрезан или настройки менялись по ходу)"
}

private fun loadTrack(file: File, options: Options): LoadedTrack? {
    val content = file.readText()
    val document = when (val parsed = TrackFileCodec().parse(content)) {
        is Result.Success -> parsed.data
        is Result.Error -> {
            println("${file.name}: не разобран (${parsed.error})")
            return null
        }
    }
    val segmentKind = options.segmentKind ?: document.kind
    val points = document.toGeoPoints(activityId = 0L, fallbackStartEpochMillis = 0L)
    val config = TrackProcessingConfig.forSegmentKind(segmentKind)
        .let { base -> options.sensitivity?.let(base::withAutoPauseSensitivity) ?: base }
    return LoadedTrack(
        name = file.name,
        segmentKind = segmentKind,
        points = points,
        config = config,
        isKornoExport = document.isKornoExport,
    )
}

private fun printSummary(tracks: List<LoadedTrack>) {
    println()
    println("СВОДКА".title())
    println(
        "%-32s %-8s %6s %6s %9s %9s %9s %6s %7s %7s".format(
            Locale.US, "файл", "тип", "точек", "выбр.", "сырые км", "после км", "движение", "стоп", "набор м", "vmax",
        ),
    )
    tracks.forEach { track ->
        val run = runPipeline(track.points, track.config)
        println(
            "%-32s %-8s %6d %6d %9.2f %9.2f %9s %6d %7.0f %7.1f".format(
                Locale.US,
                track.name.take(32),
                track.segmentKind.name.take(8),
                track.points.size,
                run.outlierCount,
                rawDistanceMeters(track.points) / 1_000.0,
                run.statistics.distanceMeters / 1_000.0,
                formatDuration(run.statistics.movingTimeMillis),
                run.stopCount,
                run.statistics.elevationGainMeters,
                run.statistics.maxSpeedMetersPerSecond * 3.6,
            ),
        )
        printSignalQuality(track)
        printRecordedComparison(track, run)
    }
    println()
    println(
        "«сырые км» — сумма отрезков без фильтрации и сглаживания, верхняя оценка: " +
            "дрожание координат в неё тоже входит.",
    )
    if (tracks.any(LoadedTrack::isKornoExport)) {
        println(
            "Часть файлов — экспорт самого Korno. Такой трек уже сглажен, и пороги по нему " +
                "калибруются хуже, чем по чужому или по сырой записи.",
        )
    }
}

/**
 * Чем текущий прогон разошёлся с тем, что приложение решило во время самой записи.
 *
 * Совпадение здесь не самоцель: логи, снятые до изменения порогов, обязаны расходиться —
 * ради этого пороги и меняли. Ценно само число: видно, сколько точек поменяли вердикт и
 * не превратилось ли «чуть подкрутили» в «переписали трек».
 */
private fun printRecordedComparison(track: LoadedTrack, run: TrackRun) {
    val recorded = track.recorded ?: return
    println(
        "    на телефоне: выбросов %d, стоянок %d   сейчас: выбросов %d, стоянок %d".format(
            Locale.US,
            recorded.outlierCount,
            recorded.stopCount,
            run.outlierCount,
            run.stopCount,
        ),
    )
}

private fun printSignalQuality(track: LoadedTrack) {
    val quality = signalQuality(track.points)
    val accuracy = quality.accuracyPercentiles
        ?.let { "погрешность м: p50 %.1f  p90 %.1f  max %.1f".format(Locale.US, it.p50, it.p90, it.max) }
        ?: "погрешность: в файле нет"
    val interval = quality.intervalSecondsPercentiles
        ?.let { "интервал с: p50 %.1f  p90 %.1f  max %.1f".format(Locale.US, it.p50, it.p90, it.max) }
        ?: "интервал: одна точка"
    println("    $accuracy    $interval")
    if (quality.pointsWithoutAccuracy > 0) {
        println("    без погрешности: ${quality.pointsWithoutAccuracy} точек")
    }
}

/**
 * Перебор по одному порогу за раз: одновременная смена двух порогов не даёт понять,
 * который из них подвинул цифру.
 */
private fun printSweeps(track: LoadedTrack) {
    println()
    println("ПОРОГИ — ${track.name} (${track.segmentKind})".title())

    SWEEPS.forEach { sweep ->
        val current = sweep.currentValue(track.config)
        println()
        println("  ${sweep.name}  (сейчас ${"%.2f".format(Locale.US, current)})")
        println("  %10s %8s %10s %10s %6s".format(Locale.US, "значение", "выбр.", "дист. км", "движение", "стоп"))
        sweep.values(track.config).forEach { value ->
            val config = runCatching { sweep.apply(track.config, value) }.getOrNull()
            if (config == null) {
                println("  %10.2f %8s".format(Locale.US, value, "недопустимо"))
                return@forEach
            }
            val run = runPipeline(track.points, config)
            val marker = if (kotlin.math.abs(value - current) < 1e-6) "*" else " "
            println(
                "  %9.2f%s %8d %10.3f %10s %6d".format(
                    Locale.US,
                    value,
                    marker,
                    run.outlierCount,
                    run.statistics.distanceMeters / 1_000.0,
                    formatDuration(run.statistics.movingTimeMillis),
                    run.stopCount,
                ),
            )
        }
    }
}

/**
 * Сегменты по виду движения: живой вид (повтор онлайн-цикла приложения) и итоговая
 * разметка декодера, затем перебор порогов. Решения детектора выводятся целиком — именно
 * по ним видно, почему граница встала там.
 */
private fun printSegments(track: LoadedTrack, sweep: Boolean) {
    val input = segmentInput(track.points, track.detectorSignals, track.recognitions) ?: return
    val config = SegmentDetectorConfig()
    val run = runLiveDetector(input, config, sensitivity = track.sensitivity)

    println()
    println("СЕГМЕНТЫ — ${track.name}".title())
    println(
        "  вердиктов распознавания: %d, показаний шагомера: %d".format(
            Locale.US,
            input.recognitions.size,
            input.signals.count { it is DetectorSignal.StepCounter },
        ),
    )
    println()
    println("  живой вид, решения детектора:")
    run.events.forEach { event ->
        println("    %9s  %s".format(Locale.US, formatDuration(event.timestampMillis - input.startMillis), event.describe(input)))
    }

    println()
    println("  до слияния коротких:")
    printSegmentTable(run.detected, input.startMillis)
    println("  после слияния:")
    printSegmentTable(run.merged, input.startMillis)

    // То, что запишется при сохранении: декодер по всей записи, затем слияние коротких.
    val decoderConfig = ModeDecoderConfig()
    val decoded = runModeDecoder(input, decoderConfig, track.sensitivity)
    println()
    println("  декодер (Витерби), до слияния:")
    printSegmentTable(decoded.detected, input.startMillis)
    println("  после слияния:")
    printSegmentTable(decoded.merged, input.startMillis)

    if (!sweep) return
    DECODER_SWEEPS.forEach { decoderSweep ->
        val current = decoderSweep.currentValue(decoderConfig)
        println()
        println("  декодер: ${decoderSweep.name}  (сейчас ${"%.2f".format(Locale.US, current)})")
        println("  %10s %8s %8s  %s".format(Locale.US, "значение", "сегм.", "слито", "по видам, км"))
        decoderSweep.values.forEach { value ->
            val swept = runModeDecoder(input, decoderSweep.apply(decoderConfig, value), track.sensitivity)
            val marker = if (kotlin.math.abs(value - current) < 1e-6) "*" else " "
            println(
                "  %9.2f%s %8d %8d  %s".format(
                    Locale.US,
                    value,
                    marker,
                    swept.detected.size,
                    swept.merged.size,
                    swept.merged.byKind(),
                ),
            )
        }
    }
    DETECTOR_SWEEPS.forEach { detectorSweep ->
        val current = detectorSweep.currentValue(config)
        println()
        println("  ${detectorSweep.name}  (сейчас ${"%.2f".format(Locale.US, current)})")
        println("  %10s %8s %8s  %s".format(Locale.US, "значение", "сегм.", "слито", "по видам, км"))
        detectorSweep.values.forEach { value ->
            val swept = runLiveDetector(input, detectorSweep.apply(config, value), sensitivity = track.sensitivity)
            val marker = if (kotlin.math.abs(value - current) < 1e-6) "*" else " "
            println(
                "  %9.2f%s %8d %8d  %s".format(
                    Locale.US,
                    value,
                    marker,
                    swept.detected.size,
                    swept.merged.size,
                    swept.merged.byKind(),
                ),
            )
        }
    }
}

/**
 * Доля эталонного времени, где итоговая разметка совпала с метками, — по записи и в сумме:
 * декодер как есть, декодер без Activity Recognition (`ADR 0007`) на той же записи и
 * живой вид — то, что человек видел на экране записи, до слияния коротких.
 */
private fun printGroundTruth(tracks: List<LoadedTrack>) {
    val rows = tracks.filter { it.groundTruthMarks.isNotEmpty() }.mapNotNull { track ->
        val input = segmentInput(track.points, track.detectorSignals, track.recognitions) ?: return@mapNotNull null
        fun match(segments: List<ActivitySegment>) =
            groundTruthMatch(track.groundTruthMarks, segments, input.signals, input.endMillis)
        fun decoded(input: SegmentInput) = runModeDecoder(input, ModeDecoderConfig(), track.sensitivity).merged
        track.name to listOf(
            match(decoded(input)),
            match(decoded(input.withoutRecognition())),
            match(runLiveDetector(input, SegmentDetectorConfig(), sensitivity = track.sensitivity).detected),
        )
    }
    if (rows.isEmpty()) return

    println()
    println("ЭТАЛОННАЯ РАЗМЕТКА — доля времени, где вид совпал с метками".title())
    println("  %-40s %9s %8s %8s %8s".format(Locale.US, "запись", "эталон", "с AR", "без AR", "живой"))
    val total = "в сумме" to rows.map { it.second }.reduce { sum, row -> sum.zip(row, GroundTruthMatch::plus) }
    (rows + total).forEach { (name, matches) ->
        println(
            "  %-40s %9s %8s %8s %8s".format(
                Locale.US,
                name.takeLast(40),
                formatDuration(matches.first().referenceMillis),
                *matches.map { match -> match.share?.let { "%.1f%%".format(Locale.US, it * 100) } ?: "—" }.toTypedArray(),
            ),
        )
    }
}

private fun List<ActivitySegment>.byKind(): String =
    groupBy { it.kind }.entries.joinToString(" · ") { (kind, segments) ->
        "%s %.2f".format(Locale.US, kind, segments.sumOf(ActivitySegment::distanceMeters) / 1_000.0)
    }

private fun printSegmentTable(segments: List<ActivitySegment>, startMillis: Long) {
    println("  %-8s %9s %9s %8s %7s %9s".format(Locale.US, "вид", "начало", "длит.", "км", "шаги", "шаг/мин"))
    segments.forEach { segment ->
        val duration = (segment.endTimeEpochMillis ?: segment.startTimeEpochMillis) - segment.startTimeEpochMillis
        val cadence = segment.stepCount?.takeIf { duration > 0 }?.let { it * 60_000.0 / duration }
        println(
            "  %-8s %9s %9s %8.2f %7s %9s".format(
                Locale.US,
                segment.kind.name.take(8),
                formatDuration(segment.startTimeEpochMillis - startMillis),
                formatDuration(duration),
                segment.distanceMeters / 1_000.0,
                segment.stepCount?.toString() ?: "—",
                cadence?.let { "%.0f".format(Locale.US, it) } ?: "—",
            ),
        )
    }
}

private fun DetectorEvent.describe(input: SegmentInput): String = when (this) {
    is DetectorEvent.CandidateStarted -> "кандидат $kind"
    is DetectorEvent.CandidateDropped -> "кандидат $kind не удержался"
    is DetectorEvent.SegmentConfirmed ->
        "подтверждено ${from ?: "—"} → $to, граница ${formatDuration(boundaryMillis - input.startMillis)}"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    return "%d:%02d:%02d".format(Locale.US, totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}

private fun String.title(): String = "$this\n" + "─".repeat(length)
