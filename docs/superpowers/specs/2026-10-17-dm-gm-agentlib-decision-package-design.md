# 2026-10-17 DM→GM 决策包迁移：AgentLib 五工具 + Core 持久化（约束设计书）

> **状态**：Simos 生产实现已完成（未提交）；AgentLib `0.2.3-SNAPSHOT` U1 已交付并含分段表态修正；`clean verify` rc=0。  
> **上游需求明细**：`docs/superpowers/plans/2026-10-17-agentlib-decision-package-read-port-needs.md`。
> **本文取代**：`docs/superpowers/reports/2026-10-17-dm-gm-decision-package-migration-options.md`（2026-10-17）——该文只保留为方案对比留痕，后续以本文为准。
> **分支/工作树**：`agentlib/dock-0.2.1` / `.claude/worktrees/agentlib-dock`（基点 `03faf0a3` = main）。
> **上游事实**：AgentLib `v0.2.2`（`c46e777`）+ `0.2.3-SNAPSHOT`（读口提交 `0481401`）；本文按真实实现设计，不按 0.2.1 的旧假设设计。
> **前置状态**：§4 的 U1 已实现（`DecisionPackageReadPort` / 五工具端口重载）；本机 `~/.m2` 已有 `0.2.3-SNAPSHOT` 安装件。Simos 侧 S1/S2/T 已完成，`clean verify` 通过。

---

## 0. 用户原话附录（逐字，不改写）

**2026-10-17（本次设计目标）**：

> 「肯定要复用啊，不复用我写出来干什么？ga模块里有互斥内容优先复用agentlib，持久化直接包给core不得了」

**2026-10-17（对 HTTP 的态度）**：

> 「然后是限制2，不是有通用的MCPtool吗？http被保留本来就是无奈之选」

**2026-10-17（此前对“所有 Ask 进包”的语义）**：

> 「我在隔壁Agentlib搞通用决策包就是为了适配这里的DM-GM流程……所有子Agent执行的需要判决的工具都进包，到这翻译一下自然是所有DM执行的需要GM确认的都进包，是同一个语义在不同项目里的翻版，根据这个思路思考如何迁移」

**已确认的既有裁定（不是本次重新提问）**：

- `revise` / `drop` / `intent` 保留；它们必须是**非 `Ask`** 的包控制面，否则会自己再入包。
- 新增真实纯文本 `request` 工具；纯 NL 请求也是包内一条，而不是空包。
- `MERGED` 是 Simos 领域状态，不要求 AgentLib `PackageState` 认识它。
- HTTP 审批面不是必须保留；MCP 决策面是主路径。

---

## 1. 目标 / 非目标

### 1.1 目标

1. **统一入口**：DM 的每一次 `ToolGate.Ask` 工具调用都进入本轮待裁决队列；回合末进入该 DM 当前 tick 的同一个决策包。DM 不再走单条 `PendingApprovals`。
2. **唯一包状态机**：包状态只由 AgentLib 的 `DecisionPackageTransitions` / `DecisionPackageTools` 驱动；`DecisionPacket` 降为只读领域投影，不再自己迁状态。
3. **复用五工具**：GM 的决策面直接注册 AgentLib 的五个 MCP 工具：
   `decision_package_list` / `decision_package_get` / `decision_package_decide` /
   `decision_package_decide_item` / `decision_package_commit`。
4. **持久化归 core**：`DecisionPackageSnapshot` 随 `GaState` 进入 `Command → ChangeSet → Revision`；core 是世界状态权威，不另建 ga 侧持久表。
5. **重启可继续**：进程重启后，GM 仍能 list/get/decide/commit 重启前的包；不依赖 `DecisionPackages` 的进程内表。
6. **HTTP 退役**：不把 `ApprovalHttpEndpoint` 搬到 Simos；旧单条审批 MCP 面一并退役。只有确实存在非包 `Ask` 时才保留内部兜底。
7. **行为可回放**：所有包状态、领域投影、效果、outcome 都在同一套 revision 语义下；不使用 `System.currentTimeMillis()` 参与世界状态。

### 1.2 非目标

- 不要求 Brain 迁移；AgentLib 的 `DecisionPackages` 仍是 Brain 的内存适配器，Simos 不使用它。
- 不把 `DecisionPacket`/`FormattedCall` 直接塞进 AgentLib；领域字段仍属 Simos。
- 不要求 AgentLib 认识 tick、branch、DM/GM、`MERGED`、preview、targets。
- 本批不重新设计 `MergedEffectPlan` 的内部语义；只改它与包状态的衔接。
- 不把 `PendingDecisionQueue` 的 30 分钟默认 deadline 当世界时钟；tick 生命周期由 Simos 决定。

---

## 2. 现状与事实（回代码核实）

### 2.1 AgentLib 0.2.2 已具备的能力

| 能力 | 证据 |
|---|---|
| 宿主给包 id | `AgentLibMosire/src/main/java/io/mosire/agentlib/decision/DecisionPackages.java:322` |
| 同包 append / replace / remove | 同文件 `:381` / `:412` / `:443`；纯核心 `DecisionPackageTransitions.java:88` / `:140` / `:181` |
| 完整快照 | `DecisionPackageSnapshot.java:29`；`DecisionPackages.java:987` |
| 纯状态转移 | `DecisionPackageTransitions.java:50/88/140/181/215/264/310/361/417` |
| 一次提交计划 | `DecisionCommitPlan.java`；`DecisionCommitPort.java` 的 `commit(packageId, plan)` |
| 五个 MCP 决策工具 | `DecisionPackageTools.java:91` 构造；`:286` 起五个工具 |
| 旧内存适配器 | `DecisionPackages` 类注仍明确“不落库、进程退出失效”；`snapshot()` 只有导出，没有 `restore(snapshot)` |

