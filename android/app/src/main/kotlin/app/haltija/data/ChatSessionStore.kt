package app.haltija.data

import app.haltija.core.data.chat.Chat
import app.haltija.core.data.chat.ChatHeader
import app.haltija.core.data.chat.ChatMessage
import app.haltija.core.data.store.CharacterEntry
import app.haltija.core.data.store.ChatEntry
import app.haltija.core.data.store.ChatRepository
import app.haltija.core.data.store.StTimestamp
import app.haltija.core.prompt.macro.CoreMacros
import app.haltija.core.prompt.macro.MacroEngine
import app.haltija.core.prompt.macro.MacroEnv
import app.haltija.core.prompt.macro.MacroRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Clock
import java.util.UUID

/** 会话一直持有原始 Chat；界面消息只是它的投影，不参与写回。 */
data class ChatSession(val character: CharacterEntry, val fileName: String, val chat: Chat)

/** 不依赖 Android API，可用真实磁盘验证聊天的恢复与兼容性。调用方在 IO 线程执行。 */
class ChatSessionStore(
    private val repository: ChatRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun list(character: CharacterEntry): List<ChatEntry> = repository.list(character.fileName)

    fun load(character: CharacterEntry, fileName: String): ChatSession = ChatSession(
        character,
        fileName,
        ensureIntegrity(repository.load(character.fileName, fileName) ?: throw IOException("无法读取会话：$fileName")),
    )

    fun create(character: CharacterEntry, userName: String): ChatSession {
        var instant = clock.instant()
        var fileName = repository.newChatFileName(character.displayName, instant, clock.zone)
        // 一毫秒内连续新建也不能覆盖已有会话；保留 ST 的文件名格式。
        while (repository.exists(character.fileName, fileName)) {
            instant = instant.plusMillis(1)
            fileName = repository.newChatFileName(character.displayName, instant, clock.zone)
        }
        var chat = Chat.empty(buildJsonObject { put("integrity", UUID.randomUUID().toString()) })
        val registry = MacroRegistry().also(CoreMacros::install)
        val greeting = MacroEngine(
            registry,
            MacroEnv(names = mapOf("char" to character.displayName, "user" to userName)),
        ).evaluate(character.card.firstMes)
        if (greeting.isNotBlank()) {
            chat = chat.append(message(character.displayName, greeting, false))
        }
        return ChatSession(character, fileName, chat).also(::save)
    }

    /** 只追加新消息，保留既有头部、swipes、extra 和所有未知字段。 */
    fun append(session: ChatSession, text: String, isUser: Boolean, userName: String, reasoning: String = ""): ChatSession =
        session.copy(chat = session.chat.append(message(
            if (isUser) userName else session.character.displayName, text, isUser, reasoning,
        )))

    fun save(session: ChatSession) {
        repository.save(session.character.fileName, session.fileName, session.chat)
    }

    private fun ensureIntegrity(chat: Chat): Chat {
        // 老存档缺 integrity 时只补这一字段；已有值绝不重新生成。
        return if (chat.header.integrity == null) {
            Chat(ChatHeader(buildJsonObject {
                chat.header.raw.forEach { (key, value) -> put(key, value) }
                put("chat_metadata", buildJsonObject {
                    chat.metadata.forEach { (key, value) -> put(key, value) }
                    put("integrity", UUID.randomUUID().toString())
                })
            }), chat.messages)
        } else chat
    }

    private fun message(name: String, text: String, isUser: Boolean, reasoning: String = ""): ChatMessage {
        val base = ChatMessage.create(
            name, text, isUser, StTimestamp.messageTimestamp(clock.instant()),
            extra = buildJsonObject { if (reasoning.isNotEmpty()) put("reasoning", reasoning) },
        )
        return if (isUser) base else ChatMessage(buildJsonObject {
            base.raw.forEach { (key, value) -> put(key, value) }
            put("swipes", JsonArray(listOf(JsonPrimitive(text))))
            put("swipe_id", 0)
        })
    }
}
