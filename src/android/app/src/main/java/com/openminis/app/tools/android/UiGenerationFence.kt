package com.openminis.app.tools.android

/** Pure generation/ref lifetime fence used by Android UI observations. */
class UiGenerationFence(
    private val maxEntries: Int = 4,
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Verdict { VALID, STALE, REF_NOT_FOUND, TRUNCATED }

    enum class Freshness { FRESH, CONTENT_CHANGED, STALE, REF_NOT_FOUND }

    private data class Entry(
        val sessionId: String,
        val displayId: Int,
        val fingerprint: String,
        val truncated: Boolean,
        val refs: Set<String>,
        val createdAt: Long,
    )

    private var nextGeneration = 0L
    private val entries = LinkedHashMap<Long, Entry>()

    @Synchronized
    fun nextGeneration(): Long = ++nextGeneration

    @Synchronized
    fun install(
        generation: Long,
        fingerprint: String,
        refs: Set<String>,
        truncated: Boolean = false,
        sessionId: String = "",
        displayId: Int = 0,
    ) {
        entries[generation] = Entry(sessionId, displayId, fingerprint, truncated, refs.toSet(), clock())
        trim()
    }

    @Synchronized
    fun validate(
        generation: Long,
        ref: String,
        currentFingerprint: String,
        currentTruncated: Boolean = false,
        sessionId: String = "",
        displayId: Int = 0,
    ): Verdict {
        val entry = entries[generation] ?: return Verdict.STALE
        if (entry.sessionId != sessionId || entry.displayId != displayId) return Verdict.STALE
        if (clock() - entry.createdAt > ttlMs) {
            entries.remove(generation)
            return Verdict.STALE
        }
        // A digest that stopped before the end of the tree proves nothing about the
        // part it never read, and a truncated observation can never prove that a
        // text/description identity is unique. Neither may count as "unchanged".
        if (entry.truncated || currentTruncated) return Verdict.TRUNCATED
        if (ref !in entry.refs) return Verdict.REF_NOT_FOUND
        return if (entry.fingerprint == currentFingerprint) Verdict.VALID else Verdict.STALE
    }

    /**
     * How a ref relates to the current screen, without deciding whether it may be used:
     * [Freshness.CONTENT_CHANGED] means "the screen is not provably the one that was
     * observed" (fingerprint differs, or either scan was cut short), which is a question
     * for [UiRefResolutionPolicy], not an automatic refusal. Expired, foreign and unknown
     * refs stay refusals.
     */
    @Synchronized
    fun freshness(
        generation: Long,
        ref: String,
        currentFingerprint: String,
        currentTruncated: Boolean = false,
        sessionId: String = "",
        displayId: Int = 0,
    ): Freshness {
        val base = lookup(generation, ref, sessionId, displayId)
        if (base != Freshness.FRESH) return base
        val entry = entries.getValue(generation)
        if (entry.truncated || currentTruncated) return Freshness.CONTENT_CHANGED
        return if (entry.fingerprint == currentFingerprint) Freshness.FRESH else Freshness.CONTENT_CHANGED
    }

    /** Lifetime, scope and membership only; never looks at screen content. */
    @Synchronized
    fun lookup(generation: Long, ref: String, sessionId: String = "", displayId: Int = 0): Freshness {
        val entry = entries[generation] ?: return Freshness.STALE
        if (entry.sessionId != sessionId || entry.displayId != displayId) return Freshness.STALE
        if (clock() - entry.createdAt > ttlMs) {
            entries.remove(generation)
            return Freshness.STALE
        }
        if (ref !in entry.refs) return Freshness.REF_NOT_FOUND
        return Freshness.FRESH
    }

    @Synchronized
    fun clear() {
        entries.clear()
        nextGeneration = 0L
    }

    @Synchronized
    fun clearDisplay(displayId: Int) {
        entries.entries.removeAll { it.value.displayId == displayId }
    }

    @Synchronized
    private fun trim() {
        val now = clock()
        entries.entries.removeAll { now - it.value.createdAt > ttlMs }
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }
}
