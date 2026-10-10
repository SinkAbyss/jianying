package com.sinkabyss.framewatermark

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val AppBackground = Color(0xFF111216)
internal val AppSurface = Color(0xFF1B1D23)
internal val AppSurfaceRaised = Color(0xFF24262D)
internal val AppGold = Color(0xFFE8B83B)
internal val AppMuted = Color(0xFF9AA0AA)
private val AppPalette = darkColorScheme(
    primary = AppGold,
    onPrimary = Color(0xFF201A0B),
    background = AppBackground,
    onBackground = Color(0xFFF5F4F0),
    surface = AppSurface,
    onSurface = Color(0xFFF5F4F0),
    surfaceVariant = AppSurfaceRaised,
    onSurfaceVariant = Color(0xFFD2D3D7),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppPalette) {
                Surface(Modifier.fillMaxSize(), color = AppBackground) {
                    FrameWatermarkApp()
                }
            }
        }
    }
}

internal enum class EditorTab(val title: String) {
    IMAGE("图像"), SIGNATURE("签名"), FRAME("边框"), INFO("信息"),
}

internal open class PhotoDraft(private val metadata: PhotoMetadata) {
    var showParameters by mutableStateOf(true)
    var parameterManual by mutableStateOf(false)
    var parameterManualInitialized by mutableStateOf(false)
    var parameterText by mutableStateOf("")
    val parameterFields = mutableStateMapOf<ParameterField, Boolean>().apply {
        ParameterField.entries.forEach { put(it, true) }
    }

    var showDate by mutableStateOf(true)
    var dateManual by mutableStateOf(false)
    var dateManualInitialized by mutableStateOf(false)
    var dateText by mutableStateOf("")

    var showPlace by mutableStateOf(false)
    var placeManual by mutableStateOf(false)
    var placeManualInitialized by mutableStateOf(false)
    var placeText by mutableStateOf("")

    var showModel by mutableStateOf(false)
    var modelManual by mutableStateOf(false)
    var modelManualInitialized by mutableStateOf(false)
    var modelText by mutableStateOf("")
    var missingPromptShown by mutableStateOf(false)

    fun automaticParameters(): String = metadata.parameterText(parameterFields.filterValues { it }.keys)
    fun currentParameters(): String = if (parameterManual) parameterText else automaticParameters()
    fun currentDate(): String = if (dateManual) dateText else metadata.date
    fun currentPlace(): String = if (placeManual) placeText else ""
    fun currentModel(): String = if (modelManual) modelText else metadata.model

    fun selectParameterMode(manual: Boolean) {
        if (manual && !parameterManualInitialized) {
            parameterText = automaticParameters()
            parameterManualInitialized = true
        }
        parameterManual = manual
    }

    fun selectDateMode(manual: Boolean) {
        if (manual && !dateManualInitialized) {
            dateText = metadata.date
            dateManualInitialized = true
        }
        dateManual = manual
    }

    fun selectPlaceMode(manual: Boolean) {
        if (manual && !placeManualInitialized) {
            placeText = ""
            placeManualInitialized = true
        }
        placeManual = manual
    }

    fun selectModelMode(manual: Boolean) {
        if (manual && !modelManualInitialized) {
            modelText = metadata.model
            modelManualInitialized = true
        }
        modelManual = manual
    }

    open fun missingEnabledFields(): List<String> = buildList {
        val parameterGroupActive = parameterManual || parameterFields.any { it.value }
        if (showParameters && parameterGroupActive && currentParameters().isBlank()) add("摄影参数")
        if (showDate && currentDate().isBlank()) add("日期")
        if (showPlace && currentPlace().isBlank()) add("地点")
        if (showModel && currentModel().isBlank()) add("机型")
    }
}

internal data class PhotoEntry(
    val loaded: LoadedPhoto,
    val draft: PhotoDraft = PhotoDraft(loaded.metadata),
    val artDraft: ArtPhotoDraft = ArtPhotoDraft(loaded.metadata),
    /** One analysis per photo, shared by the live preview and the JPEG export. */
    val analysis: PhotoColorAnalysis? = null,
    /** Appearance is per photo; see [WatermarkStyle]. */
    val style: WatermarkStyle = WatermarkStyle(),
)

internal data class SavedImage(val name: String, val width: Int, val height: Int, val relativePath: String)private data class MissingSaveConfirmation(val request: WatermarkSaveRequest, val fields: List<String>)
private data class LargeSaveConfirmation(val request: WatermarkSaveRequest, val warning: ImageTooLargeException)

@Composable
internal fun FrameWatermarkApp(initialPhotos: List<PhotoEntry> = emptyList(), initialArtSession: Boolean = false) {
    val context = LocalContext.current
    var dataEpoch by remember { mutableIntStateOf(0) }
    val recovery = remember(context, dataEpoch) { runCatching { DataBackupStore(context).recover() } }
    if (recovery.isFailure) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text("上次数据恢复未完成")
            Text("${recovery.exceptionOrNull()?.message ?: "请重试"}", color = AppMuted)
            Button({ dataEpoch++ }) { Text("重试") }
        }
        return
    }
    key(dataEpoch) { FrameWatermarkContent(initialPhotos, initialArtSession, onRestored = { dataEpoch++ }) }
}

