package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.openai.OpenAIModelsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Codex OAuth model list used to be a hard-coded allow-list, so a model OpenAI
 * shipped later never showed up however often the user pressed "refresh". The
 * ChatGPT backend serves its own per-account list; these tests pin how that body
 * is read and how it is combined with the bundled fallback.
 */
class CodexLiveCatalogTest {

    private fun body(vararg models: String) = """{"models":[${models.joinToString(",")}]}"""

    private fun entry(
        slug: String,
        priority: Int,
        visibility: String = "list",
        name: String = slug,
        efforts: List<String> = listOf("low", "high"),
    ) = """{"slug":"$slug","display_name":"$name","visibility":"$visibility","priority":$priority,""" +
        """"supported_reasoning_levels":[${efforts.joinToString(",") { """{"effort":"$it","description":"d"}""" }}],""" +
        """"input_modalities":["text","image"]}"""

    @Test
    fun `models are returned in the backend's priority order with their names`() {
        val parsed = OpenAIModelsApi.parseCodexModels(
            body(entry("late", 9), entry("gpt-6.1-sol", 1, name = "GPT-6.1 Sol"), entry("mid", 5)),
        )
        assertEquals(listOf("gpt-6.1-sol", "mid", "late"), parsed.map { it.id })
        assertEquals("GPT-6.1 Sol", parsed.first().displayName)
    }

    @Test
    fun `reasoning levels and image input are carried over`() {
        val model = OpenAIModelsApi.parseCodexModels(
            body(entry("m", 1, efforts = listOf("Low", "XHigh"))),
        ).single()
        assertEquals(true, model.supportsReasoning)
        assertEquals(listOf("low", "xhigh"), model.reasoningEffortValues)
        assertEquals(listOf("text", "image"), model.inputModalities)
    }

    @Test
    fun `a model without reasoning levels does not claim to reason`() {
        val model = OpenAIModelsApi.parseCodexModels(body(entry("m", 1, efforts = emptyList()))).single()
        assertEquals(null, model.supportsReasoning)
        assertEquals(null, model.reasoningEffortValues)
    }

    // ── Negative cases: nothing the picker should not offer gets through ───

    @Test
    fun `hidden and unlisted models are dropped`() {
        val parsed = OpenAIModelsApi.parseCodexModels(
            body(entry("shown", 1), entry("hidden", 2, visibility = "hide"), entry("none", 3, visibility = "none")),
        )
        assertEquals(listOf("shown"), parsed.map { it.id })
    }

    @Test
    fun `entries without a slug and repeated slugs are dropped`() {
        val parsed = OpenAIModelsApi.parseCodexModels(
            body("""{"display_name":"no slug","priority":1}""", """{"slug":"  ","priority":2}""", entry("dup", 3), entry("dup", 4)),
        )
        assertEquals(listOf("dup"), parsed.map { it.id })
    }

    @Test
    fun `a malformed or unexpected body yields an empty list`() {
        assertTrue(OpenAIModelsApi.parseCodexModels("").isEmpty())
        assertTrue(OpenAIModelsApi.parseCodexModels("<html>blocked</html>").isEmpty())
        assertTrue(OpenAIModelsApi.parseCodexModels("""{"error":"nope"}""").isEmpty())
        assertTrue(OpenAIModelsApi.parseCodexModels("""{"models":"not-an-array"}""").isEmpty())
        assertTrue(OpenAIModelsApi.parseCodexModels("""{"models":[]}""").isEmpty())
    }

    // ── Combining the live list with the bundled fallback ───────────────────

    private fun text(id: String) = LLMModel(id, id, "OpenAI")
    private val imageRoute = LLMModel("gpt-image-2", "GPT Image 2", "OpenAI", outputModalities = listOf("image"))

    @Test
    fun `an empty live list falls back to the bundled catalog unchanged`() {
        val bundled = listOf(text("old"), imageRoute)
        assertEquals(bundled, OpenAIModelsApi.mergeCodexCatalog(bundled, emptyList()))
    }

    @Test
    fun `the live list replaces the bundled text models but keeps the image-only route`() {
        val merged = OpenAIModelsApi.mergeCodexCatalog(
            bundled = listOf(text("old"), imageRoute),
            live = listOf(text("new-a"), text("new-b")),
        )
        assertEquals(listOf("new-a", "new-b", "gpt-image-2"), merged.map { it.id })
    }

    @Test
    fun `a route the live list already carries is not duplicated`() {
        val merged = OpenAIModelsApi.mergeCodexCatalog(
            bundled = listOf(text("old"), imageRoute),
            live = listOf(text("new"), imageRoute),
        )
        assertEquals(listOf("new", "gpt-image-2"), merged.map { it.id })
    }
}
