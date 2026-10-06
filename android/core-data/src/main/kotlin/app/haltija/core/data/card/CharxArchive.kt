package app.haltija.core.data.card

import app.haltija.core.data.json.array
import app.haltija.core.data.json.obj
import app.haltija.core.data.json.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** CharX 归档的解析结果。 */
class CharxContents(
    val card: CharacterCard,
    /** 归档内全部条目，键是 zip 内的规范路径。 */
    val entries: Map<String, ByteArray>,
    /** 按 `data.assets` 声明解析出来的资源，键是 zip 内的规范路径。 */
    val assetBuffers: Map<String, ByteArray>,
) {
    /** 头像资源，挑选规则与 SillyTavern 的 `pickCharXIconAsset` 一致。 */
    val icon: ByteArray?
        get() {
            val declared = card.assets ?: return null
            val candidates = declared.mapNotNull { el ->
                val asset = el as? JsonObject ?: return@mapNotNull null
                if (asset.string("type") != "icon") return@mapNotNull null
                val ext = asset.string("ext")?.lowercase()?.removePrefix(".") ?: return@mapNotNull null
                if (ext !in IMAGE_EXTENSIONS) return@mapNotNull null
                val zipPath = asset.string("uri")?.let(CharxArchive::embeddedZipPath) ?: return@mapNotNull null
                val bytes = assetBuffers[zipPath] ?: return@mapNotNull null
                Triple(asset.string("name"), zipPath, bytes)
            }
            // 优先取 name == "main"，否则取第一个 —— 与 ST 一致
            return (candidates.firstOrNull { it.first?.lowercase() == "main" } ?: candidates.firstOrNull())?.third
        }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "apng", "avif", "bmp", "jfif")
    }
}

/**
 * CharX 归档读取。
 *
 * CharX 就是一个 zip，内含 `card.json`（CCv2 / CCv3）以及被 `data.assets`
 * 用 `embeded://` 之类 URI 引用的资源文件。
 *
 * 行为对齐 SillyTavern 的 `CharXParser`：
 *  - 支持「自解压」归档 —— zip 数据前面可以有多余字节，靠扫描 `PK\x03\x04` 定位；
 *  - `card.json` 用后缀匹配，且跳过 `__MACOSX` 条目；
 *  - `card.json` 必须存在且带 `spec` 字段。
 */
object CharxArchive {

    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** RisuAI 导出用的是拼错的 `embeded://`，三种前缀都要认。 */
    private val EMBEDDED_URI_PREFIXES = listOf("embeded://", "embedded://", "__asset:")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isZip(bytes: ByteArray): Boolean = bytes.indexOfSequence(ZIP_SIGNATURE) >= 0

    /** 扫描 zip 起始偏移；找不到时返回 0（与 ST 的 `findZipStart` 一致）。 */
    fun findZipStart(bytes: ByteArray): Int {
        val idx = bytes.indexOfSequence(ZIP_SIGNATURE)
        return if (idx > 0) idx else 0
    }

    fun read(bytes: ByteArray): CharxContents {
        val start = findZipStart(bytes)
        val entries = LinkedHashMap<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes, start, bytes.size - start)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        if (entries.isEmpty()) throw CardFormatException("CharX 归档为空或不是合法 zip")

        val cardEntry = entries.entries.firstOrNull { (name, _) ->
            name.endsWith("card.json") && !name.startsWith("__MACOSX")
        } ?: throw CardFormatException("CharX 归档中找不到 card.json")

        val root = json.parseToJsonElement(cardEntry.value.toString(Charsets.UTF_8)) as? JsonObject
            ?: throw CardFormatException("card.json 的顶层不是 JSON 对象")
        if (!root.containsKey("spec")) {
            throw CardFormatException("CharX 的 card.json 缺少 spec 字段")
        }

        val card = CharacterCard(root)

        // 解析 data.assets 里声明的资源
        val assetBuffers = LinkedHashMap<String, ByteArray>()
        card.assets?.forEach { el ->
            val asset = el as? JsonObject ?: return@forEach
            val zipPath = asset.string("uri")?.let(::embeddedZipPath) ?: return@forEach
            entries[zipPath]?.let { assetBuffers[zipPath] = it }
        }

        return CharxContents(card, entries, assetBuffers)
    }

    /** 把 `embeded://assets/icon/main.png` 之类 URI 转成 zip 内规范路径。 */
    fun embeddedZipPath(uri: String): String? {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        val prefix = EMBEDDED_URI_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return null
        return normalizeZipEntryPath(trimmed.substring(prefix.length))
    }

    /** 反斜杠转正斜杠、去掉开头的 `./`、消解 `.` 与 `..`。 */
    fun normalizeZipEntryPath(entryName: String): String? {
        val unified = entryName.replace('\\', '/').trim()
        if (unified.isEmpty()) return null

        val segments = ArrayList<String>()
        for (part in unified.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments += part
            }
        }
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
