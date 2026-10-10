package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Frozen on the UI thread at the save click; reused through confirmation and retry. */
internal class FrameSaveRequest private constructor(
    override val photo: LoadedPhoto,
    private val options: FrameOptions,
) : WatermarkSaveRequest {
    companion object {
        fun capture(photo: LoadedPhoto, options: () -> FrameOptions) = FrameSaveRequest(photo, options())
    }

    override suspend fun render(context: Context, allowDownsample: Boolean, approvedSampleSize: Int?): Bitmap =
        try {
            render { PhotoLoader.loadForExport(context, it, allowDownsample, approvedSampleSize) }
        } catch (failure: OutOfMemoryError) {
            val nextSample = (if (allowDownsample) approvedSampleSize ?: 1 else 1) * 2
            Log.w("JianyingExport", "Frame allocation failed; nextSample=$nextSample", failure)
            throw ImageTooLargeException(photo.metadata.width, photo.metadata.height, nextSample)
                .apply { initCause(failure) }
        }

    internal suspend fun render(loadPhoto: suspend (LoadedPhoto) -> Bitmap): Bitmap {
        val source = loadPhoto(photo)
        try {
            return withContext(Dispatchers.Default) { FrameRenderer.render(source, options) }
        } finally {
            source.recycle()
        }
    }
}
