package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * [T-android-scheduled-tasks-design] SharedPreferences-backed JSON array of
 * [ScheduledTask] rows. Same pattern as [com.openminis.app.offload.AlarmOffloadManager]
 * — small dataset, low write frequency, no Room migration cost.
 */
class ScheduledTaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { ScheduledTask.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(taskId: String): ScheduledTask? = all().firstOrNull { it.id == taskId }

    fun upsert(task: ScheduledTask, synchronous: Boolean = false, preserveOrder: Boolean = false) {
        val current = all()
        val existingIndex = current.indexOfFirst { it.id == task.id }
        val updated = if (preserveOrder && existingIndex >= 0) {
            current.toMutableList().also { it[existingIndex] = task }
        } else {
            current.filter { it.id != task.id } + task
        }
        write(updated, synchronous = synchronous)
    }

    /** Rewrite only model bindings via all/upsert; trigger times/AlarmManager stay untouched. */
    internal fun migrateModelBindings(updates: Map<String, ScheduledTaskBindingUpdate>): Int {
        val current = all()
        val (rewritten, changed) = rewriteScheduledTaskBindings(current, updates)
        if (changed > 0) {
            val oldById = current.associateBy { it.id }
            rewritten.filter { oldById[it.id] != it }.forEach { task ->
                // Sync commit before the Room migration marker; retry is idempotent if
                // the process stops between individual task writes.
                upsert(task, synchronous = true, preserveOrder = true)
            }
        }
        return changed
    }

    fun delete(taskId: String) {
        write(all().filter { it.id != taskId })
    }

    fun clear() {
        prefs.edit().remove(KEY_TASKS).apply()
    }

    private fun write(tasks: List<ScheduledTask>, synchronous: Boolean = false) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        val editor = prefs.edit().putString(KEY_TASKS, arr.toString())
        if (synchronous) {
            check(editor.commit()) { "failed to persist scheduled-task migration" }
        } else {
            editor.apply()
        }
    }

    /**
     * Cold flow that emits the current task list whenever the prefs file
     * changes. Used by [ScheduledTasksViewModel] to keep the list screen
     * live.
     */
    fun observe(): Flow<List<ScheduledTask>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TASKS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "ScheduledTaskStore"
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
    }
}

internal data class ScheduledTaskBindingUpdate(
    val modelBinding: String?,
    val modelId: String? = null,
)

/** Pure helper shared by the SharedPreferences migration and its JVM tests. */
internal fun rewriteScheduledTaskBindings(
    tasks: List<ScheduledTask>,
    updates: Map<String, ScheduledTaskBindingUpdate>,
): Pair<List<ScheduledTask>, Int> {
    var changed = 0
    val rewritten = tasks.map { task ->
        if (task.id !in updates) return@map task
        val update = updates.getValue(task.id)
        val modelId = update.modelId ?: task.modelId
        if (task.modelBinding == update.modelBinding && task.modelId == modelId) return@map task
        changed++
        task.copy(modelBinding = update.modelBinding, modelId = modelId)
    }
    return rewritten to changed
}
