package com.gameuistudio.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebUiCaptureSheet(
    result: ApkInspectionResult,
    onDismiss: () -> Unit,
    onCaptured: (DomCapture) -> Unit,
    onReferenceCaptured: (File) -> Unit
) {
    val context = LocalContext.current
    val indexFile = remember(result.id) {
        result.resources.firstOrNull {
            it.archivePath.equals("assets/index.html", ignoreCase = true)
        }?.let { File(it.extractedPath) }
    }

    var loaded by remember(result.id) { mutableStateOf(false) }
    var captureCount by remember(result.id) { mutableIntStateOf(0) }
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
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
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
                    status = "页面已载入：可点击、滚动、切页后继续捕获"
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

    fun captureCurrent(after: (() -> Unit)? = null) {
        if (!loaded) return
        status = "正在读取当前可见页面 DOM…"
        webView.evaluateJavascript(WebUiCapture.captureScript) { raw ->
            val capture = WebUiCapture.decodeEvaluateResult(raw)
            if (capture == null) {
                status = "捕获失败：没有读到可解析的页面结构"
                after?.invoke()
            } else {
                captureCount += 1
                status = "第 $captureCount 页：识别 ${capture.nodes.size} 个可编辑元素"
                onCaptured(capture)
                after?.invoke()
            }
        }
    }

    fun captureWholeScrollablePage() {
        if (!loaded) return
        status = "正在分析滚动页面高度…"
        webView.evaluateJavascript(WebUiCapture.scrollMetricsScript) { raw ->
            val metrics = WebUiCapture.decodeScrollMetrics(raw)
            if (metrics == null) {
                status = "无法读取页面滚动高度"
                return@evaluateJavascript
            }

            val total = ceil(metrics.documentHeight / metrics.viewportHeight)
                .toInt()
                .coerceIn(1, 12)

            fun captureSegment(index: Int) {
                if (index >= total) {
                    webView.evaluateJavascript("window.scrollTo(0,0);") {}
                    status = "整页拆解完成：共 $total 个屏幕片段"
                    return
                }

                val y = index * metrics.viewportHeight
                status = "整页拆解：正在捕获 ${index + 1}/$total"
                webView.evaluateJavascript("window.scrollTo(0,$y);") {
                    webView.postDelayed({
                        webView.evaluateJavascript(WebUiCapture.captureScript) { captureRaw ->
                            val capture = WebUiCapture.decodeEvaluateResult(captureRaw)
                            if (capture != null) {
                                captureCount += 1
                                onCaptured(
                                    capture.copy(
                                        title = capture.title.ifBlank { "滚动页面" } +
                                            " · ${index + 1}/$total"
                                    )
                                )
                            }
                            captureSegment(index + 1)
                        }
                    }, 180L)
                }
            }

            captureSegment(0)
        }
    }

    fun saveReferenceScreenshot() {
        if (webView.width <= 0 || webView.height <= 0) {
            status = "底稿截图失败：预览尺寸无效"
            return
        }
        runCatching {
            val bitmap = Bitmap.createBitmap(
                webView.width,
                webView.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val file = File(context.cacheDir, "apk-reference-${System.currentTimeMillis()}.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            onReferenceCaptured(file)
            status = "当前原页面已保存为半透明设计底稿"
        }.onFailure {
            status = "底稿截图失败：${it.message ?: "未知错误"}"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF20242B)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(0.97f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("APK 页面拆解工作台", fontSize = 19.sp)
            Text(
                "在原页面中直接点击、滚动、切换模块；可逐页捕获，也可把整个滚动页连续拆成多页。",
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
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.68f)
            )

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                CaptureButton("捕获当前页", primary = true, enabled = loaded) {
                    captureCurrent()
                }
                CaptureButton("整页连续拆解", primary = true, enabled = loaded) {
                    captureWholeScrollablePage()
                }
                CaptureButton("截图作底稿", enabled = loaded) {
                    saveReferenceScreenshot()
                }
                CaptureButton("上一屏", enabled = loaded) {
                    webView.evaluateJavascript("window.scrollBy(0,-innerHeight*0.85);") {}
                }
                CaptureButton("下一屏", enabled = loaded) {
                    webView.evaluateJavascript("window.scrollBy(0,innerHeight*0.85);") {}
                }
                CaptureButton("返回", enabled = webView.canGoBack()) {
                    webView.goBack()
                }
                CaptureButton("刷新", enabled = indexFile != null) {
                    loaded = false
                    status = "正在重新载入…"
                    webView.reload()
                }
                CaptureButton("完成", onClick = onDismiss)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "已加入：DOM 去重、嵌套按钮过滤、CSS 背景图、::before/::after 伪元素、滚动分屏、多次连续捕获和半透明底稿。",
                fontSize = 10.sp,
                color = Color(0xFFB6BDC8)
            )
            Spacer(Modifier.height(18.dp))
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
