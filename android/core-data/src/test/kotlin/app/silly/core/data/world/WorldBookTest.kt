package app.silly.core.data.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 世界书读写的验收测试。
 *
 * `fixtures/worlds/fixture-book.json` 覆盖了 constant / selective+次级键 /
 * 正则键+atDepth / 停用条目四类，其中第 4 条是**负向用例**（永远不应激活）。
 */
class WorldBookTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixtureText(path: String): String =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    private fun book() = WorldBook.parse(fixtureText("worlds/fixture-book.json"), "fixture-book")

    // ------------------------------------------------------------ 结构

    @Test
    fun `条目数量与 uid 正确`() {
        val book = book()

        assertEquals(4, book.size)
        assertEquals(listOf(0, 1, 2, 3), book.entries.map { it.uid })
        assertEquals("fixture-book", book.name)
    }

    @Test
    fun `解析结果与原始 JSON 逐字段一致`() {
        val book = book()
        val expected = json.parseToJsonElement(fixtureText("worlds/fixture-book.json")) as JsonObject

        assertEquals(expected, book.toJsonObject())
    }

    @Test
    fun `下一个可用 uid 是最大值加一`() {
        assertEquals(4, book().nextUid())
        assertEquals(0, WorldBook.empty().nextUid())
    }

    // ------------------------------------------------------------ 字段语义

    @Test
    fun `uid 0 是 constant 条目：无需键命中`() {
        val entry = assertNotNull(book().entry(0))

        assertTrue(entry.constant)
        assertEquals(listOf("dragon", "wyrm"), entry.keys)
        assertFalse(entry.disable)
        assertEquals(WorldInfoPosition.BEFORE, entry.position)
    }

    @Test
    fun `uid 1 是选择性条目：次级键与 AND_ANY 逻辑`() {
        val entry = assertNotNull(book().entry(1))

        assertFalse(entry.constant)
        assertTrue(entry.selective)
        assertEquals(SelectiveLogic.AND_ANY, entry.selectiveLogic)
        assertEquals(listOf("silver", "enchanted"), entry.secondaryKeys)
        assertEquals(2, entry.scanDepth)
        assertEquals(false, entry.caseSensitive)
        assertEquals(true, entry.matchWholeWords)
        assertEquals(WorldInfoPosition.AFTER, entry.position)
    }

    @Test
    fun `uid 2 是正则键 + atDepth + sticky_cooldown`() {
        val entry = assertNotNull(book().entry(2))

        assertTrue(entry.hasRegexKey, "键 '/whisper(s)?/i' 应被识别为正则")
        assertEquals(WorldInfoPosition.AT_DEPTH, entry.position)
        assertEquals(2, entry.depth)
        assertEquals(3, entry.sticky)
        assertEquals(2, entry.cooldown)
    }

    @Test
    fun `uid 3 是停用条目：负向用例`() {
        val entry = assertNotNull(book().entry(3))

        assertTrue(entry.disable, "第 4 条必须是停用的，用于验证停用条目不会激活")
        assertTrue(entry.constant, "它同时是 constant，所以只要引擎错误地忽略 disable 就会立刻暴露")
    }

    @Test
    fun `正则键判定`() {
        assertTrue(WorldInfoEntry.looksLikeRegex("/whisper(s)?/i"))
        assertTrue(WorldInfoEntry.looksLikeRegex("/abc/"))
        assertFalse(WorldInfoEntry.looksLikeRegex("abc"))
        assertFalse(WorldInfoEntry.looksLikeRegex("a/b"))
        assertFalse(WorldInfoEntry.looksLikeRegex("/abc"))
    }

    @Test
    fun `枚举取值与 ST 的数值一一对应`() {
        assertEquals(0, SelectiveLogic.AND_ANY.value)
        assertEquals(1, SelectiveLogic.NOT_ALL.value)
        assertEquals(2, SelectiveLogic.NOT_ANY.value)
        assertEquals(3, SelectiveLogic.AND_ALL.value)

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), WorldInfoPosition.entries.map { it.value })
        assertEquals(WorldInfoPosition.BEFORE, WorldInfoPosition.from(0))
        assertEquals(WorldInfoPosition.OUTLET, WorldInfoPosition.from(7))
        assertNull(WorldInfoPosition.from(99), "未知取值应返回 null，而不是猜一个")
    }

    // ------------------------------------------------------------ 增删

    @Test
    fun `新增条目后 uid 与内容正确且原条目不动`() {
        val book = book()
        val newUid = book.nextUid()
        val newEntry = WorldInfoEntry(
            raw = buildJsonObject {
                put("uid", newUid)
                put("key", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("new"))))
                put("content", "新增的内容")
                put("constant", false)
                put("selective", true)
                put("order", 500)
                put("position", 4)
                put("disable", false)
            },
            jsonKey = newUid.toString(),
        )

        val updated = book.withEntry(newEntry)

        assertEquals(5, updated.size)
        assertEquals("新增的内容", updated.entry(4)?.content)
        // 原书不受影响
        assertEquals(4, book.size)
        assertEquals(book.toJsonObject(), updated.withoutEntry(4).toJsonObject())
    }

    @Test
    fun `删除条目后其余条目逐字段不变`() {
        val book = book()
        val updated = book.withoutEntry(2)

        assertEquals(3, updated.size)
        assertNull(updated.entry(2))
        assertEquals(book.entry(1)?.toJsonObject(), updated.entry(1)?.toJsonObject())
        assertEquals(book.entry(3)?.toJsonObject(), updated.entry(3)?.toJsonObject())
    }

    // ------------------------------------------------------------ 往返

    @Test
    fun `序列化再解析结果不变`() {
        val original = book()
        val reread = WorldBook.parse(WorldBook.encode(original), "fixture-book")

        assertEquals(original.toJsonObject(), reread.toJsonObject())
        assertEquals(original.entries.map { it.uid }, reread.entries.map { it.uid })
    }

    @Test
    fun `未知字段被保留`() {
        val text = """{"entries":{"0":{"uid":0,"key":["a"],"content":"x","未来才有字段":{"a":1}}},"name":"t"}"""
        val parsed = WorldBook.parse(text)

        assertEquals(1, parsed.size)
        assertNotNull(parsed.entry(0)?.raw?.get("未来才有字段"), "未知字段不能丢")
        assertEquals(json.parseToJsonElement(text), parsed.toJsonObject())
    }

    @Test
    fun `文件系统读写往返`() {
        val original = book()
        val tmp = java.nio.file.Files.createTempDirectory("sillyapp-world-test").resolve("b.json")

        WorldBook.write(tmp, original)
        val back = WorldBook.read(tmp)

        assertEquals(original.toJsonObject(), back.toJsonObject())
        assertEquals("b", back.name) // 文件里没有 name 字段时退回文件名
        java.nio.file.Files.deleteIfExists(tmp)
    }

    @Test
    fun `损坏的世界书给出明确错误`() {
        val e = kotlin.test.assertFailsWith<WorldInfoFormatException> {
            WorldBook.parse("{不是 json")
        }
        assertTrue(e.message!!.contains("合法 JSON"))
    }
}
