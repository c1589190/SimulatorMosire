# AgentLib 0.2.1 → simos-ga 迁移覆盖审计（2026-10-17）

> **性质**：只读审计；不改生产代码、不把“编译通过”写成“迁移完成”。
> **工作树**：`.claude/worktrees/agentlib-dock` · **分支**：`agentlib/dock-0.2.1` · **HEAD**：`0c377129`
> **结论用途**：纠正“当前只需验收”的过窄口径，为下一份约束设计书列出真实迁移面。
> **核实边界**：本文所有“已用/未用”均以当前树 `src/main` 的 import/调用点和 `~/ProjectMosire` 的 0.2.1 源码为准；未跑测试即不写测试结论。

---

## 0. 一句话结论

**不是“AgentLib 已经整体应用到 ga 了”。** 当前只完成了三条线：

1. 决策人 `DecisionAgentRunner` 的**循环/上下文/请求骨架**改用 `AgentLoop` / `ContextWindow` / `ContextComposer` / `LlmRoundTrip` / `AgentConfig`；
2. 批 R 的**角色维**（`AgentTool.roles()` + `RoleSet` + `RoleCatalogAudit`）；
3. 批 S 的 **system 单空格**硬约束。

`simos-ga` 仍有大量**自建 Agent/决策结构**；其中最大的缺口是 **AgentLib 0.2.1 的 `io.mosire.agentlib.decision` 包（22 个类）在当前树里引用数为 0**。因此现有 S/R 只能算“局部对接验收”，不能作为“ga 的 Agent 实现整体迁移完成”的验收。

### 0.1 用户 2026-10-17 澄清后的目标口径（本文后续建议以此为准）

用户明确：AgentLib 的「所有子 Agent 执行时需判决的工具调用都进包」在 Simos 的翻译是：

> **所有 DM 执行、需要 GM 确认的工具调用都进同一个决策包。**

因此目标不是“保留现有显式 `propose/confirm` 再零散复用几个类”，而是：

- DM 的 `ToolGate.Ask` 不再单条阻塞审批，统一进 `PendingDecisionQueue`；
- 回合收尾把队列收成/追加到该 DM 当前 tick 的决策包；
- GM 通过 `DecisionPackages` / `DecisionSubmissionPort` 裁决，通过 `DecisionCommitPort` 一次提交；
- Simos 的 `DecisionPacket` 不再是第二套状态机，而是包在世界状态里的**持久投影 + 领域明细**（branch/tick/intent/preview/targets/outcome/merge plan）；
- 显式 `propose/confirm/revise/drop` 若继续存在会自己也是 `Ask`（见 `docs/superpowers/plans/2026-10-14-gm-dm-interaction.md` §G8），造成“把提议包本身再提议进包”的递归，必须在迁移中删除或改成非 `Ask` 的包控制面。

---

## 1. 当前已接的范围（有代码坐标）

| 能力 | Simos 落点 | 证据 |
|---|---|---|
| 0.2.1 版本钉 | 父 POM | `pom.xml:99` `<agentlib-mosire.version>0.2.1</agentlib-mosire.version>` |
| 通用 Agent 循环 | `DecisionAgentRunner` | `DecisionAgentRunner.java:14-20` 引入 `AgentLoop` / `LoopState` / `StopPolicy` / `TurnListener` / `TurnResult`；`:1339-1345` 装配并 `runTurn` |
| 上下文窗口 / 请求 / 往返 | `DecisionAgentRunner` 的 `SimosComposer` / `TurnRuntime` | `DecisionAgentRunner.java:1300` `ContextWindow`；`:1395` `ContextComposer`；`:1479` 依赖 `LlmRoundTrip` 的写回语义 |
| Agent 描述符 | `DecisionAgentRunner.agentConfigFor` | `:1374` 起 10 组件显式赋值 |
| 角色声明 | `SimosTool.roles()` | `simos-ga-spi/.../SimosTool.java:64` 从 `toolRoles()` 派生 `Set<String>` |
| 包装后转发 | `RecordingToolSource.RecordingTool.roles()` | `simos-ga/.../RecordingToolSource.java:128-129` |
| 包装后面校验 | `ToolRoleAudit.requireFacesOnAgentTools` | `simos-ga/.../ToolRoleAudit.java:160` 起；`Shell.java:1435` 调用 |
| 库侧角色目录审计 | `RoleCatalogAudit` | `Shell.java:1436-1451`；输入 A/B 分离 |
| 调用者角色 | DM / GM 权限集 | `DecisionCallerFactory.java:316` `{DM}`；`Shell.java:1803` `{GM}` |
| 基础审批链 | `Shell` | `Shell.java:1237-1267`：`PendingApprovals` / `HttpApprovalChannel` / `ApprovalCoordinator` / `ApprovalHttpEndpoint` / `ToolCallAuthorizer` |

