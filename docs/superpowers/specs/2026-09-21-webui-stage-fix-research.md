# WebUI 阶段修复 —— 只读调查报告

> 三路只读调查（2026-09-21），**未跑 Maven、未改任何生产文件**。
> 配套：`2026-09-21-webui-stage-fix-brainstorm.md`（用户原始需求 R1~R13）。
> ★ 每条都带 **文件:行号** 锚；**没有锚的一律标"推断"**。

---

## A. GSimulator 的道路/河流（连通性）编辑 —— 用户论断成立且比预想更强

### A.1 数据结构：连通性与地形**在物理上就是两套字段**

| 表示 | 位置 | 状态 |
|---|---|---|
| **`edges`** | `MapData` 顶层 | ★ **当代权威**：稀疏边表 `edgeKey → pathwayId → props`，`edgeKey = "minQ_minR\|maxQ_maxR"`（**顺序无关**） |
| **`pathwayGroups`** | `MapData` 顶层 | ★ **当代权威**：边类型注册表 + 每类属性 schema（`river/road` 各带 `width` 默认值） |
| `HexCell.edgeTags` | 每格 | **前端工作态 + 遗留**（后端只透传、**从不读取**） |
| `HexCell.riverMask` | 每格 | **@Deprecated** 6-bit，仅迁移源；写入恒 0 |
| `rivers`/`roads` | `MapData` 顶层 | **@Deprecated** records，已无调用 |
| `terrainBlocks` / `terrainTypes` | `MapData` 顶层 | **活字段**（地形专用，**与连通性无关**） |

锚：`~/DevMosire/GSimulator/gsim-map/src/main/java/com/gsim/map/map/MapData.java:33-46`（顶层字段）、`:27-30`（`edges` javadoc）、`:71-83`+`:348-374`（`rivers`/`roads` @Deprecated）、`:200-207`（riverMask→edgeTags 迁移）、`:390-417`（`edgeKey` 确定性）、`:419-480`（`PathwayGroup`/`PropertyDef`/默认两组）；`gsim-map/src/main/java/com/gsim/map/map/MapDiff.java:34-44`；`docs/DATA-MODEL.md:89-97`。

★ **更正 `CLAUDE.md`**：旧仓在本分支（`refactor/gsim-module-split`）里 **`terrainBlocks` 没有被 @Deprecated**——被废弃的是 `TerrainBlockProcessor` **类**（`TerrainBlockProcessor.java:15-18`「Replaced by TerrainCanvas」）。simos 的 M6 导入器把"terrainBlocks 当成 @Deprecated"是有偏差的。

### A.2 编辑交互：**独立模式 + 完全不同的手势**

- 工具栏**独立按钮** `🛤️`：`web/index.html:16-21`（`setTool('pathway')`）
- 与地形笔刷**互斥的 tool 状态**：切模式清起点 `js/ui.js:14-21`；地形泛涂**显式排除** pathway `js/events.js:92`
- **手势**：**右键拖拽 = 画边**；**左键点/拖 = 删边**（12px 命中阈值）；**起点 → 续点 → 同格/非相邻 = 结束**
  锚：`js/events.js:2-45`（mousedown 右键进 pathway / 左键 `findSegmentAtPixel`）、`:96-107`（拖拽续画/连删）、`js/pathway.js:74-123`（waypoint 状态机 + 相邻校验 + 拖拽去重 + **缺格自动建** + **双向写边**）、`:125-142`（删段）、`:160-191`（`findSegmentAtPixel`）、`:196-274`（渲染：白色辉光底 + 组别色半格线）、`:450-516`（`syncEdgesToHexTags` / `syncHexTagsToEdges`）
- **读侧成熟工具**：`tracePathway` 把边集**分解成链**（端点/分支/环）— `MapService.java:743-829`

### A.3 自动生成 / 约束 / 校验

- ★ **没有自动生成**：生成器只按高度场造地形；`riverMask` 恒 0、`edges` 空（`MapService.java:1075-1076`）。**没有"河流由高度场生成"、没有 A\* 造路造河**。
- 约束：**只有前端**校验相邻（`direction() >= 0`）；★ **后端不校验**（`setEdgeTag` 只查 tag 已注册 + 两格不同，`MapService.java:340-373`；MCP 工具 `GsimapEdgeSetTool:79` 同样**不查相邻**）⇒ **MCP 可以连非相邻格**（真缺口）。
- 校验：`removeEdgeTag` **严格**（不存在即抛，`MapService.java:381-421`）；tag 必须在 `pathwayGroups` 注册，否则抛（`:347-350`）；标签空 ⇒ 自动删边；`resolveEdgeWithDefaults` 合并默认值（`:616-634`）。

