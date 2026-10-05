package app.silly.core.data.store

import app.silly.core.data.card.CharacterCard
import app.silly.core.data.card.CharacterCardCodec
import app.silly.core.data.chat.Chat
import app.silly.core.data.chat.ChatFile
import app.silly.core.data.preset.Preset
import app.silly.core.data.preset.PresetKind
import app.silly.core.data.preset.PresetStore
import app.silly.core.data.world.WorldBook

/** 目录布局常量。与 SillyTavern 的 `data/<user>/` 一一对应。 */
object StPaths {
    const val CHARACTERS = "characters"
    const val CHATS = "chats"
    const val WORLDS = "worlds"
    const val PRESETS = "presets"
    const val GROUPS = "groups"
    const val GROUP_CHATS = "group chats"
    const val BACKGROUNDS = "backgrounds"
    const val THEMES = "themes"
    const val SETTINGS = "settings.json"

    /** 角色卡支持的扩展名。 */
    val CARD_EXTENSIONS = setOf("png", "json", "charx")

    /**
     * 某个角色的聊天子目录名。
     *
     * ST 用的是**头像文件名去掉扩展名**（`src/endpoints/chats.js:594`），
     * 不是角色的显示名 —— 两者常常不一样，写错就找不到历史。
     */
    fun chatDirectoryFor(avatarFileName: String): String =
        CHATS + "/" + avatarFileName.substringBeforeLast('.')
}

/** 一个角色目录项。 */
data class CharacterEntry(
    /** `characters/` 下的文件名，ST 里它就是角色 id。 */
    val fileName: String,
    val card: CharacterCard,
    /** 头像图片字节（PNG 卡就是文件本身；JSON/CharX 卡可能为空）。 */
    val avatar: ByteArray?,
) {
    val displayName: String get() = card.name.ifBlank { fileName.substringBeforeLast('.') }

    override fun equals(other: Any?): Boolean =
        other is CharacterEntry && fileName == other.fileName && card.toJsonObject() == other.card.toJsonObject()

    override fun hashCode(): Int = 31 * fileName.hashCode() + card.toJsonObject().hashCode()
}

/** 一份聊天的目录项。 */
data class ChatEntry(
    val fileName: String,
    val chat: Chat,
) {
    /** 不含扩展名的名字，用于展示。 */
    val displayName: String get() = fileName.removeSuffix(".jsonl")

    val messageCount: Int get() = chat.size
}

/**
 * 角色卡仓库。
 *
 * ## 一个性能提醒
 *
 * PNG 卡的头像**就是文件本身**，所以列目录时必须把每个文件读进来才能拿到名字与头像。
 * 一个 500KB 的卡、100 个角色就是 50MB 的读取。
 * 真实使用需要加缩略图缓存 —— 但那属于 UI 层的优化，不该让格式层承担。
 */
class CharacterRepository(private val root: StDataRoot) {

    /** 列出全部角色卡，按显示名排序。 */
    fun list(): List<CharacterEntry> {
        val names = root.listFiles(StPaths.CHARACTERS, StPaths.CARD_EXTENSIONS)
        return names.mapNotNull { name -> load(name) }.sortedBy { it.displayName.lowercase() }
    }

    /** 加载单个角色卡；解析失败返回 null（坏卡不该让整个列表挂掉）。 */
    fun load(fileName: String): CharacterEntry? {
        val path = StDataRoot.joinPath(StPaths.CHARACTERS, fileName)
        val bytes = root.read(path) ?: return null

        return try {
            val card = CharacterCardCodec.read(bytes)
            val avatar = if (fileName.endsWith(".png", ignoreCase = true)) bytes else null
            CharacterEntry(fileName, card, avatar)
        } catch (_: Exception) {
            null
        }
    }

    /** 写回角色卡。PNG 卡需要一张底图；[CharacterCardCodec.writePng] 会保留原有图像字节。 */
    fun savePng(fileName: String, card: CharacterCard, basePng: ByteArray) {
        val path = StDataRoot.joinPath(StPaths.CHARACTERS, fileName)
        root.write(path, CharacterCardCodec.writePng(card, basePng))
    }

    fun saveJson(fileName: String, card: CharacterCard) {
        val path = StDataRoot.joinPath(StPaths.CHARACTERS, fileName)
        root.write(path, CharacterCardCodec.writeJson(card))
    }

    fun delete(fileName: String) {
        root.delete(StDataRoot.joinPath(StPaths.CHARACTERS, fileName))
    }
}

/**
 * 聊天仓库。
 *
 * 目录结构：`chats/<头像名去扩展名>/<角色名> - <时间>.jsonl`。
 */
class ChatRepository(private val root: StDataRoot) {

    /** 列出某个角色的全部聊天，最近的在前。 */
    fun list(avatarFileName: String): List<ChatEntry> {
        val directory = StPaths.chatDirectoryFor(avatarFileName)
        return root.listFiles(directory, setOf("jsonl"))
            .mapNotNull { name ->
                val bytes = root.read(StDataRoot.joinPath(directory, name)) ?: return@mapNotNull null
                try {
                    ChatEntry(name, ChatFile.parse(bytes))
                } catch (_: Exception) {
                    null
                }
            }
            // ST 的文件名里带时间戳，字典序倒排即「最近的在前」
            .sortedByDescending { it.fileName }
    }

