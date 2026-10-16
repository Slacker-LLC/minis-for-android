package com.openminis.app.voicecall.runtime

import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus

/**
 * 通话里的任务语音：口头查询任务、切换任务，以及用已核实的状态口头汇报进度。
 * 这里全是纯函数，播报文字由 [TaskVoiceText] 提供（生产实现读字符串资源）。
 */
internal interface TaskVoiceText {
    fun status(status: String): String
    fun none(): String
    fun summary(count: Int, items: String): String
    fun item(title: String, status: String): String
    fun switching(title: String): String
    fun notFound(target: String): String
    fun ambiguous(target: String): String
    fun back(): String
    fun noPrevious(): String
    fun alreadyHere(): String
    fun update(title: String, status: String, progress: String): String
    fun progressRunning(tool: String): String
    fun progressSteps(done: Int, tool: String): String
}

/** 一个可被口头查询/切换的任务。[sessionId] 是任务当前所在的会话。 */
internal data class VoiceTask(
    val id: String,
    val sessionId: String,
    val title: String,
    val status: String,
    val progress: String,
    val updatedAt: Long,
)

internal fun AgentTaskEntity.toVoiceTask() = VoiceTask(
    id = id,
    sessionId = currentOwnerSessionId ?: originSessionId,
    title = goal.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_TITLE_CHARS) ?: "",
    status = status,
    progress = progress,
    updatedAt = updatedAt,
)

private const val MAX_TITLE_CHARS = 24

/** 一次口头报告里最多念几个任务，再多就听不过来了。 */
internal const val MAX_SPOKEN_TASKS = 5

/** 后台完成播报里带的进度/结论最多念这么多字。 */
private const val MAX_SPOKEN_PROGRESS_CHARS = 160

internal sealed interface TaskCommand {
    /** 「现在有哪些任务」 */
    data object ListTasks : TaskCommand

    /** 「切换到整理相册那个任务」：[target] 是任务名片段或「第二个」之类的序号。 */
    data class Switch(val target: String) : TaskCommand

    /** 「回到上一个任务」 */
    data object Back : TaskCommand
}

/** 超过这个长度的话更像是正常请求，不当作任务指令（宁可漏掉，也不劫持一个真实请求）。 */
private const val MAX_COMMAND_CJK_CHARS = 28
private const val MAX_COMMAND_LATIN_CHARS = 48
private const val MAX_TARGET_CHARS = 20

private val BACK_PHRASES = listOf(
    "上一个任务", "上个任务", "之前的任务", "刚才的任务", "前一个任务",
    "previous task", "last task", "back to the previous",
)
private val LIST_PHRASES = listOf(
    "有哪些任务", "有什么任务", "有几个任务", "什么任务", "任务进度", "任务情况", "任务状态",
    "查看任务", "看看任务", "看一下任务", "列出任务", "汇报任务", "任务怎么样", "任务列表",
    "active tasks", "list tasks", "task status", "what tasks", "which tasks", "my tasks",
)
private val SWITCH_ZH = listOf(
    Regex("(?:切换到|切换至|切换去|切到|跳到|转到|换到|回到)(.+?)任务"),
    Regex("(?:我想|我要|让我|想)?(?:和|跟)(.+?)任务(?:聊|说|谈|对话|沟通)"),
)
private val SWITCH_EN = Regex("(?:switch|go|jump|talk)\\s+(?:to|with)\\s+(?:the\\s+)?(.+?)\\s+task\\b")
private val FILLERS = Regex("^(?:那个|这个|一下|到|至)+|(?:那个|这个|一下|吧|呢|啊|吗|的)+$")

/**
 * 把一句话解析成任务指令；不是指令返回 null，由调用方照常交给模型。
 * 只认短句和固定句式——「帮我新建一个整理照片的任务」「去查一下任务」这类正常请求不会被截走。
 */
internal fun parseTaskCommand(raw: String): TaskCommand? {
    val text = raw.trim().lowercase().trimEnd('。', '.', '?', '？', '!', '！', ' ')
    val limit = if (text.any { it.code >= 0x2E80 }) MAX_COMMAND_CJK_CHARS else MAX_COMMAND_LATIN_CHARS
    if (text.isEmpty() || text.length > limit) return null
    if (BACK_PHRASES.any { text.contains(it) }) return TaskCommand.Back
    for (re in SWITCH_ZH + SWITCH_EN) {
        val target = re.find(text)?.groupValues?.get(1)?.trim()?.replace(FILLERS, "")?.trim() ?: continue
        if (target.isNotEmpty() && target.length <= MAX_TARGET_CHARS) return TaskCommand.Switch(target)
    }
    if (LIST_PHRASES.any { text.contains(it) }) return TaskCommand.ListTasks
    return null
}

internal sealed interface TaskMatch {
    data class Found(val task: VoiceTask) : TaskMatch
    data object None : TaskMatch
    data object Ambiguous : TaskMatch
}

private val ORDINAL_DIGITS = mapOf(
    '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
    '1' to 1, '2' to 2, '3' to 3, '4' to 4, '5' to 5,
)

