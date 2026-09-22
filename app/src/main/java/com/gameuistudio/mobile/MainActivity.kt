package com.gameuistudio.mobile

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.OutlinedTextField
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
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
    var showMoreTools by remember { mutableStateOf(false) }
    var showAdaptation by remember { mutableStateOf(false) }
    var showQuickActions by remember { mutableStateOf(false) }
    var customPreset by remember { mutableStateOf<PreviewPreset?>(null) }
    var canvasNavigationMode by remember { mutableStateOf(false) }
    var showPresetMenu by remember { mutableStateOf(false) }
    var pendingJson by remember { mutableStateOf("") }

    val preset = customPreset ?: PREVIEW_PRESETS[presetIndex]
    val editable = customPreset == null && presetIndex == 0

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
                } else 9f / 16f
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
        containerColor = Color(0xFF0B0D10),
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        topBar = {
            PortraitTopBar(
                projectName = vm.projectName,
                preset = preset,
                onUndo = vm::undo,
                onRedo = vm::redo,
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
                                    customPreset = null
                                    canvasZoom = 1f
                                    showPresetMenu = false
                                }
                            )
                        }
                    }
                },
                onFit = { canvasZoom = 1f },
                onMore = { showMoreTools = true }
            )
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().padding(
                    start = 6.dp,
                    end = 6.dp,
                    bottom = if (vm.selectedIds.isNotEmpty()) 114.dp else 58.dp
                )
            ) {
                CanvasWorkspace(
                    vm = vm,
                    preset = preset,
                    canvasZoom = canvasZoom,
                    showSafeArea = showSafeArea,
                    editable = editable && !canvasNavigationMode,
                    cropMode = cropMode,
                    navigationMode = canvasNavigationMode,
                    assetsRoot = File(context.filesDir, "game_ui_studio/current"),
                    onCanvasZoomChange = { canvasZoom = it.coerceIn(0.35f, 3f) },
                    onElementLongPress = {
                        vm.selectElement(it)
                        showQuickActions = true
                    }
                )

                if (canvasNavigationMode) {
                    EditorModeBadge("画布导航 · 拖动 / 双指缩放 · 双击适配", Color(0xDD7C3AED))
                } else if (cropMode) {
                    EditorModeBadge("裁剪 · 单指拖图 · 双指缩放/旋转", Color(0xDD0F766E))
                } else if (!editable) {
                    EditorModeBadge("适配预览 · 只读", Color(0xCC111318))
                } else if (vm.multiSelectMode) {
                    EditorModeBadge("多选 · 点选或拖框", Color(0xDD0F766E))
                }
            }

            if (vm.selectedIds.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .padding(start = 6.dp, end = 6.dp, bottom = 58.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xF2181C23))
                        .border(1.dp, Color(0xFF343B47), RoundedCornerShape(12.dp))
                ) {
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
                        onResetImage = vm::resetImageTransform,
                        onRotateLeft = { vm.rotateSelected(-90f) },
                        onRotateRight = { vm.rotateSelected(90f) },
                        onNudge = { dx, dy -> vm.nudgeSelected(dx, dy) },
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

            PortraitBottomDock(
                modifier = Modifier.align(Alignment.BottomCenter),
                onImport = { imageLauncher.launch(arrayOf("image/*")) },
                onAddText = vm::addText,
                onAddButton = vm::addButton,
                navigationMode = canvasNavigationMode,
                onToggleNavigation = {
                    canvasNavigationMode = !canvasNavigationMode
                    if (canvasNavigationMode && vm.multiSelectMode) vm.toggleMultiSelectMode()
                },
                onPages = { showPages = true },
                onLayers = { showLayers = true },
                onMore = { showMoreTools = true }
            )
        }
    }

    if (showMoreTools) {
        MoreToolsSheet(
            safeArea = showSafeArea,
            snapping = vm.snappingEnabled,
            multiSelect = vm.multiSelectMode,
            navigationMode = canvasNavigationMode,
            onDismiss = { showMoreTools = false },
            onAddPanel = vm::addPanel,
            onLibrary = { showMoreTools = false; showLibrary = true },
            onAdaptation = { showMoreTools = false; showAdaptation = true },
            onToggleSafe = { showSafeArea = !showSafeArea },
            onToggleSnap = vm::toggleSnapping,
            onToggleMulti = vm::toggleMultiSelectMode,
            onToggleNavigation = {
                canvasNavigationMode = !canvasNavigationMode
                if (canvasNavigationMode && vm.multiSelectMode) vm.toggleMultiSelectMode()
            },
            onSave = { ProjectStorage.save(context, vm.toProject()) },
            onExportJson = {
                pendingJson = ProjectStorage.projectJson(vm.toProject())
                exportJsonLauncher.launch("${vm.projectName}.json")
            },
            onExportProject = { exportZipLauncher.launch("${vm.projectName}.guiproject.zip") }
        )
    }


    if (showQuickActions && vm.selected != null && vm.selectedIds.size == 1) {
        QuickActionSheet(
            element = vm.selected!!,
            cropMode = cropMode,
            onDismiss = { showQuickActions = false },
            onCrop = {
                if (vm.selected?.type == ElementType.IMAGE) {
                    if (!cropMode) vm.beginTransaction()
                    cropMode = true
                }
                showQuickActions = false
            },
            onDuplicate = { vm.duplicateSelected(); showQuickActions = false },
            onFront = { vm.bringToFront(); showQuickActions = false },
            onBack = { vm.sendToBack(); showQuickActions = false },
            onLock = { vm.toggleLock(); showQuickActions = false },
            onProperties = { showQuickActions = false; showProperties = true },
            onDelete = { vm.deleteSelected(); showQuickActions = false }
        )
    }


    if (showAdaptation) {
        AdaptationCheckSheet(
            elements = vm.elements.toList(),
            customPreset = customPreset,
            onDismiss = { showAdaptation = false },
            onApplyCustom = { width, height ->
                customPreset = PreviewPreset("自定义 ${width.toInt()}×${height.toInt()}", width, height)
                canvasZoom = 1f
            },
            onUseDesign = {
                customPreset = null
                presetIndex = 0
                canvasZoom = 1f
            }
        )
    }

    if (showProperties && vm.selected != null && vm.selectedIds.size == 1) {
        PropertiesSheet(
            element = vm.selected!!,
            onDismiss = { showProperties = false },
            onAnchor = vm::setAnchor,
            onWidthMode = vm::setWidthMode,
            onHeightMode = vm::setHeightMode,
            onToggleBackground = vm::toggleBackground,
            onImageFit = vm::setImageFit,
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
private fun PortraitTopBar(
    projectName: String,
    preset: PreviewPreset,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPresetClick: () -> Unit,
    presetMenu: @Composable () -> Unit,
    onFit: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF171B22)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            projectName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        CompactButton("↶", onUndo)
        CompactButton("↷", onRedo)
        Box {
            CompactButton(preset.label.removePrefix("设计稿 "), onPresetClick, wide = true)
            presetMenu()
        }
        CompactButton("适配", onFit, wide = true, primary = true)
        CompactButton("⋯", onMore)
    }
}

