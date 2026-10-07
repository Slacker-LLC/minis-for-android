package com.openminis.app.tools.runtime

/**
 * Which tool calls of one model turn may run at the same time.
 *
 * Codex's scheduler puts parallel-safe tools behind a shared read lock and everything else behind an exclusive
 * write lock; this is the same split. A call is parallel-safe only when it reads and changes nothing: file
 * reads and searches, web fetches, device lookups. Writes, edits, the shell (one persistent bash, whose cd/export
 * state depends on the order of commands), the browser, UI automation, questions to the user and anything not
 * listed here are exclusive: each waits for every call before it and runs alone. The list is a whitelist on
 * purpose - a tool nobody classified runs the way it always did, one at a time, in order.
 */
object ToolConcurrency {
    private val PARALLEL_SAFE: Set<String> = setOf(
        "linux.file.read", "linux.file.list", "linux.file.search", "linux.file.grep",
        "linux.file.head_tail", "linux.file.info", "linux.file.image.read",
        "android.web.search", "android.web.fetch",
        "android.time", "android.capabilities", "android.weather",
        "android.app.list", "android.app.info",
        "android.wifi.info", "android.settings.get", "android.logs.read",
        "android.calendar.read", "android.contacts.search", "android.location.get",
        "system.info", "memory_get", "conversation.history", "conversation_history",
        "notification.recent", "notification.search",
    )

    /** [name] may be the model-facing name or an alias; it is resolved to the registry's canonical name first. */
    fun isParallelSafe(name: String): Boolean {
        val canonical = ToolRegistry.canonicalName(name) ?: name
        return canonical in PARALLEL_SAFE
    }
}
