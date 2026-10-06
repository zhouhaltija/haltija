package app.haltija.core.data.chat

import app.haltija.core.data.json.array
import app.haltija.core.data.json.bool
import app.haltija.core.data.json.int
import app.haltija.core.data.json.obj
import app.haltija.core.data.json.text
import app.haltija.core.data.json.textList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 聊天文件的第一行：头部。
 *
 * SillyTavern 有两种写法：
 *
 * ```jsonc
 * // ① 现代版本 saveChat 写出的（public/script.js:7428）
 * {"chat_metadata":{},"user_name":"unused","character_name":"unused"}
 *
 * // ② 老版本 / 第三方导入的
 * {"user_name":"User","character_name":"Seraphina","create_date":"...","chat_metadata":{}}
 * ```
 *
 * 注意 ① 里 `user_name` / `character_name` 是**字面量 `"unused"`**，不是真实名字；
 * 而且头部没有 `create_date` —— 那是角色字段，不属于聊天。
 */
class ChatHeader(val raw: JsonObject) {

    val metadata: JsonObject = raw.obj("chat_metadata") ?: JsonObject(emptyMap())

    val userName: String? = raw.text("user_name")
    val characterName: String? = raw.text("character_name")
    val createDate: String? = raw.text("create_date")

    /**
     * ST 的聊天完整性校验串。
     *
     * `public/script.js:7666` 在加载时会补一个 uuidv4，保存前服务端会比对
     * （`src/endpoints/chats.js:337`）。**写回时必须原样保留**，
     * 否则 ST 会判定校验失败并拒绝覆盖这个聊天文件。
     */
    val integrity: String? = metadata.text("integrity")

    /** 是否为现代 ST 写出的头部。 */
    val isModernStyle: Boolean
        get() = userName == "unused" && characterName == "unused"

    fun toJsonObject(): JsonObject = raw

    override fun toString(): String =
        "ChatHeader(modern=$isModernStyle, integrity=${integrity ?: "-"}, keys=${raw.keys})"

    companion object {
        /** 造一个现代 ST 风格的头部。 */
        fun of(metadata: JsonObject = JsonObject(emptyMap())): ChatHeader = ChatHeader(
            buildJsonObject {
                put("chat_metadata", metadata)
                put("user_name", "unused")
                put("character_name", "unused")
            },
        )
    }
}

/**
 * 一条聊天消息。
 *
 * 与 [app.haltija.core.data.card.CharacterCard] 同样的取舍：
 * 以原始 [JsonObject] 为唯一真相来源，只叠加类型化读取入口，
 * 保证 `extra`、`swipe_info`、第三方塞进去的键在读写往返中一字不差。
 */
class ChatMessage(val raw: JsonObject) {

    val name: String = raw.text("name").orEmpty()
    val isUser: Boolean = raw.bool("is_user") ?: false
    val isSystem: Boolean = raw.bool("is_system") ?: false
    val sendDate: String = raw.text("send_date").orEmpty()
    val mes: String = raw.text("mes").orEmpty()

    /** 消息级附加数据（附件、生成参数、正则命中记录等），必须原样保留。 */
    val extra: JsonObject = raw.obj("extra") ?: JsonObject(emptyMap())

    /** 全部候选回复；空列表表示这条消息没有 swipe。 */
    val swipes: List<String> = raw.textList("swipes")

    /** 当前显示的是第几个 swipe。 */
    val swipeId: Int = raw.int("swipe_id") ?: 0

    val swipeInfo: JsonArray? = raw.array("swipe_info")

    val forceAvatar: String? = raw.text("force_avatar")
    val originalAvatar: String? = raw.text("original_avatar")

    val hasSwipes: Boolean get() = swipes.isNotEmpty()

    /** 当前真正显示的内容。 */
    val activeText: String get() = swipes.getOrNull(swipeId) ?: mes

    /**
     * [mes] 是否与 `swipes[swipe_id]` 一致。
     *
     * ST 维持这个不变量（`public/script.js:6790` 每次生成都回写
     * `swipes[swipe_id] = mes`）。不一致说明文件被外部工具改过。
     */
    val isSwipeConsistent: Boolean get() = !hasSwipes || activeText == mes

    fun toJsonObject(): JsonObject = raw

    override fun toString(): String =
        "ChatMessage(name='$name', user=$isUser, system=$isSystem, mes='${mes.take(24)}')"

    companion object {
        /** 造一条新消息。字段顺序与 ST 一致，便于人眼比对。 */
        fun create(
            name: String,
            mes: String,
            isUser: Boolean,
            sendDate: String,
            isSystem: Boolean = false,
            extra: JsonObject = JsonObject(emptyMap()),
        ): ChatMessage = ChatMessage(
            buildJsonObject {
                put("name", name)
                put("is_user", isUser)
                put("is_system", isSystem)
                put("send_date", sendDate)
                put("mes", mes)
                put("extra", extra)
            },
        )
    }
}

/**
 * 一份聊天记录：头部 + 消息。
 *
 * 内存模型与 ST 前端一致 —— 头部独立于消息列表
 * （前端加载时 `data.shift()` 把头部取走，只把消息留在 `chat` 数组里）。
 */
class Chat(
    val header: ChatHeader,
    val messages: List<ChatMessage>,
) {
    val metadata: JsonObject get() = header.metadata

    val size: Int get() = messages.size

    val lastMessage: ChatMessage? get() = messages.lastOrNull()

    fun append(message: ChatMessage): Chat = Chat(header, messages + message)

    /** 找出违反 ST swipe 不变量的消息下标（正常文件应为空）。 */
    fun swipeInconsistencies(): List<Int> =
        messages.mapIndexedNotNull { i, m -> i.takeIf { !m.isSwipeConsistent } }

    override fun toString(): String = "Chat(${messages.size} messages, integrity=${header.integrity})"

    companion object {
        /** 空聊天（现代 ST 风格头部）。 */
        fun empty(metadata: JsonObject = JsonObject(emptyMap())): Chat =
            Chat(ChatHeader.of(metadata), emptyList())
    }
}
