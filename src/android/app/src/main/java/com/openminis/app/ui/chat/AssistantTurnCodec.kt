package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.MediaRef
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One ordered source for the live Agent turn and its persisted transcript. Call build on IO. */
internal object AssistantTurnCodec {
    data class Turn(val parts: List<AgentContentPart>, val partsJson: String)

    fun build(
        blocks: List<AssistantBlock>,
        startIndex: Int,
        toolInputs: Map<String, String>,
        mediaBaseDir: File,
    ): Turn {
        val parts = mutableListOf<AgentContentPart>()
        val json = JSONArray()
        for (block in blocks.drop(startIndex.coerceAtLeast(0))) {
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    val part = AgentContentPart.Text(block.content)
                    parts.add(part)
                    encodePart(part, emptyMap())?.let(json::put)
                }
                "tool_use" -> if (block.toolName.isNotBlank()) {
                    val input = runCatching { JSONObject(toolInputs[block.id] ?: "{}") }.getOrElse { JSONObject() }
                    val part = AgentContentPart.ToolUse(block.id, block.toolName, input, block.thoughtSignature)
                    parts.add(part)
                    encodePart(part, mapOf(block.id to block))?.let(json::put)
                }
                "media" -> {
                    val ref = requireNotNull(block.mediaRef) { "Generated media has no persistent reference" }
                    val file = mediaFile(ref, mediaBaseDir)
                    if (ref.mimeType.startsWith("image/")) {
                        parts.add(AgentContentPart.ImageData(file.readBytes(), ref.mimeType))
                    } else {
                        parts.add(AgentContentPart.Text("[Generated media: ${ref.originalFileName ?: file.name}]"))
                    }
                    json.put(JSONObject().put("type", "mediaRef").put("value", JSONObject()
                        .put("id", ref.id)
                        .put("relativePath", ref.relativePath)
                        .put("mimeType", ref.mimeType)
                        .put("originalFileName", ref.originalFileName ?: JSONObject.NULL)))
                }
            }
        }
        return Turn(parts, json.toString())
    }

    fun encodeParts(parts: List<AgentContentPart>, metadata: Map<String, AssistantBlock> = emptyMap()): String {
        val json = JSONArray()
        for (part in parts) encodePart(part, metadata)?.let(json::put)
        return json.toString()
    }

    fun interrupted(turn: Turn, userStopped: Boolean = true): Turn {
        val reason = if (userStopped) "The user stopped this response." else "This response was interrupted by an error."
        val marker = AgentContentPart.Text("<system-reminder>$reason Content may be incomplete.</system-reminder>")
        return Turn(turn.parts + marker, JSONArray(turn.partsJson).put(encodePart(marker, emptyMap())).toString())
    }

    fun discardMedia(blocks: List<AssistantBlock>, mediaBaseDir: File) {
        for (ref in blocks.mapNotNull { it.mediaRef }) mediaFile(ref, mediaBaseDir).delete()
    }

    private fun encodePart(part: AgentContentPart, metadata: Map<String, AssistantBlock>): JSONObject? = when (part) {
        is AgentContentPart.Text -> JSONObject().put("type", "text").put("value", part.text)
        is AgentContentPart.ToolUse -> if (part.name.isBlank()) null else {
            val block = metadata[part.id]
            JSONObject().put("type", "toolUse").put("value", JSONObject()
                .put("toolUseId", part.id).put("name", part.name)
                .put("input", part.input.toString()).put("description", block?.toolTitle ?: "")
                .put("pageURL", block?.browserURL ?: "").put("imageFilePath", block?.imageFilePath ?: "")
                .put("thoughtSignature", part.thoughtSignature ?: JSONObject.NULL))
        }
        else -> null
    }

    fun mediaBlock(ref: MediaRef, mediaBaseDir: File): AssistantBlock = AssistantBlock(
        id = "media_${ref.id}", kind = "media", mediaRef = ref,
        toolTitle = ref.originalFileName ?: File(ref.relativePath).name,
        imageFilePath = mediaFile(ref, mediaBaseDir).absolutePath,
    )

    fun restoreMediaBlock(value: JSONObject, mediaBaseDir: File): AssistantBlock = mediaBlock(
        MediaRef(
            id = value.getString("id"), relativePath = value.getString("relativePath"),
            mimeType = value.getString("mimeType"),
            originalFileName = value.optString("originalFileName").takeUnless { it.isBlank() || it == "null" },
        ), mediaBaseDir,
    )

    private fun mediaFile(ref: MediaRef, mediaBaseDir: File): File {
        require(ref.relativePath.isNotBlank() && !File(ref.relativePath).isAbsolute) { "Invalid media path" }
        val base = mediaBaseDir.canonicalFile
        val file = File(base, ref.relativePath).canonicalFile
        require(file.toPath().startsWith(base.toPath()) && file != base) { "Media path escapes storage" }
        return file
    }
}
