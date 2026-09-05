package com.openminis.app.agent

import com.openminis.app.runtime.minisd.WorkspaceFileClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Unit tests for Issue #185: Fail-safe SOUL.md seeding. */
class SoulStoreEnsureExistsTest {

    @Test
    fun `isMissingFileNotFound detects true ENOENT error`() {
        val failure = WorkspaceFileClient.Failure(
            code = "RUNTIME_UNAVAILABLE",
            detail = "open path: No such file or directory (os error 2)",
        )
        assertTrue(SoulStore.isMissingFileNotFound(failure))
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

        val failure = WorkspaceFileClient.Failure(
            code = "RUNTIME_UNAVAILABLE",
            detail = "daemon socket disconnected",
        )
        assertFalse(SoulStore.isMissingFileNotFound(failure))
    }
}
