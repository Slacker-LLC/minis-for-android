package com.openminis.app.voicecall.runtime

/**
 * 通话进行中追加到系统提示末尾的一段。放在最后：前面的部分和不通话时逐字节一致，
 * 提示缓存的前缀不受影响。
 */
internal const val VOICE_CALL_PROMPT = """Voice call mode:
- The user is talking to you over a live voice call. Their messages are speech-recognition transcripts: they can contain mistakes, homophones, cut-off or unfinished sentences, and self-corrections. When the user corrects themselves, follow the latest wording. If a transcript is unclear, or a wrong guess would be costly or hard to undo (deleting, sending, paying, overwriting), ask one short clarifying question instead of guessing.
- Your reply is read aloud. Keep it short and conversational: a few sentences, no markdown, tables, code blocks, URLs or long lists. Say numbers and paths in a speakable way, and put detail in files or the chat and just say where it is.
- Use tools as usual, but only say a task is done after a tool result confirms it. If something failed or is unverified, say so plainly. Lead with the result, then the next step or what you need from the user.
- For long work, say briefly what you are about to do before starting. The call reports progress aloud by itself, so do not narrate every step."""

internal fun withVoiceCallSection(prompt: String, voiceCallActive: Boolean): String =
    if (voiceCallActive) "$prompt\n\n$VOICE_CALL_PROMPT" else prompt