### A.4 ★★ 两份/三份表示的关系与优先级（**这是 M6"非空即报错"的根因**）

- 权威：**`edges` 是后端权威**（工具只读写它）；**`edgeTags` 是前端权威**（渲染/编辑只读它）；两者在 load/save 时**互相同步**（单向镜像，**非合并**）。
- ★ **隐患 1**：`syncEdgesToHexTags` **只追加不清空** ⇒ 若存档里同时留了**陈旧 `edgeTags`**，load 会把陈旧标签并回来、save 时再写回 `edges`。
- ★ **隐患 2**：web save 发整份 `mapData`（**含 `hexes[].edgeTags` 与 `edges`**），后端**不剥离** ⇒ 冗余双份同时落盘。
- ★★ **隐患 3（最重）**：**`MapDiff` 不携带 `edges`/`pathwayGroups`**（`MapDiff.java:34-44`），而 `applyDiff` 直接沿用 `base.edges()`（`MapResolver.java:241-267`）⇒ **非根节点上的连通性写入不可表达、静默丢失**。⇒ **这正是 `CLAUDE.md` 说的"四个字段漂移"在连通性上的具体表现**（旧仓真出过 `edges-wiped-on-rebuild` 事故）。
- ⇒ **正确的融合规则应是"`edges` 胜出，据 `edges` 重建 `edgeTags`，忽略 `riverMask`"**，但**旧仓里没有这条规则**，只有增量的迁移与镜像。

---

## B. SimulatorMosire 现有 WebUI 结构

### B.1 模式框架

- **5 个模式**在 `simos-app/src/main/resources/webui/modes.js:20-59`：`view` / `region` / `map-edit` / `region-edit` / `unit`，各带 `writes` 白名单；`isWriteAllowed` **fail-closed**（`:86-88`），唯一调用点 `app.js:317-323`（拒绝时**不发请求**）。
- ★ **模式栏按钮硬编码在 `index.html:18-25`，与 `modes.js` 的 MODES 两份数据源，且没有测试互相钉住**；真正钉住的是 **Java** `WebuiAssetsTest.java:58 MODE_LABELS` + `:149-158 allFiveModesAreEnabledRealControls`（按 label 循环断言"恰一个 button、不 disabled"）。
- **新增一个模式要改 9 处**：`modes.js:20-59` / `index.html:18-25`（按钮）/ `index.html`（`<section data-modes="…">`，先例 `:177`）/ `map.js:2283-2306`（若改地图行为）/ `styles.css` / `WebuiAssetsTest.java:58`（+ 资产清单 `:45/:51-52/:60`）/ `modes.test.cjs:16-26`（+ 白名单用例）/ 三处下界（`gate-contract.test.cjs:9-20 REQUIRED_FILES`、`:28 MIN_ASSERTIONS`、`run-gate.cjs:18 MIN_TESTS`）。

### B.2 审批 UI（要撤的东西，比想象的少）

- 前端**只有一个计数标签**，没有列表/批准/驳回按钮：`index.html:210-211`（`<h3>待批</h3>` + `<p id="approvals-count">`）
- 渲染 `app.js:368-386 mountApprovals`；由 `index.html:246` 的 `boot({approvals:"approvals-count"})` 触发（`app.js:393-395`）；导出 `:450`；API `api.js:252-254` → `GET /api/approvals`
- ★★ **隐藏断点**：`simos-app/src/test/js/write-allowlist.test.cjs:112` `await api.approvals();` —— **删掉前端会把这条件例打红**，必须同步改。
- 后端 `/api/approvals` 是 AgentLib `ApprovalHttpEndpoint` 的**透传代理**：`GuiServer.java:102/221-222/385-387/594-637`；装配 `Shell.java:354-371`、`:403-409`；**后端建议保留**（Java 测试 `ShellApprovalTest.java:185-246`、`GuiApiTest.java:557-566` 覆盖它）。

### B.3 右栏 / 决策人痕迹

- 右栏 `#right-panel`（`index.html:205`）**只有两块**：区域面板（`data-modes="region region-edit"`，`:206-209`）+ 待批（`:210-211`）。
- 渲染：`panels.js:557-590 renderRight` → `:485-554 drawRight` → `:466-482 regionDetail`；数据源 `:29-31 loadOverview`（`/api/map/overview` 的 `body.regions`）；分组纯函数 `:46-75 groupByTag`。
- ★ **前端零"决策人"痕迹**（`decisionMaker`/`Directive`/`verdict`/`Sd` 在 `webui/**` 命中 **0**）。
- ★★ **后端也没有任何只读端点/工具能列出决策人** ⇒ **必须新加 Java 查询面**（见 C.1）。

