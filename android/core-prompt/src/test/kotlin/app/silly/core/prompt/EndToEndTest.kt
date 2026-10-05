package app.silly.core.prompt

import app.silly.core.data.card.CharacterCardCodec
import app.silly.core.data.world.WorldBook
import app.silly.core.prompt.assemble.PromptAssembler
import app.silly.core.prompt.assemble.StoryString
import app.silly.core.prompt.engine.GlobalScanData
import app.silly.core.prompt.engine.LoreCollections
import app.silly.core.prompt.engine.WorldInfoEngine
import app.silly.core.prompt.engine.WorldInfoSettings
import app.silly.core.prompt.engine.WorldInfoSource
import app.silly.core.prompt.macro.CoreMacros
import app.silly.core.prompt.macro.MacroChatMessage
import app.silly.core.prompt.macro.MacroEngine
import app.silly.core.prompt.macro.MacroEnv
import app.silly.core.prompt.macro.MacroRegistry
import app.silly.core.prompt.model.PromptMessage
import app.silly.core.prompt.model.PromptRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `core-prompt` 的端到端测试。
 *
 * 链路：**真实角色卡 + 真实世界书 → 宏渲染 → 故事串 → 组装 → 成品消息列表**。
 *
 * 输入全部取自 `fixtures/`（由 SillyTavern 自己生成），所以这一条链路
 * 走通了，就说明「从 ST 的数据出发，产出一份能直接发给模型的 messages」
 * 这件事是成立的 —— 后续 `core-provider` 只需把 `PromptMessage` 翻译成
 * 各家 API 的请求体。
 */
class EndToEndTest {

    private fun fixture(path: String): ByteArray =
        javaClass.getResourceAsStream("/$path")?.use { it.readBytes() }
            ?: fail("找不到 fixture: $path —— 请先运行 `node tools/gen-fixtures.mjs`")

    private fun card() = CharacterCardCodec.read(fixture("characters/seraphina-v3.png"))

    private fun worldBook() = WorldBook.parse(fixture("worlds/fixture-book.json"), "fixture-book")

    private fun macroEngine(env: MacroEnv): MacroEngine {
        val registry = MacroRegistry()
        CoreMacros.install(registry)
        return MacroEngine(registry, env)
    }