### 1.1 证据：A+B 的语义改动其实很窄

`e26d49a4` 的 225 个文件中，**只有 25 个文件的单文件 diff ≥ 10 行**；其余约 200 个是 2 行左右的 `roles()` → `toolRoles()` 机械改名。真正的运行流语义改动集中在：

- `simos-ga/.../decision/DecisionAgentRunner.java`（+348/−92）；
- 少量 `Shell` / `ToolRoleAudit` / `SimosTool` 接线；
- 其余大头是测试与设计文档。

⇒ “ga 的 Agent 实现改成复用库标准件”这句话，**当时实际只覆盖了 `DecisionAgentRunner` 这一条运行流**，不能自动外推到决策包、裁决器、效果执行和决策 HTTP 面。

---

## 2. AgentLib 0.2.1 包级覆盖表

| AgentLib 包 | 当前 Simos 使用 | 判定 |
|---|---|---|
| `agent` | `AgentConfig` | ✅ 已接 |
| `context` | `ContextWindow` / `ContextComposer` / `LlmRoundTrip` | ✅ 已接 |
| `runtime` | `AgentLoop` + 三个 SPI | ✅ 已接（仅 `DecisionAgentRunner`） |
| `llm` | `LlmClient` / `LlmMessage` / `LlmRequest` / `ToolDef` / 路由装配 | ✅ 已接 |
| `tool` | `AgentTool` / `ToolDefs` / `ToolCallAuthorizer` / `ToolExecutionGuard` / `ToolRegistry` / `ToolResult` | ✅ 已接 |
| `permission` | `AgentPermissionSet` / `AccessToken` / `Resource*` / `RoleSet` / `RoleCatalogAudit` | ✅ 已接 |
| `approval` | 基础审批链已接；**上级判定族未接** | ⚠️ 部分 |
| `decision` | **0 个生产 import** | ❌ 未接 |
| `store` | `ConversationStore` / `SqliteConversationStore` | ✅ 已接 |
| `event` | 不用 AgentLib `SqliteEventStore` | ⏸ 有意不复用（见 §4） |
| `plugin` | `ToolSource` | ✅ 已接 |
| `mcp` / `config` | app 侧使用 | ✅ 已接 |

### 2.1 关键事实：`decision` 包零引用

在当前树的生产代码里对以下符号做名称检索，结果均为 **0 个生产文件**：

```text
DecisionPackages        DecisionPackage        PendingDecisionQueue
DecisionTurnScope       CommitReceipt          DecisionCommitPort
DecisionSubmissionPort  DecisionPackagesHttpHandler
```

AgentLib 0.2.1 的 `decision` 包共有 **22 个类**（`find .../agentlib/decision -name '*.java' | wc -l` ⇒ 22），包括：