/** 「第二个」→ 2；不是序号返回 null。 */
internal fun ordinalOf(target: String): Int? {
    val m = Regex("^第(.)个?$").find(target.trim()) ?: return null
    return ORDINAL_DIGITS[m.groupValues[1].single()]
}

/**
 * 在 [tasks]（顺序必须和 [summarizeTasks] 念出来的一致）里找目标：先认序号，再认名字片段。
 * 唯一命中才算找到；多个命中是 [TaskMatch.Ambiguous]，不替用户挑。
 */
internal fun matchTask(tasks: List<VoiceTask>, target: String): TaskMatch {
    ordinalOf(target)?.let { n ->
        return tasks.take(MAX_SPOKEN_TASKS).getOrNull(n - 1)?.let { TaskMatch.Found(it) } ?: TaskMatch.None
    }
    val hits = tasks.filter { it.title.contains(target, ignoreCase = true) }
    return when (hits.size) {
        0 -> TaskMatch.None
        1 -> TaskMatch.Found(hits.single())
        else -> TaskMatch.Ambiguous
    }
}

/** 口头清单里的任务：只有进行中的（含等待/需要处理/暂停），已结束的不念，免得被历史任务淹没。 */
internal fun spokenTasks(all: List<VoiceTask>): List<VoiceTask> =
    all.filter { it.status !in AgentTaskEntity.TERMINAL_STATUSES }

/** 口头任务清单：进行中的最多念 [MAX_SPOKEN_TASKS] 个，每个带状态；没有就直说没有。 */
internal fun summarizeTasks(all: List<VoiceTask>, text: TaskVoiceText): String {
    val tasks = spokenTasks(all)
    if (tasks.isEmpty()) return text.none()
    val shown = tasks.take(MAX_SPOKEN_TASKS)
    return text.summary(tasks.size, shown.joinToString(" ") { text.item(it.title, text.status(it.status)) })
}

/**
 * 口头切换的目标：序号对应刚才念的清单（只含进行中的）；名字片段则在所有任务里找，
 * 这样也能切到刚完成的任务去看结果。
 */
internal fun matchSpokenTask(all: List<VoiceTask>, target: String): TaskMatch =
    matchTask(if (ordinalOf(target) != null) spokenTasks(all) else all, target)

/** 值得主动打断念出来的状态；取消/暂停是用户自己做的，不用播报。 */
private val ANNOUNCED_STATUSES = setOf(
    AgentTaskEntity.STATUS_COMPLETED,
    AgentTaskEntity.STATUS_NEEDS_USER,
    AgentTaskEntity.STATUS_FAILED,
    AgentTaskEntity.STATUS_BUDGET_EXHAUSTED,
)

/** 一条待播报的任务状态变化。[key] 用来和已经在回答里说过的结果去重。 */
internal data class TaskStatusUpdate(val key: String, val task: VoiceTask)

internal fun taskUpdateKey(taskId: String, status: String) = "$taskId:$status"

/**
 * 对比上次看到的状态 [previous]（任务 id → 状态）和现在的任务：只有「已知任务」变成了
 * 完成/需要处理/失败/预算用尽才算更新。第一次看到的任务只是建立基线，不播报。
 */
internal fun detectTaskUpdates(previous: Map<String, String>, now: List<VoiceTask>): List<TaskStatusUpdate> =
    now.filter { task ->
        val before = previous[task.id]
        before != null && before != task.status && task.status in ANNOUNCED_STATUSES
    }.map { TaskStatusUpdate(taskUpdateKey(it.id, it.status), it) }

internal fun speakUpdate(update: TaskStatusUpdate, text: TaskVoiceText): String {
    val task = update.task
    val detail = if (task.status == AgentTaskEntity.STATUS_COMPLETED) task.progress.trim().take(MAX_SPOKEN_PROGRESS_CHARS) else ""
    return text.update(task.title, text.status(task.status), detail)
}

/**
 * 进行中的工作的一句话进度，只描述已经发生的事实：正在跑的工具和已完成的步数。
 * 没有正在执行的工具就返回 null——宁可不说，也不编造进度。
 */
internal fun describeProgress(blocks: List<AssistantBlock>, text: TaskVoiceText): String? {
    val running = blocks.lastOrNull {
        it.toolStatus == ToolBlockStatus.RUNNING && it.toolTitle.isNotBlank()
    } ?: return null
    val done = blocks.count { it.toolStatus == ToolBlockStatus.SUCCESS || it.toolStatus == ToolBlockStatus.FAILED }
    return if (done > 0) text.progressSteps(done, running.toolTitle) else text.progressRunning(running.toolTitle)
}

/**
 * 口头切换留下的「回到上一个任务」栈（进程内）。[pop] 跳过和当前会话相同的项：
 * 来回切换时不会「回到」自己。
 */
internal class TaskBackStack {
    private val stack = ArrayDeque<String>()

    @Synchronized
    fun push(sessionId: String) {
        stack.remove(sessionId)
        stack.addLast(sessionId)
        while (stack.size > MAX_DEPTH) stack.removeFirst()
    }

    @Synchronized
    fun pop(current: String?): String? {
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            if (top != current) return top
        }
        return null
    }

    companion object {
        private const val MAX_DEPTH = 10
        val shared = TaskBackStack()
    }
}
