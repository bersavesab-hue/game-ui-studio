package com.gameuistudio.mobile

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySheet(vm: EditorViewModel, projectRoot: File, onDismiss: () -> Unit) {
    var componentName by remember { mutableStateOf("") }
    var templateName by remember { mutableStateOf("") }
    var renameComponentId by remember { mutableStateOf<String?>(null) }
    var renameTemplateId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text("资源库", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "素材、组件和页面模板均属于整个工程，可跨页面重复使用。",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("全局素材", "${vm.assetLibrary.size} 个")
            if (vm.assetLibrary.isEmpty()) {
                EmptyLibraryBox("导入图片后会自动加入全局素材库")
            } else {
                vm.assetLibrary.forEach { asset ->
                    val file = File(projectRoot, asset.path)
                    val bitmap = remember(asset.path, file.lastModified()) {
                        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(Color(0xFF2A2F38), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF3C4450), RoundedCornerShape(10.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(74.dp).background(Color(0xFF111318), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = asset.name,
                                    modifier = Modifier.size(70.dp),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text("无预览", fontSize = 9.sp, color = Color(0xFF6B7280))
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(asset.name, fontSize = 13.sp, maxLines = 1)
                            Text(
                                "比例 ${"%.2f".format(asset.aspectRatio)}  ·  ${asset.path}",
                                fontSize = 9.sp,
                                color = Color(0xFF9CA3AF),
                                maxLines = 1
                            )
                        }
                        LibraryButton("放入画布", primary = true) { vm.insertAsset(asset.id) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("组件", "${vm.components.size} 个")
            OutlinedTextField(
                value = componentName,
                onValueChange = { componentName = it.take(30) },
                label = { Text("组件名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                LibraryButton("保存当前选择", primary = true) {
                    vm.saveSelectionAsComponent(componentName)
                    componentName = ""
                }
            }
            Spacer(Modifier.height(6.dp))
            if (vm.components.isEmpty()) {
                EmptyLibraryBox("先选择一个或多个元素，再保存成可复用组件")
            } else {
                vm.components.forEach { component ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(Color(0xFF2A2F38), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniLayoutPreview(
                            elements = component.elements,
                            sourceWidth = component.width,
                            sourceHeight = component.height,
                            modifier = Modifier.size(54.dp, 72.dp)
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(component.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${component.elements.size} 个元素 · ${component.width.toInt()}×${component.height.toInt()}",
                                fontSize = 9.sp,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                        LibraryButton("插入") { vm.insertComponent(component.id) }
                        Spacer(Modifier.size(4.dp))
                        LibraryButton("改名") {
                            renameComponentId = component.id
                            renameTemplateId = null
                            renameText = component.name
                        }
                        Spacer(Modifier.size(4.dp))
                        LibraryButton("删除", danger = true) { vm.deleteComponent(component.id) }
                    }
                    if (renameComponentId == component.id) {
                        RenameRow(
                            value = renameText,
                            onChange = { renameText = it },
                            onApply = {
                                vm.renameComponent(component.id, renameText)
                                renameComponentId = null
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionTitle("页面模板", "${vm.pageTemplates.size} 个")
            OutlinedTextField(
                value = templateName,
                onValueChange = { templateName = it.take(30) },
                label = { Text("模板名称（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            LibraryButton("保存当前页为模板", primary = true) {
                vm.saveCurrentPageAsTemplate(templateName)
                templateName = ""
            }
            Spacer(Modifier.height(6.dp))
            if (vm.pageTemplates.isEmpty()) {
                EmptyLibraryBox("可把已完成页面保存为模板，再一键生成新页面")
            } else {
                vm.pageTemplates.forEach { template ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .background(Color(0xFF2A2F38), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MiniLayoutPreview(
                            elements = template.elements,
                            sourceWidth = DESIGN_WIDTH,
                            sourceHeight = DESIGN_HEIGHT,
                            modifier = Modifier.size(54.dp, 84.dp)
                        )
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(template.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${template.elements.size} 个元素", fontSize = 9.sp, color = Color(0xFF9CA3AF))
                        }
                        LibraryButton("新建页面") { vm.createPageFromTemplate(template.id) }
                        Spacer(Modifier.size(4.dp))
                        LibraryButton("改名") {
                            renameTemplateId = template.id
                            renameComponentId = null
                            renameText = template.name
                        }
                        Spacer(Modifier.size(4.dp))
                        LibraryButton("删除", danger = true) { vm.deletePageTemplate(template.id) }
                    }
                    if (renameTemplateId == template.id) {
                        RenameRow(
                            value = renameText,
                            onChange = { renameText = it },
                            onApply = {
                                vm.renamePageTemplate(template.id, renameText)
                                renameTemplateId = null
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(count, fontSize = 11.sp, color = Color(0xFF9CA3AF))
    }
}

@Composable
private fun EmptyLibraryBox(text: String) {
    Box(
        Modifier.fillMaxWidth().height(74.dp).padding(top = 6.dp)
            .background(Color(0xFF171B22), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 11.sp, color = Color(0xFF7F8792))
    }
}

@Composable
private fun LibraryButton(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val color = when {
        danger -> Color(0xFF7F1D1D)
        primary -> Color(0xFF2563EB)
        else -> Color(0xFF353B46)
    }
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 5.dp)
    ) { Text(text, fontSize = 10.sp) }
}
