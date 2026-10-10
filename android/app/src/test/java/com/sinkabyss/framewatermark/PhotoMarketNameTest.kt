package com.sinkabyss.framewatermark

import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoMarketNameTest {
    private fun jpeg(rootName: String? = null, exifName: String? = null,
        waterInfo: String? = null, little: Boolean = true): ByteArray {
        val root = mutableListOf<Pair<Int, String>>()
        rootName?.let { root += 0x9a00 to it }
        val sub = mutableListOf<Pair<Int, String>>()
        exifName?.let { sub += 0x9a00 to it }
        waterInfo?.let { sub += 0x889e to it }
        val rootCount = root.size + if (sub.isEmpty()) 0 else 1
        val subOffset = 8 + 2 + rootCount * 12 + 4
        val dataOffset = subOffset + if (sub.isEmpty()) 0 else 2 + sub.size * 12 + 4
        val strings = (root + sub).map { (it.second + "\u0000").toByteArray() }
        val tiff = ByteBuffer.allocate(dataOffset + strings.sumOf { it.size })
            .order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        tiff.put(if (little) byteArrayOf(73, 73) else byteArrayOf(77, 77))
        tiff.putShort(42).putInt(8)
        var address = dataOffset
        var stringIndex = 0
        fun entries(values: List<Pair<Int, String>>) {
            for ((tag, _) in values) {
                val bytes = strings[stringIndex++]
                tiff.putShort(tag.toShort()).putShort(2).putInt(bytes.size)
                if (bytes.size <= 4) {
                    tiff.put(bytes)
                    repeat(4 - bytes.size) { tiff.put(0) }
                } else tiff.putInt(address)
                System.arraycopy(bytes, 0, tiff.array(), address, bytes.size)
                address += bytes.size
            }
        }
        tiff.putShort(rootCount.toShort())
        entries(root)
        if (sub.isNotEmpty()) tiff.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(subOffset)
        tiff.putInt(0)
        if (sub.isNotEmpty()) {
            tiff.putShort(sub.size.toShort())
            entries(sub)
            tiff.putInt(0)
        }
        val segment = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
        val size = segment.size + 2
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(),
            (size shr 8).toByte(), size.toByte()) + segment + byteArrayOf(0xff.toByte(), 0xd9.toByte())
    }

    @Test fun readsBothByteOrdersAndBothDirectories() {
        assertEquals("Redmi Note 12 Turbo", PhotoMarketName.read(jpeg(rootName = "Redmi Note 12 Turbo").inputStream(), "Xiaomi"))
        assertEquals("REDMI K80 Ultra", PhotoMarketName.read(jpeg(exifName = "REDMI K80 Ultra", little = false).inputStream(), "Xiaomi"))
    }

    @Test fun explicitNameWinsAndWatermarkJsonIsOnlyFallback() {
        val json = """{"waterLogo":"REDMI","waterName":"K80 Ultra"}"""
        assertEquals("Preferred model", PhotoMarketName.read(jpeg(rootName = "Preferred model", exifName = "Other model", waterInfo = json).inputStream(), "Xiaomi"))
        assertEquals("REDMI K80 Ultra", PhotoMarketName.read(jpeg(waterInfo = json).inputStream(), "Redmi"))
        assertEquals("", PhotoMarketName.read(jpeg(waterInfo = """{"waterLogo":"REDMI"}""").inputStream(), "Xiaomi"))
    }

    @Test fun malformedMetadataAndOtherBrandsDoNotSupplyNames() {
        val bytes = jpeg(rootName = "Redmi Note 12 Turbo")
        assertEquals("", PhotoMarketName.read(bytes.copyOf(22).inputStream(), "Xiaomi"))
        assertEquals("", PhotoMarketName.read(bytes.inputStream(), "Samsung"))
        val corrupt = bytes.copyOf().apply { for (i in 18..21) this[i] = 0xff.toByte() }
        assertEquals("", PhotoMarketName.read(corrupt.inputStream(), "Xiaomi"))
        assertEquals("", PhotoMarketName.read(jpeg(rootName = "Bad\nName").inputStream(), "Xiaomi"))
        assertEquals("", PhotoMarketName.read(jpeg(waterInfo = "{broken").inputStream(), "Xiaomi"))
    }

    @Test fun photoNameWinsOverMappingAndMissingNameKeepsFallback() {
        val exif = ExifInterface(jpeg().inputStream()).apply {
            setAttribute(ExifInterface.TAG_MODEL, "23049RAD8C")
            setAttribute(ExifInterface.TAG_MAKE, "Xiaomi")
        }
        val names = mapOf("23049RAD8C" to "Wrong catalogue entry")
        assertEquals("Redmi Note 12 Turbo", PhotoMetadataReader.read(exif, "photo.jpg", 1, 1, names, "Redmi Note 12 Turbo").model)
        assertEquals("Wrong catalogue entry", PhotoMetadataReader.read(exif, "photo.jpg", 1, 1, names).model)
    }

    // Personal originals remain local; these checks require the recorded files in artifacts/.
    private fun checkOriginal(name: String, expected: String) = runBlocking {
        val file = File(System.getProperty("c1.workspace"), "artifacts/MODEL-FIELD-001/$name")
        assumeTrue("Local original required: $file", file.isFile)
        val source = object : PhotoContentSource {
            override fun displayName(uri: Uri) = name
            override fun mimeType(uri: Uri) = "image/jpeg"
            override fun openInputStream(uri: Uri) = file.inputStream()
            override fun openTypedInputStream(uri: Uri, mimeType: String) = file.inputStream()
        }
        // Deliberately wrong lookup proves the actual preview path prefers the photo field.
        val photo = PhotoLoader.loadForPreview(source, Uri.parse("content://local/$name"),
            mapOf("23049RAD8C" to "Wrong catalogue entry", "REDMI K80 ULTRA" to "Wrong catalogue entry"))
        try { assertEquals(expected, photo.metadata.model) } finally { photo.preview.recycle() }
    }

    @Test fun note12OriginalLoadsWithItsEmbeddedMarketName() = checkOriginal("IMG_20261006_205511.jpg", "Redmi Note 12 Turbo")
    @Test fun k80OriginalLoadsWithItsEmbeddedMarketName() = checkOriginal("IMG_20261007_181328.jpg", "REDMI K80 Ultra")
}
