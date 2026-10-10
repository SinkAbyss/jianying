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

/** Real production Canvas outputs; targets are evidence only and never enter the analyzer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1CalibrationSampleTest {
    @Test fun renderAllSeparatedAndOwnInputs() {
        val root = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))
        val round = System.getProperty("r1.round") ?: "final"
        require(round.matches(Regex("[a-z0-9-]+")))
        val folder = File(root, "artifacts/FRAME-BG-001/R1/rounds/$round").apply { mkdirs() }
        val pairs = JSONObject(File(root, "artifacts/FRAME-BG-001/R1/calibration/pairs.json").readText())
        val context = RuntimeEnvironment.getApplication()
        val circle = BitmapFactory.decodeResource(context.resources, R.drawable.circle_a)
        val signature = BitmapFactory.decodeResource(context.resources, R.drawable.sig_a)
        val records = JSONArray()
        var ownCount = 0
        var refCount = 0
        for (group in listOf("vivoPairs", "ownInputs")) {
            val inputs = pairs.getJSONArray(group)
            for (index in 0 until inputs.length()) {
                val input = inputs.getJSONObject(index)
                val path = input.getString(if (group == "vivoPairs") "photoFile" else "inputFile")
                val file = File(root, path)
                val expected = input.getString(if (group == "vivoPairs") "photoSha256" else "inputSha256")
                assertEquals(expected, hash(file))
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("Cannot decode $path")
                val analysis = PhotoColorAnalyzer.analyze(source)
                val modes = if (group == "vivoPairs") listOf(BorderBackgroundMode.LIGHT_ADAPTIVE, BorderBackgroundMode.DARK_ADAPTIVE) else BorderBackgroundMode.entries
                for (layout in FrameLayoutKind.entries) for (mode in modes) {
                    val style = BorderStyleResolver.resolve(mode, analysis)
                    val options = FrameOptions(layout = layout, circleBitmap = circle, signatureBitmap = signature,
                        circleSizePercent = 110, parameterText = "26 mm  f/1.8  1/125 s  ISO 100",
                        dateText = "2026.10.01", placeText = "广东省 广州市", style = style)
                    val output = FrameRenderer.render(source, options)
                    val target = File(folder, "${input.getString("id")}-${layout.name.lowercase()}-${mode.name.lowercase()}.jpg")
                    target.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    val geometry = FrameGeometry.calculate(source.width, source.height, layout)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(target.absolutePath, bounds)
                    assertEquals(geometry.canvasWidth, bounds.outWidth)
                    assertEquals(geometry.canvasHeight, bounds.outHeight)
                    val lch = style.solidColor?.let(ColorScience::toOklch)
                    val record = JSONObject().put("id", input.getString("id")).put("group", group)
                        .put("category", if (group == "vivoPairs") "参考" else input.getString("relative").substringBefore('\\'))
                        .put("input", path).put("inputSha256", expected).put("mode", mode.name).put("layout", layout.name)
                        .put("file", target.relativeTo(root).path.replace('\\', '/')).put("sha256", hash(target))
                        .put("canvas", JSONArray(listOf(output.width, output.height)))
                        .put("meanLightness", analysis.meanLightness).put("neutral", analysis.neutral)
                        .put("representative", JSONArray(listOf(analysis.representative.lightness, analysis.representative.chroma, analysis.representative.hue)))
                        .put("selectedRepresentative", style.representative?.let { JSONArray(listOf(it.lightness, it.chroma, it.hue)) } ?: JSONObject.NULL)
                        .put("clusters", JSONArray(analysis.clusters.map { JSONObject().put("weight", it.weight).put("lch", JSONArray(listOf(it.color.lightness, it.color.chroma, it.color.hue))) }))
                        .put("background", style.solidColor?.let(::hex) ?: "blur")
                        .put("lch", lch?.let { JSONArray(listOf(it.lightness, it.chroma, it.hue)) } ?: JSONObject.NULL)
                        .put("signature", hex(style.foreground.signature)).put("primary", hex(style.foreground.primary)).put("secondary", hex(style.foreground.secondary))
                    records.put(record)
                    if (group == "ownInputs") ownCount++ else refCount++
                    output.recycle()
                }
                assertEquals("Input modified: $path", expected, hash(file))
                source.recycle()
                FrameBackgroundRenderer.clearCache()
            }
        }
        circle.recycle()
        signature.recycle()
        File(folder, "manifest.json").writeText(JSONObject().put("round", round)
            .put("algorithmVersion", ColorScience.ALGORITHM_VERSION)
            .put("renderer", "Android Canvas / Robolectric NATIVE API35").put("renders", records).toString(2))
        assertEquals(160, ownCount)
        assertEquals(64, refCount)
    }
    private fun hex(color: Int) = "#%06X".format(color and 0xffffff)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
