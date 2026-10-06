package app.haltija.core.provider.transport

import app.haltija.core.prompt.model.PromptMessage
import app.haltija.core.prompt.model.PromptRole
import app.haltija.core.provider.AnthropicProvider
import app.haltija.core.provider.GeminiProvider
import app.haltija.core.provider.GenerationOptions
import app.haltija.core.provider.OpenAiCompatibleProvider
import app.haltija.core.provider.ProviderRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 编排层的测试。
 *
 * [ProviderHttpClient] 是接口，所以这里用一个假客户端把**预先录好的响应**按指定粒度
 * 推回去 —— 整套「构造请求 → 收流 → 解析 → 产出增量」的流程都能确定性验证，
 * 不需要联网，也不需要 Android。
 */
class ChatServiceTest {

    /** 假客户端：把预设的响应按 [chunkSize] 切片推给调用方。 */
    private class FakeClient(
        private val chunks: List<String>,
        private val chunkSize: Int = Int.MAX_VALUE,
        private val error: ProviderHttpException? = null,
        private val fullBody: String = "",
    ) : ProviderHttpClient {

        var lastRequest: ProviderRequest? = null
            private set

        override suspend fun stream(request: ProviderRequest, onChunk: suspend (String) -> Unit) {
            lastRequest = request
            error?.let { throw it }
            // 把预设内容重新按 chunkSize 切开，模拟网络分片
            val all = chunks.joinToString("")
            all.chunked(chunkSize).forEach { onChunk(it) }
        }

        override suspend fun execute(request: ProviderRequest): String {
            lastRequest = request
            error?.let { throw it }
            return fullBody
        }
    }

    private val messages = listOf(
        PromptMessage(PromptRole.SYSTEM, "SYS"),
        PromptMessage(PromptRole.USER, "hi"),
    )

    private val options = GenerationOptions(model = "m", maxTokens = 64)

    private fun openAiSse(vararg pieces: String): String = buildString {
        pieces.forEach { append("data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n") }
        append("data: [DONE]\n\n")
    }

    // ------------------------------------------------------------ 流式

    @Test
    fun `流式生成能拼出完整文本`() = runTest {
        val client = FakeClient(listOf(openAiSse("Hel", "lo", "!")))
        val service = ChatService(client)

        val result = ChatService.collect(
            service.stream(OpenAiCompatibleProvider("https://x/v1", "k"), messages, options),
        )

        assertEquals("Hello!", result.text)
    }

    @Test
    fun `分片粒度不影响结果`() = runTest {
        val sse = openAiSse("Hel", "lo")

        val whole = ChatService.collect(
            ChatService(FakeClient(listOf(sse))).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
            ),
        )
        val byChar = ChatService.collect(
            ChatService(FakeClient(listOf(sse), chunkSize = 1)).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
            ),
        )
        val byThree = ChatService.collect(
            ChatService(FakeClient(listOf(sse), chunkSize = 3)).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
            ),
        )

        assertEquals("Hello", whole.text)
        assertEquals(whole, byChar, "逐字符分片结果不同")
        assertEquals(whole, byThree, "按 3 字符分片结果不同")
    }

    @Test
    fun `思维链与正文分开累计`() = runTest {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"再答\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val result = ChatService.collect(
            ChatService(FakeClient(listOf(sse))).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
            ),
        )

        assertEquals("再答", result.text)
        assertEquals("先想", result.reasoning)
    }

    @Test
    fun `流出结束时收到的 done 标记被传递`() = runTest {
        val deltas = ChatService(FakeClient(listOf(openAiSse("x")))).stream(
            OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
        ).toList()

        assertEquals("x", deltas.map { it.text }.joinToString(""))
        assertTrue(deltas.last().done, "最后一个增量应带 done")
    }

    @Test
    fun `Anthropic 的 event 流能正确解析`() = runTest {
        val sse = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        val result = ChatService.collect(
            ChatService(FakeClient(listOf(sse))).stream(AnthropicProvider("https://x", "k"), messages, options),
        )

        assertEquals("答", result.text)
    }

    @Test
    fun `Gemini 的 thought part 进思维链`() = runTest {
        val sse = buildString {
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"想\",\"thought\":true}]}}]}\n\n")
            append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"答\"}]}}]}\n\n")
        }
        val result = ChatService.collect(
            ChatService(FakeClient(listOf(sse))).stream(GeminiProvider("https://x/v1beta", "k"), messages, options),
        )

        assertEquals("答", result.text)
        assertEquals("想", result.reasoning)
    }

    @Test
    fun `响应末尾没有空行也能收尾`() = runTest {
        // 有些服务端最后一个事件后不打空行就断连
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"tail\"}}]}"
        val result = ChatService.collect(
            ChatService(FakeClient(listOf(sse))).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"), messages, options,
            ),
        )

        assertEquals("tail", result.text)
    }

    @Test
    fun `请求确实是按所选 provider 构造的`() = runTest {
        val client = FakeClient(listOf(openAiSse("x")))
        ChatService(client).stream(
            AnthropicProvider("https://api.anthropic.com", "sk-ant"),
            listOf(PromptMessage(PromptRole.SYSTEM, "SYS"), PromptMessage(PromptRole.USER, "hi")),
            options,
        ).toList()

        val request = client.lastRequest ?: fail("应当发出请求")
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant", request.headers["x-api-key"])
        assertTrue(request.body.contains("\"system\":\"SYS\""), "system 应在顶层")
    }

    // ------------------------------------------------------------ 错误

    @Test
    fun `HTTP 错误原样抛出`() = runTest {
        val client = FakeClient(
            chunks = emptyList(),
            error = ProviderHttpException(401, "{\"error\":\"bad key\"}"),
        )
        try {
            ChatService.collect(
                ChatService(client).stream(OpenAiCompatibleProvider("https://x/v1", "k"), messages, options),
            )
            fail("应当抛出 ProviderHttpException")
        } catch (e: ProviderHttpException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.message!!.contains("bad key"))
        }
    }

    // ------------------------------------------------------------ 非流式

    @Test
    fun `非流式走 execute 并一次产出全文`() = runTest {
        val client = FakeClient(
            chunks = emptyList(),
            fullBody = """{"choices":[{"message":{"content":"一次性回复"}}]}""",
        )
        val result = ChatService.collect(
            ChatService(client).stream(
                OpenAiCompatibleProvider("https://x/v1", "k"),
                messages,
                options.copy(stream = false),
            ),
        )

        assertEquals("一次性回复", result.text)
        assertTrue(client.lastRequest!!.body.contains("\"stream\":false"))
    }

    @Test
    fun `非流式的 Request 也能按 provider 取正文`() = runTest {
        val anthropicBody = """{"content":[{"type":"text","text":"A"}]}"""
        val result = ChatService.collect(
            ChatService(FakeClient(emptyList(), fullBody = anthropicBody)).stream(
                AnthropicProvider("https://x", "k"),
                messages,
                options.copy(stream = false),
            ),
        )

        assertEquals("A", result.text)
    }

    // ------------------------------------------------------------ 聚合

    @Test
    fun `ChatResult 的加法按字段累加`() {
        val a = ChatResult(text = "a", reasoning = "r")
        val b = ChatResult(text = "b", images = listOf("data:x"))

        val sum = a + app.haltija.core.provider.stream.StreamDelta(text = "c", images = listOf("d"))

        assertEquals("ac", sum.text)
        assertEquals("r", sum.reasoning)
        assertEquals(listOf("d"), sum.images)
        assertTrue(!b.isEmpty)
        assertTrue(ChatResult.EMPTY.isEmpty)
    }
}
