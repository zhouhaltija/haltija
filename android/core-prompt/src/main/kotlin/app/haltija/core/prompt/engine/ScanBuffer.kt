package app.haltija.core.prompt.engine

import app.haltija.core.data.world.SelectiveLogic

/**
 * 世界书扫描缓冲。
 *
 * 对应 SillyTavern 的 `WorldInfoBuffer`（`public/scripts/world-info.js:199-474`）。
 *
 * 它负责三件事：
 *  1. 把聊天消息按「深度」切出一段文本（[get]）；
 *  2. 对这段文本做键匹配，含正则键、大小写、整词（[matchKeys]）；
 *  3. 给分组评分提供分数（[getScore]）。
 *
 * ## 深度约定
 *
 * 传入的消息必须**最新在前**（下标 0 = 最后一条）。
 * `script.js:4624` 就是这么构造的：`coreChat.map(...).reverse()`。
 *
 * ## 那些 `\u0001` 是什么
 *
 * ST 用 `MATCHER = '\x01'` 做段首哨兵、`JOINER = '\n\x01'` 连接各段。
 * 它不只是排版：`\x01` 是非单词字符，正好充当整词匹配里 `\W` 的边界，
 * 所以第一段文本也能被 `(?:^|\W)` 正确匹配到。**必须原样保留。**
 *
 * @param messages 聊天消息文本，**最新的在前**。
 * @param globalScanData 角色卡 / persona 等聊天外文本，供 `match*` 开关使用。
 * @param settings 全局设置。
 * @param substitute 宏替换函数。ST 在匹配前对每个键做 `substituteParams`
 *   （`world-info.js:4915`）；默认不做替换，方便测试。
 */
