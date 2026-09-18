package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.android.AndroidPackageController
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner

/**
 * [T-eta-xposed-groups] Wi-Fi and Bluetooth, switched directly instead of through the settings UI.
 *
 * Ported from Eta `agent/tool/AgentStructuredDeviceTools.kt` (`set_device_state`, Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta runs one root command; here the same command
 * goes through the structured privileged path, one argv per call, with the target validated first.
 * The answer is the shared command result plus what was asked for, so a toggle that did not happen
 * says why instead of returning a bare boolean.
 */
class AndroidDeviceStateHandler : AndroidSystemHandler() {

    override val definition = AgentToolDefinition(
        name = "android.device.state",
        description = "Turn Wi-Fi or Bluetooth on or off directly, without driving the settings GUI.",
        parameters = mapOf(
            "target" to AgentToolParam("string", "Device capability", DeviceStatePolicy.TARGETS),
            "enabled" to AgentToolParam("boolean", "true to enable, false to disable"),
        ),
        required = listOf("target", "enabled"),
    )

    override suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        toolId: String,
    ): ToolExecutionResult {
        val a = args(argsJson)
        if (!a.has("enabled")) {
            return ToolExecutionResult("Error: enabled is required (true or false)", false)
        }
        val target = a.optString("target")
        val enabled = a.optBoolean("enabled")
        val argv = DeviceStatePolicy.argv(target, enabled)
            ?: return ToolExecutionResult(
                "Error: target must be " +
                    "${DeviceStatePolicy.TARGETS.joinToString(" or ")} (got '$target')",
                false,
            )
        val result = PrivilegedCommandRunner.run(
            context = context,
            sessionId = sessionId.ifBlank { "global" },
            argv = argv,
            operation = "set device state $target=${if (enabled) "on" else "off"}",
            risk = CommandRisk.USER_VISIBLE,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val payload = AndroidPackageController.commandJson(result).apply {
            put("target", target.trim().lowercase())
            put("enabled", enabled)
        }
        return ToolExecutionResult(payload.toString(2), result.success)
    }

    private companion object {
        const val COMMAND_TIMEOUT_MS = 15_000L
    }
}
