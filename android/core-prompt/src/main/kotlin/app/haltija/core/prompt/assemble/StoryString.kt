package app.haltija.core.prompt.assemble

import app.haltija.core.data.preset.ExtensionPromptType

/**
 * `story_string` 的渲染管线。
 *
 * 逐句对应 SillyTavern 的 `renderStoryString`（`public/scripts/power-user.js:2234-2264`）：
 *
 * ```js
 * let output = Handlebars.compile(storyString, { noEscape: true })(params);
 * output = substituteParams(output, params.user, params.char);   // 处理 {{trim}} 之类 ST 宏
 * output = output.replace(/^\n+/, '');                           // 去掉开头换行
 * if (output.length > 0 && !output.endsWith('\n') && position !== IN_CHAT) {
 *     if (!instructEnabled || (instructWrap && !storyStringSuffix)) output += '\n';
 * }
 * ```
 *
 * 顺序很重要：**先渲染 Handlebars，再跑 ST 宏**。
 *
 * 这条顺序不是随便定的。ST 在 `public/scripts/macros.js:18-25` 注册了桥接 helper，
 * 让模板里未定义的 `{{xxx}}` **原样留在渲染结果里**，交给后面的宏引擎。
 * 所以 `{{trim}}` 才会在 Handlebars 阶段之后还有机会被处理。
 * 详见 [HandlebarsRenderer] 的说明。
 */
object StoryString {

    /** `script.js:4702-4718` 传入 `renderStoryString` 的参数。 */
    data class Params(
        val description: String = "",
        val personality: String = "",
        val persona: String = "",
        val scenario: String = "",
        val system: String = "",
        val char: String = "",
        val user: String = "",
        val wiBefore: String = "",
        val wiAfter: String = "",
        val anchorBefore: String = "",
        val anchorAfter: String = "",
        val mesExamples: String = "",
        val mesExamplesRaw: String = "",
    ) {
        /**
         * 展开成模板变量表。
         *
         * `loreBefore` / `loreAfter` 是与 `wiBefore` / `wiAfter` 等价的别名
         * （`script.js:4712-4713`），必须一并提供，否则老模板会渲染成空。
         */
        fun toMap(): Map<String, String> = linkedMapOf(
            "description" to description,
            "personality" to personality,
            "persona" to persona,
            "scenario" to scenario,
            "system" to system,
            "char" to char,
            "user" to user,
            "wiBefore" to wiBefore,
            "wiAfter" to wiAfter,
            "loreBefore" to wiBefore,
            "loreAfter" to wiAfter,
            "anchorBefore" to anchorBefore,
            "anchorAfter" to anchorAfter,
            "mesExamples" to mesExamples,
            "mesExamplesRaw" to mesExamplesRaw,
        )
    }

    /**
     * 影响末尾换行追加的指令模板设置。
     *
     * 对应 `renderStoryString` 里用到的 `instructSettings.enabled` / `.wrap` /
     * `.story_string_suffix` 三个字段。
     */
    data class Options(
        /** `story_string` 的注入位置；`IN_CHAT` 时不追加末尾换行。 */
        val position: ExtensionPromptType = ExtensionPromptType.IN_PROMPT,
        val instructEnabled: Boolean = false,
        val instructWrap: Boolean = true,
        val instructStoryStringSuffix: String = "",
    )

    data class Result(
        val text: String,
        /** 模板引用了不存在的参数、或含未闭合/不支持的语法。 */
        val warnings: List<String> = emptyList(),
    )

    /**
     * @param substitute ST 宏替换。默认不做替换，便于单独测试模板渲染。
     */
    fun render(
        template: String,
        params: Params,
        options: Options = Options(),
        substitute: (String) -> String = { it },
    ): Result {
        val map = params.toMap()
        val warnings = ArrayList<String>()

        // ① 模板引用了哪些参数；缺失的提前报出来（对应 ST 的 validateStoryString）
        for (name in HandlebarsVariables.of(template)) {
            if (name == "trim") continue // ST 宏，不是模板变量
            if (name !in map) warnings += "story_string 引用了不存在的变量 {{$name}}"
        }

        val (rendered, hbWarnings) = HandlebarsRenderer.render(template, map)
        warnings += hbWarnings

        // ② ST 宏替换
        var output = substitute(rendered)

        // ③ 去掉开头的换行
        output = output.replace(LEADING_NEWLINES, "")

        // ④ 补末尾换行
        if (output.isNotEmpty() && !output.endsWith("\n") && options.position != ExtensionPromptType.IN_CHAT) {
            if (!options.instructEnabled || (options.instructWrap && options.instructStoryStringSuffix.isEmpty())) {
                output += "\n"
            }
        }

        return Result(output, warnings)
    }

    private val LEADING_NEWLINES = Regex("^\\n+")
}
