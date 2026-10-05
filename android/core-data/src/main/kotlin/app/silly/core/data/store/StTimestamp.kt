package app.silly.core.data.store

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * SillyTavern 用到的两种时间戳。
 *
 * 它们**格式不同、时区也不同**，混用会让 ST 那边对不上：
 *
 * | 用途 | 函数 | 格式 | 时区 |
 * |---|---|---|---|
 * | 消息体里的 `send_date` | `getMessageTimeStamp()` | `2026-10-05T08:30:45.123Z` | **恒定 UTC** |
 * | 聊天文件名里的那一段 | `humanizedDateTime()` | `2026-10-05@16h30m45s123ms` | **本地时间** |
 *
 * 注意第二行：ST 的 `humanizedDateTime()` 用的是 `getFullYear()` 这类**本地**取值，
 * 所以同一个瞬间在不同时区下文件名不同。这不是 bug，是想让文件名对用户可读。
 *
 * 两种格式的毫秒都**补齐三位**，所以不能直接用 `DateTimeFormatter.ISO_INSTANT`
 * （它会在毫秒为 0 时省略 `.000`）。
 */
object StTimestamp {

    private val MESSAGE_FORMAT = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    private val HUMANIZED_DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 消息体里的 `send_date`，对应 ST 的 `getMessageTimeStamp()`。 */
    fun messageTimestamp(instant: Instant): String = MESSAGE_FORMAT.format(instant)

    /** 聊天文件名里的时间戳，对应 ST 的 `humanizedDateTime()`（本地时间）。 */
    fun humanized(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = instant.atZone(zone)
        val date = HUMANIZED_DATE.format(local)
        // 手工拼后半段：ST 用 h/m/s/ms 做分隔符，DateTimeFormatter 的表达力不够直白
        return buildString {
            append(date)
            append('@')
            append(pad(local.hour, 2)).append('h')
            append(pad(local.minute, 2)).append('m')
            append(pad(local.second, 2)).append('s')
            append(pad(local.nano / 1_000_000, 3)).append("ms")
        }
    }

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
