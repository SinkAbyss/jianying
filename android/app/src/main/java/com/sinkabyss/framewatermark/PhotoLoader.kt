package com.sinkabyss.framewatermark

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

data class LoadedPhoto(
    val uri: Uri,
    val metadata: PhotoMetadata,
    val preview: Bitmap,
)

class ImageTooLargeException(
    val width: Int,
    val height: Int,
    val sampleSize: Int,
) : Exception("导出时内存分配失败。继续后会按 ${sampleSize}× 降采样另存，原图保持不变。")

internal interface PhotoContentSource {
    fun displayName(uri: Uri): String
    fun mimeType(uri: Uri): String?
    fun openInputStream(uri: Uri): InputStream?
    fun openTypedInputStream(uri: Uri, mimeType: String): InputStream?
}

private class ResolverPhotoContentSource(private val resolver: ContentResolver) : PhotoContentSource {
    override fun displayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return "照片"
    }

    override fun mimeType(uri: Uri): String? = resolver.getType(uri)

    override fun openInputStream(uri: Uri): InputStream? = resolver.openInputStream(uri)

    override fun openTypedInputStream(uri: Uri, mimeType: String): InputStream? =
        resolver.openTypedAssetFileDescriptor(uri, mimeType, Bundle.EMPTY)?.createInputStream()
}

object PhotoLoader {
    private const val PREVIEW_LONG_EDGE = 1_600

    suspend fun loadForPreview(context: Context, uri: Uri): LoadedPhoto =
        withContext(Dispatchers.IO) {
            loadForPreview(ResolverPhotoContentSource(context.contentResolver), uri, DeviceMarketNames.read(context))
        }

    internal suspend fun loadForPreview(source: PhotoContentSource, uri: Uri,
        marketNames: Map<String, String> = emptyMap()): LoadedPhoto = withContext(Dispatchers.IO) {
        val name = source.displayName(uri)
        val bounds = readBounds(source, uri)
        require(bounds.width > 0 && bounds.height > 0) { "无法读取这张图片的尺寸" }
        val exif = readExif(source, uri)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = orientedSize(bounds.width, bounds.height, orientation)
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
        val photoMarketName = if (make.equals("Xiaomi", true) || make.equals("Redmi", true)) {
            runCatching { openImageStream(source, uri).use { PhotoMarketName.read(it, make) } }.getOrDefault("")
        } else ""
        val metadata = PhotoMetadataReader.read(exif, name, oriented.first, oriented.second, marketNames, photoMarketName)
        val sample = sampleForLongEdge(bounds.width, bounds.height, PREVIEW_LONG_EDGE)
        val bitmap = decode(source, uri, sample, orientation)
        LoadedPhoto(uri, metadata, bitmap)
    }

    suspend fun loadForExport(context: Context, photo: LoadedPhoto, allowDownsample: Boolean,
        approvedSampleSize: Int? = null): Bitmap =
        loadForExport(ResolverPhotoContentSource(context.contentResolver), photo, allowDownsample, approvedSampleSize)

    internal suspend fun loadForExport(source: PhotoContentSource, photo: LoadedPhoto, allowDownsample: Boolean,
        approvedSampleSize: Int? = null): Bitmap = withContext(Dispatchers.IO) {
        val bounds = readBounds(source, photo.uri)
        require(bounds.width > 0 && bounds.height > 0) { "无法读取这张图片的尺寸" }
        val exif = readExif(source, photo.uri)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = orientedSize(bounds.width, bounds.height, orientation)
        // Try the original directly. A smaller decode requires an explicit retry approval.
        val sample = if (allowDownsample) (approvedSampleSize ?: 1).coerceAtLeast(1) else 1
        Log.i("JianyingExport", "original=${oriented.first}x${oriented.second} sample=$sample")
        decode(source, photo.uri, sample, orientation)
    }

    private data class Bounds(val width: Int, val height: Int)

    private fun readBounds(source: PhotoContentSource, uri: Uri): Bounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageStream(source, uri).use { BitmapFactory.decodeStream(it, null, options) }
        return Bounds(options.outWidth, options.outHeight)
    }

    private fun readExif(source: PhotoContentSource, uri: Uri): ExifInterface? =
        runCatching { openImageStream(source, uri).use(::ExifInterface) }.getOrNull()

    private fun decode(source: PhotoContentSource, uri: Uri, sampleSize: Int, orientation: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = openImageStream(source, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("系统无法解码这张照片")
        try {
            return orientForExif(bitmap, orientation)
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun openImageStream(source: PhotoContentSource, uri: Uri): InputStream {
        var firstFailure: Exception? = null
        try {
            source.openInputStream(uri)?.let { return it }
        } catch (failure: Exception) {
            firstFailure = failure
        }

        val mimeType = runCatching { source.mimeType(uri) }.getOrNull()
        for (candidate in listOfNotNull(mimeType, "image/*").distinct()) {
            try {
                source.openTypedInputStream(uri, candidate)?.let { return it }
            } catch (failure: Exception) {
                if (firstFailure == null) firstFailure = failure
            }
        }

        val provider = uri.authority ?: uri.scheme ?: "未知来源"
        val failure = FileNotFoundException(
            "无法打开所选图片（来源：$provider）。请重新选择；如果照片在云端，请先下载到本机。",
        )
        firstFailure?.let { failure.initCause(it) }
        throw failure
    }

    internal fun orientForExif(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    internal fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> = when (orientation) {
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270 -> height to width
        else -> width to height
    }

    private fun sampleForLongEdge(width: Int, height: Int, limit: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > limit) sample *= 2
        return sample
    }

}
