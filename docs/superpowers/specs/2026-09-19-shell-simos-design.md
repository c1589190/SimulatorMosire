# Simos 外壳（M5）设计

> **状态**：待用户批准（2026-09-19 落稿）。
> **基线**：M4 关账 `cf4a05f`（M4 17/17 完成，700 条用例全绿）。
> **输入**：总纲 §5.4 / §5.5 / §10.4 / §10.5、主计划 §二 M5 行与 §六 P5 流水线、ADR-1、`2026-09-19-agentlib-extension-requirements.md`（含其"交付核验"）。
> **上游交付（已实测）**：`AgentLibMosire db4df36`——P1-A/B/C 全交付（`startWith` 公开、`startHttp` JDK-HttpServer Streamable HTTP、通用 HTTP 审批件）；本仓 `AgentLibAvailabilityTest` 15/15 绿（126 类 ≥ 118，13 个被钉类在册）。

---

## 〇 裁决

### 〇.1 范围与判据（总纲 M5 行的细化）

**M5 = CoreSimos 外壳**：让 **Agent / MCP / 玩家**经**同一条** `Command → ChangeSet → Revision` 路径改同一个世界。

| # | 判据 | 本 spec 的落点 |
|---|---|---|
| ① | Agent 与玩家改同一状态**走同一 Command 路径** | `ShellEndToEndTest`：MCP 工具路径与 GUI HTTP 路径的写各产生 revision + 完整事件链，落在**同一张** `revisions` 表，按 `initiator` 可辨；护栏 R1/R4 |
| ② | MCP 能达到任何**合法**状态 | `McpCoverageTest`：`simos.command.catalog` 列出的**每个**已注册命令类型（+ `advance`/`fork`）都能经工具面提交并生效（审批通过前提下）；护栏 R5 |

**合法状态的定义**（防歧义）：由**已注册命令面**可达的状态。M5 命令面 = unit 8 条（M3 操作面，补 7 条）+ `AdvanceTime` + `ForkBranch`。

### 〇.2 控制器裁定（S1~S10，可在评审时推翻）

