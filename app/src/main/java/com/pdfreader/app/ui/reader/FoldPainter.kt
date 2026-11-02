package com.pdfreader.app.ui.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Draws the footnote "curtain" and its pull-up handle over the lower part of a page.
 * Shared by the live reader (inside PDFView) and the page-curl images, so both look identical.
 */
object FoldPainter {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val tmp = RectF()
    const val LABEL = "▲  Footnotes"

    /**
     * @param pageLeft/pageTop/pw/ph  where the page sits on [c]
     * @param foldY   page fraction where the curtain starts
     * @param cx      page fraction (horizontal) where the handle is centred
     * @param bg      colour the curtain must END UP with (the reading theme background)
     * @param unit    canvas pixels per dp at this place
     * @param fix     maps an intended on-screen colour to what must be drawn (see [ScanFilter.preimage])
     * @param handleOut receives the handle rectangle (canvas coordinates), or null
     */
    fun draw(c: Canvas, pageLeft: Float, pageTop: Float, pw: Float, ph: Float,
             foldY: Float, cx: Float, bg: Int, unit: Float, fix: (Int) -> Int, handleOut: RectF?) {
        val y0 = pageTop + ph * foldY
        fill.style = Paint.Style.FILL
        fill.color = fix(bg)
        c.drawRect(pageLeft - 1f, y0, pageLeft + pw + 1f, pageTop + ph + 1f, fill)

        val hw = 108f * unit; val hh = 28f * unit
        val hx = pageLeft + pw * cx
        val top = y0 + 8f * unit
        tmp.set(hx - hw / 2f, top, hx + hw / 2f, top + hh)
        fill.color = fix(0xFFF5A623.toInt())
        c.drawRoundRect(tmp, hh / 2f, hh / 2f, fill)
        text.color = fix(0xFF1C1B2E.toInt())
        text.textSize = 12f * unit
        c.drawText(LABEL, tmp.centerX(), tmp.centerY() + text.textSize * 0.35f, text)
        handleOut?.set(tmp)
    }
}
