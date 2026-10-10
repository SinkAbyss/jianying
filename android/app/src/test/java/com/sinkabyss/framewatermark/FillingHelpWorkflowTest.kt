package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillingHelpWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Session {
        val photos = (1..2).map { number ->
            PhotoEntry(LoadedPhoto(Uri.parse("content://p05/$number"),
                PhotoMetadata("照片 $number", 400, 300),
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6C8057.toInt()) }))
        }
        var index by mutableIntStateOf(1)
        var expanded by mutableStateOf(true)
        var height by mutableFloatStateOf(420f)
        var tab by mutableStateOf(EditorTab.INFO)
        var busy by mutableStateOf(false)
        var colorTarget by mutableStateOf<ColorTarget?>(null)
        var callbacks = 0
        val colors = ForegroundColors(ColorChoice.custom(0x112233), ColorChoice.custom(0x66CCFF))
        val signature = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) }
        init {
            photos.forEachIndexed { i, photo ->
                photo.draft.apply {
                    parameterFields[ParameterField.ISO] = false
                    selectParameterMode(true)
                    parameterText = if (i == 0) "第一张自己的参数" else ""
                    selectModelMode(true)
                    modelText = "机型草稿 ${i + 1}"
                    showModel = true
                    missingPromptShown = true
                }
                photo.artDraft.apply {
                    selectModelMode(true)
                    modelText = if (i == 0) "第一张艺术机型" else ""
                    showModel = true
                    showPhrase = true
                    phrase = "短句草稿 ${i + 1}"
                    missingPromptShown = true
                }
            }
        }
    }

    private fun showEditor(session: Session, art: Boolean, fontScale: Float = 1f) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    val entry = session.photos[session.index]
                    if (art) ArtSignatureScreen(entry, session.photos, session.index,
                        "test-signature", session.signature, 2, 0, 73, session.busy,
                        onBack = { session.callbacks++ }, onSave = { session.callbacks++ },
                        onSignature = { session.callbacks++ }, onHorizontal = { session.callbacks++ },
                        onVertical = { session.callbacks++ }, onOpacity = { session.callbacks++ },
                        onChoosePhoto = { session.index = it }, onAddPhotos = { session.callbacks++ },
                        onRemovePhoto = { session.callbacks++ },
                        materials = emptyList(), onOpenMaterials = { session.callbacks++ },
                        panelExpanded = session.expanded, panelHeightDp = session.height,
                        onPanelExpandedChanged = { session.expanded = it }, onPanelHeightChanged = { session.height = it },
                        colors = session.colors, colorTarget = session.colorTarget,
                        onSelectColor = { _, _ -> session.callbacks++ },
                        onOpenColor = { session.colorTarget = it }, onColorChange = { session.callbacks++ },
                        onCancelColor = { session.colorTarget = null }, onApplyColor = { session.colorTarget = null },
                        onRecipes = { session.callbacks++ })
                    else EditorScreen(entry, session.photos, session.index,
                        FrameLayoutKind.BOX, BorderBackgroundMode.BLACK, "test-circle", session.signature, 83,
                        "test-signature", session.signature, 117, session.tab, session.expanded, session.height, session.busy,
                        materials = emptyList(), onOpenMaterials = { session.callbacks++ },
                        onBack = { session.callbacks++ }, onOpenRecipes = { session.callbacks++ },
                        onSave = { session.callbacks++ }, onAddPhotos = { session.callbacks++ },
                        onRemovePhoto = { session.callbacks++ },
                        onChoosePhoto = { session.index = it }, onLayoutChanged = { session.callbacks++ },
                        onBackgroundChanged = { session.callbacks++ }, colors = session.colors, colorTarget = session.colorTarget,
                        onOpenColor = { session.colorTarget = it }, onColorChange = { session.callbacks++ },
                        onCancelColor = { session.colorTarget = null }, onApplyColor = { session.colorTarget = null },
                        onCircleChanged = { session.callbacks++ }, onCircleSizeChanged = { session.callbacks++ },
                        onSignatureChanged = { session.callbacks++ }, onSignatureSizeChanged = { session.callbacks++ },
                        onTabChanged = { session.tab = it }, onPanelExpandedChanged = { session.expanded = it },
                        onPanelHeightChanged = { session.height = it })
                    }
                }
            }
        }
    }

    private fun openHelp() {
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("填写帮助").performClick()
        compose.onNodeWithText("信息填写帮助").assertExists()
    }

    private fun assertDrafts(session: Session) {
        assertEquals(0, session.callbacks)
        assertEquals(420f, session.height, 0.01f)
        assertEquals("第一张自己的参数", session.photos[0].draft.parameterText)
        assertEquals("", session.photos[1].draft.parameterText)
        assertEquals("机型草稿 2", session.photos[1].draft.modelText)
        assertEquals("第一张艺术机型", session.photos[0].artDraft.modelText)
        assertEquals("", session.photos[1].artDraft.modelText)
        assertEquals("短句草稿 2", session.photos[1].artDraft.phrase)
        session.photos.forEach {
            assertTrue(it.draft.parameterManualInitialized)
            assertTrue(it.draft.parameterManual)
            assertFalse(it.draft.parameterFields[ParameterField.ISO]!!)
            assertTrue(it.draft.missingPromptShown)
            assertTrue(it.artDraft.missingPromptShown)
        }
    }

    @Test fun frameHelpReturnsToSamePhotoAndScrolledInfoPanelWithoutChangingDrafts() {
        val session = Session()
        showEditor(session, false)
        val end = "手动文字每项最多 50 个可见字符；清空后会保留你的草稿。"
        compose.onNodeWithText(end).performScrollTo()
        val before = compose.onNodeWithText(end).fetchSemanticsNode().boundsInRoot
        openHelp()
        compose.onNode(hasText("机型") and hasAnyAncestor(isDialog())).performScrollTo()
        compose.onNodeWithText("‹ 返回编辑").performClick()
        compose.onNodeWithText("填写帮助").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithText(end).fetchSemanticsNode().boundsInRoot)
        assertEquals(EditorTab.INFO, session.tab)
        assertTrue(session.expanded)
        assertEquals(1, session.index)
        assertDrafts(session)
        // Photos are switched by tapping their thumbnail; the strip no longer renders text labels.
        compose.onAllNodesWithContentDescription("切换到这张照片")[0].performClick()
        assertEquals(0, session.index)
        assertDrafts(session)
    }

    @Test fun artHelpSystemBackPreservesScrollAndClearedManualDraft() {
        val session = Session()
        showEditor(session, true)
        compose.onNodeWithText("信息").performClick()
        compose.onNodeWithText("地点", substring = false).performScrollTo()
        val before = compose.onNodeWithText("地点", substring = false).fetchSemanticsNode().boundsInRoot
        openHelp()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("信息填写帮助").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithText("地点", substring = false).fetchSemanticsNode().boundsInRoot)
        assertEquals(1, session.index)
        assertDrafts(session)
    }

    @Test fun collapsedPanelAndOtherFrameTabStillHaveHelpAndReturnUnchanged() {
        val session = Session().apply { expanded = false; tab = EditorTab.FRAME }
        showEditor(session, false)
        openHelp()
        compose.onNodeWithText("‹ 返回编辑").performClick()
        assertFalse(session.expanded)
        assertEquals(EditorTab.FRAME, session.tab)
        assertEquals(1, session.index)
        assertDrafts(session)
    }

    @Test fun missingAutomaticFieldsStayEmptyAndUninitializedAfterReadingHelp() {
        val session = Session()
        session.photos[1].draft.apply {
            parameterManual = false; parameterManualInitialized = false; parameterText = ""
            modelManual = false; modelManualInitialized = false; modelText = ""
            missingPromptShown = false
        }
        showEditor(session, false)
        openHelp()
        compose.onNodeWithContentDescription("个性边框填写效果图").assertExists()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNodeWithText("‹ 返回编辑").performClick()
        val draft = session.photos[1].draft
        assertEquals("", draft.currentParameters())
        assertEquals("", draft.currentModel())
        assertEquals("", draft.currentDate())
        assertEquals("", draft.currentPlace())
        assertFalse(draft.parameterManualInitialized)
        assertFalse(draft.modelManualInitialized)
        assertFalse(draft.missingPromptShown)
    }

    @Test fun busyAndColorEditingDisableMoreMenu() {
        val session = Session().apply { busy = true }
        showEditor(session, true)
        compose.onNodeWithContentDescription("更多").assertIsNotEnabled()
        compose.runOnUiThread { session.busy = false; session.colorTarget = ColorTarget.INFO }
        compose.onNodeWithContentDescription("更多").assertIsNotEnabled()
        compose.onNodeWithText("填写帮助").assertDoesNotExist()
    }

    @Test fun modalTapsNeverSaveOrChangePhotoAndRepeatedVisitsStartAtExample() {
        val session = Session()
        showEditor(session, false)
        repeat(2) {
            openHelp()
            compose.onNodeWithText("填写示例").assertIsDisplayed()
            compose.onNode(isDialog()).performTouchInput { click(center) }
            assertEquals(1, session.index)
            assertDrafts(session)
            compose.onNode(hasText("机型") and hasAnyAncestor(isDialog())).performScrollTo()
            compose.onNodeWithText("‹ 返回编辑").performClick()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun bothEditorTopBarsRemainReadableOnNarrowScreenWithLargeFont() {
        listOf(false, true).forEach { art ->
            showEditor(Session(), art, 1.5f)
            assertTopBar(art)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp")
    fun bothEditorTopBarsRemainReadableAtSingleRowWidthBoundary() {
        listOf(false, true).forEach { art ->
            showEditor(Session(), art)
            assertTopBar(art)
        }
    }

    private fun assertTopBar(art: Boolean) {
        compose.onNodeWithText(if (art) "艺术签名" else "个性边框").assertDoesNotExist()
        compose.onNodeWithText("完整照片预览").assertDoesNotExist()
        compose.onNodeWithText("‹ 水印库").assertIsDisplayed()
        val save = compose.onNodeWithText("保存当前").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val more = compose.onNodeWithContentDescription("更多").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("More must be beside Save without overlap", save.right <= more.left)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h640dp")
    fun helpContentCanScrollAtNarrowWidthAndLargeFontWithoutLosingReturn() {
        listOf(FillingHelpKind.FRAME, FillingHelpKind.ART).forEach { kind ->
            compose.runOnUiThread {
                compose.activity.setContent {
                    MaterialTheme {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                            Box(Modifier.fillMaxSize().width(320.dp)) { FillingHelpScreen(kind) {} }
                        }
                    }
                }
            }
            val fields = if (kind == FillingHelpKind.FRAME)
                listOf("填写示例", "焦距", "光圈", "快门", "ISO", "日期时间", "地点", "机型")
            else listOf("填写示例", "机型", "个性短句", "日期时间", "地点")
            fields.forEach {
                compose.onNodeWithText(it, substring = false).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("‹ 返回编辑").assertIsDisplayed()
            }
        }
    }

    @Test fun artEditorOpensOnlyArtFieldsAndItsRenderedExample() {
        showEditor(Session(), true)
        openHelp()
        compose.onNodeWithContentDescription("艺术签名填写效果图").assertExists()
        compose.onNodeWithContentDescription("个性边框填写效果图").assertDoesNotExist()
        listOf("焦距", "光圈", "快门", "ISO", "摄影参数").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        }
        listOf("机型", "个性短句", "日期时间", "地点").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun frameEditorOpensOnlyFrameFieldsAndItsRenderedExample() {
        showEditor(Session(), false)
        openHelp()
        compose.onNodeWithContentDescription("个性边框填写效果图").assertExists()
        compose.onNodeWithContentDescription("艺术签名填写效果图").assertDoesNotExist()
        compose.onNode(hasText("个性短句") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        listOf("焦距", "光圈", "快门", "ISO", "日期时间", "地点", "机型").forEach {
            compose.onNode(hasText(it) and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        }
    }
}
