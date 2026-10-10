package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact reproducibility checks for mode revisits; these do not assert visual acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1ModeStabilityTest {
    private val root get() = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))

    private fun inputs(): List<Pair<String, File>> {
        val pairs = JSONObject(File(root, "artifacts/FRAME-BG-001/R1/calibration/pairs.json").readText())
        return listOf("vivoPairs", "ownInputs").flatMap { group ->
            val rows = pairs.getJSONArray(group)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                row.getString("id") to File(root, row.getString(if (group == "vivoPairs") "photoFile" else "inputFile"))
            }
        }
    }

    @Test fun reanalysisAndModeOrderNeverChangeTheSolvedColors() {
        val other = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff0055ff.toInt()) }
        try {
            for ((id, file) in inputs()) {
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("Cannot decode $file")
                try {
                    val first = PhotoColorAnalyzer.analyze(source)
                    val expected = BorderBackgroundMode.entries.associateWith { BorderStyleResolver.resolve(it, first) }
                    repeat(3) { cycle ->
                        // Another photo and a different mode order must not contaminate the first photo.
                        val otherAnalysis = PhotoColorAnalyzer.analyze(other)
                        BorderBackgroundMode.entries.forEach { BorderStyleResolver.resolve(it, otherAnalysis) }
                        val again = PhotoColorAnalyzer.analyze(source)
                        assertEquals("$id analysis, cycle $cycle", first, again)
                        BorderBackgroundMode.entries.reversed().forEach { mode ->
                            assertEquals("$id $mode, cycle $cycle", expected.getValue(mode), BorderStyleResolver.resolve(mode, again))
                        }
                    }
                } finally { source.recycle() }
            }
        } finally { other.recycle() }
    }

    @Test fun returningToEachModeReproducesEveryRenderedPixelWithWarmAndColdCaches() {
        val context = RuntimeEnvironment.getApplication()
        val circle = BitmapFactory.decodeResource(context.resources, R.drawable.circle_a)
        val signature = BitmapFactory.decodeResource(context.resources, R.drawable.sig_a)
        val other = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffe59842.toInt()) }
        val otherAnalysis = PhotoColorAnalyzer.analyze(other)
        val records = JSONArray()
        try {
            for ((id, file) in inputs()) {
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: error("Cannot decode $file")
                val sourceBefore = pixelHash(source)
                val analysis = PhotoColorAnalyzer.analyze(source)
                val decodedAgain = BitmapFactory.decodeFile(file.absolutePath) ?: error("Cannot decode $file")
                try {
                    for (layout in FrameLayoutKind.entries) {
                        fun options(mode: BorderBackgroundMode) = FrameOptions(
                            layout = layout, circleBitmap = circle, signatureBitmap = signature,
                            circleSizePercent = 110, parameterText = "26 mm  f/1.8  1/125 s  ISO 100",
                            dateText = "2026.10.01", placeText = "广东省 广州市",
                            style = BorderStyleResolver.resolve(mode, analysis),
                        )
                        val expected = BorderBackgroundMode.entries.associateWith { drawHash(source, options(it)) }
                        // Reverse traversal revisits each mode after rendering other modes.
                        for (mode in BorderBackgroundMode.entries.reversed()) {
                            val neighbor = BorderBackgroundMode.entries[(mode.ordinal + 1) % BorderBackgroundMode.entries.size]
                            drawHash(source, options(neighbor))
                            // Simulate another photo / layout before returning to the original selection.
                            drawHash(other, FrameOptions(layout = if (layout == FrameLayoutKind.BAR) FrameLayoutKind.BOX else FrameLayoutKind.BAR,
                                style = BorderStyleResolver.resolve(mode, otherAnalysis)))
                            val warmHash = drawHash(source, options(mode))
                            assertEquals("$id $layout $mode after switching", expected.getValue(mode), warmHash)
                            FrameBackgroundRenderer.clearCache()
                            val coldHash = drawHash(decodedAgain, options(mode))
                            assertEquals("$id $layout $mode after fresh decode and cold cache", expected.getValue(mode), coldHash)
                            records.put(JSONObject().put("id", id).put("layout", layout.name).put("mode", mode.name)
                                .put("baselinePixelSha256", expected.getValue(mode)).put("revisitedPixelSha256", warmHash)
                                .put("coldPixelSha256", coldHash).put("allPixelsEqual", true))
                        }
                    }
                    assertEquals("$id source pixels modified", sourceBefore, pixelHash(source))
                } finally {
                    FrameBackgroundRenderer.clearCache()
                    source.recycle()
                    decodedAgain.recycle()
                }
            }
            assertEquals(320, records.length())
            val round = System.getProperty("r1.round") ?: "final"
            require(round.matches(Regex("[a-z0-9-]+")))
            val outputFolder = if (ColorScience.ALGORITHM_VERSION >= 3) "visual-round-2/$round/mode-stability" else "mode-stability"
            val folder = File(root, "artifacts/FRAME-BG-001/R1/$outputFolder").apply { mkdirs() }
            File(folder, "report.json").writeText(JSONObject().put("renderer", "Android Canvas / Robolectric NATIVE API35")
                .put("outputPhotoLongEdge", 500).put("inputs", 32).put("modes", 5).put("layouts", 2)
                .put("modeLayoutCases", records.length()).put("pixelComparisons", records.length() * 2)
                .put("deviceUiClicksVerified", false).put("records", records).toString(2))
        } finally {
            FrameBackgroundRenderer.clearCache()
            circle.recycle(); signature.recycle(); other.recycle()
        }
    }

    /** Uses the preview's actual preparation / Canvas path, at a bounded size for exhaustive checks. */
    private fun drawHash(source: Bitmap, options: FrameOptions): String {
        val geometry = FrameGeometry.calculate(source.width, source.height, options.layout, outputLongEdge = 500)
        val output = Bitmap.createBitmap(geometry.canvasWidth, geometry.canvasHeight, Bitmap.Config.ARGB_8888)
        try {
            val prepared = FrameRenderer.prepareBackground(source, geometry, options.style)
            FrameRenderer.draw(Canvas(output), source, geometry, options, prepared)
            return pixelHash(output)
        } finally { output.recycle() }
    }

    private fun pixelHash(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).apply { asIntBuffer().put(pixels) }.array()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
