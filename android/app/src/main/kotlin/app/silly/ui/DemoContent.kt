package app.silly.ui

import app.silly.core.data.card.CharacterCard
import app.silly.core.data.world.WorldBook
import app.silly.core.prompt.assemble.PromptAssembler
import app.silly.core.prompt.assemble.StoryString
import app.silly.core.prompt.engine.GlobalScanData
import app.silly.core.prompt.engine.LoreCollections
import app.silly.core.prompt.engine.WorldInfoEngine
import app.silly.core.prompt.engine.WorldInfoSource
import app.silly.core.prompt.macro.CoreMacros
import app.silly.core.prompt.macro.MacroChatMessage
import app.silly.core.prompt.macro.MacroEngine
import app.silly.core.prompt.macro.MacroEnv
import app.silly.core.prompt.macro.MacroRegistry
import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 内置的演示角色与世界书。
 *
 * 真实的角色卡加载（PNG 的 `tEXt:chara`、CharX 等）在 `core-data` 里已经实现并测过，
 * 但「从哪拿到这些文件」是存储层的事（下一步做 SAF 选目录）。
 * 在那之前，这一份内存里的内容能让整条链路先跑起来。
 */
object DemoContent {

    const val CHARACTER_NAME = "Seraphina"
    const val USER_NAME = "Traveler"

    private const val DESCRIPTION =
        "A scholar who keeps a lantern lit for travellers. She remembers every book shelved " +
            "in the rain-soaked library at the edge of the map, though she never says how."

    private const val PERSONALITY = "Patient. Curious. Fond of long silences."

    private const val SCENARIO = "A rain-soaked library at the edge of the map."

    private const val GREETING = "The door was already open when you arrived."

    private const val POST_HISTORY = "Keep the prose quiet. Do not explain the lantern."

    fun character(): CharacterCard = CharacterCard(
        buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put(
                "data",
                buildJsonObject {
                    put("name", CHARACTER_NAME)
                    put("description", DESCRIPTION)
                    put("personality", PERSONALITY)
                    put("scenario", SCENARIO)
                    put("first_mes", GREETING)
                    put("mes_example", "")
                    put("creator_notes", "")
                    put("system_prompt", "")
                    put("post_history_instructions", POST_HISTORY)
                    put("alternate_greetings", JsonArray(emptyList()))
                    put("tags", JsonArray(emptyList()))
                    put("creator", "sillyapp")
                    put("character_version", "1.0")
                    put("extensions", JsonObject(emptyMap()))
                },
            )
        },
    )

    fun worldBook(): WorldBook = WorldBook.parse(
        """
        {
          "entries": {
            "0": {
              "uid": 0, "key": ["lantern"], "keysecondary": [], "comment": "Lantern",
              "content": "The lantern never gutters, no matter the wind.",
              "constant": false, "selective": true, "selectiveLogic": 0, "order": 100,
              "position": 0, "disable": false, "delayUntilRecursion": 0,
              "probability": 100, "useProbability": true, "depth": 4,
              "matchPersonaDescription": false, "matchCharacterDescription": false,
              "matchCharacterPersonality": false, "matchCharacterDepthPrompt": false,
              "matchScenario": false, "matchCreatorNotes": false
            },
            "1": {
              "uid": 1, "key": ["rain"], "keysecondary": [], "comment": "Rain",
              "content": "The rain has not stopped for three days. Nobody here finds that strange.",
              "constant": false, "selective": true, "selectiveLogic": 0, "order": 90,
              "position": 1, "disable": false, "delayUntilRecursion": 0,
              "probability": 100, "useProbability": true, "depth": 4,
              "matchPersonaDescription": false, "matchCharacterDescription": false,
              "matchCharacterPersonality": false, "matchCharacterDepthPrompt": false,
              "matchScenario": false, "matchCreatorNotes": false
            }
          }
        }
        """.trimIndent(),
        "demo-book",
    )

    /** 组装要发给模型的消息（用内置演示内容）。 */
    fun buildMessages(history: List<PromptMessage>): List<PromptMessage> =
        PromptPipeline.buildMessages(character(), worldBook(), history)
}

/**
 * 把「角色卡 + 世界书 + 历史」组装成发给模型的消息。
 *
 * 与 [DemoContent] 分开，是因为它接的是**真实数据** ——
 * App 从 ST 数据目录里加载卡片与世界书后，走的就是这条路径。
 */
object PromptPipeline {

    fun buildMessages(
        card: CharacterCard,
        book: WorldBook?,
        history: List<PromptMessage>,
    ): List<PromptMessage> {
        val characterName = card.name.ifBlank { "Assistant" }
        val userName = "Traveler"

        // ① 世界书激活（扫描文本用最新的在前）
        val scanText = history.takeLast(SCAN_DEPTH).map { it.content }.reversed()
        val activation = WorldInfoEngine(tokenCounter = { it.length / 4 }).activate(
            chat = scanText,
            maxContext = 4096,
            lore = if (book == null) {
                LoreCollections()
            } else {
                LoreCollections(global = listOf(WorldInfoSource(book.name.ifBlank { "book" }, book.entries)))
            },
            globalScanData = GlobalScanData(
                characterDescription = card.description,
                characterPersonality = card.personality,
                scenario = card.scenario,
            ),
        )

        // ② 宏与故事串
        val macroEnv = MacroEnv(
            names = mapOf("char" to characterName, "user" to userName),
            character = mapOf(
                "description" to card.description,
                "personality" to card.personality,
                "scenario" to card.scenario,
                "persona" to "A traveller with a wet coat.",
            ),
            chat = history.map { MacroChatMessage(it.content, it.role == PromptRole.USER) },
        )
        val registry = MacroRegistry()
        CoreMacros.install(registry)
        val macros = MacroEngine(registry, macroEnv)

        val storyString = StoryString.render(
            "{{#if description}}{{description}}\n{{/if}}" +
                "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
                "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}" +
                "{{#if persona}}{{persona}}\n{{/if}}",
            StoryString.Params(
                description = macros.evaluate(card.description),
                personality = macros.evaluate(card.personality),
                scenario = macros.evaluate(card.scenario),
                persona = "A traveller with a wet coat.",
                char = characterName,
                user = userName,
            ),
            substitute = { macros.evaluate(it) },
        )

        // ③ 组装
        return PromptAssembler().assemble(
            sources = mapOf(
                "main" to storyString.text,
                "worldInfoBefore" to activation.result.worldInfoBefore,
                "worldInfoAfter" to activation.result.worldInfoAfter,
                "jailbreak" to macros.evaluate(card.postHistoryInstructions),
            ),
            history = history,
        )
    }

    private const val SCAN_DEPTH = 8
}
