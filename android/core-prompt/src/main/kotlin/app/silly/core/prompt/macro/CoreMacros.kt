package app.silly.core.prompt.macro

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * SillyTavern 的内置宏。
 *
 * 逐条对应 `public/scripts/macros/definitions/` 下的注册项
 * （core / env / state / chat / time / variable），
 * 完整清单见 `docs/macro-engine-spec.md` §5。
 *
 * ## 与 ST 的三处有意的差异
 *
 * 1. **`{{random}}` 不复刻 ST 的熵池**。ST 用 `seedrandom('added entropy.', {entropy:true})`，
 *    每次结果都不同且无法用固定种子复现。这里直接用注入的均匀随机源 —— 语义等价，
 *    而且可测试。
 * 2. **`{{roll}}` 同理**，用注入的随机源而不是 `Math.random()`。
 * 3. **`{{pick}}` 暂未实现**。它在 ST 里是「同一聊天 + 同一文本 + 同一位置必定同值」的
 *    确定性选择，依赖内容哈希 + **宏在文本中的绝对偏移** + ARC4 种子。
 *    偏移需要引擎在求值时透传位置信息，留到后续和 Prompt 组装一起做。
 *
 * ## 时间格式
 *
 * ST 用 moment 的 `LT` / `LL`，依赖 locale。这里按语言映射到 `DateTimeFormatter` 的模式，
 * 未覆盖的语言回退到英文格式。
 */
object CoreMacros {

    fun install(registry: MacroRegistry) {
        installUtility(registry)
        installNames(registry)
        installCharacter(registry)
        installChat(registry)
        installTime(registry)
        installRandom(registry)
        installVariables(registry)
        installControl(registry)
    }

    // ------------------------------------------------------------ 工具

    private fun installUtility(r: MacroRegistry) {
        r.register("space", listOf(intArg("count", optional = true))) { ctx ->
            " ".repeat(repeatCount(ctx.unnamedArgs[0], 1))
        }
        r.register("newline", listOf(intArg("count", optional = true))) { ctx ->
            "\n".repeat(repeatCount(ctx.unnamedArgs[0], 1))
        }
        r.register("noop") { "" }
        r.register("//", listOf(MacroArgDef("comment", optional = true))) { "" }
        r.register("comment", listOf(MacroArgDef("comment", optional = true))) { "" }

        // 按 UTF-16 code point 反转，代理对不拆散（与 JS 的 Array.from 一致）
        r.register("reverse", listOf(MacroArgDef("value"))) { ctx ->
            reverseByCodePoint(ctx.unnamedArgs[0] ?: "")
        }

        r.register("banned", listOf(MacroArgDef("word"))) { _ -> "" }

        r.register("input") { ctx -> ctx.env.input }
        r.register("isMobile") { ctx -> ctx.env.isMobile.toString() }
        r.register("model") { ctx -> ctx.env.system("model") }

        r.register("maxPrompt") { ctx -> ctx.env.system("maxPrompt") }
        r.register("maxContext") { ctx -> ctx.env.system("maxContext") }
        r.register("maxResponse") { ctx -> ctx.env.system("maxResponse") }
        r.alias("maxPrompt", "maxPromptTokens")
        r.alias("maxContext", "maxContextTokens")
        r.alias("maxResponse", "maxResponseTokens")
    }

    // ------------------------------------------------------------ 名字与角色

    private fun installNames(r: MacroRegistry) {
        r.register("user") { ctx -> ctx.env.name("user") }
        r.register("char") { ctx -> ctx.env.name("char") }
        r.register("group") { ctx -> ctx.env.name("group") }
        r.alias("group", "charIfNotGroup")
        r.register("groupNotMuted") { ctx -> ctx.env.name("groupNotMuted") }
        r.register("notChar") { ctx -> ctx.env.name("notChar") }
    }

