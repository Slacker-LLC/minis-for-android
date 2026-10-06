package com.openminis.app.backup

import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackupKdfBoundsTest {
    private fun kdf(iterations: Int?) = BackupManifest.Encryption.KDF(
        alg = "pbkdf2-hmac-sha256",
        salt = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() }),
        iterations = iterations,
    )

    private fun assertRejected(iterations: Int?) {
        try {
            BackupCrypto.deriveKeys("passphrase", kdf(iterations))
            fail("iterations=$iterations should be rejected")
        } catch (_: BackupCrypto.CorruptMemberException) {
        }
    }

    @Test
    fun `absent and ordinary counts are accepted by the check`() {
        assertEquals(BackupCrypto.PBKDF2_ITERATIONS, BackupCrypto.checkedIterations(kdf(null)))
        assertEquals(1_000, BackupCrypto.checkedIterations(kdf(1_000)))
        assertEquals(BackupCrypto.MAX_KDF_ITERATIONS, BackupCrypto.checkedIterations(kdf(BackupCrypto.MAX_KDF_ITERATIONS)))
    }

    @Test
    fun `a package cannot ask for unbounded or nonsensical work before it is authenticated`() {
        // Rejected up front: if the check were missing these would hang or burn CPU instead of failing fast.
        assertRejected(Int.MAX_VALUE)
        assertRejected(BackupCrypto.MAX_KDF_ITERATIONS + 1)
        assertRejected(0)
        assertRejected(-5)
    }

    @Test
    fun `a derivation inside the bound still runs`() {
        BackupCrypto.deriveKeys("passphrase", kdf(1_000)) // does not throw
    }
}

class BackupEncryptionOrderTest {
    /** Plays out the in-place `X -> X.enc` rename over a set of names and reports whether any member was overwritten. */
    private fun encryptAll(names: List<String>): Pair<Set<String>, Boolean> {
        val present = names.toMutableSet()
        var overwritten = false
        for (f in BackupExporter.encryptionOrder(names.map(::File)).map { it.path }) {
            val dest = "$f.enc"
            if (dest in present) overwritten = true
            present.remove(f)
            present.add(dest)
        }
        return present to overwritten
    }

    @Test
    fun `a staged file named like another member's ciphertext is not overwritten`() {
        val (after, overwritten) = encryptAll(listOf("memory/memo.md", "memory/memo.md.enc"))
        org.junit.Assert.assertFalse(overwritten)
        assertEquals(setOf("memory/memo.md.enc", "memory/memo.md.enc.enc"), after)
    }

    @Test
    fun `longer collision chains stay collision free`() {
        val names = listOf("a", "a.enc", "a.enc.enc", "b/c", "b/c.enc")
        val (after, overwritten) = encryptAll(names)
        org.junit.Assert.assertFalse(overwritten)
        assertEquals("every member survives under its own name", names.size, after.size)
    }
}
