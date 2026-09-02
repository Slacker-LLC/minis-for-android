package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the streaming inline-markdown split scanner. */
class SafeInlineSplitOffsetTest {

    private fun pad(lines: Int = 90) =
        (1..lines).joinToString("\n") { "$it. 这是一行足够长的正文内容用于撑开增量解析的阈值" } + "\n"

    @Test
    fun `inline code containing double star does not poison the split`() {
        val text = "1. 通配符写法 `a**b` 注意\n" + pad() + "z".repeat(300)
        assertTrue("expected a reusable boundary", safeInlineSplitOffset(text) > 0)
    }

    @Test
    fun `unclosed backtick is treated as literal text`() {
        val text = "1. 孤立反引号 ` 后面还有 **加粗** 内容\n" + pad() + "z".repeat(300)
        assertTrue("expected a reusable boundary", safeInlineSplitOffset(text) > 0)
    }

    @Test
    fun `open bold span still blocks the split`() {
        val text = "1. 开头 **还没有闭合\n" + pad() + "z".repeat(300)
        assertEquals(0, safeInlineSplitOffset(text))
    }

    @Test
    fun `offset lands immediately after a newline`() {
        val text = "1. 代码 `a**b` 与 **加粗** 混排\n" + pad() + "z".repeat(300)
        val offset = safeInlineSplitOffset(text)
        assertTrue(offset > 0)
        assertEquals('\n', text[offset - 1])
    }

    @Test
    fun `short input yields no split`() {
        assertEquals(0, safeInlineSplitOffset("1. 很短的内容\n"))
    }
}
