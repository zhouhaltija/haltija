package app.silly.core.prompt.macro

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 内置宏的验收测试。
 *
 * 期望值来自 `docs/macro-engine-spec.md`（从 ST 源码读出并实测的规范）。
 * 随机源与时钟都是注入的，所以概率、骰子、时间相关的用例是**确定性的**。
 */
class CoreMacrosTest {

    private val fixedClock: ZonedDateTime =
        ZonedDateTime.of(2026, 10, 5, 14, 30, 45, 0, ZoneId.of("Asia/Shanghai"))

    private fun engine(
        env: MacroEnv = MacroEnv(),
    ): MacroEngine {
        val registry = MacroRegistry()
        CoreMacros.install(registry)
        return MacroEngine(registry, env)
    }

    private fun defaultEnv(
        random: () -> Double = { 0.0 },
        chat: List<MacroChatMessage> = emptyList(),
        variables: MacroVariables = MacroVariables(),
        locale: Locale = Locale.ENGLISH,
    ) = MacroEnv(
        names = mapOf(
            "char" to "Seraphina",
            "user" to "Traveler",
            "group" to "The Trio",
            "groupNotMuted" to "Muted-less",
            "notChar" to "Traveler",
        ),
        character = mapOf(
            "description" to "A wandering scholar.",
            "personality" to "Curious, patient.",
            "scenario" to "A rain-soaked library.",
            "persona" to "The user is a traveler.",
            "charPrompt" to "CHAR-PROMPT",
            "charInstruction" to "CHAR-POST",
            "charDepthPrompt" to "DEPTH",
            "creatorNotes" to "NOTES",
            "firstMessage" to "Hello there.",
            "alternateGreetings" to "Alt one.\u0001Alt two.",
            "version" to "1.2",
            "mesExamples" to "<START>",
            "mesExamplesRaw" to "<START> raw",
        ),
        system = mapOf("model" to "test-model", "maxPrompt" to "2048", "maxContext" to "4096", "maxResponse" to "512"),
        random = random,
        variables = variables,
        chat = chat,
        clock = { fixedClock },
        locale = locale,
        input = "typed text",
    )

    private fun eval(text: String, env: MacroEnv = defaultEnv()): String = engine(env).evaluate(text)

    // ------------------------------------------------------------ 工具

    @Test
    fun `space 与 newline`() {
        assertEquals(" ", eval("{{space}}"))
        assertEquals("   ", eval("{{space::3}}"))
        assertEquals("", eval("{{space::0}}"))
        assertEquals("\n", eval("{{newline}}"))
        assertEquals("\n\n", eval("{{newline::2}}"))
    }

    @Test
    fun `space 负数回退成原文`() {
        // JS 的 ' '.repeat(-1) 抛 RangeError → 引擎捕获 → 宏原样保留
        assertEquals("{{space::-1}}", eval("{{space::-1}}"))
    }

    @Test
    fun `reverse 按码点反转且不拆散代理对`() {
        assertEquals("cba", eval("{{reverse::abc}}"))
        // 代理对必须整体保留
        assertEquals("b😀a", eval("{{reverse::a😀b}}"))
        assertEquals("", eval("{{reverse::}}"))
    }

    @Test
    fun `简单工具宏`() {
        assertEquals("", eval("{{noop}}"))
        assertEquals("", eval("{{// 注释}}"))
        assertEquals("typed text", eval("{{input}}"))
        assertEquals("true", eval("{{isMobile}}"))
        assertEquals("test-model", eval("{{model}}"))
        assertEquals("2048", eval("{{maxPrompt}}"))
        assertEquals("2048", eval("{{maxPromptTokens}}"))
    }

    // ------------------------------------------------------------ 名字与角色

