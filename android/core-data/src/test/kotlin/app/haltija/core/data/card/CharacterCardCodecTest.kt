package app.haltija.core.data.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 角色卡解析的验收测试。
 *
 * 所有输入都来自 `fixtures/`，由 SillyTavern 自己生成；
 * 期望值来自 `fixtures/expected/` 黄金文件。
 * 也就是说这些测试断言的是「和 SillyTavern 读出来一模一样」，
 * 而不是「符合我写的解析器」。
 */
class CharacterCardCodecTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(path: String): ByteArray =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    private fun golden(path: String): JsonObject =
        json.parseToJsonElement(fixture("expected/$path").toString(Charsets.UTF_8)) as JsonObject

    // ------------------------------------------------------------ 容器识别

    @Test
    fun `靠魔数识别容器类型，不依赖扩展名`() {
        assertEquals(CardFormat.PNG, CharacterCardCodec.detectFormat(fixture("characters/seraphina-v3.png")))
        assertEquals(CardFormat.CHARX, CharacterCardCodec.detectFormat(fixture("characters/charx-test.charx")))
        assertEquals(CardFormat.JSON, CharacterCardCodec.detectFormat(fixture("characters/legacy-v1.json")))
    }

    // ------------------------------------------------------------ PNG

    @Test
    fun `CCv3 卡与黄金文件逐字段一致`() {
        val card = CharacterCardCodec.read(fixture("characters/seraphina-v3.png"))

        assertEquals(CharacterCardCodec.SPEC_V3, card.spec)
        assertEquals("3.0", card.specVersion)
        assertFalse(card.isV1)
        assertEquals("Seraphina", card.name)
        assertEquals(golden("seraphina-v3.json"), card.toJsonObject())
    }

    @Test
    fun `CCv2 卡与黄金文件逐字段一致`() {
        val card = CharacterCardCodec.read(fixture("characters/seraphina-v2.png"))

        assertEquals(CharacterCardCodec.SPEC_V2, card.spec)
        assertFalse(card.isV1)
        assertEquals(golden("seraphina-v2.json"), card.toJsonObject())
    }

    @Test
    fun `同时存在 chara 与 ccv3 时必须选 ccv3`() {
        val card = CharacterCardCodec.read(fixture("characters/precedence-test.png"))

        // fixture 里 chara 块的 name 是 WRONG_chara_chunk，ccv3 才是 RIGHT_ccv3_chunk
        assertEquals("RIGHT_ccv3_chunk", card.name)
        assertEquals(golden("precedence-ccv3-wins.json"), card.toJsonObject())
    }

    @Test
    fun `PNG 无卡数据时明确报错`() {
        val stripped = CharacterCardCodec.stripCardData(fixture("characters/seraphina-v3.png"))
        assertFalse(CharacterCardCodec.hasCardData(stripped))
        try {
            CharacterCardCodec.readPng(stripped)
            fail("应当抛出 CardFormatException")
        } catch (e: CardFormatException) {
            assertTrue(e.message!!.contains("tEXt"), "错误信息应说明缺少 tEXt：${e.message}")
        }
    }

    // ------------------------------------------------------------ JSON

    @Test
    fun `V1 扁平卡可解析且被识别为 V1`() {
        val card = CharacterCardCodec.read(fixture("characters/legacy-v1.json"))

        assertTrue(card.isV1, "没有 spec 也没有 data 的卡应被判定为 V1")
        assertEquals(null, card.spec)
        assertEquals("Legacy V1 Card", card.name)
        // V1 时 data 退化为根对象
        assertEquals(card.root, card.data)
        assertEquals(golden("legacy-v1.json"), card.toJsonObject())
    }

    // ------------------------------------------------------------ CharX

    @Test
    fun `CharX 解出 card_json 与内嵌 icon`() {
        val contents = CharxArchive.read(fixture("characters/charx-test.charx"))

        assertEquals("CharX Test", contents.card.name)
        assertEquals(CharacterCardCodec.SPEC_V3, contents.card.spec)
        assertEquals(golden("charx-test.json"), contents.card.toJsonObject())

        val icon = assertNotNull(contents.icon, "应能从 assets 中解出 icon")
        assertTrue(icon.size > 1000, "icon 不应为空壳，实际 ${icon.size} 字节")
    }

    @Test
    fun `CharX 缺少 spec 时拒绝导入`() {
        try {
            CharxArchive.read(zipOf("card.json" to """{"name":"no spec"}"""))
            fail("应当抛出 CardFormatException")
        } catch (e: CardFormatException) {
            assertTrue(e.message!!.contains("spec"), "错误信息应提到 spec：${e.message}")
        }
    }

    @Test
    fun `CharX 支持自解压归档（zip 前有多余字节）`() {
        val zip = zipOf("card.json" to """{"spec":"chara_card_v3","data":{"name":"SFX"}}""")
        val prefixed = "#!/bin/sh\necho self-extracting\n".toByteArray() + zip

        val contents = CharxArchive.read(prefixed)
        assertEquals("SFX", contents.card.name)
    }

    // ------------------------------------------------------------ 写出与往返

    @Test
    fun `写出后再读回，卡内容不变`() {
        val basePng = fixture("characters/seraphina-v3.png")
        val original = CharacterCardCodec.read(basePng)

        val rewritten = CharacterCardCodec.writePng(original, basePng)
        val reread = CharacterCardCodec.read(rewritten)

        assertEquals(original.toJsonObject(), reread.toJsonObject())
    }

    @Test
    fun `写卡不破坏图像数据`() {
        val basePng = fixture("characters/seraphina-v3.png")
        val card = CharacterCardCodec.read(basePng)

        val rewritten = CharacterCardCodec.writePng(card, basePng)
        val before = CharacterCardCodec.stripCardData(basePng)
        val after = CharacterCardCodec.stripCardData(rewritten)

        assertContentEquals(before, after, "剥离卡数据后，图像字节必须完全一致")
    }

    @Test
    fun `重复写卡不会累积重复的 tEXt 块`() {
        val basePng = fixture("characters/seraphina-v3.png")
        var png = basePng
        repeat(3) {
            png = CharacterCardCodec.writePng(CharacterCardCodec.read(png), png)
        }

        val textKeywords = app.haltija.core.data.png.Png.readChunks(png)
            .filter { it.type == "tEXt" }
            .map { app.haltija.core.data.png.PngText.decode(it).keyword.lowercase() }

        assertEquals(1, textKeywords.count { it == CharacterCardCodec.KEYWORD_V2 }, "chara 块应只有 1 个")
        assertEquals(1, textKeywords.count { it == CharacterCardCodec.KEYWORD_V3 }, "ccv3 块应只有 1 个")
    }

    @Test
    fun `不写 ccv3 块时只留 chara`() {
        val basePng = fixture("characters/seraphina-v2.png")
        val card = CharacterCardCodec.read(basePng)

        val png = CharacterCardCodec.writePng(card, basePng, includeV3Chunk = false)
        val keywords = app.haltija.core.data.png.Png.readChunks(png)
            .filter { it.type == "tEXt" }
            .map { app.haltija.core.data.png.PngText.decode(it).keyword.lowercase() }

        assertEquals(listOf(CharacterCardCodec.KEYWORD_V2), keywords)
    }

    // ------------------------------------------------------------ 便捷字段

    @Test
    fun `开户白列表由 first_mes 与 alternate_greetings 组成`() {
        val card = CharacterCardCodec.read(fixture("characters/seraphina-v3.png"))

        assertTrue(card.firstMes.isNotEmpty(), "fixture 应带 first_mes")
        assertEquals(card.firstMes, card.greetings.first())
        assertEquals(card.alternateGreetings, card.greetings.drop(1))
    }

    @Test
    fun `第三方 extensions 字段被完整保留`() {
        val card = CharacterCardCodec.read(fixture("characters/seraphina-v3.png"))

        val extensions = assertNotNull(card.extensions, "V3 卡应带 extensions")
        assertTrue(extensions.isNotEmpty(), "extensions 不应为空，否则这个用例没有意义")
        // 无损写回：重新序列化后 extensions 必须一字不差
        val roundTripped = CharacterCardCodec.read(
            CharacterCardCodec.writeJson(card).let { CharacterCardCodec.read(it) }.let {
                CharacterCardCodec.writePng(it, fixture("characters/seraphina-v3.png"))
            },
        )
        assertEquals(extensions, roundTripped.extensions)
    }

    // ------------------------------------------------------------ 辅助

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray, message: String) {
        if (!expected.contentEquals(actual)) {
            val firstDiff = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: -1
            fail("$message（长度 ${expected.size} vs ${actual.size}，首个差异位于 $firstDiff）")
        }
    }
}