    private fun installCharacter(r: MacroRegistry) {
        r.register("charPrompt") { ctx -> ctx.env.character("charPrompt") }
        r.register("charInstruction") { ctx -> ctx.env.character("charInstruction") }
        r.register("charDescription") { ctx -> ctx.env.character("description") }
        r.alias("charDescription", "description")
        r.register("charPersonality") { ctx -> ctx.env.character("personality") }
        r.alias("charPersonality", "personality")
        r.register("charScenario") { ctx -> ctx.env.character("scenario") }
        r.alias("charScenario", "scenario")
        r.register("persona") { ctx -> ctx.env.character("persona") }
        r.register("mesExamplesRaw") { ctx -> ctx.env.character("mesExamplesRaw") }
        r.register("mesExamples") { ctx -> ctx.env.character("mesExamples") }
        r.register("charDepthPrompt") { ctx -> ctx.env.character("charDepthPrompt") }
        r.register("charCreatorNotes") { ctx -> ctx.env.character("creatorNotes") }
        r.alias("charCreatorNotes", "creatorNotes")
        r.register("charVersion") { ctx -> ctx.env.character("version") }
        r.alias("charVersion", "version")
        r.alias("charVersion", "char_version")

        // {{charFirstMessage}} / {{greeting}}：0 → firstMessage，其余 → alternateGreetings[i-1]
        r.register("charFirstMessage", listOf(intArg("index", optional = true))) { ctx ->
            val index = ctx.unnamedArgs[0]?.trim()?.toIntOrNull() ?: 0
            if (index == 0) {
                ctx.env.character("firstMessage")
            } else {
                val alternatives = ctx.env.character("alternateGreetings")
                val list = if (alternatives.isEmpty()) emptyList() else alternatives.split(ALT_GREETING_SEPARATOR)
                list.getOrNull(index - 1).orEmpty()
            }
        }
        r.alias("charFirstMessage", "greeting")
    }

    // ------------------------------------------------------------ 会话

    private fun installChat(r: MacroRegistry) {
        r.register("lastMessage") { ctx -> ctx.env.lastMessage { true }?.mes.orEmpty() }
        r.register("lastMessageId") { ctx ->
            ctx.env.lastMessageIndex { true }?.toString().orEmpty()
        }
        r.register("lastUserMessage") { ctx ->
            ctx.env.lastMessage { it.isUser && !it.isSystem }?.mes.orEmpty()
        }
        r.register("lastCharMessage") { ctx ->
            ctx.env.lastMessage { !it.isUser && !it.isSystem }?.mes.orEmpty()
        }
        r.register("allChatRange") { ctx ->
            if (ctx.env.chat.isEmpty()) "" else "0-${ctx.env.chat.size - 1}"
        }
    }

    // ------------------------------------------------------------ 时间

    private fun installTime(r: MacroRegistry) {
        r.register("time", listOf(MacroArgDef("offset", optional = true))) { ctx ->
            val now = ctx.env.clock()
            val offset = ctx.unnamedArgs[0]?.trim().orEmpty()
            val moment = if (UTC_OFFSET.matches(offset)) {
                val hours = offset.substring(3).toInt()
                now.withFixedOffset(hours)
            } else {
                now
            }
            format(moment, ltPattern(ctx.env.locale), ctx.env.locale)
        }

        r.register("date") { ctx -> format(ctx.env.clock(), llPattern(ctx.env.locale), ctx.env.locale) }
        r.register("weekday") { ctx -> format(ctx.env.clock(), "EEEE", ctx.env.locale) }
        r.register("isotime") { ctx -> format(ctx.env.clock(), "HH:mm", ctx.env.locale) }
        r.register("isodate") { ctx -> format(ctx.env.clock(), "yyyy-MM-dd", ctx.env.locale) }

        r.register("datetimeformat", listOf(MacroArgDef("format"))) { ctx ->
            val pattern = ctx.unnamedArgs[0].orEmpty()
            if (pattern.isEmpty()) "" else format(ctx.env.clock(), pattern, ctx.env.locale)
        }

        // 与 ST 一致：取「从末尾数第二条非系统消息且它是 user」的时间差
        r.register("idleDuration") { ctx ->
            val target = ctx.env.chat.lastOrNull { !it.isSystem }
            val index = ctx.env.chat.indexOfLast { !it.isSystem }
            val previousIndex = ctx.env.chat.subList(0, maxOf(index, 0))
                .indexOfLast { !it.isSystem }
            val candidate = if (previousIndex >= 0) ctx.env.chat[previousIndex] else null
            val date = candidate?.sendDate ?: target?.sendDate
            if (date.isNullOrEmpty()) {
                "just now"
            } else {
                runCatching {
                    val then = ZonedDateTime.parse(date)
                    humanize(java.time.Duration.between(then, ctx.env.clock()))
                }.getOrDefault("just now")
            }
        }
        r.alias("idleDuration", "idle_duration")
    }

