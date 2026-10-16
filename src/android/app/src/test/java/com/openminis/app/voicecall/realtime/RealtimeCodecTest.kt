package com.openminis.app.voicecall.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RealtimeCodecTest {
    private fun b64(vararg b: Int) = Base64.getEncoder().encodeToString(ByteArray(b.size) { b[it].toByte() })
    private val cfgTools = RealtimeSessionConfig("m", "be brief", "marin", delegateByTool = true)
    private val cfgItems = cfgTools.copy(delegateByTool = false)

    // ---- OpenAI GA ----
    private val openai get() = RealtimeProfiles.OPENAI.newCodec()

    @Test
    fun `openai session update uses the GA shape and registers the delegate tool`() {
        val s = JSONObject(openai.open(cfgTools).single()).getJSONObject("session")
        assertEquals("realtime", s.getString("type"))
        assertEquals("audio/pcm", s.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(24000, s.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("marin", s.getJSONObject("audio").getJSONObject("output").getString("voice"))
        val td = s.getJSONObject("audio").getJSONObject("input").getJSONObject("turn_detection")
        assertTrue(td.getBoolean("create_response")); assertTrue(td.getBoolean("interrupt_response"))
        assertEquals(DELEGATE_TOOL_NAME, s.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun `without tools the model must not answer on its own and no tool is registered`() {
        val s = JSONObject(openai.open(cfgItems).single()).getJSONObject("session")
        assertTrue(!s.has("tools"))
        assertEquals(false, s.getJSONObject("audio").getJSONObject("input").getJSONObject("turn_detection").getBoolean("create_response"))
    }

    @Test
    fun `openai events are normalised`() {
        val c = openai
        assertEquals(listOf(RealtimeEvent.Ready), c.parse("""{"type":"session.updated"}"""))
        val audio = c.parse("""{"type":"response.output_audio.delta","delta":"${b64(1, 2, 3, 4)}"}""").single() as RealtimeEvent.AudioOut
        assertEquals(listOf<Byte>(1, 2, 3, 4), audio.pcm.toList())
        assertEquals(listOf(RealtimeEvent.UserSpeechStarted), c.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(
            listOf(RealtimeEvent.UserTranscript("你好")),
            c.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":" 你好 "}"""),
        )
        assertEquals(
            listOf(RealtimeEvent.AssistantText("好的", true)),
            c.parse("""{"type":"response.output_audio_transcript.done","transcript":"好的"}"""),
        )
        assertEquals(listOf(RealtimeEvent.Failure("bad key")), c.parse("""{"type":"error","error":{"message":"bad key"}}"""))
        assertTrue(c.parse("not json").isEmpty())
        assertTrue(c.parse("""{"type":"something.new"}""").isEmpty())
    }

    @Test
    fun `a tool call seen twice is reported once and the turn still completes`() {
        val c = openai
        val args = c.parse("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"x\"}"}""")
        assertEquals(RealtimeEvent.ToolCall("c1", DELEGATE_TOOL_NAME, "{\"request\":\"x\"}"), args.single())
        val done = c.parse("""{"type":"response.done","response":{"output":[{"type":"function_call","call_id":"c1","name":"$DELEGATE_TOOL_NAME","arguments":"{}"}]}}""")
        assertEquals(listOf(RealtimeEvent.ResponseDone), done)
    }

    @Test
    fun `a tool call only present in response done is still reported`() {
        val done = openai.parse("""{"type":"response.done","response":{"output":[{"type":"function_call","call_id":"c2","name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"y\"}"}]}}""")
        assertEquals(2, done.size)
        assertEquals("c2", (done[0] as RealtimeEvent.ToolCall).callId)
        assertEquals(RealtimeEvent.ResponseDone, done[1])
    }

    @Test
    fun `openai tool result and backend item messages`() {
        val r = openai.toolResult("c1", DELEGATE_TOOL_NAME, "done").map { JSONObject(it) }
        assertEquals("function_call_output", r[0].getJSONObject("item").getString("type"))
        assertEquals("c1", r[0].getJSONObject("item").getString("call_id"))
        assertEquals("response.create", r[1].getString("type"))
        val b = openai.backendItem("[BACKEND] hi").map { JSONObject(it) }
        assertEquals("user", b[0].getJSONObject("item").getString("role"))
        assertEquals("[BACKEND] hi", b[0].getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("response.create", b[1].getString("type"))
    }

    @Test
    fun `audio in is base64 of the pcm bytes`() {
        val m = JSONObject(openai.audioIn(byteArrayOf(9, 8, 7)))
        assertEquals("input_audio_buffer.append", m.getString("type"))
        assertEquals(listOf<Byte>(9, 8, 7), Base64.getDecoder().decode(m.getString("audio")).toList())
    }

    // ---- Qwen dialect ----
    @Test
    fun `qwen uses its own session shape and event names`() {
        val c = RealtimeProfiles.QWEN.newCodec()
        val s = JSONObject(c.open(cfgTools.copy(voice = "longanlingxin")).single()).getJSONObject("session")
        assertTrue(!s.has("type"))
        assertEquals("longanlingxin", s.getString("voice"))
        assertEquals(16000, s.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getInt("sample_rate"))
        assertEquals("qwen3-asr-flash-realtime", s.getJSONObject("input_audio_transcription").getString("model"))
        val a = c.parse("""{"type":"response.audio.delta","delta":"${b64(5, 6)}"}""").single() as RealtimeEvent.AudioOut
        assertEquals(2, a.pcm.size)
        assertEquals(listOf(RealtimeEvent.AssistantText("嗯", true)), c.parse("""{"type":"response.audio_transcript.done","transcript":"嗯"}"""))
    }

    // ---- Gemini ----
    private val gemini get() = RealtimeProfiles.GEMINI.newCodec()

    @Test
    fun `gemini setup carries model audio modality instructions and the tool`() {
        val s = JSONObject(gemini.open(cfgTools.copy(model = "gemini-x", voice = "Puck")).single()).getJSONObject("setup")
        assertEquals("models/gemini-x", s.getString("model"))
        assertEquals("AUDIO", s.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertEquals("Puck", s.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
        assertEquals("be brief", s.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(DELEGATE_TOOL_NAME, s.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertTrue(s.has("inputAudioTranscription") && s.has("outputAudioTranscription"))
    }

    @Test
    fun `gemini audio in declares the sample rate`() {
        val m = JSONObject(gemini.audioIn(byteArrayOf(1, 2))).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", m.getString("mimeType"))
    }

    @Test
    fun `gemini turn flows into user text audio assistant text and done`() {
        val c = gemini
        assertEquals(listOf(RealtimeEvent.Ready), c.parse("""{"setupComplete":{}}"""))
        assertTrue(c.parse("""{"serverContent":{"inputTranscription":{"text":"帮我"}}}""").isEmpty())
        assertTrue(c.parse("""{"serverContent":{"inputTranscription":{"text":"看看"}}}""").isEmpty())
        val first = c.parse("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${b64(1, 2)}"}}]}}}""")
        assertEquals(RealtimeEvent.UserTranscript("帮我看看"), first[0])
        assertTrue(first[1] is RealtimeEvent.AudioOut)
        c.parse("""{"serverContent":{"outputTranscription":{"text":"好的，"}}}""")
        c.parse("""{"serverContent":{"outputTranscription":{"text":"马上。"}}}""")
        val done = c.parse("""{"serverContent":{"turnComplete":true}}""")
        assertEquals(listOf(RealtimeEvent.AssistantText("好的，马上。", true), RealtimeEvent.ResponseDone), done)
    }

    @Test
    fun `gemini interruption and tool call`() {
        val c = gemini
        assertEquals(listOf(RealtimeEvent.UserSpeechStarted), c.parse("""{"serverContent":{"interrupted":true}}"""))
        val call = c.parse("""{"toolCall":{"functionCalls":[{"id":"f1","name":"$DELEGATE_TOOL_NAME","args":{"request":"查天气"}}]}}""").single() as RealtimeEvent.ToolCall
        assertEquals("f1", call.callId)
        assertEquals("查天气", JSONObject(call.argsJson).getString("request"))
        val r = JSONObject(c.toolResult("f1", DELEGATE_TOOL_NAME, "晴").single()).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("f1", r.getString("id"))
        assertEquals("晴", r.getJSONObject("response").getString("result"))
    }

    // ---- xAI ----
    @Test
    fun `xai session keeps voice and vad on the root and audio format in the GA shape`() {
        val c = RealtimeProfiles.XAI.newCodec()
        val s = JSONObject(c.open(cfgTools.copy(voice = "eve")).single()).getJSONObject("session")
        assertTrue(!s.has("type"))
        assertEquals("eve", s.getString("voice"))
        assertEquals("server_vad", s.getJSONObject("turn_detection").getString("type"))
        assertEquals("audio/pcm", s.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(24000, s.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("grok-transcribe", s.getJSONObject("audio").getJSONObject("input").getJSONObject("transcription").getString("model"))
        val tool = s.getJSONArray("tools").getJSONObject(0)
        assertEquals(DELEGATE_TOOL_NAME, tool.getString("name"))
        assertTrue(!s.has("tool_choice"))
    }

    @Test
    fun `xai function call is paired by call id`() {
        val c = RealtimeProfiles.XAI.newCodec()
        val call = c.parse("""{"type":"response.function_call_arguments.done","call_id":"x1","name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"a\"}"}""").single() as RealtimeEvent.ToolCall
        assertEquals("x1", call.callId)
        val r = c.toolResult("x1", DELEGATE_TOOL_NAME, "ok").map { JSONObject(it) }
        assertEquals("x1", r[0].getJSONObject("item").getString("call_id"))
        assertEquals("response.create", r[1].getString("type"))
    }

    // ---- Zhipu ----
    @Test
    fun `zhipu session uses flat audio formats and a flat tool`() {
        val c = RealtimeProfiles.ZHIPU.newCodec()
        val s = JSONObject(c.open(cfgTools.copy(model = "glm-realtime", voice = "tongtong")).single()).getJSONObject("session")
        assertEquals("pcm16", s.getString("input_audio_format"))
        assertEquals("pcm", s.getString("output_audio_format"))
        assertEquals("glm-realtime", s.getString("model"))
        assertEquals("audio", s.getJSONObject("beta_fields").getString("chat_mode"))
        assertEquals("server_vad", s.getJSONObject("turn_detection").getString("type"))
        assertEquals(DELEGATE_TOOL_NAME, s.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun `zhipu function call has no call id so one is synthesised and not echoed back`() {
        val c = RealtimeProfiles.ZHIPU.newCodec()
        val call = c.parse("""{"type":"response.function_call_arguments.done","response_id":"r9","output_index":1,"name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"a\"}"}""").single() as RealtimeEvent.ToolCall
        assertEquals("r9:1", call.callId)
        val r = c.toolResult(call.callId, DELEGATE_TOOL_NAME, "ok").map { JSONObject(it) }
        val item = r[0].getJSONObject("item")
        assertEquals("function_call_output", item.getString("type"))
        assertTrue(!item.has("call_id"))
        assertEquals("ok", item.getString("output"))
        assertEquals("response.create", r[1].getString("type"))
        // 没有 response_id 的残缺事件不能凭空造出调用。
        assertTrue(c.parse("""{"type":"response.function_call_arguments.done","name":"$DELEGATE_TOOL_NAME","arguments":"{}"}""").isEmpty())
    }

    // ---- StepFun ----
    @Test
    fun `stepfun nests the tool under function and has no create_response`() {
        val c = RealtimeProfiles.STEPFUN.newCodec()
        val s = JSONObject(c.open(cfgTools).single()).getJSONObject("session")
        assertEquals("pcm16", s.getString("input_audio_format"))
        assertEquals("pcm16", s.getString("output_audio_format"))
        assertTrue(!s.getJSONObject("turn_detection").has("create_response"))
        val tool = s.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals(DELEGATE_TOOL_NAME, tool.getJSONObject("function").getString("name"))
        assertTrue(!tool.has("name"))
    }

    // ---- Doubao ----
    private val doubao get() = RealtimeProfiles.DOUBAO.newCodec()

    @Test
    fun `doubao session create carries the fixed model, 16k pcm in, 24k s16le out and a flat tool`() {
        val m = JSONObject(doubao.open(cfgTools.copy(model = "1.2.6.1", voice = "zh_female_vv_uranus_bigtts")).single())
        assertEquals("session.create", m.getString("type"))
        val s = m.getJSONObject("session")
        assertEquals("1.2.6.1", s.getString("model"))
        assertEquals(16000, s.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getInt("rate"))
        assertEquals("pcm_s16le", s.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getString("type"))
        assertEquals("zh_female_vv_uranus_bigtts", s.getJSONObject("audio").getJSONObject("output").getString("voice"))
        assertEquals(DELEGATE_TOOL_NAME, s.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun `doubao events are normalised`() {
        val c = doubao
        assertEquals(listOf(RealtimeEvent.Ready), c.parse("""{"type":"session.created","session":{"id":"d1"}}"""))
        val a = c.parse("""{"type":"response.output_audio.delta","delta":"${b64(1, 2)}"}""").single() as RealtimeEvent.AudioOut
        assertEquals(2, a.pcm.size)
        assertEquals(listOf(RealtimeEvent.UserSpeechStarted), c.parse("""{"type":"conversation.item.input_audio_transcription.started"}"""))
        assertEquals(
            listOf(RealtimeEvent.UserTranscript("你好")),
            c.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}"""),
        )
        c.parse("""{"type":"response.output_text.delta","delta":"好的，"}""")
        c.parse("""{"type":"response.output_text.delta","delta":"马上。"}""")
        assertEquals(listOf(RealtimeEvent.AssistantText("好的，马上。", true)), c.parse("""{"type":"response.output_text.done"}"""))
        assertEquals(listOf(RealtimeEvent.ResponseDone), c.parse("""{"type":"response.done"}"""))
        assertEquals(listOf(RealtimeEvent.Failure("bad")), c.parse("""{"type":"error","message":"bad"}"""))
        assertTrue(c.parse("""{"type":"response.canceled"}""").isEmpty())
    }

    @Test
    fun `doubao function calls come as items and the result goes back as a tool item`() {
        val c = doubao
        val calls = c.parse("""{"type":"response.function_call_arguments.done","items":[{"call_id":"a","name":"$DELEGATE_TOOL_NAME","arguments":"{\"request\":\"1\"}"},{"call_id":"b","name":"$DELEGATE_TOOL_NAME","arguments":"{}"}]}""")
        assertEquals(listOf("a", "b"), calls.map { (it as RealtimeEvent.ToolCall).callId })
        val r = c.toolResult("a", DELEGATE_TOOL_NAME, "done").map { JSONObject(it) }
        assertEquals(1, r.size)
        val item = r.single().getJSONArray("items").getJSONObject(0)
        assertEquals("a", item.getString("call_id"))
        assertEquals("tool", item.getString("role"))
        assertEquals("done", item.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun `doubao needs mute keep alive, a graceful close and speaks notes directly`() {
        val c = doubao
        assertEquals("input_audio_mute.commit", JSONObject(c.muteSignal(true).single()).getString("type"))
        assertEquals("input_audio_unmute.commit", JSONObject(c.muteSignal(false).single()).getString("type"))
        assertEquals("session.close", JSONObject(c.closeSession().single()).getString("type"))
        val say = JSONObject(c.speak("要允许删除吗")!!.single())
        assertEquals("speech_text_buffer.commit", say.getString("type"))
        assertEquals("要允许删除吗", say.getString("text"))
        // 其他协议没有这些事件，也不用 speak。
        assertTrue(openai.muteSignal(true).isEmpty() && openai.closeSession().isEmpty() && openai.speak("x") == null)
    }

    @Test
    fun `new profiles build their connection from the key alone`() {
        val x = RealtimeProfiles.connection(RealtimeUserConfig("xai", "k"))!!
        assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-latest", x.url)
        assertEquals("Bearer k", x.headers["Authorization"])
        assertEquals("eve", x.voice)
        val z = RealtimeProfiles.connection(RealtimeUserConfig("zhipu", "k"))!!
        assertEquals("wss://open.bigmodel.cn/api/paas/v4/realtime", z.url)
        assertEquals("glm-realtime", z.model)
        val s = RealtimeProfiles.connection(RealtimeUserConfig("stepfun", "k"))!!
        assertEquals("wss://api.stepfun.com/v1/realtime?model=stepaudio-3-realtime-preview", s.url)
        val d = RealtimeProfiles.connection(RealtimeUserConfig("doubao", "dk"))!!
        assertEquals("wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue", d.url)
        assertEquals("dk", d.headers["X-Api-Key"])
        assertTrue(!d.headers.containsKey("Authorization"))
        assertEquals("1.2.6.1", d.model)
    }

    // ---- shared ----
    @Test
    fun `backend output is prefixed and bounded`() {
        assertEquals("[BACKEND] ok", backendOutput("  ok "))
        val long = backendOutput("x".repeat(10_000))
        assertTrue(long.length <= BACKEND_OUTPUT_MAX_CHARS)
        assertTrue(long.startsWith(BACKEND_PREFIX) && long.endsWith("…"))
    }

    // ---- profiles ----
    @Test
    fun `connection is built from what the user filled in`() {
        val o = RealtimeProfiles.connection(RealtimeUserConfig("openai", " sk-1 "))!!
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1", o.url)
        assertEquals("Bearer sk-1", o.headers["Authorization"])
        val g = RealtimeProfiles.connection(RealtimeUserConfig("gemini", "K E"))!!
        assertTrue(g.url.endsWith("BidiGenerateContent?key=K+E"))
        assertTrue(g.headers.isEmpty())
        val q = RealtimeProfiles.connection(RealtimeUserConfig("qwen", "k", workspace = "ws123"))!!
        assertTrue(q.url.startsWith("wss://ws123.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3.8"))
    }

    @Test
    fun `incomplete or unsafe settings are refused`() {
        assertEquals("apiKey", RealtimeProfiles.missing(RealtimeUserConfig("openai", " ")))
        assertEquals("workspace", RealtimeProfiles.missing(RealtimeUserConfig("qwen", "k")))
        assertEquals("customUrl", RealtimeProfiles.missing(RealtimeUserConfig("custom_openai", "k")))
        assertEquals("model", RealtimeProfiles.missing(RealtimeUserConfig("custom_openai", "k", customUrl = "wss://x/y")))
        assertEquals("profile", RealtimeProfiles.missing(RealtimeUserConfig("nope", "k")))
        assertNull(RealtimeProfiles.connection(RealtimeUserConfig("qwen", "k")))
        // 明文 ws:// 会把 key 暴露在链路上，一律拒绝。
        assertNull(RealtimeProfiles.connection(RealtimeUserConfig("custom_openai", "k", model = "m", customUrl = "ws://example.com/rt")))
        assertNotNull(RealtimeProfiles.connection(RealtimeUserConfig("custom_openai", "k", model = "m", customUrl = "wss://example.com/rt?model={model}")))
    }

    @Test
    fun `every profile can build its codec`() {
        RealtimeProfiles.ALL.forEach { assertNotNull(it.newCodec()) }
        assertTrue(RealtimeProfiles.ALL.map { it.id }.toSet().size == RealtimeProfiles.ALL.size)
    }
}
