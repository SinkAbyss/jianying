package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun UserMaterialLibraryScreen(materials: List<UserMaterial>, store: UserMaterialStore,
    initialKind: MaterialKind, fromEditor: Boolean, allowImages: Boolean, onBack: () -> Unit,
    onCreate: (MaterialCreation) -> Unit, onDelete: (UserMaterial) -> Unit, onUse: (UserMaterial) -> Unit,
    onFontsChanged: () -> Unit = {}, initialFontTab: Boolean = false) {
    var tab by rememberSaveable { mutableIntStateOf(if (initialFontTab) 2 else if (initialKind == MaterialKind.IMAGE) 1 else 0) }
    var fonts by remember { mutableStateOf<List<UserFont>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<UserFont?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = importing) { }
    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.fonts.read() } }
            .onSuccess { fonts = it }.onFailure { error = it.message }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { store.fonts.import(uri); store.fonts.read() } }
                    .onSuccess { fonts = it; error = null }.onFailure { error = it.message ?: "字体导入失败" }
                importing = false
            }
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onBack, enabled = !importing) { Text(if (fromEditor) "‹ 返回编辑" else "‹ 水印库") }
            Text("素材库", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(Modifier.width(70.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("签名", "圆图", "字体").forEachIndexed { index, name ->
                FilterChip(tab == index, { tab = index; error = null }, enabled = !importing, label = { Text(name) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        Button({ menu = true }) { Text("添加签名") }
                        DropdownMenu(menu, { menu = false }) {
                            listOf("键盘输入" to MaterialCreation.TEXT, "手写输入" to MaterialCreation.HANDWRITING,
                                "图片提取" to MaterialCreation.SIGNATURE_IMAGE).forEach { (name, creation) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { menu = false; onCreate(creation) })
                            }
                        }
                    }
                }
                1 -> Button({ onCreate(MaterialCreation.CIRCLE_IMAGE) }) { Text("导入圆图 / Logo") }
                2 -> Button({ picker.launch(arrayOf("*/*")) }, enabled = !importing) {
                    Text(if (importing) "导入中…" else "导入字体")
                }
            }
        }
        if (tab == 2) {
            Text("TTF / OTF · 单个不超过32 MB\n字体用于键盘输入，导入后重开仍可使用。", Modifier.padding(horizontal = 16.dp), color = AppMuted, fontSize = 12.sp)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (fonts.isEmpty()) item { Text("还没有导入字体", color = AppMuted) }
                items(fonts.size, key = { fonts[it].id }) { index ->
                    val font = fonts[index]
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(font.name, Modifier.weight(1f).padding(vertical = 16.dp), maxLines = 2)
                            TextButton({ fontToDelete = font }, enabled = !importing) { Text("删除", color = AppMuted) }
                        }
                    }
                }
            }
        } else {
            val assets = materials.filter { it.kind == if (tab == 0) MaterialKind.SIGNATURE else MaterialKind.IMAGE }
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(assets, key = { it.id }) { asset ->
                    val bitmap by produceState<Bitmap?>(null, asset.id, store) {
                        value = withContext(Dispatchers.IO) { runCatching { store.bitmap(asset.id, thumbnail = true) }.getOrNull() }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                        Box(Modifier.fillMaxWidth().height(90.dp).padding(12.dp), contentAlignment = Alignment.Center) {
                            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                                colorFilter = if (asset.kind == MaterialKind.SIGNATURE) ColorFilter.tint(Color.White) else null) }
                        }
                        Text(asset.name, Modifier.padding(horizontal = 12.dp), maxLines = 1, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            if (fromEditor && (asset.kind == MaterialKind.SIGNATURE || allowImages)) TextButton({ onUse(asset) }) { Text("使用") }
                            else Spacer(Modifier.width(4.dp))
                            TextButton({ onDelete(asset) }) { Text("删除", color = AppMuted, fontSize = 12.sp) }
                        }
                    }
                }
            }
            if (assets.isEmpty()) Text("还没有这类素材，点上方按钮添加。", Modifier.padding(16.dp), color = AppMuted)
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = Color(0xFFF09D95), fontSize = 12.sp) }
    }
    fontToDelete?.let { font ->
        AlertDialog(onDismissRequest = { if (!importing) fontToDelete = null },
            title = { Text("删除字体？") },
            text = { Text("删除「${font.name}」后，新建签名将不能再选择它。已有签名会保留当前外观，转为图片素材；原始字体文件不会删除。") },
            confirmButton = { TextButton({
                importing = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { store.deleteFont(font.id); store.fonts.read() } }
                        .onSuccess { fonts = it; error = null; fontToDelete = null; onFontsChanged() }
                        .onFailure { error = it.message ?: "字体删除失败"; fontToDelete = null; onFontsChanged() }
                    importing = false
                }
            }, enabled = !importing) { Text(if (importing) "删除中…" else "删除") } },
            dismissButton = { TextButton({ fontToDelete = null }, enabled = !importing) { Text("取消") } })
    }
}
