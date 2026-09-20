# SDSimos 前置调查 —— 既有接口实测与基础设施缺口

> 本文是 `2026-09-20-sd-simos-brainstorm.md` 的**调查附录**（只蒸馏结论，不抄 transcript）。术语沿用该文：**SDSimos = 国家 + 决策人**（R1 裁定 NAA = SDSimos），决策的领域对象叫 **`Directive`**（R3），一个决策人一个 tick 一个 `Directive`（R4）。
>
> **来源标注（重要）**：
> - **▲ 实测** = 控制器本会话（2026-09-20）**直接读本仓当前字节**得到，非转述。
> - **◇ 引用** = 取自 explore transcript（agent 实读当前字节、自带 `文件:行`）的结论；本会话未逐行复核，但两条 transcript 均由探索代理在同一棵树上实读。
> - **○ 空白** = 目前没有可靠结论。
>
> **原件落位（2026-09-20 更新）**：六份原件已全部取得。§A.1（Unit 模型）、§A.2（Info/地址/信封/SPI）、§A.3（Agent/权限/AgentLib）三份探索报告，**其中两两已落盘于 `/home/cna/.local/share/opencode/tool-output/`，Unit 那份只存在于会话 DB**——本会话从 DB 的 `part` 表取出原文（见「参考原件」）。★ **曾经的现象**：Unit 那份一度未落盘，§A.1 先由控制器直接读源码写成（▲），**现已与 DB 原文逐条对拍，结论一致**。
> **§B 来源**：三份外部研究（① 交战判决与战损〔主料〕② 编制与指挥链/ORBAT/拆附回归 ③ 决策周期/命令延迟/情报可见性）**均由 librarian 产出，含 URL**；原件位置见「参考原件」。

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

> **本节已由三路 librarian 回填（2026-09-20）。** 三份原件 = ① 交战判决与战损（主料，最长）② 编制与指挥链 / ORBAT / 拆附回归 ③ 决策周期 / 命令延迟 / 情报可见性。**每条结论带可靠性标签与 URL**。
>
> **可靠性标签**：**[Doctrine]** 官方条令/政府出版物 · **[OR]** 同行评审运筹/学术 · **[Mil-acad]** 军校论文/国防科学机构 · **[Sim-doc/Practitioner]** 厂商手册、开发者/玩家、爱好者惯例（**用于机制形状，不用于数值真值**）· **[Academic]** 学术/预印本。
>
> **区分**：★ 标记者为**对本设计最可迁移**的点；每节末尾给「对 SDSimos 的迁移点」。

### B.1 「多结局 + 概率，选其一」的规范名

