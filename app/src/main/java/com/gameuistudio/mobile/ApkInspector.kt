package com.gameuistudio.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

enum class ApkFramework {
    WEBVIEW,
    NATIVE_ANDROID,
    UNITY,
    GODOT,
    UNKNOWN
}

data class ApkResource(
    val archivePath: String,
    val extractedPath: String,
    val category: String,
    val sizeBytes: Long
)

data class ApkInspectionResult(
    val id: String,
    val fileName: String,
    val packageName: String,
    val versionName: String,
    val framework: ApkFramework,
    val totalEntries: Int,
    val imageCount: Int,
    val fontCount: Int,
    val xmlCount: Int,
    val webCount: Int,
    val extractedCount: Int,
    val resources: List<ApkResource>,
    val notes: List<String>
)

object ApkInspector {
    private const val MAX_ENTRY_BYTES = 25L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 300L * 1024L * 1024L
    private const val MAX_ENTRIES = 8000

    private val imageExt = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val fontExt = setOf("ttf", "otf", "woff", "woff2")
    private val webExt = setOf("html", "htm", "css", "js", "json", "svg")

    fun inspect(context: Context, uri: Uri): ApkInspectionResult {
        val displayName = queryDisplayName(context, uri)
        val root = File(context.filesDir, "game_ui_studio/apk_imports").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val session = File(root, id).apply { mkdirs() }
        val apkFile = File(session, "source.apk")

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开 APK" }
            apkFile.outputStream().use { output -> input.copyTo(output) }
        }

        val archiveNames = mutableListOf<String>()
        val resources = mutableListOf<ApkResource>()
        var extractedBytes = 0L
        var totalEntries = 0

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements() && totalEntries < MAX_ENTRIES) {
                val entry = entries.nextElement()
                totalEntries++
                val name = entry.name.replace('\\', '/')
                archiveNames += name
                if (entry.isDirectory) continue

                val ext = name.substringAfterLast('.', "").lowercase()
                val category = when {
                    ext in imageExt -> "image"
                    ext in fontExt -> "font"
                    ext == "xml" -> "xml"
                    ext in webExt -> "web"
                    else -> null
                } ?: continue

                val declaredSize = entry.size.coerceAtLeast(0L)
                if (declaredSize > MAX_ENTRY_BYTES) continue
                if (extractedBytes + declaredSize > MAX_TOTAL_BYTES) continue

                val dest = safeDestination(session, "extracted/$name") ?: continue
                dest.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    dest.outputStream().use { output ->
                        val copied = input.copyToLimited(output, MAX_ENTRY_BYTES)
                        extractedBytes += copied
                    }
                }

                resources += ApkResource(
                    archivePath = name,
                    extractedPath = dest.absolutePath,
                    category = category,
                    sizeBytes = dest.length()
                )
            }
        }

        val framework = detectFramework(archiveNames)
        val pkg = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)

        val notes = buildList {
            when (framework) {
                ApkFramework.WEBVIEW -> {
                    add("检测到 WebView/H5 打包结构，可直接提取 assets 内的 HTML、CSS、JS 与 UI 图片。")
                    if (archiveNames.any { it == "assets/index.html" }) add("发现 assets/index.html。")
                    if (archiveNames.any { it.startsWith("assets/ui-v2/") }) add("发现 ui-v2 资源目录，适合直接导入素材库重新设计。")
                }
                ApkFramework.NATIVE_ANDROID -> add("检测到原生 Android 资源。图片可直接提取；编译后的 XML 需要后续做二进制资源解析。")
                ApkFramework.UNITY -> add("检测到 Unity。第一阶段提取可见图片/字体；完整 Prefab/Atlas 解析后续单独处理。")
                ApkFramework.GODOT -> add("检测到 Godot。第一阶段提取 APK 内可见资源；PCK 场景解析后续单独处理。")
                ApkFramework.UNKNOWN -> add("未能明确识别框架，已按通用 APK 资源扫描处理。")
            }
            if (totalEntries >= MAX_ENTRIES) add("APK 条目较多，本次扫描已达到安全上限 $MAX_ENTRIES。")
        }

        return ApkInspectionResult(
            id = id,
            fileName = displayName,
            packageName = pkg?.packageName ?: "未知",
            versionName = pkg?.versionName ?: "未知",
            framework = framework,
            totalEntries = totalEntries,
            imageCount = resources.count { it.category == "image" },
            fontCount = resources.count { it.category == "font" },
            xmlCount = resources.count { it.category == "xml" },
            webCount = resources.count { it.category == "web" },
            extractedCount = resources.size,
            resources = resources,
            notes = notes
        )
    }

    private fun detectFramework(names: List<String>): ApkFramework {
        val lower = names.map { it.lowercase() }
        return when {
            lower.any { it.contains("libunity.so") } ||
                lower.any { it.startsWith("assets/bin/data/") } ||
                lower.any { it.endsWith("globalgamemanagers") } -> ApkFramework.UNITY

            lower.any { it.contains("libgodot") } ||
                lower.any { it.endsWith(".pck") } -> ApkFramework.GODOT

            lower.any { it == "assets/index.html" } ||
                (lower.any { it.startsWith("assets/") && it.endsWith(".js") } &&
                    lower.any { it.startsWith("assets/") && it.endsWith(".css") }) -> ApkFramework.WEBVIEW

            lower.any { it.startsWith("res/layout") && it.endsWith(".xml") } -> ApkFramework.NATIVE_ANDROID
            else -> ApkFramework.UNKNOWN
        }
    }

    private fun safeDestination(root: File, relative: String): File? {
        val target = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        return if (targetPath.startsWith(rootPath)) target else null
    }

    private fun java.io.InputStream.copyToLimited(
        output: java.io.OutputStream,
        limit: Long
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw IllegalStateException("APK 内单个资源超过安全限制")
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "import.apk"
            }
        }
        return uri.lastPathSegment ?: "import.apk"
    }
}
