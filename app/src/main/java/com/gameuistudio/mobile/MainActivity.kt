package com.gameuistudio.mobile

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorApp(vm: EditorViewModel = viewModel()) {
    val context = LocalContext.current
    var presetIndex by remember { mutableStateOf(0) }
    var canvasZoom by remember { mutableFloatStateOf(1f) }
    var showSafeArea by remember { mutableStateOf(true) }
    var cropMode by remember { mutableStateOf(false) }
    var showProperties by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var canvasNavigationMode by remember { mutableStateOf(false) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var pendingJson by remember { mutableStateOf("") }

    val preset = PREVIEW_PRESETS[presetIndex]
    val editable = presetIndex == 0

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEachIndexed { index, uri ->
            runCatching {
                val relative = ProjectStorage.importAsset(context, uri)
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片 ${index + 1}"
                val file = ProjectStorage.assetFile(context, relative)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                val ratio = if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight
                } else 16f / 9f
                vm.addImage(relative, name, ratio)
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingJson.toByteArray()) }
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                ProjectStorage.writeProjectZip(context, vm.toProject(), output)
            }
        }
    }

    LaunchedEffect(Unit) {
        ProjectStorage.ensure(context)
        vm.loadProject(ProjectStorage.load(context))
    }

    LaunchedEffect(vm.revision, vm.loaded) {
        if (vm.loaded) {
            delay(250)
            ProjectStorage.save(context, vm.toProject())
        }
    }

    LaunchedEffect(vm.selectedId, vm.selectedIds.size, vm.currentPageId, editable, canvasNavigationMode) {
        if (cropMode && (!editable || canvasNavigationMode || vm.selectedIds.size != 1 || vm.selected?.type != ElementType.IMAGE)) {
            cropMode = false
            vm.endTransaction()
        }
    }

    Scaffold(
        containerColor = Color(0xFF0D1015),
        topBar = {
            TopEditorBar(
                projectName = vm.projectName,
                preset = preset,
                zoom = canvasZoom,
                safeArea = showSafeArea,
                snapping = vm.snappingEnabled,
                multiSelect = vm.multiSelectMode,
                navigationMode = canvasNavigationMode,
                onUndo = vm::undo,
                onRedo = vm::redo,
                onImport = { imageLauncher.launch(arrayOf("image/*")) },
                onAddText = vm::addText,
                onAddButton = vm::addButton,
                onAddPanel = vm::addPanel,
                onPresetClick = { showPresetMenu = true },
                presetMenu = {
                    DropdownMenu(expanded = showPresetMenu, onDismissRequest = { showPresetMenu = false }) {
                        PREVIEW_PRESETS.forEachIndexed { index, item ->
                            DropdownMenuItem(
                                text = { Text("${item.label}  ${item.width.toInt()}×${item.height.toInt()}") },
                                onClick = {
                                    if (cropMode) {
                                        cropMode = false
                                        vm.endTransaction()
                                    }
                                    presetIndex = index
                                    canvasZoom = 1f
                                    showPresetMenu = false
                                }
                            )
                        }
                    }
                },
                onZoomOut = { canvasZoom = (canvasZoom - 0.1f).coerceAtLeast(0.5f) },
                onZoomIn = { canvasZoom = (canvasZoom + 0.1f).coerceAtMost(2.5f) },
                onFit = { canvasZoom = 1f },
                onToggleSafe = { showSafeArea = !showSafeArea },
                onToggleSnap = vm::toggleSnapping,
                onToggleMulti = vm::toggleMultiSelectMode,
                onToggleNavigation = {
                    canvasNavigationMode = !canvasNavigationMode
                    if (canvasNavigationMode && vm.multiSelectMode) vm.toggleMultiSelectMode()
                },
                onLayers = { showLayers = true },
                onPages = { showPages = true },
                onLibrary = { showLibrary = true },
                onSave = { ProjectStorage.save(context, vm.toProject()) },
                onExportJson = {
                    pendingJson = ProjectStorage.projectJson(vm.toProject())
                    exportJsonLauncher.launch("${vm.projectName}.json")
                },
                onExportProject = { exportZipLauncher.launch("${vm.projectName}.guiproject.zip") }
            )
        },
        bottomBar = {
            Column(Modifier.background(Color(0xFF171B22))) {
                PageStrip(vm)
                AssetStrip(
                    elements = vm.elements.filter { it.type == ElementType.IMAGE },
                    selectedIds = vm.selectedIds.toSet(),
                    contextFilesRoot = File(context.filesDir, "game_ui_studio/current"),
                    onSelect = { vm.selectElement(it) }
                )
                SelectedToolBar(
                    selected = vm.selected,
                    selectedCount = vm.selectedIds.size,
                    cropMode = cropMode,
                    editable = editable && !canvasNavigationMode,
                    multiSelect = vm.multiSelectMode,
                    groupEditActive = vm.activeGroupEditId != null,
                    selectedGrouped = vm.selection.mapNotNull { it.groupId }.distinct().size == 1,
                    onToggleCrop = {
                        if (!cropMode) vm.beginTransaction() else vm.endTransaction()
                        cropMode = !cropMode
                    },
                    onDuplicate = vm::duplicateSelected,
                    onDelete = vm::deleteSelected,
                    onFront = vm::bringToFront,
                    onBack = vm::sendToBack,
                    onLock = vm::toggleLock,
                    onGroup = vm::groupSelected,
                    onUngroup = vm::ungroupSelected,
                    onEnterGroupEdit = vm::enterSelectedGroupEdit,
                    onExitGroupEdit = vm::exitGroupEdit,
                    onAlign = vm::alignSelected,
                    onDistributeH = { vm.distributeSelected(horizontal = true) },
                    onDistributeV = { vm.distributeSelected(horizontal = false) },
                    onProperties = { if (vm.selectedIds.size == 1) showProperties = true },
                    onFinishMulti = { if (vm.multiSelectMode) vm.toggleMultiSelectMode() }
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            CanvasWorkspace(
                vm = vm,
                preset = preset,
                canvasZoom = canvasZoom,
                showSafeArea = showSafeArea,
                editable = editable && !canvasNavigationMode,
                cropMode = cropMode,
                navigationMode = canvasNavigationMode,
                assetsRoot = File(context.filesDir, "game_ui_studio/current"),
                onCanvasZoomChange = { canvasZoom = it.coerceIn(0.35f, 3f) }
            )

            if (canvasNavigationMode) {
                Text(
                    "画布导航 · 拖动画布 / 双指缩放",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                        .background(Color(0xDD7C3AED), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            if (!editable) {
                Text(
                    "适配预览 · 只读",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                        .background(Color(0xCC111318), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
            if (editable && vm.multiSelectMode) {
                Text(
                    "多选模式 · 点元素或在空白处拖框选择",
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                        .background(Color(0xDD0F766E), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }

    if (showProperties && vm.selected != null && vm.selectedIds.size == 1) {
        PropertiesSheet(
            element = vm.selected!!,
            onDismiss = { showProperties = false },
            onAnchor = vm::setAnchor,
            onWidthMode = vm::setWidthMode,
            onHeightMode = vm::setHeightMode,
            onRotate = vm::rotateSelected,
            onFlip = vm::flipSelected,
            onReset = vm::resetImageTransform,
            vm = vm
        )
    }

    if (showLayers) LayersSheet(vm = vm, onDismiss = { showLayers = false })
    if (showPages) PagesSheet(vm = vm, onDismiss = { showPages = false })
    if (showLibrary) LibrarySheet(
        vm = vm,
        projectRoot = File(context.filesDir, "game_ui_studio/current"),
        onDismiss = { showLibrary = false }
    )
}

@Composable
private fun TopEditorBar(
    projectName: String,
    preset: PreviewPreset,
    zoom: Float,
    safeArea: Boolean,
    snapping: Boolean,
    multiSelect: Boolean,
    navigationMode: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImport: () -> Unit,
    onAddText: () -> Unit,
    onAddButton: () -> Unit,
    onAddPanel: () -> Unit,
    onPresetClick: () -> Unit,
    presetMenu: @Composable () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onToggleSafe: () -> Unit,
    onToggleSnap: () -> Unit,
    onToggleMulti: () -> Unit,
    onToggleNavigation: () -> Unit,
    onLayers: () -> Unit,
    onPages: () -> Unit,
    onLibrary: () -> Unit,
    onSave: () -> Unit,
    onExportJson: () -> Unit,
    onExportProject: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(Color(0xFF20242B))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(projectName, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(max = 170.dp))
        TinyButton("撤销", onUndo)
        TinyButton("重做", onRedo)
        TinyButton("导入图片", onImport, primary = true)
        TinyButton("文字", onAddText)
        TinyButton("按钮", onAddButton)
        TinyButton("面板", onAddPanel)
        Box {
            TinyButton("${preset.label} ▼", onPresetClick)
            presetMenu()
        }
        TinyButton("－", onZoomOut)
        Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
        TinyButton("＋", onZoomIn)
        TinyButton("适配", onFit)
        TinyButton(if (safeArea) "安全区✓" else "安全区", onToggleSafe)
        TinyButton(if (snapping) "吸附✓" else "吸附", onToggleSnap, primary = snapping)
        TinyButton(if (multiSelect) "多选✓" else "多选", onToggleMulti, primary = multiSelect)
        TinyButton(if (navigationMode) "画布手势✓" else "画布手势", onToggleNavigation, primary = navigationMode)
        TinyButton("图层", onLayers)
        TinyButton("页面", onPages)
        TinyButton("资源库", onLibrary, primary = true)
        TinyButton("保存", onSave)
        TinyButton("导出JSON", onExportJson)
        TinyButton("导出工程", onExportProject)
    }
}

@Composable
private fun TinyButton(text: String, onClick: () -> Unit, primary: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) Color(0xFF2563EB) else Color(0xFF353B46)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 12.sp) }
}

@Composable
private fun PageStrip(vm: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("页面", color = Color(0xFF9CA3AF), fontSize = 10.sp)
        vm.pages.forEachIndexed { index, page ->
            val active = page.id == vm.currentPageId
            Button(
                onClick = { vm.switchPage(page.id) },
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Color(0xFF0F766E) else Color(0xFF2A2F38)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text("${index + 1} ${page.name}", fontSize = 10.sp, maxLines = 1)
            }
        }
        TinyButton("＋", vm::addPage)
    }
}

@Composable
private fun CanvasWorkspace(
    vm: EditorViewModel,
    preset: PreviewPreset,
    canvasZoom: Float,
    showSafeArea: Boolean,
    editable: Boolean,
    cropMode: Boolean,
    navigationMode: Boolean,
    assetsRoot: File,
    onCanvasZoomChange: (Float) -> Unit
) {
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var marqueeStart by remember { mutableStateOf<Offset?>(null) }
    var marqueeEnd by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF0B0D10))
    ) {
        val availableW = maxWidth.value - 36f
        val availableH = maxHeight.value - 36f
        val fitScale = min(availableW / preset.width, availableH / preset.height).coerceAtLeast(0.08f)
        val renderScale = fitScale * canvasZoom
        val canvasW = (preset.width * renderScale).dp
        val canvasH = (preset.height * renderScale).dp

        val navigationModifier = if (navigationMode) {
            Modifier.pointerInput(preset.label, navigationMode) {
                var gestureZoom = canvasZoom
                detectTransformGestures { _, pan, zoom, _ ->
                    gestureZoom = (gestureZoom * zoom).coerceIn(0.35f, 3f)
                    onCanvasZoomChange(gestureZoom)
                    scope.launch {
                        hScroll.scrollBy(-pan.x)
                        vScroll.scrollBy(-pan.y)
                    }
                }
            }
        } else Modifier

        Box(
            Modifier.fillMaxSize().then(navigationModifier)
                .horizontalScroll(hScroll).verticalScroll(vScroll).padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.requiredSize(canvasW, canvasH)
                    .background(Color(0xFFF4F4F4), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF646B75), RoundedCornerShape(10.dp))
            ) {
                if (showSafeArea) {
                    val marginX = preset.width * 0.04f * renderScale
                    val marginY = preset.height * 0.06f * renderScale
                    Box(
                        Modifier.offset(marginX.dp, marginY.dp)
                            .requiredSize(
                                (preset.width * renderScale - marginX * 2).dp,
                                (preset.height * renderScale - marginY * 2).dp
                            )
                            .border(2.dp, Color(0xFF0F766E), RoundedCornerShape(8.dp))
                    )
                }

                if (editable && vm.snappingEnabled) {
                    vm.snapGuideX?.let { guideX ->
                        Box(
                            Modifier.offset((guideX * renderScale).dp, 0.dp)
                                .width(1.dp).fillMaxHeight().background(Color(0xFFFF3D9A))
                        )
                    }
                    vm.snapGuideY?.let { guideY ->
                        Box(
                            Modifier.offset(0.dp, (guideY * renderScale).dp)
                                .height(1.dp).fillMaxWidth().background(Color(0xFFFF3D9A))
                        )
                    }
                }

                vm.elements.sortedBy { it.zIndex }.filterNot { it.hidden }.forEach { element ->
                    val rect = resolveElement(element, preset.width, preset.height)
                    EditorElementView(
                        element = element,
                        rect = rect,
                        selected = vm.isSelected(element.id),
                        singleSelection = vm.selectedIds.size == 1,
                        renderScale = renderScale,
                        editable = editable && !vm.multiSelectMode,
                        cropMode = cropMode && vm.selectedId == element.id && element.type == ElementType.IMAGE,
                        assetsRoot = assetsRoot,
                        onSelect = { vm.selectElement(element.id) },
                        onBegin = vm::beginTransaction,
                        onEnd = vm::endTransaction,
                        onMove = { dx, dy -> vm.moveTransient(element.id, dx, dy) },
                        onResize = { dw, dh -> vm.resizeTransient(element.id, dw, dh, keepRatio = element.type == ElementType.IMAGE) },
                        onCrop = { panX, panY, zoom, rotation -> vm.updateCropTransient(element.id, panX, panY, zoom, rotation) }
                    )
                }

                if (vm.selectedIds.size > 1 && editable) {
                    val chosen = vm.selection
                    if (chosen.isNotEmpty()) {
                        val left = chosen.minOf { it.x }
                        val top = chosen.minOf { it.y }
                        val right = chosen.maxOf { it.x + it.width }
                        val bottom = chosen.maxOf { it.y + it.height }
                        Box(
                            Modifier.offset((left * renderScale).dp, (top * renderScale).dp)
                                .requiredSize(((right - left) * renderScale).dp, ((bottom - top) * renderScale).dp)
                                .border(2.dp, Color(0xFF06B6D4), RoundedCornerShape(4.dp))
                        )
                    }
                }

                if (editable && vm.multiSelectMode) {
                    val selectionOverlay = Modifier.fillMaxSize()
                        .pointerInput(renderScale, vm.currentPageId) {
                            detectTapGestures { tap ->
                                val x = tap.x / density / renderScale
                                val y = tap.y / density / renderScale
                                val hit = vm.elements.filterNot { it.hidden }.sortedByDescending { it.zIndex }
                                    .firstOrNull { e -> x >= e.x && x <= e.x + e.width && y >= e.y && y <= e.y + e.height }
                                if (hit != null) vm.selectElement(hit.id, additive = true) else vm.select(null)
                            }
                        }
                        .pointerInput(renderScale, vm.currentPageId) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    marqueeStart = start
                                    marqueeEnd = start
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    marqueeEnd = change.position
                                },
                                onDragEnd = {
                                    val a = marqueeStart
                                    val b = marqueeEnd
                                    if (a != null && b != null) {
                                        vm.selectRect(
                                            a.x / density / renderScale,
                                            a.y / density / renderScale,
                                            b.x / density / renderScale,
                                            b.y / density / renderScale,
                                            additive = false
                                        )
                                    }
                                    marqueeStart = null
                                    marqueeEnd = null
                                },
                                onDragCancel = {
                                    marqueeStart = null
                                    marqueeEnd = null
                                }
                            )
                        }
                    Box(selectionOverlay)
                }

                val a = marqueeStart
                val b = marqueeEnd
                if (a != null && b != null) {
                    val leftPx = min(a.x, b.x)
                    val topPx = min(a.y, b.y)
                    val widthPx = max(1f, kotlin.math.abs(a.x - b.x))
                    val heightPx = max(1f, kotlin.math.abs(a.y - b.y))
                    Box(
                        Modifier.offset((leftPx / density).dp, (topPx / density).dp)
                            .requiredSize((widthPx / density).dp, (heightPx / density).dp)
                            .background(Color(0x332563EB))
                            .border(1.dp, Color(0xFF2563EB))
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorElementView(
    element: EditorElement,
    rect: ResolvedRect,
    selected: Boolean,
    singleSelection: Boolean,
    renderScale: Float,
    editable: Boolean,
    cropMode: Boolean,
    assetsRoot: File,
    onSelect: () -> Unit,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onCrop: (Float, Float, Float, Float) -> Unit
) {
    val density = LocalDensity.current.density
    val borderColor = if (cropMode) Color(0xFF0F766E) else if (singleSelection) Color(0xFF2563EB) else Color(0xFF06B6D4)
    val baseModifier = Modifier
        .offset((rect.x * renderScale).dp, (rect.y * renderScale).dp)
        .requiredSize((rect.width * renderScale).dp, (rect.height * renderScale).dp)
        .then(if (selected) Modifier.border(2.dp, borderColor) else Modifier)
        .clickable { onSelect() }
        .pointerInput(element.id, editable, cropMode, renderScale) {
            if (!editable || element.locked) return@pointerInput
            if (cropMode && element.type == ElementType.IMAGE) {
                detectTransformGestures { _, pan, zoom, rotation ->
                    onCrop(pan.x / density / renderScale, pan.y / density / renderScale, zoom, rotation)
                }
            } else {
                detectDragGestures(
                    onDragStart = { onSelect(); onBegin() },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onMove(amount.x / density / renderScale, amount.y / density / renderScale)
                    }
                )
            }
        }

    Box(baseModifier, contentAlignment = Alignment.Center) {
        when (element.type) {
            ElementType.IMAGE -> RenderImage(element, renderScale, assetsRoot)
            ElementType.TEXT -> Text(
                element.text,
                color = Color(0xFF111318),
                fontSize = (element.fontSize * renderScale).sp,
                fontWeight = FontWeight.SemiBold
            )
            ElementType.BUTTON -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape((element.cornerRadius * renderScale).dp))
                    .background(Color(0xFF2563EB)),
                contentAlignment = Alignment.Center
            ) {
                Text(element.text, color = Color.White, fontSize = (element.fontSize * renderScale).sp)
            }
            ElementType.PANEL -> Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape((element.cornerRadius * renderScale).dp))
                    .background(Color(0xFFCBD5E1).copy(alpha = element.opacity))
            )
        }

        if (selected && singleSelection && editable && !element.locked && !cropMode) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(10.dp, 10.dp).size(24.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White)
                    .border(4.dp, Color(0xFF2563EB), RoundedCornerShape(50))
                    .pointerInput(element.id, renderScale) {
                        detectDragGestures(
                            onDragStart = { onBegin() },
                            onDragEnd = onEnd,
                            onDragCancel = onEnd,
                            onDrag = { change, amount ->
                                change.consume()
                                onResize(amount.x / density / renderScale, amount.y / density / renderScale)
                            }
                        )
                    }
            )
        }
    }
}

