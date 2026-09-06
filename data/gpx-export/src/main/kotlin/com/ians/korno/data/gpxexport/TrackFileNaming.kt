package com.ians.korno.data.gpxexport

import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.data.gpxexport.format.Iso8601

/**
 * Имя файла для экспорта: дата спереди, чтобы папка с треками сортировалась по времени,
 * и понятное название следом. Одно и то же имя предлагается и в диалоге сохранения,
 * и при массовом экспорте — иначе файлы одной активности выглядели бы разными.
 */
object TrackFileNaming {

    private const val MAX_TITLE_LENGTH = 48

    fun fileName(activity: Activity, format: TrackFileFormat): String {
        val date = Iso8601.format(activity.startTimeEpochMillis).take(DATE_LENGTH)
        val title = activity.title
            ?.let(::slug)
            ?.takeIf { it.isNotEmpty() }
            ?: activity.activityType.name.lowercase()
        return "$date-$title.${format.extension}"
    }

    /**
     * Массовый экспорт складывает файлы в одну папку, а названия активностей
     * повторяются, поэтому к имени добавляется id — SAF иначе создаст «name (1)».
     */
    fun uniqueFileName(activity: Activity, format: TrackFileFormat): String {
        val base = fileName(activity, format).removeSuffix(".${format.extension}")
        return "$base-${activity.id}.${format.extension}"
    }

    private fun slug(title: String): String = buildString {
        title.trim().take(MAX_TITLE_LENGTH).forEach { character ->
            when {
                character.isLetterOrDigit() -> append(character.lowercaseChar())
                // Разделители схлопываются: «Morning  run!» не должно давать «morning--run-».
                isNotEmpty() && last() != '-' -> append('-')
            }
        }
    }.trim('-')

    private const val DATE_LENGTH = 10
}
