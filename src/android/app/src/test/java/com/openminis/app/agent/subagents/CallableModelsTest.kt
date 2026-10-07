package com.openminis.app.agent.subagents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallableModelsTest {
    private fun src(entry: String, model: String, provider: String = "P", ctx: Int? = null, image: Boolean = false, reasoning: Boolean = false) =
        CallableModels.Source(entry, model, displayName = model.uppercase(), providerLabel = provider, contextWindow = ctx, imageInput = image, reasoning = reasoning)

    @Test
    fun `a model is named by its model id when that is unique`() {
        val out = CallableModels.from(listOf(src("e1", "deepseek-v4-flash"), src("e2", "gpt-5")))
        assertEquals(listOf("deepseek-v4-flash", "gpt-5"), out.map { it.handle })
        assertEquals(listOf("e1", "e2"), out.map { it.entryId })
    }

    @Test
    fun `the same model through two providers is qualified by provider`() {
        val out = CallableModels.from(listOf(src("e1", "gpt-5", "Work"), src("e2", "GPT-5", "Home"), src("e3", "o4")))
        assertEquals(listOf("Work/gpt-5", "Home/GPT-5", "o4"), out.map { it.handle })
    }

    @Test
    fun `names stay unique even when provider and model are equal`() {
        val out = CallableModels.from(listOf(src("e1", "m", "P"), src("e2", "m", "P"), src("e3", "m", "p")))
        assertEquals(3, out.map { it.handle.lowercase() }.toSet().size)
        assertEquals("P/m", out[0].handle)
        assertTrue(out[1].handle.startsWith("P/m#"))
    }

    @Test
    fun `the list is offered in the user's order, without repeats, up to the cap`() {
        val many = (1..CallableModels.MAX_OFFERED + 5).map { src("e$it", "m$it") }
        val out = CallableModels.from(many + src("e1", "m1"))
        assertEquals(CallableModels.MAX_OFFERED, out.size)
        assertEquals("m1", out.first().handle)
    }

    @Test
    fun `the summary describes what the model offers`() {
        val s = CallableModels.from(listOf(src("e1", "v", "Acme", ctx = 200_000, image = true, reasoning = true))).single().summary
        assertEquals("V · via Acme · 200K context · image input · reasoning", s)
        val plain = CallableModels.from(listOf(src("e1", "v", "", ctx = null))).single().summary
        assertEquals("V", plain)
    }

    @Test
    fun `matching is exact apart from case and surrounding space`() {
        val list = CallableModels.from(listOf(src("e1", "deepseek-v4-flash"), src("e2", "gpt-5", "Work"), src("e3", "gpt-5", "Home")))
        assertEquals("e1", CallableModels.match("  DeepSeek-V4-Flash ", list)?.entryId)
        assertEquals("e3", CallableModels.match("home/gpt-5", list)?.entryId)
        assertNull("no prefix match", CallableModels.match("deepseek", list))
        assertNull("an ambiguous bare id is not guessed", CallableModels.match("gpt-5", list))
        assertNull("entry ids are not names", CallableModels.match("e1", list))
        assertNull(CallableModels.match("", list))
        assertFalse(list.any { it.handle.isBlank() })
    }
}
