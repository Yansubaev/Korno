package com.ians.korno.data.locationtracking.location

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampEpochMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    /**
     * Провайдер фикса: `gps`, `fused`, `network`. Без него отличить настоящий GNSS-фикс
     * от сетевого можно было только косвенно — по отсутствию скорости, — а именно это
     * различие и объясняет и стометровую погрешность, и дыры в треке.
     */
    val provider: String? = null,
    /**
     * Сколько миллисекунд фикс пролежал до того, как дошёл до приложения. Fused Location
     * Provider умеет отдавать кэш, и по времени получения этого не видно.
     */
    val ageMillis: Long? = null,
    /**
     * Точность по вертикали и точность скорости — есть не у всех провайдеров, но когда
     * есть, сразу показывают, стоит ли верить набору высоты и доплеровской скорости.
     */
    val verticalAccuracyMeters: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    /** Число спутников, участвовавших в решении; у сетевых фиксов отсутствует. */
    val satelliteCount: Int? = null,
)
