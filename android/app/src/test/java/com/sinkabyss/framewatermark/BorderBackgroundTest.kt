package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * FRAME-BG-001: behavior of the five 边框背景 values, the paired foreground colors and the
 * preview/export consistency of the shared style. Expectations come from the measured local
 * references, never from the implementation under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BorderBackgroundTest {
    @Test
    fun fiveModesAreExactlyTheFiveProductValuesAndDefaultToWhite() {
        assertEquals(
            listOf("白色", "黑色", "浅色自适应", "深色自适应", "模糊"),
            BorderBackgroundMode.entries.map { it.title },
        )
        assertEquals(BorderBackgroundMode.WHITE, BorderBackgroundMode.DEFAULT)
        assertTrue(BorderBackgroundMode.LIGHT_ADAPTIVE.isAdaptive)
        assertTrue(BorderBackgroundMode.DARK_ADAPTIVE.isAdaptive)
        assertFalse(BorderBackgroundMode.WHITE.isAdaptive)
        assertFalse(BorderBackgroundMode.BLUR.isAdaptive)
    }

    @Test
    fun whiteAndBlackStayPhotoIndependent() {
        val first = solidPhoto(Color.rgb(40, 90, 200))
        val second = solidPhoto(Color.rgb(200, 120, 40))
        val firstAnalysis = PhotoColorAnalyzer.analyze(first)
        val secondAnalysis = PhotoColorAnalyzer.analyze(second)

        // R1 fixed-color candidates from the latest product requirement, still photo-independent.
        assertEquals(0xFFF5F5F5.toInt(), BorderStyleResolver.resolve(BorderBackgroundMode.WHITE, firstAnalysis).solidColor)
        assertEquals(0xFFF5F5F5.toInt(), BorderStyleResolver.resolve(BorderBackgroundMode.WHITE, secondAnalysis).solidColor)
        assertEquals(0xFF141414.toInt(), BorderStyleResolver.resolve(BorderBackgroundMode.BLACK, firstAnalysis).solidColor)
        assertEquals(0xFF141414.toInt(), BorderStyleResolver.resolve(BorderBackgroundMode.BLACK, secondAnalysis).solidColor)

        val whiteInk = BorderStyleResolver.resolve(BorderBackgroundMode.WHITE, null).foreground
        val blackInk = BorderStyleResolver.resolve(BorderBackgroundMode.BLACK, null).foreground
        assertTrue("白色模式文字应为深色", ColorScience.relativeLuminance(whiteInk.primary) < 0.1)
        assertTrue("黑色模式文字应为浅色", ColorScience.relativeLuminance(blackInk.primary) > 0.8)
        first.recycle()
        second.recycle()
    }

    @Test
    fun lightAdaptiveKeepsThePhotoHueAndDoesNotWashOut() {
        // The four local 样张 bands measured Oklch hue 251 / 202 / 277 / 73 at L 0.76-0.86, C 0.032-0.048.
        val cases = listOf(
            Color.rgb(38, 74, 130) to 251.0,
            Color.rgb(30, 120, 128) to 202.0,
            Color.rgb(104, 86, 168) to 277.0,
            Color.rgb(186, 140, 86) to 73.0,
        )
        cases.forEach { (source, expectedHue) ->
            val bitmap = solidPhoto(source)
            val analysis = PhotoColorAnalyzer.analyze(bitmap)
            val style = BorderStyleResolver.resolve(BorderBackgroundMode.LIGHT_ADAPTIVE, analysis)
            val color = ColorScience.toOklch(style.solidColor!!)

            assertTrue("浅色背景明度过低: $color", color.lightness in 0.79..0.93)
            assertTrue("浅色背景应保留照片色度: $color", color.chroma >= 0.02)
            assertTrue("浅色背景色度过高: $color", color.chroma <= 0.08)
            assertTrue("浅色背景应与照片同色相: $color 期望 $expectedHue", ColorScience.hueDelta(color.hue, expectedHue) <= 25.0)

            // 泛白防线: the background must not be a near-neutral white placeholder.
            val spread = maxOf(
                Color.red(style.solidColor!!), Color.green(style.solidColor!!), Color.blue(style.solidColor!!),
            ) - minOf(
                Color.red(style.solidColor!!), Color.green(style.solidColor!!), Color.blue(style.solidColor!!),
            )
            assertTrue("浅色自适应退化成近白占位色: ${style.solidColor}", spread >= 12)
            bitmap.recycle()
        }
    }

    @Test
    fun darkAdaptiveIsDarkerAndKeepsThePhotoHue() {
        val cases = listOf(
            Color.rgb(38, 74, 130) to 251.0,
            Color.rgb(168, 48, 40) to 25.0,
            Color.rgb(60, 120, 60) to 140.0,
        )
        cases.forEach { (source, expectedHue) ->
            val bitmap = solidPhoto(source)
            val analysis = PhotoColorAnalyzer.analyze(bitmap)
            val light = BorderStyleResolver.resolve(BorderBackgroundMode.LIGHT_ADAPTIVE, analysis)
            val dark = BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE, analysis)
            val darkColor = ColorScience.toOklch(dark.solidColor!!)

            assertTrue("深色背景应在视频深色底条范围: $darkColor", darkColor.lightness in 0.26..0.56)
            assertTrue("深色背景应比浅色背景暗", darkColor.lightness < ColorScience.toOklch(light.solidColor!!).lightness - 0.18)
            assertTrue("深色背景色度应不低于浅色背景", darkColor.chroma >= ColorScience.toOklch(light.solidColor!!).chroma - 0.005)
            assertTrue("深色背景应与照片同色相", ColorScience.hueDelta(darkColor.hue, expectedHue) <= 25.0)
            bitmap.recycle()
        }
    }

    @Test
    fun neutralPhotosTakeTheNeutralBranch() {
        val gray = solidPhoto(Color.rgb(128, 128, 128))
        val analysis = PhotoColorAnalyzer.analyze(gray)
        assertTrue("中性照片应走中性分支", analysis.neutral)

        val light = ColorScience.toOklch(BorderStyleResolver.resolve(BorderBackgroundMode.LIGHT_ADAPTIVE, analysis).solidColor!!)
        val dark = ColorScience.toOklch(BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE, analysis).solidColor!!)
        assertTrue("中性浅色背景不应带色相: $light", light.chroma < 0.005)
        assertTrue("中性深色背景不应带色相: $dark", dark.chroma < 0.005)
        assertTrue(light.lightness > 0.85)
        assertTrue(dark.lightness < 0.4)
        gray.recycle()
    }

    @Test
    fun foregroundIsPairedWithTheBackgroundAndKeepsTextContrast() {
        listOf(
            BorderBackgroundMode.WHITE,
            BorderBackgroundMode.BLACK,
            BorderBackgroundMode.LIGHT_ADAPTIVE,
            BorderBackgroundMode.DARK_ADAPTIVE,
        ).forEach { mode ->
            val bitmap = solidPhoto(Color.rgb(40, 90, 200))
            val analysis = PhotoColorAnalyzer.analyze(bitmap)
            val style = BorderStyleResolver.resolve(mode, analysis)
            val background = style.solidColor!!
            val ratio = ColorScience.contrastRatio(style.foreground.primary, background)
            // Coloured adaptive bands follow measured vivo ink roles; fixed modes retain old bounds.
            assertTrue("$mode 主文字对比度不足: $ratio", ratio >= if (mode.isAdaptive) 2.5 else 4.5)
            assertTrue("$mode 次文字对比度不足", ColorScience.contrastRatio(style.foreground.secondary, background) >= if (mode.isAdaptive) 2.0 else 3.0)
            assertNotEquals("$mode 前景不能与背景同色", style.foreground.primary, background)

            if (mode.isAdaptive) {
                val backgroundHue = ColorScience.toOklch(background).hue
                val inkHue = ColorScience.toOklch(style.foreground.primary).hue
                assertTrue("$mode 前景应与背景同色系", ColorScience.hueDelta(backgroundHue, inkHue) <= 30.0)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun blurInkFollowsTheBandLuminanceOfThePhoto() {
        val darkBottom = splitPhoto(Color.rgb(240, 240, 240), Color.rgb(20, 24, 30))
        val brightBottom = splitPhoto(Color.rgb(20, 24, 30), Color.rgb(240, 240, 240))
        try {
            val darkStyle = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(darkBottom))
            val brightStyle = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(brightBottom))
            assertTrue(
                "暗底条应使用浅色墨水",
                ColorScience.relativeLuminance(darkStyle.foreground.primary) > 0.7,
            )
            assertTrue(
                "亮底条应使用深色墨水",
                ColorScience.relativeLuminance(brightStyle.foreground.primary) < 0.2,
            )
            assertTrue(darkStyle.background is BorderBackgroundSpec.Blur)
            assertTrue(brightStyle.background is BorderBackgroundSpec.Blur)
        } finally {
            darkBottom.recycle()
            brightBottom.recycle()
        }
    }

    @Test
    fun blurBackgroundKeepsARealGradientAndLeavesThePhotoSharp() {
        // Left/right color split: the band must still show that gradient after the gaussian blur.
        val photo = splitPhotoVertical(Color.rgb(210, 60, 40), Color.rgb(30, 90, 200))
        try {
            val style = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(photo))
            val output = FrameRenderer.render(photo, FrameOptions(layout = FrameLayoutKind.BAR, style = style))
            val geometry = FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR)

            val left = averageColor(output, 0, output.height - geometry.band / 2, output.width / 6, 4)
            val right = averageColor(output, output.width - output.width / 6, output.height - geometry.band / 2, output.width / 6, 4)
            val distance = colorDistance(left, right)
            assertTrue("模糊底条丢了照片的色彩渐变: $left vs $right", distance > 20.0)
            val bandColor = averageColor(output, 0, output.height - geometry.band / 2, output.width, 4)
            val photoCenter = output.getPixel(geometry.margin + geometry.photoWidth / 2, geometry.margin + geometry.photoHeight / 2)
            assertNotEquals("模糊背景不应等于照片像素", bandColor, photoCenter)
            assertEquals("照片区域必须保持清晰", photo.getPixel(photo.width / 2, photo.height / 2), photoCenter)
            assertEquals(geometry.canvasWidth, output.width)
            assertEquals(geometry.canvasHeight, output.height)
            output.recycle()
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun blurKernelIsASymmetricGaussianLikeKernel() {
        // Step edge: a real gaussian turns it into a monotonic ramp of the expected width.
        val width = 81
        val pixels = IntArray(width) { index -> if (index < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        FrameBackgroundRenderer.applyGaussian(pixels, width, 1, sigma = 4.0)
        val values = IntArray(width) { Color.red(pixels[it]) }

        assertTrue("边缘左侧应保持黑色: ${values.first()}", values.first() <= 2)
        assertTrue("边缘右侧应保持白色: ${values.last()}", values.last() >= 253)
        val middle = values[width / 2]
        assertTrue("阶跃中点应落在过渡区: $middle", middle in 90..165)
        for (index in 1 until width) {
            assertTrue("阶跃响应应单调不减: $index", values[index] >= values[index - 1] - 1)
        }
        val ramp = values.count { it in 10..245 }
        assertTrue("核应产生足够宽的过渡带: $ramp", ramp >= 8)

        // Impulse: symmetric and energy preserving.
        val impulse = IntArray(61) { 0xFF000000.toInt() }
        impulse[30] = 0xFFFFFFFF.toInt()
        FrameBackgroundRenderer.applyGaussian(impulse, 61, 1, sigma = 3.0)
        val profile = IntArray(61) { Color.red(impulse[it]) }
        for (offset in 1..8) {
            assertEquals("核应对称: $offset", profile[30 - offset], profile[30 + offset])
        }
        val energy = profile.sum()
        assertTrue("模糊应近似保留能量: $energy", energy in 240..270)
        assertEquals("核应在截断范围外衰减到 0", 0, profile[30 + 20])
    }

    @Test
    fun blurOfAUniformFieldDoesNotChangeIt() {
        val pixels = IntArray(64 * 8) { 0xFF3C78B4.toInt() }
        FrameBackgroundRenderer.applyGaussian(pixels, 64, 8, sigma = 5.0)
        assertTrue("均匀区域模糊后应保持不变", pixels.all { it == 0xFF3C78B4.toInt() })
    }

    @Test
    fun previewAndExportShareOneStyleAndOneBlurScale() {
        val photo = splitPhotoVertical(Color.rgb(200, 90, 40), Color.rgb(30, 90, 200))
        try {
            val analysis = PhotoColorAnalyzer.analyze(photo)
            val previewStyle = BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE, analysis)
            val exportStyle = BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE, analysis)
            assertEquals("同一分析必须得到同一背景色", previewStyle.solidColor, exportStyle.solidColor)

            val blur = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, analysis)
            val small = FrameRenderer.render(photo, FrameOptions(style = blur))
            val large = FrameRenderer.render(photo, FrameOptions(style = blur))
            val smallBand = averageColor(small, 0, small.height - small.height / 12, small.width, 4)
            val largeBand = averageColor(large, 0, large.height - large.height / 12, large.width, 4)
            assertTrue("同尺寸重复渲染的模糊底条应一致", colorDistance(smallBand, largeBand) < 1.0)
            small.recycle()
            large.recycle()
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun blurScaleStaysComparableBetweenPreviewAndExportSizes() {
        val photo = splitPhotoVertical(Color.rgb(210, 60, 40), Color.rgb(30, 90, 200))
        try {
            val analysis = PhotoColorAnalyzer.analyze(photo)
            val style = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, analysis)
            val previewGeometry = FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR, outputLongEdge = 800)
            val exportGeometry = FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR, outputLongEdge = 2_000)
            val preview = Bitmap.createBitmap(previewGeometry.canvasWidth, previewGeometry.canvasHeight, Bitmap.Config.ARGB_8888)
            val export = Bitmap.createBitmap(exportGeometry.canvasWidth, exportGeometry.canvasHeight, Bitmap.Config.ARGB_8888)
            FrameRenderer.draw(Canvas(preview), photo, previewGeometry, FrameOptions(style = style))
            FrameRenderer.draw(Canvas(export), photo, exportGeometry, FrameOptions(style = style))

            // Left/right contrast of the blurred band normalised by its own mean: a scale-free check.
            val previewContrast = bandContrastRatio(preview, previewGeometry)
            val exportContrast = bandContrastRatio(export, exportGeometry)
            assertTrue(
                "预览与导出的模糊视觉尺度差异过大: $previewContrast vs $exportContrast",
                abs(previewContrast - exportContrast) < 0.25 * maxOf(previewContrast, exportContrast),
            )
            preview.recycle()
            export.recycle()
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun preparedBlurIsCachedInsteadOfRecomputedOnEveryCanvasRedraw() {
        val photo = solidPhoto(Color.rgb(60, 110, 170))
        try {
            val style = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(photo))
            val geometry = FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR)
            val first = FrameRenderer.prepareBackground(photo, geometry, style)
            val second = FrameRenderer.prepareBackground(photo, geometry, style)
            assertSame("同一照片与尺寸的模糊背景应命中缓存", first, second)
            assertTrue(first is MaterializedBackground.Photo)
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun allTwentyModeAndLayoutCombinationsRenderWithTheLockedGeometry() {
        val horizontal = solidPhoto(Color.rgb(46, 96, 168), width = 600, height = 360)
        val vertical = solidPhoto(Color.rgb(46, 96, 168), width = 360, height = 600)
        try {
            listOf(horizontal to "horizontal", vertical to "vertical").forEach { (photo, name) ->
                listOf(FrameLayoutKind.BAR, FrameLayoutKind.BOX).forEach { layout ->
                    BorderBackgroundMode.entries.forEach { mode ->
                        val analysis = PhotoColorAnalyzer.analyze(photo)
                        val style = BorderStyleResolver.resolve(mode, analysis)
                        val output = FrameRenderer.render(
                            photo,
                            FrameOptions(
                                layout = layout,
                                signatureBitmap = null,
                                parameterText = "",
                                style = style,
                            ),
                        )
                        val geometry = FrameGeometry.calculate(photo.width, photo.height, layout)
                        assertEquals("$name-$layout-$mode 画布宽", geometry.canvasWidth, output.width)
                        assertEquals("$name-$layout-$mode 画布高", geometry.canvasHeight, output.height)
                        assertEquals(
                            "$name-$layout-$mode 照片区域被配色改动",
                            photo.getPixel(photo.width / 3, photo.height / 3),
                            output.getPixel(geometry.margin + geometry.photoWidth / 3, geometry.margin + geometry.photoHeight / 3),
                        )

                        val bandY = geometry.canvasHeight - geometry.band / 2
                        val bandPixel = output.getPixel(geometry.margin + geometry.photoWidth / 4, bandY)
                        when (mode) {
                            BorderBackgroundMode.WHITE -> assertEquals(0xFFF5F5F5.toInt(), bandPixel)
                            BorderBackgroundMode.BLACK -> assertEquals(0xFF141414.toInt(), bandPixel)
                            BorderBackgroundMode.LIGHT_ADAPTIVE,
                            BorderBackgroundMode.DARK_ADAPTIVE,
                            -> assertEquals(mode.title, style.solidColor, bandPixel)
                            BorderBackgroundMode.BLUR -> assertNotEquals("模糊底条不应是纯色", Color.WHITE, bandPixel)
                        }
                        output.recycle()
                    }
                }
            }
        } finally {
            horizontal.recycle()
            vertical.recycle()
        }
    }

    @Test
    fun analysisIsStableAcrossBitmapSizes() {
        val large = gradientPhoto(800, 600)
        val small = Bitmap.createScaledBitmap(large, 200, 150, true)
        try {
            val largeAnalysis = PhotoColorAnalyzer.analyze(large)
            val smallAnalysis = PhotoColorAnalyzer.analyze(small)
            assertEquals(largeAnalysis.neutral, smallAnalysis.neutral)
            assertTrue(
                "不同尺寸的同一照片应得到相同代表色相: ${largeAnalysis.representative} vs ${smallAnalysis.representative}",
                ColorScience.hueDelta(largeAnalysis.representative.hue, smallAnalysis.representative.hue) <= 12.0,
            )
            assertTrue(abs(largeAnalysis.meanLightness - smallAnalysis.meanLightness) < 0.08)
        } finally {
            large.recycle()
            small.recycle()
        }
    }

    @Test
    fun signatureAndTextUseThePairedInkColorsOfTheActiveMode() {
        listOf(BorderBackgroundMode.LIGHT_ADAPTIVE, BorderBackgroundMode.DARK_ADAPTIVE).forEach { mode ->
            // Export-sized photo: the small text row then has glyph interiors with the exact ink color.
            val photo = solidPhoto(Color.rgb(60, 110, 170), width = 2_000, height = 1_250)
            val analysis = PhotoColorAnalyzer.analyze(photo)
            val style = BorderStyleResolver.resolve(mode, analysis)
            val signature = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                Canvas(this).drawRect(RectF(0f, 0f, 40f, 20f), Paint().apply { color = Color.BLACK })
            }
            val output = FrameRenderer.render(
                photo,
                FrameOptions(
                    layout = FrameLayoutKind.BAR,
                    signatureBitmap = signature,
                    parameterText = "35 mm f/2.8 1/250 s ISO 100",
                    dateText = "2026.09.30 12:00:00",
                    style = style,
                ),
            )
            val geometry = FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR)
            assertTrue(
                "$mode 签名未使用配套墨色",
                countInBand(output, geometry.band, style.foreground.signature, tolerance = 6) > 0,
            )
            assertTrue(
                "$mode 主文字未使用配套墨色",
                countInBand(output, geometry.band, style.foreground.primary, tolerance = 6) > 0,
            )
            assertTrue(
                "$mode 次文字未使用配套墨色",
                countInBand(output, geometry.band, style.foreground.secondary, tolerance = 6) > 0,
            )
            output.recycle()
            signature.recycle()
            photo.recycle()
        }
    }

    @Test
    fun adaptiveRoutesBothProduceAPlausibleLightBackground() {
        val photo = gradientPhoto(400, 300)
        try {
            val analysis = PhotoColorAnalyzer.analyze(photo)
            AdaptiveVariant.entries.forEach { variant ->
                val style = BorderStyleResolver.resolve(BorderBackgroundMode.LIGHT_ADAPTIVE, analysis, variant)
                val color = ColorScience.toOklch(style.solidColor!!)
                assertTrue("$variant 浅色背景明度越界: $color", color.lightness in 0.79..0.95)
                assertTrue(
                    "$variant 前景与背景对比不足",
                    ColorScience.contrastRatio(style.foreground.primary, style.solidColor!!) >= 2.5,
                )
            }
        } finally {
            photo.recycle()
        }
    }

    @Test
    fun twoPhotosKeepTheirOwnStyleWhenSwitchingBetweenThem() {
        // One session, two photos, one selected mode: each photo must solve its own colors and the
        // previously solved style must stay stable when switching back.
        val blue = solidPhoto(Color.rgb(40, 90, 200))
        val warm = solidPhoto(Color.rgb(200, 140, 80))
        try {
            val blueAnalysis = PhotoColorAnalyzer.analyze(blue)
            val warmAnalysis = PhotoColorAnalyzer.analyze(warm)
            listOf(BorderBackgroundMode.LIGHT_ADAPTIVE, BorderBackgroundMode.DARK_ADAPTIVE).forEach { mode ->
                val blueFirst = BorderStyleResolver.resolve(mode, blueAnalysis)
                val warmStyle = BorderStyleResolver.resolve(mode, warmAnalysis)
                val blueAgain = BorderStyleResolver.resolve(mode, blueAnalysis)

                assertEquals("$mode 切回原照片必须得到同一配色", blueFirst.solidColor, blueAgain.solidColor)
                assertNotEquals("$mode 两张照片不能串用同一背景", blueFirst.solidColor, warmStyle.solidColor)
                assertEquals("$mode 前景也必须随照片重算", blueFirst.foreground.primary, blueAgain.foreground.primary)
                val blueHue = ColorScience.toOklch(blueFirst.solidColor!!).hue
                val warmHue = ColorScience.toOklch(warmStyle.solidColor!!).hue
                assertTrue("$mode 蓝照片应保持蓝色相", ColorScience.hueDelta(blueHue, 251.0) <= 30.0)
                assertTrue("$mode 暖色照片应保持暖色相", ColorScience.hueDelta(warmHue, 60.0) <= 45.0)
                assertTrue("$mode 两张照片的色相必须明显不同", ColorScience.hueDelta(blueHue, warmHue) >= 90.0)
                assertNotEquals(
                    "$mode 两张照片的墨色不能相同",
                    blueFirst.foreground.primary,
                    warmStyle.foreground.primary,
                )
            }
        } finally {
            blue.recycle()
            warm.recycle()
        }
    }

    @Test
    fun highResolutionCanvasesKeepTheBlurWorkBufferBounded() {
        val photo = splitPhotoVertical(Color.rgb(210, 60, 40), Color.rgb(30, 90, 200))
        try {
            listOf(2_000 to 1_500, 4_000 to 3_000, 12_000 to 9_000).forEach { (width, height) ->
                val bitmap = FrameBackgroundRenderer.blur(photo, width, height, BorderStyleResolver.BLUR_SIGMA_RATIO)
                try {
                    assertTrue(
                        "$width×$height 画布的模糊工作位图必须受限: ${bitmap.width}×${bitmap.height}",
                        maxOf(bitmap.width, bitmap.height) <= 720,
                    )
                    assertEquals(
                        "$width×$height 工作位图必须保持画布比例",
                        width.toDouble() / height,
                        bitmap.width.toDouble() / bitmap.height,
                        0.01,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            photo.recycle()
        }
    }

    private fun solidPhoto(color: Int, width: Int = 120, height: Int = 80): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Top half [top], bottom half [bottom]; the border band shows the bottom half. */
    private fun splitPhoto(top: Int, bottom: Int, width: Int = 240, height: Int = 160): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(RectF(0f, 0f, width.toFloat(), height / 2f), Paint().apply { color = top })
        canvas.drawRect(RectF(0f, height / 2f, width.toFloat(), height.toFloat()), Paint().apply { color = bottom })
        return bitmap
    }

    /** Left half [left], right half [right]; used to prove the blurred band keeps a gradient. */
    private fun splitPhotoVertical(left: Int, right: Int, width: Int = 240, height: Int = 160): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(RectF(0f, 0f, width / 2f, height.toFloat()), Paint().apply { color = left })
        canvas.drawRect(RectF(width / 2f, 0f, width.toFloat(), height.toFloat()), Paint().apply { color = right })
        return bitmap
    }

    private fun gradientPhoto(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (x in 0 until width) {
            val t = x.toFloat() / (width - 1)
            paint.color = Color.rgb(
                (30 + 200 * t).roundToInt(),
                (140 - 60 * t).roundToInt(),
                (60 + 150 * (1 - t)).roundToInt(),
            )
            canvas.drawRect(RectF(x.toFloat(), 0f, (x + 1).toFloat(), height.toFloat()), paint)
        }
        return bitmap
    }

    private fun averageColor(bitmap: Bitmap, left: Int, centerY: Int, width: Int, bandHeight: Int): Int {
        val startX = left.coerceIn(0, bitmap.width - 1)
        val endX = (left + width).coerceIn(startX + 1, bitmap.width)
        val startY = (centerY - bandHeight).coerceIn(0, bitmap.height - 1)
        val endY = (centerY + bandHeight).coerceIn(startY + 1, bitmap.height)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in startY until endY) {
            for (x in startX until endX) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return Color.BLACK
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun colorDistance(first: Int, second: Int): Double {
        val red = (Color.red(first) - Color.red(second)).toDouble()
        val green = (Color.green(first) - Color.green(second)).toDouble()
        val blue = (Color.blue(first) - Color.blue(second)).toDouble()
        return kotlin.math.sqrt(red * red + green * green + blue * blue)
    }

    private fun bandContrastRatio(bitmap: Bitmap, geometry: FrameGeometry): Double {
        val centerY = bitmap.height - geometry.band / 2
        val left = averageColor(bitmap, geometry.margin, centerY, geometry.photoWidth / 6, 3)
        val right = averageColor(bitmap, geometry.margin + geometry.photoWidth - geometry.photoWidth / 6, centerY, geometry.photoWidth / 6, 3)
        val mean = averageColor(bitmap, geometry.margin, centerY, geometry.photoWidth, 3)
        val meanValue = (Color.red(mean) + Color.green(mean) + Color.blue(mean)) / 3.0
        return colorDistance(left, right) / maxOf(1.0, meanValue)
    }

    private fun countInBand(bitmap: Bitmap, band: Int, color: Int, tolerance: Int = 0): Int {
        var count = 0
        for (y in bitmap.height - band until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (tolerance == 0) {
                    if (pixel == color) count++
                } else if (
                    abs(Color.red(pixel) - Color.red(color)) <= tolerance &&
                    abs(Color.green(pixel) - Color.green(color)) <= tolerance &&
                    abs(Color.blue(pixel) - Color.blue(color)) <= tolerance
                ) {
                    count++
                }
            }
        }
        return count
    }
}
