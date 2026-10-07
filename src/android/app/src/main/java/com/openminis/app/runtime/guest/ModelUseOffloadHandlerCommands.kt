package com.openminis.app.runtime.guest

import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.instance
import com.openminis.app.data.repository.loadApiKey
import com.openminis.app.data.repository.primaryEntry
import com.openminis.app.data.repository.resolvedAgentLoopEntries
import com.openminis.app.provider.CustomHeaderPolicy
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.RequestBodyMerge
import com.openminis.app.provider.openai.rawPassthroughRequest
import com.openminis.app.provider.safeOptString
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.AudioInputError
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.Companion.NO_MODELS_HINT
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.Companion.TAG
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.Companion.USAGE_HINT
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.Companion.resolveInput
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ImageGenConfig
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ImageInputError
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ImagePassthrough
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ParsedMessage
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.PassthroughSpec
import com.openminis.app.runtime.guest.NativeOffloadRequest
import com.openminis.app.runtime.guest.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal fun ModelUseOffloadHandler.cmdList(args: OffloadArgs): NativeOffloadResult {
    val providerFilter = args.get("provider")?.lowercase()
    val modalityFilter = args.get("modality")?.lowercase()

    var entries = providerRepository.resolvedAgentLoopEntries()
    if (providerFilter != null) {
        entries = entries.filter {
            it.model.provider.lowercase().contains(providerFilter) ||
                providerInstanceLabel(it).lowercase().contains(providerFilter)
        }
    }
    if (modalityFilter != null) {
        entries = entries.filter { matchesModality(it, modalityFilter) }
    }

    val body = JSONObject().apply {
        put("models", JSONArray().apply { entries.forEach { put(entryDict(it)) } })
        put("count", entries.size)
        if (entries.isEmpty()) put("hint", NO_MODELS_HINT)
        else put("usage", USAGE_HINT)
    }
    return NativeOffloadResult(0, body.toString(2) + "\n")
}

internal fun ModelUseOffloadHandler.cmdSearch(args: OffloadArgs): NativeOffloadResult {
    val query = args.positional.getOrNull(1)
        ?: return NativeOffloadResult(
            2,
            "minis-model-use search: no query. Usage: minis-model-use search <query>\n",
        )
    val q = query.lowercase()
    val modalityFilter = args.get("modality")?.lowercase()

    var matches = providerRepository.resolvedAgentLoopEntries().filter { entry ->
        entry.model.id.lowercase().contains(q) ||
            entry.model.displayName.lowercase().contains(q) ||
            entry.model.provider.lowercase().contains(q)
    }
    if (modalityFilter != null) {
        matches = matches.filter { matchesModality(it, modalityFilter) }
    }

    val body = JSONObject().apply {
        put("query", query)
        put("models", JSONArray().apply { matches.forEach { put(entryDict(it)) } })
        put("count", matches.size)
        if (matches.isEmpty()) put("hint", NO_MODELS_HINT)
        else put("usage", USAGE_HINT)
    }
    return NativeOffloadResult(0, body.toString(2) + "\n")
}

/**
 * The Image slot's primary model, for a run that names no --model but writes an image: asking for a picture
 * is enough, the user already chose which model makes them. The result still goes through [resolveEntry], so a
 * model the user has not made callable by the agent (Settings > Models > Agent models) is refused as usual.
 */
private fun ModelUseOffloadHandler.imageSlotModelId(args: OffloadArgs): String? {
    val output = args.get("output") ?: return null
    if (!isImageExt(output.substringAfterLast('.', "").lowercase())) return null
    return providerRepository.primaryEntry(com.openminis.app.data.model.ModelSlot.image)?.id
}

