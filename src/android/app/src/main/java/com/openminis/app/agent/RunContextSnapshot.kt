package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.ToolSensitivePolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-run-checkpoint] The model-context snapshot of an in-flight run.
 *
 * Ported from Eta `agent/runtime/AgentRunCheckpointStore.kt`'s context
 * snapshot field (Mangi-11/Eta @ c15de97); attribution is centralised in
 * PROVENANCE.md.
 *
 * Eta stores the provider-shaped context separately from the transcript,
 * because a resumed run needs the messages that were sent, not a rendering of
 * the UI. The port keeps the newest messages, bounds the result, marks the
 * truncation instead of hiding it, and - like the transcript - never carries a
 * sensitive tool's raw payload.
 */
object RunContextSnapshot {

    const val VERSION = 1
    const val MAX_MESSAGES = 64
    const val MAX_MESSAGE_CHARS = 2_000

    fun encode(
        messages: List<LLMMessage>,
        maxChars: Int = RunCheckpointStore.MAX_SNAPSHOT_CHARS,
    ): JSONObject {
        val entries = mutableListOf<JSONObject>()
        messages.takeLast(MAX_MESSAGES).forEach { message -> entries += encodeMessage(message) }

        var truncated = false
        var total = entries.sumOf { it.toString().length }
        while (total > maxChars && entries.isNotEmpty()) {
            total -= entries.first().toString().length
            entries.removeAt(0)
            truncated = true
        }
        return JSONObject()
            .put("version", VERSION)
            .put("truncated", truncated)
            .put("messages", JSONArray(entries))
    }

    private fun encodeMessage(message: LLMMessage): JSONObject {
        val parts = JSONArray()
        message.contentParts.forEach { part ->
            when (part) {
                is AgentContentPart.Text -> parts.put(
                    JSONObject().put("type", "text").put("text", part.text.take(MAX_MESSAGE_CHARS)),
                )
                is AgentContentPart.ToolUse -> {
                    val sensitive = ToolSensitivePolicy.isSensitive(part.name)
                    parts.put(
                        JSONObject()
                            .put("type", "toolUse")
                            .put("name", part.name)
                            .put(
                                "input",
                                if (sensitive) {
                                    ToolSensitivePolicy.ARGUMENTS_PLACEHOLDER
                                } else {
                                    part.input.toString().take(MAX_MESSAGE_CHARS)
                                },
                            ),
                    )
                }
                is AgentContentPart.ToolResult -> {
                    val redacted = ToolSensitivePolicy.redactResultPart(part)
                    parts.put(
                        JSONObject()
                            .put("type", "toolResult")
                            .put("name", part.name)
                            .put("isError", part.isError)
                            .put("content", redacted.content.take(MAX_MESSAGE_CHARS)),
                    )
                }
                is AgentContentPart.ImageData -> parts.put(
                    JSONObject()
                        .put("type", "image")
                        .put("mimeType", part.mimeType)
                        .put("bytes", part.data.size),
                )
            }
        }
        val images = JSONArray()
        message.imageParts.forEach { image ->
            images.put(JSONObject().put("mimeType", image.mimeType).put("bytes", image.data.size))
        }
        return JSONObject()
            .put("role", message.role.value)
            .put("id", message.dbMessageId ?: JSONObject.NULL)
            .put("text", message.content.take(MAX_MESSAGE_CHARS))
            .put("parts", parts)
            .put("images", images)
    }
}
