package com.sinkabyss.framewatermark

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Color as NativeColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

internal enum class MaterialCreation { TEXT, HANDWRITING, SIGNATURE_IMAGE, CIRCLE_IMAGE }

@Composable
internal fun MaterialPickerRow(
    kind: MaterialKind, materials: List<UserMaterial>, selected: String?, allowNone: Boolean,
    enabled: Boolean, onSelect: (String?) -> Unit, onOpenLibrary: (MaterialKind) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (kind == MaterialKind.SIGNATURE) "签名" else "圆图", color = AppMuted, fontSize = 12.sp)
        TextButton({ onOpenLibrary(kind) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("素材库", fontSize = 12.sp)
        }
    }
    val assets = materials.filter { it.kind == kind }
    val choices = assets.map { it.id to it.name }
    val listState = rememberLazyListState()
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(selected, choices.size, orientation) {
        val position = choices.indexOfFirst { it.first == selected }
        if (position >= 0) listState.animateScrollToItem(position + if (allowNone) 1 else 0)
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 3.dp)) {
        if (allowNone) item { MaterialChoice(null, "无", kind, selected == null, enabled, { onSelect(null) }) }
        items(choices, key = { it.first }) { (id, name) ->
            MaterialChoice(id, name, kind, selected == id, enabled, { onSelect(id) })
        }
    }
    if (assets.isEmpty()) Text("在素材库制作或导入", color = AppMuted, fontSize = 12.sp)
    }
}

