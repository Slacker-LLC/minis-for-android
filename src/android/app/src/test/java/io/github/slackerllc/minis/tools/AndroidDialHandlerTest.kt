package io.github.slackerllc.minis.tools

import io.github.slackerllc.minis.tools.runtime.TestContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDialHandlerTest {
    @Test
    fun `purpose refuses unsupported call takeover before any dial intent`() = runBlocking {
        val result = AndroidDialHandler().execute(
            """{"phone_number":"5550100","purpose":"sell insurance"}""",
            sessionId = "test",
            context = TestContext.dummy(),
            toolId = "",
        )
        assertFalse(result.success)
        assertTrue(result.output.contains("call_takeover_unsupported"))
    }
}
