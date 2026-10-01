# 后端 + MCP 稳定化调查报告（W1–W8）

> 日期：2026-10-01
> 分支：`refactor/class-first-economy`
> 状态：**只读调查完成；未改生产代码、未改测试、未 commit 代码**
> 方法：静态代码阅读（`文件:行` 证据）+ 一个只读 Python 分区原型 + 外部检索；未跑 Maven、未启动服务、未做 MCP 运行时探针

---

## 0. 结论摘要

### 0.1 已具备

- 地图/Region/Unit/GOV/管辖/决策人/军事的命令面已经齐全；`simos.command.submit` 可到达全部 71 条已注册命令。
- 城市/聚落生成算法已经存在且是纯函数：`SettlementGenerator` + `SettlementParams.defaults()` + `TerrainView.of(map)`。
- 人口批次、class-first 经济、家户 actor 的播种器已经存在：`PopulationSeeder` / `EconomySeeder.plan` / `HouseholdSeeder.payload`。
- `WorldgenInitializeTool` 已经能把一条命令批（人口 + 城市 + 批次 + 经济 + actor）原子落盘，但它只认冻结配置里的 3 国。
- 政府创建已走 Unit 流程：`GovCreateOfficePlan` 的批序是 `unit.CreateUnit → unit.SetGovFormation → sd.CreateDecisionMaker → [provider/access] → sd.PutInfo`，没有第二套政府实体命令。
- GM MCP 口实有 **94 条工具 = 23 读 + 71 写**；所有写工具 `sensitive=true`、GM 面 `GmAutoApproveGate` 无脑过、决策人面仍走人工审批。

### 0.2 主要缺口

1. **任意 Region 的数据播种入口不存在**：除 3 个冻结国家外，GM 无法用一个 GM 工具给任意空白 Region 落人口/批次/城市/经济/家户；只能逐条 `simos.command.submit`，跨域无法同一条 revision。
2. **没有统一 clean gate**：`social.SetPopulation`/`SeedGroups`/`CreateCity` 对既有数据会覆盖/追加；只有 `economy.Seed`/`actor.Seed` 有按格占用拒。一键落盘必须由新工具在提交前做只读预检。
3. **没有城市生成器工具**：算法在，缺 GM 入参与独立 MCP 工具。
4. **没有省份划分器实现**：仓库零 `province/partition` 工具；`Region`/`map.CreateRegion`/Unit/GOV 已能承载落盘。
5. **`simos.gov.createOffice` 不包含 `unit.SetJurisdiction`**：新建 GOV 的管辖恒为空；政府读回工具也缺 `module/jurisdiction/level/superiorGov`。
6. **没有按格直接建军工具**：`unit.CreateUnit` + `sd.CreateArmy` 手工调用是两条 revision，可能半写；`raiseUnit` 必须有人口/粮饷前置。
7. **决策人 due/批量/汇总/审批在 MCP 面不完整**：没有 due-only 名单、批量派工、directives/results/docs/run-status 读口；审批仍 GUI-only，纯 MCP 回合会卡在 `sd.IssueDirective` 的人工审批。
8. **`DecisionMaker.allowedTools` 是死字段**：`DecisionCallerFactory` 用全局 `WHITELIST`，N9 静默失效。
9. **回合同步有缺陷**：`sd.AdjudicateTick` 无 preview、同一 tick 可能二次生效；`simos.advance` 不调用 `Shell.advanceAndDrain`，排队的 sd effect 不会被 drain。
10. **MCP 工具元数据不一致**：15 条 sd.* 窄写与 `actor.AdjustAccounts` 的资源声明不含实际写入的命名空间；58 条写工具无 preview；tool count/文档漂移。
11. **两处“假旋钮”**：`sd.AddCombatStage` 非首阶段静默忽略参数；`unit.SetFormationOffset` 已不影响任何计算，仅剩记账。
12. **数值风险**：`OfficePolicy.defaults()` 的粮按每人每 120 天分子，`GovDaily` 却按每 tick 直接乘（只对布按 365 折算）⇒ 默认粮耗可能大 120 倍；class-first 在极低人口/零人口格有硬失败路径。

---

