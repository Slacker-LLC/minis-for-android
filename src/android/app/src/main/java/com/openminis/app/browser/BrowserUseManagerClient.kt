package com.openminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openminis.app.browser.BrowserUseManager.BridgedRun
import com.openminis.app.browser.BrowserUseManager.Companion.BLANK_PAGE_HTML
import com.openminis.app.browser.BrowserUseManager.Companion.MAX_FULL_PAGE_HEIGHT_PX
import com.openminis.app.browser.BrowserUseManager.Companion.NAVIGATION_TIMEOUT_MS
import com.openminis.app.browser.BrowserUseManager.Companion.SCREENSHOT_QUALITY
import com.openminis.app.browser.BrowserUseManager.Companion.SNAPSHOT_QUALITY
import com.openminis.app.browser.BrowserUseManager.Companion.TAG
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.tools.ExternalMountAccess
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Read a blob: URL from inside the page's JS context and deliver its bytes
 * through the `__minis__.saveBlobDownload` bridge. blob: object URLs are
 * scoped to the page — they cannot be fetched from native code, so this
 * injected fetch + FileReader round-trip is the only way to get the data.
 */
internal fun BrowserUseManager.fetchBlobDownload(blobUrl: String, contentDisposition: String?, mimeType: String?) {
    val guessedName = android.webkit.URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
    val js = """
        (function() {
            fetch(${JSONObject.quote(blobUrl)})
                .then(function(r) { return r.blob(); })
                .then(function(blob) {
                    var reader = new FileReader();
                    reader.onloadend = function() {
                        __minis__.saveBlobDownload(reader.result, ${JSONObject.quote(guessedName)});
                    };
                    reader.onerror = function() { __minis__.blobDownloadError('FileReader error'); };
                    reader.readAsDataURL(blob);
                })
                .catch(function(e) { __minis__.blobDownloadError(String(e)); });
        })();
    """.trimIndent()
    webView.post { webView.evaluateJavascript(js, null) }
}

/**
 * Compute and install a `setInitialScale` so a page authored at
 * [cssWidth] CSS pixels fits inside the visible WebView container. Called
 * after every [applyViewport] and on every container size change.
 *
 * `setInitialScale(percent)` is sticky — it applies on the next page
 * load. The tab pool's `applyViewportToAllTabs()` already reloads each
 * tab after viewport changes, so the scale is picked up on that reload.
 * Plain navigation between pages reuses whatever scale was last set.
 *
 * Passing 0 restores WebView's default behavior (use page's own scale).
 * We pass 0 whenever the CSS viewport already fits — no point shrinking
 * a 412-wide viewport on a 1080-wide container.
 */
internal fun BrowserUseManager.applyShrinkToFit(cssWidth: Int) {
    val containerPx = lastKnownContainerWidthPx
    if (containerPx <= 0 || cssWidth <= 0) return
    val density = webView.resources.displayMetrics.density
    val cssWidthPx = (cssWidth * density).toInt()
    var scalePct = if (cssWidthPx > containerPx) {
        ((containerPx.toLong() * 100) / cssWidthPx).toInt().coerceAtLeast(1)
    } else {
        0 // CSS viewport already fits — let WebView use its default scale.
    }
    // [T-android-browser-blank] Guard against a corrupted container width
    // (e.g. a synthetic 1px layout leaking into the layout listener): a
    // microscopic sticky initial scale renders the next page load
    // effectively blank. No legitimate shrink-to-fit is below ~10%
    // (desktop 1280 CSS on a 360dp phone is ~28%) — fall back to the
    // WebView default instead.
    if (scalePct in 1..9) {
        Log.w(TAG, "applyShrinkToFit: implausible scale $scalePct% (container=${containerPx}px, css=${cssWidthPx}px) — using default")
        scalePct = 0
    }
    webView.setInitialScale(scalePct)
}

@SuppressLint("SetJavaScriptEnabled")
internal fun BrowserUseManager.setupWebViewClient() {
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val urlStr = request.url?.toString()
            // T234: Google permanently disallows WebView for sign-in /
            // OAuth. Hand any auth-domain navigation to Chrome Custom
            // Tab so the user can complete login in their real Chrome
            // session instead of hitting 403 disallowed_useragent.
            if (GoogleAuthRouter.shouldRouteExternally(urlStr)) {
                if (urlStr != null) {
                    GoogleAuthRouter.openInCustomTab(view.context, urlStr)
                }
                return true
            }
            // T134: route intent://, market://, tel:, mailto:, … out
            // of the WebView so they reach the matching app instead of
            // surfacing as ERR_UNKNOWN_URL_SCHEME.
            return com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                .handle(
                    view.context,
                    request.url,
                    com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                        .Origin.AGENT_BACKGROUND,
                )
        }

        override fun onPageFinished(view: WebView, url: String?) {
            _isLoading.value = false
            _currentURL.value = url ?: ""
            _pageTitle.value = view.title ?: ""
            _canGoBack.value = view.canGoBack()
            _canGoForward.value = view.canGoForward()
            navigationDeferred?.complete(Unit)
            navigationDeferred = null
            // Record in browser history
            val histUrl = url ?: ""
            val histTitle = view.title ?: ""
            if (histUrl.isNotEmpty() && histUrl != "about:blank") {
                BrowserHistoryStore.getInstance(view.context).record(histUrl, histTitle)
            }
            // T-webview-popup-d3c6e10f (Issue 1): after the pool WebView's
            // setInitialScale settles, force a JS `resize` event so
            // `position:fixed` elements (sticky headers, cookie banners,
            // floating chat) recompute against the post-scale visual
            // viewport instead of the pre-scale layout viewport. Without
            // this, fixed-positioned UI on some sites drifts off the
            // visible region after the synthetic measure+layout pass.
            view.postDelayed({
                view.evaluateJavascript(
                    "window.dispatchEvent(new Event('resize'));", null,
                )
            }, 80)
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            if (request.isForMainFrame) {
                _isLoading.value = false
                Log.e(TAG, "Navigation error: ${error.description}")
                navigationDeferred?.complete(Unit)
                navigationDeferred = null
            }
        }

        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): android.webkit.WebResourceResponse? {
            val url = request.url ?: return null
            if (url.scheme != "minis") return null
            return interceptMinisURL(url)
        }
    }
}

