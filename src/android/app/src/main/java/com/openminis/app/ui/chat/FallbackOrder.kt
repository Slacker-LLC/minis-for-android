package com.openminis.app.ui.chat

/**
 * Returns declared members in the current fallback-attempt order, excluding the
 * current member and cycling once through the rest. A missing current member
 * preserves the existing caller behavior: iteration starts at index 1.
 */
internal fun fallbackEntryIdsInAttemptOrder(memberIds: List<String>, currentEntryId: String?): List<String> {
    val currentIndex = memberIds.indexOfFirst { it == currentEntryId }.takeIf { it >= 0 } ?: -1
    return (1 until memberIds.size).map { offset ->
        val index = if (currentIndex >= 0) (currentIndex + offset) % memberIds.size else offset
        memberIds[index]
    }
}
