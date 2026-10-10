package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FramePhraseTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun image() = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test fun realEditorKeepsPhraseAndFontIndependentWhileParametersStayEnabled() {
        val photos = (1..2).map { index -> PhotoEntry(LoadedPhoto(Uri.parse("content://phrase/$index"),
            PhotoMetadata("$index.jpg", 1200, 800, focalLength = "35 mm", aperture = "f/2.8", date = "2026.10.10"), image())) }
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { FrameWatermarkApp(photos) } } }
        compose.onNodeWithText("信息").performClick()
        compose.onNodeWithContentDescription("显示个性短句").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("个性短句")).performScrollTo().performTextReplacement("Wind and light")
        compose.onNodeWithContentDescription("选择短句字体").performScrollTo()
        compose.waitUntil(5_000) { !compose.onNodeWithContentDescription("选择短句字体")
            .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) }
        compose.onNodeWithContentDescription("选择短句字体").performClick()
        compose.onNodeWithContentDescription("字体：衬线").performClick()
        compose.onNodeWithText("字体 · 衬线 ›").assertExists()
        assertTrue(photos[0].draft.showParameters)
        assertEquals("35 mm   f/2.8", photos[0].draft.currentParameters())
        compose.onNodeWithContentDescription("切换到这张照片").performClick()
        compose.onNodeWithContentDescription("显示个性短句").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("切换到这张照片").performClick()
        compose.onNodeWithText("Wind and light").assertExists()
        compose.onNodeWithText("字体 · 衬线 ›").assertExists()
        assertFalse(photos[1].style.framePhraseEnabled)
        assertEquals("sans", photos[1].style.framePhraseFont)
    }

    @Test fun appendCopiesTextAndFontAsIndependentValues() {
        val original = WatermarkStyle().apply {
            framePhraseEnabled = true; framePhrase = "Wind"; framePhraseFont = "serif"
        }
        val copy = original.copyOf()
        assertEquals("Wind", copy.framePhrase)
        assertEquals("serif", copy.framePhraseFont)
        copy.framePhrase = "Light"; copy.framePhraseFont = "mono"; copy.framePhraseEnabled = false
        assertEquals("Wind", original.framePhrase)
        assertEquals("serif", original.framePhraseFont)
        assertTrue(original.framePhraseEnabled)
    }

    @Test fun recipesKeepSwitchAndFontWithCompatibleDefaultsWithoutPhraseText() {
        val recipe = FrameRecipe("短句", FrameLayoutKind.BAR, null, 100, null, 100,
            phraseEnabled = true, phraseFont = "serif")
        val json = FrameRecipeStore.serialize(listOf(recipe))
        val restored = FrameRecipeStore.parse(json).single()
        assertTrue(restored.phraseEnabled); assertEquals("serif", restored.phraseFont)
        assertFalse(json.contains("phraseText"))
        val old = FrameRecipeStore.parse("""[{"name":"旧配方"}]""").single()
        assertFalse(old.phraseEnabled); assertEquals("sans", old.phraseFont)
    }

    @Test fun phraseAndParametersBothRenderAndOnlyPhraseFontChanges() {
        val source = image()
        val base = FrameOptions(parameterText = "35 mm f/2.8", dateText = "2026.10.10")
        val noPhrase = FrameRenderer.render(source, base)
        val serif = FrameRenderer.render(source, base.copy(phraseText = "Wind and light", phraseTypeface = Typeface.SERIF))
        val mono = FrameRenderer.render(source, base.copy(phraseText = "Wind and light", phraseTypeface = Typeface.MONOSPACE))
        val bandStart = source.height
        val occupied = (bandStart until serif.height).filter { y ->
            (serif.width / 2 until serif.width).any { x -> Color.red(serif.getPixel(x, y)) < 180 }
        }
        val bands = occupied.filterIndexed { i, y -> i == 0 || y > occupied[i - 1] + 1 }
        assertEquals("phrase, parameters and date are three separate rows", 3, bands.size)
        val firstEnd = occupied.first { y -> y >= bands[1] } - 1
        var firstDifference = 0; var metadataDifference = 0
        for (y in bandStart until serif.height) for (x in serif.width / 2 until serif.width) {
            if (serif.getPixel(x, y) != mono.getPixel(x, y)) {
                if (y <= firstEnd) firstDifference++ else metadataDifference++
            }
        }
        assertTrue("font selection affects visible phrase pixels", firstDifference > 20)
        assertEquals("metadata retains its own font", 0, metadataDifference)
        assertTrue((bandStart until noPhrase.height).any { y -> (noPhrase.width / 2 until noPhrase.width).any { x ->
            Color.red(noPhrase.getPixel(x, y)) < 180
        } })
        listOf(source, noPhrase, serif, mono).forEach(Bitmap::recycle)
    }

    @Test fun saveFreezesEachPhotosTextAndFont() = runBlocking {
        val photo = LoadedPhoto(Uri.parse("content://phrase/save"), PhotoMetadata("save.jpg", 1200, 800), image())
        var text = "Wind"; var font = Typeface.SERIF
        val request = FrameSaveRequest.capture(photo) { FrameOptions(phraseText = text, phraseTypeface = font) }
        text = "Other"; font = Typeface.MONOSPACE
        val saved = request.render { image() }
        val expected = FrameRenderer.render(photo.preview, FrameOptions(phraseText = "Wind", phraseTypeface = Typeface.SERIF))
        assertTrue(saved.sameAs(expected))
        saved.recycle(); expected.recycle(); photo.preview.recycle()
    }

    @Test fun importedFontIsReusableAndMissingFontFallsBack() {
        val path = File(System.getProperty("c1.workspace"), "artifacts/FONT-001/fixtures/Abel-Regular.ttf")
        assumeTrue("Existing local public font fixture required", path.isFile)
        val store = UserFontStore(compose.activity)
        val font = path.inputStream().use { store.import("Abel.ttf", it) }
        val face = framePhraseTypeface(store, font.id)
        assertNotSame(Typeface.SANS_SERIF, face)
        val source = image()
        val rendered = FrameRenderer.render(source, FrameOptions(phraseText = "Wind", phraseTypeface = face))
        assertTrue(rendered.height > source.height)
        store.delete(font.id)
        assertSame(Typeface.SANS_SERIF, framePhraseTypeface(store, font.id))
        rendered.recycle(); source.recycle()
    }
}
