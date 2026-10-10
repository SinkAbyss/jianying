package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
class EditorExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Session {
        val signature = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val entry = PhotoEntry(LoadedPhoto(Uri.parse("content://editor-experience/photo"),
            PhotoMetadata("日常照片.jpg", 400, 300),
            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6C8057.toInt()) }))
        var expanded by mutableStateOf(true)
        var height by mutableFloatStateOf(420f)
        var horizontal by mutableIntStateOf(1)
        var vertical by mutableIntStateOf(2)
        var opacity by mutableIntStateOf(100)
        var signaturePercent by mutableIntStateOf(100)
        var colors by mutableStateOf(ForegroundColors())
        var target by mutableStateOf<ColorTarget?>(null)
        var before = ForegroundColors()
        var frameTab by mutableStateOf(EditorTab.SIGNATURE)
    }

    private fun showEditor(session: Session, art: Boolean = true) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                        if (art) ArtSignatureScreen(entry = session.entry, photos = listOf(session.entry), currentIndex = 0,
                            signatureId = "fixture", signature = session.signature,
                            horizontal = session.horizontal, vertical = session.vertical, opacity = session.opacity,
                            busy = false, onBack = {}, onSave = {}, onSignature = {},
                            onHorizontal = { session.horizontal = it }, onVertical = { session.vertical = it },
                            onOpacity = { session.opacity = it }, onChoosePhoto = {}, onAddPhotos = {}, onRemovePhoto = {},
                            signaturePercent = session.signaturePercent,
                            onSignaturePercent = { session.signaturePercent = it },
                            materials = emptyList(), onOpenMaterials = {},
                            panelExpanded = session.expanded, panelHeightDp = session.height,
                            onPanelExpandedChanged = { session.expanded = it },
                            onPanelHeightChanged = { session.height = it },
                            colors = session.colors, colorTarget = session.target,
                            onOpenColor = { session.before = session.colors; session.target = it },
                            onSelectColor = { target, choice -> session.colors = session.colors.withChoice(target, choice) },
                            onColorChange = { choice -> session.target?.let { session.colors = session.colors.withChoice(it, choice) } },
                            onCancelColor = { session.colors = session.before; session.target = null },
                            onApplyColor = { session.target = null }, onRecipes = {})
                        else EditorScreen(entry = session.entry, photos = listOf(session.entry), currentIndex = 0,
                            layout = FrameLayoutKind.BAR, selectedBackground = BorderBackgroundMode.WHITE,
                            selectedCircleId = null, circleBitmap = null, circleSizePercent = 100,
                            selectedSignatureId = "fixture", signatureBitmap = session.signature, signatureSizePercent = 100,
                            selectedTab = session.frameTab, panelExpanded = session.expanded, panelHeightDp = session.height,
                            isBusy = false, materials = emptyList(), onOpenMaterials = {}, onBack = {},
                            onOpenRecipes = {}, onSave = {}, onAddPhotos = {}, onRemovePhoto = {}, onChoosePhoto = {},
                            onLayoutChanged = {}, onBackgroundChanged = {},
                            colors = session.colors, colorTarget = session.target,
                            onOpenColor = { session.before = session.colors; session.target = it },
                            onColorDirect = { target, choice -> session.colors = session.colors.withChoice(target, choice) },
                            onColorChange = { choice -> session.target?.let { session.colors = session.colors.withChoice(it, choice) } },
                            onCancelColor = { session.colors = session.before; session.target = null },
                            onApplyColor = { session.target = null },
                            onCircleChanged = {}, onCircleSizeChanged = {}, onSignatureChanged = {}, onSignatureSizeChanged = {},
                            onTabChanged = { session.frameTab = it }, onPanelExpandedChanged = { session.expanded = it },
                            onPanelHeightChanged = { session.height = it })
                    }
                }
            }
        }
    }

    @Test fun artSizeSliderUpdatesAndKeepsItsValueAcrossCategories() {
        val session = Session()
        showEditor(session)
        compose.onNodeWithContentDescription("签名大小").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        assertEquals(150, session.signaturePercent)
        compose.onNodeWithText("150%").assertExists()
        compose.onNodeWithText("布局").performClick()
        compose.onNode(hasText("签名") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("150%").assertExists()
    }

    @Test fun changingArtCategoriesPreservesEnteredPhraseAndPosition() {
        val session = Session()
        session.entry.artDraft.showPhrase = true
        session.entry.artDraft.phrase = "起点"
        showEditor(session)
        compose.onNodeWithText("信息").performClick()
        compose.onNodeWithText("起点").performScrollTo().performTextReplacement("今天的光")
        compose.onNodeWithText("布局").performClick()
        // The layout category starts at its own scroll position. Its lower position row can be
        // outside the viewport after text entry; scroll the actual control into view before tapping.
        compose.onNodeWithText("左").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("签名").performClick()
        compose.onNodeWithText("信息").performClick()
        compose.onNodeWithText("今天的光").assertExists()
        assertEquals(0, session.horizontal)
        assertEquals("今天的光", session.entry.artDraft.phrase)
        assertTrue(session.entry.artDraft.showPhrase)
    }

    @Test fun quickColorAppliesImmediatelyAndAutomaticRestoresWithoutOpeningAPicker() {
        val session = Session()
        showEditor(session)
        compose.onNodeWithContentDescription("签名颜色 常用色 #E05252").performScrollTo().performClick()
        assertEquals(ColorChoice.fromHex("#E05252"), session.colors.signature)
        assertNull(session.target)
        compose.onNodeWithContentDescription("签名颜色 常用色 #E05252").assertIsSelected()
        compose.onNodeWithContentDescription("签名颜色 自动").performScrollTo().performClick()
        assertEquals(ColorChoice.Default, session.colors.signature)
        compose.onNodeWithContentDescription("签名颜色 自动").assertIsSelected()
    }

    @Test fun customColorCancelRestoresColorAndReturnsToTheSameCategory() {
        val session = Session()
        val original = ColorChoice.fromHex("#E05252")!!
        session.colors = ForegroundColors(info = original)
        showEditor(session)
        compose.onNodeWithText("信息").performClick()
        compose.onNodeWithContentDescription("信息文字颜色 自定义色盘").performScrollTo().performClick()
        compose.onNodeWithText("保存当前").assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithText("HEX · #RRGGBB").performScrollTo().performTextReplacement("#3366AA")
        assertEquals(ColorChoice.fromHex("#3366AA"), session.colors.info)
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertEquals(original, session.colors.info)
        assertNull(session.target)
        compose.onNodeWithText("信息文字颜色").assertExists()
        compose.onNodeWithText("签名颜色").assertDoesNotExist()
    }

    @Test fun pickerHandleResizesWithActionsVisibleAndPreservesAnInvalidHexDraft() {
        val session = Session()
        showEditor(session)
        compose.onNodeWithContentDescription("签名颜色 自定义色盘").performScrollTo().performClick()
        compose.onNodeWithText("HEX · #RRGGBB").performScrollTo().performTextReplacement("#12")
        val before = session.height
        compose.onNodeWithContentDescription("拖动调整编辑面板高度").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 100f))
        }
        compose.waitForIdle()
        assertTrue("Selection must allow resizing", session.height < before)
        assertTrue("Color controls retain usable minimum height", session.height >= ColorPanelMinimumHeight)
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("HEX · #RRGGBB").performScrollTo()
        compose.onNodeWithText("#12").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithContentDescription("当前照片").assertExists()
        compose.onNode(hasText("签名") and isSelected() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertExists()
    }

    @Test fun compactColorEditorKeepsActionsReachableAndPaletteHasLargerTouchArea() {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().height(280.dp)) {
                        ColorEditor(ColorTarget.INFO, ColorChoice.Default, android.graphics.Color.WHITE,
                            {}, {}, {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed()
        compose.onNodeWithContentDescription("饱和度与明度选色区域").assertHeightIsAtLeast(180.dp)
    }

    @Test fun framePickerResizesPreservesHexDraftAndCancelRestoresOriginalColor() {
        val session = Session()
        val original = ColorChoice.fromHex("#E05252")!!
        session.colors = ForegroundColors(signature = original)
        showEditor(session, art = false)
        compose.onNodeWithContentDescription("签名颜色 自定义色盘").performScrollTo().performClick()
        compose.onNodeWithText("保存当前").assertIsDisplayed().assertIsNotEnabled()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
        compose.onNodeWithText("HEX · #RRGGBB").performScrollTo().performTextReplacement("#448899")
        assertEquals(ColorChoice.fromHex("#448899"), session.colors.signature)
        compose.onNodeWithText("HEX · #RRGGBB").performTextReplacement("#AB")
        val before = session.height
        compose.onNodeWithContentDescription("调整编辑面板高度").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 100f))
        }
        compose.waitForIdle()
        assertTrue("Frame picker must allow resizing", session.height < before)
        assertTrue(session.height >= ColorPanelMinimumHeight)
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("HEX · #RRGGBB").performScrollTo()
        compose.onNodeWithText("#AB").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(original, session.colors.signature)
        assertEquals(EditorTab.SIGNATURE, session.frameTab)
        compose.onNodeWithContentDescription("当前照片").assertExists()
        compose.onNodeWithContentDescription("签名颜色 常用色 #E05252").performScrollTo().assertIsSelected()
    }
}
