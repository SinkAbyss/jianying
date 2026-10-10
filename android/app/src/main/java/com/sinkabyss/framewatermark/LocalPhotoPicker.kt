package com.sinkabyss.framewatermark

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun LocalPhotoPicker(onCancel: () -> Unit, onConfirm: (List<Uri>) -> Unit,
    onSystemPicker: () -> Unit, maxSelection: Int = 20, catalogOverride: LocalPhotoSource? = null) {
    val context = LocalContext.current
    val catalog = remember(context, catalogOverride) { catalogOverride ?: LocalPhotoCatalog(context) }
    var access by remember { mutableStateOf(localPhotoAccess(context)) }
    var photos by remember { mutableStateOf(emptyList<LocalPhoto>()) }
    var selected by remember { mutableStateOf(emptyList<Uri>()) }
    var album by remember { mutableStateOf<String?>(null) }
    var albumMenu by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Uri?>(null) }
    val gridState = rememberLazyGridState()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    // Never launch permissions automatically; the user can choose either local access or system selection.
    DisposableEffect(context, catalog) {
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<LifecycleOwner>().firstOrNull()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                loading = true
                refresh++
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer); catalog.clearThumbnails() }
    }
    LaunchedEffect(refresh) {
        loading = true
        access = localPhotoAccess(context)
        message = null
        catalog.clearThumbnails()
        try {
            val available = if (access == LocalPhotoAccess.DENIED) emptyList() else catalog.read()
            val retained = reconcileLocalPhotoSelection(selected, available)
            if (retained.size < selected.size) message = "有些已选照片暂时无法访问，已从选择中移除。"
            selected = retained
            photos = available
            if (album != null && available.none { it.albumId == album }) album = null
            if (preview != null && available.none { it.uri == preview }) preview = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            access = localPhotoAccess(context)
            photos = emptyList(); selected = emptyList(); preview = null
            message = "照片访问权限已变化，请重新允许访问。"
        } catch (_: Exception) {
            // Do not allow confirmation from a stale list after an unsuccessful refresh.
            photos = emptyList(); selected = emptyList(); preview = null
            message = "照片暂时无法读取，请重试。"
        } catch (_: OutOfMemoryError) {
            photos = emptyList(); selected = emptyList(); preview = null
            message = "照片较多，暂时无法加载。可从系统选择，或重试。"
        } finally { loading = false }
    }
    BackHandler { if (preview != null) preview = null else onCancel() }
    LaunchedEffect(selected) {
        if (preview != null && preview !in selected) preview = selected.firstOrNull()
    }
    val albums = remember(photos) { localPhotoAlbums(photos) }
    val albumCovers = remember(photos) { photos.groupBy { it.albumId }.mapValues { it.value.first().uri } }
    val displayed = remember(photos, album) { if (album == null) photos else photos.filter { it.albumId == album } }
    LaunchedEffect(album) {
        // scrollToItem also resets a currently empty grid, so its first loaded photos start at the top.
        gridState.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().background(AppBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onCancel) { Text("取消") }
            Text("选择照片", color = Color.White)
            TextButton({ if (!loading && selected.isNotEmpty()) onConfirm(selected.toList()) },
                enabled = !loading && selected.isNotEmpty()) { Text("确认 ${selected.size}/$maxSelection") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton({ albumMenu = true }, enabled = !loading && albums.isNotEmpty()) {
                    Text("${albums.find { it.id == album }?.name ?: "全部照片"} ▾ (${displayed.size})")
                }
                DropdownMenu(albumMenu, { albumMenu = false }, modifier = Modifier.widthIn(min = 280.dp).heightIn(max = 520.dp)) {
                    DropdownMenuItem(text = { Text("全部照片 (${photos.size})") },
                        leadingIcon = { photos.firstOrNull()?.uri?.let { uri ->
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) { LocalPhotoThumbnail(uri, catalog, refresh) }
                        } },
                        onClick = { album = null; albumMenu = false })
                    albums.forEach { item -> DropdownMenuItem(text = { Text("${item.name} (${item.count})") },
                        leadingIcon = { albumCovers[item.id]?.let { uri ->
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))) { LocalPhotoThumbnail(uri, catalog, refresh) }
                        } },
                        onClick = { album = item.id; albumMenu = false }) }
                }
            }
            TextButton(onSystemPicker) { Text("从系统选择") }
        }
        when (access) {
            LocalPhotoAccess.DENIED -> Column(Modifier.padding(20.dp)) {
                Text("允许访问照片后，可按相册浏览；也可以从系统选择。", color = AppMuted)
                Button({ permissions.launch(localPhotoPermissions()) }) { Text("允许访问照片") }
            }
            LocalPhotoAccess.PARTIAL -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("仅显示已允许的照片", color = AppMuted, modifier = Modifier.weight(1f))
                TextButton({ permissions.launch(localPhotoPermissions()) }) { Text("添加可访问照片") }
            }
            LocalPhotoAccess.FULL -> Unit
        }
        message?.let { text -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = AppMuted, modifier = Modifier.weight(1f))
            TextButton({ refresh++ }) { Text("重试") }
        } }
        if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (displayed.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(if (access == LocalPhotoAccess.DENIED) "等待照片访问授权" else "这里暂时没有可显示的照片", color = AppMuted)
        } else LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f), state = gridState,
            contentPadding = PaddingValues(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(displayed, key = { _, photo -> photo.uri.toString() }) { position, photo ->
                val number = selected.indexOf(photo.uri) + 1
                val toggle = {
                    if (number == 0 && selected.size >= maxSelection) message = "每次最多选择 $maxSelection 张照片。"
                    else { selected = toggleLocalPhotoSelection(selected, photo.uri, maxSelection); message = null }
                }
                Box(Modifier.aspectRatio(1f)
                    .then(if (number > 0) Modifier.border(2.dp, AppGold) else Modifier)
                    .semantics { contentDescription = "照片 ${position + 1}"; this.selected = number > 0 }
                    .clickable(onClick = toggle)) {
                    LocalPhotoThumbnail(photo.uri, catalog, refresh)
                    Box(Modifier.align(Alignment.TopEnd).size(48.dp)
                        .semantics { contentDescription = "${if (number > 0) "取消选择" else "选择"}照片 ${position + 1}" }
                        .clickable(onClick = toggle), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(30.dp).background(AppBackground, CircleShape), contentAlignment = Alignment.Center) {
                            Text(if (number > 0) "$number" else "＋", color = if (number > 0) AppGold else Color.White)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton({ preview = selected.firstOrNull() }, enabled = selected.isNotEmpty() && !loading) { Text("预览") }
        }
    }
    preview?.let { uri ->
        val position = selected.indexOf(uri)
        LocalPhotoPreview(uri, position + 1, selected.size, { preview = null }, {
        if (uri !in selected && selected.size >= maxSelection) message = "每次最多选择 $maxSelection 张照片。"
        else selected = toggleLocalPhotoSelection(selected, uri, maxSelection)
        }, if (position > 0) ({ preview = selected[position - 1] }) else null,
            if (position >= 0 && position < selected.lastIndex) ({ preview = selected[position + 1] }) else null,
            catalog)
    }
}

@Composable
private fun LocalPhotoThumbnail(uri: Uri, catalog: LocalPhotoSource, refresh: Int) {
    var bitmap by remember(uri, refresh) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri, refresh) { mutableStateOf(false) }
    LaunchedEffect(uri, refresh) {
        try { bitmap = catalog.thumbnail(uri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        catch (_: OutOfMemoryError) { failed = true }
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        ?: Box(Modifier.fillMaxSize().background(AppSurface), contentAlignment = Alignment.Center) {
            Text(if (failed) "无法预览" else "…", color = AppMuted)
        }
}

@Composable
private fun LocalPhotoPreview(uri: Uri, number: Int, total: Int, onClose: () -> Unit, onToggle: () -> Unit,
    onPrevious: (() -> Unit)?, onNext: (() -> Unit)?, source: LocalPhotoSource) {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        try { bitmap = withContext(Dispatchers.IO) { source.preview(uri) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        catch (_: OutOfMemoryError) { failed = true }
    }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(AppBackground).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClose) { Text("返回选图") }
                TextButton(onToggle, enabled = bitmap != null && !failed) { Text(if (number > 0) "已选 $number · 取消选择" else "选择这张") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), "完整照片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: if (failed) Text("暂时无法预览这张照片", color = AppMuted) else CircularProgressIndicator()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onPrevious?.invoke() }, enabled = onPrevious != null) { Text("上一张") }
                Text("$number/$total", color = AppMuted)
                TextButton({ onNext?.invoke() }, enabled = onNext != null) { Text("下一张") }
            }
        }
    }
}
