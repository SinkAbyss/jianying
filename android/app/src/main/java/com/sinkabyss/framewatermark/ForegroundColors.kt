package com.sinkabyss.framewatermark

import org.json.JSONObject
import java.util.Locale

/** A null RGB means default. Custom colors are always opaque; opacity belongs to the watermark. */
@ConsistentCopyVisibility
internal data class ColorChoice private constructor(val rgb: Int?) {
    val isCustom: Boolean get() = rgb != null
    fun resolve(defaultInk: Int): Int = rgb ?: defaultInk
    val hex: String? get() = rgb?.let { String.format(Locale.ROOT, "#%06X", it and 0xFFFFFF) }

    fun toJson(): JSONObject = JSONObject().put("mode", if (isCustom) "CUSTOM" else "DEFAULT").apply {
        hex?.let { put("rgb", it) }
    }

    companion object {
        val Default = ColorChoice(null)
        fun custom(rgb: Int) = ColorChoice(rgb or 0xFF000000.toInt())
        fun fromHex(text: String): ColorChoice? {
            val value = text.trim().removePrefix("#")
            return if (value.matches(Regex("[0-9a-fA-F]{6}"))) custom(value.toInt(16)) else null
        }
        fun fromJson(item: JSONObject?): ColorChoice = when (item?.optString("mode")) {
            "CUSTOM" -> (item.opt("rgb") as? String)?.let(::fromHex) ?: Default
            else -> Default
        }
    }
}

internal data class ForegroundColors(
    val signature: ColorChoice = ColorChoice.Default,
    val info: ColorChoice = ColorChoice.Default,
) {
    fun choice(target: ColorTarget) = if (target == ColorTarget.SIGNATURE) signature else info
    fun withChoice(target: ColorTarget, choice: ColorChoice) =
        if (target == ColorTarget.SIGNATURE) copy(signature = choice) else copy(info = choice)
}

internal enum class ColorTarget(val title: String) {
    SIGNATURE("签名颜色"), INFO("信息文字颜色")
}