@Composable
private fun RenderImage(element: EditorElement, renderScale: Float, assetsRoot: File) {
    val density = LocalDensity.current.density
    val bitmap = remember(element.assetPath) {
        element.assetPath?.let { path ->
            val file = File(assetsRoot, path)
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }
    if (bitmap == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF3F4651)), contentAlignment = Alignment.Center) {
            Text("图片缺失", color = Color.White, fontSize = 12.sp)
        }
        return
    }

    val filter = remember(element.brightness, element.contrast, element.saturation) {
        ColorFilter.colorMatrix(editorColorMatrix(element.brightness, element.contrast, element.saturation))
    }

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape((element.cornerRadius * renderScale).dp))
            .background(Color.Transparent)
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = element.name,
            contentScale = ContentScale.Crop,
            colorFilter = filter,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = element.opacity
                translationX = element.cropOffsetX * renderScale * density
                translationY = element.cropOffsetY * renderScale * density
                scaleX = element.cropZoom * if (element.flipX) -1f else 1f
                scaleY = element.cropZoom * if (element.flipY) -1f else 1f
                rotationZ = element.rotation
            }
        )
    }
}

private fun editorColorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
    val s = saturation.coerceIn(0f, 2f)
    val c = contrast.coerceIn(0f, 2f)
    val b = brightness.coerceIn(0f, 2f)
    val inv = 1f - s
    val r = 0.213f * inv
    val g = 0.715f * inv
    val bl = 0.072f * inv
    val offset = (b - 1f) * 255f + 128f * (1f - c)
    return ColorMatrix(
        floatArrayOf(
            (r + s) * c, g * c, bl * c, 0f, offset,
            r * c, (g + s) * c, bl * c, 0f, offset,
            r * c, g * c, (bl + s) * c, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

@Composable
private fun AssetStrip(
    elements: List<EditorElement>,
    selectedIds: Set<String>,
    contextFilesRoot: File,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(78.dp).horizontalScroll(rememberScrollState()).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (elements.isEmpty()) {
            Text("当前页面的图片素材会显示在这里", color = Color(0xFF9CA3AF), fontSize = 12.sp)
        }
        elements.forEach { item ->
            val bmp = remember(item.assetPath) {
                item.assetPath?.let { BitmapFactory.decodeFile(File(contextFilesRoot, it).absolutePath)?.asImageBitmap() }
            }
            val selected = item.id in selectedIds
            Column(
                Modifier.width(74.dp).clip(RoundedCornerShape(8.dp))
                    .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF2563EB) else Color(0xFF3B424E), RoundedCornerShape(8.dp))
                    .background(Color(0xFF252A33)).clickable { onSelect(item.id) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (bmp != null) Image(bmp, item.name, Modifier.size(64.dp, 45.dp), contentScale = ContentScale.Crop)
                else Box(Modifier.size(64.dp, 45.dp).background(Color.DarkGray))
                Text(item.name.take(10), fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SelectedToolBar(
    selected: EditorElement?,
    selectedCount: Int,
    cropMode: Boolean,
    editable: Boolean,
    multiSelect: Boolean,
    groupEditActive: Boolean,
    selectedGrouped: Boolean,
    onToggleCrop: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
    onEnterGroupEdit: () -> Unit,
    onExitGroupEdit: () -> Unit,
    onAlign: (String) -> Unit,
    onDistributeH: () -> Unit,
    onDistributeV: () -> Unit,
    onProperties: () -> Unit,
    onFinishMulti: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (selectedCount == 0) {
            Text(
                if (multiSelect) "点元素或拖框开始多选" else "选中元素后可移动、缩放、裁剪和设置适配锚点",
                color = Color(0xFF9CA3AF), fontSize = 12.sp
            )
            if (multiSelect) TinyButton("完成多选", onFinishMulti, primary = true)
            return@Row
        }

        Text(if (selectedCount == 1) selected?.name ?: "1 个元素" else "已选 $selectedCount 个", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (!editable) Text("当前为只读适配预览", color = Color(0xFFFBBF24), fontSize = 12.sp)

        if (selectedCount == 1 && selected?.type == ElementType.IMAGE && editable) {
            TinyButton(if (cropMode) "退出裁剪" else "裁剪", onToggleCrop, primary = cropMode)
        }
        if (editable && groupEditActive) {
            TinyButton("退出组内编辑", onExitGroupEdit, primary = true)
        } else if (editable && selectedGrouped) {
            TinyButton("编辑组内", onEnterGroupEdit, primary = true)
        }

        if (editable) {
            TinyButton("复制", onDuplicate)
            TinyButton("置顶", onFront)
            TinyButton("置底", onBack)
            TinyButton("锁定/解锁", onLock)
            if (selectedCount >= 2) {
                TinyButton("组合", onGroup, primary = true)
                TinyButton("取消组合", onUngroup)
                TinyButton("左齐", { onAlign("left") })
                TinyButton("横中", { onAlign("centerX") })
                TinyButton("右齐", { onAlign("right") })
                TinyButton("顶齐", { onAlign("top") })
                TinyButton("竖中", { onAlign("centerY") })
                TinyButton("底齐", { onAlign("bottom") })
                if (selectedCount >= 3) {
                    TinyButton("横等距", onDistributeH)
                    TinyButton("竖等距", onDistributeV)
                }
            }
            TinyButton("删除", onDelete)
        }
        if (selectedCount == 1) TinyButton("属性/适配", onProperties, primary = true)
        if (multiSelect) TinyButton("完成多选", onFinishMulti, primary = true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertiesSheet(
    element: EditorElement,
    onDismiss: () -> Unit,
    onAnchor: (Anchor) -> Unit,
    onWidthMode: (SizeMode) -> Unit,
    onHeightMode: (SizeMode) -> Unit,
    onRotate: (Float) -> Unit,
    onFlip: (Boolean) -> Unit,
    onReset: () -> Unit,
    vm: EditorViewModel
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF20242B)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("${element.name} · 属性与适配", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "位置 ${element.x.toInt()}, ${element.y.toInt()}   尺寸 ${element.width.toInt()} × ${element.height.toInt()}",
                fontSize = 12.sp, color = Color(0xFFB6BDC8)
            )

            Spacer(Modifier.height(14.dp))
            Text("快速对齐画布", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TinyButton("左", { vm.alignSelected("left") })
                TinyButton("水平中", { vm.alignSelected("centerX") })
                TinyButton("右", { vm.alignSelected("right") })
                TinyButton("顶", { vm.alignSelected("top") })
                TinyButton("垂直中", { vm.alignSelected("centerY") })
                TinyButton("底", { vm.alignSelected("bottom") })
            }

            Spacer(Modifier.height(16.dp))
            Text("锚点", fontWeight = FontWeight.Bold)
            AnchorGrid(element.anchor, onAnchor)

            Spacer(Modifier.height(14.dp))
            Text("宽度适配", fontWeight = FontWeight.Bold)
            ModeRow(element.widthMode, onWidthMode)
            Text("高度适配", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            ModeRow(element.heightMode, onHeightMode)

            if (element.type == ElementType.IMAGE) {
                Spacer(Modifier.height(18.dp))
                Text("图片编辑", fontWeight = FontWeight.Bold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TinyButton("左转90°", { onRotate(-90f) })
                    TinyButton("右转90°", { onRotate(90f) })
                    TinyButton("水平翻转", { onFlip(true) })
                    TinyButton("垂直翻转", { onFlip(false) })
                    TinyButton("重置", onReset)
                }

                ImageStyleSlider("透明度", element.opacity, 0f..1f, vm::beginTransaction,
                    { vm.updateImageStyleTransient(opacity = it) }, vm::endTransaction)
                ImageStyleSlider("圆角", element.cornerRadius, 0f..180f, vm::beginTransaction,
                    { vm.updateImageStyleTransient(radius = it) }, vm::endTransaction)
                ImageStyleSlider("亮度", element.brightness, 0f..2f, vm::beginTransaction,
                    { vm.updateImageStyleTransient(brightness = it) }, vm::endTransaction)
                ImageStyleSlider("对比度", element.contrast, 0f..2f, vm::beginTransaction,
                    { vm.updateImageStyleTransient(contrast = it) }, vm::endTransaction)
                ImageStyleSlider("饱和度", element.saturation, 0f..2f, vm::beginTransaction,
                    { vm.updateImageStyleTransient(saturation = it) }, vm::endTransaction)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AnchorGrid(current: Anchor, onAnchor: (Anchor) -> Unit) {
    val rows = listOf(
        listOf(Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT),
        listOf(Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT),
        listOf(Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT)
    )
    val labels = mapOf(
        Anchor.TOP_LEFT to "↖", Anchor.TOP_CENTER to "↑", Anchor.TOP_RIGHT to "↗",
        Anchor.CENTER_LEFT to "←", Anchor.CENTER to "●", Anchor.CENTER_RIGHT to "→",
        Anchor.BOTTOM_LEFT to "↙", Anchor.BOTTOM_CENTER to "↓", Anchor.BOTTOM_RIGHT to "↘"
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { anchor ->
                    Button(
                        onClick = { onAnchor(anchor) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (current == anchor) Color(0xFF2563EB) else Color(0xFF353B46)),
                        modifier = Modifier.width(70.dp)
                    ) { Text(labels[anchor] ?: "") }
                }
            }
        }
    }
}

@Composable
private fun ModeRow(current: SizeMode, onMode: (SizeMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SizeMode.entries.forEach { mode ->
            val label = when (mode) {
                SizeMode.FIXED -> "固定"
                SizeMode.PERCENT -> "按比例"
                SizeMode.STRETCH -> "拉伸"
            }
            Button(
                onClick = { onMode(mode) },
                colors = ButtonDefaults.buttonColors(containerColor = if (current == mode) Color(0xFF0F766E) else Color(0xFF353B46))
            ) { Text(label, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ImageStyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
    onEnd: () -> Unit
) {
    var localValue by remember(value) { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp)
            Text(String.format("%.2f", localValue), fontSize = 12.sp, color = Color(0xFFB6BDC8))
        }
        Slider(
            value = localValue.coerceIn(range.start, range.endInclusive),
            onValueChange = {
                if (!dragging) {
                    dragging = true
                    onStart()
                }
                localValue = it
                onChange(it)
            },
            valueRange = range,
            onValueChangeFinished = {
                if (dragging) {
                    dragging = false
                    onEnd()
                }
            }
        )
    }
}
