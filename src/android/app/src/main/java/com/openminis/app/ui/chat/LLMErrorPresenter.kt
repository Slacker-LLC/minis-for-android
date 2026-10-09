package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.LLMError

/**
 * What the chat shows when a model request fails: a plain-language reason and the next step, then the provider's
 * own words. The providers fold every failure into an [LLMError] whose text is the raw status line or JSON body
 * ("[400] ...", "HTTP 413: ...", "Gemini API error 404: ..."); this is the one place that turns those into
 * something a person can act on.
 */
internal object LLMErrorPresenter {

    enum class Kind { AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_REQUEST, NOT_FOUND, TOO_LARGE, REJECTED, DECODING, OTHER }

    data class Reason(val kind: Kind, val status: Int?, val detail: String)

    private val STATUS_AT_START = Regex("""^\s*\[(\d{3})]""")
    private val STATUS_IN_TEXT = Regex("""(?:HTTP|error)\s+(\d{3})""", RegexOption.IGNORE_CASE)

    /** Anthropic reports the error type, not the status, inside the brackets. */
    private val TYPE_STATUS = mapOf(
        "invalid_request_error" to 400,
        "authentication_error" to 401,
        "permission_error" to 403,
        "not_found_error" to 404,
        "request_too_large" to 413,
        "rate_limit_error" to 429,
        "api_error" to 500,
        "overloaded_error" to 529,
    )

    internal fun statusOf(detail: String): Int? {
        STATUS_AT_START.find(detail)?.let { return it.groupValues[1].toInt() }
        STATUS_IN_TEXT.find(detail)?.let { return it.groupValues[1].toInt() }
        val type = Regex("""^\s*\[([a-z_]+)]""").find(detail)?.groupValues?.get(1)
        return type?.let { TYPE_STATUS[it] }
    }

    internal fun classify(e: Throwable): Reason? {
        val llm = e as? LLMError ?: return null
        return when (llm) {
            is LLMError.InvalidApiKey -> Reason(Kind.AUTH, null, llm.detail)
            is LLMError.RateLimited -> Reason(Kind.RATE_LIMIT, 429, "")
            is LLMError.NetworkError -> Reason(Kind.NETWORK, null, llm.cause?.message.orEmpty())
            is LLMError.DecodingError -> Reason(Kind.DECODING, null, llm.cause?.message.orEmpty())
            is LLMError.TransientError -> Reason(Kind.SERVER, statusOf(llm.detail), llm.detail)
            is LLMError.ProviderError -> {
                val status = statusOf(llm.detail)
                val kind = when (status) {
                    400 -> Kind.BAD_REQUEST
                    401, 403 -> Kind.AUTH
                    404 -> Kind.NOT_FOUND
                    413 -> Kind.TOO_LARGE
                    429 -> Kind.RATE_LIMIT
                    in 500..599 -> Kind.SERVER
                    in 400..499 -> Kind.REJECTED
                    else -> Kind.OTHER
                }
                Reason(kind, status, llm.detail)
            }
            is LLMError.Cancelled, is LLMError.Unknown -> Reason(Kind.OTHER, null, llm.message.orEmpty())
        }
    }

    /** The plain-language reason alone, one line: for places that have room for a sentence, not a paragraph. */
    fun summary(context: Context, e: Throwable): String {
        val reason = classify(e) ?: return e.message.orEmpty()
        val code = reason.status?.toString().orEmpty()
        return when (reason.kind) {
            Kind.AUTH -> context.getString(R.string.llm_err_auth)
            Kind.RATE_LIMIT -> context.getString(R.string.llm_err_rate_limit)
            Kind.NETWORK -> context.getString(R.string.llm_err_network)
            Kind.SERVER -> context.getString(R.string.llm_err_server, code.ifEmpty { "5xx" })
            Kind.BAD_REQUEST -> context.getString(R.string.llm_err_bad_request)
            Kind.NOT_FOUND -> context.getString(R.string.llm_err_not_found)
            Kind.TOO_LARGE -> context.getString(R.string.llm_err_too_large)
            Kind.REJECTED -> context.getString(R.string.llm_err_rejected, code)
            Kind.DECODING -> context.getString(R.string.llm_err_decoding)
            Kind.OTHER -> e.message.orEmpty()
        }
    }

    /** [summary] plus the provider's own words, for the error banner under a reply. */
    fun present(context: Context, e: Throwable): String {
        val reason = classify(e)
        if (reason == null || reason.kind == Kind.OTHER) return e.message.orEmpty()
        val detail = reason.detail.trim().take(DETAIL_CHARS)
        val explanation = summary(context, e)
        return if (detail.isEmpty()) explanation else explanation + "\n" + context.getString(R.string.llm_err_detail, detail)
    }

    private const val DETAIL_CHARS = 240
}

/** A failed model request, shown in the chat as [LLMErrorPresenter] words it. */
internal fun ChatViewModel.setInlineError(e: Throwable) {
    val text = LLMErrorPresenter.present(context, e).ifBlank { e.message ?: "Unknown error" }
    val note = giveUpNote
    giveUpNote = null
    setInlineError(withGiveUpNote(text, note))
}

/**
 * The error banner's text with what the app did about the failure ([note]) between the reason (its first line)
 * and the provider's own words (the rest), so the banner is the one place that says it.
 */
internal fun withGiveUpNote(text: String, note: String?): String {
    if (note.isNullOrBlank()) return text
    val reason = text.substringBefore('\n')
    val rest = text.substringAfter('\n', "")
    return if (rest.isEmpty()) "$reason\n$note" else "$reason\n$note\n$rest"
}
