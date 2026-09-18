package com.openminis.app.tools.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * [T-eta-device-context] Ported from Eta `agent/tool/DeviceContextTool.kt` (Mangi-11/Eta @
 * c15de97). Fixed clock and zone, so the weekday, the offset and the DST flag a model reasons
 * about are pinned rather than read from the machine running the test.
 */
class DeviceContextPolicyTest {

    // 2026-01-05T09:30:00+08:00 (a Monday).
    private val shanghaiMillis = 1_767_576_600_000L

    @Test
    fun `the time environment carries a parseable instant and the local offset`() {
        val env = DeviceContextPolicy.timeEnvironment(
            nowMillis = shanghaiMillis,
            zone = TimeZone.getTimeZone("Asia/Shanghai"),
            locale = Locale.US,
        )

        assertEquals("Asia/Shanghai", env.getString("timezone"))
        assertEquals("2026-01-05T09:30:00+08:00", env.getString("datetime"))
        assertEquals(480, env.getInt("utc_offset_minutes"))
        assertFalse(env.getBoolean("dst"))
    }

    @Test
    fun `the weekday is answered for the device locale`() {
        val english = DeviceContextPolicy.timeEnvironment(shanghaiMillis, TimeZone.getTimeZone("Asia/Shanghai"), Locale.US)
        assertEquals("Monday", english.getString("weekday"))

        val chinese = DeviceContextPolicy.timeEnvironment(shanghaiMillis, TimeZone.getTimeZone("Asia/Shanghai"), Locale.SIMPLIFIED_CHINESE)
        assertEquals("星期一", chinese.getString("weekday"))
    }

    @Test
    fun `a daylight-saving zone reports its own offset and flag`() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        val winter = DeviceContextPolicy.timeEnvironment(shanghaiMillis, newYork, Locale.US)
        val summer = DeviceContextPolicy.timeEnvironment(shanghaiMillis + 182L * 24 * 3600 * 1000, newYork, Locale.US)

        assertFalse(winter.getBoolean("dst"))
        assertEquals(-300, winter.getInt("utc_offset_minutes"))
        assertTrue("July in New York is daylight time", summer.getBoolean("dst"))
        assertEquals(-240, summer.getInt("utc_offset_minutes"))
    }

    @Test
    fun `the locale is reported as a language tag`() {
        val env = DeviceContextPolicy.timeEnvironment(
            shanghaiMillis,
            TimeZone.getTimeZone("Asia/Shanghai"),
            Locale.forLanguageTag("zh-Hant-TW"),
        )

        assertEquals("zh-Hant-TW", env.getString("locale"))
    }

    @Test
    fun `the same instant is one clock reading in the device zone`() {
        assertEquals(
            "09:30",
            DeviceContextPolicy.localClock(shanghaiMillis, TimeZone.getTimeZone("Asia/Shanghai")),
        )
        assertEquals(
            "20:30",
            DeviceContextPolicy.localClock(shanghaiMillis, TimeZone.getTimeZone("America/New_York")),
        )
    }
}
