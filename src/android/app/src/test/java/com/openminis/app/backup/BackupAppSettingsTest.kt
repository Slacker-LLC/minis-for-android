package com.openminis.app.backup

import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a backup may carry back into the app's settings. A package is untrusted input, so most of these are
 * refusals: permission grants and device state never travel, a task with full access comes back switched
 * off, and anything the format does not know is dropped rather than guessed.
 */
class BackupAppSettingsTest {

    // -- Preferences ---------------------------------------------------------------------------

    @Test
    fun `interface and behaviour preferences are allowed`() {
        assertTrue(BackupAppSettings.isAllowed("appearance_prefs", "theme_mode"))
        assertTrue(BackupAppSettings.isAllowed("appearance_prefs", "app_language"))
        assertTrue(BackupAppSettings.isAllowed("minis_auto_compact_prefs", "autoCompactOnThreshold"))
        assertTrue(BackupAppSettings.isAllowed("mirror_settings", "mirror.selected.apt"))
    }

    @Test
    fun `permission grants, device state and secrets are never allowed`() {
        for (file in listOf(
            "offload_permissions", "offload_permissions_asked", "unattended_access", "minis_session_permissions",
            "minis_config_permission", "approval_seam", "a11y_recovery", "minis_device_identity",
            "provider_config", "env_var_values", "backup_rclone_secrets", "pending_update", "app_icon_prefs",
            "ubuntu_provision_state", "vscreen_capability", "crash_freq_prefs",
        )) {
            assertFalse(file, BackupAppSettings.isAllowed(file, "anything"))
            assertFalse(file, BackupAppSettings.isAllowed(file, "enabled"))
        }
    }

    @Test
    fun `a key outside the list is refused even in an allowed file`() {
        assertFalse(BackupAppSettings.isAllowed("appearance_prefs", "some_future_key"))
        assertFalse("install state in the mirror file", BackupAppSettings.isAllowed("mirror_settings", "rootfs.freshInstall"))
        assertFalse("speech engine id is device specific", BackupAppSettings.isAllowed("speech_recognition", "engineId"))
        assertFalse(BackupAppSettings.isAllowed("appearance_prefs", ""))
        assertFalse(BackupAppSettings.isAllowed("appearance_prefs", "x".repeat(200)))
    }

    @Test
    fun `a prompt module flag needs a module that exists`() {
        val someModule = com.openminis.app.prompt.PromptModuleRegistry.modules.first().id
        assertTrue(BackupAppSettings.isAllowed("minis_system_prompt", "enabled.$someModule"))
        assertFalse(BackupAppSettings.isAllowed("minis_system_prompt", "enabled.no-such-module"))
        assertFalse(BackupAppSettings.isAllowed("minis_system_prompt", "enabled."))
    }

    @Test
    fun `the snapshot keeps allowed values only and round trips through the plan`() {
        val live = mapOf(
            "appearance_prefs" to mapOf<String, Any?>(
                "theme_mode" to 2,
                "tool_preview" to false,
                "font_message" to 1,
                "not_allowed" to "x",
                "set" to setOf("a"),
                "big" to "y".repeat(40_000),
            ),
            "offload_permissions" to mapOf<String, Any?>("camera" to true),
            "voice_prefs" to mapOf<String, Any?>("readReplies.speed" to 1.25f, "readReplies" to true),
        )
        val snapshot = BackupAppSettings.snapshotPrefs { name -> live[name] ?: emptyMap<String, Any?>() }
        assertFalse(snapshot.containsKey("offload_permissions"))
        val appearance = snapshot["appearance_prefs"] as JsonObject
        assertEquals(setOf("theme_mode", "tool_preview", "font_message"), appearance.keys)

        val plan = BackupAppSettings.planPrefs(snapshot)
        assertEquals(0, plan.rejected)
        assertEquals(2, plan.writes["appearance_prefs"]!!["theme_mode"])
        assertEquals(false, plan.writes["appearance_prefs"]!!["tool_preview"])
        assertEquals(1.25f, plan.writes["voice_prefs"]!!["readReplies.speed"])
        assertEquals(5, plan.count)
    }

    @Test
    fun `a crafted package cannot write outside the allowlist or with the wrong type`() {
        val crafted = buildJsonObject {
            put("unattended_access", buildJsonObject { put("enabled", buildJsonObject { put("t", "b"); put("v", true) }) })
            put("minis_session_permissions", buildJsonObject { put("x", buildJsonObject { put("t", "b"); put("v", true) }) })
            put(
                "appearance_prefs",
                buildJsonObject {
                    put("theme_mode", buildJsonObject { put("t", "i"); put("v", 1) })
                    put("tool_preview", buildJsonObject { put("t", "b"); put("v", "yes") }) // wrong type
                    put("font_message", buildJsonObject { put("t", "i"); put("v", 99_999_999_999L) }) // does not fit an Int
                    put("font_app_base", buildJsonObject { put("t", "z"); put("v", 1) }) // unknown type tag
                    put("accent_color", "not an object")
                    put("surprise", buildJsonObject { put("t", "i"); put("v", 1) })
                },
            )
            put("ui_prefs", "not an object")
        }
        val plan = BackupAppSettings.planPrefs(crafted)
        assertEquals(mapOf("appearance_prefs" to mapOf<String, Any>("theme_mode" to 1)), plan.writes)
        assertEquals("2 forbidden entries + 5 bad ones + 1 malformed file", 8, plan.rejected)
    }

