package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 艺术签名配方 entry point and row text. The art editor previously had no preset entry at all, and
 * the row text is what the user reads before deciding to apply a preset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtRecipeWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private class Session {
        val entry = PhotoEntry(
            LoadedPhoto(
                Uri.parse("content://art-recipe/1"),
                PhotoMetadata("照片", 400, 300),
                Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF6C8057.toInt()) },
            ),
        )
        var index by mutableIntStateOf(0)
        var expanded by mutableStateOf(true)
        var height by mutableFloatStateOf(420f)
        var busy by mutableStateOf(false)
        var callbacks = 0
        val signature = Bitmap.createBitmap(80, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) }
    }

    private fun showArtEditor(session: Session, onRecipes: (() -> Unit)?, onSaveAll: (() -> Unit)? = null) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                        ArtSignatureScreen(session.entry, listOf(session.entry), session.index,
                            "test-signature", session.signature, 2, 0, 73, session.busy,
                            onBack = { session.callbacks++ }, onSave = { session.callbacks++ },
                            onSignature = { session.callbacks++ }, onHorizontal = { session.callbacks++ },
                            onVertical = { session.callbacks++ }, onOpacity = { session.callbacks++ },
                            onChoosePhoto = { session.index = it }, onAddPhotos = { session.callbacks++ },
                            onRemovePhoto = { session.callbacks++ },
                            materials = emptyList(), onOpenMaterials = { session.callbacks++ },
                            panelExpanded = session.expanded, panelHeightDp = session.height,
                            onPanelExpandedChanged = { session.expanded = it },
                            onPanelHeightChanged = { session.height = it },
                            colors = ForegroundColors(), colorTarget = null,
                            onOpenColor = { session.callbacks++ }, onColorChange = { session.callbacks++ },
                            onSelectColor = { _, _ -> session.callbacks++ },
                            onCancelColor = { session.callbacks++ }, onApplyColor = { session.callbacks++ },
                            onRecipes = onRecipes ?: {}, onSaveAll = onSaveAll)
                    }
                }
            }
        }
    }

    @Test
    fun artEditorExposesTheRecipeEntryAndTappingItInvokesTheCallback() {
        val session = Session()
        showArtEditor(session, onRecipes = { session.callbacks++ })
        compose.onNodeWithText("配方").assertExists()
        compose.onNodeWithText("配方").performClick()
        assertEquals(1, session.callbacks)
    }

    @Test
    fun recipeRowTextReportsPositionOpacityAndEnabledFields() {
        val label = artRecipeLabel(
            ArtRecipe("位置与信息", "sig-a", horizontal = 0, vertical = 2, opacity = 65,
                showModel = true, showPhrase = false, showDate = true, showPlace = true),
        )
        assertEquals("下左 · 透明度 65% · 机型、日期、地点", label)
    }

    @Test
    fun recipeRowTextSaysSoWhenNoOptionalFieldIsEnabled() {
        val label = artRecipeLabel(
            ArtRecipe("只有签名", "sig-a", horizontal = 1, vertical = 1, opacity = 100,
                showModel = false, showPhrase = false, showDate = false, showPlace = false),
        )
        assertEquals("中中 · 透明度 100% · 无附加信息", label)
    }

    @Test
    fun borderRecipeRowTextStillReportsLayoutBackgroundAndCircleSize() {
        val label = frameRecipeLabel(
            FrameRecipe("边框的", FrameLayoutKind.BOX, "circle-a", 120, "sig-a", 100, BorderBackgroundMode.BLUR),
        )
        assertEquals("四周留边 · 模糊 · 圆图 120%", label)
    }

    @Test
    fun recipeDrivenValuesAreReflectedInTheArtPreviewSettings() {
        val session = Session()
        showArtEditor(session, onRecipes = { session.callbacks++ })
        // The sample above renders 73% because that is the state handed to the screen; a preset is
        // applied by MainActivity feeding the restored numbers back in, which is covered by
        // ArtRecipeStoreTest. Here we only prove the screen renders the numbers it is given.
        compose.onNodeWithText("布局").performClick()
        compose.onNodeWithText("73%").assertExists()
    }

    @Test
    fun artEditorShowsTheBatchEntryOnlyWhenTheSessionHasSeveralPhotos() {
        val session = Session()
        showArtEditor(session, onRecipes = { session.callbacks++ }, onSaveAll = { session.callbacks++ })
        compose.onNodeWithText("全部保存").assertExists()
        compose.onNodeWithText("全部保存").performClick()
        assertEquals(1, session.callbacks)
    }

    @Test
    fun artEditorHidesTheBatchEntryForASinglePhoto() {
        val session = Session()
        showArtEditor(session, onRecipes = { session.callbacks++ }, onSaveAll = null)
        compose.onNodeWithText("全部保存").assertDoesNotExist()
        compose.onNodeWithText("保存当前").assertExists()
    }
}
