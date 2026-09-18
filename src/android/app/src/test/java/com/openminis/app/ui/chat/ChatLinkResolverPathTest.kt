package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.assertNull
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.test.runTest
import java.io.File

/** Unit tests for Issue #183: minis:// path double percent-encoding and '+' character decoding. */
class ChatLinkResolverPathTest {
    @get:Rule val temp = TemporaryFolder()

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
    fun `one decode preserves a literal percent sequence`() {
        val path = "workspace/my%2520file.txt"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/my%20file.txt", decoded)
    }

    @Test
    fun `decodePath preserves plus and decodes percent in same string`() {
        val path = "workspace/c++%20reference.md"
        val decoded = ChatLinkResolver.decodePath(path)
        assertEquals("workspace/c++ reference.md", decoded)
    }

    @Test
    fun `existing literal percent file wins over a second decoded filename`() = runTest {
        val literal = temp.newFile("my%20file.txt")
        temp.newFile("my file.txt")
        val found = ChatLinkResolver.resolveDecodedPath("my%2520file.txt") { File(temp.root, it).takeIf { file -> file.isFile } }
        assertEquals(literal, found)
    }

    @Test
    fun `double encoded filename falls back when first decoded path is absent`() = runTest {
        val expected = temp.newFile("c++ reference.md")
        val found = ChatLinkResolver.resolveDecodedPath("c++%2520reference.md") { File(temp.root, it).takeIf { file -> file.isFile } }
        assertEquals(expected, found)
    }

    @Test
    fun `malformed percent remains unchanged and missing path is attempted once`() = runTest {
        val attempts = mutableListOf<String>()
        val result: File? = ChatLinkResolver.resolveDecodedPath("bad%zz.txt") { attempts += it; null }
        assertNull(result)
        assertEquals(listOf("bad%zz.txt"), attempts)
    }

    @Test
    fun `encoded question mark remains part of the guest filename`() = runTest {
        val path = ChatLinkResolver.resolveDecodedPath("minis:///workspace/report%3F.txt") {
            ChatLinkResolver.resolveGuestPath(it, "minis")
        }
        assertEquals("/workspace/report?.txt", path)
    }
}
