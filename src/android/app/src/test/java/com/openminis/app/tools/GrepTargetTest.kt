package com.openminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrepTargetTest {
    @Test
    fun `a file or a directory can be searched`() {
        assertNull(LinuxFileOps.grepTargetError(JSONObject().put("exists", true).put("type", "file"), "/workspace/a"))
        assertNull(LinuxFileOps.grepTargetError(JSONObject().put("exists", true).put("type", "dir"), "/workspace"))
    }

    @Test
    fun `a path that does not exist is an error, not an empty complete search`() {
        val msg = LinuxFileOps.grepTargetError(JSONObject().put("exists", false), "/workspace/no-such-file.txt")
        assertEquals("Error: grep target not found: /workspace/no-such-file.txt", msg)
    }

    @Test
    fun `something that is neither a file nor a directory is refused`() {
        val msg = LinuxFileOps.grepTargetError(JSONObject().put("exists", true).put("type", "symlink"), "/workspace/l")
        assertEquals("Error: grep target is not a regular file or directory: /workspace/l", msg)
    }
}
