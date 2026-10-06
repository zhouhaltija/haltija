package app.haltija.core.prompt.engine

import app.haltija.core.data.json.bool
import app.haltija.core.data.json.obj
import app.haltija.core.data.json.stringList
import app.haltija.core.data.world.SelectiveLogic
import app.haltija.core.data.world.WorldInfoEntry
import app.haltija.core.prompt.model.PromptRole

/** 一本参与扫描的世界书。 */
class WorldInfoSource(val name: String, val entries: List<WorldInfoEntry>)

/** 四类世界书来源。对应 `getGlobalLore` / `getCharacterLore` / `getChatLore` / `getPersonaLore`。 */
class LoreCollections(
    val global: List<WorldInfoSource> = emptyList(),
    val character: List<WorldInfoSource> = emptyList(),
    val chat: List<WorldInfoSource> = emptyList(),
    val persona: List<WorldInfoSource> = emptyList(),
)

/**
 * 世界书激活引擎，对应 `checkWorldInfo`（`world-info.js:4709-5283`）。
 *
 * 语义规范见 `docs/worldinfo-engine-spec.md`。实现里凡是「看起来可以写得更合理」的地方，
 * 都加了注释说明为什么必须照 ST 来。
 *
 * @param tokenCounter 计算 token 数。世界书预算按 token 算，需要外部注入。
 * @param random 随机源，注入以便测试概率与加权分组。
 * @param substitute 宏替换。ST 在匹配键与写入内容前都会做。
 * @param characterFileName 当前角色卡文件名，供 `characterFilter.names` 使用。
 * @param characterTags 当前角色卡的标签 id 列表。**null 表示拿不到 tagKey**
 *   （ST 在 `getTagKeyForEntity` 返回空时**整个跳过**标签过滤），传空列表则会按「无标签」参与白/黑名单判断。
 */
