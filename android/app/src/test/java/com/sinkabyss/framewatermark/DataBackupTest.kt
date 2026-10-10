package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DataBackupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = compose.activity
    private val prefs = listOf("recipes", "preset-materials", "frame-materials", "art-signature")

    @Before fun reset() {
        listOf("user-materials", "user-fonts", "data-transfer").forEach { File(context.filesDir, it).deleteRecursively() }
        prefs.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    private fun export(): ByteArray = ByteArrayOutputStream().also { DataBackupStore(context).export(it) }.toByteArray()
    private fun bitmap(color: Int) = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun addLibrary(): Pair<UserMaterial, UserFont> {
        val store = UserMaterialStore(context)
        val fixture = File(System.getProperty("c1.workspace"), "artifacts/FONT-001/fixtures/Abel-Regular.ttf")
        check(fixture.isFile) { "This targeted check requires the existing local Abel font fixture" }
        // Seed persistent font records directly: Windows native font rendering locks the live
        // path, unlike Android. These checks exercise data transfer, not live font rendering.
        val fontId = "font-${UUID.randomUUID()}"
        val fontDir = File(context.filesDir, "user-fonts").apply { mkdirs() }
        fixture.copyTo(File(fontDir, "$fontId.font"))
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
        val font = UserFont(fontId, "Abel", "$fontId.font", sha)
        File(fontDir, "index.json").writeText("""[{"id":"$fontId","name":"Abel","file":"$fontId.font","sha256":"$sha"}]""")
        val signature = UserMaterial("user-${UUID.randomUUID()}", "Wind", MaterialKind.SIGNATURE, text = "Wind", font = font.id)
        val materialDir = File(context.filesDir, "user-materials").apply { mkdirs() }
        File(materialDir, "index.json").writeText("""[{"id":"${signature.id}","name":"Wind","kind":"SIGNATURE","file":"","text":"Wind","font":"${font.id}"}]""")
        val circle = store.addBitmap("圆图", MaterialKind.IMAGE, bitmap(Color.RED))
        FrameRecipeStore.writeChecked(context, listOf(FrameRecipe("边框", FrameLayoutKind.BOX, circle.id, 120, signature.id, 90,
            phraseEnabled = true, phraseFont = font.id)))
        ArtRecipeStore.writeChecked(context, listOf(ArtRecipe("签名", signature.id, 0, 2, 80, true, true, false, false)))
        context.getSharedPreferences("frame-materials", 0).edit().putString("circle", circle.id).putString("signature", signature.id).commit()
        context.getSharedPreferences("art-signature", 0).edit().putString("signature", signature.id).commit()
        store.delete("sig-b")
        return signature to font
    }

    @Test fun wholeBackupRestoresFontAssetsRecipesAndSettingsAndRepeatMergeDoesNotDuplicate() {
        val (signature, font) = addLibrary()
        val bytes = export()
        DataBackupStore(context).inspect(bytes.inputStream()).use { prepared ->
            assertEquals(2, prepared.summary.materials); assertEquals(1, prepared.summary.fonts)
            reset()
            val result = DataBackupStore(context).restore(prepared, replace = true)
            assertEquals(prepared.summary, result)
            val restored = UserMaterialStore(context)
            assertEquals(signature, restored.read().single { it.id == signature.id })
            assertEquals(font.sha256, restored.fonts.read().single().sha256)
            assertEquals(font.id, FrameRecipeStore.read(context).single().phraseFont)
            assertEquals(signature.id, ArtRecipeStore.read(context).single().signatureId)
            assertFalse(restored.available().any { it.id == "sig-b" })
            assertEquals(signature.id, context.getSharedPreferences("art-signature", 0).getString("signature", ""))
            DataBackupStore(context).restore(prepared, replace = false)
            assertEquals(result, DataBackupStore(context).summary())
        }
    }

    @Test fun mergeRenamesConflictingIdsAndRemapsEveryRecipeAndSelection() {
        val store = UserMaterialStore(context)
        val original = store.addBitmap("原图", MaterialKind.SIGNATURE, bitmap(Color.RED))
        context.getSharedPreferences("frame-materials", 0).edit().putString("signature", original.id).commit()
        context.getSharedPreferences("art-signature", 0).edit().putString("signature", original.id).commit()
        FrameRecipeStore.writeChecked(context, listOf(FrameRecipe("同名", FrameLayoutKind.BAR, null, 100, original.id, 100)))
        val incomingBytes = export()
        reset()
        // Simulate another library that owns the same id with different pixels.
        val dir = File(context.filesDir, "user-materials").apply { mkdirs() }
        File(dir, original.file).outputStream().use { bitmap(Color.BLUE).compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "index.json").writeText("""[{"id":"${original.id}","name":"现有","kind":"SIGNATURE","file":"${original.file}","text":"","font":"sans"}]""")
        FrameRecipeStore.writeChecked(context, listOf(FrameRecipe("同名", FrameLayoutKind.BOX, null, 100, original.id, 100)))
        DataBackupStore(context).inspect(incomingBytes.inputStream()).use {
            DataBackupStore(context).restore(it, replace = false)
            val materials = UserMaterialStore(context).read()
            assertEquals(2, materials.size)
            val imported = materials.single { asset -> asset.id != original.id }
            assertEquals(Color.RED, UserMaterialStore(context).bitmap(imported.id)!!.getPixel(8, 6))
            assertEquals(Color.BLUE, UserMaterialStore(context).bitmap(original.id)!!.getPixel(8, 6))
            assertEquals(imported.id, FrameRecipeStore.read(context).single { recipe -> recipe.name == "同名 (2)" }.signatureId)
            assertEquals(imported.id, context.getSharedPreferences("frame-materials", 0).getString("signature", ""))
            assertEquals(imported.id, context.getSharedPreferences("art-signature", 0).getString("signature", ""))
            DataBackupStore(context).restore(it, replace = false)
            assertEquals(2, UserMaterialStore(context).read().size)
            assertEquals(2, FrameRecipeStore.read(context).size)
        }
    }

    @Test fun replacementKeepsAUsableBackupOfThePreviousLibrary() {
        val first = UserMaterialStore(context).addText("First", "serif")
        val firstBytes = export()
        reset()
        val second = UserMaterialStore(context).addText("Second", "mono")
        DataBackupStore(context).inspect(firstBytes.inputStream()).use { DataBackupStore(context).restore(it, true) }
        assertEquals(first.id, UserMaterialStore(context).read().single().id)
        DataBackupStore(context).previousBackup.inputStream().use { input ->
            DataBackupStore(context).inspect(input).use {
                assertEquals(second.id, it.data.materials.single().id)
                DataBackupStore(context).restore(it, false)
            }
        }
        assertEquals(setOf(first.id, second.id), UserMaterialStore(context).read().map { it.id }.toSet())
    }

    @Test fun damagedOrTraversalPackagesAreRejectedWithoutChangingLiveData() {
        addLibrary()
        val bytes = export()
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            entries[entry.name] = zip.readBytes()
        } }
        fun zipBytes(values: Map<String, ByteArray>) = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> values.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry() } }
        }.toByteArray()
        val before = DataBackupStore(context).summary()
        val png = entries.keys.first { it.endsWith(".png") }
        val broken = zipBytes(entries + (png to byteArrayOf(1, 2, 3)))
        assertTrue(runCatching { DataBackupStore(context).inspect(broken.inputStream()).close() }.isFailure)
        val escaped = "outside-${UUID.randomUUID()}"
        assertTrue(runCatching { DataBackupStore(context).inspect(zipBytes(mapOf("../$escaped" to byteArrayOf(1))).inputStream()).close() }.isFailure)
        assertFalse(File(context.cacheDir, escaped).exists())
        val header = JSONObject(entries.getValue("backup.json").toString(Charsets.UTF_8)).put("version", 2)
        assertTrue(runCatching { DataBackupStore(context).inspect(zipBytes(entries + ("backup.json" to header.toString().toByteArray())).inputStream()).close() }.isFailure)
        assertEquals(before, DataBackupStore(context).summary())
    }

    @Test fun interruptedRestoreRecoversWholeLibraryAndPreferencesBeforeStoresOpen() {
        val (signature, _) = addLibrary()
        val journal = File(context.filesDir, "data-transfer/transaction").apply { mkdirs() }
        listOf("user-materials", "user-fonts").forEach { File(context.filesDir, it).copyRecursively(File(journal, "before/$it")) }
        val previous = JSONObject()
        prefs.forEach { name -> previous.put(name, JSONObject().apply {
            context.getSharedPreferences(name, 0).all.forEach { (key, value) ->
                put(key, if (value is Set<*>) org.json.JSONArray(value.toList()) else value)
            }
        }) }
        File(journal, "preferences.json").writeText(previous.toString())
        File(journal, "pending").writeText("1")
        File(context.filesDir, "user-materials").deleteRecursively()
        prefs.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DataBackupStore(context).recover()
        assertFalse(journal.exists())
        assertEquals(signature, UserMaterialStore(context).read().single { it.id == signature.id })
        assertEquals(signature.id, ArtRecipeStore.read(context).single().signatureId)
        assertEquals(signature.id, context.getSharedPreferences("art-signature", 0).getString("signature", ""))
    }

    @Test fun appHasDataManagementEntryAndReturnsToLibrary() {
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme { FrameWatermarkApp() } } }
        compose.onNodeWithText("数据管理").performClick()
        compose.onNodeWithText("备份数据").assertExists()
        compose.onNodeWithText("导入备份").assertExists()
        compose.onNodeWithText("‹ 返回").performClick()
        compose.onNodeWithText("水印库").assertExists()
    }
}
