package com.openminis.app.provider

import com.openminis.app.provider.gemini.GeminiModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `models.list` pages (50 per page unless asked otherwise), so the newest
 * models can sit on a later page. These pin how one page is read and when the
 * caller is told to keep going.
 */
class GeminiModelsPageTest {

    private fun model(name: String, vararg methods: String) =
        """{"name":"models/$name","displayName":"$name","supportedGenerationMethods":[${methods.joinToString(",") { "\"$it\"" }}]}"""

    @Test
    fun `chat-capable models are kept and the next page token is surfaced`() {
        val page = GeminiModelsApi.parseModelsPage(
            """{"models":[${model("gemini-3.8-flash", "generateContent", "countTokens")}],"nextPageToken":"abc"}""",
        )!!
        assertEquals(listOf("gemini-3.8-flash"), page.models.map { it.id })
        assertEquals("abc", page.nextPageToken)
    }

    @Test
    fun `the last page has no token`() {
        val page = GeminiModelsApi.parseModelsPage("""{"models":[${model("gemini-2.5-pro", "generateContent")}]}""")!!
        assertNull(page.nextPageToken)
    }

    // ── Negative cases ──────────────────────────────────────────────────────

    @Test
    fun `embedding and other non-chat models are dropped`() {
        val page = GeminiModelsApi.parseModelsPage(
            """{"models":[${model("gemini-embedding-2", "embedContent")},${model("veo-3.1", "predictLongRunning")},${model("gemini-3.8-flash", "generateContent")}]}""",
        )!!
        assertEquals(listOf("gemini-3.8-flash"), page.models.map { it.id })
    }

    @Test
    fun `a body that is not a models page yields null so the caller falls back`() {
        assertNull(GeminiModelsApi.parseModelsPage(""))
        assertNull(GeminiModelsApi.parseModelsPage("<html>nope</html>"))
        assertNull(GeminiModelsApi.parseModelsPage("""{"error":{"code":403}}"""))
    }
}
