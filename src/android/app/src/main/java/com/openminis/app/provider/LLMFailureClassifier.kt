package com.openminis.app.provider

import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.data.model.LLMError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/**
 * Port of Eta agent/model/AgentModelFailure.kt and the retry gate of
 * agent/model/AgentModelRetry.kt (Mangi-11/Eta @ c15de97), adapted to the Minis
 * provider surface ([LLMError]). Attribution and licence of the original are
 * recorded centrally in PROVENANCE.md / THIRD_PARTY_LICENSES.md; no per-file
 * licence header is added on purpose.
 *
 * Kept from upstream: the transient HTTP status set, the transient and permanent
 * provider error codes, the quota/billing permanent override, the
 * context-overflow detection (codes plus message fragments), the transport
 * mapping (InterruptedIOException to timeout, other IOException to a connection
 * failure, TLS / protocol failures never retried) and the retry gate that
 * refuses to re-issue a request which already produced side effects.
 *
 * Adapted: upstream classified raw HTTP statuses and decoded error objects. In
 * Minis every provider folds its failures into a typed [LLMError] whose detail
 * still carries the provider's own wording ("[503] ...", "[insufficient_quota] ...",
 * "HTTP 400: ..."), so [classify] takes the thrown failure while the two upstream
 * constructors survive as [classifyHttp] and [classifyStreamError] for callers
 * that hold a status code or a decoded error object.
 *
 * Deliberately NOT ported: upstream retries HTTP 429 on the same endpoint
 * (transientStatus). Minis maps a provider-level rate limit to
 * [LLMError.RateLimited] and routes it to group fallback instead - the
 * pre-existing, documented behaviour of the agent loop - so 429 stays
 * non-retryable here while [Classification.isRateLimited] still names it for the
 * fallback decision.
 *
 * The retry budget itself stays with the caller: [decide] takes the caller's
 * delay schedule (the agent loop's AUTO_RETRY_DELAYS_SEC) instead of introducing
 * a second backoff table, and bounds the overflow path with the already-ported
 * [AgentContextBudget.MAX_OVERFLOW_ATTEMPTS].
 */
object LLMFailureClassifier {

    /** The three-way split the analysis doc calls retryable / not retryable / overflow. */
    enum class FailureKind {
        /** Transient: worth another attempt on the same endpoint. */
        RETRYABLE,

        /** Permanent for this turn: quota, billing, auth, parameters, unknown. */
        NON_RETRYABLE,

        /** The provider says the request is larger than the model's window. */
        CONTEXT_OVERFLOW,
    }

    data class Classification(
        val kind: FailureKind,
        val code: String,
        val httpStatus: Int?,
        val message: String,
    ) {
        val retryable: Boolean get() = kind == FailureKind.RETRYABLE
        val overflow: Boolean get() = kind == FailureKind.CONTEXT_OVERFLOW

        /** A provider-level rate limit: Minis answers it with group fallback. */
        val isRateLimited: Boolean get() = code == CODE_RATE_LIMITED

        /** Server-side failure: Minis answers it with retry, then fallback. */
        val isHttp5xx: Boolean get() = httpStatus != null && httpStatus in 500..599
    }

    /**
     * Facts about the failing request that the caller owns.
     *
     * Upstream tracks hostedToolStarted inside AgentModelRetry per attempt. Minis
     * runs no provider-hosted tools and executes managed tools only between the
     * turns of one agent round, so the equivalent signals are "a managed tool has
     * already run in this round" and "a turn of this round is already committed to
     * the transcript". Both are recorded where they happen and checked before any
     * retry, so once a round has produced effects its failures are terminal and the
     * completed tool results stay put.
     */
    data class RequestEffects(
        val toolStarted: Boolean = false,
        val outputCommitted: Boolean = false,
    ) {
        val any: Boolean get() = toolStarted || outputCommitted
    }

    /** What the caller should do about a classified failure. */
    sealed interface Decision {
        /** Re-issue the same request on the same provider after [delaySec]. */
        data class RetrySameProvider(val delaySec: Int, val code: String) : Decision

        /** Context overflow: force a compaction, then re-issue ([attempt] of the cap). */
        data class CompactAndRetry(val attempt: Int, val code: String) : Decision

