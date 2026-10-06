package com.openminis.app.backup

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Merging a package into a device that kept chatting after the backup was taken. The package's copy of a
 * session is newer, so the importer updates the session row; that update must not take the messages that
 * exist only on this device with it (a REPLACE on the session id cascades to its messages).
 */
@RunWith(AndroidJUnit4::class)
class BackupMergeInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)
    private val tag = "merge-${System.nanoTime()}"
    private val sessionId = "session-$tag"
    private val workDirs = mutableListOf<File>()

    @After
    fun tearDown() = runBlocking<Unit> {
        workDirs.forEach { it.deleteRecursively() }
        db.chatDao().deleteSession(sessionId)
    }

    private fun message(id: String, sortOrder: Int, text: String) = MessageEntity(
        id = id,
        sessionId = sessionId,
        role = "user",
        partsJson = """[{"type":"text","value":"$text"}]""",
        createdAt = 1_000L + sortOrder,
        sortOrder = sortOrder,
    )

    @Test
    fun aNewerPackageSessionKeepsMessagesThatOnlyThisDeviceHas() = runBlocking<Unit> {
        val dao = db.chatDao()
        dao.insertSession(
            ChatSessionEntity(id = sessionId, title = "Backed up title", modelId = "m", createdAt = 1_000L, updatedAt = 5_000L),
        )
        dao.insertMessage(message("m1-$tag", 0, "in the backup"))

        val summary = BackupExporter(context, db).export(BackupExporter.Options(categories = setOf(BackupCategory.CHATS)))
        workDirs += summary.packageFile
        val extracted = File(context.cacheDir, "merge-extract-$tag").apply { deleteRecursively(); mkdirs() }
        workDirs += extracted
        BackupZip.extract(summary.packageFile, extracted)

        // After the backup: a message only this device has, and the local session row now OLDER than the
        // package's, so the merge takes the package's session metadata.
        dao.insertMessage(message("m2-$tag", 1, "only on this device"))
        dao.updateSession(dao.getSession(sessionId)!!.copy(title = "Local title", updatedAt = 4_000L))

        val report = BackupImporter(context, db).import(
            BackupZip.packageRoot(extracted),
            BackupImporter.Options(categories = setOf(BackupCategory.CHATS)),
        )

        assertEquals("the package's newer session metadata wins", "Backed up title", dao.getSession(sessionId)?.title)
        assertNotNull("the message from the package is there", dao.getMessageById("m1-$tag"))
        assertNotNull("the message only this device had survives the merge", dao.getMessageById("m2-$tag"))
        assertEquals(2, dao.messageCountForSession(sessionId))
        assertEquals(null, report.categories.firstOrNull { it.failed != null }?.failed)
    }
}
