package com.ians.korno.core.domain.model

/**
 * Форматы обмена треками. MIME-типы указаны здесь, а не в Android-слое, потому что
 * они часть самого формата, а не платформы: их же ждёт SAF при выборе файла.
 */
enum class TrackFileFormat(
    val extension: String,
    val mimeType: String,
) {
    GPX(extension = "gpx", mimeType = "application/gpx+xml"),
    TCX(extension = "tcx", mimeType = "application/vnd.garmin.tcx+xml"),
    ;

    companion object {
        /** Формат по имени файла; `null`, если расширение незнакомо. */
        fun fromFileName(fileName: String): TrackFileFormat? {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}
