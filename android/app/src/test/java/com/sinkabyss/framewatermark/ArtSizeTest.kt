package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtSizeTest {
    private fun image(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun inkHeight(bitmap: Bitmap): Int {
        val ys = (0 until bitmap.height).filter { y -> (0 until bitmap.width).any { x -> Color.red(bitmap.getPixel(x, y)) > 200 } }
        return ys.last() - ys.first() + 1
    }
    @Test fun signatureActuallyShrinksAndEnlargesInRenderedPixels() {
        val source = image(600, 800, Color.BLACK)
        val signature = image(40, 20, Color.WHITE)
        val heights = listOf(50, 100, 150).map { percent ->
            val output = ArtSignatureRenderer.render(source, ArtSignatureOptions(signature, signaturePercent = percent))
            inkHeight(output).also { output.recycle() }
        }
        assertTrue(heights[0] in 35..40)
        assertTrue(heights[1] in 70..78)
        assertTrue(heights[2] in 108..114)
        source.recycle(); signature.recycle()
    }
    @Test fun appendedStyleCopiesSizeAndRemainsIndependent() {
        val original = WatermarkStyle().apply { artSignaturePercent = 150 }
        val appended = original.copyOf()
        assertEquals(150, appended.artSignaturePercent)
        appended.artSignaturePercent = 50
        assertEquals(150, original.artSignaturePercent)
    }
    @Test fun recipesKeepSizeAndOldRecipesDefaultTo100() {
        val recipe = ArtRecipe("大签名", "sig-a", 1, 2, 100, false, false, false, false, signaturePercent = 150)
        assertEquals(150, ArtRecipeStore.parse(ArtRecipeStore.serialize(listOf(recipe))).single().signaturePercent)
        assertEquals(100, ArtRecipeStore.parse("""[{"signature":"sig-a"}]""").single().signaturePercent)
        assertEquals(200, ArtRecipeStore.parse("""[{"signature":"sig-a","signaturePercent":999}]""").single().signaturePercent)
    }
    @Test fun saveRequestKeepsClickTimeSize() = runBlocking {
        val source = image(600, 800, Color.BLACK)
        val signature = image(40, 20, Color.WHITE)
        val photo = LoadedPhoto(Uri.parse("content://art-size/photo"), PhotoMetadata("photo.jpg", 600, 800), source)
        val style = WatermarkStyle().apply { artSignaturePercent = 150 }
        val options = ArtPhotoDraft(photo.metadata).options(signature, 1, 2, 100, signaturePercent = style.artSignaturePercent)
        val request = ArtSignatureSaveRequest(photo, options)
        style.artSignaturePercent = 25
        val exported = request.render { image(600, 800, Color.BLACK) }
        assertTrue(inkHeight(exported) in 108..114)
        exported.recycle(); source.recycle(); signature.recycle()
    }
}
