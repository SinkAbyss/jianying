package com.sinkabyss.framewatermark

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 艺术签名配方 must round-trip the 九宫格 position, opacity, optional-field switches and both colour
 * slots, must stay independent from the border preset list, and must not invent records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtRecipeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun everyCombinationSurvivesSaveRead() {
        val recipes = buildList {
            for (horizontal in 0..2) {
                for (vertical in 0..2) {
                    add(
                        ArtRecipe(
                            name = "位置 $vertical$horizontal",
                            signatureId = if (horizontal == vertical) "sig-c" else "sig-a",
                            horizontal = horizontal,
                            vertical = vertical,
                            opacity = 40 + horizontal * 10 + vertical * 5,
                            showModel = horizontal == 0,
                            showPhrase = horizontal == 1,
                            showDate = vertical == 1,
                            showPlace = vertical == 2,
                            colors = ForegroundColors(
                                ColorChoice.custom(0x66CCFF),
                                if (vertical == 0) ColorChoice.Default else ColorChoice.custom(0xFFAA33.toInt()),
                            ),
                        ),
                    )
                }
            }
        }
        ArtRecipeStore.write(context, recipes)
        val read = ArtRecipeStore.read(context)

        assertEquals(recipes.size, read.size)
        recipes.forEachIndexed { index, expected ->
            val actual = read[index]
            assertEquals("第 $index 条的名称", expected.name, actual.name)
            assertEquals("第 $index 条的签名", expected.signatureId, actual.signatureId)
            assertEquals("第 $index 条的横向位置", expected.horizontal, actual.horizontal)
            assertEquals("第 $index 条的纵向位置", expected.vertical, actual.vertical)
            assertEquals("第 $index 条的透明度", expected.opacity, actual.opacity)
            assertEquals("第 $index 条的信息开关", listOf(
                expected.showModel, expected.showPhrase, expected.showDate, expected.showPlace,
            ), listOf(actual.showModel, actual.showPhrase, actual.showDate, actual.showPlace))
            assertEquals("第 $index 条的签名颜色", expected.colors.signature, actual.colors.signature)
            assertEquals("第 $index 条的信息颜色", expected.colors.info, actual.colors.info)
        }
    }

    @Test
    fun automaticColorsAreRestoredAsAutomaticNotAsAStaleCustomValue() {
        ArtRecipeStore.write(
            context,
            listOf(ArtRecipe("全自动", "sig-a", 1, 2, 100, false, false, false, false)),
        )
        val colors = ArtRecipeStore.read(context).first().colors
        assertEquals(ColorChoice.Default, colors.signature)
        assertEquals(ColorChoice.Default, colors.info)
        // "Automatic" must stay automatic, so the ink is re-solved per photo rather than pinned.
        val defaultInk = 0xFF123456.toInt()
        assertEquals(defaultInk, colors.signature.resolve(defaultInk))
        assertEquals(false, colors.info.isCustom)
    }

    @Test
    fun outOfRangeValuesAreClampedInsteadOfRejected() {
        val read = ArtRecipeStore.parse(
            """[{"name":"越界","signature":"sig-a","horizontal":9,"vertical":-3,"opacity":250}]""",
        )
        assertEquals(1, read.size)
        assertEquals(2, read[0].horizontal)
        assertEquals(0, read[0].vertical)
        assertEquals(100, read[0].opacity)
    }

    @Test
    fun recordWithoutASignatureIsDroppedAndUnknownSignatureIsDropped() {
        assertEquals(0, ArtRecipeStore.parse("""[{"name":"无签名","horizontal":1}]""").size)
        assertEquals(0, ArtRecipeStore.parse("""[{"name":"坏签名","signature":"does-not-exist"}]""").size)
        assertEquals(1, ArtRecipeStore.parse("""[{"name":"用户签名","signature":"user-1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"}]""").size)
    }

    @Test
    fun artRecipesAndFrameRecipesNeverShareOneList() {
        FrameRecipeStore.write(context, listOf(FrameRecipe("边框的", FrameLayoutKind.BAR, null, 100, "sig-a", 100)))
        ArtRecipeStore.write(context, listOf(ArtRecipe("艺术的", "sig-b", 0, 0, 80, true, false, false, false)))

        val frames = FrameRecipeStore.read(context)
        val arts = ArtRecipeStore.read(context)
        assertEquals(listOf("边框的"), frames.map { it.name })
        assertEquals(listOf("艺术的"), arts.map { it.name })
    }

    @Test
    fun emptyAndBrokenPayloadsDoNotInventRecipes() {
        assertEquals(0, ArtRecipeStore.parse("[]").size)
        assertEquals(0, ArtRecipeStore.read(context).size)
        context.getSharedPreferences(ArtRecipeStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(ArtRecipeStore.KEY, "not json").apply()
        assertEquals(0, ArtRecipeStore.read(context).size)
        assertTrue(ArtRecipeStore.read(context).isEmpty())
    }
}
