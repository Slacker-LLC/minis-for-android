package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.runtime.ToolExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

/** Real WebView CookieManager reached from model and guest bridge with documented input forms. */
@RunWith(AndroidJUnit4::class)
class AgentBrowserCompatibilityE2ETest {
    @Test
    fun cookieArraysAndStringFormsUseTheSameBrowser(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sid = app.chatRepository.createSession("scripted-model", title = "M3 browser compatibility", memoryEnabled = false).id
        val cliSid = app.chatRepository.createSession("scripted-model", title = "M3 CLI browser compatibility", memoryEnabled = false).id
        val name = "m3cookie_${UUID.randomUUID().toString().replace("-", "") }"
        val cookies = JSONArray().put(JSONObject().put("name", name).put("value", "verified"))
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(label: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", label).put("passed", true)) }
        val fixture = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val fixtureThread = thread(name = "cookie-http-e2e", isDaemon = true) {
            while (!fixture.isClosed) {
                val socket = try { fixture.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    reader.readLine(); while (!reader.readLine().isNullOrEmpty()) { }
                    val bytes = "<html><body>Cookie compatibility</body></html>".toByteArray()
                    it.getOutputStream().use { out ->
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(bytes)
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${fixture.localPort}"
        fun cli(args: List<String>): JSONObject {
            val result = GuestCommandBridge.dispatch("minis-browser-use", listOf("--compact") + args, cliSid, "/workspace", "")
            assertEquals(result.output, 0, result.exitCode)
            return JSONObject(result.output).getJSONObject("data")
        }
        try {
            // First exercise the real guest parser: --cookies becomes an actual JSONArray.
            cli(listOf("navigate", "--url", url))
            cli(listOf("set_cookies", "--cookies", cookies.toString()))
            val cliGet = cli(listOf("--json", JSONObject().put("action", "get_cookies").put("keywords", JSONArray().put(name)).put("fuzzy", false).toString()))
            assertTrue(cliGet.getString("text").contains("$name = verified"))
            passed("guest --cookies array and JSON keyword array reach the real CookieManager without a schema rejection")
            val provider = ScriptedProvider { call, history, _ ->
                val results = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                when (call) {
                    1 -> toolCall("cookie-nav", "browser_use", JSONObject().put("action", "navigate").put("url", url))
                    2 -> toolCall("cookie-set", "browser_use", JSONObject().put("action", "set_cookies").put("cookies", cookies))
                    3 -> { assertFalse(results.single { it.id == "cookie-set" }.content, results.single { it.id == "cookie-set" }.isError)
                        toolCall("cookie-get", "browser_use", JSONObject().put("action", "get_cookies").put("keywords", JSONArray().put(name)).put("fuzzy", false)) }
                    else -> { val result = results.single { it.id == "cookie-get" }; assertFalse(result.isError); assertTrue(result.content.contains("$name = verified")); text("实际 Cookie 已核验。") }
                }
            }
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "cookie-scripted-entry" }
            assertTrue(AgentRunner.prompt(app, sid, "写入并检索浏览器 Cookie", wait = true, timeoutMs = 45_000).completed)
            passed("model cookie/keyword arrays complete a real browser task and return successful paired results")
            val stringSet = ToolExecutor.execute("browser_use", JSONObject().put("action", "set_cookies").put("cookies", cookies.toString()).toString(), sid, app)
            assertTrue(stringSet.output, stringSet.success)
            val stringGet = ToolExecutor.execute("browser_use", JSONObject().put("action", "get_cookies").put("keywords", name).put("fuzzy", false).toString(), sid, app)
            assertTrue(stringGet.success); assertTrue(stringGet.output.contains("$name = verified"))
            passed("existing JSON-string cookies and space-separated keyword inputs remain compatible")
            val invalid = ToolExecutor.execute("browser_use", JSONObject().put("action", "set_cookies").put("cookies", JSONArray().put("invalid object")).toString(), sid, app)
            assertFalse(invalid.success); assertTrue(invalid.output.contains("invalid_arguments"))
            val retained = ToolExecutor.execute("browser_use", JSONObject().put("action", "get_cookies").put("keywords", JSONArray().put(name)).toString(), sid, app)
            assertTrue(retained.output.contains("$name = verified"))
            passed("invalid cookie array items are rejected before mutation and retain the previously stored value")
            report.put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            try { cli(listOf("set_cookies", "--cookies", JSONArray().put(JSONObject().put("name", name).put("value", "").put("expires", 1)).toString())) } catch (_: Throwable) { }
            fixture.close(); fixtureThread.join(6_000)
            for (session in listOf(sid, cliSid)) { AgentRunner.cancel(app, session); app.chatRepository.deleteSession(session) }
            File(app.filesDir, "agent-browser-compatibility-e2e.json").writeText(report.toString(2))
        }
    }
}