internal fun ModelUseOffloadHandler.cmdRun(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
    val modelArg = args.get("model")
        ?: imageSlotModelId(args)
        ?: return NativeOffloadResult(
            2,
            "--model is required. Usage: minis-model-use run --model <id_or_name>. " +
                "(For an image --output path, --model may be left out when an Image model is set in Settings > Models.)\n",
        )

    // Optional provider scoping — disambiguates when multiple instances expose the same model_id.
    val providerFilter = args.get("provider")

    // Resolve entry (restricted to agent-loop-visible)
    val entry = resolveEntry(modelArg, providerFilter)
        ?: return NativeOffloadResult(
            2,
            JSONObject().put("error", "model_not_found")
                .put(
                    "message",
                    if (providerFilter != null)
                        "No model '$modelArg' under provider '$providerFilter'. Use 'minis-model-use list' to see available combinations."
                    else
                        "Model '$modelArg' not visible to the agent. Add it in Settings > Models > Agent models.",
                )
                .toString() + "\n",
        )

    // Modality precheck: if --output extension implies a media modality
    // the model doesn't advertise, fail fast before hitting the API.
    // Mirrors iOS ModelUseOffloadBridge.performRun:208-228.
    val outputPath = args.get("output")
    // [T-android-model-use-relative-output] A relative --output can't be
    // resolved reliably: this handler runs in-process and does NOT inherit
    // the shell's cwd, so RuntimePathRegistry.resolveHostPath would silently drop a
    // relative path onto the rootfs root (e.g. "gen_output.json" ->
    // <rootfs>/gen_output.json) and report an unreadable bare-relative path
    // that read_image later rejects. Fail fast with a clear message asking
    // for an absolute path instead. `file://` URLs are already absolute.
    if (outputPath != null && !outputPath.startsWith("/") && !outputPath.startsWith("file://")) {
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "invalid_output_path")
                .put(
                    "message",
                    "--output must be an absolute path (e.g. /var/minis/workspace/out.jpg). " +
                        "Got the relative path '$outputPath', which cannot be resolved because " +
                        "minis-model-use does not inherit the shell's working directory.",
                )
                .toString() + "\n",
        )
    }
    val outputExt = outputPath?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val outputs = entry.model.outputModalities.orEmpty()
    val requiredModality = when {
        isImageExt(outputExt) -> "image_output".takeIf { "image" !in outputs }
        isAudioExt(outputExt) -> "audio_output".takeIf { "audio" !in outputs }
        isVideoExt(outputExt) -> "video_output".takeIf { "video" !in outputs }
        else -> null
    }
    if (requiredModality != null) {
        val supported = buildList {
            if ("text" in entry.model.inputModalities.orEmpty()) add("text_input")
            if ("text" in outputs) add("text_output")
            if ("image" in entry.model.inputModalities.orEmpty()) add("image_input")
            if ("image" in outputs) add("image_output")
            if ("audio" in entry.model.inputModalities.orEmpty()) add("audio_input")
            if ("audio" in outputs) add("audio_output")
            if ("video" in entry.model.inputModalities.orEmpty()) add("video_input")
            if ("video" in outputs) add("video_output")
        }
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "modality_not_supported")
                .put("message", "Model '${entry.model.displayName}' does not support $requiredModality. Supported modalities: ${supported.joinToString(", ")}. Use 'minis-model-use list' to find a model with the required capability.")
                .toString() + "\n",
        )
    }

    // System prompt: --system takes precedence over --system-file
    val explicitSystem = args.get("system") ?: args.get("system-file")?.let {
        readLinuxPath(it, request.sessionId)
    }

    // Parse input messages: --input <path> | stdin
    val inputText = resolveInput(args, request, ::readLinuxPath)
        ?: return NativeOffloadResult(2, "minis-model-use run: cannot read --input '${args.get("input")}'\n")
    val parsed = try {
        parseMessages(inputText, request.sessionId)
    } catch (e: ImageInputError) {
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "image_input_error")
                .put("message", e.message ?: "image input could not be resolved")
                .toString() + "\n",
        )
    } catch (e: AudioInputError) {
        // [GH#67] Malformed input_audio → explicit error, not a silent drop.
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "audio_input_error")
                .put("message", e.message ?: "audio input could not be parsed")
                .toString() + "\n",
        )
    }
    // Any `role: system` messages from the input JSON are folded into the
    // system prompt — the Android LLMMessage enum only exposes user/assistant.
    val inlineSystem = parsed.filter { it.role == "system" }
        .joinToString("\n") { it.content }
    val nonSystem = parsed.filter { it.role != "system" }

    // [GH#67] Audio input needs a model that declares the audio input
    // modality — explicit error instead of the historical silent drop.
    // Mirrors iOS ModelUseOffloadBridge's hasAudioInput gate.
    if (nonSystem.any { it.audios.isNotEmpty() } &&
        "audio" !in entry.model.inputModalities.orEmpty()
    ) {
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "modality_not_supported")
                .put(
                    "message",
                    "Model '${entry.model.displayName}' does not support audio_input, " +
                        "but the input contains an input_audio block. " +
                        "Use 'minis-model-use list --modality audio' to find an audio-capable model.",
                )
                .toString() + "\n",
        )
    }

    val messages = nonSystem.map { textMessage(it.role, it.content, it.audios) }
    val hasAudioInput = messages.any { it.audioParts.isNotEmpty() }
    // [T-android-model-use-multimodal] Forward image_url blocks as the
    // top-level `imageParts` argument — providers (OpenAI / Anthropic /
    // Gemini) attach these to the LAST user message in the conversation
    // when building the wire request. Collecting images from the LAST
    // user-role ParsedMessage matches that contract: any image attached
    // to an earlier turn would be silently ignored on the provider side
    // (this is a known limitation; iOS has the same per-call shape).
    val imageParts = nonSystem.lastOrNull { it.role == "user" }?.images.orEmpty()
    val systemPrompt = when {
        explicitSystem != null && inlineSystem.isNotEmpty() -> "$explicitSystem\n\n$inlineSystem"
        explicitSystem != null -> explicitSystem
        inlineSystem.isNotEmpty() -> inlineSystem
        else -> null
    }

    val maxTokens = args.get("max-tokens")?.toIntOrNull() ?: 4096
    val temperature = args.get("temperature")?.toDoubleOrNull()

    // Build provider + call — runBlocking is acceptable here: this handler
    // is invoked off the main thread by the offload server.
    val apiKey = providerRepository.loadApiKey(entry.providerInstanceId)
        ?: return NativeOffloadResult(
            2,
            JSONObject().put("error", "missing_api_key")
                .put("message", "No API key configured for provider ${entry.model.provider}.")
                .toString() + "\n",
        )
    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return NativeOffloadResult(2, "minis-model-use run: provider instance not found\n")
    val provider = ProviderFactory.create(instance, apiKey, entry.model, context)

    // [GH#67] input_audio serialization is implemented for the OpenAI
    // chat/completions + responses paths only. Other provider types would
    // drop the audio at their own serialization layer — fail loudly here
    // instead. Mirrors iOS ModelUseOffloadBridge.
    if (hasAudioInput && provider !is com.openminis.app.provider.openai.OpenAIProvider) {
        return NativeOffloadResult(
            2,
            JSONObject().put("error", "audio_input_unsupported_provider")
                .put(
                    "message",
                    "input_audio is only supported for OpenAI-compatible providers " +
                        "(chat/completions and responses paths) for now; provider type " +
                        "'${instance.providerType}' would silently drop the audio.",
                )
                .toString() + "\n",
        )
    }

    // [T-android-model-use-passthrough-mode] Explicit passthrough envelope —
    // the raw-mode escape hatch. Checked before image routing and message
    // dispatch because body_mode=replace requests may carry no messages at
    // all. Only OpenAI-compatible providers; others error clearly. Mirrors
    // iOS ModelUseOffloadBridge passthrough branch.
    val ptSpec = parsePassthroughEnvelope(inputText)
    if (ptSpec.active) {
        val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
            ?: return NativeOffloadResult(
                2,
                JSONObject().put("error", "passthrough_unsupported")
                    .put(
                        "message",
                        "passthrough mode is not supported for provider type " +
                            "'${instance.providerType}' yet — only OpenAI-compatible " +
                            "providers. Use the standard OpenAI input format instead.",
                    ).toString() + "\n",
            )
        return performRawPassthrough(
            spec = ptSpec,
            entry = entry,
            openAI = openAI,
            systemPrompt = systemPrompt,
            messages = messages,
            maxTokens = maxTokens,
            outputPath = outputPath,
            sessionId = request.sessionId,
        )
    }

    // [T-android-model-use-passthrough-mode GH#72] Standard mode: promote the
    // explicit `extra_body` / `extra_headers` envelope (previously image-
    // path-only) to the chat/responses text path too, and honor a custom
    // absolute `endpoint` path. Explicit envelope only — implicit unknown
    // top-level keys stay image-path-only so the chat schema keeps its
    // OpenAI top-level semantics. Mirrors iOS standard-mode promotion.
    // [T-model-use-passthrough-warnings] `callWarnings` collects every
    // provided-but-ignored/downgraded field (starting with envelope-
    // lookalike warnings from the passthrough parser); `appliedExtras`
    // confirms which passthrough capabilities actually took effect. Both
    // are attached to the final result JSON. Mirrors iOS performRun.
    val callWarnings = ptSpec.warnings.toMutableList()
    val appliedExtras = JSONObject()
    (provider as? com.openminis.app.provider.openai.OpenAIProvider)?.let { openAI ->
        val chatExtra = parseChatExtraBody(inputText, callWarnings)
        if (chatExtra.isNotEmpty()) {
            openAI.chatExtraBody = chatExtra
            appliedExtras.put("extra_body_keys", JSONArray(chatExtra.keys.sorted()))
            Log.i(TAG, "[ModelUseRoute] CHAT-PASSTHROUGH extra_body keys=[${chatExtra.keys.sorted().joinToString(",")}]")
        }
        val chatHdrs = parseExtraHeaders(inputText, callWarnings)
        if (chatHdrs.isNotEmpty()) {
            openAI.chatExtraHeaders = chatHdrs
            appliedExtras.put("extra_headers_keys", JSONArray(chatHdrs.keys.sorted()))
        }
        parseCustomEndpointPath(inputText)?.let { customPath ->
            openAI.absoluteEndpointOverride = customPath
            appliedExtras.put("custom_endpoint", customPath)
            Log.i(TAG, "[ModelUseRoute] CUSTOM-ENDPOINT absolute path=$customPath")
        }
    }

    // [T-android-image-endpoint-mode] Image-output routing (mirrors iOS
    // ModelUseOffloadBridge image branch). For OpenAI-compatible API-key
    // instances whose target model emits images, route through
    // /v1/images/generations per the instance's imageEndpointMode (with
    // auto-probe + cache), instead of the chat-completions path. OAuth
    // instances are excluded — the Codex gpt-image-2 path is handled inside
    // OpenAIProvider's isCodexImageModel branch on the normal sendMessage
    // call below, and a Codex OAuth token can't reach the Images API. On a
    // route-missing failure (auto mode) we fall through to the chat path.
    val imageRouted = tryImageGenerationRoute(
        entry = entry,
        instance = instance,
        provider = provider,
        inputJson = inputText,
        fallbackPromptMessages = nonSystem,
        inputImages = imageParts,
        outputPath = outputPath,
        outputExt = outputExt,
        sessionId = request.sessionId,
        callWarnings = callWarnings,
    )
    if (imageRouted != null) return attachCallFeedback(imageRouted, callWarnings, appliedExtras)

    val response = try {
        runBlocking {
            com.openminis.app.provider.LLMRetryPolicy.withRetry {
                provider.sendMessage(
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    imageParts = imageParts,
                )
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "sendMessage failed for ${entry.model.id}: ${e.message}", e)
        val base = e.message ?: "model_use_failed"
        val hint = imageParamHint(entry)
        val combined = if (hint.isEmpty()) base else "$base\n\n$hint"
        return NativeOffloadResult(
            1,
            JSONObject().put("error", "model_use_failed")
                .put("message", combined).toString() + "\n",
        )
    }

    // Write output — media-first if the model returned image/audio/video and
    // --output has a media extension, else fall back to text. Mirrors iOS
    // ModelUseOffloadBridge.performRun (non-streaming branch).
    val sessionId = request.sessionId
    val mediaFiles = JSONArray()
    val ts = System.currentTimeMillis() / 1000
    val modelSlug = entry.model.id.replace("/", "_")
    if (outputPath != null) {
        val guestPath = outputPath.removePrefix("file://")
        val firstMedia = response.mediaAttachments.firstOrNull()
        val outputIsMedia = isImageExt(outputExt) || isAudioExt(outputExt) || isVideoExt(outputExt)
        if (firstMedia != null && outputIsMedia) {
            try {
                writeGuestBytes(guestPath, firstMedia.data, sessionId)
            } catch (e: Throwable) {
                return NativeOffloadResult(2, "minis-model-use run: cannot write --output '$outputPath': ${e.message}\n")
            }
            logModelUseWrite(guestPath, firstMedia.data.size, sessionId)
            mediaFiles.put(JSONObject().apply {
                put("type", firstMedia.type.value)
                put("mime_type", firstMedia.mimeType)
                put("path", outputPath)
                put("size", firstMedia.data.size)
            })
        } else {
            Log.d(
                "ModelUseImage",
                "handler text fallback: path=$outputPath textLen=${response.text.length} " +
                    "mediaAttachments=${response.mediaAttachments.size} outputIsMedia=$outputIsMedia",
            )
            try {
                writeGuestBytes(guestPath, response.text.toByteArray(Charsets.UTF_8), sessionId)
            } catch (e: Throwable) {
                return NativeOffloadResult(2, "minis-model-use run: cannot write --output '$outputPath': ${e.message}\n")
            }
        }
    } else if (response.mediaAttachments.isNotEmpty()) {
        for ((idx, media) in response.mediaAttachments.withIndex()) {
            val ext = mimeToExt(media.mimeType)
            val fileName = safeGuestName("model-use-$modelSlug-$ts-$idx.$ext")
            val responsePath = uniqueGuestPath("/var/minis/attachments", fileName, sessionId)
            try {
                writeGuestBytes(responsePath, media.data, sessionId)
            } catch (e: Throwable) {
                Log.w(TAG, "model-use attachment write failed: ${e.message}")
                continue
            }
            logModelUseWrite(responsePath, media.data.size, sessionId)
            mediaFiles.put(JSONObject().apply {
                put("type", media.type.value)
                put("mime_type", media.mimeType)
                put("path", responsePath)
                put("size", media.data.size)
            })
        }
    }

    val body = JSONObject().apply {
        put("model", entry.model.id)
        put("text", response.text)
        response.usage?.let { u ->
            put("usage", JSONObject().apply {
                put("input_tokens", u.inputTokens)
                put("output_tokens", u.outputTokens)
            })
        }
        if (outputPath != null) put("output_file", outputPath)
        if (mediaFiles.length() > 0) put("media_files", mediaFiles)
    }
    return attachCallFeedback(
        NativeOffloadResult(0, body.toString(2) + "\n"),
        callWarnings, appliedExtras,
    )
}

