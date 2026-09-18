package com.openminis.app.roleplay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

/**
 * [T-eta-character-cards] Ported from Eta `agent/roleplay/CharacterCardPng.kt`
 * (Mangi-11/Eta @ c15de97). The synthetic PNGs here are built chunk by chunk, so a test failure
 * says whether the reader, the writer or the validation is at fault.
 */
class CharacterCardPngTest {

    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    private fun cardJson(name: String = "Ada"): String = JSONObject()
        .put("spec", "chara_card_v2")
        .put("data", JSONObject().put("name", name))
        .toString()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(data.size)
            stream.write(typeBytes)
            stream.write(data)
            stream.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
        }
        return out.toByteArray()
    }

    private fun ihdr(width: Int, height: Int, bitDepth: Int = 8, colorType: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeInt(width)
            stream.writeInt(height)
            stream.writeByte(bitDepth)
            stream.writeByte(colorType)
            stream.writeByte(0)
            stream.writeByte(0)
            stream.writeByte(0)
        }
        return out.toByteArray()
    }

    /** A structurally valid PNG: signature, IHDR, IDAT, IEND plus whatever the test adds. */
    private fun png(vararg extra: ByteArray, width: Int = 2, height: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(signature)
        out.write(chunk("IHDR", ihdr(width, height)))
        extra.forEach(out::write)
        out.write(chunk("IDAT", byteArrayOf(1, 2, 3, 4)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun textChunk(keyword: String, json: String): ByteArray = chunk(
        "tEXt",
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            Base64.getEncoder().encode(json.toByteArray(Charsets.UTF_8)),
    )

    @Test
    fun `png detection needs the real signature`() {
        assertTrue(CharacterCardPng.isPng(png()))
        assertFalse(CharacterCardPng.isPng(byteArrayOf(1, 2, 3)))
        assertFalse(CharacterCardPng.isPng("not a png at all".toByteArray()))
    }

    @Test
    fun `a card is read out of the chara chunk`() {
        val card = CharacterCardPng.read(png(textChunk("chara", cardJson("Ada"))))

        assertEquals("Ada", card.name)
    }

    @Test
    fun `a v3 chunk wins over the v2 copy next to it`() {
        val bytes = png(
            textChunk("chara", cardJson("Old")),
            textChunk("ccv3", cardJson("New")),
        )

        assertEquals("New", CharacterCardPng.read(bytes).name)
    }

    @Test
    fun `an image without card data is refused by name`() {
        val failure = assertThrows(CharacterCardException::class.java) { CharacterCardPng.read(png()) }

        assertEquals("CARD_METADATA_MISSING", failure.code)
    }

    @Test
    fun `a corrupt v3 is reported instead of falling back to v2`() {
        val bytes = png(
            textChunk("chara", cardJson("Old")),
            chunk("tEXt", "ccv3".toByteArray() + byteArrayOf(0) + "not base64 at all!!".toByteArray()),
        )

        val failure = assertThrows(CharacterCardException::class.java) { CharacterCardPng.read(bytes) }

        assertEquals("CARD_V3_INVALID", failure.code)
    }

    @Test
    fun `writing strips the old card chunks and inserts fresh ones before IEND`() {
        val original = png(textChunk("chara", cardJson("Old")))
        val updated = CharacterCardCodec.decodeJson(cardJson("Updated"))

        val written = CharacterCardPng.write(original, updated)

        CharacterCardPng.validate(written)
        assertEquals("Updated", CharacterCardPng.read(written).name)
        val text = written.toString(Charsets.ISO_8859_1)
        assertEquals("the stale card chunk is replaced, not duplicated", 1, Regex("chara\u0000").findAll(text).count())
        assertTrue("both spellings are written", text.contains("ccv3\u0000"))
    }

    @Test
    fun `a broken crc is refused before anything is read`() {
        val bytes = png(textChunk("chara", cardJson("Ada"))).copyOf()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertThrows(IllegalArgumentException::class.java) { CharacterCardPng.validate(bytes) }
    }

    @Test
    fun `a truncated image or an absurd geometry is refused`() {
        val bytes = png()
        assertThrows(IllegalArgumentException::class.java) { CharacterCardPng.validate(bytes.copyOfRange(0, 20)) }

        val huge = ByteArrayOutputStream().apply {
            write(signature)
            write(chunk("IHDR", ihdr(40_000, 40_000)))
            write(chunk("IDAT", byteArrayOf(1)))
            write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { CharacterCardPng.validate(huge) }
    }

    @Test
    fun `a non png byte array is not a card image`() {
        assertThrows(IllegalArgumentException::class.java) {
            CharacterCardPng.validate("definitely not a png".toByteArray())
        }
    }
}