**关键事实**：`DecisionPackageTools` 的构造参数是 concrete `DecisionPackages`（`DecisionPackageTools.java:91`），读口直接调 `packages.find/pending/all/itemVerdicts/expiryReason`（`:286-346` 等）。因此：

- 若直接使用 0.2.2 的 `DecisionPackageTools`，就必须让 `DecisionPackages` 在进程内有一份完整表；
- core 持久层有快照**不会**自动让这张进程内表有记录；
- 0.2.2 没有 `restore`，重启后五工具会看到空表。

这不是“core 没存”，而是“core 到 AgentLib 运行态表之间没有读口”。

### 2.2 Simos 现状

| 现状 | 证据 |
|---|---|
| `GaState.decisionPackets` 是 `Map<DecisionPacketId, DecisionPacket>` | `simos-ga/.../GaState.java:76-83` |
| `DecisionPacket` 自己承载 status/calls/decision 留痕 | `simos-ga/.../model/DecisionPacket.java:20-32` |
| 回合末 `DecisionTurnFinalizer` 读/写 `DecisionPacket` | `simos-app/.../decision/DecisionTurnFinalizer.java:151-153, 238-281` |
| 旧的决策写工具 | `GmDecidePacketTool` / `GmPacketExecuteTool` 等 |
| 旧的单条审批链 | `Shell.java:1236-1268` 建 `PendingApprovals`、`HttpApprovalChannel`、`ApprovalHttpEndpoint` |
| DM 的 `Ask` 仍可落到单条审批 | 同一段 `ToolCallAuthorizer` 装配；目前没有在 DM 回合绑定 `DecisionTurnScope` |
| `DecisionProposable` / `ProposalCatalog` 是“可提议面” | `simos-ga-spi/.../DecisionProposable.java`；`simos-app/.../decision/ProposalCatalog.java` |

### 2.3 0.2.2 的 release 级兼容红点

0.2.2 把审批/决策 HTTP 适配器移出 AgentLib core：

- `HttpApprovalChannel` 删除；
- `ApprovalHttpEndpoint` 移到 `MainMosire` 的 `io.mosire.main.approval`；
- `DecisionPackagesHttpHandler` 也从 AgentLib core 删除（Simos 生产代码零引用，本项无影响）。

Simos 当前直接 import `ApprovalHttpEndpoint` 与 `HttpApprovalChannel`（`Shell.java:5,8`），所以**直接升 0.2.2 会编译红**。这不是保留 HTTP 的理由；按用户口径，HTTP 本来就要退役，见 §8.5。

---

## 3. 固定架构（一句话）

```text
DM 回合
  ToolCallAuthorizer(Ask)
    -> PendingDecisionQueue(本轮)
    -> DecisionTurnFinalizer
    -> 构造 DecisionRecord(snapshot += 本轮 items)
    -> ga.ApplyDecisionPackage（core 一条 revision）

GM MCP
  AgentLib DecisionPackageTools(五工具)
    -> DecisionPackageReadPort（CoreDecisionPackageReadPort，读 GaState）
    -> DecisionSubmissionPort（CoreDecisionSubmissionPort，写 GaState）

执行
  DecisionPackageTransitions.prepareCommit
    -> DecisionEffectExecutor（效果 + outcome + 最终快照同一批）
    -> completeCommit / CommitReceipt
    -> core.submitBatch 一条 revision
```

**唯一权威**：

1. 包状态机权威 = AgentLib `DecisionPackageSnapshot`；
2. 世界持久化权威 = `GaState`/core revision；
3. Simos 领域投影 = `DecisionPacket` 视图（只读）；
4. Simos 领域扩展 = `DecisionRecord` 中的 branch/tick/intent/call 预览/mergedPlanId/outcome。

---

## 4. 上游 U1：把五工具从内存表解耦（AgentLib 0.2.3-SNAPSHOT 已实现）

### 4.1 为什么必须做

`DecisionPackageTools` 需要一个“能找到包”的读口；写入已经通过 `DecisionSubmissionPort` 解耦。把读口也抽成接口后，Simos 就能：

- 用 core 的 `DecisionPackageSnapshot` 实现读；
- 不构造 `DecisionPackages` 内存表；
- 不需要 `restore(snapshot)`；
- 保持 Brain 现有 `DecisionPackages` 用法不变。

### 4.2 已实现的最小增量

```java
public interface DecisionPackageReadPort {
  Optional<DecisionPackage> find(String packageId);
  List<DecisionPackage> pending();
  List<DecisionPackage> all();
  List<DecisionItemVerdict> verdicts(String packageId);
  Optional<String> expiryReason(String packageId);
}
```

