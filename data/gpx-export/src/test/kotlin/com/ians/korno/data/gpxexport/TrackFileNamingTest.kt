package com.ians.korno.data.gpxexport

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.SegmentKind
import com.ians.korno.core.domain.model.TrackFileFormat
import org.junit.jupiter.api.Test

class TrackFileNamingTest {

    @Test
    fun `file name starts with the activity date`() {
        val name = TrackFileNaming.fileName(activity(title = "Morning run"), TrackFileFormat.GPX, KIND)

        assertThat(name).isEqualTo("2025-09-05-morning-run.gpx")
    }

    @Test
    fun `punctuation collapses into single separators`() {
        val name = TrackFileNaming.fileName(
            activity(title = "  Morning   run: 10k!! "),
            TrackFileFormat.TCX,
            KIND,
        )

        assertThat(name).isEqualTo("2025-09-05-morning-run-10k.tcx")
    }

    @Test
    fun `segment kind replaces a missing or unusable title`() {
        assertThat(TrackFileNaming.fileName(activity(title = null), TrackFileFormat.GPX, KIND))
            .isEqualTo("2025-09-05-cycling.gpx")
        assertThat(TrackFileNaming.fileName(activity(title = "!!!"), TrackFileFormat.GPX, KIND))
            .isEqualTo("2025-09-05-cycling.gpx")
    }

    @Test
    fun `non latin titles keep their letters`() {
        assertThat(TrackFileNaming.fileName(activity(title = "Утренний бег"), TrackFileFormat.GPX, KIND))
            .isEqualTo("2025-09-05-утренний-бег.gpx")
    }

    @Test
    fun `bulk export appends the activity id to keep names unique`() {
        val name = TrackFileNaming.uniqueFileName(activity(title = "Morning run"), TrackFileFormat.GPX, KIND)

        assertThat(name).isEqualTo("2025-09-05-morning-run-7.gpx")
    }

    private fun activity(title: String?) = Activity(
        id = 7L,
        startTimeEpochMillis = 1_757_052_000_000L,
        title = title,
    )

    private companion object {
        val KIND = SegmentKind.CYCLING
    }
}
