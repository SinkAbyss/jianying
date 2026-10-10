package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

internal interface WatermarkSaveRequest {
    val photo: LoadedPhoto
    suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int? = null): Bitmap
}

internal data class ArtSignatureOptions(
    val signature: Bitmap,
    val horizontal: Int = 1,
    val vertical: Int = 2,
    val opacityPercent: Int = 100,
    val model: String = "",
    val phrase: String = "",
    val date: String = "",
    val place: String = "",
    val colors: ForegroundColors = ForegroundColors(),
    val automaticInk: Int? = null,
    val signaturePercent: Int = 100,
)

/** Photo-only output; one drawing routine for the fixed preview and original-size export. */
internal object ArtSignatureRenderer {
    private val boundsCache = WeakHashMap<Bitmap, Rect>()
    private data class TextLine(val text: String, val paint: Paint)
    private data class Layout(val bounds: Rect, val sigWidth: Float, val sigHeight: Float,
        val groupWidth: Float, val groupHeight: Float, val fit: Float, val left: Float, val top: Float,
        val lines: List<TextLine>, val lineHeights: List<Float>, val gap: Float, val signatureGap: Float)

    /** Sample the oriented preview once. Export uses the resolved ink in its click-time snapshot. */
    fun resolve(source: Bitmap, width: Int, height: Int, options: ArtSignatureOptions): ArtSignatureOptions {
        if (options.automaticInk != null || (options.colors.signature.isCustom && options.colors.info.isCustom)) return options
        val layout = measure(width, height, options)
        var luminance = 0.0
        for (y in 0 until 32) for (x in 0 until 32) {
            val sampleX = ((layout.left + layout.groupWidth * layout.fit * (x + 0.5f) / 32) / width * source.width)
                .toInt().coerceIn(0, source.width - 1)
            val sampleY = ((layout.top + layout.groupHeight * layout.fit * (y + 0.5f) / 32) / height * source.height)
                .toInt().coerceIn(0, source.height - 1)
            luminance += ColorScience.relativeLuminance(source.getPixel(sampleX, sampleY))
        }
        luminance /= 1024
        val dark = 0xFF1A1A1A.toInt()
        val light = 0xFFF4F4F4.toInt()
        fun contrast(ink: Int): Double {
            val inkLuminance = ColorScience.relativeLuminance(ink)
            return (maxOf(luminance, inkLuminance) + 0.05) / (minOf(luminance, inkLuminance) + 0.05)
        }
        return options.copy(automaticInk = if (contrast(dark) > contrast(light)) dark else light)
    }