- `DecisionPackages implements DecisionPackageReadPort`；现有方法签名行为不变；旧 `itemVerdicts(String)` 保留，`verdicts(String)` 返回顶层 `DecisionItemVerdict`（与 `DecisionPackageSnapshot.verdicts()` 同型）。
- `DecisionPackageTools` 的 `packages` 字段/构造参数改为 `DecisionPackageReadPort`；三参/全参构造器与 `of`/`all`/`tools` 工厂均保留旧 `DecisionPackages` 重载，并新增端口重载。
- 新测试 `DecisionPackageReadPortToolsTest` 5/5 绿；现有 `DecisionPackageToolsTest` 11/11、`DecisionPackagesTest` 23/23 保持绿。
- 不改 `DecisionPackageTransitions`、`DecisionPackageSnapshot`、`CommitReceipt` 或 `DecisionCommitPort`。
- 版本：上游已进入 `0.2.3-SNAPSHOT`（`pom.xml`），本机 `~/.m2` 已有安装 jar；Simos 父 POM 尚未升级。

### 4.3 本文对 AgentLib 其余部分的使用边界

| 类 | Simos 是否使用 |
|---|---|
| `DecisionPackageTools` | **使用**（五工具注册到 GM） |
| `DecisionPackageTransitions` | **使用**（所有状态转移） |
| `DecisionPackageSnapshot` | **使用**（世界状态的一部分） |
| `DecisionCommitPlan` / `DecisionCommitPort` | **使用**（commit 计划与回执） |
| `PendingDecisionQueue` / `DecisionTurnScope` | **使用**（DM Ask 收集） |
| `DecisionPackages` | **不使用**（Brain 内存适配器；Simos 无此表） |
| `DecisionPackagesHttpHandler` | **不使用**（已移出 core） |
| `SuperiorJudgeGate` / `LlmSuperiorJudgement` | 本批不使用；不冒充替代 `LlmDecisionAdjudicator` |

---

## 5. Simos 状态模型：`DecisionRecord` 是唯一包表

### 5.1 `GaState` 换值类型

```java
// 旧：Map<DecisionPacketId, DecisionPacket>
// 新：Map<DecisionPacketId, DecisionRecord>
public record GaState(
    Map<DecisionPacketId, DecisionRecord> decisionPackets,
    Map<MergedEffectPlanId, MergedEffectPlan> mergedEffectPlans,
    Map<DirectiveId, Directive> directives,
    Map<VerdictId, Verdict> verdicts,
    Map<DecisionMakerId, DecisionMaker> decisionMakers,
    Map<String, List<DecisionInfoEntry>> info,
    Map<EffectId, Effect> effects) { }
```

- `decisionPackets` 的键仍是 `DecisionPacketId`；包 id 仍是 `pkt-<dm>-<tick>`。
- 不新增第二张 `snapshot` 表；`DecisionRecord` 内部带 `DecisionPackageSnapshot`，这样同一个包只有一条世界状态记录。
- 旧状态（`DecisionPacket` 值）按 Alpha 口径直接重建，不写旧档迁移；但要保留合法的缺键读法（`null` → 空表）。

### 5.2 `DecisionRecord`

```java
public record DecisionRecord(
    DecisionPacketId id,
    String branch,
    long tick,
    DecisionMakerId proposerId,
    String intent,                         // 领域 NL 轨；不参与 AgentLib 状态机
    long createdAtRevision,
    OptionalLong confirmedAtRevision,       // DM confirm 的领域标记，不改 PackageState
    Optional<String> reasonInfoId,
    OptionalLong decidedAtRevision,
    Map<Integer, DecisionCallMeta> callMeta,
    DecisionPackageSnapshot packageState    // 唯一状态机；items/state/decision/verdicts/receipt
) { }
```

`DecisionCallMeta`：

```java
public record DecisionCallMeta(
    List<CommandTarget> targets,
    String previewJson,
    List<String> draftChecks,
    Optional<String> mergedPlanId,
    Optional<String> outcomeJson) { }
```

`DecisionCallMeta` 只存 AgentLib `PackageItem` 装不下的 Simos 领域展示/执行信息；`toolName`/`args` 以 `packageState.pkg().items()` 为准，不复制。

### 5.3 `DecisionRecord` 构造期不变量

1. map key `id` == `packageState.pkg().id()`（字符串逐字相等）。
2. `proposerId.value()` == `packageState.pkg().proposerKey()`。
3. `branch` 非空白；`tick >= 0`；`createdAtRevision >= 0`。
4. `callMeta` 的键集合 == `packageState.pkg().items()` 的 index 集合，不多不少。
5. `packageState.contentVersion() >= 1`；`packageState.pkg().state()` 为 `PROPOSED` 时 `decision == null`。
6. `packageState.pkg().decision() != null` 时，`decidedAtRevision` 必须存在；没有裁决时不得伪造。
7. `confirmedAtRevision` 只影响 `DecisionPacket` 的领域视图，不改变 AgentLib 状态。
8. `mergedPlanId` 只在 Simos 领域投影里出现；`packageState` 不新增 `MERGED`。

### 5.4 `DecisionPacket` 降为只读视图

- 保留 `DecisionPacket` 类型，供现有 GUI/读工具迁移；但它只能由 `DecisionRecord.view()` 构造。
- `PacketStatus.DRAFT` 不再由生产路径产生；它只作为旧测试/旧线格式的留痕枚举，新的 `PROPOSED` 包视图统一显示 `PENDING`。
- 删除/禁止 `DecisionPacket.withStatus(...)`、`withDecision(...)`、`withCalls(...)` 作为生产写路径（编译期移除或隐藏）。
- `PacketStatus`/`CallStatus` 保留为**纯视图枚举**，映射如下：

