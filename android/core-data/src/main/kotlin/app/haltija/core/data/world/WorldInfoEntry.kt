package app.haltija.core.data.world

import app.haltija.core.data.json.bool
import app.haltija.core.data.json.int
import app.haltija.core.data.json.obj
import app.haltija.core.data.json.text
import app.haltija.core.data.json.textList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `selectiveLogic` 取值。
 *
 * 取自 `public/scripts/world-info.js:33` 的 `world_info_logic`。
 */
enum class SelectiveLogic(val value: Int) {
    /** 主键命中，且任意一个次键命中。 */
    AND_ANY(0),

    /** 主键命中，且不是所有次键都命中。 */
    NOT_ALL(1),

    /** 主键命中，且没有任何次键命中。 */
    NOT_ANY(2),

    /** 主键命中，且所有次键都命中。 */
    AND_ALL(3),
    ;

    companion object {
        fun from(value: Int?): SelectiveLogic? = entries.firstOrNull { it.value == value }
    }
}

/**
 * `position` 取值。
 *
 * 取自 `public/scripts/world-info.js:855` 的 `world_info_position`。
 */
enum class WorldInfoPosition(val value: Int) {
    /** 角色定义之前。 */
    BEFORE(0),

    /** 角色定义之后。 */
    AFTER(1),

    /** Author's Note 之前。 */
    AN_TOP(2),

    /** Author's Note 之后。 */
    AN_BOTTOM(3),

    /** 按 [WorldInfoEntry.depth] 插进聊天历史。 */
    AT_DEPTH(4),

    /** 示例消息之前。 */
    EM_TOP(5),

    /** 示例消息之后。 */
    EM_BOTTOM(6),

    /** 放进 outlet，由提示词模板按名字取用。 */
    OUTLET(7),
    ;

    companion object {
        fun from(value: Int?): WorldInfoPosition? = entries.firstOrNull { it.value == value }
    }
}

/**
 * 世界书里的一条条目。
 *
 * 字段全集来自 `public/scripts/world-info.js:4082` 的 `newWorldInfoEntryDefinition`。
 * 与另外两个数据模型一样的取舍：原始 [JsonObject] 是唯一真相来源，
 * 这里只提供类型化读取入口，保证未知字段（比如未来版本新增的键）不会在往返中丢失。
 *
 * @param jsonKey 条目在 `entries` 对象里的键。正常情况下等于 uid 的十进制字符串，
 *   但外部工具生成的文件可能不是，所以单独保留。
 */
class WorldInfoEntry(val raw: JsonObject, val jsonKey: String) {

    /** 条目 id。缺省时退化为 [jsonKey] 的数字形式。 */
    val uid: Int = raw.int("uid") ?: jsonKey.toIntOrNull() ?: -1

    /** 主键，支持 `/正则/` 写法。 */
    val keys: List<String> = raw.textList("key")

    /** 次键，配合 [selectiveLogic] 使用。 */
    val secondaryKeys: List<String> = raw.textList("keysecondary")

    /** 备注，只给人看，不进提示词。 */
    val comment: String = raw.text("comment").orEmpty()

    /** 真正注入提示词的内容。 */
    val content: String = raw.text("content").orEmpty()

    /** 无条件注入，不需要任何键命中。 */
    val constant: Boolean = raw.bool("constant") ?: false

    val selective: Boolean = raw.bool("selective") ?: true

    val selectiveLogic: SelectiveLogic? = SelectiveLogic.from(raw.int("selectiveLogic"))

    /** 注入顺序，值大的排在后面。 */
    val order: Int = raw.int("order") ?: 100

    val position: WorldInfoPosition? = WorldInfoPosition.from(raw.int("position"))

    /** `position = atDepth` 时插入的位置（距聊天末尾多少条）。 */
    val depth: Int = raw.int("depth") ?: 4

    /** 停用。停用条目**永远不应被激活**。 */
    val disable: Boolean = raw.bool("disable") ?: false

    /** 触发概率百分比。 */
    val probability: Int = raw.int("probability") ?: 100

    val useProbability: Boolean = raw.bool("useProbability") ?: true

    /** 同一分组的条目只会有一个被注入（除非 [groupOverride]）。 */
    val group: String = raw.text("group").orEmpty()