@Composable
private fun FrameWatermarkContent(initialPhotos: List<PhotoEntry>, initialArtSession: Boolean, onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = remember { mutableStateListOf<PhotoEntry>().apply { addAll(initialPhotos) } }
    val materialStore = remember(context) { UserMaterialStore(context) }
    val initialMaterials = remember(materialStore) { runCatching { materialStore.available() } }
    var materials by remember { mutableStateOf(initialMaterials.getOrDefault(emptyList())) }
    var materialCreation by remember { mutableStateOf<MaterialCreation?>(null) }
    var materialToDelete by remember { mutableStateOf<UserMaterial?>(null) }
    var materialLibraryVisible by remember { mutableStateOf(false) }
    var dataManagementVisible by remember { mutableStateOf(false) }
    var materialLibraryKind by remember { mutableStateOf(MaterialKind.SIGNATURE) }
    var materialLibraryFontTab by remember { mutableStateOf(false) }
    var fontRevision by remember { mutableIntStateOf(0) }
    val frameMaterialPreferences = remember(context) { context.getSharedPreferences("frame-materials", Context.MODE_PRIVATE) }
    fun availableMaterial(id: String?, kind: MaterialKind): String? = id?.takeIf {
        materials.any { asset -> asset.id == it && asset.kind == kind }
    }
    fun firstSignature(excluding: String? = null): String = materials.firstOrNull {
        it.kind == MaterialKind.SIGNATURE && it.id != excluding
    }?.id.orEmpty()
    val recipes = remember(context) { mutableStateListOf<FrameRecipe>().apply { addAll(FrameRecipeStore.read(context)) } }
    val artRecipes = remember(context) { mutableStateListOf<ArtRecipe>().apply { addAll(ArtRecipeStore.read(context)) } }
    var editorVisible by remember { mutableStateOf(initialPhotos.isNotEmpty()) }
    var artSession by remember { mutableStateOf(initialArtSession) }
    val artPreferences = remember(context) { context.getSharedPreferences("art-signature", Context.MODE_PRIVATE) }
    /**
     * Style for a brand-new session: neutral geometry plus whichever materials were last used.
     * Photos added to an existing session inherit the open photo's style instead (see `picker`).
     */
    fun freshStyle() = WatermarkStyle().apply {
        circleId = availableMaterial(frameMaterialPreferences.getString("circle", null), MaterialKind.IMAGE)
        signatureId = if (artSession) availableMaterial(artPreferences.getString("signature", "sig-a"),
            MaterialKind.SIGNATURE) ?: firstSignature()
            else availableMaterial(frameMaterialPreferences.getString("signature", null), MaterialKind.SIGNATURE)
    }
    var colorTarget by remember { mutableStateOf<ColorTarget?>(null) }
    var colorBefore by remember { mutableStateOf(ForegroundColors()) }
    var currentPhotoIndex by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableStateOf(EditorTab.IMAGE) }
    var panelExpanded by remember { mutableStateOf(true) }
    var panelHeightDp by remember { mutableFloatStateOf(420f) }
    var isBusy by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var pickerStartsSession by remember { mutableStateOf(true) }
    var localPickerVisible by remember { mutableStateOf(false) }
    var recipeDialogVisible by remember { mutableStateOf(false) }
    var artRecipeDialogVisible by remember { mutableStateOf(false) }
    var missingSaveConfirmation by remember { mutableStateOf<MissingSaveConfirmation?>(null) }
    var largeSaveConfirmation by remember { mutableStateOf<LargeSaveConfirmation?>(null) }
    var savedImage by remember { mutableStateOf<SavedImage?>(null) }
    var errorMessage by remember { mutableStateOf(initialMaterials.exceptionOrNull()?.message) }
    // Batch save (P11): requests frozen once, outcomes accumulated across pauses, summary at the end.
    var batchRequests by remember { mutableStateOf<List<WatermarkSaveRequest>>(emptyList()) }
    var batchOutcomes by remember { mutableStateOf<List<BatchSaveOutcome>>(emptyList()) }
    var batchResumeIndex by remember { mutableIntStateOf(0) }
    var batchApprovedSamples by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var batchMissingConfirmation by remember { mutableStateOf<List<String>?>(null) }
    var batchWait by remember { mutableStateOf<BatchSaveWait?>(null) }
    var batchSummary by remember { mutableStateOf<List<BatchSaveOutcome>?>(null) }
    var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val currentEntry = photos.getOrNull(currentPhotoIndex)

    fun openColor(target: ColorTarget) {
        if (isBusy || colorTarget != null) return
        colorBefore = currentEntry?.style?.colors ?: ForegroundColors()
        colorTarget = target
        panelExpanded = true
    }
    fun changeColor(choice: ColorChoice) {
        val target = colorTarget ?: return
        currentEntry?.style?.let { it.colors = it.colors.withChoice(target, choice) }
    }
    fun cancelColor() {
        // Cancel only rolls back the target photo; other photos were never touched.
        currentEntry?.style?.colors = colorBefore
        colorTarget = null
    }

    fun chooseSignature(id: String?) {
        if (isSaving) return
        val preferences = if (artSession && editorVisible) artPreferences else frameMaterialPreferences
        val actualId = if (artSession && editorVisible) id ?: firstSignature() else id
        if (preferences.edit().putString("signature", actualId).commit()) {
            currentEntry?.style?.signatureId = actualId
        } else errorMessage = "签名选择保存失败，请重试。"
    }
    fun chooseCircle(id: String?) {
        if (isSaving) return
        if (frameMaterialPreferences.edit().putString("circle", id).commit()) {
            currentEntry?.style?.circleId = id
        } else errorMessage = "圆图选择保存失败，请重试。"
    }

    fun receivePhotos(uris: List<Uri>) {
        if (uris.isEmpty()) {
            isBusy = false
        } else {
            localPickerVisible = false
            scope.launch {
                isBusy = true
                // New session: uniform neutral style. Adding to an open session: every added photo
                // starts from the photo that was open, so a batch still begins visually uniform.
                val seed = if (pickerStartsSession) freshStyle()
                    else (photos.getOrNull(currentPhotoIndex)?.style?.copyOf() ?: freshStyle())
                val loaded = mutableListOf<PhotoEntry>()
                val failures = mutableListOf<String>()
                for (uri in uris) {
                    if (!pickerStartsSession && photos.any { it.loaded.uri == uri }) continue
                    runCatching {
                        val photo = PhotoLoader.loadForPreview(context, uri)
                        PhotoEntry(
                            loaded = photo,
                            analysis = withContext(Dispatchers.Default) { PhotoColorAnalyzer.analyze(photo.preview) },
                            style = seed.copyOf(),
                        )
                    }
                        .onSuccess { loaded += it }
                        .onFailure { failures += it.message ?: "有一张照片读取失败" }
                }
                if (pickerStartsSession) {
                    colorTarget = null
                    photos.clear()
                    currentPhotoIndex = 0
                    selectedTab = EditorTab.IMAGE
                    panelExpanded = true
                    panelHeightDp = 420f
                }
                photos.addAll(loaded)
                if (loaded.isNotEmpty()) editorVisible = true
                if (failures.isNotEmpty()) errorMessage = failures.joinToString("\n")
                isBusy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20),
    ) { receivePhotos(it) }

    fun openPicker(newSession: Boolean) {
        pickerStartsSession = newSession
        localPickerVisible = true
    }

    fun leaveEditor() {
        if (isSaving) return
        if (colorTarget != null) { cancelColor(); return }
        // Styles live on the photos, so dropping the session drops them with it.
        editorVisible = false
        photos.clear()
        currentPhotoIndex = 0
    }

    /** Drop one photo from the open session; the remaining photos keep their own styles. */
    fun removePhoto(index: Int) {
        if (isSaving || isBusy || colorTarget != null) return
        if (index !in photos.indices) return
        photos.removeAt(index)
        if (photos.isEmpty()) {
            leaveEditor()
            return
        }
        currentPhotoIndex = currentPhotoIndex.coerceAtMost(photos.lastIndex)
    }

    fun openMaterials(kind: MaterialKind) {
        if (isBusy || colorTarget != null) return
        materialLibraryKind = kind
        materialLibraryFontTab = false
        materialLibraryVisible = true
    }
    fun openFonts() {
        if (isBusy || colorTarget != null) return
        materialLibraryKind = MaterialKind.SIGNATURE
        materialLibraryFontTab = true
        materialLibraryVisible = true
    }
    BackHandler(enabled = !localPickerVisible && (editorVisible || materialLibraryVisible)) {
        if (materialLibraryVisible) materialLibraryVisible = false else leaveEditor()
    }

    // Material ids come from the open photo's own style, so every photo loads its own circle/signature.
    val styleCircleId = currentEntry?.style?.circleId
    val styleSignatureId = currentEntry?.style?.signatureId
    val activeSignatureId = styleSignatureId?.takeIf { it.isNotEmpty() }
    val loadedCircle by produceState<Pair<String?, Bitmap?>?>(null, styleCircleId, materials) {
        value = withContext(Dispatchers.IO) { styleCircleId to runCatching { materialStore.bitmap(styleCircleId) }.getOrNull() }
    }
    val loadedSignature by produceState<Pair<String?, Bitmap?>?>(null, activeSignatureId, materials) {
        value = withContext(Dispatchers.IO) { activeSignatureId to runCatching { materialStore.bitmap(activeSignatureId) }.getOrNull() }
    }
    val circleBitmap = loadedCircle?.takeIf { it.first == styleCircleId }?.second
    val signatureBitmap = loadedSignature?.takeIf { it.first == activeSignatureId }?.second
    val phraseFontIds = photos.filter { it.style.framePhraseEnabled }.map { it.style.framePhraseFont }.distinct()
    val phraseTypefaces by produceState<Map<String, Typeface>>(emptyMap(), phraseFontIds, fontRevision) {
        value = withContext(Dispatchers.IO) { phraseFontIds.associateWith { framePhraseTypeface(materialStore.fonts, it) } }
    }
    val materialLoading = (styleCircleId != null && loadedCircle?.first != styleCircleId) ||
        (activeSignatureId != null && loadedSignature?.first != activeSignatureId) ||
        (!artSession && phraseFontIds.any { it !in phraseTypefaces })
    LaunchedEffect(loadedCircle, loadedSignature, styleCircleId, activeSignatureId) {
        if (styleCircleId != null && loadedCircle?.first == styleCircleId && circleBitmap == null) {
            chooseCircle(null); errorMessage = "圆图素材无法读取，请重新导入。"
        }
        if (activeSignatureId != null && loadedSignature?.first == activeSignatureId && signatureBitmap == null) {
            chooseSignature(null); errorMessage = "签名素材无法读取，请重新制作或选择。"
        }
    }

    fun makeFrameOptions(entry: PhotoEntry): FrameOptions {
        val draft = entry.draft
        val style = entry.style
        return FrameOptions(
            layout = style.frameLayout,
            // The live preview only needs the open photo's materials; export re-reads per photo.
            circleBitmap = circleBitmap,
            circleSizePercent = style.circlePercent,
            signatureBitmap = signatureBitmap,
            signatureSizePercent = style.signaturePercent,
            parameterText = if (draft.showParameters) draft.currentParameters() else "",
            dateText = if (draft.showDate) draft.currentDate() else "",
            placeText = if (draft.showPlace) draft.currentPlace() else "",
            modelText = if (draft.showModel) draft.currentModel() else "",
            style = BorderStyleResolver.resolve(style.frameBackground, entry.analysis),
            colors = style.colors,
            phraseText = if (style.framePhraseEnabled) style.framePhrase else "",
            phraseTypeface = phraseTypefaces[style.framePhraseFont] ?: Typeface.SANS_SERIF,
        )
    }

    fun beginSave(request: WatermarkSaveRequest, allowDownsample: Boolean = false, approvedSampleSize: Int? = null) {
        if (isBusy || materialLoading || colorTarget != null) return
        // Lock synchronously, before launching, so repeated taps cannot start another save.
        isBusy = true
        isSaving = true
        scope.launch {
            try {
                val output = request.render(context, allowDownsample, approvedSampleSize)
                try {
                    savedImage = withContext(Dispatchers.IO) { MediaStoreWriter.save(context, output, request.photo.metadata.displayName) }
                } finally {
                    output.recycle()
                }
            } catch (tooLarge: ImageTooLargeException) {
                largeSaveConfirmation = LargeSaveConfirmation(request, tooLarge)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                errorMessage = failure.message ?: "保存失败。原图未被修改。"
            } finally {
                isSaving = false
                isBusy = false
            }
        }
    }

    fun requestSave(entry: PhotoEntry) {
        if (isBusy || materialLoading || colorTarget != null) return
        if (artSession && signatureBitmap == null) { errorMessage = "请先选择或添加签名"; return }
        val request: WatermarkSaveRequest = if (artSession) {
            ArtSignatureSaveRequest.capture(entry, requireNotNull(signatureBitmap))
        } else FrameSaveRequest.capture(entry.loaded) { makeFrameOptions(entry) }
        val activeDraft = if (artSession) entry.artDraft else entry.draft
        val missing = activeDraft.missingEnabledFields()
        if (missing.isNotEmpty() && !activeDraft.missingPromptShown) {
            activeDraft.missingPromptShown = true
            missingSaveConfirmation = MissingSaveConfirmation(request, missing)
        } else {
            beginSave(request)
        }
    }

    fun runBatch(
        allowDownsample: Boolean,
        priorOutcomes: List<BatchSaveOutcome> = batchOutcomes,
        startIndex: Int = batchResumeIndex,
        approvals: Map<Int, Int> = batchApprovedSamples,
    ) {
        // Lock synchronously, before launching, so repeated taps cannot start another batch.
        isBusy = true
        isSaving = true
        scope.launch {
            try {
                val total = batchRequests.size
                batchProgress = startIndex to total
                val result = BatchSaver.run(
                    context = context,
                    requests = batchRequests,
                    startIndex = startIndex,
                    priorOutcomes = priorOutcomes,
                    allowDownsample = allowDownsample,
                    approvedSamples = approvals,
                )
                batchOutcomes = result.outcomes
                batchApprovedSamples = approvals
                batchProgress = null
                if (result.waiting != null) {
                    batchWait = result.waiting
                    batchResumeIndex = result.waiting.index
                } else {
                    batchSummary = result.outcomes
                    batchRequests = emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                errorMessage = failure.message ?: "批量保存失败。原图未被修改。"
            } finally {
                isSaving = false
                isBusy = false
                batchProgress = null
            }
        }
    }

    /** Freeze the whole batch once, then run it; the same requests survive memory pauses. */
    fun requestBatchSave() {
        if (isBusy || materialLoading || colorTarget != null) return
        if (photos.size < 2) return
        if (artSession && signatureBitmap == null) { errorMessage = "请先选择或添加签名"; return }
        val requests = photos.map { entry ->
            if (artSession) {
                ArtSignatureSaveRequest.capture(entry, requireNotNull(signatureBitmap))
            } else FrameSaveRequest.capture(entry.loaded) { makeFrameOptions(entry) }
        }
        val missing = photos.mapIndexedNotNull { index, entry ->
            val fields = (if (artSession) entry.artDraft else entry.draft).missingEnabledFields()
            if (fields.isEmpty()) null else "第 ${index + 1} 张：${fields.joinToString("、")}"
        }
        batchRequests = requests
        batchResumeIndex = 0
        batchOutcomes = emptyList()
        batchApprovedSamples = emptyMap()
        if (missing.isNotEmpty()) batchMissingConfirmation = missing else runBatch(false)
    }

    if (batchWait != null) {
        val wait = batchWait!!
        val skipWait = wait.warning.width to wait.warning.height
        AlertDialog(
            onDismissRequest = {},
            title = { Text("第 ${wait.index + 1} 张内存不足") },
            text = { Text("第 ${wait.index + 1} 张 ${skipWait.first} × ${skipWait.second} 导出时内存分配失败。可以只对它降采样，或对这张和后面所有照片都按 ${wait.warning.sampleSize}× 降采样；也可以跳过这张继续保存其余照片。原照片都不会被修改。") },
            confirmButton = {
                TextButton(onClick = {
                    val approvals = batchApprovedSamples + (wait.index to wait.warning.sampleSize)
                    batchWait = null
                    runBatch(true, startIndex = wait.index, approvals = approvals)
                }) { Text("这张降采样") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val approvals = (wait.index until batchRequests.size).associateWith { wait.warning.sampleSize }
                        batchWait = null
                        runBatch(true, startIndex = wait.index, approvals = approvals)
                    }) { Text("全部降采样") }
                    TextButton(onClick = {
                        val skipped = batchOutcomes + BatchSaveOutcome(
                            wait.index, batchRequests.getOrNull(wait.index)?.photo?.metadata?.displayName ?: "",
                            null, "内存不足，已跳过")
                        batchWait = null
                        val next = wait.index + 1
                        if (next >= batchRequests.size) {
                            batchSummary = skipped
                            batchRequests = emptyList()
                        } else {
                            batchResumeIndex = next
                            // Skipping keeps the originals for the remaining photos: they are still
                            // tried at full size and will ask on their own if they cannot fit.
                            runBatch(false, priorOutcomes = skipped, startIndex = next, approvals = emptyMap())
                        }
                    }) { Text("跳过这张") }
                }
            },
        )
    }

    batchMissingConfirmation?.let { missing ->
        AlertDialog(
            onDismissRequest = { batchMissingConfirmation = null },
            title = { Text("有些信息还没有内容") },
            text = { Text("${missing.joinToString("\n")}\n\n可以取消回去补充，也可以按当前设置继续保存全部照片。") },
            confirmButton = {
                TextButton(onClick = { batchMissingConfirmation = null; runBatch(false) }) { Text("仍然全部保存") }
            },
            dismissButton = { TextButton(onClick = { batchMissingConfirmation = null }) { Text("继续编辑") } },
        )
    }

    batchSummary?.let { outcomes ->
        val succeeded = outcomes.count { it.succeeded }
        val failed = outcomes.filter { !it.succeeded }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("批量另存完成") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("共 ${outcomes.size} 张：成功 $succeeded 张，失败 ${failed.size} 张。原图都未覆盖。")
                    failed.forEach { outcome ->
                        Text("• ${outcome.displayName}：${outcome.error}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    outcomes.filter { it.succeeded }.forEach { outcome ->
                        val saved = outcome.saved!!
                        Text("• ${saved.width} × ${saved.height}　${saved.name}", color = AppMuted, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { batchSummary = null }) { Text("完成") }
            },
        )
    }

    if (dataManagementVisible) {
        DataManagementScreen(onBack = { dataManagementVisible = false }, onRestored = onRestored)
    } else if (localPickerVisible) {
        LocalPhotoPicker(
            onCancel = { localPickerVisible = false },
            onConfirm = ::receivePhotos,
            onSystemPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    } else if (materialLibraryVisible) {
        UserMaterialLibraryScreen(materials, materialStore, materialLibraryKind, editorVisible,
            allowImages = !artSession || !editorVisible,
            onBack = { materialLibraryVisible = false },
            onCreate = { materialCreation = it }, onDelete = { materialToDelete = it },
            onFontsChanged = { materials = materialStore.available(); fontRevision++ },
            initialFontTab = materialLibraryFontTab,
            onUse = { asset ->
                if (asset.kind == MaterialKind.IMAGE) chooseCircle(asset.id) else chooseSignature(asset.id)
                materialLibraryVisible = false
            })
    } else if (editorVisible && currentEntry != null && artSession) {
        ArtSignatureScreen(
            entry = currentEntry,
            photos = photos,
            currentIndex = currentPhotoIndex,
            signatureId = activeSignatureId.orEmpty(),
            signature = signatureBitmap,
            horizontal = currentEntry.style.artHorizontal,
            vertical = currentEntry.style.artVertical,
            opacity = currentEntry.style.artOpacity,
            signaturePercent = currentEntry.style.artSignaturePercent,
            onSignaturePercent = { if (!isBusy) currentEntry.style.artSignaturePercent = it.coerceIn(25, 200) },
            busy = isBusy || materialLoading,
            onBack = ::leaveEditor,
            onSave = { requestSave(currentEntry) },
            onSignature = { chooseSignature(it) },
            materials = materials,
            onOpenMaterials = ::openMaterials,
            panelExpanded = panelExpanded,
            panelHeightDp = panelHeightDp,
            onPanelExpandedChanged = { if (!isBusy) panelExpanded = it },
            onPanelHeightChanged = { if (!isBusy) panelHeightDp = it },
            colors = currentEntry.style.colors, colorTarget = colorTarget, onOpenColor = ::openColor,
            onSelectColor = { target, choice ->
                if (!isBusy) currentEntry.style.colors = currentEntry.style.colors.withChoice(target, choice)
            },
            onColorChange = ::changeColor, onCancelColor = ::cancelColor, onApplyColor = { colorTarget = null },
            onHorizontal = { if (!isSaving) currentEntry.style.artHorizontal = it },
            onVertical = { if (!isSaving) currentEntry.style.artVertical = it },
            onOpacity = { if (!isSaving) currentEntry.style.artOpacity = it },
            onChoosePhoto = { if (!isSaving) currentPhotoIndex = it.coerceIn(0, photos.lastIndex) },
            onAddPhotos = { if (!isSaving) openPicker(false) },
            onRemovePhoto = ::removePhoto,
            onRecipes = { artRecipeDialogVisible = true },
            onSaveAll = if (photos.size > 1) ({ requestBatchSave() }) else null,
        )
    } else if (editorVisible && currentEntry != null && !artSession) {
        EditorScreen(
            entry = currentEntry,
            photos = photos,
            currentIndex = currentPhotoIndex,
            layout = currentEntry.style.frameLayout,
            selectedBackground = currentEntry.style.frameBackground,
            selectedCircleId = currentEntry.style.circleId,
            circleBitmap = circleBitmap,
            circleSizePercent = currentEntry.style.circlePercent,
            selectedSignatureId = currentEntry.style.signatureId,
            signatureBitmap = signatureBitmap,
            signatureSizePercent = currentEntry.style.signaturePercent,
            selectedTab = selectedTab,
            colors = currentEntry.style.colors, colorTarget = colorTarget, onOpenColor = ::openColor,
            onColorDirect = { target, choice ->
                if (!isBusy) currentEntry.style.colors = currentEntry.style.colors.withChoice(target, choice)
            },
            onColorChange = ::changeColor, onCancelColor = ::cancelColor, onApplyColor = { colorTarget = null },
            panelExpanded = panelExpanded,
            panelHeightDp = panelHeightDp,
            isBusy = isBusy || materialLoading,
            materials = materials,
            onOpenMaterials = ::openMaterials,
            onBack = ::leaveEditor,
            onOpenRecipes = { recipeDialogVisible = true },
            onSave = { requestSave(currentEntry) },
            onSaveAll = if (photos.size > 1) ({ requestBatchSave() }) else null,
            onAddPhotos = { if (!isSaving) openPicker(false) },
            onRemovePhoto = ::removePhoto,
            onChoosePhoto = { index -> if (!isSaving) currentPhotoIndex = index.coerceIn(0, photos.lastIndex) },
            onLayoutChanged = { if (!isSaving) currentEntry.style.frameLayout = it },
            onBackgroundChanged = { if (!isSaving) currentEntry.style.frameBackground = it },
            onCircleChanged = ::chooseCircle,
            onCircleSizeChanged = { if (!isSaving) currentEntry.style.circlePercent = it },
            onSignatureChanged = ::chooseSignature,
            onSignatureSizeChanged = { if (!isSaving) currentEntry.style.signaturePercent = it },
            onTabChanged = { if (!isSaving) selectedTab = it },
            phraseTypeface = phraseTypefaces[currentEntry.style.framePhraseFont] ?: Typeface.SANS_SERIF,
            onOpenFonts = ::openFonts,
            onPanelExpandedChanged = { if (!isSaving) panelExpanded = it },
            onPanelHeightChanged = { if (!isSaving) panelHeightDp = it },
        )
    } else if (editorVisible && artSession) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LibraryScreen(
            onPickPhotos = { artSession = false; openPicker(true) },
            onPickArtPhotos = { artSession = true; openPicker(true) },
            onOpenMaterials = { openMaterials(MaterialKind.SIGNATURE) },
            onOpenData = { dataManagementVisible = true },
            isBusy = isBusy,
        )
    }

    materialCreation?.let { creation ->
        MaterialCreationDialogs(creation, materialStore, onDismiss = { materialCreation = null },
            onAdded = { asset ->
                materials = materialStore.available()
                if (editorVisible && (asset.kind == MaterialKind.SIGNATURE || !artSession)) {
                    if (asset.kind == MaterialKind.IMAGE) chooseCircle(asset.id) else chooseSignature(asset.id)
                    materialLibraryVisible = false
                }
                materialCreation = null
            }, textPreview = { draftSignature, modifier ->
                if (editorVisible && currentEntry != null) {
                    val photo = currentEntry.loaded
                    SignatureCreationPhotoPreview(photo.preview, photo.metadata.width, photo.metadata.height,
                        if (artSession) null else makeFrameOptions(currentEntry).copy(signatureBitmap = draftSignature),
                        if (artSession) draftSignature?.let {
                            currentEntry.artDraft.options(it, currentEntry.style.artHorizontal,
                                currentEntry.style.artVertical, currentEntry.style.artOpacity,
                                currentEntry.style.colors, currentEntry.style.artSignaturePercent)
                        } else null,
                        modifier)
                } else MaterialDemoPreview(draftSignature, modifier)
            })
    }
    materialToDelete?.let { asset ->
        AlertDialog(onDismissRequest = { materialToDelete = null },
            title = { Text("删除“${asset.name}”？") },
            text = { Text("当前水印及配方会移除这份素材。输入的原始图片保持不变。") },
            dismissButton = { TextButton({ materialToDelete = null }) { Text("取消") } },
            confirmButton = { TextButton({
                runCatching {
                    val cleaned = recipes.map { it.copy(circleId = it.circleId?.takeUnless { id -> id == asset.id },
                        signatureId = it.signatureId?.takeUnless { id -> id == asset.id }) }
                    FrameRecipeStore.writeChecked(context, cleaned)
                    val nextArt = artPreferences.getString("signature", "sig-a")
                        ?.takeUnless { it == asset.id } ?: firstSignature(asset.id)
                    check(artPreferences.edit().putString("signature", nextArt).commit()) { "签名选择更新失败，暂未删除。" }
                    val calendar = artRecipes.map { it.copy(
                        signatureId = it.signatureId.takeUnless { id -> id == asset.id }
                            ?: firstSignature(asset.id)) }
                    ArtRecipeStore.writeChecked(context, calendar)
                    materialStore.delete(asset.id)
                    recipes.clear(); recipes.addAll(cleaned)
                    artRecipes.clear(); artRecipes.addAll(calendar)
                    // Every photo carries its own material references, so clear them all; a preference
                    // is kept only while some remaining photo still points at it.
                    val remaining = photos.map { entry ->
                        val style = entry.style
                        val circle = style.circleId?.takeUnless { it == asset.id }
                        val signature = if (artSession && style.signatureId == asset.id) firstSignature(asset.id)
                            else style.signatureId?.takeUnless { it == asset.id }
                        style.circleId = circle
                        style.signatureId = signature
                        entry
                    }
                    photos.clear(); photos.addAll(remaining)
                    check(frameMaterialPreferences.edit()
                        .putString("circle", remaining.firstNotNullOfOrNull { it.style.circleId })
                        .putString("signature", remaining.firstNotNullOfOrNull { it.style.signatureId })
                        .commit()) { "素材选择更新失败，暂未删除。" }
                    materials = materialStore.available()
                }.onFailure { errorMessage = it.message ?: "素材删除失败" }
                materialToDelete = null
            }) { Text("删除") } })
    }

    if (artRecipeDialogVisible) {
        RecipeDialog(
            title = "艺术签名配方",
            description = "配方保存九宫格位置、整体透明度、四个信息开关，以及签名和信息文字两组颜色（含“自动”状态）；不保存照片或手动文字。",
            recipes = artRecipes,
            name = { it.name },
            label = ::artRecipeLabel,
            onDismiss = { artRecipeDialogVisible = false },
            onSave = { name ->
                val cleanName = TextRules.limit(name.trim().ifBlank { "艺术签名配方 ${artRecipes.size + 1}" }, 50)
                val selectedSignature = currentEntry?.style?.signatureId.orEmpty()
                if (selectedSignature.isEmpty()) {
                    errorMessage = "请先选择或添加签名"
                } else {
                    val recipe = ArtRecipe(
                        name = cleanName,
                        signatureId = selectedSignature,
                        horizontal = currentEntry?.style?.artHorizontal ?: 1,
                        vertical = currentEntry?.style?.artVertical ?: 2,
                        opacity = currentEntry?.style?.artOpacity ?: 100,
                        signaturePercent = currentEntry?.style?.artSignaturePercent ?: 100,
                        showModel = currentEntry?.artDraft?.showModel ?: false,
                        showPhrase = currentEntry?.artDraft?.showPhrase ?: false,
                        showDate = currentEntry?.artDraft?.showDate ?: false,
                        showPlace = currentEntry?.artDraft?.showPlace ?: false,
                        colors = currentEntry?.style?.colors ?: ForegroundColors(),
                    )
                    runCatching { ArtRecipeStore.writeChecked(context, artRecipes + recipe, "配方保存失败，请重试。") }
                        .onSuccess { artRecipes += recipe }
                        .onFailure { errorMessage = it.message ?: "配方保存失败，请重试。" }
                }
            },
            onApply = { recipe ->
                // Applying a preset changes only the open photo; other photos keep their own style.
                currentEntry?.style?.let { style ->
                    style.artHorizontal = recipe.horizontal
                    style.artVertical = recipe.vertical
                    style.artOpacity = recipe.opacity
                    style.artSignaturePercent = recipe.signaturePercent
                    style.colors = recipe.colors
                }
                chooseSignature(availableMaterial(recipe.signatureId, MaterialKind.SIGNATURE))
                currentEntry?.artDraft?.let { draft ->
                    draft.showModel = recipe.showModel
                    draft.showPhrase = recipe.showPhrase
                    draft.showDate = recipe.showDate
                    draft.showPlace = recipe.showPlace
                }
                artRecipeDialogVisible = false
            },
            onDelete = { recipe ->
                runCatching { ArtRecipeStore.writeChecked(context, artRecipes - recipe, "配方删除失败，请重试。") }
                    .onSuccess { artRecipes.remove(recipe) }
                    .onFailure { errorMessage = it.message ?: "配方删除失败，请重试。" }
            },
        )
    }

    if (recipeDialogVisible) {
        RecipeDialog(
            title = "边框配方",
            description = "配方保存边框形式、背景、圆图与签名选择和大小，以及签名和信息文字的默认/自定义颜色；不保存照片或手动文字。",
            recipes = recipes,
            name = { it.name },
            label = ::frameRecipeLabel,
            onDismiss = { recipeDialogVisible = false },
            onSave = { name ->
                val cleanName = TextRules.limit(name.trim().ifBlank { "边框配方 ${recipes.size + 1}" }, 50)
                val recipe = FrameRecipe(
                    name = cleanName,
                    layout = currentEntry?.style?.frameLayout ?: FrameLayoutKind.BAR,
                    circleId = currentEntry?.style?.circleId,
                    circlePercent = currentEntry?.style?.circlePercent ?: 100,
                    signatureId = currentEntry?.style?.signatureId,
                    signaturePercent = currentEntry?.style?.signaturePercent ?: 100,
                    background = currentEntry?.style?.frameBackground ?: BorderBackgroundMode.DEFAULT,
                    colors = currentEntry?.style?.colors ?: ForegroundColors(),
                    phraseEnabled = currentEntry?.style?.framePhraseEnabled ?: false,
                    phraseFont = currentEntry?.style?.framePhraseFont ?: "sans",
                )
                runCatching { FrameRecipeStore.writeChecked(context, recipes + recipe, "配方保存失败，请重试。") }
                    .onSuccess { recipes += recipe }
                    .onFailure { errorMessage = it.message ?: "配方保存失败，请重试。" }
            },
            onApply = { recipe ->
                // Applying a preset changes only the open photo; other photos keep their own style.
                currentEntry?.style?.let { style ->
                    style.frameLayout = recipe.layout
                    style.frameBackground = recipe.background
                    style.colors = recipe.colors
                    style.circlePercent = recipe.circlePercent
                    style.signaturePercent = recipe.signaturePercent
                    style.framePhraseEnabled = recipe.phraseEnabled
                    style.framePhraseFont = recipe.phraseFont
                }
                chooseCircle(availableMaterial(recipe.circleId, MaterialKind.IMAGE))
                chooseSignature(availableMaterial(recipe.signatureId, MaterialKind.SIGNATURE))
                recipeDialogVisible = false
            },
            onDelete = { recipe ->
                runCatching { FrameRecipeStore.writeChecked(context, recipes - recipe, "配方删除失败，请重试。") }
                    .onSuccess { recipes.remove(recipe) }
                    .onFailure { errorMessage = it.message ?: "配方删除失败，请重试。" }
            },
        )
    }

    missingSaveConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { missingSaveConfirmation = null },
            title = { Text("有些信息还没有内容") },
            text = { Text("当前照片缺少：${confirmation.fields.joinToString("、")}。你可以取消保存继续编辑，也可以按当前设置继续保存。") },
            confirmButton = { TextButton(onClick = { missingSaveConfirmation = null; beginSave(confirmation.request) }) { Text("仍然保存") } },
            dismissButton = { TextButton(onClick = { missingSaveConfirmation = null }) { Text("继续编辑") } },
        )
    }

    largeSaveConfirmation?.let { confirmation ->
        val warning = confirmation.warning
        AlertDialog(
            onDismissRequest = { largeSaveConfirmation = null },
            title = { Text("这次导出内存不足") },
            text = { Text("已尝试导出这张 ${warning.width} × ${warning.height} 的照片，但内存分配失败。继续将按 ${warning.sampleSize}× 降采样另存，保留照片比例；原照片不会修改。") },
            confirmButton = { TextButton(onClick = { largeSaveConfirmation = null; beginSave(confirmation.request, true, warning.sampleSize) }) { Text("降采样另存") } },
            dismissButton = { TextButton(onClick = { largeSaveConfirmation = null }) { Text("取消") } },
        )
    }

    savedImage?.let { saved ->
        AlertDialog(
            onDismissRequest = { savedImage = null },
            title = { Text("已另存新照片") },
            text = { Text("${saved.name}\n${saved.width} × ${saved.height}\n${saved.relativePath}\n\n原图未覆盖。") },
            confirmButton = { TextButton(onClick = { savedImage = null }) { Text("完成") } },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("无法完成操作") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("知道了") } },
        )
    }
    if (isSaving) SavingProgressDialog()
}

