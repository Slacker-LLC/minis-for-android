package com.openminis.app.agent

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.repository.ProviderRepository

/** Shared by direct conversations and delegated execution. */
object BotModelResolver {
    fun resolve(repository: ProviderRepository, binding: String?): ModelEntry? {
        // Prime the repository's current catalog using the same visibility path
        // used by Bot conversations, then resolve a pin only when it is an
        // explicit entry binding. Legacy group/malformed values follow main.
        val visibleTextEntries = repository.allVisibleEntries().filter { it.model.isTextOutput }
        val pinnedEntryId = (ModelBinding.parse(binding) as? ModelBinding.Entry)?.entryId
        if (pinnedEntryId != null) {
            val entry = visibleTextEntries.firstOrNull { it.id == pinnedEntryId } ?: return null
            val instance = repository.instance(entry.providerInstanceId) ?: return null
            return entry.takeIf { instance.isEnabled && repository.hasAnyCredential(instance) }
        }
        return repository.availableEntries(ModelSlot.main).firstOrNull { it.model.isTextOutput }
    }
}