| # | 项 | 裁定 | 理由 |
|---|---|---|---|
| S1 | **模块归属** | 新建 **`simos-app`**（`io.mosire.simos.app`），父 POM 加模块；main scope 依赖 core+map+social+unit+agentlib+mcp-core+mcp-json-jackson2+jackson+日志实现；**不设 enforcer** | ADR-1 §九 记账的那笔账：GUI 按 `/map` `/social` `/unit` 路由 ⇒ 天然认识各模块 ⇒ 只能是 app 层；它是组合根，无需限 |
| S2 | **GUI 形态** | **5711 单服务**：JDK `HttpServer` + 静态资源（**无 npm、无构建、无 CDN**）；`/map` 只读 Canvas（移植旧 GSimulator 的 hex-math/render **思路**，数据来自 `/api`）、`/unit` `/social` 表格+命令表单、`/api` 统一 JSON；同源 ⇒ **无 CORS**；**不做地图编辑** | 判据是"同一 Command 路径"，不是 GUI 深度；旧 8711 编辑器直接改 `MapData`，与铁律 2 冲突，不照搬 |
| S3 | **MCP 传输与工具集** | `AgentToMcpServer.startHttp(host=127.0.0.1, port=5715, path="/mcp", …)`（**owned** 形态）；**authorizer 必填**（不给 `standard()` 缺省）；工具 = **3 写 + 9 读**（§7.1 表） | 需求书 P1-B 兑现；5715 保持总纲"独立服务"拓扑（不再有 stdio 兜底）；authorizer 必填 = 防"网络入口静默绕过审批" |
| S4 | **工具↔命令映射** | `simos.command.submit` 是**通用信封**工具（`type` + `payloadJson` + `branch` + `expectedRevision`）⇒ 一条工具覆盖全部已注册命令；`advance`/`fork` 各一条专用工具裹 Core 的 record | 判据②的"任意合法状态"由通用信封一次性覆盖；Core 侧零改动即达 |
| S5 | **审批装配** | 用 AgentLib 通用件：`PendingApprovals` + `HttpApprovalChannel`（`markUp()` 闸） + `ApprovalHttpEndpoint.start(5713, …)`（恒绑回环） + `ApprovalCoordinator`（gates：`AutoApproveGate` → `ConfirmGate`，**无** Superior 判定——M5 无 LLM）；写工具 `ToolGate.Ask(SENSITIVE)`、读工具 `Allow` | P1-C 兑现；**5711 提供 `/api/approvals` 系列为服务端代理**（转发到 5713，只转发不改语义）⇒ GUI 同源，审批语义单点 |
| S6 | **AgentBinding 模型** | 记录照总纲 §5.5：`AgentBinding(BindingId, AgentId, SubjectId target, DecisionScope scope, BindingMode {SUGGEST_ONLY, AUTO_APPLY}, AgentPermissionSet permissions)`；`BindingRegistry`（增删查）；**模块声明式策略**：util.spi 新增 `AgentAttachPolicy`，map/unit 各注册一条（map：Region 且 type=Nation；unit：全部 Unit），app 只问"能绑吗" | 总纲"Core 只问 `canAttachAgent(subject)?`，不写 instanceof"的落地；**不做执行语义**（决策人执行归 Brain/MainMosire） |
| S7 | **查询层 + Facet 装配** | app 装配 `ResolverRegistry`（Map/Social/Unit 三个 resolver）+ **首个真 Facet 消费者**：`FacetRegistry` 注册两个面——`unitsHere`（simos-unit 实现）与 `population`（simos-social 实现）；查询统一经 `QueryService`（解析 → 取 head 状态 → 调 resolver/facet） | 关闭总纲 §3.2 遗留（M3 §〇.4 留白、M4 §〇.4 明记"消费者属 M5"）；Facet 协议从"已定义、无人用"变为"在用" |
| S8 | **core 只读扩展** | `CoreSimos` 增只读委托：`branches()`、`head(BranchId)`（Timeline 已有同签名方法）；**不加**任何写面 | GUI/查询层要"当前 head"；core 写面保持唯一（`submit`） |
| S9 | **unit 命令面补齐** | 新增 7 个 `CommandHandler`（`unit.CreateUnit` / `ReparentUnit` / `SetStrength` / `PlaceAt` / `PlanRoute` / `CancelRoute` / `DisbandUnit`），薄封装 M3 的 `UnitOperations`，与 `RenameUnitHandler` 同形；payload 约定见 §四 | 不补齐则 GUI/MCP 只有"改个名"可做，判据②空转 |
| S10 | **身份与审计** | MCP 路径：`initiator = agent:external-mcp`（配置可改）、correlationId = 每次调用新生成 UUID；GUI 路径：`initiator = player:gui`；审批 classKey = 工具名；事件链照 M4 契约（`received → … → committed`） | 判据①可辨、判据②可追；C21/C22 形态不变 |

### 〇.3 取代/偏离清单（相对总纲，逐条给理由）

1. **总纲 §10.2 表**写 core 依赖含 MCP SDK——**归 `simos-app`**。理由：ADR-1 收窄后 core 的 main scope 只有共享层；MCP 是外壳能力。
2. **总纲 §5.4 把 GUI 列为 CoreSimos 职责**——**归 app 层**（ADR-1 §九 已记账"M5 的账"，本 spec 结账）。
3. **5715 形态**：`startHttp` owned 网络服务（总纲拓扑原样）；需求书里的"stdio 兜底"**不需要**（P1-B 已交付）。
4. **审批端点不自写**：用 AgentLib `ApprovalHttpEndpoint`（需求书 P1-C 兑现）；GUI 经 5711 代理同源——**不**在审批 server 上另挂页面。
5. **Facet 值的类型契约**（新定）：M5 内在场实现只用 `String` / `Number` / `List<String>`（JSON 友好）；`FacetEntry.value` 仍是 `Object`，更丰富类型留待有消费者时收紧。