@Composable
internal fun SavingProgressDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = AppSurfaceRaised,
                contentColor = Color(0xFFF5F4F0), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = AppGold, strokeWidth = 2.5.dp)
                    Text("正在保存…", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

internal object MediaStoreWriter {
    fun save(context: Context, bitmap: Bitmap, sourceName: String): SavedImage {
        val baseName = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .trim('_')
            .take(48)
            .ifBlank { "照片" }
        val name = "${baseName}_简影_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.jpg"
        val relativePath = "${Environment.DIRECTORY_PICTURES}/简影边框"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("相册无法创建新照片")
        try {
            val wrote = context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output)
            } ?: false
            if (!wrote) throw IllegalStateException("JPEG 写入失败")
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (context.contentResolver.update(uri, ready, null, null) <= 0) {
                throw IllegalStateException("照片已写入，但无法发布到相册")
            }
            return SavedImage(name, bitmap.width, bitmap.height, relativePath)
        } catch (failure: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw failure
        }
    }
}

@Composable
private fun LibraryScreen(onPickPhotos: () -> Unit, onPickArtPhotos: () -> Unit, onOpenMaterials: () -> Unit,
    onOpenData: () -> Unit, isBusy: Boolean) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("水印库", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                OutlinedButton(onOpenMaterials, enabled = !isBusy) { Text("素材库") }
            }
            Spacer(Modifier.height(6.dp))
            Text("选择一种水印，开始编辑照片", color = AppMuted, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            LibraryWatermarkCard("艺术签名", "签名融入画面，留下你的印记", true, !isBusy, onPickArtPhotos)
            LibraryWatermarkCard("个性边框", "边框、签名与摄影信息，自由搭配", false, !isBusy, onPickPhotos)
            Text("点击卡片选择照片 · 预览为样式示意", color = AppMuted,
                fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
        }
        if (isBusy) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = AppGold, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在读取照片…", color = AppMuted, fontSize = 12.sp)
            }
        }
        TextButton(onOpenData, enabled = !isBusy, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
            Text("数据管理")
        }
    }
}

