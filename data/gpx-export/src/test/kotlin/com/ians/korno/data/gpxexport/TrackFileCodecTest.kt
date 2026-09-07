package com.ians.korno.data.gpxexport

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import com.ians.korno.core.domain.error.Result
import com.ians.korno.core.domain.error.TrackFileError
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.TrackFileFormat
import com.ians.korno.data.gpxexport.model.TrackDocument
import com.ians.korno.data.gpxexport.model.TrackDocumentPoint
import com.ians.korno.data.gpxexport.model.TrackDocumentSummary
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class TrackFileCodecTest {

    private val codec = TrackFileCodec()

    @ParameterizedTest
    @EnumSource(TrackFileFormat::class)
    fun `round trip preserves track geometry and metadata`(format: TrackFileFormat) {
        val document = sampleDocument()

        val parsed = codec.parse(codec.write(document, format)).requireSuccess()

        assertThat(parsed.name).isEqualTo(document.name)
        assertThat(parsed.activityType).isEqualTo(document.activityType)
        assertThat(parsed.note).isEqualTo(document.note)
        assertThat(parsed.points).hasSize(document.points.size)
        parsed.points.forEachIndexed { index, point ->
            val original = document.points[index]
            assertThat(point.latitude).isCloseTo(original.latitude, COORDINATE_TOLERANCE)
            assertThat(point.longitude).isCloseTo(original.longitude, COORDINATE_TOLERANCE)
            assertThat(point.timestampEpochMillis).isEqualTo(original.timestampEpochMillis)
            assertThat(point.isStop).isEqualTo(original.isStop)
            if (original.altitudeMeters == null) {
                assertThat(point.altitudeMeters).isNull()
            } else {
                assertThat(point.altitudeMeters!!).isCloseTo(original.altitudeMeters!!, 0.01)
            }
            if (original.speedMetersPerSecond == null) {
                assertThat(point.speedMetersPerSecond).isNull()
            } else {
                assertThat(point.speedMetersPerSecond!!.toDouble())
                    .isCloseTo(original.speedMetersPerSecond!!.toDouble(), 0.001)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TrackFileFormat::class)
    fun `round trip keeps activity types the format cannot express natively`(format: TrackFileFormat) {
        // TCX знает только Running, Biking и Other, поэтому прогулка проверяется отдельно.
        val document = sampleDocument().copy(activityType = ActivityType.WALKING)

        val parsed = codec.parse(codec.write(document, format)).requireSuccess()

        assertThat(parsed.activityType).isEqualTo(ActivityType.WALKING)
    }

    @ParameterizedTest
    @EnumSource(TrackFileFormat::class)
    fun `writer escapes special characters in user supplied names`(format: TrackFileFormat) {
        val document = sampleDocument().copy(name = "Run & <fun> \"5k\"")

        val content = codec.write(document, format)

        assertThat(content).contains("&amp;")
        assertThat(content).doesNotContain("<fun>")
        assertThat(codec.parse(content).requireSuccess().name).isEqualTo("Run & <fun> \"5k\"")
    }

    @ParameterizedTest
    @EnumSource(TrackFileFormat::class)
    fun `format is detected from content without a file name`(format: TrackFileFormat) {
        val content = codec.write(sampleDocument(), format)

        assertThat(codec.detectFormat(content)).isEqualTo(format)
    }

    @Test
    fun `format falls back to the file extension when the root element is unknown`() {
        assertThat(codec.detectFormat(content = "<foo/>", fileName = "track.tcx"))
            .isEqualTo(TrackFileFormat.TCX)
        assertThat(codec.detectFormat(content = "<foo/>", fileName = "track.txt")).isNull()
    }

    @Test
    fun `parsing a file truncated mid tag reports malformed content`() {
        val truncated = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg><trkpt lat="55.75" lon="37.6
        """.trimIndent()

        assertThat(codec.parse(truncated).requireError()).isEqualTo(TrackFileError.MALFORMED_CONTENT)
    }

    @Test
    fun `parsing a file with an unquoted attribute reports malformed content`() {
        val broken = """
            <gpx version=1.1 xmlns="http://www.topografix.com/GPX/1/1"><trk/></gpx>
        """.trimIndent()

        assertThat(codec.parse(broken).requireError()).isEqualTo(TrackFileError.MALFORMED_CONTENT)
    }

    @Test
    fun `parsing an unrelated document reports an unsupported format`() {
        val result = codec.parse("<html><body>not a track</body></html>")

        assertThat(result.requireError()).isEqualTo(TrackFileError.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `parsing an empty string reports an unsupported format`() {
        assertThat(codec.parse("").requireError()).isEqualTo(TrackFileError.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `parsing a valid file without points reports no track points`() {
        val emptyGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="Korno" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>Empty</name><trkseg/></trk>
            </gpx>
        """.trimIndent()

        assertThat(codec.parse(emptyGpx).requireError()).isEqualTo(TrackFileError.NO_TRACK_POINTS)
    }

    @Test
    fun `points without coordinates are skipped instead of failing the whole file`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="55.75" lon="37.61"><time>2025-09-05T06:00:00Z</time></trkpt>
                <trkpt lon="37.62"><time>2025-09-05T06:00:01Z</time></trkpt>
                <trkpt lat="broken" lon="37.63"/>
                <trkpt lat="91.0" lon="37.64"/>
                <trkpt lat="55.76" lon="37.65"><time>2025-09-05T06:00:04Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val parsed = codec.parse(gpx).requireSuccess()

        assertThat(parsed.points).hasSize(2)
    }

    @Test
    fun `foreign gpx without korno extensions is read with defaults`() {
        val gpx = """
            <?xml version="1.0"?>
            <!-- exported elsewhere -->
            <gpx:gpx version="1.1" xmlns:gpx="http://www.topografix.com/GPX/1/1">
              <gpx:metadata><gpx:name>Trail day</gpx:name></gpx:metadata>
              <gpx:trk>
                <gpx:type>hiking</gpx:type>
                <gpx:trkseg>
                  <gpx:trkpt lat="55.75" lon="37.61">
                    <gpx:ele>142</gpx:ele>
                    <gpx:time>2025-09-05T06:00:00.500Z</gpx:time>
                  </gpx:trkpt>
                </gpx:trkseg>
              </gpx:trk>
            </gpx:gpx>
        """.trimIndent()

        val parsed = codec.parse(gpx).requireSuccess()

        assertThat(parsed.name).isEqualTo("Trail day")
        assertThat(parsed.activityType).isEqualTo(ActivityType.HIKING)
        assertThat(parsed.points).hasSize(1)
        assertThat(parsed.points.first().isStop).isFalse()
        assertThat(parsed.points.first().speedMetersPerSecond).isNull()
        assertThat(parsed.points.first().timestampEpochMillis).isNotNull()
    }

    @ParameterizedTest
    @EnumSource(TrackFileFormat::class)
    fun `own file is recognised as a korno export`(format: TrackFileFormat) {
        val parsed = codec.parse(codec.write(sampleDocument(), format)).requireSuccess()

        assertThat(parsed.isKornoExport).isTrue()
    }

    @Test
    fun `foreign gpx is not mistaken for a korno export`() {
        val gpx = """
            <gpx version="1.1" creator="StravaGPX" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg><trkpt lat="55.75" lon="37.61"/></trkseg></trk>
            </gpx>
        """.trimIndent()

        assertThat(codec.parse(gpx).requireSuccess().isKornoExport).isFalse()
    }

    @Test
    fun `foreign tcx is not mistaken for a korno export`() {
        val tcx = """
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
              <Activities><Activity Sport="Running"><Lap><Track>
                <Trackpoint><Position>
                  <LatitudeDegrees>55.75</LatitudeDegrees>
                  <LongitudeDegrees>37.61</LongitudeDegrees>
                </Position></Trackpoint>
              </Track></Lap></Activity></Activities>
            </TrainingCenterDatabase>
        """.trimIndent()

        assertThat(codec.parse(tcx).requireSuccess().isKornoExport).isFalse()
    }

    @Test
    fun `gpx route points are used when the file has no track`() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte><rtept lat="55.75" lon="37.61"/><rtept lat="55.76" lon="37.62"/></rte>
            </gpx>
        """.trimIndent()

        assertThat(codec.parse(gpx).requireSuccess().points).hasSize(2)
    }

    @Test
    fun `garmin trackpoint speed extension is read from tcx`() {
        val tcx = """
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
              <Activities><Activity Sport="Biking">
                <Id>2025-09-05T06:00:00Z</Id>
                <Lap StartTime="2025-09-05T06:00:00Z"><Track>
                  <Trackpoint>
                    <Time>2025-09-05T06:00:00Z</Time>
                    <Position>
                      <LatitudeDegrees>55.75</LatitudeDegrees>
                      <LongitudeDegrees>37.61</LongitudeDegrees>
                    </Position>
                    <Extensions><TPX xmlns="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
                      <Speed>7.5</Speed>
                    </TPX></Extensions>
                  </Trackpoint>
                </Track></Lap>
              </Activity></Activities>
            </TrainingCenterDatabase>
        """.trimIndent()

        val parsed = codec.parse(tcx).requireSuccess()

        assertThat(parsed.activityType).isEqualTo(ActivityType.CYCLING)
        assertThat(parsed.points.single().speedMetersPerSecond!!.toDouble()).isCloseTo(7.5, 0.001)
    }

    @Test
    fun `tcx trackpoints without a position are skipped`() {
        val tcx = """
            <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
              <Activities><Activity Sport="Running"><Lap><Track>
                <Trackpoint><Time>2025-09-05T06:00:00Z</Time></Trackpoint>
                <Trackpoint>
                  <Time>2025-09-05T06:00:01Z</Time>
                  <Position>
                    <LatitudeDegrees>55.75</LatitudeDegrees>
                    <LongitudeDegrees>37.61</LongitudeDegrees>
                  </Position>
                </Trackpoint>
              </Track></Lap></Activity></Activities>
            </TrainingCenterDatabase>
        """.trimIndent()

        assertThat(codec.parse(tcx).requireSuccess().points).hasSize(1)
    }

    @Test
    fun `explicit format wins over content sniffing`() {
        val gpx = codec.write(sampleDocument(), TrackFileFormat.GPX)

        val result = codec.parse(gpx, format = TrackFileFormat.TCX)

        // Корневого TrainingCenterDatabase в GPX нет — разбор честно сообщает о формате.
        assertThat(result.requireError()).isEqualTo(TrackFileError.UNSUPPORTED_FORMAT)
    }

    @Test
    fun `tcx lap totals come from the exported summary`() {
        val content = codec.write(sampleDocument(), TrackFileFormat.TCX)

        assertThat(content).contains("<DistanceMeters>5180.00</DistanceMeters>")
        assertThat(content).contains("<TotalTimeSeconds>1780.0</TotalTimeSeconds>")
    }

    @Test
    fun `written files start with an xml declaration`() {
        TrackFileFormat.entries.forEach { format ->
            assertThat(codec.write(sampleDocument(), format))
                .contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        }
    }

    @Test
    fun `huge coordinate precision survives within a centimetre`() {
        val document = sampleDocument().copy(
            points = listOf(
                TrackDocumentPoint(
                    latitude = -33.868_820_1,
                    longitude = 151.209_295_9,
                    timestampEpochMillis = 1_757_052_000_000L,
                ),
            ),
        )

        val parsed = codec.parse(codec.write(document, TrackFileFormat.GPX)).requireSuccess()

        assertThat(parsed.points.single().latitude).isBetween(-33.868_820_2, -33.868_820_0)
    }

    private fun sampleDocument(): TrackDocument = TrackDocument(
        name = "Morning run",
        activityType = ActivityType.RUNNING,
        note = "Felt good",
        points = listOf(
            TrackDocumentPoint(
                latitude = 55.751_244_0,
                longitude = 37.618_423_0,
                timestampEpochMillis = 1_757_052_000_000L,
                altitudeMeters = 142.25,
                speedMetersPerSecond = 2.813f,
                accuracyMeters = 4.2f,
            ),
            TrackDocumentPoint(
                latitude = 55.752_100_0,
                longitude = 37.619_900_0,
                timestampEpochMillis = 1_757_052_002_500L,
                altitudeMeters = 143.5,
                speedMetersPerSecond = 0f,
                accuracyMeters = 6.0f,
                isStop = true,
            ),
            TrackDocumentPoint(
                latitude = 55.753_000_0,
                longitude = 37.621_000_0,
                timestampEpochMillis = 1_757_052_010_000L,
            ),
        ),
        summary = TrackDocumentSummary(
            startTimeEpochMillis = 1_757_052_000_000L,
            elapsedTimeMillis = 1_780_000L,
            movingTimeMillis = 1_694_000L,
            distanceMeters = 5_180.0,
            averageSpeedMetersPerSecond = 3.057,
            maxSpeedMetersPerSecond = 4.1,
            elevationGainMeters = 42.0,
        ),
    )

    private companion object {
        const val COORDINATE_TOLERANCE = 1e-7
    }
}

internal fun <D> Result<D, TrackFileError>.requireSuccess(): D {
    assertThat(this).isInstanceOf(Result.Success::class)
    return (this as Result.Success).data
}

internal fun <D> Result<D, TrackFileError>.requireError(): TrackFileError {
    assertThat(this).isInstanceOf(Result.Error::class)
    return (this as Result.Error).error
}
