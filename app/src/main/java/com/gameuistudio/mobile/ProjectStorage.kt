package com.gameuistudio.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectStorage {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun root(context: Context): File = File(context.filesDir, "game_ui_studio/current")
    private fun assets(context: Context): File = File(root(context), "assets")
    private fun projectFile(context: Context): File = File(root(context), "project.json")

    fun ensure(context: Context) {
        assets(context).mkdirs()
    }

    fun save(context: Context, project: ProjectData) {
        ensure(context)
        projectFile(context).writeText(json.encodeToString(project))
    }

    fun load(context: Context): ProjectData? {
        val file = projectFile(context)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<ProjectData>(file.readText()) }.getOrNull()
    }

    fun importAsset(context: Context, uri: Uri): String {
        ensure(context)
        val displayName = queryDisplayName(context, uri)
        val ext = displayName.substringAfterLast('.', "png").take(8).ifBlank { "png" }
        val fileName = "${UUID.randomUUID()}.$ext"
        val dest = File(assets(context), fileName)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开图片" }
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return "assets/$fileName"
    }

    fun importFileAsset(context: Context, source: File, preferredName: String = source.name): String {
        ensure(context)
        val ext = preferredName.substringAfterLast('.', source.extension.ifBlank { "bin" }).take(8).ifBlank { "bin" }
        val fileName = "${UUID.randomUUID()}.$ext"
        val dest = File(assets(context), fileName)
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return "assets/$fileName"
    }

    fun assetFile(context: Context, relativePath: String): File = File(root(context), relativePath)

    fun projectJson(project: ProjectData): String = json.encodeToString(project)

    fun writeProjectZip(context: Context, project: ProjectData, output: OutputStream) {
        ensure(context)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("project.json"))
            zip.write(projectJson(project).toByteArray())
            zip.closeEntry()

            val usedAssets = (project.assetLibrary.map { it.path } + project.allElements().mapNotNull { it.assetPath }).distinct()
            usedAssets.forEach { relative ->
                val file = assetFile(context, relative)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return it.getString(idx) ?: "image.png"
            }
        }
        return uri.lastPathSegment ?: "image.png"
    }
}
