package app.haltija.core.data.png

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PNG 容器层的测试。
 *
 * 最关键的一条是 [读入再写出必须与原文件逐字节相同] ——
 * fixtures 里的 PNG 是 SillyTavern 自己写出来的，
 * 如果我们拼回去的字节和它一模一样，就证明 chunk 布局与 CRC 计算都对了。
 */
class PngTest {

    private fun fixture(path: String): ByteArray =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    @Test
    fun `读入再写出与原文件逐字节相同`() {
        for (path in listOf(
            "characters/seraphina-v3.png",
            "characters/seraphina-v2.png",
            "characters/precedence-test.png",
        )) {
            val original = fixture(path)
            val rebuilt = Png.writeChunks(Png.readChunks(original))
            if (!original.contentEquals(rebuilt)) {
                val diff = original.indices.firstOrNull { it >= rebuilt.size || original[it] != rebuilt[it] } ?: -1
                fail("$path 重建后字节不同（原 ${original.size} / 新 ${rebuilt.size}，首个差异 $diff）")
            }
        }
    }

    @Test
    fun `chunk 顺序与数量被保留`() {
        val chunks = Png.readChunks(fixture("characters/seraphina-v3.png"))

        assertEquals("IHDR", chunks.first().type)
        assertEquals("IEND", chunks.last().type)
        assertTrue(chunks.any { it.type == "tEXt" }, "应当存在 tEXt 块")
    }

    @Test
    fun `签名校验`() {
        assertTrue(Png.isPng(fixture("characters/seraphina-v3.png")))
        assertTrue(!Png.isPng(fixture("characters/legacy-v1.json")))
        assertFailsWith<PngFormatException> { Png.readChunks(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
    }

    @Test
    fun `tEXt 块编解码往返`() {
        val chunk = PngText.encode("chara", "aGVsbG8gd29ybGQ=")
        val decoded = PngText.decode(chunk)

        assertEquals("chara", decoded.keyword)
        assertEquals("aGVsbG8gd29ybGQ=", decoded.text)
    }

    @Test
    fun `tEXt 拒绝非 Latin-1 内容`() {
        assertFailsWith<IllegalArgumentException> { PngText.encode("chara", "中文不能直接放进 tEXt") }
    }

    @Test
    fun `tEXt 拒绝超长 keyword`() {
        assertFailsWith<IllegalArgumentException> { PngText.encode("k".repeat(80), "x") }
    }
}
