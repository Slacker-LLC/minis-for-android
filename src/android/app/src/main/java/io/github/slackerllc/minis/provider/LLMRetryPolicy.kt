package io.github.slackerllc.minis.provider

import io.github.slackerllc.minis.data.model.LLMError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Unified LLM call retry policy (DeepSeek Harness dsh-llm-retry contract,
 * normal mode): retry transient failures on the SAME provider with bounded
 * exponential backoff plus jitter, then give up so the caller can fall back
 * to the next model. The main agent loop in ChatViewModel already implements
 * its own retry with a user-visible countdown; this policy is for every
 * OTHER call site (pet chat, model-use offload, vision group, voice
 * correction, compaction summaries) so network blips and 5xx do not surface
 * as hard failures there either.
 *
 * Retryable classes map to the DSH code set:
 *   TRANSPORT  -> LLMError.NetworkError
 *   SERVER     -> 5xx ProviderError
 *   TIMEOUT / EMPTY_RESPONSE -> LLMError.TransientError
 * Non-retryable: InvalidApiKey, RateLimited (provider-level, fall back
 * instead), DecodingError, Cancelled, Unknown.
 */
object LLMRetryPolicy {
    const val MAX_RETRIES = 2

    /** Base delays in ms before each retry attempt: 500ms, 1s, 2s (bounded). */
    private val BASE_DELAYS_MS = listOf(500L, 1_000L, 2_000L)

    /** 10% symmetric jitter, like the DSH normal mode. */
    private fun jittered(delayMs: Long): Long {
        val jitter = (delayMs * 0.10).toLong().coerceAtLeast(1L)
        return (delayMs - jitter + Random.nextLong(2 * jitter + 1)).coerceAtLeast(50L)
    }

    fun isRetryable(t: Throwable): Boolean = when (t) {
        is CancellationException -> false
        is LLMError.NetworkError -> true
        is LLMError.TransientError -> true
        is LLMError.ProviderError -> t.detail.contains(Regex("[5][0-9]{2}"))
        else -> false
    }

    /**
     * Run [block], retrying transient failures up to [MAX_RETRIES] times
     * with jittered exponential backoff. Cancellation is never swallowed.
     * The final failure is rethrown for the caller to handle (fallback etc.).
     */
    suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (attempt >= MAX_RETRIES || !isRetryable(t)) throw t
                val delayMs = jittered(BASE_DELAYS_MS.getOrElse(attempt) { BASE_DELAYS_MS.last() })
                attempt += 1
                android.util.Log.w(
                    "LLMRetryPolicy",
                    "retry $attempt/$MAX_RETRIES after ${t.message ?: t.javaClass.simpleName} in ${delayMs}ms",
                )
                delay(delayMs)
            }
        }
    }
}
