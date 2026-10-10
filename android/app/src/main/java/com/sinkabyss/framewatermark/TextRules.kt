package com.sinkabyss.framewatermark

import android.icu.text.BreakIterator
import java.util.Locale

object TextRules {
    const val MAX_VISIBLE_CHARACTERS = 50

    fun limit(value: String, maximum: Int = MAX_VISIBLE_CHARACTERS): String {
        if (maximum <= 0 || value.isEmpty()) return ""
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        var end = iterator.first()
        var count = 0
        while (count < maximum) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return value
            end = next
            count++
        }
        return if (iterator.next() == BreakIterator.DONE) value else value.substring(0, end)
    }

    fun graphemes(value: String): List<String> {
        if (value.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(value)
        val pieces = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            pieces += value.substring(start, end)
            start = end
            end = iterator.next()
        }
        return pieces
    }

    /**
     * True when [raw] holds more than [maximum] visible characters, i.e. [limit] would drop part of
     * it. Callers use this to tell the user that input was capped instead of silently truncating.
     */
    fun exceeds(raw: String, maximum: Int = MAX_VISIBLE_CHARACTERS): Boolean =
        maximum > 0 && raw != limit(raw, maximum)
}
