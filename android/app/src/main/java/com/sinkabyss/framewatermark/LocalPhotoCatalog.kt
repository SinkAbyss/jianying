package com.sinkabyss.framewatermark

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class LocalPhotoAccess { FULL, PARTIAL, DENIED }

internal fun localPhotoPermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
    sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    sdk >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun localPhotoAccess(sdk: Int, granted: (String) -> Boolean): LocalPhotoAccess = when {
    sdk >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> LocalPhotoAccess.FULL
    sdk >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> LocalPhotoAccess.PARTIAL
    sdk <= 32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> LocalPhotoAccess.FULL
    else -> LocalPhotoAccess.DENIED
}

internal fun localPhotoAccess(context: Context): LocalPhotoAccess = localPhotoAccess(Build.VERSION.SDK_INT) {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

internal data class LocalPhoto(val uri: Uri, val albumId: String, val albumName: String)
internal data class LocalPhotoAlbum(val id: String, val name: String, val count: Int)

internal fun localPhotoAlbumName(name: String): String = when (name) {
    "Camera" -> "相机"
    "Screenshots" -> "截图"
    else -> name.ifBlank { "其他图片" }
}

internal fun localPhotoAlbums(photos: List<LocalPhoto>): List<LocalPhotoAlbum> = photos
    .groupBy { it.albumId }.map { (id, entries) -> LocalPhotoAlbum(id, entries.first().albumName, entries.size) }
    .sortedWith(compareBy<LocalPhotoAlbum> { if (it.name == "相机") 0 else 1 }.thenBy { it.name })

/** Selection order survives switching albums; revoked/deleted rows are removed on refresh. */
internal fun reconcileLocalPhotoSelection(selected: List<Uri>, available: List<LocalPhoto>): List<Uri> {
    val accessible = available.mapTo(hashSetOf()) { it.uri }
    return selected.filter { it in accessible }.distinct()
}

internal fun toggleLocalPhotoSelection(selected: List<Uri>, uri: Uri, limit: Int): List<Uri> = when {
    uri in selected -> selected - uri
    selected.size >= limit -> selected
    else -> selected + uri
}

internal interface LocalPhotoSource {
    suspend fun read(): List<LocalPhoto>
    suspend fun thumbnail(uri: Uri): Bitmap
    suspend fun preview(uri: Uri): Bitmap
    fun clearThumbnails()
}

internal class LocalPhotoCatalog(private val context: Context) : LocalPhotoSource {
    private val resolver = context.contentResolver
    // Holds only small thumbnails. Eviction drops references; visible Compose images own theirs.
    private val thumbnails = object : LruCache<Uri, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.allocationByteCount
    }
    private val thumbnailSlots = Semaphore(3)

    override fun clearThumbnails() = thumbnails.evictAll()
    override suspend fun preview(uri: Uri): Bitmap = PhotoLoader.loadForPreview(context, uri).preview

    override suspend fun read(): List<LocalPhoto> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<LocalPhoto>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        cancellableProviderCall { signal ->
            resolver.query(collection, arrayOf(MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.IS_PENDING} = 0", null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC", signal)
                ?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val bucket = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    val path = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        signal.throwIfCanceled()
                        val directory = cursor.getString(path).orEmpty()
                        rows += LocalPhoto(ContentUris.withAppendedId(collection, cursor.getLong(id)),
                            // Distinguish directories with the same visible name (or a bucket hash collision).
                            "${cursor.getString(bucket)}:$directory",
                            localPhotoAlbumName(cursor.getString(name).orEmpty()))
                    }
                } ?: throw IllegalStateException("暂时无法读取照片，请重试。")
            rows.toList()
        }
    }

    override suspend fun thumbnail(uri: Uri): Bitmap = thumbnailSlots.withPermit {
        thumbnails.get(uri) ?: withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val bitmap = cancellableProviderCall { signal -> resolver.loadThumbnail(uri, Size(256, 256), signal) }
            thumbnails.put(uri, bitmap)
            bitmap
        }
    }
}

/** Register cancellation before a blocking provider call, allowing permission/navigation cancellation. */
private suspend fun <T> cancellableProviderCall(block: (CancellationSignal) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val result = block(signal)
            if (continuation.isActive) continuation.resume(result)
        } catch (failure: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
