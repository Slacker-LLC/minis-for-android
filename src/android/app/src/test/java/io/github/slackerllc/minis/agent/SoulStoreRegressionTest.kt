package io.github.slackerllc.minis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulStoreRegressionTest {

    @Test
    fun `name style lang and body survive serialize parse round trip`() {
        val original = SoulFile(
            metadata = SoulMetadata(
                name = "小米尼斯",
                emoji = "",
                style = "简洁、直接，保留 code 格式",
                lang = "zh",
            ),
            body = """第一行
第二行包含 \"quotes\"、'apostrophe'、$、`backticks` 和 \\slashes。

最后一段保持空行。""",
        )

        val serialized = SoulMDParser.serialize(original)
        val parsed = SoulMDParser.parse(serialized)

        assertEquals(original.metadata.name, parsed.metadata.name)
        assertEquals(original.metadata.style, parsed.metadata.style)
        assertEquals(original.metadata.lang, parsed.metadata.lang)
        assertEquals(original.body, parsed.body.trimEnd())
    }

    @Test
    fun `very long Chinese body is not rejected`() {
        val body = "人格正文".repeat(20_000)
        assertFalse(SoulStore.isOverLimit(body).isOverLimit)
        assertTrue(SoulStore.isOverLimit(body) is SoulBodyLimitCheck.Ok)
    }

    @Test
    fun `very long English body is not rejected`() {
        val body = List(20_000) { "personality" }.joinToString(" ")
        assertFalse(SoulStore.isOverLimit(body).isOverLimit)
        assertTrue(SoulStore.isOverLimit(body) is SoulBodyLimitCheck.Ok)
    }

    @Test
    fun `serialize does not truncate long body`() {
        val body = buildString {
            repeat(10_000) { i -> append("line-").append(i).append(" value\\n") }
        }
        val serialized = SoulMDParser.serialize(
            SoulFile(
                metadata = SoulMetadata.DEFAULT.copy(name = "LongSoul", style = "full", lang = "auto"),
                body = body,
            ),
        )
        val parsed = SoulMDParser.parse(serialized)

        assertEquals("LongSoul", parsed.metadata.name)
        assertEquals("full", parsed.metadata.style)
        assertEquals("auto", parsed.metadata.lang)
        assertEquals(body, parsed.body.trimEnd())
    }
}