| AgentLib 快照 | `DecisionPacket.status` | `FormattedCall.status` |
|---|---|---|
| `PROPOSED` | `PENDING`（不再是可编辑草稿锁） | `PENDING` |
| `DECIDING` | `PENDING` | 已表态：`APPROVED/REJECTED`；未表态：`PENDING` |
| `DECIDED` | 全批 → `APPROVED`；部分 → `PARTIALLY_APPROVED`；全驳 → `REJECTED` | 按 decision/verdict |
| `COMMITTED` | 按 decision 全批/部分；`outcomeJson` 有值 | 按 decision；`outcomeJson` 有值 |
| `REJECTED` | `REJECTED` | 按 decision/verdict |
| `CONFLICTED` / `EXPIRED` | `REJECTED`（详情另行给 `packageState`） | 按 decision/verdict |
| 领域 `mergedPlanId` 存在 | `MERGED` | `MERGED` |

> 映射是**展示口径**；精确状态始终在 `DecisionRecord.packageState().pkg().state()`。新的 GM 读工具必须把 `packageState` 一并输出，不允许用旧 `PacketStatus` 反推包状态。

### 5.5 `DecisionRecord` 的读/写口

- `CoreDecisionPackageReadPort`（app 层）实现 §4.2 的 `DecisionPackageReadPort`：
  - `find(id)`：从当前 head 的 `GaState.decisionPackets` 取 `record`，返回 `record.packageState().pkg()`；**包括旧 tick 的历史包**；
  - `pending()`：当前 tick、`PROPOSED` 或 `DECIDING` 且没有 `mergedPlanId` 的包；
  - `all()`：**五工具列表的当前 tick 宇宙**（不是历史全表），过滤已经进入 `mergedPlanId` 的包，避免五工具的 `awaitingCommit` 把 MERGED 当可 commit；历史包仍可由 `find(id)` 按 id 读；
  - `verdicts(id)`：直接返回 `packageState.verdicts()`（`List<DecisionItemVerdict>`），不经过旧内存适配器类型；
  - `expiryReason(id)`：`packageState.expiryReason()`。
- 分支绑定：`DecisionPackageTools` 没有 branch 参数，因此 `CoreDecisionPackageReadPort` 在装配时绑定 `ToolResources.DEFAULT_BRANCH`（或宿主当前分支）；跨分支查询另开设计。
- 读口只读当前 head；不持有可变缓存，避免重启/回放分叉。

---

## 6. 持久化命令与事务边界

### 6.1 新命令 `ga.ApplyDecisionPackage`

所有包状态写入都通过一条内部命令：

```text
type: ga.ApplyDecisionPackage
payload:
{
  "expectedContentVersion": 1,   // 0 = 新建；否则必须等于当前 snapshot.contentVersion
  "record": { ...DecisionRecord... }
}
```

- `targetPaths`: `ga:decision-package.<id>`。
- Handler：
  1. 读 base `GaState`；按 id 找旧 `DecisionRecord`；
  2. `expectedContentVersion` 校验：新建 0、更新必须等于旧 `packageState.contentVersion()`；不匹配 → 具名 `PACKAGE_VERSION_STALE`，零变化；
  3. `DecisionRecord` 构造期校验 + key/id/proposer/branch/tick 一致性；
  4. 写回 `decisionPackets`，返回 `GaChangeSet.between(base, next)`。
- 命令不直接暴露给 DM/GM 工具；由 §7/§8 的 app 层端口生成。

### 6.2 旧命令去留

| 旧件 | 处置 |
|---|---|
| `ga.UpsertDecisionPacket` | 退役 |
| `ga.SubmitDecisionPacket` | 退役 |
| `ga.DecideDecisionPacket` | 退役 |
| `UpsertDecisionPacketHandler` / `SubmitDecisionPacketHandler` / `DecideDecisionPacketHandler` | 删除 |
| `DecisionPacketPayloads.upsert/submit/decide` | 删除；只保留合并计划载荷 |
| `DecisionPacket.withStatus/withDecision/withCalls` | 删除或降为测试视图构造器，不得生产写 |

原因：这些入口各自带一套状态语义，继续存在就会与 AgentLib 包状态机形成第二套真相。**不使用旧命令做兼容壳。**

### 6.3 执行事务：效果 + outcome + 最终快照同一批

`DecisionEffectExecutor` 必须从“写 `UpsertDecisionPacket`”改为：

1. 读 `DecisionRecord`，得到 `DecisionPackageSnapshot before`；
2. `DecisionPackageTransitions.prepareCommit(before, now)` → `DecisionCommitPlan`；
3. 把 `ResolvedCall(index, toolName, args)` 逐条映射成 `EffectCall`：`packetId = record.id.value()`、`callIndex = index`、`argsJson = Jackson(args)`、`sourceCallRefs = []`；然后规划效果命令；
4. 预计算成功后的 `CommitReceipt`（`revisionRef = String.valueOf(expectedRevision + 1)`；`CommandBus` 的新 revision 是 base+1，见 `BatchResult` 文档）；
5. `DecisionPackageTransitions.completeCommit(plan, receipt)` → after snapshot；
6. 用 effect/outcome 的 callMeta 更新 `DecisionRecord`；
7. 批尾加一条 `ga.ApplyDecisionPackage`，与效果命令同 `branch/expectedRevision/batchId`；
8. 一次 `core.submitBatch`；
9. 实际 `BatchResult.Committed.ref().revision()` 必须等于 `expectedRevision + 1`；不等则记录并 fail-closed（已提交后的对账故障，不得改写 receipt）。

