package com.sinkabyss.framewatermark

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Per-photo watermark style. Every photo in a session owns one of these, so changing the background,
 * layout, materials, sizes, colours or 艺术签名 position on one photo never changes another
 * (product decision 2026-10-07; previously these were session-wide).
 *
 * Photo *data* stays in [PhotoDraft] / [ArtPhotoDraft]; this class holds appearance only.
 * New photos are given a [copyOf] of the photo that was open when they were added, so a batch still
 * starts out visually uniform while every photo remains individually adjustable.
 */
internal class WatermarkStyle {
    // 个性边框
    var frameLayout by mutableStateOf(FrameLayoutKind.BAR)
    var frameBackground by mutableStateOf(BorderBackgroundMode.DEFAULT)
    var circleId by mutableStateOf<String?>(null)
    var circlePercent by mutableIntStateOf(100)
    var signatureId by mutableStateOf<String?>(null)
    var signaturePercent by mutableIntStateOf(100)
    var framePhraseEnabled by mutableStateOf(false)
    var framePhrase by mutableStateOf("")
    var framePhraseFont by mutableStateOf("sans")

    // 艺术签名
    var artHorizontal by mutableIntStateOf(1)
    var artVertical by mutableIntStateOf(2)
    var artOpacity by mutableIntStateOf(100)
    var artSignaturePercent by mutableIntStateOf(100)

    /** Both watermarks share this photo's colour slots; only one editor is open at a time. */
    var colors by mutableStateOf(ForegroundColors())

    fun copyOf(): WatermarkStyle = WatermarkStyle().also { copy ->
        copy.frameLayout = frameLayout
        copy.frameBackground = frameBackground
        copy.circleId = circleId
        copy.circlePercent = circlePercent
        copy.signatureId = signatureId
        copy.signaturePercent = signaturePercent
        copy.framePhraseEnabled = framePhraseEnabled
        copy.framePhrase = framePhrase
        copy.framePhraseFont = framePhraseFont
        copy.artHorizontal = artHorizontal
        copy.artVertical = artVertical
        copy.artOpacity = artOpacity
        copy.artSignaturePercent = artSignaturePercent
        copy.colors = colors
    }
}
