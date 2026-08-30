package io.github.slackerllc.minis.tools.runtime

import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LinuxPythonRunHandlerCleanupTest {

    @Test
    fun runtimeFailureStillDeletesTemporaryPythonScript() = runBlocking {
        val filesDir = Files.createTempDirectory("minis-python-cleanup").toFile()
        val context = TestContext(filesDir)
        val sessionId = "issue29-python-cleanup"
        val workspace = File(filesDir, "minis-sessions/$sessionId/workspace")

        try {
            val result = LinuxPythonRunHandler().execute(
                argsJson = """{"tool_title":"python cleanup","code":"print('ok')"}""",
                sessionId = sessionId,
                context = context,
                toolId = "issue29",
            )

            assertFalse(result.success)
            assertTrue(workspace.isDirectory)
            val leftovers = workspace.listFiles { file -> file.name.startsWith("python_run_") }
            assertNotNull(leftovers)
            assertTrue("temporary python script was not deleted", leftovers!!.isEmpty())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        private val preferences = EmptySharedPreferences()

        override fun getFilesDir(): File = root

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    }

    private class EmptySharedPreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = EmptyEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }

    private class EmptyEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = true
        override fun apply() = Unit
    }
}