### B.4 时间线

- 分组 `timeline.js:91-116 groupByTick`、`:141-165 columnOfTick`、`:233` 构建 tickGroups、`:272-293 tickNode`、`:295-328 renderTrack`
- 推进 = **显式点击** `#timeline-create`（`index.html:227-228`，title「推进 N tick（core.AdvanceTime…）」）→ `timeline.js:634-665 onCreate` → `POST /api/advance`
- ★ **没有自动发命令、没有自动推进**；轮询只有 `app.js` 的 5s 只读 `pollState`/`mountApprovals`
- ⇒ 用户说的"推进即决策"是 **UX 语义**（"一 tick 一节点 + 推进按钮"读起来像"推进就决策"），**不是行为**。真正的原因见 C.6。

### B.5 底栏 / 右下角

- 底栏 `footer#timeline-bar`（`index.html:221-233`）：`#timeline-mount`、`#timeline-advance-n`、`#timeline-create`、`#timeline-fork`、`#timeline-meta`、`#timeline-status`
- `#map-hud`（`index.html:215-219`）是底栏**上方一条全宽条**（缩放/地图状态/图例）
- ★ **没有现成的"右下角"空位**：右栏 300px 通高（`styles.css` `.wb-body{align-items:stretch}` + `.col-right{flex:0 0 300px}`），右下角实际是**右栏下半部的空白** ⇒ **通知栏是新元素**（`position:fixed` 浮层，或挂在右栏底部）。

### B.6 左栏（决策人信息要挂这里）

- `#left-panel`（`index.html:37`）：`#left-status`、`#selection-detail`、`#unit-tree-mount`，加各模式 `<section data-modes>`（`unit-editor:44-86`、`map-editor:88-125`、`region-editor:127-175`、`region-info-editor:177-200`）
- 渲染入口 `panels.js:438-464 renderLeft` → 分发 `:341 renderHex` / `:402 renderUnit`；init `:593-600`

---

## C. SDSimos 当前实现面 —— **界面要用的东西大半不存在**

### C.1 决策人模型与「国家 / 单位」

- `DecisionMaker(id, affiliation, allowedTools, viewScope, decisionCadenceTicks)` — `simos-sd/.../model/DecisionMaker.java:14-19`
- ★ **归属只有 `Nation` | `Army`**（`Affiliation.java:21-42`）；**`Army` 才带 `rootUnit: UnitId`**（`Army.java:13`）⇒ **"单位决策人"= 军队决策人**，**没有直接指向 `UnitId` 的归属**。
- 创建：`sd.CreateDecisionMaker`（`{id, affiliation:{kind:"nation"|"army", id}, allowedTools[], cadence}`，`CreateDecisionMakerHandler.java:21-24,45-48`）；创建期 `viewScope` **恒空**（`:29,60`）；`allowedTools` 含通用写即拒（`:49-58`）；★ **`allowedTools` 创建后无命令可改**。
- ★★ **没有任何"列出某国/某单位的决策人"的 API**：只有全量遍历 `Shell.java:460-476 currentActorIds`（**不过滤 affiliation**）与按 ID 查 `RedactingQueryService.java:47-51`。⇒ **UI 要显示列表 ⇒ 必须新加查询面**。

### C.2 寻址

- `sd:decision-maker.<id>` = **2 段**（`SdResolver.java:33-36,70-76,132-147`；实测 `SdResolverTest.java:35-36`）
- `sd:combat.<id>` = **2 段**、`:stage.<s>` = **3**、`:stage.<s>:outcome.<o>` = **4**（`SdResolver.java:101/104/115`）⇒ A4 取代说明里的"**3/4 段**"指的是**阶段/结局子形态**，**不是 combat 本身**。
- ★ **N15：决策人地址不可用 `agent:`**（`DecisionMakerId.java:4-5`、`SdResolver.java:42`）；`ActorId` 就是 `DecisionMakerId` 裸值。

### C.3 GM 配权

- `sd.SetViewScope`（`SetViewScopeHandler.java:19-23`；载荷解析 `SdPayloads.java:369-396`）→ 写 `DecisionMaker.viewScope`（6 字段：`visibleRegions/visibleHexes/visibleUnits/seeOwnUnits/adjudicationDisclosure/redactedFields`，`ViewScope.java:17-23`）
- **随 revision 落盘 ⇒ 可回放可分岔**（`ViewScope.java:11-12`）—— **没有独立存储表**
- ★ 代码里**没有 `GM` 实体/类**；"GM"只是工具面的 `Role` 桶名 + 注释称谓

