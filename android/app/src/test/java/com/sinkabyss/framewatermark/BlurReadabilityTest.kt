package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Uniform ink is required per component; mixed-field contrast remains a recorded diagnostic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlurReadabilityTest {
    @Test fun actualGlyphLinesStayUniformAndFlatFieldsMeetContrastAcrossLayouts() {
        val evidence = File(System.getProperty("c1.workspace"), "artifacts/FRAME-BG-001/R1/readability/${System.getProperty("r1.round", "final")}").apply { mkdirs() }
        val report = JSONArray()
        val problems = mutableListOf<String>()
        val signature = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        // Native-size glyphs: at 1200px the thin secondary font has no fully opaque pixels.
        for ((w, h) in listOf(2400 to 1600, 1600 to 2400)) for (layout in FrameLayoutKind.entries) {
            for (case in listOf("all-dark", "all-bright", "mid-gray", "left-white", "right-white", "top-white", "bottom-white", "gradient", "signature-crossing", "text-crossing")) {
                val photo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(photo)
                canvas.drawColor(when (case) { "mid-gray" -> 0xffaaaaaa.toInt(); "all-bright" -> Color.WHITE; else -> Color.BLACK })
                val paint = Paint().apply { color = Color.WHITE }
                when (case) {
                    "left-white" -> canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
                    "right-white" -> canvas.drawRect(w / 2f, 0f, w.toFloat(), h.toFloat(), paint)
                    "top-white" -> canvas.drawRect(0f, 0f, w.toFloat(), h / 2f, paint)
                    "bottom-white" -> canvas.drawRect(0f, h / 2f, w.toFloat(), h.toFloat(), paint)
                    "signature-crossing" -> canvas.drawRect(0f, 0f, w * 0.15f, h.toFloat(), paint)
                    "text-crossing" -> canvas.drawRect(0f, 0f, w * 0.85f, h.toFloat(), paint)
                    "gradient" -> for (x in 0 until w) { val v = (255.0 * x / (w - 1)).toInt(); paint.color = Color.rgb(v,v,v); canvas.drawRect(x.toFloat(),0f,x+1f,h.toFloat(),paint) }
                }
                val style = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, PhotoColorAnalyzer.analyze(photo))
                val options = FrameOptions(layout = layout, signatureBitmap = signature, parameterText = "26mm f/1.8 ISO100", dateText = "2026.10.01", placeText = "Guangzhou", style = style)
                val output = FrameRenderer.render(photo, options)
                val background = FrameRenderer.render(photo, options.copy(signatureBitmap = null, parameterText = "", dateText = "", placeText = ""))
                val id = "$w-$h-${layout.name.lowercase()}-$case"
                File(evidence, "$id.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
                for (component in listOf("signature", "primary", "secondary")) {
                    val foreground = ForegroundStyle(if(component=="signature") Color.WHITE else Color.BLACK,
                        if(component=="primary") Color.WHITE else Color.BLACK, if(component=="secondary") Color.WHITE else Color.BLACK)
                    val maskStyle = style.copy(background = BorderBackgroundSpec.Solid(Color.BLACK), foreground = foreground)
                    val mask = FrameRenderer.render(photo, options.copy(style = maskStyle))
                    val band = FrameGeometry.calculate(w,h,layout).band
                    var count = 0
                    var minContrast = Double.POSITIVE_INFINITY
                    var bad = 0
                    val lineColors = mutableListOf<MutableSet<Int>>()
                    var lastOpaqueY = -2
                    for (y in output.height-band until output.height) for (x in 0 until output.width) {
                        if (Color.red(mask.getPixel(x,y)) < 250) continue
                        val ratio = ColorScience.contrastRatio(output.getPixel(x,y), background.getPixel(x,y))
                        minContrast = minOf(minContrast, ratio); count++
                        if (ratio < 4.5) bad++
                        if (mask.getPixel(x,y) == Color.WHITE) {
                            if (y > lastOpaqueY + 1) lineColors += mutableSetOf<Int>()
                            lineColors.last() += output.getPixel(x,y)
                            lastOpaqueY = y
                        }
                    }
                    // Fully opaque mask pixels can still differ by one rounding unit when composited.
                    val spreads = lineColors.map { colors -> maxOf(
                        colors.maxOf(Color::red) - colors.minOf(Color::red),
                        colors.maxOf(Color::green) - colors.minOf(Color::green),
                        colors.maxOf(Color::blue) - colors.minOf(Color::blue)) }
                    val flatField = case in listOf("all-dark", "all-bright", "mid-gray")
                    report.put(JSONObject().put("case",id).put("component",component).put("opaquePixels",count)
                        .put("minimum",minContrast).put("below4_5",bad).put("lineColorSpreads",JSONArray(spreads))
                        .put("contrastRequired",flatField))
                    if (count == 0 || spreads.isEmpty() || spreads.any { it > 2 } || (flatField && bad > 0))
                        problems += "$id $component opaque=$count spreads=$spreads contrast=$minContrast bad=$bad"
                    mask.recycle()
                }
                photo.recycle(); output.recycle(); background.recycle(); FrameBackgroundRenderer.clearCache()
            }
        }
        signature.recycle()
        File(evidence,"report.json").writeText(report.toString(2))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
