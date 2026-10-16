package com.openminis.app.voicecall.realtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Gemini Live（BidiGenerateContent）。输入 16 kHz，输出 24 kHz，函数调用走 toolCall / toolResponse。 */
class GeminiLiveCodec(
    override val inputSampleRate: Int = 16_000,
    override val outputSampleRate: Int = 24_000,
) : RealtimeCodec {
    private val inputText = StringBuilder()
    private val outputText = StringBuilder()

    override fun open(config: RealtimeSessionConfig): List<String> {
        val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        config.voice?.let {
            generation.put(
                "speechConfig",
                JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", it))),
            )
        }
        val setup = JSONObject()
            .put("model", if (config.model.startsWith("models/")) config.model else "models/${config.model}")
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", config.instructions))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        if (config.delegateByTool) {
            setup.put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        JSONArray().put(
                            JSONObject().put("name", DELEGATE_TOOL_NAME).put("description", DELEGATE_TOOL_DESCRIPTION).put(
                                "parameters",
                                JSONObject().put("type", "OBJECT")
                                    .put("properties", JSONObject().put("request", JSONObject().put("type", "STRING").put("description", DELEGATE_TOOL_PARAM_DESCRIPTION)))
                                    .put("required", JSONArray().put("request")),
                            ),
                        ),
                    ),
                ),
            )
        }
        return listOf(JSONObject().put("setup", setup).toString())
    }

    override fun audioIn(pcm: ByteArray): String = JSONObject().put(
        "realtimeInput",
        JSONObject().put(
            "audio",
            JSONObject().put("data", Base64.getEncoder().encodeToString(pcm)).put("mimeType", "audio/pcm;rate=$inputSampleRate"),
        ),
    ).toString()

    override fun parse(frame: String): List<RealtimeEvent> {
        val json = runCatching { JSONObject(frame) }.getOrNull() ?: return emptyList()
        val out = ArrayList<RealtimeEvent>()
        if (json.has("setupComplete")) out += RealtimeEvent.Ready
        json.optJSONObject("serverContent")?.let { content ->
            content.optJSONObject("inputTranscription")?.optString("text")?.let { inputText.append(it) }
            content.optJSONObject("outputTranscription")?.optString("text")?.let { outputText.append(it) }
            val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                if (data.isEmpty()) continue
                flushInput(out)
                runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { out += RealtimeEvent.AudioOut(it) }
            }
            if (content.optBoolean("interrupted")) {
                outputText.setLength(0)
                out += RealtimeEvent.UserSpeechStarted
            }
            if (content.optBoolean("turnComplete")) {
                flushInput(out)
                if (outputText.isNotBlank()) out += RealtimeEvent.AssistantText(outputText.toString().trim(), final = true)
                outputText.setLength(0)
                out += RealtimeEvent.ResponseDone
            }
        }
        json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
            flushInput(out)
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val id = call.optString("id")
                val name = call.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) {
                    out += RealtimeEvent.ToolCall(id, name, (call.optJSONObject("args") ?: JSONObject()).toString())
                }
            }
        }
        json.optJSONObject("error")?.let { out += RealtimeEvent.Failure(it.optString("message", "error")) }
        return out
    }

    private fun flushInput(out: MutableList<RealtimeEvent>) {
        val text = inputText.toString().trim()
        inputText.setLength(0)
        if (text.isNotEmpty()) out += RealtimeEvent.UserTranscript(text)
    }

    override fun toolResult(callId: String, name: String, output: String): List<String> = listOf(
        JSONObject().put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                JSONArray().put(JSONObject().put("id", callId).put("name", name).put("response", JSONObject().put("result", output))),
            ),
        ).toString(),
    )

    /** Gemini 支持函数调用，不走条目注入；万一用到，作为一轮用户文本送出。 */
    override fun backendItem(text: String): List<String> = listOf(
        JSONObject().put(
            "clientContent",
            JSONObject().put("turns", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("turnComplete", true),
        ).toString(),
    )

    /** Gemini Live 没有显式取消回复的消息；用户一开口服务端会自动打断。 */
    override fun cancelResponse(): List<String> = emptyList()
}
