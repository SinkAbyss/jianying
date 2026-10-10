package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * One color cluster of the sampled photo palette.
 *
 * @param color mean color of the cluster in Oklch
 * @param weight share of the samples that fell into this cluster (0..1)
 * @param luminance mean sRGB relative luminance of the cluster (WCAG definition)
 */
internal data class PhotoColorCluster(
    val color: Oklch,
    val weight: Double,
    val luminance: Double,
)

/**
 * Deterministic result of the single photo analysis shared by every border background mode.
 *
 * @param clusters palette clusters ordered by descending weight
 * @param representative dominant chromatic color, or a neutral color when the photo has no
 *   meaningful chroma
 * @param neutral true when the photo carries no usable chroma and the neutral branch applies
 * @param chromaticShare share of samples whose chroma is at least [PhotoColorAnalyzer.CHROMATIC_SAMPLE_CHROMA]
 * @param meanLightness mean Oklab lightness of the whole sampled photo
 * @param meanLuminance mean WCAG relative luminance of the whole sampled photo
 * @param bandLuminance mean WCAG relative luminance of the lower region the border band shows
 * @param sampleCount number of samples the analysis is based on
 */
internal data class PhotoColorAnalysis(
    val clusters: List<PhotoColorCluster>,
    val representative: Oklch,
    val neutral: Boolean,
    val chromaticShare: Double,
    val meanLightness: Double,
    val meanLuminance: Double,
    val bandLuminance: Double,
    val sampleCount: Int,
    val algorithmVersion: Int = ColorScience.ALGORITHM_VERSION,
    val lightRepresentative: Oklch = representative,
    val darkRepresentative: Oklch = representative,
    /** True when a rich dark family lacked spatial support and the broader/body family was used. */
    val darkFamilyFallback: Boolean = false,
)

/**
 * Samples the photo on a fixed grid, builds a small Oklab palette with deterministic k-means and
 * derives the statistics the background/foreground resolver needs.
 *
 * The grid is fixed in relative photo coordinates, so the same photo analyzed from a preview-sized
 * or an export-sized bitmap yields the same palette. Analysis never writes to the source bitmap.
 */
internal object PhotoColorAnalyzer {
    /** Fixed 4096-point grid, independent of image filenames and reference targets. */
    const val SAMPLE_GRID = 64

    private const val CLUSTER_COUNT = 5
    private const val KMEANS_ITERATIONS = 12
    private const val KMEANS_SEED = 0x5EED2026L
    private const val CHROMATIC_THRESHOLD = 0.02

    /** A sample counts as colored from this chroma on. */
    const val CHROMATIC_SAMPLE_CHROMA = 0.02

    /**
     * Below this share of colored samples the photo counts as neutral (gray/near-white/near-black or
     * genuinely weak color). Measured on the local inputs: 灰度 0.000, 弱彩 0.014, 暖光/带绿照片 0.13+.
     */
    private const val NEUTRAL_SHARE_LIMIT = 0.08
    private const val HUE_BINS = 12
    private const val HUE_FAMILY_SPAN = 45.0
    private const val BAND_REGION_START = 0.70
    private const val MIN_ALPHA = 16

