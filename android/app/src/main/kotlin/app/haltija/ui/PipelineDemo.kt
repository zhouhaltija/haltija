package app.haltija.ui

import app.haltija.core.data.card.CharacterCard
import app.haltija.core.prompt.assemble.PromptAssembler
import app.haltija.core.prompt.assemble.StoryString
import app.haltija.core.prompt.engine.GlobalScanData
import app.haltija.core.prompt.engine.LoreCollections
import app.haltija.core.prompt.engine.WorldInfoEngine
import app.haltija.core.prompt.engine.WorldInfoSettings
import app.haltija.core.prompt.engine.WorldInfoSource
import app.haltija.core.prompt.macro.CoreMacros
import app.haltija.core.prompt.macro.MacroChatMessage
import app.haltija.core.prompt.macro.MacroEngine
import app.haltija.core.prompt.macro.MacroEnv
import app.haltija.core.prompt.macro.MacroRegistry
import app.haltija.core.prompt.model.PromptMessage
import app.haltija.core.provider.AnthropicProvider
import app.haltija.core.provider.GenerationOptions
import app.haltija.core.provider.GeminiProvider
import app.haltija.core.provider.OpenAiCompatibleProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.haltija.core.data.world.WorldBook
import app.haltija.core.data.world.WorldInfoEntry

/**
 * 在设备上真跑一遍内核管线，把每一步的产物显示出来。
 *
 * 这不是「示例数据摆样子」—— 它调用的就是 App 实际会用的那些类，
 * 所以这一屏能跑通，就说明 `core-data` / `core-prompt` / `core-provider`
 * 三个模块在 Android 运行时上是可用的（没有用到 JVM-only 的东西）。
 */
object PipelineDemo {

    data class Step(val title: String, val detail: String)

    /** 一家的请求体。分开存 url 与 body，避免测试去「拆字符串」。 */
    data class ProviderRequestPreview(
        val provider: String,
        val url: String,
        val body: String,
    )

    data class Result(
        val steps: List<Step>,
        val messages: List<PromptMessage>,
        val requests: List<ProviderRequestPreview>,
        /** 世界书实际激活的条目 uid。 */
        val activatedUids: List<Int>,
    )

    private const val CHARACTER_NAME = "Seraphina"
    private const val USER_NAME = "Traveler"

