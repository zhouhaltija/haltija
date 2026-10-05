# SillyTavern 世界书激活引擎 — 行为规范

> 从 `SillyTavern/public/scripts/world-info.js`（SillyTavern 1.19.0，6408 行）逐行读出，
> 辅以 `script.js` / `constants.js` / `utils.js` / quick-reply 扩展。
> 用途：作为 `android/core-prompt` 里 Kotlin 重实现的规范来源。
> **行号引用对应上面那一版；上游升级后行号会漂移，语义以函数名与描述为准。**
>
> 凡标「未找到」的，是源码里确实没有对应逻辑，不是没读。

---

## 0. 数据流与默认值

**入口**：`getWorldInfoPrompt`（L892）→ `checkWorldInfo(chat, maxContext, isDryRun, globalScanData)`（L4709）。

**扫描用聊天数组**（`script.js:4624`）：

```js
chatForWI = coreChat.map(x => includeNames ? `${x.name}: ${x.mes}` : x.mes).reverse()
```

- `coreChat` 已过滤掉 `is_system` 消息、`swipe` 时 pop 掉最后一条；`mes` 已被 regex 扩展、
  附文件内容与 media 标题、reasoning 已并入。**扫描文本 ≠ 原始聊天文本**。
- `.reverse()` ⇒ **下标 0 = 最新一条**，depth 0 是最后一条消息。

**globalScanData**（`script.js:4626-4634`）：`personaDescription` `characterDescription`
`characterPersonality` `characterDepthPrompt` `scenario` `creatorNotes` `trigger`。
`trigger` 取自 `GENERATION_TYPE_TRIGGERS = [normal, continue, impersonate, swipe, regenerate, quiet]`，
不在其中一律归一为 `'normal'`。

**条目来源**（L4475-4588）：global lore、character lore（角色卡 `extensions.world` + `charLore`）、
chat lore（`chat_metadata.world_info`）、persona lore。后三类若某世界已在 global 中则跳过。

**排序**（L4606-4625），`sortFn = (a,b) => b.order - a.order`（**降序**，稳定排序）：

- `character_first`（默认，=1）：`[...characterLore.sort, ...globalLore.sort]` — 两段各自排序后拼接
- `global_first`（=2）：`[...globalLore.sort, ...characterLore.sort]`
- `evenly`（=0）：`[...globalLore, ...characterLore].sort(sortFn)` — **合并后整体排序**
- 未知值：报错并按 evenly

最终 `entries = [...chatLore.sort, ...personaLore.sort, ...上面结果]` —— **chat lore 永远最先**。

**默认值**：`depth=2` `budget=25(%)` `budget_cap=0` `include_names=true` `recursive=false`
`min_activations=0` `min_activations_depth_max=0` `max_recursion_steps=0` `case_sensitive=false`
`match_whole_words=false` `use_group_scoring=false` `overflow_alert=false`
`character_strategy=character_first`。常量：`MAX_SCAN_DEPTH=1000` `DEFAULT_DEPTH=4`
`DEFAULT_WEIGHT=100` `KNOWN_DECORATORS=['@@activate','@@dont_activate']`。

**枚举**：`world_info_logic {AND_ANY:0, NOT_ALL:1, NOT_ANY:2, AND_ALL:3}`；
`scan_state {NONE:0, INITIAL:1, RECURSION:2, MIN_ACTIVATIONS:3}`；
`world_info_position {before:0, after:1, ANTop:2, ANBottom:3, atDepth:4, EMTop:5, EMBottom:6, outlet:7}`；
`wi_anchor_position {before:0, after:1}`；`extension_prompt_roles {SYSTEM:0, USER:1, ASSISTANT:2}`。

> `addMissingWorldInfoFields`（L2104）**只在编辑器路径**调用，扫描路径不调用 ⇒ 缺字段就是
> `undefined`，靠各使用点的 `??` 兜底。

---

## 1. 扫描文本构造（`WorldInfoBuffer`，L199-474）

`#depthBuffer` 初始化（L250-260）：

```
for depth in 0..999:
    if messages[depth]: depthBuffer[depth] = messages[depth].trim()   # 假值不写入，留洞
    if depth == messages.length - 1: break
```

`get(entry, scanState)`（L279-328）：