    fun analyze(bitmap: Bitmap): PhotoColorAnalysis {
        require(bitmap.width > 0 && bitmap.height > 0) { "照片尺寸必须大于 0" }
        val samples = sample(bitmap)
        val clusters = kMeans(samples)
        val chromaticSamples = samples.filter { it.chroma >= CHROMATIC_SAMPLE_CHROMA }
        val chromaticShare = chromaticSamples.size.toDouble() / samples.size

        val meanLightness = samples.sumOf { it.lightness } / samples.size
        val meanLuminance = samples.sumOf { it.luminance } / samples.size
        val bandSamples = samples.filter { it.position >= BAND_REGION_START }
        val bandLuminance = if (bandSamples.isEmpty()) {
            meanLuminance
        } else {
            bandSamples.sumOf { it.luminance } / bandSamples.size
        }

        val chromatic = clusters.filter { it.color.chroma >= CHROMATIC_THRESHOLD }
        val neutral = chromatic.isEmpty() && chromaticShare < NEUTRAL_SHARE_LIMIT
        val representative = when {
            neutral -> Oklch(meanLightness, 0.0, 0.0)
            chromatic.isEmpty() -> dominantSampleFamily(chromaticSamples)
            else -> {
                val best = chromatic.maxByOrNull { it.weight * (it.color.chroma + 0.01) }!!
                val sameFamily = chromatic.filter { ColorScience.hueDelta(it.color.hue, best.color.hue) <= 35.0 }
                val familyWeight = sameFamily.sumOf { it.weight }.coerceAtLeast(1e-9)
                val chroma = (sameFamily.sumOf { it.weight * it.color.chroma } / familyWeight)
                    .coerceAtLeast(best.color.chroma * 0.75)
                Oklch(best.color.lightness, chroma, best.color.hue)
            }
        }

        val muted = if (neutral) representative else balancedFamily(samples, FamilyProfile.MUTED).color
        val ambient = if (neutral) representative else balancedFamily(samples, FamilyProfile.AMBIENT).color
        val rich = if (neutral) Family(representative, 0.0) else balancedFamily(samples, FamilyProfile.RICH)
        val bodySupport = samples.count {
            it.chroma >= CHROMATIC_SAMPLE_CHROMA &&
                ColorScience.hueDelta(sampleHue(it), representative.hue) <= HUE_FAMILY_SPAN
        }.toDouble() / samples.size
        val visibleBody = representative.lightness >= 0.50 && bodySupport >= 0.08
        // A supported colored cluster can carry the visual focus in an otherwise muted photo.
        // This is pixel evidence, not a center/edge weighting or a semantic subject detector.
        val supportedCluster = chromatic.any { it.weight >= 0.08 }
        val lightSource = if (!neutral && chromaticShare < 0.45 && (supportedCluster || visibleBody)) representative else muted
        val richSupported = rich.support >= 0.12
        val darkSource = if (richSupported) rich.color else if (visibleBody) representative else ambient
        return PhotoColorAnalysis(
            clusters = clusters.sortedByDescending { it.weight },
            representative = representative,
            neutral = neutral,
            chromaticShare = chromaticShare,
            meanLightness = meanLightness,
            meanLuminance = meanLuminance,
            bandLuminance = bandLuminance,
            sampleCount = samples.size,
            algorithmVersion = ColorScience.ALGORITHM_VERSION,
            lightRepresentative = if (neutral) representative else lightSource,
            darkRepresentative = if (neutral) representative else darkSource,
            darkFamilyFallback = !neutral && !richSupported,
        )
    }

    private enum class FamilyProfile { AMBIENT, MUTED, RICH }
    private data class Family(val color: Oklch, val support: Double)
    private fun sampleHue(sample: Sample) = Oklch.normalizeHue(Math.toDegrees(kotlin.math.atan2(sample.b, sample.a)))

    /** Area-led hue family, with bounded chroma influence so a small bright accent cannot dominate. */
    private fun balancedFamily(samples: List<Sample>, profile: FamilyProfile): Family {
        val eligible = samples.filter { it.chroma >= 0.012 && it.lightness in 0.12..0.98 }
        if (eligible.isEmpty()) return Family(dominantSampleFamily(samples.filter { it.chroma >= CHROMATIC_SAMPLE_CHROMA }), 0.0)
        fun weight(sample: Sample): Double {
            val areaWeight = kotlin.math.sqrt(minOf(sample.chroma, 0.07)) *
            ((sample.lightness - 0.12) / 0.25).coerceIn(0.0, 1.0) *
            ((0.98 - sample.lightness) / 0.12).coerceIn(0.0, 1.0)
            // Separate target profiles, inspired by Palette's muted / vibrant roles. Saturated
            // highlights shouldn't decide a soft light band; a dark band can carry a richer body hue.
            return when (profile) {
                FamilyProfile.AMBIENT -> areaWeight
                FamilyProfile.MUTED -> areaWeight * ((0.85 - sample.lightness) / 0.22).coerceIn(0.0, 1.0) /
                    (1.0 + (sample.chroma / 0.06) * (sample.chroma / 0.06))
                FamilyProfile.RICH -> areaWeight * ((0.70 - sample.lightness) / 0.18).coerceIn(0.0, 1.0) *
                    ((sample.chroma - 0.025) / 0.04).coerceIn(0.0, 1.0)
            }
        }
        val centers = (0 until 24).map { it * 15.0 }
        if (eligible.sumOf(::weight) <= 1e-9) return Family(dominantSampleFamily(eligible), 0.0)
        val center = centers.maxBy { candidate -> eligible.sumOf { if (ColorScience.hueDelta(sampleHue(it), candidate) < 30.0) weight(it) else 0.0 } }
        val family = eligible.filter { ColorScience.hueDelta(sampleHue(it), center) < 30.0 }
        // A rich accent needs real spatial support. Otherwise plates, tiny flowers, or UI marks
        // can overrule the photograph even after saturation is bounded.
        val supportedShare = family.count { weight(it) > 0.0 }.toDouble() / samples.size
        val mass = family.sumOf(::weight).coerceAtLeast(1e-9)
        return Family(ColorScience.toOklch(doubleArrayOf(family.sumOf { it.lightness * weight(it) } / mass,
            family.sumOf { it.a * weight(it) } / mass, family.sumOf { it.b * weight(it) } / mass)), supportedShare)
    }