### 〇.4 不做清单

- **不做地图编辑 / 地图写命令**（M6+）；`/map` 只读。
- **不做 LLM / Agent 运行时**（主 Agent、决策人"执行"归 Brain/MainMosire）——M5 只到**绑定模型 + 工具面 + 审批面**。
- **不做鉴权 / TLS**（回环基线；对外须另包，与 AgentLib 审批面同基线）。
- **不做** P2-D（cap）/ P2-E（`Operation` 扩展）/ P2-F（per-session 身份）——需求书已裁决。
- **不做**发布流水线（shade / distributionManagement 仍为开口项）；运行形态见 §3.4。
- **不新增** Facet 之外的新跨模块契约；`AgentAttachPolicy` 是唯一新增 SPI（放 `util.spi`，ADR-1 指定位置）。

---

## 一 交付物

| 模块 | 交付 |
|---|---|
| `simos-util` | **新增** `spi.AgentAttachPolicy`（模块声明"哪些主体可绑决策人"） |
| `simos-unit` | **新增** 7 个命令 handler（§四）；**新增** `facet.UnitsHereFacet`；`UnitAgentAttachPolicy` |
| `simos-social` | **新增** `facet.PopulationFacet` |
| `simos-map` | **新增** `MapAgentAttachPolicy`（Region 且 type=Nation） |
| `simos-core` | **新增** `CoreSimos.branches()` / `CoreSimos.head(BranchId)`（只读委托，零行为变化） |
| `simos-app`（**新模块**） | `Shell` + `ShellConfig` + `ShellMain`；`query.QueryService`；`tools.*`（工具集 + ToolSource）；`gui.*`（5711 服务器 + 静态前端）；`binding.*`（AgentBinding 族）；审批与 MCP 装配 |
| 文档 | 本 spec + M5 计划 + SDD 台账 + 关账报告 |

---

## 二 边界与依赖

### 2.1 依赖表（simos-app main scope）

| 依赖 | 用途 |
|---|---|
| `simos-core` | `CoreSimos` 门面（唯一写入口） |
| `simos-map` / `simos-social` / `simos-unit` | 领域类型、resolver、facet、命令 handler、codec |
| `io.mosire:agentlib-mosire` | 工具/权限/审批/MCP（SNAPSHOT——跨机重建见 CLAUDE.md） |
| `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2` | MCP SDK（**禁**聚合 `mcp`，它带 Jackson 3） |
| `com.fasterxml.jackson.core:jackson-databind` | 工具 payload / GUI JSON |
| `log4j-core` + `log4j-slf4j2-impl` | 可执行体的日志实现（M4 已裁定"实现归 app 层"） |

### 2.2 构建约束

- 父 POM `<modules>` 增 `simos-app`；**app 不设 `bannedDependencies`**（组合根）。
- `simos-app` 不是任何模块的依赖（无反向依赖；由"父 POM 不引它"天然保证）。
- `AgentLibAvailabilityTest` **扩钉**：新增 M5 直接消费的三个类——`io.mosire.agentlib.mcp.AgentToMcpServer`（`startHttp`）、`io.mosire.agentlib.approval.ApprovalHttpEndpoint`、`io.mosire.agentlib.approval.HttpApprovalChannel`。
- MCP 依赖顺序：`simos-app` 在 reactor 末位；`./mvnw verify` 六模块 → **七模块**。

---

## 三 装配与生命周期

### 3.1 `ShellConfig`

```java
record ShellConfig(Path storeDir, int checkpointInterval,
                   int guiPort, int mcpPort, String mcpPath,
                   int approvalPort, String mcpInitiator)
```

- 缺省：`guiPort=5711`、`mcpPort=5715`、`mcpPath="/mcp"`、`approvalPort=5713`（回环）、`mcpInitiator="agent:external-mcp"`。
- **`port=0` 一律支持**（测试用随机端口）：GUI 与 MCP 各提供 `boundXxxPort()` 读回（MCP 用 AgentLib 的 `boundPort()`；审批用 `ApprovalHttpEndpoint.boundPort()`）。