/** Resolve minis:// URLs to local workspace files. */
internal fun BrowserUseManager.interceptMinisURL(uri: android.net.Uri): android.webkit.WebResourceResponse? {
    try {
        val host = uri.host ?: return null
        val path = uri.path ?: ""
        val linuxPath = "/var/minis/$host$path"
        val name = path.substringAfterLast('/').ifEmpty { host }
        val bytes = if (linuxPath.startsWith("/var/minis/mounts/")) {
            runCatching { ExternalMountAccess.readBlocking(linuxPath) }.getOrElse {
                return notFound(host, path)
            }
        } else {
            val sid = sessionIdProvider()?.takeIf { it.isNotBlank() }
                ?: return notFound(host, path)
            runCatching {
                WorkspaceFileClient.readAllBlocking(sid, linuxPath)
            }.getOrElse {
                Log.w(TAG, "minis:// read failed for $linuxPath: ${it.message}")
                return notFound(host, path)
            }
        }
        val mimeType = guessMimeType(name)
        // For HTML mainframe responses, inject a `<meta viewport>` matching
        // the agent's session viewport when the page doesn't declare one.
        // Without this, Android WebView falls back to a hardcoded 980 CSS
        // px width regardless of the WebView's measured size, making
        // `set_viewport` look like a no-op for `minis://` HTML pages.
        val stream = if (mimeType == "text/html" && lastAppliedViewport != null) {
            ensureMetaViewport(bytes, lastAppliedViewport!!.first)
        } else {
            bytes.inputStream()
        }
        return android.webkit.WebResourceResponse(mimeType, "UTF-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            stream)
    } catch (e: Exception) {
        Log.w(TAG, "minis:// intercept error: ${e.message}")
        return null
    }
}

internal fun BrowserUseManager.notFound(host: String, path: String): android.webkit.WebResourceResponse =
    android.webkit.WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        "File not found: $host$path".byteInputStream(),
    )

/**
 * If the HTML lacks a `<meta name="viewport">`, splice one in matching
 * the agent's CSS-px viewport. Leaves pages that already declare a
 * viewport untouched so author intent (e.g. `width=1200`) wins.
 */
internal fun BrowserUseManager.ensureMetaViewport(html: ByteArray, cssWidth: Int): java.io.InputStream {
    val text = String(html, Charsets.UTF_8)
    if (text.contains("name=\"viewport\"", ignoreCase = true) ||
        text.contains("name='viewport'", ignoreCase = true)) {
        return text.toByteArray(Charsets.UTF_8).inputStream()
    }
    // user-scalable=yes is the default but state it explicitly so a
    // future change to WebView defaults can't silently disable
    // pinch-zoom on the agent's auto-injected viewport.
    val meta = "<meta name=\"viewport\" content=\"width=$cssWidth, initial-scale=1.0, user-scalable=yes\">"
    val headIdx = text.indexOf("<head", ignoreCase = true).takeIf { it >= 0 }?.let {
        text.indexOf('>', it).takeIf { gt -> gt >= 0 }?.plus(1)
    }
    val rewritten = if (headIdx != null) {
        text.substring(0, headIdx) + meta + text.substring(headIdx)
    } else {
        // No <head>: prepend the meta so it's still parsed before body.
        "$meta$text"
    }
    return rewritten.toByteArray(Charsets.UTF_8).inputStream()
}

internal fun BrowserUseManager.guessMimeType(filename: String): String {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "xml" -> "text/xml"
        else -> "application/octet-stream"
    }
}

internal fun BrowserUseManager.setupWebChromeClient() {
    webView.webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) {
            _pageTitle.value = title ?: ""
        }

        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            onNewWindow?.invoke(resultMsg)
            return onNewWindow != null
        }

        override fun onCloseWindow(window: WebView) {
            onCloseWindow?.invoke()
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?,
        ): Boolean {
            recordInterceptedDialog("alert", message.orEmpty(), null, "(dismissed)")
            result?.confirm()
            return true
        }

        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult?,
        ): Boolean {
            recordInterceptedDialog("confirm", message.orEmpty(), null, "false")
            result?.cancel()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult?,
        ): Boolean {
            recordInterceptedDialog("prompt", message.orEmpty(), defaultValue, "null")
            result?.cancel()
            return true
        }
    }
}

internal fun BrowserUseManager.recordInterceptedDialog(
    kind: String,
    message: String,
    defaultText: String?,
    defaultResponse: String,
) {
    val url = _currentURL.value
    dialogQueue.record(
        kind = kind,
        message = message,
        defaultText = defaultText,
        pageURL = url.ifEmpty { null },
        defaultResponse = defaultResponse,
    )
    Log.i(TAG, "[JSDialog] intercepted $kind on $url — answered $defaultResponse")
}