## 1. 数据域初始化能力（W1）

| 数据域 | 权威状态 | 现有命令 | 现有窄工具 | class-first 是否真读 | 能按任意 Region 批量指定吗 |
|---|---|---|---|---|---|
| 逐格人口 | `SocialData.populations` | `social.SetPopulation` | 无 | 间接：日结算读 `groups`，无批次回退 series | 命令可多格；无 Region 展开、无 clean gate |
| 人口批次 | `SocialData.groups` | `social.SeedGroups` | 无 | 是（social 腿） | 可一命令多格；必须先有人口序列、无窄工具 |
| 城市 | `SocialData.cities` | `social.CreateCity/UpdateCity` | 无 | 否；只影响读取/城市语义 | 一命令一城；无生成/批量工具 |
| 家户 actor | `ActorData.actors/accounts` | `actor.Seed` | 无（`AdjustAccounts` 只改已有账） | 是（actor 账） | 可一命令多格；命令层不保证与 economy 同批 |
| class-first 经济 | `EconomyData.classFirst` | `economy.Seed` | 无 | 是（唯一生产权威） | 按 hex 占用拒；无窄工具 |
| Region | `GameMap.regions` | `map.CreateRegion/UpdateRegion` | 有 | 是（税收/管辖按 Region） | 可任意 hex 集 |
| 管辖 | `Unit.jurisdiction` | `unit.SetJurisdiction` | 有 | 是（有 GOV 读数时按管辖征税） | 整体替换多个 Region |
| Unit/GOV | `UnitState` / `Unit.module` | `unit.CreateUnit` / `unit.SetGovFormation` | 有 | 是（GovFormation 触发 gov 读数） | 单单位命令 |
| sd Nation/DM | `SdState` | `sd.CreateNation` / `sd.CreateDecisionMaker` | 有 | 否（不影响经济结算） | 有窄工具 |
| 军队 | `sd.Army` + unit 编制 | `sd.CreateArmy` / `unit.CreateUnit` | 有 | 否（当前不算战力） | 无按格直建组合工具 |

**关键事实**：`WorldgenInitializeTool` 是目前唯一把 `SetPopulation + SeedGroups + CreateCity×N + economy.Seed + actor.Seed` 放同一批的 GM 工具（`WorldgenInitializeTool.java:672-731`），但它只认配置里的 3 个 nation（`config/worldgen/v17levant-nations.json:128-228`；`WorldgenConfig.java:510-520`）。

---

## 2. 自动城市生成器（W2）

### 2.1 可复用资产

- `SettlementGenerator.generate(SettlementRequest, TerrainView, SettlementParams)`：纯函数、确定性、已有真实快照实测（`SettlementGenerator.java:83`）。
- `SettlementParams.defaults()`：全套默认系数 + germanic 城市词表（`SettlementParams.java:282`）。
- `SettlementRequest` 的 11 个分量：`region/displayName/totalPopulation/urbanizationRate/agrarianSurplusRate/commercialIntegration/politicalCentralization/seed/capital/hexes/documentedNames`。
- `WorldgenInitializeTool` 已证明五命令批可行：`SetPopulation → CreateCity×N → SeedGroups → economy.Seed → actor.Seed`（`WorldgenInitializeTool.java:672-731`）。

### 2.2 任意 Region 的缺口

- `SettlementRequest` 没有 request 级默认：`totalPopulation/urbanizationRate/agrarianSurplusRate/commercialIntegration/politicalCentralization/seed` 必须由 GM 给或写默认策略（`SettlementRequest.java:53-86`）。
- `WorldgenConfig` 只有 `NationSetup.params()` 间接取参数，没有 arbitrary Region 入口；`CapitalAnchor` 只有 name + OptionalLong，没有 hex（`CapitalAnchor.java:15-27`）。
- 城市、人口、批次没有命令级 clean gate；只有 economy/actor 按 hex 占用拒。
- `map.CreateCity` 不存在；`GameMap.cities` 与 `SocialCity` 是否需要同步未裁决。
- 全海洋/零承载力 Region 会在生成器入口直接抛 `IllegalArgumentException`（`SettlementGenerator.java:108-124`）；`shortfall > 0` 只报告、不阻止落盘。
- class-first 低人口路径可能硬失败：`EconomySeeder.planClassFirst` 在“有地但无对应家户成员”时抛 `IllegalStateException`（`EconomySeeder.java:1441-1450`）。

