package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class FrameLayoutKind(val title: String) {
    BAR("底部加条"),
    BOX("四周留边"),
}

data class FrameGeometry(
    val photoWidth: Int,
    val photoHeight: Int,
    val margin: Int,
    val band: Int,
    val inset: Int,
    val circleBase: Int,
    val signatureBase: Int,
    val gap: Int,
    val parameterFont: Float,
    val detailFont: Float,
    val lineGap: Float,
    val bandPad: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
) {
    companion object {
        private const val BASIS = 2_000f

        fun calculate(
            width: Int,
            height: Int,
            layout: FrameLayoutKind,
            outputLongEdge: Int = max(width, height),
        ): FrameGeometry {
            require(width > 0 && height > 0) { "照片尺寸必须大于 0" }
            val targetLongEdge = outputLongEdge.coerceAtLeast(1)
            val photoScale = targetLongEdge.toFloat() / max(width, height)
            val lockScale = targetLongEdge / BASIS
            val horizontal = width >= height
            val bandBase = if (horizontal) 163 else 192
            val insetBarBase = if (horizontal) 58 else 44
            val insetBoxBase = if (horizontal) 65 else 68
            val marginBoxBase = if (horizontal) 30 else 32
            val circleBase = if (horizontal) 98 else 106
            val signatureBase = if (horizontal) 104 else 115
            val gapBase = if (horizontal) 29 else 31
            val parameterFontBase = if (horizontal) 34 else 36
            val detailFontBase = if (horizontal) 27 else 29
            val lineGapBase = if (horizontal) 9 else 10
            val photoWidth = (width * photoScale).roundToInt().coerceAtLeast(1)
            val photoHeight = (height * photoScale).roundToInt().coerceAtLeast(1)
            val margin = if (layout == FrameLayoutKind.BOX) (marginBoxBase * lockScale).roundToInt() else 0
            val band = (bandBase * lockScale).roundToInt().coerceAtLeast(1)
            val inset = ((if (layout == FrameLayoutKind.BOX) insetBoxBase else insetBarBase) * lockScale).roundToInt()
            val bandPad = max((10 * lockScale).roundToInt(), (band * 0.05f).roundToInt())

            return FrameGeometry(
                photoWidth = photoWidth,
                photoHeight = photoHeight,
                margin = margin,
                band = band,
                inset = inset,
                circleBase = (circleBase * lockScale).roundToInt(),
                signatureBase = (signatureBase * lockScale).roundToInt(),
                gap = (gapBase * lockScale).roundToInt(),
                parameterFont = parameterFontBase * lockScale,
                detailFont = detailFontBase * lockScale,
                lineGap = lineGapBase * lockScale,
                bandPad = bandPad,
                canvasWidth = photoWidth + margin * 2,
                canvasHeight = photoHeight + margin + band,
            )
        }
    }
}

internal data class FrameOptions(
    val layout: FrameLayoutKind = FrameLayoutKind.BAR,
    val circleBitmap: Bitmap? = null,
    val circleSizePercent: Int = 100,
    val signatureBitmap: Bitmap? = null,
    val signatureSizePercent: Int = 100,
    val parameterText: String = "",
    val dateText: String = "",
    val placeText: String = "",
    val modelText: String = "",
    /** Solved border background plus the paired signature/text colors. Defaults to 白色. */
    val style: BorderStyle = BorderStyle.White,
    val colors: ForegroundColors = ForegroundColors(),
    val phraseText: String = "",
    val phraseTypeface: Typeface = Typeface.SANS_SERIF,
)

/** Shared preview/export renderer. Both paths use this geometry and Canvas routine. */
internal object FrameRenderer {
    private val signatureBoundsCache = WeakHashMap<Bitmap, Rect>()

