package app.haltija.core.prompt.assemble

/**
 * Handlebars 的**最小可用子集**，只覆盖 SillyTavern 的 `story_string` 实际用到的语法。
 *
 * ## 为什么只做子集
 *
 * 统计 `SillyTavern/default/content/presets/context/` 下全部 34 个预设，
 * `story_string` 里出现的构造只有三种：
 *
 * | 构造 | 出现次数 |
 * |---|---|
 * | `{{#if 变量}}` … `{{/if}}` | 306 组 |
 * | `{{变量}}` | 340 处 |
 * | `{{trim}}`（其实是 ST 宏，不是 Handlebars） | 32 处 |
 *
 * 没有 `{{else}}`、没有 `{{#each}}`、没有其它 helper。
 * 所以这里只实现 `if` / `unless` / 变量 / 嵌套，其余语法保留原文并给出警告。
 *
 * ## 与 Handlebars 最容易搞错的一点：未定义的变量要**原样留下**
 *
 * ST 在 `public/scripts/macros.js:18-25` 注册了两个 helper：
 *
 * ```js
 * // 想让某个宏留到 story_string 之后再处理的，就登记成同名的 helper
 * Handlebars.registerHelper('trim', () => '{{trim}}');
 *
 * // 兜底：任何未定义的调用都原样交给 ST 宏引擎
 * Handlebars.registerHelper('helperMissing', function () {
 *     const options = arguments[arguments.length - 1];
 *     return substituteParams(`{{${options.name}}}`);
 * });
 * ```
 *
 * 所以 `{{trim}}` 渲染出来**仍然是 `{{trim}}` 字面量**，留给后面的宏引擎；
 * 而 `{{char}}` 因为参数里真有 `char`，直接取参数值。
 *
 * 本实现照此办理：[render] 遇到参数表里没有的变量时**原样输出**，
 * 而不是像裸 Handlebars 那样渲染成空串。
 *
 * ## 真值规则
 *
 * Handlebars 的 `if` 用的是 `Utils.isEmpty`，空数组与 `0` 都算**假**
 * （实测：`null` / `undefined` / `''` / `0` / `false` / `[]` → else 分支；
 * 非空数组、非空字符串、非零数字 → 真）。我们的参数全是字符串，
 * 所以退化为「非空即真」。
 *
 * ## 参数化宏的限制
 *
 * `{{random:a,b}}` 这种写法在 Handlebars 里是**语法错误**（`:` 不是合法的路径字符），
 * ST 会弹出 "Check the story string template for validity" 并抛异常。
 * 本实现选择更宽容的做法：原样保留并给出警告，让它能被后面的宏引擎处理。
 * 这是**有意的差异**，对用户更友好。
 */
object HandlebarsRenderer {

    /** 渲染结果。 */
    data class Result(
        val text: String,
        /** 未闭合的块、不支持的助手、疑似参数化宏等。 */
        val warnings: List<String> = emptyList(),
    )

    private sealed interface Node {
        data class Text(val value: String) : Node

        /** @param raw 参数缺失时原样输出的文本（对应 ST 的 helperMissing 透传）。 */
        data class Variable(val name: String, val raw: String) : Node

        data class Block(
            val helper: String,
            val argument: String,
            val body: List<Node>,
            val elseBody: List<Node>?,
        ) : Node
    }

    /** 与 `HandlebarsVariables` 用同一套 token 规则。 */
    private val TOKEN = Regex("""\{\{\{?\s*([^{}]*?)\s*\}?\}\}""")

    private val SUPPORTED_HELPERS = setOf("if", "unless")

    private val WHITESPACE = Regex("\\s+")

    fun render(template: String, params: Map<String, String>): Result {
        val (nodes, warnings) = parse(tokenize(template))
        val out = StringBuilder()
        evaluate(nodes, params, out)
        return Result(out.toString(), warnings)
    }

    /** 模板引用的变量名（含块参数），用于提前发现缺参。 */
    fun variables(template: String): Set<String> = HandlebarsVariables.of(template)

    // ------------------------------------------------------------ 词法

    private data class Token(val kind: Kind, val name: String, val raw: String) {
        enum class Kind { TEXT, VARIABLE, OPEN_BLOCK, CLOSE_BLOCK, ELSE }
    }

    private fun tokenize(template: String): List<Token> {
        val tokens = ArrayList<Token>()
        var cursor = 0

        for (match in TOKEN.findAll(template)) {
            if (match.range.first > cursor) {
                tokens += Token(Token.Kind.TEXT, "", template.substring(cursor, match.range.first))
            }
            val expr = match.groupValues[1].trim()
            when {
                expr.isEmpty() -> tokens += Token(Token.Kind.TEXT, "", match.value)

                expr.startsWith("#") -> {
                    val parts = expr.substring(1).trim().split(WHITESPACE, limit = 2)
                    val argument = parts.getOrNull(1)?.trim().orEmpty()
                    tokens += Token(Token.Kind.OPEN_BLOCK, parts[0], argument)
                }

                expr.startsWith("/") ->
                    tokens += Token(Token.Kind.CLOSE_BLOCK, expr.substring(1).trim(), "")

                expr == "else" -> tokens += Token(Token.Kind.ELSE, "", "")
                else -> tokens += Token(Token.Kind.VARIABLE, expr, match.value)
            }
            cursor = match.range.last + 1
        }

        if (cursor < template.length) {
            tokens += Token(Token.Kind.TEXT, "", template.substring(cursor))
        }
        return tokens
    }

    // ------------------------------------------------------------ 语法

