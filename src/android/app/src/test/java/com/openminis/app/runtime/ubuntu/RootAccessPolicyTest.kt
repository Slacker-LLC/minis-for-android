package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-root-access-android] The two rules of the ported Eta `RootAccess`
 * (Mangi-11/Eta @ c15de97) that decide what the user is asked and what the app then
 * believes: the "ask once" policy, and the mapping from a probe's output to a state.
 *
 * The probe itself (spawning su) needs a device; these are the decisions around it.
 */
class RootAccessPolicyTest {

    @Test
    fun `a device that was never asked is asked`() {
        assertTrue(shouldRequestRoot(explicit = false, attempted = false, wasGranted = false))
    }

    @Test
    fun `a refusal is not repeated on every launch`() {
        assertFalse(shouldRequestRoot(explicit = false, attempted = true, wasGranted = false))
    }

    @Test
    fun `a user who asks gets a probe even after a refusal`() {
        assertTrue(shouldRequestRoot(explicit = true, attempted = true, wasGranted = false))
    }

    @Test
    fun `a device that granted before is re-probed without asking`() {
        assertTrue(shouldRequestRoot(explicit = false, attempted = true, wasGranted = true))
    }

    @Test
    fun `no su binary means the capability is unavailable, not denied`() {
        assertEquals(
            RootAccessStatus.UNAVAILABLE,
            rootStatusFor(suPresent = false, exitCode = 0, stdout = "", timedOut = false, error = null),
        )
    }

    @Test
    fun `uid zero is the grant`() {
        assertEquals(
            RootAccessStatus.GRANTED,
            rootStatusFor(suPresent = true, exitCode = 0, stdout = "0\n", timedOut = false, error = null),
        )
    }

    @Test
    fun `another uid means the su manager answered with somebody else`() {
        assertEquals(
            RootAccessStatus.DENIED,
            rootStatusFor(suPresent = true, exitCode = 0, stdout = "2000", timedOut = false, error = null),
        )
    }

    @Test
    fun `a timeout is its own state, and a launch failure is a denial`() {
        assertEquals(
            RootAccessStatus.TIMED_OUT,
            rootStatusFor(suPresent = true, exitCode = 137, stdout = "", timedOut = true, error = null),
        )
        assertEquals(
            RootAccessStatus.DENIED,
            rootStatusFor(
                suPresent = true,
                exitCode = 126,
                stdout = "",
                timedOut = false,
                error = "failed to start su: Permission denied",
            ),
        )
    }

    @Test
    fun `a silent success is unknown rather than assumed granted`() {
        assertEquals(
            RootAccessStatus.UNKNOWN,
            rootStatusFor(suPresent = true, exitCode = 0, stdout = "  \n", timedOut = false, error = null),
        )
    }

    @Test
    fun `only the granted status counts as granted`() {
        assertTrue(RootAccessState(status = RootAccessStatus.GRANTED).isGranted)
        listOf(
            RootAccessStatus.UNKNOWN,
            RootAccessStatus.UNAVAILABLE,
            RootAccessStatus.NOT_GRANTED,
            RootAccessStatus.DENIED,
            RootAccessStatus.TIMED_OUT,
        ).forEach { status ->
            assertFalse(status.name, RootAccessState(status = status).isGranted)
        }
    }
}
