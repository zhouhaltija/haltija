package app.haltija.core.data.card

import app.haltija.core.data.png.Png
import app.haltija.core.data.png.PngChunk
import app.haltija.core.data.png.PngText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/** 角色卡解析失败。 */
class CardFormatException(message: String) : IllegalArgumentException(message)

/** 角色卡的落盘容器类型。 */
enum class CardFormat {
    /** PNG，卡数据藏在 `tEXt` chunk 里。 */
    PNG,

    /** CharX，一个内含 `card.json` 的 zip。 */
    CHARX,

    /** 纯 JSON 文件。 */
    JSON,
}

/**
 * 角色卡读写。
 *
 * 读取时靠**文件魔数**判断容器类型，不依赖扩展名：
 * PNG 签名 → PNG；`PK\x03\x04` → CharX；其余按 JSON 处理。
 *
 * 编码与解码细节对齐 SillyTavern：
 *  - `tEXt` chunk 的 keyword `chara` 承载 CCv2，`ccv3` 承载 CCv3，**`ccv3` 优先**；
 *  - chunk 文本是 base64（Latin-1 安全的 ASCII），解码后是 UTF-8 JSON；
 *  - 写出时移除已有的 `chara` / `ccv3`（大小写不敏感），在 IEND 之前插入新块。
 */
@OptIn(ExperimentalSerializationApi::class)
object CharacterCardCodec {

    const val KEYWORD_V2 = "chara"
    const val KEYWORD_V3 = "ccv3"

    const val SPEC_V2 = "chara_card_v2"
    const val SPEC_V3 = "chara_card_v3"

    private val COMPACT: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val READER: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    // ------------------------------------------------------------ 读取

    fun detectFormat(bytes: ByteArray): CardFormat = when {
        Png.isPng(bytes) -> CardFormat.PNG
        CharxArchive.isZip(bytes) -> CardFormat.CHARX
        else -> CardFormat.JSON
    }

    /** 自动识别容器类型并解析。 */
    fun read(bytes: ByteArray): CharacterCard = when (detectFormat(bytes)) {
        CardFormat.PNG -> readPng(bytes)
        CardFormat.CHARX -> CharxArchive.read(bytes).card
        CardFormat.JSON -> readJson(bytes)
    }

    /**
     * 从 PNG 里读卡。
     *
     * `ccv3` 优先于 `chara` —— 这一条有专门的 fixture 覆盖
     * （`fixtures/characters/precedence-test.png`：两个 chunk 内容故意不同）。
     */
    fun readPng(bytes: ByteArray): CharacterCard {
        val chunks = Png.readChunks(bytes)
        val texts = chunks.filter { it.type == "tEXt" }.map(PngText::decode)
        if (texts.isEmpty()) {
            throw CardFormatException("PNG 里没有任何 tEXt 文本块，不是角色卡")
        }
        val payload = texts.firstOrNull { it.keyword.lowercase() == KEYWORD_V3 }
            ?: texts.firstOrNull { it.keyword.lowercase() == KEYWORD_V2 }
            ?: throw CardFormatException("PNG 的 tEXt 中既没有 ccv3 也没有 chara")
        return parseCard(decodeBase64(payload.text))
    }

    /** 从纯 JSON 读卡（自动跳过 UTF-8 BOM）。 */
    fun readJson(bytes: ByteArray): CharacterCard {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return parseCard(text)
    }

    private fun parseCard(jsonText: String): CharacterCard {
        val element = try {
            READER.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            throw CardFormatException("卡数据不是合法 JSON：${e.message}")
        }
        val root = element as? JsonObject
            ?: throw CardFormatException("卡数据的顶层不是 JSON 对象")
        if (root.isEmpty()) throw CardFormatException("卡数据是空对象")
        return CharacterCard(root)
    }

    // ------------------------------------------------------------ 写出

