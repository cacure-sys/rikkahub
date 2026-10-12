# 开发踩坑记录（RikkaHub 私设分支）

这份文件只记**实际踩过、且 CI 或真机给出过证据**的坑。每条都包含「症状 → 证据 → 规则」，
目的是让下一次（新会话、新模型、或者三个月后的我）不必把同样的 20 分钟再烧一遍。

约定：**CI 绿了才算完**。本地静态检查通过只代表"括号平衡、符号存在、作用域链没断"，
不代表能编译——这一点已经被打脸两次（见 1.2、1.3）。

---

## 1. 编译类：本地检查过了、CI 还是红

### 1.1 `unit-test.yml` 只编译 `:ai` 模块

- **症状**：Unit Test 全绿，于是以为没问题；结果 Debug Build 红。
- **证据**：`unit-test.yml` 只跑 `:ai:testDebugUnitTest`。app 模块的编译根本不在它范围内。
- **规则**：**只看 Debug Build 的结论**。Unit Test 绿对 app 模块的代码毫无证明力。

### 1.2 HugeIcons 的图标是"逐图标 import"的

- **症状**：`Unresolved reference 'ArrowTurnBackward'`（CI run 38259425349）。
- **证据**：`import me.rerere.hugeicons.HugeIcons` 只给了 `HugeIcons` 这个对象；
  每个图标还要单独 import，例如 `import me.rerere.hugeicons.stroke.ArrowTurnBackward`。
- **规则**：用任何 `HugeIcons.Xxx` 之前，先确认本文件有对应的
  `import me.rerere.hugeicons.stroke.Xxx`。抄用法时**连 import 一起抄**。
  自查：`_rescue/check_ui_batch.py` 最后一段会列出"用了某图标但没有对应 import"的地方。

### 1.3 回调要加到"使用点所在的那个 composable"

- **症状**：`Unresolved reference 'onRestoreCompressed'`（CI run 38262324972，两处）。
- **证据**：`ui/pages/chat/ChatList.kt` 里有**两个** composable：外层的 `ChatList`
  （参数入口）和内层真正渲染列表的 `ChatListNormal`。参数只加在外层，使用点在内层。
- **规则**：新增一个回调/参数时，**从参数入口一路加到你使用它的那个函数**，
  每一层都要声明（内层参数可以给默认值，这样其它调用点不受影响）。
  自查：`_rescue/check_scope.py <相对路径> <符号名>` 会列出该符号每处所在的函数，
  并断言"用到它的函数都声明了它"。**gate 通过才允许推送。**

### 1.4 静态检查的能力边界

- **症状**：连续两次"本地全绿"→ CI 连续两次红（1.2、1.3）。
- **规则**：本地检查只能覆盖：括号/圆括号平衡、符号是否存在、符号的作用域链、图标 import。
  凡是"类型是否匹配、重载是否选中、Kotlin 语法细节"的活，只能交给 CI。
  因此**不要提前宣布"好了"**；正确的说法是"已推送，等 Debug Build"。

---

## 2. 网络与推送

### 2.1 `git push` 会间歇性连不上

- **症状**：`Failed to connect to github.com port 443`。
- **证据**：`api.github.com` 一直可用；`github.com:443` 时通时不通。
  另外 `git fetch` 失败时 **`origin/master` 不会更新**——曾因此用旧基线 rebase，撞了一堆冲突。
- **规则**：推送走 Git Data API：`_rescue/api_push.py <提交信息文件> [--push]`。
  它自己解析远端 master 与本地 HEAD、整树比对、只上传差异文件，
  并用 `git cat-file blob <sha>` 的字节上传（**必须这样**，否则工作区的 CRLF 会被写成 CRLF blob，
  和仓库的 LF 归一化打架）。**永远不要把 `origin/master` 当基线**，先确认它是最新的。

### 2.2 `gh` 的几个默认值会咬人

- **症状**：`gh run list` 查不到东西；`--jq` 报 `function not defined: failure/0`。
- **证据**：`gh` 默认仓库是 `upstream`（rikkahub/rikkahub），不是你的 fork；
  PowerShell 会把 `--jq` 里的 `"failure"` 引号吃掉。
- **规则**：先 `$env:GH_REPO="cacure-sys/rikkahub"`。`run list` 没有 `-R`。
  用 `head_sha` 过滤必须给**完整** SHA。别用 `-f` 格式化 `ConvertFrom-Json` 的输出。
  jq 里避免写字符串比较，或者干脆改用 Python 解析 JSON。

### 2.3 取失败日志要绕开重定向的鉴权

