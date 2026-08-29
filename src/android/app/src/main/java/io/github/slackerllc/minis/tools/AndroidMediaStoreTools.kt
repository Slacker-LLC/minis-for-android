package io.github.slackerllc.minis.tools

import android.content.Context
import io.github.slackerllc.minis.data.model.AgentToolDefinition
import io.github.slackerllc.minis.data.model.AgentToolParam
import io.github.slackerllc.minis.sandbox.offload.PhotosOffloadHandler

/** Thin structured adapter over the existing MediaStore-backed android-photos handler. */
class AndroidMediaImagesHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.media.images",
        description = "List recent device images from MediaStore with content URIs and metadata.",
        parameters = mapOf(
            "limit" to AgentToolParam("integer", "Max images (default 20, max 100)"),
            "start_date" to AgentToolParam("string", "Optional ISO range start (supply end_date too)"),
            "end_date" to AgentToolParam("string", "Optional ISO range end (supply start_date too)"),
        ),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = args(argsJson)
        val start = args.optString("start_date").ifBlank { null }
        val end = args.optString("end_date").ifBlank { null }
        if ((start == null) != (end == null)) {
            return ToolExecutionResult("Error: start_date and end_date must be supplied together", false)
        }
        val argv = mutableListOf("android-photos", "list", "--type", "photo", "--limit", args.optInt("limit", 20).coerceIn(1, 100).toString())
        start?.let { argv += listOf("--start", it) }
        end?.let { argv += listOf("--end", it) }
        return AndroidSystemOps.offload(context, sessionId, PhotosOffloadHandler(context), argv)
    }
}
