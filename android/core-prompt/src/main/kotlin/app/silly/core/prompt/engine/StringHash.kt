package app.silly.core.prompt.engine

/**
 * SillyTavern 的字符串哈希（`public/scripts/utils.js:522`，即 cyrb53）。
 *
 * 世界书的计时效果（sticky / cooldown）把**条目哈希**写进
 * `chat_metadata.timedWorldInfo`，加载时靠它找回条目。所以这个哈希必须和 ST
 * 算出来的一模一样，否则手机端和桌面端共用同一份聊天记录时，
 * 双方会各自认为「这个效果对应的条目找不到了」。
 *
 * 逐句对应 JS 实现：
 *
 * ```js
 * let h1 = 0xdeadbeef ^ seed, h2 = 0x41c6ce57 ^ seed;
 * for (...) { h1 = Math.imul(h1 ^ ch, 2654435761); h2 = Math.imul(h2 ^ ch, 1597334677); }
 * h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
 * h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
 * return 4294967296 * (2097151 & h2) + (h1 >>> 0);
 * ```
 *
 * 对应关系：
 *  - `Math.imul(a, b)` 在 JS 里就是「32 位有符号乘法回绕」，Kotlin 的 `Int * Int`
 *    天然就是这个语义，直接相乘即可；
 *  - JS 的 `^` / `>>>` 都是按 int32 运算，对应 Kotlin 的 `xor` / `ushr`；
 *  - 常量 `2654435761` 等超出 `Int` 范围，用 `.toInt()` 取回同样的位模式；
 *  - `charCodeAt(i)` 是 UTF-16 码元，Kotlin 的 `String[i].code` 语义相同
 *    （代理对会被拆成两个码元，和 JS 一致）。
 *
 * 结果最大约 2^53，`Long` 足够容纳。
 */
object StringHash {

    private const val H1_SEED = 0xdeadbeef.toInt()
    private const val H2_SEED = 0x41c6ce57
    private const val K1 = 2654435761L.toInt()
    private const val K2 = 1597334677
    private const val K3 = 2246822507L.toInt()
    private const val K4 = 3266489909L.toInt()

    fun hash(str: String, seed: Int = 0): Long {
        var h1 = H1_SEED xor seed
        var h2 = H2_SEED xor seed

        for (element in str) {
            val ch = element.code
            h1 = (h1 xor ch) * K1
            h2 = (h2 xor ch) * K2
        }

        h1 = (h1 xor (h1 ushr 16)) * K3 xor ((h2 xor (h2 ushr 13)) * K4)
        h2 = (h2 xor (h2 ushr 16)) * K3 xor ((h1 xor (h1 ushr 13)) * K4)

        val low = 2097151 and h2
        val high = h1.toLong() and 0xFFFF_FFFFL
        return low.toLong() * 4294967296L + high
    }
}
