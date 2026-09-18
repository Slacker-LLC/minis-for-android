package com.openminis.app.tools.android

/** Pure generation/ref lifetime fence used by Android UI observations. */
class UiGenerationFence(
    private val maxEntries: Int = 4,
    private val ttlMs: Long = 30_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Verdict { VALID, STALE, REF_NOT_FOUND, TRUNCATED }

    private data class Entry(
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
    fun install(generation: Long, fingerprint: String, refs: Set<String>, truncated: Boolean = false) {
        entries[generation] = Entry(fingerprint, truncated, refs.toSet(), clock())
        trim()
    }

    @Synchronized
    fun validate(
        generation: Long,
        ref: String,
        currentFingerprint: String,
        currentTruncated: Boolean = false,
    ): Verdict {
        val entry = entries[generation] ?: return Verdict.STALE
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

    @Synchronized
    fun clear() {
        entries.clear()
        nextGeneration = 0L
    }

    @Synchronized
    private fun trim() {
        val now = clock()
        entries.entries.removeAll { now - it.value.createdAt > ttlMs }
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }
}