    @Test
    fun `no preferences in the package is not an error`() {
        val plan = BackupAppSettings.planPrefs(null)
        assertTrue(plan.writes.isEmpty())
        assertEquals(0, plan.rejected)
    }

    // -- System prompt -------------------------------------------------------------------------

    @Test
    fun `the custom prompt and known module overrides are accepted, unknown ones are not`() {
        val someModule = com.openminis.app.prompt.PromptModuleRegistry.modules.first().id
        val plan = BackupAppSettings.planPrompt(
            buildJsonObject {
                put("custom", "Always answer in French.")
                put(
                    "modules",
                    buildJsonObject {
                        put(someModule, "my wording")
                        put("no-such-module", "x")
                        put("../escape", "x")
                    },
                )
            },
        )
        assertEquals("Always answer in French.", plan.custom)
        assertEquals(mapOf(someModule to "my wording"), plan.modules)
        assertEquals(2, plan.rejected)
    }

    @Test
    fun `an oversized prompt is refused`() {
        val plan = BackupAppSettings.planPrompt(
            buildJsonObject { put("custom", "x".repeat(BackupAppSettings.MAX_PROMPT_CHARS + 1)) },
        )
        assertEquals(null, plan.custom)
        assertEquals(1, plan.rejected)
    }

    // -- Scheduled tasks -----------------------------------------------------------------------

    private fun task(id: String, tier: ScheduledTaskPermissionTier, enabled: Boolean = true) = ScheduledTask(
        id = id, label = "t-$id", timeOfDayHour = 8, timeOfDayMinute = 30, repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "do it", enabled = enabled, permissionTier = tier,
    )

    private fun rows(vararg tasks: ScheduledTask): JsonArray = BackupAppSettings.tasksToJson(tasks.toList())

    @Test
    fun `a read-only task is restored as it was`() {
        val plan = BackupAppSettings.planTasks(rows(task("a", ScheduledTaskPermissionTier.READ_ONLY)), emptySet())
        assertEquals(1, plan.toWrite.size)
        assertTrue(plan.toWrite.single().enabled)
        assertEquals(0, plan.disabledForFullAccess)
    }

    @Test
    fun `a task with full access comes back switched off`() {
        val plan = BackupAppSettings.planTasks(rows(task("a", ScheduledTaskPermissionTier.FULL)), emptySet())
        val restored = plan.toWrite.single()
        assertFalse("full access must be re-confirmed by the user", restored.enabled)
        assertEquals(ScheduledTaskPermissionTier.FULL, restored.permissionTier)
        assertEquals(1, plan.disabledForFullAccess)
    }

    @Test
    fun `an old row with no tier counts as full access and is switched off`() {
        val legacy = JSONObject(task("old", ScheduledTaskPermissionTier.READ_ONLY).toJson().toString()).apply { remove("permissionTier") }
        val array = JsonArray(listOf(kotlinx.serialization.json.Json.parseToJsonElement(legacy.toString())))
        val plan = BackupAppSettings.planTasks(array, emptySet())
        assertFalse(plan.toWrite.single().enabled)
        assertEquals(1, plan.disabledForFullAccess)
    }

    @Test
    fun `a task already on the device is kept, duplicates and garbage are rejected`() {
        val array = JsonArray(
            rows(task("keep", ScheduledTaskPermissionTier.READ_ONLY), task("new", ScheduledTaskPermissionTier.READ_ONLY)) +
                rows(task("new", ScheduledTaskPermissionTier.READ_ONLY)) + // duplicate id
                listOf(kotlinx.serialization.json.JsonPrimitive("garbage")),
        )
        val plan = BackupAppSettings.planTasks(array, setOf("keep"))
        assertEquals(listOf("new"), plan.toWrite.map { it.id })
        assertEquals(1, plan.skipped)
        assertEquals(2, plan.rejected)
    }

    @Test
    fun `a flood of tasks is capped`() {
        val many = (0 until BackupAppSettings.MAX_TASKS + 50).map { task("t$it", ScheduledTaskPermissionTier.READ_ONLY) }
        val plan = BackupAppSettings.planTasks(BackupAppSettings.tasksToJson(many).let { JsonArray(it + it.take(50)) }, emptySet())
        assertTrue(plan.toWrite.size <= BackupAppSettings.MAX_TASKS)
        assertTrue(plan.rejected > 0)
    }

    // -- Document ------------------------------------------------------------------------------

    @Test
    fun `an empty document is recognised so nothing is written for it`() {
        assertTrue(BackupAppSettings.isEmpty(BackupAppSettings.document(buildJsonObject {}, null, emptyMap(), JsonArray(emptyList()))))
        assertFalse(BackupAppSettings.isEmpty(BackupAppSettings.document(buildJsonObject {}, "p", emptyMap(), JsonArray(emptyList()))))
    }
}
