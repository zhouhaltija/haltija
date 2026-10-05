package app.silly.core.data.card

import app.silly.core.data.json.array
import app.silly.core.data.json.obj
import app.silly.core.data.json.string
import app.silly.core.data.json.text
import app.silly.core.data.json.textList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 一张角色卡。
 *
 * **为什么不用 `@Serializable data class`：**
 * 角色卡里塞满了第三方字段 —— `extensions`、`character_book`、各家前端自己加进去的键。
 * 用强类型模型解码再编码会把这些字段丢掉，等于静默损坏用户的卡。
 * 所以这里以**原始 [JsonObject] 为唯一真相来源**，只在其上提供类型化读取入口，
 * 写回时保证除显式修改外一字不差。
 *
 * SillyTavern 支持三代格式：
 *  - **V1**：扁平对象，没有 `spec`，没有 `data`；
 *  - **V2**：`spec = "chara_card_v2"`，字段收在 `data` 里；
 *  - **V3**：`spec = "chara_card_v3"`，在 V2 基础上增加 `assets` / `group_only_greetings`。
 *
 * 三者的差异被 [data] 这一个属性抹平：V1 时它就等于 [root]。
 */
class CharacterCard(val root: JsonObject) {

    val spec: String? = root.string("spec")
    val specVersion: String? = root.string("spec_version")

    /** V1 卡：既没有 `spec`，也没有 `data` 对象。 */
    val isV1: Boolean = spec == null && root["data"] !is JsonObject

    /** V2/V3 的有效字段容器；V1 就是根对象本身。 */
    val data: JsonObject = (root["data"] as? JsonObject) ?: root

    val name: String = data.text("name").orEmpty()
    val description: String = data.text("description").orEmpty()
    val personality: String = data.text("personality").orEmpty()
    val scenario: String = data.text("scenario").orEmpty()
    val firstMes: String = data.text("first_mes").orEmpty()
    val mesExample: String = data.text("mes_example").orEmpty()

    /** V1 写作 `creatorcomment`，V2+ 写作 `creator_notes`（V3 卡里两者往往同时存在）。 */
    val creatorNotes: String = (data.text("creator_notes") ?: root.text("creatorcomment")).orEmpty()

    val systemPrompt: String = data.text("system_prompt").orEmpty()
    val postHistoryInstructions: String = data.text("post_history_instructions").orEmpty()

    val alternateGreetings: List<String> = data.textList("alternate_greetings")
    val groupOnlyGreetings: List<String> = data.textList("group_only_greetings")

    val tags: List<String> = data.textList("tags")
    val creator: String = data.text("creator").orEmpty()
    val characterVersion: String = data.text("character_version").orEmpty()

    /** 卡内嵌的世界书（`character_book`）。 */
    val characterBook: JsonObject? = data.obj("character_book")

    /** 第三方扩展数据，必须原样保留。 */
    val extensions: JsonObject? = data.obj("extensions")

    /** CharX 内嵌资源的索引（每个元素含 `uri` / `type` / `name` / `ext`）。 */
    val assets: JsonArray? = data.array("assets")

    /** 全部开场白：`first_mes` 在前，`alternate_greetings` 依次跟在后面。 */
    val greetings: List<String> = buildList {
        if (firstMes.isNotEmpty()) add(firstMes)
        addAll(alternateGreetings)
    }

    /** 原样返回，不做任何规范化 —— 用于无损写回。 */
    fun toJsonObject(): JsonObject = root

    override fun toString(): String = "CharacterCard(spec=${spec ?: "v1"}, name='$name')"
}
