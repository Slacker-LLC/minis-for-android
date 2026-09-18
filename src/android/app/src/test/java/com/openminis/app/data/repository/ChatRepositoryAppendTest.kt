package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryAppendTest {
    @Test
    fun `append uses one DAO transaction and returns its assigned row`() = runTest {
        val calls = mutableListOf<String>()
        var assigned: MessageEntity? = null
        val dao = Proxy.newProxyInstance(ChatDao::class.java.classLoader, arrayOf(ChatDao::class.java)) { _, method, args ->
            calls += method.name
            check(method.name == "appendMessageWithPreview") { "Non-atomic DAO call: ${method.name}" }
            val input = args[0] as MessageEntity
            assertEquals("hello", args[1])
            input.copy(sortOrder = 42).also { assigned = it }
        } as ChatDao

        val result = ChatRepository(dao).appendMessage("session", "user", """[{"type":"text","value":"hello"}]""")

        assertSame(assigned, result)
        assertEquals(42, result.sortOrder)
        assertEquals(listOf("appendMessageWithPreview"), calls)
    }
}
