from pathlib import Path


def once(path: str, old: str, new: str) -> None:
    p = Path(path)
    s = p.read_text()
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}")
    p.write_text(s.replace(old, new, 1))


anthropic = "src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt"
once(
    anthropic,
    "    private val customUserAgent: String? = null,\n) : LLMProvider {",
    "    private val customUserAgent: String? = null,\n"
    "    /** Optional explicit OAuth identifier used by private builds/tests; null keeps the public mirror fail-closed. */\n"
    "    private val oauthIdentifierPromptOverride: String? = null,\n"
    ") : LLMProvider {",
)
once(
    anthropic,
    "    internal fun resolveSystemPrompt(userPrompt: String?): JSONArray? {\n"
    "        val claudeCodePrefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT\n"
    "        if (isOAuth) {\n",
    "    internal fun resolveSystemPrompt(userPrompt: String?): JSONArray? {\n"
    "        if (isOAuth) {\n"
    "            // The private Claude Code identifier is an OAuth-only requirement.\n"
    "            // API-key Anthropic requests must remain usable in the public mirror.\n"
    "            val claudeCodePrefix = oauthIdentifierPromptOverride\n"
    "                ?: com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT\n",
)

anthropic_test = "src/android/app/src/test/java/com/openminis/app/provider/AnthropicProviderTest.kt"
once(
    anthropic_test,
    "            isOAuth = true,\n        )",
    "            isOAuth = true,\n"
    "            oauthIdentifierPromptOverride = \"test-claude-code-prefix\",\n"
    "        )",
)

openai_test = Path("src/android/app/src/test/java/com/openminis/app/provider/OpenAIProviderTest.kt")
s = openai_test.read_text()
if "import org.json.JSONArray\n" not in s:
    if s.count("import org.json.JSONObject\n") != 1:
        raise SystemExit("OpenAIProviderTest: JSONObject import mismatch")
    s = s.replace(
        "import org.json.JSONObject\n",
        "import org.json.JSONArray\nimport org.json.JSONObject\n",
        1,
    )

# Before the explicit streaming section, every success fixture is an old
# non-streaming Chat Completions response. sendMessage() now intentionally
# drives the streaming transport internally, so convert only this prefix.
marker = "    // -- Streaming --"
if s.count(marker) != 1:
    raise SystemExit("OpenAIProviderTest: streaming marker mismatch")
prefix, suffix = s.split(marker, 1)
prefix = prefix.replace("MockResponse().setBody(", "chatCompletionSse(")
s = prefix + marker + suffix

teardown = """    @After
    fun tearDown() {
        server.shutdown()
    }
"""
helper = r'''

    /** Convert a non-stream Chat Completions fixture into the SSE shape
     * sendMessage() now intentionally uses internally. */
    private fun chatCompletionSse(json: String): MockResponse {
        val source = JSONObject(json)
        val sourceChoices = source.optJSONArray("choices") ?: JSONArray()
        val streamChoices = JSONArray()
        for (i in 0 until sourceChoices.length()) {
            val choice = sourceChoices.getJSONObject(i)
            val message = choice.optJSONObject("message") ?: JSONObject()
            val delta = JSONObject()
            if (message.has("role")) delta.put("role", message.opt("role"))
            if (message.has("content")) delta.put("content", message.opt("content"))
            if (message.has("tool_calls")) delta.put("tool_calls", message.opt("tool_calls"))
            val streamChoice = JSONObject().put("delta", delta)
            if (choice.has("finish_reason")) {
                streamChoice.put("finish_reason", choice.opt("finish_reason"))
            }
            streamChoices.put(streamChoice)
        }
        val event = JSONObject().put("choices", streamChoices)
        if (source.has("usage")) event.put("usage", source.opt("usage"))
        val body = "data: $event\n\ndata: [DONE]\n\n"
        return MockResponse()
            .setBody(body)
            .setHeader("Content-Type", "text/event-stream")
    }
'''
if s.count(teardown) != 1:
    raise SystemExit("OpenAIProviderTest: tearDown block not found")
s = s.replace(teardown, teardown + helper, 1)

empty_old = '''    @Test
    fun `sendMessage handles empty choices`() = runBlocking {
        server.enqueue(chatCompletionSse("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))

        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertEquals("", response.text)
        assertNull(response.stopReason)
    }
'''
empty_new = '''    @Test(expected = LLMError.TransientError::class)
    fun `sendMessage treats empty choices as transient upstream failure`() = runBlocking {
        server.enqueue(chatCompletionSse("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        Unit
    }
'''
if s.count(empty_old) != 1:
    raise SystemExit("OpenAIProviderTest: empty-choices test shape changed")
s = s.replace(empty_old, empty_new, 1)

stream_old = '''    @Test
    fun `sendMessage sets stream false for non-streaming`() = runBlocking {
        server.enqueue(chatCompletionSse("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(false, body.getBoolean("stream"))
        assertTrue(!body.has("stream_options"))
    }
'''
stream_new = '''    @Test
    fun `sendMessage uses streaming transport internally`() = runBlocking {
        server.enqueue(chatCompletionSse("""{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }
'''
if s.count(stream_old) != 1:
    raise SystemExit("OpenAIProviderTest: sendMessage transport test shape changed")
s = s.replace(stream_old, stream_new, 1)

# The reasoning-effort capture helper is below the streaming/error sections;
# convert its one old Chat Completions fixture too.
capture_start = s.index("    private fun captureBody(")
capture_end = s.index("\n    @Test", capture_start)
capture = s[capture_start:capture_end]
if capture.count("MockResponse().setBody(") != 1:
    raise SystemExit("OpenAIProviderTest: captureBody fixture mismatch")
capture = capture.replace("MockResponse().setBody(", "chatCompletionSse(", 1)
s = s[:capture_start] + capture + s[capture_end:]
openai_test.write_text(s)
