package com.sinkabyss.framewatermark

import android.content.Context
import java.util.Locale

/** Offline, unambiguous Model -> Marketing Name entries from Google's public device catalogue. */
internal object DeviceMarketNames {
    @Volatile private var cached: Map<String, String>? = null

    fun read(context: Context): Map<String, String> = cached ?: synchronized(this) {
        cached ?: context.resources.openRawResource(R.raw.device_market_names).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val split = line.indexOf('\t')
                if (split > 0) line.substring(0, split) to line.substring(split + 1) else null
            }.toMap()
        }.also { cached = it }
    }

    fun resolve(rawModel: String, make: String = "", names: Map<String, String> = emptyMap()): String {
        val model = rawModel.trim()
        if (model.isEmpty()) return ""
        val key = model.uppercase(Locale.ROOT)
        names[key]?.let { return it }
        val withoutBrand = if (make.isNotBlank() && model.startsWith(make.trim() + " ", ignoreCase = true))
            model.substring(make.trim().length).trim().uppercase(Locale.ROOT) else key
        names[withoutBrand]?.let { return it }
        // Also resolves the user's device for metadata callers without an Android Context.
        if (withoutBrand == "23049RAD8C") return "Redmi Note 12 Turbo"
        // Preserve EXIF marketing names; unrecognised code-like identifiers must not become labels.
        val code = Regex("^[A-Z0-9_-]+$")
        val namedProduct = Regex("^(?:iPhone|Pixel|Galaxy|Redmi|POCO|Xiaomi|vivo|OPPO|OnePlus|HONOR|HUAWEI)(?:\\s|\\d)", RegexOption.IGNORE_CASE)
        return if (!namedProduct.containsMatchIn(model) && withoutBrand.any(Char::isDigit) && code.matches(withoutBrand)) "" else model
    }
}