    @Test
    fun `名字与角色字段`() {
        assertEquals("Seraphina", eval("{{char}}"))
        assertEquals("Traveler", eval("{{user}}"))
        assertEquals("The Trio", eval("{{group}}"))
        assertEquals("The Trio", eval("{{charIfNotGroup}}"))
        assertEquals("A wandering scholar.", eval("{{description}}"))
        assertEquals("A wandering scholar.", eval("{{charDescription}}"))
        assertEquals("Curious, patient.", eval("{{personality}}"))
        assertEquals("A rain-soaked library.", eval("{{scenario}}"))
        assertEquals("The user is a traveler.", eval("{{persona}}"))
        assertEquals("CHAR-PROMPT", eval("{{charPrompt}}"))
        assertEquals("CHAR-POST", eval("{{charInstruction}}"))
        assertEquals("DEPTH", eval("{{charDepthPrompt}}"))
        assertEquals("NOTES", eval("{{creatorNotes}}"))
        assertEquals("1.2", eval("{{version}}"))
    }

    @Test
    fun `charFirstMessage 取首条与备选`() {
        assertEquals("Hello there.", eval("{{charFirstMessage}}"))
        assertEquals("Hello there.", eval("{{greeting::0}}"))
        assertEquals("Alt one.", eval("{{greeting::1}}"))
        assertEquals("Alt two.", eval("{{greeting::2}}"))
        assertEquals("", eval("{{greeting::9}}"), "越界返回空串")
    }

    @Test
    fun `缺失的角色字段返回空串而不是报错`() {
        assertEquals("", eval("{{scenario}}", MacroEnv()))
    }

    // ------------------------------------------------------------ 变量

    @Test
    fun `局部变量读写`() {
        // 变量存在 env.variables 里，所以这一串调用必须共用一个 env
        val env = defaultEnv()
        assertEquals("", eval("{{setvar::name::Alice}}", env))
        assertEquals("Alice", eval("{{getvar::name}}", env))
        assertEquals("true", eval("{{hasvar::name}}", env))
        assertEquals("false", eval("{{hasvar::missing}}", env))
        assertEquals("", eval("{{deletevar::name}}", env))
        assertEquals("", eval("{{getvar::name}}", env))
    }

    @Test
    fun `数字变量自增自减`() {
        val env = defaultEnv()
        eval("{{setvar::n::5}}", env)
        assertEquals("6", eval("{{incvar::n}}", env))
        assertEquals("7", eval("{{incvar::n}}", env))
        assertEquals("6", eval("{{decvar::n}}", env))
    }

    @Test
    fun `addvar 数字相加字符串拼接`() {
        val env = defaultEnv()
        eval("{{setvar::n::2}}", env)
        eval("{{addvar::n::3}}", env)
        assertEquals("5", eval("{{getvar::n}}", env))

        eval("{{setvar::s::ab}}", env)
        eval("{{addvar::s::cd}}", env)
        assertEquals("abcd", eval("{{getvar::s}}", env))
    }

    @Test
    fun `局部与全局变量互不干扰`() {
        val env = defaultEnv()
        eval("{{setvar::x::local}}", env)
        eval("{{setglobalvar::x::global}}", env)

        assertEquals("local", eval("{{getvar::x}}", env))
        assertEquals("global", eval("{{getglobalvar::x}}", env))
        assertEquals("true", eval("{{globalvarexists::x}}", env))
    }

    @Test
    fun `变量跨求值保留`() {
        val shared = MacroVariables()
        val env = defaultEnv(variables = shared)

        eval("{{setvar::persist::value}}", env)
        assertEquals("value", eval("{{getvar::persist}}", env))
    }

    // ------------------------------------------------------------ 时间

    @Test
    fun `固定时钟下的时间宏`() {
        assertEquals("2026-10-05", eval("{{isodate}}"))
        assertEquals("14:30", eval("{{isotime}}"))
        assertEquals("Monday", eval("{{weekday}}"))
        assertEquals("October 5, 2026", eval("{{date}}"))
        assertEquals("2:30 PM", eval("{{time}}"))
    }

    @Test
    fun `time 支持 UTC 偏移`() {
        // 上海 14:30 → UTC+0 是 06:30
        assertEquals("6:30 AM", eval("{{time::UTC+0}}"))
        assertEquals("6:30 AM", eval("{{time_UTC+0}}"), "旧写法应被预处理改写")
    }

    @Test
    fun `datetimeformat 用 moment 风格的模式`() {
        assertEquals("2026/10/05", eval("{{datetimeformat::yyyy/MM/dd}}"))
        assertEquals("14:30:45", eval("{{datetimeformat::HH:mm:ss}}"))
    }