### 2.3 建议

- 只读工具 `simos.city.generate`（缺省 `preview=true`）→ 输出计划（逐格农村人口 + 城市表 + audit + shortfall + warnings）。
- `preview=false` 一键落盘；干净门通过才 `submitBatch`，固定命令序与 `WorldgenInitializeTool` 同源；目标 Region 已有任一相关记录 ⇒ `REJECTED` 具名、零 revision。
- 建议默认只落 `social`（人口 + 批次 + 城市）；`economy.Seed`/`actor.Seed` 作为显式开关（原因：低人口/无人口会硬失败，且经济种子必须与批次同源）。

---

## 3. 自动省份划分器（W3）

### 3.1 结论

仓库**零实现**；但几何与落盘原语足够。`HexCoord.neighbors()` / `HexGrid` / `Region.hexes()` 已提供六邻接与集合语义（`HexCoord.java:67,72`；`HexGrid.java:15-33,59-84`）。`map.CreateRegion` 只要求 id 唯一、hex 非空、每格在图上；域层明确允许 Region 重叠（`RegionOperations.java:40-63,123-136`）。

### 3.2 只读原型实测（Python，非 Java 生产实现）

在真档 `worlds/v17levant.json` 上实测：
- 大汉 650 hex 单连通；法蒂玛 702；德意志第二帝国 430。
- **递归主轴二分**：650 hex → 13 省，每省 50 hex，0 不连通，14.5ms。
- 多源 BFS：3.6ms，但大小 22–91，需容量修复。
- 容量封顶 BFS：10.8ms 后在“全簇已满 50 但仍有剩余格”卡死，17–77。
- k-means：34–61，超出 20–50 带。

**建议算法**：递归主轴二分 + 六邻接连通修复 + 确定性 tie-break（坐标自然序、轴枚举序 q→r→s→d）；首都圈可选单列；建议输出 `suggestedRegionId/name/center/hexes/hexCount/contiguous/warnings`。建议模式必须零写入。

### 3.3 一键落盘与硬门

固定命令序（全部走现有 GM 工具/命令，同批或固定分片，一条 revision）：
```text
map.CreateRegion × N
→ unit.CreateUnit × (N+1)
→ unit.SetGovFormation × (N+1)   // 省=PROVINCE+superiorGov=中央；中央=CENTRAL
→ unit.SetJurisdiction × N       // 每省纳入自己的省 Region
→ sd.CreateDecisionMaker × (N+1)
→ [sd.SetDecisionMakerProvider / sd.SetDecisionMakerAccess]
→ sd.PutInfo
```

**重要发现**：真实大汉 Region 已被 3 个外部 Region 相交（灰角伯国 2 格、区域27 2 格、区域33 1 格），法蒂玛也有 3 个。如果 clean gate 写成“任何相交 Region ⇒ 拒”，父 Region 自身就会命中、门恒拒；即使排除父，大汉也会因外部相交被拒。因此“相关元素”的精确定义需要用户裁定（见 §10）。

---

## 4. 政府创建 = Unit 流程（W4）

- `simos.gov.createOffice` 已是 app 级纯组合，只写 unit/sd，无政府实体命令；批序正确，但**缺 `unit.SetJurisdiction`**，新建 GOV 的管辖恒为空（`GovCreateOfficePlan.java:33-36,272-286`）。
- Agent 手动序列已核实：
  - 中央：`unit.CreateUnit → unit.SetGovFormation(level=CENTRAL) → unit.SetJurisdiction(regions=[] 或首都圈) → sd.CreateDecisionMaker`；
  - 省：`unit.CreateUnit → unit.SetGovFormation(level=PROVINCE, superiorGov=中央) → unit.SetJurisdiction(regions=[本省 Region]) → sd.CreateDecisionMaker`。
  - 顺序错由域层具名拒；分条提交要逐条更新 `expectedRevision`。