```
depth = entry.scanDepth ?? (world_info_depth + skew)
if depth <= startDepth(=0): return ''          # ⇒ scanDepth=0 的条目永不命中
if depth < 0: return ''
if depth > 1000: depth = 1000

MATCHER = '\x01'; JOINER = '\n\x01'
result = MATCHER + depthBuffer[0..depth).join(JOINER)     # 半开区间
+ 按固定顺序追加（各项有真值判断）：
  personaDescription → characterDescription → characterPersonality
  → characterDepthPrompt → scenario → creatorNotes
+ injectBuffer.join(JOINER)                    # 扩展注入
+ recurseBuffer.join(JOINER)                   # 仅当 scanState != MIN_ACTIVATIONS
```

> 反直觉：`depth > 0` 时 result 至少是 `'\x01'`，**永不为空串**。
> `\x01` 同时充当整词匹配的 `\W` 边界。

---

## 2. 键匹配（`matchKeys`，L337-366）

```
keyRegex = parseRegexFromString(needle)
if keyRegex: return keyRegex.test(haystack)     # 正则：无视 caseSensitive 与 matchWholeWords
haystack = caseSensitive(entry ?? global) ? haystack : lower(haystack)
needle   = 同上
if (entry.matchWholeWords ?? global):
    if needle.split(/\s+/).length > 1: return haystack.includes(needle)   # 多词退化为 includes
    else: return RegExp("(?:^|\\W)(" + escapeRegex(needle) + ")(?:$|\\W)").test(haystack)
else: return haystack.includes(needle)
```

- 每个键匹配前 `trim()` 并做宏替换；空替换结果视为不命中。
- 主键「任一命中即可」（`find`，取数组顺序第一个）。
- **`getScore` 不 trim 键**（L439/449）——与主激活路径不一致。

**正则键**（L2901-2926）：整串锚定 `/^\/([\w\W]+?)\/([gimsuy]*)$/`；模式内出现未转义 `/` 则非法；
`pattern.replace('\\/', '/')` **只替换第一处**（JS 的 `String.replace` 传字符串非全局）；
`/abc/ii` 之类重复修饰符因 `new RegExp` 抛错而返回 null。**每次匹配都重新 new RegExp**，
所以带 `g`/`y` 不会因 `lastIndex` 残留出 bug。

---

## 3. 次键与 selectiveLogic（L4924-4978）

`hasSecondaryKeywords = entry.selective && keysecondary.length` ⇒ **`selective` 为假时次键被完全忽略**。

```
any=false; all=true
for k in keysecondary:
    hit = substitute(k) && matchKeys(textToScan, trim(k), entry)
    if hit: any=true else all=false
    if AND_ANY &&  hit: return true
    if NOT_ALL && !hit: return true
if NOT_ANY && !any: return true
if AND_ALL && all: return true
return false
```

次键复用与主键**完全相同**的 `textToScan`。`selectiveLogic ?? 0`。

---

## 4. 递归

**轮次上限**（L4768）：`if max_recursion_steps && max_recursion_steps <= count: break`
⇒ `N` = **总共最多 N 轮扫描**（0 = 无限）。

**触发**（L5097-5107）：

```
if recursive && !token_budget_overflowed && successfulNewEntriesForRecursion.length: nextState = RECURSION
if recursive && !token_budget_overflowed && scanState == MIN_ACTIVATIONS && buffer.hasRecurse(): nextState = RECURSION
```

`successfulNewEntries = newEntries.filter(not failedProbability)`；
`...ForRecursion = successfulNewEntries.filter(not preventRecursion)`。

**进入下一轮时**（L5135-5144）：

```
text = successfulNewEntriesForRecursion.map(content).join('\n')
buffer.addRecurse(text); allActivatedText = text + '\n' + allActivatedText
```

⇒ 递归内容只进 `#recurseBuffer`，**原始聊天每轮都被重扫**；缓冲单调累积；
最后一轮（nextState 变 NONE）的内容**不写入**缓冲。

| 字段 | 语义 |
|---|---|
| `excludeRecursion` | 仅当 `scanState == RECURSION && recursive && !sticky` 时**跳过该条目** |
| `preventRecursion` | 不影响激活，只是该条目 content **不进递归缓冲** |
| `delayUntilRecursion` | 非递归轮屏蔽（除非 sticky）；递归轮要求 `<= currentRecursionDelayLevel` |

**延迟层级**（L4754-4762、L5128-5133）：

```
levels = unique(truthy delayUntilRecursion, true→1).sort(asc)
currentLevel = levels.shift() ?? 0
# 本轮将结束时：
if nextState == NONE && levels.length: nextState = RECURSION; currentLevel = levels.shift()
```

