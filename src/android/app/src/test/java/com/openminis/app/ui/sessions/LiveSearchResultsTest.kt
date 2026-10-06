package com.openminis.app.ui.sessions

import com.openminis.app.data.db.ChatSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSearchResultsTest {
    private fun session(id: String, title: String) =
        ChatSessionEntity(id = id, title = title, modelId = "m", createdAt = 1, updatedAt = 1)

    @Test
    fun deletedSessionsLeaveTheResults() {
        val results = listOf(session("a", "alpha"), session("b", "beta"))
        val all = listOf(session("b", "beta"))
        assertEquals(listOf("b"), SessionListViewModel.liveSearchResults(all, results).map { it.id })
    }

    @Test
    fun survivorsShowTheirCurrentTitleAndKeepTheirOrder() {
        val results = listOf(session("b", "old b"), session("a", "old a"))
        val all = listOf(session("a", "new a"), session("b", "new b"), session("c", "other"))
        val live = SessionListViewModel.liveSearchResults(all, results)
        assertEquals(listOf("b", "a"), live.map { it.id })
        assertEquals(listOf("new b", "new a"), live.map { it.title })
    }

    @Test
    fun aSearchResultIsNeverInventedFromTheFullList() {
        assertTrue(SessionListViewModel.liveSearchResults(listOf(session("a", "x")), emptyList()).isEmpty())
        assertTrue(SessionListViewModel.liveSearchResults(emptyList(), listOf(session("a", "x"))).isEmpty())
    }
}