@Composable
private fun LibraryWatermarkCard(title: String, description: String, art: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "选择照片，使用$title", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color(0xFF30323A)),
    ) {
        Column(Modifier.padding(10.dp)) {
            LibraryWatermarkPreview(art, Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(description, color = AppMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF302A16)),
                    contentAlignment = Alignment.Center) {
                    Text("›", color = AppGold, fontSize = 26.sp)
                }
            }
        }
    }
}

@Composable
private fun LibraryWatermarkPreview(art: Boolean, modifier: Modifier) {
    val resources = LocalResources.current
    val photo = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.landscape_demo) }
    val signature = remember(resources) { BitmapFactory.decodeResource(resources, R.drawable.sig_a) }
    val geometry = remember(photo) {
        FrameGeometry.calculate(photo.width, photo.height, FrameLayoutKind.BAR, outputLongEdge = 2_000)
    }
    val width = if (art) photo.width else geometry.canvasWidth
    val height = if (art) photo.height else geometry.canvasHeight
    val options = remember(signature) {
        FrameOptions(signatureBitmap = signature, parameterText = "35 mm  f/2.8  1/250 s  ISO 100")
    }
    androidx.compose.foundation.Canvas(modifier.aspectRatio(width.toFloat() / height)) {
        drawIntoCanvas { drawing ->
            val canvas = drawing.nativeCanvas
            val checkpoint = canvas.save()
            try {
                canvas.scale(size.width / width, size.height / height)
                if (art) ArtSignatureRenderer.draw(canvas, photo, width, height,
                    ArtSignatureOptions(signature, phrase = "把此刻留在光里"))
                else FrameRenderer.draw(canvas, photo, geometry, options)
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }
    }
}