- `GovScope` 只按本级 jurisdiction 给直辖；`GovTerritory` 只做名义聚合、不进授权（`GovScope.java:23-55,94-143`；`GovTerritory.java:19-36,48-84`）。
- `GovLevel` 目前只是标签：CENTRAL 可带上级、PROVINCE 可无上级，缺跨字段硬校验（`GovFormation.java:20-22`；`UnitOperations.java:488-504`）。
- 缺 gov 读回：`unit.get/list` 不含 `module/jurisdiction/level/superiorGov`；`sd.decision-maker` 有 scope，但没有 GOV 结构视图。
- 无人口/无经济时建 GOV 本身不报错；**若 classFirst 为空，`GovDaily` 根本不跑**；staff>0 但无 actor 国库账时只发 `ADMIN_SUPPLY` 缺口。

---

## 5. 按格建军（W5）

- 直接建军今天没有组合工具；手工路径 `unit.CreateUnit → sd.CreateArmy` 是两条 revision、非原子，可能只落一半。
- `unit.CreateUnit` 必填 `id/name/member/equipment/speed/mobilityPerMille`；`position` 与 `parent` 至少给一个；`sd.CreateArmy` 必填 `armyId/rootUnitId/name`，`masterGovUnitId` 可选且必须是已有 GOV。
- `unit.CreateUnit` 不校验 position 是否在 `GameMap` 上；`member=0` 合法，但 `raiseUnit` 要求 manpower≥1。
- `sd.Army.masterGovUnitId` 与 `unit.ArmyFormation.masterGov` 无同步命令，两个“主子”视图会漂移。
- `raiseUnit` 必须有人口批次（MALE+ADULT）且来源总量足；无人口时推导期具名拒。它今天**不校验 jurisdiction**。
- 建议新增 GM-only `simos.unit.spawnArmy`：preview/apply、同一批 `unit.CreateUnit → [unit.SetArmyFormation?] → sd.CreateArmy → sd.PutInfo`，一条 revision 原子；保留 `raiseUnit` 为抽取组建。

---

## 6. 决策人调度 + 回合闭环（W6）

- `due = (now − 最近 Directive tick) ≥ decisionCadenceTicks`；从未有 Directive 恒 due（`SdQueryService.java:302-317`）。
- 没有 due-only 名单；`simos.sd.decision-makers` 输出 due 但不支持筛选（`DecisionMakersTool.java:50-53`）。
- `sd.RunDecision` 同步、逐人；GUI `/api/sd/run-decision` 异步 + run-status 轮询；MCP 无 run-status（`RunDecisionTool.java:103-148`；`GuiServer.java:1438-1520,1580-1618`）。
- GM MCP 缺 directives / decision-results / docs 读口（`SimosToolSource.java:273-281,469-511`）。
- **`allowedTools` 是死字段**：`DecisionCallerFactory.java:196-198` 用全局 `WHITELIST`；`DecisionAgentRunner.java:296-298` 用 `callerFactory.whitelist()`。N9 静默失效。
- 裁决链：
  - 参与令 = 同 tick 且非 `SUPERSEDED/CANCELLED`；可嵌令 = 注册面 − `sd.*` − `simos.command.submit` − 5 条 GmOnly（`DirectiveWhitelist.java:31-47`；`GmOnlyCommand.java:12-16`）。
  - 三道闸：白名单、`CommandTargets`、逐目标 scope（`AdjudicateTickTool.java:456-491`）。
  - 可裁决地图：`SetTerrain/CreateRegion/UpdateRegion/DeleteRegion/RandomizeRegion`；`SetEdge`/`RegisterPathwayGroup` 无目标声明，会被拒。
  - `sd.VoidAdjudication` 撤最新 revision 并回滚 map/unit/social；`sd.RejectDirective` 打回令、不动世界。
  - **同一 tick 不先 void 再裁会落第二条 `EFFECTIVE` 记录**，存在重复执行风险（`AdjudicateTickToolTest.java:480-505`，推断）。
