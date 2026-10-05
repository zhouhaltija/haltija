package app.silly.core.data.png

/**
 * PNG `tEXt` 文本块编解码。
 *
 * 数据布局：`keyword` + `0x00` + `text`，全部 Latin-1 编码。
 * 行为对齐 SillyTavern 依赖的 `png-chunk-text`。
 *
 * 角色卡就藏在这里：keyword 为 `chara`（CCv2）或 `ccv3`（CCv3），
 * text 是 base64 编码的 UTF-8 JSON。
 *
 * 与 `png-chunk-text` 的一处**有意差异**：它在 text 中遇到 `0x00` 会抛异常，
 * 这里选择宽容处理。合法数据（base64）不可能含 `0x00`，
 * 宽容不会带来错误结果，只会让畸形容器也能被读出来。
 */
object PngText {

    /** PNG 规范：keyword 长度 1..79。 */
    const val MAX_KEYWORD_LENGTH = 79

    fun encode(keyword: String, text: String): PngChunk {
        require(keyword.isNotEmpty()) { "tEXt keyword 不能为空" }
        require(keyword.length <= MAX_KEYWORD_LENGTH) {
            "tEXt keyword 超过 $MAX_KEYWORD_LENGTH 字符上限：'$keyword'"
        }
        require('\u0000' !in keyword) { "tEXt keyword 不允许包含 0x00" }
        require('\u0000' !in text) { "tEXt text 不允许包含 0x00" }
        requireLatin1(keyword, "keyword")
        requireLatin1(text, "text")

        val kw = keyword.toLatin1Bytes()
        val tx = text.toLatin1Bytes()
        val data = ByteArray(kw.size + 1 + tx.size)
        kw.copyInto(data, 0)
        data[kw.size] = 0
        tx.copyInto(data, kw.size + 1)
        return PngChunk("tEXt", data)
    }

    data class Decoded(val keyword: String, val text: String)

    fun decode(chunk: PngChunk): Decoded = decode(chunk.data)

    fun decode(data: ByteArray): Decoded {
        val sep = data.indexOf(0)
        return if (sep < 0) {
            // 没有分隔符时，整块当作 keyword —— 与 png-chunk-text 的宽容行为一致
            Decoded(String(data, Charsets.ISO_8859_1), "")
        } else {
            Decoded(
                String(data, 0, sep, Charsets.ISO_8859_1),
                String(data, sep + 1, data.size - sep - 1, Charsets.ISO_8859_1),
            )
        }
    }

    private fun ByteArray.indexOf(value: Byte): Int {
        for (i in indices) if (this[i] == value) return i
        return -1
    }

    private fun requireLatin1(s: String, what: String) {
        for ((i, ch) in s.withIndex()) {
            require(ch.code <= 0xFF) {
                "PNG tEXt 只允许 Latin-1，$what 第 ${i + 1} 个字符是 U+${
                    ch.code.toString(16).uppercase().padStart(4, '0')
                }"
            }
        }
    }

    private fun String.toLatin1Bytes(): ByteArray {
        val out = ByteArray(length)
        for (i in indices) out[i] = this[i].code.toByte()
        return out
    }
}
