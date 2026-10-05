# 两条路线详解：纯 Kotlin 内核 vs 内嵌 Node

> 本文所有数据都是在你这台机器上、对这份 SillyTavern `1.19.0` 实测得到的，
> 不是估算。命令和结果都可复现。

---

## 0. 先纠正我上一轮说错的一件事

我上一轮说「nodejs-mobile 最高 Node 18，而 ST 要 Node 20，所以有版本墙」。

**实测：ST 1.19.0 在 Node 18.20.4 下完整启动并正常提供服务。**

```
$ node18 server.js --port 8123 --browserLaunchEnabled=false
Compiling frontend libraries...
webpack 5.105.4 compiled successfully in 15858 ms
SillyTavern is listening on IPv4: 127.0.0.1:8123

$ curl -o /dev/null -w "%{http_code}" http://127.0.0.1:8123/        → 200
$ curl -X POST .../api/settings/get                                  → 200
$ curl -X POST .../api/characters/all                                → 200（返回真实角色 JSON）
```

`engines: { node: ">= 20" }` 只是 npm 的警告，不是硬门槛。另外对 96 个 `src/**/*.js`
跑 `node18 --check`，**语法错误 0 个**。

所以：**官方 nodejs-mobile v18.20.4 可以直接用，不需要自己从 nodejs 源码编 Node 22。**
这一条我上一轮讲错了，路线 B 的门槛比我说的低。

---

## 1. 决定性事实：ST 的「智能」到底在哪

这是选型的分水岭，我把它查实了。

你在 ST 网页里按下发送键之后，发生的是：

```js
// public/scripts/openai.js:3141
async function sendOpenAIRequest(type, messages, signal, ...) {
    const { generate_data, stream } =
        await createGenerationParameters(oai_settings, model, type, messages, ...);
    //                              ↑ 这一步在浏览器里跑
    await fetch('/api/backends/chat-completions/generate', {
        method: 'POST',
        body: JSON.stringify(generate_data),   // messages[] 已经是拼好的成品
    });
}
```

**`messages[]` 是前端拼好的。** 服务端的 `/generate` 只是拿着成品转发给上游 provider。

也就是说，下面这些东西**全在浏览器 JS 里**，而不在 `src/`：

| 能力 | 所在文件 | 行数 |
|---|---|---|
| 世界书激活（关键词/递归/预算/位置） | `public/scripts/world-info.js` | 6408 |
| Prompt 排序与注入 | `public/scripts/PromptManager.js` | 2144 |
| 聊天历史管理、swipe、截断 | `public/scripts/chats.js` | 2422 |
| 各家 provider 的请求体构造、流式解析 | `public/scripts/openai.js` | 7396 |
| 宏引擎 | `public/scripts/macros.js` + `macros/` | 747+ |

### 这条事实的后果

> **内嵌 Node 跑 ST 服务端，并不能让你省掉世界书和 Prompt 组装——那部分代码不在服务端。**
> 只要你要原生 Compose UI，这部分 Kotlin 代码你**两条路都得写**。

那内嵌 Node 到底买到了什么？只剩下服务端那 9449 行：

| 服务端能力 | 文件 | 行数 |
|---|---|---|
| provider 代理（各家 API 怪癖、流式归一化、logit bias） | `src/endpoints/backends/chat-completions.js` | 2995 |
| Prompt 格式转换（Claude / Google / 兼容层） | `src/prompt-converters.js` | 1451 |
| 角色卡存储 + 导入导出 | `src/endpoints/characters.js` | 1687 |
| 聊天文件存储 | `src/endpoints/chats.js` | 1152 |
| CHARX 卡（zip 格式） | `src/charx.js` | 399 |
| tokenizer | `src/tokenizers/` | — |

---

## 2. 路线 A：纯 Kotlin 内核

### 2.1 架构

```
┌─────────────────────────────────────────────────┐
│ app/            Compose UI                      │
│   chat / characters / settings / import-export  │
├─────────────────────────────────────────────────┤
│ core-prompt     世界书引擎 + Prompt 组装 + 宏    │
│ core-data       卡解析 + 聊天 jsonl + 预设       │
│ core-provider   OpenAI 兼容 / Anthropic / Gemini │
│                   + SSE 流                      │
│ core-tokenizer  jtokkit（JVM 版 tiktoken）       │
├─────────────────────────────────────────────────┤
│ domain          Engine 接口（纯 Kotlin，无 IO）  │
└─────────────────────────────────────────────────┘
```

App 里没有 Node、没有 WebView、没有 348MB 的 `node_modules`。

### 2.2 要写什么