internal fun ModelUseOffloadHandler.parseImageGenConfig(inputJson: String): ImageGenConfig {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return ImageGenConfig()

    var prompt: String? = null
    var n = 1
    var size: String? = null
    var quality: String? = null

    // generation_config first (lower priority).
    obj.optJSONObject("generation_config")?.let { gc ->
        val gn = if (gc.has("n")) gc.optInt("n", 1)
        else if (gc.has("number_of_images")) gc.optInt("number_of_images", 1) else 1
        n = maxOf(1, gn)
        size = gc.safeOptString("size", "").ifEmpty { gc.safeOptString("image_size", "").ifEmpty { null } }
        quality = gc.safeOptString("quality", "").ifEmpty { null }
    }
    // Top-level overrides.
    obj.safeOptString("prompt", "").takeIf { it.isNotEmpty() }?.let { prompt = it }
    if (obj.has("n")) n = maxOf(1, obj.optInt("n", n))
    obj.safeOptString("size", "").takeIf { it.isNotEmpty() }?.let { size = it }
    obj.safeOptString("quality", "").takeIf { it.isNotEmpty() }?.let { quality = it }

    // Endpoint override (image_endpoint / endpoint / generation_config.image_endpoint).
    val rawEndpoint = obj.safeOptString("image_endpoint", "").ifEmpty {
        obj.safeOptString("endpoint", "").ifEmpty {
            obj.optJSONObject("generation_config")?.safeOptString("image_endpoint", "").orEmpty()
        }
    }
    val endpoint = when (rawEndpoint.lowercase()) {
        "auto" -> com.openminis.app.data.model.ImageEndpointMode.auto
        "images_generations", "images-generations", "images-gen" ->
            com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
        "chat_completions", "chat-completions", "chat" ->
            com.openminis.app.data.model.ImageEndpointMode.chatCompletions
        else -> null
    }
    return ImageGenConfig(prompt, n, size, quality, endpoint)
}

