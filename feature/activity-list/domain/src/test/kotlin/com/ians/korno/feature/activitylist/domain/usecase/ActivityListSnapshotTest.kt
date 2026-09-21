package com.ians.korno.feature.activitylist.domain.usecase

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class ActivityListSnapshotTest {

    /** Авто-прогулка с перегоном на автобусе и ручной паузой между ними. */
    private val mixed = activity(id = 1L, distance = 5_000.0, movingTime = 40 * MINUTE)
    private val mixedSegments = listOf(
        segment(1L, SegmentKind.ON_FOOT, distance = 3_000.0, movingTime = 30 * MINUTE),
        segment(1L, SegmentKind.PAUSE, distance = 0.0, movingTime = 0L),
        segment(1L, SegmentKind.VEHICLE, distance = 2_000.0, movingTime = 10 * MINUTE),
    )

    /** Хайкинг: вид поставил человек, внутри — пеший участок и переезд. */
    private val hike = activity(id = 2L, distance = 12_000.0, movingTime = 3 * HOUR)
    private val hikeSegments = listOf(
        segment(2L, SegmentKind.HIKING, distance = 10_000.0, movingTime = 2 * HOUR),
        segment(2L, SegmentKind.VEHICLE, distance = 2_000.0, movingTime = HOUR),
    )

    /** Авто-запись, где детектор не решил ничего. */
    private val undetected = activity(id = 3L, distance = 800.0, movingTime = 15 * MINUTE)
    private val undetectedSegments = listOf(
        segment(3L, SegmentKind.UNKNOWN, distance = 800.0, movingTime = 15 * MINUTE),
    )

    private val snapshot = ActivityListSnapshot(
        activities = listOf(mixed, hike, undetected),
        segments = mixedSegments + hikeSegments + undetectedSegments,
    )

    @Test
    fun `activity falls under every kind it contains`() {
        // Смешанная запись — под каждым своим видом, любой длины.
        assertThat(snapshot.select(SegmentKind.ON_FOOT, ALL).activities.map(Activity::id)).containsExactly(1L)
        assertThat(snapshot.select(SegmentKind.HIKING, ALL).activities.map(Activity::id)).containsExactly(2L)
        assertThat(snapshot.select(SegmentKind.VEHICLE, ALL).activities.map(Activity::id)).containsExactly(1L, 2L)
        // Вида, которого в записи нет, — нет и чипа.
        assertThat(snapshot.select(SegmentKind.RUNNING, ALL).activities).isEqualTo(emptyList<Activity>())
    }

    @Test
    fun `hiking is a chip of its own, apart from on foot`() {
        // Хайкинг — отдельный вид, а не разновидность «Пешком»: под пеший чип запись 2
        // не попадает. Её переезд при этом виден под «Транспортом», как у любой смешанной.
        assertThat(snapshot.select(SegmentKind.ON_FOOT, ALL).activities.map(Activity::id)).doesNotContain(2L)
        assertThat(snapshot.select(SegmentKind.VEHICLE, ALL).activities.map(Activity::id)).contains(2L)
    }

    @Test
    fun `dominant kind is the face of the activity`() {
        // Три часа записи, из них два — тропа: лицо записи — хайкинг, а не автобус.
        assertThat(snapshot.dominantKindOf(hike)).isEqualTo(SegmentKind.HIKING)
        assertThat(snapshot.dominantKindOf(mixed)).isEqualTo(SegmentKind.ON_FOOT)
        // Судить не по чему: ни одного сегмента движения.
        assertThat(snapshot.dominantKindOf(undetected)).isEqualTo(SegmentKind.UNKNOWN)
    }

    @Test
    fun `undetected activity falls under unknown`() {
        val other = snapshot.select(SegmentKind.UNKNOWN, ALL)

        assertThat(other.activities.map(Activity::id)).containsExactly(3L)
        assertThat(other.distanceMeters).isEqualTo(800.0)
    }

    @Test
    fun `chip totals count only segments of that kind`() {
        // Пеший сегмент смешанной записи, без автобуса внутри неё.
        val onFoot = snapshot.select(SegmentKind.ON_FOOT, ALL)
        assertThat(onFoot.distanceMeters).isEqualTo(3_000.0)
        assertThat(onFoot.movingTimeMillis).isEqualTo(30 * MINUTE)

        val hiking = snapshot.select(SegmentKind.HIKING, ALL)
        assertThat(hiking.distanceMeters).isEqualTo(10_000.0)
        assertThat(hiking.movingTimeMillis).isEqualTo(2 * HOUR)

        val vehicle = snapshot.select(SegmentKind.VEHICLE, ALL)
        assertThat(vehicle.distanceMeters).isEqualTo(4_000.0)
        assertThat(vehicle.movingTimeMillis).isEqualTo(HOUR + 10 * MINUTE)
    }

    @Test
    fun `all counts activity rows whole`() {
        val all = snapshot.select(kind = null, include = ALL)

        assertThat(all.activities.map(Activity::id)).containsExactly(1L, 2L, 3L)
        assertThat(all.distanceMeters).isEqualTo(17_800.0)
        assertThat(all.movingTimeMillis).isEqualTo(40 * MINUTE + 3 * HOUR + 15 * MINUTE)
    }

    @Test
    fun `segment without moving time falls back to its span`() {
        val legacy = activity(id = 4L, distance = 1_000.0, movingTime = 10 * MINUTE)
        val snapshot = ActivityListSnapshot(
            activities = listOf(legacy),
            segments = listOf(
                segment(4L, SegmentKind.CYCLING, distance = 1_000.0, movingTime = null, span = 12 * MINUTE),
            ),
        )

        assertThat(snapshot.select(SegmentKind.CYCLING, ALL).movingTimeMillis).isEqualTo(12 * MINUTE)
    }

    @Test
    fun `chips cover the whole database and keep unknown last`() {
        assertThat(snapshot.chipKinds).containsExactly(
            SegmentKind.ON_FOOT,
            SegmentKind.HIKING,
            SegmentKind.VEHICLE,
            SegmentKind.UNKNOWN,
        )
    }

    @Test
    fun `period and search filter whole activities`() {
        val selection = snapshot.select(SegmentKind.ON_FOOT) { it.id == 1L }

        assertThat(selection.activities.map(Activity::id)).containsExactly(1L)
        // Дистанция — только пеших сегментов попавшей записи, без второй и без её автобуса.
        assertThat(selection.distanceMeters).isEqualTo(3_000.0)
    }

    private fun activity(
        id: Long,
        distance: Double,
        movingTime: Long,
    ) = Activity(
        id = id,
        startTimeEpochMillis = 0L,
        endTimeEpochMillis = movingTime,
        distanceMeters = distance,
        movingTimeMillis = movingTime,
    )

    private fun segment(
        activityId: Long,
        kind: SegmentKind,
        distance: Double,
        movingTime: Long?,
        span: Long = movingTime ?: 0L,
    ) = ActivitySegment(
        activityId = activityId,
        kind = kind,
        startTimeEpochMillis = 0L,
        endTimeEpochMillis = span,
        distanceMeters = distance,
        movingTimeMillis = movingTime,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val ALL: (Activity) -> Boolean = { true }
    }
}
