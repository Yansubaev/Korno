package com.ians.korno.core.trackprocessing

import kotlin.math.abs

/**
 * Размер вектора состояния фильтра: положение и скорость по двум осям.
 * Матрицы хранятся построчно в плоском [DoubleArray] — на треке в тысячи точек это
 * разница между одним массивом и четырьмя объектами на каждый шаг.
 */
internal const val STATE_SIZE = 4

internal fun identityMatrix(): DoubleArray = DoubleArray(STATE_SIZE * STATE_SIZE).also { matrix ->
    for (index in 0 until STATE_SIZE) matrix[index * STATE_SIZE + index] = 1.0
}

internal fun multiply(first: DoubleArray, second: DoubleArray): DoubleArray {
    val product = DoubleArray(STATE_SIZE * STATE_SIZE)
    for (row in 0 until STATE_SIZE) {
        for (column in 0 until STATE_SIZE) {
            var sum = 0.0
            for (inner in 0 until STATE_SIZE) {
                sum += first[row * STATE_SIZE + inner] * second[inner * STATE_SIZE + column]
            }
            product[row * STATE_SIZE + column] = sum
        }
    }
    return product
}

internal fun multiplyVector(matrix: DoubleArray, vector: DoubleArray): DoubleArray {
    val product = DoubleArray(STATE_SIZE)
    for (row in 0 until STATE_SIZE) {
        var sum = 0.0
        for (column in 0 until STATE_SIZE) {
            sum += matrix[row * STATE_SIZE + column] * vector[column]
        }
        product[row] = sum
    }
    return product
}

internal fun transpose(matrix: DoubleArray): DoubleArray {
    val result = DoubleArray(STATE_SIZE * STATE_SIZE)
    for (row in 0 until STATE_SIZE) {
        for (column in 0 until STATE_SIZE) {
            result[column * STATE_SIZE + row] = matrix[row * STATE_SIZE + column]
        }
    }
    return result
}

/** Поэлементное сложение: годится и для матриц, и для векторов состояния. */
internal fun add(first: DoubleArray, second: DoubleArray): DoubleArray =
    DoubleArray(first.size) { index -> first[index] + second[index] }

internal fun subtract(first: DoubleArray, second: DoubleArray): DoubleArray =
    DoubleArray(first.size) { index -> first[index] - second[index] }

/**
 * Обращение методом Гаусса—Жордана с выбором главного элемента; `null`, если матрица
 * вырождена.
 *
 * Вырождение здесь не теоретическая возможность: ковариация вырождается, когда фильтр
 * считает какую-то составляющую состояния известной точно. Обратный проход в этом месте
 * просто останавливается — лучше оставить оценку прямого прохода, чем поделить на ноль.
 */
internal fun invert(matrix: DoubleArray): DoubleArray? {
    val working = matrix.copyOf()
    val inverse = identityMatrix()

    for (column in 0 until STATE_SIZE) {
        var pivotRow = column
        for (row in column + 1 until STATE_SIZE) {
            if (abs(working[row * STATE_SIZE + column]) > abs(working[pivotRow * STATE_SIZE + column])) {
                pivotRow = row
            }
        }
        val pivot = working[pivotRow * STATE_SIZE + column]
        if (!pivot.isFinite() || abs(pivot) < PIVOT_EPSILON) return null

        if (pivotRow != column) {
            working.swapRows(pivotRow, column)
            inverse.swapRows(pivotRow, column)
        }

        val scale = 1.0 / working[column * STATE_SIZE + column]
        for (index in 0 until STATE_SIZE) {
            working[column * STATE_SIZE + index] *= scale
            inverse[column * STATE_SIZE + index] *= scale
        }

        for (row in 0 until STATE_SIZE) {
            if (row == column) continue
            val factor = working[row * STATE_SIZE + column]
            if (factor == 0.0) continue
            for (index in 0 until STATE_SIZE) {
                working[row * STATE_SIZE + index] -= factor * working[column * STATE_SIZE + index]
                inverse[row * STATE_SIZE + index] -= factor * inverse[column * STATE_SIZE + index]
            }
        }
    }
    return inverse
}

private const val PIVOT_EPSILON = 1e-12

private fun DoubleArray.swapRows(first: Int, second: Int) {
    for (index in 0 until STATE_SIZE) {
        val buffer = this[first * STATE_SIZE + index]
        this[first * STATE_SIZE + index] = this[second * STATE_SIZE + index]
        this[second * STATE_SIZE + index] = buffer
    }
}
