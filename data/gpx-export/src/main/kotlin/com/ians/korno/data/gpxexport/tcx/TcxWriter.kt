package com.ians.korno.data.gpxexport.tcx

import com.ians.korno.data.gpxexport.format.SegmentKindCodec
import com.ians.korno.data.gpxexport.format.Iso8601
import com.ians.korno.data.gpxexport.format.KORNO_NAMESPACE
import com.ians.korno.data.gpxexport.format.formatDecimal
import com.ians.korno.data.gpxexport.gpx.ACCURACY_DECIMALS
import com.ians.korno.data.gpxexport.gpx.ALTITUDE_DECIMALS
import com.ians.korno.data.gpxexport.gpx.COORDINATE_DECIMALS
import com.ians.korno.data.gpxexport.gpx.SPEED_DECIMALS
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.xml.XmlWriter

internal const val TCX_NAMESPACE =
    "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"

internal object TcxWriter {

    fun write(document: TrackDocument): String {
        val writer = XmlWriter()
        val startTime = document.startTimeEpochMillis
        val elapsedSeconds = (document.summary?.elapsedTimeMillis ?: elapsedFromPoints(document)) / 1_000.0

        writer.declaration()
        writer.element(
            name = "TrainingCenterDatabase",
            attributes = listOf(
                "xmlns" to TCX_NAMESPACE,
                "xmlns:korno" to KORNO_NAMESPACE,
            ),
        ) {
            element("Activities") {
                element(
                    name = "Activity",
                    attributes = listOf("Sport" to SegmentKindCodec.toTcxSport(document.kind)),
                ) {
                    text("Id", startTime?.let(Iso8601::format))
                    element(
                        name = "Lap",
                        attributes = startTime?.let { listOf("StartTime" to Iso8601.format(it)) }.orEmpty(),
                    ) {
                        text("TotalTimeSeconds", formatDecimal(elapsedSeconds, decimals = 1))
                        text(
                            "DistanceMeters",
                            formatDecimal(document.summary?.distanceMeters ?: 0.0, decimals = 2),
                        )
                        text(
                            "MaximumSpeed",
                            formatDecimal(
                                document.summary?.maxSpeedMetersPerSecond ?: 0.0,
                                SPEED_DECIMALS,
                            ),
                        )
                        // Калории обязательны по схеме; шагомера и пульсометра у нас нет,
                        // а выдумывать число хуже, чем честно отдать ноль.
                        text("Calories", "0")
                        text("Intensity", "Active")
                        text("TriggerMethod", "Manual")
                        element("Track") {
                            document.points.forEach { point -> writePoint(point) }
                        }
                    }
                    text("Notes", document.note)
                    // Sport в TCX ограничен тремя значениями схемы, поэтому точный тип
                    // и название дублируются расширением — ради round-trip своих же файлов.
                    element("Extensions") {
                        text("korno:ActivityName", document.name)
                        text("korno:SegmentKind", document.kind.name)
                    }
                }
            }
        }
        return writer.build()
    }

    private fun XmlWriter.writePoint(point: TrackDocumentPoint) {
        element("Trackpoint") {
            text("Time", point.timestampEpochMillis?.let(Iso8601::format))
            element("Position") {
                text("LatitudeDegrees", formatDecimal(point.latitude, COORDINATE_DECIMALS))
                text("LongitudeDegrees", formatDecimal(point.longitude, COORDINATE_DECIMALS))
            }
            text("AltitudeMeters", point.altitudeMeters?.let { formatDecimal(it, ALTITUDE_DECIMALS) })

            val hasExtensions = point.speedMetersPerSecond != null ||
                point.accuracyMeters != null ||
                point.isStop
            if (hasExtensions) {
                element("Extensions") {
                    text(
                        "korno:Speed",
                        point.speedMetersPerSecond?.let { formatDecimal(it.toDouble(), SPEED_DECIMALS) },
                    )
                    text(
                        "korno:Accuracy",
                        point.accuracyMeters?.let { formatDecimal(it.toDouble(), ACCURACY_DECIMALS) },
                    )
                    if (point.isStop) text("korno:Stop", "true")
                }
            }
        }
    }

    private fun elapsedFromPoints(document: TrackDocument): Long {
        val timestamps = document.points.mapNotNull { it.timestampEpochMillis }
        if (timestamps.size < 2) return 0L
        return (timestamps.max() - timestamps.min()).coerceAtLeast(0L)
    }
}
