package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/** User-requested local visual comparison; no runtime photo-specific rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1GreenHuePreviewTest {
    @Test fun renderTwoSmallHueShiftsTowardGreenAtTheSameDepth() {
        val root = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))
        val base = File(root, "artifacts/FRAME-BG-001/R1")
        val inputs = JSONObject(File(base, "calibration/pairs.json").readText()).getJSONArray("vivoPairs")
        val input = (0 until inputs.length()).map { inputs.getJSONObject(it) }.single { it.getString("id") == "vivo-02" }
        val sourceFile = File(root, input.getString("photoFile"))
        assertEquals(input.getString("photoSha256"), hash(sourceFile))
        val source = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: error("Cannot decode photo-only reference")
        val context = RuntimeEnvironment.getApplication()
        val circle = BitmapFactory.decodeResource(context.resources, R.drawable.circle_a)
        val signature = BitmapFactory.decodeResource(context.resources, R.drawable.sig_a)
        val folder = historicalPreviewOutput(base, "green-hue-preview-1").apply { mkdirs() }
        val records = JSONArray()
        try {
            val original = historicalCalibrationStyle(root, "vivo-02", BorderBackgroundMode.DARK_ADAPTIVE)
            val originalTone = ColorScience.toOklch(original.solidColor!!)
            for (degrees in listOf(0, 8, 16)) {
                fun shifted(color: Int): Int {
                    if (degrees == 0) return color
                    val tone = ColorScience.toOklch(color)
                    return ColorScience.toArgb(tone.copy(hue = Oklch.normalizeHue(tone.hue + degrees)))
                }
                val style = original.copy(background = BorderBackgroundSpec.Solid(shifted(original.solidColor!!)),
                    foreground = ForegroundStyle(shifted(original.foreground.signature), shifted(original.foreground.primary),
                        shifted(original.foreground.secondary)))
                val minimumContrast = listOf(style.foreground.signature, style.foreground.primary, style.foreground.secondary)
                    .minOf { ColorScience.contrastRatio(it, style.solidColor!!) }
                assertTrue("Hue shift $degrees must keep foreground readable", minimumContrast >= 4.5)
                for (layout in FrameLayoutKind.entries) {
                    val options = FrameOptions(layout = layout, circleBitmap = circle, signatureBitmap = signature,
                        circleSizePercent = 110, parameterText = "26 mm  f/1.8  1/125 s  ISO 100",
                        dateText = "2026.10.01", placeText = "广东省 广州市", style = style)
                    val output = FrameRenderer.render(source, options)
                    try {
                        val target = File(folder, "vivo-02-${layout.name.lowercase()}-green-$degrees.jpg")
                        target.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                        val geometry = FrameGeometry.calculate(source.width, source.height, layout)
                        assertEquals(geometry.canvasWidth, output.width)
                        assertEquals(geometry.canvasHeight, output.height)
                        records.put(JSONObject().put("hueShiftDegrees", degrees).put("layout", layout.name)
                            .put("file", target.relativeTo(root).path.replace('\\', '/')).put("sha256", hash(target))
                            .put("requestedOklch", JSONArray(listOf(originalTone.lightness, originalTone.chroma,
                                Oklch.normalizeHue(originalTone.hue + degrees))))
                            .put("background", "#%06X".format(style.solidColor!! and 0xffffff))
                            .put("minimumForegroundContrast", minimumContrast))
                    } finally { output.recycle() }
                }
            }
            assertEquals(6, records.length())
            assertEquals(input.getString("photoSha256"), hash(sourceFile))
            File(folder, "manifest.json").writeText(JSONObject().put("purpose", "User asks for slightly greener dark frame")
                .put("input", input.getString("photoFile")).put("inputSha256", input.getString("photoSha256"))
                .put("policy", "Only Oklch hue +0/+8/+16 degrees, L and C preserved before sRGB mapping; ink hue follows")
                .put("renderer", "Android Canvas / Robolectric NATIVE API35").put("productionAlgorithmChanged", false)
                .put("visualAccepted", false).put("renders", records).toString(2))
        } finally { source.recycle(); circle.recycle(); signature.recycle() }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
