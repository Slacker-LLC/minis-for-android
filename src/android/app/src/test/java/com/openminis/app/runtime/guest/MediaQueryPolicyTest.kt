package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-eta-media-search] Ported from Eta's `search_media` / `search_audio` filter rules
 * (Mangi-11/Eta @ c15de97). The escaping matters in practice: phone albums are full of names
 * like "IMG_2026-01-05 50% off.png".
 */
class MediaQueryPolicyTest {

    @Test
    fun `limits default and clamp to the advertised range`() {
        assertEquals(MediaQueryPolicy.DEFAULT_LIMIT, MediaQueryPolicy.clampLimit(null))
        assertEquals(1, MediaQueryPolicy.clampLimit(0))
        assertEquals(MediaQueryPolicy.MAX_LIMIT, MediaQueryPolicy.clampLimit(1_000))
        assertEquals(50, MediaQueryPolicy.clampLimit(50))
    }

    @Test
    fun `only the advertised media kinds are accepted`() {
        assertEquals("photo", MediaQueryPolicy.type("PHOTO"))
        assertEquals("audio", MediaQueryPolicy.type(" audio "))
        assertEquals("all", MediaQueryPolicy.type(null))
        assertEquals("all", MediaQueryPolicy.type(""))
        assertNull(MediaQueryPolicy.type("document"))
        assertEquals(listOf("photo", "video", "audio", "all"), MediaQueryPolicy.TYPES)
    }

    @Test
    fun `a blank query applies no filter`() {
        assertNull(MediaQueryPolicy.nameFilter(null))
        assertNull(MediaQueryPolicy.nameFilter("   "))
    }

    @Test
    fun `a query becomes a literal like clause`() {
        val filter = MediaQueryPolicy.nameFilter("holiday")!!

        assertEquals("display_name LIKE ? ESCAPE '\\'", filter.first)
        assertEquals("%holiday%", filter.second)
    }

    @Test
    fun `wildcards inside a query are escaped`() {
        assertEquals("%50\\%%", MediaQueryPolicy.nameFilter("50%")!!.second)
        assertEquals("%a\\_b%", MediaQueryPolicy.nameFilter("a_b")!!.second)
        assertEquals("%c:\\\\path%", MediaQueryPolicy.nameFilter("c:\\path")!!.second)
    }

    @Test
    fun `the filter column can be chosen`() {
        assertEquals(
            "bucket_display_name LIKE ? ESCAPE '\\'",
            MediaQueryPolicy.nameFilter("Camera", column = "bucket_display_name")!!.first,
        )
    }
}
