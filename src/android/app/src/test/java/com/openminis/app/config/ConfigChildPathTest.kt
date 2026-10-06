package com.openminis.app.config

import com.openminis.app.config.fields.ClosureField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigChildPathTest {

    @Test
    fun anIdWithoutDotsSplitsAsBefore() {
        assertEquals("models" to "inst/gpt", ConfigRegistry.splitChildPath("models.inst/gpt.contextWindow"))
    }

    @Test
    fun anIdWithDotsKeepsAllOfItAndTheFieldIsAfterTheLastDot() {
        assertEquals("models" to "inst/mimo-v2.5", ConfigRegistry.splitChildPath("models.inst/mimo-v2.5.contextWindow"))
        assertEquals("models" to "a/b.c.d", ConfigRegistry.splitChildPath("models.a/b.c.d.isHidden"))
    }

    @Test
    fun pathsWithoutTwoSeparatorsAreNotChildPaths() {
        assertNull(ConfigRegistry.splitChildPath("models"))
        assertNull(ConfigRegistry.splitChildPath("models.only"))
        assertNull(ConfigRegistry.splitChildPath(".id.field"))
        assertNull(ConfigRegistry.splitChildPath("models.id."))
    }

    private class FakeCollection(private val ids: List<String>) : ConfigCollection {
        override val basePath = "fakecoll"
        override val displayName = "Fake"
        override val description = "test"
        override fun childIds() = ids
        override fun fields(forId: String): List<ConfigField> =
            if (forId !in ids) emptyList()
            else listOf(
                ClosureField(
                    path = "fakecoll.$forId.value",
                    displayName = "v", description = "d",
                    valueSchema = ConfigSchema.Str(maxLength = 10),
                    reader = { ConfigValue.Str("x:$forId") },
                    writer = {},
                ),
            )
        override fun add(payload: ConfigValue) = error("unused")
        override fun remove(id: String) = error("unused")
    }

    @Test
    fun theRegistryResolvesAFieldOfAnIdThatContainsDots() {
        val registry = ConfigRegistry.newForTest()
        registry.register(FakeCollection(listOf("inst/mimo-v2.5", "plain")))

        val dotted = registry.resolveField("fakecoll.inst/mimo-v2.5.value")
        assertNotNull(dotted)
        assertEquals(ConfigValue.Str("x:inst/mimo-v2.5"), dotted!!.read())
        assertNotNull(registry.resolveField("fakecoll.plain.value"))
        assertNull(registry.resolveField("fakecoll.inst/mimo-v2.nope"))
        assertNull(registry.resolveField("fakecoll.missing.id.value"))
    }
}
