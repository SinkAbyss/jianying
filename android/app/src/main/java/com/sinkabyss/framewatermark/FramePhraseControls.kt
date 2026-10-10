package com.sinkabyss.framewatermark

import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun framePhraseTypeface(store: UserFontStore, id: String): Typeface =
    runCatching { store.typeface(id) }.getOrDefault(Typeface.SANS_SERIF)

@Composable
internal fun FramePhraseControls(style: WatermarkStyle, enabled: Boolean, onOpenFonts: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { UserMaterialStore(context) }
    val fonts by produceState(MaterialPixels.fonts, store) {
        value = withContext(Dispatchers.IO) {
            MaterialPixels.fonts + runCatching { store.fonts.read() }.getOrDefault(emptyList()).map { it.id to it.name }
        }
    }
    var fontPickerVisible by remember { mutableStateOf(false) }
    val selected = style.framePhraseFont.takeIf { id -> fonts.any { it.first == id } } ?: "sans"
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurfaceRaised)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("个性短句")
                Switch(style.framePhraseEnabled, { style.framePhraseEnabled = it }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "显示个性短句" })
            }
            if (style.framePhraseEnabled) {
                LimitedTextField("个性短句", style.framePhrase) { style.framePhrase = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ fontPickerVisible = true }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "选择短句字体" }) {
                        Text("字体 · ${fonts.firstOrNull { it.first == selected }?.second ?: "简洁"} ›")
                    }
                    TextButton(onOpenFonts, enabled = enabled) { Text("字体库") }
                }
            }
        }
    }
    if (fontPickerVisible) AlertDialog(onDismissRequest = { fontPickerVisible = false },
        title = { Text("选择短句字体") },
        text = {
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(fonts, key = { it.first }) { (id, name) ->
                    FontOptionCard(id, name, store, id == selected, enabled) {
                        style.framePhraseFont = id
                        fontPickerVisible = false
                    }
                }
            }
        }, confirmButton = { TextButton({ fontPickerVisible = false }) { Text("关闭") } })
}
