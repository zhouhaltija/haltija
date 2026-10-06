package app.haltija.core.prompt.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 跨语言一致性测试：Kotlin 的斜杠正则必须和 SillyTavern 的 `parseRegexFromString` 行为一致。
 *
 * golden 由 `tools/gen-worldinfo-goldens.mjs` 生成 —— 它在生成时**从 ST 源码里
 * 逐字抽取**该函数并运行，所以这是真正的「拿 ST 的行为当标准」，
 * 而不是「拿我抄的一份 JS 当标准」。
 *
 * 比对的不是模式字符串，而是**对 19 个探针串的实际匹配结果**。
 * 原因：JS 的 `RegExp.source` 会把 `/` 转义成 `\/`，而 ST 内部变量是未转义的，
 * 两边文本形态不同但语义相同，比字符串只会得到假阴性。
 *
 * 这个测试的价值在于暴露 JVM 与 JS 正则引擎的语义分歧（sticky、unicode 属性转义等）。
 */
class RegexKeyConformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val golden: JsonObject by lazy {
        val bytes = javaClass.getResourceAsStream("/worldinfo-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/worldinfo-goldens.json —— 请运行 `node tools/gen-worldinfo-goldens.mjs`")
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private val probes: List<String> by lazy {
        golden["probes"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private val cases: List<JsonObject> by lazy {
        golden["parseRegexFromString"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `探针串非空且用例数量合理`() {
        assertTrue(probes.size >= 10, "探针串太少：${probes.size}")
        assertTrue(cases.size >= 20, "用例太少：${cases.size}")
    }

    @Test
    fun `合法性判定与 ST 完全一致`() {
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            val input = case["input"]!!.jsonPrimitive.content
            val expectedValid = case["valid"]!!.jsonPrimitive.boolean
            val actualValid = RegexKey.isValid(input)
            if (expectedValid != actualValid) {
                mismatches += "  '$input' ST=$expectedValid Kotlin=$actualValid"
            }
        }
        assertTrue(mismatches.isEmpty(), "合法性判定不一致：\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `修饰符集合与 ST 完全一致`() {
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            if (!case["valid"]!!.jsonPrimitive.boolean) continue
            val input = case["input"]!!.jsonPrimitive.content
            val expected = case["flagSet"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted()
            // 注意类型：expected 是 List<String>，这里必须把 Char 转成 String，
            // 否则 Kotlin 会拿 List<Char> 和 List<String> 比，恒不相等。
            val actual = RegexKey.parse(input)?.flags?.toSet()?.map { it.toString() }?.sorted() ?: emptyList()
            if (expected != actual) mismatches += "  '$input' ST=$expected Kotlin=$actual"
        }
        assertTrue(mismatches.isEmpty(), "修饰符不一致：\n${mismatches.joinToString("\n")}")
    }

    /**
     * 已知的 JVM / JS 正则语义分歧。
     *
     * 这些是**引擎层面的差异，不是实现 bug**，无法在不自己写正则引擎的前提下消除。
     * 列在这里而不是从用例表里删掉，是为了让分歧保持可见：
     * 一旦将来某条不再分歧，[已知分歧仍然存在] 会失败并提醒更新文档。
     */
    private val knownDivergences = mapOf(
        "/\\p{L}+/" to
            "JS 在没有 u 修饰符时按 Annex B 把 \\p 当作字面量 p；JVM 始终按 Unicode 属性解释。" +
            "真实世界书不会用这种写法。",
        "/^\$/m" to
            "Java 的 MULTILINE 在**空输入**上让 ^$ 不匹配，JS 匹配。" +
            "已确认只影响空串这一种情形，^abc$ 之类完全一致。",
    )

    @Test
    fun `每一条合法正则的匹配行为都与 ST 一致（已知分歧除外）`() {
        val mismatches = mutableListOf<String>()
        for (case in cases) {
            if (!case["valid"]!!.jsonPrimitive.boolean) continue
            val input = case["input"]!!.jsonPrimitive.content
            if (input in knownDivergences) continue

            val expected = case["matched"]!!.jsonArray.map { it.jsonPrimitive.boolean }
            val key = RegexKey.parse(input)
            if (key == null) {
                mismatches += "  '$input' Kotlin 解析失败但 ST 认为合法"
                continue
            }
            for ((i, probe) in probes.withIndex()) {
                val want = expected[i]
                val got = key.matches(probe)
                if (want != got) mismatches += "  '$input' 对探针 ${quote(probe)}：ST=$want Kotlin=$got"
            }
        }
        assertTrue(
            mismatches.isEmpty(),
            "出现了未登记的匹配行为分歧（要么是 bug，要么需要补进 knownDivergences）：\n" +
                mismatches.joinToString("\n"),
        )
    }

    @Test
    fun `已知分歧仍然存在`() {
        for ((input, reason) in knownDivergences) {
            val case = cases.firstOrNull { it["input"]!!.jsonPrimitive.content == input }
                ?: fail("用例表里没有 '$input'，它应当留在表里以保持可见")
            val expected = case["matched"]!!.jsonArray.map { it.jsonPrimitive.boolean }
            val key = RegexKey.parse(input) ?: fail("'$input' 应当能解析")

            val diverged = probes.withIndex().any { (i, probe) -> expected[i] != key.matches(probe) }
            assertTrue(diverged, "登记的分歧 '$input' 实际已不存在，请从 knownDivergences 移除。原因记录：$reason")
        }
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""

    // ------------------------------------------------------------ 单元用例

    @Test
    fun `非法输入一律返回 null`() {
        for (bad in listOf("abc", "/abc", "abc/", "", "/", "/a/b/", "/abc/x", "/[(", "//")) {
            assertEquals(null, RegexKey.parse(bad), "'$bad' 应判为非法")
        }
    }

    @Test
    fun `合法输入解析出正确的模式与修饰符`() {
        val key = RegexKey.parse("/whisper(s)?/i") ?: fail("应解析成功")
        assertEquals("whisper(s)?", key.pattern)
        assertEquals("i", key.flags)
        assertTrue(key.matches("A WHISPER"))
        assertTrue(key.matches("I whisper softly"))
        assertTrue(!key.matches("no match here"))
    }

    @Test
    fun `转义的斜杠被反转义`() {
        val key = RegexKey.parse("/a\\/b/") ?: fail("应解析成功")
        assertEquals("a/b", key.pattern)
        assertTrue(key.matches("x a/b y"))
    }

    @Test
    fun `sticky 修饰符被正确模拟`() {
        val sticky = RegexKey.parse("/abc/y") ?: fail("应解析成功")
        assertTrue(sticky.matches("abc at start"), "串首应当命中")
        assertTrue(!sticky.matches("xabc"), "y 要求从串首开始，不应命中")

        val plain = RegexKey.parse("/abc/") ?: fail("应解析成功")
        assertTrue(plain.matches("xabc"), "没有 y 时应当普通包含匹配")
    }

    @Test
    fun `MULTILINE 与 DOT_MATCHES_ALL 生效`() {
        val m = RegexKey.parse("/^end/m") ?: fail("应解析成功")
        assertTrue(m.matches("line1\nend"))

        val s = RegexKey.parse("/a.b/s") ?: fail("应解析成功")
        assertTrue(s.matches("a\nb"), "s 修饰符应让 . 匹配换行")

        val noS = RegexKey.parse("/a.b/") ?: fail("应解析成功")
        assertTrue(!noS.matches("a\nb"), "没有 s 时 . 不应匹配换行")
    }
}