### C.4 ★★ 工具面：**运行时 MCP 只挂了 EXTERNAL 桶**

- `Role` 三桶（`SimosToolSource.java:46-54`）：`EXTERNAL` / `GM` / `DECISION_AGENT`
- 9 条读工具三桶共享（`SimosToolSource.java:110-122`）：`simos.command.catalog` / `state.resolve` / `state.facets` / `timeline.branches` / `map.overview` / `map.hex` / `unit.list` / `unit.get` / `social.population`
- 写：`EXTERNAL` = `simos.command.submit` / `simos.advance` / `simos.fork`（`:90-96`）；**GM** = `sd.IssueDirective` / `sd.SubmitVerdict` / **`sd.SetViewScope`**（`:97-101`）；**DECISION_AGENT** = `sd.IssueDirective` / `sd.SubmitVerdict`（**无** `SetViewScope`、**无**通用写）（`:102-105`）
- 敏感位与门：窄写 `AbstractNarrowWriteTool.java:62-74`（`level(DEFAULT,sensitive=true)` + `Ask(SENSITIVE)`）；三条通用写同形（`CommandSubmitTool:70-92`、`AdvanceTool:70-92`、`ForkTool:62-77`）；读工具 `ToolSpec.DEFAULT` + `ALLOW`（`SimosToolsTest.java:298-304`）
- ★★ **`Shell` 用 5 参构造器装配 ⇒ 默认桶 = `Role.EXTERNAL`**（`SimosToolSource.java:67-74`、`Shell.java:374-378`）⇒ **GM / DECISION_AGENT 的桶不在任何运行中的 MCP 上**；`Shell.toolsFor(role)` 存在但**全仓只有测试调用**（`Shell.java:483-488`、`SimosToolsTest.java:242-244`）。

### C.5 审批（现状）

- 链：`MCP tools/call → AgentToMcpServer → ToolCallAuthorizer → ToolExecutionGuard(级别) → tool.gate()=Ask → ApprovalCoordinator → AutoApproveGate→ConfirmGate → HttpApprovalChannel.await → PendingApprovals`（装配 `Shell.java:354-371`，`AgentToMcpServer.startHttp(..., toolAuthorizer)` `:384-393`）
- 请求字段（★ **外部依赖**）：`id, tool, classKey, summary, digest, createdAtEpochMs, deadlineEpochMs, callerKey, requesterId, kind, goal`（`ApprovalRequest.java:47-58`）；**事件面只带 `{id,tool,classKey,digest}`**，`summary` 不进事件（`:9-12`）
- GUI 代理：`GET /api/approvals` ⇒ `{"pending":[…]}`；`POST /api/approvals/{id}` 体 `{"decision":"approve"|"deny","scope":"once"|"session","by":"gui"}` ⇒ `{decision,scope}`（`ShellApprovalTest.java:185-204`）
- ★ 审批端点**硬编码回环**（AgentLib 无 host 形参），**只有 GUI 是对外的**

### C.6 ★★「建议决策」信号 —— **不存在，必须新造**

- `decisionCadenceTicks` **是只写字段**：全仓无任何"距上次决策多少 tick / 是否到周期"的计算
- 断点是**静态常量表**（`Breakpoints.java:20-50`）；`AdjudicatorRunner.run(..., due, ...)` 的 **`due` 是入参**，**没有任何代码计算它**，且**该类未接入 `Shell`**
- `TriggerEvaluator` 只服务 `Effect.trigger` 与 `CombatStage.exit`（推进时求值），**不是决策人的待决信号**（`SdTimeParticipant.java:85-142`）
- 唯一机器可判的近似物是 **R4 不变量**（`(decisionMakerId, tick)` 唯一，`IssueDirectiveHandler.java:129-143`、`SdState.java:253-267`）⇒ 理论上可"扫 `directives()` 看该 `(dm,tick)` 有没有行"，但**没有 API 暴露**
- `DirectiveStatus` 四档（PLANNED/ISSUED/EXECUTED/CANCELLED），handler **只写 `ISSUED`**，其余档无人设置

### C.7 可见性 / redaction（**有洞**）

