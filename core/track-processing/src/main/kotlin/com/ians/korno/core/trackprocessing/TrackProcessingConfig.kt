package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.ActivityType
import com.ians.korno.core.domain.model.AutoPauseSensitivity
import kotlin.math.max

data class TrackProcessingConfig(
    val maximumSpeedMetersPerSecond: Double,
    val maximumAccelerationMetersPerSecondSquared: Double,
    val smoothingRadius: Int = 1,
    val stopWindowMillis: Long = 15_000L,
    val minimumStopDurationMillis: Long = 10_000L,
    val stopRadiusMeters: Double = 12.0,
    val stopSpeedMetersPerSecond: Double = 0.8,
    val resumeSpeedMetersPerSecond: Double = 1.2,
    val elevationNoiseMeters: Double = 3.0,
) {
    init {
        require(maximumSpeedMetersPerSecond > 0.0)
        require(maximumAccelerationMetersPerSecondSquared > 0.0)
        require(smoothingRadius >= 0)
        require(stopWindowMillis >= minimumStopDurationMillis)
        require(minimumStopDurationMillis > 0L)
        require(stopRadiusMeters >= 0.0)
        require(stopSpeedMetersPerSecond >= 0.0)
        require(resumeSpeedMetersPerSecond >= stopSpeedMetersPerSecond)
        require(elevationNoiseMeters >= 0.0)
    }

    companion object {
        fun forActivityType(activityType: ActivityType): TrackProcessingConfig = when (activityType) {
            ActivityType.WALKING, ActivityType.HIKING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 5.5,
                maximumAccelerationMetersPerSecondSquared = 4.0,
                stopSpeedMetersPerSecond = 0.25,
                resumeSpeedMetersPerSecond = 0.65,
            )
            ActivityType.RUNNING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 10.0,
                maximumAccelerationMetersPerSecondSquared = 6.0,
                stopSpeedMetersPerSecond = 0.45,
                resumeSpeedMetersPerSecond = 1.0,
            )
            ActivityType.CYCLING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 25.0,
                maximumAccelerationMetersPerSecondSquared = 10.0,
                stopSpeedMetersPerSecond = 0.6,
                resumeSpeedMetersPerSecond = 1.5,
            )
            ActivityType.DRIVING -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 70.0,
                maximumAccelerationMetersPerSecondSquared = 15.0,
                stopSpeedMetersPerSecond = 0.8,
                resumeSpeedMetersPerSecond = 2.0,
            )
            ActivityType.OTHER -> TrackProcessingConfig(
                maximumSpeedMetersPerSecond = 55.0,
                maximumAccelerationMetersPerSecondSquared = 12.0,
                stopSpeedMetersPerSecond = 0.45,
                resumeSpeedMetersPerSecond = 1.0,
            )
        }
    }
}

fun TrackProcessingConfig.withAutoPauseSensitivity(
    sensitivity: AutoPauseSensitivity,
): TrackProcessingConfig = when (sensitivity) {
    AutoPauseSensitivity.LOW -> copy(
        stopWindowMillis = 25_000L,
        minimumStopDurationMillis = 20_000L,
        stopRadiusMeters = 8.0,
        stopSpeedMetersPerSecond = stopSpeedMetersPerSecond * 0.7,
        resumeSpeedMetersPerSecond = resumeSpeedMetersPerSecond * 1.2,
    )
    AutoPauseSensitivity.MEDIUM -> this
    AutoPauseSensitivity.HIGH -> copy(
        stopWindowMillis = 10_000L,
        minimumStopDurationMillis = 5_000L,
        stopRadiusMeters = 16.0,
        stopSpeedMetersPerSecond = stopSpeedMetersPerSecond * 1.3,
        resumeSpeedMetersPerSecond = max(
            stopSpeedMetersPerSecond * 1.3,
            resumeSpeedMetersPerSecond * 0.85,
        ),
    )
}
