package com.openminis.app.ui.chat

import com.openminis.app.agent.*

import android.content.Context
import com.openminis.app.R
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.ProviderErrorCatalog
import com.openminis.app.provider.ProviderErrorCatalog.Category

/**
 * What the chat shows when a model request fails: a plain-language reason and the next step, then the provider's
 * own words. The providers fold every failure into an [LLMError] whose text is the raw status line or JSON body
 * ("[400] ...", "HTTP 413: ...", "Gemini API error 404: ..."); this is the one place that turns those into
 * something a person can act on.
 */
internal object LLMErrorPresenter {

    enum class Kind {
        AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_REQUEST, NOT_FOUND, TOO_LARGE, REJECTED, DECODING, OTHER,

        // A 400 whose body says what was wrong. Only these five are told apart; anything else stays BAD_REQUEST.
        BAD_TOOL_PAIRING, BAD_CONTEXT, BAD_IMAGE, BAD_MODEL, BAD_THINKING,

        // Account-level failures, read from the provider's own code/message (see ProviderErrorCatalog), whatever the status.
        // Out of balance (400/402/429 depending on the vendor).
        BAD_BALANCE,

        // A plan, free-tier or usage window is used up, expired, or does not include the model.
        PLAN_LIMIT,

        // The provider's safety layer refused the content.
        CONTENT_POLICY,

        // The provider has no capacity right now (Anthropic 529, OpenAI server_is_overloaded, ...).
        OVERLOADED,

        // The account's country or region is not served.
        REGION,

        // Not the provider's fault: the key holds a line break or other invisible character, so the request was never sent.
        BAD_HEADER,

        // A JDK/Kotlin exception ("List is empty.") that escaped the app's own code. Its text is not meant for people.
        INTERNAL,
    }

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

    private val TOOL_PAIRING = listOf(
        "no tool output found", // OpenAI Responses: a function_call without its function_call_output
        "tool_use ids were found without tool_result", // Anthropic
        "responding to each", // Chat Completions: "...must be followed by tool messages responding to each tool_call_id"
        "must be followed by tool messages",
        "no tool call found", // the reverse: an output with no call
        "function response parts", // Gemini: number of function response parts must equal function call parts
    )
    /** OkHttp refuses a header value holding a control character: "Unexpected char 0x0a at 58 in Authorization value". */
    private val HEADER_CHAR = Regex("""Unexpected char 0x[0-9a-fA-F]+ at \d+ in .+ value""")

    /** Framework exceptions whose message ("List is empty.", "Index 3 out of bounds") only makes sense to a developer. */
    private val INTERNAL_TYPES = listOf(
        NoSuchElementException::class, IndexOutOfBoundsException::class, NullPointerException::class,
        ClassCastException::class, NumberFormatException::class, ConcurrentModificationException::class,
        UnsupportedOperationException::class,
    )

    private fun isInternal(e: Throwable) = INTERNAL_TYPES.any { it.isInstance(e) }

    /** What a throwable that is not a provider error was about, or null when its own message should be shown. */
    private fun classifyOther(e: Throwable): Reason? {
        val message = e.message.orEmpty()
        return when {
            HEADER_CHAR.containsMatchIn(message) -> Reason(Kind.BAD_HEADER, null, message)
            isInternal(e) -> Reason(Kind.INTERNAL, null, message.ifBlank { e.javaClass.simpleName })
            else -> null
        }
    }

    private val CONTEXT = listOf(
        "context length", "context_length", "maximum context", "context window", "too many tokens",
        "prompt is too long", "input is too long", "reduce the length", "max_tokens_to_sample",
    )

    /**
     * What a 400 was about, read from the provider's own body. The providers word these differently, so this is a
     * list of phrases that are specific enough not to mean something else; no match leaves the general 400 text.
     * Pure, so the phrases are unit-tested.
     */
    internal fun refine400(detail: String): Kind {
        // Providers quote identifiers differently (`tool_use`, 'tool_calls'); the phrases below carry no quotes.
        val d = detail.lowercase().replace("`", "").replace("'", "").replace("\"", "")
        return when {
            TOOL_PAIRING.any { it in d } -> Kind.BAD_TOOL_PAIRING
            ProviderErrorCatalog.categoryOf(d) == Category.BALANCE -> Kind.BAD_BALANCE
            CONTEXT.any { it in d } -> Kind.BAD_CONTEXT
            "image" in d && listOf("not support", "unsupported", "invalid image", "does not accept", "cannot be", "vision", "multimodal").any { it in d } -> Kind.BAD_IMAGE
            "model" in d && listOf("not found", "does not exist", "invalid model", "unknown model", "model_not_found", "not supported").any { it in d } -> Kind.BAD_MODEL
            listOf("reasoning", "thinking", "budget_tokens", "effort").any { it in d } &&
                listOf("invalid", "unsupported", "not support", "unknown", "must be", "not allowed").any { it in d } -> Kind.BAD_THINKING
            else -> Kind.BAD_REQUEST
        }
    }

