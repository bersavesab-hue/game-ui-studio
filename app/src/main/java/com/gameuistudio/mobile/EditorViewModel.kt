package com.gameuistudio.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class EditorViewModel : ViewModel() {
    val elements = mutableStateListOf<EditorElement>()
    val pages = mutableStateListOf<EditorPage>()
    val assetLibrary = mutableStateListOf<AssetRecord>()
    val components = mutableStateListOf<ComponentTemplate>()
    val pageTemplates = mutableStateListOf<PageTemplate>()
    val selectedIds = mutableStateListOf<String>()

    var projectName by mutableStateOf("未命名 UI 工程")
        private set
    var currentPageId by mutableStateOf<String?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var loaded by mutableStateOf(false)
        private set

    var snappingEnabled by mutableStateOf(true)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    var snapGuideX by mutableStateOf<Float?>(null)
        private set
    var snapGuideY by mutableStateOf<Float?>(null)
        private set
    var activeGroupEditId by mutableStateOf<String?>(null)
        private set

    private data class Snapshot(
        val projectName: String,
        val pages: List<EditorPage>,
        val currentPageId: String?,
        val assetLibrary: List<AssetRecord>,
        val components: List<ComponentTemplate>,
        val pageTemplates: List<PageTemplate>,
        val selectedIds: List<String>,
        val activeGroupEditId: String?
    )

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var transactionOpen = false

    val selectedId: String?
        get() = selectedIds.lastOrNull()

    val selected: EditorElement?
        get() = selectedId?.let { id -> elements.firstOrNull { it.id == id } }

    val selection: List<EditorElement>
        get() = elements.filter { it.id in selectedIds }

    val currentPage: EditorPage?
        get() = currentPageId?.let { id -> pages.firstOrNull { it.id == id } }

    private fun migratePortraitProject(project: ProjectData?): ProjectData? {
        if (sourceProject == null) return null
        if (project.designWidth <= project.designHeight) return project

        val sx = DESIGN_WIDTH / project.designWidth.coerceAtLeast(1f)
        val sy = DESIGN_HEIGHT / project.designHeight.coerceAtLeast(1f)

        fun migrateElement(e: EditorElement): EditorElement = e.copy(
            x = (e.x * sx).coerceIn(0f, DESIGN_WIDTH),
            y = (e.y * sy).coerceIn(0f, DESIGN_HEIGHT),
            width = (e.width * sx).coerceIn(40f, DESIGN_WIDTH),
            height = (e.height * sy).coerceIn(40f, DESIGN_HEIGHT),
            fontSize = (e.fontSize * min(sx, sy)).coerceAtLeast(12f),
            cornerRadius = e.cornerRadius * min(sx, sy),
            cropOffsetX = e.cropOffsetX * sx,
            cropOffsetY = e.cropOffsetY * sy
        )

        return project.copy(
            version = 4,
            designWidth = DESIGN_WIDTH,
            designHeight = DESIGN_HEIGHT,
            pages = sourceProject.pages.map { page -> page.copy(elements = page.elements.map(::migrateElement)) },
            components = project.components.map { component ->
                component.copy(
                    width = component.width * sx,
                    height = component.height * sy,
                    elements = component.elements.map(::migrateElement)
                )
            },
            pageTemplates = project.pageTemplates.map { template ->
                template.copy(elements = template.elements.map(::migrateElement))
            },
            elements = sourceProject.elements.map(::migrateElement)
        )
    }

    fun loadProject(project: ProjectData?) {
        val sourceProject = migratePortraitProject(project)
        elements.clear()
        pages.clear()
        assetLibrary.clear()
        components.clear()
        pageTemplates.clear()
        selectedIds.clear()
        activeGroupEditId = null

        projectName = sourceProject?.projectName ?: "未命名 UI 工程"
        val loadedPages = when {
            sourceProject == null -> emptyList()
            sourceProject.pages.isNotEmpty() -> sourceProject.pages
            sourceProject.elements.isNotEmpty() -> listOf(
                EditorPage(UUID.randomUUID().toString(), "页面 1", sourceProject.elements.sortedBy { it.zIndex })
            )
            else -> emptyList()
        }
        if (loadedPages.isEmpty()) {
            pages += EditorPage(UUID.randomUUID().toString(), "页面 1")
        } else {
            pages.addAll(loadedPages)
        }

        currentPageId = sourceProject?.currentPageId?.takeIf { wanted -> pages.any { it.id == wanted } } ?: pages.first().id

        val loadedAssets = sourceProject?.assetLibrary.orEmpty()
        if (loadedAssets.isNotEmpty()) {
            assetLibrary.addAll(loadedAssets)
        } else {
            val derived = (sourceProject?.allElements().orEmpty()).filter { it.type == ElementType.IMAGE && it.assetPath != null }
                .distinctBy { it.assetPath }
                .map { e -> AssetRecord(e.assetId ?: UUID.randomUUID().toString(), e.name, e.assetPath!!, (e.width / e.height.coerceAtLeast(1f)).coerceAtLeast(0.05f)) }
            assetLibrary.addAll(derived)
        }
        components.addAll(sourceProject?.components.orEmpty())
        pageTemplates.addAll(sourceProject?.pageTemplates.orEmpty())
        loadActivePageElements()
        undoStack.clear()
        redoStack.clear()
        clearSnapGuides()
        loaded = true
    }

    fun toProject(): ProjectData {
        syncCurrentPage()
        return ProjectData(
            version = 4,
            projectName = projectName,
            pages = pages.toList(),
            currentPageId = currentPageId,
            assetLibrary = assetLibrary.toList(),
            components = components.toList(),
            pageTemplates = pageTemplates.toList(),
            elements = emptyList()
        )
    }

    fun renameProject(name: String) {
        mutate { projectName = name.ifBlank { "未命名 UI 工程" } }
    }

    fun toggleSnapping() {
        snappingEnabled = !snappingEnabled
        if (!snappingEnabled) clearSnapGuides()
    }

    fun toggleMultiSelectMode() {
        multiSelectMode = !multiSelectMode
        if (!multiSelectMode && selectedIds.size > 1) {
            val keep = selectedId
            selectedIds.clear()
            if (keep != null) selectedIds += keep
        }
    }

    fun isSelected(id: String): Boolean = id in selectedIds

    fun select(id: String?) {
        if (id == null) {
            selectedIds.clear()
            return
        }
        selectElement(id, additive = multiSelectMode)
    }

    fun selectElement(id: String, additive: Boolean = multiSelectMode) {
        val tapped = elements.firstOrNull { it.id == id } ?: return
        if (activeGroupEditId != null && tapped.groupId != activeGroupEditId) activeGroupEditId = null
        val editingThisGroup = activeGroupEditId != null && tapped.groupId == activeGroupEditId
        val groupMembers = if (editingThisGroup) {
            listOf(id)
        } else {
            tapped.groupId?.let { group -> elements.filter { it.groupId == group }.map { it.id } } ?: listOf(id)
        }

        if (additive) {
            val allSelected = groupMembers.all { it in selectedIds }
            if (allSelected) selectedIds.removeAll(groupMembers.toSet())
            else groupMembers.forEach { if (it !in selectedIds) selectedIds += it }
        } else {
            selectedIds.clear()
            selectedIds.addAll(groupMembers)
        }
    }

    fun selectRect(left: Float, top: Float, right: Float, bottom: Float, additive: Boolean = false) {
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        val hits = elements.filter { e ->
            !e.hidden && e.x < r && e.x + e.width > l && e.y < b && e.y + e.height > t
        }.flatMap { hit ->
            hit.groupId?.let { group -> elements.filter { it.groupId == group }.map { it.id } } ?: listOf(hit.id)
        }.distinct()

        if (!additive) selectedIds.clear()
        hits.forEach { if (it !in selectedIds) selectedIds += it }
    }

    fun beginTransaction() {
        if (transactionOpen) return
        pushUndoSnapshot()
        transactionOpen = true
    }

    fun endTransaction() {
        if (!transactionOpen) return
        transactionOpen = false
        syncCurrentPage()
        clearSnapGuides()
        redoStack.clear()
        revision++
    }

    fun addImage(assetPath: String, displayName: String, aspectRatio: Float = 16f / 9f) {
        mutate {
            val asset = assetLibrary.firstOrNull { it.path == assetPath } ?: AssetRecord(
                id = UUID.randomUUID().toString(),
                name = displayName,
                path = assetPath,
                aspectRatio = aspectRatio.coerceAtLeast(0.05f)
            ).also { assetLibrary += it }
            addImageElement(asset, elements.size)
        }
    }

    fun insertAsset(assetId: String) {
        val asset = assetLibrary.firstOrNull { it.id == assetId } ?: return
        mutate { addImageElement(asset, elements.size) }
    }

    private fun addImageElement(asset: AssetRecord, index: Int) {
        var w = 620f
        var h = (w / asset.aspectRatio.coerceAtLeast(0.1f)).coerceAtLeast(80f)
        if (h > 620f) {
            h = 620f
            w = (h * asset.aspectRatio.coerceAtLeast(0.1f)).coerceAtLeast(80f)
        }
        val item = EditorElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.IMAGE,
            name = asset.name,
            x = ((DESIGN_WIDTH - w) / 2f + (index % 4) * 24f).coerceIn(0f, DESIGN_WIDTH - w),
            y = ((DESIGN_HEIGHT - h) / 2f + (index % 4) * 24f).coerceIn(0f, DESIGN_HEIGHT - h),
            width = w,
            height = h,
            zIndex = nextZ(),
            assetPath = asset.path,
            assetId = asset.id
        )
        elements += item
        setSelection(listOf(item.id))
    }

    fun addText() = addSimpleElement(ElementType.TEXT, "文字", 420f, 100f, text = "文字", fontSize = 48f)

    fun addButton() = addSimpleElement(ElementType.BUTTON, "按钮", 300f, 112f, text = "按钮", fontSize = 42f, radius = 22f)

    fun addPanel() = addSimpleElement(ElementType.PANEL, "面板", 560f, 320f, radius = 28f, opacity = 0.9f)

    private fun addSimpleElement(
        type: ElementType,
        name: String,
        w: Float,
        h: Float,
        text: String = "",
        fontSize: Float = 42f,
        radius: Float = 0f,
        opacity: Float = 1f
    ) {
        mutate {
            val item = EditorElement(
                id = UUID.randomUUID().toString(), type = type, name = name,
                x = (DESIGN_WIDTH - w) / 2f, y = (DESIGN_HEIGHT - h) / 2f,
                width = w, height = h, zIndex = nextZ(), text = text,
                fontSize = fontSize, cornerRadius = radius, opacity = opacity
            )
            elements += item
            setSelection(listOf(item.id))
        }
    }

    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toSet()
        mutate {
            elements.removeAll { it.id in ids }
            selectedIds.clear()
        }
    }

    fun duplicateSelected() {
        val source = selection.sortedBy { it.zIndex }
        if (source.isEmpty()) return
        mutate {
            val groupMap = mutableMapOf<String, String>()
            val copies = source.map { current ->
                val newGroup = current.groupId?.let { old -> groupMap.getOrPut(old) { UUID.randomUUID().toString() } }
                current.copy(
                    id = UUID.randomUUID().toString(),
                    name = current.name + " 副本",
                    x = (current.x + 36f).coerceAtMost(DESIGN_WIDTH - current.width),
                    y = (current.y + 36f).coerceAtMost(DESIGN_HEIGHT - current.height),
                    zIndex = nextZ() + source.indexOf(current),
                    groupId = newGroup
                )
            }
            elements.addAll(copies)
            setSelection(copies.map { it.id })
            normalizeZ()
        }
    }

    fun bringToFront() {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        mutate {
            var z = nextZ()
            elements.filter { it.id in ids }.sortedBy { it.zIndex }.forEach { item ->
                replace(item.id) { it.copy(zIndex = z++) }
            }
            normalizeZ()
        }
    }

    fun sendToBack() {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        mutate {
            var z = -ids.size
            elements.filter { it.id in ids }.sortedBy { it.zIndex }.forEach { item ->
                replace(item.id) { it.copy(zIndex = z++) }
            }
            normalizeZ()
        }
    }

    fun moveLayerUp(id: String) {
        mutate {
            val sorted = elements.sortedBy { it.zIndex }.toMutableList()
            val index = sorted.indexOfFirst { it.id == id }
            if (index >= 0 && index < sorted.lastIndex) {
                val tmp = sorted[index + 1]
                sorted[index + 1] = sorted[index]
                sorted[index] = tmp
                applyLayerOrder(sorted)
            }
        }
    }

    fun moveLayerDown(id: String) {
        mutate {
            val sorted = elements.sortedBy { it.zIndex }.toMutableList()
            val index = sorted.indexOfFirst { it.id == id }
            if (index > 0) {
                val tmp = sorted[index - 1]
                sorted[index - 1] = sorted[index]
                sorted[index] = tmp
                applyLayerOrder(sorted)
            }
        }
    }

    fun toggleVisibility(id: String) {
        mutate { replace(id) { it.copy(hidden = !it.hidden) } }
    }

    fun toggleLock(id: String) {
        mutate { replace(id) { it.copy(locked = !it.locked) } }
    }

    fun toggleLock() {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        val shouldLock = selection.any { !it.locked }
        mutate {
            ids.forEach { id -> replace(id) { it.copy(locked = shouldLock) } }
        }
    }

    fun groupSelected() {
        if (selectedIds.size < 2) return
        val group = UUID.randomUUID().toString()
        val ids = selectedIds.toSet()
        mutate {
            ids.forEach { id -> replace(id) { it.copy(groupId = group) } }
            activeGroupEditId = null
        }
    }

    fun ungroupSelected() {
        if (selectedIds.isEmpty()) return
        val groups = selection.mapNotNull { it.groupId }.toSet()
        if (groups.isEmpty()) return
        mutate {
            elements.toList().filter { it.groupId in groups }.forEach { item ->
                replace(item.id) { it.copy(groupId = null) }
            }
            if (activeGroupEditId?.let { it in groups } == true) activeGroupEditId = null
        }
    }

    fun setAnchor(anchor: Anchor) {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        mutate { ids.forEach { id -> replace(id) { it.copy(anchor = anchor) } } }
    }

    fun setWidthMode(mode: SizeMode) {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        mutate { ids.forEach { id -> replace(id) { it.copy(widthMode = mode) } } }
    }

    fun setHeightMode(mode: SizeMode) {
        val ids = selectedIds.toSet()
        if (ids.isEmpty()) return
        mutate { ids.forEach { id -> replace(id) { it.copy(heightMode = mode) } } }
    }

    fun alignSelected(command: String) {
        val items = selection.filterNot { it.locked }
        if (items.isEmpty()) return
        mutate {
            if (items.size == 1) {
                val e = items.first()
                replace(e.id) {
                    when (command) {
                        "left" -> it.copy(x = 0f)
                        "centerX" -> it.copy(x = (DESIGN_WIDTH - it.width) / 2f)
                        "right" -> it.copy(x = DESIGN_WIDTH - it.width)
                        "top" -> it.copy(y = 0f)
                        "centerY" -> it.copy(y = (DESIGN_HEIGHT - it.height) / 2f)
                        "bottom" -> it.copy(y = DESIGN_HEIGHT - it.height)
                        else -> it
                    }
                }
            } else {
                val bounds = boundsOf(items)
                items.forEach { e ->
                    replace(e.id) {
                        when (command) {
                            "left" -> it.copy(x = bounds.left)
                            "centerX" -> it.copy(x = bounds.centerX - it.width / 2f)
                            "right" -> it.copy(x = bounds.right - it.width)
                            "top" -> it.copy(y = bounds.top)
                            "centerY" -> it.copy(y = bounds.centerY - it.height / 2f)
                            "bottom" -> it.copy(y = bounds.bottom - it.height)
                            else -> it
                        }
                    }
                }
            }
        }
    }

    fun distributeSelected(horizontal: Boolean) {
        val items = selection.filterNot { it.locked }
        if (items.size < 3) return
        mutate {
            if (horizontal) {
                val sorted = items.sortedBy { it.x }
                val left = sorted.first().x
                val right = sorted.last().x + sorted.last().width
                val totalWidth = sorted.sumOf { it.width.toDouble() }.toFloat()
                val gap = ((right - left - totalWidth) / (sorted.size - 1)).coerceAtLeast(0f)
                var cursor = left
                sorted.forEach { item ->
                    replace(item.id) { it.copy(x = cursor) }
                    cursor += item.width + gap
                }
            } else {
                val sorted = items.sortedBy { it.y }
                val top = sorted.first().y
                val bottom = sorted.last().y + sorted.last().height
                val totalHeight = sorted.sumOf { it.height.toDouble() }.toFloat()
                val gap = ((bottom - top - totalHeight) / (sorted.size - 1)).coerceAtLeast(0f)
                var cursor = top
                sorted.forEach { item ->
                    replace(item.id) { it.copy(y = cursor) }
                    cursor += item.height + gap
                }
            }
        }
    }

    fun moveTransient(id: String, dx: Float, dy: Float) {
        val current = elements.firstOrNull { it.id == id } ?: return
        if (current.locked) return
        if (id !in selectedIds) setSelection(listOf(id))

        val moving = selection.filterNot { it.locked }
        if (moving.isEmpty()) return
        val bounds = boundsOf(moving)
        var adjustedDx = dx.coerceIn(-bounds.left, DESIGN_WIDTH - bounds.right)
        var adjustedDy = dy.coerceIn(-bounds.top, DESIGN_HEIGHT - bounds.bottom)

        if (snappingEnabled) {
            val xSnap = bestSelectionXSnap(moving, bounds, adjustedDx)
            val ySnap = bestSelectionYSnap(moving, bounds, adjustedDy)
            if (xSnap != null) {
                adjustedDx = xSnap.value
                snapGuideX = xSnap.guide
            } else snapGuideX = null
            if (ySnap != null) {
                adjustedDy = ySnap.value
                snapGuideY = ySnap.guide
            } else snapGuideY = null
        } else clearSnapGuides()

        moving.forEach { item -> replace(item.id) { it.copy(x = it.x + adjustedDx, y = it.y + adjustedDy) } }
    }

    fun resizeTransient(id: String, dw: Float, dh: Float, keepRatio: Boolean = false) {
        if (selectedIds.size > 1) return
        replace(id) { e ->
            if (e.locked) return@replace e
            val ratio = if (e.height == 0f) 1f else e.width / e.height
            var nw = (e.width + dw).coerceIn(40f, DESIGN_WIDTH - e.x)
            var nh = (e.height + dh).coerceIn(40f, DESIGN_HEIGHT - e.y)
            if (keepRatio) {
                if (abs(dw) >= abs(dh)) nh = (nw / ratio).coerceAtMost(DESIGN_HEIGHT - e.y)
                else nw = (nh * ratio).coerceAtMost(DESIGN_WIDTH - e.x)
            }
            e.copy(width = nw, height = nh)
        }
    }

    fun updateCropTransient(id: String, panX: Float, panY: Float, zoomFactor: Float, rotationDelta: Float) {
        if (selectedIds.size > 1) return
        replace(id) { e ->
            if (e.type != ElementType.IMAGE || e.locked) e else e.copy(
                cropOffsetX = (e.cropOffsetX + panX).coerceIn(-1800f, 1800f),
                cropOffsetY = (e.cropOffsetY + panY).coerceIn(-1200f, 1200f),
                cropZoom = (e.cropZoom * zoomFactor).coerceIn(0.25f, 8f),
                rotation = e.rotation + rotationDelta
            )
        }
    }

    fun rotateSelected(degrees: Float) {
        val id = selectedId ?: return
        if (selectedIds.size != 1) return
        mutate { replace(id) { it.copy(rotation = it.rotation + degrees) } }
    }

    fun flipSelected(horizontal: Boolean) {
        val id = selectedId ?: return
        if (selectedIds.size != 1) return
        mutate { replace(id) { if (horizontal) it.copy(flipX = !it.flipX) else it.copy(flipY = !it.flipY) } }
    }

    fun resetImageTransform() {
        val id = selectedId ?: return
        if (selectedIds.size != 1) return
        mutate {
            replace(id) {
                it.copy(
                    cropZoom = 1f, cropOffsetX = 0f, cropOffsetY = 0f, rotation = 0f,
                    flipX = false, flipY = false, opacity = 1f, brightness = 1f,
                    contrast = 1f, saturation = 1f, cornerRadius = 0f
                )
            }
        }
    }

    fun updateImageStyleTransient(
        opacity: Float? = null,
        radius: Float? = null,
        brightness: Float? = null,
        contrast: Float? = null,
        saturation: Float? = null
    ) {
        val id = selectedId ?: return
        if (selectedIds.size != 1) return
        replace(id) { e ->
            e.copy(
                opacity = opacity ?: e.opacity,
                cornerRadius = radius ?: e.cornerRadius,
                brightness = brightness ?: e.brightness,
                contrast = contrast ?: e.contrast,
                saturation = saturation ?: e.saturation
            )
        }
    }

    fun setText(value: String) {
        val id = selectedId ?: return
        if (selectedIds.size != 1) return
        mutate { replace(id) { it.copy(text = value, name = value.ifBlank { it.name }) } }
    }

    // Asset library / components / page templates / group inner edit
    fun deleteAsset(assetId: String) {
        val asset = assetLibrary.firstOrNull { it.id == assetId } ?: return
        val used = pages.any { p -> p.elements.any { it.assetPath == asset.path } } ||
            elements.any { it.assetPath == asset.path } ||
            components.any { c -> c.elements.any { it.assetPath == asset.path } } ||
            pageTemplates.any { t -> t.elements.any { it.assetPath == asset.path } }
        if (used) return
        mutate { assetLibrary.removeAll { it.id == assetId } }
    }

    fun saveSelectionAsComponent() {
        val source = selection.sortedBy { it.zIndex }
        if (source.isEmpty()) return
        val bounds = boundsOf(source)
        mutate {
            val normalized = source.mapIndexed { index, e ->
                e.copy(
                    id = UUID.randomUUID().toString(),
                    x = e.x - bounds.left,
                    y = e.y - bounds.top,
                    zIndex = index,
                    groupId = null,
                    locked = false,
                    hidden = false
                )
            }
            components += ComponentTemplate(
                id = UUID.randomUUID().toString(),
                name = "组件 ${components.size + 1}",
                width = bounds.width.coerceAtLeast(40f),
                height = bounds.height.coerceAtLeast(40f),
                elements = normalized
            )
        }
    }

    fun insertComponent(componentId: String) {
        val component = components.firstOrNull { it.id == componentId } ?: return
        mutate {
            val groupId = UUID.randomUUID().toString()
            val originX = ((DESIGN_WIDTH - component.width) / 2f).coerceAtLeast(0f)
            val originY = ((DESIGN_HEIGHT - component.height) / 2f).coerceAtLeast(0f)
            val startZ = nextZ()
            val copies = component.elements.mapIndexed { index, e ->
                e.copy(
                    id = UUID.randomUUID().toString(),
                    x = (originX + e.x).coerceIn(0f, DESIGN_WIDTH - e.width),
                    y = (originY + e.y).coerceIn(0f, DESIGN_HEIGHT - e.height),
                    zIndex = startZ + index,
                    groupId = groupId,
                    locked = false,
                    hidden = false
                )
            }
            elements.addAll(copies)
            setSelection(copies.map { it.id })
            normalizeZ()
        }
    }

    fun deleteComponent(componentId: String) {
        mutate { components.removeAll { it.id == componentId } }
    }

    fun saveCurrentPageAsTemplate() {
        val source = snapshotCurrentPage() ?: return
        mutate {
            val cloned = source.elements.map { e -> e.copy(id = UUID.randomUUID().toString()) }
            pageTemplates += PageTemplate(UUID.randomUUID().toString(), "模板 ${pageTemplates.size + 1}", cloned)
        }
    }

    fun createPageFromTemplate(templateId: String) {
        val template = pageTemplates.firstOrNull { it.id == templateId } ?: return
        mutate {
            syncCurrentPage()
            val groupMap = mutableMapOf<String, String>()
            val cloned = template.elements.map { e ->
                e.copy(
                    id = UUID.randomUUID().toString(),
                    groupId = e.groupId?.let { old -> groupMap.getOrPut(old) { UUID.randomUUID().toString() } }
                )
            }
            val page = EditorPage(UUID.randomUUID().toString(), template.name + " 页面", cloned)
            pages += page
            currentPageId = page.id
            elements.clear(); elements.addAll(cloned.sortedBy { it.zIndex })
            selectedIds.clear()
            activeGroupEditId = null
        }
    }

    fun deletePageTemplate(templateId: String) {
        mutate { pageTemplates.removeAll { it.id == templateId } }
    }

    fun enterSelectedGroupEdit() {
        val group = selection.mapNotNull { it.groupId }.distinct().singleOrNull() ?: return
        activeGroupEditId = group
        val keep = selectedId?.takeIf { id -> elements.firstOrNull { it.id == id }?.groupId == group }
            ?: elements.firstOrNull { it.groupId == group }?.id
        selectedIds.clear()
        if (keep != null) selectedIds += keep
        revision++
    }

    fun exitGroupEdit() {
        val group = activeGroupEditId ?: return
        activeGroupEditId = null
        selectedIds.clear()
        selectedIds.addAll(elements.filter { it.groupId == group }.map { it.id })
        revision++
    }

    // Page system
    fun addPage() {
        mutate {
            syncCurrentPage()
            val page = EditorPage(UUID.randomUUID().toString(), "页面 ${pages.size + 1}")
            pages += page
            currentPageId = page.id
            elements.clear()
            selectedIds.clear()
            activeGroupEditId = null
        }
    }

    fun duplicateCurrentPage() {
        val source = snapshotCurrentPage() ?: return
        mutate {
            val groupMap = mutableMapOf<String, String>()
            val cloned = source.elements.map { e ->
                e.copy(
                    id = UUID.randomUUID().toString(),
                    groupId = e.groupId?.let { old -> groupMap.getOrPut(old) { UUID.randomUUID().toString() } }
                )
            }
            val page = EditorPage(UUID.randomUUID().toString(), source.name + " 副本", cloned)
            pages += page
            currentPageId = page.id
            elements.clear(); elements.addAll(cloned)
            selectedIds.clear()
            activeGroupEditId = null
        }
    }

    fun switchPage(id: String) {
        if (id == currentPageId || pages.none { it.id == id }) return
        syncCurrentPage()
        currentPageId = id
        loadActivePageElements()
        selectedIds.clear()
        activeGroupEditId = null
        clearSnapGuides()
        transactionOpen = false
        revision++
    }

    fun renameCurrentPage(name: String) {
        val id = currentPageId ?: return
        mutate {
            syncCurrentPage()
            val index = pages.indexOfFirst { it.id == id }
            if (index >= 0) pages[index] = pages[index].copy(name = name.ifBlank { pages[index].name })
        }
    }

    fun deleteCurrentPage() {
        val id = currentPageId ?: return
        if (pages.size <= 1) return
        mutate {
            syncCurrentPage()
            val index = pages.indexOfFirst { it.id == id }
            if (index >= 0) pages.removeAt(index)
            val next = pages.getOrNull(index.coerceAtMost(pages.lastIndex)) ?: pages.first()
            currentPageId = next.id
            elements.clear(); elements.addAll(next.elements.sortedBy { it.zIndex })
            selectedIds.clear()
            activeGroupEditId = null
        }
    }

    fun undo() {
        if (transactionOpen || undoStack.isEmpty()) return
        redoStack.add(makeSnapshot())
        restoreSnapshot(undoStack.removeLast())
        revision++
    }

    fun redo() {
        if (transactionOpen || redoStack.isEmpty()) return
        undoStack.add(makeSnapshot())
        restoreSnapshot(redoStack.removeLast())
        revision++
    }

    fun clearProject() {
        mutate {
            pages.clear()
            assetLibrary.clear()
            components.clear()
            pageTemplates.clear()
            val page = EditorPage(UUID.randomUUID().toString(), "页面 1")
            pages += page
            currentPageId = page.id
            elements.clear()
            selectedIds.clear()
            activeGroupEditId = null
            projectName = "未命名 UI 工程"
        }
    }

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val centerX get() = (left + right) / 2f
        val centerY get() = (top + bottom) / 2f
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun boundsOf(items: List<EditorElement>): Bounds = Bounds(
        left = items.minOf { it.x },
        top = items.minOf { it.y },
        right = items.maxOf { it.x + it.width },
        bottom = items.maxOf { it.y + it.height }
    )

    private data class SnapCandidate(val value: Float, val guide: Float)

    private fun bestSelectionXSnap(moving: List<EditorElement>, bounds: Bounds, proposedDx: Float): SnapCandidate? {
        val threshold = 12f
        val excluded = moving.map { it.id }.toSet()
        val candidates = mutableListOf(
            SnapCandidate(-bounds.left, 0f),
            SnapCandidate(DESIGN_WIDTH / 2f - bounds.centerX, DESIGN_WIDTH / 2f),
            SnapCandidate(DESIGN_WIDTH - bounds.right, DESIGN_WIDTH)
        )
        elements.filter { it.id !in excluded && !it.hidden }.forEach { other ->
            val targetEdges = listOf(other.x, other.x + other.width / 2f, other.x + other.width)
            targetEdges.forEach { target ->
                candidates += SnapCandidate(target - bounds.left, target)
                candidates += SnapCandidate(target - bounds.centerX, target)
                candidates += SnapCandidate(target - bounds.right, target)
            }
        }
        return candidates.minByOrNull { abs(it.value - proposedDx) }?.takeIf { abs(it.value - proposedDx) <= threshold }
    }

    private fun bestSelectionYSnap(moving: List<EditorElement>, bounds: Bounds, proposedDy: Float): SnapCandidate? {
        val threshold = 12f
        val excluded = moving.map { it.id }.toSet()
        val candidates = mutableListOf(
            SnapCandidate(-bounds.top, 0f),
            SnapCandidate(DESIGN_HEIGHT / 2f - bounds.centerY, DESIGN_HEIGHT / 2f),
            SnapCandidate(DESIGN_HEIGHT - bounds.bottom, DESIGN_HEIGHT)
        )
        elements.filter { it.id !in excluded && !it.hidden }.forEach { other ->
            val targetEdges = listOf(other.y, other.y + other.height / 2f, other.y + other.height)
            targetEdges.forEach { target ->
                candidates += SnapCandidate(target - bounds.top, target)
                candidates += SnapCandidate(target - bounds.centerY, target)
                candidates += SnapCandidate(target - bounds.bottom, target)
            }
        }
        return candidates.minByOrNull { abs(it.value - proposedDy) }?.takeIf { abs(it.value - proposedDy) <= threshold }
    }

    private fun clearSnapGuides() {
        snapGuideX = null
        snapGuideY = null
    }

    private fun mutate(block: () -> Unit) {
        pushUndoSnapshot()
        block()
        syncCurrentPage()
        redoStack.clear()
        revision++
    }

    private fun pushUndoSnapshot() {
        undoStack.add(makeSnapshot())
        while (undoStack.size > 100) undoStack.removeFirst()
    }

    private fun makeSnapshot(): Snapshot = Snapshot(
        projectName = projectName,
        pages = snapshotPages(),
        currentPageId = currentPageId,
        assetLibrary = assetLibrary.toList(),
        components = components.toList(),
        pageTemplates = pageTemplates.toList(),
        selectedIds = selectedIds.toList(),
        activeGroupEditId = activeGroupEditId
    )

    private fun restoreSnapshot(snapshot: Snapshot) {
        projectName = snapshot.projectName
        pages.clear(); pages.addAll(snapshot.pages)
        assetLibrary.clear(); assetLibrary.addAll(snapshot.assetLibrary)
        components.clear(); components.addAll(snapshot.components)
        pageTemplates.clear(); pageTemplates.addAll(snapshot.pageTemplates)
        currentPageId = snapshot.currentPageId?.takeIf { wanted -> pages.any { it.id == wanted } } ?: pages.firstOrNull()?.id
        loadActivePageElements()
        selectedIds.clear(); selectedIds.addAll(snapshot.selectedIds.filter { id -> elements.any { it.id == id } })
        activeGroupEditId = snapshot.activeGroupEditId
        clearSnapGuides()
    }

    private fun snapshotPages(): List<EditorPage> {
        val id = currentPageId
        return pages.map { page ->
            if (page.id == id) page.copy(elements = elements.sortedBy { it.zIndex }) else page
        }
    }

    private fun snapshotCurrentPage(): EditorPage? {
        val id = currentPageId ?: return null
        return pages.firstOrNull { it.id == id }?.copy(elements = elements.sortedBy { it.zIndex })
    }

    private fun syncCurrentPage() {
        val id = currentPageId ?: return
        val index = pages.indexOfFirst { it.id == id }
        if (index >= 0) pages[index] = pages[index].copy(elements = elements.sortedBy { it.zIndex })
    }

    private fun loadActivePageElements() {
        val page = currentPageId?.let { id -> pages.firstOrNull { it.id == id } }
        elements.clear()
        if (page != null) elements.addAll(page.elements.sortedBy { it.zIndex })
    }

    private fun setSelection(ids: List<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids.distinct().filter { id -> elements.any { it.id == id } })
    }

    private fun nextZ(): Int = (elements.maxOfOrNull { it.zIndex } ?: 0) + 1

    private fun replace(id: String, transform: (EditorElement) -> EditorElement) {
        val index = elements.indexOfFirst { it.id == id }
        if (index >= 0) elements[index] = transform(elements[index])
    }

    private fun normalizeZ() {
        applyLayerOrder(elements.sortedBy { it.zIndex })
    }

    private fun applyLayerOrder(sortedBackToFront: List<EditorElement>) {
        val byId = sortedBackToFront.mapIndexed { index, item -> item.id to item.copy(zIndex = index) }.toMap()
        for (i in elements.indices) {
            byId[elements[i].id]?.let { elements[i] = it }
        }
    }
}
