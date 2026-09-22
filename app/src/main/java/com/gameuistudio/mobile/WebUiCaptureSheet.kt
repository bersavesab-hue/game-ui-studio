package com.gameuistudio.mobile

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebUiCaptureSheet(
    result: ApkInspectionResult,
    onDismiss: () -> Unit,
    onCaptured: (DomCapture) -> Unit
) {
    val context = LocalContext.current
    val indexFile = remember(result.id) {
        result.resources.firstOrNull {
            it.archivePath.equals("assets/index.html", ignoreCase = true)
        }?.let { File(it.extractedPath) }
    }

    var loaded by remember(result.id) { mutableStateOf(false) }
    var status by remember(result.id) {
        mutableStateOf(if (indexFile != null) "正在载入拆解页面…" else "未发现 assets/index.html")
    }

    val webView = remember(result.id) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            WebView.setWebContentsDebuggingEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded = true
                    status = "页面已载入，可在预览里操作后捕获当前界面"
                }
            }
            indexFile?.let { loadUrl(it.toURI().toString()) }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(0.96f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("WebView 页面自动拆解", fontSize = 19.sp)
            Text(
                "直接操作下面的原页面，切到你想重做的界面后点“捕获当前页”。",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                status,
                fontSize = 10.sp,
                color = if (loaded) Color(0xFF86EFAC) else Color(0xFFFBBF24),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f)
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CaptureButton("捕获当前页", primary = true, enabled = loaded) {
                    status = "正在读取 DOM、尺寸和样式…"
                    webView.evaluateJavascript(WebUiCapture.captureScript) { raw ->
                        val capture = WebUiCapture.decodeEvaluateResult(raw)
                        if (capture == null) {
                            status = "捕获失败：没有读到可解析的页面结构"
                        } else {
                            status = "已识别 ${capture.nodes.size} 个可编辑元素"
                            onCaptured(capture)
                        }
                    }
                }
                CaptureButton("返回", enabled = webView.canGoBack()) {
                    webView.goBack()
                }
                CaptureButton("刷新", enabled = indexFile != null) {
                    loaded = false
                    status = "正在重新载入…"
                    webView.reload()
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "这一模式针对 city-restaurant-game-v2 这类 WebView/H5 APK：运行原页面后读取真实 DOM、位置、文字、颜色、按钮和图片，再转换成编辑器图层。",
                fontSize = 10.sp,
                color = Color(0xFFB6BDC8)
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun CaptureButton(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Color(0xFF2563EB) else Color(0xFF353B46),
            disabledContainerColor = Color(0xFF252A33),
            disabledContentColor = Color(0xFF687180)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp)
    }
}
