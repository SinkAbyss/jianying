package com.sinkabyss.framewatermark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 50-character rule counts visible characters and must report dropped input, because callers
 * show "超出部分未录入" instead of silently truncating (需求第 5 节).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextRulesLimitTest {
    @Test
    fun exactlyFiftyVisibleCharactersIsNotReportedAsCapped() {
        val fifty = "字".repeat(50)
        assertFalse("正好 50 字不应报告超限", TextRules.exceeds(fifty))
        assertEquals(fifty, TextRules.limit(fifty))
    }

    @Test
    fun oneCharacterOverIsReportedAndClippedToFifty() {
        val fiftyOne = "字".repeat(51)
        assertTrue("51 字必须报告超限", TextRules.exceeds(fiftyOne))
        assertEquals("裁到 50 个可见字符", 50, TextRules.graphemes(TextRules.limit(fiftyOne)).size)
    }

    @Test
    fun pasteOfLongTextIsFullyReportedEvenThoughItIsClipped() {
        val pasted = "多".repeat(200)
        assertEquals(50, TextRules.graphemes(TextRules.limit(pasted)).size)
        assertTrue(TextRules.exceeds(pasted))
    }

    @Test
    fun combiningMarksCountAsOneVisibleCharacter() {
        val base = "e\u0301".repeat(50) // 50 graphemes, 100 code points
        assertEquals("组合字符必须算作一个可见字符", 50, TextRules.graphemes(base).size)
        assertFalse("50 个组合字符不应报告超限", TextRules.exceeds(base))
        assertTrue("51 个组合字符必须报告超限", TextRules.exceeds("e\u0301".repeat(51)))
    }

    @Test
    fun emojiSurrogatePairsAreCountedAsOneVisibleCharacter() {
        val emoji = "🙂".repeat(50)
        assertEquals(50, TextRules.graphemes(emoji).size)
        assertFalse(TextRules.exceeds(emoji))
        assertTrue(TextRules.exceeds("🙂".repeat(51)))
    }

    @Test
    fun emptyAndNonPositiveMaximumStaySafe() {
        assertFalse(TextRules.exceeds(""))
        assertFalse(TextRules.exceeds("任意", maximum = 0))
        assertEquals("", TextRules.limit("任意", maximum = 0))
    }
}
