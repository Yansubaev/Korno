package com.ians.korno.data.gpxexport.tcx

import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.data.gpxexport.TrackFileParseException
import com.ians.korno.data.gpxexport.format.ActivityTypeCodec
import com.ians.korno.data.gpxexport.format.Iso8601
import com.ians.korno.data.gpxexport.format.isKornoDocument
import com.ians.korno.data.gpxexport.format.parseDecimal
import com.ians.korno.data.gpxexport.gpx.isTruthy
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.xml.XmlEvent
import com.ians.korno.data.gpxexport.xml.XmlPullReader

internal object TcxParser {

    fun parse(source: String): TrackDocument {
        val reader = XmlPullReader(source)
        val text = StringBuilder()

        var sawRoot = false
        var ownFile = false
        var sportType: ActivityType? = null
        var exactType: ActivityType? = null
        var name: String? = null
        var note: String? = null
        val points = mutableListOf<TrackDocumentPoint>()
        var point: MutableTcxPoint? = null

        while (true) {
            when (val event = reader.next()) {
                is XmlEvent.StartElement -> {
                    text.setLength(0)
                    when (event.name) {
                        "TrainingCenterDatabase" -> {
                            sawRoot = true
                            ownFile = isKornoDocument(event.attributes)
                        }
                        "Activity" -> sportType = ActivityTypeCodec.parse(event.attributes["Sport"])
                        "Trackpoint" -> point = MutableTcxPoint()
                    }
                }

                is XmlEvent.Text -> text.append(event.value)

                is XmlEvent.EndElement -> {
                    val value = text.toString().trim()
                    text.setLength(0)

                    when (event.name) {
                        "LatitudeDegrees" -> point?.latitude = parseDecimal(value)
                        "LongitudeDegrees" -> point?.longitude = parseDecimal(value)
                        "AltitudeMeters" -> point?.altitudeMeters = parseDecimal(value)
                        "Time" -> point?.let { it.timestampEpochMillis = Iso8601.parse(value) }
                        // <Speed> есть и у Garmin (TPX), и у нас — читаем оба.
                        "Speed" -> point?.speedMetersPerSecond = parseDecimal(value)?.toFloat()
                        "Accuracy" -> point?.accuracyMeters = parseDecimal(value)?.toFloat()
                        "Stop" -> point?.isStop = value.isTruthy()
                        "ActivityName" -> name = value.takeIf { it.isNotEmpty() }
                        "ActivityType" -> exactType = ActivityTypeCodec.parse(value) ?: exactType
                        "Notes" -> if (note == null) note = value.takeIf { it.isNotEmpty() }
                        "Trackpoint" -> {
                            point?.toPoint()?.let(points::add)
                            point = null
                        }
                    }
                }

                XmlEvent.EndDocument -> break
            }
        }

        if (!sawRoot) throw TrackFileParseException(TrackFileError.UNSUPPORTED_FORMAT)

        return TrackDocument(
            name = name,
            activityType = exactType ?: sportType ?: ActivityType.OTHER,
            points = points,
            note = note,
            isKornoExport = ownFile,
        )
    }
}

private class MutableTcxPoint(
    var latitude: Double? = null,
    var longitude: Double? = null,
    var timestampEpochMillis: Long? = null,
    var altitudeMeters: Double? = null,
    var speedMetersPerSecond: Float? = null,
    var accuracyMeters: Float? = null,
    var isStop: Boolean = false,
) {
    /** Пауза без координат — валидный Trackpoint по схеме TCX, но для трека бесполезна. */
    fun toPoint(): TrackDocumentPoint? {
        val latitude = latitude?.takeIf { it in -90.0..90.0 } ?: return null
        val longitude = longitude?.takeIf { it in -180.0..180.0 } ?: return null
        return TrackDocumentPoint(
            latitude = latitude,
            longitude = longitude,
            timestampEpochMillis = timestampEpochMillis,
            altitudeMeters = altitudeMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            accuracyMeters = accuracyMeters,
            isStop = isStop,
        )
    }
}
