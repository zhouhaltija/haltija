package app.silly.core.data.png

import java.util.zip.CRC32

/**
 * 一个 PNG chunk。
 *
 * 只保存类型与数据，CRC 在写出时按 PNG 规范重新计算 —— 行为与 SillyTavern
 * 依赖的 `png-chunks-extract`（读）和 `src/png/encode.js`（写）一致。
 */
class PngChunk(val type: String, val data: ByteArray) {

    init {
        require(type.length == 4) { "PNG chunk 类型必须是 4 个 ASCII 字符，实际是 '$type'" }
    }

    /** 把数据按 Latin-1 解码为文本，tEXt 等文本 chunk 用。 */
    fun asLatin1(): String = String(data, Charsets.ISO_8859_1)

    override fun equals(other: Any?): Boolean =
        other is PngChunk && type == other.type && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * type.hashCode() + data.contentHashCode()

    override fun toString(): String = "PngChunk($type, ${data.size} bytes)"
}

/** PNG 解析失败。 */
class PngFormatException(message: String) : IllegalArgumentException(message)

/**
 * 极简 PNG 容器读写。
 *
 * 只做 chunk 级别的拆装，不解码图像数据 —— 角色卡只需要读写 tEXt chunk，
 * 图像字节原样搬运即可。零第三方依赖。
 */
object Png {

    /** PNG 文件签名：`89 50 4E 47 0D 0A 1A 0A`。 */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= SIGNATURE.size && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /**
     * 解析 PNG 的全部 chunk。
     *
     * 行为对齐 `png-chunks-extract`：
     *  - 校验 8 字节签名；
     *  - 第一个 chunk 必须是 IHDR；
     *  - 遇到 IEND 立即停止，忽略其后的字节。
     */
    fun readChunks(bytes: ByteArray): List<PngChunk> {
        if (bytes.size < SIGNATURE.size) throw PngFormatException("文件长度不足，不是 PNG")
        for (i in SIGNATURE.indices) {
            if (bytes[i] != SIGNATURE[i]) {
                throw PngFormatException("PNG 签名不匹配（第 ${i + 1} 个字节）")
            }
        }

        val chunks = ArrayList<PngChunk>()
        var idx = SIGNATURE.size
        while (idx + 12 <= bytes.size) {
            val length = readUInt32(bytes, idx).toLong() and 0xFFFF_FFFFL
            idx += 4
            if (length > MAX_CHUNK_LENGTH) throw PngFormatException("chunk 长度非法：$length")
            val len = length.toInt()
            if (idx + 4 + len + 4 > bytes.size) throw PngFormatException("chunk 数据越界（声明长度 $len）")

            val type = String(bytes, idx, 4, Charsets.US_ASCII)
            idx += 4
            val data = bytes.copyOfRange(idx, idx + len)
            idx += len + 4 // 跳过 4 字节 CRC（读取时不校验）

            if (chunks.isEmpty() && type != "IHDR") throw PngFormatException("PNG 缺少 IHDR")
            chunks += PngChunk(type, data)
            if (type == "IEND") return chunks
        }
        return chunks
    }

    /** 把 chunk 列表编码回 PNG 字节流，CRC 按规范重新计算。 */
    fun writeChunks(chunks: List<PngChunk>): ByteArray {
        var size = SIGNATURE.size
        for (c in chunks) size += 12 + c.data.size

        val out = ByteArray(size)
        SIGNATURE.copyInto(out, 0)
        var idx = SIGNATURE.size
        val crc = CRC32()

        for (c in chunks) {
            writeUInt32(out, idx, c.data.size)
            idx += 4

            val typeBytes = c.type.toByteArray(Charsets.US_ASCII)
            typeBytes.copyInto(out, idx)
            idx += 4
            c.data.copyInto(out, idx)
            idx += c.data.size

            crc.reset()
            crc.update(typeBytes)
            crc.update(c.data)
            writeUInt32(out, idx, crc.value.toInt())
            idx += 4
        }
        return out
    }

    private const val MAX_CHUNK_LENGTH = 64L * 1024 * 1024

    private fun readUInt32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun writeUInt32(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}
