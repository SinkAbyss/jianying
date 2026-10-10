package com.sinkabyss.framewatermark

import androidx.compose.foundation.Canvas
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal enum class FillingHelpKind { FRAME, ART }

/** Both editors keep this menu mounted while their own help page is open. */
@Composable
internal fun EditorMoreMenu(enabled: Boolean, kind: FillingHelpKind) {
    var expanded by remember { mutableStateOf(false) }
    var helpVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(enabled) { if (!enabled) expanded = false }

    Box {
        IconButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "更多" }) {
            val ink = if (enabled) MaterialTheme.colorScheme.onSurface else AppMuted
            Canvas(Modifier.size(24.dp)) {
                listOf(5f, 12f, 19f).forEach { y ->
                    drawCircle(ink, radius = 1.8.dp.toPx(), center = Offset(size.width / 2, size.height * y / 24f))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("填写帮助") }, enabled = enabled, onClick = {
                expanded = false
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                helpVisible = true
            })
        }
    }
    if (helpVisible) FillingHelpScreen(kind, onBack = { helpVisible = false })
}

/** The watermark kind selects content; examples have no access to photo drafts. */
@Composable
internal fun FillingHelpScreen(kind: FillingHelpKind, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false,
    )) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ 返回编辑") }
                    Text("信息填写帮助", Modifier.weight(1f).padding(start = 8.dp),
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (kind) {
                        FillingHelpKind.FRAME -> FrameFillingHelp()
                        FillingHelpKind.ART -> ArtFillingHelp()
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameFillingHelp() {
    HelpExample(FillingHelpKind.FRAME)
    HelpGroup("摄影参数") {
        HelpField("焦距", "镜头焦距，例如 35 mm。")
        HelpField("光圈", "光圈大小，例如 f/2.8。")
        HelpField("快门", "曝光时间，例如 1/250 s。")
        HelpField("ISO", "感光度，例如 ISO 100。")
    }
    HelpGroup("拍摄信息") {
        HelpField("日期时间", "拍摄时间，例如 2026.10.01 16:30。")
        HelpField("地点", "拍摄地点，例如 麦克唐纳湖。")
        HelpField("机型", "拍摄设备，例如 示例相机 X1。")
    }
}

@Composable
private fun ArtFillingHelp() {
    HelpExample(FillingHelpKind.ART)
    HelpGroup("文字信息") {
        HelpField("机型", "拍摄设备，例如 示例相机 X1。")
        HelpField("个性短句", "一句心情，例如 把此刻留在光里。")
        HelpField("日期时间", "拍摄时间，例如 2026.10.01 16:30。")
        HelpField("地点", "拍摄地点，例如 麦克唐纳湖。")
    }
}

@Composable
private fun HelpExample(kind: FillingHelpKind) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpHeading("填写示例")
        Text("示例仅供参考", color = AppMuted, fontSize = 13.sp)
        HelpWatermarkPreview(kind)
    }
}

/** Uses the same drawing routines as the editors and export, with fixed sample values. */
@Composable
private fun HelpWatermarkPreview(kind: FillingHelpKind) {
    val resources = LocalResources.current
    val photo = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.help_landscape) }
    val signature = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.sig_a) }
    val frame = remember(photo) { FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BOX) }
    val frameOptions = remember(signature) {
        FrameOptions(layout = FrameLayoutKind.BOX, signatureBitmap = signature,
            parameterText = "35 mm  f/2.8  1/250 s  ISO 100", dateText = "2026.10.01 16:30",
            placeText = "麦克唐纳湖", modelText = "示例相机 X1")
    }
    val artOptions = remember(photo, signature) {
        ArtSignatureRenderer.resolve(photo, photo.width, photo.height,
            ArtSignatureOptions(signature, model = "示例相机 X1", phrase = "把此刻留在光里",
                date = "2026.10.01 16:30", place = "麦克唐纳湖"))
    }
    val width = if (kind == FillingHelpKind.FRAME) frame.canvasWidth else photo.width
    val height = if (kind == FillingHelpKind.FRAME) frame.canvasHeight else photo.height
    Canvas(Modifier.fillMaxWidth().aspectRatio(width.toFloat() / height).clip(RoundedCornerShape(14.dp))
        .semantics { contentDescription = if (kind == FillingHelpKind.FRAME) "个性边框填写效果图" else "艺术签名填写效果图" }) {
        drawIntoCanvas { drawing ->
            val canvas = drawing.nativeCanvas
            val checkpoint = canvas.save()
            try {
                canvas.scale(size.width / width, size.height / height)
                when (kind) {
                    FillingHelpKind.FRAME -> FrameRenderer.draw(canvas, photo, frame, frameOptions)
                    FillingHelpKind.ART -> ArtSignatureRenderer.draw(canvas, photo, width, height, artOptions)
                }
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}

@Composable
private fun HelpGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppSurface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HelpHeading(title)
        content()
    }
}

@Composable
private fun HelpHeading(title: String) {
    Text(title, Modifier.semantics { heading() }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HelpField(title: String, explanation: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, Modifier.semantics { heading() }, color = AppGold, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Text(explanation, fontSize = 14.sp, lineHeight = 22.sp)
    }
}
