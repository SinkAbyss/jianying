package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.util.AtomicFile
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class MaterialKind { SIGNATURE, IMAGE }
internal data class UserMaterial(
    val id: String, val name: String, val kind: MaterialKind,
    val file: String = "", val text: String = "", val font: String = "sans",
)

/** App-owned files only. Atomic index writes precede publishing additions or deleting files. */
internal class UserMaterialStore(context: Context) {
    private val app = context.applicationContext
    val fonts = UserFontStore(app)
    private val directory = File(app.filesDir, "user-materials")
    private val index get() = AtomicFile(File(directory, "index.json"))
    private val presetPreferences = app.getSharedPreferences("preset-materials", Context.MODE_PRIVATE)
    private fun deletedPresets(): Set<String> = presetPreferences.getStringSet("deleted", emptySet())!!.toSet()
    private fun presets() = listOf(
        UserMaterial("sig-a", "签名 a", MaterialKind.SIGNATURE),
        UserMaterial("sig-b", "签名 b", MaterialKind.SIGNATURE),
        UserMaterial("sig-c", "签名 c", MaterialKind.SIGNATURE),
        UserMaterial("sig-d", "几何签名 d", MaterialKind.SIGNATURE),
        UserMaterial("circle-a", "圆图 a", MaterialKind.IMAGE),
        UserMaterial("circle-b", "圆图 b", MaterialKind.IMAGE),
        UserMaterial("circle-c", "圆图 c", MaterialKind.IMAGE),
    )
    @Synchronized fun available(): List<UserMaterial> {
        val deleted = deletedPresets()
        return presets().filterNot { it.id in deleted } + read()
    }
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Synchronized fun read(): List<UserMaterial> {
        if (!index.baseFile.exists() && !File(directory, "index.json.bak").exists()) return emptyList()
        return try {
            val array = JSONArray(index.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            List(array.length()) { position ->
                val item = array.getJSONObject(position)
                val id = item.getString("id")
                require(isUserMaterialId(id))
                val file = item.optString("file")
                require(file.isEmpty() || file == "$id.png")
                UserMaterial(id, item.getString("name"), MaterialKind.valueOf(item.getString("kind")),
                    file, item.optString("text"), item.optString("font", "sans"))
            }.also { require(it.map { asset -> asset.id }.distinct().size == it.size) }
        } catch (error: Exception) {
            throw IllegalStateException("素材库读取失败，原有记录已保留，请稍后重试。", error)
        }
    }

    @Synchronized private fun write(items: List<UserMaterial>) {
        directory.mkdirs()
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("kind", it.kind.name).put("file", it.file).put("text", it.text).put("font", it.font)) }
        val target = index
        val stream = target.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (failure: Throwable) {
            target.failWrite(stream)
            throw failure
        }
    }

    @Synchronized fun addText(text: String, font: String): UserMaterial {
        val clean = TextRules.limit(text.trim())
        require(clean.isNotEmpty()) { "请先输入签名文字" }
        val current = read()
        fonts.typeface(font)
        val asset = UserMaterial("user-${UUID.randomUUID()}", clean, MaterialKind.SIGNATURE, text = clean,
            font = font)
        write(current + asset)
        return asset
    }

    @Synchronized fun addBitmap(name: String, kind: MaterialKind, bitmap: Bitmap): UserMaterial {
        val current = read()
        require(bitmap.width > 0 && bitmap.height > 0)
        val asset = UserMaterial("user-${UUID.randomUUID()}", TextRules.limit(name), kind)
            .let { it.copy(file = "${it.id}.png") }
        directory.mkdirs()
        val file = File(directory, asset.file)
        check(file.createNewFile()) { "素材文件创建失败，请重试" }
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            write(current + asset)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
        return asset
    }

    /** Keep stable material IDs and exact pixels before removing a font dependency. */
    @Synchronized fun deleteFont(id: String) {
        require(isUserFontId(id)) { "系统字体不能删除" }
        val current = read()
        val dependent = current.filter { it.text.isNotEmpty() && it.font == id }
        val created = mutableListOf<File>()
        var published = false
        try {
            val frozen = dependent.associate { asset ->
                val pixels = bitmap(asset.id) ?: error("无法保留已有签名，字体未删除")
                val file = File(directory, "${asset.id}.png")
                check(file.createNewFile()) { "签名文件已存在，字体未删除，请重试" }
                created += file
                file.outputStream().use { check(pixels.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                asset.id to asset.copy(file = file.name, text = "", font = "sans")
            }
            if (frozen.isNotEmpty()) write(current.map { frozen[it.id] ?: it })
            published = true
            // If font index publication fails, the preserved signatures remain usable for retry.
            fonts.delete(id)
        } catch (failure: Throwable) {
            if (!published) created.forEach { it.delete() }
            throw failure
        }
    }

    @Synchronized fun delete(id: String) {
        if (presets().any { it.id == id }) {
            check(presetPreferences.edit().putStringSet("deleted", deletedPresets() + id).commit()) {
                "预置素材删除失败，请重试"
            }
            cache.remove(id)
            cache.remove("thumb:$id")
            return
        }
        val current = read()
        val target = current.firstOrNull { it.id == id } ?: return
        write(current.filterNot { it.id == id })
        cache.remove(id) // Never recycle: a frozen save request may still own this bitmap reference.
        cache.remove("thumb:$id")
        if (target.file.isNotEmpty()) File(directory, target.file).delete()
    }

    @Synchronized fun bitmap(id: String?, thumbnail: Boolean = false): Bitmap? {
        if (id == null) return null
        if (id in deletedPresets()) return null
        val key = if (thumbnail) "thumb:$id" else id
        cache.get(key)?.let { return it }
        val preset = signatureResourceId(id) ?: circleResourceId(id)
        val bitmap = if (preset != null) BitmapFactory.decodeResource(app.resources, preset) else {
            val asset = read().firstOrNull { it.id == id } ?: return null
            if (asset.text.isNotEmpty()) MaterialPixels.text(asset.text, fonts.typeface(asset.font))
            else {
                val path = File(directory, asset.file).absolutePath
                val file = File(path)
                if (!file.isFile) return null
                val options = BitmapFactory.Options()
                if (thumbnail) {
                    options.inJustDecodeBounds = true
                    file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
                    options.inJustDecodeBounds = false
                    var sample = 1
                    while (max(options.outWidth, options.outHeight) / sample > 384) sample *= 2
                    options.inSampleSize = sample
                }
                file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            }
        }
        val result = if (thumbnail && bitmap != null && max(bitmap.width, bitmap.height) > 384) {
            val factor = 384f / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1),
                (bitmap.height * factor).roundToInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap
        result?.let { cache.put(key, it) }
        return result
    }
}

internal fun isUserMaterialId(id: String?): Boolean = id != null &&
    Regex("user-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)

internal object MaterialPixels {
    val fonts = listOf("sans" to "简洁", "serif" to "衬线", "mono" to "等宽", "italic" to "斜体")
    fun typeface(font: String): Typeface = when (font) {
        "serif" -> Typeface.SERIF
        "mono" -> Typeface.MONOSPACE
        "italic" -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        else -> Typeface.SANS_SERIF
    }

    /** Text definition stays in the index; a high-resolution mask is regenerated on load. */
    fun text(value: String, font: String): Bitmap = text(value, typeface(font))

    fun text(value: String, face: Typeface): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 240f; typeface = face
        }
        val measured = paint.measureText(value).coerceAtLeast(1f)
        paint.textSize *= minOf(1f, 4000f / measured)
        val bounds = Rect()
        paint.getTextBounds(value, 0, value.length, bounds)
        val result = Bitmap.createBitmap((bounds.width() + 32).coerceAtLeast(1),
            (bounds.height() + 32).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(result).drawText(value, 16f - bounds.left, 16f - bounds.top, paint)
        return result
    }

    fun decode(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取素材图片" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4096 ||
            bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
        val source = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("素材图片解码失败")
        val orientation = runCatching { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        } }.getOrNull() ?: 1
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            .also { if (it !== source) source.recycle() }
    }

    fun crop(source: Bitmap, region: android.graphics.RectF): Bitmap {
        val left = (region.left * source.width).roundToInt().coerceIn(0, source.width - 1)
        val top = (region.top * source.height).roundToInt().coerceIn(0, source.height - 1)
        val right = (region.right * source.width).roundToInt().coerceIn(left + 1, source.width)
        val bottom = (region.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun hasTransparency(source: Bitmap): Boolean {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return pixels.count { Color.alpha(it) < 250 } > pixels.size / 100
    }

    /** A tintable alpha mask: original RGB never becomes part of the signature.
     * Corner paper samples model gradual lighting rather than treating shadow as ink. */
    fun extract(source: Bitmap, preserveAlpha: Boolean, threshold: Int): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val patchWidth = (source.width / 20).coerceIn(1, 32)
        val patchHeight = (source.height / 20).coerceIn(1, 32)
        fun corner(left: Int, top: Int): FloatArray {
            val samples = (top until top + patchHeight).flatMap { y ->
                (left until left + patchWidth).map { x -> pixels[y * source.width + x] }
            }.filter { Color.alpha(it) > 240 }
            return floatArrayOf(0f, 0f, 0f).also { rgb ->
                val channels = listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)
                channels.forEachIndexed { index, channel ->
                    val values = samples.map(channel).sorted()
                    rgb[index] = values.getOrElse(values.size / 2) { 255 }.toFloat()
                }
            }
        }
        val corners = if (preserveAlpha) emptyList() else listOf(corner(0, 0),
            corner(source.width - patchWidth, 0), corner(0, source.height - patchHeight),
            corner(source.width - patchWidth, source.height - patchHeight))
        val distances = if (preserveAlpha) null else FloatArray(pixels.size) { i ->
            val pixel = pixels[i]
            val x = (i % source.width).toFloat() / (source.width - 1).coerceAtLeast(1)
            val y = (i / source.width).toFloat() / (source.height - 1).coerceAtLeast(1)
            fun background(channel: Int): Float =
                (corners[0][channel] * (1 - x) + corners[1][channel] * x) * (1 - y) +
                    (corners[2][channel] * (1 - x) + corners[3][channel] * x) * y
            val r = Color.red(pixel) - background(0); val g = Color.green(pixel) - background(1)
            val b = Color.blue(pixel) - background(2)
            max(abs(r * .299f + g * .587f + b * .114f), sqrt((r * r + g * g + b * b) / 3f))
        }
        if (distances != null) {
            val edges = (0 until source.width step max(1, source.width / 64)).flatMap { x ->
                listOf(distances[x], distances[(source.height - 1) * source.width + x])
            } + (0 until source.height step max(1, source.height / 64)).flatMap { y ->
                listOf(distances[y * source.width], distances[y * source.width + source.width - 1])
            }
            require(edges.sorted()[edges.size / 2] <= max(35f, threshold.toFloat())) {
                "背景过于复杂，请换用底色干净的签名图片，裁框四角留出少量背景。"
            }
        }
        pixels.indices.forEach { i ->
            val originalAlpha = Color.alpha(pixels[i])
            val alpha = if (preserveAlpha) originalAlpha else
                (((distances!![i] - threshold) / 32f).coerceIn(0f, 1f) * originalAlpha).roundToInt()
            pixels[i] = (alpha.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun trim(source: Bitmap): Bitmap {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        var left = source.width; var top = source.height; var right = -1; var bottom = -1
        pixels.forEachIndexed { i, color -> if (Color.alpha(color) > 8) {
            val x = i % source.width; val y = i / source.width
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        } }
        require(right >= left) { "没有提取到可见笔迹，请调整框选区域或提取强度" }
        return Bitmap.createBitmap(source, left, top, right - left + 1, bottom - top + 1)
    }
}