/**
 * [T-android-model-use-image-passthrough GH#62] Parse passthrough extras from
 * the input JSON. Two complementary modes, both fully optional and additive
 * (absent → empty, old calls unaffected):
 *   • Explicit envelope: `extra_body` (object), `extra_headers` (string map),
 *     `endpoint_path` (string). Unambiguous; recommended in help.
 *   • Implicit: any TOP-LEVEL key not in [imageReservedKeys] is folded into
 *     the body — so `{"prompt":..,"image":"data:..","watermark":false}` just
 *     works for Seedream-style image-to-image without an envelope.
 * Explicit `extra_body` wins over implicit keys on conflict. Values are kept
 * as raw org.json types so JSONObject.put() re-serializes them faithfully.
 * Mirrors iOS ModelUseOffloadBridge.parseImagePassthrough.
 */
internal fun ModelUseOffloadHandler.parseImagePassthrough(
    inputJson: String,
    warnings: MutableList<String> = mutableListOf(),
): ImagePassthrough {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return ImagePassthrough()

    val body = LinkedHashMap<String, Any?>()
    // Implicit: unknown top-level keys → body.
    for (key in obj.keys()) {
        if (key in imageReservedKeys) continue
        body[key] = obj.opt(key)
    }
    // Explicit envelope overrides/augments the implicit keys.
    // [T-model-use-passthrough-warnings] Type mismatches warn instead of
    // silently skipping (deduped against the standard-mode parser's copy).
    obj.opt("extra_body")?.takeIf { it != JSONObject.NULL }?.let { raw ->
        if (raw is JSONObject) {
            for (key in raw.keys()) body[key] = raw.opt(key)
        } else {
            warnings.add(
                "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                    "Wrap your fields in an object: \"extra_body\":{...}.",
            )
        }
    }
    val headers = LinkedHashMap<String, String>()
    obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL }?.let { raw ->
        if (raw is JSONObject) {
            for (key in raw.keys()) {
                val v = raw.opt(key)
                if (v is String) {
                    headers[key] = v
                } else {
                    warnings.add(
                        "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                            "this header was ignored. Header values must be JSON strings.",
                    )
                }
            }
        } else {
            warnings.add(
                "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                    "all custom headers were ignored.",
            )
        }
    }
    val path = obj.safeOptString("endpoint_path", "").trim().takeIf { it.isNotEmpty() }
    val sanitized = CustomHeaderPolicy.sanitizeWithWarnings(headers)
    warnings.addAll(sanitized.warnings)
    return ImagePassthrough(body, sanitized.headers, path)
}

