package app.silly.core.prompt.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** 计时效果的类型。 */
enum class TimedEffectType(val key: String) {
    /** 激活后保持 N 条消息。 */
    STICKY("sticky"),

    /** 触发后 N 条消息内不再触发。 */
    COOLDOWN("cooldown"),

    /** 聊天不足 N 条时屏蔽（**不持久化、不消耗**）。 */
    DELAY("delay"),
}

/**
 * 一条计时效果。
 *
 * 对应 ST 写进 `chat_metadata.timedWorldInfo[type][key]` 的对象
 * （`world-info.js:604-611`）：
 *
 * ```jsonc
 * { "hash": 5059922895146125, "start": 12, "end": 15, "protected": false }
 * ```
 *
 * 单位是「当前聊天消息条数」（**过滤后的 coreChat**，不含 system 消息）。
 */
data class TimedEffect(
    val hash: Long,
    val start: Int,
    val end: Int,
    /** 受保护的效果不会因「聊天未推进」被取消。sticky 结束时新建的 cooldown 就是受保护的。 */
    val isProtected: Boolean,
) {
    fun toJsonObject(): JsonObject = JsonObject(
        linkedMapOf(
            "hash" to JsonPrimitive(hash),
            "start" to JsonPrimitive(start),
            "end" to JsonPrimitive(end),
            "protected" to JsonPrimitive(isProtected),
        ),
    )

    companion object {
        /** 宽容解析：结构不对时返回 null，由调用方丢弃。 */
        fun fromJson(obj: JsonObject?): TimedEffect? {
            if (obj == null) return null
            return try {
                TimedEffect(
                    hash = obj["hash"]?.jsonPrimitive?.long ?: return null,
                    start = obj["start"]?.jsonPrimitive?.int ?: return null,
                    end = obj["end"]?.jsonPrimitive?.int ?: return null,
                    isProtected = obj["protected"]?.jsonPrimitive?.boolean ?: false,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 可持久化的计时效果状态，直接对应 `chat_metadata.timedWorldInfo`。
 *
 * key 是 `"${world}.${uid}"`。**delay 不在这里** —— ST 不持久化它。
 */
class TimedWorldInfo(
    val sticky: MutableMap<String, TimedEffect> = linkedMapOf(),
    val cooldown: MutableMap<String, TimedEffect> = linkedMapOf(),
) {
    fun mapFor(type: TimedEffectType): MutableMap<String, TimedEffect>? = when (type) {
        TimedEffectType.STICKY -> sticky
        TimedEffectType.COOLDOWN -> cooldown
        TimedEffectType.DELAY -> null
    }

    fun toJsonObject(): JsonObject = JsonObject(
        linkedMapOf(
            "sticky" to JsonObject(sticky.mapValues { it.value.toJsonObject() }),
            "cooldown" to JsonObject(cooldown.mapValues { it.value.toJsonObject() }),
        ),
    )

    override fun toString(): String = "TimedWorldInfo(sticky=${sticky.size}, cooldown=${cooldown.size})"

    companion object {
        /** 从 `chat_metadata.timedWorldInfo` 解析；结构与 ST 的 `#ensureChatMetadata` 一致地做清洗。 */
        fun fromJson(obj: JsonObject?): TimedWorldInfo {
            val result = TimedWorldInfo()
            for ((typeName, target) in listOf("sticky" to result.sticky, "cooldown" to result.cooldown)) {
                val raw = obj?.get(typeName) as? JsonObject ?: continue
                for ((key, value) in raw) {
                    TimedEffect.fromJson(value as? JsonObject)?.let { target[key] = it }
                }
            }
            return result
        }

        val EMPTY = TimedWorldInfo()
    }
}

/**
 * 计时效果引擎，对应 `WorldInfoTimedEffects`（`world-info.js:479-790`）。
 *
 * ## 生命周期
 *
 * 一次扫描开始时调 [checkTimedEffects]（把仍在生效的条目填进内部缓冲），
 * 扫描过程中用 [isEffectActive] 查询，扫描结束时调 [setTimedEffects] 写入新效果，
 * 最后 [cleanUp] 清缓冲。
 *
 * ## 与 ST 的一处有意差异
 *
 * ST 只用**条目哈希**查找条目；哈希包含 `content`，所以条目一被编辑，效果就失联。
 * 这里先按哈希找，找不到再退回 `world.uid`。
 * 这样做唯一的实际影响是：从桌面 ST 那边继承来的 `timedWorldInfo`
 * （哈希由 JS 的 `JSON.stringify` 算出，与我们的键序未必一致）在本 App 里**仍能正确生效**，
 * 而 ST 自己在这种情况下会静默丢弃。这是更健壮的方向，不会让本 App 的行为偏离 ST 的语义。
 */
class TimedEffects(
    private val chatLength: Int,
    private val entries: List<ScanEntry>,
    private val isDryRun: Boolean,
    val state: TimedWorldInfo = TimedWorldInfo.EMPTY,
) {
    private val stickyBuffer = ArrayList<ScanEntry>()
    private val cooldownBuffer = ArrayList<ScanEntry>()
    private val delayBuffer = ArrayList<ScanEntry>()

    private val byHash: Map<Long, ScanEntry> = entries.associateBy { it.hash }
    private val byKey: Map<String, ScanEntry> = entries.associateBy { it.key }

    /** 对应 `checkTimedEffects()`（L682-688）。 */
    fun checkTimedEffects() {
        if (!isDryRun) {
            // 顺序很重要：先处理 sticky，它到期的回调会立刻塞进 cooldown 缓冲
            checkTimedEffectOfType(TimedEffectType.STICKY, stickyBuffer)
            checkTimedEffectOfType(TimedEffectType.COOLDOWN, cooldownBuffer)
        }
        // delay 在 dry run 下也生效
        checkDelayEffect(delayBuffer)
    }

    /**
     * 对应 `#checkTimedEffectOfType`（L619-660）。
     *
     * 状态机的四种走向：
     *  1. 聊天未推进（`chatLength <= start`）且非受保护 → 取消；
     *  2. 找不到条目 → 只有过了 `end` 才清掉，否则先留着（可能下次还能对上）；
     *  3. 条目已不再配置这个效果 → 清掉；
     *  4. 已过 `end` → 清掉，并触发 `onEnded`；
     *  5. 其余 → 视为生效中，进缓冲。
     */
    private fun checkTimedEffectOfType(type: TimedEffectType, buffer: MutableList<ScanEntry>) {
        val effects = state.mapFor(type) ?: return
        val expired = ArrayList<String>()

        for ((key, value) in effects) {
            val entry = byHash[value.hash] ?: byKey[key]

            if (chatLength <= value.start && !value.isProtected) {
                expired += key
                continue
            }

            if (entry == null) {
                if (chatLength >= value.end) expired += key
                continue
            }

            if (!hasEffect(entry, type)) {
                expired += key
                continue
            }

            if (chatLength >= value.end) {
                expired += key
                onEnded(type, entry)
                continue
            }

            buffer += entry
        }

        for (key in expired) effects.remove(key)
    }

    /**
     * 效果到期回调。对应 ST 的 `#onEnded`（L518-529）。
     *
     * **sticky 到期时若条目配了 cooldown，会新建一个 `protected: true` 的冷却效果，
     * 并立刻让它在本次扫描生效** —— 这是「sticky 结束后进入冷却」的实现方式。
     */
    private fun onEnded(type: TimedEffectType, entry: ScanEntry) {
        if (type != TimedEffectType.STICKY) return
        val cooldown = entry.entry.cooldown ?: return

        val effect = newEffect(cooldown, entry, isProtected = true)
        state.cooldown[entry.key] = effect
        cooldownBuffer += entry
    }

    /** 对应 `#checkDelayEffect`（L666-677）。 */
    private fun checkDelayEffect(buffer: MutableList<ScanEntry>) {
        for (entry in entries) {
            val delay = entry.entry.delay ?: continue
            if (chatLength < delay) buffer += entry
        }
    }

    /** 对应 `isEffectActive`（L777-783）。 */
    fun isEffectActive(type: TimedEffectType, entry: ScanEntry): Boolean = when (type) {
        TimedEffectType.STICKY -> stickyBuffer.any { it.hash == entry.hash }
        TimedEffectType.COOLDOWN -> cooldownBuffer.any { it.hash == entry.hash }
        TimedEffectType.DELAY -> delayBuffer.any { it.hash == entry.hash }
    }

    /**
     * 对应 `setTimedEffects`（L730-736）与 `#setTimedEffectOfType`（L710-724）。
     *
     * **已存在的不刷新** —— 条目再次命中不会延长效果，
     * 只有效果到期被删掉之后再激活才会重新写入。
     */
    fun setTimedEffects(activatedEntries: List<ScanEntry>) {
        if (isDryRun) return
        for (entry in activatedEntries) {
            setTimedEffectOfType(TimedEffectType.STICKY, entry)
            setTimedEffectOfType(TimedEffectType.COOLDOWN, entry)
        }
    }

    private fun setTimedEffectOfType(type: TimedEffectType, entry: ScanEntry) {
        // 与 ST 的 `if (!entry[type]) return` 一致：字段缺失**或为 0** 都视为没配这个效果
        val duration = durationOf(entry, type) ?: return
        val effects = state.mapFor(type) ?: return
        if (effects.containsKey(entry.key)) return
        effects[entry.key] = newEffect(duration, entry, isProtected = false)
    }

    private fun newEffect(duration: Int, entry: ScanEntry, isProtected: Boolean) = TimedEffect(
        hash = entry.hash,
        start = chatLength,
        end = chatLength + duration,
        isProtected = isProtected,
    )

    fun cleanUp() {
        stickyBuffer.clear()
        cooldownBuffer.clear()
        delayBuffer.clear()
    }

    /** 该效果的有效时长；未配置**或为 0** 时返回 null（对应 JS 的假值判断）。 */
    private fun durationOf(entry: ScanEntry, type: TimedEffectType): Int? = when (type) {
        TimedEffectType.STICKY -> entry.entry.sticky
        TimedEffectType.COOLDOWN -> entry.entry.cooldown
        TimedEffectType.DELAY -> entry.entry.delay
    }?.takeIf { it != 0 }

    /** 是否配置了该效果（对应 `entry[type]` 的真值判断）。 */
    private fun hasEffect(entry: ScanEntry, type: TimedEffectType): Boolean =
        durationOf(entry, type) != null
}
