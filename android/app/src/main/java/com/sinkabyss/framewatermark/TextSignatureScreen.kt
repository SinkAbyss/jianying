package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

@Composable
internal fun TextSignatureDialog(store: UserMaterialStore, onDismiss: () -> Unit, onAdded: (UserMaterial) -> Unit,
    photoPreview: @Composable (Bitmap?, Modifier) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var font by rememberSaveable { mutableStateOf("sans") }
    var fonts by remember { mutableStateOf(MaterialPixels.fonts) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.fonts.read() } }
            .onSuccess { fonts = MaterialPixels.fonts + it.map { item -> item.id to item.name } }
            .onFailure { error = it.message }
    }
    LaunchedEffect(font, fonts) {
        val position = fonts.indexOfFirst { it.first == font }
        if (position >= 0) list.animateScrollToItem(position)
    }
    val maskResult = remember(text, font) {
        runCatching { if (text.isBlank()) null else MaterialPixels.text(text, store.fonts.typeface(font)) }
    }
    val mask = maskResult.getOrNull()
    // Compose/render-thread layers may still hold these pixels after recomposition; let GC release them.
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                photoPreview(mask, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = "签名成片实时预览" })
                Surface(Modifier.fillMaxWidth(), color = AppSurface,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ keyboard?.hide(); onDismiss() }, enabled = !busy) { Text("取消") }
                            Text("添加签名", fontWeight = FontWeight.SemiBold)
                            TextButton({
                                busy = true
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { store.addText(text, font) } }
                                        .onSuccess { keyboard?.hide(); onAdded(it) }
                                        .onFailure { error = it.message ?: "签名保存失败" }
                                    busy = false
                                }
                            }, enabled = text.isNotBlank() && maskResult.isSuccess && !busy) {
                                Text(if (busy) "保存中…" else "完成")
                            }
                        }
                        OutlinedTextField(text, {
                            text = TextRules.limit(it)
                            error = if (TextRules.graphemes(it).size > 50) "最多50个字符" else null
                        }, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            placeholder = { Text("输入签名文字") }, singleLine = true, enabled = !busy,
                            trailingIcon = { if (text.isNotEmpty()) TextButton({ text = "" }, enabled = !busy) { Text("清空") } })
                        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)) {
                            items(fonts, key = { it.first }) { (id, name) ->
                                FontOptionCard(id, name, store, font == id, !busy) { font = id }
                            }
                        }
                        Text("左右滑动选择字体 · 更多字体可在素材库导入", color = AppMuted, fontSize = 11.sp)
                        val message = maskResult.exceptionOrNull()?.message ?: error
                        message?.let { Text(it, color = Color(0xFFF09D95), fontSize = 12.sp, maxLines = 2) }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
internal fun FontOptionCard(id: String, name: String, store: UserMaterialStore, isSelected: Boolean,
    enabled: Boolean, onSelect: () -> Unit) {
    val sample by produceState<Bitmap?>(null, id, store) {
        value = withContext(Dispatchers.IO) { runCatching { MaterialPixels.text("简影 Abc", store.fonts.typeface(id)) }.getOrNull() }
    }
    val shape = RoundedCornerShape(9.dp)
    Column(Modifier.width(112.dp).height(64.dp).clip(shape).background(AppSurfaceRaised)
        .border(if (isSelected) 2.dp else 0.dp, if (isSelected) AppGold else Color.Transparent, shape)
        .semantics { contentDescription = "字体：$name"; selected = isSelected }
        .clickable(enabled = enabled, onClick = onSelect).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        sample?.let { Image(it.asImageBitmap(), null, Modifier.weight(1f).fillMaxWidth(),
            contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.White)) }
        Text(name, fontSize = 10.sp, color = AppMuted, maxLines = 1)
    }
}

/** Same native renderers as the editors; typing changes only this transient signature mask. */
@Composable
internal fun SignatureCreationPhotoPreview(photo: Bitmap, photoWidth: Int, photoHeight: Int,
    frame: FrameOptions?, art: ArtSignatureOptions?, modifier: Modifier) {
    val resolvedArt = remember(photo, art, photoWidth, photoHeight) {
        art?.let { ArtSignatureRenderer.resolve(photo, photoWidth, photoHeight, it) }
    }
    val geometry = remember(photoWidth, photoHeight, frame?.layout) {
        frame?.let { FrameGeometry.calculate(photoWidth, photoHeight, it.layout, outputLongEdge = 2000) }
    }
    val width = geometry?.canvasWidth ?: photoWidth
    val height = geometry?.canvasHeight ?: photoHeight
    Canvas(modifier.background(Color(0xFF08090B))) {
        val scale = min(size.width / width, size.height / height)
        drawIntoCanvas { drawing ->
            val canvas = drawing.nativeCanvas
            val checkpoint = canvas.save()
            try {
                canvas.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                canvas.scale(scale, scale)
                if (frame != null && geometry != null) FrameRenderer.draw(canvas, photo, geometry, frame)
                else if (resolvedArt != null) ArtSignatureRenderer.draw(canvas, photo, width, height, resolvedArt)
                else canvas.drawBitmap(photo, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { canvas.restoreToCount(checkpoint) }
        }
    }
}

@Composable
internal fun MaterialDemoPreview(signature: Bitmap?, modifier: Modifier) {
    val resources = LocalContext.current.resources
    val photo = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.landscape_demo) }
    Box(modifier) {
        SignatureCreationPhotoPreview(photo, photo.width, photo.height,
            FrameOptions(signatureBitmap = signature, parameterText = "35 mm  f/2.8  1/250 s  ISO 100"), null, Modifier.fillMaxSize())
        Text("效果示意", Modifier.align(Alignment.TopStart).padding(8.dp), color = AppMuted, fontSize = 11.sp)
    }
}
