package com.ians.korno.data.locationtracking.location

import com.ians.korno.core.domain.model.GpsAccuracy

/**
 * Интервал здесь один, без отдельной нижней границы.
 *
 * Прежде их было две, и просили они разное: 4000 при нижней границе 2000. Провайдер
 * выдавал по нижней — то есть ровно вдвое чаще, чем сказано в настройке, — и то же
 * повторялось во всех режимах. Разница между «как часто мне нужно» и «быстрее не надо»
 * тут ничего не выражала, кроме расхождения обещанного с происходящим.
 */
data class LocationUpdateConfig(
    val intervalMillis: Long,
    val accuracy: GpsAccuracy,
)
