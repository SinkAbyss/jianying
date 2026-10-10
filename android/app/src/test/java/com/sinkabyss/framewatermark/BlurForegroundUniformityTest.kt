package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject
import java.io.File

/** Locks down the reported signature-b split through the production Canvas path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlurForegroundUniformityTest {
    @Test fun oneSignatureShouldNotContainBothBlackAndWhiteInk() {
        val photo = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.BLACK)
            drawRect(0f, 0f, 180f, 800f, Paint().apply { color = Color.WHITE })
        }
        val signature = BitmapFactory.decodeResource(RuntimeEnvironment.getApplication().resources, R.drawable.sig_b)
        val style = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(photo))
        val options = FrameOptions(layout = FrameLayoutKind.BAR, signatureBitmap = signature,
            signatureSizePercent = 112, style = style)
        val output = FrameRenderer.render(photo, options)
        val maskStyle = style.copy(background = BorderBackgroundSpec.Solid(Color.BLACK),
            foreground = ForegroundStyle(Color.WHITE, Color.WHITE, Color.WHITE))
        val mask = FrameRenderer.render(photo, options.copy(style = maskStyle))
        val geometry = FrameGeometry.calculate(photo.width, photo.height, options.layout)
        var black = 0; var white = 0; var flatWhite = 0
        val opaqueColors = mutableSetOf<Int>()
        for (y in output.height - geometry.band until output.height) for (x in 0 until output.width) {
            if (Color.red(mask.getPixel(x, y)) < 250) continue
            val flat = mask.getPixel(x, y)
            if (Color.red(flat) >= 250 && Color.green(flat) >= 250 && Color.blue(flat) >= 250) flatWhite++
            val pixel = output.getPixel(x, y)
            if (flat == Color.WHITE) opaqueColors += pixel
            if (Color.red(pixel) < 5 && Color.green(pixel) < 5 && Color.blue(pixel) < 5) black++
            if (Color.red(pixel) > 250 && Color.green(pixel) > 250 && Color.blue(pixel) > 250) white++
        }
        val out = File(System.getProperty("c1.workspace") ?: error("c1.workspace missing"),
            "artifacts/FRAME-BG-001/R1/blur-uniformity").apply { mkdirs() }
        File(out, "reproduction.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "result.json").writeText(JSONObject().put("blackOpaqueSignaturePixels", black)
            .put("whiteOpaqueSignaturePixels", white).put("flatWhiteControlPixels", flatWhite)
            .put("opaqueColorCount", opaqueColors.size).put("signature", "b").put("sizePercent", 112)
            .put("mode", "BLUR").put("fixture", "Synthetic bright/dark field; not user's original photo")
            .put("expected", "A single signature should not mix black and white ink").toString(2))
        mask.recycle(); output.recycle(); signature.recycle(); photo.recycle(); FrameBackgroundRenderer.clearCache()
        assertTrue("Flat-color control must tint signature-b uniformly", flatWhite >= black + white)
        assertTrue("Mixed polarity inside signature: black=$black white=$white", !(black > 5 && white > 5))
        assertTrue("The whole signature must have one ink: $opaqueColors", opaqueColors.size == 1)
    }
}
