package com.openminis.app.provider

import com.openminis.app.provider.anthropic.AnthropicModelsApi
import com.openminis.app.provider.anthropic.AnthropicModelsApi.PageNext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicModelsPagingTest {
    private val url = "https://api.anthropic.com/v1/models?limit=512"

    @Test
    fun aSinglePageIsDone() {
        assertEquals(PageNext.Done, AnthropicModelsApi.pageNext(url, JSONObject("""{"data":[],"has_more":false}""")))
        assertEquals(PageNext.Done, AnthropicModelsApi.pageNext(url, JSONObject("""{"data":[]}""")))
    }

    @Test
    fun hasMoreWithACursorAsksForTheNextPageKeepingTheQuery() {
        val next = AnthropicModelsApi.pageNext(
            url, JSONObject("""{"data":[],"has_more":true,"last_id":"claude-x"}"""),
        ) as PageNext.Next
        assertTrue(next.url, next.url.startsWith("https://api.anthropic.com/v1/models?"))
        assertTrue(next.url, "limit=512" in next.url)
        assertTrue(next.url, "after_id=claude-x" in next.url)
    }

    @Test
    fun theCursorIsEncodedAndReplacesAnEarlierOne() {
        val next = AnthropicModelsApi.pageNext(
            "https://h/v1/models?limit=512&after_id=old",
            JSONObject("""{"has_more":true,"last_id":"a b&c"}"""),
        ) as PageNext.Next
        assertEquals(1, Regex("after_id=").findAll(next.url).count())
        assertTrue(next.url, "after_id=a%20b%26c" in next.url)
    }

    @Test
    fun hasMoreWithoutACursorIsBrokenNotSilentlyComplete() {
        assertEquals(PageNext.Broken, AnthropicModelsApi.pageNext(url, JSONObject("""{"has_more":true}""")))
        assertEquals(PageNext.Broken, AnthropicModelsApi.pageNext("not a url", JSONObject("""{"has_more":true,"last_id":"x"}""")))
    }
}
