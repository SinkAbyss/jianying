package com.sinkabyss.framewatermark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved border preset. From 0.1.5 on the record also carries the 边框背景 choice; records written by
 * older builds have no such field and are read back with the default 白色 while every other field is
 * preserved.
 */
internal data class FrameRecipe(
    val name: String,
    val layout: FrameLayoutKind,
    val circleId: String?,
    val circlePercent: Int,
    val signatureId: String?,
    val signaturePercent: Int,
    val background: BorderBackgroundMode = BorderBackgroundMode.DEFAULT,
    val colors: ForegroundColors = ForegroundColors(),
    val phraseEnabled: Boolean = false,
    val phraseFont: String = "sans",
)

internal object FrameRecipeStore {
    const val PREFERENCES = "recipes"
    const val KEY = "frames"
    const val BACKGROUND_FIELD = "background"

    fun read(context: Context): List<FrameRecipe> = runCatching {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        parse(json)
    }.getOrDefault(emptyList())

    fun write(context: Context, recipes: List<FrameRecipe>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, serialize(recipes))
            .apply()
    }

    /** For reference cleanup: do not delete a material unless the recipe update persisted. */
    fun writeChecked(context: Context, recipes: List<FrameRecipe>,
        failureMessage: String = "配方更新失败，素材暂未删除，请重试。") {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(KEY, "[]")
        if (!preferences.edit().putString(KEY, serialize(recipes)).commit()) {
            preferences.edit().putString(KEY, previous).commit()
            error(failureMessage)
        }
    }

    fun parse(json: String): List<FrameRecipe> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    FrameRecipe(
                        name = item.optString("name", "边框配方"),
                        layout = runCatching { FrameLayoutKind.valueOf(item.optString("layout")) }
                            .getOrDefault(FrameLayoutKind.BAR),
                        circleId = item.optString("circle").takeIf(String::isNotBlank),
                        circlePercent = item.optInt("circlePercent", 100).coerceIn(50, 140),
                        signatureId = item.optString("signature").takeIf { signatureResourceId(it) != null || isUserMaterialId(it) },
                        signaturePercent = item.optInt("signaturePercent", 100).coerceIn(50, 140),
                        background = BorderBackgroundMode.fromStorage(item.optString(BACKGROUND_FIELD)),
                        colors = ForegroundColors(
                            ColorChoice.fromJson(item.optJSONObject("signatureColor")),
                            ColorChoice.fromJson(item.optJSONObject("infoColor")),
                        ),
                        phraseEnabled = item.optBoolean("phraseEnabled", false),
                        phraseFont = item.optString("phraseFont", "sans").takeIf {
                            MaterialPixels.fonts.any { font -> font.first == it } || isUserFontId(it)
                        } ?: "sans",
                    ),
                )
            }
        }
    }

    fun serialize(recipes: List<FrameRecipe>): String {
        val json = JSONArray()
        recipes.forEach { recipe ->
            json.put(
                JSONObject()
                    .put("name", recipe.name)
                    .put("layout", recipe.layout.name)
                    .put("circle", recipe.circleId.orEmpty())
                    .put("circlePercent", recipe.circlePercent)
                    .put("signature", recipe.signatureId.orEmpty())
                    .put("signaturePercent", recipe.signaturePercent)
                    .put(BACKGROUND_FIELD, recipe.background.name)
                    .put("signatureColor", recipe.colors.signature.toJson())
                    .put("infoColor", recipe.colors.info.toJson())
                    .put("phraseEnabled", recipe.phraseEnabled)
                    .put("phraseFont", recipe.phraseFont),
            )
        }
        return json.toString()
    }
}

internal fun circleResourceId(id: String?): Int? = when (id) {
    "circle-a" -> R.drawable.circle_a
    "circle-b" -> R.drawable.circle_b
    "circle-c" -> R.drawable.circle_c
    else -> null
}

internal fun signatureResourceId(id: String?): Int? = when (id) {
    "sig-a" -> R.drawable.sig_a
    "sig-b" -> R.drawable.sig_b
    "sig-c" -> R.drawable.sig_c
    "sig-d" -> R.drawable.sig_d
    else -> null
}
