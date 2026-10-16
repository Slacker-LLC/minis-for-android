package com.openminis.app.voicecall

import com.openminis.app.voicecall.ports.ApprovalDecision
import com.openminis.app.voicecall.runtime.parseApprovalPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalPhrasesTest {
    @Test
    fun `plain allow phrases allow once`() {
        listOf("允许", "同意。", "可以", "好的", "Yes", "OK, allow it").forEach {
            assertEquals(it, ApprovalDecision.ALLOW_ONCE, parseApprovalPhrase(it))
        }
    }

    @Test
    fun `session wording allows for the session`() {
        listOf("本次会话都允许", "总是允许", "allow for this session", "always allow").forEach {
            assertEquals(it, ApprovalDecision.ALLOW_SESSION, parseApprovalPhrase(it))
        }
    }

    @Test
    fun `deny wording denies`() {
        listOf("拒绝", "不允许", "不要", "别", "No", "don't allow it", "deny").forEach {
            assertEquals(it, ApprovalDecision.DENY, parseApprovalPhrase(it))
        }
    }

    @Test
    fun `any negation beats an allow word - fail closed`() {
        listOf("不可以", "允许个鬼，不行", "好的，不过先别", "不是，拒绝", "不要拒绝").forEach {
            assertEquals(it, ApprovalDecision.DENY, parseApprovalPhrase(it))
        }
    }

    @Test
    fun `unrelated or long speech is not a decision`() {
        listOf("", "   ", "。。。", "今天天气怎么样", "帮我把这个文件好好整理一下然后发给我", "嗯").forEach {
            assertNull(it, parseApprovalPhrase(it))
        }
    }

    @Test
    fun `latin words are matched as whole words`() {
        assertNull(parseApprovalPhrase("I know the answer"))
        assertNull(parseApprovalPhrase("note this"))
    }
}
