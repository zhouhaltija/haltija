# 交接文档

> 最后更新：2026-10-05
> 当前版本：`0.1.0`（应用名 `SillyApp`，**待更名为 `haltija`**——见[第 8 节](#8-待办与更名计划)）

---

## 1. 这是什么

SillyTavern 的 **Android 原生客户端**，纯 Kotlin 实现，**不使用 WebView、不内嵌 Node**。

SillyTavern 本身是一个 Node 服务 + 浏览器前端。它的服务端只做两件事：读写文件、转发 LLM 请求；
真正"有营养"的逻辑（世界书激活、宏引擎、Prompt 组装）全在前端 JS 里
（`world-info.js` 6408 行、`PromptManager.js` 2144 行）。所以本项目的做法是：
**把那些逻辑用 Kotlin 重写，只对齐 ST 的数据格式**，从而得到一个原生 App。

选择这条路的理由与另一条路（内嵌 Node）的对比，见：
- [`docs/architecture-options.md`](architecture-options.md) —— 方案总览
- [`docs/route-comparison.md`](route-comparison.md) —— Route A（纯 Kotlin）vs Route B（内嵌 Node）的实测数据

License 是 **AGPL-3.0**（与上游一致），见 [`LICENSE`](../LICENSE) 与 [`NOTICE`](../NOTICE)。

---

## 2. 当前进度

**359 项测试全绿**（`core-data` 95、`core-prompt` 191、`core-provider` 61、`app` 12 —— 数字会随提交变化，以 `./gradlew test` 为准）。
主干 8160 行 / 测试 5480 行。

| 模块 | 状态 | 说明 |
|---|---|---|
| `core-data` | ✅ | 角色卡（PNG `tEXt:chara`/`ccv3`、CharX、旧版 JSON v1）、`chats/*.jsonl`、`worlds/*.json`、`presets/*`、目录仓库层、时间戳格式 |
| `core-prompt` | ✅ | 世界书引擎、宏引擎（含全部内置宏）、Handlebars 渲染、`story_string` 管线、Prompt 组装 |
| `core-provider` | ✅ | SSE 解析、OpenAI 兼容 / Anthropic / Gemini 的请求构造与流式解释、传输编排 |
| `app` | 🟡 | 能聊天、能读 ST 数据目录；**缺角色列表页与聊天持久化** |

已产出的 APK：`dist/SillyApp-0.1.0.apk`（签名 release，13.5 MB，minSdk 26 / targetSdk 36）。

---

## 3. 目录结构

```
sillyApp/
├── android/                      Gradle 工程
│   ├── core-data/                纯 JVM：数据格式读写（不依赖 Android SDK）
│   ├── core-prompt/              纯 JVM：世界书 / 宏 / Prompt 组装
│   ├── core-provider/            纯 JVM：provider 与 SSE
│   └── app/                      Android：Compose UI + OkHttp + 存储实现
├── fixtures/                     由 ST 生成的数据样本 + 跨语言 goldens
├── tools/                        生成 fixtures 与 goldens 的脚本（Node）
├── docs/                         架构与算法规格
├── SillyTavern/                  上游检出（已 gitignore，建议改 submodule）
├── .toolchain/                   JDK 21 + Gradle 发行版 + 签名密钥（已 gitignore）
└── dist/                         分发的 APK（已 gitignore）
```

三个 core 模块都是**纯 JVM**（`org.jetbrains.kotlin.jvm`），不依赖 Android SDK。
这不只是洁癖：它让 90% 的逻辑能在几秒内跑完测试，且不需要模拟器。
`app` 只做三件事：UI、HTTP 传输、文件系统实现。

---

## 4. 核心验证手段：跨语言对照

**这是本项目最重要的方法论，接手的人一定要理解。**

「重写一份 ST」最大的风险是**测了个寂寞**——测试和实现出自同一个人的同一个理解，
理解错了就一起错，测试全绿也说明不了什么。

所以本项目的做法是：**把 ST 的真实实现逐字抽出来跑，用它的输出当期望值。**

| 对照对象 | 抽取方式 | 生成脚本 |
|---|---|---|
| 世界书激活 | 纯函数模块（`getStringHash`、`parseRegexFromString` 等） | `tools/gen-worldinfo-goldens.mjs` |
| 宏引擎 | 整棵模块树抽到 `SillyTavern/` 内的临时目录（让 `chevrotain` 能解析） | `tools/gen-macro-goldens.mjs` |
| Handlebars 渲染 | 用真实的 `handlebars` 包 | `tools/gen-handlebars-goldens.mjs` |
| 流式解析 | 抽 `getStreamingReply`，桩掉 3 个外部依赖 | `tools/gen-streaming-goldens.mjs` |
| 时间戳格式 | 抽 `humanizedDateTime` / `getMessageTimeStamp`，跨 4 个时区 | `tools/gen-time-goldens.mjs` |
| 数据样本 | 用 ST 自己的代码写出 PNG 卡 / jsonl / 预设 | `tools/gen-fixtures.mjs` |

**这套手段已经抓出 5 个真实 bug**（详见第 6 节）。接手后如果要改这些模块，
**先跑 `tools/` 里的生成脚本再生 golden，再改 Kotlin** —— 顺序反了就会把期望值改成自己的实现。

`tools/verify-fixtures.mjs` 是一道独立校验：16 项检查，确认 fixtures 本身没被改坏。

---

## 5. 已实现的关键语义（容易踩错的）

### 5.1 数据格式

| 项 | 要点 |
|---|---|
| 角色卡 PNG | `tEXt` 块，关键词 `chara`(V2) / `ccv3`(V3)；base64(UTF-8 JSON)；**V3 优先于 V2** |
| CharX | zip 里的 `card.json`（spec 字段必填）；支持自解压前缀；`embeded://` 等 URI 前缀 |
| 聊天文件 | 第 0 行是头部（现代格式 `user_name: "unused"`），其余是消息 |
| 聊天头部判定 | 首个对象**不含** `mes`/`is_user`/`is_system` 即为头部 |
| `chat_metadata.integrity` | uuidv4，**必须原样保留**，否则 ST 拒绝覆盖该文件（`chats.js:337`） |
| swipe 不变量 | `mes === swipes[swipe_id]`（`script.js:6790`） |
| 消息 `send_date` | ISO 8601 UTC，毫秒补 3 位（`RossAscends-mods.js:192`） |
| 聊天文件名 | `<角色名> - <yyyy-MM-dd@HHhmmsssSSSms>.jsonl`，用**本地时区**（`RossAscends-mods.js:169`） |
| 聊天子目录 | `chats/<头像文件名去扩展名>/` —— **不是角色显示名**（`chats.js:594`） |

**所有数据模型都以原始 `JsonObject` 为唯一真相来源**，只叠加类型化读取入口。
不做「解析成 Kotlin 对象再序列化回去」——ST 的卡和聊天里带着大量第三方字段，
丢了就再也找不回来。

### 5.2 世界书引擎

- `outlet` 容器的顺序是**降序**，其它容器是升序（照抄 ST，不改）
- `emEntries` 里 EMBottom 在 EMTop **之前**（因为用的是 `unshift`）
- 预算判断用 `>=` 然后 `continue`，**不是 break**
- `successfulNewEntries` **不计入预算**
- `scanDepth = 0` 永不匹配
- `delayUntilRecursion` 推进层级需要 **≥2 个不同层级**；只配一层且 `recursive = false` 则永不激活
- `vectorized` 字段在 ST 1.19.0 里是死代码

### 5.3 宏引擎

- 参数分隔：`:` = 一个参数（可含冒号），`::` = 多参数分隔，**空白也算一个参数**，逗号**不是**分隔符
- 宏名**大小写不敏感**
- 参数有类型校验（`strictArgs` 默认 true）：arity/类型不符时宏**原样返回**，不是返回空
- 未知宏原样返回；`\{\{` 在收尾处理时反转义
- `{{trim}}` 不是宏，是**后处理正则** `/(?:\r?\n)*\{\{trim\}\}(?:\r?\n)*/gi` → 吃掉换行

### 5.4 Handlebars ↔ ST 宏的桥

`public/scripts/macros.js:18-25` 注册了 `trim` helper（返回字面量 `{{trim}}`）与
`helperMissing`（返回 `substituteParams('{{name}}')`）——**未定义的模板变量会原样保留**，
交给后面的宏引擎处理。这是两套模板系统能共存的关键。

真值语义：`null`/`undefined`/`''`/`0`/`false`/`[]` 都是假（**空数组是假**，与 JS 不同）。
`{{#if true}}` 里的 `true` 是**字面量**（helper 参数位置），但独立变量 `{{true}}` 走 `helperMissing` 原样保留。

### 5.5 Prompt 组装

默认顺序**不能凭直觉猜**，抄自 `PromptManager.js:2088`：

```
main → worldInfoBefore → personaDescription → charDescription → charPersonality
→ scenario → enhanceDefinitions(默认关) → nsfw → worldInfoAfter
→ dialogueExamples → chatHistory → jailbreak
```

`worldInfoBefore` 在角色描述**之前**，`worldInfoAfter` 却在 `nsfw` **之后**，
`jailbreak`（角色卡 PHI）排在聊天历史**之后**。

**角色卡字段本身可能含宏**（描述、性格、场景里会有 `{{char}}`/`{{user}}`），
所以进组装器前必须先过宏引擎。

### 5.6 三家 provider 的差异

| | system 放哪 | 助手角色名 | 鉴权 | 其它 |
|---|---|---|---|---|
| OpenAI 兼容 | 一条普通 `system` 消息 | `assistant` | `Authorization: Bearer` | `max_tokens` / `stop` |
| Anthropic | **顶层 `system` 字段** | `assistant` | `x-api-key` | **相邻同角色消息必须合并**；`stop_sequences` |
| Gemini | **顶层 `systemInstruction`** | **`model`** | URL 查询参数 `key` | 模型名在 URL 路径；流式要 `alt=sse` |

思维链字段名各家不同：DeepSeek `delta.reasoning_content`、OpenRouter `delta.reasoning`、
Anthropic `delta.thinking`、**Gemini 是 `parts[]` 里 `thought: true` 的那些**。

---

## 6. 已修复的真实 bug（供参考，别再犯）

| # | 现象 | 根因 |
|---|---|---|
| 1 | 正则 `/abc/ii` 被静默接受 | JS 拒绝重复标志，Kotlin 的 `Set` 悄悄去重了 |
| 2 | `pattern.replace('\\/','/')` 只换第一处 | JS 的字符串 `replace` 传字符串时**非全局** |
| 3 | `{{#if true}}` 整块不渲染 | Handlebars 在 helper 参数位置把 `true`/数字当**字面量**，我当成了变量查找 |
| 4 | 端到端测试报「残留未替换的宏」 | 角色卡描述里**本来就有** `{{user}}`/`{{char}}`，是真实数据 |
| 5 | 中文回复出现乱码 | OkHttp 按字节读、逐块 UTF-8 解码，**多字节字符跨块被切碎**；必须用 `InputStreamReader` |

bug 3 和 5 特别值得注意：它们只在特定输入下出现（字面量分支、非 ASCII + 特定分片），
英文样本永远测不出来。

---

## 7. 构建与测试

### 环境准备（本机已就绪，换机器需要重做）

```bash
export JAVA_HOME="$PWD/.toolchain/jdk21/Contents/Home"
export GRADLE_USER_HOME="$PWD/.toolchain/gradle-home"    # 必须！沙箱内 Gradle 写不了 ~/.gradle
export ANDROID_USER_HOME="$PWD/.toolchain/android-home"  # 避免 AGP 往 ~/.android 写
```

JDK 21（Temurin 21.0.12.1，sha256 `44db0f08…`）与 Gradle 9.8.0 都在 `.toolchain/`。
Gradle wrapper 的 `distributionUrl` 指向腾讯镜像（GitHub / services.gradle.org 在国内太慢）。

### 命令

```bash
cd android
./gradlew test                       # 全部单测（不需要模拟器）
./gradlew :app:assembleDebug         # debug APK
./gradlew :app:assembleRelease       # 签名 release APK（需要 .toolchain/keystore.properties）

node tools/gen-fixtures.mjs          # 重新生成 fixtures
node tools/verify-fixtures.mjs       # 校验 fixtures 没被改坏（16 项）
node tools/gen-*-goldens.mjs         # 重新生成各类 goldens
```

`local.properties` 里的 `sdk.dir` 指向本机 Android SDK（已 gitignore）。

### 三个版本约束的坑（报错信息会误导人）

1. **AGP 9 自带 Kotlin 扩展**，Android 模块里**不能**再应用 `org.jetbrains.kotlin.jvm`，
   否则 `Cannot add extension with name 'kotlin'`。
2. **compileSdk 要用已安装的次版本号**：本机是 `android-36.1` + build-tools `36.1.0`，
   需显式写 `compileSdkMinor = 1` 与 `buildToolsVersion = "36.1.0"`。
3. **依赖版本会偷偷抬高 compileSdk 要求**：
   - Compose BOM `2026.09.00`（Compose 1.12.1）要 SDK 37 → 降到 `2026.02.00`（1.10.3）
   - OkHttp `5.x` 的 `okhttp-android` 变体也要更高 SDK → 降到 `4.12.0`
   - 报错是 `checkDebugAarMetadata` 列一堆依赖，**不会告诉你该降版本**

装了 SDK 37 之后可以把这两处升回去。

---

## 8. 待办与更名计划

### 8.1 【优先】更名为 haltija

产品名定为 **haltija**（芬兰神话里守护某处所/家宅的精灵，与「世界书守护者」的意象贴合）。
目标仓库：`git@github.com:zhouhaltija/haltija.git`

需要改动的地方：

| 项 | 从 | 到 |
|---|---|---|
| Gradle 根工程名 | `settings.gradle.kts` 里的 `rootProject.name` | `haltija` |
| Android namespace | `app.silly` | `app.haltija` |
| applicationId | `app.silly` | `app.haltija` |
| Kotlin 包名 | `app.silly.*` | `app.haltija.*` |
| 源码目录 | `app/src/main/kotlin/app/silly/` | `app/src/main/kotlin/app/haltija/` |
| 应用显示名 | `res/values/strings.xml` 的 `app_name` = `SillyApp` | `haltija` |
| 文档 | README / docs 里的 `SillyApp` | `haltija` |
| APK 文件名 | `dist/SillyApp-*.apk` | `dist/haltija-*.apk` |

**注意**：
- 改 `applicationId` 会让新包**装不成升级**（系统视为另一个应用）。目前是 0.1.0、
  还没人装过，是改名的好时机；改了之后要提醒测试者先卸载旧包。
- 更名**不涉及**数据格式，`core-*` 模块里除包名外无实质改动。
- 建议用一个提交专门做机械改名（`git mv` + `sed`），方便 review 与回滚。

命令参考（动手前先 `git status` 确认工作区干净）：

```bash
cd android
git mv app/src/main/kotlin/app/silly app/src/main/kotlin/app/haltija
git mv app/src/test/kotlin/app/silly   app/src/test/kotlin/app/haltija
# 再全局替换包名与显示名（core-* 模块内没有 app.silly，只有 app.silly.core.*）
grep -rl 'app\.silly' --include='*.kt' --include='*.kts' --include='*.xml' . | xargs sed -i '' 's/app\.silly/app.haltija/g'
```

### 8.2 功能待办

| 优先级 | 项 | 说明 |
|---|---|---|
| 高 | **角色列表页** | 目前只会用目录里按名字排序的第一个角色。网格 + 头像，点选切换 |
| 高 | **聊天持久化** | 消息只在内存里，退出即丢。`chats/*.jsonl` 的读写实现与测试都已就绪，只差接进 ViewModel |
| 高 | 新建/切换会话 | 文件名用 `ChatRepository.newChatFileName()`；**务必保留 `chat_metadata.integrity`** |
| 中 | swipe / 编辑 / 删除消息 | ST 的核心交互；注意 `mes === swipes[swipe_id]` 不变量 |
| 中 | 角色卡导入 | 从系统文件选择器导入 PNG / CharX |
| 中 | 预设界面 | `presets/*` 的读取已实现，缺 UI |
| 低 | 图片 / 多模态 | Gemini 的 inline 图片能解析出来，但没显示 |
| 低 | 角色卡加载缓存 | PNG 卡必须整份读入才能拿到名字与头像，100 个卡就是 50MB I/O |
| 低 | 流式时的前台服务 | 长回答切到后台容易被杀；Manifest 里权限已声明 |

### 8.3 已知的未实现 / 有意为之

- **不做 instruct 模板拍平**：ST 的 `formatInstructModeChat` 只对 text-completion 类后端有意义，
  本项目面向对话式接口（消息自带 role）。
- **不支持 Cohere / Mistral**：goldens 里有它们的用例，但没有 Kotlin 实现。
- **`{{pick}}` 宏**：需要引擎传入宏在文本中的**绝对偏移量**（ST 的种子链是
  `chatIdHash + contentHash + offset + rerollSeed` → `getStringHash` → seedrandom ARC4）。
  接口已预留，未实现。

---

## 9. 接手后的第一步建议

1. `cd android && ./gradlew test` —— 确认 359 项全绿，环境没问题
2. 读 [`docs/worldinfo-engine-spec.md`](worldinfo-engine-spec.md) 与
   [`docs/macro-engine-spec.md`](macro-engine-spec.md) —— 这两份是算法规格，比代码好读
3. 装一下 `dist/SillyApp-0.1.0.apk` 感受现状（记得先填 API Key）
4. 按 [8.1](#81-优先更名为-haltija) 做更名，再动功能

有疑问优先看 `tools/` 里的生成脚本 —— 它们说明了「期望值是从哪来的」。
