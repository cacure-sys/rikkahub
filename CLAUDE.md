# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 编译流程（重要）

- **不要在本地编译验证。** 本地 gradle wrapper 会尝试下载 `gradle-9.5.0-bin.zip`，但被本机 SSL 证书拦截（`PKIX path building failed`，`services.gradle.org` 证书验证失败），下载必失败。
- 本项目通过 **推到 GitHub 用 Actions 编译** 来验证构建。改动写完后直接提交/推送，由 CI 跑编译与打包（release/debug APK）。
- 本地只做静态检查（grep/读代码），不要运行 `./gradlew`。
- **CI 绿了才算完。** 本地静态检查只能覆盖括号平衡 / 符号是否存在 / 符号的作用域链，
  编译器的活只能交给 CI——这一点已经被打脸两次。具体踩过的坑见文末「踩坑记录」。

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Architecture Overview

### Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

### Key Technologies

- **Jetpack Compose**: Modern UI toolkit
- **Koin**: Dependency injection
- **Room**: Database ORM
- **DataStore**: Preferences storage
- **OkHttp**: HTTP client with SSE support
- **Navigation 3**: App navigation
- **Kotlinx Serialization**: JSON handling

### Core Packages (app module)

- `data/`: Data layer with repositories, database entities, and API clients
- `ui/pages/`: Screen implementations and ViewModels
- `ui/components/`: Reusable UI components
- `di/`: Dependency injection modules
- `utils/`: Utility functions and extensions

### Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT, SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables users to regenerate responses and switch between different conversation branches, creating a tree-like conversation structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Development Guidelines

### UI Development

- Follow Material Design 3 principles
- Use existing UI components from `ui/components/`
- Reference `SettingProviderPage.kt` for page layout patterns
- Use `FormItem` for consistent form layouts
- Implement proper state management with ViewModels
- Use `Lucide.XXX` for icons, and import `import com.composables.icons.lucide.XXX` for each icon
- Use `LocalToaster.current` for toast messages

### Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- If the user explicitly requests localization, all languages should be supported.
- English(en) is the default language. Chinese(zh), Japanese(ja), Traditional Chinese(zh-rTW), Korean(ko-rKR), and
  Russian(ru) are supported.
- When localization is needed, use the `locale-tui-localization` skill for managing string resources.

## 踩坑记录

只记**实际踩过、且 CI 或真机给出过证据**的坑。每条是「症状 → 证据 → 规则」。
约定：**CI 绿了才算完**——本地静态检查通过只代表括号平衡、符号存在、作用域链没断。

### 1. 编译类：本地检查过了、CI 还是红

#### 1.1 `unit-test.yml` 只编译 `:ai` 模块

- **症状**：Unit Test 全绿，于是以为没问题；结果 Debug Build 红。
- **证据**：`unit-test.yml` 只跑 `:ai:testDebugUnitTest`，app 模块的编译不在它范围内。
- **规则**：**只看 Debug Build 的结论**。Unit Test 绿对 app 模块代码毫无证明力。

#### 1.2 HugeIcons 的图标是"逐图标 import"的

- **症状**：`Unresolved reference 'ArrowTurnBackward'`（CI run 38259425349）。
- **证据**：`import me.rerere.hugeicons.HugeIcons` 只给了 `HugeIcons` 这个对象；
  每个图标还要单独 import，例如 `import me.rerere.hugeicons.stroke.ArrowTurnBackward`。
- **规则**：用 `HugeIcons.Xxx` 前先确认本文件有 `import me.rerere.hugeicons.stroke.Xxx`。
  抄用法时**连 import 一起抄**。自查：`_rescue/check_ui_batch.py` 末段会列出
  "用了某图标但没有对应 import"的地方。

#### 1.3 回调要加到"使用点所在的那个 composable"

- **症状**：`Unresolved reference 'onRestoreCompressed'`（CI run 38262324972，两处）。
- **证据**：`ui/pages/chat/ChatList.kt` 里有**两个** composable：外层的 `ChatList`
  （参数入口）和内层真正渲染列表的 `ChatListNormal`。参数只加在外层，使用点在内层。
- **规则**：新增回调/参数时**从参数入口一路加到你使用它的那个函数**，每一层都要声明
  （内层参数给默认值，这样其它调用点不受影响）。
  自查：`_rescue/check_scope.py <相对路径> <符号名>` 会列出该符号每处所在的函数，
  并断言"用到它的函数都声明了它"。**gate 通过才允许推送。**

#### 1.4 静态检查的能力边界

- **症状**：连续两次"本地全绿" → CI 连续两次红（1.2、1.3）。
- **规则**：本地检查只能覆盖：括号/圆括号平衡、符号是否存在、符号的作用域链、图标 import。
  凡是"类型是否匹配、重载是否选中、Kotlin 语法细节"的活，只能交给 CI。
  因此**不要提前宣布"好了"**；正确的说法是"已推送，等 Debug Build"。

#### 1.5 LazyColumn 的 item 里"提前 return"会留下假空白