- 被批子集为空：按 AgentLib 语义直接 `REJECTED`，零效果命令、零 `ga.ApplyDecisionPackage` 之外的效果写入；如果没有 approved，`prepareCommit` 具名拒，调用方返回 `NO_ITEM_DECIDED`。
- 冲突/拒：整批零 revision；`DecisionRecord` 保持原样；`DecisionSubmission.receipt == null` 并带 `commitNote`。
- `MERGED` 包：`CoreDecisionSubmissionPort.commitPackage` 在发现 `callMeta` 有 `mergedPlanId` 时返回 `PACKAGE_MERGED`；合并计划执行器是唯一执行路径，执行成功后用 §6.3 同样的 receipt/after snapshot 逻辑写回来源包。

### 6.4 时间与 deadline

- 世界状态不使用 `System.currentTimeMillis()`。
- `SimosDecisionClock.NO_WALL_CLOCK_DEADLINE = Long.MAX_VALUE`：所有 `ProposeRequest.deadlineEpochMs` 都用它，不启用 30 分钟墙钟超时。
- `SimosDecisionClock.QUEUE_DEADLINE_MILLIS = Long.MAX_VALUE / 2`：只用于 `PendingDecisionQueue` 的构造期正值校验；队列产出的 request 会被 `toPackageRequest(...)` 重新写成上面的哨兵，绝不让 `now + max` 溢出。
- `DecisionPackageTransitions` 的 `nowEpochMs` 使用可回放的逻辑时刻（实现统一定义为 `tick` 或 0 的单调值；不得混入真实墙钟）。
- `DecisionPackage.createdAtEpochMs` 只作为 AgentLib 记录字段；Simos 的权威建包时间仍是 `createdAtRevision`。
- 到点/作废：本批不自动按 tick 过期；旧 `PROPOSED` 包保留，GM 仍可按 id 查。`CoreDecisionPackageReadPort.pending()` 只列当前 tick，避免旧包混入当前待裁列表；历史仍可 `find`。

---

## 7. DM 流程：所有 `Ask` 进包

### 7.1 回合绑定

`DecisionAgentRunner`（以及 GM actAs 的 DM 调用执行器）在每轮：

```java
PendingDecisionQueue queue = new PendingDecisionQueue(
    decisionMakerId.value(),
    AccessToken.DEFAULT,
    SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
try (DecisionTurnScope ignored = DecisionTurnScope.bind(queue)) {
    agentLoop.runTurn(...);
} finally {
    DecisionTurnScope.clear();
}
// queue 是局部引用，随 DecisionTurn 交回 finalizer；clear 只清线程作用域，不丢对象
```

- `ToolCallAuthorizer` 的 `Ask` 分支已优先读 `DecisionTurnScope.current()`，命中即 `DECISION_QUEUED`，不执行、不投 `PendingApprovals`；本设计不修改这个分支。
- `Block` 仍硬拒；`Allow` 仍直执行；本设计只改 `Ask` 的回合上下文装配。
- GM actAs 走同一条 `DecisionTurnQueueExecutor`：每次调用仍进入该 DM 当前 tick 的同一个包。

### 7.2 回合末 flush

`DecisionTurnFinalizer` 改为“包追加器”：

1. 取本回合 queue 的条目：`SimosDecisionClock.toPackageRequest(queue, now)` 先调用 `queue.toProposeRequest(now)` 消费队列，再用同一组 items 重建 `ProposeRequest`，把 `deadlineEpochMs` 固定为 `NO_WALL_CLOCK_DEADLINE`；
2. 计算 `pkt-<dm>-<tick>`；
3. 读 `DecisionRecord`：
   - 不存在：`DecisionPackageTransitions.propose(id, request, now)` 建包；
   - 存在且 `PROPOSED` 且无 verdict：把 queue 条目重新编号为 `maxIndex+1..`，`DecisionPackageTransitions.append(...)`；
   - 存在但内容已冻结/已裁决：本回合 flush 具名失败 `PACKAGE_CONTENT_FROZEN`，本回合不入包、不执行；DM 回合标记失败；
4. 为每条新 `PackageItem` 调 `DecisionCallPreviewer`（原 `ProposalCatalog` 的 preview/target 提取）生成 `DecisionCallMeta`；preview 失败则整批零写，不删条目；
5. 若本回合没有显式 `intent`，把整轮最后一段非空 assistant 文本写入 `DecisionRecord.intent`（领域 NL 轨）；
6. 若 queue 为空、包不存在、最后一段文本非空：追加一条 `simos.ga.request` 包条目（text = 最后一段文本），保证纯文本不静默丢失；
7. 用一条 `ga.ApplyDecisionPackage` 提交（同 turn 的审计 `ga.PutInfo` 可在同一批）；
8. 若 core 返回冲突：重读 head 重试一次；第二次冲突则 fail loud，不部分写入。

> `PENDING` 不是锁；`confirmedAtRevision` 也不锁内容。唯一冻结点是 AgentLib 的第一条逐条裁决（`PACKAGE_CONTENT_FROZEN`）。DM 若在 GM 已开始逐条裁决后再跑，回合启动守卫应直接拒绝（见 §9 不变量 I6），而不是让队列在回合末才发现。

### 7.3 DM 工具面

