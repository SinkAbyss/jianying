package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.util.WeakHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Background actually drawn by [FrameRenderer]: a solid color or a prepared blur bitmap. */
internal sealed interface MaterializedBackground {
    data class Solid(val color: Int) : MaterializedBackground

    /** @param bitmap blur bitmap sized for the canvas aspect; drawn to the whole canvas. */
    data class Photo(val bitmap: Bitmap, val sigmaRatio: Double, val inks: BlurInkMaps) : MaterializedBackground
}

/**
 * Builds the blur background bitmap on the CPU so that API 29 devices and the JPEG export path use
 * exactly the same pixels.
 *
 * The photo is cover-scaled to the canvas aspect, downsampled to a bounded work size and blurred
 * with three edge-clamped box passes, which is the standard O(n) approximation of a gaussian. The
 * work size is a fixed fraction of the canvas long edge and the blur strength is expressed as a
 * fraction of that long edge, so preview and export keep the same visual blur scale.
 */
internal object FrameBackgroundRenderer {
    /** Work bitmap long edge as a fraction of the canvas long edge. */
    private const val WORK_LONG_EDGE_RATIO = 0.36
    private const val MIN_WORK_LONG_EDGE = 160
    private const val MAX_WORK_LONG_EDGE = 720
    private const val BOX_PASSES = 3
    private const val CACHE_ENTRIES_PER_SOURCE = 3

    private val cache = WeakHashMap<Bitmap, LinkedHashMap<String, MaterializedBackground>>()

    fun materialize(source: Bitmap, geometry: FrameGeometry, style: BorderStyle): MaterializedBackground =
        when (val background = style.background) {
            is BorderBackgroundSpec.Solid -> MaterializedBackground.Solid(background.color)
            is BorderBackgroundSpec.Blur -> {
                val key = "${geometry.canvasWidth}x${geometry.canvasHeight}:${background.sigmaRatio}"
                synchronized(cache) { cache[source]?.get(key) }?.let { return it }
                val bitmap = blur(
                        source = source,
                        canvasWidth = geometry.canvasWidth,
                        canvasHeight = geometry.canvasHeight,
                        sigmaRatio = background.sigmaRatio,
                    )
                val materialized = MaterializedBackground.Photo(bitmap, background.sigmaRatio, BlurForeground.prepare(bitmap, source, geometry))
                synchronized(cache) {
                    val entries = cache.getOrPut(source) { LinkedHashMap() }
                    // Another worker may have completed the same key. Keep one immutable result.
                    entries[key]?.let {
                        bitmap.recycle()
                        return it
                    }
                    entries[key] = materialized
                    while (entries.size > CACHE_ENTRIES_PER_SOURCE) {
                        val oldest = entries.keys.firstOrNull() ?: break
                        entries.remove(oldest)
                    }
                    materialized
                }
            }
        }