@Composable
private fun PortraitBottomDock(
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onAddText: () -> Unit,
    onAddButton: () -> Unit,
    navigationMode: Boolean,
    onToggleNavigation: () -> Unit,
    onPages: () -> Unit,
    onLayers: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .background(Color(0xFF171B22))
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        PortraitDockButton("图片", onImport, primary = true)
        PortraitDockButton("文字", onAddText)
        PortraitDockButton("按钮", onAddButton)
        PortraitDockButton("画布", onToggleNavigation, primary = navigationMode)
        PortraitDockButton("页面", onPages)
        PortraitDockButton("图层", onLayers)
        PortraitDockButton("更多", onMore)
    }
}

@Composable
private fun PortraitDockButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(44.dp).width(48.dp),
        shape = RoundedCornerShape(11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFF2563EB) else Color(0xFF282E37)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CompactTopBar(
    projectName: String,
    preset: PreviewPreset,
    zoom: Float,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPresetClick: () -> Unit,
    presetMenu: @Composable () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF171B22)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            projectName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        CompactButton("↶", onUndo)
        CompactButton("↷", onRedo)
        Box {
            CompactButton(preset.label, onPresetClick, wide = true)
            presetMenu()
        }
        CompactButton("−", onZoomOut)
        Text("${(zoom * 100).toInt()}%", color = Color(0xFFCDD5E1), fontSize = 11.sp)
        CompactButton("+", onZoomIn)
        CompactButton("适配", onFit, wide = true, primary = true)
        CompactButton("⋯", onMore)
    }
}