@Composable
private fun MaterialChoice(id: String?, name: String, kind: MaterialKind, isSelected: Boolean, enabled: Boolean,
    onClick: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { UserMaterialStore(context) }
    val bitmap by produceState<Bitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { runCatching { store.bitmap(id, thumbnail = true) }.getOrNull() }
    }
    val shape = if (kind == MaterialKind.IMAGE) CircleShape else RoundedCornerShape(9.dp)
    Box {
        Box(Modifier.width(if (kind == MaterialKind.IMAGE) 48.dp else 82.dp).height(48.dp)
            .clip(shape).background(AppSurfaceRaised)
            .border(if (isSelected) 2.dp else 0.dp, if (isSelected) AppGold else Color.Transparent, shape)
            .semantics { contentDescription = name; this.selected = isSelected }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(if (kind == MaterialKind.IMAGE) 3.dp else 7.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap!!.asImageBitmap(), null,
                Modifier.fillMaxSize().clip(shape),
                contentScale = if (kind == MaterialKind.IMAGE) ContentScale.Crop else ContentScale.Fit,
                colorFilter = if (kind == MaterialKind.SIGNATURE) ColorFilter.tint(Color.White) else null)
            else Text(if (id == null) "无" else "…", color = AppMuted, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun MaterialCreationDialogs(
    creation: MaterialCreation, store: UserMaterialStore,
    onDismiss: () -> Unit, onAdded: (UserMaterial) -> Unit,
    textPreview: @Composable (Bitmap?, Modifier) -> Unit = { bitmap, modifier -> MaterialDemoPreview(bitmap, modifier) },
) {
    var imageUri by remember(creation) { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it == null) onDismiss() else imageUri = it
    }
    LaunchedEffect(creation) {
        if (creation == MaterialCreation.CIRCLE_IMAGE || creation == MaterialCreation.SIGNATURE_IMAGE)
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    when (creation) {
        MaterialCreation.TEXT -> TextSignatureDialog(store, onDismiss, onAdded, textPreview)
        MaterialCreation.HANDWRITING -> HandwritingMaterialDialog(store, onDismiss, onAdded)
        MaterialCreation.SIGNATURE_IMAGE, MaterialCreation.CIRCLE_IMAGE -> imageUri?.let { uri ->
            ImageMaterialDialog(uri, if (creation == MaterialCreation.CIRCLE_IMAGE) MaterialKind.IMAGE else MaterialKind.SIGNATURE,
                store, onDismiss, onAdded)
        }
    }
}

private data class InkStroke(val points: List<Offset>)
private fun handwritingBitmap(strokes: List<InkStroke>, size: Size, strokeWidth: Float): Bitmap {
    require(strokes.isNotEmpty() && size.width > 0) { "请先写下签名" }
    // Draw at 2× screen pixels, preserving the same round stroke and geometry as the preview.
    val scale = 2f
    val bitmap = Bitmap.createBitmap((size.width * scale).roundToInt().coerceAtLeast(1),
        (size.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(bitmap)
    canvas.scale(scale, scale)
    drawInk(canvas, strokes, strokeWidth)
    return try { MaterialPixels.trim(bitmap).also { if (it !== bitmap) bitmap.recycle() } }
        catch (error: Throwable) { bitmap.recycle(); throw error }
}

private fun drawInk(canvas: NativeCanvas, strokes: List<InkStroke>, inkWidth: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = NativeColor.WHITE; strokeWidth = inkWidth; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { stroke ->
        val points = stroke.points
        if (points.size == 1) {
            paint.style = Paint.Style.FILL; canvas.drawCircle(points[0].x, points[0].y, inkWidth / 2f, paint); paint.style = Paint.Style.STROKE
        } else if (points.isNotEmpty()) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) {
                    val previous = points[i - 1]; val point = points[i]
                    quadTo(previous.x, previous.y, (previous.x + point.x) / 2f, (previous.y + point.y) / 2f)
                }
                lineTo(points.last().x, points.last().y)
            }
            canvas.drawPath(path, paint)
        }
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

@Composable
private fun HandwritingMaterialDialog(store: UserMaterialStore, onDismiss: () -> Unit, onAdded: (UserMaterial) -> Unit) {
    val activity = LocalContext.current.hostActivity()
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inkWidth = with(LocalDensity.current) { 6.dp.toPx() }
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = AppBackground) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消") }
                    Text("手写签名", fontSize = 20.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ strokes.clear(); current = emptyList() }, enabled = !busy) { Text("清空") }
                        Button(onClick = {
                            busy = true
                            val snapshot = strokes.toList()
                            val drawingSize = canvasSize
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) {
                                    val bitmap = handwritingBitmap(snapshot, drawingSize, inkWidth)
                                    try { store.addBitmap("手写签名", MaterialKind.SIGNATURE, bitmap) } finally { bitmap.recycle() }
                                } }.onSuccess(onAdded).onFailure { error = it.message ?: "保存失败" }
                                busy = false
                            }
                        }, enabled = landscape && strokes.isNotEmpty() && current.isEmpty() && !busy) {
                            Text(if (busy) "保存中…" else "保存")
                        }
                    }
                }
                if (!landscape) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                Canvas(Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "横屏手写画布" }
                    .clip(RoundedCornerShape(12.dp)).background(AppBackground)
                    .pointerInput(busy) {
                        if (!busy) awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            current = listOf(down.position)
                            try {
                                var pressed = true
                                while (pressed) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    current = current + Offset(change.position.x.coerceIn(0f, size.width.toFloat()),
                                        change.position.y.coerceIn(0f, size.height.toFloat()))
                                    pressed = change.pressed
                                    change.consume()
                                }
                                if (current.isNotEmpty()) strokes.add(InkStroke(current))
                            } finally { current = emptyList() }
                        }
                    }) {
                    canvasSize = size
                    drawIntoCanvas { drawInk(it.nativeCanvas, strokes.toList() + if (current.isEmpty()) emptyList() else listOf(InkStroke(current)), inkWidth) }
                }
                error?.let { Text(it, color = Color(0xFFF09D95)) }
            }
        }
    }
}

