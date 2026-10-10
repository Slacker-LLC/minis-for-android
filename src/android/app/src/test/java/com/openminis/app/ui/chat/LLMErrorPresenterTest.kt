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

    private fun bad(body: String) = kind(LLMError.ProviderError("[400] $body"))

    @Test
    fun aMissingToolResultIsToldApartFromTheGeneral400() {
        assertEquals(Kind.BAD_TOOL_PAIRING, bad("No tool output found for function call call_abc123."))
        assertEquals(
            Kind.BAD_TOOL_PAIRING,
            bad("An assistant message with 'tool_calls' must be followed by tool messages responding to each 'tool_call_id'."),
        )
        assertEquals(Kind.BAD_TOOL_PAIRING, bad("messages.3: `tool_use` ids were found without `tool_result` blocks immediately after"))
    }

    @Test
    fun otherCommon400sAreToldApart() {
        assertEquals(Kind.BAD_CONTEXT, bad("This model's maximum context length is 131072 tokens"))
        assertEquals(Kind.BAD_CONTEXT, bad("prompt is too long: 250000 tokens > 200000 maximum"))
        assertEquals(Kind.BAD_IMAGE, bad("image input is not supported by this model"))
        assertEquals(Kind.BAD_IMAGE, bad("Invalid image data"))
        assertEquals(Kind.BAD_MODEL, bad("The model `gpt-9` does not exist"))
        assertEquals(Kind.BAD_THINKING, bad("Invalid value for reasoning_effort"))
        assertEquals(Kind.BAD_THINKING, bad("thinking.budget_tokens: must be >= 1024"))
    }

    @Test
    fun a400ThatOnlyLooksLikeOneOfThemStaysGeneral() {
        // A too-long tool_call_id is a different fault from a missing result.
        assertEquals(Kind.BAD_REQUEST, bad("Invalid 'messages[3].tool_call_id': string too long. Expected a string with maximum length 64"))
        assertEquals(Kind.BAD_REQUEST, bad("messages: text content blocks must be non-empty"))
        assertEquals(Kind.BAD_REQUEST, bad(""))
    }

    @Test
    fun onlyA400IsRefined() {
        // The same words under another status keep that status's meaning.
        assertEquals(Kind.TOO_LARGE, kind(LLMError.ProviderError("HTTP 413: maximum context length exceeded")))
        assertEquals(Kind.NOT_FOUND, kind(LLMError.ProviderError("HTTP 404: model does not exist")))
        assertEquals(Kind.SERVER, kind(LLMError.ProviderError("[500] No tool output found for function call x")))
        assertEquals(Kind.REJECTED, kind(LLMError.ProviderError("[422] image input is not supported")))
    }
}
