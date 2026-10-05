# SillyTavern 宏系统 — 行为规范

> 从 `SillyTavern/public/scripts/macros/`（约 6301 行，含 chevrotain 词法/语法分析器）
> 读出并实测。用途：作为 `android/core-prompt/macro` 里 Kotlin 重实现的规范来源。
> **行为已用 `tools/gen-macro-goldens.mjs` 做跨语言对照锁定**（见文末）。

---

## 1. 语法

```
{{名字}}                     无参数
{{名字:参数}}                单参数 —— 参数里可以再出现冒号
{{名字::参数1::参数2}}       多参数，用 `::` 分隔
{{名字 参数}}                空白分隔 —— 同样是**一个**参数
```

### 1.1 `:` 与 `::` 的区别不是「吃不吃空格」

两者在空白处理上**完全相同**（参数取首末 token 区间，首尾空白天然被裁掉）。
真正的区别是**参数个数**（`MacroParser.js:136-184`）：

| 写法 | 结果 |
|---|---|
| `{{echo:a:b}}` | 1 个参数 `a:b` |
| `{{echo:::a}}` | 1 个参数 `:a` |
| `{{echo::a::b}}` | 2 个参数 → echo 只收 1 个 → **整段回退成原文** |
| `{{echo abc}}` | 1 个参数 `abc` |
| `{{opt::a::}}` | 2 个参数：`a` 与 `""`（空参数合法，**不是 null**） |

逗号**不是**分隔符：`{{opt:a,b}}` → 1 个参数 `a,b`。

### 1.2 大小写

宏名查找**不敏感**：注册与查询都用 `toLowerCase()` 作 key（`MacroRegistry.js:302`）。
`{{USER}}` / `{{User}}` 都能命中 `user`。

### 1.3 标识符规则

`MACRO_IDENTIFIER_PATTERN = /^[a-zA-Z][\w-_]*$/`（`MacroLexer.js:17`）：
必须以字母开头，可含数字、下划线、连字符。

---

## 2. 回退规则（最容易漏的一节）

下面这些情况 ST **原样返回 `{{...}}` 文本**，既不报错也不置空：

- 宏名未注册；
- 参数个数不符（多了、或少于必填数）；
- 参数类型不符（`strictArgs` 默认为真）；
- handler 抛异常；
- 语法不完整：`{{` 未闭合、`{{}}`、`{{ }}`。

**例外**：宏名不是合法标识符时，把开头的 `{{` 当普通文本、从下一个字符继续扫。
这条让 `{{{{char}}}}` 得到 `{{Seraphina}}`（外层花括号是文本，内层才是宏）。

---

## 3. 求值

- 顶层从左到右；**参数先递归求值**（inside-out），所以 `{{up:{{char}}}}` → `SERAPHINA`。
- 同一宏出现多次各自独立求值。
- **宏结果不会被二次扫描**，不存在「结果里的 `{{}}` 被再展开」。
- **没有递归深度或迭代上限**（源码里未找到任何 guard）。

### 3.1 预处理（求值前，`MacroEngine.js:273-294`）

| priority | 规则 |
|---|---|
| 10 | `{{time_UTC±N}}` → `{{time::UTC±N}}`（大小写不敏感） |
| 20 | `<USER>`→`{{user}}`、`<BOT>`/`<CHAR>`→`{{char}}`、`<GROUP>`→`{{group}}`、`<CHARIFNOTGROUP>`→`{{charIfNotGroup}}` |

### 3.2 后处理（求值后，`MacroEngine.js:299-323`）

| priority | 规则 |
|---|---|
| 10 | `\{`→`{`、`\}`→`}`（**这是 `\{\{char\}\}` 能输出 `{{char}}` 的机制**） |
| 20 | `(?:\r?\n)*{{trim}}(?:\r?\n)*` → 删除（大小写不敏感，**只吃换行不吃空格**） |
| 30 | 删除所有 ELSE 标记 `"\u0000\u001FELSE\u001F\u0000"` |

### 3.3 `{{trim}}` 不是宏，是后处理正则

- 非 scoped 时 handler 返回字面量 `"{{trim}}"`，再由上面的正则删掉**它和它前后的换行**。
- `foo  {{trim}}  bar` → `foo    bar`（**空格保留**）
- `foo\n\n{{trim}}\n\nbar` → `foobar`
- `{{trim::abc}}` → handler 忽略参数 → 输出**空串**

---

## 4. 参数模型

