package com.openminis.app.tools.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a root-deployed minisd; skipped on ordinary non-root CI devices. */
@RunWith(AndroidJUnit4::class)
class RootShellMinisdInstrumentedTest {
    @Test
    fun structuredRootExecUsesMinisdBroker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = ToolExecutor.execute(
            name = "root.shell",
            argsJson = """{"tool":"getprop","args":["ro.build.version.sdk"]}""",
            sessionId = "instrumented-root-exec",
            context = context,
            caller = ToolPermissionManager.CALLER_LOCAL,
        )
        assumeFalse("minisd is not deployed: ${result.output}", result.output.contains("RUNTIME_UNAVAILABLE"))
        assertTrue(result.output, result.success)
        // Device-independent: the broker returns the REAL sdk of the host.
        assertEquals("${android.os.Build.VERSION.SDK_INT}", result.output.trim())
    }
}
