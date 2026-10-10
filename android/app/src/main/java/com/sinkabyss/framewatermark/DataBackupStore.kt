package com.sinkabyss.framewatermark

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class BackupSummary(val materials: Int, val fonts: Int, val frames: Int, val art: Int, val bytes: Long) {
    fun description() = "素材 $materials · 字体 $fonts · 边框配方 $frames · 签名配方 $art"
}

/** A prepared import owns its private staging files until closed. No live data changes during inspection. */
internal class PreparedBackup internal constructor(internal val root: File, internal val data: BackupData) : AutoCloseable {
    val summary get() = data.summary()
    override fun close() { root.deleteRecursively() }
}

internal data class BackupData(
    val materials: List<UserMaterial>, val fonts: List<UserFont>,
    val frames: List<FrameRecipe>, val art: List<ArtRecipe>,
    val settings: JSONObject, val files: Map<String, File>,
) {
    fun summary() = BackupSummary(materials.size, fonts.size, frames.size, art.size, files.values.sumOf { it.length() })
}

/** Versioned portable package, bounded extraction and a recoverable whole-library transaction. */
internal class DataBackupStore(context: Context) {
    private val app = context.applicationContext
    private val transfer = File(app.filesDir, "data-transfer")
    private val journal get() = File(transfer, "transaction")
    val previousBackup get() = File(transfer, "恢复前备份.zip")

    fun summary(): BackupSummary = snapshot().summary()

    fun export(output: OutputStream) = writeZip(snapshot(), output)

    private fun settings(): JSONObject {
        val frame = app.getSharedPreferences("frame-materials", 0)
        val art = app.getSharedPreferences("art-signature", 0)
        return JSONObject().put("deleted", JSONArray(app.getSharedPreferences("preset-materials", 0)
            .getStringSet("deleted", emptySet())!!.sorted()))
            .put("circle", frame.getString("circle", "").orEmpty())
            .put("signature", frame.getString("signature", "").orEmpty())
            .put("artSignature", art.getString("signature", "").orEmpty())
    }

    private fun snapshot(): BackupData {
        val store = UserMaterialStore(app)
        val fonts = store.fonts.read()
        val materials = store.read()
        val files = linkedMapOf<String, File>()
        fonts.forEach { files["fonts/${it.file}"] = File(app.filesDir, "user-fonts/${it.file}") }
        materials.filter { it.file.isNotEmpty() }.forEach {
            files["materials/${it.file}"] = File(app.filesDir, "user-materials/${it.file}")
        }
        val prefs = app.getSharedPreferences("recipes", 0)
        // Unlike the UI's tolerant read, backup must not quietly export an empty damaged recipe list.
        val frames = FrameRecipeStore.parse(prefs.getString("frames", "[]")!!).map {
            it.copy(phraseFont = it.phraseFont.takeUnless { id -> isUserFontId(id) && fonts.none { font -> font.id == id } } ?: "sans")
        }
        val art = ArtRecipeStore.parse(prefs.getString("art", "[]")!!)
        return BackupData(materials, fonts, frames, art, settings(), files).also { validate(it) }
    }

