package com.openminis.app.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.SessionOverrides
import com.openminis.app.roleplay.CharacterBinding
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Character identity, session settings and compaction history must survive a backup round trip.
 */
@RunWith(AndroidJUnit4::class)
class BackupSessionIdentityInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val tag = "identity-${System.nanoTime()}"
    private val sessionId = "session-$tag"
    private val workDirs = mutableListOf<File>()

    private val binding = CharacterBinding(
        characterId = "char-$tag",
        cardSnapshotJson = """{"spec":"chara_card_v2","data":{"name":"Ada"}}""",
        characterName = "Ada",
        userName = "Tester",
    ).toJson()
    private val overrides = SessionOverrides(systemPrompt = "Speak like a pirate", temperature = 0.3).toJsonOrNull()

    @After
    fun tearDown() = runBlocking<Unit> {
        workDirs.forEach { it.deleteRecursively() }
        db.chatDao().deleteSession(sessionId)
    }

    private suspend fun seedSession() {
        db.chatDao().insertSession(
            ChatSessionEntity(
                id = sessionId, title = "Bound", modelId = "m", createdAt = 1_000L, updatedAt = 2_000L,
                roleplayJson = binding, sessionOverrides = overrides,
            ),
        )
        db.chatDao().insertCompactMarker(
            CompactMarkerEntity(
                id = "marker-$tag", sessionId = sessionId, summary = "s", firstKeptSortOrder = 0,
                compactedCount = 3, createdAt = 1_500L, version = 2,
            ),
        )
    }

    /** Export chats, run [between] (what happens to the device after the backup), then restore the package. */
    private suspend fun exportThenImport(between: suspend () -> Unit): BackupImporter.Report {
        val summary = BackupExporter(context, db).export(BackupExporter.Options(categories = setOf(BackupCategory.CHATS)))
        workDirs += summary.packageFile
        val extracted = File(context.cacheDir, "identity-extract-$tag").apply { deleteRecursively(); mkdirs() }
        workDirs += extracted
        BackupZip.extract(summary.packageFile, extracted)
        between()
        return BackupImporter(context, db).import(
            BackupZip.packageRoot(extracted),
            BackupImporter.Options(categories = setOf(BackupCategory.CHATS)),
        )
    }

    private suspend fun forgetSession() {
        db.chatDao().deleteSession(sessionId)
    }

    @Test
    fun aSessionComesBackWithItsCharacterSettingsAndMarkerVersion() = runBlocking<Unit> {
        seedSession()
        exportThenImport { forgetSession() }

        val session = db.chatDao().getSession(sessionId)!!
        assertEquals("character binding", "Ada", CharacterBinding.fromJson(session.roleplayJson)?.characterName)
        assertEquals("session settings", 0.3, SessionOverrides.fromJson(session.sessionOverrides).temperature)
        assertEquals("compaction model", 2, db.chatDao().listCompactMarkers(sessionId).single().version)
    }

}
