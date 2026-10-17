# 2026-10-17 AgentLib 0.2.1 对接 —— ga 模块 Agent 实现改为复用库内标准结构（约束设计书）

> **分支**：`agentlib/dock-0.2.1` · **工作树**：`.claude/worktrees/agentlib-dock`（基点 `03faf0a3` = main）
> **状态**：设计书 + 批次 A/B/C · **作者**：DSH 会话（控制方兼实现方）
> **上游**：`~/ProjectMosire`（AgentLib 的家，HEAD `ef241ae`，v0.2.1 已发布到 GitHub Pages）
> **消费方文档**：《对接须知-三个库-2026-10-17.md》《接入指南-三个库给消费方.md》（`~/ProjectMosire/`）
> ★ 本文是执行前置：**没有本设计书就不动代码**（`AGENTS.md` §五.3 → `docs/agents/02-一-并发.md` §一.8）。

---

## 0. 用户原话附录（2026-10-17，逐字照录，不改写）

1. 「在当前目录下找到项目的Agentlib对接工作区，先恢复工作状态」
2. 「停停停，告诉我你准备怎么对接，找不到包我直接给你贴github page不得了」
3. 「我只说一点，agentlib更新了许多可以复用的标准化Agent结构，你应该把目前ga模块的相关Agent实现，秉持着lib里能复用就复用的原则来进行重写和对接，懂我意思吗？可以开一个新分支方便保存开发进度和日后合并」
4. （就“timeBudget/quota 要不要开判”的**先行指示，随后撤回**）「肯定开啊，超了算中止，默认数值给个30分钟，测试文件里改成毫秒级代码判断」
5. （**撤回第 4 条，现行有效**）「等等等，可以不开是吧？先不开」

**落点**：
- 第 3 条 = 本设计书的**唯一目标**：ga 的 Agent 实现按「库内能复用就复用」重写；
- 「新分支保存开发进度和日后合并」⇒ 本分支**允许提交保存进度**（提交只在 `agentlib/dock-0.2.1`，主树仍是最终入账口）；
- 第 4/5 条 ⇒ 本批 **timeBudget / quotaMaxTokens 不开判**（只填不判，行为逐字不变），留批 D；若将来开，按“超出 = 中止 + 毫秒级预算测试”的口径做。

---

## 1. 目标 / 非目标

### 1.1 目标

1. **升级**：`io.mosire:{agentlib-mosire, workspace-mosire, docs-mosire}` `0.1.0 → 0.2.1`（版本唯一拼写点 = 父 POM `agentlib-mosire.version`，`:95`）；
2. **重写 ga 的 Agent 实现**：`simos-ga` 的 `DecisionAgentRunner`（1639 行）里**自建的循环/窗口/请求拼装/终止判定**，改为装配库侧标准件：
   - `io.mosire.agentlib.runtime.AgentLoop`（循环骨架，8 类 565 行）
   - `io.mosire.agentlib.context.{ContextWindow, ContextComposer, LlmRoundTrip}`（上下文窗口，3 类 378 行）
   - `StopPolicy` / `TurnListener` / `ToolCallDispatcher`（三个宿主策略 SPI）
   - 终止词汇 `TurnResult` / `StopReason` / `StopSignal` / `LoopState`
   - `io.mosire.agentlib.agent.AgentConfig`（**Agent 描述符**：库的分工是「`AgentConfig` 归宿主、`AgentLoop` 刻意不持有它」⇒ 它的复用点在 ga 的**装配层**，见 §3.4）
3. **行为等价**：工具面、事件、落库顺序、请求序列、异常类型**逐字不变**（判据见 §4）；
4. 新分支上**提交保存进度**，日后合并回 `main`。

### 1.2 非目标（本批明确不做）