@Composable
internal fun EditorScreen(
    entry: PhotoEntry,
    photos: List<PhotoEntry>,
    currentIndex: Int,
    layout: FrameLayoutKind,
    selectedBackground: BorderBackgroundMode,
    selectedCircleId: String?,
    circleBitmap: Bitmap?,
    circleSizePercent: Int,
    selectedSignatureId: String?,
    signatureBitmap: Bitmap?,
    signatureSizePercent: Int,
    selectedTab: EditorTab,
    panelExpanded: Boolean,
    panelHeightDp: Float,
    isBusy: Boolean,
    materials: List<UserMaterial>,
    onOpenMaterials: (MaterialKind) -> Unit,
    onBack: () -> Unit,
    onOpenRecipes: () -> Unit,
    onSave: () -> Unit,
    onSaveAll: (() -> Unit)? = null,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (Int) -> Unit,
    onChoosePhoto: (Int) -> Unit,
    onLayoutChanged: (FrameLayoutKind) -> Unit,
    onBackgroundChanged: (BorderBackgroundMode) -> Unit,
    colors: ForegroundColors, colorTarget: ColorTarget?, onOpenColor: (ColorTarget) -> Unit,
    onColorChange: (ColorChoice) -> Unit, onCancelColor: () -> Unit, onApplyColor: () -> Unit,
    onColorDirect: (ColorTarget, ColorChoice) -> Unit = { _, _ -> },
    onCircleChanged: (String?) -> Unit,
    onCircleSizeChanged: (Int) -> Unit,
    onSignatureChanged: (String?) -> Unit,
    onSignatureSizeChanged: (Int) -> Unit,
    onTabChanged: (EditorTab) -> Unit,
    onPanelExpandedChanged: (Boolean) -> Unit,
    onPanelHeightChanged: (Float) -> Unit,
    phraseTypeface: Typeface = Typeface.SANS_SERIF,
    onOpenFonts: () -> Unit = { onOpenMaterials(MaterialKind.SIGNATURE) },
) {
    // The preview and the export resolve the style from this same analysis and mode.
    val style = remember(selectedBackground, entry.analysis) {
        BorderStyleResolver.resolve(selectedBackground, entry.analysis)
    }
    val options = FrameOptions(
        layout = layout,
        circleBitmap = circleBitmap,
        circleSizePercent = circleSizePercent,
        signatureBitmap = signatureBitmap,
        signatureSizePercent = signatureSizePercent,
        parameterText = if (entry.draft.showParameters) entry.draft.currentParameters() else "",
        dateText = if (entry.draft.showDate) entry.draft.currentDate() else "",
        placeText = if (entry.draft.showPlace) entry.draft.currentPlace() else "",
        modelText = if (entry.draft.showModel) entry.draft.currentModel() else "",
        style = style,
        colors = colors,
        phraseText = if (entry.style.framePhraseEnabled) entry.style.framePhrase else "",
        phraseTypeface = phraseTypeface,
    )
    val locked = isBusy || colorTarget != null

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val headerHeight = 62f
        val minPanel = if (colorTarget != null) ColorPanelMinimumHeight else 148f
        val maxPanel = (maxHeight.value - headerHeight - 130f).coerceAtLeast(minPanel)
        val actualPanelHeight = panelHeightDp.coerceIn(minPanel, maxPanel)
        val animatedHeight by animateDpAsState(actualPanelHeight.dp, label = "panel-height")
        val density = LocalDensity.current.density
        val latestPanelHeight = rememberUpdatedState(panelHeightDp)
        val latestSetPanelHeight = rememberUpdatedState(onPanelHeightChanged)
        val latestSetPanelExpanded = rememberUpdatedState(onPanelExpandedChanged)
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(
                busy = isBusy,
                interactionEnabled = !locked,
                onBack = onBack,
                onRecipes = onOpenRecipes,
                onSave = onSave,
                onSaveAll = onSaveAll,
            )
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF08090B))) {
                LivePhotoPreview(entry, options, Modifier.fillMaxSize())
            }
            Column(
                Modifier.fillMaxWidth().height(animatedHeight).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(AppSurface),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(38.dp)
                        .semantics { contentDescription = "调整编辑面板高度" }
                        .pointerInput(maxPanel, density, isBusy, colorTarget) {
                            if (isBusy) return@pointerInput
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    latestSetPanelHeight.value((latestPanelHeight.value.coerceIn(minPanel, maxPanel) - dragAmount / density).coerceIn(minPanel, maxPanel))
                                },
                                onDragEnd = {
                                    if (colorTarget != null) {
                                        latestSetPanelExpanded.value(true)
                                        return@detectVerticalDragGestures
                                    }
                                    val expanded = latestPanelHeight.value >= 205f
                                    latestSetPanelExpanded.value(expanded)
                                    if (expanded && latestPanelHeight.value < 275f) latestSetPanelHeight.value(min(420f, maxPanel))
                                    if (!expanded) latestSetPanelHeight.value(148f)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(AppGold))
                    }
                }
                if (colorTarget != null) {
                    key(colorTarget) {
                        ColorEditor(colorTarget, colors.choice(colorTarget),
                            if (colorTarget == ColorTarget.SIGNATURE) style.foreground.signature else style.foreground.primary,
                            onColorChange, onCancelColor, onApplyColor,
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 15.dp, vertical = 6.dp))
                    }
                } else {
                    PhotoStrip(photos, currentIndex,
                        { if (!locked) onChoosePhoto(it) }, { if (!locked) onRemovePhoto(it) }, { if (!locked) onAddPhotos() })
                    if (panelExpanded) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal = 15.dp, vertical = 6.dp)) {
                        when (selectedTab) {
                            EditorTab.IMAGE -> ImageControls(selectedCircleId, circleSizePercent, onCircleChanged, onCircleSizeChanged,
                                materials, !isBusy, onOpenMaterials)
                            EditorTab.SIGNATURE -> Column {
                                ColorSetting(ColorTarget.SIGNATURE, colors.signature, !isBusy,
                                    onSelect = { onSelect -> onColorDirect(ColorTarget.SIGNATURE, onSelect) },
                                    onOpen = { onOpenColor(ColorTarget.SIGNATURE) })
                                SignatureControls(
                                selectedSignatureId,
                                signatureSizePercent,
                                onSignatureChanged,
                                onSignatureSizeChanged,
                                materials, !isBusy, onOpenMaterials,
                            )
                            }
                            EditorTab.FRAME -> FrameControls(
                                layout = layout,
                                onLayoutChanged = onLayoutChanged,
                                selectedBackground = selectedBackground,
                                onBackgroundChanged = onBackgroundChanged,
                                analysis = entry.analysis,
                                preview = entry.loaded.preview,
                            )
                            EditorTab.INFO -> Column {
                                ColorSetting(ColorTarget.INFO, colors.info, !isBusy,
                                    onSelect = { onSelect -> onColorDirect(ColorTarget.INFO, onSelect) },
                                    onOpen = { onOpenColor(ColorTarget.INFO) })
                                FramePhraseControls(entry.style, !isBusy, onOpenFonts)
                                InfoControls(entry.draft, entry.loaded.metadata)
                            }
                        }
                        }
                    }
                EditorTabBar(selectedTab) { if (!locked) onTabChanged(it) }
                }
            }
        }
    }
}