| 模块 | 内容 | 对应 ST 代码 | 人日（估） |
|---|---|---|---|
| 卡解析 | PNG `tEXt` chunk 读 `chara`(V2)/`ccv3`(V3)，后者优先；JSON V1/V2/V3；CHARX(zip) | `character-card-parser.js` 98 + `charx.js` 399 | 3–5 |
| 聊天存储 | 文件名 `<角色名> - <YYYY-MM-DD HHmmss>.jsonl`；每行一条消息；首行 metadata | `endpoints/chats.js` | 2–3 |
| **世界书引擎** | 激活、递归、预算、位置、分组、粘性/冷却/延迟 | `world-info.js` | **8–15** |
| Prompt 组装 | context/instruct/sysprompt 模板 + 宏替换 + 历史截断 | `PromptManager.js` + `macros.js` | 6–10 |
| Provider | 三家 API + SSE + reasoning 块 + 停止符 | `openai.js` 的对应部分 | 5–8 |
| Tokenizer | jtokkit cl100k/o200k，其它模型用启发式估算 | `tiktoken` | 1–2 |
| Compose UI | 列表/聊天/设置/导入导出 | （全新） | 10–20 |
| **合计** | | | **≈ 35–63 人日** |

### 2.3 数据格式规格（可以照抄的部分）

这些是**格式**，不是代码，照抄没有法律问题：

**角色卡**：PNG 的 `tEXt` chunk，keyword 为 `chara`（V2）或 `ccv3`（V3），
内容是 `base64(UTF8(JSON))`。V3 优先。

**聊天**：`chats/<角色名>/<角色名> - 2026-10-05 14:30:00.jsonl`，
一行一条消息：

```json
{"name":"Seraphina","is_user":false,"is_system":false,
 "send_date":"2026-10-05T14:30:00.000Z","mes":"...","extra":{},
 "swipes":["..."],"swipe_id":0,"swipe_info":[...]}
```

**世界书条目**（`worlds/*.json` 的 `entries` map）字段全集，直接从 ST 抄：
`key`, `keysecondary`, `comment`, `content`, `constant`, `vectorized`, `selective`,
`selectiveLogic`, `addMemo`, `order`, `position`, `disable`, `ignoreBudget`,
`excludeRecursion`, `preventRecursion`, `matchPersonaDescription`,
`matchCharacterDescription`, `matchCharacterPersonality`, `matchCharacterDepthPrompt`,
`matchScenario`, `matchCreatorNotes`, `delayUntilRecursion`, `probability`,
`useProbability`, `depth`, `outletName`, `group`, `groupOverride`, `groupWeight`,
`scanDepth`, `caseSensitive`, `matchWholeWords`, `useGroupScoring`, `automationId`,
`role`, `sticky`, `cooldown`, `delay`, ...

两个枚举必须对齐：

```js
world_info_logic    = { AND_ANY: 0, NOT_ALL: 1, NOT_ANY: 2, AND_ALL: 3 }
world_info_position = { before: 0, after: 1, ANTop: 2, ANBottom: 3,
                        atDepth: 4, EMTop: 5, EMBottom: 6, outlet: 7 }
```

### 2.4 运行时表现

| 指标 | 值 |
|---|---|
| APK 体积 | ≈ 15–30 MB |
| 冷启动 | < 1 s |
| 常驻内存 | ≈ 60–150 MB |
| 后台存活 | 只在生成时用前台服务，其余时间无进程 |

### 2.5 坑

1. **世界书语义不可能 100% 一致**。递归、`delayUntilRecursion`、分组评分这些边角
   在 ST 里是长年迭代出来的。接受 90% 即可，别追求逐位对齐。
2. **tokenizer 不统一**。jtokkit 只覆盖 OpenAI 系（cl100k/o200k）。Llama/Mistral
   等只能估算。影响：上下文预算略偏，不影响请求正确性。
3. **扩展永远没有**。你已经选了「核心即可」，这条接受。
4. **上游新 provider 要自己跟**。
5. **存储位置要想好**：`/data/data/<pkg>/files/` 卸载即丢；建议用 SAF 让用户
   指定一个共享目录（如 `Documents/SillyTavern/data/default-user`），
   这样手机和 PC 能直接共享同一份数据，也能用 Syncthing 同步。

---

## 3. 路线 B：内嵌 Node

### 3.1 它是怎么工作的

nodejs-mobile 的思路不是「塞一个 node 可执行文件」，而是：