class ScanBuffer(
    messages: List<String>,
    private val globalScanData: GlobalScanData = GlobalScanData.EMPTY,
    private val settings: WorldInfoSettings = WorldInfoSettings.DEFAULT,
    private val substitute: (String) -> String = { it },
) {

    /**
     * 按深度索引的消息。`null` 表示该深度没有消息（JS 里是数组空洞，
     * `join` 时输出空串）。
     */
    private val depthBuffer: Array<String?> = arrayOfNulls(MAX_SCAN_DEPTH)

    /**
     * 有效长度。
     *
     * JS 的数组只增长到最高被赋值过的下标，`slice(0, depth)` 会被截断到这个长度。
     * Kotlin 用定长数组，必须自己记住长度，否则会多拼出一堆空段。
     */
    private val depthBufferLength: Int = minOf(messages.size, MAX_SCAN_DEPTH)

    private val recurseBuffer = ArrayList<String>()
    private val injectBuffer = ArrayList<String>()

    /** min-activations 模式下的深度增量。 */
    private var skew: Int = 0

    init {
        // 与 #initDepthBuffer（L250-260）逐句对应
        var depth = 0
        while (depth < MAX_SCAN_DEPTH) {
            if (depth < messages.size && messages[depth].isNotEmpty()) {
                depthBuffer[depth] = messages[depth].trim()
            }
            if (depth == messages.size - 1) break
            depth++
        }
    }

    // ------------------------------------------------------------ 缓冲管理

    /** 追加递归文本。只增不减（与 ST 的 `addRecurse` 一致）。 */
    fun addRecurse(message: String) {
        recurseBuffer += message
    }

    /** 追加扩展注入文本（对应 `prompt.scan` 为真的扩展提示）。 */
    fun addInject(message: String) {
        injectBuffer += message
    }

    fun hasRecurse(): Boolean = recurseBuffer.isNotEmpty()

    fun hasInject(): Boolean = injectBuffer.isNotEmpty()

    /** 深度增量 +1。 */
    fun advanceScan() {
        skew++
    }

    /** 当前基准深度 = 全局深度 + skew。 */
    fun getDepth(): Int = settings.depth + skew

    fun resetExternalEffects() {
        // 外部强制激活表在引擎里持有，这里留出清理钩子
    }

    // ------------------------------------------------------------ 扫描文本

    /**
     * 取出用于匹配的扫描文本。
     *
     * 与 `get()`（L279-328）逐句对应。注意几个容易写错的地方：
     *  - `scanDepth = 0` 会让 [startDepth] 判断为真而返回空串 ⇒ **该条目永不命中**；
     *  - 只要 depth > 0，返回值至少是 `"\u0001"`，**不可能是空串**；
     *  - 递归缓冲只在**非** [ScanState.MIN_ACTIVATIONS] 轮次拼接。
     */
    fun get(entry: ScanEntry, scanState: ScanState): String {
        var depth = entry.scanDepth ?: getDepth()

        if (depth <= START_DEPTH) return ""
        if (depth < 0) return ""
        if (depth > MAX_SCAN_DEPTH) depth = MAX_SCAN_DEPTH

        val end = minOf(depth, depthBufferLength)
        val sb = StringBuilder()
        sb.append(MATCHER)
        for (i in 0 until end) {
            if (i > 0) sb.append(JOINER)
            sb.append(depthBuffer[i].orEmpty())
        }

        val data = globalScanData
        val raw = entry.entry
        if (raw.matchPersonaDescription && data.personaDescription.isNotEmpty()) {
            sb.append(JOINER).append(data.personaDescription)
        }
        if (raw.matchCharacterDescription && data.characterDescription.isNotEmpty()) {
            sb.append(JOINER).append(data.characterDescription)
        }
        if (raw.matchCharacterPersonality && data.characterPersonality.isNotEmpty()) {
            sb.append(JOINER).append(data.characterPersonality)
        }
        if (raw.matchCharacterDepthPrompt && data.characterDepthPrompt.isNotEmpty()) {
            sb.append(JOINER).append(data.characterDepthPrompt)
        }
        if (raw.matchScenario && data.scenario.isNotEmpty()) {
            sb.append(JOINER).append(data.scenario)
        }
        if (raw.matchCreatorNotes && data.creatorNotes.isNotEmpty()) {
            sb.append(JOINER).append(data.creatorNotes)
        }

        if (injectBuffer.isNotEmpty()) {
            sb.append(JOINER).append(injectBuffer.joinToString(JOINER))
        }

        // Min activations 轮次看不到递归内容
        if (recurseBuffer.isNotEmpty() && scanState != ScanState.MIN_ACTIVATIONS) {
            sb.append(JOINER).append(recurseBuffer.joinToString(JOINER))
        }

        return sb.toString()
    }

    // ------------------------------------------------------------ 键匹配

    /**
     * 键匹配。与 `matchKeys()`（L337-366）逐句对应。
     *
     * 三个关键点，写错任何一个都会让世界书激活行为偏离：
     *
     *  1. **正则键在大小写转换之前匹配**，并且**无视** `caseSensitive` 与 `matchWholeWords`
     *     —— 正则自己的修饰符说了算；
     *  2. **整词匹配只对单 token 的普通键生效**；键里含空白（多个 token）时退化为
     *     纯 `includes`，不做任何边界检查；
     *  3. 整词边界用的是 `\W`（非单词字符），而不是 `\b`，所以标点和
     *     `\u0001` 哨兵都算边界。
     */
    fun matchKeys(haystack: String, needle: String, entry: ScanEntry): Boolean {
        // 正则键：直接匹配原始文本
        val keyRegex = RegexKey.parse(needle)
        if (keyRegex != null) return keyRegex.matches(haystack)

        val hay = transform(haystack, entry)
        val ndl = transform(needle, entry)
        val matchWholeWords = entry.entry.matchWholeWords ?: settings.matchWholeWords

        if (matchWholeWords) {
            val words = ndl.split(WHITESPACE)
            if (words.size > 1) return hay.contains(ndl)
            val pattern = Regex("(?:^|\\W)(${escapeRegex(ndl)})(?:$|\\W)")
            return pattern.containsMatchIn(hay)
        }
        return hay.contains(ndl)
    }

    /**
     * 分组评分。与 `getScore()`（L428-473）逐句对应。
     *
     * 注意它与主激活路径的**三处不一致**（ST 自己的历史遗留）：
     *  - 键**不做 trim**；
     *  - 键**不做宏替换**；
     *  - `selectiveLogic` **不做默认值兜底**，未知取值只返回主键分数。
     */
    fun getScore(entry: ScanEntry, scanState: ScanState): Int {
        val bufferState = get(entry, scanState)
        val primaryKeys = entry.entry.keys
        val secondaryKeys = entry.entry.secondaryKeys

        var primaryScore = 0
        for (key in primaryKeys) {
            if (matchKeys(bufferState, key, entry)) primaryScore++
        }

        var secondaryScore = 0
        for (key in secondaryKeys) {
            if (matchKeys(bufferState, key, entry)) secondaryScore++
        }

        if (primaryKeys.isEmpty()) return 0

        if (secondaryKeys.isNotEmpty()) {
            when (entry.entry.selectiveLogic) {
                SelectiveLogic.AND_ANY -> return primaryScore + secondaryScore
                SelectiveLogic.AND_ALL ->
                    return if (secondaryScore == secondaryKeys.size) {
                        primaryScore + secondaryScore
                    } else {
                        primaryScore
                    }
                // NOT_ANY / NOT_ALL / null（不兜底）都只算主键
                else -> Unit
            }
        }

        return primaryScore
    }

    // ------------------------------------------------------------ 内部

    /** 应用大小写设置：`entry.caseSensitive ?? 全局`，为假则转小写。 */
    private fun transform(str: String, entry: ScanEntry): String {
        val caseSensitive = entry.entry.caseSensitive ?: settings.caseSensitive
        return if (caseSensitive) str else str.lowercase()
    }

    /**
     * 宏替换后再 trim —— 与激活路径一致（`world-info.js:4916`）。
     * 空结果视为不命中。
     */
    fun substitutedKey(raw: String): String? {
        val substituted = substitute(raw)
        if (substituted.isEmpty()) return null
        return substituted.trim()
    }

    /** 供引擎使用的当前 skew（测试与调试）。 */
    val currentSkew: Int get() = skew

    companion object {
        /** `world-info.js:98`。 */
        const val MAX_SCAN_DEPTH = 1000

        /** `world-info.js:233` —— 全程恒为 0，从未被修改。 */
        const val START_DEPTH = 0

        /** 段首哨兵。同时充当整词匹配的 `\W` 边界。 */
        const val MATCHER = "\u0001"

        /** 段间连接符。 */
        const val JOINER = "\n\u0001"

        private val WHITESPACE = Regex("\\s+")

        /** `utils.js:1377` 的 `escapeRegex`。注意它连 `-` 和 `/` 都会转义。 */
        private val REGEX_SPECIALS = setOf(
            '/', '-', '\\', '^', '$', '*', '+', '?', '.', '(', ')', '|', '[', ']', '{', '}',
        )

        fun escapeRegex(input: String): String = buildString(input.length * 2) {
            for (ch in input) {
                if (ch in REGEX_SPECIALS) append('\\')
                append(ch)
            }
        }
    }
}
