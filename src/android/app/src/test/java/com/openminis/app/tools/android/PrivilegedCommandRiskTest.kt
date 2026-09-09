package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedCommandRiskTest {

    @Test
    fun `generic root commands are classified from tool and args`() {
        assertEquals(
            CommandRisk.READ_ONLY,
            PrivilegedCommandRisk.classify("getprop", listOf("ro.product.model")),
        )
        assertEquals(
            CommandRisk.READ_ONLY,
            PrivilegedCommandRisk.classify("pm", listOf("--user", "0", "list", "packages")),
        )
        assertEquals(
            CommandRisk.MUTATING,
            PrivilegedCommandRisk.classify("pm", listOf("install", "/sdcard/app.apk")),
        )
        assertEquals(
            CommandRisk.MUTATING,
            PrivilegedCommandRisk.classify("settings", listOf("--user", "0", "put", "system", "foo", "bar")),
        )
        assertEquals(
            CommandRisk.READ_ONLY,
            PrivilegedCommandRisk.classify("settings", listOf("--user", "0", "get", "system", "foo")),
        )
        assertEquals(
            CommandRisk.USER_VISIBLE,
            PrivilegedCommandRisk.classify("am", listOf("force-stop", "com.example.app")),
        )
        assertEquals(
            CommandRisk.ROOT_SETUP,
            PrivilegedCommandRisk.classify("mount", listOf("--bind", "/a", "/b")),
        )
    }

    @Test
    fun `unknown or arbitrary shell commands fail closed to highest risk`() {
        assertEquals(
            CommandRisk.ROOT_SETUP,
            PrivilegedCommandRisk.classify("sh", listOf("-c", "id")),
        )
        assertEquals(
            CommandRisk.MUTATING,
            PrivilegedCommandRisk.classify("unknown-tool", emptyList()),
        )
    }

    @Test
    fun `declared risk cannot be lowered by command classification`() {
        assertEquals(
            CommandRisk.ROOT_SETUP,
            CommandRisk.max(
                CommandRisk.READ_ONLY,
                PrivilegedCommandRisk.classify("sh", listOf("-c", "id")),
            ),
        )
        assertEquals(
            CommandRisk.ROOT_SETUP,
            CommandRisk.max(CommandRisk.ROOT_SETUP, CommandRisk.READ_ONLY),
        )
    }
}