@Composable
private fun ImageMaterialDialog(uri: Uri, kind: MaterialKind, store: UserMaterialStore,
    onDismiss: () -> Unit, onAdded: (UserMaterial) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    var preserveAlpha by remember { mutableStateOf(false) }
    var transparent by remember { mutableStateOf(false) }
    var threshold by remember { mutableIntStateOf(25) }
    var extracted by remember { mutableStateOf<Bitmap?>(null) }
    var lightInk by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        runCatching { withContext(Dispatchers.IO) {
            MaterialPixels.decode(context, uri).let { it to MaterialPixels.hasTransparency(it) }
        } }.onSuccess { (bitmap, alpha) -> source = bitmap; transparent = alpha; preserveAlpha = alpha }
            .onFailure { error = it.message ?: "图片读取失败" }
    }
    val previewToken = remember(source, crop, preserveAlpha, threshold) { Any() }
    val previewResult by produceState<Pair<Any, Bitmap?>?>(null, previewToken) {
        if (kind == MaterialKind.SIGNATURE) return@produceState
        val input = source ?: return@produceState
        delay(100)
        val result = withContext(Dispatchers.Default) {
            val cropped = MaterialPixels.crop(input, crop)
            val longest = maxOf(cropped.width, cropped.height)
            val working = if (longest > 1024) {
                Bitmap.createScaledBitmap(cropped, (cropped.width * 1024f / longest).roundToInt().coerceAtLeast(1),
                    (cropped.height * 1024f / longest).roundToInt().coerceAtLeast(1), true)
                    .also { if (cropped !== input && it !== cropped) cropped.recycle() }
            } else cropped
            working
        }
        value = previewToken to result
    }
    val preview = previewResult?.takeIf { it.first === previewToken }?.second
    fun extractSelection() {
        val input = source ?: return
        val region = RectF(crop)
        val keepAlpha = preserveAlpha
        val cutoff = threshold
        error = null
        busy = true
        scope.launch {
            runCatching { withContext(Dispatchers.Default) {
                val cropped = MaterialPixels.crop(input, region)
                var mask: Bitmap? = null
                try {
                    mask = MaterialPixels.extract(cropped, keepAlpha, cutoff)
                    MaterialPixels.trim(mask).also { if (it !== mask) mask.recycle() }
                } catch (failure: Throwable) {
                    mask?.recycle()
                    throw failure
                } finally { if (cropped !== input) cropped.recycle() }
            } }.onSuccess { extracted = it }.onFailure { error = it.message ?: "提取失败，请调整裁选范围" }
            busy = false
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = AppBackground) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss, enabled = !busy) { Text("取消") }
                    Text(if (kind == MaterialKind.IMAGE) "导入圆图 / Logo"
                        else if (extracted == null) "修剪图片" else "效果预览", fontSize = 20.sp)
                    if (kind == MaterialKind.SIGNATURE) TextButton(onClick = {
                        if (extracted == null) extractSelection() else {
                            val mask = extracted ?: return@TextButton
                            busy = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) {
                                    store.addBitmap("图片签名", MaterialKind.SIGNATURE, mask)
                                } }.onSuccess(onAdded).onFailure { error = it.message ?: "保存失败" }
                                busy = false
                            }
                        }
                    }, enabled = source != null && !busy) { Text(if (busy) "处理中…" else "完成") }
                }
                Text(if (extracted != null) "仅保留内容轮廓，背景透明；颜色随水印样式显示。"
                    else if (kind == MaterialKind.SIGNATURE) "裁选签名区域，四角留少量干净底色，再点完成提取。"
                    else "拖动框选区域；拖动左上或右下角调整范围。", color = AppMuted, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (extracted != null) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp)
                            .aspectRatio((extracted!!.width.toFloat() / extracted!!.height).coerceIn(1f, 4f))) {
                            TransparencyGrid(Modifier.fillMaxSize())
                            Image(extracted!!.asImageBitmap(), "提取后的透明签名", Modifier.fillMaxSize().padding(20.dp),
                                contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(if (lightInk) Color.White else Color.Black))
                        }
                    } else source?.let { input -> CropCanvas(input, crop, !busy) { crop = it } }
                        ?: if (error == null) CircularProgressIndicator() else Unit
                    if (busy) CircularProgressIndicator()
                }
                if (kind == MaterialKind.SIGNATURE) {
                    if (transparent && extracted == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(preserveAlpha, { preserveAlpha = it }, enabled = !busy)
                        Text("保留原图透明度")
                    }
                    if (!preserveAlpha && source != null && (extracted != null || error != null)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("去底强度", fontSize = 13.sp); Text(threshold.toString(), color = AppGold)
                        }
                        Slider(threshold.toFloat(), { threshold = it.roundToInt() },
                            onValueChangeFinished = { extractSelection() }, valueRange = 0f..100f, enabled = !busy)
                    }
                    if (extracted != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ extracted = null; error = null }, enabled = !busy) { Text("重新裁选") }
                        Spacer(Modifier.weight(1f))
                        TextButton({ lightInk = !lightInk }, enabled = !busy) { Text(if (lightInk) "查看深色笔画" else "查看浅色笔画") }
                    }
                }
                if (kind == MaterialKind.IMAGE) {
                Text("圆图预览", color = AppMuted, fontSize = 12.sp)
                Box(Modifier.fillMaxWidth().height(100.dp).background(AppSurfaceRaised, RoundedCornerShape(12.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), null,
                        if (kind == MaterialKind.IMAGE) Modifier.size(84.dp).clip(androidx.compose.foundation.shape.CircleShape) else Modifier.fillMaxSize(),
                        contentScale = if (kind == MaterialKind.IMAGE) ContentScale.Crop else ContentScale.Fit) }
                }
                error?.let { Text(it, color = Color(0xFFF09D95), fontSize = 12.sp) }
                Button(onClick = {
                    val input = source ?: return@Button
                    val region = RectF(crop)
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) {
                            val cropped = MaterialPixels.crop(input, region)
                            try {
                                store.addBitmap("自选圆图", kind, cropped)
                            } finally {
                                if (cropped !== input) cropped.recycle()
                            }
                        } }.onSuccess(onAdded).onFailure { error = it.message ?: "保存失败" }
                        busy = false
                    }
                }, enabled = source != null && preview != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "保存中…" else "确认并存入素材库") }
                }
                if (kind == MaterialKind.SIGNATURE) error?.let { Text(it, color = Color(0xFFF09D95), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun TransparencyGrid(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(12.dp))) {
        val cell = 12.dp.toPx()
        for (row in 0..(size.height / cell).toInt()) for (column in 0..(size.width / cell).toInt()) {
            drawRect(if ((row + column) % 2 == 0) Color(0xFF666A74) else Color(0xFF8D9199),
                topLeft = Offset(column * cell, row * cell), size = Size(cell, cell))
        }
    }
}

