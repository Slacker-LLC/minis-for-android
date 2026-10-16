package com.openminis.app.voicecall.realtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * 豆包实时语音模型 3.0（Seeduplex）全双工协议，纯 JSON 文本帧
 * （接入必读 https://www.volcengine.com/docs/6561/2549732，事件参考 https://www.volcengine.com/docs/6561/2549778）。
 *
 * 和 OpenAI Realtime 同名事件但字段不同：会话用 `session.create`，函数结果走 `items[]`（`role=tool`），
 * 输入固定 16 kHz、输出固定 24 kHz，麦克风停送音频时必须发 `input_audio_mute.commit` 保活，
 * 结束时先发 `session.close`。没有「注入一条对话条目让模型念」的事件，改用 `speech_text_buffer.commit`
 * 直接合成指定文本。
 */
class DoubaoDuplexCodec : RealtimeCodec {
    override val inputSampleRate = 16_000
    override val outputSampleRate = 24_000

    private val emittedCalls = HashSet<String>()
    private val assistantText = StringBuilder()

    override fun open(config: RealtimeSessionConfig): List<String> {
        val session = JSONObject()
            .put("model", config.model)
            .put("instructions", config.instructions)
            .put(
                "audio",
                JSONObject()
                    .put("input", JSONObject().put("format", JSONObject().put("type", "pcm").put("rate", inputSampleRate)))
                    .put(
                        "output",
                        JSONObject()
                            .put("format", JSONObject().put("type", "pcm_s16le").put("rate", outputSampleRate))
                            .apply { config.voice?.let { put("voice", it) } },
                    ),
            )
        if (config.delegateByTool) {
            session.put(
                "tools",
                JSONArray().put(
                    JSONObject().put("type", "function").put("name", DELEGATE_TOOL_NAME)
                        .put("description", DELEGATE_TOOL_DESCRIPTION)
                        .put(
                            "parameters",
                            JSONObject().put("type", "object")
                                .put("properties", JSONObject().put("request", JSONObject().put("type", "string").put("description", DELEGATE_TOOL_PARAM_DESCRIPTION)))
                                .put("required", JSONArray().put("request")),
                        ),
                ),
            )
        }
        return listOf(JSONObject().put("type", "session.create").put("session", session).toString())
    }

    override fun audioIn(pcm: ByteArray): String =
        JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(pcm)).toString()

    override fun parse(frame: String): List<RealtimeEvent> {
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return emptyList()
        return when (val type = json.optString("type")) {
            "session.created" -> listOf(RealtimeEvent.Ready)
            "response.output_audio.delta" -> {
                val pcm = runCatching { Base64.getDecoder().decode(json.optString("delta")) }.getOrNull()
                if (pcm == null || pcm.isEmpty()) emptyList() else listOf(RealtimeEvent.AudioOut(pcm))
            }
            "response.output_text.delta" -> {
                assistantText.append(json.optString("delta"))
                emptyList()
            }
            "response.output_text.done" -> {
                val text = json.optString("text").ifBlank { json.optString("transcript") }.ifBlank { assistantText.toString() }
                assistantText.setLength(0)
                if (text.isBlank()) emptyList() else listOf(RealtimeEvent.AssistantText(text, final = true))
            }
            "conversation.item.input_audio_transcription.started" -> listOf(RealtimeEvent.UserSpeechStarted)
            "conversation.item.input_audio_transcription.completed" ->
                json.optString("transcript").ifBlank { json.optString("text") }.trim().takeIf { it.isNotEmpty() }
                    ?.let { listOf(RealtimeEvent.UserTranscript(it)) } ?: emptyList()
            "response.function_call_arguments.done" -> {
                val out = ArrayList<RealtimeEvent>()
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        out += toolCall(item.optString("call_id"), item.optString("name"), item.optString("arguments"))
                    }
                } else {
                    out += toolCall(json.optString("call_id"), json.optString("name"), json.optString("arguments"))
                }
                out
            }
            "response.done" -> {
                emittedCalls.clear()
                assistantText.setLength(0)
                listOf(RealtimeEvent.ResponseDone)
            }
            "error" -> listOf(
                RealtimeEvent.Failure(
                    json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: json.optString("message").ifBlank { type },
                ),
            )
            else -> emptyList()
        }
    }

    private fun toolCall(callId: String, name: String, args: String): List<RealtimeEvent> {
        if (callId.isBlank() || name.isBlank() || !emittedCalls.add(callId)) return emptyList()
        return listOf(RealtimeEvent.ToolCall(callId, name, args.ifBlank { "{}" }))
    }

    /** 函数结果以 `role=tool` 条目回传；回传完模型自己继续，不需要再发 `response.create`。 */
    override fun toolResult(callId: String, name: String, output: String): List<String> = listOf(
        JSONObject().put("type", "conversation.item.create").put(
            "items",
            JSONArray().put(
                JSONObject().put("call_id", callId).put("role", "tool")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", output))),
            ),
        ).toString(),
    )

    override fun backendItem(text: String): List<String> = speak(text.removePrefix(BACKEND_PREFIX)) ?: emptyList()

    override fun speak(text: String): List<String>? =
        listOf(JSONObject().put("type", "speech_text_buffer.commit").put("text", text).toString())

    override fun cancelResponse(): List<String> = listOf(JSONObject().put("type", "response.cancel").toString())

    override fun muteSignal(muted: Boolean): List<String> =
        listOf(JSONObject().put("type", if (muted) "input_audio_mute.commit" else "input_audio_unmute.commit").toString())

    override fun closeSession(): List<String> = listOf(JSONObject().put("type", "session.close").toString())
}
