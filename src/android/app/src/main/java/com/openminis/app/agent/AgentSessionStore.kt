package com.openminis.app.agent

/** Process-owned runtime sessions. Opening a page only attaches a projection to this owner. */
object AgentSessionStore {
    private val sessions = mutableMapOf<String, AgentSession>()
    private val aliases = mutableMapOf<String, String>()
    private val _aliasGeneration = kotlinx.coroutines.flow.MutableStateFlow(0)
    val aliasGeneration: kotlinx.coroutines.flow.StateFlow<Int> = _aliasGeneration

    @Synchronized
    fun resolvePersistedId(id: String): String = aliases[id] ?: id

    @Synchronized
    internal fun getOrCreate(id: String, create: () -> AgentSession): AgentSession {
        val key = resolvePersistedId(id)
        return sessions.getOrPut(key, create)
    }

    @Synchronized
    internal fun existing(id: String): AgentSession? = sessions[resolvePersistedId(id)]
        ?.takeUnless { it.execution.isClosed }

    @Synchronized
    internal fun hasSession(id: String): Boolean = sessions[resolvePersistedId(id)]?.execution?.isClosed == false

    @Synchronized
    internal fun activeRun(id: String): String? = sessions[resolvePersistedId(id)]
        ?.takeIf { it._isStreaming.value }?.activeRunId

    @Synchronized
    internal fun activeRunTask(id: String) = sessions[resolvePersistedId(id)]
        ?.takeIf { it._isStreaming.value }?.activeGoalTask

    @Synchronized
    internal fun updateRunGoal(id: String, task: com.openminis.app.data.db.AgentTaskEntity?) {
        val core = sessions[resolvePersistedId(id)]?.takeIf { it._isStreaming.value } ?: return
        core.activeGoalTask = task
        val run = core.activeRunId ?: return
        if (task != null) RunCheckpointStore.appendEvent(core.context, resolvePersistedId(id), run,
            org.json.JSONObject().put("type", "task_bound").put("task_id", task.id)
                .put("revision", task.revision).put("stop_generation", task.stopGeneration).toString())
    }

    /** Cancel only work with this durable task identity, even when several tasks share a session. */
    internal suspend fun stopTaskWork(task: com.openminis.app.data.db.AgentTaskEntity, stopOwner: Boolean = true) {
        com.openminis.app.tools.JobRegistry.list().filter { it.taskId == task.id && it.status == com.openminis.app.tools.JobRegistry.JobStatus.RUNNING }
            .forEach { com.openminis.app.tools.JobRegistry.kill(it.id, "task was cancelled") }
        com.openminis.app.agent.subagents.SubAgents.cancelTask(task.id)
        if (stopOwner) stopObsoleteGoalRun(task.currentOwnerSessionId ?: task.goalSessionId ?: task.originSessionId, task)
    }

    internal suspend fun stopObsoleteGoalRun(id: String, task: com.openminis.app.data.db.AgentTaskEntity) {
        val core = synchronized(this) { sessions[resolvePersistedId(id)] } ?: return
        val owned = core.activeGoalTask ?: return
        if (core._isStreaming.value && owned.id == task.id &&
            (owned.stopGeneration != task.stopGeneration || owned.revision != task.revision)) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { core.cancelStream() }
        }
    }

    @Synchronized
    internal fun rename(from: String, to: String) {
        if (from == to) return
        val session = sessions.remove(resolvePersistedId(from)) ?: return
        check(sessions[to] == null || sessions[to] === session) { "Session already has an execution owner: $to" }
        sessions[to] = session
        aliases[from] = to
        _aliasGeneration.value++
    }

    @Synchronized
    fun release(id: String) {
        val key = resolvePersistedId(id)
        val session = sessions[key] ?: return
        session.close()
        // Retain the closed owner until all old writes have exited; reacquisition cannot fork a loop.
        session.execution.invokeOnClosed {
            synchronized(this) {
                if (sessions[key] === session) {
                    sessions.remove(key)
                    aliases.entries.removeAll { it.value == key }
                    _aliasGeneration.value++
                }
            }
        }
    }

    /** Close first and await all persistence/compaction cleanup before a destructive Room delete. */
    internal suspend fun closeAndAwait(id: String) {
        val session = synchronized(this) { sessions[resolvePersistedId(id)]?.also { it.close() } }
        session?.execution?.awaitClosed()
    }
}
