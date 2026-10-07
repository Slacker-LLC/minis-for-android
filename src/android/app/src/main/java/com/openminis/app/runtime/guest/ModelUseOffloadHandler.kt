package com.openminis.app.runtime.guest

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.runtime.guest.NativeOffloadHandler
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.NativeOffloadResult
import org.json.JSONObject
import com.openminis.app.data.repository.resolvedAgentLoopEntries

/**
 * `minis-model-use` — list, search, and invoke LLM models from the Ubuntu shell.
 * Mirrors iOS ModelUseOffload.m + ModelUseOffloadBridge.swift.
 *
 * Subcommands:
 *   minis-model-use list [--provider <name>] [--modality <m>]
 *   minis-model-use search <query> [--provider <name>] [--modality <m>]
 *   minis-model-use run --model <id_or_name> [--input <path>] [--output <path>]
 *                       [--system <text>] [--system-file <path>]
 *                       [--max-tokens N] [--temperature F]
 *
 * Only entries/groups exposed via the "Available Models in Agent Loop" section
 * in Settings > Models are visible (see ProviderRepository.resolvedAgentLoopEntries).
 */
class ModelUseOffloadHandler(
    internal val context: Context,
    internal val providerRepository: ProviderRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        // --help is never a usage error: the other guest CLIs answer it with
        // exit 0, and an agent that asked for usage should not have to tell a
        // failed call apart from a printed one. No arguments stays a usage
        // error, as before.
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        if (args.positional.isEmpty()) return NativeOffloadResult(2, HELP)

        return try {
            when (val sub = args.positional[0]) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "run" -> cmdRun(args, request)
                else -> NativeOffloadResult(2, "minis-model-use: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "uncaught: ${e.message}", e)
            NativeOffloadResult(
                1,
                JSONObject().put("error", "model_use_failed")
                    .put("message", e.message ?: "unknown").toString() + "\n",
            )
        }
    }




    /**
     * [T-android-image-endpoint-mode] Parsed image-generation knobs from the
     * input JSON top level (native /images/generations shape) with
     * `generation_config` as a lower-priority fallback. Mirrors iOS
     * ModelUseOffloadBridge.parseImageGenerationConfig.
     */
    internal data class ImageGenConfig(
        val prompt: String? = null,
        val n: Int = 1,
        val size: String? = null,
        val quality: String? = null,
        val endpointOverride: com.openminis.app.data.model.ImageEndpointMode? = null,
    )


    /**
     * [T-android-model-use-image-passthrough GH#62] Provider-specific extras
     * forwarded verbatim to the /images/generations request.
     */
    internal data class ImagePassthrough(
        val body: Map<String, Any?> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val path: String? = null,
    )

    /**
     * Keys the handler already interprets itself — never treated as implicit
     * passthrough body fields, so existing callers' structure/behavior is
     * unchanged. (Explicit `extra_body` can still re-introduce any of these.)
     * Mirrors iOS ModelUseOffloadBridge.imageReservedKeys.
     */
    internal val imageReservedKeys: Set<String> = setOf(
        "messages", "model", "chat_model", "prompt", "n", "number_of_images",
        "size", "image_size", "quality", "generation_config", "endpoint",
        "image_endpoint", "endpoint_path", "extra_body", "extra_headers",
        "stream", "temperature", "max_tokens",
    )




    // MARK: - Passthrough mode [T-android-model-use-passthrough-mode]

    /**
     * Explicit passthrough envelope: verbatim body/headers/endpoint, raw
     * (unparsed) response output. Mirrors iOS ModelUseOffloadBridge.PassthroughSpec.
     */
    internal data class PassthroughSpec(
        val active: Boolean = false,
        val endpoint: String? = null,        // "/abs/path?q" (absolute) or "rel/segment"
        val method: String = "POST",
        val headers: Map<String, String> = emptyMap(),
        val body: Map<String, Any?> = emptyMap(),
        val bodyMode: String = "merge",      // "merge" | "replace"
        /** [T-model-use-passthrough-warnings] Ignored/downgraded-field feedback. */
        val warnings: List<String> = emptyList(),
    )
























    /**
     * One parsed message from the input JSON, carrying both text content
     * and any image attachments extracted from a content array. Images
     * collected here are forwarded to [LLMProvider.sendMessage]'s
     * `imageParts` parameter (which providers attach to the last user
     * message), not embedded in [LLMMessage.content] — that field stays
     * a plain string so providers don't see a stringified `[{type:...}]`
     * payload they can't parse.
     */
    internal data class ParsedMessage(
        val role: String,
        val content: String,
        val images: List<LLMMessage.ImagePart>,
        /** [GH#67] input_audio blocks extracted from the content array. */
        val audios: List<LLMMessage.AudioPart> = emptyList(),
    )





    internal class ImageInputError(message: String) : RuntimeException(message)

    /** [GH#67] Malformed/undecodable input_audio block — hard input error. */
    internal class AudioInputError(message: String) : RuntimeException(message)


    companion object {
        internal fun resolveInput(
            args: OffloadArgs,
            request: NativeOffloadRequest,
            readPath: (String, String?) -> String?,
        ): String? = when {
            args.get("input") != null -> readPath(args.get("input")!!, request.sessionId)
            args.hasFlag("input") -> null
            else -> request.stdin.orEmpty()
        }

        internal const val TAG = "ModelUseOffload"
        internal const val NO_MODELS_HINT =
            "No models available. Go to Settings > Models to add models that the agent can use."

        /** Appended to non-empty list/search results so the agent knows how to invoke a model.
         *  Three forms are supported by `run --model`:
         *    1. Plain `model_id` (e.g. `claude-sonnet-4-6`) — works when unique.
         *    2. `instance_label/model_id` (e.g. `deepseek/deepseek-v4-flash`) — required when
         *       the same model_id is configured under multiple provider instances.
         *    3. `--model <model_id> --provider <instance_label>` — equivalent to (2),
         *       preferred when the model_id itself contains slashes.
         *  `entry_id` (UUID) also works but is opaque; prefer the human-readable forms.
         */
        internal const val USAGE_HINT =
            "To invoke a model, pass `--model <model_id>` to `minis-model-use run`. " +
            "If multiple providers expose the same `model_id`, disambiguate either with " +
            "`--model <instance_label>/<model_id>` (e.g. `--model deepseek/deepseek-v4-flash`) " +
            "or with `--model <model_id> --provider <instance_label>` " +
            "(e.g. `--model deepseek-v4-flash --provider deepseek`). " +
            "The opaque `entry_id` (UUID) is also accepted. " +
            // [T-android-model-use-image-passthrough GH#62] Progressive-disclosure
            // breadcrumbs: surface capability keywords up front so the model knows
            // what's possible and which model's per-entry `hint` to read for the
            // exact param shape.
            "Capabilities by modality (run without --model, or inspect a model's `hint` field, " +
            "for the exact JSON shape): text generation, image generation, image-to-image, " +
            "image editing, audio (TTS/STT), embeddings. " +
            "Topics/params you can pass per call: messages, generation_config (size/quality/n/" +
            "aspect_ratio/image_size), image_endpoint, and — for OpenAI-compatible image models — " +
            "PASSTHROUGH via `extra_body`, `extra_headers`, `endpoint_path`, or any unknown " +
            "top-level field (forwarded verbatim; enables provider-specific params like Seedream " +
            "image-to-image). Read the target model's `hint` for concrete examples."

        internal const val HELP = """minis-model-use — list, search, and invoke LLM models

Usage:
  minis-model-use list [--provider <name>] [--modality <mod>]
  minis-model-use search <query> [--provider <name>] [--modality <mod>]
  minis-model-use run --model <id_or_name> [--provider <label_or_id>]
                      [--input <path>] [--output <path>]
                      [--system <text>] [--system-file <path>]
                      [--max-tokens N] [--temperature F]

Run model selection:
  --model accepts: model_id, display name, entry_id (UUID), OR the qualified
  form `<instance_label>/<model_id>` (e.g. `deepseek/deepseek-v4-flash`) to
  disambiguate when the same model_id is configured under multiple providers.
  Equivalently, pass `--model <model_id> --provider <instance_label>`.

Input format (OpenAI Chat Completions JSON — the ONLY input format):
  Standard fields (messages, system, temperature, max_tokens,
  generation_config) are AUTOMATICALLY CONVERTED to whatever the selected
  model's provider expects on the wire. Do NOT hand-write provider-native
  bodies as the primary input.

  AUDIO INPUT (audio_input models, e.g. gpt-4o-audio / Qwen-Audio):
    Add an input_audio content block to a user message (official OpenAI
    shape; forwarded verbatim on both the Chat Completions and Responses
    API paths). The model MUST declare the audio_input modality (see
    `list --modality audio`), otherwise the call fails with an explicit
    modality error.
    {"messages":[{"role":"user","content":[
       {"type":"text","text":"Transcribe this audio"},
       {"type":"input_audio","input_audio":{"data":"<base64>",
        "format":"wav"}}]}]}
    NOTE: dedicated STT transcription endpoints (POST
    /v1/audio/transcriptions on speaches / faster-whisper / whisper.cpp
    servers) require multipart/form-data uploads, which this tool does
    NOT support yet — passthrough mode sends JSON bodies only (known
    limitation). For speech-to-text today, use a chat-protocol audio
    model as above.

  Standard-mode extras (OpenAI-compatible providers, all endpoints):
    extra_body     object — merged VERBATIM into the final request body
                   (your keys win; `model` stays locked). Use for provider
                   fields our schema doesn't model, e.g. OpenRouter web
                   search: {"extra_body":{"plugins":[{"id":"web"}]}} — these
                   are NOT converted.
    extra_headers  string map — added to request headers; same-name REPLACES
                   the default (incl. auth headers).
    endpoint       "/abs/path" starting with "/" replaces the ENTIRE path on
                   the provider's own base URL host (credentials never leave
                   the host); body is still auto-converted, response parsed.

Passthrough mode (raw escape hatch; response is NOT parsed):
  For endpoints/formats our schema doesn't model (video gen, TTS,
  provider-native APIs), add a top-level "passthrough" object:
    {"passthrough": {
       "endpoint": "/v1/audio/speech",  // "/abs/path" replaces the whole
                                         // path on the provider base host;
                                         // omit = chat path
       "method":  "POST",               // default POST
       "headers": {"X-Custom": "1"},    // same-name REPLACES defaults
       "body":    {"input": "hi", "voice": "alloy"},
       "body_mode": "replace"           // replace = body IS the whole request
                                        // body; merge (default) = layered
                                        // over the converted OpenAI baseline,
                                        // model locked
    }}
  Passthrough values are sent VERBATIM (no conversion). The response is
  returned RAW: full bytes to --output (required for binary), or inline text
  up to 64KB; the result reports http_status, content_type, bytes and the
  fully-assembled endpoint_url. OpenAI-compatible providers only for now.

Image generation fields (only for image_output models):
  Pass image params either at the top level OR under "generation_config".
  Top level matches OpenAI /v1/images/generations and takes precedence.

  OpenAI-style (DALL-E / gpt-image-1 etc.):
    n         integer, number of images (default 1)
    size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
    quality   "standard" | "hd"
    prompt    string (overrides last user message)

  Gemini-style (Imagen / gemini-2.5-flash-image etc.) — under generation_config:
    aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
    image_size         "512px" | "1K" | "2K" | "4K"
    number_of_images   1-4
    person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
  Unknown providers silently ignore unsupported fields.

  Example (OpenAI):
    {"prompt":"a red panda astronaut","size":"1792x1024","quality":"hd","n":1}
  Example (Gemini):
    {"messages":[{"role":"user","content":"a red panda astronaut"}],
     "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}

Examples:
  minis-model-use list
  minis-model-use list --modality image_input
  minis-model-use search gemini
  minis-model-use run --model claude-sonnet-4-6 --input /var/minis/workspace/prompt.json
  minis-model-use run --model deepseek/deepseek-v4-flash --input msgs.json   # qualified form
  minis-model-use run --model deepseek-v4-flash --provider deepseek --input msgs.json   # equivalent
  echo 'What is 2+2?' | minis-model-use run --model gpt-4o
  minis-model-use run --model gemini-2.5-flash --system 'You are a poet' \
                      --input msgs.json --output /var/minis/workspace/out.txt
"""
    }
}
