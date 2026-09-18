package com.openminis.app.agent

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository

/** Shared by direct conversations and delegated execution. */
object BotModelResolver {
    fun resolve(repository: ProviderRepository, binding: String?): ModelEntry? {
        val visible = repository.allVisibleEntries().filter { it.model.isTextOutput }
        if (!binding.isNullOrBlank()) {
            return visible.firstOrNull { it.id == binding }
                ?: visible.singleOrNull { it.baseModel.id == binding }
        }
        val config = repository.config.value
        val primary = config.modelGroups.firstOrNull { it.id == config.defaultPrimaryGroupId }
        return primary?.let(repository::firstEnabledMemberEntry)?.takeIf { it in visible }
            ?: repository.lastUsedVisibleEntry()?.takeIf { it in visible }
            ?: repository.newestProviderNewestTextEntry()
    }
}
