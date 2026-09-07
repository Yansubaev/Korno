package com.ians.korno.tools.trackcalibration

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.GeoPoint
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

Опции:
  --type=<ACTIVITY_TYPE>   тип активности вместо указанного в файле
                           (RUNNING, CYCLING, WALKING, HIKING, DRIVING, OTHER)
  --sensitivity=<LEVEL>    чувствительность автопаузы: LOW, MEDIUM, HIGH
  --no-sweep               только сводка по трекам, без перебора порогов
"""

fun main(arguments: Array<String>) {
    val options = parseOptions(arguments)
    if (options == null || options.paths.isEmpty()) {
        println(USAGE.trim())
        exitProcess(if (options == null) 1 else 0)
    }

    val files = options.paths.flatMap(::trackFilesIn).sortedBy(File::getName)
    if (files.isEmpty()) {
        println("Файлов GPX или TCX не найдено: ${options.paths.joinToString { it.path }}")
        exitProcess(1)
    }

    val tracks = files.mapNotNull { file -> loadTrack(file, options) }
    if (tracks.isEmpty()) exitProcess(1)

    printSummary(tracks)
    if (!options.noSweep) tracks.forEach(::printSweeps)
}

private class Options(
    val paths: List<File>,
    val activityType: ActivityType?,
    val sensitivity: AutoPauseSensitivity?,
    val noSweep: Boolean,
)

private class LoadedTrack(
    val name: String,
    val activityType: ActivityType,
    val points: List<GeoPoint>,
    val config: TrackProcessingConfig,
    val isKornoExport: Boolean,
)

private fun parseOptions(arguments: Array<String>): Options? {
    val paths = mutableListOf<File>()
    var activityType: ActivityType? = null
    var sensitivity: AutoPauseSensitivity? = null
    var noSweep = false

    arguments.forEach { argument ->
        when {
            argument == "--help" || argument == "-h" -> return null
            argument == "--no-sweep" -> noSweep = true
            argument.startsWith("--type=") -> {
                activityType = enumOrNull<ActivityType>(argument.substringAfter('=')) ?: return null
            }
            argument.startsWith("--sensitivity=") -> {
                sensitivity = enumOrNull<AutoPauseSensitivity>(argument.substringAfter('=')) ?: return null
            }
            argument.startsWith("-") -> return null
            else -> paths += File(argument)
        }
    }
    return Options(paths, activityType, sensitivity, noSweep)
}

private inline fun <reified E : Enum<E>> enumOrNull(value: String): E? =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }

private fun trackFilesIn(path: File): List<File> = when {
    path.isDirectory -> path.walkTopDown().filter { it.isFile && it.isTrackFile() }.toList()
    path.isFile -> listOf(path)
    else -> {
        println("Не найдено: ${path.path}")
        emptyList()
    }
}

private fun File.isTrackFile(): Boolean =
    extension.equals("gpx", ignoreCase = true) || extension.equals("tcx", ignoreCase = true)

private fun loadTrack(file: File, options: Options): LoadedTrack? {
    val content = file.readText()
    val document = when (val parsed = TrackFileCodec().parse(content)) {
        is Result.Success -> parsed.data
        is Result.Error -> {
            println("${file.name}: не разобран (${parsed.error})")
            return null
        }
    }
    val activityType = options.activityType ?: document.activityType
    val points = document.toGeoPoints(activityId = 0L, fallbackStartEpochMillis = 0L)
    val config = TrackProcessingConfig.forActivityType(activityType)
        .let { base -> options.sensitivity?.let(base::withAutoPauseSensitivity) ?: base }
    return LoadedTrack(
        name = file.name,
        activityType = activityType,
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
        val run = runPipeline(track.points, track.activityType, track.config)
        println(
            "%-32s %-8s %6d %6d %9.2f %9.2f %9s %6d %7.0f %7.1f".format(
                Locale.US,
                track.name.take(32),
                track.activityType.name.take(8),
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
    println("ПОРОГИ — ${track.name} (${track.activityType})".title())

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
            val run = runPipeline(track.points, track.activityType, config)
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

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    return "%d:%02d:%02d".format(Locale.US, totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
}

private fun String.title(): String = "$this\n" + "─".repeat(length)
