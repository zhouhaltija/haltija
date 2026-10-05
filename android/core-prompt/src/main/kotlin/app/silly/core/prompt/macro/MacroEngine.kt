package app.silly.core.prompt.macro

/**
 * SillyTavern 宏引擎的 Kotlin 实现。
 *
 * 对应 `public/scripts/macros/engine/`（MacroLexer / MacroParser / MacroEngine /
 * MacroCstWalker / MacroRegistry，合计约 3100 行）。ST 用 chevrotain 写了一个
 * 完整的词法/语法分析器；这里实现的是**行为等价**的简化版本，
 * 用 [MacroEngineTest] 对着从真实引擎抽出来跑的 golden 逐条比对。
 *
 * ## 语法（`MacroLexer.js:83-84`、`MacroParser.js:136-184`）
 *
 * ```
 * {{名字}}                     无参数
 * {{名字:参数}}                单参数 —— 参数里**可以**再出现冒号
 * {{名字::参数1::参数2}}       多参数，用 `::` 分隔
 * ```
 *
 * `:` 与 `::` 的区别不是「吞不吞空格」，而是**单参数 vs 多参数列表**：
 *  - `{{echo:a:b}}`   → 一个参数 `a:b`
 *  - `{{echo:::a}}`   → `::` 分隔，一个参数 `:a`
 *  - `{{echo::a::b}}` → 两个参数 → echo 只收 1 个 → **整段回退成原文**
 *
 * 参数一律 `trim`；参数里可以嵌套宏（内层先求值）。
 *
 * ## 回退（fallback）规则 —— 最容易漏的一点
 *
 * 下面这些情况 ST 都**原样返回 `{{...}}` 文本**，而不是报错也不是替换成空：
 *  - 宏名未注册；
 *  - 参数个数与定义不符（多了或少了必填的）；
 *  - 语法不完整（`{{` 未闭合、`{{}}`、`{{ }}`）。
 *
 * 只有「宏名不是合法标识符」时才换一种处理：把开头的 `{{` 当作普通文本、从下一个字符继续扫。
 * 这条让 `{{{{char}}}}` 得到 `{{Seraphina}}`（外层花括号是文本，内层是宏）。
 *
 * ## 预处理与后处理（`MacroEngine.js:273-320`）
 *
 * 预处理（求值前）：
 *  - `{{time_UTC±N}}` → `{{time::UTC±N}}`
 *  - `<USER>` / `<BOT>` / `<CHAR>` / `<GROUP>` / `<CHARIFNOTGROUP>` → 对应宏
 *
 * 后处理（求值后）：
 *  - `\{` → `{`、`\}` → `}`（**这就是 `\{\{char\}\}` 能输出 `{{char}}` 的原因**）
 *  - **`{{trim}}` 连同其前后换行一起删掉** —— 它不是宏，是一条正则
 *  - 清掉残留的 ELSE 标记
 */

/** 参数类型。对齐 `MacroRegistry.js` 的 `MacroValueType`。 */
enum class MacroValueType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
}

/**
 * 一个匿名参数的声明。
 *
 * @param defaultValue 注意：**这个字段在求值时并不生效**。
 *   实测 `{{pad}}`（optional + defaultValue）拿到的仍是 `null`；
 *   ST 自己的 `{{space}}` 之所以有默认 1，是因为它的 handler 里写了 `count ?? 1`。
 *   这里保留字段只是为了对齐注册结构。
 */
data class MacroArgDef(
    val name: String,
    val type: MacroValueType = MacroValueType.STRING,
    val optional: Boolean = false,
    val defaultValue: String? = null,
)

/**
 * handler 拿到的上下文。
 *
 * @param resolve 再跑一次求值（含前后处理）。`{{if}}` 需要它来「只求值被选中的分支」。
 * @param isZeroArgMacro 该名字是否注册了零参宏。`{{if char}}` 要能把 `char` 展开成 `{{char}}`。
 */