### 3.2 装配顺序（照 AgentLib 参考装配 `MainMosire App.start` 的范式）

1. `CoreSimos`（`CoreConfig`）→ 注册 codec 3 个 + handler 8 个 + participant 1 个（`UnitTimeParticipant`，`MovementCost` 由 app 注入——M3 口径）；
2. 查询层：`ResolverRegistry`（Map/Social/Unit resolver）+ `FacetRegistry`（unitsHere/population）+ 三个 `AgentAttachPolicy`；
3. 审批侧：`PendingApprovals` → `HttpApprovalChannel`（先建，未 `markUp`）→ `ApprovalCoordinator`（gates: `AutoApproveGate`→`ConfirmGate`；channels: [http]）→ `ToolCallAuthorizer.of(guard, coordinator)`（**带审批**，非 `standard()`）；
4. `ApprovalHttpEndpoint.start(approvalPort, pending, coordinator)` → 成功后再 `channel.markUp()`（可用性认"端口在监听"）；
5. 工具侧：`SimosToolSource`（id `"simos"`）→ `McpSourceBridge.bind(source, registry)`；
6. `AgentToMcpServer.startHttp("127.0.0.1", mcpPort, mcpPath, registry, "simos-shell", version, caller, authorizer)`；
7. GUI：`GuiServer`（5711；静态资源 + `/api`；MCP/审批端口注入页面）；
8. `ShellMain` 解析参数 → `Shell.start(config)`；日志打印四个实际端口。

**caller（MCP 调用者身份）**：`ToolContext.of(AccessToken.GUEST, permissionSet, AgentIdentity.external())`；`permissionSet` = 白名单 `simos.*` + `sensitiveAllowed`/`destructiveAllowed` 显式开（写工具带敏感标记）+ 资源面 `map/social/unit` 三命名空间。**装配期定死**（无 per-session 身份，S/P2-F）。

### 3.3 关闭次序

`Shell.close()`：GUI → MCP server → 审批端点（`ApprovalHttpEndpoint.close`）→ `HttpApprovalChannel.close` → `CoreSimos.close()`；**MCP 与审批都先 `closeGracefully` 再停 server**（AgentLib 已按此实现，我们照其契约调用）。

### 3.4 运行形态

- `ShellMain`（`java -cp` 或 `./mvnw -pl simos-app exec:java`）；**不做 fat jar**（发布流水线开口项）。
- 单实例假设：同一 `storeDir` 只跑一个 Shell（SqliteStore 单连接；跨进程并发不在 M5 范围——M4 遗留条目）。

---

## 四 命令面补齐（unit 7 条）

七个 handler 与 `RenameUnitHandler` **同形**（C26：自反序列化 payload → 调 `UnitOperations` → `Applied`/`Rejected`）。`at`（时刻）一律取**命令执行时 base 状态的 `meta.timestamp()`**（信封不带时刻，C22/裁定 35 口径；初始段/移动段都以此为准）。

| type | payload（`{"…"}`） | 调用的 M3 操作 | 关键拒绝 |
|---|---|---|---|
| `unit.CreateUnit` | `id, name, position{q,r}, member, equipment{}, speed, mobilityPerMille, parent?` | `create(state, Unit)`（初始段时刻 = base 时间戳） | id 已存在 |
| `unit.ReparentUnit` | `id, parent?`（null=清根） | `reparent(state, id, Optional<UnitId>, at)` | 查无此人 / 环 |
| `unit.SetStrength` | `id, member, equipment{}` | `setStrength(state, id, int, Map)` | 查无此人 / 负值 |
| `unit.PlaceAt` | `id, hex{q,r}?`（null=撤销位置） | `placeAt(state, id, Optional<HexCoord>, at)` | 查无此人 |
| `unit.PlanRoute` | `id, waypoints[{q,r}…]` | `planRoute(state, id, Route, at)` | 路线构造期校验（M3） |
| `unit.CancelRoute` | `id` | `cancelRoute(state, id)` | 查无此人 |
| `unit.DisbandUnit` | `id` | `disband(state, id, at)` | 查无此人 |

