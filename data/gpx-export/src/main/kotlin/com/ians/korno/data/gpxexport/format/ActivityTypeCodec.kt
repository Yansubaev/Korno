package com.ians.korno.data.gpxexport.format

import com.ians.korno.core.domain.model.ActivityType

/**
 * Соответствие типов активности и того, как их называют форматы обмена.
 *
 * В GPX `<type>` — свободный текст, поэтому пишем своё имя, а читаем ещё и то,
 * что кладут туда Strava и Garmin. В TCX атрибут `Sport` схемой ограничен тремя
 * значениями, так что точный тип дублируется расширением Korno — иначе прогулка
 * вернулась бы из собственного же файла как «Other».
 */
internal object ActivityTypeCodec {

    fun toGpxType(activityType: ActivityType): String = activityType.name.lowercase()

    fun toTcxSport(activityType: ActivityType): String = when (activityType) {
        ActivityType.RUNNING -> "Running"
        ActivityType.CYCLING -> "Biking"
        else -> "Other"
    }

    fun parse(value: String?): ActivityType? {
        val normalized = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        ActivityType.entries.firstOrNull { it.name.lowercase() == normalized }?.let { return it }
        return when (normalized) {
            "run", "jogging", "trail_running", "trailrun" -> ActivityType.RUNNING
            "ride", "bike", "biking", "cycle", "ebikeride", "virtualride" -> ActivityType.CYCLING
            "walk", "on_foot", "foot" -> ActivityType.WALKING
            "hike", "hiking", "trekking" -> ActivityType.HIKING
            "drive", "car", "driving", "motor", "in_vehicle" -> ActivityType.DRIVING
            else -> null
        }
    }
}
