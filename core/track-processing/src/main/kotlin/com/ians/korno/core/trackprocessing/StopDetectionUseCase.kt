package com.ians.korno.core.trackprocessing

import com.ians.korno.core.domain.model.GeoPoint
import kotlin.math.max

/**
 * Вход в стоянку и выход из неё намеренно устроены по-разному.
 *
 * Объявить стоянку можно только по окну длиной [TrackProcessingConfig.stopWindowMillis]:
 * одна точка ничего не доказывает, а ложная автопауза рвёт трек. Снимать же стоянку по
 * окну нельзя: на автопаузе частота опроса GPS снижается, и окно нередко схлопывается до
 * единственной точки — сравнивать становится не с чем, и запись залипает в автопаузе до
 * ручного вмешательства. Поэтому выход считается по последнему доступному отрезку, каким
 * бы он ни был, — см. [resumePositions].
 */
class StopDetectionUseCase {
    operator fun invoke(
        points: List<GeoPoint>,
        config: TrackProcessingConfig,
    ): List<GeoPoint> {
        val result = points.map { it.copy(isStop = false) }.toMutableList()
        val validIndices = result.indices.filterNot { result[it].isOutlier }
        var windowStartPosition = 0
        var isStopped = false

        validIndices.forEachIndexed { endPosition, endIndex ->
            val end = result[endIndex]
            while (
                windowStartPosition < endPosition &&
                end.timestampEpochMillis - result[validIndices[windowStartPosition]].timestampEpochMillis >
                config.stopWindowMillis
            ) {
                windowStartPosition++
            }

            if (isStopped) {
                val resumeIndices = resumePositions(windowStartPosition, endPosition)
                    .map { position -> validIndices[position] }
                if (hasResumeEvidence(result, resumeIndices, config)) {
                    isStopped = false
                    // Окно начинается заново с точки возобновления. Иначе оставшиеся в нём
                    // точки стоянки на следующем же сэмпле снова дали бы hasStopEvidence,
                    // и статус дёргался бы туда-обратно в пределах пары сотен миллисекунд.
                    windowStartPosition = endPosition
                } else {
                    result[endIndex] = end.copy(isStop = true)
                }
                return@forEachIndexed
            }

            val windowIndices = validIndices.subList(windowStartPosition, endPosition + 1)
            val firstIndex = windowIndices.firstOrNull() ?: return@forEachIndexed
            val durationMillis = end.timestampEpochMillis - result[firstIndex].timestampEpochMillis
            if (durationMillis < config.minimumStopDurationMillis) return@forEachIndexed

            val reportedSpeeds = reportedSpeeds(result, windowIndices)
            val accuracyAllowance = accuracyAllowance(result, windowIndices, config)
            val maximumDisplacement = windowIndices.maxOf { index ->
                distanceMeters(result[firstIndex], result[index])
            }
            // Doppler-скорость устойчива к дрожанию координат, но некоторые провайдеры
            // отдают ноль при реальном движении. Пройденное расстояние — независимый
            // второй признак, поэтому берётся максимум из двух.
            val movementSpeed = max(
                reportedSpeeds.averageOrZero(),
                positionalSpeed(result[firstIndex], end, accuracyAllowance),
            )
            val hasStopEvidence =
                movementSpeed <= config.stopSpeedMetersPerSecond &&
                maximumDisplacement <= config.stopRadiusMeters + accuracyAllowance
            if (hasStopEvidence) {
                isStopped = true
                windowIndices.forEach { index -> result[index] = result[index].copy(isStop = true) }
            }
        }

        return result
    }

    /**
     * Позиции точек, по которым проверяется возобновление движения. Обычно это то же окно,
     * что и для входа в стоянку. Но если окно схлопнулось до текущей точки, берётся
     * предыдущая валидная точка, какой бы давней она ни была: пропущенный на пару секунд
     * позже выход из автопаузы куда безобиднее, чем автопауза, из которой нет выхода.
     */
    private fun resumePositions(windowStartPosition: Int, endPosition: Int): IntRange {
        val startPosition = if (windowStartPosition < endPosition) windowStartPosition else endPosition - 1
        return startPosition.coerceAtLeast(0)..endPosition
    }

    private fun hasResumeEvidence(
        result: List<GeoPoint>,
        indices: List<Int>,
        config: TrackProcessingConfig,
    ): Boolean {
        if (indices.size < 2) return false
        val reportedSpeeds = reportedSpeeds(result, indices)
        // Doppler-скорость привязана к одной точке и не размывается длиной отрезка,
        // поэтому её достаточно самой по себе.
        if (reportedSpeeds.any { it >= config.resumeSpeedMetersPerSecond }) return true
        val positionalSpeed = positionalSpeed(
            first = result[indices.first()],
            last = result[indices.last()],
            accuracyAllowance = accuracyAllowance(result, indices, config),
        )
        return max(reportedSpeeds.averageOrZero(), positionalSpeed) >= config.resumeSpeedMetersPerSecond
    }

    /**
     * Погрешность вычитается один раз на отрезок, а не из каждого сегмента: вычитание
     * на каждом шаге стирало обычную ходьбу полностью.
     */
    private fun positionalSpeed(first: GeoPoint, last: GeoPoint, accuracyAllowance: Double): Double {
        val durationSeconds = (last.timestampEpochMillis - first.timestampEpochMillis) / 1_000.0
        if (durationSeconds <= 0.0) return 0.0
        return (distanceMeters(first, last) - accuracyAllowance).coerceAtLeast(0.0) / durationSeconds
    }

    private fun reportedSpeeds(result: List<GeoPoint>, indices: List<Int>): List<Double> =
        indices.mapNotNull { index ->
            result[index].speedMetersPerSecond
                ?.toDouble()
                ?.takeIf { it.isFinite() && it >= 0.0 }
        }

    private fun accuracyAllowance(
        result: List<GeoPoint>,
        indices: List<Int>,
        config: TrackProcessingConfig,
    ): Double = indices
        .mapNotNull { result[it].accuracyMeters?.toDouble() }
        .maxOrNull()
        ?.coerceAtMost(config.stopRadiusMeters)
        ?: 0.0

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
