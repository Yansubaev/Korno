package com.ians.korno.data.gpxexport.gpx

import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.data.gpxexport.TrackFileParseException
import com.ians.korno.data.gpxexport.format.ActivityTypeCodec
import com.ians.korno.data.gpxexport.format.Iso8601
import com.ians.korno.data.gpxexport.format.parseDecimal
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.xml.XmlEvent
import com.ians.korno.data.gpxexport.xml.XmlPullReader

internal object GpxParser {

    fun parse(source: String): TrackDocument {
        val reader = XmlPullReader(source)
        val path = ArrayDeque<String>()
        val text = StringBuilder()

        var sawRoot = false
        var metadataName: String? = null
        var note: String? = null
        var trackName: String? = null
        var activityType: ActivityType? = null
        val trackPoints = mutableListOf<TrackDocumentPoint>()
        // Маршрут — запасной источник: часть экспортёров кладёт трек в <rte>,
        // и брать его стоит, только когда <trk> в файле нет вовсе.
        val routePoints = mutableListOf<TrackDocumentPoint>()
        var point: MutablePoint? = null

        while (true) {
            when (val event = reader.next()) {
                is XmlEvent.StartElement -> {
                    text.setLength(0)
                    path.addLast(event.name)
                    when (event.name) {
                        "gpx" -> sawRoot = true
                        "trkpt", "rtept" -> point = readPointAttributes(event.attributes)
                    }
                }

                is XmlEvent.Text -> text.append(event.value)

                is XmlEvent.EndElement -> {
                    val value = text.toString().trim()
                    text.setLength(0)
                    val parent = path.getOrNull(path.size - 2)

                    when (event.name) {
                        "name" -> when (parent) {
                            "metadata" -> metadataName = value.takeIf { it.isNotEmpty() }
                            "trk" -> trackName = value.takeIf { it.isNotEmpty() }
                        }
                        "desc" -> if (note == null) note = value.takeIf { it.isNotEmpty() }
                        "type" -> if (parent == "trk") {
                            activityType = ActivityTypeCodec.parse(value) ?: activityType
                        }
                        "ele" -> point?.altitudeMeters = parseDecimal(value)
                        "time" -> point?.let { it.timestampEpochMillis = Iso8601.parse(value) }
                        "speed" -> point?.speedMetersPerSecond = parseDecimal(value)?.toFloat()
                        "accuracy" -> point?.accuracyMeters = parseDecimal(value)?.toFloat()
                        "stop" -> point?.isStop = value.isTruthy()
                        "trkpt" -> {
                            point?.toPoint()?.let(trackPoints::add)
                            point = null
                        }
                        "rtept" -> {
                            point?.toPoint()?.let(routePoints::add)
                            point = null
                        }
                    }

                    if (path.isNotEmpty()) path.removeLast()
                }

                XmlEvent.EndDocument -> break
            }
        }

        if (!sawRoot) throw TrackFileParseException(TrackFileError.UNSUPPORTED_FORMAT)

        return TrackDocument(
            name = trackName ?: metadataName,
            activityType = activityType ?: ActivityType.OTHER,
            points = trackPoints.ifEmpty { routePoints },
            note = note,
        )
    }

    /** Точка без координат бесполезна: такой `<trkpt>` пропускается целиком. */
    private fun readPointAttributes(attributes: Map<String, String>): MutablePoint? {
        val latitude = parseDecimal(attributes["lat"]) ?: return null
        val longitude = parseDecimal(attributes["lon"]) ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return MutablePoint(latitude = latitude, longitude = longitude)
    }
}

internal class MutablePoint(
    val latitude: Double,
    val longitude: Double,
    var timestampEpochMillis: Long? = null,
    var altitudeMeters: Double? = null,
    var speedMetersPerSecond: Float? = null,
    var accuracyMeters: Float? = null,
    var isStop: Boolean = false,
) {
    fun toPoint(): TrackDocumentPoint = TrackDocumentPoint(
        latitude = latitude,
        longitude = longitude,
        timestampEpochMillis = timestampEpochMillis,
        altitudeMeters = altitudeMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        accuracyMeters = accuracyMeters,
        isStop = isStop,
    )
}

internal fun String.isTruthy(): Boolean = equals("true", ignoreCase = true) || this == "1"
