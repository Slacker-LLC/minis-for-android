package com.openminis.app.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openminis.app.config.confirm.ConfigConfirmationGate
import com.openminis.app.config.fields.ClosureField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The real bridge, in each autonomy mode: who is asked, and what the agent cannot change. */
@RunWith(AndroidJUnit4::class)
class AutonomyConfirmationInstrumentedTest {
    private val original = AutonomyStore.current
    @Volatile private var normalValue = "start"
    @Volatile private var riskyValue = "start"
    private val tag = System.nanoTime()
    private val normalPath = "test.autonomy$tag.normal"
    private val riskyPath = "test.autonomy$tag.risky"

    @Before
    fun register() {
        val registry = ConfigRegistry.get()
        registry.register(
            ClosureField(
                path = normalPath, displayName = "normal", description = "test field",
                valueSchema = ConfigSchema.Str(maxLength = 20), risk = ConfigRisk.NORMAL,
                reader = { ConfigValue.Str(normalValue) }, writer = { normalValue = (it as ConfigValue.Str).value },
            ),
        )
        registry.register(
            ClosureField(
                path = riskyPath, displayName = "risky", description = "test field",
                valueSchema = ConfigSchema.Str(maxLength = 20), risk = ConfigRisk.SENSITIVE,
                reader = { ConfigValue.Str(riskyValue) }, writer = { riskyValue = (it as ConfigValue.Str).value },
            ),
        )
    }

    @After
    fun restore() {
        ConfigConfirmationGate.userReject()
        AutonomyStore.setMode(original)
    }

    private fun items(path: String, value: String) =
        JSONArray().put(JSONObject().put("path", path).put("value_json", "\"$value\""))

    /** Runs a write; `onPending` is called if the confirmation dialog would have opened. */
    private fun write(path: String, value: String, onPending: (() -> Unit)? = null): Pair<JSONObject, Boolean> = runBlocking {
        val result = async(Dispatchers.IO) {
            ConfigBridge.performWriteBatch(items(path, value), null, "agent", null, skipConfirmation = false)
        }
        var sawPending = false
        val deadline = System.currentTimeMillis() + 3_000
        while (!result.isCompleted && System.currentTimeMillis() < deadline) {
            if (ConfigConfirmationGate.pending.value != null) {
                sawPending = true
                onPending?.invoke()
                break
            }
            kotlinx.coroutines.delay(20)
        }
        withTimeout(10_000) { result.await() } to sawPending
    }

    @Test
    fun smartLetsAnOrdinarySettingThroughWithoutAsking() {
        AutonomyStore.setMode(AutonomyMode.SMART)
        val (result, asked) = write(normalPath, "changed")
        assertFalse("no dialog for an ordinary setting", asked)
        assertTrue(result.toString(), result.optBoolean("ok", false))
        assertEquals("changed", normalValue)
    }

    @Test
    fun smartStillAsksForARiskySettingAndHonoursARefusal() {
        AutonomyStore.setMode(AutonomyMode.SMART)
        val (result, asked) = write(riskyPath, "nope") { ConfigConfirmationGate.userReject() }
        assertTrue("the dialog opened", asked)
        assertEquals("a refused change is not applied", "start", riskyValue)
        assertFalse(result.optBoolean("ok", true))
    }

    @Test
    fun askStillAsksForAnOrdinarySetting() {
        AutonomyStore.setMode(AutonomyMode.ASK)
        val (_, asked) = write(normalPath, "changed") { ConfigConfirmationGate.userReject() }
        assertTrue(asked)
        assertEquals("start", normalValue)
    }

    @Test
    fun fullNeverAsks() {
        AutonomyStore.setMode(AutonomyMode.FULL)
        val (result, asked) = write(riskyPath, "changed")
        assertFalse(asked)
        assertTrue(result.toString(), result.optBoolean("ok", false))
        assertEquals("changed", riskyValue)
    }

    @Test
    fun theAgentCannotRaiseItsOwnAutonomy() {
        AutonomyStore.setMode(AutonomyMode.ASK)
        val (result, _) = write("permissions.autonomy.mode", "full")
        assertFalse(result.optBoolean("ok", true))
        assertEquals("the mode is unchanged", AutonomyMode.ASK, AutonomyStore.current)
        assertNull(ConfigConfirmationGate.pending.value)
    }
}