- `ArgDef { name, type, optional }`；**`optional` 必须是后缀**，否则注册失败。
- `minArgs` = 第一个 optional 的下标；`maxArgs` = 数组长度。
- **`defaultValue` 在运行时完全不生效**（只用于文档）。
  ST 自己的 `{{space}}` 之所以有默认 1，是因为它的 handler 里写了 `count ?? 1`。
- 类型**只校验不转型**，handler 拿到的永远是字符串：
  - `string` 恒真
  - `integer`：`/^-?\d+$/`
  - `number`：`Number.isFinite(Number(trimmed))` —— **空串通过**（`Number('')===0`）
  - `boolean`：`on/true/1/off/false/0`
- 具名参数**未实现**：`{{show::k=v}}` 的参数就是字面量 `k=v`。

---

## 5. 内置宏清单

> 参数：`a, b` 必填；`[a]` 可选；`*` 前缀表示 list 参数。
> 类别：U=utility R=random N=names C=character CH=chat T=time V=variable P=prompts S=state

### 5.1 工具（U）

| 宏 | 参数 | 语义 |
|---|---|---|
| `space` | `[count]` int | `" ".repeat(Number(count ?? 1))`；负数抛错→原文；`{{space::0}}`→`""` |
| `newline` | `[count]` int | `"\n".repeat(Number(count ?? 1))` |
| `noop` | — | `""` |
| `trim` | `[content]` | scoped→返回已 trim 的 content；非 scoped→返回 `"{{trim}}"` 交由后处理 |
| `if` | `condition, content` | 见 §6.1 |
| `else` | — | 返回 ELSE 标记（后处理删除） |
| `reverse` | `value` | 按 **code point** 反转（`Array.from`，代理对不拆散） |
| `//`（`comment`） | `comment` | `""` |
| `input` | — | `#send_textarea` 的值（DOM，移动端需自行决定语义） |
| `banned` | `word` | 去掉首尾引号；返回 `""` |
| `outlet` | `key` | `extension_prompts[OUTLET(key)].value \|\| ''` |
| `maxPrompt` / `maxContext` / `maxResponse` | — | 对应 token 数转字符串 |

### 5.2 随机（R）

| 宏 | 参数 | 语义 |
|---|---|---|
| `roll` | `formula` | 见 §6.2 |
| `random` | `*items` | 均匀随机取一个；空列表→`""`；**不可复现**（熵池） |
| `pick` | `*items` | 确定性选择，见 §6.3 |

### 5.3 名字 / 角色（N / C）

`user` `char` `group`（别名 `charIfNotGroup`）`groupNotMuted` `notChar`
`charPrompt` `charInstruction` `charDescription`（别名 `description`）
`charPersonality`（别名 `personality`）`charScenario`（别名 `scenario`）
`persona` `mesExamplesRaw` `mesExamples` `charDepthPrompt`
`charCreatorNotes`（别名 `creatorNotes`）`charFirstMessage`（别名 `greeting`，参数 `[index]`）
`charVersion`（别名 `version`）

全部直接读 `env` 对应字段，缺失时返回 `""`。

### 5.4 会话（CH）

| 宏 | 语义 |
|---|---|
| `lastMessage` / `lastUserMessage` / `lastCharMessage` / `lastMessageId` | 从末尾向前扫，**跳过系统消息**；扫到第一条满足条件的。swipe 生成中的消息也跳过 |
| `firstIncludedMessageId` | `chat_metadata.lastInContextMessageId` |
| `lastSwipeId` / `currentSwipeId` | 最后一条消息的 `swipes.length` / `swipe_id + 1` |
| `allChatRange` | `0-${chat.length-1}`，chat 空则 `""` |

### 5.5 时间（T）

| 宏 | 语义 |
|---|---|
| `time` | 无参→本地化短时间 `LT`；`UTC±N` 参数→按该偏移 |
| `date` | `format('LL')` 本地化长日期 |
| `weekday` | `format('dddd')` |
| `isotime` | `HH:mm`（本地时区，24 小时制） |
| `isodate` | `YYYY-MM-DD`（本地时区） |
| `datetimeformat` | 参数是 moment 格式串 |
| `idleDuration` | 取「从末尾数第二条非系统消息且它是 user」的时间差，`humanize()` 无方向；找不到→`just now` |
| `timeDiff` | `humanize(true)` —— **带 in/ago 方向，非绝对值** |

### 5.6 变量（V）

