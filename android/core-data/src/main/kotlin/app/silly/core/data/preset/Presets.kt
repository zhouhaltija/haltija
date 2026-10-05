package app.silly.core.data.preset

import app.silly.core.data.json.bool
import app.silly.core.data.json.int
import app.silly.core.data.json.text
import kotlinx.serialization.json.JsonObject

/**
 * 预设类型。
 *
 * SillyTavern 的 `default/content/presets/` 下有 10 个子目录：
 * `context` `instruct` `sysprompt` `reasoning` `openai` `textgen` `kobold`
 * `novel` `moving-ui` `quick-replies`。
 *
 * 这里只建模与本项目相关的四类 —— 它们的 schema 互不相同，
 * 而且共同决定「提示词长什么样」。后端的采样参数（`openai` / `textgen` 等）
 * 属于 provider 层，在 `core-provider` 里单独处理。
 */
enum class PresetKind(val directory: String) {
    /** 上下文模板：决定角色定义怎么拼成 `story_string`。内容是 Handlebars 模板。 */
    CONTEXT("context"),

    /** 指令模板：决定 user / assistant 轮次用什么分隔符包裹。 */
    INSTRUCT("instruct"),

    /** 系统提示词。 */
    SYSPROMPT("sysprompt"),

    /** 思维链模板：把模型的思考过程包进 prefix / suffix 之间。 */
    REASONING("reasoning"),
}

/** 所有预设的公共面：原始 JSON 是唯一真相来源。 */
sealed interface Preset {
    val raw: JsonObject

    /** 预设名。文件里通常有 `name`，否则退回文件名。 */
    val name: String

    /** 来源文件名（不含 `.json`）。 */
    val fileName: String?

    fun toJsonObject(): JsonObject = raw
}

/**
 * 上下文模板（`presets/context/` 下的 JSON）。
 *
 * 核心字段 [storyString] 是一段 **Handlebars 模板**，变量由 Prompt 组装阶段注入：
 * `system` `description` `personality` `scenario` `persona` `char` `user`
 * `wiBefore` `wiAfter` `anchorBefore` `anchorAfter` …
 *
 * 默认值取自 `public/scripts/power-user.js:90`。
 */
class ContextTemplate(
    override val raw: JsonObject,
    override val fileName: String? = null,
) : Preset {

    override val name: String = raw.text("name") ?: fileName.orEmpty()

    val storyString: String = raw.text("story_string") ?: DEFAULT_STORY_STRING

    /** 每条示例对话之间的分隔符。 */
    val exampleSeparator: String = raw.text("example_separator") ?: DEFAULT_SEPARATOR

    /** 聊天起始标记。 */
    val chatStart: String = raw.text("chat_start") ?: DEFAULT_SEPARATOR

    val useStopStrings: Boolean = raw.bool("use_stop_strings") ?: true
    val namesAsStopStrings: Boolean = raw.bool("names_as_stop_strings") ?: true

    /** `story_string` 的注入位置，取值见 [ExtensionPromptType]。 */
    val storyStringPosition: Int =
        raw.int("story_string_position") ?: ExtensionPromptType.IN_PROMPT.value

    /** `story_string` 的注入深度。 */
    val storyStringDepth: Int = raw.int("story_string_depth") ?: 1

    /** `story_string` 的注入角色，取值见 [ExtensionPromptRole]。 */
    val storyStringRole: Int =
        raw.int("story_string_role") ?: ExtensionPromptRole.SYSTEM.value

    val trimSentences: Boolean = raw.bool("trim_sentences") ?: false
    val singleLine: Boolean = raw.bool("single_line") ?: false
    val alwaysForceName2: Boolean = raw.bool("always_force_name2") ?: false

    /** [storyString] 里用到的模板变量名（不含 block 语法与辅助函数）。 */
    val templateVariables: Set<String> get() = HandlebarsTemplate.variables(storyString)

    override fun toString(): String = "ContextTemplate(name='$name', vars=$templateVariables)"

    companion object {
        const val DEFAULT_STORY_STRING: String =
            "{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
                "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
                "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}"
        const val DEFAULT_SEPARATOR: String = "***"
    }
}

/**
 * 指令模板（`presets/instruct/` 下的 JSON）。
 *
 * 决定同一段对话在发给模型前怎么被包裹成「提问 / 回答」序列。
 * 字段较多，绝大多数预设只用到其中几个。
 */
