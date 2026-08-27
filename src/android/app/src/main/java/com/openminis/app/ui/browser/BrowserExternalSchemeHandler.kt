package com.openminis.app.ui.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.openminis.app.logging.AppLogger

/**
 * Centralized router for URLs that must not be handed to an in-app WebView.
 * This includes supported external-app schemes, unknown custom schemes, and
 * malformed links. Intercepting all three prevents Chromium error pages such
 * as ERR_UNKNOWN_URL_SCHEME and keeps failures user-facing and controlled.
 */
object BrowserExternalSchemeHandler {
    private const val TAG = "BrowserExternalScheme"

    private const val INTENT_SCHEME = "intent"
    private const val ANDROID_APP_SCHEME = "android-app"

    private val EXTERNAL_VIEW_SCHEMES = setOf(
        "tel", "mailto", "sms", "smsto", "mms", "mmsto",
        "geo", "market", "whatsapp", "tg", "weixin",
    )

    /**
     * True when [url] must be intercepted before WebView.loadUrl(). Besides
     * valid external schemes this deliberately includes malformed links: an
     * invalid initial URL does not get a shouldOverrideUrlLoading callback.
     */
    fun shouldHandleExternally(url: String?): Boolean =
        when (BrowserUrlPolicy.classify(url)) {
            BrowserUrlPolicy.Kind.INTERNAL -> false
            BrowserUrlPolicy.Kind.EXTERNAL,
            BrowserUrlPolicy.Kind.INVALID,
            -> true
        }

    /**
     * Route an external URL, block an unknown custom scheme, or consume a
     * malformed URL with a concise message. Returns false only for URLs that
     * are safe to continue through the in-app WebView pipeline.
     */
    fun handle(context: Context, url: String?): Boolean {
        return when (BrowserUrlPolicy.classify(url)) {
            BrowserUrlPolicy.Kind.INTERNAL -> false
            BrowserUrlPolicy.Kind.INVALID -> {
                AppLogger.info(TAG, "blocked malformed link")
                toast(context, "Invalid link.")
                true
            }
            BrowserUrlPolicy.Kind.EXTERNAL -> {
                val value = url?.trim().orEmpty()
                val uri = runCatching { value.toUri() }.getOrNull()
                if (uri == null) {
                    AppLogger.info(TAG, "failed to parse external link")
                    toast(context, "Invalid link.")
                    true
                } else {
                    handleExternalUri(context, uri)
                }
            }
        }
    }

    fun handle(context: Context, uri: Uri?): Boolean =
        if (uri == null) false else handle(context, uri.toString())

    private fun handleExternalUri(context: Context, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == null) {
            toast(context, "Invalid link.")
            return true
        }

        return when (scheme) {
            INTENT_SCHEME, ANDROID_APP_SCHEME -> handleIntentScheme(context, uri.toString())
            in EXTERNAL_VIEW_SCHEMES -> handleViewIntent(context, uri)
            else -> {
                // Unknown app schemes are intentionally blocked rather than
                // launched. This preserves the browsing context and avoids
                // surprising cross-app jumps from arbitrary web content.
                AppLogger.info(TAG, "blocked unknown scheme: $scheme (uri=$uri)")
                toast(context, "Blocked link to external app ($scheme)")
                true
            }
        }
    }

    private fun handleIntentScheme(context: Context, url: String): Boolean {
        val intent = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrElse {
            AppLogger.warning(TAG, "parseUri failed for $url: ${it.message}")
            toast(context, "Invalid app link.")
            return true
        }

        intent.selector = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = intent.getStringExtra("browser_fallback_url")

        try {
            context.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            AppLogger.info(TAG, "No app handles $url; fallback=${fallback != null}")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "startActivity failed for $url: ${e.message}")
        }

        if (!fallback.isNullOrBlank() && BrowserUrlPolicy.classify(fallback) == BrowserUrlPolicy.Kind.INTERNAL) {
            try {
                val fb = Intent(Intent.ACTION_VIEW, fallback.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fb)
                return true
            } catch (e: Exception) {
                AppLogger.warning(TAG, "fallback $fallback failed: ${e.message}")
            }
        }

        toast(context, "No app available to open this link.")
        return true
    }

    private fun handleViewIntent(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(context, "No app available to open this link.")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "ACTION_VIEW failed for $uri: ${e.message}")
            toast(context, "Unable to open this link.")
        }
        return true
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