// MARK: - Call feedback [T-model-use-passthrough-warnings]

/** Human-readable JSON type name for warning messages. Mirrors iOS jsonTypeName. */
internal fun ModelUseOffloadHandler.jsonTypeName(v: Any?): String = when (v) {
    null, JSONObject.NULL -> "null"
    is String -> "string"
    is Boolean -> "bool"
    is Number -> "number"
    is JSONArray -> "array"
    is JSONObject -> "object"
    else -> v.javaClass.simpleName
}

/**
 * Append collected `warnings` and `applied_extras` to a result's JSON body.
 * Both are omitted when empty (no noise on clean calls); warnings are
 * de-duplicated (the same type mismatch can be seen by more than one
 * parser on the image path). Non-JSON outputs pass through unchanged.
 * Mirrors iOS ModelUseOffloadBridge.attachCallFeedback.
 */
internal fun ModelUseOffloadHandler.attachCallFeedback(
    result: NativeOffloadResult,
    warnings: List<String>,
    extras: JSONObject?,
): NativeOffloadResult {
    if (warnings.isEmpty() && (extras == null || extras.length() == 0)) return result
    val body = try {
        val t = result.output.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return result
    if (warnings.isNotEmpty()) {
        val merged = LinkedHashSet<String>()
        body.optJSONArray("warnings")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i)?.let { merged.add(it) }
        }
        merged.addAll(warnings)
        body.put("warnings", JSONArray(merged.toList()))
    }
    if (extras != null && extras.length() > 0 && !body.has("applied_extras")) {
        body.put("applied_extras", extras)
    }
    return NativeOffloadResult(result.exitCode, body.toString(2) + "\n")
}

