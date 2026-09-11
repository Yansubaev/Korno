package com.ians.korno.core.domain.model

enum class ActivityType {
    WALKING,
    RUNNING,
    CYCLING,
    DRIVING,
    HIKING,
    OTHER,
}

/** Активности на своих ногах, где шагомер что-то значит: велосипед и машина шагов не считают. */
val ActivityType.countsSteps: Boolean
    get() = this == ActivityType.WALKING || this == ActivityType.RUNNING || this == ActivityType.HIKING
