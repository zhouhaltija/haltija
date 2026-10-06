haltija
=======

> 产品名与 Android 包名已统一为 **haltija** / `app.haltija`。
> 目标仓库 `git@github.com:zhouhaltija/haltija.git`。

当前版本 **0.2.0**：角色网格与搜索、会话新建/切换、聊天保存与恢复。
安装包：`dist/haltija-0.2.0.apk`（签名 release）。旧 `app.silly` 与新版是不同应用，需重新配置连接并授权共享目录。

SillyTavern 的 Android 原生客户端。**纯 Kotlin 内核**，不使用 WebView，不内嵌 Node。

UI 与交互逻辑完全自己实现，仅严格对齐 SillyTavern 的**数据格式**，
以便与桌面端共用同一份 `data/` 目录。

- 技术栈：Kotlin + Jetpack Compose
- 许可：[AGPL-3.0](LICENSE)
- 上游：[SillyTavern](https://github.com/SillyTavern/SillyTavern)

---

## 为什么不用 WebView / 不用内嵌 Node

见 [docs/route-comparison.md](docs/route-comparison.md)。一句话版本：

> SillyTavern 的「交互逻辑」全在前端 JS 里（世界书 6408 行、PromptManager 2144 行），
> 服务端只做文件存储和 provider 转发。所以内嵌 Node 既省不掉你要写的代码，
> 还要多背 348MB 依赖和 229MB 常驻内存（均为实测值）。

---

## 目录结构

```
.
├── android/                 Gradle 根
│   ├── app/                 Android 壳 + Compose 界面
│   ├── core-data/           SillyTavern 数据格式读写
│   ├── core-prompt/         世界书引擎 + 宏 + Prompt 组装
│   └── core-provider/       LLM provider + SSE
├── fixtures/                测试基准：由 SillyTavern 自己生成的真实样本
├── tools/
│   ├── gen-fixtures.mjs     重新生成 fixtures
│   └── verify-fixtures.mjs  用 ST 自己的解析器校验 fixtures
├── docs/                   架构与选型文档
└── SillyTavern/             上游只读检出（不入库）
```

---

## 对齐的数据格式

| 数据 | 位置 | 格式 |
|---|---|---|
| 角色卡 | `characters/*.png` | PNG `tEXt` chunk，keyword `chara`(V2) / `ccv3`(V3)，内容为 base64(JSON)，**V3 优先** |
| 角色卡 | `characters/*.json` | JSON，V1 扁平 / V2,V3 带 `spec`+`data` |
| 角色卡 | `characters/*.charx` | zip，内含 `card.json` |
| 聊天 | `chats/<角色>/<名> - <时间>.jsonl` | 首行 metadata，其后每行一条消息 |
| 世界书 | `worlds/<书>.json` | `{ entries: { "<uid>": {...} } }` |
| 预设 | `presets/{context,instruct,sysprompt,reasoning}/*.json` | 各自独立 schema |

---

## 开发

### 依赖

- JDK 21（Android Studio 自带；本仓库也支持工作区内的本地 JDK，见下）
- Android Studio（含 Android SDK）—— 仅在构建 `app` 模块时需要
- Node.js ≥ 20 —— 仅用于生成 / 校验 fixtures，App 本身不需要

### 构建与测试

`core-data` 是纯 Kotlin/JVM 模块，**不需要 Android SDK**，有 JDK 就能跑：

```bash
cd android
./gradlew :core-data:test
```

### 构建 Android App

```bash
export JAVA_HOME="$PWD/.toolchain/jdk21/Contents/Home"
export GRADLE_USER_HOME="$PWD/.toolchain/gradle-home"
# AGP 会往 ~/.android 写分析数据；指到工作区内，避免动到用户目录
export ANDROID_USER_HOME="$PWD/.toolchain/android-home"
cd android

./gradlew :app:assembleDebug          # 产物在 app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # 管线自检（不需要模拟器）
```

`local.properties` 里的 `sdk.dir` 指向本机 Android SDK（该文件已 gitignore）。

打签名 release 包需要 `.toolchain/keystore.properties` 与密钥库；两样都已 gitignore。
缺了也能正常构建 debug —— 签名密钥不该进版本库。

```bash
./gradlew :app:assembleRelease    # 产物在 dist/ 之前先落在 app/build/outputs/apk/release/
```

#### 构建时踩到的三个坑（都已写进配置的注释里）

1. **AGP 9 自带 Kotlin 扩展**，Android 模块里**不能**再应用 `org.jetbrains.kotlin.jvm`，
   否则报 `Cannot add extension with name 'kotlin'`。
2. **compileSdk 要用已安装的次版本号**：本机 SDK 是 `android-36.1` + build-tools `36.1.0`，
   AGP 默认去找 `android-36` / `36.0.0` 并尝试下载（会因 SDK 目录不可写而失败）。
   显式写 `compileSdkMinor = 1` 与 `buildToolsVersion = "36.1.0"`。
3. **Compose BOM 要挑与 compileSdk 匹配的**：BOM `2026.09.00`（Compose 1.12.1）要求
   compileSdk ≥ 37，而本机最高只有 36.1，于是 AAR 元数据检查会列出 11 条错误。
   降到 `2026.02.00`（Compose 1.10.3）即可。
   **将来装了 SDK 37 就可以把这条升回去。**

### 本地工具链（可选）

如果不想等 Android Studio 装完，可以在工作区内放一套 JDK + Gradle，
它们都在 `.toolchain/` 下且已被 gitignore：

```bash
export JAVA_HOME="$PWD/.toolchain/jdk21/Contents/Home"
export GRADLE_USER_HOME="$PWD/.toolchain/gradle-home"   # 必须指到工作区内，否则 Gradle 写不了 ~/.gradle
cd android && ./gradlew :core-data:test
```

Gradle wrapper 的 `distributionUrl` 默认指向腾讯镜像（国内直连 services.gradle.org 很慢），
需要官方源时改 `android/gradle/wrapper/gradle-wrapper.properties`。

### 重新生成测试基准

fixtures 全部由 SillyTavern 自己的模块和 API 产出，不是手写的。
改动格式相关代码后请重新校验：

```bash
node tools/gen-fixtures.mjs      # 需要 SillyTavern/node_modules 已安装
node tools/verify-fixtures.mjs   # 退出码非 0 表示基准本身有问题
```

---

## 进度

| 模块 | 状态 | 验证方式 |
|---|---|---|
| 测试基准 `fixtures/` | ✅ | `verify-fixtures.mjs` 16 项（用 ST 自己的解析器交叉校验） |
| `core-data` 角色卡 | ✅ | 15 项；含 PNG 逐字节往返、V3 优先级、CharX |
| `core-data` 聊天 | ✅ | 14 项；含 jsonl 逐字节往返、integrity 保留 |
| `core-data` 世界书 | ✅ | 15 项；ST 自己的 `readWorldInfoFile` 交叉验证 |
| `core-data` 预设 | ✅ | 18 项；含「全字段」样本，防止键名写错后静默退回默认值 |
| `core-prompt` 正则键 | ✅ | 10 项**跨语言一致性**；golden 由 ST 源码逐字抽取后运行生成 |
| `core-prompt` 字符串哈希 | ✅ | 4 项跨语言一致性（cyrb53，计时效果的身份标识） |
| `core-prompt` 扫描缓冲 | ✅ | 27 项；覆盖 `scanDepth=0`、整词边界、递归缓冲可见性等反直觉行为 |
| `core-prompt` 世界书引擎主循环 | ✅ | 49 项；含递归、层级递进、分组锁死、预算溢出等 |
| `core-prompt` 计时效果 | ✅ | sticky / cooldown / delay，含「sticky 到期转 protected 冷却」 |
| `core-prompt` 分组与评分 | ✅ | groupOverride 优先、加权随机、已激活组锁死 |
| `core-prompt` 预算 | ✅ | `>=` 判定、`ignoreBudget` 放行、`budgetCap` 封顶 |
| `core-prompt` Handlebars 子集 | ✅ | 13 项**跨语言一致性**；74 条 golden 由真实 Handlebars + ST 的桥接 helper 生成 |
| `core-prompt` story_string 管线 | ✅ | 17 项；四步管线（模板→宏→去首换行→补尾换行）逐项覆盖 |
| `core-prompt` 宏引擎（语法+求值+回退） | ✅ | 15 项**跨语言一致性**；golden 由真实引擎抽取后运行生成 |
| `core-prompt` 块宏与内置宏 | ✅ | 35 项；含 `{{if}}` 分支/取反/变量简写/嵌套 else、变量、时间、随机 |
| `core-prompt` `{{pick}}` 确定性选择 | ⬜ | 需要引擎透传宏在文本中的绝对偏移 |
| `core-prompt` Prompt 组装 | ✅ | 17 项；默认顺序由 fixture 从 `PromptManager.js` 原文抽取后断言 |
| `core-prompt` **端到端链路** | ✅ | 4 项：真实角色卡 + 世界书 → 宏 → 故事串 → 组装 → 成品消息列表 |
| `core-provider` SSE 解析 | ✅ | 19 项；重点覆盖**任意分片**（切在 `data:` 与冒号之间也要正确） |
| `core-provider` 流式解释器 | ✅ | 10 项**跨语言一致性**；golden 由 ST 的 `getStreamingReply` 抽取后运行生成 |
| `core-provider` 请求构造 | ✅ | 20 项；覆盖三家在 system 位置 / 角色名 / 鉴权头 / 消息合并上的差异 |
| `core-provider` 传输契约与编排 | ✅ | 12 项；用假客户端跑完整流式流程，含分片、错误、非流式 |
| `app` OkHttp 传输实现 | ✅ | 读超时而非总超时；错误正文原样带出；UTF-8 增量解码 |
| `app` 聊天界面 | ✅ | 可真的收发：设置 → 组装 → 流式渲染；停止或断流保留已收内容 |
| `app` 管线自检屏 | ✅ | 把内核每一步的产物摊开显示 |
| `core-data` 存储层 | ✅ | 20 项；仓库布局对齐 ST（聊天目录用头像名，不是显示名）|
| `app` 目录实现 | ✅ | `java.io.File` 与 SAF 两种；SAF 可直指桌面 ST 的 `data/default-user/` |
| `app` 角色列表 | ✅ | PNG 头像网格、名字搜索与角色切换 |
| `app` 会话管理 | ✅ | 新建/切换、按目录和角色记住最近会话、重启恢复 |
| `app` 聊天持久化 | ✅ | 原始 JSON 追加写回，保留 integrity/未知字段；保存失败可重试 |
| `app` 生成收尾与传输 | ✅ | 17 项新增单测；完整回复/取消/断流落盘、阻塞读可取消 |
| `app` 打包 | ✅ | 0.2.0 debug / 签名 release APK，产物在 `dist/` |

**合计 376 项测试全绿**，另加 fixtures 的 16 项交叉校验。

### 交接文档

[`docs/handover.md`](docs/handover.md) —— 项目全貌、已验证的语义、踩过的坑、待办与更名计划。
接手请从这里开始。

### 关于「跨语言一致性测试」

数据层的验证据说还算硬（逐字节往返），但世界书引擎没法这么验 —— ST 自己的测试套件里
没有世界书测试，`checkWorldInfo` 又依赖大量 DOM 全局量，没法直接跑。

所以对引擎里**可以独立运行的纯函数**（`parseRegexFromString`、`getStringHash`），
`tools/gen-worldinfo-goldens.mjs` 会在生成时**从 ST 源码里按括号配平逐字抽取**该函数、
运行它、把结果写成 golden，再由 Kotlin 测试逐一比对。

抽取而不是手抄，是为了避免「自己测自己」：ST 升级后重跑一次就能发现行为漂移。

这套做法立刻抓出了三处 JVM 与 JS 的正则语义分歧，其中两处已修（重复修饰符、
`String.replace` 非全局），一处登记为已知分歧并附原因；若将来分歧消失，测试会失败提醒更新文档。

### 设计约束（贯穿全部数据模型）

1. **原始 JSON 是唯一真相来源。** 所有模型（`CharacterCard` / `ChatMessage` / `WorldInfoEntry`）
   都包裹一个 `JsonObject`，只叠加类型化读取入口，不重新编码。
   原因：这些格式里有大量第三方字段（`extensions`、`extra`、未来版本新增的键），
   强类型模型解码再编码会静默丢数据 —— 那等于损坏用户的卡和聊天记录。
2. **写回保护。** 私有目录先写临时文件并同步，再 `ATOMIC_MOVE`；SAF 覆盖前完成备份，
   中断时可从备份恢复。DocumentsProvider 的实际行为需真机验证。
3. **`chat_metadata` 必须原样保留。** 里面的 `integrity` 是 ST 的完整性校验串，
   丢了或改了，ST 会拒绝覆盖该聊天文件。


---

## 许可与致谢

本项目以 **AGPL-3.0** 发布，与上游 SillyTavern 保持一致。
数据格式的实现参考了 SillyTavern（AGPL-3.0），详见 [NOTICE](NOTICE)。
