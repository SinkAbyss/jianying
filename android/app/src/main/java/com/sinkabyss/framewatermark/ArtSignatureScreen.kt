package com.sinkabyss.framewatermark

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

internal class ArtPhotoDraft(metadata: PhotoMetadata) : PhotoDraft(metadata) {
    var showPhrase by mutableStateOf(false)
    var phrase by mutableStateOf("")

    init {
        showParameters = false
        showDate = false
        showPlace = false
        showModel = false
    }

    override fun missingEnabledFields(): List<String> = super.missingEnabledFields() +
        if (showPhrase && phrase.isBlank()) listOf("个性短句") else emptyList()

    fun options(signature: Bitmap, horizontal: Int, vertical: Int, opacity: Int,
        colors: ForegroundColors = ForegroundColors(), signaturePercent: Int = 100) = ArtSignatureOptions(
        signature = signature, horizontal = horizontal, vertical = vertical, opacityPercent = opacity,
        model = if (showModel) currentModel() else "",
        phrase = if (showPhrase) phrase else "",
        date = if (showDate) currentDate() else "",
        place = if (showPlace) currentPlace() else "",
        colors = colors,
        signaturePercent = signaturePercent,
    )
}

private enum class ArtEditorTab(val title: String) { SIGNATURE("签名"), LAYOUT("布局"), INFO("信息") }