- **症状**：`GET /actions/jobs/{id}/logs` 报 `401 Server failed to authenticate`。
- **证据**：该接口 302 到对象存储，urllib 会把 `Authorization` 头带过去，存储侧拒绝。
- **规则**：用 `_rescue/ci_log2.py <run_id>`（自定义 redirect handler，重定向时摘掉鉴权头），
  或直接用 `gh api .../logs`。

---

## 3. Windows / PowerShell

- **坑**：PS 5.1 读 UTF-8 无 BOM 的 `.ps1` 会按 GBK 解，脚本里的中文和路径全乱
  （`D:\时纾` → `D:\鏃剁壕`）。
  **规则**：`.ps1` 一律纯 ASCII，路径用 `$PSScriptRoot` 拼。
- **坑**：内联 `python -c "...\"..."` 里的 `\"` 会被 PowerShell 吃掉，导致 Python 语法错误。
  **规则**：稍复杂的 Python 一律写成脚本文件再跑（`_rescue/*.py`）。
- **坑**：`pwsh` 不在 PATH。**规则**：用 `powershell -NoProfile -ExecutionPolicy Bypass -File`。
- **坑**：控制台按 GBK 输出，打印阿拉伯语/emoji 会 `UnicodeEncodeError` 并**中断脚本**
  （曾因此少看了一半的搜索结果，误判成"只有这些触点"）。
  **规则**：打印前 `sys.stdout.reconfigure(encoding='utf-8', errors='replace')`，
  或者输出纯 ASCII。**结论性的扫描不要因为打印失败就半途而废。**

---

## 4. 数据与迁移

- **Room 的迁移校验**：实体的 `@ColumnInfo(name=..., defaultValue="...")` 必须与
  `ALTER TABLE` 里写的 `DEFAULT` **完全一致**，否则启动时报
  `Migration didn't properly handle: <table>`。
  改列时两边一起改，别只改一边。
- **消息在哪**：聊天消息在 `message_node` 表（`id / conversation_id / node_index / messages /
  select_index`，本分支加了 `archived`），不在 `conversation.nodes` 列里（那一列是空的）。
  写探针时先确认表名，别对着空列做结论。
- **请求构造只有一个收口**：`Conversation.currentMessages`。
  想让某些消息"留在库里但不发给模型"，在这里过滤即可，发送路径自动全生效。
  但注意 `messageNodes` 有 90 多处 UI 用途——改数据语义前先分清哪些是"显示"、哪些是"发送"。

---

## 5. 提示词与占位符

- `applyPlaceholders` 只认**单**大括号：`{content}`、`{additional_context}`、`{locale}`。
  写成 `{{content}}` 不会生效，只会留下多余的括号。
- 删掉一个占位符的绑定之前，先全仓库搜一遍它出现在哪些提示词/字符串里：
  一是默认提示词（`data/ai/prompts/*.kt`），二是各语言的 `res/*/strings.xml` 里给你的提示，
  三是用户已保存的自定义提示词（这个搜不到，只能提醒用户）。
  漏掉的后果不是编译错，而是模型收到字面量 `{target_tokens}` 这种东西。

---

## 6. 安装与真机验证

- 每次 CI 用**一次性 debug 签名** → 覆盖安装会失败。
  **规则**：先卸载（**会清空应用数据**）→ 装新包 → 重新导入备份 zip。
- 数据库迁移链（25→26→27→28）**只有在真机第一次打开时才会真正执行**。
  CI 只能证明它能编译和打包，不能证明迁移不崩。
- 真机验证清单（每次改动涉及数据/发送路径时）：
  1. 导入备份后能否正常打开会话（迁移没崩）
  2. 压缩一次 → 归档的原文还在不在、记忆有没有变短
  3. 恢复被压缩的原文 → 原文是否重新出现在发送内容里
  4. 自动压缩触发后，下一轮发给模型的上下文是否只剩记忆 + 最近 N 条

---

## 7. 本目录里的救援工具（`_rescue/`，不属于仓库、不进提交）

| 工具 | 用途 |
|---|---|
| `api_push.py <msgfile> [--push]` | 走 Git Data API 推送（`github.com:443` 不通时用） |
| `ci_status.py` | 打印指定 SHA 的所有 run 状态 + `FAILED_RUNS=` |
| `ci_log2.py <run_id>` | 取失败 job 的编译错误（能绕开重定向鉴权） |
| `watch_ci.ps1` | 后台盯 CI（纯 ASCII，20 分钟兜底退出） |
| `check_ui_batch.py` | 撤销压缩 UI 那批的接线断言 + 图标 import 自查 |
| `check_scope.py <relpath> <symbol>` | **符号作用域链检查**：用到它的函数是否都声明了它 |
