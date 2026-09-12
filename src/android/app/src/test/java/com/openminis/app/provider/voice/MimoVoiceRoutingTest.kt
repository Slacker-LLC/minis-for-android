package com.openminis.app.provider.voice

import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MimoVoiceRoutingTest {

    private fun bodyOf(request: okhttp3.Request): JSONObject = Buffer().also { buffer ->
        request.body!!.writeTo(buffer)
    }.readUtf8().let(::JSONObject)

    @Test
    fun `base tts model id is not sent as a voice id`() {
        val provider = MimoVoiceProvider("instance", "https://api.xiaomimimo.com/v1", "key")
        val request = provider.buildVoiceOutputRequest(
            VoiceOutputRequest(
                input = "hello",
                model = "mimo-v2.5-tts",
                voice = "mimo-v2.5-tts",
                responseFormat = VoiceOutputFormat.WAV,
            ),
        )
        val body = bodyOf(request)

        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url.toString())
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        assertEquals("mimo_default", body.getJSONObject("audio").getString("voice"))
    }

    @Test
    fun `explicit built in voice is preserved`() {
        val provider = MimoVoiceProvider("instance", "https://api.xiaomimimo.com/v1", "key")
        val request = provider.buildVoiceOutputRequest(
            VoiceOutputRequest(
                input = "hello",
                model = "mimo-v2.5-tts",
                voice = "Mia",
                responseFormat = VoiceOutputFormat.WAV,
            ),
        )

        assertEquals("Mia", bodyOf(request).getJSONObject("audio").getString("voice"))
    }
}
