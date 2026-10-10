package com.sinkabyss.framewatermark

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceMarketNamesTest {
    @Test fun bundledNamesResolveTheUsersDeviceAndBrandPrefixes() {
        val names = DeviceMarketNames.read(RuntimeEnvironment.getApplication())
        assertEquals("Redmi Note 12 Turbo", names["23049RAD8C"])
        assertEquals("Redmi Note 12 Turbo", DeviceMarketNames.resolve(" 23049rad8c ", "Redmi", names))
        assertEquals("Redmi Note 12 Turbo", DeviceMarketNames.resolve("Redmi 23049RAD8C", "Redmi", names))
    }
    @Test fun unknownCodesStayBlankAndReadableMarketNamesStayIntact() {
        assertEquals("", DeviceMarketNames.resolve("23049UNKNOWN"))
        assertEquals("", DeviceMarketNames.resolve("SM-UNKNOWN9"))
        assertEquals("", DeviceMarketNames.resolve("PEGM00"))
        assertEquals("Redmi Note 12 Turbo", DeviceMarketNames.resolve("Redmi Note 12 Turbo"))
        assertEquals("iPhone 15 Pro", DeviceMarketNames.resolve("iPhone 15 Pro"))
    }
    @Test fun missingModelDoesNotUseTheCurrentPhone() {
        assertEquals("", DeviceMarketNames.resolve(""))
        assertEquals("", PhotoMetadataReader.read(null, "no-exif.jpg", 600, 800).model)
    }
    @Test fun exifReaderAndManualDraftUseTheMarketName() {
        val file = File.createTempFile("market-model", ".jpg", RuntimeEnvironment.getApplication().cacheDir)
        file.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val exif = ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_MODEL, "23049RAD8C")
            setAttribute(ExifInterface.TAG_MAKE, "Redmi")
        }
        val metadata = PhotoMetadataReader.read(exif, "photo.jpg", 600, 800, DeviceMarketNames.read(RuntimeEnvironment.getApplication()))
        assertEquals("Redmi Note 12 Turbo", metadata.model)
        val draft = PhotoDraft(metadata)
        draft.selectModelMode(true)
        assertEquals("Redmi Note 12 Turbo", draft.currentModel())
        draft.modelText = "我的相机"
        assertEquals("我的相机", draft.currentModel())
        file.delete()
    }
}
