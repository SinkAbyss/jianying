package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForegroundColorsTest {
    private val red = ColorChoice.fromHex("#D12345")!!
    private val blue = ColorChoice.fromHex("#3467CD")!!
    private fun bitmap(width: Int, height: Int, color: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun count(image: Bitmap, color: Int, top: Int = 0): Int {
        val pixels = IntArray(image.width * (image.height - top))
        image.getPixels(pixels, 0, image.width, 0, top, image.width, image.height - top)
        return pixels.count { it == color }
    }
    /** Thin glyphs may never reach full coverage. Check their composited RGB against the bare backdrop. */
    private fun countTint(image: Bitmap, backdrop: Bitmap, ink: Int, top: Int = 0): Int {
        val shifts = listOf(16, 8, 0)
        var found = 0
        for (y in top until image.height) for (x in 0 until image.width) {
            val before = backdrop.getPixel(x, y); val after = image.getPixel(x, y)
            if (before == after) continue
            val channel = shifts.maxBy { kotlin.math.abs(((ink shr it) and 255) - ((before shr it) and 255)) }
            val delta = ((ink shr channel) and 255) - ((before shr channel) and 255)
            if (delta == 0) continue
            val alpha = (((after shr channel) and 255) - ((before shr channel) and 255)).toDouble() / delta
            if (alpha > 0.2 && alpha <= 1.01 && shifts.all {
                kotlin.math.abs(((after shr it) and 255) - (((before shr it) and 255) +
                    (((ink shr it) and 255) - ((before shr it) and 255)) * alpha)) <= 3.0
            }) found++
        }
        return found
    }
    private fun evidence(name: String, image: Bitmap) {
        val directory = File(System.getProperty("c1.workspace"), "artifacts/FG-COLOR-001/0.1.20-2026-10-04/host-images")
        directory.mkdirs()
        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun recipesPreserveMixedChoicesAndLegacyOrInvalidFieldsFallBackLocally() {
        assertEquals(red, ColorChoice.fromHex("d12345"))
        assertNull(ColorChoice.fromHex("#123")); assertNull(ColorChoice.fromHex("#AAD12345"))
        assertNull(ColorChoice.fromHex("#GG0000"))
        val recipe = FrameRecipe("颜色", FrameLayoutKind.BOX, "circle-a", 90, "sig-b", 110,
            BorderBackgroundMode.BLUR, ForegroundColors(red, ColorChoice.Default))
        val context = RuntimeEnvironment.getApplication()
        FrameRecipeStore.writeChecked(context, listOf(recipe))
        assertEquals(listOf(recipe), FrameRecipeStore.read(context))
        val legacy = FrameRecipeStore.parse("""[{"name":"旧配方","layout":"BOX","background":"BLACK","signature":"sig-b"}]""").single()
        assertEquals(ForegroundColors(), legacy.colors)
        assertEquals(BorderBackgroundMode.BLACK, legacy.background)
        val invalid = FrameRecipeStore.parse("""[{"name":"坏颜色","layout":"BOX","background":"BLUR","signature":"sig-b",
            "signatureColor":{"mode":"CUSTOM","rgb":123456},"infoColor":{"mode":"CUSTOM","rgb":"#3467cd"}}]""").single()
        assertEquals(ColorChoice.Default, invalid.colors.signature)
        assertEquals(blue, invalid.colors.info)
        assertEquals("sig-b", invalid.signatureId); assertEquals(BorderBackgroundMode.BLUR, invalid.background)
        val cleared = recipe.copy(signatureId = null)
        assertEquals(recipe.colors, FrameRecipeStore.parse(FrameRecipeStore.serialize(listOf(cleared))).single().colors)
    }

    @Test fun customFrameInksSurviveFiveBackgroundsAndCircleAndMaskStayUnchanged() {
        val source = bitmap(600, 400, Color.rgb(70, 120, 90))
        val signature = bitmap(90, 30, Color.WHITE)
        val originalMask = signature.getPixel(20, 10)
        val circleInk = Color.rgb(157, 121, 34)
        val circle = bitmap(24, 24, circleInk)
        try {
            for (mode in BorderBackgroundMode.entries) {
                val options = FrameOptions(signatureBitmap = signature, circleBitmap = circle,
                    parameterText = "35 mm f/2.8", dateText = "2026.10.04",
                    style = BorderStyleResolver.resolve(mode, PhotoColorAnalyzer.analyze(source)),
                    colors = ForegroundColors(red, blue))
                val result = FrameRenderer.render(source, options)
                val backdrop = FrameRenderer.render(source, options.copy(signatureBitmap = null, parameterText = "", dateText = ""))
                try {
                    assertTrue("$mode signature core", count(result, red.rgb!!, source.height) > 30)
                    assertTrue("$mode all information", countTint(result, backdrop, blue.rgb!!, source.height) > 5)
                    assertTrue("$mode original circle", count(result, circleInk, source.height) > 30)
                    evidence("frame-${mode.name.lowercase()}.png", result)
                } finally { result.recycle(); backdrop.recycle() }
                assertEquals(originalMask, signature.getPixel(20, 10))
            }
        } finally { source.recycle(); signature.recycle(); circle.recycle() }
    }

    @Test fun artAutomaticInkUsesActualLandingGroupAndCustomGroupsAndOpacityRemainIndependent() {
        val source = bitmap(800, 600, Color.WHITE)
        Canvas(source).drawRect(400f, 0f, 800f, 600f, Paint().apply { color = Color.BLACK })
        val signature = bitmap(60, 30, Color.WHITE)
        try {
            val base = ArtSignatureOptions(signature, horizontal = 0, vertical = 1, phrase = "此刻")
            val left = ArtSignatureRenderer.resolve(source, 800, 600, base)
            val right = ArtSignatureRenderer.resolve(source, 800, 600, base.copy(horizontal = 2))
            assertEquals(0xFF1A1A1A.toInt(), left.automaticInk)
            assertEquals(0xFFF4F4F4.toInt(), right.automaticInk)
            for ((name, options) in listOf("art-auto-light-region.png" to left, "art-auto-dark-region.png" to right,
                "art-custom.png" to base.copy(horizontal = 2, colors = ForegroundColors(red, blue)))) {
                val result = ArtSignatureRenderer.render(source, options)
                try {
                    val sigInk = options.colors.signature.rgb ?: options.automaticInk!!
                    assertTrue(count(result, sigInk) > 100)
                    if (options.colors.info.isCustom) assertTrue("Custom text hue", countTint(result, source, blue.rgb!!) > 5)
                    evidence(name, result)
                } finally { result.recycle() }
            }
            val mixed = ArtSignatureRenderer.resolve(source, 800, 600,
                base.copy(horizontal = 2, colors = ForegroundColors(red, ColorChoice.Default)))
            assertEquals(red, mixed.colors.signature); assertEquals(right.automaticInk, mixed.automaticInk)
            val infoOnly = ArtSignatureRenderer.resolve(source, 800, 600,
                base.copy(colors = ForegroundColors(ColorChoice.Default, blue)))
            assertEquals(left.automaticInk, infoOnly.automaticInk); assertEquals(blue, infoOnly.colors.info)
            val invisible = ArtSignatureRenderer.render(source, mixed.copy(opacityPercent = 0))
            try { assertTrue(invisible.sameAs(source)) } finally { invisible.recycle() }
            val half = ArtSignatureRenderer.render(source, base.copy(colors = ForegroundColors(red, blue), opacityPercent = 50))
            try {
                assertEquals(0, count(half, red.rgb!!)); assertEquals(0, count(half, blue.rgb!!))
                assertFalse(half.sameAs(source))
            } finally { half.recycle() }
            assertEquals(Color.WHITE, signature.getPixel(20, 10))
        } finally { source.recycle(); signature.recycle() }
    }

    @Test fun artSaveFreezesAutomaticInkBeforeLoadingAndRetriesAtAnotherResolution() = runBlocking {
        val preview = bitmap(400, 300, Color.BLACK)
        val signature = bitmap(60, 30, Color.WHITE)
        val photo = LoadedPhoto(Uri.parse("content://color.fixture/photo"), PhotoMetadata("photo.jpg", 800, 600), preview)
        var active = ArtSignatureOptions(signature, horizontal = 0)
        val request = ArtSignatureSaveRequest(photo, active)
        active = active.copy(horizontal = 2, colors = ForegroundColors(red, blue))
        try {
            for ((width, height) in listOf(800 to 600, 400 to 300)) {
                val result = request.render { selected ->
                    assertSame(photo, selected)
                    // A contrary loader image proves that the saved automatic ink is not recomputed.
                    bitmap(width, height, Color.WHITE)
                }
                try {
                    assertTrue(count(result, 0xFFF4F4F4.toInt()) > 100)
                    assertEquals(0, count(result, 0xFF1A1A1A.toInt()))
                    assertEquals(0, count(result, active.colors.signature.rgb!!))
                } finally { result.recycle() }
            }
            val workspace = File(requireNotNull(System.getProperty("c1.workspace")))
            val input = BitmapFactory.decodeFile(File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/photos/p1-h-green.jpg").path)
            if (input != null) try {
                val actualSignature = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.sig_a)
                try {
                val art = ArtSignatureRenderer.render(input, ArtSignatureOptions(actualSignature, phrase = "此刻",
                    colors = ForegroundColors(red, blue)))
                try { evidence("normal-photo-art-custom.png", art) } finally { art.recycle() }
                val frame = FrameRenderer.render(input, FrameOptions(signatureBitmap = actualSignature, parameterText = "35 mm f/2.8",
                    colors = ForegroundColors(red, blue), style = BorderStyle.Black))
                try { evidence("normal-photo-frame-custom.png", frame) } finally { frame.recycle() }
                } finally { actualSignature.recycle() }
            } finally { input.recycle() }
        } finally { preview.recycle(); signature.recycle() }
    }
}