/**
 * Parse the top-level `passthrough` object into a [PassthroughSpec].
 * Mirrors iOS ModelUseOffloadBridge.parsePassthroughEnvelope.
 */
internal fun ModelUseOffloadHandler.parsePassthroughEnvelope(inputJson: String): PassthroughSpec {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return PassthroughSpec()

    val warnings = mutableListOf<String>()
    val envRaw: Any? = obj.opt("passthrough")
    val env = envRaw as? JSONObject
    if (env == null) {
        // [T-model-use-passthrough-warnings] Exact key absent or wrong
        // type — surface the two silent failure shapes. Mirrors iOS.
        if (envRaw != null && envRaw != JSONObject.NULL) {
            warnings.add(
                "Top-level 'passthrough' is not a JSON object (actual type: ${jsonTypeName(envRaw)}) — " +
                    "it was ignored and this call ran in STANDARD mode. " +
                    "Expected shape: {\"passthrough\":{\"endpoint\":...,\"body\":{...}}}.",
            )
            return PassthroughSpec(warnings = warnings)
        }
        val envelopeKeys = setOf("endpoint", "body", "body_mode", "headers", "method")
        for (key in obj.keys()) {
            if (key == "passthrough" || key in imageReservedKeys) continue
            val v = obj.opt(key) as? JSONObject ?: continue
            val normalized = key.lowercase().replace("_", "").replace("-", "")
            val looksLikeName = normalized in setOf("passthrough", "passthru", "passthough", "pathrough")
            val looksLikeShape = v.keys().asSequence().any { it in envelopeKeys }
            if (looksLikeName || looksLikeShape) {
                warnings.add(
                    "Top-level key '$key' looks like a passthrough envelope, but the exact key " +
                        "must be lowercase 'passthrough' — it was ignored and this call ran in STANDARD mode.",
                )
            }
        }
        return PassthroughSpec(warnings = warnings)
    }

    var endpoint: String? = null
    env.opt("endpoint")?.takeIf { it != JSONObject.NULL }?.let { epRaw ->
        val ep = (epRaw as? String)?.trim()
        if (!ep.isNullOrEmpty()) {
            endpoint = ep
        } else {
            warnings.add(
                "passthrough.endpoint is not a non-empty string (actual type: ${jsonTypeName(epRaw)}) — " +
                    "ignored; the default chat/completions path was used.",
            )
        }
    }
    var method = "POST"
    env.opt("method")?.takeIf { it != JSONObject.NULL }?.let { mRaw ->
        // PATCH included — provider-native update APIs use it. Mirrors iOS.
        val allowed = setOf("GET", "POST", "PUT", "DELETE", "PATCH")
        val m = (mRaw as? String)?.uppercase()
        if (m != null && m in allowed) {
            method = m
        } else {
            val desc = (mRaw as? String) ?: jsonTypeName(mRaw)
            warnings.add("method value '$desc' is not supported (supported: GET/POST/PUT/DELETE/PATCH) — sent as POST.")
        }
    }
    val headers = LinkedHashMap<String, String>()
    env.opt("headers")?.takeIf { it != JSONObject.NULL }?.let { hRaw ->
        if (hRaw is JSONObject) {
            for (key in hRaw.keys()) {
                val v = hRaw.opt(key)
                if (v is String) {
                    headers[key] = v
                } else {
                    warnings.add(
                        "passthrough.headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                            "this header was ignored. Header values must be JSON strings.",
                    )
                }
            }
        } else {
            warnings.add(
                "passthrough.headers is not a JSON object (actual type: ${jsonTypeName(hRaw)}) — " +
                    "all custom headers were ignored.",
            )
        }
    }
    val body = LinkedHashMap<String, Any?>()
    env.opt("body")?.takeIf { it != JSONObject.NULL }?.let { bRaw ->
        if (bRaw is JSONObject) {
            for (key in bRaw.keys()) body[key] = bRaw.opt(key)
        } else {
            warnings.add(
                "passthrough.body is not a JSON object (actual type: ${jsonTypeName(bRaw)}) — " +
                    "ignored. Wrap your body fields in an object: \"body\":{...}.",
            )
        }
    }
    var bodyMode = "merge"
    env.opt("body_mode")?.takeIf { it != JSONObject.NULL }?.let { bmRaw ->
        val bm = (bmRaw as? String)?.lowercase()
        if (bm != null && bm in setOf("merge", "replace")) {
            bodyMode = bm
        } else {
            val desc = (bmRaw as? String) ?: jsonTypeName(bmRaw)
            warnings.add("body_mode value '$desc' is invalid (only merge/replace are supported) — treated as merge.")
        }
    }
    val sanitized = CustomHeaderPolicy.sanitizeWithWarnings(headers)
    warnings.addAll(sanitized.warnings)
    return PassthroughSpec(
        true,
        endpoint,
        method,
        sanitized.headers,
        bodyMode = bodyMode,
        body = body,
        warnings = warnings,
    )
}

