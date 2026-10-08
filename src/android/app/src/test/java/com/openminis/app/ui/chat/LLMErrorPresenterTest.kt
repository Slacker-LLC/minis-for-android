package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMError
import com.openminis.app.ui.chat.LLMErrorPresenter.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LLMErrorPresenterTest {
    private fun kind(e: Throwable) = LLMErrorPresenter.classify(e)?.kind

    @Test
    fun statusIsReadFromEachProvidersWording() {
        assertEquals(400, LLMErrorPresenter.statusOf("[400] Invalid value for reasoning_effort"))
        assertEquals(413, LLMErrorPresenter.statusOf("HTTP 413: Payload Too Large"))
        assertEquals(404, LLMErrorPresenter.statusOf("Gemini API error 404: models/x is not found"))
        assertEquals(400, LLMErrorPresenter.statusOf("[invalid_request_error] messages: too long"))
        assertEquals(529, LLMErrorPresenter.statusOf("[overloaded_error] Overloaded"))
        assertNull(LLMErrorPresenter.statusOf("something went wrong"))
    }

    @Test
    fun aProviderErrorIsClassifiedByItsStatus() {
        assertEquals(Kind.BAD_REQUEST, kind(LLMError.ProviderError("[400] bad")))
        assertEquals(Kind.NOT_FOUND, kind(LLMError.ProviderError("HTTP 404: no model")))
        assertEquals(Kind.TOO_LARGE, kind(LLMError.ProviderError("HTTP 413: big")))
        assertEquals(Kind.REJECTED, kind(LLMError.ProviderError("[422] unprocessable")))
        assertEquals(Kind.AUTH, kind(LLMError.ProviderError("[401] no")))
        assertEquals(Kind.SERVER, kind(LLMError.ProviderError("[500] boom")))
        assertEquals(Kind.OTHER, kind(LLMError.ProviderError("images/edits requires at least one input image")))
    }

    @Test
    fun typedErrorsKeepTheirMeaning() {
        assertEquals(Kind.AUTH, kind(LLMError.InvalidApiKey()))
        assertEquals(Kind.RATE_LIMIT, kind(LLMError.RateLimited()))
        assertEquals(Kind.SERVER, kind(LLMError.TransientError("[503] busy")))
        assertEquals(Kind.NETWORK, kind(LLMError.NetworkError(java.io.IOException("x"))))
    }

    @Test
    fun anErrorThatIsNotAnLLMErrorIsLeftAlone() {
        assertNull(LLMErrorPresenter.classify(IllegalStateException("tool failed")))
    }
}
