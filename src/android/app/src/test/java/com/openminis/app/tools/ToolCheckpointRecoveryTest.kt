package com.openminis.app.tools

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolCheckpointRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `atomic completion retains unrelated pending intents`() {
        val file = temp.newFile("atomic.jsonl")
        file.writeText("old snapshot")
        val content = """{"callId":"1","state":"done"}""" + "\n" +
            """{"callId":"2","state":"pending","tool":"write","args":"{}","at":1}"""
        ToolCheckpointStore.replaceAtomically(file, content)
        assertEquals(content, file.readText())
        assertEquals(listOf("2"), ToolCheckpointStore.readPending(file).map { it.callId })
        assertEquals(listOf("atomic.jsonl"), temp.root.list()!!.toList())
    }

    @Test fun `inspection and restart preserve every unresolved intent`() {
        val file = temp.newFile("checkpoints.jsonl")
        val records = (1..60).joinToString("\n") {
            """{"callId":"$it","tool":"write","args":"{}","at":1,"state":"pending"}"""
        }
        file.writeText(records)
        assertEquals(60, ToolCheckpointStore.readPending(file).size)
        assertEquals(records, file.readText())
        assertEquals(60, ToolCheckpointStore.readPending(java.io.File(file.path)).size)
    }

    @Test fun `durable completion is not reported as unknown`() {
        val file = temp.newFile("done.jsonl")
        file.writeText("""{"callId":"1","state":"done"}""" + "\n" +
            """{"callId":"2","state":"pending","tool":"write","args":"{}","at":1}""")
        assertEquals(listOf("2"), ToolCheckpointStore.readPending(file).map { it.callId })
    }

    @Test fun `a sensitive call's arguments never reach the intent file`() {
        val file = temp.newFile("sensitive.jsonl")
        val canary = "canary-secret-7f3a"
        ToolCheckpointStore.recordIntentTo(file, "c1", "mcp.vault.read", """{"token":"$canary"}""")
        ToolCheckpointStore.recordIntentTo(file, "c2", "get_setting", """{"key":"$canary"}""")
        ToolCheckpointStore.recordIntentTo(file, "c3", "linux.file.read", """{"path":"/workspace/a.txt"}""")
        val text = file.readText()
        assertFalse("no sensitive argument is persisted", text.contains(canary))
        assertTrue("an ordinary call keeps its arguments for recovery", text.contains("/workspace/a.txt"))
        assertEquals(listOf("c1", "c2", "c3"), ToolCheckpointStore.readPending(file).map { it.callId })
    }

    @Test fun `a reusable call id cannot clear an identified execution`() {
        val file = temp.newFile("settle.jsonl")
        (1..5).forEach { ToolCheckpointStore.recordIntentTo(file, "c$it", "linux.shell", "{}") }
        file.appendText("""{"callId":"old","state":"done"}""" + "\n")
        // settle() by call id only touches legacy rows: a provider call id is reusable,
        // so an identified intent is cleared through acknowledge(intentIds) instead.
        ToolCheckpointStore.settle(file, setOf("c1", "c2", "c4"))
        assertEquals((1..5).map { "c$it" }, ToolCheckpointStore.readPending(file).map { it.callId })
        assertEquals("done rows are pruned", 5, file.readLines().count { it.isNotBlank() })

        val legacy = temp.newFile("legacy.jsonl")
        legacy.writeText(
            """{"callId":"l1","state":"pending","tool":"write","args":"{}","at":1}""" + "\n" +
                """{"callId":"l2","state":"pending","tool":"write","args":"{}","at":1}""",
        )
        ToolCheckpointStore.settle(legacy, setOf("l1"))
        assertEquals(listOf("l2"), ToolCheckpointStore.readPending(legacy).map { it.callId })
        ToolCheckpointStore.settle(legacy, setOf("l2"))
        assertFalse("nothing in flight leaves no file", legacy.exists())
    }
}