/**
 * Standard-mode chat `extra_body`: the EXPLICIT envelope only (implicit
 * unknown top-level keys stay image-path-only, per iOS). Values kept as raw
 * org.json types so JSONObject.put re-serializes them faithfully.
 */
internal fun ModelUseOffloadHandler.parseChatExtraBody(inputJson: String, warnings: MutableList<String>): Map<String, Any?> {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return emptyMap()
    val raw = obj.opt("extra_body")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
    val extra = raw as? JSONObject
    if (extra == null) {
        // [T-model-use-passthrough-warnings] type-mismatch feedback
        warnings.add(
            "extra_body is not a JSON object (actual type: ${jsonTypeName(raw)}) — ignored. " +
                "Wrap your fields in an object: \"extra_body\":{...}.",
        )
        return emptyMap()
    }
    val out = LinkedHashMap<String, Any?>()
    for (key in extra.keys()) out[key] = extra.opt(key)
    return out
}

/** Standard-mode `extra_headers` (string map). */
internal fun ModelUseOffloadHandler.parseExtraHeaders(inputJson: String, warnings: MutableList<String>): Map<String, String> {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return emptyMap()
    val raw = obj.opt("extra_headers")?.takeIf { it != JSONObject.NULL } ?: return emptyMap()
    val hdrs = raw as? JSONObject
    if (hdrs == null) {
        warnings.add(
            "extra_headers is not a JSON object (actual type: ${jsonTypeName(raw)}) — " +
                "all custom headers were ignored.",
        )
        return emptyMap()
    }
    val out = LinkedHashMap<String, String>()
    for (key in hdrs.keys()) {
        val v = hdrs.opt(key)
        if (v is String) {
            out[key] = v
        } else {
            warnings.add(
                "extra_headers.$key value is not a string (actual type: ${jsonTypeName(v)}) — " +
                    "this header was ignored. Header values must be JSON strings.",
            )
        }
    }
    // [T-eta-provider-passthrough] Same Eta filter as the other two header
    // surfaces: protocol-managed and credential-managed names never reach
    // the wire, invalid names/values are dropped, case-insensitive
    // duplicates collapse to the last one.
    val sanitized = CustomHeaderPolicy.sanitizeWithWarnings(out)
    warnings.addAll(sanitized.warnings)
    return sanitized.headers
}

/**
 * Parse a CUSTOM absolute endpoint path from top-level `endpoint` /
 * `image_endpoint` values. A value starting with "/" is an absolute path
 * replacing the entire URL path after scheme+host. Symbolic values
 * (auto/images-gen/chat) return null. Mirrors iOS parseCustomEndpointPath.
 *
 * Note: on Android these arrive inside the input JSON (not as translated
 * argv), so unlike iOS there is no rootfs-dataPath prefix to strip — the
 * value is used verbatim.
 */
internal fun ModelUseOffloadHandler.parseCustomEndpointPath(inputJson: String): String? {
    val obj = try {
        val t = inputJson.trim()
        if (t.startsWith("{")) JSONObject(t) else null
    } catch (_: Throwable) { null } ?: return null
    val raw = obj.safeOptString("image_endpoint", "").ifEmpty { obj.safeOptString("endpoint", "") }
        .trim()
    return if (raw.startsWith("/")) raw else null
}

/**
 * Execute a raw passthrough request and build the metadata result. The
 * response is NOT parsed — full raw bytes go to --output (or, when
 * text-decodable and small, inline into the result). An unwritable --output
 * degrades to inline text + `output_error` instead of failing a call whose
 * HTTP request already completed. Mirrors iOS performRawPassthrough.
 */
