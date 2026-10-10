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

/** Generate a requested tone ladder with real Canvas frames; production styles stay unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1FlowerToneStepsTest {
    @Test fun renderFiveDepthStepsRelativeToTheOriginalTone() {
        renderDepthSteps(listOf(0, 10, 20, 30, 40), "flower-tone-steps-1", "User-requested 10% depth ladder")
    }

    @Test fun renderMidpointBetweenOriginalAndTenPercent() {
        renderDepthSteps(listOf(0, 5, 10), "flower-tone-fine-1", "User preference between original and 10%; compare the 5% midpoint")
    }

    private fun renderDepthSteps(percents: List<Int>, folderName: String, purpose: String) {
        val root = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))
        val base = File(root, "artifacts/FRAME-BG-001/R1")
        val inputs = JSONObject(File(base, "calibration/pairs.json").readText()).getJSONArray("ownInputs")
        val input = (0 until inputs.length()).map { inputs.getJSONObject(it) }.single { it.getString("id") == "own-15" }
        val sourceFile = File(root, input.getString("inputFile"))
        assertEquals(input.getString("inputSha256"), hash(sourceFile))
        val source = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: error("Cannot decode flower photo")
        val context = RuntimeEnvironment.getApplication()
        val circle = BitmapFactory.decodeResource(context.resources, R.drawable.circle_a)
        val signature = BitmapFactory.decodeResource(context.resources, R.drawable.sig_a)
        val folder = historicalPreviewOutput(base, folderName).apply { mkdirs() }
        val records = JSONArray()
        try {
            val original = historicalCalibrationStyle(root, "own-15", BorderBackgroundMode.LIGHT_ADAPTIVE)
            val lightInks = original.foreground
            val lightTone = ColorScience.toOklch(original.solidColor!!)
            val darkInks = historicalCalibrationStyle(root, "own-15", BorderBackgroundMode.DARK_ADAPTIVE).foreground
            for (percent in percents) {
                // Percent refers to Oklch L reduction from the ORIGINAL, not cumulative steps.
                val requested = lightTone.copy(lightness = lightTone.lightness * (1.0 - percent / 100.0))
                val background = if (percent == 0) original.solidColor!! else ColorScience.toArgb(requested)
                val darkerInk = ColorScience.contrastRatio(0xff000000.toInt(), background) >=
                    ColorScience.contrastRatio(0xffffffff.toInt(), background)
                val seed = if (darkerInk) lightInks else darkInks
                val foreground = if (percent == 0) lightInks else ForegroundStyle(
                    signature = readableInk(seed.signature, background, darkerInk),
                    primary = readableInk(seed.primary, background, darkerInk),
                    secondary = readableInk(seed.secondary, background, darkerInk),
                )
                val style = original.copy(background = BorderBackgroundSpec.Solid(background), foreground = foreground)
                val minimumContrast = listOf(foreground.signature, foreground.primary, foreground.secondary)
                    .minOf { ColorScience.contrastRatio(it, background) }
                assertTrue("$percent% foreground must remain readable", minimumContrast >= 4.5)
                for (layout in FrameLayoutKind.entries) {
                    val options = FrameOptions(layout = layout, circleBitmap = circle, signatureBitmap = signature,
                        circleSizePercent = 110, parameterText = "26 mm  f/1.8  1/125 s  ISO 100",
                        dateText = "2026.10.01", placeText = "广东省 广州市", style = style)
                    val output = FrameRenderer.render(source, options)
                    try {
                        val target = File(folder, "own-15-${layout.name.lowercase()}-depth-$percent.jpg")
                        target.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                        val geometry = FrameGeometry.calculate(source.width, source.height, layout)
                        assertEquals(geometry.canvasWidth, output.width)
                        assertEquals(geometry.canvasHeight, output.height)
                        records.put(JSONObject().put("depthPercent", percent).put("layout", layout.name)
                            .put("file", target.relativeTo(root).path.replace('\\', '/')).put("sha256", hash(target))
                            .put("requestedOklch", JSONArray(listOf(requested.lightness, requested.chroma, requested.hue)))
                            .put("background", hex(background)).put("signature", hex(foreground.signature))
                            .put("primary", hex(foreground.primary)).put("secondary", hex(foreground.secondary))
                            .put("minimumForegroundContrast", minimumContrast))
                    } finally { output.recycle() }
                }
            }
            assertEquals(percents.size * FrameLayoutKind.entries.size, records.length())
            assertEquals(input.getString("inputSha256"), hash(sourceFile))
            File(folder, "manifest.json").writeText(JSONObject().put("purpose", purpose)
                .put("input", input.getString("inputFile")).put("inputSha256", input.getString("inputSha256"))
                .put("percentDefinition", "Oklch L = original L * (1 - depthPercent/100); not cumulative")
                .put("hueAndChromaPolicy", "Preserved before sRGB gamut mapping")
                .put("foregroundPolicy", "Same-hue ink adjusted for background readability; original 0% preserved")
                .put("renderer", "Android Canvas / Robolectric NATIVE API35")
                .put("productionAlgorithmChanged", false).put("visualAccepted", false).put("renders", records).toString(2))
        } finally { source.recycle(); circle.recycle(); signature.recycle() }
    }

    private fun readableInk(seed: Int, background: Int, darker: Boolean): Int {
        if (ColorScience.contrastRatio(seed, background) >= 4.5) return seed
        val tone = ColorScience.toOklch(seed)
        for (step in 1..100) {
            val lightness = (tone.lightness + (if (darker) -1 else 1) * step * 0.01).coerceIn(0.0, 1.0)
            val ink = ColorScience.toArgb(tone.copy(lightness = lightness))
            if (ColorScience.contrastRatio(ink, background) >= 4.5) return ink
        }
        return if (darker) 0xff000000.toInt() else 0xffffffff.toInt()
    }

    private fun hex(color: Int) = "#%06X".format(color and 0xffffff)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
