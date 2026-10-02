package com.openminis.app.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.BotEntity
import com.openminis.app.data.db.CharacterEntity
import com.openminis.app.prompt.CustomPromptStore
import com.openminis.app.roleplay.CharacterMemoryRepository
import com.openminis.app.roleplay.CharacterStoragePolicy
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import com.openminis.app.scheduled.ScheduledTaskStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The reinstall path end to end, on a device: a real export, the data damaged the way an uninstall would, then
 * a real import of the package, and every kind of thing the user cares about is back. Runs against the app's
 * own database and files, so it uses ids of its own and puts back what it touched.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val tag = "rt-${System.nanoTime()}"
    private val characterId = "char-$tag"
    private val botId = "bot-$tag"
    private val readOnlyTaskId = "task-ro-$tag"
    private val fullTaskId = "task-full-$tag"
    private val workDirs = mutableListOf<File>()

    private val card = JSONObject()
        .put("spec", "chara_card_v2").put("spec_version", "2.0")
        .put("data", JSONObject().put("name", "Round Trip Ada").put("description", "d")).toString()
    private val avatar = ByteArray(5000) { (it * 7).toByte() }

    private val appearance get() = context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
    private val permissions get() = context.getSharedPreferences("unattended_access", Context.MODE_PRIVATE)
    private var originalTheme: Int? = null
    private var originalCustomPrompt: String? = null

    private fun task(id: String, tier: ScheduledTaskPermissionTier) = ScheduledTask(
        id = id, label = "label $tag", timeOfDayHour = 6, timeOfDayMinute = 15, repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p", enabled = true, permissionTier = tier,
    )

    @Before
    fun setUp() {
        originalTheme = if (appearance.contains("theme_mode")) appearance.getInt("theme_mode", 0) else null
        originalCustomPrompt = CustomPromptStore.text(context)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        workDirs.forEach { it.deleteRecursively() }
        db.characterDao().deleteCharacter(characterId)
        File(context.filesDir, CharacterStoragePolicy.avatarRelativePath(characterId)).delete()
        CharacterMemoryRepository.discard(context, characterId)
        db.botDao().getBot(botId)?.let { db.botDao().deleteBot(it) }
        ScheduledTaskManager(context).apply { delete(readOnlyTaskId); delete(fullTaskId) }
        if (originalTheme == null) appearance.edit().remove("theme_mode").apply()
        else appearance.edit().putInt("theme_mode", originalTheme!!).apply()
        CustomPromptStore.save(context, originalCustomPrompt.orEmpty())
        permissions.edit().remove("planted_by_test").apply()
    }

    private suspend fun seed() {
        appearance.edit().putInt("theme_mode", 2).apply()
        CustomPromptStore.save(context, "Round trip prompt $tag")
        db.characterDao().upsertCharacter(CharacterEntity(characterId, "Round Trip Ada", card, null, 100, 200))
        File(context.filesDir, CharacterStoragePolicy.avatarRelativePath(characterId)).apply { parentFile?.mkdirs(); writeBytes(avatar) }
        CharacterMemoryRepository.file(context, characterId).apply { parentFile?.mkdirs(); writeText("remembers $tag") }
        db.botDao().insertBot(BotEntity(botId, "Bot $tag", "be brief", null, true, 100, 200))
        val store = ScheduledTaskStore(context)
        store.upsert(task(readOnlyTaskId, ScheduledTaskPermissionTier.READ_ONLY), synchronous = true)
        store.upsert(task(fullTaskId, ScheduledTaskPermissionTier.FULL), synchronous = true)
    }

    /** What an uninstall does to this data, minus the files that are not ours. */
    private suspend fun wipe() {
        appearance.edit().putInt("theme_mode", 0).apply()
        CustomPromptStore.save(context, "")
        db.characterDao().deleteCharacter(characterId)
        File(context.filesDir, CharacterStoragePolicy.avatarRelativePath(characterId)).delete()
        CharacterMemoryRepository.discard(context, characterId)
        db.botDao().getBot(botId)?.let { db.botDao().deleteBot(it) }
        ScheduledTaskManager(context).apply { delete(readOnlyTaskId); delete(fullTaskId) }
    }

    private suspend fun exportThenImport(
        passphrase: String?,
        skipIntegrity: Boolean = false,
        tamper: ((File) -> Unit)? = null,
    ): BackupImporter.Report {
        val summary = BackupExporter(context, db).export(
            BackupExporter.Options(
                categories = setOf(BackupCategory.CHATS, BackupCategory.PROVIDERS),
                includeCredentials = true,
                passphrase = passphrase,
            ),
        )
        val extracted = File(context.cacheDir, "rt-extract-$tag").apply { deleteRecursively(); mkdirs() }
        workDirs += extracted
        workDirs += summary.packageFile
        BackupZip.extract(summary.packageFile, extracted)
        val root = BackupZip.packageRoot(extracted)
        wipe()
        tamper?.invoke(root)
        return BackupImporter(context, db).import(
            root,
            BackupImporter.Options(
                categories = setOf(BackupCategory.CHATS, BackupCategory.PROVIDERS),
                passphrase = passphrase,
                skipIntegrityCheck = skipIntegrity,
            ),
        )
    }

    private suspend fun assertEverythingCameBack(report: BackupImporter.Report) {
        assertEquals("theme preference", 2, appearance.getInt("theme_mode", -1))
        assertEquals("custom system prompt", "Round trip prompt $tag", CustomPromptStore.text(context))

        val character = db.characterDao().character(characterId)
        assertNotNull("character", character)
        assertEquals("Round Trip Ada", character!!.name)
        assertEquals(200L, character.updatedAt)
        val avatarFile = File(context.filesDir, CharacterStoragePolicy.avatarRelativePath(characterId))
        assertTrue("character artwork", avatarFile.isFile && avatarFile.readBytes().contentEquals(avatar))
        assertEquals(avatarFile.absolutePath, character.avatarPath)
        assertEquals("character memory", "remembers $tag", CharacterMemoryRepository.file(context, characterId).readText())

        assertEquals("bot", "Bot $tag", db.botDao().getBot(botId)?.name)

        val store = ScheduledTaskStore(context)
        assertTrue("read-only task is back and on", store.get(readOnlyTaskId)?.enabled == true)
        val full = store.get(fullTaskId)
        assertNotNull(full)
        assertFalse("a task with full access must come back switched off", full!!.enabled)
        assertEquals(ScheduledTaskPermissionTier.FULL, full.permissionTier)

        assertTrue("report names the restored extras", (report.extras["characters"] ?: 0) >= 1)
        assertTrue((report.extras["bots"] ?: 0) >= 1)
        assertTrue((report.extras["scheduled_tasks"] ?: 0) >= 2)
        assertTrue((report.extras["scheduled_tasks_disabled"] ?: 0) >= 1)
        assertTrue((report.extras["settings"] ?: 0) >= 1)
        assertTrue((report.extras["prompt"] ?: 0) >= 1)
        assertTrue(report.categories.none { it.failed != null })
    }

    @Test
    fun plainPackageBringsEverythingBack() = runBlocking<Unit> {
        seed()
        assertEverythingCameBack(exportThenImport(passphrase = null))
    }

    @Test
    fun encryptedPackageBringsEverythingBack() = runBlocking<Unit> {
        seed()
        assertEverythingCameBack(exportThenImport(passphrase = "correct horse battery staple"))
    }

    @Test
    fun aCraftedPackageCannotPlantPermissions() = runBlocking<Unit> {
        seed()
        // An attacker who edits a package also fixes its hashes, so the integrity pass is skipped here to test
        // the allowlist on its own; the next test shows the integrity pass catching a plain edit.
        val report = exportThenImport(passphrase = null, skipIntegrity = true) { root ->
            val file = File(root, "data/${BackupAppSettings.FILE_NAME}")
            val doc = JSONObject(file.readText())
            val prefs = doc.getJSONObject("prefs")
            prefs.put(
                "unattended_access",
                JSONObject().put("planted_by_test", JSONObject().put("t", "b").put("v", true)),
            )
            doc.put("prefs", prefs)
            file.writeText(doc.toString())
        }
        assertFalse("a permission preference must never be restored", permissions.contains("planted_by_test"))
        assertTrue("and the report says it ignored something", (report.extras["ignored"] ?: 0) >= 1)
        assertEquals("the legitimate settings still came back", 2, appearance.getInt("theme_mode", -1))
    }

    @Test
    fun anEditedPackageIsCaughtByTheIntegrityCheck() = runBlocking<Unit> {
        seed()
        val caught = try {
            exportThenImport(passphrase = null) { root ->
                File(root, "data/${BackupAppSettings.FILE_NAME}").appendText(" ")
            }.let { report -> report.integrityFailed.isNotEmpty() || report.categories.any { it.failed != null } }
        } catch (e: Exception) {
            true // refusing to import at all is also a pass
        }
        assertTrue("an edited member must not be imported silently", caught)
        assertFalse("and the tampered settings were not applied", appearance.getInt("theme_mode", -1) == 2)
    }
}