    private fun Category.toKind() = when (this) {
        Category.BALANCE -> Kind.BAD_BALANCE
        Category.PLAN_LIMIT -> Kind.PLAN_LIMIT
        Category.CONTENT_POLICY -> Kind.CONTENT_POLICY
        Category.OVERLOADED -> Kind.OVERLOADED
        Category.REGION -> Kind.REGION
    }

    internal fun classify(e: Throwable): Reason? {
        val llm = e as? LLMError ?: return classifyOther(e)
        return when (llm) {
            is LLMError.InvalidApiKey -> {
                // A 403 is not always a bad key: it can be a region block or an empty account.
                val kind = ProviderErrorCatalog.categoryOf(llm.detail)
                    ?.takeIf { it == Category.REGION || it == Category.BALANCE || it == Category.PLAN_LIMIT }
                    ?.toKind() ?: Kind.AUTH
                Reason(kind, statusOf(llm.detail), llm.detail)
            }
            is LLMError.RateLimited -> Reason(Kind.RATE_LIMIT, 429, "")
            is LLMError.NetworkError -> Reason(Kind.NETWORK, null, llm.cause?.message.orEmpty())
            is LLMError.DecodingError -> Reason(Kind.DECODING, null, llm.cause?.message.orEmpty())
            is LLMError.TransientError -> {
                val kind = when (ProviderErrorCatalog.categoryOf(llm.detail)) {
                    Category.OVERLOADED -> Kind.OVERLOADED
                    Category.BALANCE -> Kind.BAD_BALANCE
                    Category.PLAN_LIMIT -> Kind.PLAN_LIMIT
                    else -> Kind.SERVER
                }
                Reason(kind, statusOf(llm.detail), llm.detail)
            }
            is LLMError.ProviderError -> {
                val status = statusOf(llm.detail)
                // The vendor's own code/message wins over the status: Zhipu's overdue account is a 429, DashScope's a 400.
                // A 5xx is the provider's own failure whatever its text mentions; only "overloaded" is read from it.
                val category = ProviderErrorCatalog.categoryOf(llm.detail)?.takeUnless {
                    status == 401 || (status != null && status in 500..599 && it != Category.OVERLOADED)
                }
                val kind = if (category != null) category.toKind() else when (status) {
                    400 -> refine400(llm.detail)
                    402 -> Kind.BAD_BALANCE
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
            is LLMError.Cancelled -> Reason(Kind.OTHER, null, llm.message.orEmpty())
            is LLMError.Unknown -> llm.cause?.let(::classifyOther) ?: Reason(Kind.OTHER, null, llm.message.orEmpty())
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
            Kind.BAD_TOOL_PAIRING -> context.getString(R.string.llm_err_400_tool_pairing)
            Kind.BAD_CONTEXT -> context.getString(R.string.llm_err_400_context)
            Kind.BAD_IMAGE -> context.getString(R.string.llm_err_400_image)
            Kind.BAD_MODEL -> context.getString(R.string.llm_err_400_model)
            Kind.BAD_THINKING -> context.getString(R.string.llm_err_400_thinking)
            Kind.BAD_BALANCE -> context.getString(R.string.llm_err_balance)
            Kind.PLAN_LIMIT -> context.getString(R.string.llm_err_plan_limit)
            Kind.CONTENT_POLICY -> context.getString(R.string.llm_err_content_policy)
            Kind.OVERLOADED -> context.getString(R.string.llm_err_overloaded)
            Kind.REGION -> context.getString(R.string.llm_err_region)
            Kind.BAD_HEADER -> context.getString(R.string.llm_err_bad_header)
            Kind.INTERNAL -> context.getString(R.string.llm_err_internal)
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
        if (detail.isEmpty()) return explanation
        // The provider only wrote what it wrote; a header or framework failure is the app's own, so it is not "the provider's message".
        val label = if (reason.kind == Kind.BAD_HEADER || reason.kind == Kind.INTERNAL) R.string.llm_err_detail_app else R.string.llm_err_detail
        return explanation + "\n" + context.getString(label, detail)
    }

    private const val DETAIL_CHARS = 240
}

/** A failed model request, shown in the chat as [LLMErrorPresenter] words it. */
internal fun com.openminis.app.agent.AgentSession.setInlineError(e: Throwable) {
    if (e is com.openminis.app.tools.ToolCheckpointStore.Unavailable) {
        setInlineError(context.getString(R.string.tool_checkpoint_unavailable))
        return
    }
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
