package com.template.webviewapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import java.io.File

/**
 * Owns everything about *how the WebView behaves*: settings, navigation
 * rules, progress, offline detection, file uploads, geolocation, and
 * downloads. Deliberately has no Activity-lifecycle knowledge beyond what's
 * passed in as callbacks, so it stays unit-testable and reusable.
 */
class WebViewController(
    private val activity: Activity,
    private val webView: WebView,
    private val progressBar: ProgressBar,
    private val onOfflineStateChanged: (Boolean) -> Unit,
    private val onConnectivityBannerChanged: (Boolean) -> Unit,
    private val requestRuntimePermission: (String, (Boolean) -> Unit) -> Unit,
    private val launchFileChooser: (Intent, (Array<Uri>?) -> Unit) -> Unit
) {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var startUrlHost: String? = null

    // The registrable "root" of the start URL's host, with a leading "www."
    // stripped -- e.g. both "www.example.com" and "example.com" normalize
    // to "example.com". Used by isSameSite() so navigation across
    // subdomains of the app's own site (checkout., auth., m., cdn., etc.)
    // stays inside the WebView instead of bouncing out to the browser.
    private var startUrlRootHost: String? = null

    // Once a page has finished loading successfully at least once, a later
    // failure (connection drop, 5xx) shouldn't blank out content the user
    // is already reading -- show a small banner instead of the full offline
    // screen, which is only appropriate for a cold-start failure.
    private var hasLoadedSuccessfully = false

    fun setup(startUrl: String) {
        startUrlHost = Uri.parse(startUrl).host
        startUrlRootHost = startUrlHost?.removePrefix("www.")

        configureSettings()
        webView.webViewClient = buildWebViewClient()
        webView.webChromeClient = buildWebChromeClient()
        webView.loadUrl(startUrl)
    }

    private fun configureSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // Required for onCreateWindow() to actually fire on
            // window.open()/target="_blank" -- without this it's off by
            // default and those links fall through to default OS handling.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            // Hand off to the system download manager / browser rather than
            // trying to implement in-app downloads -- this is the correct
            // MVP behavior for a generic WebView wrapper.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        }
    }

    // True if `host` is the app's own site: the exact root domain, "www."
    // plus the root domain, or any subdomain of it. False for anything
    // genuinely external (a different brand/domain entirely), which is
    // the only case that should still exit to the system browser.
    private fun isSameSite(host: String?): Boolean {
        val root = startUrlRootHost ?: return false
        if (host == null) return false
        val normalizedHost = host.removePrefix("www.")
        return normalizedHost == root || normalizedHost.endsWith(".$root")
    }

    private fun buildWebViewClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // Keep same-site navigation inside the app -- this now matches
            // any subdomain of the app's own root domain (www., checkout.,
            // auth., m., cdn., etc), not just an exact host string match.
            // Anything genuinely off-site (payment gateways, third-party
            // auth, mailto:, tel:, etc) still goes to the system so it
            // opens in the real browser/app.
            return if (isSameSite(url.host)) {
                false
            } else {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (_: Exception) {
                    // No app can handle it (e.g. unsupported scheme) -- ignore.
                }
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            progressBar.visibility = android.view.View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progressBar.visibility = android.view.View.GONE
            hasLoadedSuccessfully = true
            onOfflineStateChanged(false)
            onConnectivityBannerChanged(false)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            // Only trigger offline UI for the main frame failing, not for
            // a stray ad/tracker sub-resource timing out.
            if (request.isForMainFrame) {
                handleMainFrameFailure()
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            // 5xx = the server itself is having trouble, not just a bad
            // network path -- treat it the same as a connectivity failure
            // rather than silently doing nothing. 4xx (404, etc) is left
            // alone since that's usually a real page-not-found, not
            // something a "you're offline" message would explain correctly.
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                handleMainFrameFailure()
            }
        }

        private fun handleMainFrameFailure() {
            if (hasLoadedSuccessfully) {
                onConnectivityBannerChanged(true)
            } else {
                // Chromium renders its own network-error interstitial (the
                // "https://... no internet" page) into the WebView surface
                // itself as part of failing the navigation -- our offlineView
                // overlay sits on top of it, but blanking the WebView here
                // too means there's nothing web-like underneath even for a
                // single frame. Without this, a device that's slow to paint
                // offlineView can show a flash of Chromium's own error page,
                // which breaks the native-app illusion.
                webView.stopLoading()
                webView.loadUrl("about:blank")
                onOfflineStateChanged(true)
            }
        }
    }

    private fun buildWebChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            if (newProgress >= 100) {
                progressBar.visibility = android.view.View.GONE
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            requestRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION) { granted ->
                callback.invoke(origin, granted, false)
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Covers getUserMedia() (camera/mic) calls from web content.
            val cameraRequested = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (!cameraRequested) {
                request.deny()
                return
            }
            requestRuntimePermission(Manifest.permission.CAMERA) { granted ->
                if (granted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }

        // Handles target="_blank" links and window.open() calls, which go
        // through window creation rather than shouldOverrideUrlLoading.
        // Left unhandled, these either silently do nothing or fall through
        // to default OS handling that can pop the system browser -- so we
        // intercept the target URL with a throwaway WebView, then route it
        // through the same same-site check as normal navigation.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            val transportWebView = WebView(activity)
            transportWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    if (isSameSite(url.host)) {
                        webView.loadUrl(url.toString())
                    } else {
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
                        } catch (_: Exception) {
                            // No app can handle it -- ignore.
                        }
                    }
                    return true
                }
            }

            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = transportWebView
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            filePathCallback = callback

            requestRuntimePermission(Manifest.permission.CAMERA) { _ ->
                // Whether or not camera was granted, always offer the
                // standard system file/gallery picker as a fallback so
                // uploads work even when PERMISSION_CAMERA is off.
                val chooserIntent = buildFileChooserIntent(fileChooserParams)
                launchFileChooser(chooserIntent) { uris ->
                    filePathCallback?.onReceiveValue(uris)
                    filePathCallback = null
                }
            }
            return true
        }
    }

    private fun buildFileChooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val captureIntents = mutableListOf<Intent>()
        try {
            val photoFile = File.createTempFile("capture_", ".jpg", activity.externalCacheDir)
            val photoUri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", photoFile
            )
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }
            if (cameraIntent.resolveActivity(activity.packageManager) != null) {
                captureIntents.add(cameraIntent)
            }
        } catch (_: Exception) {
            // No camera app available or file creation failed -- fall back
            // to the plain content chooser below.
        }

        return Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_INITIAL_INTENTS, captureIntents.toTypedArray())
        }
    }
}
