# SDSimos 前置调查 —— 既有接口实测与基础设施缺口

> 本文是 `2026-09-20-sd-simos-brainstorm.md` 的**调查附录**（只蒸馏结论，不抄 transcript）。术语沿用该文：**SDSimos = 国家 + 决策人**（R1 裁定 NAA = SDSimos），决策的领域对象叫 **`Directive`**（R3），一个决策人一个 tick 一个 `Directive`（R4）。
>
> **来源标注（重要）**：
> - **▲ 实测** = 控制器本会话（2026-09-20）**直接读本仓当前字节**得到，非转述。
> - **◇ 引用** = 取自 explore transcript（agent 实读当前字节、自带 `文件:行`）的结论；本会话未逐行复核，但两条 transcript 均由探索代理在同一棵树上实读。
> - **○ 空白** = 目前没有可靠结论。
>
> **缺口提示**：规划中的**第三份 transcript（Unit 模型）未落盘** —— 约定目录 `/home/cna/.local/share/opencode/tool-output/` 下只有两份 SDSimos 相关原件（`bg_ecf53159` Info/地址/信封/SPI、`bg_f651e4db` Agent/权限/AgentLib）。因此 **§A.1 Unit 块由控制器直接读源码重做**（▲），并与 brainstorm §7 的探子结论（探子 `bg_50d01d6f`）交叉核对。

---

## §A 既有接口实测清单

### A.1 Unit 模型（▲ 本会话直读源码）

**总判断：Unit 是一棵「严格单父树 + 两条时态序列」的节点；没有「集合单位」容器、没有作战状态字段、没有战损增量语义、`PlanRoute` 载荷不容稀疏路点、`ReparentUnit` 只改单点。** 与 brainstorm §7 的五个缺口逐条一致。

| 类型 / 方法 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `Unit` | `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java:20-29` | `record Unit(UnitId id, String name, SegmentedSeries<Optional<UnitId>> parent, SegmentedSeries<Optional<HexCoord>> position, int member, Map<String,Integer> equipment, int speed, int mobilityPerMille, Optional<Movement> movement)` | 单位节点：**无 status / mode / type 字段**；编制=单父时态序列，位置=时态序列 |
| `parent` 自指 | `Unit.java:40-44` | 构造期拒 `parent == 自身 id` | 自环在节点内即拒 |
| `equipment` 冻结 | `Unit.java:48-50, 78-93` | 拷贝+逐键校验，**赋值处** `Collections.unmodifiableMap` | SpotBugs `EI_EXPOSE_REP` 只认赋值处（形态 7） |
| `UnitState` | `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java:23` | `record UnitState(Map<UnitId,Unit> units)` | `id → Unit` 表 + 构造期编制树不变量 |
| 编制树成环校验 | `UnitState.java:74-97` | 按 `parent` 段 `from` 的**关键时点**逐点查环 | 严格单父树；**没有子列表 / 多父 / `Army` 容器**；「下属」只能全表反查（如 `UnitOperations.disband`） |
| `effectivePosition` | `UnitState.java:55-72` | `Optional<HexCoord> effectivePosition(UnitId, SimosTimestamp)` | 自身无位置则向父递归取 |
| `Movement` | `simos-unit/src/main/java/io/mosire/simos/unit/Movement.java:12-13` | `record Movement(Route route, SimosTimestamp departedAt, int speedAtDeparture, int mobilityAtDeparture)` | 在途行程；出发速度/机动性在出发时冻结 |
| `MovementStatus` | `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementStatus.java:4-10` | `enum { IN_TRANSIT, ARRIVED, NEED_REPLAN }` | ★ **只是「路线进度」**（走没走完 / 地图变了），**不是作战状态**；由 `UnitMoves.evaluate` 现算，**不落库** |
| `UnitMoves.evaluate` | `simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java:31-74` | `MovementState evaluate(Unit, SimosTimestamp, GameMap, MovementCost)` | 纯函数，**不写回状态**（预算 = `speedAtDeparture × 1000 × Δtick` 毫 MP） |
| `Route` | `simos-unit/src/main/java/io/mosire/simos/unit/Route.java:8` | `record Route(List<HexCoord> waypoints, List<HexCoord> path)` | ★ **类型支持稀疏路点**（waypoints 是 path 的子序列，`:26-34`）；path 必须逐格相邻、无重复（`:35-42`） |
| `PlanRouteHandler` | `simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanRouteHandler.java:44` | `Route route = new Route(waypoints, waypoints)` | ★ handler **写死 `waypoints == path`** ⇒ 载荷必须**逐格相邻**；稀疏路点**载荷通道不存在**（brainstorm §7④） |
| `PathFinder`（A*） | `simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java:59-107` | `static Optional<List<HexCoord>> findPath(GameMap, HexCoord, HexCoord, Unit, MovementCost)` | A* **已存在且有生产调用者**：`simos-app/.../gui/GuiServer.java:421`（只读 `/api/map/path`，app 层）。⇒「回归路径」可用它补中间格，但**载荷通道仍缺**（见上） |
| `UnitOperations` | `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java` | 见下 | 纯函数面（M3 裁定 U5 的 8 项） |
| ├ `create` | `:34` | `UnitState create(UnitState, Unit)` | 同 id 已存在 ⇒ 抛 |
| ├ `reparent` | `:47-64` | `UnitState reparent(UnitState, UnitId, Optional<UnitId>, SimosTimestamp)` | ★ **一次只改一个单位的父**（append 一条 `parent` 段） |
| ├ `rename` | `:66` | `UnitState rename(...)` | 追加段语义同族 |
| ├ `setStrength` | `:82-97` | `UnitState setStrength(UnitState, UnitId, int member, Map<String,Integer> equipment)` | ★ **整份替换**（member + equipment 全量），**不是增量**；全仓无 combat/casualty |
| ├ `placeAt` | `:100-116` | `placeAt(..., Optional<HexCoord>, SimosTimestamp)` | 顺带清空在途路线 |
| ├ `planRoute` | `:119-143` | `planRoute(UnitState, UnitId, Route, SimosTimestamp)` | 起点须等于 `effectivePosition`，否则拒 |
| ├ `cancelRoute` | `:145` | `cancelRoute(...)` | 删在途 |
| └ `disband` | `:162-176` | `disband(UnitState, UnitId, SimosTimestamp)` | ★ **在 `at` 有下属 ⇒ 抛**（先改编再解散）；**不能整棵子树迁移** |
| `UnitChangeSet` | `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java:22` | `record UnitChangeSet(FieldDelta<Unit> units) implements ChangeSet` | **当前 1 个组件**（`units`）；`between` `:25`、`apply` `:32`、`isEmpty` `:39` |
| `UnitAgentAttachPolicy` | `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitAgentAttachPolicy.java:19` | `implements AgentAttachPolicy`，`namespace()="unit"`，任意存在的 `Unit` 可绑 | 决策人 tool-scope 的既有基础（◇ 与 M5 T10 结论一致） |
| `UnitTimeParticipant` | `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java:69` | `implements TimeParticipant`，`namespace()="unit"` | ★ **全仓唯一** `TimeParticipant` 实现（▲ 实测 `grep implements TimeParticipant` 仅此一处）；它读 `state.module("map")` |
| `unit.*` 命令 | `simos-app/.../Shell.java:201-208` | 8 个 handler：Rename/Create/Reparent/SetStrength/PlaceAt/PlanRoute/CancelRoute/DisbandUnit | 全部注册于一处 |