| 工具 | Gate | 语义 |
|---|---|---|
| `simos.ga.request` | `Ask` | 纯文本请求；进入包，作为 `PackageItem`；不产生世界写 |
| `simos.ga.packet.intent` | `Allow` | 改 `DecisionRecord.intent`；不改 AgentLib 状态；冻结后拒 |
| `simos.ga.packet.revise` | `Allow` | `DecisionPackageTransitions.replaceItem` + 重算 `DecisionCallMeta`；冻结后拒 |
| `simos.ga.packet.drop` | `Allow` | `DecisionPackageTransitions.removeItem` + 删 meta；冻结后拒 |
| `simos.ga.packet.confirm` | `Allow` | 只写 `confirmedAtRevision` 领域标记；不改 `PackageState` |
| `simos.ga.packet.reopen` | `Allow` | 清 `confirmedAtRevision`；已裁决后拒 |
| `simos.ga.packet.propose` | —— | **删除**（所有 Ask 自动入包，不再需要显式 propose） |
| `simos.ga.packet.my` | 只读 | 从 `DecisionRecord.view()` 读自己的包 |

- `intent`/`confirm` 在没有包时返回 `PACKAGE_NOT_FOUND`；纯 NL 的首条应由 `request` 建立。
- `revise`/`drop`/`intent`/`confirm`/`reopen` 都**不得**是 `Ask`，否则会递归入包。
- `DecisionProposable` 与 `ProposalDeclarationAudit` 退役；`ProposalCatalog` 改名/收窄为 `DecisionCallPreviewer`，只负责“真 preview + targets + draftChecks”，不再回答“能否入包”。能否入包由 `ToolGate.Ask` 与权限链决定。

---

## 8. GM 流程：直接复用五工具

### 8.1 注册

在 GM 工具面注册：

```java
DecisionPackageTools.all(
    coreDecisionReadPort,       // DecisionPackageReadPort
    coreDecisionSubmissionPort, // DecisionSubmissionPort
    Set.of(ToolRole.GM.name()))
```

- 五个工具名、参数 schema、错误码全部沿用 0.2.2 `DecisionPackageTools`，不在 Simos 重写。
- `callerOf(ToolContext)` 从身份取 `by`；模型参数里的 `by` 不采信。
- 旧的 `GmDecidePacketTool` / `GmPacketExecuteTool` 删除；`GmApprovalsTool` / `GmApproveTool` 删除；`GmPacketsTool` 保留为**只读摘要**（不迁状态、不写），`GmPacketTool` 保留为**只读领域投影**（见 §8.3）。
- `GmMergedPlanUpsertTool` / `GmMergedPlanApplyTool` 保留为 Simos 领域工具。

### 8.2 写口 `CoreDecisionSubmissionPort`

实现 `DecisionSubmissionPort`（AgentLib 接口）：

| 方法 | 实现 |
|---|---|
| `submitDecision` | 读 `DecisionRecord`；`DecisionPackageTransitions.decide(current, currentVersion, decision, now)`；提交 `ga.ApplyDecisionPackage`；返回 `DecisionSubmission(newPkg, null, "尚未执行")` |
| `itemDecided` | 同上走 `itemDecided`；返回 `DecisionSubmission` |
| `commitPackage` | 读记录 → `prepareCommit` → `DecisionEffectExecutor` 的效果/outcome/最终快照同批 → `DecisionSubmission(afterPkg, receipt, "")` |

- 所有失败映射为 `DecisionPackages.RejectionException` 的既有码（`PACKAGE_NOT_FOUND` / `PACKAGE_VERSION_STALE` / `PACKAGE_CONTENT_FROZEN` / `NO_ITEM_DECIDED` / `PACKAGE_NOT_DECIDED` / `COMMIT_FAILED` / `PACKAGE_MERGED`）。
- 不捕获后改写成成功；不把 core 的 `BatchResult` 类型泄进 AgentLib。

### 8.3 领域读投影

`DecisionPackageTools` 的 `list/get` 给出包 id/状态/条目/toolName/args；它没有 Simos 的 `intent`、`previewJson`、`targets`、`draftChecks`、`mergedPlanId`。

保留一个只读 `simos.gm.packet`：

- 输入：`{packageId}`；
- 数据源：`DecisionRecord.view()`，不读 `DecisionPackages`，不迁状态；
- 输出：现有 `DecisionPacket` 视图字段 + `packageState` + `verdicts` + `intent` + 每 call 的 `preview/targets/draftChecks/mergedPlanId/outcomeJson`；
- 它**只读**，不是第二套决策面；任何裁决/执行仍必须走 §8.1 五工具。
- `GmPacketsTool` 保留为只读摘要（当前实现），列表能力同时由 `decision_package_list` 承担；它不得写包或迁状态。

### 8.4 并发

- `CoreDecisionSubmissionPort` 每次写都从当前 head 读记录，并用 `CommandEnvelope.expectedRevision` 乐观锁提交。
- DM 回合 flush 与 GM decide 并发时，只有一个 revision 成功；另一个得到 `Conflict`/`PACKAGE_VERSION_STALE`，零部分写入。
- 五工具不持有包内存缓存，因此冲突不会留下“工具显示已裁、世界未裁”的分叉。

### 8.5 HTTP 审批面：退役，不搬家

用户已明确 HTTP 是“无奈之选”，因此本设计不把 `ApprovalHttpEndpoint` 放进 Simos，也不保留 `/api/approvals` 代理。处置：

