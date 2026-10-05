# fixtures — 测试基准

这里的文件**不是手写的**，全部由 SillyTavern 自己的模块或 HTTP API 生成，
再用 SillyTavern 自己的解析器校验过（`tools/verify-fixtures.mjs`，11 项全过）。

这样做的原因：Kotlin 数据层的唯一验收标准是「和 SillyTavern 读出来一样」。
如果基准本身是「我以为的格式」，那测试只是自证循环。

```
node tools/gen-fixtures.mjs      # 重新生成
node tools/verify-fixtures.mjs   # 校验（退出码 0 = 通过）
```

---

## characters/

| 文件 | 内容 | 测什么 |
|---|---|---|
| `seraphina-v3.png` | 同时含 `chara` + `ccv3` 两个 tEXt chunk，内容一致 | 标准 V3 卡的读取 |
| `seraphina-v2.png` | 只有 `chara` chunk，`spec = chara_card_v2` | V2 卡的读取 |
| `precedence-test.png` | `chara` 里是 V2 卡（name = `WRONG_chara_chunk`），`ccv3` 里是 V3 卡（name = `RIGHT_ccv3_chunk`） | **优先级**：必须选 `ccv3`，选错会读到 WRONG |
| `legacy-v1.json` | 扁平 JSON，无 `spec` 无 `data` | 老式 V1 卡的降级处理 |
| `charx-test.charx` | zip，含 `card.json` 与内嵌 `assets/icon/main.png` | CHARX 归档的读取与资源解出 |

> PNG 样本取自 SillyTavern 自带的 `default/content/default_Seraphina.png`，
> 所以是尺寸、chunk 布局都真实的卡，不是构造出来的一像素图。

## chats/default_Seraphina/

| 文件 | 内容 | 测什么 |
|---|---|---|
| `fixture-basic.jsonl` | 现代 ST 头部（`user_name`/`character_name` 均为字面量 `"unused"`，无 `create_date`）+ 3 条消息，最后一条带 3 个 swipe | 标准读写、swipe 结构 |
| `fixture-imported.jsonl` | 导入式头部（真实名字 + `create_date`）+ `is_system` 隐藏消息、带 `force_avatar` 的用户消息 | 老格式头部的兼容、边角字段不丢 |
| `fixture-integrity.jsonl` | `chat_metadata` 含 `integrity` UUID + `custom_flag` + `attachments` + `note_prompt` | **完整性校验串与自定义 metadata 必须原样保留** |

### 关于 `integrity`

`public/script.js:7666` 在加载聊天时若发现 `chat_metadata.integrity` 缺失，会补一个 uuidv4；
保存前服务端会比对（`src/endpoints/chats.js:337`）。**如果客户端写回时把它丢了或改了，
SillyTavern 会判定完整性校验失败并拒绝覆盖该聊天文件。**
这是「无损保留 metadata」这条设计约束的直接来源，`fixture-integrity.jsonl` 就是它的守卫用例。

### 关于头部

头部有两种写法，**都必须能读**：

```jsonc
// ① 现代（public/script.js:7428）——注意 "unused" 是字面量，不是真名
{"chat_metadata":{},"user_name":"unused","character_name":"unused"}

// ② 导入式 / 老版本（src/endpoints/chats.js:839 接受 user_name / name / chat_metadata 任一）
{"user_name":"User","character_name":"Seraphina","create_date":"...","chat_metadata":{}}
```

ST 前端加载时**无条件**把第一行当头部（`data.shift()`），这对第一行就是消息的文件会误吃一行。
Kotlin 实现改用更稳的判据：第一行是对象且**不含** `mes`/`is_user`/`is_system` 才算头部。

目录名 `default_Seraphina` 是真实的：SillyTavern 用头像文件名（去掉 `.png`）作为聊天子目录名。

**关键不变量**（`public/script.js:6790`）：

```
mes === swipes[swipe_id]
swipes.length === swipe_info.length
```

生成器最初写的样本违反了这个不变量，被校验器抓出来，现在已修正。

## worlds/

`fixture-book.json` — 一本 4 条目的世界书，覆盖：

| uid | 覆盖点 |
|---|---|
| 0 | `constant: true`，无条件注入 |
| 1 | `selective` + `keysecondary` + `selectiveLogic: AND_ANY` + `scanDepth` + `matchWholeWords` |
| 2 | 正则键 `/whisper(s)?/i` + `position: atDepth(4)` + `sticky` + `cooldown` + `depth` |
| 3 | `disable: true`，**永远不应被激活**（负向用例） |

## presets/

从 SillyTavern 默认预设各取 2 个：`context/` `instruct/` `sysprompt/` `reasoning/`。
它们 schema 各不相同，用于验证不会互相串用。

## expected/

黄金文件。Kotlin 解析器的输出必须与这里**逐字段完全一致**（`deepEqual`，不是抽查几个字段）。

`precedence-ccv3-wins.json` 是 `precedence-test.png` 的正确答案——
注意它的 `data.name` 是 `RIGHT_ccv3_chunk`。

## MANIFEST.json

生成时间与全部文件清单（含字节数），由生成器自动写出。
