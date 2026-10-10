package com.sinkabyss.framewatermark

import android.Manifest
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The actual picker is rendered; only media I/O is supplied by a small fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalPhotoPickerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val camera = Uri.parse("content://picker/1")
    private val favorite = Uri.parse("content://picker/2")
    private val bili = Uri.parse("content://picker/3")
    private val source = object : LocalPhotoSource {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        val previews = mutableListOf<Uri>()
        @Volatile var failReadWithOom = false
        override suspend fun read(): List<LocalPhoto> {
            if (failReadWithOom) throw OutOfMemoryError("fixture catalog allocation failure")
            return listOf(LocalPhoto(camera, "camera", "相机"),
                LocalPhoto(favorite, "favorite", "洛天依-收藏相册"), LocalPhoto(bili, "bili", "bili"))
        }
        override suspend fun thumbnail(uri: Uri) = bitmap
        override suspend fun preview(uri: Uri): Bitmap { synchronized(previews) { previews += uri }; return bitmap }
        override fun clearThumbnails() = Unit
    }
    private fun show(limit: Int = 20, confirm: (List<Uri>) -> Unit = {}, cancel: () -> Unit = {}, system: () -> Unit = {}) {
        shadowOf(compose.activity).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            LocalPhotoPicker(cancel, confirm, system, limit, source)
        } } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("照片 1")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun tappingWholePhotoSelectsAndCancelsWithoutOpeningPreview() {
        show()
        compose.onNodeWithText("预览").assertIsNotEnabled()
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("确认 1/20").assertExists()
        compose.onNodeWithContentDescription("照片 1").assertIsSelected()
        compose.onNodeWithText("返回选图").assertDoesNotExist()
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("确认 0/20").assertExists()
        compose.onNodeWithText("预览").assertIsNotEnabled()
    }

    @Test fun crossAlbumSelectionPreviewsInOrderAndConfirmsSameUris() {
        var confirmed = emptyList<Uri>()
        show(confirm = { confirmed = it })
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("全部照片 ▾ (3)").performClick()
        compose.onNodeWithText("洛天依-收藏相册 (1)").performClick()
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("确认 2/20").assertExists()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("1/2").assertExists()
        compose.onNodeWithText("上一张").assertIsNotEnabled()
        compose.waitUntil(10_000) { synchronized(source.previews) { camera in source.previews } }
        compose.onNodeWithText("下一张").performClick()
        compose.onNodeWithText("2/2").assertExists()
        compose.waitUntil(10_000) { synchronized(source.previews) { favorite in source.previews } }
        compose.onNodeWithText("返回选图").performClick()
        compose.onNodeWithText("确认 2/20").performClick()
        assertEquals(listOf(camera, favorite), confirmed)
    }

    @Test fun numberCircleTogglesAndLimitDoesNotAddAnotherPhoto() {
        show(limit = 1)
        compose.onNodeWithContentDescription("选择照片 1").performClick()
        compose.onNodeWithContentDescription("照片 2").performClick()
        compose.onNodeWithText("每次最多选择 1 张照片。").assertExists()
        compose.onNodeWithText("确认 1/1").assertExists()
        compose.onNodeWithContentDescription("取消选择照片 1").performClick()
        compose.onNodeWithText("确认 0/1").assertExists()
    }

    @Test fun cancelAndSystemFallbackUseTheirActualButtons() {
        var cancelled = 0; var system = 0
        show(cancel = { cancelled++ }, system = { system++ })
        compose.onNodeWithText("从系统选择").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(1, system)
        assertEquals(1, cancelled)
    }

    @Test fun catalogOomClearsPreviousSelectionAndKeepsRetryAndSystemFallbackReachable() {
        var confirmed = emptyList<Uri>(); var system = 0
        show(confirm = { confirmed = it }, system = { system++ })
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("确认 1/20").assertExists()
        source.failReadWithOom = true
        // Exercise the actual foreground refresh after changing the fixture's provider behavior.
        compose.runOnUiThread {
            (compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry)
                .handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            (compose.activity.lifecycle as androidx.lifecycle.LifecycleRegistry)
                .handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("照片较多，暂时无法加载。可从系统选择，或重试。")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("确认 0/20").assertIsNotEnabled()
        compose.onNodeWithText("预览").assertIsNotEnabled()
        compose.onNodeWithContentDescription("照片 1").assertDoesNotExist()
        compose.onNodeWithText("从系统选择").performClick()
        assertEquals(1, system)
        assertEquals(emptyList<Uri>(), confirmed)
        source.failReadWithOom = false
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("照片 1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("确认 0/20").assertIsNotEnabled()
        compose.onNodeWithContentDescription("照片 1").performClick()
        compose.onNodeWithText("确认 1/20").performClick()
        assertEquals(listOf(camera), confirmed)
    }
}
