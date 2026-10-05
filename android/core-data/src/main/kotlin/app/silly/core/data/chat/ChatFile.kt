package app.silly.core.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 聊天文件格式错误。 */
class ChatFormatException(message: String) : IllegalArgumentException(message)

/** 解析结果，附带宽容解析时被丢弃的行号，便于 UI 提示而不是静默吞掉。 */
class ChatParseResult(
    val chat: Chat,
    /** 整体无法解析为 JSON、因而被跳过的行号（从 1 开始）。 */
    val droppedLineNumbers: List<Int>,
    /** 第一行是否被识别为头部。 */
    val hadHeader: Boolean,
)

/**
 * `chats/<角色>/<名字> - <时间>.jsonl` 的读写。
 *
 * 文件格式：
 *  - 第 1 行：头部（可能不存在，见下）；
 *  - 第 2..n 行：每条消息一行，紧凑 JSON。
 *
 * 行之间用 `\n` 连接，**结尾没有换行符** —— 与 ST 的
 * `chatData.map(m => JSON.stringify(m)).join('\n')` 一致。
 *
 * ### 头部识别
 *
 * ST 前端加载时**无条件** `data.shift()` 拿走第一行当头部
 * （`public/script.js:7656`），这对第三方导出、第一行就是消息的文件会误伤。
 * 这里采用更稳的判据：第一行是对象，且**不含** `mes` / `is_user` / `is_system`
 * 中的任何一个，才认定它是头部；否则视为无头部文件。
 *
 * 这个判据同时兼容 ST 的两种头部写法（现代 `unused` 风格与导入式真实名字风格），
 * 以及 ST 导入时使用的校验规则（`user_name` / `name` / `chat_metadata` 三者有其一）。
 */
object ChatFile {

    private val COMPACT: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val BOM = '\uFEFF'

    // ------------------------------------------------------------ 解析

    fun parse(text: String): Chat = parseDetailed(text).chat

    /** 字节重载：仓库层从文件读到的是 [ByteArray]。 */
    fun parse(bytes: ByteArray): Chat = parse(bytes.toString(StandardCharsets.UTF_8))

    fun parseDetailed(text: String): ChatParseResult {
        val rawLines = text.removePrefix(BOM.toString()).split('\n')
        val indexed = rawLines.withIndex().filter { it.value.isNotBlank() }
        if (indexed.isEmpty()) {
            return ChatParseResult(Chat.empty(), emptyList(), hadHeader = false)
        }

        val objects = ArrayList<Pair<Int, JsonObject>>(indexed.size)
        val dropped = ArrayList<Int>()
        for ((i, line) in indexed) {
            val obj = tryParseObject(line)
            if (obj == null) dropped += i + 1 else objects += (i + 1) to obj
        }

        val first = objects.firstOrNull()
        val hasHeader = first != null && !looksLikeMessage(first.second)

        val header: ChatHeader
        val messageObjects: List<JsonObject>
        if (hasHeader) {
            header = ChatHeader(first!!.second)
            messageObjects = objects.drop(1).map { it.second }
        } else {
            header = ChatHeader.of()
            messageObjects = objects.map { it.second }
        }

        return ChatParseResult(
            chat = Chat(header, messageObjects.map(::ChatMessage)),
            droppedLineNumbers = dropped,
            hadHeader = hasHeader,
        )
    }

    // ------------------------------------------------------------ 写出

    /** 序列化为 jsonl 文本（结尾无换行，与 ST 一致）。 */
    fun write(chat: Chat): String {
        val sb = StringBuilder()
        sb.append(encode(chat.header.toJsonObject()))
        for (message in chat.messages) {
            sb.append('\n').append(encode(message.toJsonObject()))
        }
        return sb.toString()
    }

    fun writeBytes(chat: Chat): ByteArray = write(chat).toByteArray(StandardCharsets.UTF_8)

    // ------------------------------------------------------------ 文件

    fun read(path: Path): Chat = parse(Files.readString(path, StandardCharsets.UTF_8))

    fun read(file: File): Chat = read(file.toPath())

    /**
     * 写回聊天文件。
     *
     * 用「先写临时文件再原子改名」的方式，避免写到一半进程被杀导致聊天记录截断
     * —— ST 自己也用 `writeFileAtomicSync`（`src/endpoints/chats.js:552`）。
     */
    fun write(path: Path, chat: Chat) {
        val parent = path.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, writeBytes(chat))
        Files.move(
            tmp, path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    fun write(file: File, chat: Chat) = write(file.toPath(), chat)

    // ------------------------------------------------------------ 内部

    private fun encode(obj: JsonObject): String = COMPACT.encodeToString(JsonObject.serializer(), obj)

    /** 一条消息必然带 `mes` 或 `is_user` / `is_system`；头部不会有这些。 */
    private fun looksLikeMessage(obj: JsonObject): Boolean =
        obj.containsKey("mes") || obj.containsKey("is_user") || obj.containsKey("is_system")

    private fun tryParseObject(line: String): JsonObject? = try {
        COMPACT.parseToJsonElement(line) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
