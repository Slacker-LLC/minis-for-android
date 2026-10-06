package com.openminis.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the app's real stores with throw-away keys, and puts the metadata file back afterwards. */
@RunWith(AndroidJUnit4::class)
class EnvVarRepositoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val metadata = File(context.filesDir, "env-vars.json")
    private var original: ByteArray? = null
    private val created = mutableListOf<String>()

    @Before
    fun setUp() {
        original = metadata.takeIf { it.isFile }?.readBytes()
    }

    @After
    fun tearDown() {
        metadata.deleteRecursively()
        original?.let { metadata.writeBytes(it) }
        // Values written by the test: remove from the encrypted store through a fresh repository.
        val repo = EnvVarRepository(context)
        created.forEach { key -> repo.entries.value.find { it.key == key }?.let { repo.delete(it.id) } }
    }

    private fun key(name: String) = "ENVTEST_${name}_${System.nanoTime()}".also { created += it }

    @Test
    fun multilineUnicodeAndQuotedValuesAreStoredExactly() {
        val repo = EnvVarRepository(context)
        val k = key("EXACT")
        val value = "-----BEGIN KEY-----\nabc\n-----END KEY-----\n路径\t'quoted' \"double\" back\\slash"
        assertTrue(repo.add(k, value))
        assertEquals(value, repo.getValue(k))
        assertEquals(value, repo.allAsDict()[k])
    }

    @Test
    fun aValueWithControlCharactersIsRefusedNotSilentlyChanged() {
        val repo = EnvVarRepository(context)
        val k = key("CTRL")
        assertFalse(repo.add(k, "bad\u0000value"))
        assertFalse(repo.add(k, "bad\u009Bvalue"))
        assertNull(repo.getValue(k))
        assertTrue(repo.entries.value.none { it.key == k })
    }

    @Test
    fun aMetadataWriteFailureLeavesNothingBehind() {
        val repo = EnvVarRepository(context)
        val k = key("ROLLBACK")
        // A directory where the file belongs makes the replace fail.
        metadata.delete()
        File(metadata.parentFile, "env-vars.json.tmp").mkdirs()
        try {
            assertFalse(repo.add(k, "value"))
        } finally {
            File(metadata.parentFile, "env-vars.json.tmp").deleteRecursively()
        }
        assertTrue(repo.entries.value.none { it.key == k })
        assertNull("the value must not be left without its metadata", repo.getValue(k))
    }

    @Test
    fun anUnreadableMetadataFileBlocksWritesInsteadOfBeingOverwritten() {
        val k0 = key("EXISTING")
        EnvVarRepository(context).add(k0, "keep")
        val before = metadata.readText()
        metadata.writeText("{ this is not json")

        val repo = EnvVarRepository(context)
        assertFalse(repo.isReadable)
        assertFalse(repo.add(key("NEW"), "x"))
        assertEquals("{ this is not json", metadata.readText())

        metadata.writeText(before)
        assertTrue(EnvVarRepository(context).isReadable)
        assertNotNull(EnvVarRepository(context).entries.value.find { it.key == k0 })
    }
}