fun BrowserUseManager.drainInterceptedDialogReport(): String? = dialogQueue.drainReport()

// -- Execute Action --

suspend fun BrowserUseManager.execute(input: BrowserActionInput): BrowserActionResult {
    val prevUrl = withContext(Dispatchers.Main) { webView.url }
    var result: BrowserActionResult = when (input.action) {
        BrowserAction.NAVIGATE -> navigate(input.url)
        BrowserAction.SCREENSHOT ->
            return screenshot(fullPage = input.fullPage, readImage = input.readImage)
        BrowserAction.CLICK -> click(input.selector, input.coordinateX, input.coordinateY)
        BrowserAction.TYPE -> type(input.selector, input.coordinateX, input.coordinateY, input.text, input.submit)
        BrowserAction.GET_TEXT -> return getText(input.selector, input.offset, input.maxChars)
        BrowserAction.SCROLL -> scroll(input.selector, input.direction, input.amount)
        BrowserAction.GET_PAGE_INFO -> return getPageInfo()
        BrowserAction.EXECUTE_JS -> return executeJS(input.script)
        BrowserAction.FIND_ELEMENTS -> return findElements(input.selector)
        BrowserAction.HOVER -> hover(input.selector)
        BrowserAction.GET_READABLE -> return getReadable(input.offset, input.maxChars)
        BrowserAction.SET_USER_AGENT -> return setUserAgent(input.userAgent)
        BrowserAction.SET_VIEWPORT ->
            return BrowserActionResult.error("set_viewport must be routed through BrowserTabPool")
        BrowserAction.GET_BACKBONE -> return getBackbone(input.maxDepth)
        BrowserAction.FETCH -> return fetch(input.url)
        BrowserAction.GET_COOKIES -> return getCookies(input.keywords, input.fuzzy)
        BrowserAction.SET_COOKIES -> return setCookies(input.cookies)
        BrowserAction.SCROLL_AND_COLLECT -> return scrollAndCollect(
            input.scrollCount, input.itemSelector, input.keywords,
        )
        BrowserAction.WAIT_FOR_DOM_STABLE -> return waitForDomStable(input.timeoutMs)
        BrowserAction.WAIT_FOR_SELECTOR -> return waitForSelector(input.selector, input.timeoutMs)
        BrowserAction.GO_BACK -> return historyNavigation(backwards = true)
        BrowserAction.GO_FORWARD -> return historyNavigation(backwards = false)
        BrowserAction.RELOAD -> return reloadPage()
        BrowserAction.NEW_TAB, BrowserAction.CLOSE_TAB, BrowserAction.LIST_TABS ->
            return BrowserActionResult.error("Tab management actions must be routed through BrowserTabPool")
    }

    // Auto-capture screenshot after visual-change actions
    if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
        result = attachSnapshot(result, attachImage = input.readImage)
    }

    // Detect URL change after visual-change actions (ignore hash-only changes)
    if (result.success && BrowserAction.visualChangeActions.contains(input.action)) {
        val newUrl = withContext(Dispatchers.Main) { webView.url }
        if (prevUrl != null && newUrl != null) {
            val prevNoHash = prevUrl.substringBefore("#")
            val curNoHash = newUrl.substringBefore("#")
            if (prevNoHash != curNoHash) {
                result = result.copy(
                    text = result.text + "\n[URL Changed] Page navigated: $prevUrl -> $newUrl. Take a screenshot to see the current state before continuing."
                )
            }
        }
    }

    return result
}

internal suspend fun BrowserUseManager.attachSnapshot(
    result: BrowserActionResult,
    attachImage: Boolean = true,
): BrowserActionResult {
    return try {
        delay(300) // Let page settle
        val bitmap = captureWebViewBitmap() ?: return result
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_QUALITY, output)
        bitmap.recycle()
        result.copy(
            base64Image = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            imageFilePath = null,
            attachImage = attachImage,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Auto-snapshot failed: ${e.message}")
        result
    }
}

// -- Navigate --

internal suspend fun BrowserUseManager.navigate(urlString: String?): BrowserActionResult {
    if (urlString.isNullOrEmpty()) return BrowserActionResult.error("Missing 'url' parameter")

    var normalized = urlString
    if (!normalized.contains("://")) normalized = "https://$normalized"

    awaitNavigation(label = "navigation to $normalized") {
        // Re-assert the last applied viewport before loadUrl. Intercepted
        // navigations (minis://) served via shouldInterceptRequest skip
        // the layout pass that a real network load triggers, so without
        // this the page reports Android WebView's 980px no-meta fallback
        // even when a session override (e.g. 960x540) is active.
        lastAppliedViewport?.let { (w, h) -> applyViewport(w, h) }
        webView.loadUrl(normalized)
    }

    _currentURL.value = _currentURL.value.ifEmpty { normalized }

    val meta = navigationMetadata()
    return BrowserActionResult(text = meta)
}

