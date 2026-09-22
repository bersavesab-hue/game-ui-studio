package com.gameuistudio.mobile

import kotlinx.serialization.Serializable

const val DESIGN_WIDTH = 1080f
const val DESIGN_HEIGHT = 1920f

@Serializable
enum class ElementType { IMAGE, TEXT, BUTTON, PANEL }

@Serializable
enum class Anchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

@Serializable
enum class SizeMode { FIXED, PERCENT, STRETCH }

@Serializable
enum class ImageFit { COVER, CONTAIN, FILL, FIT_WIDTH, FIT_HEIGHT }

@Serializable
data class EditorElement(
    val id: String,
    val type: ElementType,
    val name: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val zIndex: Int = 0,
    val locked: Boolean = false,
    val hidden: Boolean = false,
    val groupId: String? = null,
    val anchor: Anchor = Anchor.TOP_LEFT,
    val widthMode: SizeMode = SizeMode.FIXED,
    val heightMode: SizeMode = SizeMode.FIXED,
    val text: String = "",
    val fontSize: Float = 42f,
    val assetPath: String? = null,
    val assetId: String? = null,
    val cropZoom: Float = 1f,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    val rotation: Float = 0f,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val opacity: Float = 1f,
    val cornerRadius: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val isBackground: Boolean = false,
    val imageFit: ImageFit = ImageFit.COVER
)

@Serializable
data class EditorPage(
    val id: String,
    val name: String,
    val elements: List<EditorElement> = emptyList()
)

@Serializable
data class AssetRecord(
    val id: String,
    val name: String,
    val path: String,
    val aspectRatio: Float = 16f / 9f
)

@Serializable
data class ComponentTemplate(
    val id: String,
    val name: String,
    val width: Float,
    val height: Float,
    val elements: List<EditorElement>
)

@Serializable
data class PageTemplate(
    val id: String,
    val name: String,
    val elements: List<EditorElement>
)

@Serializable
data class ProjectData(
    val version: Int = 5,
    val projectName: String = "未命名 UI 工程",
    val designWidth: Float = DESIGN_WIDTH,
    val designHeight: Float = DESIGN_HEIGHT,
    val pages: List<EditorPage> = emptyList(),
    val currentPageId: String? = null,
    val assetLibrary: List<AssetRecord> = emptyList(),
    val components: List<ComponentTemplate> = emptyList(),
    val pageTemplates: List<PageTemplate> = emptyList(),
    // v0.1/v0.2 compatibility. New saves leave this empty.
    val elements: List<EditorElement> = emptyList()
) {
    fun allElements(): List<EditorElement> {
        val pageElements = if (pages.isNotEmpty()) pages.flatMap { it.elements } else elements
        return pageElements + components.flatMap { it.elements } + pageTemplates.flatMap { it.elements }
    }
}

data class PreviewPreset(
    val label: String,
    val width: Float,
    val height: Float
)

val PREVIEW_PRESETS = listOf(
    PreviewPreset("设计稿 9:16", 1080f, 1920f),
    PreviewPreset("主流 9:19.5", 1080f, 2340f),
    PreviewPreset("长屏 9:20", 1080f, 2400f),
    PreviewPreset("超长屏 9:21", 1080f, 2520f),
    PreviewPreset("10:16", 1200f, 1920f),
    PreviewPreset("平板竖屏", 1600f, 2560f)
)

data class ResolvedRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

fun resolveElement(element: EditorElement, previewWidth: Float, previewHeight: Float): ResolvedRect {
    if (element.isBackground) {
        return ResolvedRect(0f, 0f, previewWidth, previewHeight)
    }

    val leftMargin = element.x
    val rightMargin = DESIGN_WIDTH - (element.x + element.width)
    val topMargin = element.y
    val bottomMargin = DESIGN_HEIGHT - (element.y + element.height)

    val resolvedWidth = when (element.widthMode) {
        SizeMode.FIXED -> element.width
        SizeMode.PERCENT -> previewWidth * (element.width / DESIGN_WIDTH)
        SizeMode.STRETCH -> (previewWidth - leftMargin - rightMargin).coerceAtLeast(40f)
    }
    val resolvedHeight = when (element.heightMode) {
        SizeMode.FIXED -> element.height
        SizeMode.PERCENT -> previewHeight * (element.height / DESIGN_HEIGHT)
        SizeMode.STRETCH -> (previewHeight - topMargin - bottomMargin).coerceAtLeast(40f)
    }

    val centerOffsetX = (element.x + element.width / 2f) - DESIGN_WIDTH / 2f
    val centerOffsetY = (element.y + element.height / 2f) - DESIGN_HEIGHT / 2f

    val resolvedX = when (element.anchor) {
        Anchor.TOP_LEFT, Anchor.CENTER_LEFT, Anchor.BOTTOM_LEFT -> leftMargin
        Anchor.TOP_CENTER, Anchor.CENTER, Anchor.BOTTOM_CENTER -> previewWidth / 2f + centerOffsetX - resolvedWidth / 2f
        Anchor.TOP_RIGHT, Anchor.CENTER_RIGHT, Anchor.BOTTOM_RIGHT -> previewWidth - rightMargin - resolvedWidth
    }
    val resolvedY = when (element.anchor) {
        Anchor.TOP_LEFT, Anchor.TOP_CENTER, Anchor.TOP_RIGHT -> topMargin
        Anchor.CENTER_LEFT, Anchor.CENTER, Anchor.CENTER_RIGHT -> previewHeight / 2f + centerOffsetY - resolvedHeight / 2f
        Anchor.BOTTOM_LEFT, Anchor.BOTTOM_CENTER, Anchor.BOTTOM_RIGHT -> previewHeight - bottomMargin - resolvedHeight
    }

    return ResolvedRect(resolvedX, resolvedY, resolvedWidth, resolvedHeight)
}
