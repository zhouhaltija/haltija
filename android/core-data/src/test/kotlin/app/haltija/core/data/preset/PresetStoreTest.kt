package app.haltija.core.data.preset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 预设读写的验收测试。
 *
 * 两类样本配合使用：
 *  - `fixtures/presets/<kind>/` 里 ST 自带的预设 —— 保证读得懂真实数据；
 *  - `AllFields.json` —— **每个字段都设成非默认值**，保证访问器真的读到了那个键。
 *
 * 后者不是形式主义：如果 fixture 缺某个键，访问器即使把键名拼错，
 * 也会因为「退回默认值」而让测试通过。只有值可辨识时才测得出拼写错误。
 */
class PresetStoreTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun text(path: String): String =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    private fun context(name: String) = PresetStore.parse(PresetKind.CONTEXT, text("presets/context/$name.json"), name)
    private fun instruct(name: String) = PresetStore.parse(PresetKind.INSTRUCT, text("presets/instruct/$name.json"), name)
    private fun sysprompt(name: String) = PresetStore.parse(PresetKind.SYSPROMPT, text("presets/sysprompt/$name.json"), name)
    private fun reasoning(name: String) = PresetStore.parse(PresetKind.REASONING, text("presets/reasoning/$name.json"), name)

    // ------------------------------------------------------------ 类型分发

    @Test
    fun `按类型分发到正确的实现`() {
        assertTrue(context("Adventure") is ContextTemplate)
        assertTrue(instruct("Adventure") is InstructTemplate)
        assertTrue(sysprompt("Actor") is SystemPrompt)
        assertTrue(reasoning("Blank") is ReasoningTemplate)
    }

    @Test
    fun `默认值取自 ST 的 power-user 定义`() {
        // Blank reasoning 只有 name，其余字段全都走默认
        val blank = reasoning("Blank") as ReasoningTemplate
        assertEquals("Blank", blank.name)
        assertEquals("", blank.prefix)
        assertEquals("", blank.suffix)
        assertEquals("", blank.separator)

        assertTrue(ContextTemplate.DEFAULT_STORY_STRING.contains("{{#if personality}}"))
        assertEquals("***", ContextTemplate.DEFAULT_SEPARATOR)
    }

    // ------------------------------------------------------------ 全字段覆盖

    @Test
    fun `ContextTemplate 每个字段都被读到（不存在键名拼错）`() {
        val c = context("AllFields") as ContextTemplate

        assertEquals("AllFields Context", c.name)
        assertEquals("AllFields", c.fileName)
        assertEquals("SYS={{system}}|WI={{wiBefore}}|DESC={{description}}|T={{trim}}", c.storyString)
        assertEquals("<<EX>>", c.exampleSeparator)
        assertEquals("<<START>>", c.chatStart)
        assertFalse(c.useStopStrings, "use_stop_strings 默认是 true，这里必须是 false 才说明读到了")
        assertFalse(c.namesAsStopStrings)
        assertEquals(1, c.storyStringPosition, "默认是 0，读到 1 才说明键名正确")
        assertEquals(7, c.storyStringDepth, "默认是 1")
        assertEquals(2, c.storyStringRole, "默认是 0")
        assertTrue(c.alwaysForceName2, "默认是 false")
        assertTrue(c.trimSentences, "默认是 false")
        assertTrue(c.singleLine, "默认是 false")
    }

    @Test
    fun `InstructTemplate 每个字段都被读到`() {
        val i = instruct("AllFields") as InstructTemplate

        assertEquals("AllFields Instruct", i.name)
        assertEquals("<IN>", i.inputSequence)
        assertEquals("<OUT>", i.outputSequence)
        assertEquals("<LASTOUT>", i.lastOutputSequence)
        assertEquals("<SYS>", i.systemSequence)
        assertEquals("<STOP>", i.stopSequence)
        assertFalse(i.wrap, "默认是 true")
        assertFalse(i.macro, "默认是 true")
        assertEquals(NamesBehavior.ALWAYS, i.namesBehavior, "默认是 none")
        assertEquals("thinking", i.activationRegex)
        assertEquals("<FIRSTOUT>", i.firstOutputSequence)
        assertTrue(i.skipExamples, "默认是 false")
        assertEquals("<OUTSUF>", i.outputSuffix)
        assertEquals("<INSUF>", i.inputSuffix)
        assertEquals("<SYSSUF>", i.systemSuffix)
        assertEquals("<ALIGN>", i.userAlignmentMessage)
        assertTrue(i.systemSameAsUser, "默认是 false")
        assertEquals("<LASTSYS>", i.lastSystemSequence)
        assertEquals("<FIRSTIN>", i.firstInputSequence)
        assertEquals("<LASTIN>", i.lastInputSequence)
        assertFalse(i.sequencesAsStopStrings, "默认是 true")
        assertEquals("<PREFIX>", i.storyStringPrefix)
        assertEquals("<SUFFIX>", i.storyStringSuffix)
    }

    @Test
    fun `SystemPrompt 每个字段都被读到`() {
        val s = sysprompt("AllFields") as SystemPrompt

        assertEquals("AllFields SysPrompt", s.name)
        assertEquals("<CONTENT>", s.content)
        assertEquals("<POSTHISTORY>", s.postHistory)
    }

    @Test
    fun `ReasoningTemplate 每个字段都被读到`() {
        val r = reasoning("AllFields") as ReasoningTemplate

        assertEquals("AllFields Reasoning", r.name)
        assertEquals("<PRE>", r.prefix)
        assertEquals("<SUF>", r.suffix)
        assertEquals("<SEP>", r.separator)
    }

    // ------------------------------------------------------------ 真实预设

    @Test
    fun `ST 自带的 DeepSeek 思维链模板`() {
        val r = reasoning("DeepSeek") as ReasoningTemplate

        assertEquals("<think>\n", r.prefix)
        assertEquals("\n</think>", r.suffix)
        assertEquals("\n\n", r.separator)
    }

    @Test
    fun `instructions 的 names_behavior 被正确区分`() {
        assertEquals(NamesBehavior.NONE, (instruct("Adventure") as InstructTemplate).namesBehavior)
        assertEquals(NamesBehavior.FORCE, (instruct("Alpaca-Single-Turn") as InstructTemplate).namesBehavior)
    }

    @Test
    fun `sysprompt 的 content 保留宏占位`() {
        val actor = sysprompt("Actor") as SystemPrompt

        assertTrue(actor.content.contains("{{char}}"))
        assertTrue(actor.content.contains("{{user}}"))
        assertEquals("", actor.postHistory)
    }

    // ------------------------------------------------------------ Handlebars 分析

    @Test
    fun `从 story_string 中解析出模板变量`() {
        val vars = (context("Adventure") as ContextTemplate).templateVariables

        assertEquals(
            linkedSetOf(
                "anchorBefore", "system", "wiBefore", "description", "personality",
                "scenario", "wiAfter", "persona", "anchorAfter", "trim",
            ),
            vars,
        )
    }

    @Test
    fun `Handlebars 变量解析的各种写法`() {
        assertEquals(setOf("x"), HandlebarsTemplate.variables("{{x}}"))
        assertEquals(setOf("x"), HandlebarsTemplate.variables("{{#if x}}{{/if}}"))
        assertEquals(setOf("x"), HandlebarsTemplate.variables("{{{x}}}"))
        assertEquals(setOf("x"), HandlebarsTemplate.variables("{{^x}}"))
        assertEquals(emptySet(), HandlebarsTemplate.variables("{{else}}"))
        assertEquals(emptySet(), HandlebarsTemplate.variables("没有模板语法"))
        assertEquals(setOf("a", "b"), HandlebarsTemplate.variables("{{a}} {{ b }}"))
        // 保留顺序去重
        assertEquals(listOf("a", "b"), HandlebarsTemplate.variables("{{a}}{{b}}{{a}}").toList())
    }

    @Test
    fun `识别是否为模板`() {
        assertTrue(HandlebarsTemplate.isTemplate("{{x}}"))
        assertFalse(HandlebarsTemplate.isTemplate("plain text"))
    }

    // ------------------------------------------------------------ 目录与往返

    @Test
    fun `无损往返：解析后重新序列化，结构不变`() {
        for (kind in PresetKind.entries) {
            for (name in listOf("AllFields")) {
                val original = PresetStore.parse(kind, text("presets/${kind.directory}/$name.json"), name)
                val reread = PresetStore.parse(kind, PresetStore.encode(original), name)
                assertEquals(original.toJsonObject(), reread.toJsonObject(), "$kind 往返后结构变了")
            }
        }
    }

    @Test
    fun `读取目录时按文件名排序且只取 json`() {
        val dir = java.nio.file.Files.createTempDirectory("haltija-preset-test")
        java.nio.file.Files.writeString(dir.resolve("b.json"), """{"name":"B"}""")
        java.nio.file.Files.writeString(dir.resolve("a.json"), """{"name":"A"}""")
        java.nio.file.Files.writeString(dir.resolve("note.txt"), "ignore me")

        val presets = PresetStore.readDirectory(dir)

        assertEquals(listOf("a", "b"), presets.map { it.fileName })
        java.nio.file.Files.deleteIfExists(dir.resolve("a.json"))
        java.nio.file.Files.deleteIfExists(dir.resolve("b.json"))
        java.nio.file.Files.deleteIfExists(dir.resolve("note.txt"))
        java.nio.file.Files.deleteIfExists(dir)
    }

    @Test
    fun `readAll 从用户数据目录定位预设`() {
        val root = java.nio.file.Files.createTempDirectory("haltija-userdata")
        val dir = root.resolve("presets").resolve("sysprompt")
        java.nio.file.Files.createDirectories(dir)
        java.nio.file.Files.writeString(dir.resolve("Mine.json"), """{"name":"Mine","content":"hi"}""")

        val found = PresetStore.readAll(root, PresetKind.SYSPROMPT)

        assertEquals(1, found.size)
        assertEquals("Mine", found[0].name)
        assertTrue(found[0] is SystemPrompt)
        java.nio.file.Files.deleteIfExists(dir.resolve("Mine.json"))
    }

    @Test
    fun `未知字段在往返中保留`() {
        val text = """{"name":"X","content":"y","未来的字段":{"a":1}}"""
        val p = PresetStore.parse(PresetKind.SYSPROMPT, text)

        assertNotNull(p.raw["未来的字段"], "未知字段不能丢")
        assertEquals(json.parseToJsonElement(text), p.toJsonObject())
    }

    @Test
    fun `损坏的预设给出明确错误`() {
        val e = kotlin.test.assertFailsWith<PresetFormatException> {
            PresetStore.parse(PresetKind.CONTEXT, "不是 json")
        }
        assertTrue(e.message!!.contains("合法 JSON"))
    }

    // ------------------------------------------------------------ 枚举

    @Test
    fun `枚举取值与 ST 一致`() {
        assertEquals(-1, ExtensionPromptType.NONE.value)
        assertEquals(0, ExtensionPromptType.IN_PROMPT.value)
        assertEquals(1, ExtensionPromptType.IN_CHAT.value)
        assertEquals(2, ExtensionPromptType.BEFORE_PROMPT.value)
        assertNull(ExtensionPromptType.from(42))

        assertEquals(0, ExtensionPromptRole.SYSTEM.value)
        assertEquals(1, ExtensionPromptRole.USER.value)
        assertEquals(2, ExtensionPromptRole.ASSISTANT.value)

        assertEquals("none", NamesBehavior.NONE.value)
        assertEquals("force", NamesBehavior.FORCE.value)
        assertEquals("always", NamesBehavior.ALWAYS.value)
    }
}
