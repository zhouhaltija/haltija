package app.haltija.core.prompt.engine

/**
 * 世界书内容的装饰器解析。
 *
 * 对应 `public/scripts/world-info.js:4652-4698` 的 `parseDecorators`。
 *
 * 装饰器是写在条目 **content 开头** 的 `@@` 行，只影响 content（键不受影响）。
 * 目前只有两个有实际作用：`@@activate`（无条件激活）与 `@@dont_activate`（抑制激活）。
 *
 * ## 三个反直觉的边界（ST 的真实行为，必须原样保留）
 *
 * 1. **前缀匹配而非等值匹配**：`isKnownDecorator` 用的是 `startsWith`，
 *    所以 `@@activateFoo` 会被**收集**进 decorators，但后续用的是精确
 *    `includes('@@activate')` ⇒ 既不激活也不抑制，**静默失效**。
 *
 * 2. **全是装饰器行时内容不被裁剪**：`newContent` 的初值就是原文，
 *    只有遇到第一行非 `@@` 内容才会覆盖它。所以 content 若整段都是 `@@` 行，
 *    返回的 content **仍然包含装饰器行**。
 *
 * 3. **`@@@` 是转义**：在尚未遇到未知装饰器（`fallbacked == false`）之前，`@@@xxx`
 *    行被直接跳过（既不算装饰器也不进内容）；但一旦前面出现过未知的 `@@` 行，
 *    `@@@activate` 会被当作 `@@activate` 收集。
 */
object Decorators {

    /** `world-info.js:100`。 */
    val KNOWN_DECORATORS = listOf("@@activate", "@@dont_activate")

    /** ST 里用于「立即激活」的装饰器字面量。 */
    const val ACTIVATE = "@@activate"

    /** ST 里用于「抑制激活」的装饰器字面量。 */
    const val DONT_ACTIVATE = "@@dont_activate"

    data class Parsed(val decorators: List<String>, val content: String)

    fun parse(content: String): Parsed {
        if (!content.startsWith("@@")) return Parsed(emptyList(), content)

        // 注意初值是原文 —— 这是边界 2 的来源
        var newContent = content
        val lines = content.split("\n")
        val decorators = ArrayList<String>()
        var fallbacked = false

        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("@@")) {
                if (line.startsWith("@@@") && !fallbacked) continue
                if (isKnownDecorator(line)) {
                    decorators += if (line.startsWith("@@@")) line.substring(1) else line
                    fallbacked = false
                } else {
                    fallbacked = true
                }
            } else {
                newContent = lines.subList(i, lines.size).joinToString("\n")
                break
            }
        }

        return Parsed(decorators, newContent)
    }

    private fun isKnownDecorator(data: String): Boolean {
        val normalized = if (data.startsWith("@@@")) data.substring(1) else data
        return KNOWN_DECORATORS.any { normalized.startsWith(it) }
    }
}
