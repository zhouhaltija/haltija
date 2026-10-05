package app.silly.core.prompt.assemble

import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole

/**
 * Prompt 管理器里的一项：一个标识符 + 是否启用。
 *
 * 对应 ST `prompt_order[<character_id>].order` 里的元素
 * （`PromptManager.js`；设置存在 `oai_settings.prompt_order`）。
 */
data class PromptOrderEntry(val identifier: String, val enabled: Boolean = true)

/**
 * 默认的 Prompt 顺序。
 *
 * 逐项照抄 `PromptManager.js:2088-2139` 的 `promptManagerDefaultPromptOrder` ——
 * 顺序**不能凭直觉猜**：比如 `worldInfoBefore` 在角色描述**之前**，
 * 而 `worldInfoAfter` 却在 `nsfw` 之后；`jailbreak`（即 PHI）排在聊天历史**之后**。
 */
object PromptOrder {
    val DEFAULT: List<PromptOrderEntry> = listOf(
        PromptOrderEntry("main"),
        PromptOrderEntry("worldInfoBefore"),
        PromptOrderEntry("personaDescription"),
        PromptOrderEntry("charDescription"),
        PromptOrderEntry("charPersonality"),
        PromptOrderEntry("scenario"),
        PromptOrderEntry("enhanceDefinitions", enabled = false),
        PromptOrderEntry("nsfw"),
        PromptOrderEntry("worldInfoAfter"),
        PromptOrderEntry("dialogueExamples"),
        PromptOrderEntry("chatHistory"),
        PromptOrderEntry("jailbreak"),
    )

    /** 默认顺序里用于「系统提示」的标识符。 */
    const val MAIN = "main"

    /** 插入聊天历史的位置。 */
    const val CHAT_HISTORY = "chatHistory"

    /** 角色卡里的 post_history_instructions。 */
    const val JAILBREAK = "jailbreak"
}

/**
 * 按 `depth` 注入到聊天历史里的一段文本。
 *
 * 对应世界书的 `position = atDepth`：`ActivationResult.depthEntries`
 * 以及 ST 里各种 `IN_CHAT` 的扩展注入。
 *
 * @param depth 距聊天末尾多少条。0 = 插到最后一条之后，1 = 插在最后一条之前…
 */
data class DepthInjection(
    val depth: Int,
    val content: String,
    val role: PromptRole = PromptRole.SYSTEM,
)

/**
 * 把各路来源按 Prompt 管理器的顺序拼成最终要发给模型的消息列表。
 *
 * ## 它做什么
 *
 * 输入是一张「标识符 → 文本」的表（由 App 层从角色卡、世界书引擎、
 * 预设等处收集好），加上聊天历史；输出是对齐 `prompt_order` 的 [PromptMessage] 列表。
 *
 * 分工是刻意的：**这个类不认识角色卡、不认识世界书**，只认识标识符。
 * 这样它就能用纯数据测试，也让 App 层可以自由决定每段文本怎么来。
 *
 * ## 它不做什么
 *
 * **不套 instruct 模板**。ST 的 `formatInstructModeChat` 是把消息拍平成
 * `### Instruction:` 那种纯文本，只对 text-completion 类后端有意义。
 * 本项目面向 OpenAI 兼容 / Anthropic / Gemini 三家**对话式**接口，
 * 消息本来就带 role，不需要拍平。
 */
class PromptAssembler(
    /** 顺序表；默认用 ST 的默认顺序。 */
    private val order: List<PromptOrderEntry> = PromptOrder.DEFAULT,

    /** 标识符 → 角色。未列出的默认 SYSTEM。 */
    private val roles: Map<String, PromptRole> = emptyMap(),
) {

    /**
     * 组装。
     *
     * @param sources 标识符 → 文本。**空串或全空白的条目会被跳过**，
     *   所以调用方不需要自己判断某段内容有没有。
     * @param history 聊天历史（最新的在**末尾**）。会插到 `chatHistory` 所在的位置。
     * @param depthInjections 按深度注入的文本，先作用于 [history]。
     */
    fun assemble(
        sources: Map<String, String>,
        history: List<PromptMessage> = emptyList(),
        depthInjections: List<DepthInjection> = emptyList(),
    ): List<PromptMessage> {
        val historyWithInjections = applyDepthInjections(history, depthInjections)
        val result = ArrayList<PromptMessage>(order.size + historyWithInjections.size)

        for (entry in order) {
            if (!entry.enabled) continue

            if (entry.identifier == PromptOrder.CHAT_HISTORY) {
                // 明确标记来源：历史消息原本带着调用方的 source，
                // 到了这里它们就是「聊天历史」的一部分
                result += historyWithInjections.map {
                    it.copy(source = PromptMessage.Source.CHAT_HISTORY)
                }
                continue
            }

            val content = sources[entry.identifier] ?: continue
            if (content.isBlank()) continue

            result += PromptMessage(
                role = roles[entry.identifier] ?: PromptRole.SYSTEM,
                content = content,
                source = sourceOf(entry.identifier),
            )
        }

        return result
    }

    /**
     * 把深度注入插进聊天历史。
     *
     * `depth` 是「距聊天末尾多少条」：`0` 追加到最后，`1` 插在最后一条之前。
     * 世界书的 `atDepth` 用 `depth = 1` 表示「最近一条消息之前」，与此一致；
     * 超出历史长度时钳制到最前面。
     */
    private fun applyDepthInjections(
        history: List<PromptMessage>,
        injections: List<DepthInjection>,
    ): List<PromptMessage> {
        val valid = injections.filter { it.content.isNotBlank() }
        if (valid.isEmpty()) return history

        // 位置 i 表示「插在 history[i] 之前」；i == history.size 表示追加到最后
        val byPosition = LinkedHashMap<Int, MutableList<DepthInjection>>()
        for (injection in valid) {
            val position = (history.size - injection.depth.coerceAtLeast(0))
                .coerceIn(0, history.size)
            byPosition.getOrPut(position) { ArrayList() } += injection
        }

        val result = ArrayList<PromptMessage>(history.size + valid.size)
        for (i in 0..history.size) {
            byPosition[i]?.forEach { injection ->
                result += PromptMessage(
                    role = injection.role,
                    content = injection.content,
                    source = PromptMessage.Source.WORLD_INFO,
                )
            }
            if (i < history.size) result += history[i]
        }
        return result
    }

    private fun sourceOf(identifier: String): PromptMessage.Source = when (identifier) {
        PromptOrder.MAIN, PromptOrder.JAILBREAK -> PromptMessage.Source.SYSTEM_PROMPT
        "worldInfoBefore", "worldInfoAfter" -> PromptMessage.Source.WORLD_INFO
        "dialogueExamples" -> PromptMessage.Source.SEPARATOR
        "charDescription", "charPersonality", "scenario", "personaDescription",
        "nsfw", "enhanceDefinitions",
        -> PromptMessage.Source.CHARACTER
        else -> PromptMessage.Source.UNKNOWN
    }

    companion object {
        /**
         * 组装时用到的全部标识符（含默认关闭的），供 UI 列出可选项。
         */
        val KNOWN_IDENTIFIERS: List<String> = listOf(
            "main", "worldInfoBefore", "personaDescription", "charDescription",
            "charPersonality", "scenario", "enhanceDefinitions", "nsfw",
            "worldInfoAfter", "dialogueExamples", "chatHistory", "jailbreak",
        )
    }
}
