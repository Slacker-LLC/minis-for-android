package com.openminis.app.data.db

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderConfigImageSlotTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun entry(modelId: String) = ModelEntry(
        providerInstanceId = "inst",
        baseModel = LLMModel(id = modelId, displayName = modelId, provider = "OpenAI"),
        uuid = compositeEntryKey("inst", modelId),
    )

    private fun config(image: List<String>) = ProviderConfig(
        instances = mutableListOf(
            ProviderInstance(
                id = "inst",
                label = "inst",
                providerType = ProviderType.openAI,
                credentialType = ProviderCredential.apiKey,
            ),
        ),
        modelEntries = mutableListOf(entry("img-a"), entry("img-b"), entry("chat")),
        slots = ModelSlots(main = listOf("inst/chat"), image = image),
    )

    @Test
    fun imageSlotSurvivesRoomRoundTrip() {
        val original = config(listOf("inst/img-b", "inst/img-a"))
        val restored = original.toSnapshot(json).toProviderConfig(json)
        assertEquals(listOf("inst/img-b", "inst/img-a"), restored.slots.entries(ModelSlot.image))
        assertEquals(listOf("inst/chat"), restored.slots.main)
    }

    @Test
    fun snapshotWithoutImageRowIsBackfilledFromMirror() {
        val mirror = config(listOf("inst/img-a", "inst/img-b"))
        val legacyDb = config(emptyList())
        val legacySnapshot = legacyDb.toSnapshot(json).let { s ->
            s.copy(meta = s.meta.filterNot { it.key == ProviderConfigMetaKeys.SLOT_IMAGE })
        }
        val loaded = legacySnapshot.toProviderConfig(json)
        val restored = loaded.withImageSlotBackfilledFrom(legacySnapshot, mirror)
        assertEquals(listOf("inst/img-a", "inst/img-b"), restored.slots.image)
    }

    @Test
    fun backfillDropsIdsThatNoLongerExist() {
        val mirror = config(listOf("inst/gone", "inst/img-b"))
        val snapshot = config(emptyList()).toSnapshot(json).let { s ->
            s.copy(meta = s.meta.filterNot { it.key == ProviderConfigMetaKeys.SLOT_IMAGE })
        }
        val restored = snapshot.toProviderConfig(json).withImageSlotBackfilledFrom(snapshot, mirror)
        assertEquals(listOf("inst/img-b"), restored.slots.image)
    }

    @Test
    fun existingImageRowIsAuthoritativeEvenWhenEmpty() {
        // A user who cleared the image slot must not get the stale mirror value back.
        val mirror = config(listOf("inst/img-a"))
        val snapshot = config(emptyList()).toSnapshot(json)
        val loaded = snapshot.toProviderConfig(json)
        assertSame(loaded, loaded.withImageSlotBackfilledFrom(snapshot, mirror))
    }

    @Test
    fun backfillWithoutMirrorLeavesConfigUntouched() {
        val snapshot = config(emptyList()).toSnapshot(json).let { s ->
            s.copy(meta = s.meta.filterNot { it.key == ProviderConfigMetaKeys.SLOT_IMAGE })
        }
        val loaded = snapshot.toProviderConfig(json)
        assertSame(loaded, loaded.withImageSlotBackfilledFrom(snapshot, null))
    }
}
