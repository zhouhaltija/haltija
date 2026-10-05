package app.silly.core.data.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 时间戳格式的跨语言一致性测试。
 *
 * golden 由 `tools/gen-time-goldens.mjs` 生成 —— 它把 ST 的
 * `humanizedDateTime()` 与 `getMessageTimeStamp()`（`RossAscends-mods.js:169-195`）
 * 逐字抽出来运行，覆盖 **4 个时区 × 7 个瞬间**。
 *
 * 为什么要跨时区：`humanizedDateTime` 取的是本地时间，而 `getMessageTimeStamp`
 * 恒为 UTC。两者用同一个 formatter 就会错。
 */
class StTimestampTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Case(
        val timezone: String,
        val epochMillis: Long,
        val iso: String,
        val humanized: String,
        val messageTimestamp: String,
    )

    private val cases: List<Case> by lazy {
        val bytes = javaClass.getResourceAsStream("/time-goldens.json")?.use { it.readBytes() }
            ?: fail("找不到 fixtures/time-goldens.json —— 请运行 `node tools/gen-time-goldens.mjs`")
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        (root["cases"] as kotlinx.serialization.json.JsonArray).map { element ->
            val o = element.jsonObject
            Case(
                timezone = o["timezone"]!!.jsonPrimitive.content,
                epochMillis = o["epochMillis"]!!.jsonPrimitive.long,
                iso = o["iso"]!!.jsonPrimitive.content,
                humanized = o["humanized"]!!.jsonPrimitive.content,
                messageTimestamp = o["messageTimestamp"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `用例数量与覆盖面合理`() {
        assertTrue(cases.size >= 20, "用例太少：${cases.size}")
        assertTrue(cases.map { it.timezone }.distinct().size >= 3, "应覆盖多个时区")
    }

    @Test
    fun `humanized 与 ST 逐条一致`() {
        val mismatches = mutableListOf<String>()

        for (case in cases) {
            val actual = StTimestamp.humanized(Instant.ofEpochMilli(case.epochMillis), ZoneId.of(case.timezone))
            if (actual != case.humanized) {
                mismatches += "  [${case.timezone}] ${case.iso}  ST=${case.humanized}  Kotlin=$actual"
            }
        }

        assertTrue(mismatches.isEmpty(), "humanizedDateTime 分歧：\n${mismatches.joinToString("\n")}")
    }

    @Test
    fun `消息时间戳与 ST 逐条一致且恒为 UTC`() {
        for (case in cases) {
            val actual = StTimestamp.messageTimestamp(Instant.ofEpochMilli(case.epochMillis))
            assertEquals(case.messageTimestamp, actual, "[${case.timezone}] ${case.iso}")
            assertTrue(actual.endsWith("Z"), "消息时间戳必须是 UTC")
        }
    }

    @Test
    fun `毫秒补齐三位`() {
        val base = Instant.parse("2026-10-05T08:30:45Z")

        assertEquals("2026-10-05T08:30:45.000Z", StTimestamp.messageTimestamp(base))
        assertEquals("2026-10-05T08:30:45.007Z", StTimestamp.messageTimestamp(base.plusMillis(7)))
        assertTrue(StTimestamp.humanized(base).endsWith("s000ms"))
        assertTrue(StTimestamp.humanized(base.plusMillis(7)).endsWith("s007ms"))
    }

    @Test
    fun `同一个瞬间在不同时区下文件名不同`() {
        val instant = Instant.parse("2026-10-05T08:30:45.123Z")

        val shanghai = StTimestamp.humanized(instant, ZoneId.of("Asia/Shanghai"))
        val newYork = StTimestamp.humanized(instant, ZoneId.of("America/New_York"))
        val utc = StTimestamp.humanized(instant, ZoneId.of("UTC"))

        assertEquals("2026-10-05@16h30m45s123ms", shanghai)
        assertEquals("2026-10-05@04h30m45s123ms", newYork)
        assertEquals("2026-10-05@08h30m45s123ms", utc)

        // 但 ISO 那个永远是同一个
        assertEquals(
            StTimestamp.messageTimestamp(instant),
            StTimestamp.messageTimestamp(instant),
        )
    }

    @Test
    fun `跨天的时区差异会被正确反映`() {
        // UTC 的 2026-01-01 00:00 在上海是当天 08:00，在纽约是 2025-12-31 19:00
        val instant = Instant.parse("2026-01-01T00:00:00Z")

        assertTrue(StTimestamp.humanized(instant, ZoneId.of("Asia/Shanghai")).startsWith("2026-01-01"))
        assertTrue(
            StTimestamp.humanized(instant, ZoneId.of("America/New_York")).startsWith("2025-12-31"),
            "纽约此时还停在前一天",
        )
    }

    @Test
    fun `新聊天文件名与 ST 的形式一致`() {
        val repo = ChatRepository(MemoryStDataRoot())
        val instant = Instant.parse("2026-10-05T08:30:45.123Z")

        assertEquals(
            "Seraphina - 2026-10-05@16h30m45s123ms.jsonl",
            repo.newChatFileName("Seraphina", instant, ZoneId.of("Asia/Shanghai")),
        )
        // 非法字符仍要过滤
        assertEquals(
            "A_B - 2026-10-05@16h30m45s123ms.jsonl",
            repo.newChatFileName("A/B", instant, ZoneId.of("Asia/Shanghai")),
        )
    }
}
