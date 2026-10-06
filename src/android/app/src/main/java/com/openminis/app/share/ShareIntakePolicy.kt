package com.openminis.app.share

/** Decisions the share intake makes that do not depend on Android: what a SEND carries, and how much may wait. */
internal object ShareIntakePolicy {
    /** Shares waiting for a chat, across handoffs. */
    const val MAX_BUFFERED_ITEMS = 50

    /** Staged bytes waiting for a chat, across handoffs (each handoff is already capped on its own). */
    const val MAX_BUFFERED_BYTES = 64L * 1024L * 1024L

    enum class SingleSendSource { TEXT, STREAM, NONE }

    /**
     * What an ACTION_SEND carries. Inline text wins when there is any, so a page link shared as
     * `text/plain` stays inline; otherwise a stream is the payload even for `text/plain` (a shared
     * text file has EXTRA_STREAM and no EXTRA_TEXT).
     */
    fun singleSendSource(mimeType: String, text: String?, hasStream: Boolean): SingleSendSource {
        val hasText = !text.isNullOrBlank()
        return when {
            mimeType == "text/plain" && hasText -> SingleSendSource.TEXT
            hasStream -> SingleSendSource.STREAM
            hasText -> SingleSendSource.TEXT
            else -> SingleSendSource.NONE
        }
    }

    data class Merged(
        val accepted: List<PendingShare.Item>,
        val rejected: List<PendingShare.Item>,
    )

    /**
     * Appends [incoming] to [existing] without duplicates, in arrival order, and refuses what would
     * take the queue past [maxItems] or [maxBytes] (attachments are measured with [sizeOf]).
     * Items already queued are never rejected.
     */
    fun merge(
        existing: List<PendingShare.Item>,
        incoming: List<PendingShare.Item>,
        sizeOf: (PendingShare.Item) -> Long,
        maxItems: Int = MAX_BUFFERED_ITEMS,
        maxBytes: Long = MAX_BUFFERED_BYTES,
    ): Merged {
        val queued = LinkedHashMap<Pair<PendingShare.Item.Kind, String>, PendingShare.Item>()
        var bytes = 0L
        for (item in existing) {
            if (queued.put(item.kind to item.value, item) == null) bytes += sizeOf(item)
        }
        val rejected = ArrayList<PendingShare.Item>()
        for (item in incoming) {
            val key = item.kind to item.value
            if (key in queued) continue
            val size = sizeOf(item)
            if (queued.size >= maxItems || bytes + size > maxBytes) {
                rejected += item
            } else {
                queued[key] = item
                bytes += size
            }
        }
        return Merged(queued.values.toList(), rejected)
    }
}
