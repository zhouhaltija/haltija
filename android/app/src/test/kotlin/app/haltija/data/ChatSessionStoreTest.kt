package app.haltija.data

import app.haltija.core.data.card.CharacterCard
import app.haltija.core.data.chat.ChatFile
import app.haltija.core.data.store.CharacterEntry
import app.haltija.core.data.store.ChatRepository
import app.haltija.core.data.store.MemoryStDataRoot
import app.haltija.core.data.store.StDataRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChatSessionStoreTest {
    private val directory = Files.createTempDirectory("haltija-sessions").toFile()
    private val clock = Clock.fixed(Instant.parse("2026-10-05T08:30:45Z"), ZoneId.of("Asia/Shanghai"))
    private fun character(file: String = "avatar-id.png", name: String = "Alice", greeting: String = "Hello {{user}}, I'm {{char}}") =
        CharacterEntry(file, CharacterCard(Json.parseToJsonElement(
            """{"spec":"chara_card_v2","spec_version":"2.0","data":{"name":"$name","first_mes":"$greeting"}}""",
        ) as JsonObject), null)
    private fun store(root: StDataRoot) = ChatSessionStore(ChatRepository(root), clock)

    @AfterTest fun cleanup() { directory.deleteRecursively() }

    @Test fun `新会话落盘并用头像文件名而不是角色显示名作目录`() {
        val root = JavaStDataRoot(directory)
        val session = store(root).create(character(), "Traveler")
        assertEquals("Alice - 2026-10-05@16h30m45s000ms.jsonl", session.fileName)
        assertTrue(root.exists("chats/avatar-id/${session.fileName}"))
        assertFalse(root.exists("chats/Alice/${session.fileName}"))
        assertEquals(4, UUID.fromString(session.chat.header.integrity).version())
        assertEquals("Hello Traveler, I'm Alice", session.chat.lastMessage!!.mes)
        assertEquals("2026-10-05T08:30:45.000Z", session.chat.lastMessage!!.sendDate)
        assertTrue(session.chat.lastMessage!!.isSwipeConsistent)
    }

    @Test fun `重建仓库能恢复消息正文与思考`() {
        val root = JavaStDataRoot(directory)
        val store = store(root)
        var session = store.create(character(), "Traveler")
        session = store.append(session, "中文提问", true, "Traveler")
        store.save(session)
        session = store.append(session, "中文回复🌙", false, "Traveler", "逐步思考")
        store.save(session)
        val restored = store(JavaStDataRoot(directory)).load(character(), session.fileName)
        assertEquals(ChatFile.write(session.chat), ChatFile.write(restored.chat))
        assertEquals(session.chat.header.integrity, restored.chat.header.integrity)
        assertEquals(3, restored.chat.size)
        assertEquals("中文回复🌙", restored.chat.lastMessage!!.activeText)
        assertEquals("逐步思考", restored.chat.lastMessage!!.extra["reasoning"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(restored.chat.lastMessage!!.isSwipeConsistent)
    }

    @Test fun `追加消息不改已有头部元数据swipes或第三方字段`() {
        val root = JavaStDataRoot(directory)
        val original = """
            {"user_name":"unused","character_name":"unused","plugin_header":{"x":1},"chat_metadata":{"integrity":"keep-me","variables":{"score":12},"timedWorldInfo":{"sticky":{"book.1":{"end":4}}},"plugin_data":[1,2]}}
            {"name":"Alice","is_user":false,"is_system":false,"mes":"second","swipes":["first","second"],"swipe_id":1,"swipe_info":[{"extra":{"foo":1}},{"extra":{"bar":2}}],"extra":{"reasoning":"old thought","attachment":{"url":"x"}},"unknown":true}
            {"name":"System","is_system":true,"mes":"hidden","extra":{"plugin":"data"}}
        """.trimIndent()
        root.write("chats/avatar-id/imported.jsonl", original.toByteArray())
        val store = store(root)
        val before = store.load(character(), "imported.jsonl")
        val after = store.append(before, "new", true, "Traveler")
        store.save(after)
        val restored = store.load(character(), "imported.jsonl")
        assertEquals(before.chat.header.raw, restored.chat.header.raw)
        before.chat.messages.forEachIndexed { index, message -> assertEquals(message.raw, restored.chat.messages[index].raw) }
        assertEquals(3, restored.chat.size)
        assertEquals("keep-me", restored.chat.header.integrity)
        assertTrue(restored.chat.swipeInconsistencies().isEmpty())
    }

    @Test fun `老存档缺完整性字段时只补该字段且多次保存不重建`() {
        val root = MemoryStDataRoot(initial = mapOf("chats/avatar-id/old.jsonl" to
            """{"user_name":"Old","custom":true,"chat_metadata":{"foo":1}}""".toByteArray()))
        val store = store(root)
        val session = store.load(character(), "old.jsonl")
        store.save(session)
        store.save(session)
        val loaded = store.load(character(), "old.jsonl")
        assertEquals(session.chat.header.integrity, loaded.chat.header.integrity)
        assertEquals("true", loaded.chat.header.raw["custom"].toString())
        assertEquals("1", loaded.chat.metadata["foo"].toString())
        assertEquals("Old", loaded.chat.header.userName)
    }

    @Test fun `固定时钟连续新建会话不会覆盖原文件`() {
        val root = MemoryStDataRoot()
        val store = store(root)
        val first = store.create(character(), "Traveler")
        val second = store.create(character(), "Traveler")
        assertNotEquals(first.fileName, second.fileName)
        assertNotEquals(first.chat.header.integrity, second.chat.header.integrity)
        assertEquals("Alice - 2026-10-05@16h30m45s001ms.jsonl", second.fileName)
        assertEquals(listOf(second.fileName, first.fileName), store.list(character()).map { it.fileName })
        assertEquals(first.chat.header.integrity, store.load(character(), first.fileName).chat.header.integrity)
    }

    @Test fun `同显示名的不同角色和多个会话彼此隔离`() {
        val root = JavaStDataRoot(directory)
        val store = store(root)
        val alice = character("one.png")
        val another = character("two.png")
        val first = store.create(alice, "Traveler")
        val second = store.create(alice, "Traveler")
        val other = store.create(another, "Traveler")
        store.save(store.append(first, "only first", true, "Traveler"))
        assertEquals(2, store.load(alice, first.fileName).chat.size)
        assertEquals(1, store.load(alice, second.fileName).chat.size)
        assertEquals(1, store.load(another, other.fileName).chat.size)
        assertEquals(2, store.list(alice).size)
        assertEquals(1, store.list(another).size)
    }

    @Test fun `空开场白可创建空会话`() {
        val root = MemoryStDataRoot()
        val session = store(root).create(character(greeting = ""), "Traveler")
        assertEquals(0, session.chat.size)
        assertTrue(session.chat.header.integrity != null)
    }

    @Test fun `损坏聊天拒绝加载原文件不被宽容解析后覆盖`() {
        val bytes = """{"chat_metadata":{"integrity":"keep"}}
BAD LINE
{"mes":"hello","is_user":true}""".toByteArray()
        val root = MemoryStDataRoot(initial = mapOf("chats/avatar-id/broken.jsonl" to bytes))
        assertFailsWith<IllegalArgumentException> { store(root).load(character(), "broken.jsonl") }
        assertTrue(bytes.contentEquals(root.read("chats/avatar-id/broken.jsonl")!!))
    }

    @Test fun `保存失败抛出错误并可用同一快照重试`() {
        val memory = MemoryStDataRoot()
        var fail = false
        val root = object : StDataRoot by memory {
            override fun write(path: String, bytes: ByteArray) {
                if (fail) throw IOException("disk full")
                memory.write(path, bytes)
            }
        }
        val store = store(root)
        val original = store.create(character(), "Traveler")
        val pending = store.append(original, "partial reply", false, "Traveler", "partial thought")
        fail = true
        assertFailsWith<IOException> { store.save(pending) }
        assertEquals(1, store.load(character(), original.fileName).chat.size)
        fail = false
        store.save(pending)
        assertEquals(ChatFile.write(pending.chat), ChatFile.write(store.load(character(), pending.fileName).chat))
    }
}