        /** Stop: no retry, and for a side-effecting round no fallback either. */
        data class Terminal(val code: String, val message: String) : Decision
    }

    const val CODE_CONTEXT_OVERFLOW = "CONTEXT_OVERFLOW"
    const val CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val CODE_RATE_LIMITED = "HTTP_429"
    const val CODE_MODEL_TIMEOUT = "MODEL_TIMEOUT"
    const val CODE_CONNECTION_FAILED = "MODEL_CONNECTION_FAILED"
    const val CODE_TLS_FAILED = "MODEL_TLS_FAILED"
    const val CODE_PROTOCOL_ERROR = "MODEL_PROTOCOL_ERROR"
    const val CODE_STREAM_ERROR = "PROVIDER_STREAM_ERROR"
    const val CODE_UNCLASSIFIED = "UNCLASSIFIED_FAILURE"

    /** Upstream transientStatus. 429 is listed because upstream retries it. */
    private val TRANSIENT_STATUS = setOf(408, 429, 500, 502, 503, 504, 524, 529)

    /** Upstream permanentCodes: quota and billing failures never retry. */
    private val PERMANENT_CODES = setOf(
        "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
    )

    /** Upstream transientCodes. */
    private val TRANSIENT_CODES = setOf(
        "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
        "api_error", "internal_error", "provider_unavailable", "service_unavailable",
    )

    /** Upstream isContextOverflow code set. */
    private val OVERFLOW_CODES = setOf(
        "context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long",
    )

    /** Upstream isContextOverflow message fragments. */
    private val OVERFLOW_MESSAGES = listOf(
        "maximum context length",
        "prompt is too long",
        "exceeds the context window",
        "input token count exceeds",
    )

    /** Upstream isPermanent body fragments. */
    private val PERMANENT_TEXT = listOf(
        "insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit",
    )

    /**
     * A bare 4xx/5xx token, not a digit run inside a larger number
     * ("max_tokens=4096" must not read as HTTP 409). Upstream had the status as
     * an Int; Minis often only has it inside the detail string.
     */
    private val STATUS_REGEX = Regex("(?<![0-9])(4[0-9]{2}|5[0-9]{2})(?![0-9])")

    private val OVERFLOW_CODE_REGEX = tokenRegex(OVERFLOW_CODES)
    private val TRANSIENT_CODE_REGEX = tokenRegex(TRANSIENT_CODES)
    private val PERMANENT_CODE_REGEX = tokenRegex(PERMANENT_CODES)