    /** Cover-crops [source] to the canvas aspect, downsamples it and applies the gaussian blur. */
    fun blur(source: Bitmap, canvasWidth: Int, canvasHeight: Int, sigmaRatio: Double): Bitmap {
        require(source.width > 0 && source.height > 0) { "照片尺寸必须大于 0" }
        require(canvasWidth > 0 && canvasHeight > 0) { "画布尺寸必须大于 0" }
        val canvasLongEdge = max(canvasWidth, canvasHeight)
        val workLongEdge = (canvasLongEdge * WORK_LONG_EDGE_RATIO)
            .roundToInt()
            .coerceIn(MIN_WORK_LONG_EDGE, MAX_WORK_LONG_EDGE)
        val scale = workLongEdge.toFloat() / canvasLongEdge
        val workWidth = max(1, (canvasWidth * scale).roundToInt())
        val workHeight = max(1, (canvasHeight * scale).roundToInt())

        val crop = coverCrop(source, canvasWidth.toFloat() / canvasHeight)
        val work = Bitmap.createBitmap(workWidth, workHeight, Bitmap.Config.ARGB_8888)
        Canvas(work).drawBitmap(
            source,
            crop,
            RectF(0f, 0f, workWidth.toFloat(), workHeight.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )

        val sigma = (sigmaRatio * workLongEdge).coerceAtLeast(0.0)
        if (sigma >= 0.6) {
            val pixels = IntArray(workWidth * workHeight)
            work.getPixels(pixels, 0, workWidth, 0, 0, workWidth, workHeight)
            applyGaussian(pixels, workWidth, workHeight, sigma)
            work.setPixels(pixels, 0, workWidth, 0, 0, workWidth, workHeight)
        }
        return work
    }

    /** Center crop of [source] that matches the requested aspect ratio (cover, never upscales the rect). */
    internal fun coverCrop(source: Bitmap, targetAspect: Float): Rect {
        val sourceAspect = source.width.toFloat() / source.height
        return if (sourceAspect > targetAspect) {
            val width = (source.height * targetAspect).roundToInt().coerceIn(1, source.width)
            val left = (source.width - width) / 2
            Rect(left, 0, left + width, source.height)
        } else {
            val height = (source.width / targetAspect).roundToInt().coerceIn(1, source.height)
            val top = (source.height - height) / 2
            Rect(0, top, source.width, top + height)
        }
    }

    /** Three box passes on the RGB channels; alpha is preserved so opaque photos stay opaque. */
    internal fun applyGaussian(pixels: IntArray, width: Int, height: Int, sigma: Double) {
        val radii = boxRadiiForGaussian(sigma, BOX_PASSES)
        if (radii.isEmpty()) return
        val channels = Array(3) { IntArray(pixels.size) }
        val alpha = IntArray(pixels.size)
        for (index in pixels.indices) {
            alpha[index] = (pixels[index] ushr 24) and 0xFF
            channels[0][index] = (pixels[index] shr 16) and 0xFF
            channels[1][index] = (pixels[index] shr 8) and 0xFF
            channels[2][index] = pixels[index] and 0xFF
        }
        val scratch = IntArray(pixels.size)
        for (channel in channels.indices) {
            for (radius in radii) {
                boxBlurHorizontal(channels[channel], scratch, width, height, radius)
                boxBlurVertical(scratch, channels[channel], width, height, radius)
            }
        }
        for (index in pixels.indices) {
            pixels[index] = (alpha[index] shl 24) or
                (channels[0][index].coerceIn(0, 255) shl 16) or
                (channels[1][index].coerceIn(0, 255) shl 8) or
                channels[2][index].coerceIn(0, 255)
        }
    }

    /** Radii of the [passes] box passes whose combined kernel approximates a gaussian of [sigma]. */
    internal fun boxRadiiForGaussian(sigma: Double, passes: Int): IntArray {
        if (sigma <= 0.0 || passes <= 0) return IntArray(0)
        val idealWidth = sqrt((12.0 * sigma * sigma / passes) + 1.0)
        var lower = floor(idealWidth).toInt()
        if (lower % 2 == 0) lower--
        if (lower < 1) lower = 1
        val upper = lower + 2
        val idealMiddle = (12.0 * sigma * sigma - passes * lower * lower - 4.0 * passes * lower - 3.0 * passes) /
            (-4.0 * lower - 4.0)
        val middle = idealMiddle.roundToInt()
        return IntArray(passes) { index -> ((if (index < middle) lower else upper) - 1) / 2 }
    }

    /** Edge-clamped horizontal box pass (edge pixels extend), O(pixels) per row. */
    private fun boxBlurHorizontal(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) {
            source.copyInto(target)
            return
        }
        val window = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var sum = 0L
            for (offset in -radius..radius) {
                sum += source[row + offset.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                target[row + x] = (sum / window).toInt()
                sum += source[row + (x + radius + 1).coerceIn(0, width - 1)]
                sum -= source[row + (x - radius).coerceIn(0, width - 1)]
            }
        }
    }

    private fun boxBlurVertical(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) {
            source.copyInto(target)
            return
        }
        val window = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0L
            for (offset in -radius..radius) {
                sum += source[offset.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                target[y * width + x] = (sum / window).toInt()
                sum += source[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                sum -= source[(y - radius).coerceIn(0, height - 1) * width + x]
            }
        }
    }

    /** Test/diagnostic helper: clears the prepared-background cache. */
    internal fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /** Test/diagnostic helper: number of cached source bitmaps. */
    internal fun cacheSize(): Int = synchronized(cache) { cache.size }

    /** Convenience for measuring the work buffer without duplicating the sizing rule. */
    internal fun workSize(canvasWidth: Int, canvasHeight: Int): Pair<Int, Int> {
        val canvasLongEdge = max(canvasWidth, canvasHeight)
        val workLongEdge = (canvasLongEdge * WORK_LONG_EDGE_RATIO)
            .roundToInt()
            .coerceIn(MIN_WORK_LONG_EDGE, MAX_WORK_LONG_EDGE)
        val scale = workLongEdge.toFloat() / canvasLongEdge
        return max(1, (canvasWidth * scale).roundToInt()) to max(1, (canvasHeight * scale).roundToInt())
    }
}
