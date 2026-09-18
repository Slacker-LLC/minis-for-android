package com.openminis.app.provider.openai

import org.json.JSONObject

/**
 * [T-eta-responses-citations] `url_citation` annotations returned by the
 * Responses API, rendered the way Eta renders them.
 *
 * Ported from Eta `agent/model/ResponsesCitationFormatter.kt` and the annotation
 * handling in `agent/model/OpenAiResponsesProvider.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md.
 *
 * Rules kept from Eta: only http(s) URLs count, a repeated URL collapses into its
 * first occurrence, numbering follows first-seen order, `>` is percent-escaped so
 * the URL survives inside a Markdown `<...>` destination, and a citation without a
 * title falls back to `来源 N`.
 *
 * Deviation forced by this app's stream: Eta formats the finished text, so it can
 * insert a marker at any offset. Minis' text is append-only — a delta that reached
 * the UI and the transcript cannot be rewritten — so [ResponsesCitationStream]
 * inlines a citation only when it ends exactly at the current head of the stream
 * and degrades the rest to the trailing source list, which is the same fallback
 * Eta applies to offsets that no longer apply.
 */
object ResponsesCitationFormatter {

    data class Citation(
        val start: Int?,
        val end: Int?,
        val url: String,
        val title: String,
    )

    fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")

    /**
     * Reads one annotation object. Both shapes the API family uses are accepted:
     * the flat `{type:"url_citation", url, title, start_index, end_index}` and a
     * relay-style nesting under `url_citation`. Returns null when the annotation
     * carries no usable URL.
     */
    fun read(annotation: JSONObject): Citation? {
        val source = annotation.optJSONObject("url_citation") ?: annotation
        val url = source.optString("url")
        if (!isHttpUrl(url)) return null
        return Citation(
            start = source.optInt("start_index", -1).takeIf { it >= 0 },
            end = source.optInt("end_index", -1).takeIf { it >= 0 },
            url = url,
            title = source.optString("title"),
        )
    }

    /** Eta's inline marker: a numbered link inserted right after the cited span. */
    fun inlineMarker(number: Int, url: String): String = " [[$number]](<${url.escapeAngleBrackets()}>)"

    /** Eta's fallback block for citations whose offsets no longer apply. */
    fun sourcesBlock(citations: List<Citation>, numbering: Map<String, Int>): String {
        if (citations.isEmpty()) return ""
        val block = StringBuilder("\n\n来源：")
        citations.forEachIndexed { index, citation ->
            val number = numbering[citation.url] ?: (index + 1)
            val label = citation.title.ifBlank { "来源 ${index + 1}" }
            block.append("\n- [$number] [$label](<${citation.url.escapeAngleBrackets()}>)")
        }
        return block.toString()
    }

    private fun String.escapeAngleBrackets(): String = replace(">", "%3E")
}

/**
 * [T-eta-responses-citations] Accumulates the citations of one Responses turn.
 *
 * [accept] is called for every annotation in arrival order with the length of the
 * text that has already reached the stream: the citation is inlined when it ends
 * exactly there, and remembered for [trailingSources] otherwise. A URL is only
 * ever numbered once, so an inline marker and a source-list entry can never
 * disagree about a citation's number.
 */
class ResponsesCitationStream {

    private val numberByUrl = LinkedHashMap<String, Int>()
    private val unresolved = LinkedHashMap<String, ResponsesCitationFormatter.Citation>()

    /** Returns the inline marker to append, or null when the citation is deferred. */
    fun accept(
        citation: ResponsesCitationFormatter.Citation,
        streamedLength: Int,
    ): String? {
        if (!ResponsesCitationFormatter.isHttpUrl(citation.url)) return null
        if (numberByUrl.containsKey(citation.url)) return null
        val number = numberByUrl.size + 1
        numberByUrl[citation.url] = number
        val start = citation.start
        val end = citation.end
        if (start != null && end != null && end == streamedLength && end >= start) {
            return ResponsesCitationFormatter.inlineMarker(number, citation.url)
        }
        unresolved[citation.url] = citation
        return null
    }

    /** The trailing source list, or null when every citation was inlined. One-shot. */
    fun trailingSources(): String? {
        if (unresolved.isEmpty()) return null
        val block = ResponsesCitationFormatter.sourcesBlock(unresolved.values.toList(), numberByUrl)
        unresolved.clear()
        return block
    }
}
