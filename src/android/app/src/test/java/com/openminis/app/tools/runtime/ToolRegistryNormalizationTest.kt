package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying that ~25 MCP and Android agent tools (Checklist B & C)
 * correctly resolve via exact, wire, alias, and normalized (stripped) names.
 */
class ToolRegistryNormalizationTest {

    private class DummyHandler(override val definition: AgentToolDefinition) : ToolHandler {
        override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult =
            ToolExecutionResult("ok", true)
    }

    @Before
    fun setUp() {
        val tools = listOf(
            "android.time" to listOf("get_current_time"),
            "android.weather" to listOf("get_weather"),
            "android.location.get" to listOf("get_location"),
            "android.wifi.info" to listOf("wifi_info"),
            "android.wifi.scan" to listOf("list_wifi_networks"),
            "android.bluetooth.status" to listOf("bluetooth_status"),
            "android.contacts.search" to listOf("search_contacts"),
            "android.calendar.read" to listOf("read_calendar"),
            "android.sms.read" to listOf("read_sms"),
            "android.calllog.read" to listOf("read_call_log"),
            "android.app.usage" to listOf("app_usage"),
            "android.media.info" to listOf("get_media_info"),
            "android.media.images" to listOf("list_media_images"),
            "android.tts.voices" to listOf("list_tts_voices"),
            "android.clipboard" to listOf("clipboard"),
            "android.phone.dial" to listOf("dial_phone"),
            "android.intent.send" to listOf("send_intent"),
            "android.settings.get" to listOf("system.get_setting"),
            "android.web.search" to listOf("web_search"),
            "android.web.fetch" to listOf("url_fetch"),
            "root.shell" to listOf("shell_root"),
            "minis.config" to listOf("minis-config"),
        )

        for ((name, aliases) in tools) {
            val def = AgentToolDefinition(
                name = name,
                description = "Test $name",
                parameters = emptyMap(),
            )
            ToolRegistry.register(DummyHandler(def), aliases)
        }
    }

    @Test
    fun `canonicalName resolves exact names`() {
        assertEquals("android.time", ToolRegistry.canonicalName("android.time"))
        assertEquals("root.shell", ToolRegistry.canonicalName("root.shell"))
    }

    @Test
    fun `canonicalName resolves api wire names`() {
        assertEquals("android.time", ToolRegistry.canonicalName("android_time"))
        assertEquals("android.location.get", ToolRegistry.canonicalName("android_location_get"))
        assertEquals("root.shell", ToolRegistry.canonicalName("root_shell"))
    }

    @Test
    fun `canonicalName resolves registered aliases`() {
        assertEquals("android.time", ToolRegistry.canonicalName("get_current_time"))
        assertEquals("root.shell", ToolRegistry.canonicalName("shell_root"))
        assertEquals("android.web.search", ToolRegistry.canonicalName("web_search"))
    }

    @Test
    fun `canonicalName resolves stripped normalized names`() {
        // Checklist B & C test cases
        assertEquals("android.time", ToolRegistry.canonicalName("androidtime"))
        assertEquals("android.weather", ToolRegistry.canonicalName("androidweather"))
        assertEquals("android.location.get", ToolRegistry.canonicalName("androidlocationget"))
        assertEquals("android.wifi.info", ToolRegistry.canonicalName("androidwifiinfo"))
        assertEquals("android.wifi.scan", ToolRegistry.canonicalName("androidwifiscan"))
        assertEquals("android.bluetooth.status", ToolRegistry.canonicalName("androidbluetoothstatus"))
        assertEquals("android.contacts.search", ToolRegistry.canonicalName("androidcontactssearch"))
        assertEquals("android.calendar.read", ToolRegistry.canonicalName("androidcalendarread"))
        assertEquals("android.sms.read", ToolRegistry.canonicalName("androidsmsread"))
        assertEquals("android.calllog.read", ToolRegistry.canonicalName("androidcalllogread"))
        assertEquals("android.app.usage", ToolRegistry.canonicalName("androidapp_usage"))
        assertEquals("android.app.usage", ToolRegistry.canonicalName("androidappusage"))
        assertEquals("android.media.info", ToolRegistry.canonicalName("androidmediainfo"))
        assertEquals("android.media.images", ToolRegistry.canonicalName("androidmediaimages"))
        assertEquals("android.tts.voices", ToolRegistry.canonicalName("androidttsvoices"))
        assertEquals("android.clipboard", ToolRegistry.canonicalName("androidclipboard"))
        assertEquals("android.phone.dial", ToolRegistry.canonicalName("androidphonedial"))
        assertEquals("android.intent.send", ToolRegistry.canonicalName("androidintentsend"))
        assertEquals("android.settings.get", ToolRegistry.canonicalName("androidsettingsget"))
        assertEquals("android.web.search", ToolRegistry.canonicalName("androidwebsearch"))
        assertEquals("android.web.fetch", ToolRegistry.canonicalName("androidwebfetch"))
        assertEquals("root.shell", ToolRegistry.canonicalName("root_shell"))
        assertEquals("root.shell", ToolRegistry.canonicalName("rootshell"))
        assertEquals("minis.config", ToolRegistry.canonicalName("minis-config"))
        assertEquals("minis.config", ToolRegistry.canonicalName("minisconfig"))
    }

    @Test
    fun `AgentToolDefinition matchesName supports stripped names`() {
        val def = AgentToolDefinition(
            name = "android.location.get",
            description = "Get location",
            parameters = emptyMap(),
        )
        assertTrue(def.matchesName("android.location.get"))
        assertTrue(def.matchesName("android_location_get"))
        assertTrue(def.matchesName("androidlocationget"))
        assertTrue(def.matchesName("ANDROIDLOCATIONGET"))
    }
}
