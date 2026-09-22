package com.gameuistudio.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    var rename by remember(vm.currentPageId) { mutableStateOf(vm.currentPage?.name ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("页面", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("每个页面拥有独立图层，共用同一素材目录", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
                Text("${vm.pages.size} 页", fontSize = 12.sp, color = Color(0xFFB6BDC8))
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PageActionButton("新建页面") { vm.addPage() }
                PageActionButton("复制当前") { vm.duplicateCurrentPage() }
                PageActionButton("删除当前", danger = true) { vm.deleteCurrentPage() }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = rename,
                onValueChange = { rename = it },
                label = { Text("当前页面名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            PageActionButton("应用名称") { vm.renameCurrentPage(rename) }

            Spacer(Modifier.height(14.dp))
            vm.pages.forEachIndexed { index, page ->
                val active = page.id == vm.currentPageId
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(if (active) Color(0xFF25365D) else Color(0xFF2A2F38), RoundedCornerShape(10.dp))
                        .border(
                            if (active) 1.5.dp else 1.dp,
                            if (active) Color(0xFF2563EB) else Color(0xFF3C4450),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            vm.switchPage(page.id)
                            rename = page.name
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${index + 1}", modifier = Modifier.padding(end = 10.dp), color = Color(0xFF9CA3AF), fontSize = 12.sp)
                    Column(Modifier.weight(1f)) {
                        Text(page.name, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        val count = if (active) vm.elements.size else page.elements.size
                        Text("$count 个元素", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                    }
                    if (active) Text("当前", color = Color(0xFF93C5FD), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun PageActionButton(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) Color(0xFF7F1D1D) else Color(0xFF353B46)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 11.sp) }
}
