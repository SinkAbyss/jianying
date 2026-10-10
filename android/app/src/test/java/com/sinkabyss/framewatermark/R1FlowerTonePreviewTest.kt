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

/** A visual experiment, not a per-photo production rule or a replacement for user acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1FlowerTonePreviewTest {
    @Test fun renderCurrentAndSlightlyDeeperLavenderWithTheActualCanvas() {
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
        val folder = historicalPreviewOutput(base, "flower-tone-preview-1").apply { mkdirs() }
        val records = JSONArray()
        try {
            val original = historicalCalibrationStyle(root, "own-15", BorderBackgroundMode.LIGHT_ADAPTIVE)
            val tone = ColorScience.toOklch(original.solidColor!!)
            // Only lower perceptual lightness; preserve the lavender hue and chroma.
            val deeper = original.copy(background = BorderBackgroundSpec.Solid(
                ColorScience.toArgb(tone.copy(lightness = tone.lightness - 0.03)),
            ))
            for ((name, style) in listOf("current" to original, "deeper" to deeper)) {
                for (ink in listOf(style.foreground.signature, style.foreground.primary, style.foreground.secondary)) {
                    assertTrue("$name foreground must remain readable", ColorScience.contrastRatio(ink, style.solidColor!!) >= 4.5)
                }
                for (layout in FrameLayoutKind.entries) {
                    val options = FrameOptions(layout = layout, circleBitmap = circle, signatureBitmap = signature,
                        circleSizePercent = 110, parameterText = "26 mm  f/1.8  1/125 s  ISO 100",
                        dateText = "2026.10.01", placeText = "广东省 广州市", style = style)
                    val output = FrameRenderer.render(source, options)
                    try {
                        val target = File(folder, "own-15-${layout.name.lowercase()}-$name.jpg")
                        target.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                        val geometry = FrameGeometry.calculate(source.width, source.height, layout)
                        assertEquals(geometry.canvasWidth, output.width)
                        assertEquals(geometry.canvasHeight, output.height)
                        records.put(JSONObject().put("variant", name).put("layout", layout.name)
                            .put("file", target.relativeTo(root).path.replace('\\', '/')).put("sha256", hash(target))
                            .put("background", "#%06X".format(style.solidColor!! and 0xffffff))
                            .put("minimumForegroundContrast", listOf(style.foreground.signature, style.foreground.primary, style.foreground.secondary)
                                .minOf { ColorScience.contrastRatio(it, style.solidColor!!) }))
                    } finally { output.recycle() }
                }
            }
            assertEquals(input.getString("inputSha256"), hash(sourceFile))
            File(folder, "manifest.json").writeText(JSONObject().put("purpose", "User visual comparison only")
                .put("input", input.getString("inputFile")).put("inputSha256", input.getString("inputSha256"))
                .put("lightnessDelta", -0.03).put("hueAndChromaPolicy", "Preserved before sRGB gamut mapping")
                .put("renderer", "Android Canvas / Robolectric NATIVE API35")
                .put("productionAlgorithmChanged", false).put("visualAccepted", false).put("renders", records).toString(2))
        } finally { source.recycle(); circle.recycle(); signature.recycle() }
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