class WorldInfoEngine(
    private val settings: WorldInfoSettings = WorldInfoSettings.DEFAULT,
    private val tokenCounter: (String) -> Int = { it.length / 4 },
    private val random: () -> Double = { Math.random() },
    private val substitute: (String) -> String = { it },
    private val characterFileName: String? = null,
    private val characterTags: List<String>? = null,
    /** 外部强制激活表，key = `"${world}.${uid}"`。 */
    private val externalActivations: Map<String, ScanEntry> = emptyMap(),
) {

    /**
     * 跑一次世界书扫描。
     *
     * @param chat 聊天消息文本，**最新的在前**（`script.js:4624` 的 `.reverse()`）。
     * @param maxContext 当前模型的上下文长度，用于按百分比算世界书预算。
     * @param timedWorldInfo 从 `chat_metadata.timedWorldInfo` 读出的状态；结果里会返回更新后的版本。
     */
    fun activate(
        chat: List<String>,
        maxContext: Int,
        lore: LoreCollections,
        globalScanData: GlobalScanData = GlobalScanData.EMPTY,
        timedWorldInfo: TimedWorldInfo = TimedWorldInfo.EMPTY,
        isDryRun: Boolean = false,
    ): ActivationOutcome {
        val sortedEntries = sortEntries(lore)
        val buffer = ScanBuffer(chat, globalScanData, settings, substitute)
        val timed = TimedEffects(chat.size, sortedEntries, isDryRun, timedWorldInfo)

        timed.checkTimedEffects()

        // ST 在条目为空时提前返回，并**跳过** setTimedEffects / cleanUp（L4749-4751）
        if (sortedEntries.isEmpty()) {
            return ActivationOutcome(ActivationResult.EMPTY, timedWorldInfo)
        }

        val budget = computeBudget(maxContext)

        // delayUntilRecursion 的层级：去重、升序，先取最小的一层
        val availableLevels = sortedEntries
            .mapNotNull { it.entry.delayUntilRecursion.takeIf { d -> d != 0 } }
            .distinct()
            .sorted()
            .toMutableList()
        var currentDelayLevel = if (availableLevels.isNotEmpty()) availableLevels.removeAt(0) else 0

        var scanState: ScanState? = ScanState.INITIAL
        var tokenBudgetOverflowed = false
        var rounds = 0
        val allActivatedEntries = LinkedHashMap<String, ScanEntry>()
        val failedProbabilityKeys = HashSet<String>()
        /** 已做宏替换的副本，key 与 [allActivatedEntries] 一致。 */
        val substituted = HashMap<String, ScanEntry>()
        var allActivatedText = ""

        while (scanState != null) {
            if (settings.maxRecursionSteps != 0 && settings.maxRecursionSteps <= rounds) break
            rounds++

            var nextScanState = ScanState.NONE
            val activatedNow = LinkedHashSet<ScanEntry>()

            for (entry in sortedEntries) {
                // 已处理过（含概率失败）的条目在所有后续轮次都跳过
                if (entry.key in failedProbabilityKeys || allActivatedEntries.containsKey(entry.key)) continue
                if (entry.disable) continue

                if (entry.entry.triggers.isNotEmpty() && globalScanData.trigger !in entry.entry.triggers) continue
                if (!passesCharacterFilter(entry)) continue

                val isSticky = timed.isEffectActive(TimedEffectType.STICKY, entry)
                val isCooldown = timed.isEffectActive(TimedEffectType.COOLDOWN, entry)
                val isDelay = timed.isEffectActive(TimedEffectType.DELAY, entry)

                if (isDelay) continue
                if (isCooldown && !isSticky) continue

                val delayUntil = entry.entry.delayUntilRecursion
                if (scanState != ScanState.RECURSION && delayUntil != 0 && !isSticky) continue
                if (scanState == ScanState.RECURSION && delayUntil != 0 && delayUntil > currentDelayLevel && !isSticky) continue
                if (scanState == ScanState.RECURSION && settings.recursive && entry.entry.excludeRecursion && !isSticky) continue

                if (Decorators.ACTIVATE in entry.decorators) {
                    activatedNow += entry
                    continue
                }
                if (Decorators.DONT_ACTIVATE in entry.decorators) continue

                externalActivations[entry.key]?.let {
                    activatedNow += it
                    continue
                }

                if (entry.constant) {
                    activatedNow += entry
                    continue
                }
                if (isSticky) {
                    activatedNow += entry
                    continue
                }

                // `key` 为空数组的条目直接跳过（ST 要求必须是数组且非空）
                if (entry.entry.keys.isEmpty()) continue

                val textToScan = buffer.get(entry, scanState)

                val primaryMatched = entry.entry.keys.any { key ->
                    val needle = buffer.substitutedKey(key) ?: return@any false
                    buffer.matchKeys(textToScan, needle, entry)
                }
                if (!primaryMatched) continue

                val hasSecondary = entry.entry.selective && entry.entry.secondaryKeys.isNotEmpty()
                if (!hasSecondary) {
                    activatedNow += entry
                    continue
                }

                if (matchSecondaryKeys(entry, textToScan, buffer)) activatedNow += entry
            }

            // 排序：sticky 生效中的优先，其余保持 sortedEntries 的顺序
            val newEntries: MutableList<ScanEntry> = if (activatedNow.size > 1) {
                val indexOf = sortedEntries.withIndex().associate { (i, e) -> e.key to i }
                activatedNow.sortedWith(
                    compareByDescending<ScanEntry> { if (timed.isEffectActive(TimedEffectType.STICKY, it)) 1 else 0 }
                        .thenBy { indexOf[it.key] ?: -1 },
                ).toMutableList()
            } else {
                activatedNow.toMutableList()
            }

            var newContent = ""
            val accumulatedTokens = tokenCounter(allActivatedText)

            filterByInclusionGroups(newEntries, allActivatedEntries, buffer, scanState, timed)

            var ignoresBudget = newEntries.count { it.entry.ignoreBudget }

            for (entry in newEntries) {
                if (entry.entry.ignoreBudget) ignoresBudget--

                // 预算溢出后不再收普通条目，但仍要放行后面的 ignoreBudget 条目
                if (tokenBudgetOverflowed && !entry.entry.ignoreBudget) {
                    if (ignoresBudget > 0) continue
                    break
                }

                if (!verifyProbability(entry, timed, failedProbabilityKeys)) continue

                val resolved = entry.withContent(substitute(entry.content))
                substituted[entry.key] = resolved
                newContent += resolved.content + "\n"

                // 注意是 `>=`，且这里是 continue 而不是 break
                if (!entry.entry.ignoreBudget && accumulatedTokens + tokenCounter(newContent) >= budget) {
                    tokenBudgetOverflowed = true
                    continue
                }

                allActivatedEntries[entry.key] = resolved
            }

            // successful 只看概率失败，**不看预算** —— 被预算跳过的条目仍会进递归缓冲
            val successful = newEntries.filter { it.key !in failedProbabilityKeys }
            val forRecursion = successful.filter { !it.entry.preventRecursion }

            if (settings.recursive && !tokenBudgetOverflowed && forRecursion.isNotEmpty()) {
                nextScanState = ScanState.RECURSION
            }
            if (settings.recursive && !tokenBudgetOverflowed &&
                scanState == ScanState.MIN_ACTIVATIONS && buffer.hasRecurse()
            ) {
                nextScanState = ScanState.RECURSION
            }

            val minNotSatisfied =
                settings.minActivations > 0 && allActivatedEntries.size < settings.minActivations
            if (nextScanState == ScanState.NONE && !tokenBudgetOverflowed && minNotSatisfied) {
                val overMax = (settings.minActivationsDepthMax > 0 &&
                    buffer.getDepth() > settings.minActivationsDepthMax) ||
                    buffer.getDepth() > chat.size
                if (!overMax) {
                    nextScanState = ScanState.MIN_ACTIVATIONS
                    buffer.advanceScan()
                }
            }

            // 还有没走完的 delayUntilRecursion 层级时，即使递归关闭也要继续
            if (nextScanState == ScanState.NONE && availableLevels.isNotEmpty()) {
                nextScanState = ScanState.RECURSION
                currentDelayLevel = availableLevels.removeAt(0)
            }

            scanState = if (nextScanState == ScanState.NONE) null else nextScanState

            if (scanState != null) {
                val text = forRecursion.mapNotNull { substituted[it.key]?.content }.joinToString("\n")
                if (text.isNotEmpty()) {
                    buffer.addRecurse(text)
                    allActivatedText = "$text\n$allActivatedText"
                }
            }
        }

        val result = buildResult(allActivatedEntries, rounds, tokenBudgetOverflowed)

        // ST 在成功扫描的末尾才写计时效果（dry run 下 setTimedEffects 直接返回）
        timed.setTimedEffects(allActivatedEntries.values.toList())
        timed.cleanUp()

        return ActivationOutcome(result, timed.state, failedProbabilityKeys)
    }

    // ------------------------------------------------------------ 输出组装

    /**
     * 对应 `world-info.js:5189-5281`。
     *
     * **顺序陷阱**：先按 order **降序**遍历、再 `unshift`，所以除 outlet 外
     * 每个容器最终都是 order **升序**；而 outlet 用的是 `push`，
     * 于是它是**降序** —— 同一个函数里两种相反的约定，必须照抄。
     */
    private fun buildResult(
        allActivated: Map<String, ScanEntry>,
        rounds: Int,
        budgetOverflowed: Boolean,
    ): ActivationResult {
        val wiBefore = ArrayList<String>()
        val wiAfter = ArrayList<String>()
        val em = ArrayList<EmEntry>()
        val anTop = ArrayList<String>()
        val anBottom = ArrayList<String>()
        val depthGroups = ArrayList<DepthEntryGroup>()
        val outlets = LinkedHashMap<String, MutableList<String>>()

        // 降序遍历 + 前插 = 最终升序
        for (entry in allActivated.values.sortedByDescending { it.order }) {
            val content = entry.content
            if (content.isEmpty()) continue

            when (entry.position) {
                0 -> wiBefore.add(0, content)
                1 -> wiAfter.add(0, content)

                2 -> anTop.add(0, content)
                3 -> anBottom.add(0, content)

                4 -> {
                    // 分组键用 `depth ?? 4`，但建组时 ST 没有兜底（L5241），这里如实保留
                    val role = PromptRole.fromOrDefault(entry.entry.role)
                    val groupKeyDepth = entry.entry.depth
                    val index = depthGroups.indexOfFirst { it.depth == groupKeyDepth && it.role == role }
                    if (index >= 0) {
                        val g = depthGroups[index]
                        depthGroups[index] = g.copy(entries = listOf(content) + g.entries)
                    } else {
                        depthGroups += DepthEntryGroup(entry.entry.depth, role, listOf(content))
                    }
                }

                5 -> em.add(0, EmEntry(WiAnchorPosition.BEFORE, content))
                6 -> em.add(0, EmEntry(WiAnchorPosition.AFTER, content))

                7 -> {
                    val name = entry.entry.outletName
                    if (name.isEmpty()) continue
                    // push ⇒ 降序，与上面所有容器相反
                    outlets.getOrPut(name) { ArrayList() }.add(content)
                }
                // position 用严格数字比较：缺字段或越界一律丢弃
                else -> Unit
            }
        }

        return ActivationResult(
            worldInfoBefore = wiBefore.joinToString("\n"),
            worldInfoAfter = wiAfter.joinToString("\n"),
            emEntries = em,
            depthEntries = depthGroups,
            anBeforeEntries = anTop,
            anAfterEntries = anBottom,
            outletEntries = outlets,
            allActivatedEntries = allActivated.values.toList(),
            scanRounds = rounds,
            budgetOverflowed = budgetOverflowed,
        )
    }

    // ------------------------------------------------------------ 条目排序

    /** 对应 `getSortedEntries`（L4590-4644）。 */
    private fun sortEntries(lore: LoreCollections): List<ScanEntry> {
        fun List<WorldInfoSource>.collect(): List<ScanEntry> =
            flatMap { source -> source.entries.map { ScanEntry.of(it, source.name) } }

        /** `Array.sort` 与 Kotlin 的 `sortedByDescending` 都是稳定排序。 */
        fun List<ScanEntry>.byOrder(): List<ScanEntry> = sortedByDescending { it.order }

        val global = lore.global.collect()
        val character = lore.character.collect()

        val merged = when (settings.characterStrategy) {
            // evenly：合并后整体排序
            InsertionStrategy.EVENLY -> (global + character).byOrder()
            // 这两个策略是「两段各自排序后拼接」，段间不比较 order
            InsertionStrategy.CHARACTER_FIRST -> character.byOrder() + global.byOrder()
            InsertionStrategy.GLOBAL_FIRST -> global.byOrder() + character.byOrder()
        }

        // chat lore 永远最先，其次 persona lore
        return lore.chat.collect().byOrder() + lore.persona.collect().byOrder() + merged
    }

    // ------------------------------------------------------------ 概率

    /** 对应 `verifyProbability`（L5028-5049）。 */
    private fun verifyProbability(
        entry: ScanEntry,
        timed: TimedEffects,
        failed: MutableSet<String>,
    ): Boolean {
        // `probability === 100` 短路；注意 ST 里 `useProbability` 为真而 `probability` 缺失时**永不激活**
        if (!entry.entry.useProbability || entry.entry.probability == 100) return true
        if (timed.isEffectActive(TimedEffectType.STICKY, entry)) return true

        if (random() * 100 <= entry.entry.probability) return true

        failed += entry.key
        return false
    }

    // ------------------------------------------------------------ 次键

    /** 对应 `matchSecondaryKeys`（L4943-4978）。 */
    private fun matchSecondaryKeys(entry: ScanEntry, textToScan: String, buffer: ScanBuffer): Boolean {
        val logic = entry.entry.selectiveLogic ?: SelectiveLogic.AND_ANY

        var hasAny = false
        var hasAll = true

        for (key in entry.entry.secondaryKeys) {
            val needle = buffer.substitutedKey(key)
            val hit = needle != null && buffer.matchKeys(textToScan, needle, entry)

            if (hit) hasAny = true else hasAll = false

            if (logic == SelectiveLogic.AND_ANY && hit) return true
            if (logic == SelectiveLogic.NOT_ALL && !hit) return true
        }

        if (logic == SelectiveLogic.NOT_ANY && !hasAny) return true
        if (logic == SelectiveLogic.AND_ALL && hasAll) return true
        return false
    }

    // ------------------------------------------------------------ 角色过滤

    /** 对应 `characterFilter` 检查（L4816-4843）。 */
    private fun passesCharacterFilter(entry: ScanEntry): Boolean {
        val filter = entry.entry.raw.obj("characterFilter") ?: return true
        val isExclude = filter.bool("isExclude") ?: false

        val names = filter.stringList("names")
        if (names.isNotEmpty()) {
            val included = characterFileName != null && characterFileName in names
            val filtered = if (isExclude) included else !included
            if (filtered) return false
        }

        val tags = filter.stringList("tags")
        // ST 只有在能拿到 tagKey 时才做标签过滤；拿不到就整个跳过
        if (tags.isNotEmpty() && characterTags != null) {
            val included = characterTags.any { it in tags }
            val filtered = if (isExclude) included else !included
            if (filtered) return false
        }

        return true
    }

    // ------------------------------------------------------------ 分组

    /**
     * 对应 `filterByInclusionGroups`（L5388-5475）。在概率与预算**之前**就地裁剪 [newEntries]。
     *
     * 三步：先按计时效果裁（sticky 直接当赢家），再按评分裁，最后逐组决策
     * （被此前激活过的组锁死 → groupOverride 优先 → 加权随机）。
     */
    private fun filterByInclusionGroups(
        newEntries: MutableList<ScanEntry>,
        allActivated: Map<String, ScanEntry>,
        buffer: ScanBuffer,
        scanState: ScanState,
        timed: TimedEffects,
    ) {
        // group 字段可含多个组名，按 `,` + 任意空白切分
        val groups = LinkedHashMap<String, MutableList<ScanEntry>>()
        for (entry in newEntries) {
            if (entry.group.isEmpty()) continue
            for (name in entry.group.split(GROUP_SEPARATOR).filter { it.isNotEmpty() }) {
                groups.getOrPut(name) { ArrayList() }.add(entry)
            }
        }
        if (groups.isEmpty()) return

        val hasStickyGroup = HashSet<String>()

        // ① 按计时效果裁剪
        for ((name, group) in groups) {
            val hasSticky = group.any { timed.isEffectActive(TimedEffectType.STICKY, it) }
            if (hasSticky) {
                hasStickyGroup += name
                for (entry in group) {
                    if (!timed.isEffectActive(TimedEffectType.STICKY, entry)) newEntries.remove(entry)
                }
            }
            for (entry in group) {
                if (timed.isEffectActive(TimedEffectType.COOLDOWN, entry) ||
                    timed.isEffectActive(TimedEffectType.DELAY, entry)
                ) {
                    newEntries.remove(entry)
                }
            }
        }

        // ② 按评分裁剪
        for ((name, group) in groups) {
            if (!settings.useGroupScoring && group.none { it.entry.useGroupScoring == true }) continue
            if (name in hasStickyGroup) continue

            val live = group.filter { it in newEntries }
            if (live.size < 2) continue

            val scores = live.map { buffer.getScore(it, scanState) }
            val maxScore = scores.max()
            for (i in live.indices) {
                val scored = live[i].entry.useGroupScoring ?: settings.useGroupScoring
                if (!scored) continue
                if (scores[i] < maxScore) newEntries.remove(live[i])
            }
        }

        // ③ 逐组决策
        for ((name, group) in groups) {
            // 此前已激活过「group 字段整串等于组名」的条目 ⇒ 本轮该组候选全灭
            if (allActivated.values.any { it.group == name }) {
                for (entry in group) newEntries.remove(entry)
                continue
            }

            val live = group.filter { it in newEntries }
            if (live.size <= 1) continue

            // groupOverride：同组内 order 最高的一个直接胜出，不参与随机
            val prios = live.filter { it.entry.groupOverride }.sortedByDescending { it.order }
            if (prios.isNotEmpty()) {
                removeAllBut(newEntries, live, prios[0])
                continue
            }

            // 加权随机
            val total = live.sumOf { it.entry.groupWeight }
            val roll = random() * total
            var cumulative = 0
            var winner: ScanEntry? = null
            for (entry in live) {
                cumulative += entry.entry.groupWeight
                if (roll <= cumulative) {
                    winner = entry
                    break
                }
            }
            if (winner == null) continue
            removeAllBut(newEntries, live, winner)
        }
    }

    private fun removeAllBut(all: MutableList<ScanEntry>, group: List<ScanEntry>, keep: ScanEntry?) {
        for (entry in group) {
            if (entry === keep) continue
            all.remove(entry)
        }
    }

    // ------------------------------------------------------------ 预算

    /** 对应 `world-info.js:4736-4743`。 */
    private fun computeBudget(maxContext: Int): Int {
        var budget = Math.round(settings.budgetPercent * maxContext / 100.0).toInt()
        if (budget == 0) budget = 1 // `|| 1`：下限 1 token
        if (settings.budgetCap > 0 && budget > settings.budgetCap) budget = settings.budgetCap
        return budget
    }

    private companion object {
        // 组名之间用「逗号 + 任意空白」分隔（ST 用 /,\s* / 切分）。
        // 注意别把那个正则字面量写进 KDoc —— 里面的 `*/` 会把注释提前闭合。
        val GROUP_SEPARATOR = Regex(",\\s*")
    }
}
