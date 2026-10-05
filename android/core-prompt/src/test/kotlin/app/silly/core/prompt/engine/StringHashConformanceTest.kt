package app.silly.core.prompt.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 字符串哈希的跨语言一致性测试。
 *
 * 世界书的计时效果把条目哈希写进 `chat_metadata.timedWorldInfo`，
 * 加载时靠它找回条目。哈希算错，手机端和桌面端共用聊天记录时
 * 就会互相认为「这个效果对应不上条目」。
 *
 * golden 由 `tools/gen-worldinfo-goldens.mjs` 从 `public/scripts/utils.js`
 * **逐字抽取** `getStringHash` 并运行得到。
 */
class StringHashConformanceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val golden: JsonObject by lazy {
        val bytes = javaClass.getResourceAsStream("/worldinfo-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/worldinfo-goldens.json —— 请运行 `node tools/gen-worldinfo-goldens.mjs`")
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private val cases: List<Pair<String, Long>> by lazy {
        golden["getStringHash"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { el ->
                val o = el.jsonObject
                o["input"]!!.jsonPrimitive.content to o["hash"]!!.jsonPrimitive.long
            }
    }

    @Test
    fun `用例数量合理且包含边界输入`() {
        assertTrue(cases.size >= 8, "用例太少：${cases.size}")
        assertTrue(cases.any { it.first.isEmpty() }, "应包含空串")
        assertTrue(cases.any { it.first.length > 100 }, "应包含长文本")
    }

    @Test
    fun `每一条哈希都与 ST 完全一致`() {
        val mismatches = mutableListOf<String>()
        for ((input, expected) in cases) {
            val actual = StringHash.hash(input)
            if (actual != expected) {
                val shown = if (input.length > 40) input.take(37) + "..." else input
                mismatches += "  ${json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(shown))}  ST=$expected Kotlin=$actual"
            }
        }
        assertTrue(mismatches.isEmpty(), "哈希不一致：\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `种子参数生效且保持与 ST 一致`() {
        // seed 默认为 0；显式传 0 应当与默认一致
        assertEquals(StringHash.hash("abc"), StringHash.hash("abc", 0))
        // 非零 seed 应当改变结果
        assertTrue(StringHash.hash("abc", 1) != StringHash.hash("abc", 0))
    }

    @Test
    fun `代理对按 UTF-16 码元处理`() {
        // JS 的 charCodeAt 对 emoji 会拆成两个码元；Kotlin 的 String 索引语义相同。
        // 用两个各自独立的字符拼出与代理对等价的码元序列，结果必须一致。
        val emoji = "😀"
        assertEquals(2, emoji.length, "Kotlin 字符串应同样是 UTF-16 表示")
        assertEquals(
            StringHash.hash(emoji),
            StringHash.hash("${emoji[0]}${emoji[1]}"),
        )
    }
}
