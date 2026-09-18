package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @
 * c15de97). The scan is one argv and the row parser is the only thing between a file listing and the
 * model, so both are pinned here.
 */
class ChatImagePolicyTest {

    private val qqDirectory = ChatImagePolicy.QQ_DIRECTORY

    @Test
    fun `the scan is one argv, with the filter and the size ceiling as arguments`() {
        val argv = ChatImagePolicy.findArgv(qqDirectory, ChatImagePolicy.QQ_PATH_FILTER)

        assertEquals("find", argv.first())
        assertEquals(qqDirectory, argv[1])
        assertTrue(argv.contains("-type"))
        assertTrue(argv.contains("f"))
        assertTrue("the grouping tokens are arguments, not shell", argv.contains("("))
        assertTrue(argv.contains("*/chatthumb/*"))
        assertTrue(
            "the ceiling is the encoder's own",
            argv.contains("-" + ChatImagePolicy.MAX_FILE_BYTES + "c"),
        )
        assertTrue(argv.contains("-printf"))
        assertTrue(argv.any { it.startsWith("%T@|%s|%p") })
    }

    @Test
    fun `a well-formed line under the scanned directory becomes a row`() {
        val row = ChatImagePolicy.row(
            "1712345678.0|4096|$qqDirectory/2024-04/chatimg/abc.jpg",
            qqDirectory,
            ChatImagePolicy::qqKind,
        )

        requireNotNull(row)
        assertEquals("abc.jpg", row.getString("name"))
        assertEquals("image", row.getString("kind"))
        assertEquals(1_712_345_678L, row.getLong("modified_at_epoch_seconds"))
        assertEquals(4096L, row.getLong("size_bytes"))
    }

    @Test
    fun `a path outside the scanned directory is refused`() {
        assertNull(
            "another app's cache is not this scan's business",
            ChatImagePolicy.row(
                "1712345678.0|4096|/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/a.jpg",
                qqDirectory,
                ChatImagePolicy::qqKind,
            ),
        )
        assertNull(
            "a prefix that only looks like the directory is not inside it",
            ChatImagePolicy.row(
                "1712345678.0|4096|" + qqDirectory + "-backup/a.jpg",
                qqDirectory,
                ChatImagePolicy::qqKind,
            ),
        )
    }

    @Test
    fun `a malformed line is not a row`() {
        assertNull(ChatImagePolicy.row("", qqDirectory, ChatImagePolicy::qqKind))
        assertNull(ChatImagePolicy.row("no|fields", qqDirectory, ChatImagePolicy::qqKind))
        assertNull(
            ChatImagePolicy.row("notatime|4096|$qqDirectory/a.jpg", qqDirectory, ChatImagePolicy::qqKind),
        )
        assertNull(
            ChatImagePolicy.row("1712345678.0|notasize|$qqDirectory/a.jpg", qqDirectory, ChatImagePolicy::qqKind),
        )
    }

    @Test
    fun `QQ's three cache trees keep their names`() {
        assertEquals("original", ChatImagePolicy.qqKind("/x/chatraw/a.jpg"))
        assertEquals("image", ChatImagePolicy.qqKind("/x/chatimg/a.jpg"))
        assertEquals("thumbnail", ChatImagePolicy.qqKind("/x/chatthumb/a.jpg"))
        assertEquals("other", ChatImagePolicy.qqKind("/x/elsewhere/a.jpg"))
        assertEquals("image", ChatImagePolicy.wechatKind("/x/image/a.jpg"))
    }
}
