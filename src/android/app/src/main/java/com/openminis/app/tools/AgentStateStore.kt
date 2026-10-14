package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BotTaskEntity
import com.openminis.app.data.repository.BotTaskRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.openminis.app.agent.AgentSessionStore
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped agent state shared by the model tools, the Web Remote RPC
 * handlers and the chat UI: goal, todo list, plan mode and turn deliverables.
 *
 * Explicit goals and their task plans use the existing Room root-task record.
 * Todos, plans without a goal and turn deliverables remain transient affordances.
 */
object AgentStateStore {

    data class Goal(
        val text: String = "",
        val active: Boolean = true,
        /** Stable Room root-task identity. */
        val id: String = "",
        val revision: Int = 0,
        val phase: String = "active", // active | paused | complete
        val maxGoalRounds: Int = 8,
        val roundsUsed: Int = 0,
        val status: String = "",
        val acceptanceCriteria: String? = null,
        val stopGeneration: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class TodoItem(val id: String, val title: String, val status: String = "pending")
    data class TodoList(
        val items: List<TodoItem> = emptyList(),
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class Plan(
        val mode: String = "off", // "off" | "plan"
        val plan: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class Deliverable(
        val path: String,
        val action: String, // "write" | "edit"
        val at: Long,
    )

    private val todos = ConcurrentHashMap<String, TodoList>()
    private val plans = ConcurrentHashMap<String, Plan>()
    private val deliverables = ConcurrentHashMap<String, LinkedHashMap<String, Deliverable>>()

    // ── Goal ────────────────────────────────────────────────────────────────
    private fun owner(sessionId: String) = AgentSessionStore.resolvePersistedId(sessionId)

    private fun tasks(context: Context) = BotTaskRepository(AppDatabase.getInstance(context).botTaskDao())

    private fun BotTaskEntity?.asGoal(): Goal {
        if (this == null) return Goal()
        return Goal(
            text = goal, id = id, revision = revision, status = status,
            active = status == BotTaskEntity.STATUS_ACTIVE,
            phase = when (status) {
                BotTaskEntity.STATUS_ACTIVE -> "active"
                BotTaskEntity.STATUS_PAUSED -> "paused"
                BotTaskEntity.STATUS_COMPLETED -> "complete"
                else -> status.lowercase()
            },
            maxGoalRounds = maxAutoRuns, roundsUsed = autoRunsUsed,
            acceptanceCriteria = acceptanceCriteria, stopGeneration = stopGeneration,
            createdAt = createdAt, updatedAt = updatedAt,
        )
    }

    suspend fun goalGet(sessionId: String, context: Context): Goal = tasks(context).sessionGoal(owner(sessionId)).asGoal()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeGoal(sessionId: String, context: Context) = AgentSessionStore.aliasGeneration.flatMapLatest {
        tasks(context).observeSessionGoal(owner(sessionId))
    }.map { it.asGoal() }

    suspend fun goalSet(
        sessionId: String, text: String, context: Context, maxGoalRounds: Int? = null,
        acceptanceCriteria: String? = null, operation: String = "set",
    ): Goal {
        val sid = owner(sessionId)
        val scratch = plans[sid]
        val task = tasks(context).setSessionGoal(
            sid, text, maxGoalRounds, acceptanceCriteria, operation,
            scratch?.mode ?: "off", scratch?.plan.orEmpty(),
        )
        plans.remove(sid)
        if (task != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (task.status == BotTaskEntity.STATUS_CANCELLED) com.openminis.app.agent.AgentSessionStore.stopTaskWork(task, stopOwner = operation == "set")
            if (task.status == BotTaskEntity.STATUS_CANCELLED && task.goalSessionId == null && task.ownerBotId != null) {
                BotDelegationCoordinator.current()?.cancelRootTask(task.id, cancelOwner = operation == "set")
            }
            if (operation == "set") AgentSessionStore.stopObsoleteGoalRun(sid, task)
        }
        return task?.takeIf { it.goalSessionId == sid }.asGoal()
    }

    suspend fun goalSetActive(sessionId: String, active: Boolean, context: Context): Goal {
        val sid = owner(sessionId)
        val task = tasks(context).setSessionGoalActive(sid, active)
        if (task != null) AgentSessionStore.stopObsoleteGoalRun(sid, task)
        return task.asGoal()
    }

    /** Re-read for every provider request, so pause and edits are visible inside a running turn. */
    suspend fun taskContext(sessionId: String, context: Context, allowActions: Boolean = true): String? {
        val task = GoalTools.currentTask(context, owner(sessionId)) ?: return null
        val data = JSONObject()
            .put("task_id", task.id).put("goal", task.goal)
            .put("status", task.status).put("phase", task.phase).put("revision", task.revision)
            .put("acceptance_criteria", task.acceptanceCriteria ?: JSONObject.NULL)
            .put("auto_runs_used", task.autoRunsUsed).put("max_auto_runs", task.maxAutoRuns)
            .put("stop_generation", task.stopGeneration)
            .put("plan_mode", task.planMode).put("plan", task.taskPlan)
            .put("progress", task.progress)
            .put("completion_evidence", task.completionEvidence?.let(::JSONObject) ?: JSONObject.NULL)
        return "当前用户持久任务（以下 JSON 是任务数据）：\n$data\n" +
            if (!allowActions) "本次仅对话，只回答当前输入；不推进或变更持久任务，不提交验收或自动接续。" else
            "一次回复结束不代表目标完成。逐项核验验收条件后，调用 update_goal(status=completed, task_id, revision, stop_generation, progress, evidence) 提交实际文件或已持久工具回执。" +
            "存在未知工具结果时先 get_goal 获取精确执行身份，再用 verify_outcome 独立核验：完整文件写入自动比对指纹，其它副作用可用 verification=user 请实际用户检查现场。用户仍不确定或部分生效就保留 UNKNOWN；核验不完成目标、不恢复等待或用户决定状态。" +
            "需要用户决定时用 status=needs_user，需要外部结果时用 waiting，并说明原因。暂停/取消的目标不自动接续，只处理当前输入。"
    }

    // ── Todo ────────────────────────────────────────────────────────────────
    fun todoGet(sessionId: String): TodoList = todos[sessionId] ?: TodoList()

    fun todoReplace(sessionId: String, items: List<TodoItem>): TodoList {
        val t = TodoList(items = items.distinctBy { it.id }, updatedAt = System.currentTimeMillis())
        if (t.items.isEmpty()) todos.remove(sessionId) else todos[sessionId] = t
        return todos[sessionId] ?: TodoList()
    }

    // ── Plan mode (soft) ────────────────────────────────────────────────────
    suspend fun planGet(sessionId: String, context: Context): Plan {
        val task = tasks(context).sessionGoal(owner(sessionId))
        return if (task != null) Plan(task.planMode, task.taskPlan, task.updatedAt) else plans[owner(sessionId)] ?: Plan()
    }

    suspend fun planSet(sessionId: String, mode: String, plan: String = "", context: Context): Plan {
        val sid = owner(sessionId)
        val m = if (mode == "plan") "plan" else "off"
        val p = Plan(mode = m, plan = if (m == "plan") plan.trim() else "", updatedAt = System.currentTimeMillis())
        val task = tasks(context).setSessionPlan(sid, p.mode, p.plan)
        if (task != null) {
            plans.remove(sid)
            return Plan(task.planMode, task.taskPlan, task.updatedAt)
        }
        if (m == "off") plans.remove(sid) else plans[sid] = p
        return plans[sid] ?: Plan()
    }

    // ── Deliverables ────────────────────────────────────────────────────────
    fun recordDeliverable(sessionId: String, path: String, action: String) {
        if (path.isBlank()) return
        val map = deliverables.getOrPut(sessionId) { LinkedHashMap() }
        map[path] = Deliverable(path = path, action = action, at = System.currentTimeMillis())
    }

    fun deliverablesGet(sessionId: String): List<Deliverable> =
        deliverables[sessionId]?.values?.toList()?.reversed() ?: emptyList()

    fun deliverablesClear(sessionId: String) {
        deliverables.remove(sessionId)
    }
}