    @Test
    fun `中文 locale 的格式不同`() {
        val env = defaultEnv(locale = Locale.SIMPLIFIED_CHINESE)
        assertEquals("14:30", eval("{{time}}", env))
        assertTrue(eval("{{date}}", env).contains("2026"), "中文长日期应含年份")
    }

    // ------------------------------------------------------------ 随机

    @Test
    fun `roll 支持 NdM 与纯数字简写`() {
        // random 恒为 0 ⇒ 每颗骰子出 1
        assertEquals("1", eval("{{roll::1d6}}"))
        assertEquals("1", eval("{{roll::6}}"), "纯数字应被补成 1d6")
        assertEquals("3", eval("{{roll::3d6}}"))
        assertEquals("4", eval("{{roll::1d6+3}}"))
    }

    @Test
    fun `roll 公式非法时返回空串而非原文`() {
        // 实测 droll：骰子数可省略（d6 合法）；0d6 / 1d0 / kh 都非法
        assertEquals("1", eval("{{roll::d6}}"))
        assertEquals("", eval("{{roll::0d6}}"))
        assertEquals("", eval("{{roll::1d0}}"))
        assertEquals("", eval("{{roll::2d6kh1}}"), "不支持 kh")
    }

    @Test
    fun `random 从列表里取`() {
        assertEquals("a", eval("{{random::a::b::c}}", defaultEnv(random = { 0.0 })))
        assertEquals("c", eval("{{random::a::b::c}}", defaultEnv(random = { 0.99 })))
        // 单参数写法按逗号拆
        assertEquals("a", eval("{{random:a,b,c}}", defaultEnv(random = { 0.0 })))
        assertEquals("b", eval("{{random a,b,c}}", defaultEnv(random = { 0.5 })))
        assertEquals("", eval("{{random::}}", defaultEnv(random = { 0.0 })))
    }

    // ------------------------------------------------------------ 控制流

    @Test
    fun `if 的基本分支`() {
        assertEquals("YES", eval("{{if::yes::YES}}"))
        assertEquals("", eval("{{if::false::HIDDEN}}"), "false 为假且无 else → 空")
        assertEquals("", eval("{{if::::EMPTY}}"), "空串为假")
        assertEquals("shown", eval("{{if::notamacro::shown}}"), "未知名字按字面量，非空即真")
    }

    @Test
    fun `if 的块写法与 else`() {
        assertEquals("THEN", eval("{{if yes}}THEN{{/if}}"))
        assertEquals("ELSE", eval("{{if false}}THEN{{else}}ELSE{{/if}}"))
        assertEquals("", eval("{{if false}}THEN{{/if}}"))
    }

    @Test
    fun `if 支持取反`() {
        assertEquals("NEG", eval("{{if !false}}NEG{{/if}}"))
        assertEquals("", eval("{{if !yes}}NEG{{/if}}"))
    }

    @Test
    fun `if 会把零参宏名展开`() {
        // char 是零参宏 ⇒ 先展开成 Seraphina，非空 ⇒ 真
        assertEquals("Name: Seraphina", eval("{{if char}}Name: {{char}}{{/if}}"))
        // noop 展开成空串 ⇒ 假
        assertEquals("[end]", eval("{{if noop}}X{{/if}}[end]"))
    }

    @Test
    fun `if 支持变量简写`() {
        val env = defaultEnv()
        eval("{{setvar::flag::on}}", env)
        assertEquals("FLAG", eval("{{if .flag}}FLAG{{/if}}", env))

        eval("{{setvar::off_flag::off}}", env)
        assertEquals("", eval("{{if .off_flag}}FLAG{{/if}}", env))
    }

    @Test
    fun `if 只求值被选中的分支`() {
        val env = defaultEnv()
        // 未被选中的分支里的 setvar 不应产生副作用
        eval("{{if false}}{{setvar::side}}v{{/setvar}}{{else}}Y{{/if}}", env)
        assertEquals("false", eval("{{hasvar::side}}", env))

        eval("{{if true}}{{setvar::side}}v{{/setvar}}{{/if}}", env)
        assertEquals("v", eval("{{getvar::side}}", env))
    }

