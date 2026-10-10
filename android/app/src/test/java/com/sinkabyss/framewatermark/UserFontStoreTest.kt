package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Typeface
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserFontStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun fixture(name: String) = File(System.getProperty("c1.workspace"), "artifacts/FONT-001/fixtures/$name")
        .also {
            assumeTrue(
                "FONT-001 开放字体测试素材缺失（本地素材，未随源码分发）：${it.absolutePath}",
                it.isFile,
            )
        }
    private fun import(store: UserFontStore, name: String): UserFont = fixture(name).inputStream().use { store.import(name, it) }

    @Test fun importedTtfSurvivesSourceRemovalAndUsesItsOwnPixelsAfterReload() {
        val source = File(context.cacheDir, "source.ttf").apply { fixture("Abel-Regular.ttf").copyTo(this) }
        val store = UserMaterialStore(context)
        val font = source.inputStream().use { store.fonts.import("Abel.ttf", it) }
        val signature = store.addText("Watermark 123", font.id)
        assertTrue(source.delete())
        val reopened = UserMaterialStore(context)
        assertEquals(font, reopened.fonts.read().single())
        assertEquals(font.id, reopened.read().single().font)
        val expected = MaterialPixels.text(signature.text, Typeface.Builder(fixture("Abel-Regular.ttf")).build()!!)
        val actual = reopened.bitmap(signature.id)!!
        assertTrue(actual.sameAs(expected))
        assertFalse(actual.sameAs(MaterialPixels.text(signature.text, "sans")))
        assertTrue(reopened.bitmap(signature.id, thumbnail = true)!!.width <= 384)
    }

    @Test fun otfWithMixedTextRendersAndDuplicateBytesReuseStableIdentity() {
        val store = UserFontStore(context)
        val font = import(store, "SourceSerif4-Regular.otf")
        val again = fixture("SourceSerif4-Regular.otf").inputStream().use { store.import("另一个名字.otf", it) }
        assertEquals(font, again)
        assertEquals(listOf(font), store.read())
        assertEquals(2, File(context.filesDir, "user-fonts").listFiles()!!.size)
        val bitmap = MaterialPixels.text("简影 ABC", UserFontStore(context).typeface(font.id))
        assertTrue(bitmap.width > 32 && bitmap.height > 32)
        assertFalse(bitmap.sameAs(MaterialPixels.text("简影 ABC", "sans")))
    }

    @Test fun badAndTruncatedFilesDoNotChangeExistingFontsOrLeaveNewFiles() {
        val store = UserFontStore(context)
        val font = import(store, "Abel-Regular.ttf")
        val invalid = listOf("not a font".toByteArray(), fixture("Abel-Regular.ttf").readBytes().copyOf(256))
        invalid.forEach { bytes ->
            assertThrows(Exception::class.java) { store.import("invalid.ttf", ByteArrayInputStream(bytes)) }
            assertEquals(listOf(font), store.read())
            assertEquals(2, File(context.filesDir, "user-fonts").listFiles()!!.size)
        }
    }

    @Test fun oversizeOrInterruptedStreamLeavesLibraryIntact() {
        val store = UserFontStore(context)
        val font = import(store, "Abel-Regular.ttf")
        val oversized = object : InputStream() {
            var remaining = UserFontStore.MAX_BYTES + 1
            override fun read() = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        assertThrows(IllegalArgumentException::class.java) { store.import("large.ttf", oversized) }
        assertThrows(java.io.IOException::class.java) {
            store.import("interrupted.otf", object : InputStream() { override fun read(): Int = throw java.io.IOException("provider stopped") })
        }
        assertEquals(listOf(font), store.read())
        assertEquals(2, File(context.filesDir, "user-fonts").listFiles()!!.size)
    }

    @Test fun damagedIndexIsPreservedAndMissingFontCannotSilentlyBecomeSans() {
        val store = UserMaterialStore(context)
        val font = import(store.fonts, "Abel-Regular.ttf")
        val signature = store.addText("Abel", font.id)
        // Windows keeps native mapped font files open; model a missing private file via a new stable ID.
        val missingId = "font-00000000-0000-0000-0000-000000000001"
        listOf("user-fonts/index.json", "user-materials/index.json").forEach { path ->
            File(context.filesDir, path).apply { writeText(readText().replace(font.id, missingId)) }
        }
        assertThrows(Exception::class.java) { UserMaterialStore(context).bitmap(signature.id) }
        assertThrows(Exception::class.java) { UserMaterialStore(context).addText("No fallback", missingId) }
        assertEquals(listOf(signature.copy(font = missingId)), store.read())
        val index = File(context.filesDir, "user-fonts/index.json").apply { writeText("broken") }
        assertThrows(IllegalStateException::class.java) { import(UserFontStore(context), "Abel-Regular.ttf") }
        assertEquals("broken", index.readText())
    }

    @Test fun deleteUnusedFontRemovesSelectionAndCacheButKeepsSource() {
        val store = UserMaterialStore(context)
        val font = import(store.fonts, "Abel-Regular.ttf")
        store.deleteFont(font.id)
        assertTrue(UserFontStore(context).read().isEmpty())
        // Native Windows font mappings can retain the file until process exit. Android removal
        // is verified on the phone; the host checks catalog/cache and retry cleanup separately.
        assertThrows(Exception::class.java) { store.fonts.typeface(font.id) }
        assertTrue(fixture("Abel-Regular.ttf").isFile)
        store.deleteFont(font.id) // Repeated removal is harmless.
        assertThrows(IllegalArgumentException::class.java) { store.deleteFont("sans") }
    }

    @Test fun reopenCleansUnreferencedPrivateCopyAndKeepsActiveFont() {
        val store = UserFontStore(context)
        val font = import(store, "Abel-Regular.ttf")
        val orphan = File(context.filesDir, "user-fonts/font-00000000-0000-0000-0000-000000000002.font")
            .apply { writeText("retired copy") }
        assertEquals(listOf(font), UserFontStore(context).read())
        assertFalse(orphan.exists())
        assertTrue(File(context.filesDir, "user-fonts/${font.file}").exists())
    }

    @Test fun deletingUsedFontKeepsSignatureIdentityAndExactPixelsAfterReload() {
        val store = UserMaterialStore(context)
        val font = import(store.fonts, "Abel-Regular.ttf")
        val signature = store.addText("Preserve 123", font.id)
        val expected = MaterialPixels.text(signature.text, Typeface.Builder(fixture("Abel-Regular.ttf")).build()!!)
        store.bitmap(signature.id, thumbnail = true) // Existing caches must remain safe.
        val other = store.addText("保留系统签名", "serif")
        store.deleteFont(font.id)
        val reopened = UserMaterialStore(context)
        val preserved = reopened.read().first { it.id == signature.id }
        assertEquals(signature.name, preserved.name)
        assertEquals("", preserved.text)
        assertEquals("${signature.id}.png", preserved.file)
        assertTrue(reopened.bitmap(signature.id)!!.sameAs(expected))
        assertEquals(other, reopened.read().first { it.id == other.id })
        assertTrue(reopened.fonts.read().isEmpty())
    }

    @Test fun failedSignaturePreservationDoesNotRemoveFontOrModifyOtherSignatures() {
        val store = UserMaterialStore(context)
        val font = import(store.fonts, "Abel-Regular.ttf")
        val signature = store.addText("Keep", font.id)
        val occupied = File(context.filesDir, "user-materials/${signature.id}.png").apply { writeText("occupied") }
        assertThrows(IllegalStateException::class.java) { store.deleteFont(font.id) }
        assertEquals(listOf(font), store.fonts.read())
        assertEquals(listOf(signature), store.read())
        assertEquals("occupied", occupied.readText())
    }
}
