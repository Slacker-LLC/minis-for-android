package com.openminis.app.scheduled

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskBotIdJsonTest {
    private fun task(botId: String? = null) = ScheduledTask(
        id = "routine-1",
        label = "Daily review",
        timeOfDayHour = 9,
        timeOfDayMinute = 15,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "Review the day",
        botId = botId,
    )

    @Test
    fun `bot id round trips and is written only when present`() {
        val encoded = task("bot-123").toJson()
        assertEquals("bot-123", encoded.getString("botId"))
        assertEquals("bot-123", ScheduledTask.fromJson(encoded).botId)

        val ordinary = task().toJson()
        assertFalse(ordinary.has("botId"))
        assertEquals(null, ScheduledTask.fromJson(ordinary).botId)
    }

    @Test
    fun `legacy missing bot id and malformed values default to null`() {
        val legacy = task().toJson()
        assertEquals(null, ScheduledTask.fromJson(legacy).botId)
        assertEquals(null, ScheduledTask.fromJson(JSONObject(legacy.toString()).put("botId", 7)).botId)
        assertEquals(null, ScheduledTask.fromJson(JSONObject(legacy.toString()).put("botId", "  ")).botId)
        assertTrue(ScheduledTask.fromJson(JSONObject(legacy.toString()).put("botId", JSONObject.NULL)).botId == null)
    }
}
