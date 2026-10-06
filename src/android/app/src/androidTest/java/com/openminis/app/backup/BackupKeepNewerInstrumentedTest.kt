package com.openminis.app.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Restoring an older package must not undo what the user did after it was taken: a session the device
 * holds in a newer state keeps its edited messages, while messages the device lacks are filled in.
 */
@RunWith(AndroidJUnit4::class)
class BackupKeepNewerInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val repo = ChatRepository(db.chatDao())
    private val workDirs = mutableListOf<File>()
    private var sessionId: String? = null

    @After
    fun tearDown() = runBlocking<Unit> {
        workDirs.forEach { it.deleteRecursively() }
        sessionId?.let { repo.deleteSession(it) }
    }

    @Test
    fun anOlderPackageDoesNotRollBackALaterEdit() = runBlocking<Unit> {
        val session = repo.createSession("model-x")
        sessionId = session.id
        val edited = repo.appendMessage(session.id, "assistant", """[{"type":"text","value":"original answer"}]""")
        val removedLater = repo.appendMessage(session.id, "user", """[{"type":"text","value":"asked once"}]""")

        val summary = BackupExporter(context, db).export(
            BackupExporter.Options(categories = setOf(BackupCategory.CHATS), includeCredentials = false, passphrase = null),
        )
        val extracted = File(context.cacheDir, "keepnewer-${System.nanoTime()}").apply { mkdirs() }
        workDirs += extracted
        workDirs += summary.packageFile
        BackupZip.extract(summary.packageFile, extracted)
        val root = BackupZip.packageRoot(extracted)

        // After the package was taken: the user edits one message and deletes another.
        db.chatDao().updateMessageParts(edited.id, """[{"type":"text","value":"edited later"}]""")
        db.chatDao().deleteMessage(removedLater.id)
        db.chatDao().touchSession(session.id, System.currentTimeMillis() + 60_000)

        BackupImporter(context, db).import(
            root,
            BackupImporter.Options(categories = setOf(BackupCategory.CHATS), passphrase = null, skipIntegrityCheck = false),
        )

        val kept = db.chatDao().getMessageById(edited.id)
        assertNotNull(kept)
        assertEquals("""[{"type":"text","value":"edited later"}]""", kept!!.partsJson)
        assertNotNull("a message the device no longer has is filled in", db.chatDao().getMessageById(removedLater.id))
    }
}