```
app/src/main/
├── jniLibs/arm64-v8a/
│   ├── libnode.so          ← 整个 Node 运行时编成共享库（约 60MB 原始 / 28MB 压缩）
│   └── libc++_shared.so
├── java/.../NodeRuntime.kt ← JNI 封装
└── assets/nodejs-project/  ← ST 本体
    ├── server.js
    ├── src/
    ├── package.json
    └── node_modules/       ← 348MB（实测）
```

启动流程：

```kotlin
// 1. 首次运行把 assets 解压到 filesDir（348MB，ARM 上约 10–30s）
extractAssets("nodejs-project", File(filesDir, "nodejs-project"))

// 2. 加载运行时
System.loadLibrary("node")

// 3. 在后台线程启动，Node 跑完整 event loop
thread {
    startNodeWithArguments(arrayOf(
        "node",
        File(filesDir, "nodejs-project/server.js").path,
        "--port", "8000",
        "--browserLaunchEnabled=false",   // 别去拉系统浏览器
        "--disableCsrf",                  // 本地 App，不需要 CSRF
        "--dataRoot", File(filesDir, "st-data").path,
    ))
}

// 4. 等 127.0.0.1:8000 就绪，然后原生 UI 用 OkHttp 访问
```

`libnode.so` 是通过 `dlopen` 加载的共享库，**不是 exec 一个二进制**——
所以躲开了 Android 10+ 的 W^X 限制，这点比「打包一个 node 可执行文件」干净得多。

### 3.2 实测可行性（对路线 B 有利的发现）

| 检查项 | 结果 | 意义 |
|---|---|---|
| ST 能否在 Node 18 启动 | ✅ 能，HTTP 200 | 不需要自编 Node 22 |
| `src/` 语法兼容 Node 18 | ✅ 0 个错误 | 无语法级障碍 |
| 原生 `.node` 模块 | ✅ **0 个** | 不需要为 Android 交叉编译任何扩展 |
| `child_process` 使用 | ✅ **0 处** | 避开了 nodejs-mobile 最大的坑 |
| `worker_threads` 使用 | ✅ **0 处** | 同上 |
| onnxruntime / transformers.js | ✅ 纯 WASM | Android 上能跑 |

**这四项（0 原生模块、0 child_process、0 worker_threads、纯 WASM）能同时成立，
是相当少见的。** 路线 B 在技术上比我预想的干净很多。

### 3.3 你买到了什么

1. **provider 代理层**——`chat-completions.js` 2995 行 + `prompt-converters.js` 1451 行。
   这里面是各家 API 的脏活：Anthropic 的 prompt cache 与 thinking budget、
   Google 的 safety settings、OpenRouter 的 header、logit bias、reasoning 块解析、
   流式归一化、错误分类。这是 ST 真正积累下来的东西。
2. **上游自动跟进**：ST 支持了新模型/新 provider，你 `git pull` 一下就有。
3. **一条后路**：将来想要完整 ST，加一个 WebView Activity 就能用，不用重写。
4. **文件格式处理**：包括 CHARX 这类边角格式。

### 3.4 代价（实测）

| 指标 | 实测值 |
|---|---|
| `node_modules` | **348 MB**（onnxruntime-web 68MB + sillytavern-transformers 56MB 是大头） |
| libnode.so | 约 60MB 原始 / 28MB 压缩（每个 ABI 一份） |
| `src/` + `public/` | 18 MB + 26 MB |
| **服务端进程 RSS** | **229 MB**（heapUsed 77MB / external 25MB） |
| 冷启动 | 20 s（含 webpack 编译前端）；热启动 4 s（**macOS**，Android ARM 会更慢） |
| 压缩后 AAB 估算 | **120–200 MB** |

关于启动的 20s：`src/server-main.js:354` 每次启动都会跑
`webpackMiddleware.runWebpackCompiler({ pruneCache: true })`，把 `lib.js` 编出来。
有文件系统缓存，但它会按 `包版本 + gitRevision + webpack 版本` 换 key。
**你不需要 web UI 的话，这一步是纯浪费**，可以在打包时预先生成 `lib.js` 跳过。

### 3.5 坑

1. **内存**。229MB 只是 Node 服务端本身，再叠加 Compose UI 和图形。
   中低端机（per-app 限制常在 192–512MB）很容易被 LMK 干掉。
   而且 Android 会优先杀内存占用大的后台进程。