- 载荷坏 ⇒ `Rejected`（理由进 `simos.command.rejected` 事件）；**拒绝不留 revision**（R9 形态）。
- `UnitChangeSet.between(before, after)` 产出变更集（与现有 handler 一致）。

---

## 五 查询层

### 5.1 `QueryService`（simos-app）

```java
record QueryTarget(BranchId branch, RevisionId revision)   // 缺省 = head
SimulationState stateAt(QueryTarget t)                     // CoreSimos.replay
QueryResult resolve(String addressText, QueryTarget t)     // ResolverRegistry.resolve
List<FacetEntry> facets(String addressText, QueryTarget t) // FacetRegistry.queryAll
List<String> facetNames()
```

- `ResolveContext(state, state.meta().timestamp())`；**每次查询重放**（小规模可接受；缓存留待有消费者——不做）。
- 未注册 namespace / 未注册 facet ⇒ 明确失败（`IllegalArgumentException` / 空表语义按 util 契约），**不静默**。

### 5.2 两个真 Facet（关闭总纲 §3.2 遗留）

| facet | 实现模块 | subject 形态 | 返回（value 只用 JSON 基础/简单型，S 〇.3-5） |
|---|---|---|---|
| `unitsHere` | `simos-unit` | `map:<mapId>:hex.<q>_<r>` | 该格上的单位摘要（`List<String>`：`unit:<id> <name>` 形） |
| `population` | `simos-social` | 同上 | `Number`：`ctx.at` 时刻该格人口 |

- 两实现都**只读** `ctx.state()` 的对应切片（unit 用 `effectivePosition(id, ctx.at)`；social 用 `PopulationSeries.valueAt(ctx.at)`）。
- `FacetRegistry` 的注册与查询在 app 装配；`MapSimos` 对两者**零知情**（铁律 3）。

---

## 六 AgentBinding（模型层，不做执行）

```java
record BindingId(String value)         // 非空白
record AgentId(String value)           // 形如 agent:<id>（C21 形态）
record DecisionScope(String value)     // 不透明标签；M5 只校验非空白（语义留待消费者）
enum   BindingMode { SUGGEST_ONLY, AUTO_APPLY }
record AgentBinding(BindingId id, AgentId agentId, SubjectId target, DecisionScope scope,
                    BindingMode mode, AgentPermissionSet permissions)
```

- `BindingRegistry`（app）：`bind/unbind/list/bySubject/byAgent`；重复 `(agentId,target)` ⇒ 拒绝。
- **可绑性判定**：`util.spi.AgentAttachPolicy`：

```java
public interface AgentAttachPolicy {
  String namespace();                                   // 与地址首段一致
  boolean canAttach(Address subject, ResolveContext ctx);
}
```

  - `MapAgentAttachPolicy`：Region 且 `type=Nation`；`UnitAgentAttachPolicy`：全部 Unit。
  - `BindingRegistry.bind` 先解析 subject（canonical）→ 问对应 namespace 的 policy → 无 policy/不许 ⇒ 拒绝。
- **边界**：M5 只到"记录 + 可绑性 + 查询"。绑定后的执行（决策人产出 → Command）由 Brain/MainMosire 消费，**不在本仓实现**。

---

## 七 AgentLib 集成

### 7.1 工具集（3 写 + 9 读；`ToolSpec`/gate/resources 一表）