    // ------------------------------------------------------------ 随机

    private fun installRandom(r: MacroRegistry) {
        // {{roll}}：droll 语法只有 NdM±K
        r.register("roll", listOf(MacroArgDef("formula"))) { ctx ->
            val raw = ctx.unnamedArgs[0].orEmpty().trim()
            val formula = if (DIGITS_ONLY.matches(raw)) "1d$raw" else raw
            val match = DICE.matchEntire(formula)
                ?: return@register "" // 校验失败 → 空串（与 ST 一致）
            val count = match.groupValues[1].ifEmpty { "1" }.toInt()
            val faces = match.groupValues[2].toInt()
            val modifier = match.groupValues[3].toIntOrNull() ?: 0
            var total = modifier
            repeat(count) { total += (ctx.env.random() * faces).toInt() + 1 }
            total.toString()
        }

        // {{random}}：list 宏。参数可以写成 ::a::b，也可以写成单个 "a,b"
        r.register("random", isList = true) { ctx ->
            val items = randomItems(ctx.unnamedArgs)
            if (items.isEmpty()) "" else items[(ctx.env.random() * items.size).toInt().coerceIn(0, items.size - 1)]
        }
    }

    /** `{{random::a::b}}` 与 `{{random:a,b}}` / `{{random a,b}}` 都要支持。 */
    private fun randomItems(args: List<String?>): List<String> {
        val present = args.map { it ?: "" }
        if (present.size != 1) return present.filter { it.isNotEmpty() }

        val single = present[0]
        if (single.contains("::")) return single.split("::").map { it.trim() }
        return single.split(",").map { it.replace(ESCAPED_COMMA, ",").trim() }.filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ 变量

    private fun installVariables(r: MacroRegistry) {
        for (isGlobal in listOf(false, true)) {
            val scope = if (isGlobal) "globalvar" else "var"

            r.register("set$scope", listOf(MacroArgDef("name"), MacroArgDef("value"))) { ctx ->
                ctx.env.variables.set(ctx.unnamedArgs[0].orEmpty(), ctx.unnamedArgs[1].orEmpty(), isGlobal)
                ""
            }
            r.register("add$scope", listOf(MacroArgDef("name"), MacroArgDef("value"))) { ctx ->
                ctx.env.variables.add(ctx.unnamedArgs[0].orEmpty(), ctx.unnamedArgs[1].orEmpty(), isGlobal)
                ""
            }
            r.register("inc$scope", listOf(MacroArgDef("name"))) { ctx ->
                ctx.env.variables.add(ctx.unnamedArgs[0].orEmpty(), "1", isGlobal)
            }
            r.register("dec$scope", listOf(MacroArgDef("name"))) { ctx ->
                ctx.env.variables.add(ctx.unnamedArgs[0].orEmpty(), "-1", isGlobal)
            }
            r.register("get$scope", listOf(MacroArgDef("name"))) { ctx ->
                ctx.env.variables.get(ctx.unnamedArgs[0].orEmpty(), isGlobal).orEmpty()
            }
            r.register("has$scope", listOf(MacroArgDef("name"))) { ctx ->
                ctx.env.variables.has(ctx.unnamedArgs[0].orEmpty(), isGlobal).toString()
            }
            r.register("delete$scope", listOf(MacroArgDef("name"))) { ctx ->
                ctx.env.variables.delete(ctx.unnamedArgs[0].orEmpty(), isGlobal)
                ""
            }
        }

        r.alias("hasvar", "varexists")
        r.alias("deletevar", "flushvar")
        r.alias("hasglobalvar", "globalvarexists")
        r.alias("deleteglobalvar", "flushglobalvar")
    }

    // ------------------------------------------------------------ 控制流

    /**
     * `{{if}}`，对应 `core-macros.js:134-213`。
     *
     * `delayArgResolution = true`：参数保持原文，由 handler 决定求值哪个分支 ——
     * 这样未被选中的分支里的宏不会产生副作用（比如 `{{setvar}}`）。
     */
    private fun installControl(r: MacroRegistry) {
        r.register(
            name = "if",
            unnamedArgs = listOf(MacroArgDef("condition"), MacroArgDef("content")),
            delayArgResolution = true,
        ) { ctx ->
            val rawCondition = ctx.unnamedArgs[0].orEmpty()
            val rawContent = ctx.unnamedArgs[1].orEmpty()

            // ① `!` 前缀表示取反
            var condition = rawCondition
            var inverted = false
            if (INVERTED_PREFIX.containsMatchIn(condition)) {
                inverted = true
                condition = condition.replaceFirst(INVERTED_PREFIX, "")
            }

            // ② 求值嵌套宏 ③ 变量简写 ④ 零参宏展开
            condition = ctx.resolve(condition)
            condition = when {
                LOCAL_VAR_SHORTHAND.matches(condition) ->
                    ctx.resolve("{{getvar::${condition.substring(1)}}}")
                GLOBAL_VAR_SHORTHAND.matches(condition) ->
                    ctx.resolve("{{getglobalvar::${condition.substring(1)}}}")
                ctx.isZeroArgMacro(condition) -> ctx.resolve("{{$condition}}")
                else -> condition
            }

            // ⑤ 真值判定：只有 '' 与 off/false/0 为假
            var falsy = condition.isEmpty() || isFalseBoolean(condition)
            if (inverted) falsy = !falsy

            // ⑥ 按顶层 {{else}} 切分
            val (thenBranch, elseBranch) = splitOnTopLevelElse(rawContent)

            val chosen = if (!falsy) thenBranch else elseBranch
            if (chosen == null) return@register ""

            // ⑦ 只求值被选中的分支，并去掉公共缩进
            trimScopedContent(ctx.resolve(chosen))
        }

        // {{else}} 自身返回控制字符标记，由后处理删掉
        r.register("else") { MacroEngine.ELSE_MARKER }
    }

    /**
     * `trimScopedContent`，对应 `MacroEngine.js:372-413`。
     *
     * 不只是 `trim()`：先按**第一个非空行的前导空白**作为基准缩进，
     * 把每行砍掉同样的长度，最后再整体 `trim()`。这让多行模板能自然书写。
     */
    fun trimScopedContent(content: String): String {
        val lines = content.split("\n")
        val firstNonEmpty = lines.firstOrNull { it.isNotBlank() } ?: return content.trim()
        val baseIndent = firstNonEmpty.length - firstNonEmpty.trimStart().length

        val dedented = lines.joinToString("\n") { line ->
            if (line.length >= baseIndent) line.substring(baseIndent) else line.trimStart()
        }
        return dedented.trim()
    }

    /**
     * 找到**顶层**的 `{{else}}` 并切成两段。
     *
     * 「顶层」= 不在任何 `{{if …}}…{{/if}}` 内部。ST 判定嵌套时只数
     * **带 1 个参数的 `{{if}}`**（`core-macros.js:104-128`）。
     */
    fun splitOnTopLevelElse(content: String): Pair<String, String?> {
        val token = Regex("""\{\{\s*(#?[^{}]*?)\s*\}\}""")
        var depth = 0

        for (match in token.findAll(content)) {
            val inner = match.groupValues[1].trim()
            val name = inner.substringBefore(':').substringBefore(' ').trim().lowercase()

            when {
                // ST 只把「带 1 个参数的 {{if}}」计入嵌套深度。
                // 注意 `{{if true}}`（空白分隔）也是 1 个参数 —— 漏掉它会把
                // 内层的 {{else}} 误当成外层分隔符。
                name == "if" && argCount(inner) == 1 -> depth++
                name == "/if" -> if (depth > 0) depth--
                name == "else" && depth == 0 -> {
                    return content.substring(0, match.range.first) to
                        content.substring(match.range.last + 1)
                }
            }
        }
        return content to null
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 数一下一次宏调用有几个参数。
     *
     * 与 `MacroEngine.parseCall` 的规则一致：
     * `::a::b` → 2；`:a` → 1；` a`（空白分隔）→ 1；否则 0。
     */
    private fun argCount(inner: String): Int {
        val colon = inner.indexOf(':')
        if (colon < 0) {
            val head = inner.trim()
            val space = head.indexOfFirst { it.isWhitespace() }
            if (space < 0) return 0
            // 名字之后还有内容 ⇒ 空白分隔的单参数
            return if (head.substring(space).isBlank()) 0 else 1
        }
        val rest = inner.substring(colon)
        return if (rest.startsWith("::")) rest.substring(2).split("::").size else 1
    }

    /** `isFalseBoolean`（utils.js:1019）—— 只有这三个是「假」。 */
    fun isFalseBoolean(arg: String?): Boolean =
        arg != null && arg.trim().lowercase() in FALSE_VALUES

    private fun intArg(name: String, optional: Boolean) =
        MacroArgDef(name, MacroValueType.INTEGER, optional)

    /** JS 的 `repeat(NaN)` 得到空串；负数会抛 RangeError（由引擎捕获后回退成原文）。 */
    private fun repeatCount(raw: String?, fallback: Int): Int {
        if (raw == null) return fallback
        val value = raw.trim().toIntOrNull() ?: return 0
        require(value >= 0) { "Invalid count value: $value" }
        return value
    }

    private fun reverseByCodePoint(value: String): String {
        val builder = StringBuilder()
        var i = value.length
        while (i > 0) {
            val low = value[i - 1]
            if (Character.isLowSurrogate(low) && i >= 2 && Character.isHighSurrogate(value[i - 2])) {
                builder.append(value, i - 2, i)
                i -= 2
            } else {
                builder.append(low)
                i--
            }
        }
        return builder.toString()
    }

    private fun ZonedDateTime.withFixedOffset(hours: Int): ZonedDateTime =
        withZoneSameInstant(java.time.ZoneOffset.ofHours(hours))

    private fun format(moment: ZonedDateTime, pattern: String, locale: Locale): String =
        runCatching { moment.format(DateTimeFormatter.ofPattern(pattern, locale)) }.getOrDefault("")

    /** moment 的 `LT`（本地化短时间）。 */
    private fun ltPattern(locale: Locale): String = when (locale.language) {
        "zh", "de", "fr", "ru", "es", "it", "pt" -> "HH:mm"
        "ja", "ko" -> "H:mm"
        else -> "h:mm a"
    }

    /** moment 的 `LL`（本地化长日期）。 */
    private fun llPattern(locale: Locale): String = when (locale.language) {
        "zh", "ja", "ko" -> "yyyy年M月d日"
        "de" -> "d. MMMM yyyy"
        "fr" -> "d MMMM yyyy"
        "ru" -> "d MMMM yyyy"
        else -> "MMMM d, yyyy"
    }

    private fun humanize(duration: java.time.Duration): String {
        val seconds = duration.seconds
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 45 -> "a few seconds"
            minutes < 2 -> "a minute"
            minutes < 45 -> "$minutes minutes"
            hours < 2 -> "an hour"
            hours < 22 -> "$hours hours"
            days < 2 -> "a day"
            days < 26 -> "$days days"
            else -> "${days / 30} months"
        }
    }

    /** `alternateGreetings` 在 env 里是单个字符串，用控制符分隔（见 App 层的组装约定）。 */
    const val ALT_GREETING_SEPARATOR = "\u0001"

    private val FALSE_VALUES = setOf("off", "false", "0")
    private val UTC_OFFSET = Regex("^UTC[+-]\\d+$", RegexOption.IGNORE_CASE)
    private val DIGITS_ONLY = Regex("^\\d+$")
    private val DICE = Regex("^([1-9]\\d*)?d([1-9]\\d*)([+-]\\d+)?$", RegexOption.IGNORE_CASE)
    private val ESCAPED_COMMA = "\\,"
    private val INVERTED_PREFIX = Regex("^\\s*!\\s*")
    private val LOCAL_VAR_SHORTHAND = Regex("^\\.[A-Za-z][\\w-]*$")
    private val GLOBAL_VAR_SHORTHAND = Regex("^\\$[A-Za-z][\\w-]*$")
}

/** 取最近的满足条件的那条消息（对应 `chat-macros.js:83-116`）。 */
internal fun MacroEnv.lastMessage(predicate: (MacroChatMessage) -> Boolean): MacroChatMessage? =
    lastMessageIndex(predicate)?.let { chat[it] }

internal fun MacroEnv.lastMessageIndex(predicate: (MacroChatMessage) -> Boolean): Int? {
    for (i in chat.indices.reversed()) {
        val message = chat[i]
        // 跳过「swipe 生成中」的消息（swipe_id 越界）
        if (message.swipes.isNotEmpty() && message.swipeId >= message.swipes.size) continue
        if (predicate(message)) return i
    }
    return null
}
