package com.openminis.app.data.storage

import android.content.Context
import com.openminis.app.agent.AgentSessionStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import com.openminis.app.tools.AgentStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** User-selected session cleanup, using the same execution fence as session deletion. */
internal object SessionStorage {
    enum class Category { INPUTS, WORK, OUTPUTS, BROWSER }
    data class Preview(val bytes: Map<Category, Long?>) {
        fun selectedBytes(selected: Set<Category>): Long? =
            if (selected.any { bytes[it] == null }) null else selected.sumOf { bytes.getValue(it)!! }
    }
    data class Result(val before: Preview, val after: Preview, val selected: Set<Category>) {
        val complete: Boolean get() = selected.all { after.bytes[it] == 0L }
        val removedBytes: Long? get() = before.selectedBytes(selected)?.let { beforeBytes ->
            after.selectedBytes(selected)?.let { afterBytes -> (beforeBytes - afterBytes).coerceAtLeast(0L) }
        }
    }
    private val cleanup = Mutex()

    suspend fun preview(context: Context, sessionId: String): Preview = withContext(Dispatchers.IO) {
        require(UbuntuPaths.isSafeSessionId(sessionId)) { "invalid session ID" }
        suspend fun measure(block: suspend () -> Long): Long? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        Preview(linkedMapOf(
            Category.INPUTS to measure {
                WorkspaceFileClient.treeSize(sessionId, "/var/minis/attachments") +
                    (MediaStore(context).sessionSizes(setOf(sessionId))[sessionId] ?: 0L)
            },
            Category.WORK to measure { WorkspaceFileClient.sessionWorkspaceSize(sessionId) },
            Category.OUTPUTS to measure { WorkspaceFileClient.treeSize(sessionId, "/var/minis/offloads") },
            Category.BROWSER to measure { WorkspaceFileClient.treeSize(sessionId, "/var/minis/browser") },
        ))
    }

    /** Confirmation authorizes cleanup to finish even if the settings page is dismissed. */
    suspend fun clear(context: Context, sessionId: String, categories: Set<Category>): Result =
        withContext(NonCancellable + Dispatchers.IO) {
            val selected = categories.toSet()
            cleanup.withLock {
                require(UbuntuPaths.isSafeSessionId(sessionId)) { "invalid session ID" }
                val dao = AppDatabase.getInstance(context).chatDao()
                checkNotNull(dao.getSession(sessionId)) { "session_deleted" }
                if (selected.isEmpty()) {
                    val current = preview(context, sessionId)
                    return@withLock Result(current, current, selected)
                }
                val goal = AgentStateStore.goalGet(sessionId, context)
                if (goal.id.isNotEmpty() && goal.status !in BotTaskEntity.TERMINAL_STATUSES && goal.status != BotTaskEntity.STATUS_PAUSED) {
                    AgentStateStore.goalSetActive(sessionId, false, context)
                }
                // Terminal/CLI background jobs can exist without a live chat owner.
                com.openminis.app.runtime.ExecutionCoordinator.sessionDidTerminate(sessionId)
                AgentSessionStore.closeAndAwait(sessionId)
                try {
                    checkNotNull(dao.getSession(sessionId)) { "session_deleted" }
                    val before = preview(context, sessionId)
                    if (Category.WORK in selected) WorkspaceFileClient.deleteSessionWorkspaceChildren(sessionId)
                    for ((category, root) in listOf(Category.INPUTS to "/var/minis/attachments",
                        Category.OUTPUTS to "/var/minis/offloads", Category.BROWSER to "/var/minis/browser")) {
                        if (category in selected && WorkspaceFileClient.info(sessionId, root).optBoolean("exists")) {
                            WorkspaceFileClient.deleteChildren(sessionId, root)
                        }
                    }
                    if (Category.INPUTS in selected) MediaStore(context).deleteSessionMedia(sessionId)
                    Result(before, preview(context, sessionId), selected)
                } finally {
                    AgentSessionStore.release(sessionId)
                }
            }
        }
}