- `simos.advance` 不调用 `Shell.advanceAndDrain`，排队的 sd effect 不会被 drain（`AdvanceTool.java:121`；`Shell.java:1085-1090`）。
- 建议 MVP：`dueOnly` 只读筛选 + `simos.sd.run-due` 批量触发（preview 0 rev；apply 逐 DM、非原子、汇总）；显式行动名册留后续阶段。
- allowedTools 最小修复：`DecisionCallerFactory` 增 `whitelistFor(DM)`；`permissionsFor` 与 `DecisionAgentRunner` 工具面都改用它；空名单语义建议与用户确认。

---

## 7. 对外 MCP 工具面稳定化（W7）

- GM 桶实有 **94 条 = 23 读 + 71 写**；精确集合由 `SimosToolsTest:181-283` / `McpPortTopologyTest:212-215` 冻结。
- 19 条命令已注册但无窄工具：`sd.SetDirectiveStatus`、`social.{SetPopulation,CreateCity,UpdateCity,SeedGroups}`、`economy.{AddDemand,CancelDemand,RegisterCandidate,Seed,SetMarketPrice,MigrateHousehold,TransferAssetShare,SwitchMode,GmAdjust,UnitBorrow,UnitRepay}`、`actor.Seed`、`unit.{RecruitStaff,DismissStaff}`。
- GUI 有但 MCP 无：审批队列 `/api/approvals`、LLM provider upsert/delete/test、`say`、directives、run-status；`sd.DecisionResults`/`sd.DecisionDocs` 只在决策人桶。
- 13 条工具已有 preview/dryRun；58 条直接 apply，其中 52 条窄写无 preview。
- 资源声明不一致：15 条 `sd.*` 窄写与 `actor.AdjustAccounts` 实际写 sd/actor，但 ResourceManifest 缺声明；`VoidAdjudication` 只声明 sd 而 `submitRestore` 跨域；`ForkTool` resources=NONE 但写分支/时间轴。
- 静默 no-op/空变更风险：`SdAddCombatStage` 非首阶段静默忽略；`UnitSetFormationOffset` 已无计算效果；单条 commit 不拒空 changeset。
- 测试缺口：**没有逐 GM 工具的真实 MCP `tools/call` 可达性测试**；现有读工具虽逐条调用但绕过了 `ToolCallAuthorizer`；preview 零 revision 只覆盖 levy/debt/raise。
- 建议：新增 `McpGmToolReachabilityTest`（真 Shell + 真 MCP client、表驱动、tools/list 与源码双向对齐、每工具最小合法调用 + committed 恰 +1 或具名拒 + 零 revision 断言）。

---

## 8. 外部检索结论（W8）

- MCP 工具名 1–128、`A-Za-z0-9_.-`、server 内唯一；input/output 用 JSON Schema；执行错误用 `isError:true`；readOnly/destructive/idempotent annotation 只是提示。
- 列表分页用不透明 cursor + `nextCursor`；长任务/批处理在 MCP 侧用 Tasks 扩展承载（实验性）。
- K8s server-side dry-run、Terraform plan/apply 是成熟的两阶段模式；幂等重试需显式 idempotency key（Stripe 24h + 参数比对）。
- 平衡连通 q-划分 q≥2 是强 NP-hard，只能用启发式；递归二分 / 多源 BFS 是常见做法；tie-break 必须用全序键 + 显式 seed，禁止依赖 Map/Set 迭代序。
- 外部协议级 batch/Tasks 不应成为 SimulatorMosire 的第二写入口：所有落盘仍走 `Command → ChangeSet → Revision`，一批一条 revision。

来源 URL 见 W8 子报告（S1–S14）。

---

## 9. 缺口清单（按依赖排序）

