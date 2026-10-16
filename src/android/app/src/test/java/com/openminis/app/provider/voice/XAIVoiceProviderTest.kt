package com.openminis.app.provider.voice

import okhttp3.MultipartBody
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * xAI 语音请求形状，对照 docs.x.ai audio 文档（STT: `file` 必须是最后一个表单字段、无 response_format/prompt；
 * TTS: `POST /v1/tts`，`language` 必填，`voice_id` / `output_format` / `speed` 0.7–1.5）。
 * 真实 xAI 账号的朗读 / 听写**未验证**，这里只钉住请求形状。
 */
class XAIVoiceProviderTest {

    private val provider = XAIVoiceProvider("instance", "https://api.x.ai", "key")

    private fun json(request: Request): JSONObject =
        JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())

    private fun partNames(request: Request): List<String> =
        (request.body as MultipartBody).parts.map { part ->
            Regex("""name="([^"]+)"""").find(part.headers!!.get("Content-Disposition")!!)!!.groupValues[1]
        }

    private fun partText(request: Request, name: String): String? =
        (request.body as MultipartBody).parts.firstOrNull { part ->
            part.headers!!.get("Content-Disposition")!!.contains("name=\"$name\"")
        }?.body?.let { Buffer().also(it::writeTo).readUtf8() }

    private fun input(language: String? = "zh-CN", model: String? = null, prompt: String? = null) =
        VoiceInputRequest(
            audioData = byteArrayOf(1, 2, 3),
            model = model,
            language = language,
            prompt = prompt,
        )

    // ── STT ─────────────────────────────────────────────────────────────

    @Test
    fun `stt posts to v1 stt with the current default model`() {
        val request = provider.buildVoiceInputRequest(input())
        assertEquals("https://api.x.ai/v1/stt", request.url.toString())
        assertEquals("Bearer key", request.header("Authorization"))
        assertEquals("grok-voice-transcribe-2.0", partText(request, "model"))
    }

    @Test
    fun `stt sends the file as the LAST multipart field`() {
        val names = partNames(provider.buildVoiceInputRequest(input()))
        assertEquals("file", names.last())
        assertEquals(listOf("model", "language", "file"), names)
    }

    @Test
    fun `stt omits fields the xAI contract does not define`() {
        val request = provider.buildVoiceInputRequest(input(prompt = "domain terms"))
        val names = partNames(request)
        assertFalse(names.contains("response_format"))
        assertFalse(names.contains("prompt"))
    }

    @Test
    fun `stt reduces region tags to the primary language code and omits blank language`() {
        assertEquals("zh", partText(provider.buildVoiceInputRequest(input("zh-CN")), "language"))
        assertFalse(partNames(provider.buildVoiceInputRequest(input(language = null))).contains("language"))
    }

    @Test
    fun `stt keeps an explicitly chosen model`() {
        val request = provider.buildVoiceInputRequest(input(model = "grok-voice-transcribe-3.0"))
        assertEquals("grok-voice-transcribe-3.0", partText(request, "model"))
    }

    // ── TTS ─────────────────────────────────────────────────────────────

    @Test
    fun `tts posts to v1 tts with the required fields`() {
        val request = provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "你好"))
        val body = json(request)
        assertEquals("https://api.x.ai/v1/tts", request.url.toString())
        assertEquals("Bearer key", request.header("Authorization"))
        assertEquals("你好", body.getString("text"))
        assertEquals("eve", body.getString("voice_id"))
        // language 是必填；VoiceOutputRequest 没有语言，交给 xAI 自动识别。
        assertEquals("auto", body.getString("language"))
        assertEquals("mp3", body.getJSONObject("output_format").getString("codec"))
    }

    @Test
    fun `tts does not send OpenAI style fields`() {
        val body = json(provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "hi", model = "grok-tts-1")))
        for (field in listOf("model", "input", "voice", "response_format")) {
            assertFalse("unexpected $field", body.has(field))
        }
    }

    @Test
    fun `tts honours voice, wav and clamps speed to the documented range`() {
        fun speedBody(speed: Float) = json(
            provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "hi", speed = speed)),
        )
        assertEquals(1.2, speedBody(1.2f).getDouble("speed"), 1e-6)
        assertEquals(0.7, speedBody(0.25f).getDouble("speed"), 1e-6)
        assertEquals(1.5, speedBody(4.0f).getDouble("speed"), 1e-6)
        assertFalse(json(provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "hi"))).has("speed"))

        val custom = json(
            provider.buildVoiceOutputRequest(
                VoiceOutputRequest(input = "hi", voice = "ara", responseFormat = VoiceOutputFormat.WAV),
            ),
        )
        assertEquals("ara", custom.getString("voice_id"))
        assertEquals("wav", custom.getJSONObject("output_format").getString("codec"))
    }

    @Test
    fun `tts falls back to mp3 for codecs xAI does not offer`() {
        for (format in listOf(VoiceOutputFormat.OPUS, VoiceOutputFormat.AAC)) {
            val body = json(provider.buildVoiceOutputRequest(VoiceOutputRequest(input = "hi", responseFormat = format)))
            assertEquals("mp3", body.getJSONObject("output_format").getString("codec"))
        }
    }

    @Test
    fun `a custom base url that already ends in v1 does not double the version`() {
        val custom = XAIVoiceProvider("instance", "https://proxy.example/v1", "key")
        assertEquals("https://proxy.example/v1/tts", custom.buildVoiceOutputRequest(VoiceOutputRequest("hi")).url.toString())
        assertTrue(custom.buildVoiceInputRequest(input()).url.toString().endsWith("/v1/stt"))
    }
}
