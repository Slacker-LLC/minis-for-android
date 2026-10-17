package com.openminis.app.runtime.guest

import org.json.JSONObject

/**
 * When a `minis-model-use run` that had to produce media came back without any, it has failed: the
 * caller asked for a `.png`/`.mp3`/... file, or the model can only output images. Exiting 0 and
 * writing the model's text reply into the requested file leaves the agent holding an "image" that
 * cannot be opened and a success to report to the user.
 */
internal object ModelUseMediaPolicy {
    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "heic")
    private val AUDIO_EXTS = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac")
    private val VIDEO_EXTS = setOf("mp4", "mov", "webm", "mkv")

    fun isImageExt(ext: String): Boolean = ext in IMAGE_EXTS
    fun isAudioExt(ext: String): Boolean = ext in AUDIO_EXTS
    fun isVideoExt(ext: String): Boolean = ext in VIDEO_EXTS

    fun isMediaExt(ext: String): Boolean = isImageExt(ext) || isAudioExt(ext) || isVideoExt(ext)

    /** [outputs] is the model's advertised output modalities. */
    fun mediaRequired(outputPath: String?, outputExt: String, outputs: Collection<String>): Boolean =
        (outputPath != null && isMediaExt(outputExt)) || ("image" in outputs && "text" !in outputs)

    /** Exit 1 with a machine-readable error and the model's own text (usually the refusal reason). */
    fun noMediaResult(modelId: String, endpoint: String, text: String, outputPath: String?): NativeOffloadResult {
        val body = JSONObject().apply {
            put("error", "no_media_generated")
            put(
                "message",
                "The model returned no image/media (often a content-policy refusal or quota limit). " +
                    "Nothing was saved" + (outputPath?.let { " to $it" } ?: "") +
                    ". Do not reference a generated file; tell the user it failed and why.",
            )
            put("model", modelId)
            put("endpoint", endpoint)
            if (text.isNotBlank()) put("model_text", text.take(2000))
        }
        return NativeOffloadResult(1, body.toString(2) + "\n")
    }
}
