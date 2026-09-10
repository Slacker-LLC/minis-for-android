package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.ui.sandbox.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decides what should happen when a link inside chat markdown is tapped.
 *
 * Routing order:
 *  1. Recognized minis:// deep-link action  → DeepLink (delegated to MainActivity via Intent.ACTION_VIEW)
 *  2. minis://<sandbox path>, file://, or absolute /var/minis|/root path → SandboxFile
 *  3. Non-http(s) external schemes (intent://, mailto:, tel:, geo:, …)   → ExternalApp
 *  4. Anything else (http(s), about, file)                                → Web
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    private suspend fun resolve(rawUrl: String, sessionId: String?, context: Context?): ChatLinkAction {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ChatLinkAction.Web(rawUrl)

        val uri = runCatching { trimmed.toUri() }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        // Strip the URL query before decoding, so an encoded '?' in a filename
        // cannot become a query delimiter during path resolution.
        val fileInput = trimmed.substringBefore('?')

        // 1. minis:// deep links — only branch out when the URL maps to a known action,
        //    otherwise fall through to sandbox-path handling.
        if (scheme == "minis") {
            val action = DeepLinkHandler.parse(uri)
            if (action !is DeepLinkAction.Unknown) {
                return ChatLinkAction.DeepLink(action)
            }
        }

        // 2. Sandbox file resolution — canonical guest files are staged through
        // minisd; only SAF mounts and app-local file:// paths use host files.
        if (context != null) {
            val staged = resolveDecodedPath(fileInput) { candidate ->
                val guestPath = resolveGuestPath(candidate, scheme) ?: return@resolveDecodedPath null
                stageGuestFile(context, guestPath, sessionId)?.let { FileItem.from(it) }
            }
            if (staged != null) return ChatLinkAction.SandboxFile(staged)
        }

        // Prefer a session-scoped resolver when we know which chat this link
        //    the caller knows which chat this link belongs to. The global
        //    `RuntimePathRegistry.bindMounts` is last-writer-wins, so on a device
        //    with multiple sessions the resolver otherwise points at
        //    whichever session booted its shell most recently.
        val hostFile = resolveDecodedPath(fileInput) { candidate ->
            resolveSandboxFile(candidate, scheme, sessionId, context)?.takeIf { it.isFile }
        }
        android.util.Log.w("ChatLinkDiag",
            "resolve url=${trimmed.take(200)} sid=$sessionId hostFile=${hostFile?.absolutePath} exists=${hostFile?.exists()}")
        if (hostFile != null && hostFile.exists() && !hostFile.isDirectory) {
            FileItem.from(hostFile)?.let { return ChatLinkAction.SandboxFile(it) }
        }

        // T136: intent://, mailto:, tel:, geo:, market: etc. need a system
        // dispatch — the in-app preview WebView's `loadUrl(...)` doesn't
        // trip `shouldOverrideUrlLoading` for the initial URL, so without
        // this hop those schemes hit the WebView and surface as
        // ERR_UNKNOWN_URL_SCHEME.
        if (com.openminis.app.ui.browser.BrowserExternalSchemeHandler.shouldHandleExternally(trimmed)) {
            return ChatLinkAction.ExternalApp(trimmed)
        }

        return ChatLinkAction.Web(trimmed)
    }

    suspend fun resolveAsync(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction =
        withContext(Dispatchers.IO) {
            resolve(rawUrl, sessionId, context)
        }

    /**
     * Decode URL-encoded paths safely. Protects '+' from being decoded to spaces
     * (since '+' is valid in file names and only represents space in form queries),
     * One pass preserves literal percent sequences; a second decode is tried
     * only if the first path cannot be resolved.
     */
    internal fun decodePath(rawPath: String): String {
        // Protect '+' so URLDecoder doesn't convert it into a space (Issue #183)
        val protected = rawPath.replace("+", "%2B")
        return runCatching { java.net.URLDecoder.decode(protected, "UTF-8") }.getOrDefault(rawPath)
    }

    internal suspend fun <T> resolveDecodedPath(rawPath: String, lookup: suspend (String) -> T?): T? {
        val decoded = decodePath(rawPath)
        lookup(decoded)?.let { return it }
        val second = decodePath(decoded)
        return if (second != decoded) lookup(second) else null
    }

    /**
     * Map a chat link to a host File when it points into the sandbox, else null.
     * Accepts:
     *   minis://attachments/foo.png        → /var/minis/attachments/foo.png
     *   minis:///var/minis/workspace/x.csv → /var/minis/workspace/x.csv (absolute)
     *   file:///path/to/file               → /path/to/file
     *   /var/minis/workspace/x.csv         → resolved via bind mount
     *   /root/whatever                     → resolved relative to rootfs
     */
    private fun resolveSandboxFile(
        raw: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): File? {
        fun lookup(linuxPath: String): File? =
            if (isCanonicalGuestPath(linuxPath)) {
                null
            } else if (sessionId != null && context != null) {
                RuntimePathRegistry.resolveSessionHostPath(sessionId, linuxPath, context)
            } else {
                RuntimePathRegistry.resolveHostPath(linuxPath)
            }
        return when (scheme) {
            "minis" -> {
                // Keep '#' — attachment filenames legitimately contain it.
                // `minis://` URLs don't use fragments, so stripping at '#'
                // would truncate filenames like `foo #China.mp4`.
                val stripped = raw.removePrefix("minis://")
                val linuxPath = if (stripped.startsWith("/")) stripped else "/var/minis/$stripped"
                lookup(linuxPath)
            }
            "file" -> {
                val path = raw.removePrefix("file://")
                if (path.isEmpty()) null else File(path)
            }
            null -> {
                if (raw.startsWith("/")) lookup(raw) else null
            }
            else -> null
        }
    }

    internal fun resolveGuestPath(raw: String, scheme: String?): String? {
        val path = when (scheme) {
            "minis" -> {
                val stripped = raw.removePrefix("minis://")
                if (stripped.startsWith('/')) stripped else "/var/minis/$stripped"
            }
            null -> {
                if (raw.startsWith('/')) raw else null
            }
            else -> null
        } ?: return null
        return path.takeIf(::isCanonicalGuestPath)
    }

    private suspend fun stageGuestFile(context: Context, path: String, sessionId: String?): File? {
        if (sessionId == null && isSessionScopedGuestPath(path)) return null
        val fileName = path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${sessionId.orEmpty()}:$path".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.US, it) }
        val cacheFile = File(File(context.cacheDir, "chat-link-media"), "$digest-$fileName")
        return try {
            WorkspaceFileClient.readToFile(sessionId.orEmpty(), path, cacheFile)
            cacheFile.takeIf { it.isFile }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private fun isCanonicalGuestPath(path: String): Boolean {
        val roots = listOf(
            "/var/minis",
            "/workspace",
            "/memory",
            "/skills",
            "/shared",
            "/home/minis",
        )
        return roots.any { path == it || path.startsWith("$it/") } &&
            path != "/var/minis/mounts" && !path.startsWith("/var/minis/mounts/")
    }

    private fun isSessionScopedGuestPath(path: String): Boolean =
        path == "/var/minis" || path.startsWith("/var/minis/workspace/") ||
            path == "/var/minis/workspace" || path.startsWith("/var/minis/attachments/") ||
            path == "/var/minis/attachments" || path.startsWith("/var/minis/offloads/") ||
            path == "/var/minis/offloads" || path.startsWith("/var/minis/browser/") ||
            path == "/var/minis/browser" || path == "/workspace" ||
            path.startsWith("/workspace/")

    /** Fire a system intent so MainActivity's BROWSABLE filter picks the deep link up. */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
