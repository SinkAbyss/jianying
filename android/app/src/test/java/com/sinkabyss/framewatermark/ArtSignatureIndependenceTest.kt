package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtSignatureIndependenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun choosingOnSecondPhotoDoesNotChangeFirstPhotoSelection() {
        val photos = (1..2).map { index ->
            PhotoEntry(LoadedPhoto(Uri.parse("content://art-independent/$index"),
                PhotoMetadata("photo$index.jpg", 400, 300),
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)),
                style = WatermarkStyle().apply { signatureId = "sig-a" })
        }
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("art-signature", Context.MODE_PRIVATE)
                .edit().clear().putString("signature", "sig-a").commit()
            compose.activity.setContent { MaterialTheme { FrameWatermarkApp(photos, initialArtSession = true) } }
        }
        compose.onNodeWithContentDescription("签名 a").assertIsSelected()
        compose.onNodeWithContentDescription("切换到这张照片").performClick()
        compose.onNodeWithText("2/2").assertExists()
        compose.onNodeWithContentDescription("签名 b").performClick()
        compose.onNodeWithContentDescription("签名 b").assertIsSelected()
        compose.onNodeWithContentDescription("切换到这张照片").performClick()
        compose.onNodeWithText("1/2").assertExists()
        compose.onNodeWithContentDescription("签名 a").assertIsSelected()
        compose.onNodeWithContentDescription("切换到这张照片").performClick()
        compose.onNodeWithContentDescription("签名 b").assertIsSelected()
        assertEquals("sig-a", photos[0].style.signatureId)
        assertEquals("sig-b", photos[1].style.signatureId)
    }

    @Test fun savingMultiplePhotosLoadsEachCapturedSignatureAndRendersItsShape() = runBlocking {
        fun bitmap(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
        val wide = bitmap(80, 20, Color.WHITE)
        val tall = bitmap(20, 80, Color.WHITE)
        val entries = listOf("wide", "tall").map { id ->
            PhotoEntry(LoadedPhoto(Uri.parse("content://art-independent/$id"),
                PhotoMetadata("$id.jpg", 400, 300), bitmap(400, 300, Color.BLACK)),
                style = WatermarkStyle().apply {
                    signatureId = id
                    colors = ForegroundColors(signature = ColorChoice.custom(0xff0000))
                })
        }
        // The same currently visible bitmap must not make every saved photo use that signature.
        val requests = entries.map { ArtSignatureSaveRequest.capture(it, wide) }
        entries.forEach { it.style.signatureId = "changed-after-click" }
        val readIds = mutableListOf<String>()
        val widths = requests.map { request ->
            val result = request.render(loadSignature = { id ->
                readIds += id
                when (id) { "wide" -> wide; "tall" -> tall; else -> null }
            }, loadPhoto = { bitmap(400, 300, Color.BLACK) })
            try {
                val xs = (0 until result.width).filter { x -> (0 until result.height).any { y ->
                    Color.red(result.getPixel(x, y)) > 180
                } }
                xs.last() - xs.first() + 1
            } finally { result.recycle() }
        }
        assertEquals(listOf("wide", "tall"), readIds)
        assertTrue("saved signatures must preserve their different shapes", widths[0] > widths[1] * 3)
        entries.forEach { it.loaded.preview.recycle() }; wide.recycle(); tall.recycle()
    }

    @Test fun appendedPhotoInheritsSignatureWithoutLinkingLaterSelections() {
        val current = WatermarkStyle().apply { signatureId = "sig-b" }
        val added = current.copyOf()
        assertEquals("sig-b", added.signatureId)
        added.signatureId = "sig-c"
        assertEquals("sig-b", current.signatureId)
        current.signatureId = "sig-a"
        assertEquals("sig-c", added.signatureId)
    }
}
