package app.silly.core.prompt.engine

import app.silly.core.data.world.SelectiveLogic
import app.silly.core.data.world.WorldBook
import app.silly.core.data.world.WorldInfoEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 世界书引擎的验收测试。
 *
 * 期望值全部来自 `docs/worldinfo-engine-spec.md`。
 * 每个「反直觉」条目都对应至少一个用例 —— 这些是「顺手写得更合理」会出错的地方。
 *
 * 随机源与 token 计数都是注入的，所以概率与预算相关的用例是**确定性的**。
 */
class WorldInfoEngineTest {

    // ------------------------------------------------------------ 测试替身

    private fun entry(
        uid: Int = 0,
        keys: List<String> = emptyList(),
        secondary: List<String> = emptyList(),
        content: String = "CONTENT-$uid",
        order: Int = 100,
        constant: Boolean = false,
        selective: Boolean = true,
        selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        position: Int = 0,
        depth: Int = 4,
        role: Int = 0,
        disable: Boolean = false,
        excludeRecursion: Boolean = false,
        preventRecursion: Boolean = false,
        delayUntilRecursion: Int = 0,
        probability: Int = 100,
        useProbability: Boolean = true,
        ignoreBudget: Boolean = false,
        group: String = "",
        groupOverride: Boolean = false,
        groupWeight: Int = 100,
        useGroupScoring: Boolean? = null,
        sticky: Int? = null,
        cooldown: Int? = null,
        delay: Int? = null,
        triggers: List<String> = emptyList(),
        outletName: String = "",
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        scanDepth: Int? = null,
        characterFilter: kotlinx.serialization.json.JsonObject? = null,
    ): WorldInfoEntry {
        val map = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "uid" to kotlinx.serialization.json.JsonPrimitive(uid),
            "key" to kotlinx.serialization.json.JsonArray(keys.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            "keysecondary" to kotlinx.serialization.json.JsonArray(secondary.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            "comment" to kotlinx.serialization.json.JsonPrimitive(""),
            "content" to kotlinx.serialization.json.JsonPrimitive(content),
            "constant" to kotlinx.serialization.json.JsonPrimitive(constant),
            "vectorized" to kotlinx.serialization.json.JsonPrimitive(false),
            "selective" to kotlinx.serialization.json.JsonPrimitive(selective),
            "selectiveLogic" to kotlinx.serialization.json.JsonPrimitive(selectiveLogic.value),
            "addMemo" to kotlinx.serialization.json.JsonPrimitive(false),
            "order" to kotlinx.serialization.json.JsonPrimitive(order),
            "position" to kotlinx.serialization.json.JsonPrimitive(position),
            "disable" to kotlinx.serialization.json.JsonPrimitive(disable),
            "ignoreBudget" to kotlinx.serialization.json.JsonPrimitive(ignoreBudget),
            "excludeRecursion" to kotlinx.serialization.json.JsonPrimitive(excludeRecursion),
            "preventRecursion" to kotlinx.serialization.json.JsonPrimitive(preventRecursion),
            "matchPersonaDescription" to kotlinx.serialization.json.JsonPrimitive(false),
            "matchCharacterDescription" to kotlinx.serialization.json.JsonPrimitive(false),
            "matchCharacterPersonality" to kotlinx.serialization.json.JsonPrimitive(false),
            "matchCharacterDepthPrompt" to kotlinx.serialization.json.JsonPrimitive(false),
            "matchScenario" to kotlinx.serialization.json.JsonPrimitive(false),
            "matchCreatorNotes" to kotlinx.serialization.json.JsonPrimitive(false),
            "delayUntilRecursion" to kotlinx.serialization.json.JsonPrimitive(delayUntilRecursion),
            "probability" to kotlinx.serialization.json.JsonPrimitive(probability),
            "useProbability" to kotlinx.serialization.json.JsonPrimitive(useProbability),
            "depth" to kotlinx.serialization.json.JsonPrimitive(depth),
            "outletName" to kotlinx.serialization.json.JsonPrimitive(outletName),
            "group" to kotlinx.serialization.json.JsonPrimitive(group),
            "groupOverride" to kotlinx.serialization.json.JsonPrimitive(groupOverride),
            "groupWeight" to kotlinx.serialization.json.JsonPrimitive(groupWeight),
            "automationId" to kotlinx.serialization.json.JsonPrimitive(""),
            "role" to kotlinx.serialization.json.JsonPrimitive(role),
            "triggers" to kotlinx.serialization.json.JsonArray(triggers.map { kotlinx.serialization.json.JsonPrimitive(it) }),
        )
        if (useGroupScoring != null) map["useGroupScoring"] = kotlinx.serialization.json.JsonPrimitive(useGroupScoring)
        if (sticky != null) map["sticky"] = kotlinx.serialization.json.JsonPrimitive(sticky)
        if (cooldown != null) map["cooldown"] = kotlinx.serialization.json.JsonPrimitive(cooldown)
        if (delay != null) map["delay"] = kotlinx.serialization.json.JsonPrimitive(delay)
        if (caseSensitive != null) map["caseSensitive"] = kotlinx.serialization.json.JsonPrimitive(caseSensitive)
        if (matchWholeWords != null) map["matchWholeWords"] = kotlinx.serialization.json.JsonPrimitive(matchWholeWords)
        if (scanDepth != null) map["scanDepth"] = kotlinx.serialization.json.JsonPrimitive(scanDepth)
        if (characterFilter != null) map["characterFilter"] = characterFilter

        return WorldInfoEntry(kotlinx.serialization.json.JsonObject(map), uid.toString())
    }

    private fun lore(vararg entries: WorldInfoEntry) =
        LoreCollections(global = listOf(WorldInfoSource("book", entries.toList())))

    private fun engine(
        settings: WorldInfoSettings = WorldInfoSettings.DEFAULT,
        random: () -> Double = { 0.5 },
        tokenCounter: (String) -> Int = { 1 },
        characterFileName: String? = null,
        characterTags: List<String>? = null,
        external: Map<String, ScanEntry> = emptyMap(),
    ) = WorldInfoEngine(
        settings = settings,
        tokenCounter = tokenCounter,
        random = random,
        characterFileName = characterFileName,
        characterTags = characterTags,
        externalActivations = external,
    )

    private fun run(
        entries: List<WorldInfoEntry>,
        chat: List<String> = listOf("hello world"),
        settings: WorldInfoSettings = WorldInfoSettings.DEFAULT,
        random: () -> Double = { 0.5 },
        tokenCounter: (String) -> Int = { 1 },
        globalScanData: GlobalScanData = GlobalScanData.EMPTY,
        timed: TimedWorldInfo = TimedWorldInfo.EMPTY,
        characterFileName: String? = null,
        characterTags: List<String>? = null,
    ) = engine(settings, random, tokenCounter, characterFileName, characterTags)
        .activate(
            chat = chat,
            maxContext = 1000,
            lore = LoreCollections(global = listOf(WorldInfoSource("book", entries))),
            globalScanData = globalScanData,
            timedWorldInfo = timed,
        )

    // ------------------------------------------------------------ 基本激活

    @Test
    fun `constant 条目无条件激活`() {
        val out = run(listOf(entry(uid = 0, constant = true)))
        assertEquals(1, out.result.allActivatedEntries.size)
        assertEquals("CONTENT-0", out.result.worldInfoBefore)
    }

    @Test
    fun `主键命中才激活`() {
        val e = entry(uid = 0, keys = listOf("dragon"))

        assertTrue(run(listOf(e), chat = listOf("a dragon appears")).result.allActivatedEntries.isNotEmpty())
        assertTrue(run(listOf(e), chat = listOf("nothing here")).result.allActivatedEntries.isEmpty())
    }

    @Test
    fun `disable 为真的条目即使 constant 也不激活`() {
        val out = run(listOf(entry(uid = 0, constant = true, disable = true)))
        assertTrue(out.result.allActivatedEntries.isEmpty())
    }

    @Test
    fun `disable 写成数字 1 也被视为停用`() {
        // ST 用宽松相等：`entry.disable == true`，而 1 == true
        val raw = entry(uid = 0, constant = true).raw.toMutableMap()
        raw["disable"] = kotlinx.serialization.json.JsonPrimitive(1)
        val e = WorldInfoEntry(kotlinx.serialization.json.JsonObject(raw), "0")

        assertTrue(run(listOf(e)).result.allActivatedEntries.isEmpty(), "disable: 1 必须停用")
    }

    @Test
    fun `没有键且非 constant 的条目不激活`() {
        assertTrue(run(listOf(entry(uid = 0, keys = emptyList()))).result.allActivatedEntries.isEmpty())
    }

    @Test
    fun `条目为空时返回空结果且不报错`() {
        val out = run(emptyList())
        assertTrue(out.result.allActivatedEntries.isEmpty())
        assertEquals(0, out.result.scanRounds)
    }

    // ------------------------------------------------------------ 次键

    @Test
    fun `selective 为假时次键被忽略`() {
        val e = entry(uid = 0, keys = listOf("sword"), secondary = listOf("silver"), selective = false)
        // 主键命中即可，不需要次键
        assertTrue(run(listOf(e), chat = listOf("a sword")).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `AND_ANY 需要至少一个次键命中`() {
        val e = entry(uid = 0, keys = listOf("sword"), secondary = listOf("silver"), selectiveLogic = SelectiveLogic.AND_ANY)

        assertTrue(run(listOf(e), chat = listOf("a silver sword")).result.allActivatedEntries.isNotEmpty())
        assertTrue(run(listOf(e), chat = listOf("a plain sword")).result.allActivatedEntries.isEmpty())
    }

    @Test
    fun `NOT_ALL 在任一次键不命中时通过`() {
        val e = entry(
            uid = 0, keys = listOf("sword"),
            secondary = listOf("silver", "cursed"),
            selectiveLogic = SelectiveLogic.NOT_ALL,
        )
        assertTrue(run(listOf(e), chat = listOf("a silver sword")).result.allActivatedEntries.isNotEmpty())
        assertTrue(
            run(listOf(e), chat = listOf("a silver cursed sword")).result.allActivatedEntries.isEmpty(),
            "两个次键都命中 ⇒ NOT_ALL 不通过",
        )
    }

    @Test
    fun `NOT_ANY 在无次键命中时通过`() {
        val e = entry(uid = 0, keys = listOf("sword"), secondary = listOf("silver"), selectiveLogic = SelectiveLogic.NOT_ANY)

        assertTrue(run(listOf(e), chat = listOf("a plain sword")).result.allActivatedEntries.isNotEmpty())
        assertTrue(run(listOf(e), chat = listOf("a silver sword")).result.allActivatedEntries.isEmpty())
    }

    @Test
    fun `AND_ALL 要求全部次键命中`() {
        val e = entry(
            uid = 0, keys = listOf("sword"),
            secondary = listOf("silver", "cursed"),
            selectiveLogic = SelectiveLogic.AND_ALL,
        )
        assertTrue(run(listOf(e), chat = listOf("a silver cursed sword")).result.allActivatedEntries.isNotEmpty())
        assertTrue(run(listOf(e), chat = listOf("a silver sword")).result.allActivatedEntries.isEmpty())
    }

    // ------------------------------------------------------------ 概率

    @Test
    fun `probability 为 100 时短路通过`() {
        assertTrue(run(listOf(entry(uid = 0, constant = true, probability = 100)), random = { 0.999 }).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `probability 为 0 时只在随机数为 0 时通过`() {
        val e = entry(uid = 0, constant = true, probability = 0)
        assertTrue(run(listOf(e), random = { 0.5 }).result.allActivatedEntries.isEmpty())
        assertTrue(run(listOf(e), random = { 0.0 }).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `概率失败不再重掷`() {
        // 递归开着，如果会重掷，第二轮的随机数（0.0）就能让它通过
        val settings = WorldInfoSettings(recursive = true)
        val rolls = ArrayDeque(listOf(0.9, 0.0, 0.0))
        val e = entry(uid = 0, constant = true, probability = 50, sticky = 1)

        val out = run(listOf(e), settings = settings, random = { rolls.removeFirstOrNull() ?: 0.0 })
        assertTrue(out.result.allActivatedEntries.isEmpty(), "一次生成内不应重掷")
    }

    @Test
    fun `useProbability 为假时无视 probability`() {
        val e = entry(uid = 0, constant = true, probability = 0, useProbability = false)
        assertTrue(run(listOf(e), random = { 0.99 }).result.allActivatedEntries.isNotEmpty())
    }

    // ------------------------------------------------------------ 位置分流与排序

    @Test
    fun `不同 position 进入不同容器`() {
        val entries = listOf(
            entry(uid = 0, content = "BEFORE", position = 0, constant = true),
            entry(uid = 1, content = "AFTER", position = 1, constant = true),
            entry(uid = 2, content = "ANTOP", position = 2, constant = true),
            entry(uid = 3, content = "ANBOTTOM", position = 3, constant = true),
            entry(uid = 4, content = "EMTOP", position = 5, constant = true),
            entry(uid = 5, content = "EMBOTTOM", position = 6, constant = true),
            entry(uid = 6, content = "DEPTH", position = 4, constant = true, depth = 3),
        )
        val r = run(entries).result

        assertEquals("BEFORE", r.worldInfoBefore)
        assertEquals("AFTER", r.worldInfoAfter)
        assertEquals(listOf("ANTOP"), r.anBeforeEntries)
        assertEquals(listOf("ANBOTTOM"), r.anAfterEntries)
        // 降序遍历(uid4=EMTop 在前) + unshift ⇒ 后处理的 EMBottom 排到了前面
        assertEquals(2, r.emEntries.size)
        assertEquals(WiAnchorPosition.AFTER, r.emEntries[0].position)
        assertEquals("EMBOTTOM", r.emEntries[0].content)
        assertEquals(WiAnchorPosition.BEFORE, r.emEntries[1].position)
        assertEquals("EMTOP", r.emEntries[1].content)
        assertEquals(1, r.depthEntries.size)
        assertEquals(3, r.depthEntries[0].depth)
        assertEquals(listOf("DEPTH"), r.depthEntries[0].entries)
    }

    @Test
    fun `before 容器按 order 升序`() {
        val entries = listOf(
            entry(uid = 0, content = "O300", order = 300, constant = true),
            entry(uid = 1, content = "O100", order = 100, constant = true),
            entry(uid = 2, content = "O200", order = 200, constant = true),
        )
        // 先按 order 降序排（300,200,100），再逐个 unshift ⇒ 最终升序
        assertEquals("O100\nO200\nO300", run(entries).result.worldInfoBefore)
    }

    @Test
    fun `outlet 容器是降序，与其它容器相反`() {
        val entries = listOf(
            entry(uid = 0, content = "O300", order = 300, position = 7, outletName = "box", constant = true),
            entry(uid = 1, content = "O100", order = 100, position = 7, outletName = "box", constant = true),
            entry(uid = 2, content = "O200", order = 200, position = 7, outletName = "box", constant = true),
        )
        assertEquals(listOf("O300", "O200", "O100"), run(entries).result.outletEntries["box"])
    }

    @Test
    fun `outlet 没有名字的条目被丢弃`() {
        val e = entry(uid = 0, content = "X", position = 7, outletName = "", constant = true)
        val r = run(listOf(e)).result
        assertTrue(r.outletEntries.isEmpty())
        assertEquals(1, r.allActivatedEntries.size, "仍算已激活，只是不进提示词")
    }

    @Test
    fun `未知 position 被静默丢弃但仍算激活`() {
        val e = entry(uid = 0, content = "X", position = 99, constant = true)
        val r = run(listOf(e)).result

        assertEquals(1, r.allActivatedEntries.size)
        assertEquals("", r.worldInfoBefore)
        assertEquals("", r.worldInfoAfter)
    }

    @Test
    fun `atDepth 按 depth 与 role 分组`() {
        val entries = listOf(
            entry(uid = 0, content = "D2-SYS", position = 4, depth = 2, role = 0, constant = true),
            entry(uid = 1, content = "D2-USER", position = 4, depth = 2, role = 1, constant = true),
            entry(uid = 2, content = "D5-SYS", position = 4, depth = 5, role = 0, constant = true),
        )
        val groups = run(entries).result.depthEntries

        assertEquals(3, groups.size, "深度或角色不同就另起一组")
        assertEquals(listOf(2, 2, 5), groups.map { it.depth })
        assertEquals(
            listOf(app.silly.core.prompt.model.PromptRole.SYSTEM, app.silly.core.prompt.model.PromptRole.USER, app.silly.core.prompt.model.PromptRole.SYSTEM),
            groups.map { it.role },
        )
    }

    @Test
    fun `atDepth 组内文本按 order 升序`() {
        val entries = listOf(
            entry(uid = 0, content = "O300", order = 300, position = 4, depth = 2, constant = true),
            entry(uid = 1, content = "O100", order = 100, position = 4, depth = 2, constant = true),
        )
        assertEquals(listOf("O100", "O300"), run(entries).result.depthEntries[0].entries)
    }

    @Test
    fun `worldInfoString 是无分隔符拼接`() {
        val entries = listOf(
            entry(uid = 0, content = "AAA", position = 0, constant = true),
            entry(uid = 1, content = "BBB", position = 1, constant = true),
        )
        assertEquals("AAABBB", run(entries).result.worldInfoString)
    }

    // ------------------------------------------------------------ 预算

    @Test
    fun `预算按百分比计算并有下限 1`() {
        // maxContext 1000、25% ⇒ 预算 250 token；每条内容 100 字符 + 换行 = 101
        val entries = (0 until 5).map {
            entry(uid = it, content = "X".repeat(100), order = 100 - it, constant = true)
        }
        val out = engine(tokenCounter = { it.length }).activate(
            chat = listOf("hi"), maxContext = 1000,
            lore = LoreCollections(global = listOf(WorldInfoSource("b", entries))),
        )
        // 101、202 都 < 250；第三条 303 >= 250 ⇒ 触顶，只进两条
        assertEquals(2, out.result.allActivatedEntries.size)
        assertTrue(out.result.budgetOverflowed)
    }

    @Test
    fun `预算用大于等于判定`() {
        val entries = listOf(entry(uid = 0, content = "A", order = 10, constant = true))
        // maxContext 4、budget 25% ⇒ 1；"A\n" 是 2 token，2 >= 1 ⇒ 触顶
        val out = engine(tokenCounter = { it.length }).activate(
            chat = listOf("hi"), maxContext = 4,
            lore = LoreCollections(global = listOf(WorldInfoSource("b", entries))),
        )
        assertTrue(out.result.allActivatedEntries.isEmpty(), "恰好等于预算也应拒绝")
        assertTrue(out.result.budgetOverflowed)
    }

    @Test
    fun `ignoreBudget 条目在预算溢出后仍被加入`() {
        val entries = listOf(
            entry(uid = 0, content = "A", order = 100, constant = true),
            entry(uid = 1, content = "B", order = 50, constant = true, ignoreBudget = true),
        )
        val out = engine(tokenCounter = { it.length }).activate(
            chat = listOf("hi"), maxContext = 40, // 预算 10
            lore = LoreCollections(global = listOf(WorldInfoSource("b", entries))),
        )
        val contents = out.result.allActivatedEntries.map { it.content }
        assertTrue(contents.contains("B"), "ignoreBudget 条目必须放行，实际：$contents")
    }

    @Test
    fun `budgetCap 生效`() {
        val entries = (0 until 20).map {
            entry(uid = it, content = "X".repeat(10), order = 100 - it, constant = true)
        }
        val out = engine(
            settings = WorldInfoSettings(budgetPercent = 100, budgetCap = 30),
            tokenCounter = { it.length },
        ).activate(
            chat = listOf("hi"), maxContext = 1000,
            lore = LoreCollections(global = listOf(WorldInfoSource("b", entries))),
        )
        // 11、22 都 < 30；第三条 33 >= 30 ⇒ 封顶，只进两条
        assertEquals(2, out.result.allActivatedEntries.size, "封顶 30 token，每条 11")
    }

    // ------------------------------------------------------------ 分组

    @Test
    fun `groupOverride 条目直接胜出`() {
        val entries = listOf(
            entry(uid = 0, content = "LOW", order = 10, constant = true, group = "g", groupWeight = 100),
            entry(uid = 1, content = "WIN", order = 50, constant = true, group = "g", groupOverride = true),
        )
        val contents = run(entries).result.allActivatedEntries.map { it.content }
        assertEquals(listOf("WIN"), contents)
    }

    @Test
    fun `加权随机按累计权重选一个`() {
        val entries = listOf(
            entry(uid = 0, content = "A", constant = true, group = "g", groupWeight = 50),
            entry(uid = 1, content = "B", constant = true, group = "g", groupWeight = 50),
        )
        // roll = 0.1 * 100 = 10 <= 50 ⇒ 选 A
        assertEquals(listOf("A"), run(entries, random = { 0.1 }).result.allActivatedEntries.map { it.content })
        // roll = 0.9 * 100 = 90 > 50, <= 100 ⇒ 选 B
        assertEquals(listOf("B"), run(entries, random = { 0.9 }).result.allActivatedEntries.map { it.content })
    }

    @Test
    fun `已激活过的组会把后续轮次的候选全部锁死`() {
        val a = entry(uid = 0, content = "A", order = 200, constant = true, group = "g")
        val b = entry(uid = 1, content = "B", order = 100, constant = true, group = "g")

        // 第一轮 A 胜出并记为已激活；第二轮因递归开启会重扫，
        // 此时该组已被锁死，B 不应被补上
        val out = run(listOf(a, b), settings = WorldInfoSettings(recursive = true, maxRecursionSteps = 5))

        assertEquals(listOf("A"), out.result.allActivatedEntries.map { it.content })
        assertTrue(out.result.scanRounds >= 2, "应当进入过第二轮，否则锁死逻辑没被测到")
    }

    @Test
    fun `group 字段含多个组名时按逗号切分`() {
        val entries = listOf(
            entry(uid = 0, content = "A", constant = true, group = "g1, g2"),
            entry(uid = 1, content = "B", constant = true, group = "g2"),
        )
        val out = run(entries).result
        assertEquals(1, out.allActivatedEntries.size, "两条共享 g2，应只留一条")
    }

    // ------------------------------------------------------------ 计时效果

    @Test
    fun `sticky 激活后持续若干条消息`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "STICKY", sticky = 3)

        // 第 1 次：聊天 6 条（最新在前，trigger 是最新那条），激活并写下 sticky 效果
        val first = run(listOf(e), chat = listOf("trigger") + List(5) { "m$it" })
        assertEquals(1, first.result.allActivatedEntries.size)
        val effect = assertNotNull(first.timedWorldInfo.sticky["book.0"])
        assertEquals(6, effect.start, "start = 当前聊天条数")
        assertEquals(9, effect.end, "end = start + sticky")
        assertFalse(effect.isProtected)

        // 第 2 次：聊天更长且不再含 trigger，但 sticky 仍应生效
        val second = run(listOf(e), chat = List(7) { "m$it" }, timed = first.timedWorldInfo)
        assertEquals(1, second.result.allActivatedEntries.size, "sticky 期间无需键命中")
    }

    @Test
    fun `sticky 到期后转入冷却`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "X", sticky = 2, cooldown = 5)

        val first = run(listOf(e), chat = listOf("trigger"))
        assertEquals(1, first.result.allActivatedEntries.size)
        assertEquals(1, first.timedWorldInfo.sticky["book.0"]!!.start)
        assertEquals(3, first.timedWorldInfo.sticky["book.0"]!!.end)

        // chat 长度 4 已越过 sticky 的 end=3 ⇒ 到期并转入冷却
        val second = run(listOf(e), chat = listOf("a", "b", "c", "trigger"), timed = first.timedWorldInfo)

        val cd = second.timedWorldInfo.cooldown["book.0"]
        assertNotNull(cd, "sticky 到期应写入 cooldown")
        assertTrue(cd.isProtected, "sticky 结束时新建的 cooldown 必须是 protected")
        assertTrue(second.result.allActivatedEntries.isEmpty(), "刚进冷却，本次不应激活")
    }

    @Test
    fun `冷却期间被抑制`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "X", cooldown = 5)
        val chatLen = 3
        val state = TimedWorldInfo(
            cooldown = linkedMapOf("book.0" to TimedEffect(0L, 0, chatLen + 5, false)),
        )
        val out = engine().activate(
            chat = listOf("a", "b", "trigger"),
            maxContext = 1000,
            lore = LoreCollections(global = listOf(WorldInfoSource("book", listOf(e)))),
            timedWorldInfo = state,
        )
        // 哈希对不上会退回 world.uid 查找，所以冷却生效
        assertTrue(out.result.allActivatedEntries.isEmpty(), "冷却中不应激活")
    }

    @Test
    fun `delay 在聊天不足时屏蔽条目`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "X", delay = 10)
        // 聊天条数 1 < delay ⇒ 屏蔽
        assertTrue(run(listOf(e), chat = listOf("trigger")).result.allActivatedEntries.isEmpty())
        // 聊天条数 11 >= delay，且 trigger 在最新那条 ⇒ 激活
        assertTrue(run(listOf(e), chat = listOf("trigger") + List(10) { "m" }).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `delay 为 0 视为未配置`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "X", delay = 0)
        assertTrue(run(listOf(e), chat = listOf("trigger")).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `已存在的计时效果不会被刷新`() {
        val e = entry(uid = 0, keys = listOf("trigger"), content = "X", sticky = 3)
        val first = run(listOf(e), chat = listOf("trigger"))
        val original = first.timedWorldInfo.sticky["book.0"]!!

        // 再次激活，start/end 不应变化
        val second = run(listOf(e), chat = listOf("trigger"), timed = first.timedWorldInfo)
        assertEquals(original, second.timedWorldInfo.sticky["book.0"])
    }

    // ------------------------------------------------------------ 递归

    @Test
    fun `递归关闭时只扫一轮`() {
        val e = entry(uid = 0, content = "X", constant = true)
        assertEquals(1, run(listOf(e), settings = WorldInfoSettings(recursive = false)).result.scanRounds)
    }

    @Test
    fun `递归开启且本轮有新条目时会再扫一轮`() {
        val e = entry(uid = 0, content = "X", constant = true)
        val out = run(listOf(e), settings = WorldInfoSettings(recursive = true, maxRecursionSteps = 5))
        assertTrue(out.result.scanRounds >= 2, "递归开启时应进入第二轮，实际 ${out.result.scanRounds}")
    }

    @Test
    fun `maxRecursionSteps 限制总轮数`() {
        val e = entry(uid = 0, content = "X", constant = true)
        val out = run(listOf(e), settings = WorldInfoSettings(recursive = true, maxRecursionSteps = 2))
        assertEquals(2, out.result.scanRounds)
    }

    @Test
    fun `preventRecursion 的条目不会触发递归`() {
        val e = entry(uid = 0, content = "X", constant = true, preventRecursion = true)
        assertEquals(1, run(listOf(e), settings = WorldInfoSettings(recursive = true)).result.scanRounds)
    }

    @Test
    fun `递归内容能激活只匹配递归文本的条目`() {
        val seed = entry(uid = 0, keys = listOf("seed"), content = "MAGICWORD appears")
        val derived = entry(uid = 1, keys = listOf("magicword"), content = "DERIVED")

        // 聊天里没有 magicword，只有 seed；MAGICWORD 来自 seed 的内容
        val out = run(
            listOf(seed, derived),
            chat = listOf("a seed here"),
            settings = WorldInfoSettings(recursive = true, maxRecursionSteps = 3),
        )
        val contents = out.result.allActivatedEntries.map { it.content }
        assertTrue(contents.contains("DERIVED"), "递归应激活派生条目，实际：$contents")
    }

    @Test
    fun `excludeRecursion 的条目不参与递归轮`() {
        val seed = entry(uid = 0, keys = listOf("seed"), content = "MAGICWORD")
        val excluded = entry(uid = 1, keys = listOf("magicword"), content = "EXCLUDED", excludeRecursion = true)

        val out = run(
            listOf(seed, excluded),
            chat = listOf("a seed here"),
            settings = WorldInfoSettings(recursive = true, maxRecursionSteps = 3),
        )
        assertFalse(
            out.result.allActivatedEntries.map { it.content }.contains("EXCLUDED"),
            "excludeRecursion 条目不应在递归轮被激活",
        )
    }

    @Test
    fun `delayUntilRecursion 靠层级递进在没有常规递归时也能激活`() {
        // 两个不同层级，才会产生「还有层级没走完」的第二次机会。
        // 注意：只配一个层级时该层级会被立刻取为 currentLevel，后续没有推进机会，
        // 条目在 recursive=false 下就永远不会激活 —— 这是 ST 的真实行为。
        val level2 = entry(uid = 0, content = "L2", constant = true, delayUntilRecursion = 2)
        val level3 = entry(uid = 1, content = "L3", constant = true, delayUntilRecursion = 3)

        val out = run(
            listOf(level2, level3),
            settings = WorldInfoSettings(recursive = false, maxRecursionSteps = 5),
            chat = List(3) { "m" },
        )

        assertEquals(2, out.result.scanRounds, "层级递进应触发第二轮，且不看 recursive")
        val contents = out.result.allActivatedEntries.map { it.content }
        assertTrue(contents.contains("L2"), "层级 2 的条目在第二轮（currentLevel=3）应被放行")
    }

    @Test
    fun `只有一个 delayUntilRecursion 层级且递归关闭时不激活`() {
        val only = entry(uid = 0, content = "ONLY", constant = true, delayUntilRecursion = 2)
        val out = run(listOf(only), settings = WorldInfoSettings(recursive = false), chat = List(3) { "m" })

        assertEquals(1, out.result.scanRounds)
        assertTrue(
            out.result.allActivatedEntries.isEmpty(),
            "唯一层级被立刻取为 currentLevel，没有后续轮次 ⇒ 不激活",
        )
    }

    // ------------------------------------------------------------ 触发类型与角色过滤

    @Test
    fun `triggers 不匹配时跳过`() {
        val e = entry(uid = 0, content = "X", constant = true, triggers = listOf("continue"))
        assertTrue(run(listOf(e), globalScanData = GlobalScanData(trigger = "normal")).result.allActivatedEntries.isEmpty())
        assertTrue(run(listOf(e), globalScanData = GlobalScanData(trigger = "continue")).result.allActivatedEntries.isNotEmpty())
    }

    @Test
    fun `characterFilter 白名单与黑名单`() {
        val whitelist = kotlinx.serialization.json.JsonObject(
            mapOf(
                "names" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("a.png"))),
                "tags" to kotlinx.serialization.json.JsonArray(emptyList()),
                "isExclude" to kotlinx.serialization.json.JsonPrimitive(false),
            ),
        )
        val e = entry(uid = 0, content = "X", constant = true, characterFilter = whitelist)

