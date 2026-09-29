package com.openminis.app.provider

import com.openminis.app.provider.rules.ModelRulesDocument
import com.openminis.app.provider.rules.ModelRulesParser
import com.openminis.app.provider.rules.ModelRulesProvider
import java.io.File
import org.junit.Assert.assertTrue

internal object ModelRulesTestFixtures {
    private fun assetFile(): File {
        val candidates = listOf(
            File("src/main/assets/model-rules.json"),
            File("app/src/main/assets/model-rules.json"),
            File("src/android/app/src/main/assets/model-rules.json"),
        )
        val asset = candidates.firstOrNull(File::isFile)
        assertTrue("Unable to locate the built-in model-rules.json from ${System.getProperty("user.dir")}", asset != null)
        return requireNotNull(asset)
    }

    fun bundledDocumentText(): String = assetFile().readText(Charsets.UTF_8)

    fun bundledDocument(): ModelRulesDocument =
        requireNotNull(ModelRulesParser.parse(bundledDocumentText()))

    fun installBundledCatalog() {
        ModelRulesProvider.installForTests(bundledDocument())
    }

    fun staticModel(providerKey: String, modelId: String) = bundledDocument().let { document ->
        ModelRulesProvider.installForTests(document)
        requireNotNull(document.staticModels[providerKey]?.firstOrNull { it.id == modelId }) {
            "Missing $modelId in model-rules.json staticModels.$providerKey"
        }
    }
}
