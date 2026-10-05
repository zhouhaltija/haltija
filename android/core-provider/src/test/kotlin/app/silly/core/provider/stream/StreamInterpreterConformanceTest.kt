package app.silly.core.provider.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 流式解析的跨语言一致性测试。
 *
 * golden 由 `tools/gen-streaming-goldens.mjs` 生成 —— 它把 ST 的
 * `getStreamingReply`（`public/scripts/openai.js:3222-3306`）**逐字抽取**出来，
 * 只桩掉 `chat_completion_sources` / `isDataURL` / `oai_settings` 三样外部依赖，
 * 然后喂同一批 JSON 得到期望值。
 *
 * 这样就能确认「同一份 API 响应，我的解析结果和 ST 一模一样」——
 * 包括那些各家不一样的思维链字段名（DeepSeek 的 `reasoning_content`、
 * OpenRouter 的 `reasoning`、Anthropic 的 `thinking`、Gemini 的 `thought` part）。
 *
 * 未覆盖：`cohere` / `mistralai` —— 本项目不支持这两家，用例保留在 golden 里
 * 但没有对应的 Kotlin 实现。
 */
class StreamInterpreterConformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val golden: JsonObject by lazy {
        val bytes = javaClass.getResourceAsStream("/streaming-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/streaming-goldens.json —— 请运行 `node tools/gen-streaming-goldens.mjs`")
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private data class Case(
        val source: String,
        val desc: String,
        val data: JsonObject,
        val text: String,
        val reasoning: String,
        val images: List<String>,
    )