    fun render(source: Bitmap, options: FrameOptions): Bitmap {
        val geometry = FrameGeometry.calculate(source.width, source.height, options.layout)
        val output = Bitmap.createBitmap(geometry.canvasWidth, geometry.canvasHeight, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(output), source, geometry, options)
            return output
        } catch (failure: Throwable) {
            output.recycle()
            throw failure
        }
    }

    /** Materializes the background (solid color or blur bitmap) for the given geometry. */
    fun prepareBackground(source: Bitmap, geometry: FrameGeometry, style: BorderStyle): MaterializedBackground =
        FrameBackgroundRenderer.materialize(source, geometry, style)

    fun draw(canvas: Canvas, source: Bitmap, geometry: FrameGeometry, options: FrameOptions,
        preparedBackground: MaterializedBackground? = null) {
        val photoLeft = geometry.margin.toFloat()
        val photoTop = geometry.margin.toFloat()
        val photoRight = photoLeft + geometry.photoWidth
        val photoBottom = photoTop + geometry.photoHeight
        val outRight = geometry.canvasWidth.toFloat()
        val outBottom = geometry.canvasHeight.toFloat()

        val background = preparedBackground ?: prepareBackground(source, geometry, options.style)
        drawBackground(canvas, background, geometry)
        val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(photoLeft, photoTop, photoRight, photoBottom),
            photoPaint,
        )

        val bandY = (geometry.canvasHeight - geometry.band).toFloat()
        val leftX = geometry.inset.toFloat()
        val rightX = geometry.canvasWidth - geometry.inset.toFloat()
        var cursor = leftX
        val circle = options.circleBitmap
        if (circle != null) {
            val maxCircle = max(1f, geometry.band - geometry.bandPad * 2f)
            val diameter = min(maxCircle, geometry.circleBase * (options.circleSizePercent.coerceIn(50, 140) / 100f))
            val top = bandY + (geometry.band - diameter) / 2f
            val circlePath = Path().apply { addCircle(cursor + diameter / 2f, top + diameter / 2f, diameter / 2f, Path.Direction.CW) }
            val save = canvas.save()
            canvas.clipPath(circlePath)
            canvas.drawBitmap(
                circle,
                min(circle.width, circle.height).let { edge ->
                    val x = (circle.width - edge) / 2; val y = (circle.height - edge) / 2
                    Rect(x, y, x + edge, y + edge)
                },
                RectF(cursor, top, cursor + diameter, top + diameter),
                photoPaint,
            )
            canvas.restoreToCount(save)
            cursor += diameter + geometry.gap
        }

        val signature = options.signatureBitmap
        if (signature != null) {
            val inkBounds = signatureBounds(signature)
            if (!inkBounds.isEmpty) {
                val ratio = inkBounds.width().toFloat() / inkBounds.height()
                val requestedHeight = geometry.signatureBase * (options.signatureSizePercent.coerceIn(50, 140) / 100f)
                val maxHeight = max(1f, geometry.band - geometry.bandPad * 2f)
                val leftBudget = max(0f, (rightX - leftX) * 0.55f - (cursor - leftX))
                val requestedWidth = requestedHeight * ratio
                val fitScale = minOf(1f, maxHeight / requestedHeight, leftBudget / requestedWidth)
                val height = requestedHeight * fitScale
                val width = requestedWidth * fitScale
                if (height > 0.5f && width > 0.5f) {
                    val top = bandY + (geometry.band - height) / 2f
                    val signaturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                        colorFilter = PorterDuffColorFilter(options.colors.signature.resolve(options.style.foreground.signature), PorterDuff.Mode.SRC_IN)
                    }
                    val region = RectF(cursor, top, cursor + width, top + height)
                    if (background is MaterializedBackground.Photo && !options.colors.signature.isCustom) {
                        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                        BlurForeground.apply(inkPaint, background, region, secondary = false)
                        signaturePaint.colorFilter = PorterDuffColorFilter(inkPaint.color, PorterDuff.Mode.SRC_IN)
                        canvas.drawBitmap(signature, inkBounds, region, signaturePaint)
                    } else canvas.drawBitmap(signature, inkBounds, region, signaturePaint)
                    cursor += width
                }
            }
        }

        val rowTexts = buildList {
            options.phraseText.trim().takeIf(String::isNotEmpty)?.let { add(it to geometry.parameterFont) }
            options.parameterText.trim().takeIf(String::isNotEmpty)?.let { add(it to geometry.parameterFont) }
            val details = listOf(options.dateText, options.placeText).map(String::trim).filter(String::isNotEmpty)
            if (details.isNotEmpty()) add(details.joinToString("  ·  ") to geometry.detailFont)
            options.modelText.trim().takeIf(String::isNotEmpty)?.let { add(it to geometry.detailFont) }
        }
        val availableWidth = max(0f, rightX - cursor - if (cursor > leftX) geometry.gap else 0).toFloat()
        if (rowTexts.isEmpty() || availableWidth <= 1f) return

        val rows = rowTexts.mapIndexed { index, (text, baseSize) ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = options.colors.info.resolve(if (index == 0) options.style.foreground.primary else options.style.foreground.secondary)
                textSize = baseSize.coerceAtLeast(8f)
                val phraseRow = options.phraseText.isNotBlank() && index == 0
                val metadataIndex = index - if (options.phraseText.isNotBlank()) 1 else 0
                typeface = if (phraseRow) options.phraseTypeface else if (metadataIndex == 0)
                    Typeface.create("sans-serif", Typeface.NORMAL) else Typeface.create("sans-serif-light", Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            TextRow(text = text, paint = paint, lines = wrap(text, paint, availableWidth))
        }

        val safeBandHeight = max(1f, geometry.band - geometry.bandPad * 2f)
        val allowedHeight = safeBandHeight + geometry.photoHeight * 0.32f
        var totalHeight = rows.sumOf { it.lines.size * it.paint.textSize * 1.2 }.toFloat() + geometry.lineGap * (rows.size - 1).coerceAtLeast(0)
        if (totalHeight > allowedHeight) {
            val shrink = (allowedHeight / totalHeight).coerceIn(0.48f, 1f)
            rows.forEach { row ->
                row.paint.textSize = max(8f, row.paint.textSize * shrink)
                row.lines = wrap(row.text, row.paint, availableWidth)
            }
            totalHeight = rows.sumOf { it.lines.size * it.paint.textSize * 1.2 }.toFloat() + geometry.lineGap * (rows.size - 1).coerceAtLeast(0)
        }

        val centeredY = outBottom - geometry.band + (geometry.band - totalHeight) / 2f
        var y = max(0f, min(centeredY, outBottom - geometry.bandPad - totalHeight))
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) y += geometry.lineGap
            row.lines.forEach { line ->
                val baseline = (y + row.paint.textSize).coerceAtMost(outBottom - 1f)
                val measured = row.paint.measureText(line)
                val lineLeft = rightX - min(measured, availableWidth)
                if (background is MaterializedBackground.Photo && !options.colors.info.isCustom) {
                    val metrics = row.paint.fontMetrics
                    BlurForeground.apply(row.paint, background,
                        RectF(lineLeft, baseline + metrics.top, rightX, baseline + metrics.bottom), secondary = rowIndex > 0)
                }
                canvas.drawText(line, lineLeft, baseline, row.paint)
                y = baseline + max(1f, row.paint.textSize * 0.2f)
            }
        }
    }

    private data class TextRow(val text: String, val paint: Paint, var lines: List<String>)

    /** One background layer under the photo: solid fill or the prepared blur bitmap. */
    private fun drawBackground(canvas: Canvas, background: MaterializedBackground, geometry: FrameGeometry) {
        val right = geometry.canvasWidth.toFloat()
        val bottom = geometry.canvasHeight.toFloat()
        when (background) {
            is MaterializedBackground.Solid -> {
                val paint = Paint().apply { color = background.color }
                canvas.drawRect(0f, 0f, right, bottom, paint)
            }
            is MaterializedBackground.Photo -> {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                canvas.drawBitmap(
                    background.bitmap,
                    Rect(0, 0, background.bitmap.width, background.bitmap.height),
                    RectF(0f, 0f, right, bottom),
                    paint,
                )
            }
        }
    }

    private fun signatureBounds(bitmap: Bitmap): Rect = synchronized(signatureBoundsCache) {
        signatureBoundsCache[bitmap] ?: run {
            val pixels = IntArray(bitmap.width)
            var left = bitmap.width
            var top = bitmap.height
            var right = -1
            var bottom = -1
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in pixels.indices) {
                    if (Color.alpha(pixels[x]) > 8) {
                        left = min(left, x)
                        right = max(right, x)
                        top = min(top, y)
                        bottom = max(bottom, y)
                    }
                }
            }
            val bounds = if (right < left || bottom < top) Rect() else Rect(left, top, right + 1, bottom + 1)
            signatureBoundsCache[bitmap] = bounds
            bounds
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (maxWidth <= 1f) return emptyList()
        val words = text.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) result += line
            line = ""
            val pieces = TextRules.graphemes(word)
            for (piece in pieces) {
                val joined = line + piece
                if (line.isNotEmpty() && paint.measureText(joined) > maxWidth) {
                    result += line
                    line = piece
                } else {
                    line = joined
                }
            }
        }
        if (line.isNotEmpty()) result += line
        return result
    }
}