- `RedactingQueryService`（`app/query/RedactingQueryService.java`）：`scopeOf(actor,target)` 取 `viewScope`，**取不到即空范围（fail-closed）**（`:47-51`）；`mapOverview` 过滤 hex/region/city（`:54-…`）、`units` 过滤到 `visibleUnits ∪ (seeOwnUnits ? 自己的 rootUnit 子树)`（`:…-124`）
- 接线：**只有** `GET /api/map/overview?as=<dmId>` 与 `GET /api/units?as=<dmId>`（`GuiServer.java:295-305,318-333`）+ 两个读工具的 `actor` 参数（`MapOverviewTool:50,65-68`、`UnitListTool:48,63-66`）
- ★★ **两个洞**：① **`adjudicationDisclosure` 与 `redactedFields` 被解析、被存、却从未被应用**（只经 `ChannelAdmission.redactedBrief:61-81` 回显）；② 其余端点（`/api/state`、`/api/timeline`、`/api/map/hex`、`/api/unit/{id}`、`/api/social/population`、`/api/resolve`、`/api/facets`）**完全不过 redaction**

### C.8 ★★ `DecisionChannel` 无任何入站传输

- `DecisionChannel` 的 4 个实现（`GuiDecisionChannel`/`McpDecisionChannel`/`HttpDecisionChannel`/`CliDecisionChannel`）**只被测试调用**：**没有 GUI 端点、没有 MCP 工具、没有 HTTP 路由**
- `AdjudicatorRunner` 同样**未接入 `Shell`**（只有测试引用）
- ⇒ **"多渠道决策提交"目前是"有抽象、无入口"**；`?as=` 前端**从未使用**（`webui/**` 搜 `as=` 无命中）

### C.9 MCP 服务能否再开一个给 GM

- 现在**只有一个**：`AgentToMcpServer.startHttp(bind, mcpPort, mcpPath, toolRegistry, name, version, mcpCaller, toolAuthorizer)`（`Shell.java:384-393`）；`--mcp-port` 在 `ShellMain.java:72`，缺省 5716/5715 区（`ShellConfig`）
- ★ **可以再开一个**（`startHttp` 接受任意 `ToolRegistry`）：做法 = 新建 `ToolRegistry` + `McpSourceBridge.bind(new SimosToolSource(..., Role.GM), registry)` + 再次 `startHttp(..., 新端口)`。★ 需 app 层改 `Shell`（加第二个端口配置 + 字段 + 关闭顺序）。`MCP_SERVER_NAME = "simos-shell"` 是常量（重名需评估）。

---

## D. 汇总：**要新造的东西**（UI 依赖它们）

| # | 缺什么 | 为什么 UI 需要它 | 落点 |
|---|---|---|---|
| 1 | **决策人查询面**（按 affiliation 列决策人 + 取单个详情） | R6/R7/R8 三处都要读 | `GuiServer` 新端点 + 或 `SdResolver`/新读工具 |
| 2 | **"待决/建议决策"信号**（该 dm 本 tick 是否已决策 / 是否到周期） | R10 的列表 | 新服务端计算（R4 不变量 + `decisionCadenceTicks`） |
| 3 | **"开始决策"的权限与入口**（用户优先、GM Agent 其次） | R11 | 新契约（渠道 or 命令）+ 配额 |
| 4 | **GM 桶的传输**（GM MCP server 或 GM 写端点） | R13 | 第二个 `ToolRegistry` + `startHttp` |
| 5 | **通知栏**（审批摘要） | R4 | 新前端元素（fixed 浮层） |
| 6 | **`adjudicationDisclosure`/`redactedFields` 真正生效** | 可见性正确性（C.7 洞） | `RedactingQueryService` |
| 7 | **决策人信息数据结构**（R6/R7 要显示的字段） | 左栏面板 | 待定（`SdInfoEntry` / `DecisionMaker` 字段） |

## E. 我未能核实的

1. **`agentlib-mosire` 是外部依赖**：审批请求字段/MCP server 构造读的是**本机另一份源码**（`~/ProjectMosire/AgentLibMosire`），**与 `~/.m2` 里的 `0.1.0-SNAPSHOT` jar 是否一致未核**。
2. **旧仓"非根节点 edge 写入丢失"**（A.4 隐患 3）是**结构推断**（`MapDiff` 无 `edges` + `applyDiff` 用 `base.edges()`），**未运行验证**。
3. **布局**（右下角无空位、右栏通高）来自 CSS 阅读，**未在浏览器实测**。
4. **demo 世界有没有 nation/army/决策人/带 tag 的区域**——**未验**（决定"决策人模式"打开是否为空）。
5. 旧仓 `river.js` 的历史：当前树无此文件，`git log` 该路径无记录 ⇒ 只能说"**在 git 历史中未找到**"，**不能说不存在过**（模块改名前路径未搜）。
