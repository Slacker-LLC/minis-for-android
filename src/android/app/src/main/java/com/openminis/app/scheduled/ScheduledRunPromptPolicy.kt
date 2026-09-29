package com.openminis.app.scheduled

/** Safety reminder for unattended scheduled turns; it is a soft constraint, not an approval mechanism. */
object ScheduledRunPromptPolicy {
    const val SAFETY_NOTE = "本次是无人值守的定时运行。不要执行发送消息、删除数据、付款等有外部影响的操作；需要这类操作时停止并说明需要用户确认。"

    fun appendSafetyNote(systemPrompt: String?, sessionSource: String?): String? {
        if (sessionSource != "scheduled") return systemPrompt
        return listOf(systemPrompt?.takeIf { it.isNotBlank() }, SAFETY_NOTE)
            .joinToString("\n\n")
    }
}