| 工具 | 类 | spec | gate | resources |
|---|---|---|---|---|
| `simos.command.catalog` | 读 | DEFAULT | Allow | — |
| `simos.state.resolve` | 读 | DEFAULT | Allow | map+soc+unit READ_ONLY |
| `simos.state.facets` | 读 | DEFAULT | Allow | 同上 |
| `simos.timeline.branches` | 读 | DEFAULT | Allow | — |
| `simos.map.overview` | 读 | DEFAULT | Allow | map READ_ONLY |
| `simos.map.hex` | 读 | DEFAULT | Allow | map READ_ONLY |
| `simos.unit.list` | 读 | DEFAULT | Allow | unit READ_ONLY |
| `simos.unit.get` | 读 | DEFAULT | Allow | unit READ_ONLY |
| `simos.social.population` | 读 | DEFAULT | Allow | social READ_ONLY |
| `simos.command.submit` | 写 | `sensitive=true` | **Ask**（classKey=工具名；summary 含 type/branch/expected） | map+soc+unit UNRESTRICTED |
| `simos.advance` | 写 | `sensitive=true` | **Ask** | 三命名空间 UNRESTRICTED |
| `simos.fork` | 写 | `sensitive=true` | **Ask** | — |

- **写工具执行语义**：构造 `CommandEnvelope`（commandId/correlationId 新生成，initiator 取 `ShellConfig.mcpInitiator`）→ `CoreSimos.submit` → `CommandResult` 折成 `ToolResult`（`Rejected/Conflict → ToolResult.error`，`Committed → ok`）——**这是全仓唯一让 Agent 写状态的路**。
- 读工具经 `QueryService`；`catalog` 列 `CommandRegistry.types()`（Core 暴露？——**不**：由 app 持有**已注册命令类型清单**（它与注册 handler 同源），catalog 读它；避免给 core 加新面）。
- **内部工具**（如调试/`binding` 管理若上 MCP）一律 `noExport=true`（R2）。

### 7.2 MCP 服务

`AgentToMcpServer.startHttp("127.0.0.1", mcpPort, mcpPath, registry, "simos-shell", "0.1.0-SNAPSHOT", caller, authorizer)`；`ToolSpec.noExport` 工具不外发；`tools.listChanged` 由 AgentLib 恒广播（已修）；**无** resumability / 协议版本校验（AgentLib 边界，照实记）。

### 7.3 审批链

- 人 →（5711 `/api/approvals` 代理）→ 5713 `ApprovalHttpEndpoint` → `PendingApprovals` → 唤醒 `HttpApprovalChannel.await` → `ApprovalCoordinator` 放行/拒绝 → 工具执行或 `APPROVAL_DENIED`。
- 审查语义**不自建**：超时 DENY、无渠道 fail-closed、`APPROVE_SESSION` 会话键受 `sessionGrantable` 约束（AgentLib 契约原样）。

---

## 八 GUI（5711）

### 8.1 服务器形态

JDK `HttpServer`（**虚拟线程 executor**）+ `StaticFileHandler` 式静态资源（classpath `/webui/...`）；**无构建、无 CDN**（HTMX/Tailwind 等一律不用）；同源 ⇒ **零 CORS 头**。

### 8.2 端点表

| 方法/路径 | 语义 |
|---|---|
| `GET /` `/map` `/unit` `/social` | 静态页面 |
| `GET /api/state` | `{branches, heads, current meta}` |
| `GET /api/resolve?address=…` | resolver 候选 |
| `GET /api/facets?address=…` | facet 汇总（含 `unitsHere`/`population`） |
| `GET /api/map/overview` `/api/map/hex?q=&r=` | 地图只读视图数据 |
| `GET /api/units` `/api/unit/{id}` | 单位列表/详情（含 `effectivePosition`） |
| `GET /api/social/population?q=&r=` | 人口时序点 |
| `POST /api/command` | 信封提交（`initiator=player:gui`） |
| `POST /api/advance` `/api/fork` | 两条 Core 命令 |
| `GET /api/approvals` · `POST /api/approvals/{id}` | **代理**到 5713（状态码/体透传；5713 不可达 ⇒ 502） |

**全部写端点经 `CoreSimos.submit`**；GUI 不打开任何 store/timeline 写面（R1）。

### 8.3 前端三页（无构建）

