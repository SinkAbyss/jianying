package com.sinkabyss.framewatermark

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** FRAME-BG-001: the recipe record must carry 边框背景 and stay compatible with older records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FrameRecipeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun allFiveBackgroundsSurviveSaveReadAndApply() {
        val recipes = BorderBackgroundMode.entries.mapIndexed { index, mode ->
            FrameRecipe(
                name = "配方 ${mode.title}",
                layout = if (index % 2 == 0) FrameLayoutKind.BAR else FrameLayoutKind.BOX,
                circleId = if (index % 2 == 0) "circle-a" else null,
                circlePercent = 100 + index * 5,
                signatureId = if (index % 2 == 0) "sig-b" else null,
                signaturePercent = 90 + index * 5,
                background = mode,
            )
        }
        FrameRecipeStore.write(context, recipes)
        val read = FrameRecipeStore.read(context)

        assertEquals(recipes.size, read.size)
        BorderBackgroundMode.entries.forEachIndexed { index, mode ->
            assertEquals("第 $index 条的边框背景必须恢复", mode, read[index].background)
            assertEquals(recipes[index].layout, read[index].layout)
            assertEquals(recipes[index].circleId, read[index].circleId)
            assertEquals(recipes[index].circlePercent, read[index].circlePercent)
            assertEquals(recipes[index].signatureId, read[index].signatureId)
            assertEquals(recipes[index].signaturePercent, read[index].signaturePercent)
        }
    }

    @Test
    fun writtenRecordContainsTheBackgroundField() {
        FrameRecipeStore.write(
            context,
            listOf(
                FrameRecipe("测试", FrameLayoutKind.BOX, "circle-b", 120, "sig-a", 110, BorderBackgroundMode.BLUR),
            ),
        )
        val raw = context.getSharedPreferences(FrameRecipeStore.PREFERENCES, Context.MODE_PRIVATE)
            .getString(FrameRecipeStore.KEY, "")!!
        assertTrue("新写入的记录必须包含边框背景字段: $raw", raw.contains("\"${FrameRecipeStore.BACKGROUND_FIELD}\":\"BLUR\""))
    }

    @Test
    fun legacyRecordWithoutBackgroundReadsAsWhiteAndKeepsEveryOtherField() {
        // Exactly the 0.1.4 serialization shape, which had no background field.
        val legacy = """
            [
              {"name":"旧配方","layout":"BOX","circle":"circle-c","circlePercent":130,
               "signature":"sig-d","signaturePercent":80}
            ]
        """.trimIndent()
        val read = FrameRecipeStore.parse(legacy)
        assertEquals(1, read.size)
        assertEquals(BorderBackgroundMode.WHITE, read[0].background)
        assertEquals("旧配方", read[0].name)
        assertEquals(FrameLayoutKind.BOX, read[0].layout)
        assertEquals("circle-c", read[0].circleId)
        assertEquals(130, read[0].circlePercent)
        assertEquals("sig-d", read[0].signatureId)
        assertEquals(80, read[0].signaturePercent)
    }

    @Test
    fun unknownOrMissingBackgroundFallsBackToWhiteWithoutTouchingOtherValues() {
        val read = FrameRecipeStore.parse(
            """[{"name":"x","layout":"BAR","background":"SEPIA","circlePercent":77}]""",
        )
        assertEquals(BorderBackgroundMode.WHITE, read[0].background)
        assertEquals(77, read[0].circlePercent)
        assertFalse(read[0].background.isAdaptive)
    }

    @Test
    fun emptyAndBrokenPayloadsDoNotInventRecipes() {
        assertEquals(0, FrameRecipeStore.parse("[]").size)
        assertEquals(0, FrameRecipeStore.read(context).size)
        context.getSharedPreferences(FrameRecipeStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(FrameRecipeStore.KEY, "not json").apply()
        assertEquals(0, FrameRecipeStore.read(context).size)
    }
}