    fun load(avatarFileName: String, chatFileName: String): Chat? {
        val path = StDataRoot.joinPath(StPaths.chatDirectoryFor(avatarFileName), chatFileName)
        val bytes = root.read(path) ?: return null
        return ChatFile.parse(bytes)
    }

    /** 写回聊天。目录不存在时自动创建。 */
    fun save(avatarFileName: String, chatFileName: String, chat: Chat) {
        val directory = StPaths.chatDirectoryFor(avatarFileName)
        root.mkdirs(directory)
        root.write(StDataRoot.joinPath(directory, chatFileName), ChatFile.writeBytes(chat))
    }

    fun delete(avatarFileName: String, chatFileName: String) {
        root.delete(StDataRoot.joinPath(StPaths.chatDirectoryFor(avatarFileName), chatFileName))
    }

    /**
     * 生成一个新聊天文件名，格式与 ST 完全一致。
     *
     * ST 是 `${character.name} - ${humanizedDateTime()}.jsonl`（`script.js:1397`），
     * 也就是 `Seraphina - 2026-10-05@16h30m45s123ms.jsonl`。
     * 时间用**本地时区** —— 与 [StTimestamp.humanized] 的约定一致。
     */
    fun newChatFileName(
        characterName: String,
        instant: java.time.Instant,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): String = "${sanitize(characterName)} - ${StTimestamp.humanized(instant, zone)}.jsonl"

    private fun sanitize(name: String): String =
        name.map { if (it in ILLEGAL_FILENAME_CHARS) '_' else it }.joinToString("")

    private companion object {
        val ILLEGAL_FILENAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    }
}

/** 世界书仓库。 */
class WorldInfoRepository(private val root: StDataRoot) {

    /** 列出全部世界书（按名字排序）。 */
    fun listNames(): List<String> =
        root.listFiles(StPaths.WORLDS, setOf("json")).map { it.removeSuffix(".json") }.sorted()

    fun load(name: String): WorldBook? {
        val bytes = root.read(StDataRoot.joinPath(StPaths.WORLDS, "$name.json")) ?: return null
        return try {
            WorldBook.parse(bytes, name)
        } catch (_: Exception) {
            null
        }
    }

    fun save(book: WorldBook, name: String = book.name) {
        root.write(StDataRoot.joinPath(StPaths.WORLDS, "$name.json"), WorldBook.encode(book).toByteArray())
    }

    fun delete(name: String) {
        root.delete(StDataRoot.joinPath(StPaths.WORLDS, "$name.json"))
    }
}

/** 预设仓库。 */
class PresetRepository(private val root: StDataRoot) {

    fun list(kind: PresetKind): List<Preset> {
        val directory = StDataRoot.joinPath(StPaths.PRESETS, kind.directory)
        return root.listFiles(directory, setOf("json")).mapNotNull { name ->
            val bytes = root.read(StDataRoot.joinPath(directory, name)) ?: return@mapNotNull null
            try {
                PresetStore.parse(kind, bytes, name.removeSuffix(".json"))
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 面向数据根的门面：一次拿到四个仓库。
 *
 * @param userHandle `data/` 下的用户名目录，ST 默认是 `default-user`。
 *                   传空串表示数据根就是用户目录本身（SAF 选中 `default-user` 时）。
 */
class SillyTavernData(
    val root: StDataRoot,
    val userHandle: String = "default-user",
) {
    /** 把用户目录拼进相对路径。 */
    private fun scoped(inner: StDataRoot): StDataRoot =
        if (userHandle.isEmpty()) inner else PrefixStDataRoot(inner, userHandle)

    private val scopedRoot by lazy { scoped(root) }

    val characters by lazy { CharacterRepository(scopedRoot) }
    val chats by lazy { ChatRepository(scopedRoot) }
    val worlds by lazy { WorldInfoRepository(scopedRoot) }
    val presets by lazy { PresetRepository(scopedRoot) }

    /** 该数据根里是否真的像一份 ST 数据（用于选目录后做一次校验）。 */
    fun looksLikeStData(): Boolean =
        root.isDirectory(StDataRoot.joinPath(userHandle, StPaths.CHARACTERS)) ||
            root.isDirectory(StDataRoot.joinPath(userHandle, StPaths.CHATS)) ||
            root.isDirectory(StDataRoot.joinPath(userHandle, StPaths.WORLDS)) ||
            root.isDirectory(StDataRoot.joinPath(userHandle, StPaths.PRESETS))
}

/** 给另一个数据根加路径前缀。用于把 `default-user` 这层折进相对路径。 */
private class PrefixStDataRoot(
    private val delegate: StDataRoot,
    private val prefix: String,
) : StDataRoot {

    override val label: String get() = delegate.label

    private fun under(path: String) = StDataRoot.joinPath(prefix, path)

    override fun exists(path: String) = delegate.exists(under(path))
    override fun isDirectory(path: String) = delegate.isDirectory(under(path))
    override fun list(path: String) = delegate.list(under(path))
    override fun read(path: String) = delegate.read(under(path))
    override fun write(path: String, bytes: ByteArray) = delegate.write(under(path), bytes)
    override fun delete(path: String) = delegate.delete(under(path))
    override fun mkdirs(path: String) = delegate.mkdirs(under(path))
}
