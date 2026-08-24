package com.openminis.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/** Standard, user-visible dialing only; call takeover needs role/system privileges. */
class AndroidDialHandler : AndroidSystemHandler() {
    override val definition = AgentToolDefinition(
        name = "android.phone.dial",
        description = "Open the system dialer for a number. Does not place a call or take over a call.",
        parameters = mapOf(
            "phone_number" to AgentToolParam("string", "Number to prefill in the dialer"),
            "purpose" to AgentToolParam("string", "Unsupported: AI call takeover requires role/system privileges"),
        ),
        required = listOf("phone_number"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = args(argsJson)
        val number = args.optString("phone_number").trim()
        if (number.isBlank()) return ToolExecutionResult("Error: phone_number is required", false)
        if (args.optString("purpose").isNotBlank()) {
            return ToolExecutionResult(
                "Error: call_takeover_unsupported: purpose requires an approved InCallService/role or system privilege; no dial intent was sent",
                false,
            )
        }
        return AndroidSystemOps.sendIntent(
            context,
            JSONObject()
                .put("type", "activity")
                .put("action", Intent.ACTION_DIAL)
                .put("data", Uri.fromParts("tel", number, null).toString()),
        )
    }
}