    private class Frame(
        val helper: String,
        val argument: String,
        val body: MutableList<Node> = ArrayList(),
        var elseBody: MutableList<Node>? = null,
    ) {
        fun target(): MutableList<Node> = elseBody ?: body
    }

    private fun parse(tokens: List<Token>): Pair<List<Node>, List<String>> {
        val root = ArrayList<Node>()
        val stack = ArrayList<Frame>()
        val warnings = ArrayList<String>()

        fun emit(node: Node) {
            if (stack.isEmpty()) root += node else stack.last().target() += node
        }

        for (token in tokens) {
            when (token.kind) {
                Token.Kind.TEXT -> emit(Node.Text(token.raw))

                Token.Kind.VARIABLE -> {
                    // Handlebars 不允许路径里出现 `:`，ST 会直接抛错；这里宽容放行
                    if (':' in token.name) {
                        warnings += "{{${token.name}}} 含参数，Handlebars 无法解析（ST 会报错），已原样保留给宏引擎"
                    }
                    emit(Node.Variable(token.name, token.raw))
                }

                Token.Kind.OPEN_BLOCK -> {
                    if (token.name !in SUPPORTED_HELPERS) {
                        warnings += "不支持的 Handlebars 块助手 {{#${token.name}}}，已按「真」处理"
                    }
                    if (token.raw.isEmpty()) {
                        warnings += "{{#${token.name}}} 没有参数"
                    }
                    stack += Frame(token.name, token.raw)
                }

                Token.Kind.ELSE -> {
                    val frame = stack.lastOrNull() ?: continue
                    if (frame.elseBody == null) frame.elseBody = ArrayList()
                }

                Token.Kind.CLOSE_BLOCK -> {
                    val frame = stack.removeLastOrNull() ?: continue
                    emit(Node.Block(frame.helper, frame.argument, frame.body, frame.elseBody))
                }
            }
        }

        // 未闭合的块：把已收集的内容吐出来，而不是丢掉
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            warnings += "未闭合的 {{#${frame.helper}}}"
            frame.body.forEach(::emit)
            frame.elseBody?.forEach(::emit)
        }

        return root to warnings
    }

    // ------------------------------------------------------------ 求值

    private fun evaluate(nodes: List<Node>, params: Map<String, String>, out: StringBuilder) {
        for (node in nodes) {
            when (node) {
                is Node.Text -> out.append(node.value)

                is Node.Variable -> {
                    // 只有**参数表里存在**才替换；不存在就原样输出，
                    // 让后面的 ST 宏引擎接手（对应 helperMissing 的行为）
                    val value = params[node.name]
                    if (value != null) out.append(value) else out.append(node.raw)
                }

                is Node.Block -> {
                    val truthy = isTruthy(resolveBlockArgument(node.argument, params))
                    val takeBody = if (node.helper == "unless") !truthy else truthy
                    evaluate(if (takeBody) node.body else (node.elseBody ?: emptyList()), params, out)
                }
            }
        }
    }

    /**
     * 求一个 `{{#if …}}` 的参数值。
     *
     * Handlebars 在 **helper 参数位置**把 `true` / `false` / 数字当作**字面量**，
     * 而不是变量名 —— 所以 `{{#if true}}` 走的是「真」分支，
     * `{{#if 0}}` 走「假」（`Utils.isEmpty(0)` 为真）。
     *
     * 注意这与**独立变量**不同：`{{true}}` 里的 `true` 是路径查找，
     * 找不到就走 `helperMissing` 原样保留成 `{{true}}`。
     */
    private fun resolveBlockArgument(argument: String, params: Map<String, String>): String? = when {
        argument == "true" -> "true"
        argument == "false" -> ""
        argument.toDoubleOrNull() != null -> if (argument.toDouble() == 0.0) "" else argument
        else -> params[argument]
    }

    /** JS 真值语义在「值都是字符串」前提下的退化形式。 */
    fun isTruthy(value: String?): Boolean = !value.isNullOrEmpty()
}

/**
 * 从模板里静态提取变量名 —— 供组装阶段提前校验缺参。
 *
 * 与 ST 的 `validateStoryString`（`power-user.js:2266`）用途一致。
 */
object HandlebarsVariables {
    private val TOKEN = Regex("""\{\{\{?\s*([^{}]*?)\s*\}?\}\}""")
    private val BLOCK_KEYWORDS = setOf("if", "else", "each", "unless", "with", "lookup", "log", "this")
    private val IDENTIFIER = Regex("""[A-Za-z_@][A-Za-z0-9_.\-@]*""")
    private val WHITESPACE = Regex("\\s+")

    fun of(template: String): Set<String> {
        val found = LinkedHashSet<String>()
        for (match in TOKEN.findAll(template)) {
            var expr = match.groupValues[1].trim()
            if (expr.isEmpty()) continue

            val isBlock = expr[0] == '#' || expr[0] == '/' || expr[0] == '^'
            if (isBlock) expr = expr.substring(1).trim()

            val parts = expr.split(WHITESPACE).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            val head = parts[0]
            if (isBlock) {
                // {{#if x}} → 取第一个参数；{{^x}}（反向 section）→ head 本身就是变量
                val candidate = if (head in BLOCK_KEYWORDS) parts.getOrNull(1) else head
                candidate?.let { IDENTIFIER.find(it)?.value?.let(found::add) }
            } else {
                if (head in BLOCK_KEYWORDS) continue
                IDENTIFIER.find(head)?.value?.let(found::add)
            }
        }
        return found
    }
}