    /**
     * Representative color of the genuinely colored pixels: the dominant hue family (chroma weighted)
     * decides the hue, and the mean of that family decides lightness and chroma. This keeps a photo
     * with one saturated region from being treated as a gray photo just because k-means averaged the
     * colored pixels with their surroundings.
     */
    private fun dominantSampleFamily(chromaticSamples: List<Sample>): Oklch {
        if (chromaticSamples.isEmpty()) return Oklch(0.5, 0.0, 0.0)
        val binWidth = 360.0 / HUE_BINS
        val weights = DoubleArray(HUE_BINS)
        chromaticSamples.forEach { sample ->
            val hue = Oklch.normalizeHue(Math.toDegrees(kotlin.math.atan2(sample.b, sample.a)))
            val bin = ((hue / binWidth).toInt()).coerceIn(0, HUE_BINS - 1)
            weights[bin] += sample.chroma
        }
        val bestBin = weights.indices.maxByOrNull { weights[it] } ?: 0
        val center = bestBin * binWidth + binWidth / 2.0
        var sumL = 0.0
        var sumA = 0.0
        var sumB = 0.0
        var sumWeight = 0.0
        chromaticSamples.forEach { sample ->
            val hue = Oklch.normalizeHue(Math.toDegrees(kotlin.math.atan2(sample.b, sample.a)))
            if (ColorScience.hueDelta(hue, center) > HUE_FAMILY_SPAN) return@forEach
            val weight = sample.chroma
            sumL += sample.lightness * weight
            sumA += sample.a * weight
            sumB += sample.b * weight
            sumWeight += weight
        }
        if (sumWeight <= 0.0) return Oklch(0.5, 0.0, 0.0)
        return ColorScience.toOklch(
            doubleArrayOf(sumL / sumWeight, sumA / sumWeight, sumB / sumWeight),
        )
    }

    private data class Sample(
        val lightness: Double,
        val a: Double,
        val b: Double,
        val luminance: Double,
        /** Relative vertical position in the photo, 0 = top, 1 = bottom. */
        val position: Double,
    ) {
        val chroma: Double get() = kotlin.math.hypot(a, b)

        fun distanceTo(color: Oklch): Double {
            val la = color.chroma * kotlin.math.cos(Math.toRadians(color.hue))
            val lb = color.chroma * kotlin.math.sin(Math.toRadians(color.hue))
            return (lightness - color.lightness) * (lightness - color.lightness) +
                (a - la) * (a - la) + (b - lb) * (b - lb)
        }
    }

