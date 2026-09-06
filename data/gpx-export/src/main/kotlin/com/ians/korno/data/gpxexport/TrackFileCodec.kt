package com.ians.korno.data.gpxexport

import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.data.gpxexport.gpx.GpxParser
import com.ians.korno.data.gpxexport.gpx.GpxWriter
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.tcx.TcxParser
import com.ians.korno.data.gpxexport.tcx.TcxWriter
import com.ians.korno.data.gpxexport.xml.XmlParseException

/** Разбор прервался на содержимом, а не на синтаксисе XML. */
internal class TrackFileParseException(val error: TrackFileError) : Exception(error.name)

/**
 * Единая точка входа в чтение и запись файлов треков.
 *
 * Чистый Kotlin: ни Android SDK, ни ввода-вывода — на вход и выход только строки.
 * Работу с документами SAF ведёт Android-слой, а этот модуль остаётся тестируемым
 * без эмулятора и переносимым, если проект уедет на KMP.
 */
class TrackFileCodec {

    fun write(document: TrackDocument, format: TrackFileFormat): String = when (format) {
        TrackFileFormat.GPX -> GpxWriter.write(document)
        TrackFileFormat.TCX -> TcxWriter.write(document)
    }

    /**
     * Разбирает содержимое файла. [format] можно не указывать — тогда он определяется
     * по самому содержимому.
     */
    fun parse(
        content: String,
        format: TrackFileFormat? = null,
    ): Result<TrackDocument, TrackFileError> {
        val resolvedFormat = format
            ?: detectFormat(content)
            ?: return Result.Error(TrackFileError.UNSUPPORTED_FORMAT)

        val document = try {
            when (resolvedFormat) {
                TrackFileFormat.GPX -> GpxParser.parse(content)
                TrackFileFormat.TCX -> TcxParser.parse(content)
            }
        } catch (error: TrackFileParseException) {
            return Result.Error(error.error)
        } catch (error: XmlParseException) {
            return Result.Error(TrackFileError.MALFORMED_CONTENT)
        }

        if (document.points.isEmpty()) return Result.Error(TrackFileError.NO_TRACK_POINTS)
        return Result.Success(document)
    }

    /**
     * Формат определяется по содержимому, а расширение — лишь подсказка на случай,
     * когда корневой элемент не найден: имя файла легко теряется или врёт, особенно
     * после пересылки через мессенджер.
     */
    fun detectFormat(content: String, fileName: String? = null): TrackFileFormat? {
        val head = content.take(FORMAT_PROBE_LENGTH)
        return when {
            head.contains("<TrainingCenterDatabase", ignoreCase = true) -> TrackFileFormat.TCX
            head.contains("<gpx", ignoreCase = true) -> TrackFileFormat.GPX
            else -> fileName?.let(TrackFileFormat::fromFileName)
        }
    }

    private companion object {
        /** Корневой элемент идёт после декларации и, изредка, DOCTYPE — этого хватает. */
        const val FORMAT_PROBE_LENGTH = 4_096
    }
}
