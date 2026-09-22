package com.gameuistudio.mobile

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLDecoder
import java.util.UUID

@Serializable
data class DomCapture(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val title: String = "",
    val nodes: List<DomNode> = emptyList()
)

@Serializable
data class DomNode(
    val kind: String,
    val tag: String,
    val text: String = "",
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: String = "",
    val backgroundColor: String = "",
    val fontSize: Float = 16f,
    val fontWeight: String = "400",
    val borderRadius: Float = 0f,
    val borderWidth: Float = 0f,
    val borderColor: String = "",
    val opacity: Float = 1f,
    val src: String = "",
    val backgroundImage: String = "",
    val zIndex: Int = 0
)

data class WebRebuildData(
    val pageName: String,
    val elements: List<EditorElement>,
    val assets: List<AssetRecord>
)

object WebUiCapture {
    private val json = Json { ignoreUnknownKeys = true }

    val captureScript: String = """
        (function() {
          function rgbaVisible(v) {
            if (!v) return false;
            if (v === 'transparent') return false;
            var m = v.match(/rgba\([^,]+,[^,]+,[^,]+,\s*([\d.]+)\)/);
            return !m || parseFloat(m[1]) > 0.02;
          }
          function px(v) {
            var n = parseFloat(v || '0');
            return isFinite(n) ? n : 0;
          }
          function cleanText(v) {
            return (v || '').replace(/\s+/g, ' ').trim().slice(0, 160);
          }
          var all = Array.from(document.querySelectorAll('body *'));
          var out = [];
          for (var i = 0; i < all.length && out.length < 320; i++) {
            var e = all[i];
            if (!e || ['SCRIPT','STYLE','META','LINK','NOSCRIPT'].includes(e.tagName)) continue;
            var cs = getComputedStyle(e);
            if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity || '1') < 0.02) continue;
            var r = e.getBoundingClientRect();
            if (r.width < 6 || r.height < 6) continue;
            if (r.bottom < 0 || r.right < 0 || r.top > innerHeight || r.left > innerWidth) continue;

            var tag = (e.tagName || '').toLowerCase();
            var role = (e.getAttribute('role') || '').toLowerCase();
            var interactive = tag === 'button' || tag === 'a' || role === 'button' ||
              tag === 'input' || tag === 'select' || tag === 'textarea' ||
              typeof e.onclick === 'function' || cs.cursor === 'pointer';

            var img = tag === 'img' || (cs.backgroundImage && cs.backgroundImage !== 'none');
            var text = cleanText(e.innerText || e.textContent || '');
            var hasInteractiveParent = !!e.parentElement && !!e.parentElement.closest('button,a,[role="button"]');
            var leafText = text.length > 0 && e.children.length === 0 && !hasInteractiveParent;
            var panel = !interactive && !img && rgbaVisible(cs.backgroundColor) &&
              r.width * r.height > 600 && r.width < innerWidth * 0.98 && r.height < innerHeight * 0.98;

            var kind = interactive ? 'button' : (img ? 'image' : (leafText ? 'text' : (panel ? 'panel' : '')));
            if (!kind) continue;

            var src = '';
            if (tag === 'img') src = e.currentSrc || e.src || '';
            out.push({
              kind: kind,
              tag: tag,
              text: text,
              x: Math.max(0, r.left),
              y: Math.max(0, r.top),
              width: Math.min(r.width, innerWidth - Math.max(0, r.left)),
              height: Math.min(r.height, innerHeight - Math.max(0, r.top)),
              color: cs.color || '',
              backgroundColor: cs.backgroundColor || '',
              fontSize: px(cs.fontSize),
              fontWeight: cs.fontWeight || '400',
              borderRadius: px(cs.borderRadius),
              borderWidth: px(cs.borderTopWidth),
              borderColor: cs.borderTopColor || '',
              opacity: parseFloat(cs.opacity || '1') || 1,
              src: src,
              backgroundImage: cs.backgroundImage || '',
              zIndex: parseInt(cs.zIndex || '0') || 0
            });
          }

          out.sort(function(a,b){
            if (a.zIndex !== b.zIndex) return a.zIndex - b.zIndex;
            return (a.y - b.y) || (a.x - b.x);
          });

          return JSON.stringify({
            viewportWidth: Math.max(1, innerWidth),
            viewportHeight: Math.max(1, innerHeight),
            title: document.title || '',
            nodes: out
          });
        })();
    """.trimIndent()

    fun decodeEvaluateResult(raw: String): DomCapture? {
        return runCatching {
            val decoded = json.parseToJsonElement(raw).jsonPrimitive.content
            json.decodeFromString<DomCapture>(decoded)
        }.getOrNull()
    }