internal suspend fun BrowserUseManager.navigationMetadata(): String {
    val url = _currentURL.value
    val title = _pageTitle.value
    // Read the viewport directly from the page (`window.innerWidth/Height`)
    // so a session or global viewport override shows the actual layout
    // size, not the UA profile default. Matches iOS which likewise queries
    // the WKWebView's live bounds rather than a profile constant.
    val scrollInfo = evaluateJavascript(
        "JSON.stringify({" +
            "sx:window.scrollX||0,sy:window.scrollY||0," +
            "pw:document.documentElement.scrollWidth||0," +
            "ph:document.documentElement.scrollHeight||0," +
            "vw:window.innerWidth||0,vh:window.innerHeight||0" +
            "})"
    )
    var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
    var vpW = 0; var vpH = 0
    try {
        val info = JSONObject(scrollInfo)
        scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
        pageW = info.optInt("pw"); pageH = info.optInt("ph")
        vpW = info.optInt("vw"); vpH = info.optInt("vh")
    } catch (_: Exception) {}

    // Fall back to the UA profile default when the page hasn't populated
    // `window.inner*` yet (e.g. navigation failure / about:blank).
    val fallback = currentProfile.viewportSize
    val effectiveVpW = if (vpW > 0) vpW else fallback.first
    val effectiveVpH = if (vpH > 0) vpH else fallback.second

    return buildString {
        appendLine("Navigated to $url")
        if (title.isNotEmpty()) appendLine("  Title: $title")
        appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
        if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
        append("  Scroll position: ($scrollX, $scrollY)")
    }
}

// -- Screenshot --

internal suspend fun BrowserUseManager.screenshot(
    fullPage: Boolean = false,
    readImage: Boolean = true,
): BrowserActionResult {
    var truncated = false
    var originalHeightPx = 0
    var didStretch = false
    var savedW = 0
    var savedH = 0

    if (fullPage) {
        // Measure full document height in CSS pixels.
        val cssScrollHeight = evaluateJavascript("document.documentElement.scrollHeight").let {
            it.trim().toIntOrNull() ?: 0
        }
        val density = webView.resources.displayMetrics.density
        val scrollHeightPx = if (cssScrollHeight > 0) {
            (cssScrollHeight * density).toInt()
        } else {
            withContext(Dispatchers.Main) { webView.height }
        }
        originalHeightPx = scrollHeightPx
        val cappedPx = scrollHeightPx.coerceAtMost(MAX_FULL_PAGE_HEIGHT_PX)
        truncated = scrollHeightPx > MAX_FULL_PAGE_HEIGHT_PX

        // Eagerize lazy images and wait two RAFs so layout settles before capture.
        try {
            evaluateJavascript(
                """
                (async () => {
                    document.querySelectorAll('img[loading="lazy"]').forEach(i => i.loading = 'eager');
                    await new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                    return 'ok';
                })()
                """.trimIndent()
            )
        } catch (_: Exception) { /* best-effort */ }
        delay(50)

        // Snapshot current viewport, stretch to cssScrollHeight, capture, restore.
        val applied = lastAppliedViewport ?: currentProfile.viewportSize
        savedW = applied.first
        savedH = applied.second
        val cssCappedHeight = (cappedPx / density).toInt().coerceAtLeast(savedH)
        withContext(Dispatchers.Main) {
            applyViewport(savedW, cssCappedHeight)
        }
        didStretch = true
        Log.i(TAG, "full_page stretch: ${savedW}x$cssCappedHeight CSS (px=$cappedPx, original=$scrollHeightPx, truncated=$truncated)")
    }

    val bitmap = try {
        captureWebViewBitmap()
    } finally {
        if (didStretch) {
            withContext(Dispatchers.Main) {
                applyViewport(savedW, savedH)
            }
        }
    } ?: return BrowserActionResult.error("Failed to capture screenshot")

    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, out)
    val jpegBytes = out.toByteArray()

    val file = saveBitmapToFile(bitmap, "screenshot")
    val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

    val w = bitmap.width; val h = bitmap.height
    bitmap.recycle()

    Log.i(TAG, "Screenshot saved: ${file.absolutePath}, ${w}x$h, ${jpegBytes.size} bytes (full_page=$fullPage)")

    val meta = viewportMetadata(
        imageW = w,
        imageH = h,
        fileSize = jpegBytes.size,
        fullPage = fullPage,
        truncated = truncated,
        originalHeightPx = originalHeightPx,
    )

    return BrowserActionResult(
        text = meta,
        base64Image = base64,
        imageFilePath = file.absolutePath,
        attachImage = readImage,
    )
}

/** Collect viewport + page metadata for screenshot results. Mirrors iOS viewportMetadata. */
internal suspend fun BrowserUseManager.viewportMetadata(
    imageW: Int,
    imageH: Int,
    fileSize: Int,
    fullPage: Boolean = false,
    truncated: Boolean = false,
    originalHeightPx: Int = 0,
): String {
    val url = _currentURL.value
    val title = _pageTitle.value

    val scrollInfo = evaluateJavascript(
        "JSON.stringify({" +
            "sx:window.scrollX||0,sy:window.scrollY||0," +
            "pw:document.documentElement.scrollWidth||0," +
            "ph:document.documentElement.scrollHeight||0," +
            "vw:window.innerWidth||0,vh:window.innerHeight||0" +
            "})"
    )
    var scrollX = 0; var scrollY = 0; var pageW = 0; var pageH = 0
    var vpW = 0; var vpH = 0
    try {
        val info = JSONObject(scrollInfo)
        scrollX = info.optInt("sx"); scrollY = info.optInt("sy")
        pageW = info.optInt("pw"); pageH = info.optInt("ph")
        vpW = info.optInt("vw"); vpH = info.optInt("vh")
    } catch (_: Exception) {}

    val fallback = currentProfile.viewportSize
    val effectiveVpW = if (vpW > 0) vpW else fallback.first
    val effectiveVpH = if (vpH > 0) vpH else fallback.second

    return buildString {
        appendLine("Screenshot captured")
        appendLine("  URL: $url")
        if (title.isNotEmpty()) appendLine("  Title: $title")
        appendLine("  Image: ${imageW}x$imageH (${fileSize / 1024}KB)")
        appendLine("  Viewport: ${effectiveVpW}x$effectiveVpH")
        if (pageW > 0 || pageH > 0) appendLine("  Page size: ${pageW}x$pageH")
        if (fullPage) {
            appendLine("  Full page: true")
            if (originalHeightPx > 0) appendLine("  Original height: ${originalHeightPx}px")
            if (truncated) appendLine("  Truncated: true (capped at ${MAX_FULL_PAGE_HEIGHT_PX}px)")
        }
        append("  Scroll position: ($scrollX, $scrollY)")
    }
}

