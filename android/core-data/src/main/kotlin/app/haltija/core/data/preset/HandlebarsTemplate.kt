package app.haltija.core.data.preset

/**
 * Handlebars 模板的最小分析工具。
 *
 * `story_string` 是 Handlebars 模板，例如 Adventure 预设：
 *
 * ```handlebars
 * {{#if anchorBefore}}{{anchorBefore}}
 * {{/if}}{{#if system}}{{system}}
 * {{/if}}…{{trim}}
 * ```
 *
 * 我们**不实现 Handlebars 解释器**（那是另一个量级的工程，而且 ST 用的是
 * 官方 handlebars 库，语义细节很多）。这里只做一件轻量但对 Prompt 组装很关键的事：
 * **静态解析出模板引用了哪些变量**，这样组装阶段就知道该准备什么、
 * 也能在变量缺失时提前报错，而不是渲染出一堆空字符串。
 */
object HandlebarsTemplate {

    /** 匹配 `{{x}}`、`{{{x}}}`、`{{#if x}}`、`{{/if}}` 等。 */
    private val TOKEN = Regex("""\{\{\{?\s*([^{}]*?)\s*\}?\}\}""")

    /** 块级关键字，本身不是变量。 */
    private val BLOCK_KEYWORDS = setOf(
        "if", "else", "each", "unless", "with", "lookup", "log", "this",
    )

    private val IDENTIFIER = Regex("""[A-Za-z_@][A-Za-z0-9_.\-@]*""")

    /**
     * 提取模板中引用的变量名，按出现顺序去重。
     *
     * - `{{#if description}}` → `description`
     * - `{{/if}}` / `{{else}}` → 无
     * - `{{char}}` → `char`
     * - `{{trim}}` → `trim`（ST 里它是个宏而非变量，但同样需要在组装阶段被处理，
     *   所以一并返回，由调用方决定怎么对待）
     */
    fun variables(template: String): Set<String> {
        val found = LinkedHashSet<String>()
        for (match in TOKEN.findAll(template)) {
            var expr = match.groupValues[1].trim()
            if (expr.isEmpty()) continue

            val isBlock = expr[0] == '#' || expr[0] == '/' || expr[0] == '^'
            if (isBlock) expr = expr.substring(1).trim()

            val parts = expr.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue

            val head = parts[0]
            if (isBlock) {
                // {{#if x}} → 取第一个参数；{{^x}}（反向 section）→ head 本身就是变量；
                // {{/if}} 这种闭合标签不产生变量。
                val candidate = if (head in BLOCK_KEYWORDS) parts.getOrNull(1) else head
                candidate?.let { IDENTIFIER.find(it)?.value?.let(found::add) }
            } else {
                if (head in BLOCK_KEYWORDS) continue
                IDENTIFIER.find(head)?.value?.let(found::add)
            }
        }
        return found
    }

    /** 模板里是否含有任何 Handlebars 语法。 */
    fun isTemplate(text: String): Boolean = TOKEN.containsMatchIn(text)
}
