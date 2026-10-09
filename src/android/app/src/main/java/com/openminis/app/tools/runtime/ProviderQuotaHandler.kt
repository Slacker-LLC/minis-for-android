package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.provider.quota.ProviderQuotaRepository
import com.openminis.app.provider.quota.ProviderQuotaText
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * What is left on the user's providers: balance, or the usage windows of a subscription. The agent can answer "how much DeepSeek
 * balance do I have", warn before a long job on an empty account, or pick the provider that still has quota. Keys never appear.
 */
class ProviderQuotaHandler : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read what is left on the user's model providers: the remaining balance (DeepSeek, Moonshot, SiliconFlow, OpenRouter, " +
            "relay services) or the remaining share of a subscription's windows (ChatGPT, Claude, Kimi Code), with when each resets. " +
            "Answers are cached for a few minutes; refresh=true reads them again. A provider the service gives no balance API for says so.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."),
            "provider" to AgentToolParam("string", "Only the provider whose label contains this text (case-insensitive). Omit for all."),
            "refresh" to AgentToolParam("boolean", "Read again instead of using the cached answer (default false)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "provider", "refresh"),
        timeoutMs = 60_000L,
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val title = args.optString("tool_title", "provider quota")
        val providers = (context.applicationContext as? MinisApp)?.providerRepositoryOrNull
            ?: return ToolExecutionResult("provider_quota: providers are not loaded yet", false, toolTitle = title)
        val filter = args.optString("provider").trim().lowercase().takeIf { it.isNotEmpty() }
        val force = args.optBoolean("refresh", false)
        val enabled = providers.config.value.instances.filter { it.isEnabled }
        val matching = enabled.filter { filter == null || it.label.lowercase().contains(filter) || it.providerType.displayName.lowercase().contains(filter) }
        val supported = matching.filter { ProviderQuotaRepository.supported(context, it) }
        if (supported.isEmpty()) {
            val why = if (filter != null && matching.isEmpty()) "no enabled provider matches '$filter'" else "none of ${matching.size} provider(s) has a balance source"
            return ToolExecutionResult("provider_quota: $why", filter == null, toolTitle = title)
        }
        coroutineScope {
            supported.map { instance -> async { ProviderQuotaRepository.refresh(context, providers, instance, force) } }.awaitAll()
        }
        val lines = supported.map { instance ->
            ProviderQuotaText.describe(instance.label.ifBlank { instance.providerType.displayName }, ProviderQuotaRepository.stateOf(instance.id))
        }
        val others = matching.size - supported.size
        val footer = if (others > 0 && filter == null) "\n($others other provider(s) have no balance source)" else ""
        return ToolExecutionResult(lines.joinToString("\n") + footer, true, toolTitle = title)
    }

    companion object {
        const val NAME = "provider_quota"
    }
}