@Composable
internal fun ArtSignatureScreen(
    entry: PhotoEntry, photos: List<PhotoEntry>, currentIndex: Int,
    signatureId: String, signature: Bitmap?, horizontal: Int, vertical: Int, opacity: Int,
    busy: Boolean, onBack: () -> Unit, onSave: () -> Unit,
    onSignature: (String) -> Unit, onHorizontal: (Int) -> Unit, onVertical: (Int) -> Unit,
    onOpacity: (Int) -> Unit, onChoosePhoto: (Int) -> Unit, onAddPhotos: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    materials: List<UserMaterial>, onOpenMaterials: (MaterialKind) -> Unit,
    panelExpanded: Boolean, panelHeightDp: Float,
    onPanelExpandedChanged: (Boolean) -> Unit, onPanelHeightChanged: (Float) -> Unit,
    colors: ForegroundColors, colorTarget: ColorTarget?, onOpenColor: (ColorTarget) -> Unit,
    onSelectColor: (ColorTarget, ColorChoice) -> Unit,
    onColorChange: (ColorChoice) -> Unit, onCancelColor: () -> Unit, onApplyColor: () -> Unit,
    onRecipes: () -> Unit,
    onSaveAll: (() -> Unit)? = null,
    signaturePercent: Int = 100,
    onSignaturePercent: (Int) -> Unit = {},
) {
    val rawOptions = signature?.let { entry.artDraft.options(it, horizontal, vertical, opacity, colors, signaturePercent) }
    val options = remember(entry.loaded, rawOptions) { rawOptions?.let {
        ArtSignatureRenderer.resolve(entry.loaded.preview, entry.loaded.metadata.width, entry.loaded.metadata.height, it)
    } }
    val colorPicking = colorTarget != null
    val locked = busy || colorPicking
    var selectedTab by remember { mutableStateOf(ArtEditorTab.SIGNATURE) }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val collapsedHeight = 148f // Handle, photo strip and category tabs remain available.
        val headerHeight = 62f
        val maxPanel = (maxHeight.value - headerHeight - 130f).coerceAtLeast(collapsedHeight)
        val minimumHeight = if (colorPicking) min(ColorPanelMinimumHeight, maxPanel) else collapsedHeight
        val actualHeight = if (colorPicking || panelExpanded) panelHeightDp.coerceIn(minimumHeight, maxPanel) else collapsedHeight
        val animatedHeight by animateDpAsState(actualHeight.dp, label = "art-panel-height")
        val density = LocalDensity.current.density
        val latestHeight = rememberUpdatedState(panelHeightDp)
        val latestSetHeight = rememberUpdatedState(onPanelHeightChanged)
        val latestSetExpanded = rememberUpdatedState(onPanelExpandedChanged)
        val signatureScroll = rememberScrollState()
        val layoutScroll = rememberScrollState()
        val infoScroll = rememberScrollState()
        val settingsScroll = when (selectedTab) {
            ArtEditorTab.SIGNATURE -> signatureScroll
            ArtEditorTab.LAYOUT -> layoutScroll
            ArtEditorTab.INFO -> infoScroll
        }
        Column(Modifier.fillMaxSize()) {
        EditorTopBar(busy = busy, interactionEnabled = !locked, onBack = onBack, onSave = onSave,
            title = "艺术签名", canSave = signature != null, helpKind = FillingHelpKind.ART,
            onRecipes = onRecipes, onSaveAll = onSaveAll)
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF08090B))) {
            Canvas(Modifier.fillMaxSize()) {
                val source = entry.loaded.preview
                val width = entry.loaded.metadata.width.coerceAtLeast(1)
                val height = entry.loaded.metadata.height.coerceAtLeast(1)
                val scale = min(size.width / width, size.height / height)
                drawIntoCanvas { drawing ->
                    val native = drawing.nativeCanvas
                    val checkpoint = native.save()
                    try {
                        native.translate((size.width - width * scale) / 2, (size.height - height * scale) / 2)
                        native.scale(scale, scale)
                        if (options != null) ArtSignatureRenderer.draw(native, source, width, height, options)
                        else native.drawBitmap(source, null, android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat()),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    } finally {
                        native.restoreToCount(checkpoint)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(animatedHeight)
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(AppSurface)) {
            Box(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "拖动调整编辑面板高度" }
                .pointerInput(maxPanel, density, busy, colorPicking) {
                if (busy) return@pointerInput
                detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                    change.consume()
                    latestSetHeight.value((latestHeight.value.coerceIn(minimumHeight, maxPanel) - amount / density).coerceIn(minimumHeight, maxPanel))
                    // Reopen during an upward drag so the preview follows the handle immediately.
                    latestSetExpanded.value(true)
                }, onDragEnd = {
                    if (colorPicking) return@detectVerticalDragGestures
                    val expanded = latestHeight.value >= 205f
                    latestSetExpanded.value(expanded)
                    if (!expanded) latestSetHeight.value(collapsedHeight)
                    else if (latestHeight.value < 275f) latestSetHeight.value(min(420f, maxPanel))
                })
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(AppGold))
            }
            if (!colorPicking) PhotoStrip(photos, currentIndex,
                { if (!locked) onChoosePhoto(it) }, { if (!locked) onRemovePhoto(it) }, { if (!locked) onAddPhotos() })
            if (colorTarget != null) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(colorTarget) {
                        ColorEditor(colorTarget, colors.choice(colorTarget), options?.automaticInk ?: android.graphics.Color.WHITE,
                            onColorChange, onCancelColor, onApplyColor, modifier = Modifier.fillMaxSize())
                    }
                }
            } else {
            if (panelExpanded) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(settingsScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (selectedTab) {
                ArtEditorTab.SIGNATURE -> {
                MaterialPickerRow(MaterialKind.SIGNATURE, materials, signatureId, false, !busy,
                    { it?.let(onSignature) }, onOpenMaterials)
                if (signatureId.isEmpty()) Text("请在素材库添加签名后保存", color = AppMuted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("签名大小")
                    Text("$signaturePercent%", color = AppGold)
                }
                Slider(value = signaturePercent.toFloat(),
                    onValueChange = { onSignaturePercent(it.roundToInt()) }, valueRange = 25f..200f,
                    enabled = !busy, modifier = Modifier.semantics { contentDescription = "签名大小" })
                ColorSetting(ColorTarget.SIGNATURE, colors.signature, !busy,
                    { onSelectColor(ColorTarget.SIGNATURE, it) }) { onOpenColor(ColorTarget.SIGNATURE) }
                }
                ArtEditorTab.LAYOUT -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("整体透明度")
                    Text("$opacity%", color = AppGold)
                }
                Slider(value = opacity.toFloat(), onValueChange = { onOpacity(it.roundToInt()) },
                    valueRange = 0f..100f, enabled = !busy)
                Text("水印位置", fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("上", "中", "下").forEachIndexed { index, label ->
                        PositionButton(label, vertical == index, !busy, Modifier.weight(1f)) { onVertical(index) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("左", "中", "右").forEachIndexed { index, label ->
                        PositionButton(label, horizontal == index, !busy, Modifier.weight(1f)) { onHorizontal(index) }
                    }
                }
                }
                ArtEditorTab.INFO -> {
                ColorSetting(ColorTarget.INFO, colors.info, !busy,
                    { onSelectColor(ColorTarget.INFO, it) }) { onOpenColor(ColorTarget.INFO) }
                val draft = entry.artDraft
                val metadata = entry.loaded.metadata
                InfoSection("机型", draft.showModel, { draft.showModel = it }, draft.modelManual,
                    draft::selectModelMode, metadata.model.ifBlank { "这张照片没有机型信息" },
                    draft.modelText, { draft.modelText = it })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("个性短句")
                    Switch(draft.showPhrase, { draft.showPhrase = it }, enabled = !busy)
                }
                if (draft.showPhrase) LimitedTextField("个性短句", draft.phrase) { draft.phrase = it }
                InfoSection("日期时间", draft.showDate, { draft.showDate = it }, draft.dateManual,
                    draft::selectDateMode, metadata.date.ifBlank { "这张照片没有拍摄日期" },
                    draft.dateText, { draft.dateText = it })
                InfoSection("地点", draft.showPlace, { draft.showPlace = it }, draft.placeManual,
                    draft::selectPlaceMode, "地点名称可手动填写", draft.placeText, { draft.placeText = it })
                }
                }
            }
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ArtEditorTab.entries.forEach { tab ->
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = selectedTab == tab, enabled = !busy, role = Role.Tab,
                            onClick = { selectedTab = tab }), contentAlignment = Alignment.Center) {
                        Text(tab.title, color = if (selectedTab == tab) AppGold else AppMuted,
                            fontWeight = if (selectedTab == tab) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun PositionButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Color(0xFF302A16) else Color.Transparent,
            contentColor = if (selected) AppGold else AppMuted)) { Text(label) }
}
