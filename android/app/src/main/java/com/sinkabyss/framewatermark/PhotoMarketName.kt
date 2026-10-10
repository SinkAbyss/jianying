package com.sinkabyss.framewatermark

import org.json.JSONObject
import java.io.InputStream

/** Reads Xiaomi's photo-owned market name, which AndroidX does not expose as a named tag. */
internal object PhotoMarketName {
    fun read(input: InputStream, make: String): String {
        if (!make.trim().equals("Xiaomi", true) && !make.trim().equals("Redmi", true)) return ""
        if (input.read() != 0xff || input.read() != 0xd8) return ""
        var scanned = 2
        while (scanned < 1_048_576) {
            if (input.read() != 0xff) return ""
            var marker = input.read()
            while (marker == 0xff) marker = input.read()
            if (marker < 0 || marker == 0xda || marker == 0xd9) return ""
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) return ""
            val size = (high shl 8) + low - 2
            if (size < 0) return ""
            val segment = ByteArray(size)
            var position = 0
            while (position < size) {
                val count = input.read(segment, position, size - position)
                if (count <= 0) return ""
                position += count
            }
            scanned += size + 4
            if (marker == 0xe1 && segment.size >= 14 &&
                segment.copyOfRange(0, 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                parseExif(segment).takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return ""
    }

    internal fun parseExif(segment: ByteArray): String = runCatching {
        val base = 6
        val little = segment[base] == 73.toByte() && segment[base + 1] == 73.toByte()
        require(little || (segment[base] == 77.toByte() && segment[base + 1] == 77.toByte()))
        fun number(offset: Long, length: Int): Long {
            require(offset >= 0 && offset + length <= segment.size - base)
            var result = 0L
            repeat(length) { i ->
                val index = base + offset.toInt() + if (little) length - 1 - i else i
                result = (result shl 8) or (segment[index].toLong() and 255)
            }
            return result
        }
        require(number(2, 2) == 42L)
        data class Directory(val names: Map<Int, String>, val exifOffset: Long)
        fun directory(offset: Long): Directory {
            if (offset == 0L) return Directory(emptyMap(), 0)
            val count = number(offset, 2).toInt()
            require(offset + 2 + count * 12L + 4 <= segment.size - base)
            val values = mutableMapOf<Int, String>()
            var exifOffset = 0L
            repeat(count) { i ->
                val entry = offset + 2 + i * 12L
                val tag = number(entry, 2).toInt()
                val type = number(entry + 2, 2)
                val length = number(entry + 4, 4)
                if (tag == 0x8769 && type == 4L && length == 1L) exifOffset = number(entry + 8, 4)
                if (tag in listOf(0x9a00, 0x889e) && type == 2L && length in 1..4096) {
                    val address = if (length <= 4) entry + 8 else number(entry + 8, 4)
                    if (address >= 0 && address + length <= segment.size - base) {
                        values[tag] = String(segment, base + address.toInt(), length.toInt(), Charsets.UTF_8)
                            .trimEnd('\u0000').trim()
                    }
                }
            }
            return Directory(values, exifOffset)
        }
        val root = directory(number(4, 4))
        clean(root.names[0x9a00]).takeIf(String::isNotBlank)?.let { return it }
        val exif = directory(root.exifOffset)
        clean(exif.names[0x9a00]).takeIf(String::isNotBlank)?.let { return it }
        val json = exif.names[0x889e] ?: root.names[0x889e] ?: return ""
        val data = JSONObject(json)
        val name = clean(data.optString("waterName"))
        if (name.isBlank()) return ""
        val brand = clean(data.optString("waterLogo"))
        clean(if (brand.isBlank() || name.startsWith(brand, true)) name else "$brand $name")
    }.getOrDefault("")

    private fun clean(value: String?): String = value?.trim()?.takeIf {
        it.length in 1..128 && it.none { character -> character.isISOControl() || character == '\ufffd' }
    }.orEmpty()
}