- `/map`：只读 Canvas 六角图（移植旧 `hex-math`+`render` 思路；地形色块、路径、区域描边可后置）+ 点击查格详情（走 `/api/facets`）。
- `/unit`：列表 + 详情 + 命令表单（8 条命令的字段表单，提交 `/api/command`）。
- `/social`：某格人口序列取值 + 简表。
- 三页共享 `app.js`/`api.js`/`styles.css`（单套静态资源 ⇒"同一套构建"）。

### 8.4 审批面板

`/` 首页顶部含"待批"计数（轮询 `/api/approvals`），点开为列表 + 批准/拒绝（`POST …/{id}`，体 `{"decision":"approve|deny","scope":"once|session","by":"gui"}`）——语义完全落在 AgentLib 端点。

---

## 九 身份与审计

1. **initiator**：MCP 写 = `ShellConfig.mcpInitiator`（缺省 `agent:external-mcp`）；GUI 写 = `player:gui`；两者都是 C21 `<kind>:<id>` 形态。
2. **correlationId**：工具/GUI **每次调用生成一个新 UUID**，并令 `commandId = correlationId = 该值`（C22 的"单命令链缺省"由**调用方**在这里显式满足；不生成两个独立值——那会切断"一次工具调用"与"一条命令链"的一一对应）。工具把该值写进 `ToolResult` 便于对账；审批 `approval.requested/decided` 与命令事件经它关联。
3. **审计链**：任意写命令可 `events WHERE correlation_id=?` 得到 `received → … → committed`（M4 契约）；审批另有 `approval.requested/decided`（AgentLib EventBus——**M5 挂一个 `EventBus` 到 AgentLib 的 `SqliteEventStore` 吗？** 不：simos 自己的 events 表是唯一真相；审批事件由 AgentLib 记到它自己的 bus，**M5 先不桥接**，如实记。）

---

## 十 判据验证设计

### 10.1 ① 同一 Command 路径（`ShellEndToEndTest`）

1. 起 Shell（`storeDir=@TempDir`，三个端口全 0）；种子世界（genesis + checkpoint，同 M4 测试范式）。
2. **玩家路径**：`POST /api/command`（`unit.RenameUnit`）⇒ `Committed`。
3. **Agent 路径**：MCP 客户端（SDK `HttpClientStreamableHttpTransport`）调 `simos.command.submit`（改名）——触发审批：测试轮询 `pending()` 后 `decide(APPROVE_ONCE)`，断言工具**先拒后放**。
4. 断言：两条 revision 在**同一张** `revisions` 表、`initiator` 分别为 `player:gui` / `agent:external-mcp`、事件链齐全、状态按两条命令各自生效。

### 10.2 ② 任意合法状态（`McpCoverageTest`）

1. `simos.command.catalog` 取全部类型；对**每个**类型构造最小合法 payload（夹具世界：两单位 + 走廊地图）。
2. 逐个经 `simos.command.submit` 提交（审批用测试直批）⇒ 全部 `Committed`；`advance`/`fork` 各自提交生效。
3. **反向**：对同一 type 提交非法 payload ⇒ `Rejected` 且**不留 revision**（R9 形态）。

### 10.3 测试基础设施

- 随机端口（0）+ `@TempDir` store；MCP 客户端直连 `http://127.0.0.1:<boundPort>/mcp`；审批直批用 `PendingApprovals.decide`（测试进程内可达）。
- **官方 SDK 客户端连通性**是本 spec 的**首个实测项**（AgentLib 侧只验过 raw HTTP 客户端）。

---

## 十一 护栏清单（每条都要 G13 变异自证）

