package app.silly.core.prompt.assemble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 跨语言一致性测试：Kotlin 的模板渲染必须和**真实 Handlebars** 的结果一致。
 *
 * golden 由 `tools/gen-handlebars-goldens.mjs` 生成 —— 它用的是 ST 自己
 * `node_modules` 里的 handlebars，并且注册了 ST 的两个桥接 helper
 * （`public/scripts/macros.js:18-25`）。没有那两个 helper，
 * `{{trim}}` 和 `{{unknownMacro}}` 的行为就会与 ST 不同。
 *
 * 用例里包含 ST 自带的默认 `story_string` 与 Adventure 预设的 `story_string`，
 * 也就是真实数据，不是构造出来的玩具模板。
 */
class HandlebarsConformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val golden: JsonObject by lazy {
        val bytes = javaClass.getResourceAsStream("/handlebars-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/handlebars-goldens.json —— 请运行 `node tools/gen-handlebars-goldens.mjs`")
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private data class Case(
        val template: String,
        val variant: String,
        val output: String?,
        val error: String?,
        val params: Map<String, String>,
    )

    private val cases: List<Case> by lazy {
        (golden["cases"] as kotlinx.serialization.json.JsonArray).map { el ->
            val o = el.jsonObject
            Case(
                template = o["template"]!!.jsonPrimitive.content,
                variant = o["variant"]!!.jsonPrimitive.content,
                output = o["output"]?.jsonPrimitive?.content,
                error = o["error"]?.jsonPrimitive?.content,
                params = o["params"]!!.jsonObject
                    .mapValues { (_, v) -> v.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `用例数量合理且包含真实预设模板`() {
        assertTrue(cases.size >= 30, "用例太少：${cases.size}")
        assertTrue(cases.any { it.template.contains("anchorBefore") }, "应包含真实预设模板")
        assertTrue(cases.any { it.template == "{{trim}}" }, "应包含桥接 helper 用例")
    }

    @Test
    fun `每一条渲染结果都与真实 Handlebars 一致`() {
        val mismatches = mutableListOf<String>()

        for (case in cases) {
            if (case.error != null) continue // ST 会抛错的用例另行处理

            val actual = HandlebarsRenderer.render(case.template, case.params).text
            if (actual != case.output) {
                mismatches += buildString {
                    append("  模板 ").append(quote(case.template))
                    append(" [").append(case.variant).append("]\n")
                    append("    Handlebars: ").append(quote(case.output ?: "<null>")).append('\n')
                    append("    Kotlin    : ").append(quote(actual))
                }
            }
        }

        assertTrue(mismatches.isEmpty(), "渲染结果分歧：\n${mismatches.joinToString("\n")}")
    }

    // ------------------------------------------------------------ 关键行为

    @Test
    fun `未定义的变量原样留下交给宏引擎`() {
        // 这是 ST 的 helperMissing 行为，也是最容易写错的一点：
        // 裸 Handlebars 会把未知变量渲染成空串，ST 特意桥接成原样保留。
        assertEquals("{{trim}}", HandlebarsRenderer.render("{{trim}}", emptyMap()).text)
        assertEquals(
            "a{{unknownMacro}}b",
            HandlebarsRenderer.render("a{{unknownMacro}}b", emptyMap()).text,
        )
    }

    @Test
    fun `已定义的参数优先于宏透传`() {
        val params = mapOf("char" to "Seraphina")
        assertEquals("Seraphina", HandlebarsRenderer.render("{{char}}", params).text)
        assertEquals(
            "Seraphina/{{persona}}",
            HandlebarsRenderer.render("{{char}}/{{persona}}", params).text,
        )
    }

    @Test
    fun `参数存在但为空串仍然替换为空`() {
        val params = mapOf("persona" to "")
        assertEquals("", HandlebarsRenderer.render("{{persona}}", params).text)
    }

    @Test
    fun `真值规则与 Handlebars 一致`() {
        val t = "{{#if x}}Y{{else}}N{{/if}}"
        assertEquals("N", HandlebarsRenderer.render(t, mapOf("x" to "")).text)
        assertEquals("Y", HandlebarsRenderer.render(t, mapOf("x" to "0")).text, "非空字符串 \"0\" 为真")
        assertEquals("N", HandlebarsRenderer.render(t, emptyMap()).text)
    }

    @Test
    fun `unless 是 if 的反面`() {
        val t = "{{#unless x}}Y{{else}}N{{/unless}}"
        assertEquals("Y", HandlebarsRenderer.render(t, emptyMap()).text)
        assertEquals("N", HandlebarsRenderer.render(t, mapOf("x" to "v")).text)
    }

    @Test
    fun `嵌套块正确配对`() {
        val t = "{{#if a}}A{{#if b}}B{{/if}}C{{/if}}"
        assertEquals("ABC", HandlebarsRenderer.render(t, mapOf("a" to "1", "b" to "1")).text)
        assertEquals("AC", HandlebarsRenderer.render(t, mapOf("a" to "1")).text)
        assertEquals("", HandlebarsRenderer.render(t, emptyMap()).text)
    }

    @Test
    fun `三花括号与双花括号等价`() {
        val params = mapOf("char" to "X")
        assertEquals("X", HandlebarsRenderer.render("{{{char}}}", params).text)
    }

    // ------------------------------------------------------------ 告警

    @Test
    fun `未闭合的块给出警告且不丢内容`() {
        val r = HandlebarsRenderer.render("{{#if a}}CONTENT", mapOf("a" to "1"))

        assertEquals("CONTENT", r.text)
        assertTrue(r.warnings.any { it.contains("未闭合") }, "应警告未闭合：${r.warnings}")
    }

    @Test
    fun `不支持的块助手给出警告并按真处理`() {
        val r = HandlebarsRenderer.render("{{#each items}}X{{/each}}", mapOf("items" to "v"))

        assertEquals("X", r.text)
        assertTrue(r.warnings.any { it.contains("each") }, "应警告不支持的助手：${r.warnings}")
    }

    @Test
    fun `参数化宏被宽容放行并给出警告`() {
        // ST 在 Handlebars 阶段会因 `:` 非法而抛 ParseError；
        // 我们选择原样保留，让它能被后面的宏引擎处理
        val r = HandlebarsRenderer.render("{{random:a,b}}", emptyMap())

        assertEquals("{{random:a,b}}", r.text)
        assertTrue(r.warnings.any { it.contains("参数") }, "应警告参数化宏：${r.warnings}")
    }

    @Test
    fun `静态分析能提取模板变量`() {
        val vars = HandlebarsVariables.of("{{#if a}}{{b}}{{/if}}{{c}}")
        assertEquals(setOf("a", "b", "c"), vars)
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
