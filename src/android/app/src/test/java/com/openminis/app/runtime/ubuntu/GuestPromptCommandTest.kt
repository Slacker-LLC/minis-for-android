package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The interactive guest shell announces each prompt with OSC 133;A so the terminal can end OSC 7501 records. */
class GuestPromptCommandTest {
    @Test
    fun bashPrintsExactlyOneOsc133PromptMark() {
        val process = try {
            ProcessBuilder("bash", "-c", UbuntuKernel.GUEST_PROMPT_COMMAND).redirectErrorStream(true).start()
        } catch (error: Exception) {
            assumeTrue("bash is unavailable on this test host", false)
            return
        }
        val output = process.inputStream.readBytes()
        process.waitFor()
        assertEquals("\u001B]133;A\u001B\\", String(output, Charsets.ISO_8859_1))
    }
}