- 不改工具面/审批/权限语义；**不把角色维接进调用者权限集**（见 §3.3，单独批 R）；
- 不改 `DecisionRunRegistry` / GUI / MCP 的对外契约；
- **不改变** `AgentConfig` 三个“Simos 今天没有对应语义”的字段所隐含的行为（timeBudget / quotaMaxTokens / deniedTools 本批**只填不判**，见 §3.4 末尾，开判需用户裁定）；
- 不动 `LlmDecisionAdjudicator`（单次往返、无循环/无工具/无跨轮上下文，无可复用的“结构”，它已用库 `LlmClient`）；
- 不动 `LlmProviderResolver` / GUI 的「测试连接」路径（一次性调用，无 Agent 结构）；
- 不碰主树、`parallel-side-module`（D-032）、`ga-opt`、`d031-p1-baseline`。

---

## 2. 现状（回代码核实，`file:line` 均为本工作树实测）

### 2.1 自建的循环（要换掉的那一段）

`simos-ga/src/main/java/io/mosire/simos/ga/decision/DecisionAgentRunner.java`：

| 位置 | 内容 |
|---|---|
| `:1232` `run(DecisionMaker, SimulationState)` | 一轮：装载会话 → 注入身份/信件/开场快照/回合提醒 → `while(true)` |
| `:1285` `while (true)` | **自建循环**：预算判定 → `llmClient.chat` → 落盘 assistant → 进度① → 解析工具调用 → 逐条执行 → 落盘 tool → 进度② → 图片消息整批后置 |
| `:1310` | `llmClient.chat(new LlmRequest(requestMessages(history), toolDefs))` |
| `:1194` `requestMessages` | 请求形状：`[system(" ")] + 非 system 历史`（用户 2026-10-22 裁定：system 固定空格占位） |
| `:1287` | 预算：`llmCalls >= maxLlmCalls` ⇒ WARN `DECISION_RUN_BUDGET_EXCEEDED` + 抛 `TurnBudgetExceeded` |
| `:1330` 后 | 图片消息**必须排在本回合所有 tool 消息之后**（真网关实测硬约束，见类注） |
| `:1378` `execute` / `:1446` `recordResult` | 线名→真名、冻结回合改写、权限链执行面 `caller.execute(...)`、tool 消息回填 |
| `:191` `ProgressListener` | 每轮 LLM 后、每次工具后各推一次 |

### 2.2 库侧已有标准件（0.2.1，已发布）

- `runtime/`：`AgentLoop`（九步顺序是契约，四类终止回调 exactly-once）、`StopPolicy`、`TurnListener`、`ToolCallDispatcher`、`LoopState`、`StopSignal`、`StopReason`、`TurnResult`；
- `context/`：`ContextWindow`（窗口 + `unsavedTail`/`markSaved`）、`ContextComposer`（`[头部]+窗口原样+本轮输入` 硬契约）、`LlmRoundTrip`（组请求→发信→**写回**一条线，写回是往返的一部分）；
- `agent/AgentConfig`（宿主侧描述符；**AgentLoop 刻意不持有它**，旋钮以策略对象传入）。

### 2.3 升级的第一个红点（已实测，不是推测）

`.m2-repo` 已从 Pages 真下载 0.2.1 三件（日志见 §7.1），`compile` 在第一站 `simos-ga-spi` 即红：

```
SimosTool.java:[35,17] roles() in io.mosire.simos.ga.spi.SimosTool clashes with roles() in io.mosire.agentlib.tool.AgentTool
  return type java.util.Set<io.mosire.simos.ga.spi.ToolRole> is not compatible with java.util.Set<java.lang.String>
SimosToolAdapter.java:[120,24] roles() ... cannot implement roles() in io.mosire.agentlib.tool.AgentTool
```

根因：0.2.0 起 `AgentTool` 新增 `default Set<String> roles()`（角色维，§A）；Simos 的 `SimosTool.toolRoles(): Set<ToolRole>` 与之**同名同擦除**。

---

## 3. 设计决定

### 3.1 循环/窗口/请求/落库的映射表