internal fun ModelUseOffloadHandler.performRawPassthrough(
    spec: PassthroughSpec,
    entry: ModelEntry,
    openAI: com.openminis.app.provider.openai.OpenAIProvider,
    systemPrompt: String?,
    messages: List<LLMMessage>,
    maxTokens: Int,
    outputPath: String?,
    sessionId: String?,
): NativeOffloadResult {
    // Baseline body for merge mode: the standard OpenAI-shape conversion
    // (messages + system + max_tokens), so callers add ONLY their extras.
    // replace mode: spec.body verbatim (model unlocked — user owns it).
    val warnings = spec.warnings.toMutableList()
    val bodyObject: JSONObject? = if (spec.bodyMode == "replace") {
        if (spec.body.isEmpty()) {
            // Legit for GET-style calls, but a common symptom of body
            // content nested at the wrong JSON level — say so. Mirrors iOS.
            warnings.add(
                "body_mode=replace but body is empty or missing — this request was sent with NO body. " +
                    "If that is not what you intended, check that your fields are nested inside " +
                    "passthrough.body (not at the top level).",
            )
            null
        } else JSONObject().also {
            for ((k, v) in spec.body) it.put(k, v ?: JSONObject.NULL)
        }
    } else {
        val baseline = JSONObject().put("model", entry.model.id)
        if (messages.isNotEmpty()) {
            val arr = JSONArray()
            if (!systemPrompt.isNullOrEmpty()) {
                arr.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            for (m in messages) {
                arr.put(JSONObject().put("role", m.role.value).put("content", m.content))
            }
            baseline.put("messages", arr)
            baseline.put("max_tokens", maxTokens)
            baseline.put("stream", false)
        }
        // [T-eta-provider-passthrough] Recursive merge (Eta rules): nested
        // objects merge field-by-field so a caller adding `reasoning.effort`
        // no longer erases the sibling fields the app already set.
        RequestBodyMerge.mergeInto(baseline, spec.body)
        baseline.put("model", entry.model.id)   // merge mode locks model
        baseline
    }

    val result = try {
        runBlocking {
            openAI.rawPassthroughRequest(
                endpoint = spec.endpoint,
                method = spec.method,
                headers = spec.headers,
                bodyObject = bodyObject,
            )
        }
    } catch (e: Throwable) {
        Log.w(TAG, "raw passthrough failed: ${e.message}", e)
        return NativeOffloadResult(
            1,
            JSONObject().put("error", "passthrough_failed")
                .put("message", e.message ?: "passthrough request failed")
                .toString() + "\n",
        )
    }

    val out = JSONObject()
        .put("passthrough", true)
        .put("http_status", result.status)
        .put("content_type", result.contentType ?: JSONObject.NULL)
        .put("bytes", result.data.size)
        .put("endpoint_url", result.url)
        .put("body_mode", spec.bodyMode)
        .put("model_id", entry.model.id)

    if (outputPath != null) {
        val guestPath = outputPath.removePrefix("file://")
        try {
            writeGuestBytes(guestPath, result.data, sessionId)
            logModelUseWrite(guestPath, result.data.size, sessionId)
            out.put("output_file", outputPath)
        } catch (e: Throwable) {
            // Don't fail the whole call over an unwritable --output — the
            // request already succeeded. Surface it + fall back to inline.
            out.put("output_error", "Could not write --output at $outputPath: ${e.message}")
            inlineTextIfPossible(result.data, out)
        }
    } else {
        inlineTextIfPossible(result.data, out)
    }
    if (result.status < 200 || result.status >= 300) {
        out.put(
            "error_hint",
            "Provider returned HTTP ${result.status}; the raw response body (output file or inline) is unmodified.",
        )
    }
    if (warnings.isNotEmpty()) {
        out.put("warnings", JSONArray(warnings))
    }
    Log.i(TAG, "[ModelUseRoute] raw-passthrough done status=${result.status} bytes=${result.data.size} url=${result.url} warnings=${warnings.size}")
    // Non-2xx → non-zero exit code (raw body still delivered), else 0.
    val code = if (result.status in 200..299) 0 else 1
    return NativeOffloadResult(code, out.toString(2) + "\n")
}

/**
 * Inline a UTF-8-decodable response into the result (truncated at 64KB);
 * binary responses get a hint to re-run with --output. Mirrors iOS.
 */
internal fun ModelUseOffloadHandler.inlineTextIfPossible(data: ByteArray, out: JSONObject) {
    val text = try { String(data, Charsets.UTF_8).takeIf { data.isEmpty() || it.toByteArray(Charsets.UTF_8).contentEquals(data) } } catch (_: Throwable) { null }
    if (text != null) {
        val capped = if (text.length > 65536) text.substring(0, 65536) + "…[truncated]" else text
        out.put("response_text", capped)
        if (text.length > 65536) out.put("truncated", true)
    } else {
        out.put("hint", "Response is binary (${data.size} bytes) and no --output was given — re-run with --output <path> to save it.")
    }
}

/**
 * [T-android-image-endpoint-mode] Heuristic: does this provider error mean
 * the /images/generations route doesn't exist on this base URL? Only then
 * does auto-mode fall back to chat completions — real errors (auth, content
 * moderation, model-not-found) propagate unchanged. Mirrors iOS
 * looksLikeEndpointMissing.
 */
internal fun ModelUseOffloadHandler.looksLikeEndpointMissing(message: String): Boolean {
    val m = message.lowercase()
    return m.contains("404") ||
        m.contains("not found") ||
        m.contains("no such endpoint") ||
        m.contains("unknown endpoint") ||
        m.contains("invalid url") ||
        m.contains("method not allowed") ||
        m.contains("unsupported route") ||
        m.contains("405")
}