    private val cases: List<Case> by lazy {
        (golden["cases"] as JsonArray).map { element ->
            val o = element.jsonObject
            Case(
                source = o["source"]!!.jsonPrimitive.content,
                desc = o["desc"]!!.jsonPrimitive.content,
                data = o["data"]!!.jsonObject,
                text = o["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                images = (o["images"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
        }
    }

    /** ST 那边的 source 名 → 本项目的解释器。 */
    private fun interpreterFor(source: String): StreamInterpreter? = when (source) {
        "openai", "deepseek", "xai", "openrouter", "custom", "groq", "nanogpt" ->
            OpenAiStreamInterpreter()
        "claude" -> AnthropicStreamInterpreter()
        "makersuite", "vertexai" -> GeminiStreamInterpreter()
        else -> null
    }

    private fun interpret(case: Case): StreamDelta {
        val interpreter = interpreterFor(case.source)
            ?: fail("没有对应实现：${case.source}")
        // 直接把 data 当 SSE 事件喂进去；Anthropic 的 event 行不影响解析
        // （它同时看 data.type，与我们抽取的 ST 实现吃的是同一份 JSON）
        val event = SseEvent(event = case.source, data = case.data.toString())
        return interpreter.interpret(event) ?: StreamDelta.EMPTY
    }

    @Test
    fun `用例数量合理且覆盖三家`() {
        assertTrue(cases.size >= 15, "用例太少：${cases.size}")
        assertTrue(cases.any { it.source == "claude" })
        assertTrue(cases.any { it.source == "makersuite" })
        assertTrue(cases.any { it.source == "deepseek" })
    }

    @Test
    fun `每一条解析结果都与 ST 一致`() {
        val mismatches = mutableListOf<String>()

        for (case in cases) {
            if (interpreterFor(case.source) == null) continue // cohere / mistralai 未实现

            val actual = interpret(case)
            if (actual.text != case.text || actual.reasoning != case.reasoning ||
                actual.images != case.images
            ) {
                mismatches += buildString {
                    append("  [").append(case.source).append("] ").append(case.desc).append('\n')
                    append("    ST     : text=").append(quote(case.text))
                    append(" reasoning=").append(quote(case.reasoning))
                    append(" images=").append(case.images.size).append('\n')
                    append("    Kotlin : text=").append(quote(actual.text))
                    append(" reasoning=").append(quote(actual.reasoning))
                    append(" images=").append(actual.images.size)
                }
            }
        }

        assertTrue(mismatches.isEmpty(), "流式解析分歧：\n${mismatches.joinToString("\n")}")
    }

    // ------------------------------------------------------------ 关键行为

    @Test
    fun `OpenAI 家的思维链字段名各家不同`() {
        fun text(source: String, payload: String): StreamDelta =
            interpreterFor(source)!!.interpret(SseEvent(null, payload)) ?: StreamDelta.EMPTY

        assertEquals("想", text("deepseek", """{"choices":[{"delta":{"reasoning_content":"想"}}]}""").reasoning)
        assertEquals("想", text("openrouter", """{"choices":[{"delta":{"reasoning":"想"}}]}""").reasoning)
        assertEquals("答", text("openai", """{"choices":[{"delta":{"content":"答"}}]}""").text)
    }

    @Test
    fun `Anthropic 的 event 名字与 data 里的 type 都能识别`() {
        val interpreter = AnthropicStreamInterpreter()

        // 有 event 行
        assertEquals(
            "Hi",
            interpreter.interpret(
                SseEvent(
                    "content_block_delta",
                    """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""",
                ),
            )?.text,
        )
        // 没有 event 行，只看 data.type
        assertEquals(
            "Hi",
            interpreter.interpret(
                SseEvent(
                    null,
                    """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""",
                ),
            )?.text,
        )
        // 结束
        assertEquals(true, interpreter.interpret(SseEvent("message_stop", """{"type":"message_stop"}"""))?.done)
    }

    @Test
    fun `Gemini 用 part 上的 thought 标记区分思维链`() {
        val interpreter = GeminiStreamInterpreter()
        val delta = interpreter.interpret(
            SseEvent(
                null,
                """{"candidates":[{"content":{"parts":[{"text":"想","thought":true},{"text":"答"}]}}]}""",
            ),
        )

        assertEquals("答", delta?.text)
        assertEquals("想", delta?.reasoning)
    }

    @Test
    fun `Gemini 的 inlineData 变成 data URL`() {
        val interpreter = GeminiStreamInterpreter()
        val delta = interpreter.interpret(
            SseEvent(null, """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"AAAA"}}]}}]}"""),
        )

        assertEquals(listOf("data:image/png;base64,AAAA"), delta?.images)
    }

    // ------------------------------------------------------------ 事件流整体

    @Test
    fun `按 SSE 事件流解析 OpenAI 响应能拼出完整文本`() {
        val stream = buildString {
            append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }

        val result = consume(stream, OpenAiStreamInterpreter())
        assertEquals("你好", result.text)
        assertEquals("先想", result.reasoning)
        assertTrue(result.done)
    }

    @Test
    fun `按 SSE 事件流解析 Anthropic 响应能拼出完整文本`() {
        val stream = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"想一想\"}}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }

        val result = consume(stream, AnthropicStreamInterpreter())
        assertEquals("答案", result.text)
        assertEquals("想一想", result.reasoning)
        assertTrue(result.done)
    }

    @Test
    fun `按 SSE 事件流解析 Gemini 响应能拼出完整文本`() {
        val stream = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gem\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ini\"}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"!\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        }

        val result = consume(stream, GeminiStreamInterpreter())
        assertEquals("Gemini!", result.text)
    }

    @Test
    fun `分片不影响流式解析结果`() {
        val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"ab\"}}]}\n\ndata: [DONE]\n\n"

        assertEquals(
            consume(stream, OpenAiStreamInterpreter()),
            consume(stream.chunked(1).joinToString(""), OpenAiStreamInterpreter()),
        )
    }

    /** 把整段 SSE 走一遍解析器 + 解释器，返回累积结果。 */
    private fun consume(stream: String, interpreter: StreamInterpreter): StreamDelta {
        val parser = SseParser()
        var acc = StreamDelta.EMPTY
        for (event in parser.feed(stream) + parser.finish()) {
            interpreter.interpret(event)?.let { acc += it }
        }
        return acc
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
