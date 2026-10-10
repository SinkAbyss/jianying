package com.sinkabyss.framewatermark

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Oklch/D65 color used by the border background chain.
 *
 * Lightness is the Oklab L (0 = black, 1 = white), chroma is the Oklab radius and hue is the
 * Oklab hue angle in degrees. The type is intentionally free of `android.graphics` imports so the
 * color rules can be unit tested without an Android runtime.
 */
internal data class Oklch(val lightness: Double, val chroma: Double, val hue: Double) {
    companion object {
        fun of(lightness: Double, chroma: Double, hue: Double): Oklch =
            Oklch(lightness, chroma.coerceAtLeast(0.0), normalizeHue(hue))

        fun normalizeHue(hue: Double): Double = ((hue % 360.0) + 360.0) % 360.0
    }
}

/**
 * Deterministic sRGB <-> Oklab/Oklch conversion with explicit sRGB gamut mapping.
 *
 * The transfer functions follow the W3C sRGB definition and the Oklab conversion follows the
 * original author's linear-sRGB matrices. Out-of-gamut results reduce chroma instead of clipping
 * channels, which avoids the hue shift a naive clamp would introduce.
 */
internal object ColorScience {
    /** Bumped whenever the color rules change so caches and records can be invalidated. */
    const val ALGORITHM_VERSION = 6

    private const val GAMUT_SEARCH_STEPS = 22

    fun decodeSrgb(channel: Int): Double {
        val value = channel.coerceIn(0, 255) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }

    fun encodeSrgb(linear: Double): Int {
        val clamped = linear.coerceIn(0.0, 1.0)
        val value = if (clamped <= 0.0031308) clamped * 12.92 else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
        return (value * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun toLinear(argb: Int): DoubleArray = doubleArrayOf(
        decodeSrgb((argb shr 16) and 0xFF),
        decodeSrgb((argb shr 8) and 0xFF),
        decodeSrgb(argb and 0xFF),
    )

    fun toOklab(linear: DoubleArray): DoubleArray {
        val l = 0.4122214708 * linear[0] + 0.5363325363 * linear[1] + 0.0514459929 * linear[2]
        val m = 0.2119034982 * linear[0] + 0.6806995451 * linear[1] + 0.1073969566 * linear[2]
        val s = 0.0883024619 * linear[0] + 0.2817188376 * linear[1] + 0.6299787005 * linear[2]
        val lc = cbrt(l)
        val mc = cbrt(m)
        val sc = cbrt(s)
        return doubleArrayOf(
            0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc,
        )
    }

    fun toLinearRgb(oklab: DoubleArray): DoubleArray {
        val l = (oklab[0] + 0.3963377774 * oklab[1] + 0.2158037573 * oklab[2]).pow(3)
        val m = (oklab[0] - 0.1055613458 * oklab[1] - 0.0638541728 * oklab[2]).pow(3)
        val s = (oklab[0] - 0.0894841775 * oklab[1] - 1.2914855480 * oklab[2]).pow(3)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }

    fun toOkLab(color: Oklch): DoubleArray = doubleArrayOf(
        color.lightness,
        color.chroma * cos(Math.toRadians(color.hue)),
        color.chroma * sin(Math.toRadians(color.hue)),
    )

    fun toOklch(argb: Int): Oklch {
        val lab = toOklab(toLinear(argb))
        val chroma = hypot(lab[1], lab[2])
        val hue = if (chroma < 1e-6) 0.0 else Oklch.normalizeHue(Math.toDegrees(atan2(lab[2], lab[1])))
        return Oklch(lab[0], chroma, hue)
    }

    fun toOklch(oklab: DoubleArray): Oklch {
        val chroma = hypot(oklab[1], oklab[2])
        val hue = if (chroma < 1e-6) 0.0 else Oklch.normalizeHue(Math.toDegrees(atan2(oklab[2], oklab[1])))
        return Oklch(oklab[0], chroma, hue)
    }

    fun isInGamut(linear: DoubleArray, tolerance: Double = 1e-4): Boolean =
        linear.all { it >= -tolerance && it <= 1.0 + tolerance }

    /**
     * Encodes to an opaque sRGB ARGB integer. Out-of-gamut Oklch values keep lightness and hue and
     * lose chroma until they fit the sRGB cube.
     */
    fun toArgb(color: Oklch): Int {
        val lightness = color.lightness.coerceIn(0.0, 1.0)
        if (lightness <= 0.0) return 0xFF000000.toInt()
        if (lightness >= 1.0) return 0xFFFFFFFF.toInt()
        var linear = toLinearRgb(toOkLab(color.copy(lightness = lightness)))
        if (!isInGamut(linear)) {
            var low = 0.0
            var high = color.chroma
            repeat(GAMUT_SEARCH_STEPS) {
                val mid = (low + high) / 2.0
                val candidate = toLinearRgb(toOkLab(Oklch(lightness, mid, color.hue)))
                if (isInGamut(candidate)) {
                    low = mid
                    linear = candidate
                } else {
                    high = mid
                }
            }
            if (!isInGamut(linear)) linear = toLinearRgb(toOkLab(Oklch(lightness, low, color.hue)))
        }
        return argbFromLinear(linear)
    }

    fun argbFromLinear(linear: DoubleArray): Int = (0xFF shl 24) or
        (encodeSrgb(linear[0]) shl 16) or
        (encodeSrgb(linear[1]) shl 8) or
        encodeSrgb(linear[2])

    fun relativeLuminance(argb: Int): Double {
        val linear = toLinear(argb)
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
    }

    fun contrastRatio(first: Int, second: Int): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun hueDelta(first: Double, second: Double): Double {
        val delta = abs(Oklch.normalizeHue(first) - Oklch.normalizeHue(second)) % 360.0
        return if (delta > 180.0) 360.0 - delta else delta
    }

    fun channel(argb: Int, shift: Int): Int = (argb shr shift) and 0xFF
}
