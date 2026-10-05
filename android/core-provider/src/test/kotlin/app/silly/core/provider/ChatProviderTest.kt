package app.silly.core.provider

import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 三家 provider 的请求构造测试。
 *
 * 重点不在「字段名对不对」，而在**三家不一致的地方**：
 * system 放哪里、助手角色叫什么、鉴权走哪个头、连续消息要不要合并。
 * 这些写错会直接 400，而且错误信息通常很难指向真正的原因。
 */
class ChatProviderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun body(request: ProviderRequest): JsonObject =
        json.parseToJsonElement(request.body).jsonObject

    private fun messages(
        system: String? = null,
        vararg turns: Pair<PromptRole, String>,
    ): List<PromptMessage> = buildList {
        system?.let { add(PromptMessage(PromptRole.SYSTEM, it)) }
        turns.forEach { (role, text) -> add(PromptMessage(role, text)) }
    }

    private val options = GenerationOptions(
        model = "test-model",
        maxTokens = 512,
        temperature = 0.7,
        stop = listOf("###"),
    )

    // ------------------------------------------------------------ OpenAI 兼容

    @Test
    fun `OpenAI 请求的 URL 与鉴权头`() {
        val provider = OpenAiCompatibleProvider("https://api.example.com/v1/", "sk-key")
        val request = provider.buildRequest(messages(null, PromptRole.USER to "hi"), options)

        assertEquals("https://api.example.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test
    fun `OpenAI 把 system 当成一条普通消息`() {
        val provider = OpenAiCompatibleProvider("https://x/v1", "k")
        val request = provider.buildRequest(messages("SYS", PromptRole.USER to "hi"), options)
        val body = body(request)

        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(512, body["max_tokens"]?.jsonPrimitive?.content?.toInt())

        val list = body["messages"]!!.jsonArray
        assertEquals(2, list.size)
        assertEquals("system", list[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("SYS", list[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", list[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenAI 可以改用 max_completion_tokens`() {
        val provider = OpenAiCompatibleProvider("https://x/v1", "k")
        val request = provider.buildRequest(
            messages(null, PromptRole.USER to "hi"),
            options.copy(useMaxCompletionTokens = true),
        )
        val body = body(request)

        assertEquals(512, body["max_completion_tokens"]?.jsonPrimitive?.content?.toInt())
        assertNull(body["max_tokens"], "两者不应同时出现")
    }

    @Test
    fun `OpenAI 的 OpenRouter 附加头`() {
        val provider = OpenAiCompatibleProvider(
            "https://openrouter.ai/api/v1", "k",
            referer = "https://my.app", title = "My App",
        )
        val request = provider.buildRequest(messages(null, PromptRole.USER to "hi"), options)

        assertEquals("https://my.app", request.headers["HTTP-Referer"])
        assertEquals("My App", request.headers["X-Title"])
    }

    @Test
    fun `OpenAI 的 stop 序列`() {
        val provider = OpenAiCompatibleProvider("https://x/v1", "k")
        val body = body(provider.buildRequest(messages(null, PromptRole.USER to "hi"), options))

        assertEquals(listOf("###"), body["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    // ------------------------------------------------------------ Anthropic

    @Test
    fun `Anthropic 请求的 URL 与鉴权头`() {
        val provider = AnthropicProvider("https://api.anthropic.com/", "sk-ant")
        val request = provider.buildRequest(messages(null, PromptRole.USER to "hi"), options)

        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertNull(request.headers["Authorization"], "Anthropic 不用 Bearer")
    }

    @Test
    fun `Anthropic 把 system 抽到顶层而不是放进 messages`() {
        val provider = AnthropicProvider("https://x", "k")
        val body = body(
            provider.buildRequest(
                messages("SYS-A", PromptRole.USER to "hi", PromptRole.ASSISTANT to "yo"),
                options,
            ),
        )

        assertEquals("SYS-A", body["system"]?.jsonPrimitive?.content)
        val list = body["messages"]!!.jsonArray
        assertEquals(2, list.size)
        assertTrue(
            list.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" },
            "messages 里出现 system 会被 Anthropic 拒绝",
        )
    }

    @Test
    fun `Anthropic 多段 system 用空行连接`() {
        val provider = AnthropicProvider("https://x", "k")
        val list = listOf(
            PromptMessage(PromptRole.SYSTEM, "A"),
            PromptMessage(PromptRole.SYSTEM, "B"),
            PromptMessage(PromptRole.USER, "hi"),
        )
        val body = body(provider.buildRequest(list, options))

        assertEquals("A\n\nB", body["system"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Anthropic 合并连续的同一角色消息`() {
        val provider = AnthropicProvider("https://x", "k")
        val list = listOf(
            PromptMessage(PromptRole.USER, "part1"),
            PromptMessage(PromptRole.USER, "part2"),
            PromptMessage(PromptRole.ASSISTANT, "reply"),
            PromptMessage(PromptRole.ASSISTANT, "more"),
        )
        val body = body(provider.buildRequest(list, options))
        val wire = body["messages"]!!.jsonArray

        assertEquals(2, wire.size, "相邻同角色必须合并，否则 Anthropic 报错")
        assertEquals("part1\npart2", wire[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("reply\nmore", wire[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Anthropic 用 stop_sequences 而不是 stop`() {
        val provider = AnthropicProvider("https://x", "k")
        val body = body(provider.buildRequest(messages(null, PromptRole.USER to "hi"), options))

        assertNull(body["stop"], "Anthropic 不认 stop")
        assertEquals(listOf("###"), body["stop_sequences"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `Anthropic 没有 system 时不写该字段`() {
        val provider = AnthropicProvider("https://x", "k")
        val body = body(provider.buildRequest(messages(null, PromptRole.USER to "hi"), options))

        assertNull(body["system"])
    }

    // ------------------------------------------------------------ Gemini

    @Test
    fun `Gemini 把模型名放进 URL 且流式要 alt=sse`() {
        val provider = GeminiProvider("https://generativelanguage.googleapis.com/v1beta/", "AIza-key")
        val request = provider.buildRequest(messages(null, PromptRole.USER to "hi"), options)

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/test-model:streamGenerateContent?alt=sse&key=AIza-key",
            request.url,
        )
        assertNull(request.headers["Authorization"], "Gemini 用查询参数鉴权")
    }

    @Test
    fun `Gemini 非流式的端点不同`() {
        val provider = GeminiProvider("https://x/v1beta", "k")
        val request = provider.buildRequest(
            messages(null, PromptRole.USER to "hi"),
            options.copy(stream = false),
        )

        assertTrue(request.url.contains(":generateContent?"), "非流式不应带 alt=sse：${request.url}")
        assertTrue(!request.url.contains("alt=sse"))
    }

    @Test
    fun `Gemini 的助手角色叫 model`() {
        val provider = GeminiProvider("https://x/v1beta", "k")
        val body = body(
            provider.buildRequest(
                messages(null, PromptRole.USER to "hi", PromptRole.ASSISTANT to "yo"),
                options,
            ),
        )

        val contents = body["contents"]!!.jsonArray
        assertEquals("user", contents[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("model", contents[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Gemini 的 system 走 systemInstruction`() {
        val provider = GeminiProvider("https://x/v1beta", "k")
        val body = body(provider.buildRequest(messages("SYS", PromptRole.USER to "hi"), options))

        val instruction = body["systemInstruction"]!!.jsonObject
        assertEquals(
            "SYS",
            instruction["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
        assertTrue(
            (body["contents"] as JsonArray).none {
                it.jsonObject["role"]?.jsonPrimitive?.content == "system"
            },
            "system 不应出现在 contents 里",
        )
    }

    @Test
    fun `Gemini 的生成参数放在 generationConfig`() {
        val provider = GeminiProvider("https://x/v1beta", "k")
        val body = body(provider.buildRequest(messages(null, PromptRole.USER to "hi"), options))
        val config = body["generationConfig"]!!.jsonObject

        assertEquals(512, config["maxOutputTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0.7, config["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(listOf("###"), config["stopSequences"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `Gemini 的文本放在 parts 里`() {
        val provider = GeminiProvider("https://x/v1beta", "k")
        val body = body(provider.buildRequest(messages(null, PromptRole.USER to "hi"), options))

        val first = body["contents"]!!.jsonArray[0].jsonObject
        assertEquals(
            "hi",
            first["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
    }

    // ------------------------------------------------------------ 非流式取正文

    @Test
    fun `非流式响应取正文`() {
        val openai = OpenAiCompatibleProvider("https://x/v1", "k")
        assertEquals(
            "Hello",
            openai.extractContent(
                json.parseToJsonElement("""{"choices":[{"message":{"content":"Hello"}}]}""").jsonObject,
            ),
        )

        val anthropic = AnthropicProvider("https://x", "k")
        assertEquals(
            "Hello",
            anthropic.extractContent(
                json.parseToJsonElement(
                    """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"Hello"}]}""",
                ).jsonObject,
            ),
        )

        val gemini = GeminiProvider("https://x/v1beta", "k")
        assertEquals(
            "Hello",
            gemini.extractContent(
                json.parseToJsonElement(
                    """{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"Hello"}]}}]}""",
                ).jsonObject,
            ),
        )
    }

    @Test
    fun `三种请求体都能被 JSON 解析且不含多余空字段`() {
        val providers = listOf(
            OpenAiCompatibleProvider("https://x/v1", "k"),
            AnthropicProvider("https://x", "k"),
            GeminiProvider("https://x/v1beta", "k"),
        )
        val minimal = GenerationOptions(model = "m", maxTokens = 16, stop = emptyList())
        val msgs = messages(null, PromptRole.USER to "hi")

        for (provider in providers) {
            val request = provider.buildRequest(msgs, minimal)
            val parsed = body(request)
            assertTrue(parsed.isNotEmpty(), "${provider.id} 的请求体不应为空")
            assertNull(parsed["temperature"], "${provider.id} 未设置温度时不应写入")
            assertNull(parsed["top_p"])
        }
    }

    @Test
    fun `interpreter 与 provider 配套`() {
        assertEquals("openai", OpenAiCompatibleProvider("https://x/v1", "k").id)
        assertEquals("anthropic", AnthropicProvider("https://x", "k").id)
        assertEquals("gemini", GeminiProvider("https://x/v1beta", "k").id)

        assertTrue(OpenAiCompatibleProvider("https://x/v1", "k").createInterpreter() is app.silly.core.provider.stream.OpenAiStreamInterpreter)
        assertTrue(AnthropicProvider("https://x", "k").createInterpreter() is app.silly.core.provider.stream.AnthropicStreamInterpreter)
        assertTrue(GeminiProvider("https://x/v1beta", "k").createInterpreter() is app.silly.core.provider.stream.GeminiStreamInterpreter)
    }
}
