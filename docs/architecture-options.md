# SillyTavern → Android App 架构选型

> 结论先行：不要 fork SillyTavern，也不要把整个 ST 塞进 App。
> **UI 自己用 Kotlin + Compose 写，内核（角色卡/聊天/世界书/预设）也用 Kotlin 自己实现，只对齐 ST 的数据格式。**
> Node 运行时只在「后期想要扩展生态」时才作为可选模块引入。
>
> 依据见第 4 节（已按你的四个选择重新收敛）。

---

## 1. 先把 SillyTavern 拆开看

本仓库是 SillyTavern `1.19.0`（commit `06bde939f`）。它的实际构成：

| 层 | 内容 | 规模 |
|---|---|---|
| 服务端 | Express，45 个 `src/endpoints/*` 路由 | 纯 JS，**无 node-gyp 原生模块** |
| 前端 | `public/index.html` + `script.js` + `style.css` | 8231 + 12599 + 6464 行 |
| 前端模块 | `public/scripts/*`（world-info / PromptManager / chats…） | world-info 6408 行、PromptManager 2144 行 |
| 扩展 | `public/scripts/extensions/`（16 个内置 + third-party 生态） | 大量 jQuery/DOM 代码 |
| 运行时要求 | `engines.node >= 20`，CI 跑 Node 24 | — |

三个关键事实，决定了所有方案的形状：

1. **依赖里没有原生扩展**。`package-lock.json` 里只有 `protobufjs` 带 install script，无 `gypfile`；
   `tiktoken`、`jimp` 都是 WASM。也就是说 ST 服务端**理论上可以在移动端 Node 上跑**，不需要交叉编译。
2. **真正决定「交互逻辑」的代码在前端**。世界书激活、PromptManager 排序、聊天渲染与滚动、
   宏替换，几乎全在 `public/scripts/` 的浏览器 JS 里。服务端主要负责文件存储 + LLM 代理。
3. **生成接口已经是流式的**。`POST /api/backends/chat-completions/generate` 支持 `stream: true`
   并把上游 SSE 直接 pipe 回来，天生适合移动客户端。

> 所以「我不要简单的 WebView」这个诉求，翻译成工程语言就是：
> **前端要自己写**。问题只剩下——服务端放哪、放什么。

---

## 2. 三个候选方案

### 方案 A — 原生 App + 独立 ST 服务端（推荐起步）

ST 本体一行不改，照常跑在 PC / NAS / VPS 上；Android 端是**纯原生客户端**。

改造点只有一个：在 `plugins/` 下写一个 server plugin（`enableServerPlugins: true` 即可加载），
挂一组 `/api/mobile/*` 给客户端用。插件里可以直接 `import` ST 自己的模块
（`src/users.js`、`src/character-card-parser.js`、`src/png/`、`src/tokenizers/`），**逻辑零复制**。

插件要解决的问题是：ST 现有的 API 是「cookie session + CSRF + 面向网页」的，
直接给原生客户端用很别扭。桥接层提供：

```
POST /api/mobile/auth/token        换取长期 token（绕开 cookie/CSRF）
GET  /api/mobile/characters        角色列表，含解析后的卡字段 + 头像 URL
GET  /api/mobile/chats?char=:id    聊天分页 / 增量拉取
GET  /api/mobile/worldinfo         世界书 CRUD
POST /api/mobile/generate          SSE 流式生成（内部复用 ST 的 provider 代理与密钥管理）
POST /api/mobile/tokenize          走 ST 自带的 tokenizer
```

- ✅ 上游升级 = `git pull`，插件不受影响（不碰 ST 任何文件）
- ✅ 数据完全共用：同一个 `data/<handle>/` 目录，桌面端和手机端看到的是同一份角色卡/聊天/世界书
- ✅ 逃生舱：没移植的复杂功能（扩展、PromptManager 高级设置）随时可以「打开一次 ST 网页」兜底
- ⚠️ 需要一台常开的机器 / 内网穿透

### 方案 B — 原生 App + 设备内置 Node（真自包含）

用 `@capawesome/capacitor-nodejs`（2026-09 发布，基于 nodejs-mobile，后台线程跑 Node）
或 `nodejs-mobile` 把 Node 塞进 App，ST 的 `src/` 直接在里面启动，
原生 UI 通过 `127.0.0.1:8000/api/mobile/*` 访问（桥接插件同样要用）。

- ✅ 离线自包含，不需要额外机器
- ❌ **Node 版本墙**：nodejs-mobile 目前最高 `18.20.4`（capawesome fork 2026-07），
  ST 要求 `>= 20`。必须先做一次可行性验证，或自己 backport 启动路径。