| # | 缺口 | 依赖 | 优先级 |
|---|---|---|---|
| G1 | 任意 Region 的数据播种工具 + clean gate | 无 | P0 |
| G2 | 城市生成器（建议 + 一键落盘） | G1 | P0 |
| G3 | 省份划分器（建议 + 一键落盘） | G1、G4 | P0 |
| G4 | `createOffice` 补 `unit.SetJurisdiction` + gov 读回 | 无 | P0 |
| G5 | 直接建军 `simos.unit.spawnArmy` | 无 | P1 |
| G6 | `allowedTools` 运行时生效（N9 修复） | 无 | P0（决策人回合前置） |
| G7 | due-only / run-due / directives/results/docs 读口 | G6 | P1 |
| G8 | 审批 list/decide MCP 化 + run 锁统一 | G7 | P1 |
| G9 | adjudicate preview、防二次生效、advance drain | 无 | P1 |
| G10 | MCP 工具元数据对齐 + 逐工具可达性测试 | 无 | P0 |
| G11 | 假旋钮清理（SetFormationOffset / AddCombatStage 静默参数） | 无 | P2 |
| G12 | GovDaily 粮耗口径 120× 风险 | 无 | P1（数值） |
| G13 | `AdjudicateTickTool` 的资源 manifest 只声明 map/social/unit/sd，但 DirectiveWhitelist 允许 `actor.Seed`/`economy.Seed` 等可嵌令 ⇒ 这些令会被静默拒；需裁定决策人令是否允许写 economy/actor | G6 前 | P1 |
| G14 | `CommandSubmitTool`/`AdvanceTool` 的动态/多命名空间资源未建模；timeline 面无法用现有资源 SPI 表达 | 无 | P2 |

---

## 10. 需要用户裁定（调查后发现的新问题）

1. **clean gate 的“相关元素”定义（城市）**：目标 Region 内下列任一是否存在就拒？
   - 人口序列（包括 0 值）、PopulationGroup、City、economy 占格、actor 账。
   - 建议：**存在记录即拒**（即使数值为 0/空账也算“已有元素”），因为 0 批次与空账是刻意状态。
2. **clean gate 的“相关元素”定义（省份）**：
   - 真档大汉已被 3 个外部 Region 相交；若严格“任何相交 Region ⇒ 拒”，大汉/法蒂玛等会直接被门挡住。
   - 选项：A 严格任何相交；B 只拒**完全落在目标 Region 内的子 Region**与其他省份痕迹（推荐）；C 列出相交并要求显式 override。
3. **城市一键落盘默认范围**：
   - A 只落 social（人口 + 批次 + 城市）；economy/actor 用显式开关（推荐，低人口 class-first 会硬失败）；
   - B 默认一起落 social + economy + actor（需要最小人口预检）。
4. **省份一键落盘是否同时建 N+1 个决策人**：建议是（每个 GOV 绑一个），但允许关闭。
5. **中央 GOV 的 jurisdiction**：建议空管辖 `regions:[]`（只授 own 格 + 自己）；若确需首都行政，单独建首都圈 Region。
6. **直接建军语义**：建议允许无人口/无国库凭空创建（GM 特权），但 hex 必须在地图上、member≥1；`masterGov` 建议只写 `sd.Army.masterGovUnitId`（避免与 unit 侧漂移），若要双边同步则同批写 `unit.SetArmyFormation`。
7. **allowedTools 空名单语义**：
   - A 空 = 沿用全局 WHITELIST（兼容旧档/夹具）；非空 = WHITELIST ∩ allowedTools（推荐，破坏性最小）；
   - B 空 = 无工具（严格 N9，会破坏现有空名单旧档与夹具）。
8. **“本 tick 必须行动”实现**：建议 MVP = 只读 due 筛选 + `simos.sd.run-due` 批量触发，不新增世界态名册；显式行动名册留后续。
9. **审批 MCP 化**：是否允许外部 GM MCP 口直接 list/decide 审批队列？用户方向是“一切 GM 工具”，建议是；需要确认安全/审计口径。
10. **preview 标准化范围**：建议生成器/初始化/组合工具必须 preview；52 条 primitive 窄写可保持 apply-only（靠 `expectedRevision` + 批原子），不全量加 preview。
11. **假旋钮**：`unit.SetFormationOffset` 与 `sd.AddCombatStage` 的静默参数建议“删除/具名拒绝/降级为显式记账”，请选一个方向。
12. **GovDaily 粮耗 120× 风险**：建议先做一条数值对拍，再决定是修 formula 还是改默认 policy；需要确认是否纳入本轮 P0/P1。

---

## 11. 未验证 / 做不到

