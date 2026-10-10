package com.sinkabyss.framewatermark

import org.json.JSONObject
import java.io.File

internal fun historicalPreviewOutput(base: File, folderName: String): File =
    File(base, if (ColorScience.ALGORITHM_VERSION >= 3) "visual-round-2/historical-previews/$folderName" else folderName)

/** Frozen evidence fixture for old visual experiments, never part of the production APK. */
internal fun historicalCalibrationStyle(root: File, id: String, mode: BorderBackgroundMode): BorderStyle {
    val manifest = JSONObject(File(root, "artifacts/FRAME-BG-001/R1/rounds/candidate-3/manifest.json").readText())
    val rows = manifest.getJSONArray("renders")
    val row = (0 until rows.length()).map { rows.getJSONObject(it) }
        .single { it.getString("id") == id && it.getString("mode") == mode.name && it.getString("layout") == "BAR" }
    fun argb(field: String) = (0xff000000L or row.getString(field).removePrefix("#").toLong(16)).toInt()
    val lch = row.getJSONArray("selectedRepresentative")
    return BorderStyle(mode = mode, background = BorderBackgroundSpec.Solid(argb("background")),
        foreground = ForegroundStyle(argb("primary"), argb("primary"), argb("secondary")),
        representative = Oklch(lch.getDouble(0), lch.getDouble(1), lch.getDouble(2)),
        neutralInput = row.getBoolean("neutral"), algorithmVersion = 2)
}
