package app.haltija.core.prompt.assemble

import app.haltija.core.data.preset.ExtensionPromptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `story_string` 渲染管线的测试。
 *
 * 管线四步（`public/scripts/power-user.js:2234-2264`）：
 * Handlebars → ST 宏替换 → 去掉开头换行 → 按条件补末尾换行。
 * 每一步都有独立用例，顺序错了会立刻暴露。
 */
class StoryStringTest {

    private val defaultStoryString =
        "{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
            "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
            "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}"

    private fun params(
        description: String = "",
        personality: String = "",
        persona: String = "",
        scenario: String = "",
        system: String = "",
        char: String = "Seraphina",
        user: String = "Traveler",
        wiBefore: String = "",
        wiAfter: String = "",
    ) = StoryString.Params(
        description = description,
        personality = personality,
        persona = persona,
        scenario = scenario,
        system = system,
        char = char,
        user = user,
        wiBefore = wiBefore,
        wiAfter = wiAfter,
    )

    // ------------------------------------------------------------ 基本渲染

    @Test
    fun `ST 默认 story_string 的完整渲染`() {
        val r = StoryString.render(
            defaultStoryString,
            params(
                system = "Stay in character.",
                description = "A wandering scholar.",
                personality = "Curious.",
                scenario = "A library.",
                persona = "A traveler.",
            ),
        )

        assertEquals(
            "Stay in character.\n" +
                "A wandering scholar.\n" +
                "Seraphina's personality: Curious.\n" +
                "Scenario: A library.\n" +
                "A traveler.\n",
            r.text,
        )
        assertTrue(r.warnings.isEmpty(), "不该有警告：${r.warnings}")
    }

    @Test
    fun `缺参的块整段消失且不报错`() {
        val r = StoryString.render(defaultStoryString, params(description = "Only description."))

        assertEquals("Only description.\n", r.text)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `全部为空时输出空串`() {
        val r = StoryString.render(defaultStoryString, params(char = "", user = ""))
        assertEquals("", r.text)
    }

    // ------------------------------------------------------------ 管线次序

    @Test
    fun `宏替换发生在模板渲染之后`() {
        // {{trim}} 先被 Handlebars 原样保留，再被宏引擎处理
        var substituted: String? = null
        val r = StoryString.render(
            "A{{trim}}B",
            params(),
            substitute = { input ->
                substituted = input
                input.replace("{{trim}}", "")
            },
        )

        assertEquals("A{{trim}}B", substituted, "宏引擎应当拿到仍含 {{trim}} 的文本")
        assertEquals("AB\n", r.text, "渲染后还会补末尾换行")
    }

    @Test
    fun `开头的换行被去掉`() {
        val r = StoryString.render("\n\n\nCONTENT", params())
        assertEquals("CONTENT\n", r.text)
    }

    @Test
    fun `末尾没有换行时补一个`() {
        assertEquals("CONTENT\n", StoryString.render("CONTENT", params()).text)
    }

    @Test
    fun `已经有末尾换行时不重复补`() {
        assertEquals("CONTENT\n", StoryString.render("CONTENT\n", params()).text)
    }

    @Test
    fun `输出为空时不补换行`() {
        assertEquals("", StoryString.render("", params()).text)
        assertEquals("", StoryString.render("{{#if nothing}}x{{/if}}", params()).text)
    }

    // ------------------------------------------------------------ IN_CHAT 位置

    @Test
    fun `注入到聊天里时不补末尾换行`() {
        val r = StoryString.render(
            "CONTENT",
            params(),
            StoryString.Options(position = ExtensionPromptType.IN_CHAT),
        )
        assertEquals("CONTENT", r.text)
    }

    // ------------------------------------------------------------ 指令模板的影响

    @Test
    fun `指令模板启用且带 suffix 时不补末尾换行`() {
        val r = StoryString.render(
            "CONTENT",
            params(),
            StoryString.Options(
                instructEnabled = true,
                instructWrap = true,
                instructStoryStringSuffix = "\n### Response:",
            ),
        )
        assertEquals("CONTENT", r.text)
    }

    @Test
    fun `指令模板启用且 wrap 关闭时不补换行`() {
        // 规则是 `!enabled || (wrap && !suffix)`：
        // enabled 又有 suffix 时，只要 wrap 为假就不再补 —— 三个条件缺一不可
        val r = StoryString.render(
            "CONTENT",
            params(),
            StoryString.Options(
                instructEnabled = true,
                instructWrap = false,
                instructStoryStringSuffix = "\n### Response:",
            ),
        )
        assertEquals("CONTENT", r.text)
    }

    @Test
    fun `指令模板启用且 wrap 打开但无 suffix 时补换行`() {
        val r = StoryString.render(
            "CONTENT",
            params(),
            StoryString.Options(
                instructEnabled = true,
                instructWrap = true,
                instructStoryStringSuffix = "",
            ),
        )
        assertEquals("CONTENT\n", r.text)
    }

    @Test
    fun `指令模板未启用时补换行`() {
        val r = StoryString.render(
            "CONTENT",
            params(),
            StoryString.Options(
                instructEnabled = false,
                instructStoryStringSuffix = "\n### Response:",
            ),
        )
        assertEquals("CONTENT\n", r.text)
    }

    // ------------------------------------------------------------ 别名与告警

    @Test
    fun `loreBefore 是 wiBefore 的别名`() {
        val t = "{{loreBefore}}|{{loreAfter}}"
        val r = StoryString.render(t, params(wiBefore = "B", wiAfter = "A"))
        assertEquals("B|A\n", r.text)
    }

    @Test
    fun `引用不存在的变量时给出警告但不中断`() {
        val r = StoryString.render("{{#if nothing}}{{nope}}{{/if}}{{description}}", params(description = "D"))

        // {{nope}} 在块外才会输出，这里块为假所以不出现
        assertEquals("D\n", r.text)
        assertTrue(r.warnings.any { it.contains("nope") }, "应警告未知变量：${r.warnings}")
    }

    @Test
    fun `trim 不被当作缺失变量报告`() {
        val r = StoryString.render("A{{trim}}", params(), substitute = { it.replace("{{trim}}", "") })
        assertTrue(r.warnings.isEmpty(), "{{trim}} 是 ST 宏，不该被报成缺失变量：${r.warnings}")
    }

    // ------------------------------------------------------------ 真实预设

    @Test
    fun `Adventure 预设的 story_string 端到端`() {
        val template =
            "{{#if anchorBefore}}{{anchorBefore}}\n{{/if}}{{#if system}}{{system}}\n{{/if}}" +
                "{{#if wiBefore}}{{wiBefore}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
                "{{#if personality}}{{personality}}\n{{/if}}{{#if scenario}}{{scenario}}\n{{/if}}" +
                "{{#if wiAfter}}{{wiAfter}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}" +
                "{{#if anchorAfter}}{{anchorAfter}}\n{{/if}}{{trim}}"

        val r = StoryString.render(
            template,
            params(
                system = "SYS",
                description = "DESC",
                personality = "PERS",
                scenario = "SCEN",
                persona = "PERSONA",
                wiBefore = "WIB",
                wiAfter = "WIA",
            ),
            substitute = { it.replace("{{trim}}", "") },
        )

        assertEquals("SYS\nWIB\nDESC\nPERS\nSCEN\nWIA\nPERSONA\n", r.text)
    }
}
