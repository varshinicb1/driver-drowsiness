package com.drowsy.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Fast center-weighted luma + gamma lift for cabin lighting (day/night/backlit).
 * Keeps latency low: sparse pixel sampling + single ColorMatrix pass.
 */
object FrameEnhancer {

    data class Result(
        val bitmap: Bitmap,
        val luma: Float,
        val enhanced: Boolean,
    )

    /** ITU-R BT.601 luma from sparse center crop (face zone). */
    fun estimateCenterLuma(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        if (w < 8 || h < 8) return 128f
        val x0 = w / 4
        val x1 = 3 * w / 4
        val y0 = h / 4
        val y1 = 3 * h / 4
        val step = max(4, min(w, h) / 28)
        var sum = 0L
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y)
                sum += ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) / 256
                n++
                x += step
            }
            y += step
        }
        return if (n > 0) sum.toFloat() / n else 128f
    }

    /**
     * Brighten dark frames in-place copy. Skips processing when already bright enough.
     */
    fun enhance(src: Bitmap): Result {
        val luma = estimateCenterLuma(src)
        if (luma >= 108f) return Result(src, luma, false)

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
            ?: return Result(src, luma, false)

        val scale = when {
            luma < 40f -> 1.95f
            luma < 60f -> 1.6f
            luma < 80f -> 1.35f
            luma < 95f -> 1.18f
            else -> 1.08f
        }
        val offset = when {
            luma < 40f -> 48f
            luma < 60f -> 34f
            luma < 80f -> 22f
            luma < 95f -> 12f
            else -> 6f
        }
        val cm = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return Result(out, luma, true)
    }

    /** EAR reads lower in darkness — relax closed-eye threshold slightly. */
    fun adaptiveEarThreshold(base: Float, luma: Float): Float {
        val norm = luma.coerceIn(35f, 145f) / 145f
        return base * (0.68f + 0.32f * norm)
    }

    fun adaptiveMarThreshold(base: Float, luma: Float): Float =
        base * (0.92f + 0.08f * luma.coerceIn(40f, 140f) / 140f)
}
