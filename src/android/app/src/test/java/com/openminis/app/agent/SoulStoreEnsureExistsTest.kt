package com.openminis.app.agent

import com.openminis.app.runtime.files.WorkspaceFileClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame

/** Unit tests for Issue #185: Fail-safe SOUL.md seeding. */
class SoulStoreEnsureExistsTest {

    @Test
    fun `both initialization entries seed only after confirmed absence`() = runTest {
        var writes = 0
        assertFalse(SoulStore.seedAfterConfirmedMissing(inspect = {}, seed = { writes++ }))
        assertTrue(SoulStore.seedAfterConfirmedMissing(
            inspect = { throw WorkspaceFileClient.Failure("NOT_FOUND", "missing file") },
            seed = { writes++ },
        ))
        assertEquals(1, writes)
    }

    @Test
    fun `read and runtime failures cannot cause any default write`() = runTest {
        val errors = listOf(
            SocketTimeoutException("Read timed out"),
            WorkspaceFileClient.Failure("POLICY_DENIED", "permission denied"),
            CancellationException("startup cancelled"),
        )
        for (expected in errors) {
            var writes = 0
            val actual = runCatching {
                SoulStore.seedAfterConfirmedMissing(inspect = { throw expected }, seed = { writes++ })
            }.exceptionOrNull()
            assertSame(expected, actual)
            assertEquals(0, writes)
        }
    }

    @Test
    fun `seed write failure remains visible to initialization`() = runTest {
        val expected = IOException("write refused")
        val actual = runCatching {
            SoulStore.seedAfterConfirmedMissing(
                inspect = { throw WorkspaceFileClient.Failure("NOT_FOUND", "missing file") },
                seed = { throw expected },
            )
        }.exceptionOrNull()
        assertSame(expected, actual)
    }

    @Test
    fun `isMissingFileNotFound detects not_found error code`() {
        val failure = WorkspaceFileClient.Failure(
            code = "NOT_FOUND",
            detail = "file does not exist",
        )
        assertTrue(SoulStore.isMissingFileNotFound(failure))
    }

    @Test
    fun `isMissingFileNotFound rejects timeout and network errors`() {
        val timeout = SocketTimeoutException("Read timed out")
        assertFalse(SoulStore.isMissingFileNotFound(timeout))

        val ioError = IOException("Connection reset by peer")
        assertFalse(SoulStore.isMissingFileNotFound(ioError))

        val failure = WorkspaceFileClient.Failure("POLICY_DENIED", "permission denied")
        assertFalse(SoulStore.isMissingFileNotFound(failure))
    }
}
