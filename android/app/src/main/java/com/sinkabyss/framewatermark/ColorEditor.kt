package com.sinkabyss.framewatermark

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

internal const val ColorPanelMinimumHeight = 220f
private val quickColors = listOf(0x000000, 0xFFFFFF, 0x353535, 0xDDDDDD, 0xE05252, 0xD9B45B, 0x4F9B6E, 0x4E80C4)
private val hueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
internal fun ColorSetting(target: ColorTarget, choice: ColorChoice, enabled: Boolean,
    onSelect: (ColorChoice) -> Unit, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(target.title, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorTile("${target.title} 自动", !choice.isCustom, enabled,
                { onSelect(ColorChoice.Default) }) { Text("自动", color = if (!choice.isCustom) AppGold else AppMuted) }
            quickColors.forEach { rgb ->
                val color = ColorChoice.custom(rgb)
                ColorTile("${target.title} 常用色 ${color.hex}", choice == color, enabled, { onSelect(color) }) {
                    Box(Modifier.size(28.dp).background(Color(requireNotNull(color.rgb)), CircleShape)
                        .border(1.dp, Color(0xFF686B70), CircleShape))
                }
            }
            val custom = choice.isCustom && quickColors.none { choice == ColorChoice.custom(it) }
            ColorTile("${target.title} 自定义色盘", custom, enabled, onOpen) {
                Box(Modifier.size(28.dp).background(Brush.sweepGradient(hueColors), CircleShape),
                    contentAlignment = Alignment.Center) { Text("＋", color = Color.White) }
            }
        }
    }
}

@Composable
private fun ColorTile(description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape)
        .border(if (selected) 2.dp else 0.dp, if (selected) AppGold else Color.Transparent, CircleShape)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center, content = content)
}

/** Bounded panel with scrolling controls and persistent actions while its height changes. */
@Composable
internal fun ColorEditor(target: ColorTarget, choice: ColorChoice, initialInk: Int,
    onChange: (ColorChoice) -> Unit, onCancel: () -> Unit, onApply: () -> Unit,
    modifier: Modifier = Modifier) {
    var hex by remember { mutableStateOf(choice.hex ?: String.format(Locale.ROOT, "#%06X", initialInk and 0xFFFFFF)) }
    val initialHsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(choice.rgb ?: initialInk, it) } }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val validHex = ColorChoice.fromHex(hex) != null
    LaunchedEffect(choice.isCustom, initialInk) {
        if (!choice.isCustom) {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(initialInk, hsv)
            hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
            hex = String.format(Locale.ROOT, "#%06X", initialInk and 0xFFFFFF)
        }
    }
    fun updateHsv(h: Float, s: Float, v: Float) {
        val color = ColorChoice.custom(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
        hex = requireNotNull(color.hex)
        hue = h; saturation = s; value = v
        onChange(color)
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val paletteHeight = (maxHeight * 0.55f).coerceIn(180.dp, 300.dp)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.title, style = MaterialTheme.typography.titleMedium)
                val latestHue by rememberUpdatedState(hue)
                val latestUpdate by rememberUpdatedState<(Float, Float, Float) -> Unit>(::updateHsv)
                Canvas(Modifier.fillMaxWidth().height(paletteHeight).semantics { contentDescription = "饱和度与明度选色区域" }
                    .pointerInput(Unit) {
                        detectTapGestures { position -> latestUpdate(latestHue,
                            (position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (1f - position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)) }
                    }.pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            latestUpdate(latestHue, (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (1f - change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White,
                        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val point = androidx.compose.ui.geometry.Offset(saturation * size.width, (1f - value) * size.height)
                    drawCircle(Color.Black, 7.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
                    drawCircle(Color.White, 7.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
                Text("色相", color = AppMuted)
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp).clip(CircleShape)
                        .background(Brush.horizontalGradient(hueColors)))
                    Slider(value = hue, onValueChange = { updateHsv(it, saturation, value) }, valueRange = 0f..359.99f,
                        colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                        modifier = Modifier.semantics { contentDescription = "颜色色相" })
                }
                OutlinedTextField(value = hex, onValueChange = { text ->
                    hex = text
                    ColorChoice.fromHex(text)?.let { color ->
                        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(requireNotNull(color.rgb), hsv)
                        hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; onChange(color)
                    }
                }, label = { Text("HEX · #RRGGBB") }, singleLine = true, isError = !validHex,
                    supportingText = { if (!validHex) Text("请输入六位十六进制颜色，可带 #") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = AppMuted.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(ColorChoice.Default) }) { Text("恢复自动") }
                Row {
                    TextButton(onClick = onCancel) { Text("取消") }
                    Button(onClick = onApply, enabled = validHex) { Text("应用") }
                }
            }
        }
    }
}
