package app.haltija.core.data.world

import app.haltija.core.data.json.obj
import app.haltija.core.data.json.text
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** 世界书文件格式错误。 */
class WorldInfoFormatException(message: String) : IllegalArgumentException(message)

/**
 * 一本世界书（`worlds/<名字>.json`）。
 *
 * 文件结构：
 *
 * ```jsonc
 * {
 *   "entries": {
 *     "0": { "uid": 0, "key": ["dragon"], "content": "...", ... },
 *     "1": { ... }
 *   },
 *   // 可选：ST 在列表接口里会读这两个字段（src/endpoints/worldinfo.js:52）
 *   "name": "书名",
 *   "extensions": {}
 * }
 * ```
 *
 * `entries` 的键正常情况下是 uid 的十进制字符串，但外部工具不保证，
 * 所以 [WorldInfoEntry.jsonKey] 单独保留原始键，写回时用原键。
 */
class WorldBook(
    val root: JsonObject,
    /** 对应的文件名（不含 `.json`），用作 [name] 的兜底。 */
    val fileName: String? = null,
) {

    private val rawEntries: JsonObject = root.obj("entries") ?: JsonObject(emptyMap())

    /** 全部条目，按 uid 升序。 */
    val entries: List<WorldInfoEntry> = rawEntries.entries
        .mapNotNull { (key, value) ->
            (value as? JsonObject)?.let { WorldInfoEntry(it, key) }
        }
        .sortedBy { it.uid }

    val size: Int get() = entries.size

    /** 书名。文件里有 `name` 就用它，否则退回文件名 —— 与 ST 的 `/list` 行为一致。 */
    val name: String = root.text("name") ?: fileName.orEmpty()

    val extensions: JsonObject? = root.obj("extensions")

    fun entry(uid: Int): WorldInfoEntry? = entries.firstOrNull { it.uid == uid }

    /** 下一个可用 uid：现有最大值 + 1（与 ST 的 `getFreeWorldEntryUid` 一致）。 */
    fun nextUid(): Int = (entries.maxOfOrNull { it.uid } ?: -1) + 1

    /** 返回新增了一条条目的新世界书（不改动本对象）。 */
    fun withEntry(entry: WorldInfoEntry): WorldBook {
        val merged = JsonObject(rawEntries + (entry.jsonKey to entry.toJsonObject()))
        return WorldBook(JsonObject(root + ("entries" to merged)), fileName)
    }

    /** 返回删除了指定 uid 的新世界书。 */
    fun withoutEntry(uid: Int): WorldBook {
        val target = entries.firstOrNull { it.uid == uid } ?: return this
        val merged = JsonObject(rawEntries - target.jsonKey)
        return WorldBook(JsonObject(root + ("entries" to merged)), fileName)
    }

    /** 原样返回，不做规范化 —— 用于无损写回。 */
    fun toJsonObject(): JsonObject = root

    override fun toString(): String = "WorldBook(name='$name', ${entries.size} entries)"

    companion object {
        private val READER: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            allowTrailingComma = true
        }

        private val COMPACT: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** 空世界书。 */
        fun empty(name: String? = null): WorldBook {
            val root = buildJsonObject {
                put("entries", JsonObject(emptyMap()))
                if (name != null) put("name", name)
            }
            return WorldBook(root, name)
        }

        fun parse(text: String, fileName: String? = null): WorldBook {
            val root = try {
                READER.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
            } catch (e: Exception) {
                throw WorldInfoFormatException("世界书不是合法 JSON：${e.message}")
            } ?: throw WorldInfoFormatException("世界书的顶层不是 JSON 对象")
            return WorldBook(root, fileName)
        }

        fun parse(bytes: ByteArray, fileName: String? = null): WorldBook =
            parse(bytes.toString(StandardCharsets.UTF_8), fileName)

        fun read(path: Path): WorldBook = parse(
            Files.readString(path, StandardCharsets.UTF_8),
            path.fileName.toString().removeSuffix(".json"),
        )

        fun read(file: File): WorldBook = read(file.toPath())

        /** 序列化为文件内容（紧凑 JSON + 结尾换行）。 */
        fun encode(book: WorldBook): String =
            COMPACT.encodeToString(JsonObject.serializer(), book.toJsonObject()) + "\n"

        fun write(path: Path, book: WorldBook) {
            val parent = path.parent
            if (parent != null) Files.createDirectories(parent)
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            Files.write(tmp, encode(book).toByteArray(StandardCharsets.UTF_8))
            Files.move(
                tmp, path,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }

        fun write(file: File, book: WorldBook) = write(file.toPath(), book)
    }
}