    @Test
    fun `从角色卡到成品消息列表`() {
        val character = card()
        assertTrue(character.name.isNotEmpty(), "fixture 角色卡应有名字")

        // ---------------------------------------------------------- ① 世界书激活
        val lore = LoreCollections(
            global = listOf(WorldInfoSource("fixture-book", worldBook().entries)),
            character = emptyList(),
        )
        // 聊天里出现 "silver sword"，应命中 uid 1（主键 sword + 次键 silver）
        val historyText = listOf(
            "I raise my silver sword.",
            "The rain hammers the roof.",
        )

        val activation = WorldInfoEngine(tokenCounter = { it.length / 4 }).activate(
            chat = historyText,
            maxContext = 4096,
            lore = lore,
            globalScanData = GlobalScanData(
                characterDescription = character.description,
                scenario = character.scenario,
            ),
        )

        val activatedUids = activation.result.allActivatedEntries.map { it.uid }.sorted()
        assertTrue(activatedUids.contains(0), "constant 条目必然激活")
        assertTrue(activatedUids.contains(1), "silver + sword 应命中")
        assertTrue(!activatedUids.contains(2), "uid 2 的正则键是 whisper，聊天里没有 ⇒ 不应命中")

        // ---------------------------------------------------------- ② 宏渲染
        val env = MacroEnv(
            names = mapOf("char" to character.name, "user" to "Traveler"),
            character = mapOf(
                "description" to character.description,
                "personality" to character.personality,
                "scenario" to character.scenario,
                "persona" to "A weary traveler.",
                "firstMessage" to character.firstMes,
            ),
            chat = historyText.map { MacroChatMessage(it, isUser = false) },
        )
        val macros = macroEngine(env)

        // ---------------------------------------------------------- ③ 故事串
        val storyString = StoryString.render(
            // ST 自带的默认 story_string
            "{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
                "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
                "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}",
            StoryString.Params(
                system = macros.evaluate("Stay in character as {{char}}."),
                description = macros.evaluate(character.description),
                personality = character.personality,
                scenario = character.scenario,
                persona = "A weary traveler.",
                char = character.name,
                user = "Traveler",
            ),
            substitute = { macros.evaluate(it) },
        )

        assertTrue(storyString.text.startsWith("Stay in character as ${character.name}."), "宏应被替换")
        assertTrue(storyString.text.contains("Scenario: "), "场景段应存在")

        // ---------------------------------------------------------- ④ 组装
        val history = listOf(
            PromptMessage(PromptRole.USER, "I raise my silver sword."),
            PromptMessage(PromptRole.ASSISTANT, "The blade hums."),
            PromptMessage(PromptRole.USER, "Who goes there?"),
            PromptMessage(PromptRole.ASSISTANT, "Only the rain."),
        )

        val messages = PromptAssembler().assemble(
            sources = mapOf(
                "main" to storyString.text,
                "worldInfoBefore" to activation.result.worldInfoBefore,
                "worldInfoAfter" to activation.result.worldInfoAfter,
                // 角色卡字段本身可能含 {{char}} / {{user}}（这个 fixture 的
                // description 里就有），所以必须先过宏引擎 —— ST 也是这么做的
                "charDescription" to macros.evaluate(character.description),
                "personality" to macros.evaluate(character.personality),
                "scenario" to macros.evaluate(character.scenario),
                "jailbreak" to macros.evaluate(character.postHistoryInstructions),
            ),
            history = history,
            depthInjections = activation.result.depthEntries
                .filter { it.depth != null }
                .map { app.silly.core.prompt.assemble.DepthInjection(it.depth!!, it.entries.joinToString("\n")) },
        )

        // ---------------------------------------------------------- 断言
        assertTrue(messages.isNotEmpty(), "应产出消息")

        // 第一条应是 main（故事串）
        assertEquals(PromptRole.SYSTEM, messages.first().role)
        assertTrue(messages.first().content.contains(character.name))

        // 聊天历史必须原样出现在结果里，且保持顺序
        val historyContents = messages.filter { it.source == PromptMessage.Source.CHAT_HISTORY }.map { it.content }
        assertEquals(history.map { it.content }, historyContents)

        // 角色描述必须出现（且宏已被替换）
        val descriptionMessage = messages.firstOrNull { it.content.contains("[Seraphina's Personality=") }
        assertTrue(descriptionMessage != null, "角色描述应出现在某条消息里")
        assertTrue(
            !descriptionMessage.content.contains("{{char}}"),
            "角色卡字段里的宏也必须被替换",
        )

        // 组装结果里不应残留未替换的宏
        val leaked = messages.filter { it.content.contains(Regex("""\{\{[a-zA-Z]""")) }
        assertTrue(leaked.isEmpty(), "不应残留未替换的宏：${leaked.map { it.content.take(60) }}")
    }

    @Test
    fun `世界书停用条目在完整链路里也不会混进来`() {
        val character = card()
        val activation = WorldInfoEngine().activate(
            chat = listOf("disabled-entry dragon sword silver whisper"),
            maxContext = 4096,
            lore = LoreCollections(global = listOf(WorldInfoSource("fixture-book", worldBook().entries))),
            globalScanData = GlobalScanData(characterDescription = character.description),
        )

        // uid 3 是 fixture 里的负向用例：disable = true 且 constant = true
        assertTrue(
            activation.result.allActivatedEntries.none { it.uid == 3 },
            "停用条目绝不能进入提示词",
        )
        assertTrue(
            !activation.result.worldInfoBefore.contains("This entry is disabled"),
            "停用条目的内容也不能出现",
        )
    }

    @Test
    fun `故事串里的 trim 会吃掉尾换行再由管线补回一个`() {
        val macros = macroEngine(MacroEnv())
        val rendered = StoryString.render(
            "A\n{{#if true}}B\n{{/if}}{{trim}}",
            StoryString.Params(),
            substitute = { macros.evaluate(it) },
        )
        assertEquals("A\nB\n", rendered.text)
    }

    @Test
    fun `世界书设置可调且影响预算`() {
        val strict = WorldInfoEngine(
            settings = WorldInfoSettings(budgetPercent = 1, depth = 1),
            tokenCounter = { it.length },
        ).activate(
            chat = listOf("dragon"),
            maxContext = 16, // 预算 = round(1 * 16 / 100) = 0 → 下限 1
            lore = LoreCollections(global = listOf(WorldInfoSource("fixture-book", worldBook().entries))),
        )

        assertTrue(strict.result.budgetOverflowed || strict.result.allActivatedEntries.size <= 1,
            "极小预算下不应塞进很多条目")
    }
}