- **症状**（这条是**改之前就避开的，不是 CI 打脸出来的**）：折叠归档原文时，最直觉的写法是在 item 里
  `if (不显示) return@itemsIndexed`。但那样**每个被跳过的 item 仍然会组合出一个空 `Column`**，
  而 `LazyColumn` 的 `verticalArrangement = Arrangement.spacedBy(12.dp)` 会逐条攒间距 →
  31 条归档 = **372dp 的假空白**（不是内容，是间距）。
- **依据**（Kotlin/Compose 规则本身，不是猜的）：
  1. 早退要发生在**建 `Column` 之前**，被跳过的 item 才完全不入组合；
  2. 首条归档消息（要显示胶囊的那条）不能在包裹容器里 `return@itemsIndexed` ——
     `Column` 的 content 是 `@Composable` lambda、**不是 inline**，里面写 `return@itemsIndexed` 直接编译不过；
     所以那一处用 `if/else` 而不是 return。
- **规则**：在 `LazyColumn` 的 item 里"隐藏某些条目"时，把判断提到所有包裹容器之前；
  想用 return 跳过内容时，先确认那个 lambda 是不是 inline 的。

### 2. 网络与推送

#### 2.1 `git push` 会间歇性连不上

- **症状**：`Failed to connect to github.com port 443`。
- **证据**：`api.github.com` 一直可用；`github.com:443` 时通时不通。另外 `git fetch` 失败时
  **`origin/master` 不会更新**——曾因此拿旧基线 rebase，撞了一堆冲突。
- **规则**：推送走 Git Data API：`_rescue/api_push.py <提交信息文件> [--push]`。
  它自己解析远端 master 与本地 HEAD、整树比对、只上传差异文件，
  并用 `git cat-file blob <sha>` 的字节上传（**必须这样**，否则工作区的 CRLF 会被写成
  CRLF blob，和仓库的 LF 归一化打架）。**永远不要把 `origin/master` 当基线。**
  注意 `_rescue/` 在 `D:\时纾\rikkahub\_rescue\`，**不在仓库内**，不会进提交。

#### 2.2 `gh` 的几个默认值会咬人

- **症状**：`gh run list` 查不到东西；`--jq` 报 `function not defined: failure/0`。
- **证据**：`gh` 默认仓库是 `upstream`（rikkahub/rikkahub），不是你的 fork；
  PowerShell 会把 `--jq` 里的 `"failure"` 引号吃掉。
- **规则**：先 `$env:GH_REPO="cacure-sys/rikkahub"`。`run list` 没有 `-R`。
  用 `head_sha` 过滤必须给**完整** SHA。别用 `-f` 格式化 `ConvertFrom-Json` 的输出。
  jq 里避免写字符串比较，或者干脆改用 Python 解析 JSON。

#### 2.3 取失败日志要绕开重定向的鉴权

- **症状**：`GET /actions/jobs/{id}/logs` 报 `401 Server failed to authenticate`。
- **证据**：该接口 302 到对象存储，urllib 会把 `Authorization` 头带过去，存储侧拒绝。
- **规则**：用 `_rescue/ci_log2.py <run_id>`（自定义 redirect handler，重定向时摘掉鉴权头），
  或直接用 `gh api .../logs`。

### 3. Windows / PowerShell

- **坑**：PS 5.1 读 UTF-8 无 BOM 的 `.ps1` 会按 GBK 解，脚本里的中文和路径全乱
  （`D:\时纾` → `D:\鏃剁壕`）。
  **规则**：`.ps1` 一律纯 ASCII，路径用 `$PSScriptRoot` 拼。
- **坑**：内联 `python -c "...\"..."` 里的 `\"` 会被 PowerShell 吃掉，导致 Python 语法错误。
  **规则**：稍复杂的 Python 一律写成脚本文件再跑。
- **坑**：`pwsh` 不在 PATH。**规则**：用 `powershell -NoProfile -ExecutionPolicy Bypass -File`。
- **坑**：控制台按 GBK 输出，打印阿拉伯语/emoji 会 `UnicodeEncodeError` 并**中断脚本**
  （曾因此少看了一半搜索结果，误判成"只有这些触点"）。
  **规则**：输出纯 ASCII，或者把结果写进 UTF-8 文件再用读文件的工具看。
  **结论性的扫描不要因为打印失败就半途而废。**

### 4. 数据与迁移

- **Room 的迁移校验**：实体的 `@ColumnInfo(name = ..., defaultValue = "...")` 必须与
  `ALTER TABLE` 里写的 `DEFAULT` **完全一致**，否则启动时报
  `Migration didn't properly handle: <table>`。改列时两边一起改，别只改一边。
- **消息在哪**：聊天消息在 `message_node` 表（`id / conversation_id / node_index / messages /
  select_index`，本分支加了 `archived`），**不在** `conversation.nodes` 列里（那一列是空的）。
  写探针时先确认表名，别对着空列做结论。
