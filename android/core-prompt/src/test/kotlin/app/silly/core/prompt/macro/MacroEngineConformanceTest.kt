package app.silly.core.prompt.macro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 跨语言一致性测试：Kotlin 宏引擎必须与 **SillyTavern 真实宏引擎** 行为一致。
 *
 * golden 由 `tools/gen-macro-goldens.mjs` 生成 —— 它把 ST 的
 * `MacroLexer` / `MacroParser` / `MacroEngine` / `MacroCstWalker` / `MacroRegistry`
 * 从源码里逐字抽到一个临时模块树里（只重写 import 路径、把 DOM 相关的
 * `MacroDiagnostics` 换成桩），然后原样运行。
 *
 * 这里注册**同一批测试宏**，比对全部用例的输出。
 */
class MacroEngineConformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val golden: JsonObject by lazy {
        val bytes = javaClass.getResourceAsStream("/macro-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/macro-goldens.json —— 请运行 `node tools/gen-macro-goldens.mjs`")
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private val cases: List<Pair<String, String>> by lazy {
        (golden["cases"] as kotlinx.serialization.json.JsonArray).map { el ->
            val o = el.jsonObject
            o["input"]!!.jsonPrimitive.content to o["output"]!!.jsonPrimitive.content
        }
    }

    /** 注册与 golden 脚本里**同名同语义**的测试宏。 */
    private fun buildEngine(): MacroEngine {
        val registry = MacroRegistry()

        registry.register(
            name = "up",
            unnamedArgs = listOf(MacroArgDef("text")),
        ) { ctx -> (ctx.unnamedArgs[0] ?: "").uppercase() }

        registry.register(
            name = "echo",
            unnamedArgs = listOf(MacroArgDef("text")),
        ) { ctx -> ctx.unnamedArgs[0] ?: "" }

        registry.register(
            name = "twice",
            unnamedArgs = listOf(MacroArgDef("text")),
        ) { ctx -> (ctx.unnamedArgs[0] ?: "").let { it + it } }

        // 复刻 JS 的 `'.'.repeat(Number(count))`：
        //   count 缺失 → Number(undefined)=NaN → repeat(NaN)=""（不抛错）
        //   count 为负 → repeat(-1) 抛 RangeError → 引擎捕获 → 宏回退成原文
        registry.register(
            name = "pad",
            unnamedArgs = listOf(MacroArgDef("count", MacroValueType.INTEGER, optional = true, defaultValue = "1")),
        ) { ctx ->
            val count = ctx.unnamedArgs[0]?.toIntOrNull()
            if (count == null) "" else repeatDots(count)
        }

        registry.register(
            name = "pad2",
            unnamedArgs = listOf(MacroArgDef("count", MacroValueType.INTEGER, defaultValue = "1")),
        ) { ctx ->
            val count = ctx.unnamedArgs[0]?.toIntOrNull()
            if (count == null) "" else repeatDots(count)
        }

        // handler 里 `a ?? 'null'`：缺失参数渲染成字面量 "null"
        registry.register(
            name = "opt",
            unnamedArgs = listOf(
                MacroArgDef("a", optional = true),
                MacroArgDef("b", optional = true, defaultValue = "D"),
            ),
        ) { ctx ->
            val a = ctx.unnamedArgs.getOrNull(0) ?: "null"
            val b = ctx.unnamedArgs.getOrNull(1) ?: "null"
            "[$a|$b]"
        }

        registry.register(name = "noop") { "" }

        registry.register(
            name = "bang",
            unnamedArgs = listOf(MacroArgDef("text")),
        ) { ctx -> "!${ctx.unnamedArgs[0] ?: ""}!" }

        registry.register(name = "char") { ctx -> ctx.env.name("char") }
        registry.register(name = "user") { ctx -> ctx.env.name("user") }
        registry.register(name = "description") { ctx -> ctx.env.character("description") }
        registry.register(name = "group") { ctx -> ctx.env.name("group") }
        registry.register(name = "charIfNotGroup") { ctx -> ctx.env.name("group") }

        val env = MacroEnv(
            names = mapOf("char" to "Seraphina", "user" to "Traveler", "group" to "The Trio"),
            character = mapOf(
                "name" to "Seraphina",
                "description" to "A scholar.",
                "personality" to "Curious.",
            ),
            system = mapOf("maxPrompt" to "2048"),
        )

        return MacroEngine(registry, env)
    }

    // ------------------------------------------------------------ 主对照

    @Test
    fun `用例数量合理且覆盖关键语法`() {
        assertTrue(cases.size >= 30, "用例太少：${cases.size}")
        assertTrue(cases.any { it.first == "{{up:abc}}" }, "应覆盖单参数")
        assertTrue(cases.any { it.first.contains("::a::b") }, "应覆盖多参数分隔符")
        assertTrue(cases.any { it.first == "{{}}" }, "应覆盖空宏")
    }

