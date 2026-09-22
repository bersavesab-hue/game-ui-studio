package com.gameuistudio.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
fun LayersSheet(vm: EditorViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("图层", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("多选模式下点图层可追加/取消选择", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                }
                Text("${vm.elements.size} 个元素", fontSize = 12.sp, color = Color(0xFFB6BDC8))
            }

            Spacer(Modifier.height(12.dp))

            if (vm.elements.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(100.dp)
                        .background(Color(0xFF171B22), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("当前页面还没有元素", color = Color(0xFF9CA3AF), fontSize = 12.sp)
                }
            }

            vm.elements.sortedByDescending { it.zIndex }.forEach { item ->
                val selected = vm.isSelected(item.id)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .background(
                            if (selected) Color(0xFF25365D) else Color(0xFF2A2F38),
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            if (selected) 1.5.dp else 1.dp,
                            if (selected) Color(0xFF2563EB) else Color(0xFF3C4450),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { vm.selectElement(item.id) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (item.type) {
                            ElementType.IMAGE -> "图"
                            ElementType.TEXT -> "字"
                            ElementType.BUTTON -> "钮"
                            ElementType.PANEL -> "框"
                        },
                        modifier = Modifier.width(28.dp),
                        color = if (item.hidden) Color(0xFF6B7280) else Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.name,
                                maxLines = 1,
                                fontSize = 13.sp,
                                color = if (item.hidden) Color(0xFF6B7280) else Color.White
                            )
                            if (item.groupId != null) {
                                Text(" 组", fontSize = 9.sp, color = Color(0xFF5EEAD4))
                            }
                        }
                        Text(
                            "z=${item.zIndex}  ${item.x.toInt()},${item.y.toInt()}  ${item.width.toInt()}×${item.height.toInt()}",
                            fontSize = 9.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }

                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LayerButton(if (item.hidden) "显示" else "隐藏") { vm.toggleVisibility(item.id) }
                        LayerButton(if (item.locked) "解锁" else "锁") { vm.toggleLock(item.id) }
                        LayerButton("↑") { vm.moveLayerUp(item.id) }
                        LayerButton("↓") { vm.moveLayerDown(item.id) }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun LayerButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF353B46)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 5.dp)
    ) { Text(text, fontSize = 10.sp) }
}