- **请求构造只有一个收口**：`Conversation.currentMessages`。想让某些消息"留在库里但不发给模型"，
  在这里过滤即可，发送路径自动全生效。但 `messageNodes` 有 90 多处 UI 用途——
  改数据语义前先分清哪些是"显示"、哪些是"发送"。

### 5. 提示词与占位符

- `applyPlaceholders` 只认**单**大括号：`{content}`、`{additional_context}`、`{locale}`。
  写成 `{{content}}` 不会生效，只会留下多余的括号。
- 删掉一个占位符的绑定之前，先全仓库搜一遍它出现在哪些提示词/字符串里：
  一是默认提示词（`data/ai/prompts/*.kt`），二是各语言 `res/*/strings.xml` 里给你的提示，
  三是用户已保存的自定义提示词（这个搜不到，只能提醒用户）。
  漏掉的后果不是编译错，而是模型收到字面量 `{target_tokens}` 这种东西。

### 6. 安装与真机验证

- 每次 CI 用**一次性 debug 签名** → 覆盖安装会失败。
  **规则**：先卸载（**会清空应用数据**）→ 装新包 → 重新导入备份 zip。
- 数据库迁移链（25→26→27→28）**只有在真机第一次打开时才会真正执行**。
  CI 只能证明它能编译和打包，不能证明迁移不崩。
- 真机验证清单（每次改动涉及数据/发送路径时）：
  1. 导入备份后能否正常打开会话（迁移没崩）
  2. 压缩一次 → 归档的原文还在不在、记忆有没有变短
  3. 恢复被压缩的原文 → 原文是否重新出现在发送内容里
  4. 自动压缩触发后，下一轮发给模型的上下文是否只剩记忆 + 最近 N 条

### 7. `_rescue/` 救援工具（`D:\时纾\rikkahub\_rescue\`，不属于仓库、不进提交）

| 工具 | 用途 |
|---|---|
| `api_push.py <msgfile> [--push]` | 走 Git Data API 推送（`github.com:443` 不通时用） |
| `ci_status.py` | 打印指定 SHA 的所有 run 状态 + `FAILED_RUNS=` |
| `ci_log2.py <run_id>` | 取失败 job 的编译错误（能绕开重定向鉴权） |
| `watch_ci.ps1` | 后台盯 CI（纯 ASCII，20 分钟兜底退出） |
| `check_ui_batch.py` | 撤销压缩 UI 那批的接线断言 + 图标 import 自查 |
| `check_scope.py <relpath> <symbol>` | **符号作用域链检查**：用到它的函数是否都声明了它 |
| `check_collapse.py` | 归档折叠那批的接线断言（含"早退必须在 Column 之前"） |
| `check_bulk.py` | 多选批量归档/删除那批的接线断言（含确认框的 `text` 必须是 @Composable lambda） |
| `scan_icons.py` | 列出 app 里实际用过的全部 HugeIcons 名称——**选图标前先看它**，别猜出不存在的名字 |
| `fetch_artifact.py <run_id> <artifact> <outdir>` | 从 CI 下产物到本地并打印 sha256（重定向时摘鉴权头，同 `ci_log2.py`） |

### 8. 沙箱与环境：所有 pwsh 突然全挂

- **症状**：连 `"pwsh alive"` 都返回 `SetNamedSecurityInfoW failed (Win32 5): grantWrite(D:\时纾)`。
  grep / 读文件正常，但任何 `pwsh` 命令都跑不起来。
- **证据链**：`D:\时纾` 缺 **WRITE_OWNER**（`writeDac=true / writeOwner=false`），
  DSH 给工作区划权限时正好卡在这一步。用环境自带的 `diagnose-windows-sandbox-acl` 技能脚本
  （`-Path` / `-AllowRoot` / `-Out`，并以"不受限"方式跑它）能修：备份原权限 → 补当前账户完全控制 →
  打印撤销命令，复核到 `writeOwner: false → true` 才算成功。**one path per invocation**，
  一层修不好就对更深的那层再跑一次（当时连跑了两层：`D:\时纾` → `rikkahub-master`）。
- **修完仍然写不进去**：`D:\时纾` 带着**低完整性标签**（`LOW_LABEL=True`），子目录却是 `lowLabel:null`。
  这时拦人的已经不是目录权限，而是**沙箱的完整性限制** —— 那个脚本只写权限、不碰标签，
  **再跑多少次都没用，不要循环跑**。
- **判据**（怎么分清是哪种）：agent 的文件编辑工具能写、`git status` 能读，
  但受限 `pwsh` 写 `.git` 报 `Unable to create '.git/index.lock': Permission denied`
  → 是**沙箱写入限制**，不是文件损坏、也不是残留锁文件（先 `Get-ChildItem -Filter *.lock` 确认没有残留锁，
  再去动权限）。当时的 `git commit` 就是这么失败的。
- **出路**：把会话文件策略切成不受限（`danger-full-access`）后，同一条命令**一次就过**。
  被拒绝后不要换别的路子绕。
