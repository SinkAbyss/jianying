package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameRendererTest {
    @Test
    fun lockedGeometryCoversAllFourLandscapeAndPortraitCombinations() {
        val cases = listOf(
            Triple(FrameLayoutKind.BAR, 2_000, 1_200) to (2_000 to 1_363),
            Triple(FrameLayoutKind.BOX, 2_000, 1_200) to (2_060 to 1_393),
            Triple(FrameLayoutKind.BAR, 1_200, 2_000) to (1_200 to 2_192),
            Triple(FrameLayoutKind.BOX, 1_200, 2_000) to (1_264 to 2_224),
        )

        cases.forEach { (input, expected) ->
            val geometry = FrameGeometry.calculate(input.second, input.third, input.first, outputLongEdge = 2_000)
            assertEquals(expected.first, geometry.canvasWidth)
            assertEquals(expected.second, geometry.canvasHeight)
        }
    }

    @Test
    fun androidCanvasExportsFourR4DemoPhotoSamplesWithoutChangingSources() {
        val workspace = File(System.getProperty("c1.workspace") ?: error("c1.workspace was not set"))
        val r4 = File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/photos")
        val exportDirectory = File(workspace, "android/app/build/test-output/c1-export-samples").apply { mkdirs() }
        val horizontalFile = File(r4, "p1-h-green.jpg")
        val verticalFile = File(r4, "p3-v-blue.jpg")
        assertTrue("R4 横图样本缺失", horizontalFile.isFile)
        assertTrue("R4 竖图样本缺失", verticalFile.isFile)
        val originalHashes = listOf(horizontalFile, verticalFile).associateWith(::sha256)
        val horizontal = BitmapFactory.decodeFile(horizontalFile.absolutePath)
        val vertical = BitmapFactory.decodeFile(verticalFile.absolutePath)
        assertNotNull(horizontal)
        assertNotNull(vertical)

        val circle = BitmapFactory.decodeFile(File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/circles/circle-a.jpg").absolutePath)
        assertNotNull(circle)

        val outputs = listOf(
            Triple("horizontal-bar.jpg", horizontal!!, FrameLayoutKind.BAR),
            Triple("horizontal-box.jpg", horizontal, FrameLayoutKind.BOX),
            Triple("vertical-bar.jpg", vertical!!, FrameLayoutKind.BAR),
            Triple("vertical-box.jpg", vertical, FrameLayoutKind.BOX),
        )
        outputs.forEach { (name, source, layout) ->
            val result = FrameRenderer.render(
                source,
                FrameOptions(
                    layout = layout,
                    circleBitmap = circle,
                    circleSizePercent = 110,
                ),
            )
            val outputFile = File(exportDirectory, name)
            outputFile.outputStream().use { stream ->
                assertTrue("JPEG 编码失败: $name", result.compress(Bitmap.CompressFormat.JPEG, 96, stream))
            }
            assertTrue("导出为空: $name", outputFile.length() > 10_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
            val geometry = FrameGeometry.calculate(source.width, source.height, layout)
            assertEquals(geometry.canvasWidth, bounds.outWidth)
            assertEquals(geometry.canvasHeight, bounds.outHeight)
            val centerPixel = result.getPixel(geometry.margin + geometry.photoWidth / 2, geometry.margin + geometry.photoHeight / 2)
            assertTrue("Android Canvas 输出照片区域为黑色: $name", Color.red(centerPixel) + Color.green(centerPixel) + Color.blue(centerPixel) > 60)
            result.recycle()
        }

        val blankFrame = FrameRenderer.render(horizontal, FrameOptions(layout = FrameLayoutKind.BAR))
        val textCheck = FrameRenderer.render(
            horizontal,
            FrameOptions(layout = FrameLayoutKind.BAR, parameterText = "Renderer test · manual text 50/50"),
        )
        val hasDifferentInkPixel = (textCheck.width * 2 / 3 until textCheck.width).any { x ->
            (textCheck.height - 200 until textCheck.height).any { y -> textCheck.getPixel(x, y) != blankFrame.getPixel(x, y) }
        }
        assertTrue("测试文字没有出现在 Android Canvas 输出中", hasDifferentInkPixel)
        blankFrame.recycle()
        textCheck.recycle()

        assertEquals(originalHashes[horizontalFile], sha256(horizontalFile))
        assertEquals(originalHashes[verticalFile], sha256(verticalFile))
        assertTrue(File(exportDirectory, "horizontal-bar.jpg").isFile)
        assertTrue(File(exportDirectory, "horizontal-box.jpg").isFile)
        assertTrue(File(exportDirectory, "vertical-bar.jpg").isFile)
        assertTrue(File(exportDirectory, "vertical-box.jpg").isFile)
        circle.recycle()
        horizontal.recycle()
        vertical.recycle()
        circle!!.recycle()
    }

    @Test
    fun presetSignatureIsOptionalAndContinuouslyScaled() {
        val workspace = File(System.getProperty("c1.workspace") ?: error("c1.workspace was not set"))
        val signatureFile = File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/signatures/sig-a.png")
        val appResource = File(workspace, "android/app/src/main/res/drawable-nodpi/sig_a.png")
        assertTrue("R4 签名 A 素材缺失", signatureFile.isFile)
        assertTrue("Android 签名 A 资源缺失", appResource.isFile)
        val signatureHash = sha256(signatureFile)
        assertEquals("Android 资源应复用原始签名文件", signatureHash, sha256(appResource))

        val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(130, 150, 165))
        }
        val signature = BitmapFactory.decodeFile(signatureFile.absolutePath)
        assertNotNull(signature)

        val noneSmall = FrameRenderer.render(source, FrameOptions(signatureSizePercent = 50))
        val noneLarge = FrameRenderer.render(source, FrameOptions(signatureSizePercent = 140))
        assertTrue("关闭签名时，大小滑块不应绘制签名", samePixels(noneSmall, noneLarge))

        val small = FrameRenderer.render(
            source,
            FrameOptions(signatureBitmap = signature, signatureSizePercent = 50),
        )
        val large = FrameRenderer.render(
            source,
            FrameOptions(signatureBitmap = signature, signatureSizePercent = 140),
        )
        val geometry = FrameGeometry.calculate(source.width, source.height, FrameLayoutKind.BAR)
        val bandColor = 0xFFF5F5F5.toInt() // R1 fixed white baseline.
        val smallInkPixels = countBandInk(small, geometry.band, bandColor)
        val largeInkPixels = countBandInk(large, geometry.band, bandColor)
        assertTrue("预置签名没有进入 Android Canvas 绘制", smallInkPixels > 0)
        assertTrue("较大的百分比应绘制出更大的签名", largeInkPixels > smallInkPixels)
        assertTrue(
            "签名应使用白色模式的配套墨色",
            countColor(large, geometry.band, BorderStyle.White.foreground.signature) > 0,
        )
        assertEquals("绘制不得改写预置签名源文件", signatureHash, sha256(signatureFile))

        noneSmall.recycle()
        noneLarge.recycle()
        small.recycle()
        large.recycle()
        signature!!.recycle()
        source.recycle()
    }

    @Test
    fun characterLimitCountsCombiningSequencesAsOneVisibleCharacter() {
        val input = "e\u0301".repeat(55)
        val limited = TextRules.limit(input)
        assertEquals(50, TextRules.graphemes(limited).size)
        assertFalse(limited.endsWith("e"))
        assertTrue(TextRules.graphemes("👨‍👩‍👧‍👦").size <= 1)
    }

    @Test
    fun missingExifDoesNotInventCaptureValues() {
        val metadata = PhotoMetadataReader.read(null, "no-exif.jpg", 1200, 800)
        assertEquals("", metadata.model)
        assertEquals("", metadata.focalLength)
        assertEquals("", metadata.aperture)
        assertEquals("", metadata.shutter)
        assertEquals("", metadata.iso)
        assertEquals("", metadata.date)
        assertFalse(metadata.hasGpsCoordinates)
    }

    @Test
    fun exifOrientationsKeepDimensionsOrSwapThemAsRequired() {
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL to (4 to 3),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (4 to 3),
            ExifInterface.ORIENTATION_ROTATE_180 to (4 to 3),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to (4 to 3),
            ExifInterface.ORIENTATION_TRANSPOSE to (3 to 4),
            ExifInterface.ORIENTATION_ROTATE_90 to (3 to 4),
            ExifInterface.ORIENTATION_TRANSVERSE to (3 to 4),
            ExifInterface.ORIENTATION_ROTATE_270 to (3 to 4),
        )
        orientations.forEach { (orientation, expected) ->
            val source = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            val output = PhotoLoader.orientForExif(source, orientation)
            assertEquals(expected.first, output.width)
            assertEquals(expected.second, output.height)
            if (output !== source) output.recycle()
            source.recycle()
        }
    }

    @Test
    fun exifReaderUsesCaptureTagsAndDoesNotInventThem() {
        val fixture = File.createTempFile("c1-exif-", ".jpg")
        try {
            val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
            fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            bitmap.recycle()
            ExifInterface(fixture.absolutePath).apply {
                setAttribute(ExifInterface.TAG_MODEL, "C1 EXIF Fixture")
                setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35/1")
                setAttribute(ExifInterface.TAG_F_NUMBER, "28/10")
                setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/250")
                setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:05:06 10:20:30")
                setLatLong(31.23, 121.47)
                saveAttributes()
            }
            val metadata = PhotoMetadataReader.read(ExifInterface(fixture.absolutePath), "fixture.jpg", 16, 12)
            assertEquals("C1 EXIF Fixture", metadata.model)
            assertEquals("35 mm", metadata.focalLength)
            assertEquals("f/2.8", metadata.aperture)
            assertEquals("1/250 s", metadata.shutter)
            assertEquals("ISO 400", metadata.iso)
            assertEquals("2024.05.06 10:20:30", metadata.date)
            assertTrue(metadata.hasGpsCoordinates)
        } finally {
            fixture.delete()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun samePixels(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) return false
        val firstPixels = IntArray(first.width)
        val secondPixels = IntArray(second.width)
        for (y in 0 until first.height) {
            first.getPixels(firstPixels, 0, first.width, 0, y, first.width, 1)
            second.getPixels(secondPixels, 0, second.width, 0, y, second.width, 1)
            if (!firstPixels.contentEquals(secondPixels)) return false
        }
        return true
    }

    private fun countBandInk(bitmap: Bitmap, bandHeight: Int, bandColor: Int): Int {
        val pixels = IntArray(bitmap.width)
        var count = 0
        for (y in bitmap.height - bandHeight until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            count += pixels.count { it != bandColor }
        }
        return count
    }

    private fun countColor(bitmap: Bitmap, bandHeight: Int, color: Int): Int {
        val pixels = IntArray(bitmap.width)
        var count = 0
        for (y in bitmap.height - bandHeight until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            count += pixels.count { it == color }
        }
        return count
    }
}