    @Test
    fun `if 的假值只有空串与 offfalse0`() {
        assertEquals("", eval("{{if::off::X}}"))
        assertEquals("", eval("{{if::FALSE::X}}"))
        assertEquals("", eval("{{if::0::X}}"))
        // 这些都不是假
        assertEquals("X", eval("{{if::no::X}}"))
        assertEquals("X", eval("{{if::0.0::X}}"))
        assertEquals("X", eval("{{if::null::X}}"))
    }

    @Test
    fun `if 嵌套时外层 else 才切分`() {
        assertEquals(
            "OUTER-ELSE",
            eval("{{if false}}{{if true}}INNER{{else}}INNER-ELSE{{/if}}{{else}}OUTER-ELSE{{/if}}"),
        )
        assertEquals(
            "INNER",
            eval("{{if true}}{{if true}}INNER{{else}}INNER-ELSE{{/if}}{{else}}OUTER-ELSE{{/if}}"),
        )
    }

    // ------------------------------------------------------------ 缩进裁剪

    @Test
    fun `块内容按首个非空行去缩进`() {
        val template = "{{if true}}\n    line1\n      line2\n    {{/if}}"
        assertEquals("line1\n  line2", eval(template))
    }

    @Test
    fun `trimScopedContent 直接测`() {
        assertEquals("a\n  b", CoreMacros.trimScopedContent("\n    a\n      b\n"))
        assertEquals("x", CoreMacros.trimScopedContent("   x   "))
    }

    // ------------------------------------------------------------ 与其他机制的配合

    @Test
    fun `trim 后处理与内置宏共存`() {
        assertEquals("AB", eval("A\n{{trim}}\nB"))
    }

    @Test
    fun `未注册的宏原样保留`() {
        assertEquals("{{notAMacro}}", eval("{{notAMacro}}"))
        assertEquals("x{{notAMacro}}y", eval("x{{notAMacro}}y"))
    }

    @Test
    fun `全套装完后宏名集合合理`() {
        val registry = MacroRegistry()
        CoreMacros.install(registry)
        val names = registry.names

        for (expected in listOf(
            "space", "newline", "noop", "trim", "if", "else", "reverse",
            "char", "user", "group", "description", "personality", "scenario", "persona",
            "getvar", "setvar", "getglobalvar", "setglobalvar",
            "time", "date", "isotime", "isodate", "weekday",
            "random", "roll", "lastMessage", "lastUserMessage",
        )) {
            if (expected == "trim") continue // trim 是后处理正则，不是注册宏
            assertTrue(registry.has(expected), "缺少内置宏 $expected")
        }
    }

    // ------------------------------------------------------------ 会话宏

    @Test
    fun `会话宏取最近的消息并跳过系统消息`() {
        val chat = listOf(
            MacroChatMessage("first user", isUser = true),
            MacroChatMessage("char reply", isUser = false),
            MacroChatMessage("hidden", isUser = false, isSystem = true),
            MacroChatMessage("last user", isUser = true),
        )
        val env = defaultEnv(chat = chat)

        assertEquals("last user", eval("{{lastMessage}}", env))
        assertEquals("last user", eval("{{lastUserMessage}}", env))
        assertEquals("char reply", eval("{{lastCharMessage}}", env))
        assertEquals("3", eval("{{lastMessageId}}", env))
        assertEquals("0-3", eval("{{allChatRange}}", env))
    }

    @Test
    fun `空聊天时会话宏返回空串`() {
        assertEquals("", eval("{{lastMessage}}"))
        assertEquals("", eval("{{allChatRange}}"))
    }

    @Test
    fun `跳过 swipe 生成中的消息`() {
        val chat = listOf(
            MacroChatMessage("older", isUser = false),
            MacroChatMessage("generating", isUser = false, swipes = listOf("a"), swipeId = 5),
        )
        val env = defaultEnv(chat = chat)
        assertEquals("older", eval("{{lastMessage}}", env))
    }

    // ------------------------------------------------------------ 辅助

    private fun MacroEngine.evaluate(text: String, env: MacroEnv): String {
        this.env = env
        return evaluate(text)
    }
}
