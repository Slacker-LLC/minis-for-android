package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for Issue #183: minis:// path double percent-encoding and '+' character decoding. */
class ChatLinkResolverPathTest {

    @Test
    fun `decodePath preserves literal plus sign`() {
        val path = "workspace/c++_guide.md"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/c++_guide.md", decoded)
    }

    @Test
    fun `decodePath decodes standard percent encoded spaces`() {
        val path = "workspace/my%20file.txt"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/my file.txt", decoded)
    }

    @Test
    fun `decodePath decodes double percent-encoding`() {
        val path = "workspace/my%2520file.txt"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/my file.txt", decoded)
    }

    @Test
    fun `decodePath preserves plus and decodes percent in same string`() {
        val path = "workspace/c++%20reference.md"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/c++ reference.md", decoded)
    }
}