`setvar` `addvar` `incvar` `decvar` `getvar` `hasvar`(`varexists`) `deletevar`(`flushvar`)
`setvarkey`(`setvarindex`) `getvarkey`(`getvarindex`)
以及对应的 `*globalvar` 系列。

- 局部落 `chat_metadata.variables[name]`（按聊天存档）
- 全局落 `extension_settings.variables.global[name]`
- `get` 的返回：trim 后为 `""` 或 `Number()` 为 NaN → 原字符串；否则返回**数字**

### 5.7 指令模板（P）

`instructStoryStringPrefix/Suffix` `instructUserPrefix/Suffix` `instructAssistantPrefix/Suffix`
`instructSystemPrefix/Suffix` `instructFirstAssistantPrefix` `instructLastAssistantPrefix`
`instructStop` `instructUserFiller` `instructSystemInstructionPrefix`
`instructFirstUserPrefix` `instructLastUserPrefix`
`defaultSystemPrompt` `systemPrompt` `exampleSeparator`（别名 `chatSeparator`）`chatStart`

统一语义：`isEnabled() ? (getValue() ?? '') : ''`。

---

## 6. 重点宏的精确语义

### 6.1 `{{if}}`（`core-macros.js:134-213`）

`unnamedArgs: [condition, content]`，`delayArgResolution: true`（参数保持原文，由 handler 自己求值）。

```
① rawCondition 以 `!` 开头 → 取反，去掉 `!`
② condition = resolve(condition)                  // 求值嵌套宏
③ 若 condition 形如 `.name` / `$name` → 转成 {{getvar::name}} / {{getglobalvar::name}}
④ 否则若存在同名且 0 参的宏 → condition = resolve("{{" + condition + "}}")
⑤ falsy = (condition === '' || isFalseBoolean(condition))
⑥ 内容按顶层 {{else}} 切成 then / else 两段
⑦ 只对**选中分支**求值；falsy 且无 else → ''
```

- falsy 只有 `''` 与 `off/false/0`（trim + 小写）。`"no"`、`"0.0"`、`"null"` 都是 **truthy**。
- 只有被选中的分支会求值（未选分支里的宏不产生副作用）。

### 6.2 `{{roll}}`（`core-macros.js:303-337`）

- 参数若是纯数字 → 前置 `1d`（`{{roll::6}}` → `1d6`）。
- droll 语法只有 `^([1-9]\d*)?d([1-9]\d*)([+-]\d+)?$`：即 `NdM±K`，N 可省略。
  **不支持** `kh/kl/dl/dh`、乘法、括号、多骰组、空格。
- 校验失败 → **返回空串**（不是原文）；无参 → 元数错误 → 原文。
- **实测补充**：骰子数可省略，`d6` 是**合法**的（`^([1-9]\d*)?d...` 里那一段可选）。
  非法的是 `0d6`（首字符必须 1-9）、`1d0`、`2d6kh1`、带空格的 `1d6 `。
- 随机源 `Math.random()`，不可复现。

### 6.3 `{{pick}}`（确定性）

种子链：

```
chatIdHash  = chat_metadata.chat_id_hash ?? getStringHash(main_chat ?? currentChatId)
contentHash = getStringHash(整段 content)
offset      = 该宏在文本里的绝对偏移
rerollSeed  = chat_metadata.pick_reroll_seed || null      // 0 视为无
seedStr     = [chatIdHash, contentHash, offset, rerollSeed].filter(非 null).join('-')
finalSeed   = getStringHash(seedStr)
rng         = seedrandom(String(finalSeed))                // ARC4
idx         = floor(rng() * list.length)
```

同一聊天 + 同一文本 + 同一位置 → 稳定；位置不同 → 不同。
要跨端一致必须移植 `getStringHash`（cyrb53，已有 Kotlin 实现）与 seedrandom 的 ARC4。

---

## 7. 跨语言对照

`tools/gen-macro-goldens.mjs` 的做法：把 ST 的
`MacroLexer` / `MacroParser` / `MacroEngine` / `MacroCstWalker` / `MacroRegistry` / `MacroFlags`
从源码**逐字抽到临时模块树**里，只重写 import 路径、把拖进 DOM 的 `MacroDiagnostics`
换成收集消息的桩，然后**原样运行真实引擎**生成 golden。

这不是「抄一份 JS 当标准」，而是生成时现场抽取 —— ST 升级后重跑就能发现行为漂移。

对照结果已抓出三处实现缺陷（都已修）：空白分隔的单参数、宏名大小写不敏感、参数类型校验。