**编译期边界（▲ 实测 pom）**：`simos-unit/pom.xml:47-49` 禁 `simos-social`/`simos-core`/`agentlib-mosire`（允许 `simos-map`）；`simos-social/pom.xml:47-49` 禁 `simos-unit`/`simos-core`/`agentlib-mosire`。⇒ **unit 与 social 编译期互不可见**；国家（map 的 Region 标签）与军队（unit）的关联只能在 SDSimos / app 层做（brainstorm 末行结论成立）。

### A.2 地址 · Info · 命令信封 · util.spi · 两阶段推进（◇ transcript `bg_ecf53159`，标 ★ 者本会话复核）

**地址系统（▲/◇）**

| 类型 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `Address` | `simos-util/src/main/java/io/mosire/simos/util/address/Address.java:13` | `record Address(List<AddressSegment> segments)`；`parse` `:54`、`namespace` `:59`、`canonical` `:63` | 地址是**段序列**，语法 `namespace:根主体[:段…]`；**不是 `type:id`**，无 `SimosAddress` 类型 |
| `AddressSegment` | `.../address/AddressSegment.java:4` | `sealed interface permits Namespace, Entity, Index, Property` | 段类型封闭四选一 |
| `Namespace` / `Entity` / `Index` / `Property` | `.../address/Namespace.java:4`、`Entity.java:11`、`Index.java:7`、`Property.java:4` | `Namespace(String ident)`、`Entity(Optional<String> kind, String name)`、`Index(List<Integer>)`、`Property(String ident)` | 段内 `kind.name`，Human `[q,r]` 归一成 canonical `hex.q_r` |
| `Resolver` | `.../resolve/Resolver.java:11` | `String namespace()` `:14`；`QueryResult resolve(Address, ResolveContext)` `:16` | 解析器 SPI |
| `ResolverRegistry` | `.../resolve/ResolverRegistry.java:15` | `register` `:19`（重复即抛）、`resolve` `:36` | 按首段**一对一**分发；**未知命名空间抛、无兜底** |
| `ResolveContext` | `.../resolve/ResolveContext.java:12` | `record ResolveContext(SimulationState state, SimosTimestamp at)` | 无扩展袋 |
| `QueryResult` / `ResolvedSubject` / `SubjectId` | `.../identity/QueryResult.java:6`、`ResolvedSubject.java:10`、`SubjectId.java:9` | `QueryResult(List<ResolvedSubject>)`；`ResolvedSubject(SubjectId id, String canonicalAddress, String typeName)`；`SubjectId(String namespace, String localId)` | 稳定身份（铁律 1）；空候选**不是错误** |

**既有地址 kind（◇）**：`map` → 根 `Map` / `hex.<q>_<r>` / `region.<id>` / `city.<id>`（`simos-map/.../MapResolver.java:47,92-96`）；`social` → 根 `Social` / `hex`（`SocialResolver.java:36`）；`unit` → `unit:<id>` + 链式定位 + 子实体 `equipment.<名>`（`UnitResolver.java:40,137`）。
★ **`agent:` 命名空间无 resolver**（▲ 实测 `implements Resolver` 仅 Map/Social/Unit 三个；Shell 只注册这三个，`Shell.java:217-220`）——`agent:` 只作 `AgentId` / 事件 `initiator` 前缀，**不可解析**。