- 队列/作用域：`PendingDecisionQueue`、`DecisionTurnScope`；
- 包模型：`DecisionPackage`、`PackageItem`、`Decision`、`PackageState`、`ProposeRequest`、`ResolvedCall`；
- 状态机：`DecisionPackages`、`ItemValidator`；
- 提交/回执：`DecisionCommitPort`、`CommitReceipt`、`DecisionSubmission`、`DecisionSubmissionPort`；
- HTTP/审计：`DecisionPackagesHttpHandler`、`DecisionReadAudit`；
- 子体/集成：`InFlightDecisionActions`、`InFlightDecisionPackages`、`SubagentDecisionLedger`、`DecisionReleaseMarker`、`DecisionItemDigest`、`DecisionEventTypes`。

这 22 个类不是“可有可无的工具类”：其中 `PendingDecisionQueue` + `ToolCallAuthorizer` 的 `Ask` 分支，正是 AgentLib 把“本轮所有需要裁决的调用收成一个包”的机制；`DecisionPackages` 是包的状态机；`DecisionCommitPort` 是“一次提交、一条 revision”的宿主缝。

---

## 3. 最大缺口：统一决策包没有迁

### 3.1 Simos 现在的决策包

当前 `simos-ga` 自建了一条**持久化、按 tick 的决策包**链：

| 组件 | 作用 | 证据 |
|---|---|---|
| `GaState.decisionPackets` | 世界状态第 1 组件：一决策人 × 一 tick 一个包 | `simos-ga/.../GaState.java:28,76-85` |
| `DecisionPacket` | 包身份、branch/tick、proposer、intent、calls、createdAtRevision、裁决留痕 | `simos-ga/.../model/DecisionPacket.java:20` 起 |
| `FormattedCall` | 工具名、argsJson、targets、preview、draftChecks、call 状态 | `simos-ga/.../model/FormattedCall.java:24` 起 |
| `SubmitDecisionPacketHandler` | DRAFT → PENDING | `.../spi/SubmitDecisionPacketHandler.java:37` |
| `DecideDecisionPacketHandler` | GM 整包/逐 call/`MERGE` 裁决 | `.../spi/DecideDecisionPacketHandler.java:62` |
| `DecisionEffectExecutor` | 被批子集 + outcome 回写 + 一次 `submitBatch` | `simos-app/.../decision/DecisionEffectExecutor.java:47-65, 318-319` |
| `DecisionTurnFinalizer` | 回合末整理 intent/保留 calls/必要时建空包并提交 | `simos-app/.../decision/DecisionTurnFinalizer.java:63-64` |

### 3.2 AgentLib 的包语义

AgentLib 的包是**内存态、按一轮 Ask 汇总、带 deadline 的统一决策单元**：

- `PendingDecisionQueue`：一个 Agent 一轮里命中的全部 `ToolGate.Ask` 先登记，回合收尾收成一个包；
- `DecisionTurnScope`：把队列绑定到本轮线程作用域；
- `DecisionPackages`：`propose / decide / commit / find / pending / expire` 状态机；
- `DecisionCommitPort`：宿主只负责“被批子集一次提交”，回执用 `CommitReceipt`；
- `DecisionPackagesHttpHandler`：决策面 HTTP 端点（待决/详情/逐条表态/执行）。

并且 `ToolCallAuthorizer` 已经内置这条链：`ToolCallAuthorizer.java:228-242` 的 `Ask` 分支会优先读 `DecisionTurnScope.current()` 的队列；未绑定队列才回落到 `askForApproval()`。

### 3.3 当前 Simos 与它的真实差异

