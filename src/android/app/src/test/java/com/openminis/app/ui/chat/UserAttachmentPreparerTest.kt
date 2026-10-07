package com.openminis.app.ui.chat

import com.openminis.app.data.model.MediaRef
import com.openminis.app.ui.chat.UserAttachmentPreparer.Companion.buildMediaRefPartJson
import com.openminis.app.ui.chat.UserAttachmentPreparer.Companion.uniqueUploadFileName
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UserAttachmentPreparerTest {
    private val dir: File = Files.createTempDirectory("uploads").toFile()

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `an upload name keeps safe characters and loses path parts`() {
        assertEquals("photo.jpg", uniqueUploadFileName(dir, "/storage/emulated/0/DCIM/photo.jpg"))
        assertEquals("a_b_.txt", uniqueUploadFileName(dir, "a b?.txt"))
        assertEquals("evil.sh", uniqueUploadFileName(dir, "..\\..\\evil.sh"))
        assertEquals("image.jpg", uniqueUploadFileName(dir, ""))
        assertFalse(uniqueUploadFileName(dir, "../../etc/passwd").contains('/'))
    }

    @Test
    fun `an existing name gets a numeric suffix before the extension`() {
        File(dir, "report.pdf").writeText("x")
        assertEquals("report_1.pdf", uniqueUploadFileName(dir, "report.pdf"))
        File(dir, "report_1.pdf").writeText("x")
        assertEquals("report_2.pdf", uniqueUploadFileName(dir, "report.pdf"))
        File(dir, "noext").writeText("x")
        assertEquals("noext_1", uniqueUploadFileName(dir, "noext"))
    }

    @Test
    fun `a media reference part carries the upload path only when there is one`() {
        val ref = MediaRef(id = "m1", relativePath = "2026-10-07/s/m1.png", mimeType = "image/png", originalFileName = "p.png")
        val withPath = JSONObject(buildMediaRefPartJson(ref, linuxPath = "/var/minis/attachments/uploads/p.png"))
        assertEquals("mediaRef", withPath.getString("type"))
        assertEquals("/var/minis/attachments/uploads/p.png", withPath.getJSONObject("value").getString("linuxPath"))
        assertEquals("p.png", withPath.getJSONObject("value").getString("originalFileName"))
        val without = JSONObject(buildMediaRefPartJson(ref.copy(originalFileName = null))).getJSONObject("value")
        assertFalse(without.has("linuxPath"))
        assertFalse(without.has("originalFileName"))
        assertEquals("2026-10-07/s/m1.png", without.getString("relativePath"))
        assertNull(without.optString("linuxPath", "").ifEmpty { null })
    }
}
