package com.openminis.app.share

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatExporterAttachmentCountTest {
    @Test
    fun currentMediaRefPartsAreCountedByMimeType() {
        val parts = """[
            {"type":"text","value":"hi"},
            {"type":"mediaRef","value":{"id":"1","relativePath":"a.png","mimeType":"image/png"}},
            {"type":"mediaRef","value":{"id":"2","relativePath":"b.jpg","mimeType":"IMAGE/JPEG"}},
            {"type":"mediaRef","value":{"id":"3","relativePath":"c.mp4","mimeType":"video/mp4"}},
            {"type":"mediaRef","value":{"id":"4","relativePath":"d.pdf","mimeType":"application/pdf"}}
        ]"""
        assertEquals(2 to 1, ChatExporter.countAttachments(parts))
    }

    @Test
    fun theOlderPartTypesStillCount() {
        assertEquals(2 to 1, ChatExporter.countAttachments("""[{"type":"image"},{"type":"image_url"},{"type":"video"}]"""))
    }

    @Test
    fun noAttachmentsOrUnreadableJsonIsZero() {
        assertEquals(0 to 0, ChatExporter.countAttachments("""[{"type":"text","value":"x"}]"""))
        assertEquals(0 to 0, ChatExporter.countAttachments("not json"))
        assertEquals(0 to 0, ChatExporter.countAttachments("""[{"type":"mediaRef"}]"""))
    }
}
