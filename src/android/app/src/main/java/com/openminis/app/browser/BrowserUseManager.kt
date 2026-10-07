package com.openminis.app.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Manages a single Android WebView for browser automation.
 * Mirrors iOS BrowserUseManager.
 */
class BrowserUseManager(
    val webView: WebView,
    profile: UserAgentProfile = UserAgentProfile.MOBILE_CHROME,
    internal val sessionIdProvider: () -> String? = { null },
) {
    companion object {
        internal const val TAG = "BrowserUseManager"
        internal const val NAVIGATION_TIMEOUT_MS = 30_000L
        internal const val SCREENSHOT_QUALITY = 80        // Explicit screenshot action (iOS: 0.8)
        internal const val SNAPSHOT_QUALITY = 70          // Auto-snapshot after visual-change actions (iOS: 0.7)
        internal const val DEFAULT_DOM_STABLE_TIMEOUT_MS = 5_000

        /**
         * Cap full_page screenshot stretched viewport at 32768 px. Above this,
         * Bitmap.createBitmap risks OOM (e.g. 32768 × ~1130 px × 4 B/ARGB ≈ 144 MB
         * at desktop 1280 CSS × 2.75 density). Pages taller than the cap are
         * truncated and the metadata exposes `truncated:true` + the original
         * scrollHeight so the agent can scroll-then-stitch if it needs more.
         */
        internal const val MAX_FULL_PAGE_HEIGHT_PX = 32768

        /**
         * Minimal HTML used in place of `about:blank` when a fresh tab
         * needs a page refresh (e.g. `set_viewport` before any navigation).
         * `<meta name="viewport" content="width=device-width">` makes
         * `window.innerWidth` track the container we just laid out, instead
         * of Android WebView's hardcoded 980px fallback for blank pages.
         * Loaded via `loadDataWithBaseURL` so WebView accepts raw HTML
         * without URL encoding.
         */
        internal const val BLANK_PAGE_HTML =
            "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        // [T-android-domstable-min-budget] C5: with the old 200ms floor and a
        // 200ms poll interval the loop could only ever take ONE sample
        // (first sample never matches lastSize=-1, then the delay exhausts
        // the budget) — wait_for_dom_stable failed on every page at the
        // minimum. 1000ms fits >=4 samples so the smallest budget can
        // actually observe two equal readings.
        internal const val MIN_DOM_STABLE_TIMEOUT_MS = 1_000
        internal const val MAX_DOM_STABLE_TIMEOUT_MS = 60_000

        @SuppressLint("SetJavaScriptEnabled")
        fun configureWebView(webView: WebView, profile: UserAgentProfile, customUA: String? = null) {
            // [T-android-browser-blank] The browser lives inside a Material3
            // ModalBottomSheet, which hosts content in its own secondary
            // window. On some OEM GPUs the WebView's hardware draw functor
            // fails to composite in that window — the page loads (title/URL
            // update normally) but the content area stays black or white.
            // Rendering the WebView through its own hardware layer texture is
            // the standard workaround for WebView-in-dialog blank rendering.
            webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                val ua = customUA ?: profile.userAgentString
                if (ua != null) userAgentString = ua
            }
            // T-android-webview-v3-port: enable first- + third-party cookies.
            // WebView ships with third-party cookies disabled by default; that
            // breaks hCaptcha / Turnstile / reCAPTCHA flows where the
            // verification widget lives in a cross-origin iframe and posts its
            // token back to the parent through a Set-Cookie round-trip. The
            // agent-driven browser is the user's surrogate; matching Chrome's
            // default unblocks the same captcha flows the user would clear in
            // a real browser tab. First-party setAcceptCookie defaults to
            // true on every Android version we support — calling it
            // explicitly anyway so the intent is grep-able.
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    internal val _currentURL = MutableStateFlow("")
    val currentURL: StateFlow<String> = _currentURL.asStateFlow()

    /** JS dialogs answered by the headless agent WebView, reported on the next tool result. */
    internal val dialogQueue = InterceptedDialogQueue()

    internal val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    internal val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    internal val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    internal val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    internal var currentProfile: UserAgentProfile = profile

    /** Callback for window.open / target="_blank" — TabPool hooks this. */
    var onNewWindow: ((Message) -> Unit)? = null

    /** Callback for window.close — TabPool hooks this. */
    var onCloseWindow: (() -> Unit)? = null

    /**
     * Callback when the page triggers a file download over http/https
     * (Content-Disposition attachment, <a download>, unrenderable MIME type).
     * TabPool hooks this and streams the URL into the session workspace.
     */
    var onDownloadStart: ((url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit)? = null

    /**
     * Callback delivering the bytes of a blob: download. blob: URLs only exist
     * inside the page's JS context, so [fetchBlobDownload] reads them via an
     * injected FileReader and hands the decoded bytes back through the bridge.
     */
    var onBlobDownloadData: ((data: ByteArray, filename: String, mimeType: String?) -> Unit)? = null

    /** Deferred for awaiting navigation completion. */
    internal var navigationDeferred: CompletableDeferred<Unit>? = null

    /** Screenshots directory. */
    internal val screenshotsDir: File by lazy {
        File(webView.context.cacheDir, "browser_screenshots").also { it.mkdirs() }
    }

    /** Deferred used by executeJS to receive results from async scripts via JS bridge. */
    internal var asyncJsDeferred: CompletableDeferred<String>? = null

    /** JavaScript interface for async script result callbacks. */
    private val jsBridge = object {
        @JavascriptInterface
        fun resolve(result: String) {
            asyncJsDeferred?.complete(result)
        }

        @JavascriptInterface
        fun reject(error: String) {
            asyncJsDeferred?.complete("{\"error\":${JSONObject.quote(error)}}")
        }

        /**
         * Receives a blob: download read as a data URL by [fetchBlobDownload]'s
         * injected FileReader. Runs on the WebView's JavaBridge thread — file
         * I/O downstream is fine, but don't touch the WebView from here.
         */
        @JavascriptInterface
        fun saveBlobDownload(dataUrl: String, filename: String) {
            val comma = dataUrl.indexOf(',')
            if (comma < 0 || !dataUrl.startsWith("data:")) {
                Log.w(TAG, "blob download: malformed data URL (len=${dataUrl.length})")
                return
            }
            val header = dataUrl.substring(5, comma)
            val mime = header.substringBefore(';').ifEmpty { null }
            val bytes = try {
                if (header.endsWith(";base64")) {
                    android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT)
                } else {
                    // Non-base64 data: URL — payload is percent-encoded text.
                    java.net.URLDecoder.decode(dataUrl.substring(comma + 1), "UTF-8")
                        .toByteArray(Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "blob download: payload decode failed: ${t.message}")
                return
            }
            Log.i(TAG, "blob download decoded: $filename (${bytes.size} bytes, mime=$mime)")
            onBlobDownloadData?.invoke(bytes, filename, mime)
        }

        @JavascriptInterface
        fun blobDownloadError(error: String) {
            Log.w(TAG, "blob download failed in page JS: $error")
        }
    }

    init {
        configureWebView(webView, profile)
        webView.addJavascriptInterface(jsBridge, "__minis__")
        setupWebViewClient()
        setupWebChromeClient()
        // Intercept page-triggered downloads (Content-Disposition attachment,
        // <a download>, unrenderable MIME types). Without a listener, WebView
        // silently drops these — the user taps "download" and nothing happens.
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.i(TAG, "onDownloadStart: ${url.take(120)} mime=$mimetype len=$contentLength")
            when {
                // blob: object URLs only exist inside the page — read via JS.
                url.startsWith("blob:") -> fetchBlobDownload(url, contentDisposition, mimetype)
                // data: URLs carry the payload inline — decode directly
                // (java.net.URL can't fetch them in the pool's downloader).
                url.startsWith("data:") -> {
                    val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                    jsBridge.saveBlobDownload(url, name)
                }
                else -> onDownloadStart?.invoke(url, userAgent, contentDisposition, mimetype, contentLength)
            }
        }
        // Track the on-screen WebView width so applyViewport() can compute a
        // shrink-to-fit initial scale. The synthetic measure/layout pass that
        // applyViewport performs to make `window.innerWidth` match the agent
        // viewport is independent of the container the AndroidView is hosted
        // in — without this listener we have no way to learn the container's
        // visible width, and oversize CSS viewports (e.g. 1280×800 on a
        // ~1080px-wide phone) would render off-screen to the right.
        webView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != lastKnownContainerWidthPx) {
                lastKnownContainerWidthPx = w
                // Re-apply the initial scale if we already have an active
                // viewport — covers rotation, sheet resize, container layout
                // settling after the WebView is first parented.
                lastAppliedViewport?.let { (vw, _) -> applyShrinkToFit(vw) }
            }
        }
    }


    /**
     * Latest on-screen width (in physical pixels) of the AndroidView hosting
     * this WebView. Updated by the layout listener installed in [init];
     * 0 until the WebView is parented and laid out for the first time.
     */
    internal var lastKnownContainerWidthPx: Int = 0


























    /** What one bridged evaluation did. */
    internal sealed interface BridgedRun {
        /** The WebView refused the source: nothing ran. */
        object NotCompiled : BridgedRun

        /** The bridge never answered inside the budget. */
        object TimedOut : BridgedRun

        /** The bridge answered: a value, or the {"error": …} envelope. */
        data class Answer(val raw: String) : BridgedRun
    }












    /**
     * Last CSS-pixel viewport applied via [applyViewport]. Used so [navigate]
     * can re-assert the same size before `loadUrl()` — intercepted
     * (`minis://`) loads skip WebView's measure pass, otherwise stranding the
     * page at the 980px no-meta fallback.
     */
    internal var lastAppliedViewport: Pair<Int, Int>? = null



























}
