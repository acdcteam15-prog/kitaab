package com.pdfreader.app.ui.reader

/**
 * Zoom maths for "crop margins" / footnote fold. Detection itself lives in [PageAnalyzer]
 * (per page) and CropProfileBuilder (per book, odd/even sides).
 */
object CropDetector {

    /**
     * Where a page ends up after the crop zoom.
     * The page is drawn fit-centred at zoom 1 (size [pw]×[ph] inside the view), then scaled by
     * [zoom] around ([pivotX],[pivotY]) — exactly what PDFView.zoomCenteredTo / Canvas.scale do.
     */
    class Layout(
        val zoom: Float, val pivotX: Float, val pivotY: Float,
        private val left0: Float, private val top0: Float, val pw: Float, val ph: Float
    ) {
        /** Screen y of a page fraction. */
        fun screenY(fy: Float): Float = pivotY + zoom * (top0 + ph * fy - pivotY)
        /** Page fraction under a screen y. */
        fun pageFracY(sy: Float): Float = ((sy - pivotY) / zoom + pivotY - top0) / ph
        /** Screen x of a page fraction. */
        fun screenX(fx: Float): Float = pivotX + zoom * (left0 + pw * fx - pivotX)
    }

    /**
     * Zoom so the content [box] ([l,t,r,b] page fractions) fills the view, as large as fits
     * (max 3×, PDFView's own limit). Horizontally centred; vertically the box top sits near the
     * top of the screen, so the first line stays in the same place from page to page even when
     * a folded footnote area makes the block shorter.
     */
    fun layout(W: Float, H: Float, pw: Float, ph: Float, box: FloatArray): Layout {
        val left = (W - pw) / 2f; val top = (H - ph) / 2f
        val cw = pw * (box[2] - box[0]); val ch = ph * (box[3] - box[1])
        val z = minOf(W / cw, H / ch).coerceIn(1f, 3f)
        if (z < 1.01f) return Layout(1f, W / 2f, H / 2f, left, top, pw, ph)
        val cx = left + pw * (box[0] + box[2]) / 2f
        val cy = top + ph * (box[1] + box[3]) / 2f
        val margin = minOf(H * 0.035f, (H - ch * z) / 2f)
        val targetY = margin + ch * z / 2f          // where the box centre must land on screen
        // Pivot p with p + z·(c − p) = target   →   p = (target − z·c) / (1 − z)
        val px = (W / 2f - z * cx) / (1f - z)
        val py = (targetY - z * cy) / (1f - z)
        return Layout(z, px, py, left, top, pw, ph)
    }
}