| 维度 | Simos 当前 | AgentLib `decision` | 影响 |
|---|---|---|---|
| 生命周期 | 包是**世界状态**，跨 revision 持久化 | 包是**进程内一轮**，进程退出即失效 | 不能直接替换；必须决定权威状态在哪 |
| 粒度 | 一决策人 × 一 tick 一个包 | 一 proposer 的一个未决包；回合结束汇总 | 需要明确 `tick` 与回合的映射 |
| 提交/修改 | DRAFT/PENDING 可反复修改，GM 决定；有 `MERGED`/`PARTIALLY_APPROVED` | PROPOSED/DECIDED/COMMITTED/REJECTED/CONFLICTED/EXPIRED；逐条 decide + 一次 commit | 现有用户裁定中的“PENDING 不是锁”“同一 tick 可多次提交”不能丢 |
| 条目 | `FormattedCall` 带 schema/预览/targets/draftChecks/outcome | `PackageItem` 只有工具名 + args + 摘要/详情 | 不能为了复用丢掉领域校验信息 |
| 提交口 | `ToolWritePort` + `CoreSimos.submitBatch` | `DecisionCommitPort` + 不透明 `CommitReceipt` | 需要适配层，不能把 core 类型塞进库 |
| 入口 | DM 直接写走审批链；包由显式 `simos.ga.packet.propose` 写入 | 所有 `Ask` 自动进本轮队列 | **行为变化**，必须单独裁定/设计 |
| HTTP | 现有 GM packet 工具，无库的 decision-package HTTP 面 | `DecisionPackagesHttpHandler` 已提供 | 是否接入是产品面问题，不是纯迁移 |

**结论**：这不是“把 import 换一下”的迁移，而是**决策单元权威与生命周期**的架构迁移。要做就必须先冻结“包状态存哪、谁写、如何与 revision 对齐、显式 propose 是否保留、所有 Ask 是否自动进包”这几条；否则会把现有已裁定的 GM/DM 交互语义推翻。

### 3.4 还有一层：`DecisionProposable` / `ProposalCatalog` 的“可提议面”会与库的自动队列冲突

Simos 当前不是“所有 DM 的 `Ask` 自动进包”，而是**显式提议**：

- `DecisionProposable` 住在 `ga-spi`，是“这条工具可被决策人 propose”的工具自声明（`simos-ga-spi/.../DecisionProposable.java:22`）；
- `ProposalCatalog` 住在 app，负责**真跑 `preview=true`** 并提取目标资源（`ProposalCatalog.java` 类注与 `preview()`）；
- `ProposalDeclarationAudit` 在装配期把“可提议工具”与两桶工具面双向钉死（`ProposalDeclarationAudit.java:54-155`）；
- `simos.ga.packet.propose` 才是建包入口；DM 的直接写 `Ask` 仍走 `AutoApproveGate → ConfirmGate → PendingApprovals`，**不自动进包**（`Shell.java:1237-1247` 的审批链，以及 `:1262` 的 `ToolCallAuthorizer` 装配没有绑定 `DecisionTurnScope`）。

AgentLib 的 0.2.1 口径相反：

- `ToolCallAuthorizer` 的 `Ask` 分支优先读 `DecisionTurnScope.current()` 的 `PendingDecisionQueue`；
- 有队列时返回 `DECISION_QUEUED`，**不投审批面、不执行**；
- 回合收尾由一个桥 flush 成一个 `DecisionPackage`；
- AgentLib 没有 `DecisionProposable` 这个声明位；它把“哪些工具可进包”交给**实际命中的 `Ask`**。

⇒ 如果按库的机制迁移，必须二选一并写进设计书：

| 口径 | 结果 |
|---|---|
| **甲：保留显式 propose** | `ProposalCatalog` 继续作为 preview/目标提取；`DecisionProposable` 继续作为目录声明；AgentLib 的 `PendingDecisionQueue` 只处理“直接写但需裁决”的 `Ask`，两条入口仍并存 |
| **乙：切到自动进包** | 所有 DM 的 `Ask` 自动进本轮队列；`DecisionProposable` / `ProposalCatalog` 的角色要重新定义（最多保留 `ProposalCatalog` 作为 `ItemValidator` 的 preview 实现），显式 `propose/confirm` 是否保留要另裁 |

