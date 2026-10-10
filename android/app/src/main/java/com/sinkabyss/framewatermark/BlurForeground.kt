package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Local luminance bounds are prepared off the UI thread and shared by preview/export. */
internal data class BlurInkMaps(val width: Int, val height: Int, val top: Int,
    val columns: Int, val low: DoubleArray, val high: DoubleArray)

internal object BlurForeground {
    // Headroom for raster edge coverage when a uniform ink can reach the diagnostic floor.
    private const val CONTRAST = 4.7

    private const val TILE = 16

    fun prepare(background: Bitmap, source: Bitmap, geometry: FrameGeometry): BlurInkMaps {
        // Measure the possible glyph area against the actual filtered backdrop at canvas
        // resolution, including the existing text overflow budget.
        val width = geometry.canvasWidth
        val height = minOf(geometry.canvasHeight, kotlin.math.ceil(geometry.band + geometry.photoHeight * 0.32).toInt() + 4)
        val top = geometry.canvasHeight - height
        val backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(backdrop)
        canvas.translate(0f, -top.toFloat())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(background, null, RectF(0f, 0f, width.toFloat(), geometry.canvasHeight.toFloat()), paint)
        paint.isAntiAlias = true
        canvas.drawBitmap(source, null, RectF(geometry.margin.toFloat(), geometry.margin.toFloat(),
            (geometry.margin + geometry.photoWidth).toFloat(), (geometry.margin + geometry.photoHeight).toFloat()), paint)
        val pixels = IntArray(width * height)
        backdrop.getPixels(pixels, 0, width, 0, 0, width, height)
        backdrop.recycle()
        val columns = (width + TILE - 1) / TILE
        val tileRows = (height + TILE - 1) / TILE
        val low = DoubleArray(columns * tileRows) { 1.0 }
        val high = DoubleArray(columns * tileRows)
        for (index in pixels.indices) {
            val luminance = ColorScience.relativeLuminance(pixels[index])
            val tile = (index / width / TILE) * columns + (index % width / TILE)
            low[tile] = minOf(low[tile], luminance); high[tile] = maxOf(high[tile], luminance)
        }
        return BlurInkMaps(width, height, top, columns, low, high)
    }

    /** One ink for the whole signature or text line. On a mixed field that cannot meet the
     * diagnostic floor with a uniform ink, choose the black/white polarity with the better
     * worst-case contrast. This preserves the component; it cannot guarantee pointwise 4.5.
     */
    fun apply(paint: Paint, background: MaterializedBackground.Photo, region: RectF, secondary: Boolean) {
        val inks = background.inks
        val x0 = (region.left - 1).toInt().coerceIn(0, inks.width - 1) / TILE
        val x1 = (region.right + 1).toInt().coerceIn(0, inks.width - 1) / TILE
        val y0 = (region.top - inks.top - 1).toInt().coerceIn(0, inks.height - 1) / TILE
        val y1 = (region.bottom - inks.top + 1).toInt().coerceIn(0, inks.height - 1) / TILE
        var low = 1.0
        var high = 0.0
        for (y in y0..y1) for (x in x0..x1) {
            val tile = y * inks.columns + x
            low = minOf(low, inks.low[tile]); high = maxOf(high, inks.high[tile])
        }
        val dark = (low + 0.05) / 0.05
        val light = 1.05 / (high + 0.05)
        paint.shader = null
        if (maxOf(dark, light) >= CONTRAST) {
            paint.color = if (dark >= light) darkInk(low, secondary) else lightInk(high, secondary)
        } else {
            paint.color = if (dark > light) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }

    private fun gray(linear: Double): Int = ColorScience.argbFromLinear(doubleArrayOf(linear, linear, linear))

    private fun darkInk(luminance: Double, secondary: Boolean): Int {
        val preferred = if (secondary) 0xff353535.toInt() else 0xff1a1a1a.toInt()
        val limit = ((luminance + 0.05) / CONTRAST - 0.05).coerceAtLeast(0.0)
        val color = gray(minOf(ColorScience.relativeLuminance(preferred), limit))
        return if ((luminance + 0.05) / (ColorScience.relativeLuminance(color) + 0.05) >= 4.5) color else 0xff000000.toInt()
    }

    private fun lightInk(luminance: Double, secondary: Boolean): Int {
        val preferred = if (secondary) 0xffdddddd.toInt() else 0xfff4f4f4.toInt()
        val limit = (CONTRAST * (luminance + 0.05) - 0.05).coerceAtMost(1.0)
        val color = gray(maxOf(ColorScience.relativeLuminance(preferred), limit))
        return if ((ColorScience.relativeLuminance(color) + 0.05) / (luminance + 0.05) >= 4.5) color else 0xffffffff.toInt()
    }
}
