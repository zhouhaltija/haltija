package app.silly.core.prompt.engine

import app.silly.core.data.world.SelectiveLogic
import app.silly.core.data.world.WorldInfoEntry
import kotlinx.serialization.json.JsonObject

/** 扫描阶段。取值对齐 `public/scripts/world-info.js:43` 的 `scan_state`。 */
enum class ScanState(val value: Int) {
    /** 停止扫描。 */
    NONE(0),

    /** 首轮正常扫描。 */
    INITIAL(1),

    /** 递归扫描：扫描串会拼接已激活条目的内容。 */
    RECURSION(2),

    /**
     * 最小激活数未达标时，逐条扩大扫描深度重试。
     *
     * 注意：这一轮的扫描串**不包含**递归缓冲（`world-info.js:322`）。
     */
    MIN_ACTIVATIONS(3),
    ;

    /** 是否为「由递归触发」的轮次 —— 决定 `excludeRecursion` / `delayUntilRecursion` 是否生效。 */
    val isRecursion: Boolean get() = this == RECURSION
}

/** 条目来源的插入策略。对齐 `world_info_insertion_strategy`。 */
enum class InsertionStrategy(val value: Int) {
    /** 全局与角色书合并后整体按 order 排序。 */
    EVENLY(0),

    /** 角色书在前（默认）。 */
    CHARACTER_FIRST(1),

    /** 全局书在前。 */
    GLOBAL_FIRST(2),
    ;

    companion object {
        fun from(value: Int?): InsertionStrategy = entries.firstOrNull { it.value == value } ?: CHARACTER_FIRST
    }
}

/** 示例消息条目的插入锚点。对齐 `wi_anchor_position`（`world-info.js:866`）。 */
enum class WiAnchorPosition(val value: Int) {
    BEFORE(0),
    AFTER(1),
}

/**
 * 全局世界书设置。
 *
 * 默认值取自 `public/scripts/world-info.js:69-82`。
 */
data class WorldInfoSettings(
    /** 默认扫描深度（最近多少条消息）。 */
    val depth: Int = 2,

    /** 上下文预算占比（百分比）。 */
    val budgetPercent: Int = 25,

    /** 预算硬上限（token）。0 表示不封顶。 */
    val budgetCap: Int = 0,

    /** 扫描文本是否带 `"角色名: "` 前缀。 */
    val includeNames: Boolean = true,

    /** 是否允许递归扫描。 */
    val recursive: Boolean = false,

    /** 最少激活条目数；>0 时会在未达标时逐条加深重扫。 */
    val minActivations: Int = 0,

    /** 最小激活模式下的深度上限。0 表示不限。 */
    val minActivationsDepthMax: Int = 0,

    /** 扫描轮次总上限。0 表示不限（与 `minActivations` 互斥，仅 UI 层面）。 */
    val maxRecursionSteps: Int = 0,

    /** `caseSensitive` 缺省值。 */
    val caseSensitive: Boolean = false,

    /** `matchWholeWords` 缺省值。 */
    val matchWholeWords: Boolean = false,

    /** `useGroupScoring` 缺省值。 */
    val useGroupScoring: Boolean = false,

    val characterStrategy: InsertionStrategy = InsertionStrategy.CHARACTER_FIRST,

    /** 预算触顶时是否提示用户。 */
    val overflowAlert: Boolean = false,
) {
    companion object {
        val DEFAULT = WorldInfoSettings()
    }
}

/**
 * 与聊天无关、但可被条目 `match*` 开关纳入扫描范围的文本。
 *
 * 字段取自 `script.js:4626-4634`。
 */
data class GlobalScanData(
    val personaDescription: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterDepthPrompt: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    /**
     * 生成类型。不在 `GENERATION_TYPE_TRIGGERS`
     * （normal / continue / impersonate / swipe / regenerate / quiet）里的一律归一为 `"normal"`。
     */
    val trigger: String = "normal",
) {
    companion object {
        val EMPTY = GlobalScanData()

        /** 合法的生成类型，见 `constants.js:36-43`。 */
        val GENERATION_TYPE_TRIGGERS =
            setOf("normal", "continue", "impersonate", "swipe", "regenerate", "quiet")

        /** 把任意生成类型归一化。 */
        fun normalizeTrigger(trigger: String?): String =
            if (trigger != null && trigger in GENERATION_TYPE_TRIGGERS) trigger else "normal"
    }
}

/**
 * 一条进入扫描的条目：原始条目 + 它所属的世界书名 + 剥离装饰器后的内容。
 *
 * @param content 剥离 `@@` 装饰器行之后的正文。边界：若原文**每一行**都是 `@@` 行，
 *   ST 不会裁剪（`world-info.js:4672` 的 `newContent` 初值即原文，只有遇到普通行才覆盖），
 *   所以这里会等于原文。见 [Decorators.parse]。
 * @param decorators 解析出的装饰器，仅 `@@activate` / `@@dont_activate` 有意义。
 */
class ScanEntry(
    val entry: WorldInfoEntry,
    val world: String,
    val uid: Int,
    val content: String,
    val decorators: List<String>,
    /** 重写哈希的口子，仅测试用。 */
    val hashOverride: Long? = null,
) {
    val key: String get() = "$world.$uid"

    /**
     * 条目身份哈希（cyrb53，见 [StringHash]）。
     *
     * ST 用 `getStringHash(JSON.stringify(entry))` 算，其中 `entry` 已带上
     * `world` / `uid` / `decorators` 且 `content` 已剥离装饰器。
     * 这里按同样的字段顺序重建，但**键序取决于源文件的解析顺序**，
     * 所以不保证与 ST 逐位相同 —— 计时效果查找因此设计了 `world.uid` 兜底。
     */
    val hash: Long = hashOverride ?: StringHash.hash(canonicalJson().toString())

    private fun canonicalJson(): JsonObject {
        val base = entry.raw.toMutableMap()
        base["world"] = kotlinx.serialization.json.JsonPrimitive(world)
        base["uid"] = kotlinx.serialization.json.JsonPrimitive(uid)
        base["content"] = kotlinx.serialization.json.JsonPrimitive(content)
        base["decorators"] = kotlinx.serialization.json.JsonArray(
            decorators.map { kotlinx.serialization.json.JsonPrimitive(it) },
        )
        return JsonObject(base)
    }

    /** 方便直接读常用字段。 */
    val order: Int get() = entry.order
    val position: Int? get() = entry.position?.value
    val constant: Boolean get() = entry.constant
    val disable: Boolean get() = entry.disable
    val group: String get() = entry.group
    val scanDepth: Int? get() = entry.scanDepth

    override fun toString(): String = "ScanEntry($key, keys=${entry.keys}, order=$order)"

    /** 按 ST 的顺序做浅拷贝替换（用于测试与后续按需改写）。 */
    fun withContent(newContent: String): ScanEntry =
        ScanEntry(entry, world, uid, newContent, decorators, hashOverride)

    companion object {
        /**
         * 从世界书条目构造扫描条目：先剥离装饰器，再算哈希
         * （顺序很重要 —— ST 在 hash 计算**之前**就剥离了装饰器，
         * 并把 `decorators` 作为数组一起参与哈希）。
         */
        fun of(entry: WorldInfoEntry, world: String): ScanEntry {
            val parsed = Decorators.parse(entry.content)
            return ScanEntry(entry, world, entry.uid, parsed.content, parsed.decorators)
        }
    }
}