@Composable
private fun CropCanvas(source: Bitmap, region: RectF, enabled: Boolean, onChange: (RectF) -> Unit) {
    val latestRegion by rememberUpdatedState(region)
    val latestChange by rememberUpdatedState(onChange)
    Canvas(Modifier.fillMaxSize().pointerInput(source, enabled) {
        if (!enabled) return@pointerInput
        var mode = 0
        var rect = RectF()
        val scale = min(size.width.toFloat() / source.width, size.height.toFloat() / source.height)
        val width = source.width * scale; val height = source.height * scale
        val offset = Offset((size.width - width) / 2, (size.height - height) / 2)
        detectDragGestures(onDragStart = { position ->
            rect = RectF(latestRegion)
            val point = position - offset
            val upper = Offset(rect.left * width, rect.top * height)
            val lower = Offset(rect.right * width, rect.bottom * height)
            mode = when {
                (point - upper).getDistance() < 80f -> 1
                (point - lower).getDistance() < 80f -> 2
                else -> 0
            }
        }, onDrag = { change, amount ->
            change.consume()
            val dx = amount.x / width; val dy = amount.y / height
            when (mode) {
                1 -> { rect.left = (rect.left + dx).coerceIn(0f, rect.right - .02f); rect.top = (rect.top + dy).coerceIn(0f, rect.bottom - .02f) }
                2 -> { rect.right = (rect.right + dx).coerceIn(rect.left + .02f, 1f); rect.bottom = (rect.bottom + dy).coerceIn(rect.top + .02f, 1f) }
                else -> rect.offset(dx.coerceIn(-rect.left, 1f - rect.right), dy.coerceIn(-rect.top, 1f - rect.bottom))
            }
            latestChange(RectF(rect))
        })
    }) {
        val scale = min(size.width / source.width, size.height / source.height)
        val width = source.width * scale; val height = source.height * scale
        val offset = Offset((size.width - width) / 2, (size.height - height) / 2)
        drawIntoCanvas { target ->
            val canvas = target.nativeCanvas
            val photo = RectF(offset.x, offset.y, offset.x + width, offset.y + height)
            canvas.drawBitmap(source, null, photo, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val rect = RectF(offset.x + region.left * width, offset.y + region.top * height,
                offset.x + region.right * width, offset.y + region.bottom * height)
            val paint = Paint().apply { color = 0xAA000000.toInt() }
            canvas.drawRect(photo.left, photo.top, photo.right, rect.top, paint)
            canvas.drawRect(photo.left, rect.bottom, photo.right, photo.bottom, paint)
            canvas.drawRect(photo.left, rect.top, rect.left, rect.bottom, paint)
            canvas.drawRect(rect.right, rect.top, photo.right, rect.bottom, paint)
            paint.apply { color = 0xFFE8B931.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(rect.left, rect.top, 12f, paint); canvas.drawCircle(rect.right, rect.bottom, 12f, paint)
        }
    }
}