**Info（◇，★ put 零调用者已复核）**

| 类型 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `InfoEntry` | `simos-util/.../info/InfoEntry.java:16` | `record InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note)` | ★ **无 `InfoKey` 类型**（key 是普通 String，▲ grep 无命中）；`value` 是**裸 `Object`**（结构化、Jackson 序列化），不是预格式化字符串 |
| `InfoSystem` | `.../info/InfoSystem.java:12` | `Optional<InfoEntry> get(Address, String, SimosTimestamp)` `:15`；`InfoSystem put(Address, InfoEntry)` `:18` | ★ **无 `remove()`**；`put` 返回新实例（不可变语义） |
| `InMemoryInfoSystem` | `.../info/InMemoryInfoSystem.java:23` | `record InMemoryInfoSystem(Map<Address,List<InfoEntry>> bySubject) implements InfoSystem` | util 里唯一实现；`get` 同 key 重叠取**插入序最后者**且 `valid().contains(at)` |

- ★ **哪些实体挂了 Info：无。** Info 是「地址 → 信息」的**外挂关系**，不挂在任何对象上；全仓唯一持有者是 `SimulationState.info()`（`.../state/SimulationState.java:14`）；demo 初始化空表（`DemoWorld.java:86`）。
- ★ **INFO 命令路径：无。** `InfoSystem.put` 在 main 源码**零外部调用者**（▲ 实测：唯二 `.put` 命中在 `InMemoryInfoSystem` 自身实现内 `:28/:54`）。Info **不进 `WorldChangeSet`**；`Replay.applyWorld` 原样保留 `base.info()`（`Replay.java:221`）。它**只随 checkpoint 信封往返**：
  - 写：`CheckpointEncoder.java:68`（`SimosObjectMapper.create().writeValueAsString(state.info())`）
  - 读：`Replay.readInfo(String)` `Replay.java:245`（落到具体类型 `InMemoryInfoSystem`，注释自陈「接缝不是终局」）
  - 信封字段：`Envelope.java:53/77/125`
  - `Address` 作 Map 键的绑定：`SimosObjectMapper.addressKeys()` `SimosObjectMapper.java:157`

**命令信封 / Registry / Bus（◇）**

| 类型 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `Command`（util） | `.../state/Command.java:8` | `interface Command { RevisionId expectedRevision(); }` | 只有乐观并发版本戳 |
| `CommandEnvelope` | `simos-core/.../command/CommandEnvelope.java:22` | `record (String commandId, String correlationId, String initiator, BranchId branch, RevisionId expectedRevision, String type, String payloadJson) implements Command` | `payloadJson` 是不透明文本（逐字节转交） |
| `CommandRegistry` | `.../command/CommandRegistry.java:27` | `CommandRegistry(Collection<CommandHandler>)`；`byType` `:59`、`types` `:65` | ★ **无可变 `register()`**；`type` 必须 `<namespace>.<Command>`，**构造期校验** `:75-84` |
| `CommandBus` | `.../command/CommandBus.java:64` | `CommandResult submit(Command)` `:117` | 分三支：`AdvanceTime`→route、`ForkBranch`→fork、`CommandEnvelope`→`dispatch` |
| `CommandResult` | `.../command/CommandResult.java:13` | `sealed { Committed(StateRef), Rejected(String), Conflict(StateRef) }` | 三态结局 |
| `AdvanceTime` / `ForkBranch` | `.../command/AdvanceTime.java:20`、`ForkBranch.java:19` | 均实现 `Command` | ★ `ForkBranch` 支**不发事件**（已知缺口） |
| `AdvanceRoute` / `StateLoader` | `.../command/AdvanceRoute.java:16`、`StateLoader.java:19` | 函数式注入点（解环 / 可测性） | 装配时注入 |

`CommandBus.submit` 信封链（◇）：`registry.byType` → **① 入口乐观并发检查**（head==expected）→ `stateLoader.load` → **② `handler.handle(state, payloadJson)`（锁外）** → `HandlerOutcome` 分支：`Rejected` 落 `received+rejected`（不留 revision）；`Applied` → **③ 锁内复查 + ④ commit**（`WorldChangeSet` 单键 = `type` 首个 `.` 前缀；`Timeline.appendRevision(row, events)`）。装配：`CoreSimos.register(CommandHandler)` `CoreSimos.java:143`；`Shell` 注册 14 个 handler（`Shell.java:193-213`）。

**`io.mosire.simos.util.spi` 全部类型（6 个 + package-info，◇）**

| 类型 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `CommandHandler` | `.../spi/CommandHandler.java:12` | `String type(); HandlerOutcome handle(SimulationState, String)` | 领域命令接总线；`handle` **纯函数、不写状态** |
| `HandlerOutcome` | `.../spi/HandlerOutcome.java:13` | `sealed { Applied(ChangeSet), Rejected(String) }` | 只有「变更集 / 拒绝理由」 |
| `ModuleCodec` | `.../spi/ModuleCodec.java:15` | `namespace/decodeChangeSet/encodeChangeSet/decodeSnapshot/encodeSnapshot/apply(ChangeSet, Snapshot, StateMeta)` | Core 与模块间**唯一**触碰状态形状处 |
| `TimeParticipant` | `.../spi/TimeParticipant.java:13` | `String namespace(); TimeProposal simulate(SimulationState, TimeRange)` | 两阶段 proposer，纯函数，所有参与者拿**同一份 base** |
| `TimeProposal` | `.../spi/TimeProposal.java:22` | `record (String namespace, ChangeSet changeSet, Set<String> reads, Set<String> writes)` | `reads/writes` 是 **canonical 地址字符串**，v1 只做精确集合匹配 |
| `AgentAttachPolicy` | `.../spi/AgentAttachPolicy.java:17` | `String namespace(); boolean canAttach(Address, ResolveContext)` | ★ **只读、只回答可不可以**；注册在 **app 层** `BindingRegistry`（`BindingRegistry.java:48`），**Core 不认识它** |

