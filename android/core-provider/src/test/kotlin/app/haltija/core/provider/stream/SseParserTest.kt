package app.haltija.core.provider.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SSE 解析器的测试。
 *
 * 重点在**分片**：网络包会在任意位置切开，最要命的是切在 `data:` 与它的冒号之间、
 * 或者切在 `\r\n` 中间。所以每个用例都会用「逐字符喂」再跑一遍，
 * 结果必须与整段喂入完全一致。
 */
class SseParserTest {

    private fun parseAll(chunks: List<String>): List<SseEvent> {
        val parser = SseParser()
        val events = ArrayList<SseEvent>()
        chunks.forEach { events += parser.feed(it) }
        events += parser.finish()
        return events
    }

    /** 整段喂入与逐字符喂入必须得到同样的结果。 */
    private fun assertChunkingIrrelevant(text: String) {
        val whole = parseAll(listOf(text))
        val byChar = parseAll(text.map { it.toString() })
        val byChunk = parseAll(text.chunked(3))
        assertEquals(whole, byChar, "逐字符分片结果不同")
        assertEquals(whole, byChunk, "按 3 字符分片结果不同")
    }

    // ------------------------------------------------------------ 基本

    @Test
    fun `单个事件`() {
        val events = parseAll(listOf("data: hello\n\n"))
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
        assertEquals(null, events[0].event)
    }

    @Test
    fun `event 字段被保留`() {
        val events = parseAll(listOf("event: content_block_delta\ndata: {}\n\n"))
        assertEquals(1, events.size)
        assertEquals("content_block_delta", events[0].event)
        assertEquals("{}", events[0].data)
    }

    @Test
    fun `多个事件`() {
        val events = parseAll(listOf("data: a\n\ndata: b\n\n"))
        assertEquals(listOf("a", "b"), events.map { it.data })
    }

    @Test
    fun `US-ASCII 之外的内容原样保留`() {
        val events = parseAll(listOf("data: 中文内容\n\n"))
        assertEquals("中文内容", events[0].data)
    }

    // ------------------------------------------------------------ 多行与分隔符

    @Test
    fun `同一个事件的多个 data 行用换行拼接`() {
        val events = parseAll(listOf("data: line1\ndata: line2\n\n"))
        assertEquals(1, events.size)
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun `CRLF 与 CR 都能当行分隔符`() {
        val crlf = parseAll(listOf("data: a\r\n\r\n"))
        assertEquals(listOf("a"), crlf.map { it.data })

        val cr = parseAll(listOf("data: a\r\r"))
        assertEquals(listOf("a"), cr.map { it.data })
    }

    @Test
    fun `CRLF 不会被当成两个换行`() {
        // 若 \r 与 \n 各算一次换行，会多派发一个空事件
        val events = parseAll(listOf("data: a\r\ndata: b\r\n\r\n"))
        assertEquals(1, events.size)
        assertEquals("a\nb", events[0].data)
    }

    @Test
    fun `只有一行 data 后无空行时由 finish 派发`() {
        val parser = SseParser()
        val during = parser.feed("data: tail\n")
        assertTrue(during.isEmpty(), "没有空行不应派发")
        assertEquals(listOf("tail"), parser.finish().map { it.data })
    }

    @Test
    fun `末尾没有换行也能由 finish 派发`() {
        val parser = SseParser()
        parser.feed("data: x")
        assertEquals(listOf("x"), parser.finish().map { it.data })
    }

    // ------------------------------------------------------------ 注释与空值

    @Test
    fun `注释行被忽略`() {
        val events = parseAll(listOf(": keep-alive\ndata: real\n\n"))
        assertEquals(listOf("real"), events.map { it.data })
    }

    @Test
    fun `data 冒号后的一个空格被吃掉其余保留`() {
        val events = parseAll(listOf("data:  two spaces\n\n"))
        assertEquals(" two spaces", events[0].data)

        val noSpace = parseAll(listOf("data:tight\n\n"))
        assertEquals("tight", noSpace[0].data)
    }

    @Test
    fun `空 data 行也是有效数据`() {
        val events = parseAll(listOf("data:\n\n"))
        assertEquals(1, events.size)
        assertEquals("", events[0].data)
    }

    @Test
    fun `没有 data 只有 event 时不派发`() {
        val events = parseAll(listOf("event: ping\n\n"))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `id 与 retry 字段被忽略`() {
        val events = parseAll(listOf("id: 42\nretry: 1000\ndata: payload\n\n"))
        assertEquals(listOf("payload"), events.map { it.data })
    }

    @Test
    fun `字段名不带冒号时按空值处理`() {
        val events = parseAll(listOf("data\n\n"))
        assertEquals(listOf(""), events.map { it.data })
    }

    // ------------------------------------------------------------ 分片

    @Test
    fun `各种输入在任意分片下结果一致`() {
        for (text in listOf(
            "data: hello\n\n",
            "event: x\ndata: {}\n\n",
            "data: a\ndata: b\n\n",
            "data: a\r\n\r\ndata: b\r\n\r\n",
            ": comment\ndata: v\n\n",
            "data: [DONE]\n\n",
            "data: 中文\n\n",
            "data: x",
        )) {
            assertChunkingIrrelevant(text)
        }
    }

    @Test
    fun `OpenAI 风格的连续 data 行`() {
        val stream = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val events = parseAll(listOf(stream))

        assertEquals(3, events.size)
        assertEquals("[DONE]", events[2].data)
        assertChunkingIrrelevant(stream)
    }

    @Test
    fun `Anthropic 风格的 event 加 data`() {
        val stream = buildString {
            append("event: message_start\ndata: {\"type\":\"message_start\"}\n\n")
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n")
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }
        val events = parseAll(listOf(stream))

        assertEquals(3, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("content_block_delta", events[1].event)
        assertEquals("message_stop", events[2].event)
        assertChunkingIrrelevant(stream)
    }

    @Test
    fun `hasPending 能识别被截断的流`() {
        val parser = SseParser()
        parser.feed("data: incomplete")
        assertTrue(parser.hasPending())

        parser.finish()
        assertTrue(!parser.hasPending())
    }
}