- 删除 `Shell` 的 `HttpApprovalChannel` / `ApprovalHttpEndpoint` 字段、构造、start/close 生命周期与端口配置；
- 删除 GUI 的 `/api/approvals` 透传路由与 `approvalPort` 相关测试；
- 删除 `GmApprovalsTool` / `GmApproveTool`（它们读写 `PendingApprovals`，与新包决策面互斥）；
- DM 的 `Ask` 已由 `DecisionTurnScope` 全量入包；不再需要单条审批 HTTP；
- 若仍有非 DM 的 `Ask` 路径，必须显式设计其审批面或 fail-closed，**不得**重新偷偷引入 HTTP；
- `GmAutoApproveGate`（GM 面自动批准链）如仍被 GM 工具使用，只保留其内部审批判断，不保留外部 HTTP 通道。

---

## 9. 不变量（实现必须守住）

- **I1**：DM 的每个 `ToolGate.Ask` 在绑定队列时只入 `PendingDecisionQueue`，不执行、不进 `PendingApprovals`、不阻塞等审批。
- **I2**：`ToolGate.Block` 硬拒；`ToolGate.Allow` 直执行；本迁移不改变二者。
- **I3**：一 DM × 一 tick × 一包；包 id 固定 `pkt-<dmId>-<tick>`；同 tick 多轮 append，index 只增、remove 留洞。
- **I4**：`AgentLib PackageState` 是唯一状态机；`DecisionPacket.status` 不得作为写状态；`PacketStatus` 只用于展示。
- **I5**：`core` 是唯一持久化权威；进程重启后五工具仍能读到包；不依赖 `DecisionPackages` 内存表，不需要 `restore`。
- **I6**：第一条逐条裁决后，内容冻结：append/replace/remove/intent 全部 `PACKAGE_CONTENT_FROZEN`；DM 回合启动守卫不得让新 Ask 在冻结后进入队列后静默丢失。
- **I7**：一次 commit = 一个包 = 一条 revision；效果、outcome、最终 `DecisionRecord` 同批落盘；冲突零落盘。
- **I8**：`MERGED` 只存在于 Simos 领域投影；`decision_package_commit` 不得执行已有 `mergedPlanId` 的包。
- **I9**：任何工具/读口都没装配时 fail-closed，不得回空列表冒充“没有待裁决项”。
- **I10**：世界状态中不出现 `System.currentTimeMillis()`；时间参数可回放。
- **I11**：旧命令（Upsert/Submit/Decide）不得保留为旁路写口。

---

## 10. 验收判据

### 10.1 DM

- **D1**：DM 工具 `Ask` 在绑定队列后返回 `DECISION_QUEUED`；`PendingApprovals.pending()` 不增长；工具目标不执行。
- **D2**：同一 DM 同 tick 连跑 4 轮，四个 Ask 都进同一个 `pkt-<dm>-<tick>`，callIndex 稳定递增；现有 `GmActAsToolTest` 的同 tick 判据保留。
- **D3**：`request` 入包为一条 `simos.ga.request`，不产生世界写；纯文本最终回合有兜底入包。
- **D4**：`revise` / `drop` / `intent` 是 `Allow`，不触发队列；首次 GM 逐条表态后全部具名拒。
- **D5**：`confirm`/`reopen` 只改领域标记，不改变 `PackageState`；已裁决后 `reopen` 拒。
- **D6**：GM 已开始逐条裁决后，DM 回合启动守卫拒绝；不出现“模型收到 queued、回合末静默丢失”。

### 10.2 GM

- **G1**：五个工具在 GM 工具面可见且角色为 `{GM}`；非 GM 调用被权限面拒。
- **G2**：`decision_package_list` 只列当前 tick 的待裁/已批未执行；不把 MERGED 包列成 awaitingCommit。
- **G3**：`decision_package_get` 默认不含参数明文；`full=true` 才含 args，并按 AgentLib `DecisionReadAudit` 落审计。
- **G4**：`decision_package_decide` / `_decide_item` 修改 `PackageState`/verdicts；重复表态/未知 index/过期包具名拒。
- **G5**：`decision_package_commit` 只提交被批子集；一次 revision；重复提交返回同一 receipt，不二次执行。
- **G6**：`simos.gm.packet` 只读，能显示 `intent`/preview/targets/draftChecks/mergedPlanId/outcomeJson，不能改状态。

### 10.3 持久化 / 重启

- **P1**：写入包后重启进程（重新打开同一 store/replay head），`decision_package_list`/`get` 仍返回该包；`decide`/`commit` 可继续。
- **P2**：重启后没有 `DecisionPackages` 内存表；生产代码无 `new DecisionPackages`、无内存表字段/参数/构造注入。允许 import `DecisionPackages.RejectionException` 作为协议异常（它不是状态表）。
- **P3**：断点前是 `PROPOSED`、`DECIDING`、`DECIDED`、`COMMITTED` 的包均能读回；`contentVersion`、verdicts、receipt、expiryReason 不丢。
- **P4**：JSON/Codec 往返 `GaState`（七张表全非空）逐字段相等；`DecisionRecord.packageState` 往返不丢。

### 10.4 旧审批面退役

- **H1**：生产代码无 `HttpApprovalChannel` / `ApprovalHttpEndpoint` / `DecisionPackagesHttpHandler` 引用。
- **H2**：GUI `/api/approvals` 路由删除，或返回 404 且不代理到不存在的端点；旧 `GmApprovalsTool` / `GmApproveTool` 不再注册。
- **H3**：没有非包 `Ask` 仍被静默放行；GM 工具链仍走 `GmAutoApproveGate`（如保留），DM 链不再走单条审批。
- **H4**：`AgentLibAvailabilityTest` 不再硬编码已删除的两个审批类。

