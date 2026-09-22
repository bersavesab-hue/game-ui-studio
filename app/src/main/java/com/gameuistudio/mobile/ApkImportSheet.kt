package com.gameuistudio.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkImportSheet(
    result: ApkInspectionResult,
    onDismiss: () -> Unit,
    onImportImages: () -> Unit,
    onCreateRebuildPage: () -> Unit,
    onOpenWebCapture: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("APK UI 拆解", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(result.fileName, fontSize = 12.sp, color = Color(0xFFB6BDC8))
            Spacer(Modifier.height(10.dp))

            InfoCard("包名", result.packageName)
            InfoCard("版本", result.versionName)
            InfoCard("识别框架", frameworkLabel(result.framework))
            InfoCard("APK 条目", result.totalEntries.toString())

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatChip("图片", result.imageCount)
                StatChip("字体", result.fontCount)
                StatChip("XML", result.xmlCount)
                StatChip("Web", result.webCount)
            }

            Spacer(Modifier.height(14.dp))
            Text("拆解结果", fontWeight = FontWeight.Bold)
            result.notes.forEach {
                Text("• $it", fontSize = 11.sp, color = Color(0xFFCBD5E1), modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("图片加入素材库", primary = true, onClick = onImportImages)
                ActionButton("新建重建设计页", onClick = onCreateRebuildPage)
            }
            if (
                result.framework == ApkFramework.WEBVIEW &&
                result.resources.any { it.archivePath.equals("assets/index.html", ignoreCase = true) }
            ) {
                Spacer(Modifier.height(7.dp))
                ActionButton("运行原页面并自动拆层", primary = true, onClick = onOpenWebCapture)
            }
            Spacer(Modifier.height(7.dp))
            ActionButton("打开素材库", onClick = onOpenLibrary)

            Spacer(Modifier.height(16.dp))
            Text("已提取资源", fontWeight = FontWeight.Bold)
            result.resources.take(80).forEach { resource ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .background(Color(0xFF2A2F38), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF39414D), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (resource.category) {
                            "image" -> "图"
                            "font" -> "字"
                            "xml" -> "XML"
                            else -> "WEB"
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        fontSize = 10.sp,
                        color = Color(0xFF93C5FD),
                        fontWeight = FontWeight.Bold
                    )
                    Column(Modifier.weight(1f)) {
                        Text(resource.archivePath, fontSize = 10.sp, maxLines = 1)
                        Text(
                            "${resource.sizeBytes / 1024} KB",
                            fontSize = 9.sp,
                            color = Color(0xFF8B95A5)
                        )
                    }
                }
            }
            if (result.resources.size > 80) {
                Text(
                    "另有 ${result.resources.size - 80} 个资源未在列表中展开。",
                    fontSize = 10.sp,
                    color = Color(0xFF8B95A5),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .background(Color(0xFF292E37), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF9CA3AF))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatChip(label: String, count: Int) {
    Box(
        Modifier.background(Color(0xFF303743), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text("$label $count", fontSize = 10.sp)
    }
}

@Composable
private fun ActionButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFF2563EB) else Color(0xFF353B46)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp)
    }
}

private fun frameworkLabel(framework: ApkFramework): String = when (framework) {
    ApkFramework.WEBVIEW -> "WebView / H5"
    ApkFramework.NATIVE_ANDROID -> "原生 Android"
    ApkFramework.UNITY -> "Unity"
    ApkFramework.GODOT -> "Godot"
    ApkFramework.UNKNOWN -> "未知 / 混合"
}