- **规范名 = categorical distribution（a.k.a. multinoulli / generalized Bernoulli）**：K 个互斥结果上的离散分布，∑pᵢ=1，是「K 路事件上最一般的分布」。[[Wikipedia](https://en.wikipedia.org/wiki/Categorical_distribution)、[Wolfram](https://mathworld.wolfram.com/CategoricalDistribution.html)、[Statlect](https://www.statlect.com/probability-distributions/multinoulli-distribution)] **[reference/教材]**
- **兵棋里的名字 = CRT 的「列」**：每个列是一张**具名结果上的概率分布**——《The Complete Book of Wargames》(Freeman 1980) 原话「CRT 通过同时改变任一列里**能发生哪些结果**与**各结果发生的赔率**，保证结果不是纯随机的」。[The Anvil of Probability](https://www.skeletoncodemachine.com/p/combat-results-table) **[hobbyist，转引原始设计文献]**
- **游戏工程里的名字 = weighted outcome table / loot table**（累积分布加权选择）。[Blockrand: Weighted Random Selection](https://docs.blockrand.net/algorithms/weighted-random-selection.html) **[hobbyist/technical]**
- **作战 OR 里**有论文**从随机 Lanchester 模型直接导出 CRT**：Fan, Ren & Tian, "An Analysis of Wargame Rules Simulation Based on Stochastic Lanchester Models," *Proc. ACM* 2017（[doi:10.1145/3171592.3171612](https://doi.org/10.1145/3171592.3171612)）**[OR]**——是 B.1 与 B.3 的桥。
- ⇒ **迁移点**：brainstorm 的「单个阶段可预设多种结局 + 概率、选唯一一个作为实际结局」**就是 categorical distribution**；在数据里应建成 `outcomeTable = [(outcome, weight)]`（weight 归一化后即概率），选中恰好一个。

### B.2 CRT 谱系（列=赔率 / 行=骰 / 格=具名结局）

**结构**：**列 = 赔率比**（攻/防，如 1:3…3:1），**行 = 骰点**（传统 1d6，后 2d6/d100），**格 = 具名结局**；「较少见地，列基于**差值**而非比值」。[[Wikipedia: Combat results table](https://en.wikipedia.org/wiki/Combat_results_table)] **[hobbyist/reference]**

**结果词表**（标准化，见 *Wargame Design*, Berg/Dunnigan et al. 1977 的约 14 个结果）：**Ae**（攻方全灭）、**Ar**（攻退）、**Ex**（exchange 互损）、**De**（防方全灭）、**Rt**（溃退）。[The Anvil of Probability](https://www.skeletoncodemachine.com/p/combat-results-table) **[hobbyist，转引原始]**

**真实现代表例（含 step loss / exchange / DRM）**——Compass Games *Russia Besieged* CRT（[PDF](https://compassgamesbucket.s3.us-east-2.amazonaws.com/downloads/Russia_Besieged_CRTv2.pdf)）**[hobbyist]**：
- `A#` / `D#` = 攻/防损失 **# 步（step loss）**并后撤；
- `BR` = "Battle Rages"，未决，**双方各 1 步损失**；
- `X2` = Exchange，**双方各 2 步损失**；
- `DR`/`AR` = 后撤 1–2 格；
- **DRM（die-roll modifier）**来自空中支援、装甲对非装甲、河流/森林/沼泽/山地、天气、领导力。

**赔率现实性**：Dupuy Institute 记录 Avalon Hill 1960 年代 CRT 大致 **1:1 → 攻方 1/3 成功、2:1 → ≈2/3、3:1 → 必然**，并与 Lawrence *War by Numbers* 的 116 场 ETO 师级交战对比。[[Dupuy: Force Ratios and CRTs](https://dupuyinstitute.org/2020/12/24/force-ratios-and-crts/)] **[OR/analytical]**

**后代 / 替代**：**d100 roll-under**（属性=成功概率、度数化成功，去掉心算比值）、**对抗骰 + 结果矩阵**、**bucket-of-dice / to-hit-number**（产出**伤亡数的分布**而非具名结局）。[[Death to the Combat Results Table!](https://newsletter.rvgames.company/p/death-to-the-combat-results-table)、[TMP: Designing a CRT](http://theminiaturespage.com/boards/msg.mv?id=190153)] **[hobbyist]**

**已知坑**：表太复杂拖慢裁决（「6+ 小时后还在查表」）；修正不应**重复惩罚**；修正差值要自洽；CRT 给玩家的是「HQ 视角」而非「一线指挥官视角」。[[Anvil](https://www.skeletoncodemachine.com/p/combat-results-table)、[Death to the CRT](https://newsletter.rvgames.company/p/death-to-the-combat-results-table)] **[hobbyist]**

- ⇒ **迁移点**：SDSimos 的「阶段结局表」可照 CRT 形状，但**只借形状不借数值**；用 `outcomeTable` + 具名结局词表（可自定义如 `ATTACKER_WIN/DEFENDER_WIN/STALEMATE`），并显式列出 **modifier** 的来源与方向。

### B.3 Lanchester 的经验失败 + salvo / stochastic duel / Pₖ

**Lanchester / CLO**：Chase–Lanchester–Osipov 连续时间微分方程炮击损耗模型（约 1902–1914）；**平方律**说伤亡比与兵力比的平方成反比。[[Armstrong 2004, *Naval Research Logistics*](https://doi.org/10.1002/nav.10102)] **[OR]**

**批评（证据充分且严重）**：
- **Epstein, *The Calculus of Conventional War* (Brookings 1985)**：Lanchester 方程「未能捕捉战争的基本动力学……给出根本不可信的作战图景」。[[Brookings](https://www.brookings.edu/books/the-calculus-of-conventional-war/)] **[OR]**
- **Helmbold 1994「The constant fallacy」**：应用 Lanchester 方程时一个持续的逻辑谬误（**别把损耗系数当物理常数**）。[doi:10.1016/0377-2217(94)90303-4](https://doi.org/10.1016/0377-2217(94)90303-4) **[OR]**
- **Lepingwell 1987「The Laws of Combat? Lanchester Reexamined」** *International Security*。[doi](https://doi.org/10.2307/2538918) **[OR]**
- **经验失败**：Dupuy Institute 报告 **9 次独立检验**在历史地面战数据上**既证不出平方律也证不出线性律**，并引 RAND Stockfish (1975)：「没有明确的经验验证……可能根本无法验证」。[[Dupuy: Lanchester equations have been weighed…](https://dupuyinstitute.org/2016/02/29/lanchester-equations-have-been-weighed/)] **[OR/analytical]**
- **根因 = 压制（suppression）未建模**：US Army CGSC 专题分析 NTC 交战数据，发现平方律不拟合、**指数律**更拟合，根因是**压制与火控**——而这正是 Lanchester 假设掉的；报告还指出把压制建模成完全「hiatus」会严重失真。[[CGSC monograph](https://cgsc.contentdm.oclc.org/digital/api/collection/p4013coll3/id/1786/download)] **[Mil-acad]**
- **海陆之别**：Hughes 指出平方律在地面「对数据拟合很差」（防御方利用地形/工事），但在海上「通常成立」（无地形优势）；并强调**只用伤亡衡量胜负会忽略压制**——地面战无海上对应物。[[Hughes 1995, NPS 全文](https://calhoun.nps.edu/server/api/core/bitstreams/f6c38a6c-d56e-4dab-b045-8284411f8672/content)] **[OR]**
- **系数不确定**：Mittal, *J. Defense Modeling & Simulation* 23(2) 2026，从小单位战斗模型反推 Lanchester 损耗系数，结果「受固有限制」。[[RePEc](https://ideas.repec.org/a/sae/joudef/v23y2026i2p193-205.html)] **[OR]**

**Salvo 模型（Wayne Hughes）**：面向**离散导弹齐射**而非连续炮弹。[[Hughes 1995, *NRL* 42(2):267–289](https://ideas.repec.org/a/wly/navres/v42y1995i2p267-289.html)、[NPS 全文](https://calhoun.nps.edu/server/api/core/bitstreams/f6c38a6c-d56e-4dab-b045-8284411f8672/content)] **[OR]**：建模**进攻力 − 防御力 vs 存活力（staying power）**，用 **fractional exchange ratio** 作稳健比较指标；兵力随战斗力相对存活力增长会**失稳**；数量优势一贯最有利；Hughes 明言「逐案结果差异之大，强烈暗示**先抓到清晰规律之前，细致仿真将是空洞的**」。

**随机扩展与验证**：
- **Armstrong 2005, "A Stochastic Salvo Model", *Operations Research* 53(5):830–841**：加随机性；引 Ancker「任何合格模型至少须给出所有相关结果随机变量的**均值与方差**」，并指确定性模型「在幸存者数量或胜利概率上可能相当误导」。[doi:10.1287/opre.1040.0195](https://doi.org/10.1287/opre.1040.0195) **[OR]**
- **Armstrong 2011, "A verification study…", *Annals of OR* 186**：区分**verification**（是否按设计工作）与 **validation**（是否反映现实）；**命中正相关**时低估方差与 95 分位损失。[doi:10.1007/s10479-011-0889-0](https://doi.org/10.1007/s10479-011-0889-0) **[OR]**
- **Li 2018 NPS 论文**：闭式随机 salvo 模型在 **overkill / intermediate / over-defense** 制度下存在**截断偏差**。[[hdl:10945/59709](https://hdl.handle.net/10945/59709)] **[Mil-acad]**
- **Lucas & McGunnigle 2003**：简单透明模型被**低估使用**，「多数战斗仿真**应当是随机的**」。[[Wiley](https://onlinelibrary.wiley.com/doi/10.1002/nav.10062)] **[OR]**

**Lethality 制度（对结局设计重要）**：Armstrong 的杀伤力分类显示「**更多更好**」在**低与高杀伤力两端都失效**，只有中等杀伤力才直觉成立；高杀伤力下**一次未被回应的齐射就能终结战斗**。[[Armstrong 2004](https://doi.org/10.1002/nav.10102)] **[OR]**

**随机对决（stochastic duel）与「P(t)」**：
- **Williams & Ancker 1963, "Stochastic Duels", *Operations Research* 11(5):803–817**：两方互射至一方被杀，效能度量 = **某方获胜的概率**；开火时间密度 + 固定击杀概率。[doi:10.1287/opre.11.5.803](https://doi.org/10.1287/opre.11.5.803) **[OR]**
- **Ancker 1967, "The Status of Developments in the Theory of Stochastic Duels—II", *OR* 15(3):388–406**：权威综述，明确讨论与 Lanchester、博弈的联系。[doi:10.1287/opre.15.3.388](https://doi.org/10.1287/opre.15.3.388) **[OR]**
- **Ancker 1979, "The One-on-One Stochastic Duel"（DTIC ADA068008）**：**混合论 + 更新论** + 详尽文献目录。[[DTIC 记录](http://oai.dtic.mil/oai/oai?identifier=ADA068008&metadataPrefix=html&verb=getRecord)] **[Mil-acad]**
- **Gafarian & Ancker 1984, "The two-on-one stochastic duel", *NRLQ* 31(2):309–324**：状态方程、胜率、均值**与方差**；证明 duel、Stochastic Lanchester、Lanchester **三者不等价**。[doi:10.1002/nav.3800310213](https://doi.org/10.1002/nav.3800310213) **[OR]**
- 后续：many-on-one + 情报共享（[Li & Liu 2012](https://doi.org/10.4236/am.2012.36097)）、时变命中概率（[Ancker 1984](https://doi.org/10.1002/nav.3800310303)）、异质目标**击杀时间概率密度函数**（[Jiang 2015, WSEAS](https://wseas.com/journals/mathematics/2015/a085706-514.pdf)）、多战场 n 人对决博弈（[MDPI Mathematics 2021](https://www.mdpi.com/2227-7390/9/8/825)）。**[OR]**
- ★ **诚实注**：**未能**证实一个正式命名为「**P(t) model**」的单独构念。经验证的是：(a) 随机对决的**胜率**与**击杀时间分布**（「P(t)」应指此），(b) 标准术语 **Pₖ（probability of kill）**。**把「P(t) model」当非正式简称，不是可引用的具名模型。**

**何时用哪个**（迁移用）：

| 模型 | 适用制度 | 失效模式 |
|---|---|---|
| Lanchester/CLO | 连续射击、同质聚合、海军炮战 | 忽略压制/地形/士气；平方律在地面经验失败；constant fallacy；系数不确定 |
| Salvo (Hughes) | 离散导弹齐射 + 防御；海军 | 确定论（无方差）；聚合目标（假设火力均摊）；无战术/目标选择 |
| Stochastic salvo | 同上、方差/风险重要时 | 正相关偏差；截断偏差 |
| Stochastic duel | 少量实体、探测+射击+击杀 | 组合爆炸；多为 1v1/2v1/nv1；非聚合 |

- ⇒ **迁移点**：**结局概率**用 categorical（B.1/B.2）；**伤亡量级**另配**独立的损耗函数**（Lanchester / salvo / duel 之一），**不要**从 CRT 反推伤亡物理；小规模参与（如「第一阶段上侦察单位」）适合 duel 的 P(win)/kill-time，大规模兵力适合聚合模型。已知陷阱：无随机（须给均值+方差）、正相关、截断偏差、把系数当常数、忽略压制。

### B.4 分阶段：JP 5-0「条件驱动而非时间驱动」+ FATHM + 攻击阶段名

**JP 5-0 *Joint Planning* 是最干净的「数据驱动阶段」权威**：
> 「一般地，战役/行动的阶段划分应**以条件驱动（condition-driven）而非时间驱动（time-driven）**来构思。」……「每个阶段应有一组**起始条件（starting conditions）**与**结束条件（ending conditions）**。**上一阶段的结束条件就是下一阶段的起始条件。**」……「阶段之间的转换……通常是**事件驱动（event driven），不是时间驱动**。」阶段可**压缩、扩展或整体省略**。
——[JP 5-0 (2020) PDF](https://www.esd.whs.mil/Portals/54/Documents/FOID/Reading%20Room/Joint_Staff/18-F-1152_JP_5-0_Joint_Planning_2020.pdf)、[JP 5-0 (2011) PDF](https://jfsc.ndu.edu/Portals/72/Documents/JC2IOS/DOPC/JP%205_0%20Joint%20Planning.pdf) **[Doctrine]**
**六阶段联合模型**（明示**非规定性**）：**Shape(0) → Deter(I) → Seize Initiative(II) → Dominate(III) → Stabilize(IV) → Enable Civil Authority(V)**。**[Doctrine]**

**FM 3-0 *Operations***：阶段 = 「大部队遂行相似或相互支援活动的一段时期」；另有 **decisive / shaping / sustaining** 框架（每个梯队**只有一个** decisive operation）。[[FM 3-0 ch.6](https://www.globalsecurity.org/military/library/policy/army/fm/3-0/ch6.htm)] **[Doctrine]**

**战术交战名**：
- **engagement** = 「通常是较低梯队机动部队之间的战术冲突……通常短暂，以分/时/日计」[[ADRP 3-90](https://www.globalsecurity.org/military/library/policy/army/adrp/3-90/adrp3_90.pdf)、[MCWP 3-01](https://www.marines.mil/Portals/1/Publications/MCWP%203-01.pdf)]。
- **meeting engagement** = 「一支尚未完成战斗展开的移动部队与敌在意外时间地点交战」[[ADP 3-90](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN34828-ADP_3-90-000-WEB-1.pdf)、[FM 3-90 ch.4](https://www.globalsecurity.org/military/library/policy/army/fm/3-90/ch4.htm)]。
- **engagement area (EA)** = 「指挥官意图以全部可用武器与支援系统的**集中效果**围歼敌军的区域」，可细分给下级。[[FM 3-90 ch.2](https://www.globalsecurity.org/military/library/policy/army/fm/3-90/ch2.htm)]。
- **engagement criteria** = 「规定发起交战情形的协议」，**trigger line** = 「用于发起并集中火力的 phase line……判据可以是**时间或事件驱动**」。[[FM 3-90 ch.2](https://www.globalsecurity.org/military/library/policy/army/fm/3-90/ch2.htm)] **[Doctrine]**

**deliberate attack 的阶段名与部队角色**：
- **序列**：侦察 → 向出发线（LD）机动 → 机动 → 展开 → **攻击** → **巩固与整编（consolidation and reorganization）**。[[FM 5-71-2 ch.3](https://www.globalsecurity.org/military/library/policy/army/fm/5-71-2/chap3.htm)、[FM 3-90.2 ch.5](https://www.globalsecurity.org/military/library/policy/army/fm/3-90-2/chap5.htm)] **[Doctrine]**
- **部队角色**：**support force → breach force → assault force**，另加 **reserve**。[[FM 90-13-1](https://www.bits.de/NRANEU/others/amd-us-archive/FM90-13-1%2891%29.pdf)、[EN5481 Lesson 2](http://images.globalsecurity.org/military/library/policy/army/accp/en5481/le2.htm)] **[Doctrine]**
- **四阶段协调攻击**：接近目标 → 孤立突破口 → 突破/突入取得立足点 → 扩张战果。[[ACCP in0821 ch.2](https://www.globalsecurity.org/military/library/policy/army/accp/in0821/ch2.htm)] **[Doctrine]**
- **渗透阶段**：撕裂阵地 → 扩大缺口 → 夺取目标。[[FM 5-71-2 ch.3](https://www.globalsecurity.org/military/library/policy/army/fm/5-71-2/chap3.htm)] **[Doctrine]**

**真实战斗模型里的数据驱动分阶段**：
- ★ **FATHM**（Fast Theater Model；Brown & Washburn, *Military Operations Research* 12(4) 2007，[doi:10.5711/morj.12.4.33](https://doi.org/10.5711/morj.12.4.33)）**[OR]**：「战争分阶段推进，阶段的完成取决于**按类别分类的目标击杀阈值**与**阶段时长的上下限**；**每个阶段有各自的 COSAGE 输入文件，故各阶段可差异极大**。」——这**正是一张阶段表**：阶段 ⇒ (进入/退出阈值、min/max 时长、自有参数文件)。
- **微 tick 阶段**：Smoler, NPS 1979, "Operational Lanchester-Type Model of Small Unit Land Combat"（[hdl:10945/18818](http://hdl.handle.net/10945/18818)）**[Mil-acad]**：每 10 秒 tick 跑 **移动 → 探测 → 火力分配 → 损耗 → 战斗终止** 五阶段。
- **兵棋的 Action–Reaction–Counteraction 循环**（Belt / Avenue-in-Depth / Box 技法），一个阶段（「critical event」）在事件完成时终止。[[Gossman et al.](https://www.govinfo.gov/content/pkg/GOVPUB-D101-PURL-LPS111099/pdf/GOVPUB-D101-PURL-LPS111099.pdf)] **[Mil-acad]**
- **开源参考**：`Reptarus/five-parsecs-campaign-manager` 的 `CampaignPhaseManager`（MISSION/BATTLE_SETUP/BATTLE_RESOLUTION）、`BattleManager` FSM、14 步战后序列。[[BATTLE_SYSTEM_ARCHITECTURE.md](https://github.com/Reptarus/five-parsecs-campaign-manager/blob/master/docs/technical/BATTLE_SYSTEM_ARCHITECTURE.md)] **[open-source，低-中可靠性]**

- ⇒ **迁移点**：SDSimos 的「阶段」应为**数据对象**，至少含 `entryConditions / exitConditions / minDuration / maxDuration / outcomeTable`；并采纳 JP 5-0 的**链式条件**（**上一阶段 exit = 下一阶段 entry**）+ FATHM 的**阈值击杀 + 时长上下限**。这直接回应 brainstorm「一场交战可自由添加多个阶段」与「单个阶段可预设多种结局」。

### B.5 战损记账：step loss / strength point / 人员与装备双轨 / 永久 vs 可回收 / 保留记录

**表示法族**（[[Hollandspiele: Scattershot Thoughts on Step Losses](https://hollandspiele.com/blogs/hollandazed-thoughts-ideas-and-miscellany/from-the-archives-scattershot-thoughts-on-step-losses)] **[hobbyist]**）：
1. **step reduction** —— 单位有 2–3「步」，损失一步即翻面/降级；
2. **strength points (SP)** —— 10+ 点逐步递减，可用独立 SP 轨；
3. **多轴轨** —— *Musket & Pike* 分开跟踪 **Formation Hits / Morale Hits / Casualty Points**，退化**不是一个标量**；
4. **combat value / force strength** —— 聚合 CV，既定赔率又吸收损失（[WITE2 ch.23](https://dornshuld.chemistry.msstate.edu/rules/wite2/23-0.html)、[Balagan](https://balagan.info/tabletop-operational-wargame-inspired-by-hells-gate)）。

**人员与装备分开跟踪 —— 具体系统**：
- ★ **WITE2（War in the East 2）**：损失**按地面 element**（人、炮、坦克、飞机）跟踪；「element 被毁时，其 manpower 等的一定比例可记为 **killed / disabled / captured**」；区分 destroyed/damaged/**disrupted**，damaged 可恢复，**disabled 的人以 1%/tick 回池、1% 转为 KIA**；战报展示被毁 element，而 **Permanent Losses 列**把 element 损失换算成 manpower/guns/tanks/planes。[[WITE2 ch.23](https://dornshuld.chemistry.msstate.edu/rules/wite2/23-0.html)] **[hobbyist/commercial sim]**
- ★ **WEGO WWII: Stalingrad**：把 **Strength**（「坦克、士兵、火炮数量……减少是**永久的**」）与 **Readiness**（休整可恢复）分开；「**损失先从 Readiness 取，再取 Strength**」；跟踪 **Personnel 与 Equipment 的随时间消耗**，并保留上一回合每场交战的 **Battle Summary Table** + 一个 **「film」文件作为发生过的记录**。[[WEGO WWII 手册](https://ftp.matrixgames.com/pub/WEGOWorldWarIIStalingrad/WEGOWW2GameManual.pdf)] **[hobbyist/commercial sim]**
- ★ **Flashpoint Campaigns: Cold War**：**「Butcher's Bill」**，按子单位类型与平台给出 *Started / Active / Withdrawn / **Fallen Out**（伤/损）/ **Destroyed**（不可修复/KIA）* + 估计恢复时间；force strength 为起始 VP 的百分比，并有灾难性损失的「sudden death」阈值。[[On Target Simulations](https://www.ontargetsimulations.com/guides/coldwar/fieldmanuals/game-operations/victory-conditions-and-end-game/)] **[hobbyist/commercial sim]**

**损失记录 / 回放 / AAR**：WEGO 的 **film file** + Battle Summary Table；WITE2 的 **losses screen**、**Last Action** 列、逐战报；**DLRC/Eagle** 训练联邦**刻意保留 ground truth 供 After Action Review**，并喂给 **Intelligence Message Generator**（即同时保留**真态**与**情报图**）。[[MITRE/CCRP HLA 论文](http://www.dodccrp.org/events/6th_ICCRTS/Tracks/Papers/Track3/114_tr3.pdf)] **[Mil-acad]**

**Deltas vs 整份替换**：**delta**（step loss / SP 递减 / element 被毁 / readiness 受损）是记录**事件**的方式；**replacement points / 补充**是恢复方式（如 Balagan 花 replacement combat strength points，不得超原编）。[[Balagan](https://balagan.info/tabletop-operational-wargame-inspired-by-hells-gate)] ★ ⇒ **对 SDSimos 直接对应铁律 2/5**：**战损记为 delta（损失事件/ChangeSet），不是覆写绝对强度**，才可被时间线回放恢复。

**效能度量（MoE）**：**Loss Exchange Ratio (LER)** 是国防科学的标 MoE——「开阔地形以损耗为主，近距地形以**压制**为主」[[Bowley, Castles & Ryan, DSTO/DTIC ADA430723 (2004)](http://oai.dtic.mil/oai/oai?identifier=ADA430723&metadataPrefix=html&verb=getRecord)] **[Mil-acad]**；**fractional exchange ratio**（Hughes）用于海军兵力比较；**保真度经验法则**：高保真应用 **probability-of-hit-to-kill**，低保真才用**损耗率**（Lanchester 或改造）[[Franz, UNSW thesis 2025](https://doi.org/10.26190/unsworks/31669)] **[Mil-acad]**。

- ⇒ **迁移点**：SDSimos 的战损应为**双轨（人员 + 装备）+ 多通道**，且**区分永久与可回收**；**保留损失记录**（供回放/AAR），并用 **delta 语义**（直接回应 §A.1 的缺口③：现有 `unit.SetStrength` 是整份替换、无增量）。

### B.6 雾战：ground truth 与 commander's estimate 分离、报告节点与决策因子节点分离、per-role 可见性

**「模型记录谁知道什么吗？」——是，靠显式分开 ground truth 与 per-side 感知**：
- DiVA 论文 *Comparison of 'Fog of War' models in digital wargames*：关键概念 = **ground truth**（「未被任何误差/不确定性遮蔽的精确真实状态」）vs **per-player 侦察图**；提出 **5 级**视觉探测保真（距离圈 → 含高度的 LOS → 天气/地物/随机 → 分类识别 → 多传感器与对抗）。[[DiVA 全文](https://www.diva-portal.org/smash/get/diva2:1763084/FULLTEXT01.pdf)] **[Mil-acad]**
- ★ **SSIM CODE**（Posadas, NPS 2001，[hdl:10945/2518](https://hdl.handle.net/10945/2518)）**[Mil-acad]** 是 **commander's estimate** 的最强先例：一个**贝叶斯网络**，三组节点——**commander's decision / reports / decision factors**——其中「决策结果**概率依赖于 reports 状态**，而**独立于 decision factors 状态**」。即指挥官基于 `P{E=f}`、`P{R=r}`、`P{B=b}` **估计**行动，而非基于真相；联系到 **Boyd 的 OODA** 与 METT-T。
- **AFSIM / Fog Analysis Tool**（Tryhorn, AFIT，[DTIC AD1135178](https://apps.dtic.mil/sti/trecms/pdf/AD1135178.pdf)）**[Mil-acad]** 把雾注入 **传感器与通信**（FIMM/FAT），并列出 **Setear** 的经典雾法：裁判、隐蔽标记、**未试单位**、规则书修改、地图修改、战争法不确定性。
- **Eagle/DLRC** 向 **Intelligence Message Generator** 提供模拟传感器输出，情报图是**一等产物**。[[MITRE/CCRP](http://www.dodccrp.org/events/6th_ICCRTS/Tracks/Papers/Track3/114_tr3.pdf)] **[Mil-acad]**

**现有仿真怎么实现（per-role 可见性的既有实现）**：

| 仿真 | 机制 | 来源 | 可靠性 |
|---|---|---|---|
| Command Ops 2 | per-side 目视、增强 sighting code；玩家只见侦查到的敌军 | 官方特性 + Armchair General | **[Sim-doc/Practitioner]** |
| Command: Modern Operations | **God's Eye** 开关；Tacview FOW 只显示己方+**已探测接触**，未分类前为半透明形状；开发者明说 Tacview 是 ground truth、是作弊向量 | [combatsim.com](https://www.combatsim.com/2020/01/command-modern-operations-a-new-open-beta-to-test-the-fog-of-war-with-tacview.htm/) | **[Sim-doc]** |
| DCS: Combined Arms | F10 地图 FOW；**接触丢失后淡出**；角色分层 **Game Master / Ground Force Command / JTAC / Observer**；⚠️ 开发者注「AI 可能**未真正探测**就‘感知’到敌单位」= 真实的 ground-truth 泄漏 | [DCS 手册](https://server.3rd-wing.net/public/Manuels%20DCS/Combined%20Arms%20Manual%20EN.pdf) | **[Sim-doc]** |
| Combined Ops (DCS 三方 C2) | 层级 **IFF**：所有接触初始 **UNKNOWN**，靠人工控制员、Mode 3 ATO 匹配、Mode 4/S 识别 | [wiki.combinedops.org](https://wiki.combinedops.org/radar-contact-visibility-logic-p48) | **[Sim-doc/Practitioner]** |
| OneSAF / JSAF | Sides & Forces 模型；传感器**混淆矩阵**（可把 M1 认成 T-80）、刻意假阳/假阴；暴露的 **VSpotter/VKillAssess/VTargetAssess「不代表 ground truth，而是 perceived reality」** | [OneSAF](http://www.bucksurdu.com/Professional/Documents/05E-SIW-030_7Mar05.pdf)、[CMU C4ISR](http://www.cs.cmu.edu/~softagents/papers/giampapa_c2s.pdf) | **[Academic/Sim-doc]** |
| Atlatl (论文) | fog-of-war 框架；**层级 AI 每梯队共享一个概率分布**，「每个梯队有该梯队受限的信息」 | [thesis](https://files01.core.ac.uk/download/618458519.pdf) | **[Academic]** |

**要照抄的模式（四层）**：(1) **权威 ground-truth 存储，任何 agent 都不得直接查询**；(2) **per-actor 感知态**，只由其传感器/探测 + 消息推出；(3) **按角色/能力裁剪的 redaction 层**；(4) **裁判/White Cell 是唯一被允许看并转译 ground truth 的实体**。

- ⇒ **迁移点**：SDSimos 的「决策人 tool-scope 限制」应落成**感知态 + redaction**，而不是「工具白名单」（§A.3 已证：工具白名单 ≠ 数据脱敏）；`groundTruth` 与 `perception[side]` 应**并列记录**，AAR/回放读 ground truth，UI 读 perception。报告节点与决策因子节点**分开**（SSIM CODE 的贝叶斯结构）。

### B.7 编制 / ORBAT：task organization、detach-attach、回归路径、子树迁移与已知坑

**条令定义（一手来源）**：
- **attach** = 「把单位或人员置于某组织中，该置于**相对临时**」；**assign** = 「置于……**相对永久**……控制并管理该单位以遂行主要职能」。*ADRP 1-02 (2015)*，[PDF](https://www.bits.de/NRANEU/others/amd-us-archive/adrp1_02%282-15%29.pdf) **[Doctrine]**
- **cross-attachment** = 「**临时**交换下属单位。例：坦克营把坦克连拨给机步营，机步营把机步连拨给坦克营」。*FM 101-5-1 / FM 1-02*，[archive.org](https://archive.org/download/operationalterms00unse/operationalterms00unse_djvu.txt) **[Doctrine，旧版]**
- **task-organizing** = 「为满足独特任务/使命而设计特定规模与组成的部队、支援参谋或保障包」。*ADP 3-0 (2019)*，[armypubs PDF](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN43323-ADP_3-0-000-WEB-1.pdf) **[Doctrine]**
- **supported / supporting** = JFC 在组件间建立的支持/被支持关系；[[FM 3-0 ch.2](https://www.globalsecurity.org/military/library/policy/army/fm/3-0/ch2.htm)]。北约对应 **SSI（Supported/Supporting Interrelationships）**：「**平等指挥官之间**的具体关系……由共同上级建立、定义、必要时仲裁」[[AJP-3, Ed. D](https://assets.publishing.service.gov.uk/media/68b84313536d629f9c82aa29/AJP-3_Ed_D_V1-O.pdf)]——★ **SSI 明确用于平等指挥官，不是父子链接**，**不应**拿来当 re-attach 机制。**[Doctrine]**

**★ 附着持续多久、什么触发回归 —— 关键原文**：
> **FM 3-0 (2022) App. B, B-21**：「**附着单位临时从属于接收司令部，常达数月或更久。当附着的**理由（reason）结束**时，它们回归其母司令部（assigned 或 organic）。**」
> **B-22**：「陆军指挥官通常把单位 **OPCON 或 TACON** 给接收司令部，**为给定任务，约数日**。OPCON 让接收指挥官**编组并指挥**部队；TACON **不允许**接收指挥官编组该单位。二者都不影响 ADCON 责任。」
——[FM 3-0 (2022) PDF](https://soldat-und-technik.de/wp-content/uploads/2022/10/ARN36290-FM_3-0.pdf)、[armypubs ARN43326](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN43326-FM_3-0-000-WEB-1.pdf) **[Doctrine]**
- ⇒ **时长是「任务界定」而非「时钟界定」**；关系是**分级的**（attach / OPCON / TACON / ADCON），**不是布尔**；「**reattach 不是条令词**」，条令动词是「**return / revert to parent headquarters**」。
- 北约指挥权限 OPCOM/OPCON/TACOM/TACON：*UK JDP 3-00 Annex 1C*（[PDF](https://assets.publishing.service.gov.uk/government/uploads/system/uploads/attachment_data/file/810041/archive_doctrine_uk_campaign_execution_jdp_3_00.pdf)）+ *DTIC ADA403478*；AJP-3 强调 OPCON 下「编成要素的**结构须保持完整**」——即 **控制 ≠ 结构性重挂父**。**[Doctrine]**

**「回归路径」的条令语汇**（★ 「return path」**不是**条令词）：
| 概念 | 标准词 | 来源 |
|---|---|---|
| 向友军会合的运动 | **linkup** | FM 3-90.2 ch.12；ATP 3-21.8 §6-44 |
| 会合点 | **linkup point**（主 + 备） | ATP 3-21.8 §6-47/6-51 |
| 紧急集结点 | **rally point** | ATP 3-21.8 §6-51 |
| 回归前集结区 | **assembly area (AA)** | FM 3-90 ch.14 |
| 穿过友军阵地 | **passage of lines**（约 2/3 通过时交接责任） | FM 3-90 ch.16 |
| 阵地交接 | **relief in place** | ADP 3-90 ch.5 |
| 陆航/舰艇归建 | **RTB / return to station** | CMO 手册 |
| 在长机下物理归建 | **form-up / station-keeping** | AFSIM `WsfFormationAttachCommand`；C:MO |
[FM 3-90.2 ch.12](https://www.globalsecurity.org/military/library/policy/army/fm/3-90-2/chap12.htm)、[ATP 3-21.8 §6](https://infantrydrills.com/manuals/fm-atp-3-21-8-infantry-rifle-platoon-squad-2024/tactical-enabling-operations-activities/linkup/)、[FM 3-90 ch.14](https://www.globalsecurity.org/military/library/policy/army/fm/3-90/ch14.htm)、[FM 3-90 ch.16](https://www.globalsecurity.org/military/library/policy/army/fm/3-90/ch16.htm)、[ADP 3-90](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN34828-ADP_3-90-000-WEB-1.pdf) **[Doctrine]**

**既有仿真怎么建模**（★ 含 **AFSIM = 与本需求近乎精确对应**）：

| 引擎 | 结构 | task-org 覆盖层 | detach/attach | 回归母体 |
|---|---|---|---|---|
| **Command Ops 2** | HQ 树；**多套 force group 结构以支持 cross attachment** | **有**（独立 OP Plan OOB 视图） | 有，**Reattach** 命令 | 隐式（有已知 bug，见坑①） |
| **Command: Modern Operations** | 群组 + Group Lead；飞机有 home base/host 指针 | 无独立覆盖层 | 有（`G`/`D`） | 有：**RTB + ferry**；Formation Editor 相对/固定方位站位 |
| ★ **AFSIM**（美国 DoD） | **两个独立结构**：具名 **`command_chain`**（命令/报告）与显式 **`WsfFormation`** 树（物理） | **有** | 有，一等命令 `WsfFormationAttachCommand`/`DetachCommand`/`AddSub`/`RemoveSub` | 有：attach 后「成员 form-up 并**保持与 leader 的相对站位**」 |
| **OneSAF / JSAF** | 按梯队单位树；「Force」是 side 下的分组 | **有**（Task Organization 窗口） | 有（Detach/Attach/Remove subordinates） | 未记为路线；重编是数据编辑 |
| **VR-Forces** | 梯队层级；聚合单位有 Superiors/Subordinates | **实体聚合** | 有（聚合坍缩/展开） | 有：Formations 下「**保持编队内正确位置**」 |
| **Janus** | 聚合单位 + 实体 | 场景定义 | 有限（mount/dismount） | 无 |
| **Steel Beasts Pro** | 呼号层级 | 未记载 | 未记载 | 无 |
| **MSDL / C2SIM** | **ORBAT 序列化含显式 superior/subordinate 关系** | **有**（Task Organization 是独立概念） | 数据级 | 无 |
来源：[Command Ops](http://www.panthergames.com/2013/12/command-ops.html)、[CMO 手册](https://www.matrixgames.com/amazon/PDF/CMO/CMO_manual_EBOOK.pdf)、[AFSIM formation.html](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/formation.html)、[OneSAF](https://www.west-point.org/users/usma1982/39377/john/Publications/2004/2004-03_MP/HillSurduMissionPlanning04.pdf)、[VR-Forces](https://www.mak.com/mak-one/apps/vr-forces)、[MSDL/C2SIM brief](http://dodccrp.org/events/18th_iccrts_2013/post_conference/presentations/021.pdf)。**[Sim-doc/Academic]**

**★ AFSIM 的关键语义（最可迁移）**：
- 编队是**层级**：可含子编队，最终到**有成员平台的 unit formation**；每个编队有**相对名**（同辈唯一）与**限定名**（`yankee.alpha.one`）。
- **每个编队要么 attached 要么 detached 于其父**；attached 时成员**保持与 leader 的相对站位**；**attached 状态是逐编队独立的**（可大部分 attached、**单个子编队 detached**）。——直接回答「树约定 vs 显式子表」：**关系是显式树上的 per-node 标志**。
- **命令传播尊重 attach**：多数命令**不**传播到 detached 子编队（`WsfFormationAttachCommand` 是显著例外）。
- ★ **attach 级联，detach 不级联**（刻意的不对称）：`AttachCommand` 会**连降序子编队一并 attach**；`DetachCommand`**不修改子编队的 attach 状态**。
- `AddSubCommand` 加编队时**初始为 detached**；`RemoveSubCommand` **移除但不销毁**（被移除者变成**顶层编队**）；`DisbandCommand` 销毁编队+后代但**不改变成员平台状态**。
- **偏移相对父**：`WsfFormationOffset(range, relativeBearing, stack, weldedWing)` 或 `(right, ahead, above)` = 「**编队相对其父的相对偏移**」。
- **command_chain 独立**：`command_chain <name> <commander|SELF>`；**一个平台可属于多个 chain，但每条 chain 至多一次**；每条 chain 至少一个 commander。
[[formation.html](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/formation.html) 及 `WsfFormation{Attach,Detach,AddSub,RemoveSub,Disband,Offset}Command`、[command_chains.html](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/command_chains.html)] **[官方 AFSIM 参考文档]**
- ⇒ **AFSIM 把「谁向谁报告」（command_chain，可多属）与「谁物理上跟谁移动」（WsfFormation，严格树 + attached/detached + 相对偏移）分开**——**与本需求完全同构，建议照抄这个拆分**。

**子树迁移语义**：
- **AFSIM 的不对称级联**（见上）⇒ **必须显式裁决：detach 移动整棵子树，还是只移动该节点？**
- **位置是相对而非绝对**：AFSIM 偏移相对父；C:MO 相对/固定方位于 lead；VR-Forces 成员「保持编队内正确位置」且「**成员被毁时其他对象补位**」。⇒ ★ **子单位必须存「相对父的偏移」，绝不存绝对坐标**，否则父一动子树瞬移。
- **composition vs instance**：OneSAF「子单位按引用传递，改一个 M1 排会**自动更新**所有含它的连」——这是**类型级编成**，与**实例级重挂父**不同，别混。
- **DIS/HLA 聚合**：用单个 aggregate 实体替换子树、disaggregation 分解（[IEEE 1278.1 draft](https://freewrl.sourceforge.io/tests/28_Distributed_interactive_simulation/1278.1-200X%20Draft%2016%20rev%2018.pdf)）——若将来加 LOD/聚合，这是模型，也是坑⑤的来源。**[standard]**

**已知坑（均有出处）**：
1. **重新附着使母体反向追子**：Command Ops 玩家报告重新附着一个 20 格外的骑兵师后，**军团长反而朝骑兵位置跑去**；开发者回复「**已知问题**……像个牧羊犬去追走失的羊」；解法是**先让孤单位靠近再 reattach**。[[Matrix Games](https://forums.matrixgames.com/viewtopic.php?t=376541)] **[Practitioner + 开发者确认]** ⇒ ★ 你的「**仅当两者同位置才可合体**」正是这个修法，**应在引擎里强制，不能靠玩家/AI**。
2. **向移动母体的回归路线会过期**：CMO 官方 changelog v1.07 修复 #16284「Flightplan 航点不更新移动基地（如航母）位置，导致飞机 RTB 时飞向错误位置并坠毁」。[[Slitherine](https://www.slitherine.com/news/command-modern-operations-v107)] **[官方 changelog，第一方承认这是个真实且易错的特性]**
3. **detach 后编队空洞不自动补**（CMO；对比 VR-Forces 会补位）。[[tutorial](https://www.youtube.com/watch?v=0mxcfrMWpSU)] **[Practitioner]**
4. **单位回不到站位**：CMO #16305「编队中的舰船未能正确变速回位」、#16230「船无法停靠属于某群的港口」。[[changelog](https://www.slitherine.com/news/command-modern-operations-v107)] **[官方]** ⇒ 回归是**速度/操舵**问题，不只是路径问题。
5. **命令过载 / 控制跨度**：Command Ops「当 command load 超过最大 command span，整个行动会失序并延迟」；「向地图上的长官发三个连级命令与发三个营级命令负担相同」。[[Steam](https://steamcommunity.com/app/521800/discussions/0/1318836262663681605/)] **[Practitioner，但是设计机制]** ⇒ 把许多小单位直接挂在高级 HQ 有代价。
6. **链式解聚 / 抖动 / 转换延迟 / 网络洪泛 / 空间「跳变」**：多分辨率仿真的经典失效（解聚使实体数暴增；抖动=频繁聚合/解聚反拖性能；映射不一致导致 HRE「**不可能地四处跳**」）；根因是重聚合时 DE 实际位置丢失。[[Natrajan et al., ACM TOMACS 1997](https://www.anandnatrajan.com/papers/TOMACS97.pdf)、[DMSO guidelines](https://www.anandnatrajan.com/papers/DMSO,96-97.pdf)、[WSC'09](https://www.informs-sim.org/wsc09papers/127.pdf)] **[同行评审]** ⇒ 这是「父动/重附着时单位瞬移」的正式描述；**「子单位相对父」是不变量**。
7. **ORBAT 跨源合并有歧义**：C2SIM/MSDL「多方 MSDL 文件必须合并……以前靠手工」，且「许多协作合并规则**仍待识别、建模、实现**」。[[GMU LS-141](https://c4i.gmu.edu/c4ifiles/LS-141/papers/LS-141%20C2SIM%2007%20Client%20(Wittman).pdf)] **[标准工作组论文]** ⇒ 若两条命令都能写层级，需要**确定性冲突规则**（你的 revision 排序已给，别绕过）。
8. **脱离条令的命名/结构会让用户困惑**：OneSAF 用户研究「v2 中创建 task organization 可能**复杂且令人困惑，因为单位命名不遵循陆军条令**」。[[DTIC ADA510823](https://apps.dtic.mil/sti/tr/pdf/ADA510823.pdf)] **[官方用户评估]** ⇒ 用条令名（echelon/attach/detach/OPCON/TACON/supported）。
9. **隐式/歧义根**：AFSIM「每条 chain 至少一个 commander（哪怕是自身）」；隐式默认 chain「在存在多个具名 chain 时导致**歧义**」；「一个平台可属多条 chain，但**每条至多一次**」。[[command_chains.html](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/command_chains.html)] **[官方]** ⇒ 每棵 task-org 树**恰一个父**（多属只在独立关系层），构造期禁环。
10. **层级无静态校验**：AFSIM「用 ASCII 文件定义 command chain/peer；**输入错误直到应用执行才发现**」。[[worldcomp paper](http://www.worldcomp-proceedings.com/proc/p2015/CSC7058.pdf)] **[学术，AFSIM 作者]** ⇒ 在命令解析期校验 attach/detach（环、存在、同位置）。
11. **父被毁 → 孤儿处理是真实设计选择**：AFSIM `DisbandCommand` 销毁编队但**不销毁成员**；`RemoveSubCommand` **提升**被移除者为顶层。[[disband](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/script/wsfformationdisbandcommand.html)、[remove](https://github.com/silengzi/afsim-source/blob/bd02a095/documentation/html/docs/script/wsfformationremovesubcommand.html)] **[官方]** ⇒ 三选一：级联销毁 / 提升为根 / 标记孤儿；**绝不留下悬空 `parentId`**。
> **注**：**未**找到把「**环**」当作现场问题的条令/仿真来源——AFSIM 是**结构性预防**（单父、只 attach 到父、限定名唯一）而非检测。把「无环」当**必须强制的不变量**，**不是**有出处的坑。

- ⇒ **迁移点**：采用 **AFSIM 拆分**——严格单父树（organic）+ **独立 task-org 覆盖层**记录 `(unit, gainingParent, relationshipType, reason, startRevision, endRevision?)`；关系枚举 `ORGANIC|ASSIGNED|ATTACHED|OPCON|TACON|SUPPORTED|SUPPORTING`；回归用 **相对父的 rejoin track**（非冻结 hex 序列）+ 可选 `marchToLinkup` 粗阶段；**attach 前置 = 同位置**（引擎强制）；显式 detach 级联策略与孤儿策略。

### B.8 决策周期 / 命令延迟 / 延期与条件效果 / 情报可见性 / LLM 兵棋

**命名地图（brainstorm 术语 → 标准术语）**——这是本节最承重的部分：

| brainstorm 术语 | 标准术语 | 出处 | 注 |
|---|---|---|---|
| **Directive**（NL 意图 + 结构化命令） | **mission order / mission-type order**；NL 部分 = **commander's intent**；结构化部分 = **task**（who/what/when/where/why） | ADP 6-0；FM 5-0 §1-64 **[Doctrine]** | mission order 是**技法**不是文档类型，恰对应「NL + 结构化」 |
| **判决**（NL → 数据，第二三段） | **adjudication** | USNWC *War Gamers' Handbook*；UK/NATO 兵棋手册 **[Doctrine]** | 角色叫 **adjudicator / umpire / White Cell / Control** |
| **效果与文本解耦、可延期** | **effect**（direct/indirect/cumulative/cascading）；延期 = **on-call target / be-prepared task / branch / sequel / trigger** | JP 3-60；ADP 6-0；FM 3-90.2 **[Doctrine]**；RAND MR1477 **[Academic]** | EBO 术语需谨慎（见下） |
| 「现在决定、条件 Y 满足后 X 发生」 | **trigger + decision point**；编码为 **event-condition-action (ECA) / if-then 规则** | DST/DSM（ATP 2-01.3、FM 5-0）；FOI EBP 仿真 **[Academic]** | 条令原型：*「当先头队越过 Phase Line Dog，即在网格…施放遮蔽烟」* |
| 给别的地址改 INFO/叙事 | **inject（注入）**；叙事控制 = **information operation / influence activity** | NATO Wargaming Handbook 2023 **[Doctrine]** | 注入是 White Cell 的工具 |
| 每个决策人每 tick 一个 Directive | **decision-cycle / battle-rhythm** 周期上限 | FM 6-0 §1-43；JP 3-60 **[Doctrine]** | ⚠️ **这不等于 order delay**（见下） |
| 决策节奏 | **tempo / command tempo / OODA cycle rate** | FM 6-0 App. A；MCDP 6 **[Doctrine]** | 已是标准词 |
| per-role 可见性过滤 | **fog of war** + **perceived state vs ground truth**；角色范围 = **need-to-know**；产品 = **COP** | ATP 2-01.3；JP 2-01.3；CRS R41848 **[Doctrine/academic]** | 实现见 B.6 |
| 第二三段 agent（人或 LLM） | **adjudicator / White Cell / Control cell**；LLM 变体 = AI adjudicator | USNWC、USAWC、NATO 手册；Snow Globe、CGSC **[Doctrine/Academic]** | 见下 6.2 |

**OODA 及其嵌套环警告**（FM 6-0 App. A、MCDP 6 ch.2）**[Doctrine]**：★ FM 6-0 §A-7 原文最要紧：
> 「OODA 循环是**为解释战斗机空战**而提出的，不是地面作战。飞行员决定行动时直接操纵飞机；相比之下，地面部队指挥官**不直接发起行动**，而是向下级指挥官下达指令，**每个下级各自执行 OODA 循环**。地面作战中，**每一级指挥官都必须先执行完 OODA 循环，整个部队才会对命令作出反应**。」
[[FM 6-0 App. A](https://www.globalsecurity.org/military/library/policy/army/fm/6-0/appa.htm)、[MCDP 6 ch.2](https://irp.fas.org/doddir/usmc/mcdp6/ch2.htm)] ⇒ **per-echelon 决策速率的条令依据**（Nation vs formation 是两级版本）。

**mission command / Auftragstaktik**：ADP 6-0 使命式命令说明**做什么与为什么、不说不怎么做**；下属「被**要求**（不只是被允许）在现行命令失效时行使有纪律的主动性」。[[ADP 6-0](https://irp.fas.org/doddir/army/adp6_0.pdf)] ⚠️ **历史性警告**：ADP 6-0 称 mission command「源于 Auftragstaktik」被 Army University Press 质疑——德文更准确是 *Führen mit Auftrag*。[[Herrera](https://www.armyupress.army.mil/Portals/7/military-review/Archives/English/JA-22/Herrera/Herrera-UA2.pdf)] **[Academic]**

**tempo / battle rhythm / MDMP**：tempo 是**相对的**（「不是绝对速度，而是相对敌人的速度」）；ADP 6-0 §1-17「**越高梯队，越应把时间花在未来作战**、给下级的指示越宽」；JP 3-60 给规划视野（当前<24h、未来 24–96h、未来计划 96h–6月），全由 **battle rhythm** 与 **JFC 决策循环**治理；FM 6-0 §1-43 定义 battle rhythm；MDMP 7 步是参谋版。[[FM 6-0](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN35404-FM_6-0-000-WEB-1.pdf)、[JP 3-60](https://www.esd.whs.mil/Portals/54/Documents/FOID/Reading%20Room/Joint_Staff/21-F-0520_JP_3-60_9-28-2018.pdf)、[MDMP 23-07-594](https://api.army.mil/e2/c/downloads/2023/11/17/f7177a3c/23-07-594-military-decision-making-process-nov-23-public.pdf)] **[Doctrine]**
- **仿真怎么建模 per-echelon 速率**：**Kalloniatis et al. (DSTO)**「Process versus Battle-Rhythm」把 HQ 工作建模为**竞争循环**（规律 battle rhythm vs 事件触发的临时规划），**workload backlog** 是失效模式——最直接可复用的「高 HQ 更慢/饱和」模型[[mssanz](https://www.mssanz.org.au/modsim09/E1/kalloniatis.pdf)] **[Academic]**；NPS「A Model of Tactical Battle Rhythm」把 TBR 当**载波频率**[[Calhoun](https://calhoun.nps.edu/bitstream/10945/36390/1/Duffy_Bordetsky_A_Model_of_Tactical_2004.pdf)]；US Army 24-852 (2024)「Staff Processes in LSCO」主张刚性 battle rhythm 在 LSCO 下失效、规划视野须随战况**压缩/扩展**（「一个 MDMP 循环可能是 24h、96h 或 2h」）[[api.army.mil](https://api.army.mil/e2/c/downloads/2024/06/07/b62f30eb/24-852-staff-processes-in-large-scale-combat-operations-part-1-rhythm-of-the-battle.pdf)]。

**★ Order delay（「每 tick 一条命令」上限**没有**捕捉到的东西）**：
- **定义**：从指挥官**决定**到下属部队**实际执行**的时间——拆为 **下达 → 沿链传递 → 下级规划 → 下级准备/编组 → 开始执行**。它**不是**决策速率上限。Command Ops 论坛原文：「orders delay 计入**下达命令、传达给受影响单位、以及组织这些单位去完成**的时间……」[[Steam](https://steamcommunity.com/app/521800/discussions/0/1499000547494820574/)] **[Practitioner]**
- **Command Ops 2 / Airborne Assault** 是参考实现，**orders delay 是其四大设计支柱之一**（官方 [Panther Games](http://www.panthergames.com/2013/12/command-ops.html)）**[Sim-doc]**。**量级（玩家测得，视为近似）**：连级命令约 **30 游戏分钟**（「painfully realistic」档）；营/团更大；顶层 HQ「**数小时**组织与出发」；场景可设 delay factor，攻方初始可为 0、守方可放大[[Steam](https://steamcommunity.com/app/521800/discussions/0/1488866813763205404/)] **[Practitioner]**。**依赖**：距离、地形、参谋效率[[Armchair General](https://armchairgeneral.com/command-ops-battles-from-the-bulge-pc-game-review.htm)]、**HQ 待办积压**（「超过某阈值后执行率骤降」）[[kriegsimulation](https://kriegsimulation.blogspot.com/2013/06/command-ops-battles-for-greece-review.html)]、单位状态（疲惫/溃退/恢复）、任务变化幅度（「所有大于阈值的任务变更：编成、路线、路点变更与重规划」）[[Matrix 补丁说明](https://forums.matrixgames.com/viewtopic.php?p=2789805)]。
- **两个须照抄的结构性质**：① **delay 也作用于预先计划好的命令**（玩家须把 StartAt/H-hour 设早以覆盖 delay——曾引起大量困惑，是个 UI 语义警告）；② **每个单位只有一条 live order**——新命令**取代**旧命令，单位可能**停在旧计划执行中途**（产生命令摩擦：「一些单位还在执行旧命令、一些在处理新命令，一切都会乱」）。
- **其他仿真**：**General Staff: Black Powder** delay = **信使行程时间**（≈10.5 km/h 骑兵）+ **Leadership Value 惩罚**，UI 禁止设早于信使到达的执行时刻[[the-friction-of-war](https://www.general-staff.com/the-friction-of-war/)]；**Envoy**（开源）「为下级拟令后沿指挥链**带真实（可定制）延迟**传递」[[GitHub](https://github.com/musurca/envoy)]。
- **学术 C2 延迟建模（正式语汇）**：**Nam & Lee 2015** 把延迟拆成**人工处理延迟**（指挥官 = 无限缓冲的单服务器，处理时间随 workload 变）与**通信传播延迟**[[doi:10.5555/2888619.2889043](https://doi.org/10.5555/2888619.2889043)] **[Academic]**；**JASSS 18(4)** 显式参数 **DTCO**（指挥决策时间）/ **OTCO**（命令时间）/ **DTPL** / **ORTPL**，并测其对 **LER** 的影响[[jasss](https://jasss.soc.surrey.ac.uk/18/4/10.html)]；**MIT 1984**「系统响应时间=各级延迟之和」+ **window of opportunity**[[hdl](http://hdl.handle.net/1721.1/2878)]；**Muirragui** 指多数 C2 仿真完全省略通信[[DTIC ADA239302](https://apps.dtic.mil/sti/tr/pdf/ADA239302.pdf)]；**Petri 网 C2 建模**（Tabacchi, NPS 1990）[[hdl](https://hdl.handle.net/10945/34943)]。
- **Clausewitz / friction**：*On War* Bk.1 Ch.7 是「**摩擦**」之源；General Staff 把其操作化为命令传输延迟 + 领导力惩罚[[the-friction-of-war](https://www.general-staff.com/the-friction-of-war/)]；ADP 6-0 §1-17 把摩擦连到不确定性与 mission command。⇒ ★ **friction 是累积退化**（延迟、误传、单位按旧命令行事、疲惫），order delay 只是其一个可测分量——**两者应分开建通道**。

**延期 / 条件效果的标准语汇**：
- **文档类型**：**OPORD**（五段式基础命令）、**FRAGORD**（改令/修令/执行 **branch**/**sequel**，重复五个段落标题、未变处写 "No change"）[[Army task spec](https://rdl.train.army.mil/catalog-ws/view/100.ATSC/497D69BD-075B-4144-9A87-3A9F8FE4BCDC-1661890760624/report.pdf)]、**mission-type order**[[ADP 6-0](https://irp.fas.org/doddir/army/adp6_0.pdf)]、**warning order**。**[Doctrine]**
- **branch / sequel**：**branch** = 「基础计划内建的应变选项……用于改变任务、方向或运动方向……基于预想的事件、机会或敌方行动造成的扰乱」；**sequel** = 「基于当前大行动可能结果（胜/负/平/决定性胜）的后续大行动或阶段」[[Army task spec](https://rdl.train.army.mil/catalog-ws/view/100.ATSC/497D69BD-075B-4144-9A87-3A9F8FE4BCDC-1661890760624/report.pdf)；[Read, *Planning for the Unplannable*, DTIC ADA234501](https://apps.dtic.mil/sti/tr/pdf/ADA234501.pdf)] **[Doctrine/Academic]**
- **trigger / phase line / on-call**：FM 3-90.2 App. G 给经典编码：*「当先头队越过 Phase Line Dog，遮蔽烟将在网格 NK 124757 施放。触发条件在 COA 兵棋推演与合成兵种演练中进一步细化。」* [[FM 3-90.2 App. G](https://www.globalsecurity.org/military/library/policy/army/fm/3-90-2/appg.htm)]；**FSEM** 列 = TGT# / 目的 / 位置 / 观察者 / **触发** / 投送手段 / 效果；**on-call vs scheduled**[[JP 3-60](https://www.esd.whs.mil/Portals/54/Documents/FOID/Reading%20Room/Joint_Staff/21-F-0520_JP_3-60_9-28-2018.pdf)]。**[Doctrine]**
- **DST / DSM**：**Decision Support Template/Matrix** 在 COA 推演中产出，描述 **NAI / TAI / Decision Points / TPL**，矩阵列出「每个决策点、该处发生的事件、敌我行动、以及喂给该决策点的 NAI」[[USMC MCPP 模块](https://www.usmcu.edu/Portals/218/MCPP/8908%20IMI%20Module%202/8908%20IMI%20Module%202/16/index.html)]；现代文章提议给 DSM 加 **FFIR 列**（触发/阈值/报告者/时限/决策权限/branch 计划）——**是「判决」数据模型的极佳模板**[[Army](https://home.army.mil/wood/contact/publications/ppb/Friendly-Force-Information-Requirements-as-Protection-Informed-Decisions)]。**[Doctrine/analysis]**
- **be-prepared vs on-order**：FM 5-0 使命陈述要求「when——按时间或按事件」；实务区分（**非正式**）：**"on order"** = 任务已交给你、等触发；**"be prepared to"** = 做计划、可能不发生、不排除其他任务[[Reddit r/army](https://www.reddit.com/r/army/comments/jsxl53/)] **[Practitioner]**。★ 正是「延期效果」需要的区分：**committed-but-triggered** vs **planned-only**。
- **效果语汇（含警告）**：AFRL EBO 的效果分类 **direct/indirect/cumulative/collateral/cascading**[[dodccrp](https://www.dodccrp.org/events/2004_CCRTS/CD/papers/052.pdf)]；RAND MR1477 (Paul K. Davis)[[RAND](https://www.rand.org/content/dam/rand/pubs/monograph_reports/2006/MR1477.pdf)]；Smith *Effects Based Operations*[[DTIC ADA457292](https://apps.dtic.mil/sti/tr/pdf/ADA457292.pdf)]。⚠️ **EBO/EBAO 约 2008 年被美国空军正式放弃、学术上有争议**——**用其词汇（effect/trigger/causal linkage/indicator），不要声称以 EBO 为框架**。**[Academic]**
- **仿真怎么编码条件**：**FOI 随机离散事件 EBP 仿真**——动作是 **if-then 规则**（condition + effect 列表），每个动作节点存状态快照以便回溯计划[[FOI-S--4487](https://foi.se/download/18.7fd35d7f166c56ebe0bffd4/1542623691375/A-stochastic-discrete-event_FOI-S--4487--SE.pdf)]；Schubert et al. *OR Perspectives* 2 (2015) 同型 + 全局规则 + Monte Carlo[[FOI-S--5026](https://www.foi.se/download/18.7fd35d7f166c56ebe0b1006f/1542623792337/Simulation-based-decision_FOI-S--5026--SE.pdf)]；职业兵棋的 **inject** 是 White Cell 预先写好的/自适应的触发事件[[NATO Wargaming Handbook 2023](https://paxsims.wordpress.com/wp-content/uploads/2023/09/nato-wargaming-handbook-202309.pdf)]。**[Academic/Doctrine]**
- ⇒ ★ **迁移点**：把「延期效果」建成 **ECA 规则 / trigger→effect 对**，子类用条令名 *scheduled（时间）/ on-call（事件）/ be-prepared（仅计划）/ branch（应变 COA）/ sequel（结果后续）*；判决行用 **DSM+FFIR 列** 的字段（trigger / threshold / reporter / timeline / decision authority / branch plan）。

**★ LLM 兵棋的已知失败模式与「给 agent 的信息应如何限定」**：
- **Snow Globe**（IQT Labs, [arXiv 2404.11446](https://arxiv.org/html/2404.11446)）**[Academic]**：control/player/team 三类 agent；**判决是最难的任务**，低质判决表现为**重复**与**偏离玩家既定计划（幻觉出新的玩家计划）**；生成「nature」的技巧是附一句 *"Include unexpected consequences."*；作者主张**幻觉正是开放式兵棋的关键**。
- **行为证据**：**Escalation Risks from Language Models**（[arXiv 2401.03408](https://arxiv.org/abs/2401.03408) / FAccT 2024）：8 个 LLM 国家 agent **全部升级**、军备竞赛、偶发核使用、难预测尖峰，**中立情景也升级**；**Human vs Machine**（[arXiv 2403.03407](https://arxiv.org/html/2403.03407v2) / AIES 2024）：107–214 名国安专家 vs GPT-3.5/4，**LLM 更具攻击性、对指令高度敏感、对 persona 不敏感**（连「和平主义者」/「攻击性反社会者」都无差），对话「质量差且维持着一种滑稽的和谐」；**Position: AI Is Not Ready for Strategic Conflicts**（[arXiv 2609.16189](https://arxiv.org/abs/2609.16189)）点名**五种失败模式**——*decision laundering / adjudication opacity / role collapse / escalation-through-adjudication / failure of strategic imagination*，主张无**可审计的安全论证**不得影响政策。**[Academic]**
- **给 agent 限定的既有做法**：**action masking**（把 LLM 动作空间限制为有效/条令选项）[[NATO STO MP-SAS-192-14](https://publications.sto.nato.int/publications/STO%20Meeting%20Proceedings/STO-MP-SAS-192/MP-SAS-192-14.pdf)]；**结构化输入/输出**（严格格式化的同步矩阵 + 预校验提示 + **JSON-schema 校验输出**）[[CGSC/SWJ](https://smallwarsjournal.com/2026/01/16/ai-enabled-wargaming-cgsc/)]；**网格参照**（LLM 空间推理弱，CGSC 加字母数字网格叠加）；**OAG + 条令 RAG + "open then closed" 提示**（优先课程/场景材料、少联网）[[USAWC War Room](https://warroom.armywarcollege.edu/articles/back-to-the-basics/)]；**per-persona 上下文窗 / per-actor 状态库 / redaction / 裁判中介消息传递**（四分离模式）；**RAND 告诫**：AI 作「adversary-in-a-box」有风险，只在你**能用其它工具交叉核对**处使用[[RAND](https://www.rand.org/pubs/commentary/2025/04/should-i-use-ai-in-my-wargame.html)]。
- ★ **LLM 兵棋特有的信息泄漏**：2025 年一份 RAND 仿真据报因**裁判叙述**泄漏意图——把 Red 的行动描述为 *"unexpected troop movements"* 等于告诉 Blue「Red 的举动令人意外」；修法是只报**可观察项**（*"increased activity at grid reference X"*）。**次源（播客，视为未核实）**[[doi:10.5281/zenodo.19489548](https://doi.org/10.5281/zenodo.19489548)] **[Practitioner/secondary]**。四分离模式 = ① per-actor 状态库（全局真值在主库、agent 不查）② redaction 层（按能力裁剪）③ 裁判中介（agent 不直接对话）④ per-persona 上下文窗。另有更微妙的泄漏：**否定式报告也泄漏**（「X 扇区未探测到潜艇威胁」暴露了 Blue 知道要去看那里）。
- ★ **prompt injection 是一等威胁**（因为 Directive 文本喂 LLM、LLM 可能读场景/工具输出）：**"Agents Under Siege"**（ACL 2025）优化后的提示在多 agent 拓扑上传播，绕过 Llama-Guard/PromptGuard，ASR 高达 94%[[ACL](https://aclanthology.org/2025.acl-long.476.pdf)]；**"Multi-Agent Systems Execute Arbitrary Malicious Code"**（[arXiv 2503.12188](https://arxiv.org/html/2503.12188v2)）；**"Adaptive Attacks Break Defenses Against Indirect Prompt Injection"**（[arXiv 2503.00061](https://arxiv.org/html/2503.00061)）绕过全部 8 种防御；**ToolHazard**（[arXiv 2608.11878](https://www.alphaxiv.org/abs/2608.11878)）——**结构化工具输出（JSON/YAML）提供「语义隔离」**、降低攻击面，轨迹早段的注入更有效，缓解把 ASR 从 ~40% 降到 <5% 但**不为零**。
- **机构项目（前史）**：**DARPA HR0011SB20254-07 "Improving Battle Planning through AI"**——用 **reduced-order models** 做 COA 判决，比实时快很多倍，对可信物理仿真验证[[DARPA FAQ](https://www.darpa.mil/sites/default/files/attachment/2025-05/faq-hr0011sb20254-07-1.pdf)] **[Government]**；**JH APL GenWar Lab**（2026 开放）——GenWar TTX + GenWar Sim（AFSIM 物理判决 + NL 命令转译）[[Army Times](https://www.armytimes.com/news/your-military/2025/11/24/new-lab-offers-generative-ai-for-defense-wargaming/)] **[News]**；**CGSC Vantage/OAG+RAG**（2025-11）——128k 上下文、导弹 Pk 表、人工覆盖协议、**"no-move-unless-ordered"**；发现**过细的提示偏向友方结果**、简化意图导向的提示判决更现实[[SWJ](https://smallwarsjournal.com/2026/01/16/ai-enabled-wargaming-cgsc/)]；**USAWC Pacific Strategy**——free-kriegsspiel + LLM 判决，"open then closed"，幻觉需教员编辑[[War Room](https://warroom.armywarcollege.edu/articles/back-to-the-basics/)]；**StraitOfConsequences**（GitHub）——结构典型的开源例子，含显式 **White-Cell Adjudicator** 端点[[GitHub](https://github.com/ColinM-sys/StraitOfConsequences)]。

**White cell / 判决实践**：
- **判决方法分类**（USNWC/USAWC/NATO/UK MOD 手册）**[Doctrine]**：**free**（裁判专业判断）、**rigid**（预定规则/表格）、**semi-rigid**（规则 + 裁判可覆盖）、**minimal/consensual**（玩家协商，matrix games）；另有 **open**（玩家在场）vs **closed**（判决组私下决定）。[[USNWC War Gamers' Handbook](https://www.govinfo.gov/content/pkg/GOVPUB-D208_200-PURL-gpo122363/pdf/GOVPUB-D208_200-PURL-gpo122363.pdf)、[USAWC](https://opanalytics.ca/aus/pdf/USAWC%20Wargame%20Handbook%201%20July%2015.pdf)、[NATO](https://paxsims.wordpress.com/wp-content/uploads/2023/09/nato-wargaming-handbook-202309.pdf)、[UK MOD](https://assets.publishing.service.gov.uk/media/5a82e90d40f0b6230269d575/doctrine_uk_wargaming_handbook.pdf)、[Banks, ISR 2023](https://doi.org/10.1093/isr/viae002)]
- **角色**：**White Cell / Control**（创建并管理环境与输入、拥有 injects、判决）、**Game controller (GameCon)**（UK，例行决定终裁）、**Adjudicator/umpire**、observer/controllers、EXCON[[Army 20-06](https://api.army.mil/e2/c/downloads/2023/01/31/bf65892d/20-06-how-to-master-wargaming-public.pdf)]。
- **White Cell 需要的数据**（照抄清单）**[Doctrine]**：**adjudication plan**（游戏设计期写）、**move templates**、**player data requirements**、**adjudication matrix**、**operational analysis**（best/worst/most-likely 结局分布）；决策点字段：「定义触发与条件（事件、时间）；定义信息需求；定义谁跟踪；定义谁决策；跨作战职能同步」[[Army 20-06](https://api.army.mil/e2/c/downloads/2023/01/31/bf65892d/20-06-how-to-master-wargaming-public.pdf)]。
- ★ **条令直接陈述 order delay**（在兵棋语境，印证上文的 order delay）：Army 20-06——
> 「……角色扮演者**不会立即执行命令**，因为**消息传递有延迟**，随后其指挥的编队**响应命令又有延迟**。」
[[Army 20-06](https://api.army.mil/e2/c/downloads/2023/01/31/bf65892d/20-06-how-to-master-wargaming-public.pdf)] **[Doctrine]**
- ⇒ **迁移点**：把「判决」当**adjudication**，方法是 free/rigid/semi-rigid 的**显式选择**；LLM 判决 = **White Cell 工具、须人工覆盖、必须暴露推理与输入**（DSM 行的 trigger/threshold/reporter/authority/branch）以免 *adjudication opacity / decision laundering*；给 agent 的输入用**结构化 + action allowlist + RAG + 网格参照 + no-move-unless-ordered**，并假定**任何 NL 通道都可能 prompt injection**。

### B.9 条令 / 标准清单

| 标准 | 相关性 | 可靠性 / URL |
|---|---|---|
| **JP 5-0, *Joint Planning*** | 分阶段：条件驱动、起止条件、事件驱动转换、六阶段模型 | **[Doctrine]** [2020 PDF](https://www.esd.whs.mil/Portals/54/Documents/FOID/Reading%20Room/Joint_Staff/18-F-1152_JP_5-0_Joint_Planning_2020.pdf) |
| **FM 3-0, *Operations*** | 阶段定义；decisive/shaping/sustaining；App. B 附着语义 | **[Doctrine]** [ch.6](https://www.globalsecurity.org/military/library/policy/army/fm/3-0/ch6.htm)、[2022 PDF](https://soldat-und-technik.de/wp-content/uploads/2022/10/ARN36290-FM_3-0.pdf) |
| **ADP/ADRP/FM 3-90, FM 3-90-1/2** | engagement / meeting engagement / EA / engagement criteria / trigger lines / deliberate attack | **[Doctrine]** [ADP 3-90](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN34828-ADP_3-90-000-WEB-1.pdf)、[ADRP 3-90](https://www.globalsecurity.org/military/library/policy/army/adrp/3-90/adrp3_90.pdf) |
| **ADP 1-02 / FM 1-02 / FM 101-5-1** | attach/assign/cross-attachment/task-organizing 定义 | **[Doctrine]** [ADRP 1-02](https://www.bits.de/NRANEU/others/amd-us-archive/adrp1_02%282-15%29.pdf)、[FM 101-5-1 text](https://archive.org/download/operationalterms00unse/operationalterms00unse_djvu.txt) |
| **FM 6-0, *Commander and Staff Org and Ops*** | command estimate、running estimates、battle rhythm、OODA App.A、AAR | **[Doctrine]** [App.A](https://www.globalsecurity.org/military/library/policy/army/fm/6-0/appa.htm)、[Army pubs](https://armypubs.army.mil/epubs/DR_pubs/DR_a/ARN35404-FM_6-0-000-WEB-1.pdf) |
| **ADP 6-0 / ADRP 6-0 / MCDP 6** | mission command；OODA；tempo | **[Doctrine]** [ADP 6-0](https://irp.fas.org/doddir/army/adp6_0.pdf)、[ADRP 6-0](https://home.army.mil/wood/application/files/7715/5751/8336/ADRP_6_0_Mission_Command.pdf)、[MCDP 6 ch.2](https://irp.fas.org/doddir/usmc/mcdp6/ch2.htm) |
| **FM 5-0, *Planning*** | wargaming as COA analysis；DST/DSM/FSEM/SYNCMAT | **[Doctrine]** 见 [MDMP 23-07-594](https://api.army.mil/e2/c/downloads/2023/11/17/f7177a3c/23-07-594-military-decision-making-process-nov-23-public.pdf) |
| **JP 3-60** | 目标：scheduled/on-call、trigger、规划视野 | **[Doctrine]** [PDF](https://www.esd.whs.mil/Portals/54/Documents/FOID/Reading%20Room/Joint_Staff/21-F-0520_JP_3-60_9-28-2018.pdf) |
| **ATP 2-01.3 / JP 2-01.3 (IPB/JIPOE)** | ground truth vs 感知；情报产品 | **[Doctrine]** [ATP](https://home.army.mil/wood/application/files/8915/5751/8365/ATP_2-01.3_Intelligence_Preparation_of_the_Battlefield.pdf)、[JP](https://irp.fas.org/doddir/dod/jp2-01-3.pdf) |
| **MCDP 1 / MCDP 1-3 / MCWP 3-01** |  attrition-vs-maneuver；主攻方向；交战 vs 战斗 | **[Doctrine]** [MCDP 1](https://www.marines.mil/Portals/1/Publications/MCDP%201%20Warfighting.pdf)、[MCDP 1-3](https://www.marines.mil/Portals/1/Publications/MCDP%201-3%20Tactics.pdf)、[MCWP 3-01](https://www.marines.mil/Portals/1/Publications/MCWP%203-01.pdf) |
| **TRADOC Reg 10-5-7 / Army 20-06** | wargaming/simulation 主管、V&V、OneSAF；兵棋实务 | **[Doctrine]** [TR 10-5-7](https://docslib.org/doc/13685638/department-of-the-army-tradoc-regulation-10-5-7)、[Army 20-06](https://api.army.mil/e2/c/downloads/2023/01/31/bf65892d/20-06-how-to-master-wargaming-public.pdf) |
| **HLA (IEEE 1516) / DIS (IEEE 1278)** | ★ **只解决互操作与时间管理，与「判决数学」无关** | **[standard]** 见 B.9 下注 |

★ **HLA/DIS 的诚实结论：对交战判决几乎无关。** 它们定义**联邦成员如何交换状态与交互、如何管理时间**，对赔率表、损耗数学、结局选择**只字未提**。DLRC 例子里 **Eagle** 做战斗数学，用 HLA 喂 C4I、ModSAF 与 **AAR**（AAR 需要 ground truth）；DIS 按心跳发全实体状态 PDU，HLA 只发变化属性。⇒ **若将来要把判决引擎联邦化、或向其它仿真流状态、或做 AAR/真值管道，HLA/DIS 相关；但不要指望它给判决语义。**[[MITRE/CCRP HLA 论文](http://www.dodccrp.org/events/6th_ICCRTS/Tracks/Papers/Track3/114_tr3.pdf)、[UCF DIS-to-HLA](https://stars.library.ucf.edu/cgi/viewcontent.cgi?article=1058&context=istlibrary)] **[Mil-acad]**

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
- ★ **§B 折入**（B.8）：标准术语把该机制叫 **trigger → effect 的 ECA（event-condition-action）规则**，子类用条令名 *scheduled（时间）/ on-call（事件）/ be-prepared（仅计划）/ branch（应变 COA）/ sequel（结果后续）*。⇒ R6 的「条件」应数据化为**触发器**（不是硬编码 Java 分支），且须区分 **committed-but-triggered**（on order）与 **planned-only**（be prepared）。

### D-R9 判决系统在 simos 里无对应步骤

- **事实**（§C③）：六步里没有「判决」这一步；`③ Resolve` 是地址集合相交，`④ Validate` 是机械校验。brainstorm §4 的「判决系统」= 汇总一个 tick 的各 Directive、产出第二三段（判决 + 效果）。
- **影响**：待裁决收窄为「**判决在哪里执行**」：
  - (a) 在 **`sd` 的 TimeParticipant（②）** 内做（引擎内、可重放、确定论），但受 §C③ 的「只能写 sd」约束；
  - (b) 在 **SDSimos 的命令路径（信封支）** 内做（把判决做成一条 `sd.*` 命令的 handler），走 `Command → ChangeSet → Revision`；
  - (c) 在**引擎外的 GUI / Agent** 做（用户智力或 LLM），只把结果写成命令——**可读性优化**归此处，但需裁决「判决本身算不算可重放的状态」。
- 决策的领域对象是 `Directive`（R3），**不与信封 `Command` 撞名**——这点不变。
- ★ **§B 折入**（B.8）：标准名是 **adjudication**，角色是 **White Cell / adjudicator**；方法须显式选 **free / rigid / semi-rigid / minimal**（USNWC/NATO 手册）。判决所需数据可照抄 **adjudication matrix + DSM/FFIR 列**（trigger / threshold / reporter / timeline / decision authority / branch plan）。★ **若判决由 LLM 做**：它只是 **White Cell 的工具**、必须人工覆盖、必须暴露推理与输入，否则继承 *adjudication opacity / decision laundering*（arXiv 2609.16189）；给 agent 的输入须 **结构化 + action allowlist + 条令 RAG + 网格参照 + no-move-unless-ordered**，并假定**任何 NL 通道都可能 prompt injection**（B.8）。
- ★ **§B 折入**（B.8）：★ **「每 tick 一条 Directive」是 decision-cycle 上限，不等于 order delay**。兵棋实践把三个时钟分开：**决策周期**（多久能决策一次）/ **命令延迟 order delay**（决定→执行开始，含下达+传输+规划+准备，约 30 分钟~数小时，随梯队/距离/参谋负载缩放）/ **执行时长**。⇒ 若 R9 想要 Command Ops 式的节奏/欠跑效果，须**新增独立的 order-latency 通道**，并考虑 **HQ 积压/饱和**（Kalloniatis 竞争循环）。

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
- ★ **§B 折入**（B.6）：INFO 的**读侧**已有明确范式——**ground truth 与 per-actor 感知态分离**（SSIM CODE 的「reports 节点 vs decision-factor 节点」），AAR/回放读 ground truth、UI 读 perception；「改别的地址的 INFO」在职业兵棋里对应 **inject**（White Cell 工具）。⇒ 若 R14 选「新造写路径」，**写的是真值还是感知**必须一起裁决。

### D-N1 阶段对象的字段（§B.4）

- **事实**：JP 5-0 把阶段定义为**条件驱动**，每阶段有 **starting/ending conditions**，且**上一阶段的结束条件 = 下一阶段的起始条件**，转换为**事件驱动**，阶段可**压缩/扩展/省略**；FATHM 另加 **阈值击杀 + min/max 时长**。
- **新增待裁决**：SDSimos 的「交战阶段」是否落成数据对象 `{id, name, participants, entryConditions, exitConditions, minDuration, maxDuration}`？⇒ 建议**是**，并采纳 JP 5-0 的**链式条件**。

### D-N2 结局表用 categorical distribution（§B.1）

- **事实**：「多结局 + 概率、选其一」的规范名 = **categorical distribution / multinoulli**；兵棋 = **CRT 列**；游戏工程 = **weighted outcome table**。
- **新增待裁决**：结局表的数据形状 = `[(outcome, weight)]`（归一化即概率），**恰好选一个**；是否允许**阶段级**独立结局表（brainstorm 已要）+ **交战级**汇总？具名结局词表用什么（自定义 vs CRT 的 Ae/Ar/Ex/De/Rt）？

### D-N3 战损：双轨 + 永久/可回收 + 保留记录 + delta 语义（§B.5）

- **事实**：WITE2 按 **element** 双轨（人员 vs 装备）并区分 destroyed/damaged/**disrupted**、**disabled 可 1%/tick 回池**；WEGO 把 **Strength（永久）vs Readiness（可恢复）** 分开、损失先取 Readiness；Flashpoint 用 **Butcher's Bill**（Started/Active/Withdrawn/Fallen Out/Destroyed + 恢复时间）+ **film file** 留档；DLRC/Eagle 保留 ground truth 供 AAR。**deltas 记录事件、replacement 另行恢复**——正合铁律 2/5。
- **新增待裁决**：SDSimos 的战损是否 = **人员 + 装备双轨**、**永久 vs 可回收**、**保留损失记录**、**以 delta（非覆盖）写入**？⇒ 直接回应 §A.1 缺口③（现有 `SetStrength` 是整份替换）。

### D-N4 ORBAT：树约定 vs 显式子表（§B.7）

- **事实**：AFSIM（最接近的实现）把 **command_chain（谁向谁报告，可多属）** 与 **WsfFormation（谁物理跟谁移动，严格树 + attached/detached 标志 + 相对偏移）** 分开；Command Ops/OneSAF 用**独立 task-org 覆盖层**；纯 `parent` 指针**无法表达**「为某任务附着、然后回归」或「cross-attach」。回归在条令里叫 **linkup**（点 = linkup point），**"return path" 不是条令词**。
- **新增待裁决**：
  1. **编制 = 严格单父树 + 独立 task-org 覆盖层**（记录 relationshipType/reason/startRevision/endRevision），还是只靠树？⇒ 建议前者；
  2. **关系枚举**取 `ORGANIC|ASSIGNED|ATTACHED|OPCON|TACON|SUPPORTED|SUPPORTING`？
  3. **detach 级联策略**（AFSIM：attach 级联、detach 只节点）——SDSimos 选哪个？
  4. **子单位位置存相对父的偏移**（防父动瞬移）？
  5. **孤儿策略**（父亡：级联销毁 / 提升为根 / 标记孤儿）？
  6. **attach 前置 = 同位置**（brainstorm 已要；Command Ops 的「母体追子」bug 与条令 linkup point 都支持）。

### D-N5 新增独立「order latency」时钟（§B.8）

- **事实**：decision-cycle 上限 ≠ order delay；Command Ops 用它做四大支柱之一，量级 30 分钟~数小时、随梯队/距离/参谋负载缩放，且**预先计划好的命令也有延迟**、**每单位只有一条 live order**。
- **新增待裁决**：SDSimos 是否新增 **order-latency 通道**（与 Directive 的 decision-cycle 分开）？是否建 **HQ 积压/饱和**？——若不做，节奏会退化成回合制。

### D-N6 per-role 可见性必须做 redaction（§B.6）

- **事实**：现有工具白名单/`PermissionChecker` **不是数据脱敏**（§A.3）；职业仿真与 LLM 兵棋的做法都是 **ground truth（agent 不查）→ per-actor 感知态（只由探测/消息推出）→ 按角色 redaction → 裁判中介**；LLM 场景还必须防 **prompt injection** 与**否定式报告泄漏**。
- **新增待裁决**：SDSimos 的「决策人能看到什么」是**感知态 + redaction**（而非只裁工具）？`groundTruth` 与 `perception[side]` 是否**并列记录**？⇒ 与 §C①（INFO 写路径）、§C②（无校验 SPI）交织。

---

## §我未能核实的 / 空白

**§A（本仓接口）**
- ~~Unit 探子 transcript 未落盘~~ ⇒ **已解决**：本会话从会话 DB 的 `part` 表取出原文，与 §A.1（控制器直读源码）逐条对拍**结论一致**。原「未落盘」的观察已作废。
- **§A.2 / §A.3 的 `文件:行`**：除标 ★ 的复核项外，均为 transcript 引用（◇），本会话**未逐行复核**其行号；若行号漂移，以源码为准。
- **AgentLib 的内部语义**（`PermissionChecker` 决策顺序、`ResourceScope` 前缀语义等）：取自 transcript 对 `~/ProjectMosire/AgentLibMosire` 源码的实读，本会话**未复核**。
- **「更细粒度 resource scope 是否真能表达 per-hex」**：transcript 称「理论上可表达、零代码如此用」——本会话**未验证**该断言。
- **SDSimos 的模块坐标与装配点**：本文只记录既有装配点（`Shell.java:188-224`、`CoreSimos.register ×3`、core pom、`ArchitectureGuardsTest`），**SDSimos 具体怎么接**属设计，不在本次调查范围。

**§B（外部研究）——原研究自陈的未核实项**
- ★ **`P(t) model` 无规范名**：外部研究**未能**证实一个正式名为「P(t) model」的独立构念；经验证的只有随机对决的胜率/击杀时间分布与标准词 **Pₖ**。**不要**把「P(t) model」当可引用出处。
- **Command Ops 的 order-delay 精确公式未公开**：30 分钟（连级）等量级来自**玩家报告与杂志评测**，非规范；**视为指示性**。
- **RAND 2025「裁判叙述泄漏」**仅见于**播客次源**，未找到对应 RAND 出版物；**未核实**，不要当 RAND 结论引用。
- **若干 2026 arXiv 预印本**（AI Arms and Influence、AI Is Not Ready、ToolHazard）仅在**摘要级**取得，具体数字**未核**。
- **「on order」vs「be prepared to」**的 committed/not-committed 区分是**实务解读**；条令仅正式说使命陈述的「when」= 按时间或按事件。
- **EBO 的地位**：约 2008 年被美国空军正式放弃、学术有争议——**只借词汇，不认框架**。
- **「环」不是有出处的现场坑**：AFSIM 是**结构性预防**（单父/只 attach 到父/限定名唯一）而非检测；「无环」应作**不变量**，不是 finding。
- **CRT / 损耗的数值真值**：除已引真例（Russia Besieged、WITE2 的 1%/tick 等）外，**未**给出更广的数值；把 hobbyist 数值当**机制形状**而非真值。
- **ORBAT 报告自陈**：Arma 3 BI wiki 被 Cloudflare 403（只有社区 SQF 代码级证据）；MSDL/C2SIM 的**具体 schema 字段名**未确认；Steel Beasts 无 task-org 覆盖层（**不要**当 (b) 的模型）；FM 3-0 (2022) B-21/B-22 **取自第三方镜像** `soldat-und-technik.de`（官方 armypubs PDF 未机器提取）；「cycles」非有出处坑。
- **§B 未逐条回源**：三份外部研究由 librarian 产出，**本会话未逐 URL 打开复核**；**可靠性标签来自原研究自评**。

**其余空白**
- **§B 之外**没有其它外部研究（如具体的兵棋 AI 架构文献）——本次三路为限。

---

## 参考原件（未入库，仅路径 / 坐标）

**探索报告（本仓接口，§A 来源）**
- Info/地址/信封/SPI/两阶段：`/home/cna/.local/share/opencode/tool-output/tool_0beb7ec4c001mEy6YJ5YPbYPex`（Task `bg_ecf53159`）
- Agent/权限/AgentLib：`/home/cna/.local/share/opencode/tool-output/tool_0beb9a3460015HtWe0I9H3r2JJ`（Task `bg_f651e4db`）
- Unit 模型：**只在会话 DB**，`part.id = prt_0beb647b3001qwWh1t3ZDWnOpK`（session `ses_f414ac137ffem11MrJBTbSGkAV`）

**外部研究（§B 来源，各带 URL 于正文）**
- ① 交战判决与战损（主料）：`/home/cna/.local/share/opencode/tool-output/tool_0bebb2be9001v1a7ZRhweSeIuN`（Task `bg_65fb21d9`）
- ② 编制与指挥链 / ORBAT / 拆附回归：**只在会话 DB**，`part.id = prt_0beba9e8c001TRRW2QRdbtJkxV`（session `ses_f4147fd98ffee0SUNHXA3egn5H`）
- ③ 决策周期 / 命令延迟 / 情报可见性：**只在会话 DB**，`part.id = prt_0beb917bc001g24m2V9bkjTZd5`（session `ses_f4147da15ffe1ewE1H0p4X3mxj`）

**其它**
- brainstorm：`docs/superpowers/specs/2026-09-20-sd-simos-brainstorm.md`（当前**未入库**，`git status` 显示 untracked）
- ★ 以上原始原件**一律不入库**（仓库已 67MB 证据、push 偏慢）；入库的只有本文。

