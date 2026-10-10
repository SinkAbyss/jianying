package com.sinkabyss.framewatermark

/** The five product values of the 边框背景 setting, in the order shown in the UI. */
internal enum class BorderBackgroundMode(val title: String, val summary: String) {
    WHITE("白色", "固定白底与深色文字"),
    BLACK("黑色", "固定黑底与浅色文字"),
    LIGHT_ADAPTIVE("浅色自适应", "从照片取色的浅色背景"),
    DARK_ADAPTIVE("深色自适应", "从照片取色的深色背景"),
    BLUR("模糊", "照片高斯模糊铺底"),
    ;

    val isAdaptive: Boolean
        get() = this == LIGHT_ADAPTIVE || this == DARK_ADAPTIVE

    companion object {
        val DEFAULT = WHITE

        fun fromStorage(value: String?): BorderBackgroundMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/** Which adaptive route produced the background; kept so evidence sheets can compare both. */
internal enum class AdaptiveVariant {
    /** Route A: dominant chromatic representative color, then a controlled lightness/chroma step. */
    REPRESENTATIVE_TRANSFORM,

    /** Route B: pick the photo's light or dark palette cluster, then a milder step. */
    CLUSTER_PICK,
    ;

    companion object {
        val DEFAULT = REPRESENTATIVE_TRANSFORM
    }
}

/** Background of a rendered frame: either one solid color or a real photo blur bitmap. */
internal sealed interface BorderBackgroundSpec {
    data class Solid(val color: Int) : BorderBackgroundSpec

    /** @param sigmaRatio gaussian sigma as a fraction of the canvas long edge. */
    data class Blur(val sigmaRatio: Double) : BorderBackgroundSpec
}

/** Colors of the signature, the primary text row and the secondary text rows. */
internal data class ForegroundStyle(
    val signature: Int,
    val primary: Int,
    val secondary: Int,
)

/** Complete solved style consumed by both the live preview and the JPEG export. */
internal data class BorderStyle(
    val mode: BorderBackgroundMode,
    val background: BorderBackgroundSpec,
    val foreground: ForegroundStyle,
    val representative: Oklch?,
    val neutralInput: Boolean,
    val algorithmVersion: Int = ColorScience.ALGORITHM_VERSION,
) {
    val solidColor: Int? get() = (background as? BorderBackgroundSpec.Solid)?.color

    companion object {
        val White = BorderStyle(
            mode = BorderBackgroundMode.WHITE,
            background = BorderBackgroundSpec.Solid(0xFFF5F5F5.toInt()),
            foreground = ForegroundStyle(SIGNATURE_ON_WHITE, PRIMARY_ON_WHITE, SECONDARY_ON_WHITE),
            representative = null,
            neutralInput = true,
        )
        val Black = BorderStyle(
            mode = BorderBackgroundMode.BLACK,
            background = BorderBackgroundSpec.Solid(0xFF141414.toInt()),
            foreground = ForegroundStyle(SIGNATURE_ON_BLACK, PRIMARY_ON_BLACK, SECONDARY_ON_BLACK),
            representative = null,
            neutralInput = true,
        )

        const val PRIMARY_ON_WHITE = 0xFF1A1A1C.toInt()
        const val SECONDARY_ON_WHITE = 0xFF45464A.toInt()
        const val SIGNATURE_ON_WHITE = PRIMARY_ON_WHITE
        const val PRIMARY_ON_BLACK = 0xFFF4F4F6.toInt()
        const val SECONDARY_ON_BLACK = 0xFFB9BABE.toInt()
        const val SIGNATURE_ON_BLACK = PRIMARY_ON_BLACK
    }
}

/**
 * Turns one [PhotoColorAnalysis] plus the chosen mode into the final background and foreground
 * colors. R1 uses separate area-led muted / richer hue families, then controlled Oklch tones.
 * Parameters are engineering candidates checked on full frames; no runtime reference targets,
 * vendor algorithm claims, or implied visual acceptance.
 */
internal object BorderStyleResolver {
    /** Light adaptive target lightness (reference light bands: 0.76-0.86). */
    private const val LIGHT_TARGET_LIGHTNESS = 0.875
    private const val LIGHT_LIGHTNESS_RANGE = 0.04
    private const val LIGHT_MIN_LIGHTNESS = 0.82
    private const val LIGHT_MAX_LIGHTNESS = 0.90
    private const val LIGHT_CHROMA_SCALE = 0.70
    private const val LIGHT_MIN_CHROMA = 0.024
    private const val LIGHT_MAX_CHROMA = 0.07

    /** Dark adaptive target lightness (video dark bands: 0.46-0.57). */
    private const val DARK_LIGHTNESS_BASE = 0.36
    private const val DARK_LIGHTNESS_TRACK = 0.25
    private const val DARK_MIN_LIGHTNESS = 0.30
    private const val DARK_MAX_LIGHTNESS = 0.56
    private const val DARK_CHROMA_SCALE = 1.05
    private const val DARK_MIN_CHROMA = 0.018
    private const val DARK_MAX_CHROMA = 0.12

    /** Neutral fallbacks for photos without usable chroma. */
    private const val NEUTRAL_LIGHT_LIGHTNESS = 0.93
    private const val NEUTRAL_DARK_LIGHTNESS = 0.27

    /** Foreground targets; light bands use a same-hue dark ink, dark bands a light ink. */
    private const val INK_ON_LIGHT_LIGHTNESS = 0.26
    private const val INK_ON_LIGHT_SECONDARY_LIGHTNESS = 0.45
    private const val INK_ON_LIGHT_CHROMA_SCALE = 1.30
    private const val INK_ON_LIGHT_MAX_CHROMA = 0.055
    private const val INK_ON_DARK_LIGHTNESS = 0.96
    private const val INK_ON_DARK_SECONDARY_LIGHTNESS = 0.84
    private const val INK_ON_DARK_CHROMA_SCALE = 0.35
    private const val INK_ON_DARK_MAX_CHROMA = 0.03

    /** Gaussian sigma of the blur background as a fraction of the canvas long edge. */
    const val BLUR_SIGMA_RATIO = 0.03

    /** WCAG contrast the primary text should keep against its background. */
    private const val MIN_TEXT_CONTRAST = 4.5

    fun resolve(
        mode: BorderBackgroundMode,
        analysis: PhotoColorAnalysis?,
        variant: AdaptiveVariant = AdaptiveVariant.DEFAULT,
    ): BorderStyle = when (mode) {
        BorderBackgroundMode.WHITE -> BorderStyle.White
        BorderBackgroundMode.BLACK -> BorderStyle.Black
        BorderBackgroundMode.LIGHT_ADAPTIVE -> adaptive(mode, analysis, light = true, variant = variant)
        BorderBackgroundMode.DARK_ADAPTIVE -> adaptive(mode, analysis, light = false, variant = variant)
        BorderBackgroundMode.BLUR -> blur(analysis)
    }

    private fun adaptive(
        mode: BorderBackgroundMode,
        analysis: PhotoColorAnalysis?,
        light: Boolean,
        variant: AdaptiveVariant,
    ): BorderStyle {
        val neutral = analysis == null || analysis.neutral
        val base = if (neutral) {
            Oklch(if (light) NEUTRAL_LIGHT_LIGHTNESS else NEUTRAL_DARK_LIGHTNESS, 0.0, 0.0)
        } else if (variant == AdaptiveVariant.CLUSTER_PICK) {
            clusterPick(analysis!!, light)
        } else {
            representativeTransform(analysis!!, light)
        }
        val color = ColorScience.toArgb(base)
        return BorderStyle(
            mode = mode,
            background = BorderBackgroundSpec.Solid(color),
            foreground = foregroundFor(color, base, light),
            representative = analysis?.takeUnless { it.neutral }?.let { if (light) it.lightRepresentative else it.darkRepresentative },
            neutralInput = neutral,
        )
    }

    /** Route A: keep the dominant representative hue and place lightness/chroma in the target band. */
    private fun representativeTransform(analysis: PhotoColorAnalysis, light: Boolean): Oklch {
        val source = if (light) analysis.lightRepresentative else analysis.darkRepresentative
        return if (light) {
            Oklch.of(
                lightness = (LIGHT_TARGET_LIGHTNESS -
                    LIGHT_LIGHTNESS_RANGE * (analysis.meanLightness - 0.5)) *
                    (1.0 - 0.05 * ((0.80 - analysis.chromaticShare) / 0.20).coerceIn(0.0, 1.0)),
                chroma = (source.chroma * LIGHT_CHROMA_SCALE).coerceIn(LIGHT_MIN_CHROMA, LIGHT_MAX_CHROMA),
                hue = source.hue,
            ).let { it.copy(lightness = it.lightness.coerceIn(LIGHT_MIN_LIGHTNESS, LIGHT_MAX_LIGHTNESS)) }
        } else {
            Oklch.of(
                lightness = (DARK_LIGHTNESS_BASE + DARK_LIGHTNESS_TRACK * analysis.meanLightness)
                    .coerceIn(DARK_MIN_LIGHTNESS, DARK_MAX_LIGHTNESS),
                chroma = (source.chroma * if (analysis.darkFamilyFallback) 0.85 else DARK_CHROMA_SCALE)
                    .coerceIn(DARK_MIN_CHROMA, DARK_MAX_CHROMA),
                // Bounded, continuous yellow-green adjustment; no photo identity or target colors.
                hue = source.hue + 16.0 * (1.0 - kotlin.math.abs(source.hue - 112.0) / 28.0).coerceIn(0.0, 1.0),
            )
        }
    }

    /** Route B: use the photo's own light or dark palette cluster and only mild adjustment. */
    private fun clusterPick(analysis: PhotoColorAnalysis, light: Boolean): Oklch {
        val eligible = analysis.clusters.filter { it.weight >= 0.08 }.ifEmpty { analysis.clusters }
        val picked = eligible.maxByOrNull { if (light) it.color.lightness else -it.color.lightness }!!
        return if (light) {
            Oklch.of(
                lightness = (0.86 + (picked.color.lightness - 0.70) * 0.10)
                    .coerceIn(LIGHT_MIN_LIGHTNESS, 0.92),
                chroma = (picked.color.chroma * LIGHT_CHROMA_SCALE).coerceIn(0.018, 0.07),
                hue = picked.color.hue,
            )
        } else {
            Oklch.of(
                lightness = (0.30 + picked.color.lightness * 0.30).coerceIn(0.26, DARK_MAX_LIGHTNESS),
                chroma = (picked.color.chroma * DARK_CHROMA_SCALE).coerceIn(0.015, DARK_MAX_CHROMA),
                hue = picked.color.hue,
            )
        }
    }

    /** Blur keeps a neutral, readable ink chosen from the luminance of the band region. */
    private fun blur(analysis: PhotoColorAnalysis?): BorderStyle {
        val bandLuminance = analysis?.bandLuminance ?: 0.2
        val gray = ColorScience.argbFromLinear(doubleArrayOf(bandLuminance, bandLuminance, bandLuminance))
        // Fallback only; actual blur ink is solved against materialized glyph regions by the renderer.
        val darkInk = ColorScience.contrastRatio(0xff000000.toInt(), gray) >= ColorScience.contrastRatio(0xffffffff.toInt(), gray)
        val primary = if (darkInk) BorderStyle.PRIMARY_ON_WHITE else BorderStyle.PRIMARY_ON_BLACK
        val secondary = if (darkInk) BorderStyle.SECONDARY_ON_WHITE else BorderStyle.SECONDARY_ON_BLACK
        val adjusted = ensureContrast(primary, gray, darkInk)
        val adjustedSecondary = ensureContrast(secondary, gray, darkInk)
        return BorderStyle(
            mode = BorderBackgroundMode.BLUR,
            background = BorderBackgroundSpec.Blur(BLUR_SIGMA_RATIO),
            foreground = ForegroundStyle(adjusted, adjusted, adjustedSecondary),
            representative = null,
            neutralInput = analysis?.neutral ?: true,
        )
    }

    private fun foregroundFor(background: Int, base: Oklch, light: Boolean): ForegroundStyle {
        if (base.chroma >= 0.005) return colouredForegroundFor(background, light)
        val primary = if (light) {
            ColorScience.toArgb(
                Oklch.of(
                    INK_ON_LIGHT_LIGHTNESS,
                    (base.chroma * INK_ON_LIGHT_CHROMA_SCALE).coerceAtMost(INK_ON_LIGHT_MAX_CHROMA),
                    base.hue,
                ),
            )
        } else {
            ColorScience.toArgb(
                Oklch.of(
                    INK_ON_DARK_LIGHTNESS,
                    (base.chroma * INK_ON_DARK_CHROMA_SCALE).coerceAtMost(INK_ON_DARK_MAX_CHROMA),
                    base.hue,
                ),
            )
        }
        val secondary = if (light) {
            ColorScience.toArgb(
                Oklch.of(
                    INK_ON_LIGHT_SECONDARY_LIGHTNESS,
                    (base.chroma * INK_ON_LIGHT_CHROMA_SCALE).coerceAtMost(INK_ON_LIGHT_MAX_CHROMA),
                    base.hue,
                ),
            )
        } else {
            ColorScience.toArgb(
                Oklch.of(
                    INK_ON_DARK_SECONDARY_LIGHTNESS,
                    (base.chroma * INK_ON_DARK_CHROMA_SCALE).coerceAtMost(INK_ON_DARK_MAX_CHROMA),
                    base.hue,
                ),
            )
        }
        val adjustedPrimary = ensureContrast(primary, background, light)
        val adjustedSecondary = ensureContrast(secondary, background, light)
        return ForegroundStyle(signature = adjustedPrimary, primary = adjustedPrimary, secondary = adjustedSecondary)
    }

    /**
     * Nine local coloured vivo bands show three different ink tones, with visible band hue.
     * The measured contrast minima are signature 3.05, primary 2.52, secondary 1.96.
     * These are visual-reference floors for solid adaptive bands, not WCAG AA claims.
     * Neutral, fixed black/white and spatially varying blur keep their existing contrast policy.
     */
    private fun colouredForegroundFor(background: Int, light: Boolean): ForegroundStyle {
        val band = ColorScience.toOklch(background)
        fun ink(lightness: Double, chromaScale: Double, maximumChroma: Double, minimumContrast: Double): Int {
            val seed = ColorScience.toArgb(Oklch.of(lightness, (band.chroma * chromaScale).coerceAtMost(maximumChroma), band.hue))
            return ensureContrast(seed, background, light, minimumContrast)
        }
        return if (light) {
            ForegroundStyle(
                signature = ink(band.lightness - 0.40, 1.40, 0.12, 3.0),
                primary = ink(band.lightness - 0.32, 1.20, 0.10, 2.5),
                secondary = ink(band.lightness - 0.27, 1.05, 0.09, 2.0),
            )
        } else {
            ForegroundStyle(
                // P01: a weakly coloured dark band made the former signature look white.
                // Keep it above the information rows, but give its hue more visible chroma.
                signature = ink(0.90, 1.80, 0.12, 3.0),
                primary = ink(0.85, 0.95, 0.12, 2.5),
                secondary = ink(0.82, 1.00, 0.13, 2.0),
            )
        }
    }

    /**
     * Pushes (or pulls) the ink lightness until the WCAG contrast against the background reaches the
     * text target. The adjustment only touches lightness, so the paired hue stays intact.
     */
    private fun ensureContrast(ink: Int, background: Int, darker: Boolean, minimumContrast: Double = MIN_TEXT_CONTRAST): Int {
        if (ColorScience.contrastRatio(ink, background) >= minimumContrast) return ink
        var color = ColorScience.toOklch(ink)
        repeat(24) {
            val step = if (darker) -0.02 else 0.02
            val next = (color.lightness + step).coerceIn(0.0, 1.0)
            color = color.copy(lightness = next)
            val candidate = ColorScience.toArgb(color)
            if (ColorScience.contrastRatio(candidate, background) >= minimumContrast) return candidate
        }
        return ColorScience.toArgb(color)
    }
}
