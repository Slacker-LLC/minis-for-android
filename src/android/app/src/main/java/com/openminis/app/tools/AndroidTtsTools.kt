package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.speech.SystemTtsVoiceCatalog
import com.openminis.app.speech.VoiceOutputState
import org.json.JSONArray
import org.json.JSONObject

/** Structured ToolRegistry view of the existing system-TTS catalog/settings. */
object AndroidTtsOps {

    suspend fun voices(context: Context): ToolExecutionResult {
        VoiceOutputState.init(context)
        val current = VoiceOutputState.systemVoiceName.value
        val voices = SystemTtsVoiceCatalog.voices(context)
        val output = JSONArray()
        voices.forEach { voice ->
            output.put(
                JSONObject()
                    .put("id", voice.name)
                    .put("name", voice.label)
                    .put("language", voice.locale.toLanguageTag())
                    .put("category", if (voice.networkRequired) "network" else "local")
                    .put("current", voice.name == current),
            )
        }
        return ToolExecutionResult(
            JSONObject().put("voices", output).put("count", output.length()).put("current_voice", current ?: JSONObject.NULL).toString(2),
            true,
        )
    }

    suspend fun setVoice(context: Context, requested: String): ToolExecutionResult {
        if (requested.isBlank()) return ToolExecutionResult("Error: voice is required", false)
        VoiceOutputState.init(context)
        val matches = SystemTtsVoiceCatalog.voices(context).filter {
            it.name.equals(requested, ignoreCase = true) || it.label.equals(requested, ignoreCase = true)
        }.ifEmpty {
            SystemTtsVoiceCatalog.voices(context).filter {
                it.name.contains(requested, ignoreCase = true) || it.label.contains(requested, ignoreCase = true)
            }
        }
        if (matches.size != 1) {
            val candidates = JSONArray(matches.take(10).map { it.name })
            return ToolExecutionResult(
                JSONObject()
                    .put("error", if (matches.isEmpty()) "voice_not_found" else "voice_ambiguous")
                    .put("requested", requested)
                    .put("candidates", candidates)
                    .toString(2),
                false,
            )
        }
        val voice = matches.single()
        VoiceOutputState.setSystemVoice(voice.name, voice.label)
        return ToolExecutionResult(
            JSONObject().put("voice", voice.name).put("name", voice.label).put("language", voice.locale.toLanguageTag()).toString(2),
            true,
        )
    }

    fun setEnabled(context: Context, enabled: Boolean): ToolExecutionResult {
        VoiceOutputState.init(context)
        VoiceOutputState.setEnabled(enabled)
        return ToolExecutionResult(JSONObject().put("enabled", VoiceOutputState.isEnabled.value).toString(), true)
    }
}

class AndroidTtsVoicesHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.tts.voices",
        description = "List available on-device text-to-speech voices and the currently selected one.",
        parameters = emptyMap(),
        timeoutMs = 10_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidTtsOps.voices(context)
}

class AndroidTtsVoiceHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.tts.voice",
        description = "Set Minis automatic read-aloud to an installed system TTS voice by ID or displayed name.",
        parameters = mapOf("voice" to AgentToolParam("string", "Voice ID or name from android.tts.voices")),
        required = listOf("voice"),
        timeoutMs = 10_000L,
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        AndroidTtsOps.setVoice(context, args(argsJson).optString("voice"))
}

class AndroidTtsEnabledHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.tts.enabled",
        description = "Enable or disable Minis automatic read-aloud. Disabling stops active speech.",
        parameters = mapOf("enabled" to AgentToolParam("boolean", "Whether automatic TTS is enabled")),
        required = listOf("enabled"),
    )
    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val a = args(argsJson)
        if (!a.has("enabled")) return ToolExecutionResult("Error: enabled is required", false)
        return AndroidTtsOps.setEnabled(context, a.optBoolean("enabled"))
    }
}
