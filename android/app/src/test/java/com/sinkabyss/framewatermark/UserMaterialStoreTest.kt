package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserMaterialStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun deletedPresetStaysUnavailableAfterReloadAndNeverUsesCachedBitmap() {
        val store = UserMaterialStore(context)
        val own = store.addText("保留签名", "sans")
        assertNotNull(store.bitmap("sig-d"))
        assertNotNull(store.bitmap("sig-d", thumbnail = true))
        store.delete("sig-d")
        val reloaded = UserMaterialStore(context)
        assertNull(store.bitmap("sig-d"))
        assertNull(reloaded.bitmap("sig-d", thumbnail = true))
        assertFalse(reloaded.available().any { it.id == "sig-d" })
        assertTrue(reloaded.available().any { it.id == "sig-a" })
        assertTrue(reloaded.available().any { it.id == "circle-a" })
        assertEquals(listOf(own), reloaded.read())
    }

    @Test fun textAndPngReloadFromAnotherStoreWithIndependentIdentity() {
        val store = UserMaterialStore(context)
        val text = store.addText("简影", "serif")
        val bitmap = Bitmap.createBitmap(30, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val image = store.addBitmap("圆图", MaterialKind.IMAGE, bitmap)
        val reopened = UserMaterialStore(context)
        assertEquals(listOf(text, image), reopened.read())
        assertNotEquals(text.id, image.id)
        assertEquals(Color.RED, reopened.bitmap(image.id)!!.getPixel(5, 5))
        assertTrue(reopened.bitmap(text.id)!!.width > 0)
    }

    @Test fun deleteRemovesOnlyOwnFileAndNeverReusesId() {
        val store = UserMaterialStore(context)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val first = store.addBitmap("笔迹", MaterialKind.SIGNATURE, bitmap)
        val kept = store.addBitmap("保留", MaterialKind.IMAGE, bitmap)
        assertEquals(first.file, store.read().first { it.id == first.id }.file)
        store.bitmap(first.id)
        store.delete(first.id)
        assertFalse(File(context.filesDir, "user-materials/${first.file}").exists())
        assertTrue(File(context.filesDir, "user-materials/${kept.file}").exists())
        assertNull(store.bitmap(first.id))
        val next = store.addText("新签名", "sans")
        assertNotEquals(first.id, next.id)
        assertEquals(setOf(kept.id, next.id), UserMaterialStore(context).read().map { it.id }.toSet())
    }

    @Test fun corruptedIndexIsNotReplacedByAnEmptyLibrary() {
        val directory = File(context.filesDir, "user-materials").apply { mkdirs() }
        val index = File(directory, "index.json").apply { writeText("damaged record") }
        assertThrows(IllegalStateException::class.java) { UserMaterialStore(context).addText("新签名", "sans") }
        assertEquals("damaged record", index.readText())
    }

    @Test fun transparentInputKeepsOriginalAlphaAndRemovesRgb() {
        val input = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.TRANSPARENT)
            setPixel(1, 0, Color.argb(64, 0, 0, 0))
            setPixel(2, 0, Color.RED)
        }
        val result = MaterialPixels.extract(input, preserveAlpha = true, threshold = 25)
        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        assertEquals(64, Color.alpha(result.getPixel(1, 0)))
        assertEquals(Color.WHITE, result.getPixel(2, 0))
        assertEquals(64, Color.alpha(input.getPixel(1, 0)))
    }

    @Test fun bothDarkOnLightAndLightOnDarkProduceTheSameMask() {
        fun extract(background: Int, ink: Int): Bitmap {
            val input = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply {
                eraseColor(background); setPixel(2, 2, ink)
            }
            return MaterialPixels.extract(input, false, 25)
        }
        listOf(extract(Color.WHITE, Color.BLACK), extract(Color.BLACK, Color.WHITE)).forEach {
            assertEquals(0, Color.alpha(it.getPixel(0, 0)))
            assertEquals(Color.WHITE, it.getPixel(2, 2))
            val trimmed = MaterialPixels.trim(it)
            assertEquals(1, trimmed.width); assertEquals(1, trimmed.height)
        }
    }

    @Test fun shadedPaperBecomesTransparentWhileColoredInkRemainsTintable() {
        val input = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 120) {
            val paper = 140 + x * 100 / 119
            input.setPixel(x, y, Color.rgb(paper, paper, paper))
        }
        for (y in 20..39) for (x in 30..89) input.setPixel(x, y, Color.rgb(100, 15, 25))
        val result = MaterialPixels.extract(input, false, 25)
        assertEquals("纸张阴影不能变成水印背景", 0, Color.alpha(result.getPixel(10, 30)))
        assertEquals(0, Color.alpha(result.getPixel(110, 30)))
        assertTrue(Color.alpha(result.getPixel(50, 30)) > 200)
        assertEquals(255, Color.red(result.getPixel(50, 30)))
        assertEquals(255, Color.green(result.getPixel(50, 30)))
        assertEquals(255, Color.blue(result.getPixel(50, 30)))
        val trimmed = MaterialPixels.trim(result)
        assertEquals(60, trimmed.width)
        assertEquals(20, trimmed.height)
    }

    @Test fun userSignatureReferenceSurvivesRecipeRoundTrip() {
        val asset = UserMaterialStore(context).addText("摄影", "sans")
        val recipe = FrameRecipe("自己的配方", FrameLayoutKind.BOX, null, 100, asset.id, 112)
        val read = FrameRecipeStore.parse(FrameRecipeStore.serialize(listOf(recipe))).single()
        assertEquals(asset.id, read.signatureId)
        assertEquals(112, read.signaturePercent)
    }
}
