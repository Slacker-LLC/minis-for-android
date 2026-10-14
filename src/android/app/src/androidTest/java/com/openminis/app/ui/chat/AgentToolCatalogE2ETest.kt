package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.agent.AgentRunner
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.agent.subagents.SubAgentStore
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.repository.awaitConfigLoaded
import com.openminis.app.mcp.server.MCPServer
import com.openminis.app.mcp.server.TokenStore
import com.openminis.app.remote.AgentPresetRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Real model directory lookup → real file delivery, and actual authenticated MCP scope checks. */
@RunWith(AndroidJUnit4::class)
class AgentToolCatalogE2ETest {
    @Test
    fun discoverPublishedToolsWithoutChangingExecutionScope(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.providerRepository.awaitConfigLoaded(); UbuntuPaths.initialize(app)
        val sessions = mutableListOf<String>()
        val enabled = SubAgentStore.enabled.value
        val report = JSONObject().put("device", android.os.Build.FINGERPRINT).put("scenarios", JSONArray())
        fun passed(name: String) { report.getJSONArray("scenarios").put(JSONObject().put("name", name).put("passed", true)) }
        suspend fun session(source: String? = null): String = app.chatRepository.createSession("scripted-model",
            title = "M3 tool catalog E2E", memoryEnabled = false, source = source).id.also(sessions::add)
        suspend fun prepare(sid: String, provider: ScriptedProvider) {
            val vm = withContext(Dispatchers.Main) { chatProjection(app, sid) }
            withTimeout(15_000) { vm.sessionLoaded.first { it } }
            withContext(Dispatchers.Main) { vm.currentProvider = provider; vm.currentModel = provider.model; vm._activeEntryId.value = "catalog-scripted-entry" }
        }
        fun canonicalNames(data: JSONObject): Set<String> = data.getJSONArray("tools").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("canonical_name") }.toSet()
        }
        suspend fun catalog(sid: String, args: JSONObject = JSONObject()): JSONObject {
            val result = ToolExecutor.execute("find_tools", args.toString(), sid, app)
            assertTrue(result.output, result.success)
            return JSONObject(result.output)
        }
        try {
            SubAgentStore.setEnabled(false)
            val sid = session()
            AgentPresetRegistry.applyToSession(app, sid, "minimal")
            var discovered = ""
            val provider = ScriptedProvider { call, history, tools ->
                assertTrue(tools.any { ToolRegistry.canonicalName(it.name) == "agent.tools" })
                val results = history.flatMap { it.contentParts }.filterIsInstance<AgentContentPart.ToolResult>()
                when (call) {
                    1 -> toolCall("catalog-discover", "find_tools", JSONObject().put("query", "file_write").put("include_schema", true))
                    2 -> {
                        val data = JSONObject(results.single { it.id == "catalog-discover" }.content)
                        assertEquals("current_session_configuration", data.getString("scope")); assertFalse(data.getBoolean("device_availability_checked"))
                        val entries = data.getJSONArray("tools")
                        val item = (0 until entries.length()).map(entries::getJSONObject)
                            .single { it.getString("canonical_name") == "linux.file.write" }
                        assertEquals("linux.file.write", item.getString("canonical_name"))
                        val schema = item.getJSONObject("input_schema")
                        assertTrue(schema.getJSONObject("properties").has("content")); assertTrue(schema.getJSONObject("properties").has("path"))
                        discovered = item.getString("name")
                        assertTrue(tools.any { it.apiName == discovered })
                        toolCall("catalog-write", discovered, JSONObject().put("path", "/workspace/catalog-delivery.txt").put("content", "catalog verified delivery\n"))
                    }
                    3 -> { assertFalse(results.single { it.id == "catalog-write" }.isError)
                        toolCall("catalog-read", "file_read", JSONObject().put("path", "/workspace/catalog-delivery.txt")) }
                    else -> { assertTrue(results.single { it.id == "catalog-read" }.content.contains("catalog verified delivery")); text("目录参数与真实文件交付已核验。") }
                }
            }
            prepare(sid, provider)
            val owner = AgentSessionStore.existing(sid)
            assertTrue(AgentRunner.prompt(app, sid, "查询工具参数后交付文件", wait = true, timeoutMs = 25_000).completed)
            assertSame(owner, AgentSessionStore.existing(sid)); assertEquals("file_write", discovered)
            passed("minimal model looks up the actual file schema and uses its published wire name to deliver and read a real file")
            val names = canonicalNames(catalog(sid, JSONObject().put("limit", 100)))
            assertTrue(names.containsAll(setOf("agent.tools", "linux.file.write", "agent.goal")))
            assertFalse(names.contains("browser_use")); assertFalse(names.contains("memory_write")); assertFalse(names.contains("agent.subagent"))
            passed("CORE catalog retains controls and omits FULL-only browser, disabled memory and disabled delegation")
            val first = catalog(sid, JSONObject().put("limit", 2))
            val second = catalog(sid, JSONObject().put("limit", 2).put("offset", first.getInt("next_offset")))
            assertEquals(first.getInt("total"), second.getInt("total"))
            assertTrue(canonicalNames(first).intersect(canonicalNames(second)).isEmpty())
            val invalid = ToolExecutor.execute("find_tools", "{\"limit\":0}", sid, app)
            assertFalse(invalid.success); assertTrue(invalid.output.contains("invalid_arguments"))
            assertTrue(canonicalNames(catalog(sid, JSONObject().put("query", "file write").put("limit", 100))).contains("linux.file.write"))
            val beyond = catalog(sid, JSONObject().put("offset", 4_294_967_296L))
            assertEquals(0, beyond.getJSONArray("tools").length()); assertTrue(beyond.isNull("next_offset"))
            passed("catalog pagination and whitespace queries are stable, oversized offsets do not wrap and invalid page sizes are rejected")
            val fullSid = session()
            prepare(fullSid, ScriptedProvider { _, _, _ -> text("准备查询目录。") })
            val fullNames = canonicalNames(catalog(fullSid, JSONObject().put("limit", 100)))
            assertTrue(fullNames.contains("browser_use")); assertFalse(fullNames.contains("memory_get")); assertFalse(fullNames.contains("agent.subagent"))
            passed("FULL discovery reuses its own active configuration and does not restore memory or delegation withheld by that configuration")
            SubAgentStore.setEnabled(true)
            val child = session(ChatSessionEntity.LEGACY_SOURCE_SUB_AGENT)
            prepare(child, ScriptedProvider { _, _, _ -> text("准备查询子会话目录。") })
            assertFalse(canonicalNames(catalog(child, JSONObject().put("limit", 100))).contains("agent.subagent"))
            passed("a legacy child session still withholds nested delegation in its discovered directory")
            val noOwner = session()
            assertNull(AgentSessionStore.existing(noOwner))
            val static = catalog(noOwner, JSONObject().put("query", "browser_use"))
            assertEquals("registered_local_interfaces", static.getString("scope")); assertFalse(static.getBoolean("device_availability_checked"))
            assertNull(AgentSessionStore.existing(noOwner))
            passed("an ownerless local query labels the registration directory honestly and does not construct another execution owner")

            val server = MCPServer(app, 24673)
            val fullToken = TokenStore.Token("catalog-full-${UUID.randomUUID()}", UUID.randomUUID().toString())
            val narrow = TokenStore.Token("catalog-narrow-${UUID.randomUUID()}", UUID.randomUUID().toString(), scope = setOf("agent.tools", "linux.file.read"))
            TokenStore.upsert(fullToken); TokenStore.upsert(narrow)
            try {
                server.start(); assertTrue(server.isRunning)
                fun rpc(token: TokenStore.Token, method: String, params: JSONObject = JSONObject()): JSONObject {
                    val connection = URL("http://127.0.0.1:24673/mcp").openConnection() as HttpURLConnection
                    try {
                        connection.requestMethod = "POST"; connection.doOutput = true; connection.readTimeout = 5_000; connection.connectTimeout = 5_000
                        connection.setRequestProperty("Authorization", "Bearer ${token.token}"); connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().toByteArray()) }
                        assertEquals(200, connection.responseCode)
                        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                fun remoteCatalog(token: TokenStore.Token): JSONObject {
                    val response = rpc(token, "tools/call", JSONObject().put("name", "agent_tools").put("arguments",
                        JSONObject().put("limit", 100).put("caller", "local_agent")))
                    val result = response.getJSONObject("result"); assertFalse(response.toString(), result.optBoolean("isError"))
                    return JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
                }
                val remote = remoteCatalog(fullToken)
                assertEquals("mcp_token", remote.getString("scope"))
                assertFalse(canonicalNames(remote).contains("root.shell")); assertFalse(canonicalNames(remote).contains("memory_write"))
                val listed = rpc(fullToken, "tools/list").getJSONObject("result").getJSONArray("tools")
                val listedNames = (0 until listed.length()).map { listed.getJSONObject(it).getString("name") }.toSet()
                val returnedNames = remote.getJSONArray("tools").let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("name") }.toSet() }
                assertEquals(listedNames, returnedNames)
                passed("authenticated MCP directory matches tools/list and cannot reveal local-only tools through a forged JSON caller")
                assertEquals(setOf("agent.tools", "linux.file.read"), canonicalNames(remoteCatalog(narrow)))
                passed("a narrow real MCP token discovers only its permitted canonical tools")
            } finally { server.stop(); TokenStore.remove(fullToken.id); TokenStore.remove(narrow.id) }
            val bytes = WorkspaceFileClient.readAll(sid, "/workspace/catalog-delivery.txt")
            File(app.filesDir, "agent-tool-catalog-delivery.txt").writeBytes(bytes)
            report.put("artifactSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }).put("passed", true)
        } catch (error: Throwable) { report.put("passed", false).put("failure", error.toString()); throw error }
        finally {
            SubAgentStore.setEnabled(enabled)
            sessions.forEach { AgentRunner.cancel(app, it); app.chatRepository.deleteSession(it) }
            File(app.filesDir, "agent-tool-catalog-e2e.json").writeText(report.toString(2))
        }
    }
}