- ❌ 常驻内存 150–400MB，要对抗 Android 后台查杀 / Doze / 省电策略
- ❌ 冷启动慢，进程死亡后要重建运行时

> 定位：**后期加一个 Local 模式**用，不适合当第一步。

### 方案 C — 完全原生重写（NativeTavern 路线）

Flutter/Kotlin 重写卡片、聊天、世界书、Prompt 组装、各家 provider。

- ✅ 最大控制力、最好性能、最好的交互动画
- ❌ 等于重做 ST 的核心复杂度（world-info 6400 行不是小数目）
- ❌ **彻底失去扩展生态**
- 📌 参考：[NativeTavern](https://github.com/miaoxworld/NativeTavern)，Dart + Rust，
  约 9 个月、112 star、25 fork，目前仍是 `v0.1.12`

只有当「你其实想做的是自己的 AI 角色聊天 App，只是兼容 ST 数据格式」时才选这条。

### 已存在的同类项目（可直接借鉴/避开）

| 项目 | 路线 | 状态 |
|---|---|---|
| [SillyTavernAndroid](https://github.com/doomedskull1011/SillyTavernAndroid) | 本地 Node + WebView UI | arm64，2026-09 仍在更新 |
| [NativeTavern](https://github.com/miaoxworld/NativeTavern) | Flutter 原生重写 | v0.1.12，活跃 |
| [@capawesome/capacitor-nodejs](https://www.npmjs.com/package/@capawesome/capacitor-nodejs) | Capacitor 内嵌 Node | 0.1.1，2026-09 |
| [capawesome-team/nodejs-mobile](https://github.com/capawesome-team/nodejs-mobile) | 维护中的 nodejs-mobile fork | Node 18.20.4 |

---

## 3. 推荐：A 起步，架构上为 B / C 留门

不把 A、B、C 当成互斥选择，而是**同一个 App 的可替换后端**：

```
┌─────────────────────────────────────────┐
│  UI 层   Jetpack Compose（100% 自己写）  │
├─────────────────────────────────────────┤
│  Domain 层  纯 Kotlin，无网络依赖         │
│    CharacterRepository / ChatRepository  │
│    PromptBuilder / WorldInfoEngine       │
├─────────────────────────────────────────┤
│  Data 层（可替换实现）                    │
│    RemoteStApi   ← 方案 A（先做这个）     │
│    EmbeddedNode  ← 方案 B（后期）         │
│    NativeEngine  ← 方案 C（渐进替换）     │
└─────────────────────────────────────────┘
```

只要 Domain 层不依赖 HTTP，A → B → C 就是**替换实现**，不是重写。

### 落地顺序

1. **工具链**：JDK 21 + Android Studio（当前机器只有 Android SDK 36.1，没有 JDK/Gradle/AS）
2. **桥接插件**：`st-mobile-bridge`，先只做 auth + characters + generate(SSE)
3. **最小可用 App**：角色列表 → 聊天页 → 流式输出 → 多会话切换
4. **Prompt 能力**：v1 只做「角色卡 + 历史 + 基础宏 + 世界书关键词激活 + 上下文预算」，
   其余交给服务端兜底；之后再逐步把 ST 的 world-info 语义对齐
5. **可选**：内置 Node 的 Local 模式；保留一个 WebView 入口给高级设置/扩展

### 必须守住的三条线

- **不 fork ST**。ST 以 submodule / 只读镜像存在，所有自定义代码在插件和自己的 App 仓库里。
- **数据格式严格对齐 ST**（`characters/*.png` 的 `tEXt:chara`、`chats/*.jsonl`、`worlds/*.json`）。
  能随时用桌面 ST 打开同一份 `data/`，这是最大的资产。
- **流式用 SSE，不要轮询**；同时处理 Android 后台断流与重连。

---

## 4. 按你的四个选择重新收敛：Node 应该拿掉

你的选择：**①完全离线自包含 ②Kotlin + Compose ③只要核心（卡/聊天/世界书/预设）④开源上架**

### 4.1 实测数据（本机）

在本仓库跑了 `npm install --omit=dev --ignore-scripts`：

| 指标 | 值 |
|---|---|
| `node_modules` | **348 MB** |
| 原生 `.node` 二进制 | **0 个**（纯 JS / WASM，无需交叉编译） |
| 最大几项 | onnxruntime-web 68MB、sillytavern-transformers 56MB、tiktoken 26MB |
| `src/` + `public/` | 18 MB + 26 MB |

Node 运行时：官方 nodejs-mobile 停在 **v18.20.4**（2024-10），而 ST 要 `>= 20`（CI 跑 24）。
第三方有 Node 22.9.0 的 Android libnode 预编译（arm64 压缩后 27.9MB），
但要自己用 [构建配方](https://github.com/fogtape/nodejs-mobile) 从 nodejs 官方源码重编，属于自建 CI 的活。

### 4.2 关键矛盾：内嵌 Node **给不了**你想要的东西

内嵌 Node 的唯一实质价值，是复用 ST 的逻辑。但：

- **ST 的服务端只做三件事**：读写 JSON 文件、代理 LLM 请求、tokenizer。
  用 Kotlin 重写大约 2000 行。为这点东西背 348MB 运行时不划算。
- **真正的「交互逻辑」在 ST 前端**（`world-info.js` 6408 行、`PromptManager.js` 2144 行），
  而前端是重度依赖 DOM 的 jQuery 代码。**光塞服务端 Node 拿不到它**——
  想跑它就得再来个隐藏 WebView 或 jsdom，等于把你想摆脱的东西又请回来。
- **扩展生态**是 Node 路线的另一半价值，但你已经选了「核心即可」。

也就是说：**在「原生 Compose UI + 只要核心」的前提下，内嵌 Node 的收益≈0，成本≈348MB+60MB 运行时。**

### 4.3 顺带要处理的三件事

1. **AGPL-3.0**。ST 是 AGPL。如果只是**对齐数据格式、独立实现**，你可以自选 MIT/Apache；
   如果**逐行移植** `world-info.js` 之类，那就是衍生作品，整个 App 必须 AGPL-3.0。
   你本来就要开源，最省心的做法是**直接给 App 上 AGPL-3.0 并注明参考了 SillyTavern**。
2. **Play 体积**。参考[应用大小上限](https://support.google.com/googleplay/android-developer/answer/9859372)：
   AAB 基础模块 200MB 上限，超出要走 asset pack / Play Asset Delivery。
   纯 Kotlin 内核的 APK 大约 15–30MB，基本不用管这条。
3. **动态代码加载**。随包分发的解释器（JS 引擎、模拟器）通常没问题；
   但**让用户在 App 里从网上下载 ST 扩展 JS 再执行**会踩
   [动态代码加载](https://developer.android.google.cn/privacy-and-security/risks/dynamic-code-loading)的红线。
   这又是一条「不要内嵌 Node」的理由。

### 4.4 修正后的推荐

```
┌──────────────────────────────────────────────┐
│  app            Compose UI（100% 自己写）      │
├──────────────────────────────────────────────┤
│  core-prompt    世界书激活 + Prompt 组装 + 宏  │  ← 自己实现，语义对齐 ST
│  core-data      PNG tEXt:chara / chats.jsonl  │  ← 自己实现，格式严格对齐 ST
│                  worlds/*.json / presets/*     │
│  core-provider  OpenAI 兼容 / Anthropic / Gemini + SSE
│  core-tokenizer jtokkit（Java 版 tiktoken）
├──────────────────────────────────────────────┤
│  domain         Engine 接口（可替换实现）      │
│    KotlinEngine        ← 现在就做这个           │
│    EmbeddedStEngine    ← 可选，后期想要扩展时   │
│    RemoteStEngine      ← 可选，想用桌面 ST 当后端│
└──────────────────────────────────────────────┘
```

同时把「数据目录」做成可导入导出（SAF）：用户可以把桌面 ST 的 `data/<handle>/`
直接指给 App，或在手机和 PC 之间用 Syncthing 同步同一份数据 —— 这是兼容性最实在的兑现方式。

### 4.5 里程碑

| 阶段 | 目标 | 验收 |
|---|---|---|
| M0 | JDK 21 + Gradle + 空 Compose 工程跑通 | 模拟器上能看到界面 |
| M1 | 数据层：解析 PNG 角色卡、读写 `chats/*.jsonl` | **用真实 ST `data/` 目录双向验证** |
| M2 | 聊天页 + 流式生成（OpenAI 兼容） | 能连续对话，断流可恢复 |
| M3 | 世界书激活 + Prompt 组装 + 预设组 | 与 ST 对同一张卡+同一本世界书输出一致 |
| M4 | 角色/会话管理、导入导出、备份 | 手机↔桌面数据互通 |
| M5 | 手势、主题、TTS（系统 TextToSpeech） | 你自己的交互习惯落地 |