class MacroContext(
    val env: MacroEnv,
    val unnamedArgs: List<String?>,
    /** 参数在宏体里的原始文本，形如 `up:abc`。 */
    val rawInner: String,
    val resolve: (String) -> String = { it },
    val isZeroArgMacro: (String) -> Boolean = { false },
)

fun interface MacroHandler {
    fun invoke(context: MacroContext): String
}

/**
 * 一条宏定义。
 *
 * @param isList list 宏（如 `{{random}}`）：参数个数**没有上限**，全部参数进 handler。
 * @param delayArgResolution 为真时**不对参数做嵌套求值**，原样交给 handler
 *   （`{{if}}` 靠它自己决定要不要求值、以及求值哪个分支）。
 */
class MacroDefinition(
    val name: String,
    val unnamedArgs: List<MacroArgDef> = emptyList(),
    val isList: Boolean = false,
    val delayArgResolution: Boolean = false,
    val handler: MacroHandler,
)

/** 宏注册表。 */
class MacroRegistry {
    private val macros = LinkedHashMap<String, MacroDefinition>()

    /**
     * 注册。同名覆盖（与 ST 的 `registerMacro` 一致）。
     *
     * **大小写不敏感**：ST 用 `name.toLowerCase()` 作 key
     * （`MacroRegistry.js:302`），所以 `{{USER}}` / `{{User}}` 都能命中 `user`。
     * 保留 [MacroDefinition.name] 的原始拼写只是为了报错好看。
     */
    fun register(definition: MacroDefinition) {
        macros[definition.name.lowercase()] = definition
    }

    fun register(
        name: String,
        unnamedArgs: List<MacroArgDef> = emptyList(),
        isList: Boolean = false,
        delayArgResolution: Boolean = false,
        handler: (MacroContext) -> String,
    ) = register(MacroDefinition(name, unnamedArgs, isList, delayArgResolution, MacroHandler(handler)))

    fun get(name: String): MacroDefinition? = macros[name.lowercase()]

    fun has(name: String): Boolean = macros.containsKey(name.lowercase())

    val names: Set<String> get() = macros.keys

    fun alias(target: String, alias: String) {
        macros[target.lowercase()]?.let {
            macros[alias.lowercase()] =
                MacroDefinition(alias, it.unnamedArgs, it.isList, it.delayArgResolution, it.handler)
        }
    }
}

