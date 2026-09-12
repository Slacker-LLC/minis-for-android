package com.openminis.app.tools

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolCheckpointRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

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
            """{"callId":"2","state":"pending","tool":"write"}""")
        assertEquals(listOf("2"), ToolCheckpointStore.readPending(file).map { it.callId })
    }
}
