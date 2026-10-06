package app.haltija.core.prompt.engine

import app.haltija.core.prompt.model.PromptRole

/**
 * 示例消息（EM）注入项。
 *
 * ST 把它按 [position] 前插 / 后插进 `mesExamplesArray`
 * （`script.js:4639-4655`）。
 */
data class EmEntry(val position: WiAnchorPosition, val content: String)

/**
 * `position = atDepth` 的条目分组。
 *
 * ST 按 `(depth ?? DEFAULT_DEPTH, role ?? SYSTEM)` 分组，组内文本用 `\n` 连接
 * 后走 `setExtensionPrompt(..., IN_CHAT, depth, ..., role)`。
 *
 * @param depth 分组时用的深度。注意 ST 在**建组对象**时没有做 `?? 4` 兜底
 *   （`world-info.js:5241`），所以缺 `depth` 的条目会产生一个 `depth: undefined` 的组，
 *   与另一个显式 `depth: 4` 的组并存。Kotlin 这边用 `null` 表达这个「未兜底」的状态。
 */
data class DepthEntryGroup(
    val depth: Int?,
    val role: PromptRole,
    val entries: List<String>,
)

/**
 * 一次世界书扫描的产物。
 *
 * 对应 `checkWorldInfo` 的返回值（`world-info.js:5281`）。
 */
class ActivationResult(
    /** `position = before` 的条目，`\n` 连接。 */
    val worldInfoBefore: String,

    /** `position = after` 的条目，`\n` 连接。 */
    val worldInfoAfter: String,

    val emEntries: List<EmEntry> = emptyList(),
    val depthEntries: List<DepthEntryGroup> = emptyList(),
    val anBeforeEntries: List<String> = emptyList(),
    val anAfterEntries: List<String> = emptyList(),

    /** key 为 outlet 名字；**刻意保持降序**（见引擎里的说明）。 */
    val outletEntries: Map<String, List<String>> = emptyMap(),

    /** 本次扫描最终生效的全部条目。 */
    val allActivatedEntries: List<ScanEntry> = emptyList(),

    /** 扫描轮数，便于调试与测试。 */
    val scanRounds: Int = 0,

    /** 预算是否触顶。 */
    val budgetOverflowed: Boolean = false,
) {
    /**
     * ST 的 `getWorldInfoPrompt` 返回 `worldInfoString = before + after`
     * （`world-info.js:898`）—— **中间没有分隔符**，就直接拼。
     */
    val worldInfoString: String get() = worldInfoBefore + worldInfoAfter

    val isEmpty: Boolean get() = allActivatedEntries.isEmpty()

    override fun toString(): String =
        "ActivationResult(${allActivatedEntries.size} entries, " +
            "before=${worldInfoBefore.length}ch, after=${worldInfoAfter.length}ch, rounds=$scanRounds)"

    companion object {
        val EMPTY = ActivationResult(worldInfoBefore = "", worldInfoAfter = "")
    }
}

/**
 * 引擎的完整产出：扫描结果 + 更新后的计时效果状态。
 *
 * 计时效果状态是**需要写回 `chat_metadata.timedWorldInfo` 的**，
 * 所以不能只返回 [ActivationResult]。
 */
class ActivationOutcome(
    val result: ActivationResult,
    val timedWorldInfo: TimedWorldInfo,
    /** 被概率挡下的条目 key，便于调试。 */
    val failedProbabilityKeys: Set<String> = emptySet(),
)
