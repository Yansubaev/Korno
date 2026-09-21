package com.ians.korno.feature.activitylist.domain.usecase

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.ians.korno.core.domain.model.Activity
import com.ians.korno.core.domain.model.ActivitySegment
import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.SegmentKind
import org.junit.jupiter.api.Test

class ActivityListSnapshotTest {

    /** Авто-прогулка с перегоном на автобусе и ручной паузой между ними. */
    private val mixed = activity(id = 1L, type = ActivityType.WALKING, distance = 5_000.0, movingTime = 40 * MINUTE)
    private val mixedSegments = listOf(
        segment(1L, SegmentKind.ON_FOOT, distance = 3_000.0, movingTime = 30 * MINUTE),
        segment(1L, SegmentKind.PAUSE, distance = 0.0, movingTime = 0L),
        segment(1L, SegmentKind.VEHICLE, distance = 2_000.0, movingTime = 10 * MINUTE),
    )

    /** Хайкинг: тип выбрал человек, внутри — пеший участок и переезд. */
    private val hike = activity(
        id = 2L,
        type = ActivityType.HIKING,
        distance = 12_000.0,
        movingTime = 3 * HOUR,
        chosenByUser = true,
    )
    private val hikeSegments = listOf(
        segment(2L, SegmentKind.ON_FOOT, distance = 10_000.0, movingTime = 2 * HOUR),
        segment(2L, SegmentKind.VEHICLE, distance = 2_000.0, movingTime = HOUR),
    )

    /** Авто-запись, где детектор не решил ничего. */
    private val undetected = activity(id = 3L, type = ActivityType.OTHER, distance = 800.0, movingTime = 15 * MINUTE)
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
        assertThat(snapshot.select(SegmentKind.ON_FOOT, ALL).activities.map(Activity::id)).containsExactly(1L, 2L)
        assertThat(snapshot.select(SegmentKind.VEHICLE, ALL).activities.map(Activity::id)).containsExactly(1L, 2L)
        // Вида, которого в записи нет, — нет и чипа.
        assertThat(snapshot.select(SegmentKind.RUNNING, ALL).activities).isEqualTo(emptyList<Activity>())
    }

    @Test
    fun `type of the activity does not affect the chip`() {
        // У записи 2 тип выбрал человек («хайкинг»), но фильтр смотрит только на сегменты:
        // её переезд виден под «Транспортом», а сама она — под «Пешком».
        assertThat(snapshot.select(SegmentKind.VEHICLE, ALL).activities.map(Activity::id)).contains(2L)
        // И запись с типом «другое» под чипы своих видов не попадает, если таких сегментов нет.
        assertThat(snapshot.select(SegmentKind.UNKNOWN, ALL).activities.map(Activity::id)).containsExactly(3L)
    }

    @Test
    fun `undetected activity falls under unknown`() {
        val other = snapshot.select(SegmentKind.UNKNOWN, ALL)

        assertThat(other.activities.map(Activity::id)).containsExactly(3L)
        assertThat(other.distanceMeters).isEqualTo(800.0)
    }

    @Test
    fun `chip totals count only segments of that kind`() {
        // Пешие сегменты обеих записей, без автобуса внутри них.
        val onFoot = snapshot.select(SegmentKind.ON_FOOT, ALL)
        assertThat(onFoot.distanceMeters).isEqualTo(13_000.0)
        assertThat(onFoot.movingTimeMillis).isEqualTo(2 * HOUR + 30 * MINUTE)

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
        val legacy = activity(id = 4L, type = ActivityType.CYCLING, distance = 1_000.0, movingTime = 10 * MINUTE)
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
        type: ActivityType,
        distance: Double,
        movingTime: Long,
        chosenByUser: Boolean = false,
    ) = Activity(
        id = id,
        startTimeEpochMillis = 0L,
        endTimeEpochMillis = movingTime,
        activityType = type,
        distanceMeters = distance,
        movingTimeMillis = movingTime,
        isAutoMode = !chosenByUser,
        isTypeChosenByUser = chosenByUser,
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