    fun run(): Result {
        val steps = ArrayList<Step>()

        // ---------------------------------------------------------- ① 角色卡
        val cardJson = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put(
                "data",
                buildJsonObject {
                    put("name", CHARACTER_NAME)
                    put("description", "A scholar who keeps a lantern lit for travellers.")
                    put("personality", "Patient. Curious. Fond of long silences.")
                    put("scenario", "A rain-soaked library at the edge of the map.")
                    put("first_mes", "The door was already open when you arrived.")
                    put("mes_example", "<START>")
                    put("creator_notes", "")
                    put("system_prompt", "")
                    put("post_history_instructions", "")
                    put("alternate_greetings", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("tags", kotlinx.serialization.json.JsonArray(emptyList()))
                    put("creator", "haltija")
                    put("character_version", "1.0")
                    put("extensions", JsonObject(emptyMap()))
                },
            )
        }
        val card = CharacterCard(cardJson)
        steps += Step("① 角色卡", "解析出：${card.name}（${card.spec}）")

        // ---------------------------------------------------------- ② 世界书
        val worldBook = WorldBook.parse(
            """
            {
              "entries": {
                "0": {
                  "uid": 0, "key": ["lantern"], "keysecondary": [], "comment": "Lantern lore",
                  "content": "The lantern never gutters, no matter the wind.",
                  "constant": false, "selective": true, "selectiveLogic": 0, "order": 100,
                  "position": 0, "disable": false, "delayUntilRecursion": 0,
                  "probability": 100, "useProbability": true, "depth": 4,
                  "matchPersonaDescription": false, "matchCharacterDescription": false,
                  "matchCharacterPersonality": false, "matchCharacterDepthPrompt": false,
                  "matchScenario": false, "matchCreatorNotes": false
                },
                "1": {
                  "uid": 1, "key": ["never-matches"], "keysecondary": [], "comment": "Negative case",
                  "content": "This should never appear.",
                  "constant": false, "selective": true, "selectiveLogic": 0, "order": 100,
                  "position": 0, "disable": true, "delayUntilRecursion": 0,
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

        val history = listOf(
            "The rain has not stopped for three days.",
            "I set the lantern on the table.",
            "Its flame does not move.",
        )

        val activation = WorldInfoEngine(tokenCounter = { it.length / 4 }).activate(
            chat = history.reversed(),
            maxContext = 4096,
            lore = LoreCollections(global = listOf(WorldInfoSource(worldBook.name, worldBook.entries))),
            globalScanData = GlobalScanData(
                characterDescription = card.description,
                scenario = card.scenario,
            ),
        )
        val activated = activation.result.allActivatedEntries.map { it.uid }.sorted()
        steps += Step(
            "② 世界书激活",
            "命中条目 ${activated}（uid 1 是停用条目，负向用例）",
        )

        // ---------------------------------------------------------- ③ 宏与故事串
        val macroEnv = MacroEnv(
            names = mapOf("char" to CHARACTER_NAME, "user" to USER_NAME),
            character = mapOf(
                "description" to card.description,
                "personality" to card.personality,
                "scenario" to card.scenario,
                "persona" to "A traveller with a wet coat.",
            ),
            chat = history.map { MacroChatMessage(it, isUser = false) },
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
                char = CHARACTER_NAME,
                user = USER_NAME,
            ),
            substitute = { macros.evaluate(it) },
        )
        steps += Step("③ 故事串", "渲染出 ${storyString.text.length} 字符，已含宏替换结果")

        // ---------------------------------------------------------- ④ 组装
        val chatMessages = listOf(
            PromptMessage(app.haltija.core.prompt.model.PromptRole.USER, "The rain has not stopped for three days."),
            PromptMessage(app.haltija.core.prompt.model.PromptRole.ASSISTANT, "It never does, this far out."),
            PromptMessage(app.haltija.core.prompt.model.PromptRole.USER, "I set the lantern on the table."),
            PromptMessage(app.haltija.core.prompt.model.PromptRole.ASSISTANT, "Its flame does not move."),
        )

        val messages = PromptAssembler().assemble(
            sources = mapOf(
                "main" to storyString.text,
                "worldInfoBefore" to activation.result.worldInfoBefore,
                "charDescription" to macros.evaluate(card.description),
            ),
            history = chatMessages,
        )
        steps += Step("④ 组装", "${messages.size} 条消息，顺序按 ST 的默认 prompt_order")

        // ---------------------------------------------------------- ⑤ 请求体
        val options = GenerationOptions(model = "demo-model", maxTokens = 512, temperature = 0.8)
        val requests = listOf(
            "OpenAI 兼容" to OpenAiCompatibleProvider("https://api.example.com/v1", "sk-demo")
                .buildRequest(messages, options),
            "Anthropic" to AnthropicProvider("https://api.anthropic.com", "sk-ant-demo")
                .buildRequest(messages, options),
            "Gemini" to GeminiProvider("https://generativelanguage.googleapis.com/v1beta", "demo-key")
                .buildRequest(messages, options),
        ).map { (name, request) ->
            ProviderRequestPreview(name, request.url, request.body)
        }

        steps += Step("⑤ 请求体", "已按三家格式分别构造（未发送）")

        return Result(steps, messages, requests, activated)
    }

    /** 供调试与后续单测复用：把一张卡与一本世界书串起来。 */
    fun entriesOf(book: WorldBook): List<WorldInfoEntry> = book.entries
}
