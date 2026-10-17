package com.openminis.app.data

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.data.model.ModelSlots
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.PruneNotice
import com.openminis.app.data.repository.addImageSlotMembersToAgentList
import com.openminis.app.data.repository.pruneNoticesFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent reaches an image model only through the Agent Models list, so a model in the
 * Image slot must be on it too; and a refresh that drops a model must say what it took out.
 */
class ImageSlotAgentListSyncTest {

    private fun entry(id: String, name: String = id) = ModelEntry(
        providerInstanceId = "p",
        baseModel = LLMModel(id = id, displayName = name, provider = "p"),
        uuid = id,
    )

    private fun config(
        entries: List<ModelEntry>,
        slots: ModelSlots = ModelSlots(),
        agent: List<String> = emptyList(),
    ) = ProviderConfig(
        modelEntries = entries.toMutableList(),
        slots = slots,
        agentLoopModelEntryIds = agent.toMutableList(),
    )

    @Test
    fun `an image slot member missing from the agent list is added, in slot order`() {
        val c = config(
            listOf(entry("img1"), entry("img2"), entry("chat")),
            ModelSlots(image = listOf("img1", "img2")),
            agent = listOf("chat"),
        )
        assertTrue(c.addImageSlotMembersToAgentList())
        assertEquals(listOf("chat", "img1", "img2"), c.agentLoopModelEntryIds)
    }

    @Test
    fun `it is idempotent and never duplicates or reorders what is there`() {
        val c = config(
            listOf(entry("img1"), entry("chat")),
            ModelSlots(image = listOf("img1")),
            agent = listOf("img1", "chat"),
        )
        assertFalse(c.addImageSlotMembersToAgentList())
        assertEquals(listOf("img1", "chat"), c.agentLoopModelEntryIds)
    }

    @Test
    fun `a slot reference that no longer resolves to an entry is not added`() {
        val c = config(listOf(entry("chat")), ModelSlots(image = listOf("ghost")))
        assertFalse(c.addImageSlotMembersToAgentList())
        assertTrue(c.agentLoopModelEntryIds.isEmpty())
    }

    @Test
    fun `other slots never touch the agent list`() {
        val c = config(listOf(entry("m")), ModelSlots(main = listOf("m"), vision = listOf("m")))
        assertFalse(c.addImageSlotMembersToAgentList())
        assertTrue(c.agentLoopModelEntryIds.isEmpty())
    }

    @Test
    fun `a pruned model that was in a slot or the agent list yields a notice naming both`() {
        val gone = entry("gone", "Gone Model")
        val c = config(
            listOf(entry("kept")),
            ModelSlots(main = listOf("gone", "kept"), light = listOf("gone")),
            agent = listOf("gone"),
        )
        assertEquals(
            listOf(PruneNotice("Gone Model", listOf(ModelSlot.main, ModelSlot.light), agentList = true)),
            c.pruneNoticesFor(listOf(gone, entry("kept")), setOf("gone")),
        )
    }

    @Test
    fun `a pruned model nobody referenced produces no notice`() {
        val c = config(listOf(entry("kept")), ModelSlots(main = listOf("kept")))
        assertTrue(c.pruneNoticesFor(listOf(entry("unused"), entry("kept")), setOf("unused")).isEmpty())
    }
}