**注意边界（◇）**：`FacetProvider`/`FacetRegistry`/`FacetEntry` 在 `util.facet`；`Resolver` 系在 `util.resolve`；`InfoSystem`/`InfoEntry` 在 `util.info`。**都不在 `util.spi`。**

**两阶段推进六步（◇）**：`TimeAdvance implements AdvanceRoute`（`.../advance/TimeAdvance.java:69`，`run` `:132`）。

| 步 | 行 | 做什么 |
|---|---|---|
| ④ 第 0 项 | `:141-143` | `range.to` 缺失 ⇒ Rejected（**先于 ①**，不查库） |
| ① Prepare | `:147-167` | head 乐观并发检查 + `stateLoader.load(base)` |
| ② Propose | `:170-177` | 逐个 `participant.simulate(state, range)` → `List<TimeProposal>` |
| ③ Resolve | `:179-195` → `TimeProposalResolver.resolve:61` | **写-写 ⇒ 拒整次推进**；**读-写 ⇒ 只留痕**（按 canonical 地址**集合相交**） |
| ④ Validate | `:197-201` → `validate:272-330` | 五项**机械**校验（namespace 有 codec / `encodeChangeSet` 不抛 / `codec.apply` 不抛 / `namespace` 与键一致且 `ref/timestamp==newMeta`） |
| ⑤ Commit | `:203-228` | `appendRevision(revisionRow, trace)` **一个事务**；主键撞 ⇒ 动了才折 `Conflict` |
| ⑥ Post-commit | `:230-231` → `:349` | **只剩写 checkpoint** |

**参与方能/不能（◇）**：`UnitTimeParticipant`（`UnitTimeParticipant.java:69`）为证——**能**读 `state.module(任意 ns)`（它读 map，`:146-154`）、用 `range.to` 评估未来、产出**本模块** `ChangeSet`、声明 `reads/writes`；**不能**写状态、不能依赖调用顺序（C25：同一 base、namespace **字典序**定序）、**每个 namespace 只能有一个 participant**（`putIfAbsent` 重复即抛 `TimeAdvance.java:124-127`）。

**变更集（◇）**：`ChangeSet` 是**纯标记接口**（`.../state/ChangeSet.java:14`，无 `isEmpty`/无 `baseRevision`）；`WorldChangeSet(Map<String,ChangeSet>)`（`simos-core/.../state/WorldChangeSet.java:23`）；通用差异机制 `FieldDelta`（`.../state/FieldDelta.java:66`）；往返断言器 `RoundTripAssertions.assertRoundTrip`（`.../verify/RoundTripAssertions.java:21`，**main 源码**）。★ **`ArchitectureGuardsTest` R1 钉住「全仓恰 4 个 main `ChangeSet` 实现者」**（`simos-core/src/test/.../ArchitectureGuardsTest.java` 的 `changeSetHasExactlyFourMainSourceImplementors`）——**新增 SDSimos 变更集会直接让此条红，必须同步改**。

**Core 可见性（◇，▲ pom 复核）**：`simos-core/pom.xml` 的 `bannedDependencies` **main scope 禁 `simos-map`/`simos-social`/`simos-unit`**，test scope 用 `include :*:*:test` 放行。`simos-util/pom.xml:54-59` 禁 agentlib + 四领域模块。

### A.3 Agent · 权限 · AgentLib（◇ transcript `bg_f651e4db`）

> 说明：`ToolSpec`/`PermissionChecker`/审批链类**全部是 AgentLib 的外部类型**（源码在 `~/ProjectMosire/AgentLibMosire/`，本仓只有装配点）。工具定义在 `simos-app`。

**工具定义（◇）**

| 类型 | 位置 | 签名要点 | 一句话 |
|---|---|---|---|
| `AgentTool`（AgentLib） | `~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/tool/AgentTool.java` | `name/description/jsonSchema/spec/gate(ToolContext)/resources/ledgerArgs/execute(ToolContext)` | 工具接口；`gate` 缺省 ALLOW，`resources` 缺省 NONE |
| `ToolSpec`（AgentLib） | `.../agentlib/permission/ToolSpec.java:25` | `record ToolSpec(AccessToken requiredLevel, boolean sensitive, boolean destructive, boolean noExport)`；`DEFAULT=(GUEST,false,false,false)` `:29` | `noExport=true` = **永不进入 MCP 暴露面**（结构性「不可外包」），当代零使用者 |
| `SimosToolSource` | `simos-app/.../tools/SimosToolSource.java:37` | `implements ToolSource`；`id()="simos"`、`listTools()` 12 条 `:84` | 12 工具唯一出处；装配 `Shell.java:247-251`（经 `McpSourceBridge.bind`） |

