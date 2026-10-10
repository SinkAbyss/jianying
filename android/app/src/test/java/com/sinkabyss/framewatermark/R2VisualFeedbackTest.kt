package com.sinkabyss.framewatermark

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bounded regression checks against user-selected visual directions; not a beauty score. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R2VisualFeedbackTest {
    @Test fun preferredDirectionsAndForegroundReadabilityArePreserved() {
        val root = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))
        val pairs = JSONObject(File(root, "artifacts/FRAME-BG-001/R1/calibration/pairs.json").readText())
        val records = JSONArray()
        for (group in listOf("vivoPairs", "ownInputs")) {
            val inputs = pairs.getJSONArray(group)
            for (index in 0 until inputs.length()) {
                val input = inputs.getJSONObject(index)
                val id = input.getString("id")
                val source = BitmapFactory.decodeFile(File(root, input.getString(if (group == "vivoPairs") "photoFile" else "inputFile")).absolutePath)
                    ?: error("Cannot decode $id")
                try {
                    val analysis = PhotoColorAnalyzer.analyze(source)
                    for (mode in listOf(BorderBackgroundMode.LIGHT_ADAPTIVE, BorderBackgroundMode.DARK_ADAPTIVE)) {
                        val style = BorderStyleResolver.resolve(mode, analysis)
                        val color = style.solidColor!!
                        val tone = ColorScience.toOklch(color)
                        // Reference-derived hierarchy for coloured bands; neutral bands retain 4.5.
                        for ((ink, floor) in listOf(style.foreground.signature to 3.0, style.foreground.primary to 2.5, style.foreground.secondary to 2.0)) {
                            assertTrue("$id $mode foreground", ColorScience.contrastRatio(ink, color) >= if (style.neutralInput) 4.5 else floor)
                        }
                        if (id == "vivo-05" && mode == BorderBackgroundMode.LIGHT_ADAPTIVE) assertTrue("Sunset should keep a warm direction", tone.hue in 55.0..95.0)
                        if (id == "vivo-04" && mode == BorderBackgroundMode.DARK_ADAPTIVE) assertTrue("Architecture should keep gray-blue", tone.hue in 220.0..275.0)
                        if (id == "own-12" && mode == BorderBackgroundMode.DARK_ADAPTIVE) assertTrue("Car should keep gray-green", tone.hue in 145.0..190.0)
                        if (id == "own-12" && mode == BorderBackgroundMode.LIGHT_ADAPTIVE) assertTrue("The visible car body should also support the light palette", tone.hue in 145.0..190.0)
                        if (id == "own-15" && mode == BorderBackgroundMode.LIGHT_ADAPTIVE) assertTrue("Lavender should stay near the approved 5% depth", PhotoColorAnalyzer.almostEquals(tone, ColorScience.toOklch(0xffbdc5e0.toInt()), 0.01))
                        if (id == "own-04" && mode == BorderBackgroundMode.LIGHT_ADAPTIVE) assertEquals("Keep the accepted green illustration", 0xffcadab4.toInt(), color)
                        if (id == "vivo-02" && mode == BorderBackgroundMode.DARK_ADAPTIVE) assertTrue("Keep the preferred greener direction", PhotoColorAnalyzer.almostEquals(tone, ColorScience.toOklch(0xff4e5e3a.toInt()), 0.01))
                        records.put(JSONObject().put("id", id).put("mode", mode.name).put("background", "#%06X".format(color and 0xffffff))
                            .put("chromaticShare", analysis.chromaticShare).put("darkFamilyFallback", analysis.darkFamilyFallback))
                    }
                } finally { source.recycle() }
            }
        }
        assertEquals(64, records.length())
        val folder = File(root, "artifacts/FRAME-BG-001/R1/visual-round-2").apply { mkdirs() }
        File(folder, "feedback-regression.json").writeText(JSONObject().put("algorithmVersion", ColorScience.ALGORITHM_VERSION)
            .put("visualAcceptance", false).put("records", records).toString(2))
    }
}