| 旧（`DecisionAgentRunner` 自建） | 新（库标准件 + 宿主策略） |
|---|---|
| `while (true)` + 预算判定 + `chat` + 解析工具 + 回填 | `AgentLoop.runTurn(turnInput, tools)`（九步结构） |
| `history` 局部 `ArrayList` | `ContextWindow(conversationId, conversations.load(id))`（本轮内存窗口） |
| `requestMessages(history)` + `toolDefs` | `ContextComposer`：`[system(" ")] + 窗口非 system 原样 + turnInput`，工具面用**装配期预计算**的 wire `ToolDef`（名字/顺序不变） |
| `llmClient.chat(...)` 与“先落盘再判” | `LlmRoundTrip` 负责组请求/发信/**写回窗口**；宿主在 `TurnListener.onRoundTrip` 里落盘 assistant（等价于旧的“响应后先 append 再判工具”） |
| 预算判定（每轮 LLM 前，工具批内**不**判） | `StopPolicy.check` 返回 `TURN_LIMIT` 信号；`checkToolCallBoundary` **恒返回空**（与旧行为逐字一致：不在工具批中间新造终止/悬空 tool_call） |
| 预算中止：WARN + 抛 `TurnBudgetExceeded` | `TurnListener.onStop`：先打同一条 WARN，再**抛** `TurnBudgetExceeded`（保留可判别类型；调用方 `DecisionAgentService:305`、`GuiServer:1896` 依赖 `instanceof`） |
| `llmClient.chat` 异常原样穿透 | `TurnListener.onLlmError`：**原样重抛** `error`（旧行为：异常穿透；调用方 `catch (RuntimeException)` 抢救已写 packet） |
| 正常收尾：INFO `DECISION_RUN_END` + 返回 `DecisionTurn` | `TurnListener.onFinished`：打同一条 INFO；`runTurn` 返回后组 `DecisionTurn`（`llmCalls = result.turns()`、`finalText = result.finalText()`） |
| 工具执行 + tool 消息回填 | `ToolCallDispatcher.dispatch`（内部复用既有 `execute/recordResult`，**权限链/冻结改写/线名翻译一行不改**） |
| 图片消息“整批 tool 之后” | dispatcher 侧缓冲：`onRoundTrip` 记录本批条数，dispatch 到本批最后一条后 **flush**（顺序与旧实现一致） |
| 每轮提醒 `decisionRoundNotice` **不落库、每轮现插** | 作为**第 1 轮 `turnInput`** 交给 `LlmRoundTrip`（写回窗口=内存；**不**走 `conversations.append`，故不落库） |
| 进度回调（LLM 后、每次工具后各一次） | `onRoundTrip` 与 dispatcher 各推一次（参数口径不变：已完成 LLM 数 + 累积工具调用） |
| `lastAssistantText` 累积 | `onRoundTrip` 里累积（同旧逻辑） |

### 3.2 窗口/落库的边界（★ 本批最容易写错的一条）

`LlmRoundTrip` 会把 `turnInput` 写回窗口；若宿主用 `window.unsavedTail()` 一把梭落库，**不落库的每轮提醒会被写进会话库**（行为变化）。故本批：

- **落库点逐字沿用旧实现**（显式 `conversations.append`）：
  1. 空会话：身份消息（+ 可选开场快照）→ append；
  2. 新信件：append；
  3. 每次成功往返：assistant → append（`onRoundTrip`）；
  4. 每条工具结果：tool 消息 → append（dispatcher，与旧 `run` 同序）；
  5. 图片消息：本批 tool 消息全部落完后 → append；
- **不使用** `unsavedTail()/markSaved()`（它隐含“把不在库里的全部落库”，与本批语义相反）；窗口只是本轮的内存工作集。

### 3.3 `roles()` 同名冲突的解法（批 A）

**决定：`SimosTool.toolRoles()` 更名 `toolRoles()`，不覆写 `AgentTool.roles()`。**

- 枚举 `ToolRole{ГM, DM}` 仍是 Simos 领域件的**唯一拼写点**（批复书 §Q8「ToolRole 的枚举值留 simos」），只是方法名让位；
- **不**覆写 `AgentTool.roles()` 的理由（硬事实）：`AgentTool.roles()` 空集 = 本维不表态（⊤）；而**调用者侧** `AgentPermissionSet.roles()` 空集 = ⊥ = “谁都不是”（fail-closed）。Simos 当前的调用者权限集**未携带**角色名 ⇒ 此刻若把 258 条工具都覆写成声明 `{GM}`/`{DM}`，库侧判定会把 GM/DM 调用者的所有工具调用全部拒掉（deny-all）——行为变化，且不是本批目标；
- 角色维的真正对接 = **批 R**（后续，单独设计）：声明侧由 `toolRoles()` 派生 `roles()` 的字符串 + 调用者权限集带角色 + 装订期审计，三件必须同一批落地并带判别测试；
- 批 A 交付：`SimosTool`/`SimosToolAdapter`/185 个实现类/29 处调用点/测试与注释的机械更名，**零行为变化**，全树编译绿。

### 3.4 `AgentConfig` 的复用（★ 修正：本批就接，不再推迟）

**复用位置**：`AgentConfig` 是**宿主侧**的 Agent 描述符（`AgentLoop` 刻意不持有它——库注：「旋钮以显式参数/策略对象传入，没装配 AgentConfig 的程序也能用」）。⇒ 复用它发生在 **ga 的装配层**：每回合为决策人构建**一份** `AgentConfig`，循环的三处旋钮（头部 system、轮次硬顶、单响应工具调用硬顶）**只从这一份描述符取**，不再散落在构造器字段里。

**逐字段映射（10 组件全部显式给值，禁止吃 `AgentConfig.Builder` 的默认值）**：

| 组件 | Simos 的取值来源 | 本批是否消费 |
|---|---|---|
| `id` | 决策人身份 `dm.id().value()`（**不是**会话 id：会话 id 仍由 `conversationIdOf(dm)` 纯函数派生） | 描述符/日志 |
| `description` | `""`（`DecisionMaker` 没有展示名；不编一个） | 否 |
| `systemPrompt` | **`" "`（一个空格）** —— 逐字沿用用户 2026-10-22 裁定（system 角色必须存在、内容固定空格，身份与规则走 user 消息） | ✅ composer 头部 |
| `model` | `LlmClient.model()`（provider 客户端自报） | 描述符/日志 |
| `allowedTools` | per-DM 白名单 `toolWhitelist`（与装配期工具面**同一份**） | ✅ 工具面同源校验 |
| `deniedTools` | `Set.of()`（Simos 今天没有 per-DM deny 语义） | 否（只填） |
| `maxTurns` | `maxLlmCalls`（生产 = `DEFAULT_MAX_LLM_CALLS` 10000） | ✅ `StopPolicy.check` |
| `maxToolCallsPerTurn` | **无上限哨兵** `UNLIMITED_TOOL_CALLS_PER_TURN = Integer.MAX_VALUE`（旧行为是 `AgentLoop` 上限 0 = 不限；`AgentConfig` 契约要求正数 ⇒ 用“大到物理上到不了”表达，同步传入 `AgentLoop.Builder.maxToolCallsPerResponse`，行为等价） | ✅ 单响应硬顶 |
| `timeBudget` | **无预算哨兵** `NO_TIME_BUDGET = Duration.ofDays(365 * 100)`（Simos 今天没有回合时间预算语义；旧循环只查 LLM 次数） | ❌ **只填不判** |
| `quotaMaxTokens` | `AgentConfig.NO_QUOTA`（0 = 不限；Simos 今天没有 token 配额账本） | ❌ **只填不判** |

**为什么有两项“只填不判”**（★ 用户 2026-10-17 已裁定「**先不开**」，见 §0 原话附录 4/5）：把它们真正接进 `StopPolicy` = **新增两种终止出口**（`TIME_BUDGET` / `QUOTA`），是**行为变化**，与本批“逐字不变”的判据冲突。所以本批：描述符里显式给出哨兵值（绝不落到 `Builder` 的 10min/30 次默认值上），`StopPolicy` 只判 `maxTurns`。**是否/何时开判留批 D**——届时按“超出 = 中止”的口径，并配毫秒级预算的测试；本批不动。

**落地形状（批 B，已实施 —— 与初稿的差异如实记）**：
- `DecisionAgentRunner.run(dm, state)` 里构建 `AgentConfig`（每回合一份，因为 `id`/`model` 依赖 `dm` 与 `LlmClient`）；
- ★ 初稿写的「新增 `AgentConfig` 构造器重载」**没做**，改为**从既有构造参数内部派生**（`maxLlmCalls → maxTurns`、`toolWhitelist → allowedTools`、`llmClient.model() → model`）。理由：可注入重载会让同一旋钮出现两处真相（构造器参数 vs config），而本批的判据恰恰是"逐字段等价"；派生是单源，且既有调用点零改动；
- `Static` 两个哨兵：`UNLIMITED_TOOL_CALLS_PER_TURN = Integer.MAX_VALUE`（传入 `AgentLoop.Builder.maxToolCallsPerResponse`，等价旧"不限"）、`NO_TIME_BUDGET = Duration.ofDays(365*100)`（**只填不判**）；`quotaMaxTokens = AgentConfig.NO_QUOTA`、`deniedTools = ∅`；
- `StopPolicy` / composer / `AgentLoop.Builder` 分别从 `config.maxTurns()` / `config.systemPrompt()` / `config.maxToolCallsPerTurn()` 取；
- `allowedTools` 与装配期工具面**同源**（工具面本来就从 `toolWhitelist` 派生，见构造器）；★ 未另加"不一致就抛"的第二份断言——那等于给同一事实再写一遍校验，真正的同源保障是"两者都读同一个字段"；
- 判据：既有 `theDefaultBudgetIsTheUserRulingOfTenThousand` / `aRunawayModelIsStoppedByTheTurnBudget` 覆盖 `maxTurns` 映射生效；`AgentConfig.Builder` 默认值泄漏用**变异自证**（删掉 `.maxTurns(maxLlmCalls)` ⇒ 预算中止用例红），不写成常驻断言（因为默认值路径不对外可达）。


---

## 4. 不可破坏的不变量（验收判据 + 负向用例）

| # | 不变量 | 判据 |
|---|---|---|
| I1 | 请求序列逐字不变 | 既有 `DecisionAgentRunnerTest` 的“请求形状/窗口形状”断言全绿；新增映射判据：第 1 轮请求 = `system(" ")` + 历史 + 提醒；第 2 轮起 = `system(" ")` + 窗口（无重复 user） |
| I2 | 事件/落库顺序不变 | 既有用例 + 新增顺序断言：`DECISION_RUN_START` → 每轮 assistant 落库 → `DECISION_TOOL_CALL`(DEBUG) → tool 落库 → … → `DECISION_RUN_END` |
| I3 | 预算中止仍是**可判别类型** | `TurnBudgetExceeded` 由 `onStop` 抛出；`DecisionRunRegistryTest`/`DecisionAgentService` 用例全绿；负向：把 `checkToolCallBoundary` 接成 `check` ⇒ 工具批中途会停（不该发生）⇒ 应红 |
| I4 | LLM 异常仍原样穿透 | 用例：FakeLlm 抛 `LlmException` ⇒ 调用方见到的类型/实例不变 |
| I5 | 图片消息仍在整批 tool 之后 | 多工具 + 有图用例断言消息序；负向：改成立即 flush ⇒ 应红（真网关 400 形状） |
| I6 | 每轮提醒**不落库** | 跑两 tick 后 `conversations.load` 中不出现提醒文案；负向：改走 `append` ⇒ 应红 |
| I7 | 工具权限链不被绕过 | `DecisionAgentRunnerTest` 中权限拒绝用例全绿（dispatcher 必须复用 `caller.execute`，不得直接 `registry.execute`） |
| I8 | 零新增终止出口 | 旧实现只有“自然收尾 / 预算中止 / LLM 异常”三类出口；新实现不得新增（如 time budget/cancel 都未装配） |
| I9 | `AgentConfig` 映射逐字段等价、默认值不泄漏 | 每回合的描述符 10 组件全部显式给值；`maxTurns` 生效（改值 ⇒ 预算行为变）；三个哨兵字段（`maxToolCallsPerTurn`=MAX_INT、`timeBudget`=无预算、`quota`=0）**只填不判**；负向：误用 `AgentConfig.Builder` 默认值（114514 / 30 / 10min）⇒ 现有可判别用例必须红 |

---

## 5. 批次与文件所有权（全部在本工作树内）

| 批 | 内容 | 主要文件 | 状态 |
|---|---|---|---|
| **A** | 版本 0.1.0→0.2.1 + `roles()`→`toolRoles()` 机械更名 + 0.2.1 其余 API 漂移修到**全树 compile/test-compile 绿** | `pom.xml`（唯一拼写点）；`simos-ga-spi/*`；全部 `SimosTool` 实现；调用点与测试 | 进行中 |
| **B** | `DecisionAgentRunner.run()` 改为装配 `AgentLoop`（§3.1 映射）+ 每回合构建/消费 `AgentConfig`（§3.4）；新增宿主策略内类；既有构造器内部派生等价 config、新增 config 重载；公开 API 只增不减 | `simos-ga/.../decision/DecisionAgentRunner.java`；可选 `simos-app/.../decision/DecisionAgentService.java`（显式给 config） | 待 A 绿后 |
| **C** | 判据：新增映射/顺序/负向用例 + 跑既有 9 个相关测试类 + 变异自证（至少 I3/I5/I6 三条） | `simos-app/src/test/**`、`simos-ga/src/test/**` | 待 B |
| **D** | `AgentConfig` 三字段（timeBudget / quotaMaxTokens / deniedTools）**是否开判**（开判 = 新增终止出口，需用户裁定）；**R**（角色维对接：声明 → 调用者权限集 → 装订期审计，单独设计） | —— | 后续 |

---

## 6. 环境事实（本树实测）

- 每棵树自己的 Maven 仓库：`.m2-repo`（首次 `tools/mvn-lock.sh` 从 `~/.m2` hardlink 播种）；
- `~/.m2/settings.xml` 已把 GitHub Pages 注册为全局仓库（`mosire-pages`），**父 POM 无需再加 `<repositories>`**（本工作树父 POM 已有同款声明，双保险）；
- 0.2.1 从 Pages **真下载成功**（`Downloaded from mosire-pages: …agentlib-mosire-0.2.1.jar (410 kB)`）；
- `compile` 实测红点：`simos-ga-spi` 的 `roles()` 冲突（§2.3）。

---

## 7. 未验证 / 风险（如实记录）

1. 0.2.1 相对 0.1.0 的**其余** API 漂移尚未收全（compile 在第一站即失败，后续模块未编译）；批 A 以“编译驱动”逐站收，任何需要语义裁定的漂移**停下来上报**，不猜。
2. 真 LLM 门控、前端门禁、全量 `clean verify` 尚未跑（留到批 C）。
3. 批 B 的“图片批后置”依赖 dispatcher 侧缓冲 + `onRoundTrip` 记录本批条数；若库的调用顺序与预期不符（例如 `onRoundTrip` 在解析工具调用之前），需回读库源码确认后再落（已读：九步顺序写明 `onRoundTrip` 在解析工具调用之前 ⇒ 可以）。
4. “行为逐字不变”的最终判据是**既有测试 + 真 LLM 样本**；若某条旧行为在库的 exactly-once 语义下无法保留，按“BLOCKED 上报”处理，不带病改判据。
