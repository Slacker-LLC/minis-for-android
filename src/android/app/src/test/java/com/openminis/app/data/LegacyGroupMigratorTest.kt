package com.openminis.app.data

import com.openminis.app.data.migration.LegacyBindingRecord
import com.openminis.app.data.migration.LegacyGroupMigrator
import com.openminis.app.data.migration.LegacyGroupStateParser
import com.openminis.app.data.migration.LegacyGroupPointers
import com.openminis.app.data.migration.LegacyModelGroup
import com.openminis.app.data.migration.LegacyState
import com.openminis.app.data.model.FallbackStrategy
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyGroupMigratorTest {
    private fun entry(id: String, modelId: String = id) = ModelEntry(
        providerInstanceId = "provider",
        baseModel = LLMModel(id = modelId, displayName = modelId, provider = "OpenAI"),
        uuid = id,
    )

    @Test
    fun migratesAllFivePointersFallbackAndAgentLoopGroups() {
        val entries = listOf(entry("m1"), entry("m2"), entry("l1"), entry("v1"), entry("i1"), entry("o1"))
        val groups = listOf(
            LegacyModelGroup("main-g", listOf("m1", "m2"), FallbackStrategy.always, sortOrder = 4),
            LegacyModelGroup("light-g", listOf("l1"), sortOrder = 2),
            LegacyModelGroup("vision-g", listOf("v1"), sortOrder = 3),
            LegacyModelGroup("voice-in-g", listOf("i1"), sortOrder = 1),
            LegacyModelGroup("voice-out-g", listOf("o1"), sortOrder = 5),
        )
        val state = LegacyState(
            config = ProviderConfig(modelEntries = entries.toMutableList(), agentLoopModelEntryIds = mutableListOf("m1")),
            groups = groups,
            pointers = LegacyGroupPointers("main-g", "light-g", "vision-g", "voice-in-g", "voice-out-g"),
            agentLoopGroupIds = listOf("main-g", "light-g", "missing"),
            entryIdAliases = emptyMap(),
            availableEntryIds = entries.map { it.id }.toSet(),
        )

        val result = LegacyGroupMigrator.migrate(state)
        assertEquals(listOf("m1", "m2"), result.config.slots.entries(ModelSlot.main))
        assertEquals(listOf("l1"), result.config.slots.entries(ModelSlot.light))
        assertEquals(listOf("v1"), result.config.slots.entries(ModelSlot.vision))
        assertEquals(listOf("i1"), result.config.slots.entries(ModelSlot.voiceInput))
        assertEquals(listOf("o1"), result.config.slots.entries(ModelSlot.voiceOutput))
        assertEquals(FallbackStrategy.always, result.config.fallbackTrigger)
        assertEquals(listOf("m1", "m2", "l1"), result.config.agentLoopModelEntryIds)
    }

    @Test
    fun missingPointersStayEmptyAndOverridePrecedenceIsMainThenSortOrder() {
        val entries = listOf(
            entry("shared").copy(overrides = ModelOverrides(defaultThinkingLevel = ThinkingLevel.LOW)),
            entry("other"),
        )
        val groups = listOf(
            LegacyModelGroup("late", listOf("shared", "other"), defaultThinkingLevel = ThinkingLevel.HIGH, contextLimitTokens = 200_000, lastContextLimitTokens = 400_000, sortOrder = 2),
            LegacyModelGroup("main", listOf("shared"), defaultThinkingLevel = ThinkingLevel.MEDIUM, contextLimitTokens = null, lastContextLimitTokens = 128_000, sortOrder = 9),
            LegacyModelGroup("early", listOf("shared"), defaultThinkingLevel = ThinkingLevel.XHIGH, contextLimitTokens = 64_000, lastContextLimitTokens = 64_000, sortOrder = 1),
        )
        val result = LegacyGroupMigrator.migrate(
            LegacyState(
                config = ProviderConfig(modelEntries = entries.toMutableList()),
                groups = groups,
                pointers = LegacyGroupPointers(main = "main", light = "gone"),
                agentLoopGroupIds = emptyList(),
                entryIdAliases = emptyMap(),
                availableEntryIds = setOf("shared", "other"),
            ),
        )
        val shared = result.config.modelEntries.first { it.id == "shared" }.overrides
        val other = result.config.modelEntries.first { it.id == "other" }.overrides
        assertEquals(ThinkingLevel.LOW, shared.defaultThinkingLevel)
        assertEquals(64_000, shared.contextLimitTokens)
        assertEquals(128_000, shared.lastContextLimitTokens)
        assertEquals(ThinkingLevel.HIGH, other.defaultThinkingLevel)
        assertEquals(200_000, other.contextLimitTokens)
        assertEquals(emptyList<String>(), result.config.slots.light)
    }

    @Test
    fun migratesGroupBindingsToAvailableEntriesAndPreservesEntryBindings() {
        val entries = listOf(entry("e1", "model-one"), entry("e2", "model-two"), entry("e3", "model-three"))
        val state = LegacyState(
            config = ProviderConfig(modelEntries = entries.toMutableList()),
            groups = listOf(
                LegacyModelGroup("g", listOf("e1", "e2")),
                LegacyModelGroup("empty", emptyList()),
                LegacyModelGroup("unavailable", listOf("e1")),
            ),
            pointers = LegacyGroupPointers(),
            agentLoopGroupIds = emptyList(),
            entryIdAliases = mapOf("old-e1" to "e1"),
            availableEntryIds = setOf("e2", "e3"),
            sessionBindings = listOf(
                LegacyBindingRecord("s-last-missing", """{"type":"group","groupId":"g","lastEntryId":"old-e1"}""", "old-model"),
                LegacyBindingRecord("s-last-available", """{"type":"group","groupId":"g","lastEntryId":"e2"}""", "old-model"),
                LegacyBindingRecord("s-no-members", """{"type":"group","groupId":"empty"}""", "old-model"),
                LegacyBindingRecord("s-all-unavailable", """{"type":"group","groupId":"unavailable"}""", "old-model"),
                LegacyBindingRecord("s-missing-group", """{"type":"group","groupId":"gone"}""", "old-model"),
                LegacyBindingRecord("s-entry", """{"type":"entry","entryId":"e3"}""", "model-three"),
            ),
        )
        val migrated = LegacyGroupMigrator.migrate(state).sessionBindings.associateBy { it.id }
        assertEquals("""{"type":"entry","entryId":"e2"}""", migrated.getValue("s-last-missing").binding)
        assertEquals("model-two", migrated.getValue("s-last-available").modelId)
        assertNull(migrated.getValue("s-no-members").binding)
        assertNull(migrated.getValue("s-all-unavailable").binding)
        assertNull(migrated.getValue("s-missing-group").binding)
        assertEquals(state.sessionBindings.last().binding, migrated.getValue("s-entry").binding)
    }

    @Test
    fun migrationIsIdempotentAndKeepsSystemVoiceIdsVerbatim() {
        val systemVoice = "__builtin_system_speech__/system-asr-offline"
        val first = LegacyGroupMigrator.migrate(
            LegacyState(
                config = ProviderConfig(),
                groups = listOf(LegacyModelGroup("voice", listOf(systemVoice))),
                pointers = LegacyGroupPointers(voiceInput = "voice"),
                agentLoopGroupIds = emptyList(),
                entryIdAliases = emptyMap(),
                availableEntryIds = setOf(systemVoice),
                scheduledBindings = listOf(LegacyBindingRecord("task", """{"type":"group","groupId":"voice"}""")),
            ),
        )
        val second = LegacyGroupMigrator.migrate(
            LegacyState(
                config = first.config,
                groups = listOf(LegacyModelGroup("voice", listOf(systemVoice))),
                pointers = LegacyGroupPointers(voiceInput = "voice"),
                agentLoopGroupIds = emptyList(),
                entryIdAliases = emptyMap(),
                availableEntryIds = setOf(systemVoice),
                scheduledBindings = first.scheduledBindings.map { LegacyBindingRecord(it.id, it.binding, it.modelId) },
            ),
        )
        assertEquals(listOf(systemVoice), first.config.slots.voiceInput)
        assertEquals(first.config.slots.voiceInput, second.config.slots.voiceInput)
        assertEquals(first.config.agentLoopModelEntryIds, second.config.agentLoopModelEntryIds)
        assertEquals(first.scheduledBindings, second.scheduledBindings)
    }

    @Test
    fun migratesBotBindingsUsingB0SelectionAndFailureRules() {
        val rawEntry = """{ "type" : "entry", "entryId" : "e3" }"""
        val result = LegacyGroupMigrator.migrate(
            LegacyState(
                config = ProviderConfig(modelEntries = listOf(entry("e1"), entry("e2"), entry("e3")).toMutableList()),
                groups = listOf(
                    LegacyModelGroup("available", listOf("e1", "e2")),
                    LegacyModelGroup("unavailable", listOf("e1")),
                    LegacyModelGroup("empty", emptyList()),
                ),
                pointers = LegacyGroupPointers(),
                agentLoopGroupIds = emptyList(),
                entryIdAliases = emptyMap(),
                availableEntryIds = setOf("e2", "e3"),
                scheduledBindings = listOf(
                    LegacyBindingRecord("scheduled-available", """{"type":"group","groupId":"available"}"""),
                    LegacyBindingRecord("scheduled-unavailable", """{"type":"group","groupId":"unavailable"}"""),
                ),
                botBindings = listOf(
                    LegacyBindingRecord("available", """{"type":"group","groupId":"available"}"""),
                    LegacyBindingRecord("unavailable", """{"type":"group","groupId":"unavailable"}"""),
                    LegacyBindingRecord("missing", """{"type":"group","groupId":"gone"}"""),
                    LegacyBindingRecord("empty", """{"type":"group","groupId":"empty"}"""),
                    LegacyBindingRecord("entry", rawEntry),
                    LegacyBindingRecord("malformed", "{bad json"),
                    LegacyBindingRecord("blank", "   "),
                    LegacyBindingRecord("legacy-bare-id", "e3"),
                ),
            ),
        )
        val migrated = result.botBindings.associateBy { it.id }
        assertEquals("""{"type":"entry","entryId":"e2"}""", migrated.getValue("available").binding)
        assertEquals("""{"type":"entry","entryId":"e1"}""", migrated.getValue("unavailable").binding)
        assertNull(migrated.getValue("missing").binding)
        assertNull(migrated.getValue("empty").binding)
        assertEquals(rawEntry, migrated.getValue("entry").binding)
        assertNull(migrated.getValue("malformed").binding)
        assertNull(migrated.getValue("blank").binding)
        assertEquals("""{"type":"entry","entryId":"e3"}""", migrated.getValue("legacy-bare-id").binding)
        assertEquals(2, result.warnings.size)
        assertEquals(setOf("malformed", "blank"), result.warnings.map { it.substringAfter("has a ").substringBefore(" model binding") }.toSet())
        val scheduled = result.scheduledBindings.associateBy { it.id }
        assertEquals("""{"type":"entry","entryId":"e2"}""", scheduled.getValue("scheduled-available").binding)
        assertEquals("""{"type":"entry","entryId":"e1"}""", scheduled.getValue("scheduled-unavailable").binding)
    }

    @Test
    fun parsesLegacyBackupJsonAtMigrationBoundaryAndCanonicalizesSlotReferences() {
        val backup = ProviderConfig(
            modelEntries = listOf(entry("legacy-one", "model-one"), entry("legacy-two", "model-two")).toMutableList(),
        )
        val json = Json { encodeDefaults = true }
        val root = json.encodeToJsonElement(ProviderConfig.serializer(), backup).jsonObject
        val oldGroup = JsonObject(
            mapOf(
                "id" to JsonPrimitive("backup-main"),
                "name" to JsonPrimitive("Backed up main"),
                "memberEntryIds" to JsonArray(listOf(JsonPrimitive("legacy-two"), JsonPrimitive("legacy-one"))),
                "strategy" to JsonPrimitive("loadBalance"),
                "fallbackStrategy" to JsonPrimitive("always"),
                "defaultThinkingLevel" to JsonPrimitive("HIGH"),
                "contextLimitTokens" to JsonPrimitive(100_000),
                "lastContextLimitTokens" to JsonPrimitive(200_000),
                "sortOrder" to JsonPrimitive(0),
            ),
        )
        val raw = JsonObject(
            root + mapOf(
                "modelGroups" to JsonArray(listOf(oldGroup)),
                "defaultPrimaryGroupId" to JsonPrimitive("backup-main"),
                "agentLoopGroupIds" to JsonArray(listOf(JsonPrimitive("backup-main"))),
            ),
        ).toString()
        val state = LegacyGroupStateParser.fromJson(
            config = backup,
            rawJson = raw,
            availableEntryIds = setOf("provider/model-one", "provider/model-two"),
        )

        val migrated = LegacyGroupMigrator.migrate(state).config
        assertEquals(listOf("provider/model-two", "provider/model-one"), migrated.slots.main)
        assertEquals(FallbackStrategy.always, migrated.fallbackTrigger)
        assertEquals(listOf("provider/model-two", "provider/model-one"), migrated.agentLoopModelEntryIds)
        assertEquals(ThinkingLevel.HIGH, migrated.modelEntries.first { it.baseModel.id == "model-two" }.overrides.defaultThinkingLevel)
        assertEquals(100_000, migrated.modelEntries.first { it.baseModel.id == "model-two" }.overrides.contextLimitTokens)
    }

}
