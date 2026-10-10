package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Batch save (P11) rules: one failure must not stop the others, memory pressure must pause rather
 * than silently shrink, and the pause must resume at the right photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BatchSaverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class FakeRequest(
        override val photo: LoadedPhoto,
        private val onRender: suspend () -> Bitmap,
    ) : WatermarkSaveRequest {
        override suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int?): Bitmap =
            onRender()
    }

    private fun photo(name: String) = LoadedPhoto(
        Uri.parse("content://batch/$name"),
        PhotoMetadata(name, 40, 30),
        Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888),
    )

    private fun ok(name: String) = FakeRequest(photo(name)) {
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    }

    private fun fail(name: String, message: String) = FakeRequest(photo(name)) {
        throw IllegalStateException(message)
    }

    private fun tooLarge(name: String, sample: Int) = FakeRequest(photo(name)) {
        throw ImageTooLargeException(6144, 8192, sample)
    }

    /** Fails until an explicit sample size is approved, then succeeds like the real loader. */
    private fun tooLargeUntilApproved(name: String, sample: Int) =
        object : WatermarkSaveRequest {
            override val photo = photo(name)
            override suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int?): Bitmap {
                if (approvedSampleSize == null) throw ImageTooLargeException(6144, 8192, sample)
                return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            }
        }

    private val saving = { _: Context, bitmap: Bitmap, name: String ->
        SavedImage(name, bitmap.width, bitmap.height, "Pictures/简影边框")
    }

    @Test
    fun oneFailureDoesNotStopTheRestAndEveryOutcomeIsReported() = runBlocking {
        val requests = listOf(ok("a.jpg"), fail("b.jpg", "相册写入失败"), ok("c.jpg"))
        val result = BatchSaver.run(context, requests, save = saving)

        assertNull(result.waiting)
        assertEquals(3, result.outcomes.size)
        assertEquals(listOf(true, false, true), result.outcomes.map { it.succeeded })
        assertEquals(2, result.successCount)
        assertEquals(1, result.failureCount)
        assertEquals("相册写入失败", result.outcomes[1].error)
    }

    @Test
    fun memoryPressurePausesAtThatPhotoWithoutShrinkingIt() = runBlocking {
        val requests = listOf(ok("a.jpg"), tooLarge("big.jpg", 2), ok("c.jpg"))
        val result = BatchSaver.run(context, requests, save = saving)

        assertNotNull("必须停在需要确认的那张而不是偷偷缩小", result.waiting)
        assertEquals(1, result.waiting!!.index)
        assertEquals(2, result.waiting!!.warning.sampleSize)
        assertEquals("停之前已完成的照片要保留", listOf(true), result.outcomes.map { it.succeeded })
        assertEquals("停下来的那张和它后面的都不能算已保存", 1, result.outcomes.size)
    }

    @Test
    fun approvedSampleIsUsedForThePausedPhotoAndTheBatchContinues() = runBlocking {
        var seenSample: Int? = -1
        val requests = listOf(
            ok("a.jpg"),
            object : WatermarkSaveRequest {
                override val photo = photo("big.jpg")
                override suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int?): Bitmap {
                    seenSample = approvedSampleSize
                    return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
                }
            },
            ok("c.jpg"),
        )
        val result = BatchSaver.run(
            context, requests, startIndex = 1, priorOutcomes = emptyList(),
            allowDownsample = true, approvedSamples = mapOf(1 to 2), save = saving,
        )
        assertNull(result.waiting)
        assertEquals(2, seenSample)
        assertEquals(listOf(true, true), result.outcomes.map { it.succeeded })
        assertEquals(listOf(1, 2), result.outcomes.map { it.index })
    }

    @Test
    fun resumingKeepsTheOutcomesCollectedBeforeThePause() = runBlocking {
        val requests = listOf(ok("a.jpg"), ok("b.jpg"), tooLargeUntilApproved("c.jpg", 4))
        val first = BatchSaver.run(context, requests, save = saving)
        assertEquals(2, first.waiting!!.index)

        val second = BatchSaver.run(
            context, requests, startIndex = first.waiting!!.index, priorOutcomes = first.outcomes,
            allowDownsample = true, approvedSamples = mapOf(2 to 4), save = saving,
        )
        assertNull(second.waiting)
        assertEquals("暂停前的结果必须保留", 3, second.outcomes.size)
        assertEquals(listOf(0, 1, 2), second.outcomes.map { it.index })
        assertEquals(3, second.successCount)
    }

    @Test
    fun outputBitmapIsRecycledEvenWhenPublishingFails() = runBlocking {
        var produced: Bitmap? = null
        val request = FakeRequest(photo("a.jpg")) {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).also { produced = it }
        }
        val result = BatchSaver.run(context, listOf(request), save = { _, _, _ -> throw IllegalStateException("x") })
        assertEquals(1, result.failureCount)
        assertTrue("失败路径也必须回收位图", produced!!.isRecycled)
    }

    @Test
    fun singlePhotoBatchIsReportedAsOneSuccess() = runBlocking {
        val result = BatchSaver.run(context, listOf(ok("only.jpg")), save = saving)
        assertEquals(1, result.successCount)
        assertEquals(0, result.failureCount)
        assertNull(result.waiting)
    }
}
