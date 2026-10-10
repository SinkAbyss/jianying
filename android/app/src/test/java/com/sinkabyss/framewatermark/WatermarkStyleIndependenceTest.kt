package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-photo style regression (product decision 2026-10-07).
 *
 * Previously one background/layout/material/size/colour choice was shared by the whole session, so
 * changing it on one photo silently changed every other photo. Every photo now owns a
 * [WatermarkStyle], and photos added to a session start from a *snapshot* of the open photo so a
 * batch still begins visually uniform without becoming linked afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatermarkStyleIndependenceTest {
    private fun photo(name: String) = LoadedPhoto(
        Uri.parse("content://style/$name"),
        PhotoMetadata(name, 40, 30),
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888),
    )

    private fun entry(name: String, style: WatermarkStyle = WatermarkStyle()) =
        PhotoEntry(loaded = photo(name), style = style)

    @Test
    fun changingTheBackgroundOnOnePhotoLeavesTheOthersAlone() {
        val first = entry("first")
        val second = entry("second", first.style.copyOf())
        val third = entry("third", first.style.copyOf())

        second.style.frameBackground = BorderBackgroundMode.DARK_ADAPTIVE

        assertEquals("photo 2 changed, photo 1 must not follow",
            BorderBackgroundMode.DEFAULT, first.style.frameBackground)
        assertEquals("photo 3 must not follow either",
            BorderBackgroundMode.DEFAULT, third.style.frameBackground)
        assertEquals(BorderBackgroundMode.DARK_ADAPTIVE, second.style.frameBackground)
    }

    @Test
    fun layoutMaterialsSizesColoursAndArtPositionAreAllIndependent() {
        val first = entry("first")
        val second = entry("second", first.style.copyOf())

        second.style.frameLayout = FrameLayoutKind.BOX
        second.style.circleId = "circle-a"
        second.style.circlePercent = 130
        second.style.signatureId = "sig-c"
        second.style.signaturePercent = 55
        second.style.colors = ForegroundColors(
            ColorChoice.custom(0x66CCFF),
            ColorChoice.custom(0xFFAA33.toInt()),
        )
        second.style.artHorizontal = 0
        second.style.artVertical = 2
        second.style.artOpacity = 40

        // Every field on the untouched photo must still hold its original value.
        assertEquals(FrameLayoutKind.BAR, first.style.frameLayout)
        assertNull(first.style.circleId)
        assertEquals(100, first.style.circlePercent)
        assertNull(first.style.signatureId)
        assertEquals(100, first.style.signaturePercent)
        assertEquals(ForegroundColors(), first.style.colors)
        assertEquals(1, first.style.artHorizontal)
        assertEquals(2, first.style.artVertical)
        assertEquals(100, first.style.artOpacity)
    }

    @Test
    fun addedPhotosStartFromASnapshotSoLaterEditsDoNotLeakBack() {
        val open = entry("open")
        open.style.frameBackground = BorderBackgroundMode.BLUR
        open.style.frameLayout = FrameLayoutKind.BOX

        // Exactly what the picker does for openPicker(newSession = false).
        val added = entry("added", open.style.copyOf())

        assertEquals("an added photo inherits the open photo's settings",
            BorderBackgroundMode.BLUR, added.style.frameBackground)
        assertEquals(FrameLayoutKind.BOX, added.style.frameLayout)
        assertNotSame("but it must be its own object, not the same one", open.style, added.style)

        // Editing after the snapshot must not travel in either direction.
        added.style.frameBackground = BorderBackgroundMode.WHITE
        assertEquals(BorderBackgroundMode.BLUR, open.style.frameBackground)
        open.style.frameLayout = FrameLayoutKind.BAR
        assertEquals(FrameLayoutKind.BOX, added.style.frameLayout)
    }

    @Test
    fun copyOfCarriesEveryField() {
        val source = WatermarkStyle().apply {
            frameLayout = FrameLayoutKind.BOX
            frameBackground = BorderBackgroundMode.LIGHT_ADAPTIVE
            circleId = "circle-b"
            circlePercent = 120
            signatureId = "sig-d"
            signaturePercent = 90
            artHorizontal = 2
            artVertical = 0
            artOpacity = 65
            colors = ForegroundColors(ColorChoice.custom(0x112233), ColorChoice.Default)
        }
        val copy = source.copyOf()

        assertEquals(source.frameLayout, copy.frameLayout)
        assertEquals(source.frameBackground, copy.frameBackground)
        assertEquals(source.circleId, copy.circleId)
        assertEquals(source.circlePercent, copy.circlePercent)
        assertEquals(source.signatureId, copy.signatureId)
        assertEquals(source.signaturePercent, copy.signaturePercent)
        assertEquals(source.artHorizontal, copy.artHorizontal)
        assertEquals(source.artVertical, copy.artVertical)
        assertEquals(source.artOpacity, copy.artOpacity)
        assertEquals(source.colors, copy.colors)
        assertNotSame(source, copy)
    }

    @Test
    fun theSameModeStillResolvesPerPhotoFromThatPhotosOwnAnalysis() {
        val first = entry("first")
        val second = entry("second", first.style.copyOf())
        first.style.frameBackground = BorderBackgroundMode.DARK_ADAPTIVE
        second.style.frameBackground = BorderBackgroundMode.DARK_ADAPTIVE

        val firstStyle = BorderStyleResolver.resolve(first.style.frameBackground, first.analysis)
        val secondStyle = BorderStyleResolver.resolve(second.style.frameBackground, second.analysis)

        assertEquals(first.style.frameBackground, second.style.frameBackground)
        assertNotNull("resolution must not fail without a photo analysis", firstStyle)
        assertNotNull(secondStyle)
    }

    @Test
    fun defaultStyleIsNeutralForANewSession() {
        val style = WatermarkStyle()
        assertEquals(FrameLayoutKind.BAR, style.frameLayout)
        assertEquals(BorderBackgroundMode.DEFAULT, style.frameBackground)
        assertNull(style.circleId)
        assertEquals(100, style.circlePercent)
        assertNull(style.signatureId)
        assertEquals(100, style.signaturePercent)
        assertEquals(ForegroundColors(), style.colors)
        assertEquals(1, style.artHorizontal)
        assertEquals(2, style.artVertical)
        assertEquals(100, style.artOpacity)
    }
}
