package app.haltija.data

import app.haltija.core.provider.ProviderRequest
import app.haltija.core.provider.transport.ProviderHttpClient
import app.haltija.core.provider.transport.ProviderHttpException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** 读超时控制 SSE；协程取消主动关闭连接，使停止生成能立即进入保存阶段。 */
class OkHttpProviderHttpClient(private val client: OkHttpClient = defaultClient()) : ProviderHttpClient {
    override suspend fun stream(request: ProviderRequest, onChunk: suspend (String) -> Unit) {
        withResponse(request) { res ->
            val body = res.body ?: throw ProviderHttpException(res.code, "响应体为空")
            // 增量 UTF-8 解码，保留跨网络分片的中文与 emoji。
            val reader = InputStreamReader(body.byteStream(), Charsets.UTF_8)
            val buffer = CharArray(READ_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = reader.read(buffer)
                if (read <= 0) break
                onChunk(String(buffer, 0, read))
            }
        }
    }

    override suspend fun execute(request: ProviderRequest): String = withResponse(request) { res ->
        res.body?.string().orEmpty()
    }

    private suspend fun <T> withResponse(request: ProviderRequest, consume: suspend (Response) -> T): T = coroutineScope {
        val builder = Request.Builder().url(request.url).post(request.body.toRequestBody(JSON_MEDIA_TYPE))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        // 阻塞读不会自己响应协程取消；另一个协程负责关闭 socket。
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ProviderHttpException(response.code, response.body?.string().orEmpty())
                    }
                    consume(response)
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderHttpException(0, "网络请求失败：${e.message}")
        } finally {
            cancellation.cancel()
        }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 4096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
