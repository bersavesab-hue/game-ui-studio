package com.gameuistudio.mobile

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File

object RuntimeExport {
    private val json = Json { prettyPrint = true }

    fun build(project: ProjectData): String {
        val assetNames = project.assetLibrary.associate { it.path to File(it.path).name }

        val root = buildJsonObject {
            put("format", JsonPrimitive("game-ui-studio-runtime"))
            put("version", JsonPrimitive(1))
            put("designWidth", JsonPrimitive(project.designWidth))
            put("designHeight", JsonPrimitive(project.designHeight))
            put("pages", buildJsonArray {
                project.pages.forEach { page ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(page.id))
                        put("name", JsonPrimitive(page.name))
                        put("elements", buildJsonArray {
                            page.elements.sortedBy { it.zIndex }.forEach { e ->
                                add(elementJson(e, assetNames))
                            }
                        })
                    })
                }
            })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    private fun elementJson(
        e: EditorElement,
        assetNames: Map<String, String>
    ): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(e.id))
        put("type", JsonPrimitive(e.type.name.lowercase()))
        put("name", JsonPrimitive(e.name))
        put("x", JsonPrimitive(e.x))
        put("y", JsonPrimitive(e.y))
        put("width", JsonPrimitive(e.width))
        put("height", JsonPrimitive(e.height))
        put("zIndex", JsonPrimitive(e.zIndex))
        put("hidden", JsonPrimitive(e.hidden))
        put("locked", JsonPrimitive(e.locked))
        put("anchor", JsonPrimitive(e.anchor.name))
        put("widthMode", JsonPrimitive(e.widthMode.name))
        put("heightMode", JsonPrimitive(e.heightMode.name))
        put("text", JsonPrimitive(e.text))
        put("fontSize", JsonPrimitive(e.fontSize))
        put("textColor", JsonPrimitive(argbHex(e.textColor)))
        put("fillColor", JsonPrimitive(argbHex(e.fillColor)))
        put("borderColor", JsonPrimitive(argbHex(e.borderColor)))
        put("borderWidth", JsonPrimitive(e.borderWidth))
        put("cornerRadius", JsonPrimitive(e.cornerRadius))
        put("opacity", JsonPrimitive(e.opacity))
        put("rotation", JsonPrimitive(e.rotation))
        put("flipX", JsonPrimitive(e.flipX))
        put("flipY", JsonPrimitive(e.flipY))
        put("imageFit", JsonPrimitive(e.imageFit.name))
        put("isBackground", JsonPrimitive(e.isBackground))
        put("asset", JsonPrimitive(e.assetPath?.let { assetNames[it] } ?: ""))
        put("fontFamily", JsonPrimitive(e.fontFamilyMode.name))
        put("fontWeight", JsonPrimitive(e.fontWeightMode.name))
        put("textAlign", JsonPrimitive(e.textAlign.name))
        put("gradientEnabled", JsonPrimitive(e.gradientEnabled))
        put("gradientEndColor", JsonPrimitive(argbHex(e.gradientEndColor)))
        put("shadowAlpha", JsonPrimitive(e.shadowAlpha))
        put("shadowRadius", JsonPrimitive(e.shadowRadius))
        put("shadowOffsetY", JsonPrimitive(e.shadowOffsetY))
    }

    private fun argbHex(color: Int): String = String.format("#%08X", color)
}