> **反直觉**：层级递进**不检查 `recursive`** —— 即使全局递归关闭，
> 带 `delayUntilRecursion` 的条目仍会被推到后续轮次激活。
>
> **但要注意一个前提（实现时验证出来的）**：最小的那个层级会被**立刻**
> `shift()` 成 `currentLevel`，所以「还有层级没走完」只有在**存在至少两个不同层级**时才成立。
> 若整本书只配了一个 `delayUntilRecursion` 值，首轮它被
> `scanState !== RECURSION` 挡下，之后又没有推进机会，
> **在 `recursive = false` 时该条目永远不会激活**。
> 这是 ST 的真实行为，已在 `WorldInfoEngineTest` 里用两个用例正反锁定。

**与预算**：`token_budget_overflowed` 会**永久阻断**其后的递归。
被预算跳过的条目**仍会**进入递归缓冲（因为 `successfulNewEntries` 只看概率失败）。

---

## 5. 概率（L5028-5049）

```
if !useProbability || probability === 100: return true
if isEffectActive('sticky', entry): return true          # sticky 不重掷
roll = Math.random() * 100
if roll <= probability: return true
failedProbabilityChecks.add(entry); return false
```

- `probability = 0` 仍可能因 `Math.random()===0` 通过（~2⁻⁵³）。
- **坑**：`useProbability=true` 而 `probability` 为 `undefined` ⇒ `roll <= undefined` 为 false ⇒ **永不激活**。
- 失败条目进 `failedProbabilityChecks`，**同一次生成内不再重掷**（局部变量，下次生成重置）。
- 概率作用于**所有**进入 `newEntries` 的条目，含 constant / sticky / `@@activate`。

---

## 6. sticky / cooldown / delay（`WorldInfoTimedEffects`，L479-790）

持久化在 `chat_metadata.timedWorldInfo[type][key]`，`type ∈ {sticky, cooldown}`，
`key = `${world}.${uid}``，值 `{hash, start, end, protected}`。
`start = chat.length`（**过滤后 coreChat 的长度**），`end = start + Number(entry[type])`。

**每轮检查**（L682-688）：非 dry run 才检查 sticky / cooldown；**delay 始终检查**。

`#checkTimedEffectOfType`（L619-660）逐项：

1. `entry = entries.find(x => String(x.hash) === String(value.hash))`（**哈希含 content ⇒ 条目被编辑即失效**）
2. `chat.length <= start && !protected` → 删除（聊天未推进）
3. 找不到条目：`chat.length >= end` 才删除
4. `!entry[type]` → 删除
5. `chat.length >= end` → 删除 + `onEnded(entry)`
6. 否则 push 进 buffer ⇒ 本次扫描视为生效

`onEnded.sticky`（L518-529）：若条目有 `cooldown`，**新建 protected 的 cooldown**
（`start=chat.length`，`end=start+cooldown`）写元数据，并立即 push 进 cooldown buffer。

`#checkDelayEffect`（L666-677）：`entry.delay && chat.length < entry.delay` → push。
delay **不持久化、不消耗**。

**设置**（L710-724、L730-736）：

```
if isDryRun: return
if !entry[type]: return
if !timedWorldInfo[type][key]: 写入 {hash, start: chat.length, end: chat.length+Number(entry[type]), protected:false}
```

> **已激活条目再次命中不会刷新/延长**（key 已存在就什么都不做）。

**主循环消费顺序**（L4845-4903）：`delay → cooldown(非 sticky) → delayUntilRecursion →
excludeRecursion → @@activate → @@dont_activate → 外部激活 → constant → sticky → 键匹配`。

**dry run**：跳过 sticky/cooldown 的检查与写入（⇒ sticky 不自动激活、cooldown 不抑制），
**delay 照常生效**；但仍会建 `timedWorldInfo` 结构、仍会改写 AN 扩展提示。

---

## 7. 分组（`filterByInclusionGroups`，L5388-5475）

在**概率与预算之前**就地裁剪 `newEntries`。`group` 字段可含多个组名，按 `/,\s*/` 切分。

1. `filterGroupsByTimedEffects`（L5337）：组内有 sticky ⇒ 移除组内非 sticky 条目并记 `hasStickyMap`；
   组内 cooldown / delay 条目也移除。
