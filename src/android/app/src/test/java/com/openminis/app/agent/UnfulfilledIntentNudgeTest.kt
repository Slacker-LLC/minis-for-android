package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnfulfilledIntentNudgeTest {

    private fun nudge(
        text: String,
        finishReason: String? = "stop",
        priorIsToolResult: Boolean = true,
        hasTools: Boolean = true,
        turn: Int = 3,
        turnCap: Int = 200,
        nudgesUsed: Int = 0,
        promptQueued: Boolean = false,
    ) = UnfulfilledIntentNudge.shouldNudge(
        text, finishReason, priorIsToolResult, hasTools, turn, turnCap, nudgesUsed, promptQueued,
    )

    // Replies recorded from mimo-v2.6-flash that ended the turn without a tool call.
    @Test
    fun `recorded announcements without a tool call are nudged`() {
        listOf(
            "我先核对真实状态，而不是直接重跑。",
            "请求已更新，现在实际执行生成：",
            "请求已更新（含 messages 和 prompt），现在实际执行生成：",
            "刚才中断的只是帮助查询（结果已拿到），现在用修正后的参数真正开始生成：",
            "安装时 SELinux 拦截了 system_server 读取 FUSE 路径。按提示把 APK 拷到 `/data/local/tmp/` 再用 root 直接 pm install。",
        ).forEach { assertTrue(it, nudge(it)) }
    }

    @Test
    fun `english announcements are nudged`() {
        assertTrue(nudge("Let me check the file first."))
        assertTrue(nudge("The request failed. I'll retry with the corrected path:"))
    }

    @Test
    fun `a trailing colon alone is enough`() {
        assertTrue(nudge("结果如下："))
    }

    // --- fail-closed cases -------------------------------------------------

    @Test
    fun `a real final answer is left alone`() {
        assertFalse(nudge("FlClash 0.9.0 已成功安装，包名 com.follow.clash。"))
        assertFalse(nudge("好的，已完成。接下来你可以直接打开使用。"))
        assertFalse(nudge("Done. Here's a summary of what I changed."))
    }

    @Test
    fun `long text is an answer not an announcement`() {
        val long = "我先说明一下情况：" + "详细内容".repeat(60)
        assertTrue(long.length > UnfulfilledIntentNudge.MAX_TEXT_CHARS)
        assertFalse(nudge(long))
    }

    @Test
    fun `a question to the user is a legitimate stop`() {
        assertFalse(nudge("我先查一下，你要用哪个城市？"))
        assertFalse(nudge("Should I retry with the other endpoint?"))
    }

    @Test
    fun `asking the user for input is a legitimate stop`() {
        assertFalse(nudge("最快的办法：你直接告诉我在哪个城市，我马上查天气给你。"))
        assertFalse(nudge("请你选择：A 还是 B："))
        assertFalse(nudge("Let me know which one you prefer:"))
    }

    @Test
    fun `empty or blank text is not nudged`() {
        assertFalse(nudge(""))
        assertFalse(nudge("   \n"))
    }

    @Test
    fun `plain text with no intent is left alone`() {
        assertFalse(nudge("端口 8080 被占用。"))
    }

    @Test
    fun `needs a tool result right before the reply`() {
        assertFalse(nudge("我先核对真实状态。", priorIsToolResult = false))
    }

    @Test
    fun `only a clean stop counts`() {
        assertFalse(nudge("现在实际执行：", finishReason = null))
        assertFalse(nudge("现在实际执行：", finishReason = "length"))
        assertFalse(nudge("现在实际执行：", finishReason = "content_filter"))
        assertTrue(nudge("现在实际执行：", finishReason = "end_turn"))
    }

    @Test
    fun `chat only turns are never nudged`() {
        assertFalse(nudge("现在实际执行：", hasTools = false))
    }

    @Test
    fun `a queued user prompt runs instead`() {
        assertFalse(nudge("现在实际执行：", promptQueued = true))
    }

    @Test
    fun `nudges are bounded`() {
        assertTrue(nudge("现在实际执行：", nudgesUsed = UnfulfilledIntentNudge.MAX_CONSECUTIVE - 1))
        assertFalse(nudge("现在实际执行：", nudgesUsed = UnfulfilledIntentNudge.MAX_CONSECUTIVE))
        assertFalse(nudge("现在实际执行：", nudgesUsed = UnfulfilledIntentNudge.MAX_CONSECUTIVE + 5))
    }

    @Test
    fun `the last permitted turn is never nudged`() {
        assertTrue(nudge("现在实际执行：", turn = 197, turnCap = 200))
        assertTrue(nudge("现在实际执行：", turn = 198, turnCap = 200))
        assertFalse(nudge("现在实际执行：", turn = 199, turnCap = 200))
        assertFalse(nudge("现在实际执行：", turn = 250, turnCap = 200))
    }

    @Test
    fun `reminder tells the model to act or finish`() {
        assertEquals(2, UnfulfilledIntentNudge.MAX_CONSECUTIVE)
        assertTrue(UnfulfilledIntentNudge.REMINDER.startsWith("<system-reminder>"))
        assertTrue(UnfulfilledIntentNudge.REMINDER.contains("final answer"))
    }
}