@Composable
internal fun EditorTopBar(busy: Boolean, onBack: () -> Unit, onSave: () -> Unit,
    onRecipes: (() -> Unit)? = null, title: String = "个性边框", canSave: Boolean = true,
    helpKind: FillingHelpKind = FillingHelpKind.FRAME, onSaveAll: (() -> Unit)? = null,
    interactionEnabled: Boolean = true) {
    val actionsEnabled = !busy && interactionEnabled
    Box(Modifier.fillMaxWidth().background(AppBackground)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, enabled = actionsEnabled,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("‹ 水印库", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                if (onRecipes != null) TextButton(onClick = onRecipes, enabled = actionsEnabled) {
                    Text("配方", color = AppGold, fontSize = 12.sp)
                }
                if (onSaveAll != null) TextButton(onClick = onSaveAll, enabled = actionsEnabled) {
                    Text("全部保存", color = AppGold, fontSize = 12.sp)
                }
                Button(
                    onClick = onSave,
                    enabled = actionsEnabled && canSave,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                    else Text("保存当前", fontSize = 12.sp)
                }
                EditorMoreMenu(enabled = actionsEnabled, kind = helpKind)
            }
        }
    }
}

@Composable
private fun LivePhotoPreview(entry: PhotoEntry, options: FrameOptions, modifier: Modifier = Modifier) {
    val source = entry.loaded.preview
    val geometry = remember(source, options.layout) {
        FrameGeometry.calculate(source.width, source.height, options.layout, outputLongEdge = 2_000)
    }
    val request = remember(source, geometry, options.style) { Any() }
    val preparedResult by produceState<Pair<Any, MaterializedBackground>?>(null, request) {
        value = request to withContext(Dispatchers.Default) { FrameRenderer.prepareBackground(source, geometry, options.style) }
    }
    val prepared = preparedResult?.takeIf { it.first === request }?.second
    androidx.compose.foundation.Canvas(modifier) {
        val scale = min(size.width / geometry.canvasWidth, size.height / geometry.canvasHeight)
        val left = (size.width - geometry.canvasWidth * scale) / 2f
        val top = (size.height - geometry.canvasHeight * scale) / 2f
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val save = canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            prepared?.let { FrameRenderer.draw(canvas, source, geometry, options, it) }
            canvas.restoreToCount(save)
        }
    }
}

