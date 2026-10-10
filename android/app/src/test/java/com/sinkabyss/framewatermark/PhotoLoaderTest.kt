package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoLoaderTest {
    @Test
    fun pickerPhotoLoadsWhenProviderOnlySupportsTypedImageStreams() = runBlocking {
        val fixture = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(45, 90, 135))
        }
        val jpeg = ByteArrayOutputStream().use { output ->
            assertTrue(fixture.compress(Bitmap.CompressFormat.JPEG, 92, output))
            output.toByteArray()
        }
        fixture.recycle()

        val source = TypedOnlyPhotoSource(jpeg)
        val loaded = PhotoLoader.loadForPreview(source, Uri.parse("content://picker.fixture/images/1"))

        assertEquals(24, loaded.preview.width)
        assertEquals(16, loaded.preview.height)
        assertTrue("picker typed-image route was not used", source.requestedMimeTypes.isNotEmpty())
        assertTrue(source.requestedMimeTypes.all { it == "image/jpeg" || it == "image/*" })
        loaded.preview.recycle()
    }

    @Test fun exportReopensOriginalDespiteSmallerPreviewAndIgnoresUnapprovedSample() = runBlocking {
        val fixture = Bitmap.createBitmap(3200, 2132, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val jpeg = ByteArrayOutputStream().use { output ->
            assertTrue(fixture.compress(Bitmap.CompressFormat.JPEG, 92, output)); output.toByteArray()
        }
        fixture.recycle()
        val source = TypedOnlyPhotoSource(jpeg)
        val photo = PhotoLoader.loadForPreview(source, Uri.parse("content://picker.fixture/export"))
        try {
            assertTrue("Preview is intentionally smaller than the original", photo.preview.width < 3200)
            val full = PhotoLoader.loadForExport(source, photo, false, 2)
            try { assertEquals(3200, full.width); assertEquals(2132, full.height) } finally { full.recycle() }
            val sampled = PhotoLoader.loadForExport(source, photo, true, 2)
            try {
                assertEquals(1600, sampled.width); assertEquals(1066, sampled.height)
            } finally { sampled.recycle() }
            val noApprovedReduction = PhotoLoader.loadForExport(source, photo, true)
            try { assertEquals(3200, noApprovedReduction.width); assertEquals(2132, noApprovedReduction.height) }
            finally { noApprovedReduction.recycle() }
        } finally { photo.preview.recycle() }
    }

    private class TypedOnlyPhotoSource(private val jpeg: ByteArray) : PhotoContentSource {
        val requestedMimeTypes = mutableListOf<String>()

        override fun displayName(uri: Uri): String = "picker-fixture.jpg"

        override fun mimeType(uri: Uri): String = "image/jpeg"

        override fun openInputStream(uri: Uri): InputStream? = null

        override fun openTypedInputStream(uri: Uri, mimeType: String): InputStream? {
            requestedMimeTypes += mimeType
            return if (mimeType == "image/jpeg") ByteArrayInputStream(jpeg) else null
        }
    }
}
