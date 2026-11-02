package com.pdfreader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Page clean-up applied as ONE colour matrix, so it costs nothing per frame:
 *  1. Levels — everything lighter than the white point becomes pure paper, everything darker
 *     than the black point pure ink. This is what wipes light-grey scan speckle, dotted noise
 *     and yellowed paper, and makes faded print crisp.
 *  2. Dark pages — a "proper" inversion: lightness is flipped but the hue is turned back
 *     (invert + hue-rotate 180°), so photos and coloured print keep their colours instead of
 *     going negative. The white point decides where paper lands: light levels keep it near the
 *     reading-theme background, strong levels push it to true black (levels saturate first).
 */
object ScanFilter {

    /** [level]: 0 off, 1 light, 2 strong. [invert]: dark pages. [paper]: reading-theme background. */
    class Spec(val invert: Boolean, val level: Int, val paper: Int) {
        val active get() = invert || level > 0
    }

    private val INK_ON_DARK = Color.parseColor("#DADADA")

    private val HUE180 = floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
         0.426f, 0.430f, 0.144f, 0f, 0f,
         0.426f, 1.430f, -0.856f, 0f, 0f,
         0f,     0f,     0f,     1f, 0f)

    private val INVERT = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f)

    /** Black point / white point of the levels step. */
    private fun lo(level: Int) = when (level) { 1 -> 25f; 2 -> 70f; else -> 0f }
    private fun hi(level: Int) = when (level) { 1 -> 225f; 2 -> 185f; else -> 255f }

    fun colorMatrix(spec: Spec): ColorMatrix {
        val lo = lo(spec.level); val hi = hi(spec.level)
        val s = 255f / (hi - lo); val o = -lo * s
        val m = ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, o,
            0f, s, 0f, 0f, o,
            0f, 0f, s, 0f, o,
            0f, 0f, 0f, 1f, 0f))
        if (spec.invert) {
            m.postConcat(ColorMatrix(INVERT))
            m.postConcat(ColorMatrix(HUE180))
            val t = INK_ON_DARK; val b = spec.paper
            m.postConcat(ColorMatrix(floatArrayOf(
                (Color.red(t) - Color.red(b)) / 255f, 0f, 0f, 0f, Color.red(b).toFloat(),
                0f, (Color.green(t) - Color.green(b)) / 255f, 0f, 0f, Color.green(b).toFloat(),
                0f, 0f, (Color.blue(t) - Color.blue(b)) / 255f, 0f, Color.blue(b).toFloat(),
                0f, 0f, 0f, 1f, 0f)))
        }
        return m
    }

    /** What a pure-white page pixel ends up as once the whole matrix (and the final clip) is done. */
    fun paperColor(spec: Spec): Int {
        val a = colorMatrix(spec).array
        fun ch(r: Int) = (a[r * 5] * 255f + a[r * 5 + 1] * 255f + a[r * 5 + 2] * 255f + a[r * 5 + 4])
            .coerceIn(0f, 255f).toInt()
        return Color.rgb(ch(0), ch(1), ch(2))
    }

    /** Colour of the area around the page: the theme background, or the inverted paper colour. */
    fun surround(spec: Spec, themeBg: Int): Int = if (spec.invert) paperColor(spec) else themeBg

    fun colorFilter(spec: Spec): ColorMatrixColorFilter? =
        if (spec.active) ColorMatrixColorFilter(colorMatrix(spec)) else null

    /** Filtered copy of [src] (used for the footnote sheet). */
    fun apply(src: Bitmap, spec: Spec): Bitmap {
        val f = colorFilter(spec) ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = f })
        src.recycle()
        return out
    }

    /**
     * The colour to draw INSIDE a filtered view so that it appears as [color] after the filter
     * (highlighter bands, the footnote curtain). Exact inverse of [colorMatrix]; alpha is kept.
     */
    fun preimage(color: Int, spec: Spec): Int {
        if (!spec.active) return color
        val v = floatArrayOf(Color.red(color).toFloat(), Color.green(color).toFloat(), Color.blue(color).toFloat())
        if (spec.invert) {
            val t = INK_ON_DARK; val b = spec.paper
            val tc = intArrayOf(Color.red(t), Color.green(t), Color.blue(t))
            val bc = intArrayOf(Color.red(b), Color.green(b), Color.blue(b))
            for (i in 0..2) {                                   // undo the recolour step
                val k = (tc[i] - bc[i]) / 255f
                v[i] = if (kotlin.math.abs(k) < 1e-3f) 0f else (v[i] - bc[i]) / k
            }
            val h = floatArrayOf(                                // hue-rotate 180° undoes itself
                HUE180[0] * v[0] + HUE180[1] * v[1] + HUE180[2] * v[2],
                HUE180[5] * v[0] + HUE180[6] * v[1] + HUE180[7] * v[2],
                HUE180[10] * v[0] + HUE180[11] * v[1] + HUE180[12] * v[2])
            for (i in 0..2) v[i] = 255f - h[i]                   // undo the inversion
        }
        val lo = lo(spec.level); val hi = hi(spec.level)
        val s = 255f / (hi - lo)
        for (i in 0..2) v[i] = (v[i] / s + lo).coerceIn(0f, 255f)    // undo the levels
        return Color.argb(Color.alpha(color), v[0].toInt(), v[1].toInt(), v[2].toInt())
    }
}