@Composable
/**
 * Horizontal photo strip: real thumbnails, the open photo highlighted, a remove cross on each and an
 * add tile at the end. Replaces the previous text-only labels.
 */
internal fun PhotoStrip(photos: List<PhotoEntry>, index: Int, onChoosePhoto: (Int) -> Unit,
    onRemovePhoto: (Int) -> Unit, onAdd: () -> Unit) {
    if (photos.getOrNull(index) == null) return
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}/${photos.size}", modifier = Modifier.width(40.dp),
            color = AppGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(photos.size) { photoIndex ->
                val photo = photos[photoIndex]
                PhotoThumbnail(
                    bitmap = photo.loaded.preview,
                    selected = photoIndex == index,
                    onChoose = { onChoosePhoto(photoIndex) },
                    onRemove = { onRemovePhoto(photoIndex) },
                )
            }
        }
        OutlinedButton(
            onClick = onAdd,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(10.dp),
        ) { Text("＋", fontSize = 16.sp) }
    }
}

/** One strip tile: the photo's own thumbnail, a selection ring, and a remove cross in the corner. */
@Composable
internal fun PhotoThumbnail(bitmap: Bitmap, selected: Boolean, onChoose: () -> Unit, onRemove: () -> Unit) {
    Box(Modifier.size(48.dp)) {
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(9.dp))
                .border(if (selected) 2.dp else 1.dp,
                    if (selected) AppGold else Color(0xFF3A3D45), RoundedCornerShape(9.dp))
                .clickable(onClick = onChoose),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (selected) "当前照片" else "切换到这张照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(18.dp)
                .clip(CircleShape)
                .background(Color(0xCC000000))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", fontSize = 9.sp, color = Color.White)
        }
    }
}

/** Display names can carry unpaired surrogates; Compose text rendering rejects them outright. */
internal fun printableName(name: String): String = buildString(name.length) {
    var index = 0
    while (index < name.length) {
        val current = name[index]
        if (Character.isHighSurrogate(current) && index + 1 < name.length &&
            Character.isLowSurrogate(name[index + 1])) {
            append(current); append(name[index + 1]); index += 2
        } else if (Character.isSurrogate(current)) {
            append('\uFFFD'); index++
        } else {
            append(current); index++
        }
    }
}

