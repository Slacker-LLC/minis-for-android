package com.openminis.app.voicecall.runtime

import com.openminis.app.voicecall.ports.ApprovalDecision

private val DENY_WORDS = listOf("不", "别", "没", "拒绝", "禁止", "否", "取消", "停")
private val DENY_LATIN = setOf("no", "nope", "deny", "denied", "refuse", "reject", "stop", "cancel", "dont")
private val ALLOW_WORDS = listOf("允许", "同意", "可以", "批准", "通过", "好", "行", "是", "对", "确认", "继续")
private val ALLOW_LATIN = setOf("allow", "yes", "yeah", "yep", "ok", "okay", "approve", "sure", "go", "continue")
private val SESSION_WORDS = listOf("本次会话", "这次会话", "本会话", "总是", "一直", "始终", "都允许", "以后")
private val SESSION_LATIN = setOf("always", "session")

/** 允许类回答只认短句：一长串话里碰巧有个「好」不能当成授权。 */
private const val MAX_ALLOW_CJK_CHARS = 14
private const val MAX_ALLOW_WORDS = 6

/**
 * 把口头回答解析成权限决定；听不懂返回 null（调用方追问，不替用户决定）。
 * 偏向拒绝：出现任何否定/拒绝词一律按拒绝，哪怕同一句里还有「允许」。
 */
internal fun parseApprovalPhrase(raw: String): ApprovalDecision? {
    val text = raw.lowercase().replace("'", "").replace("’", "")
    val compact = text.filter { it.isLetterOrDigit() }
    if (compact.isEmpty()) return null
    val words = text.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    val latin = words.toSet()
    if (DENY_WORDS.any { compact.contains(it) } || latin.any { it in DENY_LATIN }) return ApprovalDecision.DENY
    if (compact.count { it.code >= 0x2E80 } > MAX_ALLOW_CJK_CHARS || words.size > MAX_ALLOW_WORDS) return null
    val allowed = ALLOW_WORDS.any { compact.contains(it) } || latin.any { it in ALLOW_LATIN }
    if (!allowed) return null
    val wholeSession = SESSION_WORDS.any { compact.contains(it) } || latin.any { it in SESSION_LATIN }
    return if (wholeSession) ApprovalDecision.ALLOW_SESSION else ApprovalDecision.ALLOW_ONCE
}