    /** 序列化为紧凑 JSON 字符串（与 `JSON.stringify` 一致，不含多余空白）。 */
    fun encodeToString(card: CharacterCard): String =
        COMPACT.encodeToString(JsonObject.serializer(), card.toJsonObject())

    /** 写出为纯 JSON 文件内容。 */
    fun writeJson(card: CharacterCard): ByteArray =
        (encodeToString(card) + "\n").toByteArray(Charsets.UTF_8)

    /**
     * 把卡写进 PNG。
     *
     * @param basePng 载体图片。卡数据只是叠加在图片上，图像字节原样保留。
     * @param includeV3Chunk 是否同时写入 `ccv3` 块（SillyTavern 默认会写）。
     */
    fun writePng(
        card: CharacterCard,
        basePng: ByteArray,
        includeV3Chunk: Boolean = true,
    ): ByteArray = writePng(encodeToString(card), basePng, includeV3Chunk)

    fun writePng(
        cardJson: String,
        basePng: ByteArray,
        includeV3Chunk: Boolean = true,
    ): ByteArray {
        val chunks = Png.readChunks(basePng).toMutableList()

        // 移除已有的 chara / ccv3（大小写不敏感，与 ST 一致）
        chunks.removeAll { chunk ->
            chunk.type == "tEXt" && runCatching {
                PngText.decode(chunk).keyword.lowercase() in setOf(KEYWORD_V2, KEYWORD_V3)
            }.getOrDefault(false)
        }

        val insertAt = chunks.indexOfLast { it.type == "IEND" }
            .let { if (it >= 0) it else chunks.size }

        val added = ArrayList<PngChunk>(2)
        added += PngText.encode(KEYWORD_V2, encodeBase64(cardJson))

        if (includeV3Chunk) {
            // 与 ST 相同：把同一份数据标记成 CCv3 再写一份。
            // 数据不是合法 JSON 时静默跳过，不影响主 chunk 写入。
            runCatching {
                val obj = COMPACT.parseToJsonElement(cardJson).jsonObject.toMutableMap()
                obj["spec"] = JsonPrimitive(SPEC_V3)
                obj["spec_version"] = JsonPrimitive("3.0")
                PngText.encode(KEYWORD_V3, encodeBase64(JsonObject(obj).toString()))
            }.onSuccess { added += it }
        }

        chunks.addAll(insertAt, added)
        return Png.writeChunks(chunks)
    }

    /** 从 PNG 中剥离卡数据，返回纯图片。 */
    fun stripCardData(pngBytes: ByteArray): ByteArray {
        val chunks = Png.readChunks(pngBytes).filterNot { chunk ->
            chunk.type == "tEXt" && runCatching {
                PngText.decode(chunk).keyword.lowercase() in setOf(KEYWORD_V2, KEYWORD_V3)
            }.getOrDefault(false)
        }
        return Png.writeChunks(chunks)
    }

    /** 该 PNG 是否带有卡数据。 */
    fun hasCardData(pngBytes: ByteArray): Boolean = runCatching {
        val texts = Png.readChunks(pngBytes).filter { it.type == "tEXt" }.map(PngText::decode)
        texts.any {
            it.keyword.lowercase() == KEYWORD_V2 || it.keyword.lowercase() == KEYWORD_V3
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------ base64

    private fun encodeBase64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /**
     * 宽容的 base64 解码。
     *
     * Node 的 `Buffer.from(s, 'base64')` 会忽略非法字符，Java 的标准解码器则会抛异常，
     * 所以这里先过滤字符、必要时补 padding，尽量贴近 Node 的行为。
     */
    private fun decodeBase64(text: String): String {
        val cleaned = text.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        val candidates = listOf(cleaned, cleaned.padEnd(cleaned.length + (4 - cleaned.length % 4) % 4, '='))
        for (candidate in candidates) {
            try {
                return String(Base64.getMimeDecoder().decode(candidate), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // 试下一个
            }
        }
        throw CardFormatException("tEXt 块中的卡数据不是合法 base64")
    }
}
