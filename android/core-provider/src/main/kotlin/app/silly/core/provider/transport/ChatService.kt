package app.silly.core.provider.transport

import app.silly.core.provider.ChatProvider
import app.silly.core.provider.GenerationOptions
import app.silly.core.prompt.model.PromptMessage
import app.silly.core.provider.stream.SseEvent
import app.silly.core.provider.stream.SseParser
import app.silly.core.provider.stream.StreamDelta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 一次生成的累计结果。
 *
 * 流式过程中每次拿到的是增量，UI 直接拼也能用；但收尾时通常需要一个整体
 * （写入聊天文件、算 token、判断是否为空），所以这里提供聚合。
 */
data class ChatResult(
    val text: String = "",
    val reasoning: String = "",
    val images: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty() && reasoning.isEmpty() && images.isEmpty()

    operator fun plus(delta: StreamDelta) = ChatResult(
        text = text + delta.text,
        reasoning = reasoning + delta.reasoning,
        images = images + delta.images,
    )

    companion object {
        val EMPTY = ChatResult()
    }
}

/**
 * 把「构造请求 → 收流 → 解析 SSE → 产出增量」串起来。
 *
 * 这一层刻意不认识 Android、也不认识 UI —— 它只依赖 [ProviderHttpClient] 这个契约，
 * 所以可以在单元测试里用假客户端跑完整的流式流程。
 *
 * ## 为什么用 [channelFlow] 而不是普通 `flow`
 *
 * 网络回调发生在别处（OkHttp 的读线程），普通 `flow` 的 `emit` 不允许跨协程上下文调用。
 * `channelFlow` 的 `send` 则可以，正好匹配「被动接收分片」的形态。
 */
class ChatService(private val client: ProviderHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 发起一次生成，按 [StreamDelta] 增量产出。
     *
     * 流结束时会自动处理残留缓冲（有些服务端最后一个事件后不打空行就断连）。
     */
    fun stream(
        provider: ChatProvider,
        messages: List<PromptMessage>,
        options: GenerationOptions,
    ): Flow<StreamDelta> {
        if (!options.stream) return nonStreaming(provider, messages, options)

        return channelFlow {
            val request = provider.buildRequest(messages, options)
            val interpreter = provider.createInterpreter()
            val parser = SseParser()

            // 局部挂起函数：直接调 channelFlow 的 send（它本身就是挂起的）
            suspend fun handle(events: List<SseEvent>) {
                for (event in events) {
                    interpreter.interpret(event)?.let { send(it) }
                }
            }

            client.stream(request) { chunk ->
                handle(parser.feed(chunk))
            }
            handle(parser.finish())
        }
    }

    /** 非流式：一次拿全，包成单个增量吐出去。 */
    private fun nonStreaming(
        provider: ChatProvider,
        messages: List<PromptMessage>,
        options: GenerationOptions,
    ): Flow<StreamDelta> = flow {
        val request = provider.buildRequest(messages, options)
        val body = client.execute(request)
        val parsed = json.parseToJsonElement(body).jsonObject
        emit(StreamDelta(text = provider.extractContent(parsed), done = true))
    }

    companion object {
        /** 把增量流收集成一次结果。 */
        suspend fun collect(flow: Flow<StreamDelta>): ChatResult {
            var result = ChatResult.EMPTY
            flow.collect { result += it }
            return result
        }
    }
}
