package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun typingAndChangingFontUpdatePhotoPreviewWithoutSavingAndCancelPreservesLibrary() {
        val store = UserMaterialStore(compose.activity)
        val existing = store.addText("原有签名", "serif")
        val open = mutableStateOf(true)
        var shown: Bitmap? = null
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    if (open.value) TextSignatureDialog(store, { open.value = false }, { fail("Cancel cannot save") }) { bitmap, modifier ->
                        shown = bitmap
                        Box(modifier)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Keyboard")
        compose.runOnIdle { assertTrue(shown!!.sameAs(MaterialPixels.text("Keyboard", "sans"))) }
        compose.onNodeWithContentDescription("字体：等宽").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(shown!!.sameAs(MaterialPixels.text("Keyboard", "mono")))
            assertFalse(shown!!.sameAs(MaterialPixels.text("Keyboard", "sans")))
            assertEquals(listOf(existing), store.read())
        }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("添加签名").assertDoesNotExist()
        assertEquals(listOf(existing), store.read())
    }

    @Test fun completedTextKeepsSelectedFontAndReloadsIdenticalToPhotoPreview() {
        val store = UserMaterialStore(compose.activity)
        val open = mutableStateOf(true)
        var saved: UserMaterial? = null
        var pixels: Bitmap? = null
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    if (open.value) TextSignatureDialog(store, { open.value = false }, { saved = it; open.value = false }) { bitmap, modifier ->
                        pixels = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        Box(modifier)
                    }
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Preview")
        compose.onNodeWithContentDescription("字体：衬线").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10000) { saved != null }
        assertEquals("serif", saved!!.font)
        assertTrue(UserMaterialStore(compose.activity).bitmap(saved!!.id)!!.sameAs(pixels!!))
    }

    @Test fun homeMaterialLibraryOpensCreationAndReturnsWithoutStartingPhotoSession() {
        compose.onNodeWithText("素材库").performClick()
        compose.onNodeWithText("添加签名").assertExists().performClick()
        compose.onNodeWithText("键盘输入").performClick()
        compose.onNodeWithContentDescription("签名成片实时预览").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("Home material")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10000) { UserMaterialStore(compose.activity).read().any { it.text == "Home material" } }
        compose.onNodeWithText("‹ 水印库").performClick()
        compose.onNodeWithText("选择一种水印，开始编辑照片").assertExists()
    }

    @Test fun fontDeleteConfirmationCanCancelAndRemovesFontAfterConfirm() {
        val store = UserMaterialStore(compose.activity)
        val source = java.io.File(System.getProperty("c1.workspace"), "artifacts/FONT-001/fixtures/Abel-Regular.ttf")
        assumeTrue("FONT-001 开放字体测试素材缺失（本地素材，未随源码分发）", source.isFile)
        val font = source.inputStream().use { store.fonts.import("待删字体.ttf", it) }
        compose.onNodeWithText("素材库").performClick()
        compose.onNodeWithText("字体", substring = false).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText(font.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除字体？").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(listOf(font), store.fonts.read())
        compose.onNodeWithText("删除").performClick()
        compose.onAllNodesWithText("删除").onLast().performClick()
        compose.waitUntil(10000) { store.fonts.read().isEmpty() }
        compose.onNodeWithText(font.name).assertDoesNotExist()
        compose.onNodeWithText("还没有导入字体").assertExists()
    }
}
