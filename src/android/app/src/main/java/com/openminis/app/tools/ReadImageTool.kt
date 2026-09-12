package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.files.WorkspaceFileClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object ReadImageTool {
    const val NAME = "read_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read an image file from the Linux filesystem and return it for visual analysis. Supports PNG, JPEG, GIF, WEBP, and other common image formats. Use this to inspect generated charts, downloaded images, screenshots, or any visual output. If you natively support vision the image is returned directly for your analysis; if you do not, it is routed through a configured Vision Group that returns a text description — in that case pass a `prompt` to focus the description on what you actually need (you cannot see the pixels yourself, so this is how you 'ask' about the image). Metadata (dimensions, file size) is always included.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'View generated bar chart', 'Inspect downloaded screenshot'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Linux path (e.g. /var/minis/attachments/chart.png) or minis:// URL (e.g. minis://attachments/chart.png)"),
            "prompt" to AgentToolParam("string", "Optional. A custom instruction describing what you want to understand from the image (e.g. 'transcribe the table text', 'describe the people and their expressions', 'what error message is in this screenshot'). Most useful when you lack native vision and the image is described by a Vision Group — it steers that description toward your question. If omitted, a generic 'describe this image in detail' instruction is used."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "prompt"),
    )

    suspend fun execute(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val accessSession = sessionId.orEmpty()
            val info = try {
                WorkspaceFileClient.info(accessSession, path)
            } catch (error: WorkspaceFileClient.Failure) {
                return ToolExecutionResult("Error: ${error.message}", false, toolTitle = toolTitle)
            }
            if (!info.optBoolean("exists", false)) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }
            if (info.optString("type") != "file") {
                return ToolExecutionResult("Error: Path is not a regular file: $path", false, toolTitle = toolTitle)
            }

            // Keep the guest path behind WorkspaceFileClient's directory-handle
            // resolver. A cache copy is used when a local path is required by
            // BitmapFactory or by the UI; it is never a path into rootfs/SAF.
            val imageFile = context?.let { ctx ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("$accessSession\u0000$path".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                File(File(ctx.applicationContext.cacheDir, "minis-image-cache"), "read-$digest.img")
            }
            if (imageFile != null) {
                WorkspaceFileClient.readToFile(
                    accessSession,
                    path,
                    imageFile,
                    WorkspaceFileClient.MAX_FILE_BYTES,
                )
            }
            val imageBytes = if (imageFile == null) {
                WorkspaceFileClient.readAll(accessSession, path, WorkspaceFileClient.MAX_FILE_BYTES)
            } else {
                null
            }
            val localImageBytes = imageBytes ?: ByteArray(0)

            // Read dimensions without allocating the full pixel buffer. Large
            // screenshots/camera images can otherwise exhaust the Android heap
            // before the final 2000 px scale has a chance to run.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (imageFile != null) {
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            } else {
                BitmapFactory.decodeByteArray(localImageBytes, 0, localImageBytes.size, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)
            }
            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight

            val decodeMaxEdge = 4000
            var inSampleSize = 1
            while (originalWidth / inSampleSize > decodeMaxEdge ||
                originalHeight / inSampleSize > decodeMaxEdge
            ) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            val original = if (imageFile != null) {
                BitmapFactory.decodeFile(imageFile.absolutePath, decodeOptions)
            } else {
                BitmapFactory.decodeByteArray(localImageBytes, 0, localImageBytes.size, decodeOptions)
            }
                ?: return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)

            var scaled: Bitmap? = null
            try {
                val maxEdge = 2000
                val image = if (original.width > maxEdge || original.height > maxEdge) {
                    val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
                    val width = (original.width * scale).toInt().coerceAtLeast(1)
                    val height = (original.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(original, width, height, true)
                } else {
                    original
                }
                scaled = image

                val out = ByteArrayOutputStream()
                image.compress(Bitmap.CompressFormat.JPEG, 85, out)
                val imageBytes = out.toByteArray()

                val metadata = "[$path | ${originalWidth}x${originalHeight} | ${info.optLong("size", imageBytes.size.toLong())} bytes]"
                ToolExecutionResult(
                    output = metadata,
                    success = true,
                    imageData = imageBytes,
                    imageMimeType = "image/jpeg",
                    toolTitle = toolTitle,
                    imageFilePath = imageFile?.absolutePath,
                )
            } finally {
                val image = scaled
                if (image != null && image !== original) image.recycle()
                original.recycle()
            }
        } catch (e: Exception) {
            ToolExecutionResult("Error reading image: ${e.message}", false)
        }
    }
}
