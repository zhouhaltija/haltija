package app.silly.core.prompt.model

import kotlinx.serialization.json.JsonObject

/**
 * 消息角色。
 *
 * 数值取自 SillyTavern 的 `extension_prompt_roles`（`public/script.js:494`），
 * 世界书条目的 `role` 字段用的就是这套取值。
 */
enum class PromptRole(val value: Int) {
    SYSTEM(0),
    USER(1),
    ASSISTANT(2),
    ;

    companion object {
        /** 未知取值返回 null —— 不猜，因为猜错会改变提示词结构。 */
        fun from(value: Int?): PromptRole? = entries.firstOrNull { it.value == value }

        /** 世界书条目 `role` 缺省时按 SYSTEM 处理（与 ST 一致）。 */
        fun fromOrDefault(value: Int?): PromptRole = from(value) ?: SYSTEM
    }
}

/**
 * 一条待发送的消息。
 *
 * 这是**内部表示**，不是任何一家的 API 格式 —— 各 provider 在
 * `core-provider` 里把它翻译成自己的请求体。
 *
 * @param name 说话者名字。部分模板需要（`names_behavior`），
 *   也可能被 provider 用作 `name` 字段。
 * @param source 来源标记，用于调试与 token 归属统计。
 */
data class PromptMessage(
    val role: PromptRole,
    val content: String,
    val name: String? = null,
    val source: Source = Source.UNKNOWN,
) {
    enum class Source {
        /** 系统提示词预设。 */
        SYSTEM_PROMPT,

        /** 上下文模板渲染出的角色定义（`story_string`）。 */
        STORY_STRING,

        /** 角色卡里的字段（描述 / 性格 / 场景 / 人设）。 */
        CHARACTER,

        /** 世界书注入。 */
        WORLD_INFO,

        /** 聊天历史。 */
        CHAT_HISTORY,

        /** 聊天起始标记 / 分隔符。 */
        SEPARATOR,

        /** 作者注、向量记忆等扩展注入。 */
        EXTENSION,

        UNKNOWN,
    }

    /** 拼接用：按角色展开成 `角色名: 内容`。 */
    fun asText(withName: Boolean = false): String =
        if (withName && !name.isNullOrEmpty()) "$name: $content" else content
}

/**
 * 一次生成请求的输入上下文。
 *
 * @param characterName `{{char}}`
 * @param userName `{{user}}`
 * @param personaDescription 用户人设
 * @param characterDepthPrompt 角色备注 / 深度提示
 * @param creatorNotes 角色作者注
 * @param worldInfoBefore / [worldInfoAfter] 世界书按位置分流后的文本
 * @param globalScanData 供世界书 `match*` 开关使用的额外文本
 */
data class PromptContext(
    val characterName: String,
    val userName: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val personaDescription: String = "",
    val characterDepthPrompt: String = "",
    val creatorNotes: String = "",
    val mesExamples: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val worldInfoBefore: String = "",
    val worldInfoAfter: String = "",
    /** 角色卡里 `extensions` 等附加数据，供扩展读取。 */
    val extras: JsonObject? = null,
)
