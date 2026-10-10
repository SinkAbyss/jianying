package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strip used to render text labels only ("照片 2"), so a multi-photo session had no visual way to
 * tell the photos apart. It now shows each photo's own thumbnail with a selection ring and a remove
 * cross, plus the add tile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStripTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun showStrip(index: Int, session: StripSession) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    PhotoStrip(
                        photos = session.photos,
                        index = index,
                        onChoosePhoto = { session.choose = it },
                        onRemovePhoto = { session.remove = it },
                        onAdd = { session.adds++ },
                    )
                }
            }
        }
    }

    private class StripSession(count: Int) {
        val photos = (0 until count).map { entryFor(it) }
        var choose: Int? = null
        var remove: Int? = null
        var adds = 0

        companion object {
            fun entryFor(index: Int) = PhotoEntry(
                LoadedPhoto(
                    Uri.parse("content://strip/$index"),
                    PhotoMetadata("photo$index.jpg", 40, 30),
                    Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888),
                ),
            )
        }
    }

    @Test
    fun everyPhotoGetsAThumbnailAndTheOpenOneIsMarkedAsCurrent() {
        val session = StripSession(3)
        showStrip(1, session)

        // Two non-selected thumbnails plus one selected.
        assertEquals(2, compose.onAllNodesWithContentDescription("切换到这张照片").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("当前照片").assertExists()
        compose.onNodeWithText("2/3").assertExists()
    }

    @Test
    fun tappingAThumbnailSelectsThatPhoto() {
        val session = StripSession(3)
        showStrip(0, session)
        compose.onAllNodesWithContentDescription("切换到这张照片")[0].performClick()
        assertEquals(1, session.choose)
    }

    @Test
    fun tappingAThumbnailCrossRemovesThatPhoto() {
        val session = StripSession(3)
        showStrip(0, session)
        // The crosses sit in the top-right corner of each tile.
        compose.onAllNodesWithText("✕")[2].performClick()
        assertEquals(2, session.remove)
    }

    @Test
    fun addTileIsPresent() {
        val session = StripSession(2)
        showStrip(0, session)
        compose.onNodeWithText("＋").assertExists().performClick()
        assertEquals(1, session.adds)
    }

    @Test
    fun singlePhotoStillRendersOneThumbnailAndTheCounter() {
        val session = StripSession(1)
        showStrip(0, session)
        compose.onNodeWithContentDescription("当前照片").assertExists()
        compose.onNodeWithText("1/1").assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription("切换到这张照片").fetchSemanticsNodes().size)
    }

    @Test
    fun displayNamesWithUnpairedSurrogatesAreSanitizedBeforeRendering() {
        // Compose throws on unpaired surrogates; a broken JPEG name must not crash the strip.
        val broken = "bad\uD800name.jpg"
        val printable = printableName(broken)
        assertFalse("unpaired surrogate must be replaced", printable.contains('\uD800'))
        assertTrue(printable.startsWith("bad"))
        assertTrue(printable.endsWith("name.jpg"))
        // Valid pairs survive untouched.
        assertEquals("ok🙂.jpg", printableName("ok🙂.jpg"))
    }
}
