package app.haltija.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 聊天文件读写的验收测试。
 *
 * 输入来自 `fixtures/chats/`，由 SillyTavern 自己的 `/api/chats/save`
 * 原样落盘，所以测的是「和 ST 写出来的一致」，不是「符合我的实现」。
 */
class ChatFileTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(path: String): String =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    private val chatDir = "chats/default_Seraphina"

    // ------------------------------------------------------------ 头部

    @Test
    fun `现代头部：unused 字面量且无 create_date`() {
        val result = ChatFile.parseDetailed(fixture("$chatDir/fixture-basic.jsonl"))

        assertTrue(result.hadHeader)
        val header = result.chat.header
        assertEquals("unused", header.userName)
        assertEquals("unused", header.characterName)
        assertTrue(header.isModernStyle)
        assertEquals(null, header.createDate)
        assertEquals(JsonObject(emptyMap()), header.metadata)
    }

    @Test
    fun `导入式头部：真实名字与 create_date 被保留`() {
        val result = ChatFile.parseDetailed(fixture("$chatDir/fixture-imported.jsonl"))

        assertTrue(result.hadHeader)
        val header = result.chat.header
        assertEquals("User", header.userName)
        assertEquals("Seraphina", header.characterName)
        assertEquals("2026-10-05T08:00:00.000Z", header.createDate)
        assertFalse(header.isModernStyle)
    }

    @Test
    fun `integrity 必须被读出并保留`() {
        val result = ChatFile.parseDetailed(fixture("$chatDir/fixture-integrity.jsonl"))

        assertEquals("9f1c2b7a-4d3e-4f5a-8b6c-1d2e3f4a5b6c", result.chat.header.integrity)
        // 自定义 metadata 字段不能丢
        assertNotNull(result.chat.metadata["custom_flag"])
        assertNotNull(result.chat.metadata["attachments"])
        assertNotNull(result.chat.metadata["note_prompt"])
    }

    // ------------------------------------------------------------ 消息

    @Test
    fun `消息数量与基本字段正确`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-basic.jsonl"))

        assertEquals(3, chat.size)
        assertEquals("Seraphina", chat.messages[0].name)
        assertFalse(chat.messages[0].isUser)
        assertEquals("User", chat.messages[1].name)
        assertTrue(chat.messages[1].isUser)
    }

    @Test
    fun `swipe 结构完整且满足 ST 的不变量`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-basic.jsonl"))
        val swiped = chat.messages.last()

        assertTrue(swiped.hasSwipes)
        assertEquals(3, swiped.swipes.size)
        assertEquals(1, swiped.swipeId)
        assertEquals(3, swiped.swipeInfo?.size)
        assertEquals("Of course I did.", swiped.mes)
        assertEquals(swiped.mes, swiped.activeText)
        assertTrue(swiped.isSwipeConsistent)
        assertTrue(chat.swipeInconsistencies().isEmpty(), "fixture 不应存在 swipe 不一致")
    }

    @Test
    fun `系统消息与 force_avatar 不丢失`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-imported.jsonl"))

        val system = chat.messages.first { it.isSystem }
        assertEquals("[A hidden system note]", system.mes)

        val withAvatar = chat.messages.first { it.forceAvatar != null }
        assertEquals("/img/user-default.png", withAvatar.forceAvatar)
    }

    // ------------------------------------------------------------ 往返

    @Test
    fun `读入再写出与原文件逐字节相同`() {
        for (name in listOf("fixture-basic", "fixture-imported", "fixture-integrity")) {
            val original = fixture("$chatDir/$name.jsonl")
            val rewritten = ChatFile.write(ChatFile.parse(original))
            if (original != rewritten) {
                val diff = original.indices.firstOrNull { it >= rewritten.length || original[it] != rewritten[it] } ?: -1
                fail("$name 重建后文本不同（原 ${original.length} 字符 / 新 ${rewritten.length}，首个差异 $diff）")
            }
        }
    }

    @Test
    fun `追加一条消息后能正确读回`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-basic.jsonl"))
        val appended = chat.append(
            ChatMessage.create(
                name = "User",
                mes = "新的一条",
                isUser = true,
                sendDate = "2026-10-05T10:00:00.000Z",
            ),
        )

        val reread = ChatFile.parse(ChatFile.write(appended))
        assertEquals(4, reread.size)
        assertEquals("新的一条", reread.messages.last().mes)
        assertEquals("2026-10-05T10:00:00.000Z", reread.messages.last().sendDate)
        // 头部必须原样保留
        assertEquals(chat.header.toJsonObject(), reread.header.toJsonObject())
    }

    // ------------------------------------------------------------ 边角

    @Test
    fun `无头部文件不会被误吃一行`() {
        val message = """{"name":"A","is_user":false,"is_system":false,"send_date":"x","mes":"hi","extra":{}}"""
        val result = ChatFile.parseDetailed(message)

        assertFalse(result.hadHeader, "第一行是消息时不应被当作头部")
        assertEquals(1, result.chat.size)
        assertEquals("hi", result.chat.messages[0].mes)
    }

    @Test
    fun `空文件与只有换行的文件解析为空聊天`() {
        assertEquals(0, ChatFile.parse("").size)
        assertEquals(0, ChatFile.parse("\n\n  \n").size)
    }

    @Test
    fun `BOM 被剥离`() {
        val text = "\uFEFF" + fixture("$chatDir/fixture-basic.jsonl")
        assertEquals(3, ChatFile.parse(text).size)
    }

    @Test
    fun `损坏的行被跳过并记录行号，不影响其余消息`() {
        val good = """{"name":"A","is_user":false,"is_system":false,"send_date":"x","mes":"hi","extra":{}}"""
        val text = listOf("""{"chat_metadata":{},"user_name":"unused","character_name":"unused"}""", good, "{坏掉的 json", good)
            .joinToString("\n")

        val result = ChatFile.parseDetailed(text)
        assertEquals(2, result.chat.size)
        assertEquals(listOf(3), result.droppedLineNumbers)
    }

    @Test
    fun `write 生成的文本结尾没有换行符`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-basic.jsonl"))
        assertFalse(ChatFile.write(chat).endsWith("\n"), "ST 的 join('\\n') 不产生结尾换行")
    }

    @Test
    fun `聊天文件写读往返（真实文件系统）`() {
        val chat = ChatFile.parse(fixture("$chatDir/fixture-integrity.jsonl"))
        val tmp = java.nio.file.Files.createTempDirectory("haltija-chat-test").resolve("c.jsonl")

        ChatFile.write(tmp, chat)
        val back = ChatFile.read(tmp)

        assertEquals(chat.header.toJsonObject(), back.header.toJsonObject())
        assertEquals(chat.messages.size, back.messages.size)
        java.nio.file.Files.deleteIfExists(tmp)
    }
}
