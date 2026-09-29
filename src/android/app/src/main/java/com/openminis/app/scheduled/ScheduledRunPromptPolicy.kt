package com.openminis.app.scheduled

/** Safety reminder for unattended scheduled turns; it is a soft constraint, not an approval mechanism. */
object ScheduledRunPromptPolicy {
    private const val SAFETY_NOTE_BODY = "不要执行发送消息、删除数据、付款等有外部影响的操作；需要这类操作时停止并说明需要用户确认。"
    const val SAFETY_NOTE = "本次是无人值守的定时运行。$SAFETY_NOTE_BODY"
    const val UNATTENDED_SAFETY_NOTE = "本次是无人值守的后台运行。$SAFETY_NOTE_BODY"

    fun appendSafetyNote(
        systemPrompt: String?,
        sessionSource: String?,
        unattended: Boolean = false,
    ): String? {
        val note = when {
            sessionSource == "scheduled" -> SAFETY_NOTE
            unattended -> UNATTENDED_SAFETY_NOTE
            else -> return systemPrompt
        }
        return listOf(systemPrompt?.takeIf { it.isNotBlank() }, note)
            .joinToString("\n\n")
    }
}
