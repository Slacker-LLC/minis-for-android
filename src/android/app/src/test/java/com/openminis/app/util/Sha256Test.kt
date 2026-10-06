package com.openminis.app.util

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Sha256Test {

    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun `every input form gives the same lowercase hex`() {
        assertEquals(abc, Sha256.hex("abc"))
        assertEquals(abc, Sha256.hex("abc".toByteArray()))
        assertEquals(abc, Sha256.hex(ByteArrayInputStream("abc".toByteArray())))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
    }

    @Test fun `text is hashed as UTF-8`() {
        assertEquals(Sha256.hex("中文".toByteArray(Charsets.UTF_8)), Sha256.hex("中文"))
    }

    @Test fun `a stream longer than one read chunk is hashed whole`() {
        val bytes = ByteArray(200 * 1024) { (it % 251).toByte() }
        assertEquals(Sha256.hex(bytes), Sha256.hex(ByteArrayInputStream(bytes)))
    }

    @Test fun `a file digest tracks its content`() {
        val f = File.createTempFile("minis-sha256", ".txt")
        try {
            f.writeText("abc")
            val a = Sha256.hex(f)
            assertEquals(abc, a)
            f.writeText("abcd")
            assertNotEquals(a, Sha256.hex(f))
        } finally {
            f.delete()
        }
    }
}