class MacroEngine(
    val registry: MacroRegistry = MacroRegistry(),
    var env: MacroEnv = MacroEnv(),
) {

    /** 求值入口。 */
    fun evaluate(input: String): String {
        if (input.isEmpty()) return ""
        val preProcessed = runPreProcessors(input)
        val evaluated = evaluateDocument(preProcessed)
        return runPostProcessors(evaluated)
    }

    // ------------------------------------------------------------ 预处理

    private fun runPreProcessors(text: String): String {
        var result = text
        // {{time_UTC-10}} → {{time::UTC-10}}
        result = TIME_LEGACY.replace(result) { m -> "{{time::${m.groupValues[1]}}}" }
        // 老式尖括号标记
        result = result
            .replace(LT_USER, "{{user}}")
            .replace(LT_BOT, "{{char}}")
            .replace(LT_CHAR, "{{char}}")
            .replace(LT_GROUP, "{{group}}")
            .replace(LT_CHARIFNOTGROUP, "{{charIfNotGroup}}")
        return result
    }

    // ------------------------------------------------------------ 后处理

    private fun runPostProcessors(text: String): String {
        var result = text
        // \{ → { ，\} → }  —— 注意这一步**只在求值之后**做，
        // 所以 \{\{char\}\} 不会被当成宏求值，只是把反斜杠去掉
        result = UNESCAPE_BRACES.replace(result) { it.groupValues[1] }
        // {{trim}} 连同前后换行一起删掉（它不是宏，是后处理正则）
        result = LEGACY_TRIM.replace(result, "")
        // 清掉残留的 ELSE 标记
        result = result.replace(ELSE_MARKER, "")
        return result
    }

    // ------------------------------------------------------------ 求值

    private fun evaluateDocument(text: String): String {
        val items = tokenize(text)
        if (items.isEmpty()) return text
        return evaluateItems(items, text, pairScopes(items))
    }

    /** 从 `index`（指向 `{{`）开始做花括号配平，返回内部文本与结束下标。 */
    private fun matchBalanced(text: String, index: Int): Token? {
        var depth = 0
        var i = index
        while (i < text.length) {
            if (text.startsWith(MACRO_OPEN, i)) {
                depth++
                i += MACRO_OPEN.length
                continue
            }
            if (text.startsWith(MACRO_CLOSE, i)) {
                depth--
                i += MACRO_CLOSE.length
                if (depth == 0) {
                    return Token(text.substring(index + MACRO_OPEN.length, i - MACRO_CLOSE.length), i)
                }
                continue
            }
            i++
        }
        return null
    }

    private data class Token(val inner: String, val end: Int)

    // ------------------------------------------------------------ 词法

    /** 词法条目：一段文本，或一次宏调用。 */
    private sealed interface Item {
        val start: Int
        val end: Int

        data class Text(override val start: Int, override val end: Int) : Item
        data class Macro(override val start: Int, override val end: Int, val inner: String) : Item
    }

    private fun tokenize(text: String): List<Item> {
        val items = ArrayList<Item>()
        var i = 0
        var textStart = 0

        while (i < text.length) {
            if (!text.startsWith(MACRO_OPEN, i)) {
                i++
                continue
            }
            val token = matchBalanced(text, i)
            if (token == null) {
                i++
                continue
            }
            val inner = token.inner
            // 闭合标签 {{/name}} 与注释宏 {{// ...}} 的名字都不符合标识符规则，
            // 但都是合法语法，必须放行
            val accepted = isClosingTag(inner) || inner.startsWith("//") || isValidIdentifier(parseCall(inner).first)
            if (!accepted) {
                // 宏名不是合法标识符 ⇒ 把开头的 {{ 当普通文本，从下一个字符继续扫。
                // 这条让 {{{{char}}}} 得到 {{Seraphina}}：外层花括号是文本，内层才是宏。
                i += MACRO_OPEN.length
                continue
            }
            if (i > textStart) items += Item.Text(textStart, i)
            items += Item.Macro(i, token.end, inner)
            i = token.end
            textStart = i
        }

        if (textStart < text.length) items += Item.Text(textStart, text.length)
        return items
    }

    // ------------------------------------------------------------ 块宏配对

    /** 是否为闭合标签 `{{/name}}`。注释宏 `{{// ...}}` 不算。 */
    private fun isClosingTag(inner: String): Boolean =
        inner.startsWith("/") && isValidIdentifier(inner.substring(1).trim())

    /**
     * 把 `{{name …}}内容{{/name}}` 配对起来，返回「开标签下标 → 闭标签下标」。
     *
     * 用栈从内向外配对，同名匹配且大小写不敏感。嵌套的块会在各自层级被配对：
     * 外层配对时，内层的开/闭标签都落在 scoped 内容区间里，
     * 之后内容被重新当作子文档求值时再配对一次。
     */
    private fun pairScopes(items: List<Item>): Map<Int, Int> {
        val pairs = HashMap<Int, Int>()
        val openStack = ArrayList<Int>()

        for (i in items.indices) {
            val item = items[i] as? Item.Macro ?: continue
            if (isClosingTag(item.inner)) {
                val name = item.inner.substring(1).trim().lowercase()
                val openIndex = openStack.indexOfLast { idx ->
                    val open = items[idx] as Item.Macro
                    parseCall(open.inner).first.lowercase() == name
                }
                if (openIndex >= 0) {
                    pairs[openStack.removeAt(openIndex)] = i
                }
            } else {
                openStack += i
            }
        }
        return pairs
    }

    // ------------------------------------------------------------ 求值

    private fun evaluateItems(items: List<Item>, text: String, pairs: Map<Int, Int>): String {
        val out = StringBuilder()
        var i = 0

        while (i < items.size) {
            when (val item = items[i]) {
                is Item.Text -> out.append(text, item.start, item.end)

                is Item.Macro -> {
                    val closeIndex = pairs[i]
                    val scoped = if (closeIndex != null) {
                        val close = items[closeIndex] as Item.Macro
                        text.substring(item.end, close.start)
                    } else {
                        null
                    }

                    val name = parseCall(item.inner).first
                    val resolved = resolveMacro(name, item.inner, scoped)

                    if (resolved != null) {
                        out.append(resolved)
                    } else {
                        // 未注册 / 参数不匹配 / 未配对的闭合标签 ⇒ 原样保留
                        out.append(MACRO_OPEN).append(item.inner).append(MACRO_CLOSE)
                    }

                    i = if (closeIndex != null) closeIndex + 1 else i + 1
                    continue
                }
            }
            i++
        }
        return out.toString()
    }

    /**
     * 把宏体拆成「名字 + 参数」。
     *
     * 对应 `MacroParser.js:136-184` 的两条分支，外加一条容易漏的写法：
     *
     * | 写法 | 参数 |
     * |---|---|
     * | `{{echo abc}}` | **1 个**：`abc`（空白分隔也是单参数，不是「无参数 + 多余文本」） |
     * | `{{echo:abc}}` | 1 个：`abc`（内部可再含冒号） |
     * | `{{opt::a::b}}` | 2 个：`a`、`b` |
     * | `{{opt::}}` | 1 个：`""`（空参数是合法参数，不是 null） |
     *
     * 参数一律 trim（取值区间是首末 token，首尾空白天然被裁掉）。
     */
    private fun parseCall(inner: String): Pair<String, List<String>> {
        // 注释宏 `{{// ...}}`：名字就是 `//`，其余是参数
        if (inner.startsWith("//")) {
            val rest = inner.substring(2).trim()
            return "//" to if (rest.isEmpty()) emptyList() else listOf(rest)
        }

        var i = 0
        while (i < inner.length && inner[i].isWhitespace()) i++

        val nameStart = i
        while (i < inner.length && isIdentifierChar(inner[i])) i++
        val name = inner.substring(nameStart, i)

        while (i < inner.length && inner[i].isWhitespace()) i++
        val rest = inner.substring(i)

        val args = when {
            rest.startsWith("::") -> rest.substring(2).split("::").map { it.trim() }
            rest.startsWith(":") -> listOf(rest.substring(1).trim())
            rest.isEmpty() -> emptyList()
            // 空白分隔：整段是**一个**参数
            else -> listOf(rest.trim())
        }
        return name to args
    }

    private fun isIdentifierChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * 解析一条宏调用。返回 null 表示**应当原样保留**。
     *
     * 会在这里求值嵌套宏（内层先算）。
     */
    /**
     * 解析一条宏调用。返回 null 表示**应当原样保留**。
     *
     * @param scopedRaw 块宏的内容原文（`{{if x}}这段{{/if}}` 里的「这段」）。
     *   ST 把它**追加为最后一个匿名参数**（`MacroCstWalker.js:439-469`），
     *   所以 `{{setvar::n}}v{{/setvar}}` 等价于 `{{setvar::n::v}}`。
     */
    private fun resolveMacro(name: String, inner: String, scopedRaw: String? = null): String? {
        // 动态宏优先于注册表（对齐 #resolveMacro 的顺序）
        env.dynamicMacros[name.lowercase()]?.let { return it }

        val definition = registry.get(name) ?: return null

        val rawArgs = parseCall(inner).second.toMutableList()
        if (scopedRaw != null) rawArgs += scopedRaw

        // 参数个数校验：多了、或少于必填数，都回退（ST 的 strictArgs 默认开启）。
        // list 宏没有上限。
        if (!definition.isList && rawArgs.size > definition.unnamedArgs.size) return null
        val required = definition.unnamedArgs.count { !it.optional }
        if (rawArgs.size < required) return null

        // 类型校验：不合格同样回退成原文。list 宏没有声明参数，跳过。
        if (!definition.isList) {
            for (index in rawArgs.indices) {
                val def = definition.unnamedArgs[index]
                if (!isValueOfType(rawArgs[index], def.type)) return null
            }
        }

        // list 宏的参数不做声明长度补齐，全部原样传入
        if (definition.isList) {
            val args = rawArgs.map { if (definition.delayArgResolution) it else evaluateDocument(it) }
            return invoke(definition, args, inner)
        }

        // 补齐到声明长度（对应 JS 解构时缺失得到 undefined）
        val args = ArrayList<String?>(definition.unnamedArgs.size)
        for (index in definition.unnamedArgs.indices) {
            val raw = rawArgs.getOrNull(index)
            args += raw?.let { if (definition.delayArgResolution) it else evaluateDocument(it) }
        }

        return invoke(definition, args, inner)
    }

    private fun invoke(definition: MacroDefinition, args: List<String?>?, inner: String): String? =
        try {
            definition.handler.invoke(
                MacroContext(
                    env = env,
                    unnamedArgs = args ?: emptyList(),
                    rawInner = inner,
                    resolve = { evaluate(it) },
                    isZeroArgMacro = { name -> registry.get(name)?.let { it.unnamedArgs.isEmpty() && !it.isList } == true },
                ),
            )
        } catch (_: Exception) {
            // 与 ST 一致：handler 抛错时回退成原文，而不是让整段失败
            null
        }

    /** `MACRO_IDENTIFIER_PATTERN = /^[a-zA-Z][\w-_]*$/`（MacroLexer.js:17）。 */
    fun isValidIdentifier(name: String): Boolean = IDENTIFIER.matches(name)

    /**
     * 参数类型校验。对应 `MacroRegistry.js:769-788` 的 `isValueOfType`。
     *
     * **只校验不转型** —— handler 拿到的永远是字符串。
     *
     * 反直觉：`number` 类型下**空串是合法的**，因为 JS 的 `Number('') === 0`
     * 而 `Number.isFinite(0)` 为真。这里必须显式照顾，否则空参数会被误判为不合法。
     */
    fun isValueOfType(value: String, type: MacroValueType): Boolean {
        val trimmed = value.trim()
        return when (type) {
            MacroValueType.STRING -> true
            MacroValueType.INTEGER -> INTEGER_PATTERN.matches(trimmed)
            MacroValueType.NUMBER -> trimmed.isEmpty() || trimmed.toDoubleOrNull() != null
            MacroValueType.BOOLEAN -> trimmed.lowercase() in BOOLEAN_VALUES
        }
    }

    companion object {
        const val MACRO_OPEN = "{{"
        const val MACRO_CLOSE = "}}"

        /** `core-macros.js:24`。 */
        const val ELSE_MARKER = "\u0000\u001FELSE\u001F\u0000"

        private val IDENTIFIER = Regex("^[a-zA-Z][a-zA-Z0-9_-]*$")

        /** MacroRegistry.js:770 —— `/^-?\d+$/`。 */
        private val INTEGER_PATTERN = Regex("^-?\\d+$")

        /** utils.js:1010-1021 的 isTrueBoolean / isFalseBoolean 取值并集。 */
        private val BOOLEAN_VALUES = setOf("on", "true", "1", "off", "false", "0")

        private val TIME_LEGACY = Regex("""\{\{time_(UTC[+-]\d+)\}\}""", RegexOption.IGNORE_CASE)
        private val UNESCAPE_BRACES = Regex("""\\([{}])""")
        private val LEGACY_TRIM = Regex("""(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*""", RegexOption.IGNORE_CASE)

        private val LT_USER = Regex("<USER>", RegexOption.IGNORE_CASE)
        private val LT_BOT = Regex("<BOT>", RegexOption.IGNORE_CASE)
        private val LT_CHAR = Regex("<CHAR>", RegexOption.IGNORE_CASE)
        private val LT_GROUP = Regex("<GROUP>", RegexOption.IGNORE_CASE)
        private val LT_CHARIFNOTGROUP = Regex("<CHARIFNOTGROUP>", RegexOption.IGNORE_CASE)
    }
}
