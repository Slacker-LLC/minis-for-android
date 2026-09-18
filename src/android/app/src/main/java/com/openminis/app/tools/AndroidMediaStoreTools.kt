package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.guest.PhotosOffloadHandler
import com.openminis.app.runtime.guest.MediaQueryPolicy

/** Thin structured adapter over the existing MediaStore-backed android-photos handler. */
class AndroidMediaImagesHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.media.images",
        description = "List recent device photos or videos from MediaStore with content URIs and metadata. " +
            "Audio has its own tool (android.media.audio).",
        parameters = mapOf(
            "limit" to AgentToolParam("integer", "Max images (default 20, max 100)"),
            "start_date" to AgentToolParam("string", "Optional ISO range start (supply end_date too)"),
            "end_date" to AgentToolParam("string", "Optional ISO range end (supply start_date too)"),
            "media_type" to AgentToolParam("string", "photo (default) or video", listOf("photo", "video")),
            "query" to AgentToolParam("string", "Optional file-name keyword; wildcards are matched literally"),
        ),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = args(argsJson)
        val start = args.optString("start_date").ifBlank { null }
        val end = args.optString("end_date").ifBlank { null }
        if ((start == null) != (end == null)) {
            return ToolExecutionResult("Error: start_date and end_date must be supplied together", false)
        }
        // [T-eta-media-search] media_type + name filter, matching Eta's search_media surface
        // (photos and videos are one capability; audio is a separate tool).
        val mediaType = when (val requested = args.optString("media_type", "photo").trim().lowercase()) {
            "photo", "image", "images" -> "photo"
            "video", "videos" -> "video"
            else -> return ToolExecutionResult("Error: media_type must be photo or video (got '$requested')", false)
        }
        val argv = mutableListOf(
            "android-photos",
            "list",
            "--type",
            mediaType,
            "--limit",
            MediaQueryPolicy.clampLimit(args.optInt("limit", MediaQueryPolicy.DEFAULT_LIMIT)).toString(),
        )
        start?.let { argv += listOf("--start", it) }
        end?.let { argv += listOf("--end", it) }
        args.optString("query").trim().takeIf { it.isNotEmpty() }?.let { argv += listOf("--query", it) }
        return AndroidSystemOps.offload(context, sessionId, PhotosOffloadHandler(context), argv)
    }
}

/**
 * [T-eta-media-search] Audio from the same MediaStore handler Eta splits out as `search_audio`:
 * metadata and a content URI, never the bytes.
 */
class AndroidMediaAudioHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.media.audio",
        description = "List recent device audio (music, podcasts, recordings) from MediaStore with content URIs, " +
            "duration and artist/album metadata. Nothing is played and no audio bytes are read.",
        parameters = mapOf(
            "limit" to AgentToolParam("integer", "Max rows (default 20, max 100)"),
            "start_date" to AgentToolParam("string", "Optional ISO range start (supply end_date too)"),
            "end_date" to AgentToolParam("string", "Optional ISO range end (supply start_date too)"),
            "query" to AgentToolParam("string", "Optional file-name keyword; wildcards are matched literally"),
        ),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = args(argsJson)
        val start = args.optString("start_date").ifBlank { null }
        val end = args.optString("end_date").ifBlank { null }
        if ((start == null) != (end == null)) {
            return ToolExecutionResult("Error: start_date and end_date must be supplied together", false)
        }
        val argv = mutableListOf(
            "android-photos",
            "list",
            "--type",
            "audio",
            "--limit",
            MediaQueryPolicy.clampLimit(args.optInt("limit", MediaQueryPolicy.DEFAULT_LIMIT)).toString(),
        )
        start?.let { argv += listOf("--start", it) }
        end?.let { argv += listOf("--end", it) }
        args.optString("query").trim().takeIf { it.isNotEmpty() }?.let { argv += listOf("--query", it) }
        return AndroidSystemOps.offload(context, sessionId, PhotosOffloadHandler(context), argv)
    }
}