### 10.5 负向用例

- stale contentVersion → `PACKAGE_VERSION_STALE`，包零变化；
- 第一次 item verdict 后 append/replace/remove/intent → `PACKAGE_CONTENT_FROZEN`；
- 空包 → 拒绝；纯 NL 通过 `request` 建包，不造空包；
- 被批子集为空 → `REJECTED`，不调用 commit port；
- commit 冲突 → `CONFLICTED`，零效果、零 outcome；
- 重复 commit → 同一 receipt，不执行第二次；
- 旧命令类型在注册表中不存在（防旁路）。

---

## 11. 实施批次与文件所有权

> 按 AGENTS §一.5：一个责任区一个写代码代理；生产代码只写到编译过，测试由单独测试代理最后统一做；控制方不直接写实现。

| 批次 | 责任区 | 允许改 | 交账 |
|---|---|---|---|
| **U1** | AgentLib 读口（上游仓库） | **已交付**：`DecisionPackageReadPort`；`DecisionPackages`/`DecisionPackageTools` 接口化；相关测试 | **已满足**：0.2.3-SNAPSHOT、旧测试绿、五工具 fake read port 5/5 |
| **S1** | Simos 包状态/命令 | **已交付**：`DecisionRecord`/`DecisionCallMeta`、`GaState`、`GaChangeSet`、`GaCodec`、`ga.ApplyDecisionPackage`；旧三 handler/命令/旧写工具退役 | `compile -pl simos-ga -am` + `-pl simos-app -am` rc=0；GaRoundTrip 绿 |
| **S2** | DM 收包 | **已交付**：`DecisionAgentRunner`、`DecisionTurnFinalizer`、`simos.ga.request`、`intent/revise/drop/confirm/reopen`、`DecisionCallPreviewer`、`DecisionProposable` 退役 | 编译；D1–D6 测试绿 |
| **S3** | GM 复用 + HTTP 退役 | **已交付**：五工具装配、`CoreDecisionPackageReadPort`、`CoreDecisionSubmissionPort`、`DecisionEffectExecutor`、HTTP 退役 | 五工具 GM 面；HTTP 退役；G1–G6 绿 |
| **S4** | 读投影/前端 | **已交付**：`GmPacketTool`/`GmPacketsTool` 只读、GUI `/api/approvals` 404、Shell 生命周期清理 | 前端门禁 412/412 |
| **T** | 测试 | **已交付**：§10 判据、变异自证、7 表往返、abort、CONFLICTED | **3328 tests / 0 failures / 0 errors / 7 skipped；clean verify rc=0** |

**不碰**：主树 D-031 未提交改动；`parallel-side-module`；`ga-opt`；`d031-p1-baseline`；不提交、不推送、不合并。

---

## 12. 已定的实现默认值（实现方不得静默偏离）

1. **`DecisionRecord` 是否替换 `GaState.decisionPackets` 值类型**：本文定为“是”；不新增第二张 snapshot 表，避免两套状态。
2. **`DecisionPacket` 是否保留**：本文定为“保留只读视图”；生产写口全部走 `DecisionRecord`/AgentLib。
3. **`confirm` 语义**：本文定为“只写领域 `confirmedAtRevision`，不改 `PackageState`”；所有 Ask 自动入包，GM 可在未 confirm 时裁决。
4. **`propose` 去留**：本文定为“删除”；纯 NL 走 `request`，实际工具调用走自动 Ask 入包。
5. **deadline**：本文定为 `Long.MAX_VALUE` 哨兵 + 不按墙钟超时；不自动按 tick 过期，当前 tick 过滤在 read port 实现。
6. **`MERGED`**：本文定为领域投影 + `mergedPlanId`；`decision_package_commit` 对 merged 包具名拒绝，合并计划执行器是唯一执行路径。
7. **`CommitReceipt.revisionRef`**：本文定为 `expectedRevision + 1`（`CommandBus` 新 revision 的硬约束）；提交后核对实际 `BatchResult.Committed.ref()`，不一致只记对账故障，不改写世界。

---

## 13. 实现结果 / 未验证

- AgentLib 侧 U1b 修正已交付：`DecisionSubmission` 允许 `DECIDING + decision == null`，`DecisionPackageTools.itemDecided` 用真实 verdicts 渲染 approved/rejected；AgentLib 聚焦测试 64/64、全模块 681/681，`~/.m2` 已刷新。
- Simos 生产代码已完成（未提交、未合并）：`GaState.decisionPackets` 值类型为 `DecisionRecord`，精确包状态只在 `DecisionPackageSnapshot`；DM Ask 入包；五工具复用；HTTP 退役。
- `tools/mvn-lock.sh -q clean verify` → **rc=0**；**3328 tests / 0 failures / 0 errors / 7 skipped**；前端门禁 **412/412**；SpotBugs/Spotless/Shaded guard 全过。
- 定向 16 类 **161 tests / 0**；7 个变异均先红后还原（md5 复核）。
- 未跑/未验证：真实 LLM 门、`RealLlmGovScenarioTest`/`RealLlmUnitDecisionLoopTest` 仍环境变量门控；其中 `RealLlmGovScenarioTest` 仍带旧 propose/DRAFT 提示，开真 LLM 门前需迁移。
- 合并/提交/推送未做；主树 D-031 和另外两棵工作树未触碰。