    @Test
    fun `每一条输出都与 ST 的真实宏引擎一致`() {
        val engine = buildEngine()
        val mismatches = mutableListOf<String>()

        for ((input, expected) in cases) {
            val actual = engine.evaluate(input)
            if (actual != expected) {
                mismatches += "  ${quote(input)}\n    ST     : ${quote(expected)}\n    Kotlin : ${quote(actual)}"
            }
        }

        assertTrue(mismatches.isEmpty(), "宏求值分歧：\n${mismatches.joinToString("\n")}")
    }

    // ------------------------------------------------------------ 关键行为

    @Test
    fun `单冒号是一个参数且参数内可含冒号`() {
        val e = buildEngine()
        assertEquals("a:b", e.evaluate("{{echo:a:b}}"))
        assertEquals(":a", e.evaluate("{{echo:::a}}"))
    }

    @Test
    fun `双冒号是多参数分隔符`() {
        val e = buildEngine()
        assertEquals("[a|b]", e.evaluate("{{opt::a::b}}"))
        assertEquals("[a|null]", e.evaluate("{{opt::a}}"))
    }

    @Test
    fun `参数个数不匹配时整段回退成原文`() {
        val e = buildEngine()
        // opt 只声明两个参数
        assertEquals("{{opt::a::b::c}}", e.evaluate("{{opt::a::b::c}}"))
        // echo 只声明一个参数
        assertEquals("{{echo::a::b}}", e.evaluate("{{echo::a::b}}"))
        // pad2 的必填参数没给
        assertEquals("{{pad2}}", e.evaluate("{{pad2}}"))
    }

    @Test
    fun `未注册的宏原样保留`() {
        val e = buildEngine()
        assertEquals("{{unknownMacro}}", e.evaluate("{{unknownMacro}}"))
        assertEquals("a{{unknownMacro}}b", e.evaluate("a{{unknownMacro}}b"))
    }

    @Test
    fun `语法不完整时原样保留`() {
        val e = buildEngine()
        assertEquals("{{up:abc", e.evaluate("{{up:abc"))
        assertEquals("{{}}", e.evaluate("{{}}"))
        assertEquals("{{ }}", e.evaluate("{{ }}"))
        assertEquals("{{", e.evaluate("{{"))
    }

    @Test
    fun `嵌套宏内层先求值`() {
        val e = buildEngine()
        assertEquals("SERAPHINA", e.evaluate("{{up:{{char}}}}"))
        assertEquals("SeraphinaSeraphina", e.evaluate("{{twice:{{char}}}}"))
    }

    @Test
    fun `宏名不合法时把开头两个花括号当文本`() {
        val e = buildEngine()
        // 外层 {{ 是文本，内层 {{char}} 才是宏
        assertEquals("{{Seraphina}}", e.evaluate("{{{{char}}}}"))
    }

    @Test
    fun `参数会被 trim`() {
        val e = buildEngine()
        assertEquals("ABC", e.evaluate("{{up: abc }}"))
        assertEquals("padded", e.evaluate("{{echo:  padded  }}"))
    }

    @Test
    fun `非宏的花括号原样保留`() {
        val e = buildEngine()
        assertEquals("a {b} c", e.evaluate("a {b} c"))
        assertEquals("plain text", e.evaluate("plain text"))
    }

    // ------------------------------------------------------------ 预处理 / 后处理

    @Test
    fun `反斜杠转义的花括号在求值后才还原`() {
        val e = buildEngine()
        // 关键：反斜杠阻止了它被当成宏，最后只把反斜杠去掉
        assertEquals("{{char}}", e.evaluate("\\{\\{char\\}\\}"))
    }

    @Test
    fun `trim 是后处理正则并会吃掉相邻换行`() {
        val e = buildEngine()
        assertEquals("AB", e.evaluate("A\n{{trim}}\nB"))
        assertEquals("AB", e.evaluate("A{{trim}}B"))
        assertEquals("A", e.evaluate("A\n{{trim}}"))
    }

    @Test
    fun `老式尖括号标记被改写`() {
        val e = buildEngine()
        assertEquals("Seraphina", e.evaluate("<BOT>"))
        assertEquals("Seraphina", e.evaluate("<CHAR>"))
        assertEquals("Traveler", e.evaluate("<USER>"))
        assertEquals("The Trio", e.evaluate("<GROUP>"))
    }

    @Test
    fun `宏标识符规则与 ST 一致`() {
        val e = buildEngine()
        assertTrue(e.isValidIdentifier("up"))
        assertTrue(e.isValidIdentifier("char_2"))
        assertTrue(e.isValidIdentifier("get-var"))
        assertTrue(!e.isValidIdentifier("2up"), "不能以数字开头")
        assertTrue(!e.isValidIdentifier("a b"), "不能含空格")
        assertTrue(!e.isValidIdentifier(""), "不能为空")
    }

    /** 负数要抛错，模拟 JS 的 `String.prototype.repeat` 越界行为。 */
    private fun repeatDots(count: Int): String {
        require(count >= 0) { "Invalid count value: $count" }
        return ".".repeat(count)
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
