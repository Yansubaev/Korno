package com.ians.korno.data.gpxexport.gpx

import com.ians.korno.data.gpxexport.format.ActivityTypeCodec
import com.ians.korno.data.gpxexport.format.Iso8601
import com.ians.korno.data.gpxexport.format.KORNO_CREATOR
import com.ians.korno.data.gpxexport.format.KORNO_NAMESPACE
import com.ians.korno.data.gpxexport.format.formatDecimal
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.xml.XmlWriter

internal const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"

/** Знаков после запятой: ~1 см по широте, дальше врёт уже сам GPS. */
internal const val COORDINATE_DECIMALS = 7
internal const val ALTITUDE_DECIMALS = 2
internal const val SPEED_DECIMALS = 3
internal const val ACCURACY_DECIMALS = 1

internal object GpxWriter {

    fun write(document: TrackDocument): String {
        val writer = XmlWriter()
        writer.declaration()
        writer.element(
            name = "gpx",
            attributes = listOf(
                "version" to "1.1",
                "creator" to KORNO_CREATOR,
                "xmlns" to GPX_NAMESPACE,
                "xmlns:korno" to KORNO_NAMESPACE,
            ),
        ) {
            element("metadata") {
                text("name", document.name)
                text("desc", document.note)
                text("time", document.startTimeEpochMillis?.let(Iso8601::format))
            }
            element("trk") {
                text("name", document.name)
                text("type", ActivityTypeCodec.toGpxType(document.activityType))
                element("trkseg") {
                    document.points.forEach { point -> writePoint(point) }
                }
            }
        }
        return writer.build()
    }

    private fun XmlWriter.writePoint(point: TrackDocumentPoint) {
        element(
            name = "trkpt",
            attributes = listOf(
                "lat" to formatDecimal(point.latitude, COORDINATE_DECIMALS),
                "lon" to formatDecimal(point.longitude, COORDINATE_DECIMALS),
            ),
        ) {
            text("ele", point.altitudeMeters?.let { formatDecimal(it, ALTITUDE_DECIMALS) })
            text("time", point.timestampEpochMillis?.let(Iso8601::format))

            val hasExtensions = point.speedMetersPerSecond != null ||
                point.accuracyMeters != null ||
                point.isStop
            if (hasExtensions) {
                element("extensions") {
                    text(
                        "korno:speed",
                        point.speedMetersPerSecond?.let { formatDecimal(it.toDouble(), SPEED_DECIMALS) },
                    )
                    text(
                        "korno:accuracy",
                        point.accuracyMeters?.let { formatDecimal(it.toDouble(), ACCURACY_DECIMALS) },
                    )
                    if (point.isStop) text("korno:stop", "true")
                }
            }
        }
    }
}
