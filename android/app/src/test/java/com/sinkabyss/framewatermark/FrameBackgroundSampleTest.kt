package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * FRAME-BG-001 deliverable renders. Every 成片 is produced by the real Android Canvas path
 * ([FrameRenderer]) on the real preview/export code, then encoded as JPEG. Nothing here is a screen
 * capture and no expectation is computed from the renderer under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameBackgroundSampleTest {
    private val workspace = File(System.getProperty("c1.workspace") ?: error("c1.workspace was not set"))
    private val artifacts = File(workspace, "artifacts/FRAME-BG-001")
    private val inputDirectory = File(artifacts, "输入")
    // Preserve the 0.1.5 delivery evidence when re-running the existing suite for R1.
    private val evidencePrefix = if (ColorScience.ALGORITHM_VERSION >= 3) "R1/visual-round-2/legacy-regression" else "R1/legacy-regression"
    private val matrixDirectory = File(artifacts, "$evidencePrefix/01-五模式矩阵")
    private val riskDirectory = File(artifacts, "$evidencePrefix/02-风险输入")
    private val manifestFile = File(artifacts, "$evidencePrefix/清单.json")

    @Test
    fun rendersTwentyModeAndLayoutCombinationsPlusRiskInputs() {
        assumeTrue("派生输入目录缺失，先运行 tools/make_inputs.py", inputDirectory.isDirectory)
        matrixDirectory.mkdirs()
        riskDirectory.mkdirs()
        val circlePath = File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/circles/circle-a.jpg")
        val signaturePath = File(workspace, "design/B/round-4/gpt-6-luna-max-editor-reset/assets/signatures/sig-a.png")
        assertTrue("圆图素材缺失", circlePath.isFile)
        assertTrue("签名素材缺失", signaturePath.isFile)
        assumeTrue("R4 圆图素材缺失（design/ 为本地历史素材，未随源码分发）", circlePath.isFile)
        assumeTrue("R4 签名素材缺失（design/ 为本地历史素材，未随源码分发）", signaturePath.isFile)
        val circle = BitmapFactory.decodeFile(circlePath.absolutePath)
        val signature = BitmapFactory.decodeFile(signaturePath.absolutePath)

        val horizontal = File(workspace, "design/A/round-1/deepseek-flash/work/photo-h-2000.png")
        val vertical = File(workspace, "design/A/round-1/deepseek-flash/work/photo-v-2000.png")
        assumeTrue("横图原图缺失（design/A 为本地设计素材，未随源码分发）", horizontal.isFile)
        assumeTrue("竖图原图缺失（design/A 为本地设计素材，未随源码分发）", vertical.isFile)

        val sourceHashes = mutableMapOf<File, String>()
        fun hashOf(file: File): String = sourceHashes.getOrPut(file) { sha256(file) }

        val records = JSONArray()
        val timings = JSONObject()
        val analyses = JSONArray()

        fun recordAnalysis(label: String, file: File, hash: String, analysis: PhotoColorAnalysis) {
            val clusters = JSONArray()
            analysis.clusters.forEach { cluster ->
                clusters.put(
                    JSONObject()
                        .put("weight", "%.3f".format(cluster.weight))
                        .put("lightness", "%.3f".format(cluster.color.lightness))
                        .put("chroma", "%.3f".format(cluster.color.chroma))
                        .put("hue", "%.1f".format(cluster.color.hue))
                        .put("luminance", "%.3f".format(cluster.luminance)),
                )
            }
            analyses.put(
                JSONObject()
                    .put("label", label)
                    .put("input", relative(file))
                    .put("inputSha256", hash)
                    .put("neutral", analysis.neutral)
                    .put("chromaticShare", "%.3f".format(analysis.chromaticShare))
                    .put("meanLightness", "%.3f".format(analysis.meanLightness))
                    .put("meanLuminance", "%.3f".format(analysis.meanLuminance))
                    .put("bandLuminance", "%.3f".format(analysis.bandLuminance))
                    .put("representative", "L=%.3f C=%.3f H=%.1f".format(
                        analysis.representative.lightness,
                        analysis.representative.chroma,
                        analysis.representative.hue,
                    ))
                    .put("clusters", clusters),
            )
        }

        // 1) 五模式 x 横/竖 x 底条/包围式 = 20 combinations.
        listOf(
            Triple("photo-h-2000", horizontal, "horizontal"),
            Triple("photo-v-2000", vertical, "vertical"),
        ).forEach { (name, file, orientation) ->
            val source = BitmapFactory.decodeFile(file.absolutePath)!!
            try {
                val analysis = PhotoColorAnalyzer.analyze(source)
                recordAnalysis(name, file, hashOf(file), analysis)
                listOf(FrameLayoutKind.BAR, FrameLayoutKind.BOX).forEach { layout ->
                    BorderBackgroundMode.entries.forEach { mode ->
                        val target = File(matrixDirectory, "$name-${layout.name.lowercase()}-${mode.name.lowercase()}.jpg")
                        val record = renderAndSave(
                            source = source,
                            analysis = analysis,
                            mode = mode,
                            layout = layout,
                            circle = circle,
                            signature = signature,
                            text = sampleText(orientation),
                            target = target,
                            sourceFile = file,
                            sourceHash = hashOf(file),
                            extra = JSONObject().put("group", "五模式矩阵").put("orientation", orientation),
                        )
                        records.put(record)
                    }
                }
            } finally {
                source.recycle()
            }
        }

        // 2) Risk inputs: 灰度 / 弱彩 / 强彩 / 夜景 / 双色 / 暖光, five modes each.
        val riskInputs = listOf(
            File(inputDirectory, "派生/灰度-photo-h.jpg"),
            File(inputDirectory, "派生/弱彩-photo-v.jpg"),
            File(inputDirectory, "派生/强彩-p1-h.jpg"),
            File(inputDirectory, "派生/夜景-p1-h.jpg"),
            File(inputDirectory, "派生/双色-p3-v.jpg"),
            File(inputDirectory, "派生/暖光-p1-h.jpg"),
        )
        riskInputs.forEach { file ->
            assumeTrue("派生输入缺失: ${file.name}", file.isFile)
            val source = BitmapFactory.decodeFile(file.absolutePath)!!
            try {
                val analysis = PhotoColorAnalyzer.analyze(source)
                recordAnalysis(file.nameWithoutExtension, file, hashOf(file), analysis)
                val horizontalInput = source.width >= source.height
                val layout = if (horizontalInput) FrameLayoutKind.BAR else FrameLayoutKind.BOX
                BorderBackgroundMode.entries.forEach { mode ->
                    val target = File(riskDirectory, "${file.nameWithoutExtension}-${mode.name.lowercase()}.jpg")
                    val record = renderAndSave(
                        source = source,
                        analysis = analysis,
                        mode = mode,
                        layout = layout,
                        circle = circle,
                        signature = signature,
                        text = sampleText(if (horizontalInput) "horizontal" else "vertical"),
                        target = target,
                        sourceFile = file,
                        sourceHash = hashOf(file),
                        extra = JSONObject().put("group", "风险输入").put("neutral", analysis.neutral)
                            .put("bandLuminance", analysis.bandLuminance),
                    )
                    records.put(record)
                }
            } finally {
                source.recycle()
            }
        }

        // 3) Representative timings on the largest input (host JVM; not a device measurement).
        val timingSource = BitmapFactory.decodeFile(horizontal.absolutePath)!!
        try {
            val runtime = Runtime.getRuntime()
            val analysisStart = System.nanoTime()
            val analysis = PhotoColorAnalyzer.analyze(timingSource)
            timings.put("analysisMillis", (System.nanoTime() - analysisStart) / 1_000_000.0)
            val blurStyle = BorderStyleResolver.resolve(BorderBackgroundMode.BLUR, analysis)
            val geometry = FrameGeometry.calculate(timingSource.width, timingSource.height, FrameLayoutKind.BAR)
            val heapBefore = runtime.totalMemory() - runtime.freeMemory()
            val blurStart = System.nanoTime()
            val preparedBlur = FrameRenderer.prepareBackground(timingSource, geometry, blurStyle) as MaterializedBackground.Photo
            timings.put("blurMillis", (System.nanoTime() - blurStart) / 1_000_000.0)
            timings.put("inkAreaSize", listOf(preparedBlur.inks.width, preparedBlur.inks.height))
            timings.put("inkMapsBytes", 0)
            timings.put("inkMinMaxGridBytes", preparedBlur.inks.low.size.toLong() * 16)
            System.gc()
            timings.put("heapDeltaAfterBlurBytes", runtime.totalMemory() - runtime.freeMemory() - heapBefore)
            val cachedStart = System.nanoTime()
            FrameRenderer.prepareBackground(timingSource, geometry, blurStyle)
            timings.put("blurCacheLookupMillis", (System.nanoTime() - cachedStart) / 1_000_000.0)
            val exportStart = System.nanoTime()
            val output = FrameRenderer.render(
                timingSource,
                FrameOptions(
                    layout = FrameLayoutKind.BAR,
                    circleBitmap = circle,
                    signatureBitmap = signature,
                    parameterText = sampleText("horizontal").first,
                    dateText = sampleText("horizontal").second,
                    style = BorderBackgroundMode.DARK_ADAPTIVE.let { BorderStyleResolver.resolve(it, analysis) },
                ),
            )
            timings.put("exportRenderMillis", (System.nanoTime() - exportStart) / 1_000_000.0)
            timings.put("exportPixels", output.width.toLong() * output.height)
            timings.put("sourcePixels", timingSource.width.toLong() * timingSource.height)
            timings.put("analysisSamples", analysis.sampleCount)
            timings.put("workBlurSize", FrameBackgroundRenderer.workSize(output.width, output.height).let { listOf(it.first, it.second) })
            val work = FrameBackgroundRenderer.workSize(output.width, output.height)
            timings.put("workBlurPixels", work.first.toLong() * work.second)
            timings.put("workBlurBytesApprox", work.first.toLong() * work.second * 4 * 5)
            output.recycle()
        } finally {
            timingSource.recycle()
        }

        // 4) Source files must be untouched by the whole run.
        sourceHashes.forEach { (file, expected) -> assertEquals("输入被修改: ${file.name}", expected, sha256(file)) }

        val manifest = JSONObject()
            .put("generatedBy", "FrameBackgroundSampleTest (Android Canvas / Robolectric NATIVE)")
            .put("algorithmVersion", ColorScience.ALGORITHM_VERSION)
            .put("blurSigmaRatio", BorderStyleResolver.BLUR_SIGMA_RATIO)
            .put("analyses", analyses)
            .put("renders", records)
            .put("timingsHostJvm", timings)
        manifestFile.writeText(manifest.toString(2))

        assertEquals("五模式矩阵应有 20 个成片", 20, records.length() - riskInputs.size * BorderBackgroundMode.entries.size)
        assertTrue("成片清单为空", records.length() >= 50)
        circle.recycle()
        signature.recycle()
    }

    private fun renderAndSave(
        source: Bitmap,
        analysis: PhotoColorAnalysis,
        mode: BorderBackgroundMode,
        layout: FrameLayoutKind,
        circle: Bitmap?,
        signature: Bitmap?,
        text: Pair<String, String>,
        target: File,
        sourceFile: File,
        sourceHash: String,
        extra: JSONObject,
    ): JSONObject {
        val style = BorderStyleResolver.resolve(mode, analysis)
        val geometry = FrameGeometry.calculate(source.width, source.height, layout)
        val prepareStart = System.nanoTime()
        val background = FrameRenderer.prepareBackground(source, geometry, style)
        val prepareMillis = (System.nanoTime() - prepareStart) / 1_000_000.0
        val options = FrameOptions(
            layout = layout,
            circleBitmap = circle,
            circleSizePercent = 110,
            signatureBitmap = signature,
            signatureSizePercent = 100,
            parameterText = text.first,
            dateText = text.second,
            placeText = "",
            modelText = "",
            style = style,
        )
        val renderStart = System.nanoTime()
        val output = FrameRenderer.render(source, options)
        val renderMillis = (System.nanoTime() - renderStart) / 1_000_000.0
        try {
            target.outputStream().use { stream ->
                assertTrue("JPEG 编码失败: ${target.name}", output.compress(Bitmap.CompressFormat.JPEG, 94, stream))
            }
            assertTrue("成片为空: ${target.name}", target.length() > 20_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            assertEquals(target.name, geometry.canvasWidth, bounds.outWidth)
            assertEquals(target.name, geometry.canvasHeight, bounds.outHeight)

            extra.put("file", relative(target))
            extra.put("bytes", target.length())
            extra.put("input", relative(sourceFile))
            extra.put("inputSha256", sourceHash)
            extra.put("inputSize", listOf(source.width, source.height))
            extra.put("mode", mode.title)
            extra.put("modeKey", mode.name)
            extra.put("layout", layout.name)
            extra.put("canvas", listOf(output.width, output.height))
            extra.put("background", hex(style.solidColor ?: Color.TRANSPARENT))
            extra.put("backgroundKind", if (style.background is BorderBackgroundSpec.Blur) "blur" else "solid")
            extra.put("signatureInk", hex(style.foreground.signature))
            extra.put("primaryInk", hex(style.foreground.primary))
            extra.put("secondaryInk", hex(style.foreground.secondary))
            extra.put("representative", style.representative?.let {
                "L=%.3f C=%.3f H=%.1f".format(it.lightness, it.chroma, it.hue)
            } ?: "")
            extra.put("neutralInput", style.neutralInput)
            extra.put("prepareMillis", prepareMillis)
            extra.put("renderMillis", renderMillis)
            assertTrue("背景与前景必须成套: ${target.name}", style.foreground.primary != style.solidColor)
            assertTrue("模糊模式必须有真实背景位图: ${target.name}", background is MaterializedBackground.Photo || style.solidColor != null)
        } finally {
            output.recycle()
        }
        return extra
    }

    private fun sampleText(orientation: String): Pair<String, String> =
        if (orientation == "vertical") {
            "35 mm  f/2.0  1/100 s  ISO 640" to "2026.08.22 11:12  福建省 福州市"
        } else {
            "24 mm  f/1.8  1/125 s  ISO 50" to "2026.06.10 14:09  广东省 广州市"
        }

    private fun relative(file: File): String = file.relativeTo(workspace).path.replace('\\', '/')

    private fun hex(color: Int): String = "#%02X%02X%02X".format(Color.red(color), Color.green(color), Color.blue(color))

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