2. `filterGroupsByScoring`（L5292）：若 `!global_use_group_scoring && !group.some(useGroupScoring)` 跳过；
   组内有 sticky 跳过；`getScore` 最高分者胜，对 `useGroupScoring ?? global` 为真且**分数低于最高分**者移除。
3. 每组决策（L5421）：
   - 若 `allActivatedEntries` 中已有条目 **`x.group === key`（整串严格相等）** ⇒ 本轮该组候选**全灭**
   - 组内 ≤1 个 ⇒ 跳过
   - `prios = group.filter(groupOverride).sort(sortFn)` 非空 ⇒ **`prios[0]`（order 最高的 groupOverride）胜出**
   - 否则加权随机：`total = Σ(groupWeight ?? 100)`；`roll = Math.random()*total`；
     累加直到 `roll <= cur`，首个满足者胜出

`getScore`（L428-473）：主键命中计数；`numberOfPrimaryKeys==0` ⇒ 0；
有次键且 AND_ANY ⇒ `primary+secondary`；AND_ALL ⇒ 全命中才加 secondary，否则仅 primary；
NOT_ANY / NOT_ALL ⇒ 仅 primary。**不 trim 键、`selectiveLogic` 不兜底**。

---

## 8. Token 预算（L4736-4743、L5009-5077）

```
budget = Math.round(world_info_budget * maxContext / 100) || 1     # 下限 1
if budget_cap > 0 && budget > budget_cap: budget = budget_cap

newEntries 排序：sticky 生效中的优先，其余按 sortedEntries 下标
ignoresBudget = count(ignoreBudget)
for entry in newEntries:
    ignoresBudget -= (entry.ignoreBudget ? 1 : 0)
    if overflowed && !entry.ignoreBudget:
        if ignoresBudget > 0: continue      # 后面还有无视预算的条目
        break
    if !verifyProbability(): continue
    newContent += substitute(content) + '\n'
    if !entry.ignoreBudget && (textToScanTokens + tokens(newContent)) >= budget:
        overflowed = true; continue         # 注意是 continue 不是 break
    allActivatedEntries.set(`${world}.${uid}`, entry)
```

- 判定用 **`>=`**（恰好等于预算也拒绝）；**不截断**内容，整条进或整条跳。
- 溢出后跳过普通条目，直到剩余没有 `ignoreBudget` 条目才 `break`；
  `ignoreBudget` 条目溢出后照常加入。
- `textToScanTokens` 是历史累积文本，**包含上一轮被预算拒绝的条目内容**。

---

## 9. 输出与插入位置（L5189-5281）

先按 `sortFn`（order **降序**）遍历，再 `unshift` ⇒ 最终各容器为 **order 升序**
（同 order 时激活顺序被反转）。遍历中 `content` 为空则跳过（但仍留在 `allActivatedEntries`）。

| position | 值 | 容器 | 容器内顺序 |
|---|---|---|---|
| before | 0 | `WIBeforeEntries`（unshift） | order 升序 |
| after | 1 | `WIAfterEntries`（unshift） | order 升序 |
| ANTop | 2 | `ANTopEntries`（unshift） | order 升序 |
| ANBottom | 3 | `ANBottomEntries`（unshift） | order 升序 |
| atDepth | 4 | `WIDepthEntries`，按 `(depth ?? 4, role ?? SYSTEM)` 分组 | 组间=首现顺序，组内 order 升序 |
| EMTop | 5 | `EMEntries` + `{position: before}` | order 升序 |
| EMBottom | 6 | `EMEntries` + `{position: after}` | order 升序 |
| outlet | 7 | `WIOutletEntries[outletName]`（**push**） | **order 降序**（与其它相反） |
| 其它 | — | 静默丢弃 | — |

> `position` 用 **`===` 数字比较** ⇒ 字符串 `"0"` 或 ≥8 的旧数据被静默丢弃。
> `atDepth` 分组键用 `entry.depth ?? 4`，但 push 进组的对象 `depth` **未兜底**（L5241）
> ⇒ 缺 depth 的条目会生成 `depth: undefined` 的组，与显式 `depth:4` 的组并存。

返回：

```
worldInfoBefore = WIBeforeEntries.join('\n') 或 ''
worldInfoAfter  = WIAfterEntries.join('\n') 或 ''
→ { worldInfoBefore, worldInfoAfter, EMEntries, WIDepthEntries,
    ANBeforeEntries: ANTopEntries, ANAfterEntries: ANBottomEntries,
    outletEntries, allActivatedEntries }
```

