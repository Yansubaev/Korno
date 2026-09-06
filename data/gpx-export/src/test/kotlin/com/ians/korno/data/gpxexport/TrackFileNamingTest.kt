package com.ians.korno.data.gpxexport

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.TrackFileFormat
import org.junit.jupiter.api.Test

class TrackFileNamingTest {

    @Test
    fun `file name starts with the activity date`() {
        val name = TrackFileNaming.fileName(activity(title = "Morning run"), TrackFileFormat.GPX)

        assertThat(name).isEqualTo("2025-09-05-morning-run.gpx")
    }

    @Test
    fun `punctuation collapses into single separators`() {
        val name = TrackFileNaming.fileName(
            activity(title = "  Morning   run: 10k!! "),
            TrackFileFormat.TCX,
        )

        assertThat(name).isEqualTo("2025-09-05-morning-run-10k.tcx")
    }

    @Test
    fun `activity type replaces a missing or unusable title`() {
        assertThat(TrackFileNaming.fileName(activity(title = null), TrackFileFormat.GPX))
            .isEqualTo("2025-09-05-cycling.gpx")
        assertThat(TrackFileNaming.fileName(activity(title = "!!!"), TrackFileFormat.GPX))
            .isEqualTo("2025-09-05-cycling.gpx")
    }

    @Test
    fun `non latin titles keep their letters`() {
        assertThat(TrackFileNaming.fileName(activity(title = "Утренний бег"), TrackFileFormat.GPX))
            .isEqualTo("2025-09-05-утренний-бег.gpx")
    }

    @Test
    fun `bulk export appends the activity id to keep names unique`() {
        val name = TrackFileNaming.uniqueFileName(activity(title = "Morning run"), TrackFileFormat.GPX)

        assertThat(name).isEqualTo("2025-09-05-morning-run-7.gpx")
    }

    private fun activity(title: String?) = Activity(
        id = 7L,
        startTimeEpochMillis = 1_757_052_000_000L,
        activityType = ActivityType.CYCLING,
        title = title,
    )
}