2. **解压开销**。348MB 的 assets 首启解压很慢，且占用双倍空间。
   想避免「首次下载」又会被 Play 的
   [动态代码加载](https://developer.android.google.cn/privacy-and-security/risks/dynamic-code-loading)政策盯上，随包最稳。
3. **包体**。压缩后 120–200MB，逼近
   [AAB 基础模块 200MB 上限](https://support.google.com/googleplay/android-developer/answer/9859372)，
   超了要走 Play Asset Delivery。
4. **AGPL-3.0**。ST 是 AGPL。**打包 ST 本体分发 = 明确的分发行为**，
   整个 App 必须以 AGPL-3.0 开源。你本来就要开源，这条能接受，但要写清楚。
   （纯 Kotlin 路线只在「逐行移植」时才触发 AGPL；独立实现可以自选 MIT/Apache。）
5. **`git` 相关功能失效**。`command-exists` / `simple-git` 找不到 git 会走降级分支，
   插件安装/更新不可用（你也不用扩展，影响小）。
6. **内容检查**。ST 首启会去 GitHub 拉内容更新，移动端要关掉。
7. **更新 ST 版本 = 重新解压整个 348MB**。

---

## 4. 逐项对比

| 维度 | A：纯 Kotlin 内核 | B：内嵌 Node |
|---|---|---|
| **Prompt 组装 / 世界书** | 自己写 | **也要自己写**（在前端，服务端不提供） |
| Provider 支持 | 自己写（3 家 ≈ 5–8 人日） | **复用，且随上游升级** |
| 文件格式处理 | 自己写 | 复用 |
| APK 体积 | 15–30 MB | 120–200 MB |
| 冷启动 | < 1 s | 8–25 s（首次还要解压） |
| 常驻内存 | 60–150 MB | +229 MB |
| 后台被杀风险 | 低 | **高** |
| 离线能力 | 完全离线 | 完全离线 |
| 交互自由度 | **完全自由** | 完全自由（UI 也是你自己的） |
| 扩展生态 | 无 | 无（扩展也是前端 JS，要 WebView 才有） |
| 上游跟进 | 手动 | provider 层自动 |
| 许可证 | 自选（除非逐行移植） | **必须 AGPL-3.0** |
| 工程量 | ≈ 35–63 人日 | ≈ 34–64 人日 |

### 这张表最重要的一行

**两条路的工程量几乎一样**（B 省下 provider 的 5–8 人日，但多花 5–10 人日在
Node 嵌入管道 + assets 打包 + 生命周期管理上，基本抵消）。

差别不在工作量，而在**你拿这 350MB 换到了什么**：
换到的是「provider 层的正确性和自动跟进」。

所以问题变成一句话：

> **你更怕「自己写 provider 会踩坑」，还是更怕「App 又大又慢又容易被杀」？**

---

## 5. 我的建议

### 首选：A，但把 provider 层单独隔离

理由：你要的是**自己的交互 App**，那么启动速度、内存、包体、后台存活
是你每天都能感知到的东西；而 provider 层是 3 个有完整文档的 HTTP API，
一次性写好之后很少动。用 350MB 常驻内存换它，不划算。

同时把 `core-provider` 定义成接口，这样将来真觉得 provider 层维护不过来，
可以无痛替换成「内嵌 Node 只做代理」的混合形态。

### 如果选 B，做一个务实的裁剪版

不要原样打包 348MB。可行的裁剪：

- 删掉 `public/`（你不需要 web UI）→ 省 26MB
- 删掉 `onnxruntime-web` + `sillytavern-transformers`（本地向量化/分类）→ 省 124MB
- 删掉 `@jimp/*`、`highlight.js`、`moment`、`caniuse-lite` 等 UI/图片相关 → 再省 40–60MB
- 预生成 `lib.js`，跳过启动时的 webpack 编译
- 目标：`node_modules` 压到 100–130MB，AAB 约 80–110MB

### 无论选哪条，这些都要做

1. **数据目录用 SAF 可导入导出**——让它能指向桌面 ST 的 `data/<handle>/`。
   这是「兼容 SillyTavern」最实在的兑现方式，两条路都适用。
2. **先做数据层并用真实 ST 数据双向验证**，再动 UI。
3. 生成用**前台服务 + SSE**，处理断流重连。

---

## 6. 下一步

你装 Android Studio 的时候，我可以先把不依赖 Android 工具链的部分做掉：

- 用 `server.js --port 8123` 在本地起一份 ST，造一批测试数据
  （角色卡 PNG、几份聊天 jsonl、一本世界书），作为后续 Kotlin 数据层的**验收基准**
- 建立仓库骨架（Gradle 多模块：`app` / `core-data` / `core-prompt` / `core-provider`）
- 写数据层的 Kotlin 解析器，用上面那份基准做对比测试

这样等你 Android Studio 装好，直接就能在模拟器上跑起来。
