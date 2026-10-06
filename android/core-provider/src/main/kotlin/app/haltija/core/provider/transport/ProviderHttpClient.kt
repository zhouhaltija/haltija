package app.haltija.core.provider.transport

import app.haltija.core.provider.ProviderRequest

/**
 * 非 2xx 响应。
 *
 * @param statusCode HTTP 状态码
 * @param body 服务端返回的正文（通常是 JSON 错误对象，尽量原样带出来，便于定位）
 */
class ProviderHttpException(
    val statusCode: Int,
    val body: String,
) : RuntimeException("HTTP $statusCode: ${body.take(300)}")

/**
 * HTTP 传输层契约。
 *
 * **实现放在 `app` 模块**（Android 上是 OkHttp），这里只定契约 ——
 * 这样上层的编排逻辑可以用假实现做确定性测试，不需要真的联网。
 *
 * ## 实现必须遵守的两条
 *
 * 1. **分片是任意的**：`onChunk` 收到的字符串可能在任意位置切开，
 *    接收方（[app.haltija.core.provider.stream.SseParser]）负责拼接。
 *    不要试图按行切好再给。
 * 2. **非 2xx 必须抛 [ProviderHttpException]**，不要把错误正文当成正常流推下去 ——
 *    否则解析器会把它当成 SSE 内容，最后吐出一堆垃圾文本。
 */
interface ProviderHttpClient {

    /**
     * 发起流式请求，把响应体按块推给 [onChunk]。
     *
     * 返回即表示流已结束（正常结束或对端关闭）。
     */
    suspend fun stream(request: ProviderRequest, onChunk: suspend (String) -> Unit)

    /** 非流式请求，返回完整响应体。 */
    suspend fun execute(request: ProviderRequest): String
}