/**
 * Public live-preview snapshot — mirrors iOS `webView.takeSnapshot()`.
 * Called by the UI on a timer (e.g. every 3s while a tool is streaming) so
 * the Minis Computer sheet and FloatingToolStatusBar can show the browser
 * state even for actions that don't save an imageFilePath (get_readable,
 * get_text, execute_js, fetch, etc.).
 */
suspend fun BrowserUseManager.captureLiveSnapshot(): Bitmap? = captureWebViewBitmap()

internal suspend fun BrowserUseManager.captureWebViewBitmap(): Bitmap? = withContext(Dispatchers.Main) {
    try {
        // WebView may be detached (pool-owned, never added to a window), so
        // width/height can be 0. Ensure it has a layout box matching the
        // agent viewport before drawing.
        val vp = currentProfile.viewportSize
        val density = webView.resources.displayMetrics.density
        var w = webView.width
        var h = webView.height
        if (w <= 0 || h <= 0) {
            // Profile sizes are CSS px; scale to physical px so the CSS
            // viewport actually matches. See applyViewport() for context.
            val targetW = (vp.first * density).toInt().coerceAtLeast(1)
            val targetH = (vp.second * density).toInt().coerceAtLeast(1)
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(targetW, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(targetH, android.view.View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, targetW, targetH)
            w = targetW; h = targetH
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        Log.d(TAG, "captureWebViewBitmap ${w}x$h")
        bitmap
    } catch (e: Exception) {
        Log.e(TAG, "captureWebViewBitmap failed: ${e.message}")
        null
    }
}

internal fun BrowserUseManager.saveBitmapToFile(bitmap: Bitmap, prefix: String, quality: Int = SCREENSHOT_QUALITY): File {
    val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
    val file = File(screenshotsDir, filename)
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }
    return file
}

// -- Click --

internal suspend fun BrowserUseManager.click(selector: String?, x: Int?, y: Int?): BrowserActionResult {
    if (selector == null && (x == null || y == null)) {
        return BrowserActionResult.error("click requires 'selector' or 'coordinate_x'/'coordinate_y'")
    }
    return evaluateAndReturn(BrowserDomScripts.click(selector, x, y))
}

// -- Type --

internal suspend fun BrowserUseManager.type(
    selector: String?,
    x: Int?,
    y: Int?,
    text: String?,
    submit: Boolean,
): BrowserActionResult {
    if (text == null) return BrowserActionResult.error("type requires 'text'")
    if (selector == null && (x == null || y == null)) {
        return BrowserActionResult.error("type requires 'selector' or 'coordinate_x'/'coordinate_y'")
    }
    return evaluateAndReturn(BrowserDomScripts.type(selector, x, y, text, submit))
}

// -- Get Text --

/**
 * [T-browser-paged-text-android] `offset` / `max_chars` are Eta's read-page
 * arguments (Mangi-11/Eta @ c15de97): the window is normalized here so the
 * page-side arithmetic and the model-facing header both come from
 * [BrowserTextWindowPolicy].
 */
internal suspend fun BrowserUseManager.getText(selector: String?, offset: Int?, maxChars: Int?): BrowserActionResult {
    val js = BrowserDomScripts.text(
        selector = selector,
        offset = BrowserTextWindowPolicy.offset(offset),
        maxChars = BrowserTextWindowPolicy.maxChars(maxChars),
    )
    return evaluateJSAndParse(js)
}

// -- Get Readable --

internal suspend fun BrowserUseManager.getReadable(offset: Int?, maxChars: Int?): BrowserActionResult {
    return evaluateJSAndParse(
        BrowserDomScripts.readable(
            offset = BrowserTextWindowPolicy.offset(offset),
            maxChars = BrowserTextWindowPolicy.maxChars(maxChars),
        ),
    )
}

// -- Scroll --

internal suspend fun BrowserUseManager.scroll(selector: String?, direction: ScrollDirection?, amount: Int?): BrowserActionResult {
    val dir = direction ?: ScrollDirection.DOWN
    val px = amount ?: 500
    return evaluateJSAndParse(BrowserDomScripts.scroll(selector, dir.value, px))
}

// -- Get Page Info --

internal suspend fun BrowserUseManager.getPageInfo(): BrowserActionResult {
    return evaluateAndReturn(BrowserDomScripts.pageInfo())
}

// -- Execute JS --

internal suspend fun BrowserUseManager.executeJS(script: String?): BrowserActionResult {
    if (script.isNullOrEmpty()) return BrowserActionResult.error("execute_js requires 'script'")
    // Two shapes, each run at most once.
    //
    //  * Expression form first — `document.title`, `JSON.stringify(…)`,
    //    `await fetch(…)` — because that is how agents write it, and the body
    //    form answered a bare expression with a silent "undefined" (measured
    //    on the device: `document.title` → "undefined", `return document.title`
    //    → "Example Domain").
    //  * Function-body form, the documented contract (`return`, statements,
    //    top-level await).
    //
    // Chromium reports a source that failed to compile through the evaluation
    // callback as null and never runs it, so the fallback cannot double-run
    // side effects — and a script that compiles in neither form is answered as
    // a syntax error instead of the old 30-second "timed out".
    evaluateBridged(BrowserUseJS.bridgedExpression(script))?.let { return it }
    evaluateBridged(BrowserUseJS.bridgedBody(script))?.let { return it }
    return BrowserActionResult.error(
        "execute_js script did not compile — check its syntax (the WebView refused it before running anything).",
    )
}

/**
 * Run [wrapped] (one of [BrowserUseJS]'s bridged wrappers). The evaluation
 * callback is the compile signal: a source that does not compile comes back as
 * `null`, while a compiled async IIFE comes back as its promise (`{}`); the
 * value itself then arrives through the `__minis__` bridge.
 */
internal suspend fun BrowserUseManager.runBridged(wrapped: String): BridgedRun {
    val deferred = CompletableDeferred<String>()
    val callback = CompletableDeferred<String?>()
    asyncJsDeferred = deferred
    try {
        withContext(Dispatchers.Main) {
            webView.evaluateJavascript(wrapped) { raw -> callback.complete(raw) }
        }
        val compiled = callback.await()
        if (compiled == null || compiled == "null") return BridgedRun.NotCompiled
        val answer = withTimeoutOrNull(30_000L) { deferred.await() } ?: return BridgedRun.TimedOut
        return BridgedRun.Answer(answer)
    } catch (e: Exception) {
        return BridgedRun.Answer("""{\"error\":${JSONObject.quote(e.message ?: "JavaScript error")}}""")
    } finally {
        asyncJsDeferred = null
    }
}

internal fun BrowserUseManager.decodeBridged(raw: String): BrowserActionResult {
    val json = try { JSONObject(raw) } catch (_: Exception) { null }
    return if (json != null) {
        if (json.has("error")) {
            BrowserActionResult.error(json.getString("error"))
        } else {
            BrowserActionResult(text = formatJSONResult(BrowserPayloadLimiter.bound(json)))
        }
    } else {
        // A raw script return is whatever the page decided to hand back — the
        // payload budget is the only bound on it.
        BrowserActionResult(text = BrowserPayloadLimiter.boundText(raw))
    }
}

/** Null means "did not compile" — the caller picks the next shape. */
internal suspend fun BrowserUseManager.evaluateBridged(wrapped: String): BrowserActionResult? =
    when (val run = runBridged(wrapped)) {
        BridgedRun.NotCompiled -> null
        BridgedRun.TimedOut -> BrowserActionResult.error("JavaScript execution timed out (30s)")
        is BridgedRun.Answer -> decodeBridged(run.raw)
    }

/**
 * Raw value of one page expression, for callers that need the JSON the page
 * produced rather than the human-readable text an action formats. The debug
 * probes (`pageInfo.viewport`) are the callers: parsing the formatted text
 * back into JSON is what silently produced all-zero viewports on the device.
 *
 * Null when the expression did not compile, timed out, or has no value — the
 * caller keeps its own default.
 */
internal suspend fun BrowserUseManager.evaluateExpressionRaw(expression: String): String? =
    when (val run = runBridged(BrowserUseJS.bridgedExpression(expression))) {
        BridgedRun.NotCompiled -> null
        BridgedRun.TimedOut -> null
        is BridgedRun.Answer -> run.raw
    }

// -- Find Elements --

internal suspend fun BrowserUseManager.findElements(selector: String?): BrowserActionResult {
    // [T-browser-element-rows-android] A missing selector stopped being an error:
    // the ported script falls back to upstream's interactive-element list, which
    // is what "show me what this page lets me touch" means.
    return evaluateAndReturn(BrowserDomScripts.findElements(selector))
}

// -- Hover --

internal suspend fun BrowserUseManager.hover(selector: String?): BrowserActionResult {
    if (selector == null) return BrowserActionResult.error("hover requires 'selector'")
    return evaluateAndReturn(BrowserDomScripts.hover(selector))
}

// -- Get Backbone --

internal suspend fun BrowserUseManager.getBackbone(maxDepth: Int?): BrowserActionResult {
    val depth = maxDepth ?: 5
    val js = BrowserUseJS.getBackbone(depth)
    val raw = evaluateJavascript(js)
    return try {
        val json = JSONObject(raw)
        if (json.has("error")) {
            BrowserActionResult.error(json.getString("error"))
        } else {
            BrowserActionResult(text = formatBackboneResult(json))
        }
    } catch (e: Exception) {
        BrowserActionResult.error("JavaScript error: ${e.message}")
    }
}

// -- Fetch --

internal suspend fun BrowserUseManager.fetch(urlString: String?): BrowserActionResult {
    if (urlString.isNullOrEmpty()) return BrowserActionResult.error("fetch requires 'url' parameter")

    // The fetch JS runs `await fetch(...)` inside an async IIFE, which
    // resolves to a Promise. Android's `WebView.evaluateJavascript` does
    // NOT await Promises, so calling `evaluateJavascript(js)` returns the
    // Promise's `{}` string representation and the caller sees a
    // "No value for base64" parse error. Route through the __minis__
    // bridge so we actually wait for the Promise to resolve.
    val raw = awaitPromiseJs(BrowserUseJS.fetch(urlString))
        ?: return BrowserActionResult.error("fetch timed out")
    return try {
        val json = JSONObject(raw)
        if (json.has("error")) {
            return BrowserActionResult.error("Fetch failed: ${json.getString("error")}")
        }
        val contentType = json.optString("contentType", "")
        val status = json.optInt("status", 0)
        val finalURL = json.optString("url", urlString)

        // `base64` is optional — only present when the JS captured bytes.
        // If missing, fall back to `text` (JSON / plain text) so callers
        // can fetch human-readable payloads without forcing a byte roundtrip.
        val data: ByteArray? = json.optString("base64").takeIf { it.isNotEmpty() }?.let {
            try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
        } ?: json.optString("text").takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)

        if (data == null) {
            return BrowserActionResult.error("Fetch returned no body (status=$status)")
        }
        val size = json.optInt("size", data.size)
        val filename = "fetch_${System.currentTimeMillis()}.${extensionForMimeType(contentType)}"

        val text = buildString {
            appendLine("Fetched $finalURL")
            appendLine("  Status: $status")
            appendLine("  Content-Type: $contentType")
            appendLine("  Size: ${formatBytes(size)}")
            append("  Filename: $filename")
        }

        BrowserActionResult(
            text = text,
            fetchedFileData = data,
            fetchedFileName = filename,
        )
    } catch (e: Exception) {
        BrowserActionResult.error("Fetch parse error: ${e.message}")
    }
}

