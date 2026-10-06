package app.haltija.data

import app.haltija.core.provider.ProviderRequest
import app.haltija.core.provider.transport.ProviderHttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OkHttpProviderHttpClientTest {
    /** 只用本机 loopback，不访问外部模型服务。 */
    private fun withServer(status: Int = 200, body: ByteArray, holdOpen: Boolean = false, test: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val release = CountDownLatch(1)
            var accepted: java.net.Socket? = null
            val worker = thread(isDaemon = true, name = "haltija-http-test") {
                try {
                    server.accept().use { socket ->
                        accepted = socket
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                        }
                        repeat(contentLength) { input.read() }
                        val output = socket.getOutputStream()
                        val headers = "HTTP/1.1 $status Test\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n"
                        output.write(headers.toByteArray())
                        // 按字节写入，包含跨 UTF-8 边界的分片。
                        for (byte in body) { output.write(byte.toInt()); output.flush() }
                        if (holdOpen) release.await(10, TimeUnit.SECONDS)
                    }
                } catch (_: java.io.IOException) {
                    // 测试取消后连接会被关闭。
                }
            }
            try { test("http://127.0.0.1:${server.localPort}/chat") }
            finally {
                release.countDown()
                accepted?.close()
                worker.join(3000)
                assertTrue(!worker.isAlive, "测试服务器应正常退出")
            }
        }
    }

    @Test fun `阻塞等下一分片时取消能立即关闭连接`() {
        withServer(body = "data: 中文🌙\n\n".toByteArray(), holdOpen = true) { url ->
            runBlocking {
                val received = CompletableDeferred<Unit>()
                val client = OkHttpProviderHttpClient()
                val job = launch {
                    client.stream(ProviderRequest(url, emptyMap(), "{}")) { received.complete(Unit) }
                }
                withTimeout(3000) { received.await() }
                withTimeout(2000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            }
        }
    }

    @Test fun `任意字节分片仍保留中文和emoji`() {
        val body = "data: 中文回复🌙\n\n"
        withServer(body = body.toByteArray()) { url ->
            runBlocking {
                val output = StringBuilder()
                withTimeout(3000) {
                    OkHttpProviderHttpClient().stream(ProviderRequest(url, emptyMap(), "{}")) { output.append(it) }
                }
                assertEquals(body, output.toString())
            }
        }
    }

    @Test fun `HTTP错误保留状态与原始响应体`() {
        withServer(status = 401, body = "鉴权错误".toByteArray()) { url ->
            runBlocking {
                val error = assertFailsWith<ProviderHttpException> {
                    withTimeout(3000) { OkHttpProviderHttpClient().execute(ProviderRequest(url, emptyMap(), "{}")) }
                }
                assertEquals(401, error.statusCode)
                assertEquals("鉴权错误", error.body)
            }
        }
    }
}