`getWorldInfoPrompt` 另返回 `worldInfoString = before + after`（无分隔符）。

---

## 10. 容易漏掉的点

- **`automationId`**：`checkWorldInfo` **完全不使用**。消费方是 Quick Reply 扩展（监听
  `WORLD_INFO_ACTIVATED`）。
- **`triggers`**：仅当非空数组时生效，要求 `includes(globalScanData.trigger)`，否则跳过。
- **`characterFilter`**：`isExclude=true` 是黑名单，`false` 是白名单；
  `names` 对角色卡文件名，`tags` 对实体标签 id。结构兜底只在编辑器路径。
- **`vectorized`**：**未找到**任何影响激活的代码。本版本 `checkWorldInfo` 无向量检索，
  也无「无向量时降级」分支 —— vectorized 条目与普通条目一样按键匹配。
- **`decorators`**（L4652-4698）：只影响 `content`（键不受影响），每次扫描解析一次。
  消费点仅 `@@activate`（立即激活）与 `@@dont_activate`（抑制），用精确 `includes`。
  - `isKnownDecorator` 是**前缀**匹配 ⇒ `@@activateFoo` 被收集但 `includes('@@activate')` 为 false
    ⇒ 既不激活也不抑制（静默失效）。
  - content **全为 `@@` 行**时 `newContent` 从未被重写 ⇒ 返回的 content **仍是原文**（含装饰器行）。
  - `@@@` 行在未遇未知装饰器前被 `continue`；一旦 `fallbacked=true`，`@@@activate` 会被当作
    `@@activate` 收集。
  - 装饰器在 hash 计算**之前**剥离，并作为 `decorators` 数组参与 hash。
- **外部强制激活**：`WorldInfoBuffer.externalActivations` 静态 Map，key = `world.uid`。
  检查顺序在 `@@dont_activate` 之后、`constant` 之前。每次成功扫描结束清空；
  但 `sortedEntries.length===0` 提前返回时**不清空**。
- **去重**：唯一去重是 `allActivatedEntries`（key = `${world}.${uid}`），已激活条目在后续
  所有轮次（含递归轮）跳过；概率失败由 `failedProbabilityChecks` 单独拦住。
- **`isDryRun`**：不 emit 事件；跳过 sticky/cooldown 的检查与写入（delay 仍生效）；
  但仍建 metadata 结构、仍改写 AN 扩展提示、仍应用宏与 regex、仍算预算。
- **min-activations**（L5109-5126）：`over_max = (min_activations_depth_max>0 && getDepth()>该值)
  || (getDepth() > chat.length)`；未超限则下一轮 MIN_ACTIVATIONS 且 `skew++`
  （该轮扫描串**不含**递归缓冲）。实际终止由 `getDepth() > chat.length` 兜底。
- **`WORLDINFO_SCAN_DONE`** 每轮 emit，监听者可改写 `state.next` / `activated.text` /
  `recursionDelay.currentLevel` / `budget.current` / `budget.overflowed`。
- `entry.disable == true` 用**宽松相等**（`disable: 1` 也会禁用）。
- `getSortedEntries` 抛异常返回 `[]`，随后走空返回路径（该路径还会跳过
  `setTimedEffects` / `resetExternalEffects` / `cleanUp`）。

---

## 11. 实现偏差检查清单

重写时最容易写错的点，逐条对照：

1. `pattern.replace('\\/', '/')` **只替换第一处**（JS 传字符串非全局）→ Kotlin 用 `replaceFirst`。
2. 正则键走 `keyRegex.test(haystack)`，**在大小写转换之前**，且无视 `matchWholeWords`。
3. 整词匹配只对**单 token** 键生效；多词键退化为 `includes`（无边界）。
4. `scanDepth = 0` ⇒ 扫描串为空 ⇒ 该条目**永不命中**（不是「用全局深度」）。
5. 递归内容进 `#recurseBuffer`，**不进** `#depthBuffer`；MIN_ACTIVATIONS 轮看不到递归缓冲。
6. `successfulNewEntries` 只看概率失败，**不看预算** ⇒ 被预算跳过的条目仍进递归缓冲。
7. 预算判定是 `>=`，且溢出后是 `continue`（跳过普通条目）而非 `break`。
8. 输出容器先按 order **降序**排再 `unshift` ⇒ 最终 **升序**；但 `outlet` 用 `push` ⇒ **降序**。
9. `position` 用严格数字比较 ⇒ 字符串 position 被丢弃。
10. sticky 结束时若有 cooldown，新建的是 **`protected: true`** 的 cooldown，且**立即生效**。
11. 已激活条目再次命中**不会**刷新/延长计时效果。
12. `delay` 永不持久化，且 dry run 下**仍然生效**。
13. `@@` 开头但**全行都是装饰器**的 content，返回的是**原文**（未被裁剪）。
14. `disable` 用宽松相等；`probability === 100` 短路；`probability` 为 `undefined` 时永不激活。
15. 哈希基于**剥离装饰器后**的完整条目 JSON（含 `world` / `uid`），条目一被编辑即失效。

