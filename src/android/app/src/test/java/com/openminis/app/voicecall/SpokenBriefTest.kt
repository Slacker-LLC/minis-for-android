package com.openminis.app.voicecall

import com.openminis.app.voicecall.runtime.BRIEF_INPUT_CHARS
import com.openminis.app.voicecall.runtime.BRIEF_MAX_CHARS
import com.openminis.app.voicecall.runtime.BRIEF_SYSTEM_PROMPT
import com.openminis.app.voicecall.runtime.DETAIL_MAX_CHARS
import com.openminis.app.voicecall.runtime.VOICE_CALL_PROMPT
import com.openminis.app.voicecall.runtime.acceptBrief
import com.openminis.app.voicecall.runtime.buildBriefRequest
import com.openminis.app.voicecall.runtime.detailForSpeech
import com.openminis.app.voicecall.runtime.fallbackBrief
import com.openminis.app.voicecall.runtime.isSpeakableAsIs
import com.openminis.app.voicecall.runtime.stripForSpeech
import com.openminis.app.voicecall.runtime.wantsDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenBriefTest {
    private val withCode = "已经修好了。\n\n```kotlin\nfun main() { println(\"hi\") }\n```\n\n| 文件 | 状态 |\n|---|---|\n| a.kt | ok |\n\n详见 [文档](https://x.y/z)。"

    @Test
    fun `strip removes code tables urls and markdown marks`() {
        val out = stripForSpeech("# 标题\n- **要点**一\n1. 要点二\n```\ncode\n```\n| a | b |\n|---|---|\n看 [这里](http://x.y) http://z.w")
        assertFalse(out.contains("```") || out.contains("code") || out.contains("|") || out.contains("http") || out.contains("**") || out.contains("#"))
        assertTrue(out.contains("标题") && out.contains("要点一") && out.contains("要点二") && out.contains("这里"))
    }

    @Test
    fun `short clean replies are spoken as is and long or formatted ones are not`() {
        assertTrue(isSpeakableAsIs("好的，已经打开了。"))
        assertFalse(isSpeakableAsIs("a".repeat(201)))
        assertFalse(isSpeakableAsIs("结果：\n```\nx\n```"))
        assertFalse(isSpeakableAsIs("| a | b |\n|---|---|"))
        assertFalse(isSpeakableAsIs("一\n二\n三\n四"))
        assertFalse(isSpeakableAsIs("   "))
    }

    @Test
    fun `fallback brief never reads the whole reply`() {
        val long = "第一段结论。" + "很长的补充说明，".repeat(200) + "\n\n第二段不该被念。"
        val brief = fallbackBrief(long)
        assertTrue(brief.length <= BRIEF_MAX_CHARS + 1)
        assertFalse(brief.contains("第二段"))
        assertEquals("已经修好了。", fallbackBrief(withCode))
        assertEquals("", fallbackBrief("```\nonly code\n```"))
    }

    @Test
    fun `explicit detail requests are recognised and ordinary ones are not`() {
        assertTrue(wantsDetail("详细说说"))
        assertTrue(wantsDetail("把结果念出来"))
        assertTrue(wantsDetail("read it out please"))
        assertTrue(wantsDetail("explain in detail"))
        assertFalse(wantsDetail("帮我改一下配置"))
        assertFalse(wantsDetail("今天天气怎么样"))
    }

    @Test
    fun `detail is stripped and capped by the output budget`() {
        val out = detailForSpeech("结论。\n\n" + "细节。".repeat(1000) + "\n\n```\ncode\n```")
        assertTrue(out.length <= DETAIL_MAX_CHARS + 1)
        assertFalse(out.contains("code") || out.contains("```"))
    }

    @Test
    fun `brief request bounds the input and keeps the user's words`() {
        val req = buildBriefRequest("改一下", "x".repeat(BRIEF_INPUT_CHARS * 3))
        assertTrue(req.contains("改一下"))
        assertTrue(req.length < BRIEF_INPUT_CHARS + 400)
    }

    @Test
    fun `model brief is rejected when empty formatted away or too long`() {
        assertEquals("已改好，细节在屏幕上。", acceptBrief("已改好，细节在屏幕上。"))
        assertEquals("已改好", acceptBrief("**已改好**"))
        assertNull(acceptBrief(null))
        assertNull(acceptBrief("   "))
        assertNull(acceptBrief("```\nonly code\n```"))
        assertNull(acceptBrief("长".repeat(BRIEF_MAX_CHARS + 1)))
    }

    @Test
    fun `presenter prompt carries the codex presentation rules`() {
        assertTrue(BRIEF_SYSTEM_PROMPT.contains("key takeaway"))
        assertTrue(BRIEF_SYSTEM_PROMPT.contains("Never read out tables, diffs, code blocks"))
        assertTrue(BRIEF_SYSTEM_PROMPT.contains("do not claim anything is done unless the reply says it is done"))
        assertTrue(VOICE_CALL_PROMPT.contains("last for the whole task"))
    }
}