    val groupOverride: Boolean = raw.bool("groupOverride") ?: false

    val groupWeight: Int = raw.int("groupWeight") ?: 100

    /** 是否把用户 persona 描述也纳入本条目的扫描范围。 */
    val matchPersonaDescription: Boolean = raw.bool("matchPersonaDescription") ?: false

    val matchCharacterDescription: Boolean = raw.bool("matchCharacterDescription") ?: false
    val matchCharacterPersonality: Boolean = raw.bool("matchCharacterPersonality") ?: false
    val matchCharacterDepthPrompt: Boolean = raw.bool("matchCharacterDepthPrompt") ?: false
    val matchScenario: Boolean = raw.bool("matchScenario") ?: false
    val matchCreatorNotes: Boolean = raw.bool("matchCreatorNotes") ?: false

    /** 群组评分开关；null 表示跟随全局设置。 */
    val useGroupScoring: Boolean? = raw.bool("useGroupScoring")

    /** `position = outlet` 时使用的 outlet 名字。 */
    val outletName: String = raw.text("outletName").orEmpty()

    /** 编辑器里是否展开备注。对激活没有影响。 */
    val addMemo: Boolean = raw.bool("addMemo") ?: false

    /**
     * 是否走向量检索。
     *
     * 注意：SillyTavern 1.19.0 的 `checkWorldInfo` **完全没有使用**这个字段
     * （见 `docs/worldinfo-engine-spec.md` 第 10 节），
     * vectorized 条目与普通条目一样按键匹配。这里保留只是为了无损往返。
     */
    val vectorized: Boolean = raw.bool("vectorized") ?: false

    /** 扫描深度；null 表示跟随全局设置。 */
    val scanDepth: Int? = raw.int("scanDepth")

    val caseSensitive: Boolean? = raw.bool("caseSensitive")
    val matchWholeWords: Boolean? = raw.bool("matchWholeWords")

    /** 命中后，本条目的内容是否允许再触发其它条目。 */
    val excludeRecursion: Boolean = raw.bool("excludeRecursion") ?: false

    /** 本条目的内容是否允许触发其它条目。 */
    val preventRecursion: Boolean = raw.bool("preventRecursion") ?: false

    /**
     * 延迟到第几轮递归才参与。
     *
     * ST 里这个字段可以是布尔或数字：`entry.delayUntilRecursion === true ? 1 : entry.delayUntilRecursion`
     * （`world-info.js:4757`）。`true` 与 `1` 恰好都归一到层级 1，所以这里直接把布尔真读成 1。
     */
    val delayUntilRecursion: Int = run {
        val element = raw["delayUntilRecursion"] as? JsonPrimitive
        when {
            element == null -> 0
            element.isString -> element.content.toIntOrNull() ?: 0
            else -> element.booleanOrNull?.let { if (it) 1 else 0 } ?: element.content.toIntOrNull() ?: 0
        }
    }

    val ignoreBudget: Boolean = raw.bool("ignoreBudget") ?: false

    /** 注入的消息角色（0 = system，1 = user，2 = assistant）。 */
    val role: Int? = raw.int("role")

    /** 激活后保持多少条消息。 */
    val sticky: Int? = raw.int("sticky")

    /** 触发后多少条消息内不再触发。 */
    val cooldown: Int? = raw.int("cooldown")

    /** 触发后延迟多少条消息才生效。 */
    val delay: Int? = raw.int("delay")

    val triggers: List<String> = raw.textList("triggers")

    /** 是否为 `/正则/` 形式的主键。 */
    val hasRegexKey: Boolean get() = keys.any(::looksLikeRegex)

    fun toJsonObject(): JsonObject = raw

    override fun toString(): String =
        "WorldInfoEntry(uid=$uid, keys=$keys, order=$order, position=$position, disabled=$disable)"

    companion object {
        /**
         * ST 对正则键的判定：以 `/` 开头且以 `/` 结尾（可带 `i` 等修饰符）。
         * 见 `public/scripts/world-info.js` 的 `isRegex`。
         */
        fun looksLikeRegex(key: String): Boolean {
            val trimmed = key.trim()
            if (!trimmed.startsWith("/")) return false
            val lastSlash = trimmed.lastIndexOf('/')
            return lastSlash > 0
        }
    }
}
