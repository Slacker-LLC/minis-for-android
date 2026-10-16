package com.openminis.app.voicecall.realtime

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.voicecall.runtime.CallEvent
import com.openminis.app.voicecall.runtime.CallRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeSelectionTest {
    private fun instance(
        type: ProviderType = ProviderType.openAI,
        credential: ProviderCredential = ProviderCredential.apiKey,
        baseUrl: String? = null,
        azure: Boolean = false,
        enabled: Boolean = true,
    ) = ProviderInstance(id = "p1", label = "P", providerType = type, credentialType = credential, isEnabled = enabled, customBaseURL = baseUrl, azureMode = azure)

    private val on = RealtimeSelection(enabled = true, instanceId = "p1", profileId = "openai")

    @Test
    fun `official openai provider with a key resolves to the realtime endpoint`() {
        val c = resolveRealtime(on, instance(), "sk-1")!!
        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1", c.url)
        assertEquals("Bearer sk-1", c.headers["Authorization"])
    }

    @Test
    fun `gemini provider resolves for the gemini profile`() {
        val c = resolveRealtime(on.copy(profileId = "gemini"), instance(ProviderType.gemini), "g-key")
        assertNotNull(c)
        assertTrue(c!!.url.endsWith("key=g-key"))
    }

    @Test
    fun `xai key only goes to the official xai endpoint, other vendors accept any api key provider`() {
        val xai = on.copy(profileId = "xai")
        assertNotNull(resolveRealtime(xai, instance(ProviderType.xAI), "k"))
        assertNull(resolveRealtime(xai, instance(ProviderType.xAI, baseUrl = "https://relay.example.com"), "k"))
        assertNull(resolveRealtime(xai, instance(ProviderType.openAI), "k"))
        for (id in listOf("zhipu", "stepfun", "doubao")) {
            assertNotNull(id, resolveRealtime(on.copy(profileId = id), instance(ProviderType.openRouter), "k"))
            assertNull(id, resolveRealtime(on.copy(profileId = id), instance(ProviderType.openRouter, credential = ProviderCredential.oauth), "k"))
        }
    }

    @Test
    fun `engine off, missing provider or missing key never resolves`() {
        assertNull(resolveRealtime(on.copy(enabled = false), instance(), "k"))
        assertNull(resolveRealtime(on, null, "k"))
        assertNull(resolveRealtime(on, instance(), null))
        assertNull(resolveRealtime(on, instance(), "  "))
    }

    @Test
    fun `a key is never sent to an endpoint that is not its vendor`() {
        // 自定义 base URL / Azure 的 OpenAI key 不能发给 api.openai.com。
        assertNull(resolveRealtime(on, instance(baseUrl = "https://relay.example.com"), "k"))
        assertNull(resolveRealtime(on, instance(azure = true), "k"))
        // Gemini 的 key 不能用在 OpenAI 档案上，反之亦然。
        assertNull(resolveRealtime(on, instance(ProviderType.gemini), "k"))
        assertNull(resolveRealtime(on.copy(profileId = "gemini"), instance(ProviderType.openAI), "k"))
    }

    @Test
    fun `oauth, disabled, unsupported and stale selections are rejected`() {
        assertNull(resolveRealtime(on, instance(credential = ProviderCredential.oauth), "k"))
        assertNull(resolveRealtime(on, instance(enabled = false), "k"))
        assertNull(resolveRealtime(on.copy(profileId = "qwen", workspace = "w"), instance(ProviderType.unsupported), "k"))
        assertNull(resolveRealtime(on.copy(instanceId = "other"), instance(), "k"))
        assertNull(resolveRealtime(on.copy(profileId = "nope"), instance(), "k"))
    }

    @Test
    fun `qwen needs its workspace and custom needs url and model`() {
        val any = instance(ProviderType.openAI, baseUrl = "https://dashscope.example.com")
        assertNull(resolveRealtime(on.copy(profileId = "qwen"), any, "k"))
        assertNotNull(resolveRealtime(on.copy(profileId = "qwen", workspace = "ws1"), any, "k"))
        assertNull(resolveRealtime(on.copy(profileId = "custom_openai", customUrl = "wss://x/y"), any, "k"))
        assertNotNull(resolveRealtime(on.copy(profileId = "custom_openai", customUrl = "wss://x/y", model = "m"), any, "k"))
        assertNull(resolveRealtime(on.copy(profileId = "custom_openai", customUrl = "ws://x/y", model = "m"), any, "k"))
    }

    // ---- 引擎切换 ----

    private class FakeRuntime : CallRuntime {
        val flow = MutableSharedFlow<CallEvent>(extraBufferCapacity = 8)
        override val events: SharedFlow<CallEvent> = flow
        val calls = mutableListOf<String>()
        override fun start() { calls += "start"; flow.tryEmit(CallEvent.MuteChanged(false)) }
        override fun stop() { calls += "stop" }
        override fun setMuted(muted: Boolean) { calls += "mute$muted" }
        override fun interrupt() { calls += "interrupt" }
        override fun sendText(text: String) { calls += "text:$text" }
    }

    @Test
    fun `falls back to the classic pipeline when realtime is not configured`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val classic = FakeRuntime()
        val seen = mutableListOf<CallEvent>()
        val sw = EngineSwitchingCallRuntime(classic, { null }, scope)
        scope.launch { sw.events.collect { seen += it } }
        sw.start()
        scope.runCurrent()
        assertTrue("start" in classic.calls)
        assertEquals(listOf<CallEvent>(CallEvent.MuteChanged(false)), seen)
        sw.sendText("hi")
        assertTrue("text:hi" in classic.calls)
    }

    @Test
    fun `uses a fresh realtime runtime per call and only that one receives controls`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val classic = FakeRuntime()
        val made = mutableListOf<FakeRuntime>()
        val sw = EngineSwitchingCallRuntime(classic, { FakeRuntime().also { made += it } }, scope)
        val seen = mutableListOf<CallEvent>()
        scope.launch { sw.events.collect { seen += it } }
        sw.start(); sw.setMuted(true); sw.stop()
        sw.start(); sw.interrupt()
        scope.runCurrent()
        assertEquals(2, made.size)
        assertEquals(listOf("start", "mutetrue", "stop"), made[0].calls)
        assertEquals(listOf("start", "interrupt"), made[1].calls)
        assertTrue(classic.calls.none { it == "start" || it == "interrupt" })
        // 第一个运行时停掉后，它晚发的事件不会再转出来。
        val before = seen.size
        made[0].flow.tryEmit(CallEvent.MuteChanged(true))
        scope.runCurrent()
        assertEquals(before, seen.size)
        assertFalse(seen.isEmpty())
    }
}