    private fun materialsJson(items: List<UserMaterial>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)
            .put("file", it.file).put("text", it.text).put("font", it.font)) }
    }

    private fun fontsJson(items: List<UserFont>) = JSONArray().apply {
        items.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file).put("sha256", it.sha256)) }
    }

    private fun manifest(data: BackupData) = JSONObject().put("format", "jianying-backup").put("version", 1)
        .put("createdAt", System.currentTimeMillis()).put("materials", materialsJson(data.materials))
        .put("fonts", fontsJson(data.fonts)).put("frames", JSONArray(FrameRecipeStore.serialize(data.frames)))
        .put("art", JSONArray(ArtRecipeStore.serialize(data.art))).put("settings", data.settings)
        .put("files", JSONObject().apply { data.files.forEach { (name, file) ->
            put(name, JSONObject().put("size", file.length()).put("sha256", digest(file)))
        } })

    private fun writeZip(data: BackupData, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            val header = manifest(data).toString().toByteArray(Charsets.UTF_8)
            require(header.size <= MAX_MANIFEST) { "备份记录过多，无法导出" }
            zip.write(header); zip.closeEntry()
            data.files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun inspect(input: InputStream): PreparedBackup {
        val root = File(app.cacheDir, "backup-${UUID.randomUUID()}")
        check(root.mkdirs()) { "无法准备导入，请稍后重试" }
        try {
            val extracted = linkedMapOf<String, File>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && (name == "backup.json" || ASSET_PATH.matches(name))) { "不是有效的简影备份文件" }
                    require(name !in extracted && extracted.size < MAX_ENTRIES) { "备份文件存在重复或过多记录" }
                    val file = File(root, name)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            require(size <= (if (name == "backup.json") MAX_MANIFEST.toLong() else MAX_ASSET) && total <= MAX_TOTAL) {
                                "备份文件过大，无法导入"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    extracted[name] = file
                    zip.closeEntry()
                }
            }
            val json = JSONObject(extracted.remove("backup.json")?.readText() ?: error("备份文件缺少数据记录"))
            require(json.getString("format") == "jianying-backup") { "请选择简影导出的备份文件" }
            require(json.getInt("version") == 1) { "此备份需要更新简影后才能导入" }
            val declared = json.getJSONObject("files")
            require(declared.keys().asSequence().toSet() == extracted.keys) { "备份文件不完整" }
            extracted.forEach { (name, file) ->
                val record = declared.getJSONObject(name)
                require(record.getLong("size") == file.length() && record.getString("sha256") == digest(file)) { "备份文件损坏，请重新选择" }
            }
            val materialArray = json.getJSONArray("materials")
            val materials = List(materialArray.length()) { i -> materialArray.getJSONObject(i).let {
                UserMaterial(it.getString("id"), it.getString("name"), MaterialKind.valueOf(it.getString("kind")),
                    it.getString("file"), it.getString("text"), it.getString("font"))
            } }
            val fontArray = json.getJSONArray("fonts")
            val fonts = List(fontArray.length()) { i -> fontArray.getJSONObject(i).let {
                UserFont(it.getString("id"), it.getString("name"), it.getString("file"), it.getString("sha256"))
            } }
            val frames = FrameRecipeStore.parse(json.getJSONArray("frames").toString())
            val art = ArtRecipeStore.parse(json.getJSONArray("art").toString())
            require(frames.size == json.getJSONArray("frames").length() && art.size == json.getJSONArray("art").length()) {
                "备份中的配方记录无效"
            }
            val data = BackupData(materials, fonts, frames, art, json.getJSONObject("settings"), extracted)
            validate(data, verifyAssets = true)
            return PreparedBackup(root, data)
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private fun validate(data: BackupData, verifyAssets: Boolean = false) {
        require(data.materials.size + data.fonts.size + data.frames.size + data.art.size <= MAX_ENTRIES) { "数据记录过多" }
        require(data.materials.map { it.id }.distinct().size == data.materials.size &&
            data.fonts.map { it.id }.distinct().size == data.fonts.size) { "备份中的记录重复" }
        val fontIds = data.fonts.map { it.id }.toSet()
        fun font(id: String) = id in fontIds || MaterialPixels.fonts.any { it.first == id }
        fun material(id: String?, kind: MaterialKind): Boolean = id.isNullOrEmpty() ||
            data.materials.any { it.id == id && it.kind == kind } ||
            (if (kind == MaterialKind.IMAGE) circleResourceId(id) else signatureResourceId(id)) != null
        val paths = mutableSetOf<String>()
        data.fonts.forEach {
            require(isUserFontId(it.id) && it.file == "${it.id}.font") { "字体记录无效" }
            val path = "fonts/${it.file}"; paths += path
            val file = data.files[path] ?: error("备份缺少字体")
            require(file.length() <= UserFontStore.MAX_BYTES && it.sha256 == digest(file)) { "字体文件损坏" }
            if (verifyAssets) UserFontStore(app).validateFile(file)
        }
        data.materials.forEach {
            require(isUserMaterialId(it.id) && font(it.font)) { "素材或字体引用无效" }
            require(it.name.length <= 512 && it.text.length <= 512) { "素材记录过长" }
            if (it.file.isEmpty()) {
                require(it.kind == MaterialKind.SIGNATURE && it.text.isNotBlank()) { "签名记录无效" }
            } else {
                require(it.file == "${it.id}.png" && it.text.isEmpty()) { "素材文件记录无效" }
                val path = "materials/${it.file}"; paths += path
                val file = data.files[path] ?: error("备份缺少素材图片")
                if (verifyAssets) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/png" &&
                        bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) { "素材图片无效或过大" }
                }
            }
        }
        require(paths == data.files.keys && data.files.values.all { it.isFile && it.length() in 1..MAX_ASSET } &&
            data.files.values.sumOf { it.length() } <= MAX_TOTAL) { "备份素材缺失或过大" }
        data.frames.forEach { require(material(it.circleId, MaterialKind.IMAGE) && material(it.signatureId, MaterialKind.SIGNATURE) && font(it.phraseFont)) { "边框配方引用无效" } }
        data.art.forEach { require(material(it.signatureId, MaterialKind.SIGNATURE)) { "签名配方引用无效" } }
        require(material(data.settings.getString("circle"), MaterialKind.IMAGE) &&
            material(data.settings.getString("signature"), MaterialKind.SIGNATURE) &&
            material(data.settings.getString("artSignature"), MaterialKind.SIGNATURE)) { "素材选择记录无效" }
        val deleted = data.settings.getJSONArray("deleted")
        repeat(deleted.length()) { require(deleted.getString(it) in PRESETS) { "预置素材记录无效" } }
    }

    internal fun merge(current: BackupData, incoming: BackupData): BackupData {
        val files = current.files.toMutableMap()
        val fonts = current.fonts.toMutableList()
        val fontMap = mutableMapOf<String, String>()
        incoming.fonts.forEach { source ->
            val same = fonts.firstOrNull { it.sha256 == source.sha256 }
            val id = same?.id ?: source.id.takeUnless { candidate -> fonts.any { it.id == candidate } } ?: "font-${UUID.randomUUID()}"
            fontMap[source.id] = id
            if (same == null) {
                val next = source.copy(id = id, file = "$id.font")
                fonts += next; files["fonts/${next.file}"] = incoming.files.getValue("fonts/${source.file}")
            }
        }
        val materials = current.materials.toMutableList()
        val materialMap = mutableMapOf<String, String>()
        incoming.materials.forEach { source ->
            val mappedFont = fontMap[source.font] ?: source.font
            val same = materials.firstOrNull { target -> target.kind == source.kind &&
                if (source.file.isEmpty()) target.file.isEmpty() && target.text == source.text && target.font == mappedFont
                else target.file.isNotEmpty() && digest(files.getValue("materials/${target.file}")) == digest(incoming.files.getValue("materials/${source.file}")) }
            val id = same?.id ?: source.id.takeUnless { candidate -> materials.any { it.id == candidate } } ?: "user-${UUID.randomUUID()}"
            materialMap[source.id] = id
            if (same == null) {
                val next = source.copy(id = id, file = if (source.file.isEmpty()) "" else "$id.png", font = mappedFont)
                materials += next
                if (next.file.isNotEmpty()) files["materials/${next.file}"] = incoming.files.getValue("materials/${source.file}")
            }
        }
        fun mapped(id: String?) = materialMap[id] ?: id
        val frames = current.frames.toMutableList()
        incoming.frames.forEach { source ->
            val next = source.copy(circleId = mapped(source.circleId), signatureId = mapped(source.signatureId), phraseFont = fontMap[source.phraseFont] ?: source.phraseFont)
            if (frames.none { it.copy(name = "") == next.copy(name = "") }) frames += next.copy(name = uniqueName(next.name, frames.map { it.name }))
        }
        val art = current.art.toMutableList()
        incoming.art.forEach { source ->
            val next = source.copy(signatureId = mapped(source.signatureId)!!)
            if (art.none { it.copy(name = "") == next.copy(name = "") }) art += next.copy(name = uniqueName(next.name, art.map { it.name }))
        }
        val settings = JSONObject(current.settings.toString())
        listOf("circle", "signature", "artSignature").forEach { key ->
            if (settings.optString(key).isEmpty()) settings.put(key, mapped(incoming.settings.optString(key)))
        }
        // A preset stays available if either library uses it; importing must not hide existing choices.
        val incomingDeleted = incoming.settings.getJSONArray("deleted").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val currentDeleted = current.settings.getJSONArray("deleted")
        settings.put("deleted", JSONArray((0 until currentDeleted.length()).map { currentDeleted.getString(it) }.filter { it in incomingDeleted }))
        return BackupData(materials, fonts, frames, art, settings, files).also { validate(it) }
    }

    fun restore(prepared: PreparedBackup, replace: Boolean): BackupSummary {
        check(!journal.exists()) { "上次恢复尚未完成，请重新打开应用" }
        validate(prepared.data, verifyAssets = true)
        val before = snapshot()
        val target = if (replace) prepared.data else merge(before, prepared.data)
        val resultSummary = target.summary()
        check(transfer.isDirectory || transfer.mkdirs())
        if (replace) {
            val backup = AtomicFile(previousBackup)
            val stream = backup.startWrite()
            try {
                // ZipOutputStream closes its stream; AtomicFile needs its stream still open for fsync.
                writeZip(before, object : java.io.FilterOutputStream(stream) { override fun close() { flush() } })
                backup.finishWrite(stream)
            } catch (failure: Throwable) { backup.failWrite(stream); throw failure }
        }
        val stage = File(transfer, "stage-${UUID.randomUUID()}")
        try {
            writeDirectories(target, stage)
            check(journal.mkdirs())
            listOf("user-materials", "user-fonts").forEach { name ->
                val live = File(app.filesDir, name)
                if (live.exists()) check(live.copyRecursively(File(journal, "before/$name")))
            }
            File(journal, "preferences.json").writeText(preferencesJson().toString())
            atomicText(File(journal, "pending"), "1") // Published only after the rollback copy is complete.
            try {
                listOf("user-materials", "user-fonts").forEach { name ->
                    val live = File(app.filesDir, name)
                    retire(live)
                    check(File(stage, name).renameTo(live)) { "素材恢复失败" }
                }
                publishPreferences(target)
                atomicText(File(journal, "committed"), "1")
            } catch (failure: Throwable) {
                recover()
                throw failure
            }
            journal.deleteRecursively()
            return resultSummary
        } finally {
            stage.deleteRecursively()
            if (journal.exists() && !File(journal, "pending").exists()) journal.deleteRecursively()
        }
    }

    /** On interruption, restore the complete pre-import library before any store/cache is opened. */
    fun recover() {
        if (!journal.exists()) { cleanRetired(); return }
        if (File(journal, "pending").exists() && !File(journal, "committed").exists()) {
            listOf("user-materials", "user-fonts").forEach { name ->
                val live = File(app.filesDir, name)
                retire(live)
                val old = File(journal, "before/$name")
                if (old.exists()) check(old.copyRecursively(live)) { "恢复中断，请重试" }
            }
            restorePreferences(JSONObject(File(journal, "preferences.json").readText()))
        }
        check(journal.deleteRecursively()) { "恢复清理失败，请重试" }
        transfer.listFiles()?.filter { it.name.startsWith("stage-") }?.forEach { it.deleteRecursively() }
        cleanRetired()
    }

    // Native fonts can keep a file mapped. Swap the containing directory before trying cleanup;
    // inability to remove an inactive copy must not invalidate a successfully restored library.
    private fun retire(live: File) {
        if (live.exists()) check(live.renameTo(File(transfer, "retired-${UUID.randomUUID()}"))) { "无法替换现有素材，请重试" }
    }

    private fun cleanRetired() {
        transfer.listFiles()?.filter { it.name.startsWith("retired-") }?.forEach { it.deleteRecursively() }
    }

    private fun writeDirectories(data: BackupData, root: File) {
        val materialDir = File(root, "user-materials").apply { check(mkdirs()) }
        val fontDir = File(root, "user-fonts").apply { check(mkdirs()) }
        File(materialDir, "index.json").writeText(materialsJson(data.materials).toString())
        File(fontDir, "index.json").writeText(fontsJson(data.fonts).toString())
        data.files.forEach { (path, source) ->
            val parent = if (path.startsWith("fonts/")) fontDir else materialDir
            source.copyTo(File(parent, path.substringAfter('/')))
        }
    }

    private fun preferencesJson() = JSONObject().apply {
        PREF_KEYS.forEach { (name, keys) ->
            val prefs = app.getSharedPreferences(name, 0)
            put(name, JSONObject().apply { keys.forEach { key ->
                if (prefs.contains(key)) put(key, if (key == "deleted") JSONArray(prefs.getStringSet(key, emptySet())!!.sorted()) else prefs.getString(key, ""))
            } })
        }
    }

    private fun restorePreferences(json: JSONObject) {
        PREF_KEYS.forEach { (name, keys) ->
            val prefs = app.getSharedPreferences(name, 0).edit()
            val objectValue = json.getJSONObject(name)
            keys.forEach { key ->
                if (!objectValue.has(key)) prefs.remove(key)
                else if (key == "deleted") {
                    val array = objectValue.getJSONArray(key)
                    prefs.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                } else prefs.putString(key, objectValue.getString(key))
            }
            check(prefs.commit()) { "设置恢复失败，请重试" }
        }
    }

    private fun publishPreferences(data: BackupData) {
        val json = JSONObject().put("recipes", JSONObject().put("frames", FrameRecipeStore.serialize(data.frames))
            .put("art", ArtRecipeStore.serialize(data.art)))
            .put("preset-materials", JSONObject().put("deleted", data.settings.getJSONArray("deleted")))
            .put("frame-materials", JSONObject().put("circle", data.settings.getString("circle")).put("signature", data.settings.getString("signature")))
            .put("art-signature", JSONObject().put("signature", data.settings.getString("artSignature")))
        restorePreferences(json)
    }

    private fun atomicText(file: File, text: String) {
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try { stream.write(text.toByteArray()); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
    }

    companion object {
        private const val MAX_MANIFEST = 4 * 1024 * 1024
        private const val MAX_ASSET = 64L * 1024 * 1024
        private const val MAX_TOTAL = 512L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
        private val ASSET_PATH = Regex("(materials/user-[0-9a-f-]{36}\\.png|fonts/font-[0-9a-f-]{36}\\.font)")
        private val PRESETS = setOf("sig-a", "sig-b", "sig-c", "sig-d", "circle-a", "circle-b", "circle-c")
        private val PREF_KEYS = mapOf("recipes" to listOf("frames", "art"), "preset-materials" to listOf("deleted"),
            "frame-materials" to listOf("circle", "signature"), "art-signature" to listOf("signature"))
        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun uniqueName(name: String, existing: List<String>): String {
            if (name !in existing) return name
            var index = 2
            while ("$name ($index)" in existing) index++
            return "$name ($index)"
        }
    }
}
