package com.dqmp.app.display.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    triggerUnlock: Boolean = false,
    onPageError: (String) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "${settings.userAgentString} DQMP_APK"
                }

                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun isNative(): Boolean = true
                }, "AndroidApp")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Inject a foolproof flag that the web app can see
                        view?.evaluateJavascript("window.DQMP_NATIVE = true;", null)
                    }

                    @SuppressLint("WebViewClientOnReceivedSslError")
                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        // Allow self-signed certificates as requested in previous requirements for TV boxes
                        handler?.proceed()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        val errorMessage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            error?.description?.toString() ?: "Unknown error"
                        } else {
                            "Network error"
                        }
                        onPageError(errorMessage)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        android.util.Log.d("DQMP_WEB", consoleMessage?.message() ?: "")
                        return true
                    }
                }

                loadUrl(url)
            }
        },
        update = { webView ->
            // Update URL only if it changed dramatically to avoid reload loops
            if (webView.url != url) {
                webView.loadUrl(url)
            }
            
            if (triggerUnlock) {
                webView.evaluateJavascript("if(window.forceUnlockAudio) { window.forceUnlockAudio(); }", null)
            }
        }
    )
}