    fun render(source: Bitmap, options: ArtSignatureOptions): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        try {
            draw(Canvas(result), source, source.width, source.height, options)
            return result
        } catch (failure: Throwable) {
            result.recycle()
            throw failure
        }
    }

    private fun measure(width: Int, height: Int, options: ArtSignatureOptions): Layout {
        val shortEdge = min(width, height).toFloat()
        val ratio = width.toFloat() / height
        // Approved A-stage adaptation: portrait is quieter than landscape.
        val factor = when {
            ratio >= 1.2f -> 1f
            ratio <= 0.8f -> 0.8f
            else -> 0.8f + 0.2f * (ratio - 0.8f) / 0.4f
        }
        val unit = shortEdge * factor
        val margin = shortEdge * 0.042f
        val maxWidth = (width - margin * 2).coerceAtLeast(1f)
        val bounds = signatureBounds(options.signature)
        val sigHeight = unit * 0.155f * options.signaturePercent.coerceIn(25, 200) / 100f
        val sigWidth = sigHeight * bounds.width() / bounds.height().coerceAtLeast(1)
        val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            textSize = unit * 0.036f
        }
        val detailPaint = Paint(modelPaint).apply { textSize = unit * 0.021f }
        val lines = buildList {
            listOf(options.model to modelPaint, options.phrase to detailPaint,
                options.date to detailPaint, options.place to detailPaint).forEach { (text, paint) ->
                var line = ""
                TextRules.graphemes(text).forEach { character ->
                    if (line.isNotEmpty() && paint.measureText(line + character) > maxWidth) {
                        add(TextLine(line, paint)); line = ""
                    }
                    line += character
                }
                if (line.isNotBlank()) add(TextLine(line, paint))
            }
        }
        val gap = unit * 0.01f
        val signatureGap = if (lines.isEmpty()) 0f else unit * 0.016f
        val lineHeights = lines.map { (it.paint.fontMetrics.descent - it.paint.fontMetrics.ascent) }
        val groupWidth = maxOf(sigWidth, lines.maxOfOrNull { it.paint.measureText(it.text) } ?: 0f)
        val groupHeight = sigHeight + signatureGap + lineHeights.sum() + gap * (lines.size - 1).coerceAtLeast(0)
        val fit = min(1f, min(maxWidth / groupWidth.coerceAtLeast(1f),
            (height - margin * 2).coerceAtLeast(1f) / groupHeight.coerceAtLeast(1f)))
        val availableX = (width - margin * 2 - groupWidth * fit).coerceAtLeast(0f)
        val availableY = (height - margin * 2 - groupHeight * fit).coerceAtLeast(0f)
        val left = margin + availableX * options.horizontal.coerceIn(0, 2) / 2
        val top = margin + availableY * options.vertical.coerceIn(0, 2) / 2
        return Layout(bounds, sigWidth, sigHeight, groupWidth, groupHeight, fit, left, top,
            lines, lineHeights, gap, signatureGap)
    }

    fun draw(canvas: Canvas, source: Bitmap, width: Int, height: Int, options: ArtSignatureOptions) {
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        val solved = resolve(source, width, height, options)
        val defaultInk = solved.automaticInk ?: Color.WHITE
        with(measure(width, height, solved)) {
        lines.forEach { it.paint.color = solved.colors.info.resolve(defaultInk) }
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
            (options.opacityPercent.coerceIn(0, 100) * 2.55f).roundToInt())
        try {
            canvas.translate(left, top)
            canvas.scale(fit, fit)
            fun alignedX(itemWidth: Float): Float = (groupWidth - itemWidth) * options.horizontal.coerceIn(0, 2) / 2
            bitmapPaint.colorFilter = PorterDuffColorFilter(solved.colors.signature.resolve(defaultInk), PorterDuff.Mode.SRC_IN)
            val sigX = alignedX(sigWidth)
            canvas.drawBitmap(options.signature, bounds, RectF(sigX, 0f, sigX + sigWidth, sigHeight), bitmapPaint)
            var y = sigHeight + signatureGap
            lines.forEachIndexed { index, line ->
                canvas.drawText(line.text, alignedX(line.paint.measureText(line.text)), y - line.paint.fontMetrics.ascent, line.paint)
                y += lineHeights[index] + gap
            }
        } finally {
            canvas.restoreToCount(layer)
        }
        }
    }

    private fun signatureBounds(bitmap: Bitmap): Rect = synchronized(boundsCache) {
        boundsCache.getOrPut(bitmap) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            pixels.forEachIndexed { index, pixel ->
                if (Color.alpha(pixel) > 8) {
                    val x = index % bitmap.width; val y = index / bitmap.width
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            if (right >= left) Rect(left, top, right + 1, bottom + 1)
            else Rect(0, 0, bitmap.width, bitmap.height)
        }
    }
}

internal class ArtSignatureSaveRequest(
    override val photo: LoadedPhoto,
    options: ArtSignatureOptions,
    private val signatureId: String? = null,
) : WatermarkSaveRequest {
    // Captured material ids belong to each photo; resolve ink only after that material is loaded.
    private val options = if (signatureId == null)
        ArtSignatureRenderer.resolve(photo.preview, photo.metadata.width, photo.metadata.height, options) else options

    companion object {
        fun capture(entry: PhotoEntry, previewSignature: Bitmap): ArtSignatureSaveRequest {
            val style = entry.style
            return ArtSignatureSaveRequest(entry.loaded, entry.artDraft.options(previewSignature,
                style.artHorizontal, style.artVertical, style.artOpacity, style.colors, style.artSignaturePercent),
                style.signatureId.orEmpty())
        }
    }

    override suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int?): Bitmap {
        try {
            return render(
                loadPhoto = { PhotoLoader.loadForExport(context, it, allowDownsample, approvedSampleSize) },
                loadSignature = { id -> withContext(Dispatchers.IO) { UserMaterialStore(context).bitmap(id) } },
            )
        } catch (failure: OutOfMemoryError) {
            val nextSample = (if (allowDownsample) approvedSampleSize ?: 1 else 1) * 2
            Log.w("JianyingExport", "Art signature allocation failed; nextSample=$nextSample", failure)
            throw ImageTooLargeException(photo.metadata.width, photo.metadata.height, nextSample)
                .apply { initCause(failure) }
        }
    }

    internal suspend fun render(loadSignature: suspend (String) -> Bitmap? = { error("签名读取器未提供") },
        loadPhoto: suspend (LoadedPhoto) -> Bitmap): Bitmap {
        val actualOptions = signatureId?.let { id ->
            require(id.isNotBlank()) { "请先选择或添加签名" }
            val signature = loadSignature(id) ?: error("签名素材无法读取，请重新选择")
            ArtSignatureRenderer.resolve(photo.preview, photo.metadata.width, photo.metadata.height,
                options.copy(signature = signature, automaticInk = null))
        } ?: options
        val source = loadPhoto(photo)
        try {
            return withContext(Dispatchers.Default) { ArtSignatureRenderer.render(source, actualOptions) }
        } finally { source.recycle() }
    }
}
