package com.gameuistudio.mobile

import kotlin.math.max
import kotlin.math.min

enum class AdaptationSeverity { ERROR, WARNING, INFO }

data class AdaptationIssue(
    val severity: AdaptationSeverity,
    val presetLabel: String,
    val elementId: String? = null,
    val message: String
)

data class SafeRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(rect: ResolvedRect): Boolean =
        rect.x >= left &&
            rect.y >= top &&
            rect.x + rect.width <= right &&
            rect.y + rect.height <= bottom
}

fun core916Rect(previewWidth: Float, previewHeight: Float): SafeRect {
    val targetRatio = 9f / 16f
    val currentRatio = previewWidth / previewHeight
    return if (currentRatio <= targetRatio) {
        val coreHeight = previewWidth / targetRatio
        val top = (previewHeight - coreHeight) / 2f
        SafeRect(0f, top, previewWidth, top + coreHeight)
    } else {
        val coreWidth = previewHeight * targetRatio
        val left = (previewWidth - coreWidth) / 2f
        SafeRect(left, 0f, left + coreWidth, previewHeight)
    }
}

fun deviceSafeRect(previewWidth: Float, previewHeight: Float): SafeRect {
    val leftRight = max(24f, previewWidth * 0.035f)
    val top = max(56f, previewHeight * 0.032f)
    val bottom = max(84f, previewHeight * 0.048f)
    return SafeRect(
        left = leftRight,
        top = top,
        right = previewWidth - leftRight,
        bottom = previewHeight - bottom
    )
}

fun validateAdaptation(
    elements: List<EditorElement>,
    preset: PreviewPreset
): List<AdaptationIssue> {
    val issues = mutableListOf<AdaptationIssue>()
    val visible = elements.filterNot { it.hidden }
    val safe = deviceSafeRect(preset.width, preset.height)
    val core = core916Rect(preset.width, preset.height)

    val backgrounds = visible.filter { it.isBackground }
    if (backgrounds.size > 1) {
        issues += AdaptationIssue(
            AdaptationSeverity.WARNING,
            preset.label,
            message = "检测到 ${backgrounds.size} 个背景元素，建议同一页面只保留一个主背景。"
        )
    }

    backgrounds.forEach { background ->
        if (background.type != ElementType.IMAGE) {
            issues += AdaptationIssue(
                AdaptationSeverity.WARNING,
                preset.label,
                background.id,
                "${background.name} 被标记为背景，但不是图片元素。"
            )
        }
        if (background.imageFit == ImageFit.CONTAIN) {
            issues += AdaptationIssue(
                AdaptationSeverity.WARNING,
                preset.label,
                background.id,
                "${background.name} 使用“完整显示”，在 ${preset.label} 下可能出现空边；背景通常建议使用“铺满裁剪”。"
            )
        }
        if (background.imageFit == ImageFit.FILL) {
            issues += AdaptationIssue(
                AdaptationSeverity.WARNING,
                preset.label,
                background.id,
                "${background.name} 使用“拉伸填满”，不同屏幕比例下可能产生明显变形。"
            )
        }
    }

    val resolved = visible.associateWith { resolveElement(it, preset.width, preset.height) }

    resolved.forEach { (element, rect) ->
        if (element.isBackground) return@forEach

        val outside =
            rect.x < -0.5f ||
            rect.y < -0.5f ||
            rect.x + rect.width > preset.width + 0.5f ||
            rect.y + rect.height > preset.height + 0.5f

        if (outside) {
            issues += AdaptationIssue(
                AdaptationSeverity.ERROR,
                preset.label,
                element.id,
                "${element.name} 超出 ${preset.label} 屏幕边界。"
            )
        }

        if ((element.type == ElementType.BUTTON || element.type == ElementType.TEXT) && !safe.contains(rect)) {
            issues += AdaptationIssue(
                AdaptationSeverity.WARNING,
                preset.label,
                element.id,
                "${element.name} 进入系统安全边缘，可能碰到刘海、挖孔或手势区域。"
            )
        }

        val coreContains =
            rect.x >= core.left &&
            rect.y >= core.top &&
            rect.x + rect.width <= core.right &&
            rect.y + rect.height <= core.bottom

        if ((element.type == ElementType.BUTTON || element.type == ElementType.TEXT) &&
            preset.height / preset.width > 16f / 9f + 0.05f &&
            !coreContains
        ) {
            issues += AdaptationIssue(
                AdaptationSeverity.INFO,
                preset.label,
                element.id,
                "${element.name} 位于 9:16 核心区之外；确认这是有意使用长屏扩展区域。"
            )
        }

        if (element.type == ElementType.IMAGE &&
            element.imageFit == ImageFit.FILL &&
            !element.isBackground
        ) {
            issues += AdaptationIssue(
                AdaptationSeverity.WARNING,
                preset.label,
                element.id,
                "${element.name} 使用拉伸填满，图片可能变形。"
            )
        }
    }

    val buttons = visible.filter { it.type == ElementType.BUTTON && !it.isBackground }
    for (i in 0 until buttons.size) {
        for (j in i + 1 until buttons.size) {
            val a = resolved[buttons[i]] ?: continue
            val b = resolved[buttons[j]] ?: continue
            val overlapW = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
            val overlapH = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
            if (overlapW <= 0f || overlapH <= 0f) continue
            val overlapArea = overlapW * overlapH
            val smaller = min(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
            if (overlapArea / smaller >= 0.35f) {
                issues += AdaptationIssue(
                    AdaptationSeverity.WARNING,
                    preset.label,
                    message = "按钮“${buttons[i].name}”与“${buttons[j].name}”发生明显重叠。"
                )
            }
        }
    }

    if (issues.none { it.severity == AdaptationSeverity.ERROR || it.severity == AdaptationSeverity.WARNING }) {
        issues += AdaptationIssue(
            AdaptationSeverity.INFO,
            preset.label,
            message = "${preset.label} 未发现明显适配问题。"
        )
    }

    return issues
}

fun validateAllPresets(
    elements: List<EditorElement>,
    extraPreset: PreviewPreset? = null
): List<AdaptationIssue> {
    val presets = if (extraPreset == null) PREVIEW_PRESETS else PREVIEW_PRESETS + extraPreset
    return presets.flatMap { validateAdaptation(elements, it) }
}
