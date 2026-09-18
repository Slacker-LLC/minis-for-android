package com.openminis.app.data.repository

import com.openminis.app.data.db.BotDao
import com.openminis.app.data.db.BotEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.agent.BotModelResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class BotRepository(private val dao: BotDao) {
    private val mutationMutex = Mutex()
    private val conversationMutex = Mutex()

    fun observeBots(): Flow<List<BotEntity>> = dao.observeBots()

    suspend fun listBots(): List<BotEntity> = dao.listBots()

    suspend fun getBot(id: String): BotEntity? = dao.getBot(id)

    suspend fun openConversation(
        id: String,
        chatRepository: ChatRepository,
        providerRepository: ProviderRepository,
        newTopic: Boolean = false,
    ): ChatSessionEntity = conversationMutex.withLock {
        val bot = checkNotNull(dao.getBot(id)) { "Bot 已不存在" }
        if (!newTopic) {
            chatRepository.latestBotConversation(id)?.let { return@withLock it }
        }
        check(bot.enabled) { "请先启用这位成员，再开始新话题。" }
        providerRepository.awaitConfigLoaded()
        val entry = checkNotNull(BotModelResolver.resolve(providerRepository, bot.modelBinding)) {
            if (bot.modelBinding.isNullOrBlank()) "请先配置一个可用的对话模型。"
            else "这位成员的默认模型不可用，请在成员资料中重新选择。"
        }
        val binding = org.json.JSONObject().put("type", "entry").put("entryId", entry.id).toString()
        chatRepository.createSession(
            modelId = entry.baseModel.id,
            title = bot.name,
            botId = bot.id,
            modelBinding = binding,
        )
    }

    suspend fun createBot(
        name: String,
        systemPrompt: String? = null,
        modelBinding: String? = null,
    ): BotEntity = mutationMutex.withLock {
        check(dao.countBots() < MAX_BOTS) { "Bot 数量已达到上限（$MAX_BOTS）" }
        val normalizedName = normalizeText(name, NAME_MAX_CHARS)
        require(normalizedName.isNotEmpty()) { "Bot name must not be blank" }
        val now = System.currentTimeMillis()
        val bot = BotEntity(
            id = UUID.randomUUID().toString(),
            name = normalizedName,
            systemPrompt = normalizePrompt(systemPrompt),
            modelBinding = modelBinding?.trim()?.ifBlank { null },
            createdAt = now,
            updatedAt = now,
        )
        dao.insertBot(bot)
        bot
    }

    suspend fun updateBot(
        id: String,
        name: String,
        systemPrompt: String? = null,
        modelBinding: String? = null,
    ): Boolean {
        val normalizedName = normalizeText(name, NAME_MAX_CHARS)
        if (normalizedName.isEmpty() || dao.getBot(id) == null) return false
        dao.updateBotProfile(
            id = id,
            name = normalizedName,
            systemPrompt = normalizePrompt(systemPrompt),
            modelBinding = modelBinding?.trim()?.ifBlank { null },
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    suspend fun setBotEnabled(id: String, enabled: Boolean): Boolean {
        if (dao.getBot(id) == null) return false
        dao.setBotEnabled(id, enabled, System.currentTimeMillis())
        return true
    }

    suspend fun deleteBot(id: String): Boolean {
        val bot = dao.getBot(id) ?: return false
        dao.clearBotFromSessions(id)
        dao.deleteBot(bot)
        return true
    }

    private fun normalizePrompt(value: String?): String? =
        value?.replace("\r\n", "\n")?.replace('\r', '\n')
            ?.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
            ?.trim()?.take(SYSTEM_PROMPT_MAX_CHARS)?.ifBlank { null }

    private fun normalizeText(value: String, maxChars: Int): String =
        value.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(maxChars)

    companion object {
        const val NAME_MAX_CHARS = 80
        const val SYSTEM_PROMPT_MAX_CHARS = 12_000
        const val MAX_BOTS = 12
    }
}
