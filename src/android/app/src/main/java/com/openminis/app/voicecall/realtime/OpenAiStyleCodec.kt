package com.openminis.app.voicecall.realtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * OpenAI Realtime 风格的协议（OpenAI、千问 Omni-Realtime 等同族）。
 * 事件名和会话字段在各家略有差别，差别全部放在 [Dialect]。
 */
class OpenAiStyleCodec(
    private val dialect: Dialect,
    override val inputSampleRate: Int,
    override val outputSampleRate: Int,
) : RealtimeCodec {

    /** 各家 `session.update` 的字段形态。事件名基本一致，差别主要在会话配置和工具声明。 */
    enum class SessionShape {
        /** OpenAI GA：`session.type=realtime`、`audio.input.format.type=audio/pcm`。 */
        GA,

        /** 千问 Omni-Realtime：`audio.*.format.type=pcm` + `sample_rate`，音色和 VAD 在根上。 */
        QWEN,

        /** xAI Grok Voice：音色和 VAD 在 session 根上，`audio.*.format` 同 GA，转写模型 `grok-transcribe`。 */
        XAI,

        /** 智谱 GLM-Realtime：`input_audio_format=pcm16`、`output_audio_format=pcm`；函数调用事件没有 call_id。 */
        ZHIPU,

        /** 阶跃 StepAudio：`pcm16`，工具声明嵌套在 `function` 下，VAD 没有 `create_response`。 */
        STEPFUN,
    }

    data class Dialect(
        val shape: SessionShape,
        /** 输入转写模型；null 表示不启用（此时拿不到用户转写）。 */
        val transcriptionModel: String?,
    )

    private val emittedCalls = HashSet<String>()
    private val assistantText = StringBuilder()

    override fun open(config: RealtimeSessionConfig): List<String> {
        val turnDetection = JSONObject()
            .put("type", "server_vad")
            .put("interrupt_response", true)
        if (dialect.shape != SessionShape.STEPFUN && dialect.shape != SessionShape.XAI) {
            turnDetection.put("create_response", config.delegateByTool)
        }
        val session = JSONObject().put("instructions", config.instructions)
        when (dialect.shape) {
            SessionShape.GA -> {
                session.put("type", "realtime").put("model", config.model)
                    .put("output_modalities", JSONArray().put("audio"))
                session.put("audio", audioGa(turnDetection, config.voice, nestedTurnDetection = true))
            }
            SessionShape.XAI -> {
                config.voice?.let { session.put("voice", it) }
                session.put("turn_detection", JSONObject().put("type", "server_vad"))
                session.put("audio", audioGa(null, null, nestedTurnDetection = false))
            }
            SessionShape.QWEN -> {
                session.put("modalities", JSONArray().put("text").put("audio"))
                config.voice?.let { session.put("voice", it) }
                session.put(
                    "audio",
                    JSONObject()
                        .put("input", JSONObject().put("format", JSONObject().put("type", "pcm").put("sample_rate", inputSampleRate)))
                        .put("output", JSONObject().put("format", JSONObject().put("type", "pcm").put("sample_rate", outputSampleRate))),
                )
                dialect.transcriptionModel?.let { session.put("input_audio_transcription", JSONObject().put("model", it)) }
                session.put("turn_detection", turnDetection)
            }
            SessionShape.ZHIPU -> {
                session.put("model", config.model)
                session.put("modalities", JSONArray().put("audio").put("text"))
                config.voice?.let { session.put("voice", it) }
                session.put("input_audio_format", "pcm16").put("output_audio_format", "pcm")
                session.put("turn_detection", turnDetection)
                session.put("beta_fields", JSONObject().put("chat_mode", "audio"))
            }
            SessionShape.STEPFUN -> {
                session.put("modalities", JSONArray().put("text").put("audio"))
                config.voice?.let { session.put("voice", it) }
                session.put("input_audio_format", "pcm16").put("output_audio_format", "pcm16")
                session.put("turn_detection", turnDetection)
            }
        }
        if (config.delegateByTool) {
            session.put("tools", JSONArray().put(delegateTool()))
            if (dialect.shape != SessionShape.ZHIPU && dialect.shape != SessionShape.XAI) session.put("tool_choice", "auto")
        }
        return listOf(JSONObject().put("type", "session.update").put("session", session).toString())
    }

    private fun audioGa(turnDetection: JSONObject?, voice: String?, nestedTurnDetection: Boolean): JSONObject {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", inputSampleRate))
        if (nestedTurnDetection && turnDetection != null) input.put("turn_detection", turnDetection)
        dialect.transcriptionModel?.let { input.put("transcription", JSONObject().put("model", it)) }
        val output = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", outputSampleRate))
        if (nestedTurnDetection) voice?.let { output.put("voice", it) }
        return JSONObject().put("input", input).put("output", output)
    }

    private fun delegateTool(): JSONObject {
        val parameters = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("request", JSONObject().put("type", "string").put("description", DELEGATE_TOOL_PARAM_DESCRIPTION)))
            .put("required", JSONArray().put("request"))
        return if (dialect.shape == SessionShape.STEPFUN) {
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", DELEGATE_TOOL_NAME).put("description", DELEGATE_TOOL_DESCRIPTION).put("parameters", parameters),
            )
        } else {
            JSONObject().put("type", "function").put("name", DELEGATE_TOOL_NAME)
                .put("description", DELEGATE_TOOL_DESCRIPTION).put("parameters", parameters)
        }
    }

    override fun audioIn(pcm: ByteArray): String =
        JSONObject().put("type", "input_audio_buffer.append")
            .put("audio", Base64.getEncoder().encodeToString(pcm)).toString()

    override fun parse(frame: String): List<RealtimeEvent> {
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return emptyList()
        return when (val type = json.optString("type")) {
            "session.updated" -> listOf(RealtimeEvent.Ready)
            "response.output_audio.delta", "response.audio.delta" -> {
                val pcm = runCatching { Base64.getDecoder().decode(json.optString("delta")) }.getOrNull()
                if (pcm == null || pcm.isEmpty()) emptyList() else listOf(RealtimeEvent.AudioOut(pcm))
            }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" -> {
                assistantText.append(json.optString("delta"))
                emptyList()
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" -> {
                val text = json.optString("transcript").ifBlank { assistantText.toString() }
                assistantText.setLength(0)
                if (text.isBlank()) emptyList() else listOf(RealtimeEvent.AssistantText(text, final = true))
            }
            "conversation.item.input_audio_transcription.completed" ->
                json.optString("transcript").trim().takeIf { it.isNotEmpty() }
                    ?.let { listOf(RealtimeEvent.UserTranscript(it)) } ?: emptyList()
            "input_audio_buffer.speech_started" -> listOf(RealtimeEvent.UserSpeechStarted)
            "response.function_call_arguments.done" -> toolCall(
                json.optString("call_id").ifBlank { syntheticCallId(json) },
                json.optString("name"),
                json.optString("arguments"),
            )
            "response.done" -> {
                val out = ArrayList<RealtimeEvent>()
                val items = json.optJSONObject("response")?.optJSONArray("output")
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.optString("type") == "function_call") {
                        out += toolCall(item.optString("call_id"), item.optString("name"), item.optString("arguments"))
                    }
                }
                emittedCalls.clear()
                assistantText.setLength(0)
                out += RealtimeEvent.ResponseDone
                out
            }
            "error" -> listOf(RealtimeEvent.Failure(json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: json.optString("message", type)))
            else -> emptyList()
        }
    }

    /** 智谱的函数调用没有 call_id：用 `response_id:output_index` 当本地标识（回传时不发）。 */
    private fun syntheticCallId(json: JSONObject): String =
        if (dialect.shape == SessionShape.ZHIPU && json.has("response_id")) "${json.optString("response_id")}:${json.optInt("output_index")}" else ""

    /** `function_call_arguments.done` 和 `response.done` 都会带同一个调用，按 call_id 只发一次。 */
    private fun toolCall(callId: String, name: String, args: String): List<RealtimeEvent> {
        if (callId.isBlank() || name.isBlank() || !emittedCalls.add(callId)) return emptyList()
        return listOf(RealtimeEvent.ToolCall(callId, name, args.ifBlank { "{}" }))
    }

    override fun toolResult(callId: String, name: String, output: String): List<String> = listOf(
        JSONObject().put("type", "conversation.item.create").put(
            "item",
            JSONObject().put("type", "function_call_output").apply {
                // 智谱的回传项不带 call_id（文档示例只有 output）。
                if (dialect.shape != SessionShape.ZHIPU) put("call_id", callId)
                put("output", output)
                if (dialect.shape == SessionShape.ZHIPU) put("object", "realtime.item")
            },
        ).toString(),
        JSONObject().put("type", "response.create").toString(),
    )

    override fun backendItem(text: String): List<String> = listOf(
        JSONObject().put("type", "conversation.item.create").put(
            "item",
            JSONObject().put("type", "message").put("role", "user")
                .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text))),
        ).toString(),
        JSONObject().put("type", "response.create").toString(),
    )

    override fun cancelResponse(): List<String> = listOf(JSONObject().put("type", "response.cancel").toString())
}
