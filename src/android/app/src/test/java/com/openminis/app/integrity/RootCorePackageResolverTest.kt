package com.openminis.app.integrity

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RootCorePackageResolverTest {
    private var now = 0L
    private var calls = 0
    private var output: String? = ""

    @Before fun setUp() {
        now = 0; calls = 0; output = ""
        RootCorePackageResolver.clearCache()
        RootCorePackageResolver.clock = { now }
        RootCorePackageResolver.factsRunner = { calls++; output }
    }

    @After fun tearDown() {
        RootCorePackageResolver.clearCache()
        RootCorePackageResolver.clock = System::currentTimeMillis
    }

    private val persistentDump = """
        pkgFlags=[ SYSTEM HAS_CODE PERSISTENT ALLOW_CLEAR_USER_DATA ]
        sharedUser=SharedUserSetting{abc android.uid.phone/1001}
        __HOME__
        com.miui.home/.launcher.Launcher
        __IME__
        com.sohu.inputmethod.sogou/.SogouIME
    """.trimIndent()

    @Test fun `persistent and shared system uid packages are core`() {
        assertTrue(CorePackageFactsParser.parse("x.y", persistentDump).persistent)
        assertEquals("android.uid.phone", CorePackageFactsParser.parse("x.y", persistentDump).sharedUserId)
        assertTrue(CorePackageFactsParser.isCore("x.y", persistentDump))
    }

    @Test fun `the current launcher and keyboard are core`() {
        assertTrue(CorePackageFactsParser.parse("com.miui.home", persistentDump).isCurrentHome)
        assertTrue(CorePackageFactsParser.isCore("com.sohu.inputmethod.sogou", persistentDump))
    }

    @Test fun `an ordinary preinstalled app is not core`() {
        val dump = "pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]\n__HOME__\ncom.miui.home/.L\n__IME__\ncom.x/.Y"
        assertFalse(CorePackageFactsParser.isCore("com.example.bloat", dump))
    }

    @Test fun `unreadable facts fail closed`() {
        output = null
        assertTrue(RootCorePackageResolver.isCore("com.example.bloat"))
    }

    @Test fun `answers are cached briefly`() {
        output = "pkgFlags=[ SYSTEM ]\n__HOME__\n\n__IME__\n"
        assertFalse(RootCorePackageResolver.isCore("com.example.a"))
        assertFalse(RootCorePackageResolver.isCore("com.example.a"))
        assertEquals(1, calls)
        now = 6_000
        assertFalse(RootCorePackageResolver.isCore("com.example.a"))
        assertEquals(2, calls)
    }

    @Test fun `static core packages need no device and a bad name never reaches the shell`() {
        assertTrue(RootCorePackageResolver.isCore("com.android.systemui"))
        assertEquals(0, calls)
        assertFalse(RootCorePackageResolver.isCore("com.x; rm -rf /"))
        assertEquals(0, calls)
        try {
            RootCorePackageResolver.factsScript("a b")
            error("must reject")
        } catch (_: IllegalArgumentException) {
        }
    }
}
