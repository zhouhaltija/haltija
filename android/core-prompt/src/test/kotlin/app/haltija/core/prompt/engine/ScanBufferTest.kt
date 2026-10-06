package app.haltija.core.prompt.engine

import app.haltija.core.data.world.SelectiveLogic
import app.haltija.core.data.world.WorldBook
import app.haltija.core.data.world.WorldInfoEntry
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
 * 扫描缓冲与键匹配的测试。
 *
 * 这里的期望值全部来自 `docs/worldinfo-engine-spec.md`（从 ST 源码逐行读出的规范），
 * 每条反直觉行为都有对应用例，防止「顺手写得更合理」而偏离 ST。
 */
class ScanBufferTest {

    // ------------------------------------------------------------ 辅助

    /** 造一个最小条目。字段顺序与 ST 的新条目模板一致。 */
    private fun entry(
        uid: Int = 0,
        keys: List<String> = emptyList(),
        secondary: List<String> = emptyList(),
        content: String = "content",
        order: Int = 100,
        constant: Boolean = false,
        selective: Boolean = true,
        selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        scanDepth: Int? = null,
        depth: Int = 4,
        position: Int = 0,
        disable: Boolean = false,
        preventRecursion: Boolean = false,
        excludeRecursion: Boolean = false,
        delayUntilRecursion: Int = 0,
        group: String = "",
        sticky: Int? = null,
        cooldown: Int? = null,
        delay: Int? = null,
        probability: Int = 100,
        useProbability: Boolean = true,
        ignoreBudget: Boolean = false,
        useGroupScoring: Boolean? = null,
        groupOverride: Boolean = false,
        groupWeight: Int = 100,
        role: Int = 0,
        outletName: String = "",
        matchPersonaDescription: Boolean = false,
        triggers: List<String> = emptyList(),
    ): WorldInfoEntry = WorldInfoEntry(
        raw = buildJsonObject {
            put("uid", uid)
            put("key", kotlinx.serialization.json.JsonArray(keys.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("keysecondary", kotlinx.serialization.json.JsonArray(secondary.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("comment", "")
            put("content", content)
            put("constant", constant)
            put("vectorized", false)
            put("selective", selective)
            put("selectiveLogic", selectiveLogic.value)
            put("addMemo", false)
            put("order", order)
            put("position", position)
            put("disable", disable)
            put("ignoreBudget", ignoreBudget)
            put("excludeRecursion", excludeRecursion)
            put("preventRecursion", preventRecursion)
            put("matchPersonaDescription", matchPersonaDescription)
            put("matchCharacterDescription", false)
            put("matchCharacterPersonality", false)
            put("matchCharacterDepthPrompt", false)
            put("matchScenario", false)
            put("matchCreatorNotes", false)
            put("delayUntilRecursion", delayUntilRecursion)
            put("probability", probability)
            put("useProbability", useProbability)
            put("depth", depth)
            if (useGroupScoring != null) put("useGroupScoring", useGroupScoring)
            put("outletName", outletName)
            put("group", group)
            put("groupOverride", groupOverride)
            put("groupWeight", groupWeight)
            if (scanDepth != null) put("scanDepth", scanDepth)
            if (caseSensitive != null) put("caseSensitive", caseSensitive)
            if (matchWholeWords != null) put("matchWholeWords", matchWholeWords)
            put("automationId", "")
            put("role", role)
            if (sticky != null) put("sticky", sticky)
            if (cooldown != null) put("cooldown", cooldown)
            if (delay != null) put("delay", delay)
            put("triggers", kotlinx.serialization.json.JsonArray(triggers.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        },
        jsonKey = uid.toString(),
    )

    private fun scan(e: WorldInfoEntry, world: String = "book") = ScanEntry.of(e, world)

    /** 消息按「最新在前」传入。 */
    private fun buffer(
        messages: List<String>,
        settings: WorldInfoSettings = WorldInfoSettings.DEFAULT,
        global: GlobalScanData = GlobalScanData.EMPTY,
        substitute: (String) -> String = { it },
    ) = ScanBuffer(messages, global, settings, substitute)

    // ------------------------------------------------------------ get()

    @Test
    fun `默认深度只取最近 N 条且最新的在前`() {
        val b = buffer(listOf("m0", "m1", "m2"), WorldInfoSettings(depth = 2))
        val text = b.get(scan(m(keys = listOf("x"))), ScanState.INITIAL)

        // 哨兵 + m0 + JOINER + m1
        assertEquals("\u0001m0\n\u0001m1", text)
        assertFalse(text.contains("m2"), "深度 2 只能看到最近两条")
    }

    @Test
    fun `条目的 scanDepth 覆盖全局深度`() {
        val b = buffer(listOf("m0", "m1", "m2"), WorldInfoSettings(depth = 1))
        val text = b.get(scan(m(scanDepth = 3)), ScanState.INITIAL)

        assertEquals("\u0001m0\n\u0001m1\n\u0001m2", text)
    }

    @Test
    fun `scanDepth 为 0 时永不命中`() {
        val b = buffer(listOf("m0"))
        val e = scan(m(keys = listOf("m0"), scanDepth = 0))

        assertEquals("", b.get(e, ScanState.INITIAL), "scanDepth=0 必须返回空串")
        assertFalse(b.matchKeys(b.get(e, ScanState.INITIAL), "m0", e), "键再准也永不命中")
    }

    @Test
    fun `depth 大于消息数时不会多拼出空段`() {
        val b = buffer(listOf("m0", "m1"), WorldInfoSettings(depth = 50))
        assertEquals("\u0001m0\n\u0001m1", b.get(scan(m()), ScanState.INITIAL))
    }

    @Test
    fun `空消息留下空洞但不影响其它段`() {
        val b = buffer(listOf("m0", "", "m2"), WorldInfoSettings(depth = 3))
        assertEquals("\u0001m0\n\u0001\n\u0001m2", b.get(scan(m()), ScanState.INITIAL))
    }

    @Test
    fun `消息被 trim`() {
        val b = buffer(listOf("  spaced  "), WorldInfoSettings(depth = 1))
        assertEquals("\u0001spaced", b.get(scan(m()), ScanState.INITIAL))
    }

    @Test
    fun `match 开关按固定顺序追加全局文本`() {
        val global = GlobalScanData(
            personaDescription = "PERSONA",
            characterDescription = "DESC",
            characterPersonality = "PERS",
            scenario = "SCEN",
        )
        val b = buffer(
            listOf("m0"),
            WorldInfoSettings(depth = 1),
            global,
        )
        val e = scan(
            m(
                matchPersonaDescription = true,
                // 只开 persona，验证不会误加别的
            ),
        )
        assertEquals("\u0001m0\n\u0001PERSONA", b.get(e, ScanState.INITIAL))
    }

    @Test
    fun `注入缓冲追加在全局文本之后`() {
        val b = buffer(listOf("m0"), WorldInfoSettings(depth = 1))
        b.addInject("INJECT")
        assertEquals("\u0001m0\n\u0001INJECT", b.get(scan(m()), ScanState.INITIAL))
    }

    @Test
    fun `递归缓冲在 MIN_ACTIVATIONS 轮不可见`() {
        val b = buffer(listOf("m0"), WorldInfoSettings(depth = 1))
        b.addRecurse("RECURSED")

        assertEquals("\u0001m0\n\u0001RECURSED", b.get(scan(m()), ScanState.INITIAL))
        assertEquals("\u0001m0\n\u0001RECURSED", b.get(scan(m()), ScanState.RECURSION))
        assertEquals("\u0001m0", b.get(scan(m()), ScanState.MIN_ACTIVATIONS))
    }

    @Test
    fun `advanceScan 让基准深度递增`() {
        val b = buffer(listOf("m0", "m1", "m2"), WorldInfoSettings(depth = 1))
        assertEquals(1, b.getDepth())
        b.advanceScan()
        assertEquals(2, b.getDepth())
        assertEquals("\u0001m0\n\u0001m1", b.get(scan(m()), ScanState.MIN_ACTIVATIONS))
    }

    // ------------------------------------------------------------ matchKeys

    @Test
    fun `普通键做子串匹配且默认忽略大小写`() {
        val b = buffer(listOf("Hello World"))
        val e = scan(m())
        assertTrue(b.matchKeys("say hello world!", "HELLO", e))
        assertFalse(b.matchKeys("say hello world!", "absent", e))
    }

    @Test
    fun `caseSensitive 为真时区分大小写`() {
        val b = buffer(listOf("Hello"))
        assertTrue(b.matchKeys("Hello", "Hello", scan(m(caseSensitive = true))))
        assertFalse(b.matchKeys("Hello", "hello", scan(m(caseSensitive = true))))
    }

    @Test
    fun `条目级 caseSensitive 覆盖全局`() {
        val falseGlobal = buffer(listOf("x"), WorldInfoSettings(caseSensitive = false))
        assertTrue(falseGlobal.matchKeys("Hello", "hello", scan(m(caseSensitive = true))) == false)

        val trueGlobal = buffer(listOf("x"), WorldInfoSettings(caseSensitive = true))
        assertTrue(trueGlobal.matchKeys("Hello", "hello", scan(m(caseSensitive = false))) == true)
    }

    @Test
    fun `整词匹配拒绝子串命中`() {
        val e = scan(m(matchWholeWords = true))
        var b = buffer(listOf("x"))
        assertTrue(b.matchKeys("a cat here", "cat", e))
        assertFalse(b.matchKeys("concatenate", "cat", e), "整词时不应命中子串")

        b = buffer(listOf("x"))
        assertTrue(b.matchKeys("cat", "cat", e), "串首应命中")
        assertTrue(b.matchKeys("cat.", "cat", e), "标点算边界")
        assertTrue(b.matchKeys("\u0001cat\n", "cat", e), "哨兵也算边界")
    }

    @Test
    fun `整词模式下多词键退化为纯 includes`() {
        val e = scan(m(matchWholeWords = true))
        val b = buffer(listOf("x"))
        // 多词键走 includes ⇒ 不做边界检查
        assertTrue(b.matchKeys("xxhello worldxx", "hello world", e))
        // 单词键走边界正则 ⇒ 拒绝
        assertFalse(b.matchKeys("xxhelloxx", "hello", e))
    }

    @Test
    fun `正则键无视 caseSensitive 与 matchWholeWords`() {
        val e = scan(m(caseSensitive = true, matchWholeWords = true))
        val b = buffer(listOf("x"))

        // 带 i 修饰符 ⇒ 忽略大小写，即便条目声明 caseSensitive
        assertTrue(b.matchKeys("HELLO", "/hello/i", e))
        // 不带 i ⇒ 区分大小写
        assertFalse(b.matchKeys("HELLO", "/hello/", e))
        // 正则无视整词：子串也命中
        assertTrue(b.matchKeys("concatenate", "/cat/", e))
    }

    @Test
    fun `整词匹配会转义正则特殊字符`() {
        val e = scan(m(matchWholeWords = true))
        val b = buffer(listOf("x"))
        // 键里有正则元字符，必须当字面量处理
        assertTrue(b.matchKeys("a c++ b", "c++", e))
        assertFalse(b.matchKeys("a cxx b", "c++", e), "c++ 不应被当成正则 c+")
        assertTrue(b.matchKeys("price is $5.", "\$5", e), "$ 应被转义")
    }

    @Test
    fun `escapeRegex 与 ST 的实现一致`() {
        // ST: string.replace(/[/\-\\^$*+?.()|[\]{}]/g, '\\$&')
        assertEquals("a\\/b", ScanBuffer.escapeRegex("a/b"))
        assertEquals("a\\-b", ScanBuffer.escapeRegex("a-b"))
        assertEquals("\\[x\\]", ScanBuffer.escapeRegex("[x]"))
        assertEquals("plain", ScanBuffer.escapeRegex("plain"))
    }

    // ------------------------------------------------------------ getScore

    @Test
    fun `没有主键时分数为 0`() {
        val b = buffer(listOf("anything"))
        assertEquals(0, b.getScore(scan(m(keys = emptyList())), ScanState.INITIAL))
    }

    @Test
    fun `AND_ANY 合计主次键命中数`() {
        val e = scan(m(keys = listOf("alpha", "beta"), secondary = listOf("gamma", "delta")))
        val b = buffer(listOf("alpha beta gamma"), WorldInfoSettings(depth = 1))
        // 主键命中 2（alpha、beta），次键命中 1（gamma）⇒ 3
        assertEquals(3, b.getScore(e, ScanState.INITIAL))
    }

    @Test
    fun `AND_ALL 在次键未全中时只算主键`() {
        val e = scan(
            m(
                keys = listOf("alpha", "beta"),
                secondary = listOf("gamma", "delta"),
                selectiveLogic = SelectiveLogic.AND_ALL,
            ),
        )
        val partial = buffer(listOf("alpha beta gamma"), WorldInfoSettings(depth = 1))
        assertEquals(2, partial.getScore(e, ScanState.INITIAL), "次键缺一个 ⇒ 只算主键")

        val all = buffer(listOf("alpha beta gamma delta"), WorldInfoSettings(depth = 1))
        assertEquals(4, all.getScore(e, ScanState.INITIAL))
    }

    @Test
    fun `NOT_ANY 与 NOT_ALL 只算主键`() {
        for (logic in listOf(SelectiveLogic.NOT_ANY, SelectiveLogic.NOT_ALL)) {
            val e = scan(m(keys = listOf("alpha"), secondary = listOf("gamma"), selectiveLogic = logic))
            val b = buffer(listOf("alpha gamma"), WorldInfoSettings(depth = 1))
            assertEquals(1, b.getScore(e, ScanState.INITIAL), "$logic 应只算主键")
        }
    }

    // ------------------------------------------------------------ 真实 fixture

    @Test
    fun `fixture 世界书能被正确扫描`() {
        val book = loadFixtureBook()
        val entries = book.entries.map { ScanEntry.of(it, "fixture-book") }

        assertEquals(4, entries.size)
        assertEquals(listOf("0", "1", "2", "3"), entries.map { it.uid.toString() })

        val byUid = entries.associateBy { it.uid }
        assertTrue(byUid.getValue(0).constant)
        assertTrue(byUid.getValue(3).disable)
        assertTrue(byUid.getValue(2).entry.hasRegexKey)
    }

    @Test
    fun `fixture 里停用的 constant 条目仍不会被激活是引擎的职责（此处只验证字段）`() {
        val disabled = loadFixtureBook().entry(3) ?: fail("缺 uid 3")
        assertTrue(disabled.disable)
        assertTrue(disabled.constant, "它同时是 constant，所以引擎若忽略 disable 会立刻暴露")
    }

    @Test
    fun `fixture 的正则键条目能命中聊天文本`() {
        val book = loadFixtureBook()
        val e = ScanEntry.of(book.entry(2) ?: fail("缺 uid 2"), "fixture-book")
        val b = buffer(listOf("she lowered her voice to a whisper"), WorldInfoSettings(depth = 4))

        val text = b.get(e, ScanState.INITIAL)
        // 主键是 /whisper(s)?/i
        assertNotNull(e.entry.keys.firstOrNull { b.matchKeys(text, it, e) }, "whisper 应命中正则键")
    }

    @Test
    fun `fixture 的选择性条目需要次键配合`() {
        val book = loadFixtureBook()
        val e = ScanEntry.of(book.entry(1) ?: fail("缺 uid 1"), "fixture-book")

        val onlySword = buffer(listOf("I draw my sword"), WorldInfoSettings(depth = 4))
        val t1 = onlySword.get(e, ScanState.INITIAL)
        assertTrue(e.entry.keys.any { onlySword.matchKeys(t1, it, e) }, "主键 sword 命中")
        assertFalse(e.entry.secondaryKeys.any { onlySword.matchKeys(t1, it, e) }, "次键都不命中")

        val withSilver = buffer(listOf("a silver sword"), WorldInfoSettings(depth = 4))
        val t2 = withSilver.get(e, ScanState.INITIAL)
        assertTrue(e.entry.secondaryKeys.any { withSilver.matchKeys(t2, it, e) }, "次键 silver 命中")
    }

    // ------------------------------------------------------------ 宏替换钩子

    @Test
    fun `宏替换在 trim 之前应用且空结果视为无效`() {
        val b = buffer(listOf("x"), substitute = { if (it == "{{gone}}") "" else "  replaced  " })
        assertNull(b.substitutedKey("{{gone}}"), "替换为空应返回 null")
        assertEquals("replaced", b.substitutedKey("{{any}}"))
    }

    // ------------------------------------------------------------ 内部

    private fun m(
        keys: List<String> = emptyList(),
        secondary: List<String> = emptyList(),
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        scanDepth: Int? = null,
        selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        matchPersonaDescription: Boolean = false,
    ) = entry(
        keys = keys,
        secondary = secondary,
        caseSensitive = caseSensitive,
        matchWholeWords = matchWholeWords,
        scanDepth = scanDepth,
        selectiveLogic = selectiveLogic,
        matchPersonaDescription = matchPersonaDescription,
    )

    private fun loadFixtureBook(): WorldBook {
        val bytes = javaClass.getResourceAsStream("/worlds/fixture-book.json")?.use { it.readBytes() }
            ?: fail("找不到 fixture 世界书 —— 请运行 `node tools/gen-fixtures.mjs`")
        return WorldBook.parse(bytes, "fixture-book")
    }
}
