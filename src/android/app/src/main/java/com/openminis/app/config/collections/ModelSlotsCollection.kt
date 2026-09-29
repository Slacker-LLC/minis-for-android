package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.SystemVoiceEntries
import com.openminis.app.data.repository.ProviderRepository

/**
 * Fixed, ordered model slots exposed under `slots.<slot>.entries`.
 * Slots cannot be added or removed; entry ids are the only mutable binding.
 */
class ModelSlotsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {
    override val basePath: String get() = "slots"
    override val displayName: String get() = "Model slots"
    override val description: String get() = "Fixed ordered model entries for Main, Light, Vision, Voice Input, and Voice Output."
    override val addable: Boolean get() = false
    override val removable: Boolean get() = false
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE

    override fun childIds(): List<String> = ModelSlot.entries.map { it.name }

    override fun fields(forId: String): List<ConfigField> {
        val slot = parseSlot(forId) ?: return emptyList()
        return listOf(entriesField(slot))
    }

    override fun add(payload: ConfigValue): String =
        throw ConfigError.InvalidValue("Model slots are fixed and cannot be added")

    override fun remove(id: String) {
        throw ConfigError.InvalidValue("Model slots are fixed and cannot be removed")
    }

    private fun entriesField(slot: ModelSlot): ConfigField = ClosureField(
        path = "slots.${slot.name}.entries",
        displayName = "Ordered model entries",
        description = "Ordered model entry IDs assigned to the ${slot.name} slot.",
        valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
        risk = ConfigRisk.SENSITIVE,
        revertable = true,
        reader = {
            ConfigValue.Arr(
                repo.config.value.slots.entries(slot).map { ConfigValue.Str(it) },
            )
        },
        writer = { value ->
            val values = (value as? ConfigValue.Arr)?.value
                ?: throw ConfigError.TypeMismatch("array")
            val ids = values.map { item ->
                (item as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("array of strings")
            }.distinct()
            val config = repo.config.value
            for (id in ids) {
                if (!isCompatible(config, slot, id)) {
                    throw ConfigError.InvalidValue("Entry $id is not compatible with slot ${slot.name}")
                }
            }
            repo.setSlotEntries(slot, ids)
        },
    )

    private fun isCompatible(config: ProviderConfig, slot: ModelSlot, id: String): Boolean {
        val virtual = SystemVoiceEntries.resolve(id)
        if (virtual != null) {
            return when (slot) {
                ModelSlot.voiceInput -> virtual.model.hasAudioInput
                ModelSlot.voiceOutput -> virtual.model.hasAudioOutput
                else -> false
            }
        }
        val entry = config.modelEntries.firstOrNull { it.id == id } ?: return false
        if (entry.isHidden) return false
        return when (slot) {
            ModelSlot.main, ModelSlot.light -> entry.model.isTextOutput
            ModelSlot.vision -> entry.model.hasImageInput
            ModelSlot.voiceInput -> entry.model.hasAudioInput
            ModelSlot.voiceOutput -> entry.model.hasAudioOutput
        }
    }

    private fun parseSlot(id: String): ModelSlot? =
        ModelSlot.entries.firstOrNull { it.name == id }
}
