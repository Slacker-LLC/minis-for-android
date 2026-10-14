package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.agent.setMemoryEnabledForSession
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.guest.GuestCommandBridge
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.BrowserTools
import com.openminis.app.tools.runtime.ToolExecutor
import com.openminis.app.tools.runtime.ToolRegistry
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

/** Actual model session, WebView, guest command handler, HTTP MCP, memory and filesystem. */
@RunWith(AndroidJUnit4::class)
class AgentStatefulToolsE2ETest {
    @Test
    fun modelGuestAndRemoteUseOnePublishedContract(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val marker = "m3-stateful-${UUID.randomUUID()}"
        val memory = "## $marker\n真实工具记忆写入与检索验收。"
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(enabled: Boolean = true): String = app.chatRepository.createSession("scripted-model",
            title = "M3 stateful E2E", memoryEnabled = enabled).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider): ChatViewModel {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) {
                vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "stateful-scripted-entry"
            }
            return vm
        }
        val fixture = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val fixtureThread = thread(name = "stateful-browser-http", isDaemon = true) {
            while (!fixture.isClosed) {
                val socket = try { fixture.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val binary = request.substringAfter(' ').startsWith("/artifact")
                    val body = if (binary) bytes else "<html><body><h1>$marker</h1></body></html>".toByteArray()
                    val mime = if (binary) "application/octet-stream" else "text/html; charset=utf-8"
                    it.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body)
                    }
                }
            }
        }
        val base = "http://127.0.0.1:${fixture.localPort}"
        try {
            assertNotNull(ToolRegistry.handler("browser_use")); assertNotNull(ToolRegistry.handler("memory_write")); assertNotNull(ToolRegistry.handler("memory_get"))
            assertFalse(AgentTools.makeAgentTools(memoryEnabled = false, supportsImageInput = false).any { it.name in setOf("memory_write", "memory_get") })
            passed("stateful tools are registered and disabling memory still withholds both model definitions")
            val sid = session()
            val provider = ScriptedProvider { call, history, _ ->
                val results = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                when (call) {
                    1 -> toolCall("stateful-write", "memory_write", JSONObject().put("content", memory).put("tool_title", "保存验收记忆"))
                    2 -> { assertFalse(results.single { it.id == "stateful-write" }.isError)
                        toolCall("stateful-get", "memory_get", JSONObject().put("keywords", marker).put("scope", "daily")) }
                    else -> { assertTrue(results.single { it.id == "stateful-get" }.content.contains(marker)); text("记忆已核验。") }
                }
            }
            val vm = prepare(sid, provider)
            assertTrue(AgentRunner.prompt(app, sid, "保存并检索这次验收的记忆", wait = true, timeoutMs = 25_000).completed)
            assertEquals(2, vm.memoryToolRecords.value.size)
            assertTrue(app.memoryRepository.getMemory(marker, "daily").contains(marker))
            passed("model writes and retrieves real repository memory and keeps both UI records in its existing session")
            withContext(Dispatchers.Main) { AgentSessionStore.existing(sid)!!.setMemoryEnabledForSession(false) }
            val deniedMemory = memory + "\n不应写入"
            val denied = ToolExecutor.execute("memory_write", JSONObject().put("content", deniedMemory).toString(), sid, app)
            assertFalse(denied.success); assertFalse(app.memoryRepository.getMemory(marker, "daily").contains("不应写入"))
            assertTrue(ToolExecutor.execute("memory_get", JSONObject().put("keywords", marker).toString(), sid, app).success)
            val disabled = session(false)
            assertNull(AgentSessionStore.existing(disabled))
            assertFalse(ToolExecutor.execute("memory_write", JSONObject().put("content", deniedMemory).toString(), disabled, app).success)
            assertNull(AgentSessionStore.existing(disabled))
            passed("both existing and headless disabled sessions reject memory writes without constructing a new execution owner; reads remain available")
            val readonly = session()
            val readonlyMemory = OffloadPermissionManager.withUnattendedSession(readonly, ScheduledTaskPermissionTier.READ_ONLY) {
                ToolExecutor.execute("memory_write", JSONObject().put("content", deniedMemory).toString(), readonly, app)
            }
            assertFalse(readonlyMemory.success); assertFalse(app.memoryRepository.getMemory(marker, "daily").contains("不应写入"))
            passed("READ_ONLY scope rejects shared memory mutation without side effects")

            val browserSid = session(false)
            var modelPath: String? = null
            val browserProvider = ScriptedProvider { call, history, _ ->
                val results = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                when (call) {
                    1 -> toolCall("stateful-nav", "browser_use", JSONObject().put("action", "navigate").put("url", base))
                    2 -> { assertFalse(results.single { it.id == "stateful-nav" }.isError)
                        toolCall("stateful-fetch", "browser_use", JSONObject().put("action", "fetch").put("url", "$base/artifact")) }
                    else -> {
                        val fetched = results.single { it.id == "stateful-fetch" }
                        assertFalse(fetched.content, fetched.isError)
                        val minis = fetched.content.lineSequence().single { it.startsWith("minis_url: ") }.substringAfter("minis_url: ")
                        modelPath = "/var/minis/browser/" + java.net.URLDecoder.decode(minis.substringAfter("minis://browser/"), "UTF-8")
                        text("真实浏览器文件已下载并保存。")
                    }
                }
            }
            prepare(browserSid, browserProvider)
            assertTrue(AgentRunner.prompt(app, browserSid, "打开验收网页并下载资料", wait = true, timeoutMs = 70_000).completed)
            assertArrayEquals(bytes, WorkspaceFileClient.readAll(browserSid, checkNotNull(modelPath)))
            passed("model browser uses a real WebView and returns a resolvable artifact containing the exact HTTP payload")
            val cliSid = session(false)
            fun cli(action: String, extra: List<String> = emptyList()): JSONObject {
                val result = GuestCommandBridge.dispatch("minis-browser-use", listOf("--compact", action) + extra, cliSid, "/workspace", "")
                assertEquals(result.output, 0, result.exitCode)
                return JSONObject(result.output).getJSONObject("data")
            }
            cli("navigate", listOf("--url", base))
            assertTrue(cli("get_text").getString("text").contains(marker))
            val fetched = cli("fetch", listOf("--url", "$base/artifact", "--with-base64"))
            val cliPath = fetched.getString("fetched_path")
            assertArrayEquals(bytes, WorkspaceFileClient.readAll(cliSid, cliPath))
            assertFalse(fetched.has("image_base64"))
            assertEquals(BrowserTools.linuxPathToMinisURL(cliPath), fetched.getString("fetched_minis_url"))
            assertFalse(WorkspaceFileClient.info(cliSid, modelPath!!).optBoolean("exists"))
            assertFalse(WorkspaceFileClient.info(browserSid, cliPath).optBoolean("exists"))
            assertNull(AgentSessionStore.existing(cliSid))
            passed("guest bridge preserves serial tab navigation and complete native JSON while download files stay in their own sessions")
            val screenshot = cli("screenshot", listOf("--with-base64"))
            val screenshotBytes = android.util.Base64.decode(screenshot.getString("image_base64"), android.util.Base64.DEFAULT)
            assertTrue(screenshotBytes.isNotEmpty())
            assertArrayEquals(screenshotBytes, WorkspaceFileClient.readAll(cliSid, screenshot.getString("image_path")))
            assertEquals(BrowserTools.linuxPathToMinisURL(screenshot.getString("image_path")), screenshot.getString("minis_url"))
            passed("native screenshot keeps complete base64 matching its real persisted image and resource URL")
            val cancelled = async(Dispatchers.Default) {
                ToolExecutor.execute("browser_use", JSONObject().put("action", "execute_js")
                    .put("script", "await new Promise(resolve => setTimeout(resolve, 5000)); return 'late result';").toString(), browserSid, app)
            }
            delay(300)
            cancelled.cancelAndJoin()
            try { cancelled.await(); fail("browser cancellation must not return a normal tool result") }
            catch (_: CancellationException) { }
            passed("external cancellation of actual WebView work remains cancellation rather than an ordinary failure receipt")
            val readonlyFetch = OffloadPermissionManager.withUnattendedSession(cliSid, ScheduledTaskPermissionTier.READ_ONLY) {
                cli("fetch", listOf("--url", "$base/artifact"))
            }
            val readonlyPath = readonlyFetch.getString("fetched_path")
            assertTrue(readonlyPath.startsWith("/var/minis/offloads/")); assertArrayEquals(bytes, WorkspaceFileClient.readAll(cliSid, readonlyPath))
            assertEquals(BrowserTools.linuxPathToMinisURL(readonlyPath), readonlyFetch.getString("fetched_minis_url"))
            passed("READ_ONLY browser downloads use the existing allowed offload directory with a correct native artifact URL")
            val first = BrowserTools.persistArtifact(cliSid, "../报告.txt", "first".toByteArray())!!
            val second = BrowserTools.persistArtifact(cliSid, "../报告.txt", "second".toByteArray())!!
            assertNotEquals(first, second); assertTrue(first.substringAfterLast('/').contains("报告"))
            assertEquals("first", WorkspaceFileClient.readAll(cliSid, first).decodeToString())
            assertEquals("second", WorkspaceFileClient.readAll(cliSid, second).decodeToString())
            passed("shared artifact persistence keeps Unicode basenames, strips traversal and avoids overwriting repeated filenames")

            val parallel = coroutineScope {
                (1..10).map { index -> async(Dispatchers.IO) {
                    val content = "parallel-$index"
                    BrowserTools.persistArtifact(cliSid, "并发.txt", content.toByteArray())!! to content
                } }.awaitAll()
            }
            assertEquals(10, parallel.map { it.first }.toSet().size)
            for ((path, content) in parallel) assertEquals(content, WorkspaceFileClient.readAll(cliSid, path).decodeToString())
            for (index in 1..505) WorkspaceFileClient.writeBytes(cliSid, "/var/minis/browser/a-$index.txt", byteArrayOf(1))
            val existing = "/var/minis/browser/z-existing.txt"
            WorkspaceFileClient.writeBytes(cliSid, existing, "retained".toByteArray())
            val replacement = BrowserTools.persistArtifact(cliSid, "z-existing.txt", "new".toByteArray())!!
            assertNotEquals(existing, replacement)
            assertEquals("retained", WorkspaceFileClient.readAll(cliSid, existing).decodeToString())
            assertEquals("new", WorkspaceFileClient.readAll(cliSid, replacement).decodeToString())
            passed("concurrent artifacts and directories beyond a listing page preserve every existing file")

            val token = TokenStore.Token("stateful-e2e-${UUID.randomUUID()}", UUID.randomUUID().toString())
            val server = MCPServer(app, 24672)
            TokenStore.upsert(token)
            try {
                server.start(); assertTrue(server.isRunning)
                fun rpc(params: JSONObject): JSONObject {
                    val connection = URL("http://127.0.0.1:24672/mcp").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 5_000; connection.connectTimeout = 5_000
                        connection.setRequestProperty("Authorization", "Bearer ${token.token}"); connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call").put("params", params).toString().toByteArray()) }
                        assertEquals(200, connection.responseCode)
                        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                for (args in listOf<Any>("not an object", JSONObject().put("action", 9), JSONObject().put("action", "invalid-action"))) {
                    val response = rpc(JSONObject().put("name", "browser_use").put("arguments", args))
                    assertEquals(response.toString(), -32602, response.getJSONObject("error").getInt("code"))
                    assertFalse(response.toString().contains("confirm_required"))
                }
                passed("authenticated MCP rejects malformed browser arguments before issuing any confirmation ticket")
                val requested = rpc(JSONObject().put("name", "browser_use").put("arguments", JSONObject().put("action", "list_tabs")))
                val error = requested.getJSONObject("error")
                assertEquals("confirm_required", error.getString("message"))
                val confirm = error.getJSONObject("data").getString("confirm_id")
                server.approveConfirm(confirm, "browser_use")
                val invalidRetry = rpc(JSONObject().put("name", "browser_use").put("confirm_id", confirm).put("arguments", JSONObject().put("action", 9)))
                assertEquals(-32602, invalidRetry.getJSONObject("error").getInt("code"))
                val accepted = rpc(JSONObject().put("name", "browser_use").put("confirm_id", confirm).put("arguments", JSONObject().put("action", "list_tabs")))
                assertFalse(accepted.toString(), accepted.getJSONObject("result").optBoolean("isError"))
                passed("invalid retry does not consume an approved ticket and a valid browser call reaches the same registered handler")
                val memoryDenied = rpc(JSONObject().put("name", "memory_write").put("arguments", JSONObject().put("content", deniedMemory)))
                assertTrue(memoryDenied.toString().contains("permission_denied"))
                passed("remote memory calls remain local-only after handler registration")
            } finally { server.stop(); TokenStore.remove(token.id) }
            File(app.filesDir, "agent-stateful-tools-delivery.bin").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            report.put("artifactBytes", bytes.size).put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            fixture.close(); fixtureThread.join(6_000)
            app.memoryRepository.revokeEntry(memory)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            File(app.filesDir, "agent-stateful-tools-e2e.json").writeText(report.toString(2))
        }
    }
}
