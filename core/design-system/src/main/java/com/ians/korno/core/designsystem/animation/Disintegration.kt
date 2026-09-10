package com.ians.korno.core.designsystem.animation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

/**
 * «Щелчок Таноса»: снимок экрана режется на мелкие квадраты, и они, начиная с правого края,
 * срываются и улетают вправо, растворяясь. Справа налево — чтобы летящая пыль шла над уже
 * открывшимся экраном, а не над ещё целой частью снимка.
 *
 * Частицы — один drawVertices с текстурой снимка: десятки тысяч отдельных drawRect кадр не
 * вытянет. И даже так считаются и отдаются в отрисовку только колонки, которые сейчас в полёте:
 * нетронутая левая часть рисуется самим снимком, догоревшие колонки справа не рисуются вовсе.
 * HWUI умеет drawVertices только с API 29, ниже [disintegrateOnExit] просто гасит снимок.
 */
internal class Disintegration(image: ImageBitmap, particleSizePx: Float, random: Random = Random(7)) {

    private val bitmap: Bitmap = image.asAndroidBitmap()
    private val size = particleSizePx
    private val width = image.width.toFloat()
    private val columns = ceil(width / size).toInt()
    private val rows = ceil(image.height / size).toInt()
    private val count = columns * rows

    // Частица i = column * rows + row, колонка 0 — крайняя правая: так порядок в массивах
    // совпадает с порядком распада, и летящие частицы всегда лежат одним непрерывным куском.
    private val originX = FloatArray(count)
    private val originY = FloatArray(count)
    private val startAt = FloatArray(count)
    private val flyX = FloatArray(count)
    private val flyY = FloatArray(count)
    private val phase = FloatArray(count)

    // По 6 вершин (два треугольника) на частицу, у каждой вершины x и y.
    private val positions = FloatArray(count * 12)
    private val texCoords = FloatArray(count * 12)
    private val colors = IntArray(count * 6) { 0xFFFFFFFF.toInt() }

    private val paint = Paint().apply {
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    init {
        for (column in 0 until columns) {
            for (row in 0 until rows) {
                val i = column * rows + row
                val x = columnX(column)
                val y = row * size
                originX[i] = x
                originY[i] = y
                startAt[i] = columnStart(column) + random.nextFloat() * RAGGEDNESS
                flyX[i] = width * (0.3f + 0.6f * random.nextFloat())
                flyY[i] = image.height * (-0.08f + 0.1f * random.nextFloat())
                phase[i] = random.nextFloat() * 6.28f
                writeQuad(texCoords, i, x, y, x + size, y + size)
            }
        }
        texCoords.copyInto(positions)
    }

    /** [progress] — общий ход эффекта от 0 до 1; к 1 все частицы уже растворились. */
    fun draw(canvas: Canvas, progress: Float) {
        var firstFlying = 0
        while (firstFlying < columns && columnStart(firstFlying) + RAGGEDNESS + LIFE <= progress) firstFlying++
        var firstResting = firstFlying
        while (firstResting < columns && columnStart(firstResting) <= progress) firstResting++

        for (i in firstFlying * rows until firstResting * rows) {
            val p = ((progress - startAt[i]) / LIFE).coerceIn(0f, 1f)
            if (p == 0f) continue // ещё не сорвалась — вершины с init на месте
            // Квадрат по p: пыль сначала едва отрывается, а потом её подхватывает «ветер».
            val eased = p * p
            val half = if (p >= 1f) 0f else size * (1f - 0.6f * p) / 2f
            val centerX = originX[i] + size / 2f + flyX[i] * eased
            val centerY = originY[i] + size / 2f + flyY[i] * eased + sin(phase[i] + p * 6f) * size * 2f * p
            writeQuad(positions, i, centerX - half, centerY - half, centerX + half, centerY + half)
            colors.fill(((1f - p) * 255f).toInt() shl 24 or 0xFFFFFF, i * 6, i * 6 + 6)
        }

        val restingRight = (columns - firstResting) * size
        if (restingRight > 0f) {
            canvas.save()
            canvas.clipRect(0f, 0f, restingRight, bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            canvas.restore()
        }
        val flyingParticles = (firstResting - firstFlying) * rows
        if (flyingParticles > 0) {
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES, flyingParticles * 12,
                positions, firstFlying * rows * 12,
                texCoords, firstFlying * rows * 12,
                colors, firstFlying * rows * 6,
                null, 0, 0, paint,
            )
        }
    }

    private fun columnX(column: Int): Float = (columns - 1 - column) * size

    private fun columnStart(column: Int): Float = (1f - columnX(column) / width) * SWEEP

    private fun writeQuad(target: FloatArray, i: Int, left: Float, top: Float, right: Float, bottom: Float) {
        var o = i * 12
        target[o++] = left; target[o++] = top
        target[o++] = right; target[o++] = top
        target[o++] = left; target[o++] = bottom
        target[o++] = right; target[o++] = top
        target[o++] = right; target[o++] = bottom
        target[o++] = left; target[o] = bottom
    }

    private companion object {
        /** За какую долю эффекта фронт распада проходит от правого края до левого. */
        const val SWEEP = 0.6f

        /** Разброс старта внутри колонки, чтобы фронт был рваным, а не ровной вертикалью. */
        const val RAGGEDNESS = 0.08f

        /**
         * Сколько летит одна частица; SWEEP + RAGGEDNESS + LIFE = 1. Чем короче полёт относительно
         * SWEEP, тем меньше колонок в воздухе одновременно — и тем дешевле самый тяжёлый кадр.
         */
        const val LIFE = 0.32f
    }
}
