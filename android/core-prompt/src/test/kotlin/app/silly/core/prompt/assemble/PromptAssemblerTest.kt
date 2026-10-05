package app.silly.core.prompt.assemble

import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Prompt 组装的验收测试。
 *
 * 其中「默认顺序」一项是对着 fixture 断言的 —— fixture 由
 * `tools/gen-fixtures.mjs` 从 `PromptManager.js` 的
 * `promptManagerDefaultPromptOrder` **原文抽取**，
 * 这样 Kotlin 里的硬编码副本抄错了就会立刻暴露。
 */
class PromptAssemblerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(): JsonObject =
        javaClass.getResourceAsStream("/prompt-order/default.json")?.use { it.readBytes() }
            ?.let { json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
            ?: fail("找不到 fixtures/prompt-order/default.json —— 请运行 `node tools/gen-fixtures.mjs`")

    private fun user(text: String) = PromptMessage(PromptRole.USER, text)
    private fun assistant(text: String) = PromptMessage(PromptRole.ASSISTANT, text)

    // ------------------------------------------------------------ 默认顺序

    @Test
    fun `默认顺序与 ST 源码抽取出来的完全一致`() {
        val expected = (fixture()["entries"] as JsonArray).map { el ->
            val o = el.jsonObject
            PromptOrderEntry(
                identifier = o["identifier"]!!.jsonPrimitive.content,
                enabled = o["enabled"]!!.jsonPrimitive.boolean,
            )
        }

        assertEquals(expected, PromptOrder.DEFAULT)
    }

    @Test
    fun `默认顺序里几条容易记反的位置`() {
        val ids = PromptOrder.DEFAULT.map { it.identifier }

        assertTrue(ids.indexOf("worldInfoBefore") < ids.indexOf("charDescription"), "世界书 before 在角色描述之前")
        assertTrue(ids.indexOf("worldInfoAfter") > ids.indexOf("nsfw"), "世界书 after 在 nsfw 之后")
        assertTrue(ids.indexOf("chatHistory") > ids.indexOf("dialogueExamples"), "聊天历史在示例对话之后")
        assertTrue(ids.indexOf("jailbreak") > ids.indexOf("chatHistory"), "PHI 排在聊天历史**之后**")
        assertEquals("main", ids.first())
    }

    // ------------------------------------------------------------ 基本组装

    @Test
    fun `按顺序拼出消息且跳过空内容`() {
        val assembler = PromptAssembler()
        val result = assembler.assemble(
            sources = mapOf(
                "main" to "MAIN",
                "charDescription" to "DESC",
                "charPersonality" to "",        // 空 ⇒ 跳过
                "scenario" to "   ",            // 全空白 ⇒ 跳过
                "jailbreak" to "PHI",
            ),
            history = listOf(user("hi"), assistant("hello")),
        )

        assertEquals(
            listOf("MAIN", "DESC", "hi", "hello", "PHI"),
            result.map { it.content },
        )
    }

    @Test
    fun `角色分配符合预期`() {
        val assembler = PromptAssembler()
        val result = assembler.assemble(
            sources = mapOf(
                "main" to "MAIN",
                "worldInfoBefore" to "WI-B",
                "worldInfoAfter" to "WI-A",
                "charDescription" to "DESC",
            ),
            history = listOf(user("hi"), assistant("yo")),
        )

        // 默认顺序是 main → worldInfoBefore → …→ charDescription → … → worldInfoAfter → chatHistory
        assertEquals(
            listOf("MAIN", "WI-B", "DESC", "WI-A", "hi", "yo"),
            result.map { it.content },
        )
        assertEquals(
            listOf(
                PromptRole.SYSTEM, PromptRole.SYSTEM, PromptRole.SYSTEM,
                PromptRole.SYSTEM, PromptRole.USER, PromptRole.ASSISTANT,
            ),
            result.map { it.role },
        )

        assertEquals(PromptMessage.Source.SYSTEM_PROMPT, result[0].source)
        assertEquals(PromptMessage.Source.WORLD_INFO, result[1].source)
        assertEquals(PromptMessage.Source.WORLD_INFO, result[3].source)
    }

    @Test
    fun `未启用的条目不出现`() {
        val assembler = PromptAssembler(
            order = listOf(
                PromptOrderEntry("main", enabled = true),
                PromptOrderEntry("nsfw", enabled = false),
                PromptOrderEntry("jailbreak", enabled = true),
            ),
        )
        val result = assembler.assemble(mapOf("main" to "M", "nsfw" to "N", "jailbreak" to "J"))

        assertEquals(listOf("M", "J"), result.map { it.content })
    }

    @Test
    fun `chatHistory 不存在时历史被丢弃并给出可预期结果`() {
        val assembler = PromptAssembler(order = listOf(PromptOrderEntry("main")))
        val result = assembler.assemble(mapOf("main" to "M"), listOf(user("hi")))

        assertEquals(1, result.size, "顺序表里没有 chatHistory，历史就不会进结果")
    }

    @Test
    fun `空历史时只输出系统段`() {
        val result = PromptAssembler().assemble(mapOf("main" to "M"))

        assertEquals(listOf("M"), result.map { it.content })
    }

    @Test
    fun `顺序可以按角色自定义`() {
        val assembler = PromptAssembler(
            order = listOf(
                PromptOrderEntry("charDescription"),
                PromptOrderEntry("main"),
                PromptOrderEntry("chatHistory"),
            ),
        )
        val result = assembler.assemble(
            mapOf("main" to "M", "charDescription" to "D"),
            listOf(user("hi")),
        )

        assertEquals(listOf("D", "M", "hi"), result.map { it.content })
    }

    @Test
    fun `来源表里多余的标识符被忽略`() {
        val assembler = PromptAssembler(order = listOf(PromptOrderEntry("main")))
        val result = assembler.assemble(mapOf("main" to "M", "unknownThing" to "X"))

        assertEquals(listOf("M"), result.map { it.content })
    }

    // ------------------------------------------------------------ 深度注入

    @Test
    fun `depth 为 1 插在最后一条之前`() {
        val assembler = PromptAssembler()
        val result = assembler.assemble(
            sources = mapOf("main" to "M"),
            history = listOf(user("a"), assistant("b"), user("c")),
            depthInjections = listOf(DepthInjection(depth = 1, content = "INJECT")),
        )

        assertEquals(listOf("M", "a", "b", "INJECT", "c"), result.map { it.content })
    }

    @Test
    fun `depth 为 0 追加到最后`() {
        val result = PromptAssembler().assemble(
            sources = mapOf("main" to "M"),
            history = listOf(user("a")),
            depthInjections = listOf(DepthInjection(depth = 0, content = "TAIL")),
        )

        assertEquals(listOf("M", "a", "TAIL"), result.map { it.content })
    }

    @Test
    fun `depth 超出历史长度时钳制到最前`() {
        val result = PromptAssembler().assemble(
            sources = mapOf("main" to "M"),
            history = listOf(user("a")),
            depthInjections = listOf(DepthInjection(depth = 99, content = "HEAD")),
        )

        assertEquals(listOf("M", "HEAD", "a"), result.map { it.content })
    }

    @Test
    fun `多个深度注入按插入点排列`() {
        val result = PromptAssembler().assemble(
            sources = emptyMap(),
            history = listOf(user("1"), assistant("2"), user("3")),
            depthInjections = listOf(
                DepthInjection(depth = 1, content = "NEAR"),
                DepthInjection(depth = 3, content = "FAR"),
            ),
        )

        assertEquals(listOf("FAR", "1", "2", "NEAR", "3"), result.map { it.content })
    }

    @Test
    fun `深度注入的角色可指定`() {
        val result = PromptAssembler().assemble(
            sources = emptyMap(),
            history = listOf(user("a")),
            depthInjections = listOf(DepthInjection(1, "ASST", PromptRole.ASSISTANT)),
        )

        assertEquals(PromptRole.ASSISTANT, result[0].role)
    }

    @Test
    fun `空内容的注入被忽略`() {
        val result = PromptAssembler().assemble(
            sources = emptyMap(),
            history = listOf(user("a")),
            depthInjections = listOf(DepthInjection(1, ""), DepthInjection(1, "   ")),
        )

        assertEquals(listOf("a"), result.map { it.content })
    }

    // ------------------------------------------------------------ 与故事串衔接

    @Test
    fun `story_string 渲染结果可作为 main 直接喂进来`() {
        val storyString = StoryString.render(
            "{{#if description}}{{description}}\n{{/if}}{{#if scenario}}Scenario: {{scenario}}\n{{/if}}",
            StoryString.Params(description = "DESC", scenario = "SCEN"),
        ).text

        val result = PromptAssembler().assemble(
            sources = mapOf("main" to storyString),
            history = listOf(user("hi")),
        )

        assertEquals("DESC\nScenario: SCEN\n", result[0].content)
        assertEquals(PromptRole.SYSTEM, result[0].role)
    }

    @Test
    fun `完整链路：宏渲染 + 世界书 + 组装`() {
        // 1. 宏渲染角色描述
        val registry = app.silly.core.prompt.macro.MacroRegistry()
        app.silly.core.prompt.macro.CoreMacros.install(registry)
        val engine = app.silly.core.prompt.macro.MacroEngine(
            registry,
            app.silly.core.prompt.macro.MacroEnv(
                names = mapOf("char" to "Seraphina", "user" to "Traveler"),
            ),
        )

        val description = engine.evaluate("{{char}} is a scholar.")

        // 2. 世界书给出一段 before 文本
        val worldInfoBefore = "Dragons hoard memories."

        // 3. 组装
        val result = PromptAssembler().assemble(
            sources = mapOf(
                "main" to "You are {{char}}.".let { engine.evaluate(it) },
                "worldInfoBefore" to worldInfoBefore,
                "charDescription" to description,
            ),
            history = listOf(user("Hello"), assistant("Hi there")),
        )

        assertEquals(
            listOf(
                "You are Seraphina.",
                "Dragons hoard memories.",
                "Seraphina is a scholar.",
                "Hello",
                "Hi there",
            ),
            result.map { it.content },
        )
    }
}