**这是产品语义问题，不是 import 替换问题。** 不能在“验收 S/R”里顺手改掉；也不能假装 AgentLib 已经覆盖了这层。

---

## 4. 第二类缺口：上级判定 / 裁决器

AgentLib `approval` 包里有：

- `SuperiorJudgeGate`（只对 `AskKind.MODE_LIMITED` 做上级判定）；
- `LlmSuperiorJudgement`（一次性 LLM 调用，看审批请求做放行/拒）；
- `SuperiorJudgeDetails` / `SuperiorJudgeDetailRegistry` / `JudgementItem`（给上级看逐条明文）。

Simos 当前：

- 审批链是 `AutoApproveGate → ConfirmGate → PendingApprovals → HTTP`，**没有 Superior 判定**（`Shell.java:1237-1247` 注释明确“无 Superior 判定”）；
- `simos-ga` 另有自己的 `DecisionAdjudicator` / `LlmDecisionAdjudicator`（`.../adjudication/LlmDecisionAdjudicator.java:42`），做的是 **D1–D8 断点的领域 schema 判决**，不是“上级代批某条工具调用”。

两者只有“单次 LLM 调用 + fail-closed + 结果落审计”这一层相似，**不是直接替换关系**。可复用候选是：

- 把 `LlmSuperiorJudgement` 的“一次性调用、超时/异常降级、提示长度预算”作为通用机制参考/抽取；
- 但保留 Simos 的 `AdjudicationSchemas`（D1–D8 的输出形状是领域语义，不应塞进 AgentLib 通用层）；
- 若未来启用“子 Agent 向上级 Agent 请求代批”，再正式接 `SuperiorJudgeGate`；当前没有这条生产链，不能为了“用库”硬接。

### 4.1 `GmAutoApproveGate` 不是 `AutoApproveGate` 的重复

- `GmAutoApproveGate`：GM 面无条件 `APPROVE_ONCE`，是用户 2026-09-24 的明确裁定；
- `AutoApproveGate`：只对已 `APPROVE_SESSION` 的 `(caller, classKey)` 放行。

二者语义不同，**不应合并**。当前 `GmAutoApproveGate` 实现 `ApprovalGate` 已经是正确复用方式。

---

## 5. 明确留在 Simos 的领域件

以下不是“漏迁”，而是不应搬进通用库：

- `DecisionPacket` 的按 tick 持久化语义、`FormattedCall` 的 schema/preview/targets/draftChecks、`MergedEffectPlan` 的合并效果集；
- `DecisionMaker` / `Directive` / `Verdict` / `Effect` / `GaTimeParticipant`；
- `ToolRole` 的枚举值 `GM` / `DM`，以及 `ToolClass` 的三格领域派生（AgentLib 只提供通用角色代数与审计机制）；
- `DecisionPacketViews` 的领域视图字段；
- `CoreSimos.EventStore`：`simos-core/.../EventStore.java:17-24` 已写明**有意不复用** `SqliteEventStore`，因为事件的写路径必须与 revision 同一事务；这不是迁移缺口。

---

## 6. 建议的下一步（先设计，不直接改代码）

### 6.1 先冻结 S/R 的验收边界

- S/R 只证明“循环/上下文 + 角色 + 单空格”三件事；
- 不能把它们当成“ga 的 Agent 实现整体验收”；
- 在决策包迁移设计完成前，不把 `clean verify` 当成最终关账。

### 6.2 另开一份约束设计书（建议编号按仓内惯例）

主题：**把 DM 的所有 `Ask` 统一迁入 AgentLib 决策包，并让 GM 裁决/执行走包状态机**。设计书至少回答：