        assertTrue(run(listOf(e), characterFileName = "a.png").result.allActivatedEntries.isNotEmpty())
        assertTrue(run(listOf(e), characterFileName = "b.png").result.allActivatedEntries.isEmpty())
        assertTrue(run(listOf(e), characterFileName = null).result.allActivatedEntries.isEmpty())
    }

    // ------------------------------------------------------------ 真实 fixture

    @Test
    fun `fixture 世界书端到端激活`() {
        val book = loadFixtureBook()
        val sources = listOf(WorldInfoSource("fixture-book", book.entries))

        // 命中 dragon（uid 0 是 constant，uid 1 需要 sword+silver）
        val chat = listOf("I raise my silver sword")

        val out = WorldInfoEngine(tokenCounter = { it.length / 4 }).activate(
            chat = chat,
            maxContext = 4096,
            lore = LoreCollections(global = sources),
        )

        val uids = out.result.allActivatedEntries.map { it.uid }.sorted()
        assertTrue(uids.contains(0), "uid 0 是 constant，必然激活")
        assertTrue(uids.contains(1), "uid 1 主键 sword + 次键 silver 都命中")
        assertFalse(uids.contains(3), "uid 3 是停用条目，绝不能激活")
    }

    @Test
    fun `fixture 里停用的 constant 条目不会被激活`() {
        val book = loadFixtureBook()
        val out = WorldInfoEngine().activate(
            chat = listOf("disabled-entry dragon sword whisper"),
            maxContext = 4096,
            lore = LoreCollections(global = listOf(WorldInfoSource("fixture-book", book.entries))),
        )
        assertFalse(
            out.result.allActivatedEntries.any { it.uid == 3 },
            "负向用例：停用条目即使 constant 也不能激活",
        )
    }

    private fun loadFixtureBook(): WorldBook {
        val bytes = javaClass.getResourceAsStream("/worlds/fixture-book.json")?.use { it.readBytes() }
            ?: fail("找不到 fixture 世界书")
        return WorldBook.parse(bytes, "fixture-book")
    }
}
