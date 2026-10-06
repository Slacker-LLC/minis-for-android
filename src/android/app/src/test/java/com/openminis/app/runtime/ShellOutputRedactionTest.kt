package com.openminis.app.runtime

import com.openminis.app.data.EnvVarRedactor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutputRedactionTest {

    private val secret = "sk-live-ABCDEF1234567890"

    private fun redactSecret(text: String) = EnvVarRedactor.redact(text, listOf(secret)).first

    @Test
    fun aSecretInCommandOutputIsMaskedBeforeTheModelSeesIt() {
        val out = ExecutionCoordinator.shellOutputForModel("token=$secret\ndone", ::redactSecret)
        assertFalse(out.contains(secret))
        assertTrue(out.contains("done"))
    }

    @Test
    fun terminalEscapesAreStrippedBeforeMatching() {
        // A colour code in the middle of the value must not hide it from the masker.
        val colourSplit = secret.take(8) + "\u001B[0m" + secret.drop(8)
        val out = ExecutionCoordinator.shellOutputForModel("v=$colourSplit", ::redactSecret)
        assertFalse(out.contains(secret))
    }

    @Test
    fun maskingHappensBeforeTheSizeCapSoAValueIsNotLeftHalfReadable() {
        val long = "x".repeat(200_000) + secret + "y".repeat(10)
        val out = ExecutionCoordinator.shellOutputForModel(long, ::redactSecret)
        assertFalse(out.contains(secret.take(12)))
    }

    @Test
    fun withNothingToMaskTheOutputPassesThrough() {
        assertTrue(ExecutionCoordinator.shellOutputForModel("hello", { it }).contains("hello"))
    }
}
