package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

internal data class UserFont(val id: String, val name: String, val file: String, val sha256: String)

/** Import once into private storage; signatures keep a stable reference, never the provider URI. */
internal class UserFontStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "user-fonts")
    private val index get() = AtomicFile(File(directory, "index.json"))
    private val cache = LruCache<String, Typeface>(12)

    init {
        // A native font mapping can delay file removal. Retry inactive copies on the next open;
        // a damaged index must never trigger cleanup of the existing library.
        runCatching {
            val active = read().map { it.file }.toSet()
            directory.listFiles()?.filter { it.name.endsWith(".font") &&
                isUserFontId(it.name.removeSuffix(".font")) && it.name !in active }?.forEach { it.delete() }
        }
    }

    @Synchronized fun read(): List<UserFont> {
        if (!index.baseFile.exists() && !File(directory, "index.json.bak").exists()) return emptyList()
        return try {
            val array = JSONArray(index.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            List(array.length()) { position ->
                val item = array.getJSONObject(position)
                val id = item.getString("id")
                val file = item.getString("file")
                val digest = item.getString("sha256")
                require(isUserFontId(id) && file == "$id.font" && Regex("[0-9a-f]{64}").matches(digest))
                UserFont(id, item.getString("name"), file, digest)
            }.also { require(it.map { font -> font.id }.distinct().size == it.size) }
        } catch (error: Exception) {
            throw IllegalStateException("字体库读取失败，原有字体已保留，请稍后重试。", error)
        }
    }

    @Synchronized private fun write(fonts: List<UserFont>) {
        val array = JSONArray()
        fonts.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)
            .put("file", it.file).put("sha256", it.sha256)) }
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

    fun import(uri: Uri): UserFont {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "导入字体"
        return resolver.openInputStream(uri)?.use { import(name, it) } ?: error("无法打开字体文件")
    }

    @Synchronized fun import(name: String, input: InputStream): UserFont {
        val current = read() // A damaged index must never be overwritten by a new import.
        check(directory.isDirectory || directory.mkdirs()) { "无法创建字体存储目录" }
        val id = "font-${UUID.randomUUID()}"
        val file = File(directory, "$id.font")
        check(file.createNewFile()) { "无法创建字体文件，请重试" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "字体文件超过32 MB，请选择较小的字体" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            current.firstOrNull { it.sha256 == sha256 }?.let {
                typeface(it.id) // Do not report success if an existing private copy is unavailable.
                file.delete()
                return it
            }
            val face = load(file)
            val displayName = TextRules.limit(name.substringAfterLast('/').substringAfterLast('\\').trim())
                .ifBlank { "导入字体" }
            val font = UserFont(id, displayName, file.name, sha256)
            write(current + font)
            cache.put(id, face)
            return font
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    /** Call via UserMaterialStore.deleteFont so existing signature masks are preserved first. */
    @Synchronized fun delete(id: String) {
        val current = read()
        val font = current.firstOrNull { it.id == id } ?: return
        write(current.filterNot { it.id == id })
        cache.remove(id)
        File(directory, font.file).delete()
    }

    @Synchronized fun typeface(id: String): Typeface {
        if (!isUserFontId(id)) {
            require(MaterialPixels.fonts.any { it.first == id }) { "无法识别所选字体" }
            return MaterialPixels.typeface(id)
        }
        cache.get(id)?.let { return it }
        val font = read().firstOrNull { it.id == id } ?: error("所选字体不存在，请重新导入")
        return load(File(directory, font.file)).also { cache.put(id, it) }
    }

    internal fun validateFile(file: File) { load(file) }

    private fun load(file: File): Typeface {
        require(file.isFile && file.length() in 12..MAX_BYTES) { "字体文件缺失或无效，请重新导入" }
        // Read the SFNT directory before passing the private copy to the native font parser.
        RandomAccessFile(file, "r").use { source ->
            val magic = source.readInt()
            require(magic == 0x00010000 || magic == 0x4f54544f || magic == 0x74727565) {
                "请选择有效的 TTF 或 OTF 字体文件（不支持 TTC、WOFF 或压缩包）"
            }
            val count = source.readUnsignedShort()
            require(count > 0 && 12L + count * 16L <= source.length()) { "字体文件损坏，无法导入" }
            source.seek(12)
            repeat(count) {
                source.readInt() // tag
                source.readInt() // checksum
                val offset = source.readInt().toLong() and 0xffffffffL
                val length = source.readInt().toLong() and 0xffffffffL
                require(offset + length <= source.length()) { "字体文件不完整，无法导入" }
            }
        }
        // No setFallback: an invalid font must not silently become the system sans font.
        return Typeface.Builder(file).build() ?: error("手机无法读取此字体，请选择其他 TTF 或 OTF 文件")
    }

    companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}

internal fun isUserFontId(id: String): Boolean =
    Regex("font-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)
