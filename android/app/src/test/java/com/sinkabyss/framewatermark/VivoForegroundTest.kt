package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Coloured vivo hierarchy plus P01 user-feedback targets; excludes white/black references. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VivoForegroundTest {
    @Test fun darkBandsKeepTintAndSeparateSignatureFromInformation() {
        for (rgb in listOf(0xff7c8b2e.toInt(), 0xff026ca0.toInt(), 0xff52647a.toInt(), 0xff586c63.toInt())) {
            val photo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(rgb) }
            try {
                val style = BorderStyleResolver.resolve(BorderBackgroundMode.DARK_ADAPTIVE, PhotoColorAnalyzer.analyze(photo))
                val bg = ColorScience.toOklch(style.solidColor!!)
                val signature = ColorScience.toOklch(style.foreground.signature)
                val primary = ColorScience.toOklch(style.foreground.primary)
                val secondary = ColorScience.toOklch(style.foreground.secondary)
                // P01: the user found the former pale signature visually white. Keep the
                // reference hierarchy, with a darker, visibly tinted signature candidate.
                assertTrue("Signature must avoid the rejected near-white tone: $signature", signature.lightness in .88.. .915)
                assertTrue("Signature must have visible colour: $signature", signature.chroma >= .03)
                assertTrue("Information must sit below the signature", signature.lightness - primary.lightness > .035)
                assertTrue("Secondary information must sit below primary", primary.lightness > secondary.lightness + .015)
                assertTrue("Primary should retain visible background chroma: $primary $bg", primary.chroma >= bg.chroma * .65)
                for (ink in listOf(signature, primary, secondary)) assertTrue("Keep the band hue", ColorScience.hueDelta(ink.hue, bg.hue) < 12)
            } finally { photo.recycle() }
        }
    }

    @Test fun lightBandsUseColouredInkWithReferenceInformationHierarchy() {
        for (rgb in listOf(0xfff1cdad.toInt(), 0xffc9dbe5.toInt(), 0xffb7e4e7.toInt(), 0xffc3c7ea.toInt())) {
            val photo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(rgb) }
            try {
                val style = BorderStyleResolver.resolve(BorderBackgroundMode.LIGHT_ADAPTIVE, PhotoColorAnalyzer.analyze(photo))
                val bg = ColorScience.toOklch(style.solidColor!!)
                val signature = ColorScience.toOklch(style.foreground.signature)
                val primary = ColorScience.toOklch(style.foreground.primary)
                val secondary = ColorScience.toOklch(style.foreground.secondary)
                // Coloured references use darker signatures, then primary, then secondary information.
                assertTrue("Signature must retain the measured coloured ink tone: $signature", signature.lightness in .34.. .55)
                assertTrue("Primary should be lighter than signature", primary.lightness - signature.lightness > .04)
                assertTrue("Secondary should be lighter than primary", secondary.lightness - primary.lightness > .02)
                assertTrue("Tint must remain visible", signature.chroma >= bg.chroma)
                for (ink in listOf(signature, primary, secondary)) assertTrue("Keep the band hue", ColorScience.hueDelta(ink.hue, bg.hue) < 12)
            } finally { photo.recycle() }
        }
    }
}
