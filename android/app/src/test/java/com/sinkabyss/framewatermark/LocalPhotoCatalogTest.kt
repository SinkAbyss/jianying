package com.sinkabyss.framewatermark

import android.Manifest
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPhotoCatalogTest {
    @Test fun permissionsFollowSupportedApiRanges() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), localPhotoPermissions(29))
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), localPhotoPermissions(32))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), localPhotoPermissions(33))
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), localPhotoPermissions(35))
    }
    @Test fun fullPartialAndDeniedAreDistinct() {
        assertEquals(LocalPhotoAccess.FULL, localPhotoAccess(32) { it == Manifest.permission.READ_EXTERNAL_STORAGE })
        assertEquals(LocalPhotoAccess.FULL, localPhotoAccess(35) { it == Manifest.permission.READ_MEDIA_IMAGES })
        assertEquals(LocalPhotoAccess.PARTIAL, localPhotoAccess(35) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED })
        assertEquals(LocalPhotoAccess.DENIED, localPhotoAccess(35) { false })
        assertEquals(LocalPhotoAccess.DENIED, localPhotoAccess(33) { it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED })
    }
    @Test fun selectionKeepsUserOrderAcrossAlbumsAndEnforcesLimit() {
        val a = Uri.parse("content://media/1"); val b = Uri.parse("content://media/2"); val c = Uri.parse("content://media/3")
        val selected = toggleLocalPhotoSelection(toggleLocalPhotoSelection(emptyList(), b, 2), a, 2)
        assertEquals(listOf(b, a), selected)
        assertEquals(selected, toggleLocalPhotoSelection(selected, c, 2))
        assertEquals(listOf(a), toggleLocalPhotoSelection(selected, b, 2))
    }
    @Test fun revocationRemovesOnlyUnavailableSelectionsWithoutReordering() {
        val a = Uri.parse("content://media/1"); val b = Uri.parse("content://media/2"); val c = Uri.parse("content://media/3")
        val available = listOf(LocalPhoto(a, "camera", "相机"), LocalPhoto(c, "bili", "bili"))
        assertEquals(listOf(c, a), reconcileLocalPhotoSelection(listOf(c, b, a), available))
        assertEquals(emptyList<Uri>(), reconcileLocalPhotoSelection(listOf(a), emptyList()))
    }
    @Test fun targetAlbumsAreCountedAndIdenticalNamesStaySeparate() {
        val photos = listOf(LocalPhoto(Uri.parse("content://media/1"), "camera", "相机"),
            LocalPhoto(Uri.parse("content://media/2"), "album1", "洛天依-收藏相册"),
            LocalPhoto(Uri.parse("content://media/3"), "bili", "bili"),
            LocalPhoto(Uri.parse("content://media/4"), "bili", "bili"),
            LocalPhoto(Uri.parse("content://media/5"), "album2", "洛天依-收藏相册"))
        val albums = localPhotoAlbums(photos)
        assertEquals("相机", albums.first().name)
        assertEquals(2, albums.single { it.id == "bili" }.count)
        assertEquals(2, albums.count { it.name == "洛天依-收藏相册" })
        assertEquals("相机", localPhotoAlbumName("Camera"))
    }
}
