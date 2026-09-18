package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-eta-hosted-web-search] The provider's own web search, as the Responses API wants it.
 *
 * Ported from Eta `agent/model/ResponsesRequestBuilder.kt` and the `hosted_web_search_enabled`
 * column behind it (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta puts one
 * `{"type":"web_search"}` entry into the request when the entry asked for it - and only there,
 * because the Chat Completions path has no such tool. This function is the whole rule: off leaves
 * the request exactly as it was, on adds the entry once even if the caller passes its own tools.
 */
object HostedWebSearchPolicy {

    /** The hosted tool's type name in the Responses request. */
    const val TOOL_TYPE = "web_search"

    /**
     * Returns the tools the request should carry: [tools] unchanged when [enabled] is false or the
     * hosted tool is already there, and a copy with the entry appended otherwise.
     */
    fun apply(tools: JSONArray, enabled: Boolean): JSONArray {
        if (!enabled) return tools
        val alreadyThere = (0 until tools.length()).any { index ->
            tools.optJSONObject(index)?.optString("type") == TOOL_TYPE
        }
        if (alreadyThere) return tools
        return JSONArray(tools.toString()).put(JSONObject().put("type", TOOL_TYPE))
    }
}
