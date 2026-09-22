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
import java.util.zip.ZipInputStream
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
    private fun backups(context: Context): File = File(context.filesDir, "game_ui_studio/backups")

    fun ensure(context: Context) {
        assets(context).mkdirs()
    }

    fun save(context: Context, project: ProjectData) {
        ensure(context)
        val target = projectFile(context)
        if (target.exists()) {
            backups(context).mkdirs()
            val backup = File(backups(context), "project-${System.currentTimeMillis()}.json")
            runCatching { target.copyTo(backup, overwrite = true) }
            trimBackups(context, 8)
        }
        val temp = File(target.parentFile, "project.json.tmp")
        temp.writeText(json.encodeToString(project))
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }

    fun latestBackup(context: Context): ProjectData? {
        val dir = backups(context)
        val file = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return runCatching { json.decodeFromString<ProjectData>(file.readText()) }.getOrNull()
    }

    fun backupCount(context: Context): Int =
        backups(context).listFiles()?.count { it.isFile && it.extension.equals("json", true) } ?: 0

    private fun trimBackups(context: Context, keep: Int) {
        backups(context).listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { it.delete() }
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

    fun importProjectZip(context: Context, uri: Uri): ProjectData {
        val staging = File(context.cacheDir, "gui-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) { "无法打开工程包" }
                ZipInputStream(raw).use { zip ->
                    var total = 0L
                    var entries = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= 10000) { "工程包文件数量异常" }
                        val target = safeZipDestination(staging, entry.name)
                            ?: throw IllegalArgumentException("工程包包含非法路径")
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read <= 0) break
                                    total += read
                                    require(total <= 500L * 1024L * 1024L) { "工程包过大" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val project = File(staging, "project.json")
            require(project.exists()) { "工程包缺少 project.json" }
            val decoded = json.decodeFromString<ProjectData>(project.readText())

            val current = root(context)
            val replacement = File(context.filesDir, "game_ui_studio/current-import")
            if (replacement.exists()) replacement.deleteRecursively()
            replacement.mkdirs()

            project.copyTo(File(replacement, "project.json"), overwrite = true)
            val stagedAssets = File(staging, "assets")
            if (stagedAssets.exists()) {
                stagedAssets.copyRecursively(File(replacement, "assets"), overwrite = true)
            } else {
                File(replacement, "assets").mkdirs()
            }

            if (current.exists()) {
                backups(context).mkdirs()
                val snapshot = File(backups(context), "before-import-${System.currentTimeMillis()}")
                runCatching { current.copyRecursively(snapshot, overwrite = true) }
            }
            current.deleteRecursively()
            require(replacement.renameTo(current)) { "替换当前工程失败" }
            return decoded
        } finally {
            staging.deleteRecursively()
        }
    }

    fun writeGameIntegrationZip(context: Context, project: ProjectData, output: OutputStream) {
        ensure(context)
        ZipOutputStream(output).use { zip ->
            val runtimeJson = RuntimeExport.build(project)
            zip.putNextEntry(ZipEntry("ui-layout.json"))
            zip.write(runtimeJson.toByteArray())
            zip.closeEntry()

            val paths = project.allElements().mapNotNull { it.assetPath }.distinct()
            paths.forEach { relative ->
                val file = assetFile(context, relative)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry("assets/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(
                "Game UI Studio runtime export\nDesign base: ${project.designWidth.toInt()}x${project.designHeight.toInt()}\nPages: ${project.pages.size}\n".toByteArray()
            )
            zip.closeEntry()
        }
    }

    private fun safeZipDestination(root: File, name: String): File? {
        val normalized = name.replace('\\', '/')
        val target = File(root, normalized)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        return if (targetPath.startsWith(rootPath)) target else null
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
