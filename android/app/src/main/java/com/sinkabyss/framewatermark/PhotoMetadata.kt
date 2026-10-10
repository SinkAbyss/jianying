package com.sinkabyss.framewatermark

import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import kotlin.math.roundToInt

data class PhotoMetadata(
    val displayName: String,
    val width: Int,
    val height: Int,
    val model: String = "",
    val focalLength: String = "",
    val aperture: String = "",
    val shutter: String = "",
    val iso: String = "",
    val date: String = "",
    val hasGpsCoordinates: Boolean = false,
) {
    val pixelCount: Long get() = width.toLong() * height

    fun parameterText(enabled: Set<ParameterField>): String = listOf(
        ParameterField.FOCAL_LENGTH to focalLength,
        ParameterField.APERTURE to aperture,
        ParameterField.SHUTTER to shutter,
        ParameterField.ISO to iso,
    ).filter { it.first in enabled && it.second.isNotBlank() }.joinToString("   ") { it.second }
}

enum class ParameterField(val label: String) {
    FOCAL_LENGTH("焦距"),
    APERTURE("光圈"),
    SHUTTER("快门"),
    ISO("ISO"),
}

object PhotoMetadataReader {
    @Suppress("DEPRECATION")
    fun read(
        exif: ExifInterface?,
        name: String,
        width: Int,
        height: Int,
        marketNames: Map<String, String> = emptyMap(),
        photoMarketName: String = "",
    ): PhotoMetadata {
        if (exif == null) return PhotoMetadata(name, width, height)
        val focal = readNumber(exif, ExifInterface.TAG_FOCAL_LENGTH)
            ?.let { "${trimNumber(it)} mm" }.orEmpty()
        val aperture = readNumber(exif, ExifInterface.TAG_F_NUMBER)
            ?.let { "f/${trimNumber(it)}" }.orEmpty()
        val exposure = readNumber(exif, ExifInterface.TAG_EXPOSURE_TIME)
        val shutter = exposure?.takeIf { it > 0.0 }?.let {
            if (it < 1.0) "1/${(1.0 / it).roundToInt().coerceAtLeast(1)} s" else "${trimNumber(it)} s"
        }.orEmpty()
        val isoValue = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
            .takeIf { it > 0 }
            ?: exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1).takeIf { it > 0 }
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { original ->
            val match = Regex("^(\\d{4}):(\\d{2}):(\\d{2})").find(original)
            if (match == null) original else original.replaceRange(
                match.range,
                "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}",
            )
        }.orEmpty()
        val coordinates = exif.getLatLong()
        val hasGps = coordinates?.size == 2 && coordinates.all(Double::isFinite)

        return PhotoMetadata(
            displayName = name,
            width = width,
            height = height,
            model = photoMarketName.trim().takeIf(String::isNotBlank)
                ?: DeviceMarketNames.resolve(exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty(),
                    exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty(), marketNames),
            focalLength = focal,
            aperture = aperture,
            shutter = shutter,
            iso = isoValue?.let { "ISO $it" }.orEmpty(),
            date = date,
            hasGpsCoordinates = hasGps,
        )
    }

    private fun readNumber(exif: ExifInterface, tag: String): Double? {
        val value = exif.getAttributeDouble(tag, Double.NaN)
        return value.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun trimNumber(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
        .trimEnd('0')
        .trimEnd('.')
}
