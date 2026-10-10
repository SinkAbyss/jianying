package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameSaveRequestTest {
    @Test fun loaderAllocationFailureRequiresQualityConfirmationAndAdvancesApprovedSample() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "image/jpeg"
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw OutOfMemoryError("Injected allocation failure")
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "save.oom" })
        ShadowContentResolver.registerProviderInternal("save.oom", provider)
        val preview = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        val photo = LoadedPhoto(Uri.parse("content://save.oom/first"), PhotoMetadata("first.jpg", 4624, 2600), preview)
        val requests = listOf(
            FrameSaveRequest.capture(photo) { FrameOptions() },
            ArtSignatureSaveRequest(photo, ArtSignatureOptions(signature = preview)),
        )
        try {
            for (request in requests) for ((approved, expectedSample) in listOf(null to 2, 2 to 4)) {
                try {
                    request.render(context, approved != null, approved)
                    fail("Allocation failure must ask before reducing quality")
                } catch (warning: ImageTooLargeException) {
                    assertEquals(4624, warning.width); assertEquals(2600, warning.height)
                    assertEquals(expectedSample, warning.sampleSize)
                    assertTrue(warning.cause is OutOfMemoryError)
                    assertSame(photo, request.photo)
                }
            }
        } finally { preview.recycle() }
    }

    @Test fun delayedOriginalLoadingCannotChangeTheClickedPhotoAndSettings() = runBlocking {
        val fixture = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(80, 140, 100)) }
        val signature = Bitmap.createBitmap(90, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val photo = LoadedPhoto(Uri.parse("content://save.fixture/first"), PhotoMetadata("first.png", 480, 320), fixture)
        val clicked = FrameOptions(style = BorderStyle.White, signatureBitmap = signature,
            signatureSizePercent = 75, parameterText = "CLICKED", dateText = "2026.10.02",
            colors = ForegroundColors(ColorChoice.custom(Color.RED), ColorChoice.custom(Color.BLUE)))
        var currentOptions = clicked
        var reads = 0
        val request = FrameSaveRequest.capture(photo) { reads++; currentOptions }
        val expected = FrameRenderer.render(fixture, clicked)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var loadedSource: Bitmap? = null
        val job = async {
            request.render { selected ->
                assertSame(photo, selected)
                entered.complete(Unit)
                release.await()
                fixture.copy(Bitmap.Config.ARGB_8888, false).also { loadedSource = it }
            }
        }
        entered.await()
        currentOptions = clicked.copy(layout = FrameLayoutKind.BOX, style = BorderStyle.Black,
            signatureBitmap = null, signatureSizePercent = 140, parameterText = "LATER", dateText = "later",
            colors = ForegroundColors(ColorChoice.custom(Color.GREEN), ColorChoice.Default))
        release.complete(Unit)
        val actual = job.await()
        try {
            assertEquals("Settings must be read once at the save click", 1, reads)
            assertPixelsEqual(expected, actual)
            assertTrue("Loaded export source must be released", loadedSource!!.isRecycled)
        } finally { actual.recycle(); expected.recycle(); signature.recycle(); fixture.recycle() }
    }

    @Test fun largeImageConfirmationRetriesTheSamePhotoAndSettings() = runBlocking {
        val fixture = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 100, 180)) }
        val photo = LoadedPhoto(Uri.parse("content://save.fixture/large"), PhotoMetadata("large.png", 4000, 4000), fixture)
        val clicked = FrameOptions(layout = FrameLayoutKind.BOX, style = BorderStyle.White,
            parameterText = "BEFORE CONFIRMATION", placeText = "saved place")
        var currentOptions = clicked
        val request = FrameSaveRequest.capture(photo) { currentOptions }
        try {
            request.render { throw ImageTooLargeException(4000, 4000, 2) }
            fail("The original loader must request downsampling confirmation")
        } catch (expected: ImageTooLargeException) { assertEquals(2, expected.sampleSize) }
        currentOptions = FrameOptions(style = BorderStyle.Black, parameterText = "AFTER CONFIRMATION")
        val expected = FrameRenderer.render(fixture, clicked)
        val actual = request.render { selected ->
            assertSame(photo, selected)
            fixture.copy(Bitmap.Config.ARGB_8888, false)
        }
        try { assertPixelsEqual(expected, actual) }
        finally { actual.recycle(); expected.recycle(); fixture.recycle() }
    }

    private fun assertPixelsEqual(expected: Bitmap, actual: Bitmap) {
        assertEquals("Clicked layout width", expected.width, actual.width)
        assertEquals("Clicked layout height", expected.height, actual.height)
        val a = IntArray(expected.width * expected.height)
        val b = IntArray(actual.width * actual.height)
        expected.getPixels(a, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(b, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals("Export must match the clicked background, signature and text", a, b)
    }
}