@Composable
private fun CompactButton(
    text: String,
    onClick: () -> Unit,
    wide: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(36.dp).width(if (wide) 64.dp else 40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFF2563EB) else Color(0xFF2A303A),
            disabledContainerColor = Color(0xFF20242B),
            disabledContentColor = Color(0xFF657080)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text(text, fontSize = if (wide) 11.sp else 16.sp, maxLines = 1)
    }
}

@Composable
private fun ToolRailButton(
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(46.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF2563EB) else Color(0xE6222730),
            disabledContainerColor = Color(0x88222730),
            disabledContentColor = Color(0xFF5D6673)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MobileToolRail(
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onAddText: () -> Unit,
    onAddButton: () -> Unit,
    onAddPanel: () -> Unit,
    multiSelect: Boolean,
    navigationMode: Boolean,
    onToggleMulti: () -> Unit,
    onToggleNavigation: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xD9171B22)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolRailButton("图片", onImport)
        ToolRailButton("文字", onAddText)
        ToolRailButton("按钮", onAddButton)
        ToolRailButton("面板", onAddPanel)
        Spacer(Modifier.height(3.dp))
        ToolRailButton("多选", onToggleMulti, active = multiSelect)
        ToolRailButton("画布", onToggleNavigation, active = navigationMode)
    }
}

@Composable
private fun QuickRail(
    modifier: Modifier = Modifier,
    hasSelection: Boolean,
    onLayers: () -> Unit,
    onPages: () -> Unit,
    onLibrary: () -> Unit,
    onProperties: () -> Unit
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xD9171B22)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolRailButton("图层", onLayers)
        ToolRailButton("页面", onPages)
        ToolRailButton("素材", onLibrary)
        ToolRailButton("属性", onProperties, enabled = hasSelection)
    }
}

