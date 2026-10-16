package com.openminis.app.voicecall.runtime

import com.openminis.app.data.db.AgentTaskEntity
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskVoiceTest {
    private object Text : TaskVoiceText {
        override fun status(status: String) = "S:$status"
        override fun none() = "none"
        override fun summary(count: Int, items: String) = "$count|$items"
        override fun item(title: String, status: String) = "$title=$status"
        override fun switching(title: String) = "to:$title"
        override fun notFound(target: String) = "nf:$target"
        override fun ambiguous(target: String) = "amb:$target"
        override fun back() = "back"
        override fun noPrevious() = "nop"
        override fun alreadyHere() = "here"
        override fun update(title: String, status: String, progress: String) = "$title|$status|$progress".trim()
        override fun progressRunning(tool: String) = "run:$tool"
        override fun progressSteps(done: Int, tool: String) = "$done:$tool"
    }

    private fun task(id: String, title: String, status: String = AgentTaskEntity.STATUS_ACTIVE, progress: String = "") =
        VoiceTask(id, "s-$id", title, status, progress, 0L)

    private fun tool(status: ToolBlockStatus?, title: String = "t") =
        AssistantBlock(id = title + status, kind = "tool_use", toolStatus = status, toolTitle = title)

    // ---- 指令解析 ----

    @Test
    fun `list phrases are recognised`() {
        listOf("现在有哪些任务", "查看一下任务进度", "任务怎么样了", "what tasks are active", "list tasks").forEach {
            assertEquals(it, TaskCommand.ListTasks, parseTaskCommand(it))
        }
    }

    @Test
    fun `back phrases are recognised`() {
        listOf("回到上一个任务", "返回上个任务", "go back to the previous task").forEach {
            assertEquals(it, TaskCommand.Back, parseTaskCommand(it))
        }
    }

    @Test
    fun `switch phrases extract the target`() {
        assertEquals(TaskCommand.Switch("整理相册"), parseTaskCommand("切换到整理相册任务"))
        assertEquals(TaskCommand.Switch("整理相册"), parseTaskCommand("切换到那个整理相册的任务"))
        assertEquals(TaskCommand.Switch("第二个"), parseTaskCommand("切换到第二个任务。"))
        assertEquals(TaskCommand.Switch("写周报"), parseTaskCommand("我想和写周报任务聊聊"))
        assertEquals(TaskCommand.Switch("photo cleanup"), parseTaskCommand("Switch to the photo cleanup task"))
    }

    @Test
    fun `ordinary requests are never taken for task commands`() {
        listOf(
            "",
            "   ",
            "今天天气怎么样",
            "帮我新建一个整理照片的任务",
            "去查一下任务",
            "切换到",
            "切换到夜间模式",
            "把这个任务的结果发给我",
            "请帮我写一个很长很长很长很长很长的任务描述然后查看任务进度并且切换到第二个任务",
            "please write me a long report and then tell me what tasks are done so far today",
        ).forEach { assertNull(it, parseTaskCommand(it)) }
    }

    @Test
    fun `an overlong switch target is rejected`() {
        assertNull(parseTaskCommand("切换到${"很长".repeat(12)}任务"))
    }

    // ---- 匹配 ----

    @Test
    fun `ordinals map to the spoken order`() {
        assertEquals(2, ordinalOf("第二个"))
        assertEquals(3, ordinalOf("第3个"))
        assertEquals(1, ordinalOf("第一"))
        assertNull(ordinalOf("整理相册"))
        assertNull(ordinalOf("第九个"))
    }

    @Test
    fun `a unique name fragment finds the task`() {
        val tasks = listOf(task("1", "整理相册"), task("2", "写周报"))
        assertEquals(TaskMatch.Found(tasks[1]), matchTask(tasks, "周报"))
    }

    @Test
    fun `an ordinal finds the task in spoken order and rejects out of range`() {
        val tasks = listOf(task("1", "A任务"), task("2", "B任务"))
        assertEquals(TaskMatch.Found(tasks[1]), matchTask(tasks, "第二个"))
        assertEquals(TaskMatch.None, matchTask(tasks, "第三个"))
    }

    @Test
    fun `an ordinal inside the spoken five matches and one past it does not`() {
        val tasks = (1..7).map { task("$it", "任务$it") }
        assertEquals(TaskMatch.None, matchTask(tasks, "第5个".replace("5", "6")))
        assertEquals(TaskMatch.Found(tasks[4]), matchTask(tasks, "第5个"))
    }

    @Test
    fun `no match and several matches never pick a task`() {
        val tasks = listOf(task("1", "整理相册"), task("2", "整理文档"))
        assertEquals(TaskMatch.None, matchTask(tasks, "周报"))
        assertEquals(TaskMatch.Ambiguous, matchTask(tasks, "整理"))
        assertEquals(TaskMatch.None, matchTask(emptyList(), "整理"))
    }

    // ---- 清单 ----

    @Test
    fun `no active tasks says so and a long list is capped`() {
        assertEquals("none", summarizeTasks(emptyList(), Text))
        assertEquals("only finished tasks are not read out", "none",
            summarizeTasks(listOf(task("1", "旧", AgentTaskEntity.STATUS_COMPLETED), task("2", "旧2", AgentTaskEntity.STATUS_CANCELLED)), Text))
        val many = (1..8).map { task("$it", "T$it") }
        val spoken = summarizeTasks(many, Text)
        assertTrue(spoken.startsWith("8|"))
        assertEquals(MAX_SPOKEN_TASKS, spoken.substringAfter("|").split(" ").size)
    }

    @Test
    fun `history does not crowd the spoken list and ordinals follow what was spoken`() {
        val all = listOf(
            task("1", "进行中A"),
            task("2", "进行中B", AgentTaskEntity.STATUS_NEEDS_USER),
            task("3", "已完成C", AgentTaskEntity.STATUS_COMPLETED),
        )
        assertTrue(summarizeTasks(all, Text).startsWith("2|"))
        assertEquals(TaskMatch.Found(all[1]), matchSpokenTask(all, "第二个"))
        assertEquals("a finished task was never spoken, so its ordinal does not exist", TaskMatch.None, matchSpokenTask(all, "第三个"))
        assertEquals("but it can still be reached by name", TaskMatch.Found(all[2]), matchSpokenTask(all, "已完成C"))
    }

    // ---- 后台更新 ----

    @Test
    fun `only known tasks entering a notable status are announced`() {
        val before = mapOf("a" to AgentTaskEntity.STATUS_ACTIVE, "b" to AgentTaskEntity.STATUS_ACTIVE, "c" to AgentTaskEntity.STATUS_ACTIVE)
        val now = listOf(
            task("a", "A", AgentTaskEntity.STATUS_COMPLETED, "done"),
            task("b", "B", AgentTaskEntity.STATUS_PAUSED),
            task("c", "C", AgentTaskEntity.STATUS_CANCELLED),
            task("new", "N", AgentTaskEntity.STATUS_COMPLETED),
        )
        val updates = detectTaskUpdates(before, now)
        assertEquals(listOf("a:COMPLETED"), updates.map { it.key })
    }

    @Test
    fun `an unchanged status is not announced twice`() {
        val before = mapOf("a" to AgentTaskEntity.STATUS_COMPLETED)
        assertTrue(detectTaskUpdates(before, listOf(task("a", "A", AgentTaskEntity.STATUS_COMPLETED))).isEmpty())
    }

    @Test
    fun `needs user failed and budget exhausted are announced`() {
        val before = mapOf("a" to "ACTIVE", "b" to "ACTIVE", "c" to "ACTIVE")
        val now = listOf(
            task("a", "A", AgentTaskEntity.STATUS_NEEDS_USER),
            task("b", "B", AgentTaskEntity.STATUS_FAILED),
            task("c", "C", AgentTaskEntity.STATUS_BUDGET_EXHAUSTED),
        )
        assertEquals(3, detectTaskUpdates(before, now).size)
    }

    @Test
    fun `only a completed task carries its conclusion aloud and it is capped`() {
        val done = TaskStatusUpdate("k", task("a", "A", AgentTaskEntity.STATUS_COMPLETED, "x".repeat(500)))
        assertEquals(160, speakUpdate(done, Text).substringAfterLast("|").length)
        val failed = TaskStatusUpdate("k", task("a", "A", AgentTaskEntity.STATUS_FAILED, "secret internals"))
        assertEquals("A|S:FAILED|", speakUpdate(failed, Text))
    }

    // ---- 进度 ----

    @Test
    fun `progress names the running tool and the verified step count`() {
        val blocks = listOf(tool(ToolBlockStatus.SUCCESS, "a"), tool(ToolBlockStatus.FAILED, "b"), tool(ToolBlockStatus.RUNNING, "读取文件"))
        assertEquals("2:读取文件", describeProgress(blocks, Text))
        assertEquals("run:读取文件", describeProgress(listOf(tool(ToolBlockStatus.RUNNING, "读取文件")), Text))
    }

    @Test
    fun `progress is silent when nothing verifiable is running`() {
        assertNull(describeProgress(emptyList(), Text))
        assertNull(describeProgress(listOf(tool(ToolBlockStatus.SUCCESS)), Text))
        assertNull(describeProgress(listOf(tool(ToolBlockStatus.PENDING)), Text))
        assertNull(describeProgress(listOf(tool(ToolBlockStatus.RUNNING, "")), Text))
        assertNull(describeProgress(listOf(AssistantBlock(id = "x", kind = "text", content = "hi")), Text))
    }

    // ---- 返回栈 ----

    @Test
    fun `back stack returns the most recent other session and then empties`() {
        val stack = TaskBackStack()
        stack.push("a")
        stack.push("b")
        assertEquals("b", stack.pop("c"))
        assertEquals("a", stack.pop("c"))
        assertNull(stack.pop("c"))
    }

    @Test
    fun `back stack never returns the current session`() {
        val stack = TaskBackStack()
        stack.push("a")
        assertNull(stack.pop("a"))
        assertNull(TaskBackStack().pop(null))
    }

    // ---- 系统提示 ----

    @Test
    fun `voice section is appended last only while a call is active`() {
        val base = "BASE\n\nRuntime context:\n- x"
        assertEquals(base, withVoiceCallSection(base, false))
        val on = withVoiceCallSection(base, true)
        assertTrue(on.startsWith(base))
        assertTrue(on.endsWith(VOICE_CALL_PROMPT))
        assertFalse(base.contains("Voice call mode"))
    }

    @Test
    fun `voice section tells the model to ask instead of guessing and to verify completion`() {
        assertTrue(VOICE_CALL_PROMPT.contains("transcripts"))
        assertTrue(VOICE_CALL_PROMPT.contains("clarifying question instead of guessing"))
        assertTrue(VOICE_CALL_PROMPT.contains("latest wording"))
        assertTrue(VOICE_CALL_PROMPT.contains("read aloud"))
        assertTrue(VOICE_CALL_PROMPT.contains("only say a task is done after a tool result confirms it"))
    }
}
