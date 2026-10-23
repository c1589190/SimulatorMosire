# 给 AgentLib 侧的增量需求：决策包五工具读取端口（2026-10-17）

> **提出方**：SimulatorMosire（simos）控制方  
> **目标消费方**：`~/ProjectMosire/AgentLibMosire`  
> **基线**：AgentLib `v0.2.2`（`c46e777`）提出；`0.2.3-SNAPSHOT`（`0481401`）已实现并通过本机安装  
> **性质**：只提需求，不替上游定实现细节；**不要求 AgentLib 替 Simos 持久化，也不要求加 `restore`**。  
> **关联设计**：`docs/superpowers/specs/2026-10-17-dm-gm-agentlib-decision-package-design.md`

---

## 0. 一句话需求

**请把 `DecisionPackageTools` 的只读依赖从 concrete `DecisionPackages` 解耦为一个 `DecisionPackageReadPort`，让宿主可以用自己的持久化世界状态实现读口；五个工具的语义、工具名、参数和返回 JSON 全部不变。**

这是一项 **additive** 变更，不要求改 `DecisionPackageTransitions`、`DecisionPackageSnapshot`、`DecisionCommitPort`，也不要求把 Brain 的内存适配器迁走。

---

## 1. 为什么需要（现状事实）

### 1.1 五工具已经很好，但读口写死了内存表

AgentLib 0.2.2 已提供五个 MCP 决策工具（`DecisionPackageTools.java:91`、`:286` 起）：

- `decision_package_list`
- `decision_package_get`
- `decision_package_decide`
- `decision_package_decide_item`
- `decision_package_commit`

其中**写路径**已经通过 `DecisionSubmissionPort` 解耦；但**读路径**仍直接依赖 concrete `DecisionPackages`：

| 工具内部调用 | 证据 |
|---|---|
| `packages.pending()` | `DecisionPackageTools.java:286` |
| `packages.all()` | `:291` |
| `packages.find(packageId)` | `:346` / `:388` / `:411` / `:443` |
| `packages.itemVerdicts(id)` | `:478` |
| `packages.expiryReason(id)` | `:536` |
| 构造器参数 `DecisionPackages packages` | `:91-112` |

### 1.2 `DecisionPackages` 是 Brain 的内存适配器，不是持久宿主接口

- `DecisionPackages` 的类注明确“不落库、进程退出失效”；
- `snapshot(id)` 只有导出，没有 `restore(snapshot)`（`DecisionPackages.java:987`）；
- Simos 的世界状态由 core 持久化，包状态是 `GaState` 的一部分，不会另建一份 `DecisionPackages` 内存表。

因此当前状态是：

```text
core 持久层：有包快照
新进程：五工具持有 new DecisionPackages()（空表）
GM 调 decision_package_get/decide/commit
-> PACKAGE_NOT_FOUND
```

**这不是 core 没存，也不是要求 AgentLib 实现数据库；只是五工具缺少一个“从宿主状态读包”的接口。**

### 1.3 不采用的两条路

| 路 | 为什么不采用 |
|---|---|
| 给 `DecisionPackages` 加 `restore(snapshot)`，Simos 启动时灌表 | 需要维护一份进程内完整表，且写路径仍要解决内存表与 core revision 的事务对齐；等于多一套状态 |
| 在 Simos 复制五个工具 | 违反用户口径“ga 模块有互斥内容优先复用 AgentLib”；会出现第二套决策面 |

---

## 2. 需求

### N1：新增只读端口 `DecisionPackageReadPort`

建议放在 `io.mosire.agentlib.decision`，最小形状与当前五工具实际调用一一对应：

```java
public interface DecisionPackageReadPort {

  Optional<DecisionPackage> find(String packageId);

  List<DecisionPackage> pending();

  List<DecisionPackage> all();

  List<DecisionItemVerdict> verdicts(String packageId);

  Optional<String> expiryReason(String packageId);
}
```

> **已按上游 R1=B 冻结**：新接口用顶层 `DecisionItemVerdict`（与 `DecisionPackageSnapshot.verdicts()` 同型），不再让新接口依赖 `DecisionPackages.ItemVerdict`。方法名可按上游风格微调；**语义必须是这五个读能力**。五工具的返回 JSON 必须不变。

### N2：`DecisionPackages` 实现该端口

- `DecisionPackages implements DecisionPackageReadPort`；
- 现有方法签名、返回值和内存行为**逐字不变**；
- 旧的 `public List<DecisionPackages.ItemVerdict> itemVerdicts(String)` 保留；新增的 `verdicts(String)` 负责转换成顶层 `DecisionItemVerdict`；
- 不要求 `DecisionPackages` 增加 `restore`、store、数据库或自动持久化。

### N3：`DecisionPackageTools` 接受端口，而不是 concrete 表

需要修改的只是**读取依赖**：

```java
private final DecisionPackageReadPort packages;

public DecisionPackageTools(
    DecisionPackageReadPort packages,
    DecisionSubmissionPort submissions,
    Set<String> approverRoles) { ... }
```

同时：

