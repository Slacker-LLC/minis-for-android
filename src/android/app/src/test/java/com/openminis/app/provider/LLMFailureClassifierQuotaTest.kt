package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A vendor's empty-account verdict is permanent, whatever status it arrived under; an overload is not. */
class LLMFailureClassifierQuotaTest {
    private fun code(e: Throwable) = LLMFailureClassifier.classify(e).code

    @Test
    fun anEmptyAccountIsPermanentUnderAnyStatus() {
        val cases = listOf(
            "[429] 1113: Insufficient balance or no resource package. Please recharge.", // Zhipu: overdue sent as 429
            "[400] Arrearage: Access denied, please make sure your account is in good standing.", // DashScope
            "[429] exceeded_current_quota_error: Your account is suspended", // Kimi
            "[402] Insufficient Balance", // DeepSeek
            "[429] 1310: Weekly/Monthly Limit Exhausted. Your limit will reset", // Zhipu
        )
        for (detail in cases) {
            assertEquals(detail, LLMFailureClassifier.CODE_QUOTA_EXCEEDED, code(LLMError.ProviderError(detail)))
            assertFalse(detail, LLMFailureClassifier.classify(LLMError.ProviderError(detail)).retryable)
        }
    }

    @Test
    fun anOverloadedProviderStillRetries() {
        assertEquals(true, LLMFailureClassifier.classify(LLMError.TransientError("[529] overloaded_error: Overloaded")).retryable)
    }
}