/**
 * Evaluate an `(async function(){...})()` expression and wait for the
 * returned Promise to resolve via the `__minis__` bridge. Returns the
 * resolved string (JSON or plain) or null on timeout. Mirrors the same
 * pattern used by [executeJS].
 */
internal suspend fun BrowserUseManager.awaitPromiseJs(js: String): String? {
    val deferred = CompletableDeferred<String>()
    asyncJsDeferred = deferred
    val wrapped = """
        (async function(){
            try {
                var __v__ = await ($js);
                if (__v__ === undefined || __v__ === null) {
                    __minis__.resolve('null');
                } else if (typeof __v__ === 'object') {
                    __minis__.resolve(JSON.stringify(__v__));
                } else {
                    __minis__.resolve(String(__v__));
                }
            } catch(e) {
                __minis__.reject(e && e.message ? e.message : String(e));
            }
        })();
    """.trimIndent()
    withContext(Dispatchers.Main) {
        webView.evaluateJavascript(wrapped, null)
    }
    val raw = withTimeoutOrNull(60_000L) { deferred.await() }
    asyncJsDeferred = null
    return raw
}

// -- Set User Agent --

/** Set user agent from UI settings (public, non-result). */
fun BrowserUseManager.setUserAgent(profile: UserAgentProfile, customUA: String? = null) {
    currentProfile = profile
    val ua = if (profile == UserAgentProfile.CUSTOM && !customUA.isNullOrEmpty()) customUA
        else profile.userAgentString
    if (ua != null) {
        webView.settings.userAgentString = ua
    }
    applyViewport()
    if (_currentURL.value.isNotEmpty()) {
        webView.reload()
    }
}

