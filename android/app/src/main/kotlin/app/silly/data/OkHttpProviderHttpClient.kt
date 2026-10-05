package app.silly.data

import app.silly.core.provider.ProviderRequest
import app.silly.core.provider.transport.ProviderHttpClient
import app.silly.core.provider.transport.ProviderHttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * [ProviderHttpClient] 的 OkHttp 实现。
 *
 * ## 两个必须处理的点
 *
 * 1. **超时必须是「读超时」而不是「总超时」**。模型边想边吐，整个请求可能持续几分钟，
 *    但两次数据之间通常不会超过几十秒。所以 `callTimeout` 设为 0（不限总时长），
 *    只靠 `readTimeout` 兜底 —— 否则长回答会被硬生生掐断。
 * 2. **非 2xx 要把正文读出来再抛**。很多端点会在 400 的正文里写清楚是哪个字段错了，
 *    只报状态码等于把最有用的信息丢掉。
 */
class OkHttpProviderHttpClient(
    private val client: OkHttpClient = defaultClient(),
) : ProviderHttpClient {

    override suspend fun stream(request: ProviderRequest, onChunk: suspend (String) -> Unit) {
        withContext(Dispatchers.IO) {
            val response = executeRaw(request)
            response.use { res ->
                if (!res.isSuccessful) throw toHttpException(res)

                // OkHttp 4.x 的 body 是可空的；5.x 起改为非空
                val body = res.body ?: throw ProviderHttpException(res.code, "响应体为空")
                // 必须走 InputStreamReader：直接按字节读、再逐块 UTF-8 解码，
                // 会把跨块的多字节字符切碎（中文回复很容易撞上）。
                // InputStreamReader 内部有增量解码器，能正确处理块边界的半个字符。
                val reader = InputStreamReader(body.byteStream(), Charsets.UTF_8)
                val buffer = CharArray(READ_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    onChunk(String(buffer, 0, read))
                }
            }
        }
    }

    override suspend fun execute(request: ProviderRequest): String = withContext(Dispatchers.IO) {
        executeRaw(request).use { res ->
            if (!res.isSuccessful) throw toHttpException(res)
            res.body?.string().orEmpty()
        }
    }

    private fun executeRaw(request: ProviderRequest): Response {
        val builder = Request.Builder().url(request.url).post(
            request.body.toRequestBody(JSON_MEDIA_TYPE),
        )
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        return try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw ProviderHttpException(0, "网络请求失败：${e.message}")
        }
    }

    private fun toHttpException(response: Response): ProviderHttpException {
        val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
        return ProviderHttpException(response.code, body)
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 默认客户端。
         *
         * `callTimeout` 保持 0：一次生成可能要几分钟，总时长不该设限。
         * `readTimeout` 给 5 分钟，只用于「彻底没动静」的情况。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