    private fun sample(bitmap: Bitmap): List<Sample> {
        val width = bitmap.width
        val height = bitmap.height
        val result = ArrayList<Sample>(SAMPLE_GRID * SAMPLE_GRID)
        for (row in 0 until SAMPLE_GRID) {
            val y = (((row + 0.5) * height) / SAMPLE_GRID).toInt().coerceIn(0, height - 1)
            val position = (y + 0.5) / height
            for (column in 0 until SAMPLE_GRID) {
                val x = (((column + 0.5) * width) / SAMPLE_GRID).toInt().coerceIn(0, width - 1)
                val pixel = bitmap.getPixel(x, y)
                if (ColorScience.channel(pixel, 24) < MIN_ALPHA) continue
                val lab = ColorScience.toOklab(ColorScience.toLinear(pixel))
                result += Sample(
                    lightness = lab[0],
                    a = lab[1],
                    b = lab[2],
                    luminance = ColorScience.relativeLuminance(pixel),
                    position = position,
                )
            }
        }
        if (result.isEmpty()) {
            result += Sample(0.5, 0.0, 0.0, 0.2, 0.5)
        }
        return result
    }

    /** Deterministic k-means++ seeding plus Lloyd iterations in Oklab. */
    private fun kMeans(samples: List<Sample>): List<PhotoColorCluster> {
        val k = minOf(CLUSTER_COUNT, samples.size)
        var random = KMEANS_SEED
        fun nextDouble(): Double {
            random = (random * 6364136223846793005L + 1442695040888963407L)
            return ((random ushr 11).toDouble() / (1L shl 53).toDouble())
        }

        val centroids = ArrayList<Oklch>(k)
        centroids += ColorScience.toOklch(
            doubleArrayOf(samples[0].lightness, samples[0].a, samples[0].b),
        )
        while (centroids.size < k) {
            val distances = samples.map { sample -> centroids.minOf { sample.distanceTo(it) } }
            val total = distances.sum().coerceAtLeast(1e-12)
            var target = nextDouble() * total
            var chosen = samples.lastIndex
            for (index in distances.indices) {
                target -= distances[index]
                if (target <= 0.0) {
                    chosen = index
                    break
                }
            }
            centroids += ColorScience.toOklch(
                doubleArrayOf(samples[chosen].lightness, samples[chosen].a, samples[chosen].b),
            )
        }

        val assignment = IntArray(samples.size)
        repeat(KMEANS_ITERATIONS) {
            var moved = false
            for (index in samples.indices) {
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for (cluster in centroids.indices) {
                    val distance = samples[index].distanceTo(centroids[cluster])
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = cluster
                    }
                }
                if (assignment[index] != best) {
                    assignment[index] = best
                    moved = true
                }
            }
            for (cluster in centroids.indices) {
                var sumL = 0.0
                var sumA = 0.0
                var sumB = 0.0
                var count = 0
                for (index in samples.indices) {
                    if (assignment[index] != cluster) continue
                    sumL += samples[index].lightness
                    sumA += samples[index].a
                    sumB += samples[index].b
                    count++
                }
                if (count > 0) {
                    centroids[cluster] = ColorScience.toOklch(
                        doubleArrayOf(sumL / count, sumA / count, sumB / count),
                    )
                }
            }
            if (!moved) return@repeat
        }

        val counts = IntArray(centroids.size)
        val luminanceSums = DoubleArray(centroids.size)
        for (index in samples.indices) {
            counts[assignment[index]]++
            luminanceSums[assignment[index]] += samples[index].luminance
        }
        return centroids.indices
            .filter { counts[it] > 0 }
            .map { cluster ->
                PhotoColorCluster(
                    color = centroids[cluster],
                    weight = counts[cluster].toDouble() / samples.size,
                    luminance = luminanceSums[cluster] / counts[cluster],
                )
            }
    }

    /** Convenience for tests and records: the hue distance between two analyzed colors. */
    fun hueDistance(first: Oklch, second: Oklch): Double = ColorScience.hueDelta(first.hue, second.hue)

    /** True when the two colors are visually the same within [tolerance] Oklab distance. */
    fun almostEquals(first: Oklch, second: Oklch, tolerance: Double = 0.02): Boolean {
        val firstLab = ColorScience.toOkLab(first)
        val secondLab = ColorScience.toOkLab(second)
        return abs(firstLab[0] - secondLab[0]) <= tolerance &&
            abs(firstLab[1] - secondLab[1]) <= tolerance &&
            abs(firstLab[2] - secondLab[2]) <= tolerance
    }
}