    private fun tokenRegex(values: Set<String>): Regex =
        Regex("(?<![a-z0-9_])(" + values.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9_])")

    /** Classify a thrown failure into one of the three kinds. */
    fun classify(failure: Throwable): Classification = when (failure) {
        is LLMError.Cancelled -> permanent("CANCELLED", failure.message ?: "请求已取消。")
        is LLMError.InvalidApiKey -> permanent(
            "HTTP_401",
            "模型接口认证失败（HTTP 401/403），请检查 API Key 与账户权限。",
            httpStatus = 401,
        )
        is LLMError.RateLimited -> permanent(
            CODE_RATE_LIMITED,
            "模型接口暂时限流（HTTP 429）。",
            httpStatus = 429,
        )
        is LLMError.NetworkError -> transport(failure.cause ?: failure)
        // The provider already reached a transient verdict (5xx family,
        // server_error, rate_limit_exceeded); only the permanent overrides and
        // the overflow code can downgrade it.
        is LLMError.TransientError -> transientDetail(failure.detail)
        is LLMError.ProviderError -> providerDetail(failure.detail)
        is LLMError.DecodingError -> permanent(
            "DECODING_FAILED",
            "模型响应无法解析：" + (failure.message ?: ""),
        )
        is LLMError.Unknown -> permanent(
            CODE_UNCLASSIFIED,
            failure.message ?: "模型请求失败（未分类）。",
        )
        else -> transport(failure)
    }

    /** Convenience for call sites that only ask "may I try again?" (retry policy). */
    fun isRetryable(failure: Throwable): Boolean = classify(failure).retryable

    /** Upstream AgentModelFailure.http: status plus (possibly decoded) body. */
    fun classifyHttp(status: Int, body: String): Classification {
        if (isContextOverflowText(body)) return overflow()
        if (isPermanentText(body)) {
            return permanent(
                CODE_QUOTA_EXCEEDED,
                "模型接口额度或计费受限（HTTP " + status + "），请检查服务商账户。",
                httpStatus = status,
            )
        }
        return when (status) {
            400 -> permanent("HTTP_400", "模型请求参数无效（HTTP 400），请检查模型配置。", status)
            401, 403 -> permanent(
                "HTTP_" + status,
                "模型接口认证失败（HTTP " + status + "），请检查 API Key 与账户权限。",
                status,
            )
            404 -> permanent(
                "HTTP_404",
                "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。",
                status,
            )
            429 -> permanent(CODE_RATE_LIMITED, "模型接口暂时限流（HTTP 429）。", status)
            in TRANSIENT_STATUS -> retryable("HTTP_" + status, "模型接口返回 HTTP " + status + "。", status)
            else -> permanent("HTTP_" + status, "模型接口返回 HTTP " + status + "。", status)
        }
    }

    /**
     * Upstream AgentModelFailure.stream: a decoded in-stream error object. The
     * caller passes the fields the provider exposed (code, type, message).
     */
    fun classifyStreamError(code: String?, type: String?, message: String): Classification {
        val errorCode = code.orEmpty()
        val errorType = type.orEmpty()
        if (isContextOverflowText(message) || errorCode in OVERFLOW_CODES || errorType in OVERFLOW_CODES) {
            return overflow()
        }
        if (isPermanentText(message) || errorCode in PERMANENT_CODES || errorType in PERMANENT_CODES) {
            return permanent(CODE_QUOTA_EXCEEDED, "模型接口额度或计费受限，请检查服务商账户。")
        }
        val numericCode = errorCode.toIntOrNull()
        val numericType = errorType.toIntOrNull()
        val transient = errorCode in TRANSIENT_CODES || errorType in TRANSIENT_CODES ||
            (numericCode != null && numericCode in TRANSIENT_STATUS) ||
            (numericType != null && numericType in TRANSIENT_STATUS)
        return if (transient) {
            retryable(CODE_STREAM_ERROR, message.ifBlank { "模型流式接口返回了可重试的错误。" })
        } else {
            permanent(CODE_STREAM_ERROR, message.ifBlank { "模型流式接口返回错误。" })
        }
    }

    /** Upstream isContextOverflow over free text (Minis keeps details as text). */
    fun isContextOverflowText(text: String): Boolean {
        val lower = text.lowercase()
        if (OVERFLOW_CODE_REGEX.containsMatchIn(lower)) return true
        return OVERFLOW_MESSAGES.any { lower.contains(it) }
    }

    /**
     * Upstream AgentModelRetry.complete's retry gate plus AgentLoop.kt:129-140's
     * overflow recovery, expressed as one decision.
     *
     * @param retriesUsed same-provider retries this turn already spent.
     * @param retryDelaysSec the caller's schedule; its size is the retry budget.
     * @param overflowAttemptsUsed compactions already spent on this turn's overflow.
     */
    fun decide(
        classification: Classification,
        retriesUsed: Int,
        retryDelaysSec: IntArray,
        overflowAttemptsUsed: Int,
        effects: RequestEffects = RequestEffects(),
    ): Decision {
        // Upstream refuses to retry once the request produced side effects
        // (AgentModelRetry.kt:45-47, recoveryAllowed = false): terminal, which also
        // disables the overflow recovery below exactly as AgentLoop.kt:130 checks
        // recoveryAllowed.
        if (effects.any) {
            val which = mutableListOf<String>()
            if (effects.toolStarted) which.add("本轮已经执行过工具")
            if (effects.outputCommitted) which.add("本轮已经提交过输出")
            return Decision.Terminal(
                classification.code,
                classification.message + "（" + which.joinToString("；") +
                    "，失败即终态，不再重试；已完成的工具结果已保留。）",
            )
        }
        if (classification.overflow) {
            return if (overflowAttemptsUsed < AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) {
                Decision.CompactAndRetry(overflowAttemptsUsed + 1, classification.code)
            } else {
                Decision.Terminal(
                    classification.code,
                    "模型上下文超过容量，已压缩 " + overflowAttemptsUsed + " 次仍未通过（上限 " +
                        AgentContextBudget.MAX_OVERFLOW_ATTEMPTS +
                        "）。请开始新会话或手动压缩后重试；已完成的工具结果已保留。",
                )
            }
        }
        if (!classification.retryable) {
            return Decision.Terminal(classification.code, classification.message)
        }
        if (retriesUsed >= retryDelaysSec.size) {
            return Decision.Terminal(
                classification.code,
                classification.message + " 已重试 " + retryDelaysSec.size +
                    " 次仍未恢复，已保留此前完成的工具结果。",
            )
        }
        return Decision.RetrySameProvider(retryDelaysSec[retriesUsed], classification.code)
    }

    /**
     * Upstream transport(): timeouts and dropped connections are transient; TLS and
     * protocol failures are not. Upstream returns null for the latter two (the raw
     * exception propagates and is never retried); this port names the outcome so
     * callers do not have to guess.
     */
    private fun transport(failure: Throwable): Classification = when (failure) {
        is InterruptedIOException -> retryable(
            CODE_MODEL_TIMEOUT,
            "模型请求等待超时（连接、写入或读取），请检查网络后重试。",
        )
        is SSLException -> permanent(
            CODE_TLS_FAILED,
            "模型连接的 TLS 握手或证书校验失败，重试通常无法恢复：" + (failure.message ?: ""),
        )
        is ProtocolException -> permanent(
            CODE_PROTOCOL_ERROR,
            "模型接口返回了不符合协议的数据：" + (failure.message ?: ""),
        )
        is IOException -> retryable(
            CODE_CONNECTION_FAILED,
            "模型连接中断或暂时无法建立，请检查网络与服务商状态。",
        )
        else -> permanent(
            CODE_UNCLASSIFIED,
            failure.message ?: "模型请求失败（" + failure.javaClass.simpleName + "）。",
        )
    }

    /** A typed transient failure, with the permanent / overflow overrides applied. */
    private fun transientDetail(detail: String): Classification {
        if (isContextOverflowText(detail)) return overflow()
        if (isPermanentText(detail)) {
            return permanent(CODE_QUOTA_EXCEEDED, "模型接口额度或计费受限，请检查服务商账户。")
        }
        val status = httpStatusIn(detail)
        return if (status != null && status in TRANSIENT_STATUS) {
            retryable("HTTP_" + status, "模型接口返回 HTTP " + status + "。", status)
        } else {
            retryable(CODE_STREAM_ERROR, detail.ifBlank { "模型流式接口返回了可重试的错误。" })
        }
    }

    /**
     * A non-transient provider failure: the detail still names the provider's own
     * reason, so overflow, quota, transient codes and HTTP statuses are all read
     * back out of it before the failure is declared permanent.
     */
    private fun providerDetail(detail: String): Classification {
        if (isContextOverflowText(detail)) return overflow()
        if (isPermanentText(detail)) {
            return permanent(CODE_QUOTA_EXCEEDED, "模型接口额度或计费受限，请检查服务商账户。")
        }
        if (TRANSIENT_CODE_REGEX.containsMatchIn(detail.lowercase())) {
            return retryable(CODE_STREAM_ERROR, detail)
        }
        val status = httpStatusIn(detail)
        if (status != null) return classifyHttp(status, detail)
        return permanent(CODE_UNCLASSIFIED, detail.ifBlank { "模型接口返回错误。" })
    }

    private fun isPermanentText(text: String): Boolean {
        val lower = text.lowercase()
        if (PERMANENT_CODE_REGEX.containsMatchIn(lower)) return true
        return PERMANENT_TEXT.any { lower.contains(it) }
    }

    private fun httpStatusIn(text: String): Int? =
        STATUS_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun overflow() = Classification(
        FailureKind.CONTEXT_OVERFLOW,
        CODE_CONTEXT_OVERFLOW,
        null,
        "模型上下文超过容量限制。",
    )

    private fun retryable(code: String, message: String, httpStatus: Int? = null) =
        Classification(FailureKind.RETRYABLE, code, httpStatus, message)

    private fun permanent(code: String, message: String, httpStatus: Int? = null) =
        Classification(FailureKind.NON_RETRYABLE, code, httpStatus, message)
}
