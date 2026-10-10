package com.sinkabyss.framewatermark

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.security.MessageDigest

/** Actual loader/EXIF/export sampling path on all original own assets, via a file-backed provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class R1OriginalPhotoTest {
    @Test fun allOriginalAssetsDecodeOrientAndRenderUsingTheExportLoader() = runBlocking {
        val root = File(System.getProperty("c1.workspace") ?: error("Missing workspace"))
        val own = JSONObject(File(root,"artifacts/FRAME-BG-001/R1/calibration/assets.json").readText()).getJSONArray("own")
        val files = (0 until own.length()).map { File(own.getJSONObject(it).getString("source")) }
        val context = RuntimeEnvironment.getApplication()
        val provider = OriginalProvider(files)
        provider.attachInfo(context,ProviderInfo().apply { authority = "r1.originals" })
        ShadowContentResolver.registerProviderInternal("r1.originals",provider)
        val sdk = android.os.Build.VERSION.SDK_INT
        val originalEvidence = "${System.getProperty("r1.round") ?: "final"}/original-export"
        val folder = File(root,"artifacts/FRAME-BG-001/R1/$originalEvidence/api$sdk").apply { mkdirs() }
        val report = JSONArray()
        val circle = android.graphics.BitmapFactory.decodeResource(context.resources,R.drawable.circle_a)
        val signature = android.graphics.BitmapFactory.decodeResource(context.resources,R.drawable.sig_a)
        for (index in files.indices) {
            val item=own.getJSONObject(index)
            val expected=item.getString("sha256")
            assertEquals(expected,hash(files[index]))
            val uri=Uri.parse("content://r1.originals/$index")
            val loaded=PhotoLoader.loadForPreview(context,uri)
            val size=item.getJSONArray("orientedSize")
            assertEquals(size.getInt(0),loaded.metadata.width)
            assertEquals(size.getInt(1),loaded.metadata.height)
            val analysis=PhotoColorAnalyzer.analyze(loaded.preview)
            val layout=if(index%2==0) FrameLayoutKind.BAR else FrameLayoutKind.BOX
            val source=PhotoLoader.loadForExport(context,loaded,allowDownsample=false)
            assertEquals(size.getInt(0),source.width)
            assertEquals(size.getInt(1),source.height)
            val sample=1
            val style=BorderStyleResolver.resolve(BorderBackgroundMode.BLUR,analysis)
            val start=System.nanoTime()
            val output=FrameRenderer.render(source,FrameOptions(layout=layout,style=style,circleBitmap=circle,signatureBitmap=signature,
                parameterText="26 mm f/1.8 1/125 s ISO 100",dateText="2026.10.01",placeText="广东省 广州市"))
            val target=File(folder,"${item.getString("id")}-original-blur.jpg")
            target.outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.JPEG,95,it)) }
            report.put(JSONObject().put("id",item.getString("id")).put("source",files[index].absolutePath)
                .put("sha256",expected).put("EXIFOrientation",item.getInt("orientation")).put("metadataSize",size)
                .put("exportSize",JSONArray(listOf(source.width,source.height))).put("safeSample",sample)
                .put("layout",layout.name).put("canvas",JSONArray(listOf(output.width,output.height)))
                .put("millisHostJvm",(System.nanoTime()-start)/1_000_000.0).put("file",target.relativeTo(root).path.replace('\\','/')))
            assertEquals(expected,hash(files[index]))
            output.recycle(); source.recycle(); loaded.preview.recycle(); FrameBackgroundRenderer.clearCache()
        }
        circle.recycle(); signature.recycle()
        File(folder,"manifest.json").writeText(JSONObject().put("sdk",sdk).put("hostOnly",true).put("records",report).toString(2))
        assertEquals(16,report.length())
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private class OriginalProvider(val files: List<File>): ContentProvider() {
        private fun file(uri: Uri) = files[uri.lastPathSegment!!.toInt()]
        override fun onCreate() = true
        override fun getType(uri: Uri) = if(file(uri).extension.lowercase()=="png") "image/png" else "image/jpeg"
        override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri,projection: Array<out String>?,selection: String?,selectionArgs: Array<out String>?,sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(file(uri).name)) }
        override fun insert(uri: Uri,values: ContentValues?): Uri? = error("Read only")
        override fun delete(uri: Uri,selection: String?,selectionArgs: Array<out String>?) = error("Read only")
        override fun update(uri: Uri,values: ContentValues?,selection: String?,selectionArgs: Array<out String>?) = error("Read only")
    }
}