class InstructTemplate(
    override val raw: JsonObject,
    override val fileName: String? = null,
) : Preset {

    override val name: String = raw.text("name") ?: fileName.orEmpty()

    /** 分别对应 [inputSequence]（用户）与 [outputSequence]（角色）。 */
    val inputSequence: String = raw.text("input_sequence").orEmpty()
    val outputSequence: String = raw.text("output_sequence").orEmpty()

    /** 整段对话开头的第一个 user / assistant 序列。 */
    val firstInputSequence: String = raw.text("first_input_sequence").orEmpty()
    val firstOutputSequence: String = raw.text("first_output_sequence").orEmpty()

    /** 最后一个 user / assistant 序列。 */
    val lastInputSequence: String = raw.text("last_input_sequence").orEmpty()
    val lastOutputSequence: String = raw.text("last_output_sequence").orEmpty()

    val systemSequence: String = raw.text("system_sequence").orEmpty()
    val lastSystemSequence: String = raw.text("last_system_sequence").orEmpty()

    val inputSuffix: String = raw.text("input_suffix").orEmpty()
    val outputSuffix: String = raw.text("output_suffix").orEmpty()
    val systemSuffix: String = raw.text("system_suffix").orEmpty()

    /** 停止串。 */
    val stopSequence: String = raw.text("stop_sequence").orEmpty()

    /** 是否把各种 sequence 也当作停止串。 */
    val sequencesAsStopStrings: Boolean = raw.bool("sequences_as_stop_strings") ?: true

    /** 是否给内容套上一层包裹。 */
    val wrap: Boolean = raw.bool("wrap") ?: true

    /** 是否对 sequence 做宏替换。 */
    val macro: Boolean = raw.bool("macro") ?: true

    /** 是否给角色名加上前缀，取值见 [NamesBehavior]。 */
    val namesBehavior: NamesBehavior =
        NamesBehavior.from(raw.text("names_behavior")) ?: NamesBehavior.NONE

    /** 只在匹配该正则时才启用此模板。 */
    val activationRegex: String = raw.text("activation_regex").orEmpty()

    val skipExamples: Boolean = raw.bool("skip_examples") ?: false
    val systemSameAsUser: Boolean = raw.bool("system_same_as_user") ?: false

    /** 系统提示词前后缀。 */
    val storyStringPrefix: String = raw.text("story_string_prefix").orEmpty()
    val storyStringSuffix: String = raw.text("story_string_suffix").orEmpty()

    /** 用户对齐消息（部分模型需要一条额外的 user 轮次）。 */
    val userAlignmentMessage: String = raw.text("user_alignment_message").orEmpty()

    override fun toString(): String = "InstructTemplate(name='$name', names=$namesBehavior)"

    companion object {
        fun from(raw: JsonObject, fileName: String? = null) = InstructTemplate(raw, fileName)
    }
}

/**
 * 系统提示词（`presets/sysprompt/` 下的 JSON）。
 *
 * [content] 里的 `{{char}}` / `{{user}}` 由宏引擎替换。
 */
class SystemPrompt(
    override val raw: JsonObject,
    override val fileName: String? = null,
) : Preset {

    override val name: String = raw.text("name") ?: fileName.orEmpty()

    val content: String = raw.text("content").orEmpty()

    /** 追加在聊天历史之后的补充指令。 */
    val postHistory: String = raw.text("post_history").orEmpty()

    override fun toString(): String = "SystemPrompt(name='$name')"
}

/**
 * 思维链模板（`presets/reasoning/` 下的 JSON）。
 *
 * 模型的思考过程被包裹成 `prefix + 思考内容 + separator + 正式回答 + suffix`。
 */
class ReasoningTemplate(
    override val raw: JsonObject,
    override val fileName: String? = null,
) : Preset {

    override val name: String = raw.text("name") ?: fileName.orEmpty()

    /** 思考开始标记，例如 DeepSeek 的 `<think>\n`。 */
    val prefix: String = raw.text("prefix").orEmpty()

    /** 思考结束标记。 */
    val suffix: String = raw.text("suffix").orEmpty()

    /** 思考与正式回答之间的分隔。 */
    val separator: String = raw.text("separator").orEmpty()

    override fun toString(): String = "ReasoningTemplate(name='$name')"
}

/** `extension_prompt_types`，见 `public/script.js:484`。 */
enum class ExtensionPromptType(val value: Int) {
    NONE(-1),
    IN_PROMPT(0),
    IN_CHAT(1),
    BEFORE_PROMPT(2),
    ;

    companion object {
        fun from(value: Int?): ExtensionPromptType? = entries.firstOrNull { it.value == value }
    }
}

/** `extension_prompt_roles`，见 `public/script.js:494`。 */
enum class ExtensionPromptRole(val value: Int) {
    SYSTEM(0),
    USER(1),
    ASSISTANT(2),
    ;

    companion object {
        fun from(value: Int?): ExtensionPromptRole? = entries.firstOrNull { it.value == value }
    }
}

/** `names_behavior_types`，见 `public/scripts/instruct-mode.js:17`。 */
enum class NamesBehavior(val value: String) {
    NONE("none"),
    FORCE("force"),
    ALWAYS("always"),
    ;

    companion object {
        fun from(value: String?): NamesBehavior? = entries.firstOrNull { it.value == value }
    }
}