**12 条工具（◇）**：写 3 —— `simos.command.submit`（`CommandSubmitTool.java:35`，spec `level(DEFAULT,true,false)` `:72`，`ALL_WRITE`）、`simos.advance`（`AdvanceTool.java:34`）、`simos.fork`（`ForkTool.java:29`，无资源声明）；读 9 —— `simos.command.catalog`、`simos.state.resolve`、`simos.state.facets`、`simos.timeline.branches`、`simos.map.overview`、`simos.map.hex`、`simos.unit.list`、`simos.unit.get`、`simos.social.population`（均 `DEFAULT`/GUEST 级）。

**权限与审批（◇）**

| 类型 | 位置 | 一句话 |
|---|---|---|
| `PermissionChecker.denialReason` | `AgentLib .../permission/PermissionChecker.java:28-56` | 纯 allow/deny，顺序：readOnly ⇒ deniedTools ⇒ 白名单空集 deny-default ⇒ token 不足（**硬拒、不进审批**）⇒ sensitive/destructive 未放行（**终局拒**） |
| `AccessToken` | `.../permission/AccessToken.java:15` | `GUEST(0) < DEFAULT(1) < SYSTEM(2)` |
| `AgentPermissionSet` | `.../permission/AgentPermissionSet.java:26` | 7 字段：`grantedToken/allowedTools/deniedTools/destructiveAllowed/sensitiveAllowed/readOnly/resourceScopes` |
| `ToolCallAuthorizer` | `.../tool/ToolCallAuthorizer.java:122-179` | 五段唯一入口：工具不存在→硬拒→资源前置闸→`ToolGate`（Block/Ask）→执行；码 `COMMAND_BLOCKED`/`APPROVAL_DENIED`/`RESOURCE_DENIED` |
| 审批链装配 | `simos-app/.../Shell.java:229-244` | `PendingApprovals`→`HttpApprovalChannel`→`ApprovalCoordinator([AutoApproveGate,ConfirmGate])`→`ApprovalHttpEndpoint`；**无 Superior/LLM 判定**（M5） |

**Agent 绑定（◇）**

- `AgentAttachPolicy`（`util.spi`）**只读**，实现只有 `MapAgentAttachPolicy`（`simos-map/.../spi/MapAgentAttachPolicy.java:23`）与 `UnitAgentAttachPolicy`（`simos-unit/.../spi/UnitAgentAttachPolicy.java:19`）。
  ★ 本会话复核：**两个实现都存在**（▲ 实测）。`MapAgentAttachPolicy` 的 javadoc（`:13-17`）**自己记录了缺口**：总纲/spec 举例「Region 且 `type=Nation`」，但 `Region`/`RegionMeta` **没有 `type` 字段，`Nation` 在领域类型里根本不存在**；M5 按裁定实现「老实版本：任意已存在 Region 即可绑」。
- `BindingRegistry`（`simos-app/.../binding/BindingRegistry.java:37`）：`bind(AgentId, Address, DecisionScope, BindingMode, AgentPermissionSet, ResolveContext)` `:75`；★ **未接进 `Shell`**（class doc `:21`，grep 无 Shell 引用）——**模型层，不做执行**。
- `initiator` 流：`ShellConfig.mcpInitiator()` 缺省 `"agent:external-mcp"`（`ShellConfig.java:42`）→ 工具构造器 → `CommandEnvelope.initiator`（`CommandEnvelope.java:25`）→ `EventRow.agent` 存原文（`EventRow.java:21`；`CommandBus.java:387-388`）。

**按 Agent 区分能力 —— 有/无（◇，关键边界）**

| 能力 | 有/无 | 证据 |
|---|---|---|
| 按 agent 限制「能不能调用工具」 | **有机制，但当前只装配成一个静态身份** | `AgentPermissionSet.allowedTools/deniedTools/readOnly` 被 `PermissionChecker` 消费；但 MCP 整 server 只有一个 `ToolContext`：`Shell.mcpCaller()`（`Shell.java:353-358`）用 `unrestricted(DEFAULT)`，class doc `:337` 明写「**无 per-session 身份**」 |
| per-binding 权限集 | **存了但没接执行** | `AgentBinding.permissions`（`AgentBinding.java:27`）由 `BindingRegistry` 原样存；`BindingRegistry` 未接 Shell |
| 工具级「对子体不可见」 | **有** | `ToolSpec.noExport`，判定在 `AgentToMcpServer.exportable` |
| 按 agent 限制「能碰哪些资源」（命名空间/路径级） | **有**（AgentLib 第 7 维） | `AgentPermissionSet.resourceScopes` → `ResourceScopeMap`/`ResourceScope`/`ResourceAuthorizer`，由 `ToolCallAuthorizer` 在**调用时**以 `context.permissions() × tool.resources()` 构建（`ToolCallAuthorizer.java:130`）——**锚在调用者身份上**；但工具用**粗粒度固定 id**（`map:<mapId>`/`unit:*`/`social:*`，`ToolSupport.java:96-118`），**零代码按 hex/region 细分** |
| 「同一工具、不同 agent 返回不同数据」（行级脱敏/可见性过滤） | **无** | 读工具 `execute` 只读 `context.arguments()`，从不读身份；`QueryService` 读方法**无 caller/scope 参数**；无 filter/redact 层 |

