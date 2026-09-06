package com.ians.korno.core.data.local

/**
 * Прореживает маршрут до [maxPoints] точек с равномерным шагом.
 *
 * Для миниатюры важна форма, а не подробности: шаг по индексу даёт узнаваемый силуэт
 * дешевле любого упрощения по геометрии и не требует тригонометрии на каждый ряд списка.
 * Первая и последняя точки сохраняются всегда — иначе трек выглядел бы обрезанным.
 */
internal fun <T> List<T>.downsampledTo(maxPoints: Int): List<T> {
    require(maxPoints >= 2) { "Preview needs at least two points" }
    if (size <= maxPoints) return this

    val step = (size - 1).toDouble() / (maxPoints - 1)
    return List(maxPoints) { index ->
        this[(index * step).toInt().coerceAtMost(lastIndex)]
    }
}
