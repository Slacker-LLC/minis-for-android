package com.openminis.app.voicecall.runtime

/**
 * 通话里「念什么」：完整回复留在屏幕（聊天记录）上，语音只念一句结论。
 *
 * 对齐 Codex 语音模式（openai/codex，codex-rs）：
 * - 干活的一方输出很长时，交给语音那一侧之前先按预算截断
 *   （`core/src/realtime_conversation.rs` 的 `REALTIME_ASSISTANT_OUTPUT_TOKEN_BUDGET`，1000 token）；
 * - 语音那一侧只简短说结论/状态/下一步，不念表格、diff、代码块、结构化数据，
 *   用户明确要求时才详细说（`prompts/templates/realtime/backend_prompt.md` 的 "Presenting backend results"）。
 * Codex 里这一步由独立的语音模型完成；我们只有一个干活的模型，所以用一次轻量的概括调用代替，
 * 失败时退化为纯文本规则，绝不把整篇原文念出来。
 */

/** 交给概括调用的原文上限（字符）。超过的部分在概括前就截掉。 */
internal const val BRIEF_INPUT_CHARS = 4_000

/** 回复本身已经足够短且是纯口语时直接念，不再多一次模型调用。 */
private const val AS_IS_MAX_CHARS = 200
private const val AS_IS_MAX_LINES = 3

/** 一句结论念出来的上限；概括结果超过这个长度视为没概括好，改用规则退化。 */
internal const val BRIEF_MAX_CHARS = 280

/** 用户明确要求详细时，最多念这么多字（对应 Codex 的输出预算）。 */
internal const val DETAIL_MAX_CHARS = 1_000

/** 概括调用的最长等待；超时就用规则退化，不让用户干等。 */
internal const val BRIEF_TIMEOUT_MS = 8_000L

internal const val BRIEF_SYSTEM_PROMPT = """You turn an assistant's written reply into what a voice assistant says out loud. The user can already see the full reply on screen.
- Say the key takeaway, status, or next step in one to three short spoken sentences. Do not repeat what is visible unless it is the answer itself.
- Never read out tables, diffs, code blocks, URLs, file contents, logs or other structured or heavily formatted content. Mention where it is instead (for example "the details are on screen").
- Report only what the reply states. Do not add facts, do not claim anything is done unless the reply says it is done, and keep any failure or open question.
- Write plain speakable text: no markdown, no lists, no emoji. Use the same language as the reply.
- Output only the spoken text."""

private val DETAIL_REQUEST = Regex(
    "详细|详情|展开|具体(说|讲|念|读)|完整|全部(念|读|说)|念(一下|出来|给我)|读(一下|出来|给我)|逐条|一条一条|read (it|that) (out|aloud)|in detail|full (answer|reply|text)|all of it",
    RegexOption.IGNORE_CASE,
)

/** 用户是不是明确要求念详细内容。只有这时才念长版（对应 Codex「仅在用户明确要求时详细呈现」）。 */
internal fun wantsDetail(userText: String): Boolean = DETAIL_REQUEST.containsMatchIn(userText)

private val CODE_FENCE = Regex("```[\\s\\S]*?(```|$)")
private val TABLE_LINE = Regex("^\\s*\\|.*\\|\\s*$")
private val TABLE_RULE = Regex("^\\s*[-:| ]{3,}\\s*$")
private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
private val BARE_URL = Regex("https?://\\S+")
private val LIST_MARK = Regex("^\\s*(?:[-*+•]|\\d+[.)])\\s+")
private val HEADING_MARK = Regex("^\\s*#{1,6}\\s*")
private val EMPHASIS = Regex("[*_`~]+")
private val SPACES = Regex("[ \\t]+")

/**
 * 去掉不适合念的 markdown：代码块、表格整块丢掉，链接只留文字、裸地址丢掉，标题/列表/强调符号去掉。
 * 保留段落换行，供后面按段取舍。
 */
internal fun stripForSpeech(raw: String): String {
    val lines = raw.replace(CODE_FENCE, "\n").lines()
        .filterNot { TABLE_LINE.matches(it) || TABLE_RULE.matches(it) }
        .map { line ->
            line.replace(LINK, "$1").replace(BARE_URL, "")
                .replace(HEADING_MARK, "").replace(LIST_MARK, "")
                .replace(EMPHASIS, "")
                .replace(SPACES, " ").trim()
        }
    return lines.joinToString("\n").replace(Regex("\n{2,}"), "\n\n").trim()
}

/** 回复是不是短到可以原样念：没有代码块/表格，且很短。 */
internal fun isSpeakableAsIs(reply: String): Boolean {
    val text = reply.trim()
    if (text.isEmpty() || text.length > AS_IS_MAX_CHARS) return false
    if (text.contains("```") || text.lines().any { TABLE_LINE.matches(it) }) return false
    return text.lines().count { it.isNotBlank() } <= AS_IS_MAX_LINES
}

private val SENTENCE_END = Regex("[。！？!?；;.]")

private fun cutAtSentence(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val head = text.take(limit)
    val end = SENTENCE_END.findAll(head).lastOrNull()?.range?.last
    return if (end != null && end >= limit / 3) head.substring(0, end + 1) else head.trimEnd() + "…"
}

/** 概括不可用时的退化：去掉格式后取第一段，在句子边界截断。绝不念整篇。 */
internal fun fallbackBrief(reply: String): String {
    val first = stripForSpeech(reply).split("\n\n").firstOrNull { it.isNotBlank() }?.replace('\n', ' ')?.trim().orEmpty()
    return cutAtSentence(first, BRIEF_MAX_CHARS)
}

/** 用户要求详细时念的内容：去格式后按预算截断。 */
internal fun detailForSpeech(reply: String): String =
    cutAtSentence(stripForSpeech(reply).replace(Regex("\n+"), " "), DETAIL_MAX_CHARS)

/** 概括调用的用户消息：原文先按预算截断（保留开头），并带上用户刚才说的话作参照。 */
internal fun buildBriefRequest(userText: String, reply: String): String = buildString {
    append("The user said:\n").append(userText.trim().take(500))
    append("\n\nThe assistant's written reply (shown on screen):\n")
    append(reply.trim().take(BRIEF_INPUT_CHARS))
    append("\n\nWrite what the voice assistant says out loud.")
}

/** 概括结果的校验：去掉格式后为空或过长就当没概括好，返回 null 让调用方退化。 */
internal fun acceptBrief(modelOutput: String?): String? {
    val text = stripForSpeech(modelOutput.orEmpty()).replace(Regex("\n+"), " ").trim()
    return text.takeIf { it.isNotEmpty() && it.length <= BRIEF_MAX_CHARS }
}