**AgentLib（◇）**：坐标 `io.mosire:agentlib-mosire:0.1.0-SNAPSHOT`；JAR 实测 **126 class**；**不依赖任何 simos 模块**（独立通用 Agent 运行时）。包：`permission/tool/approval/mcp/plugin/llm/event/store/config/proc/retrieval` + 根 `Version`。当前消费者：`simos-core` main（**仅 `Digest`**，`CommandBus.java:5`）与 `simos-app` main。★ `util/map/social/unit` 四处 pom **均 ban agentlib-mosire**。

**MCP 服务（◇）**：`Shell.java:258-266` 调 `AgentToMcpServer.startHttp(...)`；★ **authorizer 形参必填**——三个 `startHttp` 重载全收 `ToolCallAuthorizer`，源码 class doc 明写「不得静默退化为 `standard()`」（`AgentToMcpServer.java:454`），方法体 `Objects.requireNonNull`。MCP caller 桶（裁定 64）= `AccessToken.DEFAULT + unrestricted + external()`；配置 `mcpPort=5715`/`mcpPath="/mcp"`/`approvalPort=5713`（`ShellConfig.java:39-42`）。

**GUI 读/写（◇）**：`QueryService`（`simos-app/.../query/QueryService.java:52`）四个读方法 `stateAt/resolve/facets/facetNames` **都没有 caller/agent/scope 参数**（★ 可见性按 agent 分叉在此层无插桩点）。读端点 `GuiServer.java:104-112`；写端点 `/api/command`、`/api/advance`、`/api/fork` `:115` **直接 `core.submit`、不经 authorizer、无审批闸**。

---

## §B 外部研究（军事模拟实践）

> **占位 —— 待三路 librarian 回填。** 本节**不写任何结论**：外部研究（交战判决模型、战损/效果裁决、OODA / 指挥控制节奏、AI 参战实践等）尚未回收，任何现在写下的内容都将是编造。
>
> 已知**正在/已经回收**的 librarian 素材（仅记线索，不作结论）位于 `/home/cna/.local/share/opencode/tool-output/`：`0beb83951`（FM 3-90.2 Ch.5 进攻作战）、`0beb84861`（War Gamers Handbook）、`0beb85fa5`（FM 6-0 附录 A：OODA）、`0beb85fbe`（JP 2-01.3 JIPOE）、`0beb86b8e`/`0beb88fee`（AI 战略冲突 arXiv）、`0beb87040`（Process vs Battle-Rhythm C2 建模）、`0beb89542`（离散事件效应模拟）、`0beb8b7f0`（RAND：wargame 是否该用 AI）、`0beb8bf58`（多智能体 LLM prompt 攻击）、`0bebb2be9`（军事模拟：交战判决与战损）。
> **回收后须**：逐条给「模型名 + 出处 + 对 SDSimos 的可迁移点」，并明确区分「军事实践事实」与「我们的设计选择」。

## §C ★ 三个基础设施缺口（本次调查的核心产出）

### C① INFO 没有写路径

**事实（▲ 实测 + ◇ 佐证）**：

1. `InfoSystem` 只有 `get` / `put`，**没有 `remove`**（`simos-util/.../info/InfoSystem.java:12-18`）。
2. `InfoSystem.put` 在 **main 源码零外部调用者**（▲ 实测：唯二 `.put` 命中在 `InMemoryInfoSystem` 自身 `:28/:54`）。
3. **没有 INFO 命令处理器**：没有任何 `CommandHandler` 的 `type()` 触及 Info。
4. **Info 不进 `WorldChangeSet`**：`ChangeSet` 世界里没有 Info 的位置。
5. `Replay.applyWorld` **原样保留** `base.info()`（`Replay.java:221`）——重放**不会**改变 Info。
6. Info **只随 checkpoint 信封往返**：写 `CheckpointEncoder.java:68`，读 `Replay.readInfo` `:245`，信封字段 `Envelope.java:53/77/125`。
7. 语法上**无 `InfoKey` 类型**（key 是普通 String）；`value` 是**裸 `Object`**。

**含义**：INFO 是「挂得住、读得出、**写不进**」的只读附属物。SDSimos 要为「国家自然语言总介绍」「交战详情/判决记录」「决策执行原文」落 INFO，**现有机制=无**。必须二选一并裁决：

- **(a) 把 Info 放进 SDSimos 自己的 Snapshot/ChangeSet**（作为 SDSimos 状态的一个组件）——遵守铁律 5 + 往返守卫，但不与全仓 Info 打通；
- **(b) 新造全局 INFO 写路径**（新 SPI / 新命令 + 装配点）——能复用现有 Info 读/序列化，但会改动 util/core/app 三处，且要处理「无 remove」的语义。

### C② 没有「写前跨模块校验」机制

**事实（▲ 实测 + ◇ 佐证）**：

