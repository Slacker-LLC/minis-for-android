package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageFeedbackPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context = ContextWrapper(null)

    @Before fun point() { MessageFeedbackStore.fileForTest = File(tmp.root, "feedback.json") }

    @After fun reset() { MessageFeedbackStore.fileForTest = null }

    @Test
    fun `a saved feedback is read back with its session`() {
        MessageFeedbackStore.put(context, "m1", "down", "note", sessionId = "s1")
        val listed = MessageFeedbackStore.listForSession(context, "s1")
        assertEquals(listOf("m1"), listed.map { it.first })
        assertEquals("down", listed.single().second.kind)
    }

    @Test
    fun `a later put without a session keeps the one the message already had`() {
        MessageFeedbackStore.put(context, "m1", "up", sessionId = "s1")
        MessageFeedbackStore.put(context, "m1", "down")
        assertEquals("s1", MessageFeedbackStore.all(context).getValue("m1").sessionId)
    }

    @Test
    fun `a save that cannot be written is an error, not a success`() {
        // The parent directory does not exist, so the temporary file cannot even be created.
        MessageFeedbackStore.fileForTest = File(tmp.root, "no/such/dir/feedback.json")
        try {
            MessageFeedbackStore.put(context, "m1", "up")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `a damaged file that cannot be backed up is left alone`() {
        val f = File(tmp.root, "feedback.json").apply { writeText("{ not json") }
        // A directory in the way of the backup name makes the rename fail.
        File(tmp.root, "feedback.json.corrupt").apply { mkdirs(); File(this, "x").writeText("x") }
        MessageFeedbackStore.all(context) // notices the damage
        try {
            MessageFeedbackStore.put(context, "m1", "up")
            fail("expected an IOException")
        } catch (_: IOException) {
        }
        assertEquals("{ not json", f.readText())
        assertTrue(File(tmp.root, "feedback.json.corrupt").isDirectory)
    }
}
