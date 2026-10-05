package app.silly.core.provider.stream

/**
 * 一个已完整的 SSE 事件。
 *
 * @param event `event:` 字段的值（Anthropic 用它区分 `content_block_delta` 等），
 *   没有该行时为 null。
 * @param data 把该事件所有 `data:` 行用 `\n` 连接后的内容。
 */
data class SseEvent(val event: String?, val data: String)

/**
 * 增量式 Server-Sent Events 解析器。
 *
 * ## 为什么要有它
 *
 * 流式响应是按网络包到达的，**一个包可能在任意位置被切断** —— 可能切在
 * 一行中间、甚至 `data:` 和它的冒号之间。所以不能用「按行 split」那种写法，
 * 必须把不完整的尾巴留住，等下一个包。
 *
 * 这个类因此设计成 [feed] 增量喂入、返回**本次能凑齐的完整事件**。
 *
 * ## 支持的语法（W3C EventSource）
 *
 * ```
 * : 这是一条注释，忽略
 * event: content_block_delta
 * data: {"type":"content_block_delta"}
 * data: 第二行 （会与前一行用 \n 拼接）
 *                      ← 空行代表事件结束，此时派发
 * ```
 *
 * - 行分隔符接受 `\n`、`\r\n`、`\r`
 * - `data:` 后紧跟的**一个**空格会被吃掉（`data: x` → `x`），其余保留
 * - 只有 `data` 行才算数据；`id:` / `retry:` 忽略（流式场景用不上重连）
 *
 * OpenAI 家的流以一个 `data: [DONE]` 结尾 —— 那是**数据**，不是事件名，
 * 所以这里把它当作普通事件交给上层判断。
 */
class SseParser {

    private val buffer = StringBuilder()
    private var eventName: String? = null
    private val dataLines = ArrayList<String>()

    /**
     * 喂入一段文本，返回其中已经凑齐的事件。
     *
     * 调用方可以按任意粒度切分输入 —— 逐字符喂和整段喂的结果完全一致。
     */
    fun feed(chunk: String): List<SseEvent> {
        if (chunk.isEmpty()) return emptyList()
        buffer.append(chunk)

        val events = ArrayList<SseEvent>()
        while (true) {
            val newline = findNewline() ?: break
            val line = buffer.substring(0, newline.first)
            buffer.delete(0, newline.second)
            handleLine(line, events)
        }
        return events
    }

    /**
     * 输入结束。会把残留的最后一行当作完整行处理，并派发尚未结束的事件。
     *
     * 有些服务端在最后一个事件后不再补空行就断开连接，所以这一步是必要的。
     */
    fun finish(): List<SseEvent> {
        val events = ArrayList<SseEvent>()
        if (buffer.isNotEmpty()) {
            val line = buffer.toString()
            buffer.setLength(0)
            handleLine(line, events)
        }
        dispatch(events)
        return events
    }

    /** 还有未消费的内容时返回 true（通常意味着连接被异常截断）。 */
    fun hasPending(): Boolean = buffer.isNotEmpty() || dataLines.isNotEmpty()

    // ------------------------------------------------------------ 内部

    /** 返回（换行符起点, 换行符之后的位置）。 */
    private fun findNewline(): Pair<Int, Int>? {
        for (i in buffer.indices) {
            when (buffer[i]) {
                '\n' -> return i to i + 1
                '\r' -> {
                    // \r\n 要一起吃掉，否则会多出一个空行
                    val next = if (i + 1 < buffer.length && buffer[i + 1] == '\n') i + 2 else i + 1
                    return i to next
                }
            }
        }
        return null
    }

    private fun handleLine(line: String, events: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatch(events)
            return
        }
        if (line.startsWith(":")) return // 注释

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
            else -> Unit // id / retry 等
        }
    }

    private fun dispatch(events: MutableList<SseEvent>) {
        if (dataLines.isEmpty()) {
            eventName = null
            return
        }
        events += SseEvent(eventName, dataLines.joinToString("\n"))
        dataLines.clear()
        eventName = null
    }
}
