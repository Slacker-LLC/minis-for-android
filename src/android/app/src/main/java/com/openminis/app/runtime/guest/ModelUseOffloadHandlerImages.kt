package com.openminis.app.runtime.guest

import android.util.Base64
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.instance
import com.openminis.app.data.repository.resolvedAgentLoopEntries
import com.openminis.app.data.repository.setImageEndpointResolved
import com.openminis.app.provider.openai.editImage
import com.openminis.app.provider.openai.generateImage
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.AudioInputError
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.Companion.TAG
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ImageInputError
import com.openminis.app.runtime.guest.ModelUseOffloadHandler.ParsedMessage
import com.openminis.app.runtime.guest.NativeOffloadResult
import com.openminis.app.tools.ExternalMountAccess
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-image-endpoint-mode] Attempt image-output routing through
 * /v1/images/generations. Returns a completed [NativeOffloadResult] when
 * the image path produced output, or null to fall through to the normal
 * chat-completions sendMessage path (chatCompletions mode, auto-fallback,
 * or "not an image route" — e.g. non-image model / OAuth / non-OpenAI).
 * Mirrors iOS ModelUseOffloadBridge step 4.
 */
internal fun ModelUseOffloadHandler.tryImageGenerationRoute(
    entry: ModelEntry,
    instance: com.openminis.app.data.model.ProviderInstance,
    provider: com.openminis.app.provider.LLMProvider,
    inputJson: String,
    fallbackPromptMessages: List<ParsedMessage>,
    inputImages: List<LLMMessage.ImagePart>,
    outputPath: String?,
    outputExt: String,
    sessionId: String?,
    callWarnings: MutableList<String> = mutableListOf(),
): NativeOffloadResult? {
    val outputs = entry.model.outputModalities.orEmpty()
    val wantsImageOutput = "image" in outputs
    val openAI = provider as? com.openminis.app.provider.openai.OpenAIProvider
    // Only OpenAI-compatible API-key instances with an image-output model.
    if (!wantsImageOutput || openAI == null) return null
    val pType = instance.providerType
    if (pType != ProviderType.openAI && pType != ProviderType.openRouter && pType != ProviderType.xAI) return null
    if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) return null

    val cfg = parseImageGenConfig(inputJson)
    val prompt = cfg.prompt
        ?: fallbackPromptMessages.lastOrNull { it.role == "user" }?.content
        ?: ""

    // [T-android-model-use-image-passthrough GH#62] Forward arbitrary
    // provider-specific body fields, an endpoint-path override, and extra
    // headers straight through to the /images/generations request — lets
    // callers drive models our fixed schema never modeled (e.g. Volcengine
    // Seedream image-to-image via the `image` body field) with no persisted
    // config. Set on the per-call provider so every generateImage call site
    // below picks them up; nothing leaks across runs (the provider is built
    // fresh per model-use invocation).
    val passthrough = parseImagePassthrough(inputJson, callWarnings)
    openAI.imageExtraBody = passthrough.body
    openAI.imagePathOverride = passthrough.path
    openAI.imageExtraHeaders = passthrough.headers
    if (passthrough.body.isNotEmpty() || passthrough.path != null || passthrough.headers.isNotEmpty()) {
        Log.i(
            TAG,
            "[ModelUseRoute] PASSTHROUGH bodyKeys=[${passthrough.body.keys.sorted().joinToString(",")}] " +
                "pathOverride=${passthrough.path ?: "<nil>"} " +
                "extraHeaderKeys=[${passthrough.headers.keys.sorted().joinToString(",")}]",
        )
    }

    // A pure image generator (image_output but NOT text_output) can ONLY
    // use the Images API — never let a stale cached chatCompletions pin it
    // to the chat endpoint (which 401s "Missing scopes"). Mixed text+image
    // models keep auto/probe/fallback.
    val isPureImageGenerator = "image" in outputs && "text" !in outputs

    // [T-android-image-edit-endpoint] Input-image requests now route to
    // /v1/images/edits via editImage, matching iOS ModelUseOffloadBridge —
    // the endpoint whose absence made this return image_edit_not_supported.
    // Only a PURE image generator takes that route: a mixed text+image model
    // still declines to chat, which forwards imageParts to the model and is
    // the better channel for "look at this image and answer" requests. That
    // split is the pre-existing guard semantics, deliberately unchanged.
    if (inputImages.isNotEmpty() && !isPureImageGenerator) {
        Log.i(
            TAG,
            "[ModelUseRoute] input images present for ${entry.model.id} (text+image model) — declining images route, falling through to chat so images are forwarded",
        )
        return null
    }

    val effectiveMode = when {
        cfg.endpointOverride != null -> cfg.endpointOverride
        isPureImageGenerator -> com.openminis.app.data.model.ImageEndpointMode.imagesGenerations
        instance.imageEndpointMode == com.openminis.app.data.model.ImageEndpointMode.auto &&
            instance.imageEndpointResolved != null -> instance.imageEndpointResolved!!
        else -> instance.imageEndpointMode
    }
    Log.i(
        TAG,
        "[ModelUseRoute] image route model=${entry.model.id} mode=${instance.imageEndpointMode} " +
            "resolved=${instance.imageEndpointResolved} pure=$isPureImageGenerator override=${cfg.endpointOverride} " +
            "effective=$effectiveMode",
    )

    // chatCompletions → no image route; fall through to normal sendMessage.
    if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.chatCompletions) return null

    // imagesGenerations (forced/cached/pure) → call directly; a failure here
    // is terminal (no silent fallback) so the caller sees the real error.
    if (effectiveMode == com.openminis.app.data.model.ImageEndpointMode.imagesGenerations) {
        val response = try {
            runBlocking {
                if (inputImages.isEmpty()) {
                    openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
                } else {
                    openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[ModelUseRoute] images/${if (inputImages.isEmpty()) "generations" else "edits"} (forced) failed: ${e.message}", e)
            val hint = imageParamHint(entry)
            val base = e.message ?: "image_generation_failed"
            return NativeOffloadResult(
                1,
                JSONObject().put("error", "image_generation_failed")
                    .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                    .toString() + "\n",
            )
        }
        return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
    }

    // auto mode: probe /images/generations; cache the result; on a
    // route-missing 4xx, cache chatCompletions and fall through to chat.
    val response = try {
        runBlocking {
            if (inputImages.isEmpty()) {
                openAI.generateImage(prompt, cfg.n, cfg.size, cfg.quality)
            } else {
                openAI.editImage(prompt, inputImages, cfg.n, cfg.size, cfg.quality)
            }
        }.also {
            providerRepository.setImageEndpointResolved(
                instance.id, com.openminis.app.data.model.ImageEndpointMode.imagesGenerations,
            )
        }
    } catch (e: Throwable) {
        val msg = e.message ?: ""
        val routeMissing = (e is com.openminis.app.data.model.LLMError.ProviderError ||
            e is com.openminis.app.data.model.LLMError.InvalidApiKey) && looksLikeEndpointMissing(msg)
        if (routeMissing) {
            Log.i(TAG, "[ModelUseRoute] auto probe → route missing (${msg.take(120)}) — caching chat_completions, falling back")
            providerRepository.setImageEndpointResolved(
                instance.id, com.openminis.app.data.model.ImageEndpointMode.chatCompletions,
            )
            return null // fall through to chat path
        }
        // Real error (auth/moderation/network) — surface it, don't fall through.
        Log.w(TAG, "[ModelUseRoute] auto probe failed (non-route): ${e.message}", e)
        val hint = imageParamHint(entry)
        val base = e.message ?: "image_generation_failed"
        return NativeOffloadResult(
            1,
            JSONObject().put("error", "image_generation_failed")
                .put("message", if (hint.isEmpty()) base else "$base\n\n$hint")
                .toString() + "\n",
        )
    }
    return writeImageResult(entry, response, outputPath, outputExt, "images_generations", sessionId)
}

/**
 * [T-android-image-endpoint-mode] Write an image-generation [response] to
 * --output (media-first when the path is an image extension) and build the
 * standard model-use result JSON. Shared shape with cmdRun's media-write
 * branch.
 */
internal fun ModelUseOffloadHandler.writeImageResult(
    entry: ModelEntry,
    response: com.openminis.app.data.model.LLMResponse,
    outputPath: String?,
    outputExt: String,
    endpointUsed: String,
    sessionId: String?,
): NativeOffloadResult {
    val mediaFiles = JSONArray()
    val firstMedia = response.mediaAttachments.firstOrNull()
    val ts = System.currentTimeMillis() / 1000
    val modelSlug = entry.model.id.replace("/", "_")
    if (outputPath != null) {
        val guestPath = outputPath.removePrefix("file://")
        val outputIsMedia = isImageExt(outputExt)
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
        put("image_endpoint", endpointUsed)
        if (outputPath != null) put("output_file", outputPath)
        if (mediaFiles.length() > 0) put("media_files", mediaFiles)
        if (firstMedia == null) put("warning", "Image endpoint returned no image data.")
    }
    return NativeOffloadResult(0, body.toString(2) + "\n")
}

/**
 * [T-android-model-use-session-scoped-write] Resolve a `/var/minis/<sub>/...`
 * Linux path against the caller session's App-owned storage mapping rather
 * than the global last-writer-wins bind-mount map. That global map is
 * overwritten by ExecutionCoordinator.buildSessionBindMounts on every shell
 * build, so RuntimePathRegistry.resolveHostPath("/var/minis/attachments") returns
 * whichever session built a shell most recently — a model-use call from
 * session A could then write into session B's attachments dir while the
 * response reports the abstract `/var/minis/attachments/...` path, which A's
 * shell (mounted to A's dir) can't read. Mirrors iOS da4b6c5d, which routes
 * the write to minisAttachmentsPersistentDir(for: callerSid).
 *
 * Session-scoped subdirs are attachments/offloads/workspace/browser (see
 * buildSessionBindMounts). WorkspaceFileClient resolves these directly to
 * filesDir/minis-sessions/<sid>/<sub>/<rest> and keeps session identity
 * explicit for every read/write.
 */
internal fun ModelUseOffloadHandler.safeGuestName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .ifBlank { "model-output.bin" }

internal fun ModelUseOffloadHandler.uniqueGuestPath(directory: String, filename: String, sessionId: String?): String =
    runBlocking {
        WorkspaceFileClient.uniqueChildPath(sessionId.orEmpty(), directory, safeGuestName(filename))
    }

internal fun ModelUseOffloadHandler.writeGuestBytes(path: String, bytes: ByteArray, sessionId: String?): Long =
    runBlocking { WorkspaceFileClient.writeBytes(sessionId.orEmpty(), path, bytes) }

/** [T-android-model-use-session-scoped-write] One-line audit of a model-use
 *  write so the response path vs on-disk host path vs session scoping is
 *  greppable when diagnosing "file disappears / wrong session" reports. */
internal fun ModelUseOffloadHandler.logModelUseWrite(responsePath: String, bytes: Int, sessionId: String?) {
    Log.i(
        "ModelUseImage",
        "[ModelUseWrite] path=$responsePath bytes=$bytes sessionId=$sessionId via=app-storage",
    )
}

/**
 * If [entry] is an image_output model, return a hint listing the params it
 * actually accepts, so a failed sendMessage tells the caller how to retry.
 * Empty string for non-image models.
 */
internal fun ModelUseOffloadHandler.imageParamHint(entry: ModelEntry): String {
    if ("image" !in entry.model.outputModalities.orEmpty()) return ""
    val pType = providerRepository.instance(entry.providerInstanceId)?.providerType
    return when (pType) {
        ProviderType.gemini -> """
            Hint — ${entry.model.displayName} is a Gemini image model. Pass image params under `generation_config` in the input JSON:
              aspect_ratio       "1:1" | "16:9" | "9:16" | "4:3" | "3:4"
              image_size         "512px" | "1K" | "2K" | "4K"
              number_of_images   1-4
              person_generation  "DONT_ALLOW" | "ALLOW_ADULT"
            Example:
              {"messages":[{"role":"user","content":"<prompt>"}],
               "generation_config":{"aspect_ratio":"16:9","image_size":"2K"}}
        """.trimIndent()
        ProviderType.openAI, ProviderType.openRouter, ProviderType.openAIResponses -> """
            Hint — ${entry.model.displayName} is an OpenAI-compatible image model. Pass image params at the top level of the input JSON (matches /v1/images/generations):
              n         integer, number of images (default 1)
              size      "1024x1024" | "1792x1024" | "1024x1792" | etc.
              quality   "standard" | "hd"
              prompt    string (overrides last user message)
            Example:
              {"prompt":"<prompt>","size":"1792x1024","quality":"hd","n":1}

            Passthrough (topics: image-to-image, extra_body, extra_headers, endpoint_path) — any field our schema doesn't model is forwarded VERBATIM into the /images/generations JSON body, so you can drive provider-specific params (e.g. Volcengine Seedream image-to-image via an `image` field, `watermark`, `tools`). Two ways, both optional & additive:
              • implicit: any unknown TOP-LEVEL key → request body
              • explicit: `extra_body` (object, merged into body), `extra_headers` (string map, added to request headers), `endpoint_path` (string, overrides "/images/generations" for non-standard endpoints)
            Your keys win over our defaults; `model` is always forced to the resolved id. If you set `response_format`, the b64_json auto-probe is skipped.
            Image-to-image example (Seedream — image goes in the BODY, not messages):
              {"prompt":"make the eyes blue","image":"data:image/png;base64,<...>","size":"2K","watermark":false}
            Explicit-envelope example:
              {"messages":[{"role":"user","content":"<prompt>"}],"extra_body":{"image":"<url-or-data-uri>","seed":42},"extra_headers":{"X-Custom":"1"},"endpoint_path":"/api/v3/images/generations"}
        """.trimIndent()
        // xAI (Grok) / Kimi Coding have no image-output models in the
        // current catalog — fall through to empty hint like Anthropic.
        ProviderType.anthropic,
        ProviderType.xAI,
        ProviderType.kimiCode,
        ProviderType.antigravity,
        ProviderType.unsupported,
        null -> ""
    }
}

internal fun ModelUseOffloadHandler.isImageExt(ext: String): Boolean =
    ext in setOf("png", "jpg", "jpeg", "webp", "gif", "heic")

internal fun ModelUseOffloadHandler.isAudioExt(ext: String): Boolean =
    ext in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")

internal fun ModelUseOffloadHandler.isVideoExt(ext: String): Boolean =
    ext in setOf("mp4", "mov", "webm", "mkv")

internal fun ModelUseOffloadHandler.mimeToExt(mime: String): String = when {
    mime.contains("png") -> "png"
    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
    mime.contains("webp") -> "webp"
    mime.contains("gif") -> "gif"
    mime.contains("wav") -> "wav"
    mime.contains("mp3") || mime.contains("mpeg") -> "mp3"
    mime.contains("mp4") -> "mp4"
    mime.contains("ogg") -> "ogg"
    else -> mime.substringAfterLast("/", "bin")
}

// ─── Helpers ────────────────────────────────────────────────────────────

internal fun ModelUseOffloadHandler.resolveEntry(idOrName: String, providerFilter: String? = null): ModelEntry? {
    val all = providerRepository.resolvedAgentLoopEntries()

    // Apply --provider filter first: match against instance label (case-insensitive)
    // OR instance UUID. Restricts the lookup pool so the same model_id under
    // multiple providers can be picked unambiguously.
    val pool = if (providerFilter.isNullOrEmpty()) all else {
        val pf = providerFilter.lowercase()
        all.filter { entry ->
            val inst = providerRepository.instance(entry.providerInstanceId)
            inst != null && (inst.label.lowercase() == pf || inst.id.lowercase() == pf)
        }
    }
    if (pool.isEmpty()) return null

    // 0. Qualified `<instance_label>/<model_id>` — split on FIRST '/' only
    //    since model_id itself may contain '/' (e.g. "deepseek-ai/DeepSeek-V4").
    val slash = idOrName.indexOf('/')
    if (slash > 0 && slash < idOrName.length - 1) {
        val labelPart = idOrName.substring(0, slash).lowercase()
        val modelPart = idOrName.substring(slash + 1).lowercase()
        pool.find { entry ->
            val inst = providerRepository.instance(entry.providerInstanceId)
            inst != null &&
                inst.label.lowercase() == labelPart &&
                entry.model.id.lowercase() == modelPart
        }?.let { return it }
    }

    // Exact ID
    pool.find { it.model.id == idOrName }?.let { return it }
    // Exact display name
    pool.find { it.model.displayName == idOrName }?.let { return it }
    // Entry UUID
    pool.find { it.id == idOrName }?.let { return it }
    // Case-insensitive prefix / contains
    val q = idOrName.lowercase()
    pool.find { it.model.id.lowercase().startsWith(q) }?.let { return it }
    pool.find { it.model.displayName.lowercase().contains(q) }?.let { return it }
    return null
}

internal fun ModelUseOffloadHandler.providerInstanceLabel(entry: ModelEntry): String =
    providerRepository.instance(entry.providerInstanceId)?.label.orEmpty()

internal fun ModelUseOffloadHandler.entryDict(entry: ModelEntry): JSONObject =
    com.openminis.app.offload.ModelUseManager.entryDict(
        entry,
        providerRepository.instance(entry.providerInstanceId),
    )

/**
 * Modality filter: matches iOS behavior. Terms are comma-separated and
 * checked against input/output modality lists. All requested terms must
 * be satisfied for an entry to pass.
 */
internal fun ModelUseOffloadHandler.matchesModality(entry: ModelEntry, filter: String): Boolean {
    val inputs = entry.model.inputModalities.orEmpty()
    val outputs = entry.model.outputModalities.orEmpty()
    val required = filter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    for (term in required) {
        val ok = when (term) {
            "text", "text_input" -> "text" in inputs || inputs.isEmpty()
            "text_output" -> "text" in outputs || outputs.isEmpty()
            "image", "image_input" -> "image" in inputs
            "image_output" -> "image" in outputs
            "audio", "audio_input" -> "audio" in inputs
            "audio_output" -> "audio" in outputs
            "video", "video_input" -> "video" in inputs
            "video_output" -> "video" in outputs
            "pdf", "pdf_input" -> "pdf" in inputs
            else -> true
        }
        if (!ok) return false
    }
    return true
}

internal fun ModelUseOffloadHandler.readLinuxPath(linuxPath: String, sessionId: String?): String? {
    val path = linuxPath.removePrefix("file://")
    return try {
        val bytes = if (path.startsWith("/var/minis/mounts/")) {
            ExternalMountAccess.readBlocking(path)
        } else {
            WorkspaceFileClient.readAllBlocking(sessionId.orEmpty(), path)
        }
        bytes.toString(Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Parse messages from stdin text or a JSON file. Preserves `system`
 * messages so the caller can merge them into systemPrompt. Image
 * parts (OpenAI-style `{"type":"image_url","image_url":{"url":...}}`
 * blocks) are extracted into [ParsedMessage.images] — previously
 * they were silently dropped during the content-array reduction and
 * the model received text-only input, which is why Jackson saw the
 * `NO_IMAGE` response (T-android-model-use-multimodal).
 *
 * Accepted shapes:
 * - `{"messages":[{"role":"user","content":"..."}]}` — OpenAI-style
 * - `[{"role":"...","content":"..."}, ...]` — array of messages
 * - Plain text → wrapped as a single user message
 */
internal fun ModelUseOffloadHandler.parseMessages(text: String, sessionId: String?): List<ParsedMessage> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    try {
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val arr = obj.optJSONArray("messages")
                ?: return listOf(ParsedMessage("user", trimmed, emptyList()))
            return parseMessageArray(arr, sessionId)
        }
        if (trimmed.startsWith("[")) {
            return parseMessageArray(JSONArray(trimmed), sessionId)
        }
    } catch (e: ImageInputError) {
        // Deliberate hard errors from block parsing must NOT be swallowed
        // by the plain-text fallback — otherwise the whole input JSON gets
        // sent to the model as literal text (observed with a malformed
        // input_audio block during GH#67 verification).
        throw e
    } catch (e: AudioInputError) {
        throw e
    } catch (_: Throwable) {
        // Fall through to plain-text handling
    }
    return listOf(ParsedMessage("user", trimmed, emptyList()))
}

internal fun ModelUseOffloadHandler.parseMessageArray(arr: JSONArray, sessionId: String?): List<ParsedMessage> {
    val out = mutableListOf<ParsedMessage>()
    for (i in 0 until arr.length()) {
        val m = arr.optJSONObject(i) ?: continue
        val role = m.optString("role", "user").lowercase()
        val textBuf = StringBuilder()
        val imgs = mutableListOf<LLMMessage.ImagePart>()
        val auds = mutableListOf<LLMMessage.AudioPart>()
        when (val c = m.opt("content")) {
            is String -> textBuf.append(c)
            is JSONArray -> {
                for (j in 0 until c.length()) {
                    val part = c.optJSONObject(j) ?: continue
                    val type = part.optString("type")
                    if (type == "text" || type.isEmpty()) {
                        textBuf.append(part.optString("text", ""))
                    } else if (type == "image_url") {
                        val imgObj = part.optJSONObject("image_url") ?: continue
                        val url = imgObj.optString("url", "").takeIf { it.isNotEmpty() }
                            ?: continue
                        try {
                            imgs.add(resolveImageUrl(url, sessionId))
                        } catch (e: ImageInputError) {
                            // Don't silently drop — surface to the agent so it
                            // can correct the URL or fall back to text.
                            throw e
                        }
                    } else if (type == "input_audio") {
                        // [GH#67] input_audio was previously skipped with no
                        // trace — the request went out text-only. Parse the
                        // official OpenAI shape; malformed blocks are a hard
                        // input error, not a silent drop. Mirrors iOS
                        // parseOpenAIMessages.
                        val audioObj = part.optJSONObject("input_audio")
                            ?: throw AudioInputError(
                                "input_audio block is malformed. Expected " +
                                    "{\"type\":\"input_audio\",\"input_audio\":" +
                                    "{\"data\":\"<base64>\",\"format\":\"wav\"}}.",
                            )
                        val b64 = audioObj.optString("data", "")
                        if (b64.isEmpty()) {
                            throw AudioInputError("input_audio.data is missing or empty.")
                        }
                        try {
                            Base64.decode(b64, Base64.DEFAULT)
                        } catch (e: IllegalArgumentException) {
                            throw AudioInputError("input_audio.data is not valid base64: ${e.message}")
                        }
                        val format = audioObj.optString("format", "wav").lowercase()
                        auds.add(LLMMessage.AudioPart(format = format, base64Data = b64))
                    }
                }
            }
            else -> { /* leave both buffers empty → message skipped below */ }
        }
        if (textBuf.isEmpty() && imgs.isEmpty() && auds.isEmpty()) continue
        out.add(ParsedMessage(role, textBuf.toString(), imgs, auds))
    }
    return out
}

/**
 * Resolve an OpenAI-style image_url string to raw bytes + MIME.
 * Mirrors iOS ModelUseOffloadBridge.resolveImageURL (subset that the
 * Android offload path actually needs):
 *
 *  - `data:<mime>;base64,<...>` → inline base64
 *  - `file:///<guest-path>` → guest-storage read
 *  - `/var/minis/<scope>/<path>` or `/<abs/linux/path>` → guest-storage read
 *  - `http(s)://` → throw with a hint to download via shell_execute
 *    first (matches iOS — avoids egressing user content)
 *  - anything else (relative paths, unknown schemes) → throw
 *
 * Throws [ImageInputError] on any unresolvable URL so the CLI exits
 * non-zero with a descriptive message instead of silently feeding
 * the model an image-less request (the prior failure mode).
 */
internal fun ModelUseOffloadHandler.resolveImageUrl(url: String, sessionId: String?): LLMMessage.ImagePart {
    // data:<mime>;base64,<base64>
    if (url.startsWith("data:")) {
        val rest = url.substring(5)
        val semi = rest.indexOf(';')
        val comma = rest.indexOf(',')
        if (semi <= 0 || comma <= semi) {
            throw ImageInputError("Malformed data: URL — expected `data:<mime>;base64,<...>`")
        }
        val mime = rest.substring(0, semi)
        val b64 = rest.substring(comma + 1)
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw ImageInputError("data: URL base64 payload failed to decode: ${e.message}")
        }
        return LLMMessage.ImagePart(data = bytes, mimeType = mime)
    }

    // http(s):// — surface the iOS-parity message
    if (url.startsWith("http://") || url.startsWith("https://")) {
        throw ImageInputError(
            "http(s):// image URLs are not supported by minis-model-use. " +
                "Download first with `shell_execute` (curl/wget) into " +
                "/var/minis/workspace/, then reference the local path."
        )
    }

    // file:///guest/path → strip prefix and read through App-owned guest
    // storage. External SAF mounts retain their independent Android mapping.
    val linuxPath = when {
        url.startsWith("file://") -> url.removePrefix("file://")
        url.startsWith("/") -> url
        else -> throw ImageInputError(
            "Unsupported image_url '$url'. Use a data: URL, file:///guest/path, " +
                "/var/minis/<scope>/<path>, or an absolute Linux path."
        )
    }
    val bytes = try {
        if (linuxPath.startsWith("/var/minis/mounts/")) {
            runCatching { ExternalMountAccess.readBlocking(linuxPath) }.getOrElse {
                throw ImageInputError("Image file at '$url' is unreadable: ${it.message}")
            }
        } else {
            WorkspaceFileClient.readAllBlocking(sessionId.orEmpty(), linuxPath)
        }
    } catch (e: Throwable) {
        if (e is ImageInputError) throw e
        throw ImageInputError("Image file at '$url' is unreadable: ${e.message}")
    }
    return LLMMessage.ImagePart(
        data = bytes,
        mimeType = imageMimeForExtension(linuxPath),
        linuxPath = linuxPath,
    )
}

internal fun ModelUseOffloadHandler.imageMimeForExtension(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
}

internal fun ModelUseOffloadHandler.textMessage(
    role: String,
    content: String,
    audios: List<LLMMessage.AudioPart> = emptyList(),
): LLMMessage {
    val r = when (role.lowercase()) {
        "assistant" -> LLMMessage.Role.ASSISTANT
        else -> LLMMessage.Role.USER
    }
    return LLMMessage(role = r, content = content, audioParts = audios)
}