/**
 * Force the detached pool WebView to lay out at the agent viewport size so
 * page scripts see `window.innerWidth` matching the selected profile (Mobile
 * 412×915 / Desktop 1280×800). Without this, a detached WebView has
 * width/height = 0 and pages render using WebView defaults.
 *
 * The profile dimensions are CSS pixels (iOS "points"). Android WebView
 * uses physical pixels for measure/layout and derives CSS px via
 * window.devicePixelRatio = system density. Passing 412 px directly on a
 * 2.75-density device makes the CSS viewport ~150px wide, causing pages
 * to render at a tiny logical width then upscale, making elements look
 * oversized and clipping on the right. Scale by density so the CSS
 * viewport actually matches the profile.
 */
fun BrowserUseManager.applyViewport() {
    val vp = currentProfile.viewportSize
    applyViewport(vp.first, vp.second)
}

/**
 * Lay out the detached WebView at the given CSS-pixel viewport. Used by
 * [BrowserTabPool] to apply a session or global custom viewport override
 * — mirrors iOS `BrowserUseManager.setViewport(width:height:...)`.
 */
fun BrowserUseManager.applyViewport(cssWidth: Int, cssHeight: Int) {
    val density = webView.resources.displayMetrics.density
    val w = ((cssWidth * density).toInt()).coerceAtLeast(1)
    val h = ((cssHeight * density).toInt()).coerceAtLeast(1)
    webView.measure(
        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
    )
    webView.layout(0, 0, w, h)
    lastAppliedViewport = cssWidth to cssHeight
    // Pages authored at this CSS width need to fit inside the visible
    // container. Compute and stash a setInitialScale so the next reload
    // (the tab pool always reloads after a viewport change) renders at
    // the shrink-to-fit ratio. pinch-zoom remains enabled because we
    // never touch builtInZoomControls or the page's user-scalable hint.
    applyShrinkToFit(cssWidth)
}