---

## 附录：Prompt 组装的相关事实（实现 `core-prompt/assemble` 时用）

> 这一节不属于世界书引擎，但在实现 Prompt 组装时一并查实，记在这里避免重复调研。

### A.1 `story_string` 渲染管线

`renderStoryString`（`public/scripts/power-user.js:2234-2264`）：

```
① output = Handlebars.compile(storyString, { noEscape: true })(params)
② output = substituteParams(output, params.user, params.char)   // ST 宏
③ output = output.replace(/^\n+/, '')                            // 去掉开头换行
④ if (output 非空 && !output.endsWith('\n') && position != IN_CHAT) {
       if (!instruct.enabled || (instruct.wrap && !instruct.story_string_suffix)) output += '\n'
   }
```

**参数表**（`script.js:4702-4718`）：`description` `personality` `persona` `scenario`
`system` `char` `user` `wiBefore` `wiAfter` `loreBefore`(=`wiBefore`) `loreAfter`(=`wiAfter`)
`anchorBefore` `anchorAfter` `mesExamples` `mesExamplesRaw`。

### A.2 Handlebars 与 ST 宏的桥接（最容易写错的一点）

`public/scripts/macros.js:18-25` 注册了两个 helper：

```js
Handlebars.registerHelper('trim', () => '{{trim}}');          // 原样吐出，留给宏引擎
Handlebars.registerHelper('helperMissing', function () {
    const options = arguments[arguments.length - 1];
    return substituteParams(`{{${options.name}}}`);           // 兜底：未知调用也留给宏引擎
});
```

后果：

- **未定义的 `{{xxx}}` 渲染后仍是 `{{xxx}}`**，而不是裸 Handlebars 的空串。
  任何 Handlebars 子集实现都必须照此办理，否则 `{{trim}}` 之类会凭空消失。
- 参数表里**真有**该键时优先取参数值（`{{char}}` 走参数，不走 helper）。
- **参数化宏在 story_string 里会报错**：`{{random:a,b}}` 的 `:` 不是合法路径字符，
  Handlebars 直接抛 ParseError，ST 弹「Check the story string template for validity」。
  （本项目的实现选择宽容放行并给出警告。）

**Handlebars `if` 的真值**（实测）：`null` / `undefined` / `''` / `0` / `false` / `[]` 为**假**；
非空数组、非空字符串、非零数字为真。注意空数组是**假**（`Utils.isEmpty`），这点与 JS 原生真值不同。

### A.3 ST 自带预设实际用到的 Handlebars 语法

统计 `default/content/presets/context/` 全部 34 个预设：只有 `{{#if 变量}}…{{/if}}`（306 组）、
`{{变量}}`（340 处）、`{{trim}}`（32 处）。**没有 `{{else}}`、没有 `{{#each}}`、没有其它 helper。**

### A.4 Prompt 来源清单

`preparePromptsForChatCompletion`（`public/scripts/openai.js:1363+`）创建这些条目：

| identifier | role | 内容来源 |
|---|---|---|
| `worldInfoBefore` | system | 世界书 before |
| `worldInfoAfter` | system | 世界书 after |
| `charDescription` | system | 角色描述 |
| `charPersonality` | system | 角色性格 |
| `scenario` | system | 场景 |
| `impersonate` | system | 扮演提示 |
| `quietPrompt` | system | quiet 生成 |
| `groupNudge` | system | 群聊提示 |
| `bias` | assistant | 偏置 |
| `summary` / `authorsNote` / `vectorsMemory` / `vectorsDataBank` | 视设置 | 扩展注入 |

顺序由 `oai_settings.prompt_order[<character_id>].order` 给出，
每项是 `{ identifier, enabled }`；未被启用的条目跳过。