@Composable
private fun BoxScope.EditorModeBadge(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            .background(color, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = Color.White,
        fontSize = 11.sp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreToolsSheet(
    safeArea: Boolean,
    snapping: Boolean,
    multiSelect: Boolean,
    navigationMode: Boolean,
    onDismiss: () -> Unit,
    onAddPanel: () -> Unit,
    onLibrary: () -> Unit,
    onAdaptation: () -> Unit,
    onToggleSafe: () -> Unit,
    onToggleSnap: () -> Unit,
    onToggleMulti: () -> Unit,
    onToggleNavigation: () -> Unit,
    onSave: () -> Unit,
    onExportJson: () -> Unit,
    onExportProject: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF20242B)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("更多工具", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TinyButton("添加面板", onAddPanel, primary = true)
                TinyButton("素材库", onLibrary)
                TinyButton("适配检查", onAdaptation, primary = true)
            }
            Spacer(Modifier.height(14.dp))
            Text("画布与选择", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TinyButton(if (safeArea) "安全区 ✓" else "安全区", onToggleSafe, primary = safeArea)
                TinyButton(if (snapping) "吸附 ✓" else "吸附", onToggleSnap, primary = snapping)
                TinyButton(if (multiSelect) "多选 ✓" else "多选", onToggleMulti, primary = multiSelect)
                TinyButton(if (navigationMode) "画布手势 ✓" else "画布手势", onToggleNavigation, primary = navigationMode)
            }
            Spacer(Modifier.height(14.dp))
            Text("工程", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TinyButton("保存", onSave, primary = true)
                TinyButton("导出 JSON", onExportJson)
                TinyButton("导出工程", onExportProject)
            }
            Spacer(Modifier.height(22.dp))
        }
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
    onCanvasZoomChange: (Float) -> Unit,
    onElementLongPress: (String) -> Unit
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
            Modifier
                .pointerInput(preset.label, navigationMode, canvasZoom) {
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
                .pointerInput(preset.label, "doubleTapFit") {
                    detectTapGestures(
                        onDoubleTap = {
                            onCanvasZoomChange(1f)
                            scope.launch {
                                hScroll.animateScrollTo(0)
                                vScroll.animateScrollTo(0)
                            }
                        }
                    )
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
                    val safe = deviceSafeRect(preset.width, preset.height)
                    val core = core916Rect(preset.width, preset.height)

                    if (kotlin.math.abs(core.width - preset.width) > 1f || kotlin.math.abs(core.height - preset.height) > 1f) {
                        Box(
                            Modifier.offset((core.left * renderScale).dp, (core.top * renderScale).dp)
                                .requiredSize((core.width * renderScale).dp, (core.height * renderScale).dp)
                                .border(2.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                        )
                        Text(
                            "9:16 核心区",
                            color = Color(0xFFF59E0B),
                            fontSize = 10.sp,
                            modifier = Modifier.offset(
                                (core.left * renderScale + 8f).dp,
                                (core.top * renderScale + 6f).dp
                            )
                        )
                    }

                    Box(
                        Modifier.offset((safe.left * renderScale).dp, (safe.top * renderScale).dp)
                            .requiredSize((safe.width * renderScale).dp, (safe.height * renderScale).dp)
                            .border(2.dp, Color(0xFF10B981), RoundedCornerShape(8.dp))
                    )
                    Text(
                        "系统安全区",
                        color = Color(0xFF10B981),
                        fontSize = 10.sp,
                        modifier = Modifier.offset(
                            (safe.left * renderScale + 8f).dp,
                            (safe.top * renderScale + 6f).dp
                        )
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
                        onLongPress = { onElementLongPress(element.id) },
                        onBegin = vm::beginTransaction,
                        onEnd = vm::endTransaction,
                        onMove = { dx, dy -> vm.moveTransient(element.id, dx, dy) },
                        onResizeHandle = { handle, dx, dy ->
                            vm.resizeFromHandleTransient(
                                element.id,
                                handle,
                                dx,
                                dy,
                                keepRatio = element.type == ElementType.IMAGE
                            )
                        },
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
    onLongPress: () -> Unit,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResizeHandle: (String, Float, Float) -> Unit,
    onCrop: (Float, Float, Float, Float) -> Unit
) {
    val density = LocalDensity.current.density
    val borderColor = if (cropMode) Color(0xFF0F766E) else if (singleSelection) Color(0xFF2563EB) else Color(0xFF06B6D4)
    val baseModifier = Modifier
        .offset((rect.x * renderScale).dp, (rect.y * renderScale).dp)
        .requiredSize((rect.width * renderScale).dp, (rect.height * renderScale).dp)
        .then(if (selected) Modifier.border(2.dp, borderColor) else Modifier)
        .pointerInput(element.id, selected) {
            detectTapGestures(
                onTap = { onSelect() },
                onLongPress = { onSelect(); onLongPress() }
            )
        }
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

        if (selected && singleSelection && editable && !element.locked && !element.isBackground && !cropMode) {
            ResizeHandle(
                alignment = Alignment.TopStart,
                offsetX = -12,
                offsetY = -12,
                handle = "tl",
                elementId = element.id,
                renderScale = renderScale,
                density = density,
                onBegin = onBegin,
                onEnd = onEnd,
                onResizeHandle = onResizeHandle
            )
            ResizeHandle(
                alignment = Alignment.TopEnd,
                offsetX = 12,
                offsetY = -12,
                handle = "tr",
                elementId = element.id,
                renderScale = renderScale,
                density = density,
                onBegin = onBegin,
                onEnd = onEnd,
                onResizeHandle = onResizeHandle
            )
            ResizeHandle(
                alignment = Alignment.BottomStart,
                offsetX = -12,
                offsetY = 12,
                handle = "bl",
                elementId = element.id,
                renderScale = renderScale,
                density = density,
                onBegin = onBegin,
                onEnd = onEnd,
                onResizeHandle = onResizeHandle
            )
            ResizeHandle(
                alignment = Alignment.BottomEnd,
                offsetX = 12,
                offsetY = 12,
                handle = "br",
                elementId = element.id,
                renderScale = renderScale,
                density = density,
                onBegin = onBegin,
                onEnd = onEnd,
                onResizeHandle = onResizeHandle
            )
        }
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    alignment: Alignment,
    offsetX: Int,
    offsetY: Int,
    handle: String,
    elementId: String,
    renderScale: Float,
    density: Float,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    onResizeHandle: (String, Float, Float) -> Unit
) {
    Box(
        Modifier.align(alignment)
            .offset(offsetX.dp, offsetY.dp)
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White)
            .border(4.dp, Color(0xFF2563EB), RoundedCornerShape(50))
            .pointerInput(elementId, handle, renderScale) {
                detectDragGestures(
                    onDragStart = { onBegin() },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                    onDrag = { change, amount ->
                        change.consume()
                        onResizeHandle(
                            handle,
                            amount.x / density / renderScale,
                            amount.y / density / renderScale
                        )
                    }
                )
            }
    )
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
            contentScale = when (element.imageFit) {
                ImageFit.COVER -> ContentScale.Crop
                ImageFit.CONTAIN -> ContentScale.Fit
                ImageFit.FILL -> ContentScale.FillBounds
                ImageFit.FIT_WIDTH -> ContentScale.FillWidth
                ImageFit.FIT_HEIGHT -> ContentScale.FillHeight
            },
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
    onResetImage: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onNudge: (Float, Float) -> Unit,
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
            TinyButton(if (cropMode) "完成裁剪" else "裁剪", onToggleCrop, primary = cropMode)
            if (cropMode) {
                TinyButton("重置", onResetImage)
                TinyButton("左转", onRotateLeft)
                TinyButton("右转", onRotateRight)
            }
        }
        if (editable && groupEditActive) {
            TinyButton("退出组内编辑", onExitGroupEdit, primary = true)
        } else if (editable && selectedGrouped) {
            TinyButton("编辑组内", onEnterGroupEdit, primary = true)
        }

        if (editable) {
            if (selectedCount == 1 && !cropMode && selected?.isBackground != true) {
                TinyButton("←", { onNudge(-4f, 0f) })
                TinyButton("↑", { onNudge(0f, -4f) })
                TinyButton("↓", { onNudge(0f, 4f) })
                TinyButton("→", { onNudge(4f, 0f) })
            }
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
private fun QuickActionSheet(
    element: EditorElement,
    cropMode: Boolean,
    onDismiss: () -> Unit,
    onCrop: () -> Unit,
    onDuplicate: () -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onProperties: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF20242B)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(element.name, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "长按快捷操作",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (element.type == ElementType.IMAGE && !element.isBackground) {
                    TinyButton(if (cropMode) "继续裁剪" else "裁剪图片", onCrop, primary = true)
                }
                TinyButton("属性/适配", onProperties, primary = true)
                TinyButton("复制", onDuplicate)
                TinyButton("置顶", onFront)
                TinyButton("置底", onBack)
                TinyButton(if (element.locked) "解锁" else "锁定", onLock)
                TinyButton("删除", onDelete)
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdaptationCheckSheet(
    elements: List<EditorElement>,
    customPreset: PreviewPreset?,
    onDismiss: () -> Unit,
    onApplyCustom: (Float, Float) -> Unit,
    onUseDesign: () -> Unit
) {
    var widthText by remember(customPreset) { mutableStateOf((customPreset?.width ?: 1080f).toInt().toString()) }
    var heightText by remember(customPreset) { mutableStateOf((customPreset?.height ?: 2400f).toInt().toString()) }
    val issues = validateAllPresets(elements, customPreset)
    val grouped = issues.groupBy { it.presetLabel }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF20242B)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)
        ) {
            Text("一键适配检查", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "9:16 是设计母版；这里会同时检查主流长屏、超长屏和竖屏平板。",
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            PREVIEW_PRESETS.forEach { preset ->
                val list = grouped[preset.label].orEmpty()
                val errors = list.count { it.severity == AdaptationSeverity.ERROR }
                val warnings = list.count { it.severity == AdaptationSeverity.WARNING }
                val infos = list.count { it.severity == AdaptationSeverity.INFO }
                Column(
                    Modifier.fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF292E37))
                        .padding(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${preset.label} · ${preset.width.toInt()}×${preset.height.toInt()}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            when {
                                errors > 0 -> "$errors 错误"
                                warnings > 0 -> "$warnings 警告"
                                else -> "通过"
                            },
                            color = when {
                                errors > 0 -> Color(0xFFEF4444)
                                warnings > 0 -> Color(0xFFF59E0B)
                                else -> Color(0xFF10B981)
                            },
                            fontSize = 12.sp
                        )
                    }
                    list.take(4).forEach { issue ->
                        val prefix = when (issue.severity) {
                            AdaptationSeverity.ERROR -> "●"
                            AdaptationSeverity.WARNING -> "▲"
                            AdaptationSeverity.INFO -> "·"
                        }
                        Text(
                            "$prefix ${issue.message}",
                            color = when (issue.severity) {
                                AdaptationSeverity.ERROR -> Color(0xFFFF8A8A)
                                AdaptationSeverity.WARNING -> Color(0xFFFBBF24)
                                AdaptationSeverity.INFO -> Color(0xFFB6BDC8)
                            },
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (list.size > 4) {
                        Text("另有 ${list.size - 4} 条", fontSize = 10.sp, color = Color(0xFF8B95A5))
                    }
                    if (infos > 0 && errors == 0 && warnings == 0) {
                        Text("信息提示 $infos 条", fontSize = 10.sp, color = Color(0xFF8B95A5))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("自定义设备预览", fontWeight = FontWeight.Bold)
            Text(
                "可直接输入任意竖屏分辨率，例如 1080×2376、1440×3200。",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit).take(5) },
                    label = { Text("宽") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit).take(5) },
                    label = { Text("高") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinyButton("预览自定义", {
                    val w = widthText.toFloatOrNull()
                    val h = heightText.toFloatOrNull()
                    if (w != null && h != null && w in 320f..5000f && h in 480f..7000f) {
                        onApplyCustom(w, h)
                        onDismiss()
                    }
                }, primary = true)
                TinyButton("回到 9:16 编辑", {
                    onUseDesign()
                    onDismiss()
                })
            }

            Spacer(Modifier.height(16.dp))
            Text("边界说明", fontWeight = FontWeight.Bold)
            Text("绿色框：系统安全区；橙色框：9:16 核心 UI 区。长屏多出来的区域优先留给背景和装饰。", fontSize = 11.sp, color = Color(0xFFB6BDC8), modifier = Modifier.padding(top = 5.dp))
            Spacer(Modifier.height(26.dp))
        }
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
    onToggleBackground: () -> Unit,
    onImageFit: (ImageFit) -> Unit,
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
                Text("图片适配", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TinyButton(if (element.isBackground) "取消背景" else "设为背景", onToggleBackground, primary = element.isBackground)
                    ImageFit.entries.forEach { fit ->
                        val label = when (fit) {
                            ImageFit.COVER -> "铺满裁剪"
                            ImageFit.CONTAIN -> "完整显示"
                            ImageFit.FILL -> "拉伸"
                            ImageFit.FIT_WIDTH -> "适应宽"
                            ImageFit.FIT_HEIGHT -> "适应高"
                        }
                        TinyButton(label, { onImageFit(fit) }, primary = element.imageFit == fit)
                    }
                }
                if (element.isBackground) {
                    Text(
                        "背景会自动覆盖当前预览屏幕；长屏只扩展背景，不强行拉动核心 UI。",
                        fontSize = 11.sp,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

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