internal suspend fun BrowserUseManager.setUserAgent(profile: UserAgentProfile?): BrowserActionResult {
    val newProfile = profile ?: UserAgentProfile.MOBILE_CHROME
    currentProfile = newProfile
    val ua = newProfile.userAgentString
    // Every WebView method must be called on the main thread, but the
    // offload handler's `runBlocking { ... execute(...) }` dispatches on
    // a worker. `applyViewport(...)` measures/layouts the detached
    // WebView; settings / reload likewise. Hop to main so we don't
    // crash with "A WebView method was called on thread 'worker-N'".
    withContext(Dispatchers.Main) {
        if (ua != null) {
            webView.settings.userAgentString = ua
        }
        applyViewport()
        val oldUrl = _currentURL.value
        if (oldUrl.isNotEmpty()) {
            webView.reload()
        }
    }
    val vp = newProfile.viewportSize
    return BrowserActionResult(text = "Switched to ${newProfile.value} (${vp.first}x${vp.second})")
}

// -- User Navigation --

fun BrowserUseManager.goBack() { if (webView.canGoBack()) webView.goBack() }

fun BrowserUseManager.goForward() { if (webView.canGoForward()) webView.goForward() }

fun BrowserUseManager.reload() { webView.reload() }

fun BrowserUseManager.stopLoading() { webView.stopLoading(); _isLoading.value = false }

/**
 * Reload the current page and suspend until `onPageFinished` fires (or
 * the navigation timeout expires). Used by [BrowserTabPool] after a
 * viewport change so a follow-up `get_page_info` reads the new CSS
 * viewport instead of a stale snapshot. Must be called on the main
 * thread.
 *
 * When the tab has no loaded URL (fresh WebView) or is sitting on
 * `about:blank`, a bare `webView.reload()` is a no-op and
 * `onPageFinished` never fires — we'd time out for no reason. Explicit
 * `loadUrl("about:blank")` always triggers the lifecycle, so the
 * viewport-change callers still get a deterministic page refresh.
 */
suspend fun BrowserUseManager.reloadAndWait() {
    val url = _currentURL.value
    awaitNavigation(label = "reload") {
        if (url.isEmpty() || url == "about:blank") {
            // Android WebView's `about:blank` reports `window.innerWidth=980`
            // regardless of container size (the no-meta-viewport fallback),
            // so a plain blank reload wouldn't reflect the new viewport. Load
            // an empty page that declares `width=device-width` instead —
            // `window.innerWidth` then tracks the container we just laid out.
            // `loadDataWithBaseURL(null, html, ...)` lands on `about:blank`
            // as the reported URL but with our meta-viewport in effect.
            webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
        } else {
            webView.reload()
        }
    }
}

/**
 * [T-browser-history-actions-android] Run [trigger] on the main thread and wait
 * for the page to report itself finished, bounded by [NAVIGATION_TIMEOUT_MS].
 * This is the one copy of a wait that navigate, reload, the blank load and the
 * history moves used to repeat — five near-identical deferred/timeout blocks,
 * each of which could drift from the others.
 */
internal suspend fun BrowserUseManager.awaitNavigation(label: String, trigger: () -> Unit) {
    val deferred = CompletableDeferred<Unit>()
    navigationDeferred = deferred
    _isLoading.value = true
    try {
        withContext(Dispatchers.Main) { trigger() }
    } catch (t: Throwable) {
        if (navigationDeferred === deferred) navigationDeferred = null
        _isLoading.value = false
        throw t
    }
    val handler = Handler(Looper.getMainLooper())
    val timeoutRunnable = Runnable {
        if (navigationDeferred === deferred) {
            Log.w(TAG, "Timed out waiting for $label")
            _isLoading.value = false
            deferred.complete(Unit)
            navigationDeferred = null
        }
    }
    handler.postDelayed(timeoutRunnable, NAVIGATION_TIMEOUT_MS)
    try {
        deferred.await()
    } finally {
        handler.removeCallbacks(timeoutRunnable)
    }
    _isLoading.value = false
}

/**
 * Load a minimal HTML page with `<meta viewport content="width=device-width">`
 * so `window.innerWidth` tracks the just-laid-out container size. Used by
 * [BrowserTabPool.createTab] when no initial URL is supplied, so a follow-up
 * `get_page_info` on a fresh tab reports the session viewport instead of
 * WebView's hardcoded `about:blank` 980px fallback.
 *
 * Suspends until `onPageFinished` fires so a follow-up JS evaluation sees
 * `document.body` populated. The load runs on the main thread internally, so
 * any caller may suspend on it.
 */
suspend fun BrowserUseManager.loadBlankPage() {
    awaitNavigation(label = "blank page load") {
        webView.loadDataWithBaseURL(null, BLANK_PAGE_HTML, "text/html", "utf-8", null)
    }
}

/**
 * [T-browser-history-actions-android] Upstream's `go_back` / `go_forward`: move
 * through the tab's own history and wait for the page it lands on. Nothing to
 * move to is a refusal with a reason, not the silent no-op our UI helper is —
 * read back, a no-op looks exactly like "the page changed".
 */
internal suspend fun BrowserUseManager.historyNavigation(backwards: Boolean): BrowserActionResult {
    val possible = withContext(Dispatchers.Main) {
        if (backwards) webView.canGoBack() else webView.canGoForward()
    }
    if (!possible) {
        return BrowserActionResult(
            text = BrowserHistoryPolicy.unavailable(backwards),
            success = false,
            pageURL = _currentURL.value,
        )
    }
    awaitNavigation(label = if (backwards) "go back" else "go forward") {
        if (backwards) webView.goBack() else webView.goForward()
    }
    return BrowserActionResult(
        text = BrowserHistoryPolicy.moved(backwards) + "\n" + navigationMetadata(),
        pageURL = _currentURL.value,
    )
}