1. 全仓 main 源码检索 `Validator` / `interceptor` / `canDelete` / `DeleteGuard` —— **零命中**（▲）。
2. 唯一的「写时跨模块协调」是 **`TimeProposal.reads/writes` + `TimeProposalResolver.resolve`**（`simos-core/.../advance/TimeProposalResolver.java:61`）：写-写 ⇒ 拒整次推进、读-写 ⇒ 留痕。但它是**地址级、不是语义级**——它不知道「Nation」，只知道两方是否写同一个 canonical 地址串；且**只在 `AdvanceTime` 支**，信封支命令不走它。
3. 只读跨模块可见性 = **`FacetProvider` / `FacetRegistry`**（`simos-util/.../facet/`），实现**恰 2 个**：`UnitsHereFacet`（unit）、`PopulationFacet`（social）（▲ 实测）。调用点只在 **app 层** `QueryService.facets`（`QueryService.java:146`），注册在 `Shell.java:222-224`。**Core / CommandHandler 拿不到 `FacetRegistry`。**
4. 最接近「写前策略」的既有形态是 **`AgentAttachPolicy`**（`util.spi:17`）：**只读、只回答可不可以**，且**只在 app 的 `BindingRegistry` 绑定决策人时被调用**（`BindingRegistry.java:102`），**不在任何写命令路径上**。实现恰 2 个（map / unit）——★ 这**正是** brainstrom 想要的「带 Nation 标签的区域不可删除」所需的形态，但**它现在不做写前校验**。
5. `RegionOperations.createRegion/updateRegion/deleteRegion`（`simos-map/.../ops/RegionOperations.java:50/78/109`）**没有任何跨模块校验**；`deleteRegion` 只校验「目标存在」（`:112`）。

**含义**：要实现「带 Nation 标签的区域在 SDSimos 存在时不可删除」，**必须新造机制**。可借鉴的两条路：

- **(a) 仿 `AgentAttachPolicy`**：在 `util.spi` **新增**一个写前策略接口（校验器/拦截器），由 app 层装配、**Core 在命令路径调用**——但 Core 目前**没有任何写前策略调用点**，此改动会碰 `CommandBus`（core 变更）。
- **(b) 在「拥有该数据的模块」的 handler 内做**：如 `map.DeleteRegion`，但它只能经 `SimulationState.module(ns)` 读别的切片，这会引入 `map → sd` 编译依赖，**被 `simos-map` 的 `bannedDependencies` 禁止**。

### C③ 判决 / `③ Resolve` 只做地址集合读写相交、不评估领域条件

**事实（◇ + ▲ 复核边界）**：

1. **`③ Resolve` 的真身**是 `TimeProposalResolver.resolve`（`simos-core/.../advance/TimeProposalResolver.java:61`），返回 sealed `Outcome`（`:34`）：`Blocked(AdvanceConflict)` / `Resolved(WorldChangeSet, warnings)`。它做的事只有一件：**按 canonical 地址做读写集合相交**——写-写 ⇒ 拒，读-写 ⇒ 留痕。**它不评估任何领域条件**（不知道国家、不知道交战、不知道胜负）。
2. **领域条件只可能在 `② Propose` 里评估**：参与者的 `simulate(state, range)` 是纯函数，能看到 `state` 全切片与 `range.to`，可以自己算条件。
3. **参与者只能写自己 namespace 的数据**：`TimeProposal` 携带单一 `namespace` + 本模块 `ChangeSet`；`TimeProposalResolver` 把它装进 `WorldChangeSet` 的对应键。⇒ **`sd` 参与者不能直接改 unit/map 的状态数据**（要改只能另发命令走信封支，或由 unit/map 自己的参与者改）。
4. **每个 namespace 只能有一个 participant**（`TimeAdvance.java:124-127`，`putIfAbsent` 重复即抛）；参与者按 namespace **字典序**定序、拿**同一份 base**（C25）。
5. **`④ Validate` 只做机械校验**（编码能编、能 apply、namespace/键/版本一致），**不判断语义对错**。

**含义**：brainstorm 里的「**判决系统**」（汇总一个 tick 的各 Directive，产出第二、三段：判决 + 效果）以及「**延期效果**」（未来条件达成才发生），在 simos 的六步里**没有对应的领域步骤**。可落地的唯一现成位置是 **`② Propose` 的一个新 `TimeParticipant`**（namespace 如 `sd`）：它用 `range.to` 评估条件、达标才产出 `sd` 的 ChangeSet；`③④⑤⑥` 会自动接管。**但**：它只能写 `sd` 数据，若「效果」要改 unit/map，只能把它转成待发的 `Command`（信封支）或依赖对应模块的参与者——这是设计必须正面回答的结构性问题。

---

## §D 对裁决表的影响

下列事实**改变或新增**了既有待裁决项。**不在此处下裁定**——只把「事实 → 选项收窄」摆清。

### D-R6 延期效果的落点

- **事实**（§C③）：`③ Resolve` 不做领域条件评估；唯一能评估未来的位置是 **`② Propose` 的 `TimeParticipant.simulate(state, range)`**。
- **影响**：R6「预定在未来什么条件达成后发生什么」**不能**落成「Resolve 阶段的钩子」，只能落成**新的 `sd` TimeParticipant**。⇒ 待裁决收窄为：
  1. 条件评估的 DSL/形态（在 `simulate` 里手写 Java？还是数据驱动？）；
  2. 该 participant 的 `reads/writes` 地址集怎么声明（决定它与其他模块的冲突面）；
  3. **效果若需改 unit/map**：是产出待发 `Command`，还是依赖对应模块的 participant（见 D-R9）。
- 另有硬约束：`sd` namespace **只能有一个** participant（§C③.4）。

### D-R9 判决系统在 simos 里无对应步骤

