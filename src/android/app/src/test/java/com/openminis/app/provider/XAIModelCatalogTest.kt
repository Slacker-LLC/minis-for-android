package com.openminis.app.provider

import com.openminis.app.provider.rules.ModelRulesProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** xAI 静态目录的上下文窗口，对照 docs.x.ai/developers/models（grok-4.7 为 500k，models.dev 目录里还没有它）。 */
class XAIModelCatalogTest {
    @Before
    fun installRules() = ModelRulesTestFixtures.installBundledCatalog()

    @Test
    fun `grok 4 7 declares its 500k context window`() {
        val model = ModelRulesProvider.staticModels("xAI").single { it.id == "grok-4.7" }
        assertEquals(500_000, model.contextWindowTokens)
    }
}
