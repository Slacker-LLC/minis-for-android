package com.openminis.app.tools

import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.runtime.files.WorkspaceFileClient
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared browser result and artifact handling; callers retain their existing tab pool. */
object BrowserTools {
    private val artifactMutex = Mutex()
    suspend fun execute(argsJson: String, sessionId: String, browserTabPool: BrowserTabPool): ToolExecutionResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)

        return try {
            val result = browserTabPool.execute(input)
            val toolTitle = try {
                JSONObject(argsJson).optString("tool_title", "browser_use")
            } catch (_: Exception) { "browser_use" }

            var output = result.text
            var persistentImagePath: String? = result.imageFilePath
            var inferenceBytes: ByteArray? = null

            // Persist browser screenshots to /var/minis/browser/<session>/ so the
            // agent can reference them via minis:// in subsequent tool calls
            // (mirrors iOS AIAgentSession case "browser_use").
            val base64 = result.base64Image
            var linuxImagePath: String? = null
            if (base64 != null) {
                val raw = try {
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                } catch (_: Exception) { null }
                if (raw != null) {
                    // Anthropic supports up to 8000×8000 / 5MB; we standardize at 2000
                    // long edge across attachments / browser / read_image.
                    inferenceBytes = resizeJpegToMaxEdge(raw, 2000) ?: raw
                    val filename = "screenshot_${System.currentTimeMillis()}.jpg"
                    val persistPath = persistArtifact(sessionId, filename, raw)
                    if (persistPath != null) {
                        persistentImagePath = com.openminis.app.runtime.ubuntu.UbuntuPaths.resolveForFileAccess(sessionId, persistPath)?.absolutePath
                        linuxImagePath = persistPath
                        linuxPathToMinisURL(linuxImagePath)?.let {
                            output = "$output\nminis_url: $it"
                        }
                    }
                }
            }

            // Upstream's read_image=false: the screenshot is still saved for the user
            // (thumbnail, artifact, minis:// path) but is not attached to the model,
            // which only asked for the page's metadata.
            if (!result.attachImage) inferenceBytes = null

            // Persist fetched files (fetch action) and append minis_url
            val fetchData = result.fetchedFileData
            val fetchName = result.fetchedFileName
            if (fetchData != null && fetchName != null) {
                persistArtifact(sessionId, fetchName, fetchData)?.let(::linuxPathToMinisURL)?.let {
                    output = "$output\nminis_url: $it"
                }
            }

            ToolExecutionResult(
                output = output,
                success = result.success,
                imageData = inferenceBytes,
                imageMimeType = if (inferenceBytes != null) "image/jpeg" else null,
                toolTitle = toolTitle,
                pageURL = result.pageURL,
                imageFilePath = persistentImagePath,
                imageLinuxPath = linuxImagePath,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message}", false, failureKind = ToolFailureKind.OUTCOME_UNKNOWN)
        }
    }


    fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it.isISOControl()) '_' else it }.joinToString("")
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "browser-artifact.bin"

    suspend fun persistArtifact(sessionId: String?, filename: String, bytes: ByteArray): String? = try {
        val directory = if (com.openminis.app.offload.OffloadPermissionManager.tierFor(sessionId.orEmpty()) ==
            com.openminis.app.scheduled.ScheduledTaskPermissionTier.READ_ONLY) "/var/minis/offloads" else "/var/minis/browser"
        artifactMutex.withLock {
            val path = WorkspaceFileClient.uniqueChildPath(sessionId, directory, safeName(filename))
            WorkspaceFileClient.writeBytes(sessionId, path, bytes)
            path
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        android.util.Log.w("BrowserTools", "artifact persistence failed: ${error.message}")
        null
    }

    fun linuxPathToMinisURL(path: String): String? {
        val prefix = "/var/minis/"
        if (!path.startsWith(prefix)) return null
        val rest = path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash < 0) return null
        val namespace = rest.substring(0, slash)
        val filename = rest.substring(slash + 1)
        val encoded = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "minis://$namespace/$encoded"
    }

    /**
     * Resize a JPEG so its longest edge is at most `maxEdge` px. Returns null
     * if already within bounds. Mirrors iOS AIAgentSession.resizedImageData.
     */
    private fun resizeJpegToMaxEdge(data: ByteArray, maxEdge: Int): ByteArray? {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) { bmp.recycle(); return null }
        val scale = maxEdge.toFloat() / longest
        val w = (bmp.width * scale).toInt()
        val h = (bmp.height * scale).toInt()
        val resized = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        bmp.recycle()
        val out = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        resized.recycle()
        return out.toByteArray()
    }


}
