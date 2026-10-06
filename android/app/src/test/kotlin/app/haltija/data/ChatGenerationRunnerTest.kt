package app.haltija.data

import app.haltija.core.data.store.ChatRepository
import app.haltija.core.data.store.MemoryStDataRoot
import app.haltija.core.data.store.StDataRoot
import app.haltija.core.data.store.CharacterEntry
import app.haltija.core.provider.stream.StreamDelta
import app.haltija.core.provider.transport.ProviderHttpException
import app.haltija.ui.DemoContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatGenerationRunnerTest {
    private val character = CharacterEntry("demo.png", DemoContent.character(), null)
    private val root = MemoryStDataRoot()
    private val store = ChatSessionStore(ChatRepository(root))
    private fun pending() = store.append(store.create(character, "Traveler"), "question", true, "Traveler")

    @Test fun `请求开始前用户消息已落盘完整回复保存后再通知完成`() = runBlocking {
        val pending = pending()
        var complete: GenerationCompletion? = null
        ChatGenerationRunner(store).run(pending, "Traveler", source = {
            assertEquals("question", store.load(character, pending.fileName).chat.lastMessage!!.mes)
            flow { emit(StreamDelta(text = "你好", reasoning = "思考")); emit(StreamDelta(text = "🌙")) }
        }, onProgress = {}, onComplete = {
            assertEquals("你好🌙", store.load(character, pending.fileName).chat.lastMessage!!.mes)
            complete = it
        })
        assertTrue(complete!!.saved)
        assertEquals(null, complete!!.error)
        assertEquals("思考", complete!!.session.chat.lastMessage!!.extra["reasoning"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertEquals(pending.chat.header.integrity, complete!!.session.chat.header.integrity)
    }

    @Test fun `取消流式生成后正文与思考都落盘且取消保持取消状态`() = runBlocking {
        val pending = pending()
        val received = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<GenerationCompletion>()
        val job = launch {
            ChatGenerationRunner(store).run(pending, "Traveler", source = {
                flow { emit(StreamDelta(text = "partial", reasoning = "thought")); awaitCancellation() }
            }, onProgress = { received.complete(Unit) }, onComplete = { completed.complete(it) })
        }
        withTimeout(3000) { received.await() }
        withTimeout(3000) { job.cancelAndJoin() }
        val result = completed.await()
        assertTrue(job.isCancelled)
        assertTrue(result.saved)
        assertEquals(null, result.error)
        val restored = store.load(character, pending.fileName)
        assertEquals("partial", restored.chat.lastMessage!!.mes)
        assertEquals("thought", (restored.chat.lastMessage!!.extra["reasoning"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(3, restored.chat.size)
    }

    @Test fun `网络断流保留部分回复并报告网络错误`() = runBlocking {
        val pending = pending()
        var result: GenerationCompletion? = null
        ChatGenerationRunner(store).run(pending, "Traveler", source = {
            flow { emit(StreamDelta(text = "partial")); throw ProviderHttpException(0, "disconnected") }
        }, onProgress = {}, onComplete = { result = it })
        assertTrue(result!!.saved)
        assertIs<ProviderHttpException>(result!!.error)
        assertEquals("partial", store.load(character, pending.fileName).chat.lastMessage!!.mes)
    }

    @Test fun `用户消息保存失败时不发请求且保留待重试快照`() = runBlocking {
        val pending = pending()
        val failedStore = ChatSessionStore(ChatRepository(object : StDataRoot by root {
            override fun write(path: String, bytes: ByteArray) { throw IOException("read only") }
        }))
        var requested = false
        var result: GenerationCompletion? = null
        ChatGenerationRunner(failedStore).run(pending, "Traveler", source = {
            requested = true
            flow { emit(StreamDelta(text = "should not happen")) }
        }, onProgress = {}, onComplete = { result = it })
        assertFalse(requested)
        assertFalse(result!!.saved)
        assertIs<SessionSaveException>(result!!.error)
        assertEquals("question", result!!.session.chat.lastMessage!!.mes)
        assertEquals(1, store.load(character, pending.fileName).chat.size)
        store.save(result!!.session)
        assertEquals("question", store.load(character, pending.fileName).chat.lastMessage!!.mes)
    }

    @Test fun `回复保存失败也保留正文与思考快照供重试`() = runBlocking {
        val pending = pending()
        var writes = 0
        val failedStore = ChatSessionStore(ChatRepository(object : StDataRoot by root {
            override fun write(path: String, bytes: ByteArray) {
                if (++writes == 2) throw IOException("disk full")
                root.write(path, bytes)
            }
        }))
        var result: GenerationCompletion? = null
        ChatGenerationRunner(failedStore).run(pending, "Traveler", source = {
            flow { emit(StreamDelta(text = "reply", reasoning = "thought")) }
        }, onProgress = {}, onComplete = { result = it })
        assertFalse(result!!.saved)
        assertEquals("reply", result!!.session.chat.lastMessage!!.mes)
        assertIs<SessionSaveException>(result!!.error)
        assertEquals("question", store.load(character, pending.fileName).chat.lastMessage!!.mes)
        store.save(result!!.session)
        assertEquals("reply", store.load(character, pending.fileName).chat.lastMessage!!.mes)
    }
}
