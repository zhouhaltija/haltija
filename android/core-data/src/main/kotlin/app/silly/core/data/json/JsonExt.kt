package app.silly.core.data.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON 取值助手。
 *
 * 这里刻意区分两种语义：
 *  - [string] / [bool] / [int]：只接受类型正确的值，类型不符时返回 null。
 *  - [text]：模拟 JavaScript 的 `String(x)`，任何标量都强制转成字符串。
 *
 * SillyTavern 的前端大量使用后者（例如 `String(data.name)`），
 * 所以读卡时要能容忍 `"name": 123` 这类畸形数据。
 */

/** 只在该键是 JSON 字符串时返回其内容。 */
fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 模拟 JS `String(x)`：数字、布尔、字符串都转成文本；null / 对象 / 数组返回 null。 */
fun JsonObject.text(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/**
 * 布尔取值。
 *
 * 比严格的 true/false 更宽：数字按 JS 的真值规则处理（非 0 为真）。
 * 这是必要的 —— ST 里 `entry.disable == true`、`entry.constant`、`entry.selective`
 * 用的都是 JS 真值判断，数据里写成 `1` 时会被当成真。
 */
fun JsonObject.bool(key: String): Boolean? =
    when (val p = this[key] as? JsonPrimitive) {
        null -> null
        else -> when (p.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> p.content.toDoubleOrNull()?.let { it != 0.0 }
        }
    }

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** 取字符串数组，非字符串元素被丢弃（与 ST 的宽容度一致）。 */
fun JsonObject.stringList(key: String): List<String> =
    array(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()

/** 取字符串数组，非字符串元素先被强制转成文本。 */
fun JsonObject.textList(key: String): List<String> =
    array(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList()

fun JsonObject.has(key: String): Boolean = containsKey(key)

/** 浅合并：用 [overrides] 覆盖本对象，未提及的键原样保留。 */
fun JsonObject.mergedWith(overrides: Map<String, JsonElement>): JsonObject =
    JsonObject(this + overrides)
