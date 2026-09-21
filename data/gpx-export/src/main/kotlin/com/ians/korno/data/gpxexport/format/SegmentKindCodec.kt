package com.ians.korno.data.gpxexport.format

import com.ians.korno.core.domain.model.SegmentKind

/**
 * Соответствие видов движения и того, как их называют форматы обмена.
 *
 * В GPX `<type>` — свободный текст, поэтому пишем своё имя, а читаем ещё и то,
 * что кладут туда Strava и Garmin. В TCX атрибут `Sport` схемой ограничен тремя
 * значениями, так что точный вид дублируется расширением Korno — иначе хайкинг
 * вернулся бы из собственного же файла как «Other».
 */
internal object SegmentKindCodec {

    fun toGpxType(kind: SegmentKind): String = kind.name.lowercase()

    fun toTcxSport(kind: SegmentKind): String = when (kind) {
        SegmentKind.RUNNING -> "Running"
        SegmentKind.CYCLING -> "Biking"
        else -> "Other"
    }

    fun parse(value: String?): SegmentKind? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        SegmentKind.entries.firstOrNull { it.name.lowercase() == normalized }?.let { return it }
        return when (normalized) {
            "run", "jogging", "trail_running", "trailrun" -> SegmentKind.RUNNING
            "ride", "bike", "biking", "cycle", "ebikeride", "virtualride" -> SegmentKind.CYCLING
            "walk", "walking", "foot" -> SegmentKind.ON_FOOT
            "hike", "hiking", "trekking" -> SegmentKind.HIKING
            "drive", "car", "driving", "motor", "vehicle" -> SegmentKind.VEHICLE
            else -> null
        }
    }
}