- **事实**（§C③）：六步里没有「判决」这一步；`③ Resolve` 是地址集合相交，`④ Validate` 是机械校验。brainstorm §4 的「判决系统」= 汇总一个 tick 的各 Directive、产出第二三段（判决 + 效果）。
- **影响**：待裁决收窄为「**判决在哪里执行**」：
  - (a) 在 **`sd` 的 TimeParticipant（②）** 内做（引擎内、可重放、确定论），但受 §C③ 的「只能写 sd」约束；
  - (b) 在 **SDSimos 的命令路径（信封支）** 内做（把判决做成一条 `sd.*` 命令的 handler），走 `Command → ChangeSet → Revision`；
  - (c) 在**引擎外的 GUI / Agent** 做（用户智力或 LLM），只把结果写成命令——**可读性优化**归此处，但需裁决「判决本身算不算可重放的状态」。
- 决策的领域对象是 `Directive`（R3），**不与信封 `Command` 撞名**——这点不变。

### D-R13 「Nation 区域」用 tag 还是新字段

- **事实**（§A.3 + ▲）：`RegionMeta` 只有 `(color, tag, description, annexedBy)`，**四字段都可为 null**，**没有 `type` 字段**；`Nation` 在领域类型里**不存在**。`MapAgentAttachPolicy` 的 javadoc 已显式记录此缺口，并实现「任意 Region 即可绑」的老实版本。
- **影响**：R13 待裁决收窄为三选一：
  - (a) **用 `tag` 字符串约定**（如 `tag == "Nation"`）——零结构改动，但**无类型护栏**、易拼错、且 SDSimos 只能靠字符串匹配；
  - (b) **给 `RegionMeta` 新增字段**（如 `nationId`）——动 map（M2/M8 既有铁律与往返守卫需同步），但类型安全；
  - (c) **SDSimos 自己维护 region→nation 从属**（不动 map）——最干净的分层，但「代表国家的区域」这一用户直觉要由 SDSimos 的地址/查询还原。
- 附：`annexedBy` 字段已存在，可复用为「被吞并」语义（若裁决采用）。
- ★ 连带：`map.UpdateRegion` 能设 `tag`（`CreateRegionHandler.java:19` / `UpdateRegionHandler.java:19` 的样例载荷含 `"tag":"Nation"`），所以 (a) 在**载荷层**可行；但 M5 已裁定「不臆造字段」。

### D-R14 INFO 是否纳入 SDSimos 的 ChangeSet

- **事实**（§C①）：全仓 INFO **没有写路径**、不进 `WorldChangeSet`、`InfoSystem` **无 remove**、无 `InfoKey`、`value` 是裸 `Object`；只随 checkpoint 往返。
- **影响**：R14 待裁决为**二选一**（二者互斥或可并存，需明确）：
  - **(a) 纳入 SDSimos 自己的状态**：在 `SdState`/`SdChangeSet` 里放 info 组件（如 `Map<Address, List<InfoEntry>>` 或 SDSimos 自己的条目类型）⇒ 自动进 revision、可重放、受铁律 5 往返守卫；**但与全局 `InfoSystem` 是两套**。
  - **(b) 补全全局 INFO 写路径**：新 SPI / 新命令 + 装配点，让 Info 真正进 `WorldChangeSet` ⇒ 全仓统一，但改动 util/core/app 三处，且「无 remove」的语义要补齐。
- **新增待裁决**：若选 (a)，SDSimos 的 info 条目类型是**复用 `InfoEntry`** 还是**自造**？复用会拖入 `TimeRange`/`SubjectId` 与时态语义，自造则与全局 INFO 更难打通。
- 附：brainstorm §4 明确「**单条 Command 必须自带 INFO 作为执行原文**」「判决该数据化效果甚至可以是给其他地址改 INFO」⇒ R14 的答案直接决定「判决结果写哪」。

---

## §我未能核实的 / 空白

- ★ **Unit 探子 transcript 未落盘**：§A.1 由控制器本会话直接读源码重做（▲），未拿到第三份 explore 原件，无法与 `bg_50d01d6f` 的逐行输出对拍。**结论与 brainstorm §7 五缺口一致**，但「探子原文是否还有额外发现」**未核实**。
- **§A.2 / §A.3 的 `文件:行`**：除标 ★ 的复核项外，均为 transcript 引用（◇），本会话**未逐行复核**其行号；若行号漂移，以源码为准。
- **外部研究（§B）**：**完全空白**，待三路 librarian。
- **AgentLib 的内部语义**（PermissionChecker 决策顺序、ResourceScope 前缀语义等）：取自 transcript 对 `~/ProjectMosire/AgentLibMosire` 源码的实读，本会话**未复核**。
- **「更细粒度 resource scope 是否真能表达 per-hex」**：transcript 称「理论上可表达、零代码如此用」——本会话**未验证**该断言。
- **SDSimos 的模块坐标与装配点**：本文只记录既有装配点（`Shell.java:188-224`、`CoreSimos.register ×3`、core pom、`ArchitectureGuardsTest`），**SDSimos 具体怎么接**属设计，不在本次调查范围。

---

## 参考原件（未入库，仅路径）

- explore transcript（Info/地址/信封/SPI/两阶段）：`/home/cna/.local/share/opencode/tool-output/tool_0beb7ec4c001mEy6YJ5YPbYPex`（Task `bg_ecf53159`）
- explore transcript（Agent/权限/AgentLib）：`/home/cna/.local/share/opencode/tool-output/tool_0beb9a3460015HtWe0I9H3r2JJ`（Task `bg_f651e4db`）
- brainstorm：`docs/superpowers/specs/2026-09-20-sd-simos-brainstorm.md`（当前**未入库**，`git status` 显示 untracked）

