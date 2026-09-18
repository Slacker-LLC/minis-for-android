package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-file-vision] Ported from Eta `agent/model/AgentFileVisionToolCatalog.kt`
 * (Mangi-11/Eta @ c15de97). The refusals are the point: a source this app cannot
 * read must be named as such instead of being handed to a resolver that will fail
 * somewhere less legible.
 */
class ImageSourcePolicyTest {

    @Test
    fun `an absolute path stays a guest path`() {
        val source = ImageSourcePolicy.classify("/var/minis/attachments/chart.png")

        assertEquals(ImageSource.LinuxPath("/var/minis/attachments/chart.png"), source)
    }

    @Test
    fun `a relative path keeps the existing pass-through behaviour`() {
        val source = ImageSourcePolicy.classify("attachments/chart.png")

        assertEquals(ImageSource.LinuxPath("attachments/chart.png"), source)
    }

    @Test
    fun `a minis url is resolved into its guest path`() {
        val source = ImageSourcePolicy.classify("minis://attachments/chart.png")

        assertEquals(ImageSource.LinuxPath("/var/minis/attachments/chart.png"), source)
    }

    @Test
    fun `a minis url is percent-decoded`() {
        val source = ImageSourcePolicy.classify("minis://attachments/my%20chart%20(1).png")

        assertEquals(ImageSource.LinuxPath("/var/minis/attachments/my chart (1).png"), source)
    }

    @Test
    fun `a file uri without a host becomes a guest path`() {
        assertEquals(
            ImageSource.LinuxPath("/storage/emulated/0/DCIM/a.jpg"),
            ImageSourcePolicy.classify("file:///storage/emulated/0/DCIM/a.jpg"),
        )
        assertEquals(
            ImageSource.LinuxPath("/var/minis/a b.png"),
            ImageSourcePolicy.classify("file:///var/minis/a%20b.png"),
        )
    }

    @Test
    fun `a file uri with a host is refused`() {
        val source = ImageSourcePolicy.classify("file://example.com/a.png")

        assertTrue(source is ImageSource.Refused)
        assertTrue((source as ImageSource.Refused).reason.contains("host"))
    }

    @Test
    fun `a media store uri is read directly`() {
        val source = ImageSourcePolicy.classify("content://media/external/images/media/1234")

        assertEquals(ImageSource.MediaContentUri("content://media/external/images/media/1234"), source)
    }

    @Test
    fun `a media store picker uri is read directly`() {
        val uri = "content://media/picker/0/com.android.providers.media.photopicker/media/99"

        assertEquals(ImageSource.MediaContentUri(uri), ImageSourcePolicy.classify(uri))
    }

    @Test
    fun `another content authority is refused with the export hint`() {
        val source = ImageSourcePolicy.classify("content://com.example.app/files/secret.png")

        assertTrue(source is ImageSource.Refused)
        val reason = (source as ImageSource.Refused).reason
        assertTrue(reason.contains("android-photos export"))
        assertTrue(reason.contains(ImageSourcePolicy.MEDIA_URI_PREFIX))
    }

    @Test
    fun `a remote url is refused because nothing downloads it`() {
        val source = ImageSourcePolicy.classify("https://example.com/a.png")

        assertTrue(source is ImageSource.Refused)
        assertTrue((source as ImageSource.Refused).reason.contains("remote"))
    }

    @Test
    fun `a blank path is refused`() {
        assertTrue(ImageSourcePolicy.classify("   ") is ImageSource.Refused)
    }

    @Test
    fun `surrounding whitespace never changes the classification`() {
        assertEquals(
            ImageSource.LinuxPath("/var/minis/a.png"),
            ImageSourcePolicy.classify("  /var/minis/a.png  "),
        )
        assertEquals(
            ImageSource.MediaContentUri("content://media/external/images/media/1"),
            ImageSourcePolicy.classify(" content://media/external/images/media/1 "),
        )
    }
}