1. **权威状态**：AgentLib `DecisionPackage` 是运行时状态机；`GaState.decisionPackets` 是持久投影。两者的写入顺序、失败回滚、重启恢复必须唯一。
2. **一 DM × 一 tick 一包**：AgentLib 当前 `DecisionPackages.propose` 是“一 proposer 一个未决包、不能再 append”；Simos 当前同一 tick 可跑 4 轮并都写同一个 `pkt-<dm>-<tick>`（`GmActAsToolTest` 已钉）。设计书必须给出 **append** 语义或等价的宿主 staging；不能把 4 轮拆成 4 个包。
3. **包 id**：AgentLib 生成 `dp-N`，Simos 现有是 `pkt-<dm>-<tick>`；要么由宿主给 id，要么建立稳定映射。优先保持现有 id（外部工具/测试已按它寻址）。
4. **空包 / 纯 NL**：AgentLib `propose` 拒绝空 items；Simos `DecisionPacket` 允许 zero calls + intent。设计书必须选一条：允许空包、增加宿主 metadata、或把自然语言请求做成一个真实 `Ask` 工具入包。
5. **条目映射**：`FormattedCall` 的 schema/preview/targets/draftChecks/outcome 作为领域投影；`PackageItem` 的 `args/argsDigest/summary` 与 `DecisionItemDigest` 对齐。`ProposalCatalog` 保留为 preview/目标提取/`ItemValidator`。
6. **入口语义**：DM 的 `Ask` 全部自动入 `PendingDecisionQueue`；单条 `PendingApprovals` 对 DM 停用。`DecisionProposable` 不再决定“能否进包”，是否退役要在设计书里一次说清。
7. **提交口**：`DecisionCommitPort` 适配 `CoreSimos.submitBatch`，保持“被批子集一次提交、被驳零落盘、回执 id 对得上、写后不抛”。`DecisionEffectExecutor` + `MergedEffectPlan` 的 `MERGED` 分支必须单独映射（AgentLib 没有 `MERGED` 状态）。
8. **失败/过期/回放**：deadline、EXPIRED、进程退出、revision 回放、重复提交幂等。
9. **验收判据**：现有 `DecisionPacket` 的 GM/DM 端到端用例（含同 tick 四轮、PENDING 不是锁、逐 call 裁决、合并计划、GM 代打）必须逐条保留或明确改判。

### 6.3 需要先在 AgentLib 补齐的通用缝（不是 Simos 私造）

按目标口径，AgentLib 现有 `decision` 包还缺三个通用能力，必须先在库侧补（或明确等价 seam）：

- **宿主 package id**：让 `pkt-<dm>-<tick>` 成为稳定包 id，而不是 `dp-N`；
- **append**：同一未决包在变成 `DECIDED` 前可追加条目（多轮同 tick）；
- **snapshot/restore 或 store**：让持久宿主能在重启后重建包状态；否则“包是唯一决策单元”在重启后失效。

如果这三条不补，只在 Simos 里硬接 `DecisionPackages`，一定会出现“库内存态 / 世界持久态”两套状态机——这正是本次审计要避免的假迁移。

### 6.4 可复用的非权威件

- `DecisionItemDigest`：摘要/脱敏/人读摘要；
- `CommitReceipt`：提交回执三结局与逐条 outcomes（作为 AgentLib 对外形状，不引入 core 类型）；
- `LlmSuperiorJudgement`：仅评估“上级代批”的未来场景，不混进本次 DM→GM 包迁移。

---

## 7. 未做 / 未验证

- 未修改任何生产代码；未提交。
- 未运行测试、`clean verify`、真 LLM、前端门禁。
- 未对 `DecisionPackages` 做真实行为验证；本报告的结论是“当前未接 + 需要设计”，不是“库侧实现有缺陷”。
- 未把 AgentLib `decision` 的 22 个类逐方法映射完；本报告只完成了**包级/关键链路覆盖判定**，足够支持下一份设计书立项，不足以直接派实现。
- 未处理 `agentlib-dock-adjudication-check.md` 里“唯一待裁定是合并时序”的旧结论；该结论已被本报告取代：**迁移覆盖面本身尚未闭合**。
