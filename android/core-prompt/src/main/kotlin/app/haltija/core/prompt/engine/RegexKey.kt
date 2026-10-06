package app.haltija.core.prompt.engine

/**
 * 斜杠分隔的正则字面量，例如 `/whisper(s)?/i`。
 *
 * 严格照搬 SillyTavern 的 `parseRegexFromString`
 * （`public/scripts/world-info.js:2901`）：
 *
 *  1. 必须完整匹配 `^/([\w\W]+?)/([gimsuy]*)$`，即两端有斜杠、修饰符只能来自 `gimsuy`；
 *  2. 如果模式内部出现**未转义的**斜杠，判为非法（JS 本身不在意分隔符，
 *     但 ST 要求它作为一个可移植的正则字面量成立）；
 *  3. `\/` 反转义回 `/`；
 *  4. 编译失败返回 null。
 *
 * ### 与 JavaScript 的修饰符差异
 *
 * JVM 的 `Regex` 没有与 JS 完全对应的选项，映射关系如下：
 *
 * | JS | JVM | 说明 |
 * |---|---|---|
 * | `i` | `IGNORE_CASE` | 一致 |
 * | `m` | `MULTILINE` | 一致 |
 * | `s` | `DOT_MATCHES_ALL` | 一致 |
 * | `u` | 忽略 | JVM 的正则本来就按码点处理、原生支持 `\p{...}`，
 *       无需开关。但注意一处分歧：JS 在**没有** `u` 时按 Annex B 把
 *       `\p{L}` 当作字面量，JVM 始终按 Unicode 属性解释。
 *       这种写法在真实世界书里不会出现，且见 [RegexKeyConformanceTest] 的对照结果 |
 * | `g` | 忽略 | 只影响 `test()` 的 `lastIndex` 状态；我们每次都用无状态匹配 |
 * | `y` | 忽略 | 粘性匹配，ST 的用法里不影响「是否命中」的判定 |
 */
class RegexKey internal constructor(
    val regex: Regex,
    /** 去掉斜杠与修饰符后的模式本身。 */
    val pattern: String,
    /** 原始修饰符串，例如 `"i"`。 */
    val flags: String,
) {
    fun matches(text: String): Boolean = regex.containsMatchIn(text)

    override fun toString(): String = "/$pattern/$flags"

    companion object {
        /** `^/([\w\W]+?)/([gimsuy]*)$` —— 与 ST 完全一致。 */
        private val DELIMITED = Regex("""^/([\w\W]+?)/([gimsuy]*)$""")

        /** 未转义的斜杠。 */
        private val UNESCAPED_SLASH = Regex("""(^|[^\\])/""")

        fun parse(input: String): RegexKey? {
            val match = DELIMITED.matchEntire(input) ?: return null
            var pattern = match.groupValues[1]
            val flags = match.groupValues[2]

            // JS 的 new RegExp(p, 'ii') 会抛 SyntaxError，ST 因此返回 null。
            // Kotlin 把修饰符收进 Set，重复项会被静默吞掉，必须显式拒绝。
            if (flags.toSet().size != flags.length) return null

            if (UNESCAPED_SLASH.containsMatchIn(pattern)) return null
            // 注意：JS 的 String.replace 传字符串时**只替换第一处**，不是全局。
            // 这里必须用 replaceFirst 才能与 ST 的内部变量逐字一致。
            // （对匹配结果没有影响 —— `\/` 在正则里就是字面量 `/` —— 但哈希与调试输出会不同。）
            pattern = pattern.replaceFirst("\\/", "/")

            val options = buildSet {
                if ('i' in flags) add(RegexOption.IGNORE_CASE)
                if ('m' in flags) add(RegexOption.MULTILINE)
                if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
            }

            // JS 的 y（sticky）要求匹配必须从 lastIndex 开始；新建的正则 lastIndex=0，
            // 等价于「必须从串首开始」。JVM 没有对应开关，用 \A（不受 MULTILINE 影响）
            // 显式锚定来模拟。不模拟的话，`/abc/y` 会被当成普通包含匹配，
            // 在 "xabcx" 上得出错误的 true。
            val effective = if ('y' in flags) "\\A(?:$pattern)" else pattern

            return try {
                RegexKey(Regex(effective, options), pattern, flags)
            } catch (_: Exception) {
                null
            }
        }

        /** 这个字符串是不是一个合法的斜杠正则。 */
        fun isValid(input: String): Boolean = parse(input) != null
    }
}
