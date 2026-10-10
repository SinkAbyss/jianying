package com.sinkabyss.framewatermark

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Composable
internal fun DataManagementScreen(onBack: () -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { DataBackupStore(context) }
    var summary by remember { mutableStateOf<BackupSummary?>(null) }
    var prepared by remember { mutableStateOf<PreparedBackup?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previousAvailable by remember { mutableStateOf(store.previousBackup.isFile) }
    var replace by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }

    BackHandler { if (!busy) onBack() }
    DisposableEffect(Unit) { onDispose { if (!busy) { prepared?.close(); pendingExport?.delete() } } }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { store.summary() } }
            .onSuccess { summary = it }.onFailure { message = it.message ?: "数据读取失败，请重试" }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        val source = pendingExport
        if (uri == null || source == null) {
            source?.delete(); pendingExport = null; busy = false
        } else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法保存到此位置")
                    output.use { target -> source.inputStream().use { it.copyTo(target) } }
                }
                message = "备份已保存"
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                // Do not leave a partial ZIP looking like a successful backup.
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                message = "备份未保存：${failure.message ?: "请重试"}"
            } finally { source.delete(); pendingExport = null; busy = false }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) busy = false else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val inspected = context.contentResolver.openInputStream(uri)?.use { store.inspect(it) } ?: error("无法打开备份文件")
                    // Publish ownership before crossing a cancellable dispatch boundary.
                    prepared = inspected
                    if (!isActive) { inspected.close(); prepared = null }
                }
                replace = false; message = null
            } catch (failure: CancellationException) { prepared?.close(); prepared = null; throw failure }
            catch (failure: Exception) { message = failure.message ?: "无法读取备份文件" }
            finally { busy = false }
        }
    }

    fun export(previous: Boolean) {
        busy = true; message = null
        scope.launch {
            var file: File? = null
            try {
                withContext(Dispatchers.IO) {
                    file = File(context.cacheDir, "export-${UUID.randomUUID()}.zip")
                    val target = file!!
                    if (previous) store.previousBackup.copyTo(target)
                    else target.outputStream().use { store.export(it) }
                }
                pendingExport = file
                val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                saveFile.launch(if (previous) "简影-恢复前备份-$date.zip" else "简影-备份-$date.zip")
            } catch (failure: CancellationException) { file?.delete(); pendingExport = null; busy = false; throw failure }
            catch (failure: Exception) { file?.delete(); pendingExport = null; busy = false; message = failure.message ?: "备份失败，请重试" }
        }
    }

    fun restore() {
        val source = prepared ?: return
        busy = true; message = null; confirmReplace = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.restore(source, replace) }
                Toast.makeText(context, if (replace) "数据已恢复" else "数据已合并", Toast.LENGTH_SHORT).show()
                source.close(); prepared = null
                onRestored() // Recreate all store instances and recipe state; no stale bitmap/font caches.
            } catch (failure: CancellationException) { source.close(); prepared = null; throw failure }
            catch (failure: Exception) {
                message = failure.message ?: "导入失败，请重试"
                previousAvailable = store.previousBackup.isFile
            } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, enabled = !busy) { Text("‹ 返回") }
            Text("数据管理", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("我的数据", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    summary?.let {
                        Text("素材 ${it.materials}    字体 ${it.fonts}")
                        Text("边框配方 ${it.frames}    签名配方 ${it.art}")
                        Text("素材与字体占用 ${android.text.format.Formatter.formatFileSize(context, it.bytes)}", color = AppMuted)
                    } ?: Text("正在读取…", color = AppMuted)
                }
            }
            Button({ export(false) }, enabled = !busy && summary != null, modifier = Modifier.fillMaxWidth()) { Text("备份数据") }
            OutlinedButton({ busy = true; message = null; openFile.launch(arrayOf("*/*")) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("导入备份") }
            if (previousAvailable) TextButton({ export(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("另存恢复前的备份") }
            Text("包含自定义素材、字体、配方和相关设置。原照片、成片及未完成的照片编辑不包含在内。", color = AppMuted, fontSize = 13.sp)
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("正在处理…")
            }
            message?.let { Text(it, color = AppGold) }
        }
    }

    prepared?.let { source ->
        AlertDialog(onDismissRequest = { if (!busy) { source.close(); prepared = null } },
            title = { Text("导入备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(source.summary.description())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(!replace, { replace = false }, enabled = !busy)
                        TextButton({ replace = false }, enabled = !busy) { Text("合并导入") }
                    }
                    Text("保留现有数据，相同内容不重复添加。", color = AppMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(replace, { replace = true }, enabled = !busy)
                        TextButton({ replace = true }, enabled = !busy) { Text("完整恢复") }
                    }
                    Text("替换现有数据，恢复前自动备份当前数据。", color = AppMuted, fontSize = 13.sp)
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    message?.let { Text(it, color = AppGold) }
                }
            },
            dismissButton = { TextButton({ source.close(); prepared = null }, enabled = !busy) { Text("取消") } },
            confirmButton = { TextButton({ if (replace) confirmReplace = true else restore() }, enabled = !busy) { Text("导入") } })
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("替换现有数据？") },
        text = { Text("当前素材、字体和配方将被备份中的数据替换。恢复前备份会保留，可在数据管理中另存。") },
        dismissButton = { TextButton({ confirmReplace = false }) { Text("取消") } },
        confirmButton = { TextButton({ restore() }) { Text("完整恢复") } })
}
