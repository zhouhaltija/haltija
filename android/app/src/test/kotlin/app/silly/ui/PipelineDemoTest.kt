package app.silly.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 管线自检的单元测试。
 *
 * 这一层不碰任何 Android API，所以能在普通 JVM 上跑 ——
 * 也就是说 `core-data` / `core-prompt` / `core-provider` 三个模块
 * **在 Android 模块的语境下也能正常工作**，不需要先起模拟器。
 */
class PipelineDemoTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `管线五个步骤都能跑完`() {
        val result = PipelineDemo.run()

        assertEquals(5, result.steps.size)
        assertTrue(result.steps.all { it.detail.isNotBlank() }, "每步都应有产物描述")
    }

    @Test
    fun `世界书只激活该激活的条目`() {
        val uids = PipelineDemo.run().activatedUids

        assertTrue(uids.contains(0), "uid 0（lantern）应命中")
        assertTrue(!uids.contains(1), "uid 1 是停用条目，不该被激活，实际：$uids")
    }

    @Test
    fun `组装出的消息里宏已被替换`() {
        val messages = PipelineDemo.run().messages

        assertTrue(messages.isNotEmpty())
        assertTrue(
            messages.none { it.content.contains("{{") },
            "不应残留未替换的宏：${messages.map { it.content.take(40) }}",
        )
        assertTrue(messages.any { it.content.contains("Seraphina") }, "应含角色名")
        assertTrue(messages.any { it.content.contains("lantern never gutters") }, "应含世界书内容")
    }

    @Test
    fun `三家的请求体都是合法 JSON 且各有特征`() {
        val requests = PipelineDemo.run().requests
        assertEquals(3, requests.size)

        // 请求体必须是合法 JSON —— 解析成功本身就是断言
        for (request in requests) {
            val parsed = json.parseToJsonElement(request.body).jsonObject
            assertTrue(parsed.isNotEmpty(), "${request.provider} 的请求体不应为空")
            assertTrue(request.url.startsWith("https://"), "${request.provider} 的 URL 不合法")
        }

        val byName = requests.associateBy { it.provider }
        assertTrue(byName.getValue("OpenAI 兼容").body.contains("\"messages\""))
        assertTrue(byName.getValue("Anthropic").body.contains("\"system\""), "Anthropic 的 system 在顶层")
        assertTrue(byName.getValue("Gemini").body.contains("systemInstruction"))
        assertTrue(byName.getValue("Gemini").url.contains("alt=sse"))
        assertTrue(byName.getValue("Gemini").url.contains(":streamGenerateContent"))
    }
}
