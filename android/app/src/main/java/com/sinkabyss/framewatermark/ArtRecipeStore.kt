package com.sinkabyss.framewatermark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved 艺术签名 preset. Kept separate from [FrameRecipeStore] so the two watermarks never share a
 * list; the storage shape mirrors the border preset for consistency (see [FrameRecipeStore]).
 *
 * The preset records the 九宫格 position, overall opacity, which optional fields were switched on,
 * and both foreground colour slots (签名 / 信息文字). It never records photo data or manual text.
 */
internal data class ArtRecipe(
    val name: String,
    val signatureId: String,
    val horizontal: Int,
    val vertical: Int,
    val opacity: Int,
    val showModel: Boolean,
    val showPhrase: Boolean,
    val showDate: Boolean,
    val showPlace: Boolean,
    val colors: ForegroundColors = ForegroundColors(),
    val signaturePercent: Int = 100,
)

internal object ArtRecipeStore {
    const val PREFERENCES = "recipes"
    const val KEY = "art"

    fun read(context: Context): List<ArtRecipe> = runCatching {
        val json = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        parse(json)
    }.getOrDefault(emptyList())

    fun write(context: Context, recipes: List<ArtRecipe>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, serialize(recipes))
            .apply()
    }

    /** Saving must be confirmed before the UI state changes, same contract as the border preset. */
    fun writeChecked(context: Context, recipes: List<ArtRecipe>,
        failureMessage: String = "配方保存失败，请重试。") {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(KEY, "[]")
        // commit() is deliberate: the caller must learn whether the write landed before it mutates
        // the in-memory list, so apply()'s asynchronous result would be useless here.
        if (!preferences.edit().putString(KEY, serialize(recipes)).commit()) {
            preferences.edit().putString(KEY, previous).commit()
            error(failureMessage)
        }
    }

    fun parse(json: String): List<ArtRecipe> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val signatureId = item.optString("signature").takeIf {
                    signatureResourceId(it) != null || isUserMaterialId(it)
                } ?: continue
                add(
                    ArtRecipe(
                        name = item.optString("name", "艺术签名配方"),
                        signatureId = signatureId,
                        horizontal = item.optInt("horizontal", 1).coerceIn(0, 2),
                        vertical = item.optInt("vertical", 1).coerceIn(0, 2),
                        opacity = item.optInt("opacity", 100).coerceIn(0, 100),
                        signaturePercent = item.optInt("signaturePercent", 100).coerceIn(25, 200),
                        showModel = item.optBoolean("showModel", false),
                        showPhrase = item.optBoolean("showPhrase", false),
                        showDate = item.optBoolean("showDate", false),
                        showPlace = item.optBoolean("showPlace", false),
                        colors = ForegroundColors(
                            ColorChoice.fromJson(item.optJSONObject("signatureColor")),
                            ColorChoice.fromJson(item.optJSONObject("infoColor")),
                        ),
                    ),
                )
            }
        }
    }

    fun serialize(recipes: List<ArtRecipe>): String {
        val json = JSONArray()
        recipes.forEach { recipe ->
            json.put(
                JSONObject()
                    .put("name", recipe.name)
                    .put("signature", recipe.signatureId)
                    .put("horizontal", recipe.horizontal)
                    .put("vertical", recipe.vertical)
                    .put("opacity", recipe.opacity)
                    .put("signaturePercent", recipe.signaturePercent)
                    .put("showModel", recipe.showModel)
                    .put("showPhrase", recipe.showPhrase)
                    .put("showDate", recipe.showDate)
                    .put("showPlace", recipe.showPlace)
                    .put("signatureColor", recipe.colors.signature.toJson())
                    .put("infoColor", recipe.colors.info.toJson()),
            )
        }
        return json.toString()
    }
}