    fun buildRebuildData(
        context: Context,
        inspection: ApkInspectionResult,
        capture: DomCapture
    ): WebRebuildData {
        val sx = DESIGN_WIDTH / capture.viewportWidth.coerceAtLeast(1f)
        val sy = DESIGN_HEIGHT / capture.viewportHeight.coerceAtLeast(1f)
        val assetsByArchive = inspection.resources
            .filter { it.category == "image" }
            .associateBy { it.archivePath.lowercase() }

        val createdAssets = linkedMapOf<String, AssetRecord>()

        fun assetFor(node: DomNode): AssetRecord? {
            val sourceUrl = when {
                node.src.isNotBlank() -> node.src
                node.backgroundImage.contains("url(") -> node.backgroundImage
                    .substringAfter("url(")
                    .substringBeforeLast(")")
                    .trim(' ', '\'', '"')
                else -> ""
            }
            if (sourceUrl.isBlank()) return null

            val decoded = runCatching { URLDecoder.decode(sourceUrl, "UTF-8") }.getOrDefault(sourceUrl)
            val marker = "/assets/"
            val relative = if (decoded.contains(marker)) {
                "assets/" + decoded.substringAfter(marker)
            } else {
                decoded.substringAfterLast("file://").trimStart('/')
            }.substringBefore('?').substringBefore('#')

            val resource = assetsByArchive[relative.lowercase()]
                ?: inspection.resources.firstOrNull {
                    it.category == "image" &&
                        (relative.endsWith(it.archivePath, ignoreCase = true) ||
                            decoded.endsWith(it.archivePath, ignoreCase = true))
                }
                ?: return null

            return createdAssets.getOrPut(resource.archivePath) {
                val source = File(resource.extractedPath)
                val projectPath = ProjectStorage.importFileAsset(context, source, source.name)
                val target = ProjectStorage.assetFile(context, projectPath)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.absolutePath, options)
                val ratio = if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth.toFloat() / options.outHeight
                } else 1f
                AssetRecord(
                    id = UUID.randomUUID().toString(),
                    name = source.name,
                    path = projectPath,
                    aspectRatio = ratio.coerceAtLeast(0.05f)
                )
            }
        }

        val elements = capture.nodes.mapIndexedNotNull { index, node ->
            val x = (node.x * sx).coerceIn(0f, DESIGN_WIDTH)
            val y = (node.y * sy).coerceIn(0f, DESIGN_HEIGHT)
            val w = (node.width * sx).coerceIn(12f, DESIGN_WIDTH - x)
            val h = (node.height * sy).coerceIn(12f, DESIGN_HEIGHT - y)
            if (w < 12f || h < 12f) return@mapIndexedNotNull null

            val fill = parseCssColor(node.backgroundColor, 0x00000000)
            val textColor = parseCssColor(node.color, 0xFF111318.toInt())
            val borderColor = parseCssColor(node.borderColor, 0x00000000)
            val weight = node.fontWeight.toIntOrNull() ?: if (node.fontWeight.contains("bold", true)) 700 else 400
            val asset = if (node.kind == "image") assetFor(node) else null

            val type = when (node.kind) {
                "button" -> ElementType.BUTTON
                "image" -> if (asset != null) ElementType.IMAGE else ElementType.PANEL
                "text" -> ElementType.TEXT
                else -> ElementType.PANEL
            }

            EditorElement(
                id = UUID.randomUUID().toString(),
                type = type,
                name = when (type) {
                    ElementType.BUTTON -> node.text.ifBlank { "按钮 ${index + 1}" }.take(30)
                    ElementType.TEXT -> node.text.ifBlank { "文字 ${index + 1}" }.take(30)
                    ElementType.IMAGE -> asset?.name ?: "图片 ${index + 1}"
                    ElementType.PANEL -> "面板 ${index + 1}"
                },
                x = x,
                y = y,
                width = w,
                height = h,
                zIndex = index,
                text = if (type == ElementType.TEXT || type == ElementType.BUTTON) node.text.take(160) else "",
                fontSize = (node.fontSize * sy).coerceIn(10f, 160f),
                assetPath = asset?.path,
                assetId = asset?.id,
                opacity = node.opacity.coerceIn(0f, 1f),
                cornerRadius = (node.borderRadius * minOf(sx, sy)).coerceAtLeast(0f),
                fillColor = when {
                    type == ElementType.BUTTON && fill == 0x00000000 -> 0xFF2563EB.toInt()
                    type == ElementType.PANEL && fill == 0x00000000 -> 0x22000000
                    else -> fill
                },
                textColor = textColor,
                borderColor = borderColor,
                borderWidth = (node.borderWidth * minOf(sx, sy)).coerceAtLeast(0f),
                fontWeightMode = when {
                    weight >= 650 -> FontWeightMode.BOLD
                    weight >= 500 -> FontWeightMode.MEDIUM
                    else -> FontWeightMode.NORMAL
                }
            )
        }

        return WebRebuildData(
            pageName = "自动拆页 · " + capture.title.ifBlank { inspection.fileName.substringBeforeLast('.') },
            elements = elements,
            assets = createdAssets.values.toList()
        )
    }

    private fun parseCssColor(value: String, fallback: Int): Int {
        val v = value.trim().lowercase()
        if (v.isBlank() || v == "transparent") return fallback

        if (v.startsWith("#")) {
            val raw = v.removePrefix("#")
            return runCatching {
                when (raw.length) {
                    3 -> {
                        val r = raw[0].toString().repeat(2).toInt(16)
                        val g = raw[1].toString().repeat(2).toInt(16)
                        val b = raw[2].toString().repeat(2).toInt(16)
                        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    6 -> (0xFF000000L or raw.toLong(16)).toInt()
                    8 -> raw.toLong(16).toInt()
                    else -> fallback
                }
            }.getOrDefault(fallback)
        }

        val match = Regex("""rgba?\(([^)]+)\)""").find(v) ?: return fallback
        val parts = match.groupValues[1].split(',').map { it.trim() }
        if (parts.size < 3) return fallback
        val r = parts[0].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return fallback
        val g = parts[1].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return fallback
        val b = parts[2].toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: return fallback
        val a = if (parts.size >= 4) {
            ((parts[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f) * 255f).toInt()
        } else 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