@Composable
private fun EditorTabBar(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF15161B)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorTab.entries.forEach { tab ->
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (tab == selected) Color(0xFF302A16) else Color.Transparent)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(tab.title, color = if (selected == tab) AppGold else AppMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ImageControls(
    selectedCircleId: String?,
    circleSizePercent: Int,
    onCircleChanged: (String?) -> Unit,
    onCircleSizeChanged: (Int) -> Unit,
    materials: List<UserMaterial>,
    enabled: Boolean,
    onOpenMaterials: (MaterialKind) -> Unit,
) {
    MaterialPickerRow(MaterialKind.IMAGE, materials, selectedCircleId, true, enabled, onCircleChanged, onOpenMaterials)
    if (selectedCircleId != null) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("圆图直径", fontSize = 13.sp)
            Text("$circleSizePercent%", color = AppGold, fontSize = 13.sp)
        }
        Slider(
            value = circleSizePercent.toFloat(),
            onValueChange = { onCircleSizeChanged(it.toInt().coerceIn(50, 140)) },
            valueRange = 50f..140f,
        )
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun CircleChoice(id: String?, selected: Boolean, onClick: () -> Unit) {
    val resource = circleResourceId(id)
    Box(
        Modifier.size(58.dp).clip(CircleShape)
            .background(if (id == null) AppSurfaceRaised else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) AppGold else Color(0xFF4A4D55), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (resource != null) {
            Image(painterResource(resource), contentDescription = "圆图素材 ${id?.last()}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape))
        } else Text("无", color = AppMuted, fontSize = 12.sp)
    }
}

@Composable
private fun FrameControls(
    layout: FrameLayoutKind,
    onLayoutChanged: (FrameLayoutKind) -> Unit,
    selectedBackground: BorderBackgroundMode,
    onBackgroundChanged: (BorderBackgroundMode) -> Unit,
    analysis: PhotoColorAnalysis?,
    preview: Bitmap,
) {
    Text("边框形式", color = AppMuted, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FrameLayoutKind.entries.forEach { kind ->
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp))
                    .background(AppSurfaceRaised)
                    .border(if (kind == layout) 2.dp else 1.dp,
                        if (kind == layout) AppGold else Color.Transparent, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = kind.title; selected = kind == layout }
                    .clickable { onLayoutChanged(kind) },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.width(36.dp).height(44.dp)) {
                    drawRoundRect(Color(0xFFEEEEEE), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    val inset = if (kind == FrameLayoutKind.BOX) 4.dp.toPx() else 0f
                    drawRect(Color(0xFF45474B), topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset - 10.dp.toPx()))
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("边框背景", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(selectedBackground.title, color = AppGold, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BorderBackgroundMode.entries.forEach { mode ->
            BackgroundChoice(
                mode = mode,
                selected = mode == selectedBackground,
                analysis = analysis,
                layout = layout,
                preview = preview,
                onClick = { onBackgroundChanged(mode) },
            )
        }
    }
}

/**
 * One swatch of the 边框背景 row. Solid modes show the color they would use for the current photo;
 * 模糊 shows the actual blurred background produced by the shared renderer.
 */
@Composable
private fun BackgroundChoice(
    mode: BorderBackgroundMode,
    selected: Boolean,
    analysis: PhotoColorAnalysis?,
    layout: FrameLayoutKind,
    preview: Bitmap,
    onClick: () -> Unit,
) {
    val style = remember(mode, analysis) { BorderStyleResolver.resolve(mode, analysis) }
    val solidColor = style.solidColor
    val request = remember(mode, analysis, layout, preview) { Any() }
    val blurResult by produceState<Pair<Any, Bitmap?>?>(null, request) {
        val bitmap = if (style.background is BorderBackgroundSpec.Blur) withContext(Dispatchers.Default) {
            val swatchGeometry = FrameGeometry.calculate(preview.width, preview.height, layout, outputLongEdge = 200)
            (FrameRenderer.prepareBackground(preview, swatchGeometry, style) as? MaterializedBackground.Photo)?.bitmap
        } else null
        value = request to bitmap
    }
    val blurBitmap = blurResult?.takeIf { it.first === request }?.second
    Column(
        Modifier.width(74.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF302A16) else AppSurfaceRaised)
            .border(if (selected) 2.dp else 1.dp, if (selected) AppGold else Color(0xFF4A4D55), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .border(1.dp, Color(0xFF5A5D66), CircleShape),
        ) {
            when {
                blurBitmap != null -> Image(
                    bitmap = blurBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                solidColor != null -> Box(
                    Modifier.fillMaxSize().background(Color(solidColor)),
                )
            }
        }
        Text(mode.title, color = if (selected) AppGold else AppMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun SignatureControls(
    selectedId: String?,
    sizePercent: Int,
    onSignatureChanged: (String?) -> Unit,
    onSizeChanged: (Int) -> Unit,
    materials: List<UserMaterial>,
    enabled: Boolean,
    onOpenMaterials: (MaterialKind) -> Unit,
) {
    MaterialPickerRow(MaterialKind.SIGNATURE, materials, selectedId, true, enabled, onSignatureChanged, onOpenMaterials)
    if (selectedId != null) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("签名大小", fontSize = 13.sp)
            Text("$sizePercent%", color = AppGold, fontSize = 13.sp)
        }
        Slider(
            value = sizePercent.toFloat(),
            onValueChange = { onSizeChanged(it.roundToInt().coerceIn(50, 140)) },
            valueRange = 50f..140f,
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun SignatureChoice(id: String?, selected: Boolean, onClick: () -> Unit) {
    val resource = signatureResourceId(id)
    Column(
        Modifier.width(72.dp).height(76.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFF302A16) else AppSurfaceRaised)
            .border(if (selected) 2.dp else 1.dp, if (selected) AppGold else Color(0xFF4A4D55), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (resource != null) {
            Image(
                painterResource(resource),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Text("无", color = AppMuted, fontSize = 15.sp)
            }
        }
        Text(if (id == null) "无签名" else "签名 ${id.last()}", color = if (selected) AppGold else AppMuted, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun InfoControls(draft: PhotoDraft, metadata: PhotoMetadata) {
    InfoSection(
        title = "摄影参数",
        visible = draft.showParameters,
        onVisibleChange = { draft.showParameters = it },
        manual = draft.parameterManual,
        onManualChange = draft::selectParameterMode,
        automaticSummary = draft.automaticParameters().ifBlank { "这张照片没有可用的摄影参数" },
        manualValue = draft.parameterText,
        onManualValueChange = { draft.parameterText = it },
    ) {
        Column {
            ParameterField.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { field ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = draft.parameterFields[field] == true,
                                onCheckedChange = { draft.parameterFields[field] = it },
                            )
                            Text(field.label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    InfoSection(
        title = "日期时间",
        visible = draft.showDate,
        onVisibleChange = { draft.showDate = it },
        manual = draft.dateManual,
        onManualChange = draft::selectDateMode,
        automaticSummary = metadata.date.ifBlank { "无拍摄日期" },
        manualValue = draft.dateText,
        onManualValueChange = { draft.dateText = it },
    )
    InfoSection(
        title = "地点",
        visible = draft.showPlace,
        onVisibleChange = { draft.showPlace = it },
        manual = draft.placeManual,
        onManualChange = draft::selectPlaceMode,
        automaticSummary = "请手动填写地点",
        manualValue = draft.placeText,
        onManualValueChange = { draft.placeText = it },
    )
    InfoSection(
        title = "机型",
        visible = draft.showModel,
        onVisibleChange = { draft.showModel = it },
        manual = draft.modelManual,
        onManualChange = draft::selectModelMode,
        automaticSummary = metadata.model.ifBlank { "无机型信息" },
        manualValue = draft.modelText,
        onManualValueChange = { draft.modelText = it },
    )
}

@Composable
internal fun InfoSection(
    title: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    manual: Boolean,
    onManualChange: (Boolean) -> Unit,
    automaticSummary: String,
    manualValue: String,
    onManualValueChange: (String) -> Unit,
    extraAutomaticControls: @Composable () -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurfaceRaised),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Switch(checked = visible, onCheckedChange = onVisibleChange)
            }
            if (visible) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("自动", selected = !manual) { onManualChange(false) }
                    ModeButton("手动", selected = manual) { onManualChange(true) }
                }
                if (manual) {
                    LimitedTextField(title, manualValue, onManualValueChange)
                } else {
                    Text(automaticSummary, Modifier.padding(top = 8.dp), color = if (automaticSummary.contains("没有")) AppMuted else Color(0xFFD8D7D1), fontSize = 12.sp)
                    extraAutomaticControls()
                }
            }
        }
    }
}

@Composable
private fun ModeButton(title: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, if (selected) AppGold else Color(0xFF555861)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 3.dp),
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(9.dp),
    ) { Text(title, color = if (selected) AppGold else AppMuted, fontSize = 12.sp) }
}

@Composable
internal fun LimitedTextField(title: String, value: String, onValueChange: (String) -> Unit) {
    // `value` is already clipped by TextRules.limit, so the counter alone cannot show that input was
    // capped. Track the attempted text so typing or pasting past the limit gives a visible message.
    var capped by remember(title) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            capped = TextRules.exceeds(raw)
            onValueChange(TextRules.limit(raw))
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(title) },
        isError = capped,
        supportingText = {
            if (capped) {
                Text("最多 ${TextRules.MAX_VISIBLE_CHARACTERS} 个字符，超出部分未录入", color = MaterialTheme.colorScheme.error)
            } else {
                Text("${TextRules.graphemes(value).size}/${TextRules.MAX_VISIBLE_CHARACTERS}")
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = RoundedCornerShape(12.dp),
    )
}

/** Shared preset dialog for both watermarks; the caller supplies the title, scope text and row text. */
@Composable
private fun <T> RecipeDialog(
    title: String,
    description: String,
    recipes: List<T>,
    name: (T) -> String,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onApply: (T) -> Unit,
    onDelete: (T) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var capped by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(description, color = AppMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = newName,
                    onValueChange = { raw ->
                        capped = TextRules.exceeds(raw)
                        newName = TextRules.limit(raw)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("新配方名称") },
                    isError = capped,
                    supportingText = {
                        if (capped) {
                            Text("最多 ${TextRules.MAX_VISIBLE_CHARACTERS} 个字符，超出部分未录入", color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("${TextRules.graphemes(newName).size}/${TextRules.MAX_VISIBLE_CHARACTERS}")
                        }
                    },
                    singleLine = true,
                )
                recipes.forEach { recipe ->
                    Card(colors = CardDefaults.cardColors(containerColor = AppSurfaceRaised), shape = RoundedCornerShape(11.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name(recipe), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Text(label(recipe), color = AppMuted, fontSize = 10.sp)
                            }
                            TextButton(onClick = { onApply(recipe) }) { Text("应用", color = AppGold, fontSize = 12.sp) }
                            TextButton(onClick = { onDelete(recipe) }) { Text("删除", color = AppMuted, fontSize = 12.sp) }
                        }
                    }
                }
                if (recipes.isEmpty()) Text("还没有保存的配方。", color = AppMuted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(newName); newName = ""; capped = false }) { Text("保存当前样式") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

internal fun frameRecipeLabel(recipe: FrameRecipe): String =
    "${recipe.layout.title} · ${recipe.background.title} · 圆图 ${recipe.circlePercent}%"

internal fun artRecipeLabel(recipe: ArtRecipe): String {
    val horizontal = listOf("左", "中", "右").getOrElse(recipe.horizontal) { "中" }
    val vertical = listOf("上", "中", "下").getOrElse(recipe.vertical) { "中" }
    val enabled = buildList {
        if (recipe.showModel) add("机型")
        if (recipe.showPhrase) add("短句")
        if (recipe.showDate) add("日期")
        if (recipe.showPlace) add("地点")
    }
    val fields = if (enabled.isEmpty()) "无附加信息" else enabled.joinToString("、")
    return "$vertical$horizontal · 透明度 ${recipe.opacity}% · $fields"
}