- 未跑 Maven、未编译、未运行测试、未启动服务、未做真实 MCP `tools/call` 探针；全部为静态阅读 + 只读脚本。
- 省份分区数字来自 Python 只读原型（读 `worlds/v17levant.json`），不是 Java 生产实现，未跨 JVM 复现。
- 未验证真实 store 上已有 GOV/jurisdiction 的现状；`GovDaily` 空人口/无国库行为来自源码推导。
- 未验证 `McpCoverageTest` 之外的实际 MCP 报文大小与 55/131 条批命令的传输限制。
- 未逐篇精读外部资料；部分 URL 来自搜索聚合页，未逐一打开验证。
- 未验证 `AdjudicateTick` 二次生效在真实世界上的数值后果；未验证 `advance` drain 缺口的实际影响。
- 未验证 `OfficePolicy.defaults()` 的 120× 粮耗是否真的在真实档上发生；只从常量与 `GovDaily` 代码推断。

---

## 12. 用户裁定结果（2026-10-01）

本章是用户对 §10 十二项的最终答复，优先于 §10 的建议：

1. 城市/省份工具检测到目标区域已有相关数据时，不默认硬拒死；新增“一键清空”工具（暂名 `simos.region.clear`），并在检测结果里由 Tool 明确提示清空当前区域所有相关数值后重试。清空只处理需要清的相关数值；清空后一键落盘才可执行。
2. **区域重叠 ≠ 省份相关**：取选项 **C**。列出所有相交 Region（id/name/tag/相交格数），要求显式 override；不能仅凭重叠就判为已有省份（目标 Region 本身可能不是省份）。
3. 城市一键落盘默认取 **B**：`social + economy + actor` 一起落；但必须有最小人口 / class-first 可行性前置检查，不可行时具名拒绝并提示降级开关。
4. 省份一键落盘**同时创建 N+1 个决策人**（每省 1 + 中央 1），允许参数关闭。
5. 中央 GOV 默认使用**独立首都区 Region**；先建首都区、GM 视实际情况再决定是否通过 `unit.SetJurisdiction` 扩大为大首都辖区。
6. 直接建军允许无人口、无国库（GM 特权）；**后续赋值/调整操作也要有**（member/equipment/position/masterGov 等），并补齐 army 归属与 unit.Gov 关系。
7. `allowedTools` 语义取 **A**：空 = 全局 `WHITELIST`；非空 = `WHITELIST ∩ allowedTools`。
8. **不做自动筛选/自动派出**：GOV 决策人要有行政能力才允许行动（当前未体现），军队无令理论上不能行动；本 tick 行动名单由 **GM Agent 显式指定**。`due` 只作只读展示，不作为行动触发条件。
9. 审批 MCP 化：**允许**外部 GM MCP 口 list/decide（GM-only、审计留痕）。
10. preview：生成器/初始化/组合工具必须 preview；并加**图像渲染确认**（可返回地图渲染资产或先调 `simos.map.render`）。primitive 窄写可保持 apply-only + `expectedRevision`。
11. 假旋钮解释：`unit.SetFormationOffset` 已不参与移动/战斗计算（纯记账、改了不生效）；`sd.AddCombatStage` 非首阶段会静默忽略 `combatStateId/hex`（看似记录、实际不生效）。**用户裁定：两处都改为具名拒绝**。
12. **经济真实长跑/数值校准本轮不做**：`GovDaily` 粮耗 120× 风险只记录，不在本轮做经济真实性测试；等国家初始化与 MCP 闭环稳定后再单独排期。

用户最终补充裁定（同日后续）：
1. “一键清空”拆成 `clearData`（只清 social/economy/actor 数值与关联记录）与 `clearStructures`（清省 Region、省/中央 GOV、对应决策人等生成器结构），二者分别 preview/apply、分别确认。
2. 首都区默认半径 1（7 格）；GM 后续可用 `unit.SetJurisdiction` 扩大。
3. 假旋钮已裁定：`unit.SetFormationOffset` 与 `sd.AddCombatStage` 的无消费点语义改为**具名拒绝**；不阻塞 P0–P2，排在 P8。
4. P9 允许适当跑几个 tick（建议 3–10 tick 烟测），不做一年长跑与经济真实性校准。