- 保留一个吃 `DecisionPackages` 的兼容构造器/工厂重载，委托给端口构造器；
- `of(...)` / `all(...)` / `tools(...)` 三组工厂同样增加端口形参的重载；
- `InFlightDecisionPackages` / `InFlightDecisionActions` / `DecisionReadAudit` 的口径不变；
- 五个工具的名称、schema、权限判断、参数解析、返回 JSON、错误码不变。

### N4：语义要求

- 读口只读；不得因读而改包状态、发事件或写审计（`full=true` 的明文读取审计仍由 `DecisionReadAudit` 负责，逻辑不变）。
- `find` 未命中 = `Optional.empty()`；`pending()` / `all()` 缺失 = 空表，不抛“未装配”异常（装配缺失由宿主在构造期保证）。
- `find(id)` **必须能按 id 读历史包**（包括旧 tick / 已终局包）；`pending()` / `all()` 是五工具列表用的投影，允许宿主按当前 tick / 领域状态限定可见集合。接口 javadoc 不要把 `all()` 写成“世界全历史”而让工具列表无法裁剪旧包。
- 端口不做身份/权限判断；工具的角色闸仍由 `approverRoles` + `ToolContext` 负责。
- 端口不暴露 `DecisionPackageSnapshot` 的写能力；写仍只走 `DecisionSubmissionPort`。
- 端口不要求返回 `contentVersion`；五工具当前不使用它。宿主在 `DecisionSubmissionPort` 内部做版本检查。

---

## 3. 验收

### A1：Brain 旧行为不变

- 现有 `DecisionPackagesTest` / `DecisionPackageToolsTest` 全部保持通过；
- `DecisionPackages` 继续可直接作为 `DecisionPackageReadPort` 传入五工具；
- 现有构造器/工厂调用点不需要改源码（保留兼容重载）。

### A2：不依赖内存表

新增一条 fake `DecisionPackageReadPort` 用例：

1. fake 读口返回一个 `PROPOSED` 包；
2. `DecisionPackageTools` 用该 fake 构造；
3. 调 `decision_package_list` 能看到它；
4. 调 `decision_package_get` 能读 items/verdicts/expiry；
5. 证明构造过程没有 `new DecisionPackages()`、没有 `restore`。

### A3：五工具行为逐字不变

至少覆盖：

- list 的 `pending` / `awaitingCommit` 分组不变；
- get 的 `full=false` 不含参数明文、`full=true` 触发 `DecisionReadAudit`；
- decide / decide_item 的 `by` 从 `ToolContext.identity` 取，参数里的 `by` 不采信；
- commit 的 receipt 字段与错误码不变；
- 未知 id / 未知 index / 重复表态 / 过期包等具名拒不变。

### A4：构建与版本

- 上游全量 `./mvnw verify` 绿；
- 新增接口是 public additive API，不改旧 public 签名/包名；
- 建议版本 `0.2.3`（或明确标注的 additive SNAPSHOT），由 Simos 父 POM 统一升级。

---

## 4. 非目标

- 不在 AgentLib 实现数据库、world state、revision、tick、branch；
- 不要求 `DecisionPackages` 支持重启恢复；
- 不要求把 `DecisionPackageTools` 的写路径改成 core 类型；
- 不要求 AgentLib 认识 DM/GM、`MERGED`、preview/targets/intent；
- 不把 `DecisionPackagesHttpHandler` 或 HTTP 审批面搬回 core；Simos 已决定让 HTTP 审批面退役；
- 不改 `DecisionPackageTransitions` / `DecisionPackageSnapshot` / `DecisionCommitPlan` / `CommitReceipt` 的语义。

---

## 5. Simos 侧将怎么用

```text
CoreDecisionPackageReadPort implements DecisionPackageReadPort
  -> 读 GaState.decisionPackets 的 DecisionRecord.packageState
  -> find / pending / all / verdicts / expiryReason

DecisionPackageTools(port, CoreDecisionSubmissionPort, Set.of("GM"))
  -> 五工具照旧注册到 GM 工具面

CoreDecisionSubmissionPort implements DecisionSubmissionPort
  -> DecisionPackageTransitions.decide/itemDecided/prepareCommit
  -> core.submitBatch 写 GaState/revision
```

Simos 不使用 `DecisionPackages`；它只作为 Brain 的兼容内存适配器存在。`DecisionPackageReadPort` 是五工具和 core 持久化之间的唯一读缝。

---

## 6. 证据来源

- `AgentLibMosire/src/main/java/io/mosire/agentlib/decision/DecisionPackageTools.java:91-112`（构造器）、`:286-536`（五个读调用）；
- `AgentLibMosire/src/main/java/io/mosire/agentlib/decision/DecisionPackages.java:987`（`snapshot` 只有导出，无 restore）；
- `AgentLibMosire/src/main/java/io/mosire/agentlib/decision/DecisionPackageSnapshot.java:29`；
- `AgentLibMosire/src/main/java/io/mosire/agentlib/decision/DecisionPackageTransitions.java:50/88/140/181/361/417`；
- 0.2.2 本机 `target/surefire-reports` 的 decision 测试 73/73（未在本文重跑）。
