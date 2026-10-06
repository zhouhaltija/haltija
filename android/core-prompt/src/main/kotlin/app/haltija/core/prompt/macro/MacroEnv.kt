package app.haltija.core.prompt.macro

import java.time.ZonedDateTime
import java.util.Locale

/**
 * 聊天消息在宏引擎里的最小视图。
 *
 * 只需要内置宏真正会读的字段：正文、角色标记、时间戳、swipe。
 * 与 `core-data` 的 `ChatMessage` 解耦，避免宏引擎依赖整个数据层。
 */
data class MacroChatMessage(
    val mes: String,
    val isUser: Boolean,
    val isSystem: Boolean = false,
    /** ISO 时间串，供 `{{idleDuration}}` 之类使用。 */
    val sendDate: String? = null,
    val swipes: List<String> = emptyList(),
    val swipeId: Int = 0,
)

/**
 * 变量存储。
 *
 * 对应 ST 的两套作用域（`variables.js`）：
 *  - **局部**：落盘在 `chat_metadata.variables[name]`，按聊天存档；
 *  - **全局**：落盘在 `extension_settings.variables.global[name]`。
 *
 * App 层负责把这两个 map 与真实存储对接；宏引擎只操作内存。
 */
class MacroVariables(
    val local: MutableMap<String, String> = linkedMapOf(),
    val global: MutableMap<String, String> = linkedMapOf(),
) {
    private fun scope(isGlobal: Boolean): MutableMap<String, String> = if (isGlobal) global else local

    fun get(name: String, isGlobal: Boolean): String? = scope(isGlobal)[name]

    fun set(name: String, value: String, isGlobal: Boolean) {
        scope(isGlobal)[name] = value
    }

    fun has(name: String, isGlobal: Boolean): Boolean = scope(isGlobal).containsKey(name)

    fun delete(name: String, isGlobal: Boolean) {
        scope(isGlobal).remove(name)
    }

    /**
     * 对应 ST 的 `add`：能都量化成数字就相加，否则字符串拼接。
     *
     * （ST 还有「数组则 push」的分支，移动端先不实现；见
     * `docs/macro-engine-spec.md` §5.6。）
     */
    fun add(name: String, value: String, isGlobal: Boolean): String {
        val map = scope(isGlobal)
        val current = map[name]
        val result = if (current == null) {
            value
        } else {
            val a = current.toDoubleOrNull()
            val b = value.toDoubleOrNull()
            if (a != null && b != null) formatNumber(a + b) else current + value
        }
        map[name] = result
        return result
    }

    /** 对应 ST 的 `get`：能当数字就返回数字的字符串形式，否则原样。 */
    fun getNormalized(name: String, isGlobal: Boolean): String = get(name, isGlobal).orEmpty()

    fun setToEngine(name: String, value: String, isGlobal: Boolean) = set(name, value, isGlobal)

    fun copy(): MacroVariables = MacroVariables(LinkedHashMap(local), LinkedHashMap(global))

    companion object {
        /** 整数不显示 `.0`，对应 JS 里数字直接 toString 的行为。 */
        private fun formatNumber(v: Double): String =
            if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}

/**
 * 求值环境。
 *
 * ST 的 env 由 `MacroEnvBuilder` 构造（`MacroEnvBuilder.js:81-175`），
 * 表面很宽。这里只保留内置宏真正会读的部分。
 */
class MacroEnv(
    /** `names.char` / `names.user` / `names.group` / `names.groupNotMuted` / `names.notChar`。 */
    val names: Map<String, String> = emptyMap(),

    /** `character.description` / `personality` / `scenario` / `persona` / `charPrompt` … */
    val character: Map<String, String> = emptyMap(),

    /** `system.model` 等。 */
    val system: Map<String, String> = emptyMap(),

    /** 动态宏：名字（小写）→ 返回值。优先级高于注册表。 */
    val dynamicMacros: Map<String, String> = emptyMap(),

    /** 随机源，注入以便测试。 */
    val random: () -> Double = { Math.random() },

    val variables: MacroVariables = MacroVariables(),

    /** 当前聊天，供 `{{lastMessage}}` 一类的宏使用。最新的在**末尾**（与 ST 的 chat 数组一致）。 */
    val chat: List<MacroChatMessage> = emptyList(),

    /** 时钟，注入以便测试。 */
    val clock: () -> ZonedDateTime = { ZonedDateTime.now() },

    /** 本地化格式用。`{{date}}` / `{{time}}` 的 `LL` / `LT` 依赖它。 */
    val locale: Locale = Locale.ENGLISH,

    /** `{{input}}` —— 输入框里的文字。移动端由 App 层填入。 */
    val input: String = "",

    /** `{{isMobile}}` 的返回值。 */
    val isMobile: Boolean = true,
) {
    fun name(key: String): String = names[key] ?: ""
    fun character(key: String): String = character[key] ?: ""
    fun system(key: String): String = system[key] ?: ""

    /** 只换随机源/时钟之类的派生一份，其余共享。 */
    fun with(
        clock: (() -> ZonedDateTime)? = null,
        random: (() -> Double)? = null,
        chat: List<MacroChatMessage>? = null,
        input: String? = null,
    ): MacroEnv = MacroEnv(
        names = names,
        character = character,
        system = system,
        dynamicMacros = dynamicMacros,
        random = random ?: this.random,
        variables = variables,
        chat = chat ?: this.chat,
        clock = clock ?: this.clock,
        locale = locale,
        input = input ?: this.input,
        isMobile = isMobile,
    )
}