| # | 护栏 | 形态 | 变异（期望红在哪） |
|---|---|---|---|
| R1 | **同一 Command 路径**：app 源码**零** store/timeline 写面（扫描 `io.mosire.simos.app` 不出现 `SqliteStore` / `Timeline.appendRevision` / `CheckpointStore`；行为面由 10.1 覆盖） | 扫描+行为 | 在 app 里加一行 `SqliteStore.open(...)` ⇒ 扫描用例红 |
| R2 | **暴露白名单**：`noExport` 工具不出现在 `tools/list`；`include` 语义不破 | 行为 | 去掉某内部工具的 `noExport` ⇒ 列表断言红 |
| R3 | **审批闸承重（L11 回归）**：未决/拒绝的写调用必须 `APPROVAL_DENIED` 且**无 revision** | 行为 | 写工具 gate `Ask→Allow` ⇒ "未审批也执行"用例红 |
| R4 | **身份注入**：MCP 写命令 `initiator=agent:…`、GUI `initiator=player:gui`，correlationId 可追事件链 | 行为 | initiator 写死成 `player:local` ⇒ 断言红 |
| R5 | **命令覆盖（判据②）**：catalog 与工具可达性一致（每个 type 可经 submit 到达） | 行为 | 工具实现对某 type 返回 unsupported ⇒ 覆盖用例红 |
| R6 | **查询参数原样转交（形态 4）**：`QueryService.facets` 把 address/ctx **原样**交给注册的 provider（记录型替身断言收到值） | 转发 | 转发时改一个字段 ⇒ 红 |
| R7 | **Facet 装配完整性**：`facetNames()` 含 `unitsHere`/`population`；未注册名 ⇒ 明确失败 | 行为 | 注册表摘掉一个 ⇒ 红 |
| R8 | **AgentBinding 策略门**：无 policy 的 namespace / policy 拒绝的主体 ⇒ bind 拒绝；canonical 化后再问 | 行为 | 跳过策略检查 ⇒ 红 |
| R9 | **生命周期**：`close()` 后三个端口全部释放、无停驻线程（审批长连先收） | 行为 | 去掉一次 `stop`/`closeGracefully` ⇒ 红 |

---

## 十二 任务分解（供计划，粗粒度）

| # | 任务 | 依赖 |
|---|---|---|
| T1 | `simos-app` 骨架：pom/父 POM/`ShellConfig`/`Shell` 装配顺序/`ShellMain` | — |
| T2 | core 只读扩展（`branches/head`）+ `AgentLibAvailabilityTest` 扩钉 | — |
| T3 | 查询层：resolver 装配 + 两个 facet + `QueryService` | T1 |
| T4 | unit 7 命令 handler + 载荷校验 | — |
| T5 | 工具集 + `SimosToolSource` + 身份映射（R4/R6/R7 的载体） | T3/T4 |
| T6 | 审批装配（coordinator/channel/endpoint/authorizer） | T1 |
| T7 | MCP 服务装配 + 官方客户端端到端 + 覆盖测试（R2/R3/R5） | T5/T6 |
| T8 | GUI 服务器 + `/api` 端点 + 代理（R1） | T3/T6 |
| T9 | GUI 前端三页 + Canvas 地图 | T8 |
| T10 | AgentBinding + `AgentAttachPolicy`（R8） | T1 |
| T11 | 判据端到端（①②）+ 全量门禁（R9） | T7~T10 |
| T12 | M5 关账（判据逐条、护栏自证、CLAUDE.md、报告） | T11 |

---

## 十三 未决 / 实测项（写 bite-sized 计划前的基础）

1. **官方 MCP SDK 客户端 ↔ `startHttp` 服务端连通性**未实测（AgentLib 只验过 raw HTTP）——T7 第一件事。
2. **GUI Canvas 移植规模**未估（旧 render/hex-math 面向 `MapData`，需适配 simos 的 `GameMap`/`Snapshot` 读取），T9 先估。
3. **unit 载荷细节**（尤其 `CreateUnit` 的字段→`Unit` 映射与初始段时刻）要与 M3 `UnitOperations` 逐字对齐；实施时若发现约定不可行，记取代说明。
4. **每查询重放**的代价未测（小规模可接受；缓存不做）。
5. **app 运行形态**（无 fat jar）够不够用，M5 关账时评。
