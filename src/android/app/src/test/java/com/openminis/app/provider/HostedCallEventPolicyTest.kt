package com.openminis.app.provider

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-hosted-web-search] Ported from Eta `agent/model/OpenAiResponsesProvider.kt` (Mangi-11/Eta
 * @ c15de97). What is pinned here is the shape of the stream: which events are a hosted call, which
 * phase each one is, and that one call produces exactly one start and one finish.
 */
class HostedCallEventPolicyTest {

    @Test
    fun `the known phases parse into one activity each`() {
        assertEquals(
            HostedCallEventPolicy.Activity("web_search", HostedCallEventPolicy.Phase.STARTED),
            HostedCallEventPolicy.parse("response.web_search_call.in_progress"),
        )
        assertEquals(
            HostedCallEventPolicy.Activity("web_search", HostedCallEventPolicy.Phase.STARTED),
            HostedCallEventPolicy.parse("response.web_search_call.searching"),
        )
        assertEquals(
            HostedCallEventPolicy.Activity("file_search", HostedCallEventPolicy.Phase.FINISHED),
            HostedCallEventPolicy.parse("response.file_search_call.completed"),
        )
        assertFalse(HostedCallEventPolicy.parse("response.code_interpreter_call.failed")!!.success)
    }

    @Test
    fun `anything that is not a hosted-call event is not one`() {
        assertNull(HostedCallEventPolicy.parse("response.output_text.delta"))
        assertNull(HostedCallEventPolicy.parse("response.completed"))
        assertNull(HostedCallEventPolicy.parse("web_search_call.completed"))
        assertNull(HostedCallEventPolicy.parse("response._call.completed"))
        assertNull(
            "a phase this version does not know is not a reason to render a row",
            HostedCallEventPolicy.parse("response.web_search_call.partial"),
        )
    }

    @Test
    fun `the id prefers what the event names and falls back deterministically`() {
        assertEquals(
            "ws_1",
            HostedCallEventPolicy.itemId(JSONObject().put("item_id", "ws_1"), "web_search"),
        )
        assertEquals(
            "ws_2",
            HostedCallEventPolicy.itemId(JSONObject().put("id", "ws_2"), "web_search"),
        )
        assertEquals(
            "ws_3",
            HostedCallEventPolicy.itemId(
                JSONObject().put("item", JSONObject().put("id", "ws_3")),
                "web_search",
            ),
        )
        assertEquals(
            "web_search_7",
            HostedCallEventPolicy.itemId(JSONObject().put("output_index", 7), "web_search"),
        )
    }

    @Test
    fun `one call is one start and one finish, in that order`() {
        val ledger = HostedCallEventPolicy.Ledger()
        val started = HostedCallEventPolicy.Activity("web_search", HostedCallEventPolicy.Phase.STARTED)
        val finished = HostedCallEventPolicy.Activity("web_search", HostedCallEventPolicy.Phase.FINISHED)

        assertEquals(listOf(started), ledger.accept("ws_1", started))
        assertEquals("a repeated start is not a second row", emptyList<Any>(), ledger.accept("ws_1", started))
        assertEquals(listOf(finished), ledger.accept("ws_1", finished))
        assertEquals("a repeated finish is not a second row", emptyList<Any>(), ledger.accept("ws_1", finished))
    }

    @Test
    fun `a finish without a start still reports both`() {
        val ledger = HostedCallEventPolicy.Ledger()

        val rows = ledger.accept(
            "ws_9",
            HostedCallEventPolicy.Activity("web_search", HostedCallEventPolicy.Phase.FAILED),
        )

        assertEquals(2, rows.size)
        assertEquals(HostedCallEventPolicy.Phase.STARTED, rows[0].phase)
        assertEquals(HostedCallEventPolicy.Phase.FAILED, rows[1].phase)
        assertTrue(rows[1].finished)
        assertFalse(rows[1].success)
    }
}
