package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoulIconImportTest {
    // -- SI01: sampling ---------------------------------------------------------------------------

    @Test
    fun `an image already at or near the target is not sampled`() {
        assertEquals(1, SoulIcon.sampleSizeFor(96, 96))
        assertEquals(1, SoulIcon.sampleSizeFor(150, 400))
        assertEquals(1, SoulIcon.sampleSizeFor(10, 10))
    }

    @Test
    fun `a large image is sampled but never below the target on its short side`() {
        assertEquals(64, SoulIcon.sampleSizeFor(10_000, 10_000)) // 156 px left; 128 would leave 78
        val big = SoulIcon.sampleSizeFor(10_000, 400)
        assertTrue("short side stays >= 96", 400 / big >= 96)
        assertEquals(2, SoulIcon.sampleSizeFor(192, 5_000))
        assertEquals(1, SoulIcon.sampleSizeFor(191, 5_000))
    }

    @Test
    fun `nonsense dimensions fall back to no sampling`() {
        assertEquals(1, SoulIcon.sampleSizeFor(0, 100))
        assertEquals(1, SoulIcon.sampleSizeFor(100, -1))
    }

    // -- SI02: percent-encoded minis:// ----------------------------------------------------------

    @Test
    fun `an encoded minis address is decoded to the real file name`() {
        assertEquals(
            SoulIcon.Source.LinuxPath("/var/minis/attachments/my icon.png"),
            SoulIcon.classifySource("minis://attachments/my%20icon.png"),
        )
        assertEquals(
            SoulIcon.Source.LinuxPath("/var/minis/attachments/头像.png"),
            SoulIcon.classifySource("minis://attachments/%E5%A4%B4%E5%83%8F.png"),
        )
        assertEquals("a+b stays a plus", SoulIcon.Source.LinuxPath("/var/minis/attachments/a+b.png"), SoulIcon.classifySource("minis://attachments/a+b.png"))
    }

    @Test
    fun `decoding happens once, so an escaped percent stays literal`() {
        assertEquals("100%.png", SoulIcon.percentDecode("100%25.png"))
        assertEquals("%41", SoulIcon.percentDecode("%2541"))
    }

    @Test
    fun `malformed escapes are refused rather than guessed`() {
        assertNull(SoulIcon.percentDecode("bad%zz.png"))
        assertNull(SoulIcon.percentDecode("cut%2"))
        assertNull(SoulIcon.percentDecode("lonely%"))
        assertNull("not valid UTF-8", SoulIcon.percentDecode("%FF%FE.png"))
        assertTrue(SoulIcon.classifySource("minis://attachments/x%zz.png") is SoulIcon.Source.Unsupported)
    }

    @Test
    fun `decoding does not hide a traversal from the later path check`() {
        // It must come out as a plain ".." path for the existing secure-path validation to refuse.
        assertEquals(
            SoulIcon.Source.LinuxPath("/var/minis/attachments/../../etc/passwd"),
            SoulIcon.classifySource("minis://attachments/%2e%2e/%2e%2e/etc/passwd"),
        )
    }

    // -- SI03: variation selectors need an emoji base -----------------------------------------------

    @Test
    fun `a letter or digit with a variation selector is not an emoji`() {
        assertFalse(SoulIcon.isEmojiGlyph("A️"))
        assertFalse(SoulIcon.isEmojiGlyph("z️"))
        assertFalse(SoulIcon.isEmojiGlyph("5️"))
        assertFalse(SoulIcon.isEmojiGlyph("中️"))
        assertFalse(SoulIcon.isEmojiGlyph("A‍B"))
        assertFalse("keycap needs a keycap base", SoulIcon.isEmojiGlyph("A⃣"))
    }

    @Test
    fun `the supported emoji forms are still accepted`() {
        assertTrue(SoulIcon.isEmojiGlyph("1️⃣"))
        assertTrue(SoulIcon.isEmojiGlyph("#⃣"))
        assertTrue(SoulIcon.isEmojiGlyph("❤️"))
        assertTrue(SoulIcon.isEmojiGlyph("🇯🇵")) // flag
        assertTrue(SoulIcon.isEmojiGlyph("👩‍💻")) // woman technologist
        assertTrue(SoulIcon.isEmojiGlyph("👍🏽")) // thumbs up, skin tone
        assertTrue(SoulIcon.isEmojiGlyph("✨"))
        assertFalse(SoulIcon.isEmojiGlyph("A"))
        assertFalse(SoulIcon.isEmojiGlyph("1"))
    }
}
