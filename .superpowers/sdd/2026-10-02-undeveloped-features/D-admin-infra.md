# D 线调查报告：批提交 / 迁都 / 区划 / 账目 / 地址归一

> 只读调查（未跑 Maven、未改代码/文档/世界、未调写接口）。基线 `aff48be9`；运行世界快照 head126 / tick120。
> 仓内证据一律 `相对路径:行号`；世界侧文件用绝对路径并标「运行世界事实」。文档措辞全部回代码核过（AGENTS.md §四）。
> 结论分三类：**确认缺失 / 部分具备 / 已具备等价能力**；不确定的进末节「我没做/没验证的」。

## 结论速览

| 条目 | 结论一句话 |
|---|---|
| D1 GM 通用批提交 | **部分具备**：Core 已有公开 `submitBatch` 且 23 个组合工具在用；缺 GM/MCP 通用工具；批路径不落事件链 |
| D2 迁都组合工具 | **确认缺失**；6 步 workaround 可用但非原子；国库只支持 grain/cloth/money；城市 `at`、人口批次不可搬 |
| D3 省界合并/拆分/重划 | **确认缺失**；`map.UpdateRegion` 只改 map 域且允许重叠；下游 jurisdiction/城市/税率/编制要显式重算 |
| D4 单格命名 | **确认缺失**；只有 1 格 overlay Region workaround；`HexCell`/`GameMap` 无 hex 级 name/label 字段 |
| D5 `actor.MoveAccount` | **确认缺失**；`actor.RemitGovTreasury` 是「三资源 + 显式金额 + 可支配校验」的部分替代 |
| D6 canonical 地址归一化 | **部分具备**：`UnitId.parse`/`RegionId.parse` 已收前缀；`actor.*` owner 与若干工具入口仍裸值 |
| D7 顺带核实 | `region.clearData`/`clearStructures`/`province.apply` **已具备等价能力**（组合工具样例）；`sd.DeleteNation` **确认缺失**（属 C 线，一句带过） |

---

## D1.GM 通用原子批提交 `simos.command.submitBatch`

- **结论：部分具备**（Core 机制已具备并在生产使用；**MCP/agent 工具面确认缺失**；批路径审计有已知缺口）。
- 证据：
  - 单条提交一次一 revision：`simos-app/src/main/java/io/mosire/simos/app/tools/write/CommandSubmitTool.java:96-110` 构造**一个** `CommandEnvelope`（`commandId=correlationId=UUID`）→ `core.submit(command)`；schema 只有 `type/payloadJson/branch/expectedRevision` 四键（`:62-69`）。
  - 批量机制早已存在且是 Core 公开 API：`simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java:265-306`（`public BatchResult submitBatch(List<CommandEnvelope>)`），类注明写「本方法是 Core 的公开 API（给组合根 simos-app 调用），不是命令」（`:239`）；`simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java:207-212` 转发。
  - 一条 revision 的落盘：`CommandBus.java:428-441` `commitBatch` 只建**一行** `RevisionRow`，`command_type = BATCH_COMMAND_TYPE`（常量 `"core.SubmitBatch"`，`:94`）；批内命令的 handler 在锁内对**累积候选态**逐条执行（`:360-420`），任一条被拒 ⇒ `BatchResult.Rejected`、零 revision（`:407-410`）；head≠expected ⇒ `BatchResult.Conflict(真实 head)`（`:300-303`）。
  - **`core.SubmitBatch` 是什么**：它不是第 4 种 `Command`，是**批提交落盘时的 revision 行标签**，与 `core.Bootstrap`/`core.ForkBranch`/`core.RestoreChangeSet` 同制（`CommandBus.java:90-94`、`:96-105`）。生产里由组合工具调用，例如 `WorldgenInitializeTool.java:461`、`ProvinceApplyTool.java:568`、`RegionClearDataTool.java:249`、`RegionClearStructuresTool.java:289`、`AdjudicateTickTool.java:397`（全仓 23 处 `core.submitBatch`，见 `rg '\.submitBatch\(' simos-app/src/main/java`）。
  - 运行世界事实：`/home/cna/simos-testspace/worlds/dashu-v2/store/simos.db` 的 `revisions` 表 `(main,2,parent main/1,tick 0,initiator agent:external-mcp,command_type core.SubmitBatch)`，其 changeset 含 social/economy/actor/map/sd/unit 六命名空间——**与 `WorldgenInitializeTool` 的固定批形状一致**（六命名空间、批内 `map.UpdateRegion`→`sd.CreateNation`→`unit.CreateUnit`→`unit.CreateCommandChain`→`sd.CreateArmy`，类注 `:94-112` + `:461`）。rev76 的 initiator 是 `decision-maker:...-dm`，来自 `sd.AdjudicateTick` 把一个 tick 的令 + 状态翻转 + 结果条目合成一批（`AdjudicateTickTool.java:380-405`）。
  - 工具面缺失：`/home/cna/simos-testspace/worlds/dashu-v2/logs/server.log:3` 的 MCP 外发工具清单（111 条）只有 `simos.command.submit`，无 `simos.command.submitBatch`；`logs/command_catalog.txt:1` 的 78 个命令类型里没有任何 `core.*`（catalog 只列已注册 handler，`CatalogTool.java:20-21`）。代码侧 `SimosToolSource.java:363-367` 的 `addGenericWrites` 只加了 `submit/advance/fork`，且只被 `case GM` 调用（`:328-343`）。
  - 审计缺口（代码事实）：单条 `commit` 落 `received+committed` 事件（`CommandBus.java:716-733`、`:752-765` 的 `payloadDigest`），批路径 `commitBatch` 只 `timeline.appendRevision(row)`、**不写事件行**（`:428-441`；同类证词 `AdjudicateTickTool.java:117-120`：「批不发事件链……事件表查不到」）。批行身份取**首条命令**的 `commandId/correlationId/initiator`（`CommandBus.java:425-437`），批内其余命令的 initiator 即使不同也不落盘。
- 现状可用 workaround：GM 只能逐条 `simos.command.submit`，每条各自 `expectedRevision`；要原子组合只能请人写/用已存在的组合工具（`simos.province.apply`、`region.seed/clearData/clearStructures`、`gov.*`、`spawnArmy`、`army.assignGov`、`simos.sd.run-decision-makers` 等），不能由外部 MCP 自选命令数组。
- 若实现（建议）：
  - 工具名 `simos.command.submitBatch`，落在 `SimosToolSource.addGenericWrites`（`SimosToolSource.java:363-367`，仅 `Role.GM`）。
  - 载荷对齐现有风格：`{commands:[{type,payloadJson}...], branch?(缺省 main), expectedRevision(必填), reason?(仅回显/审计线索)}`。每条 envelope：`commandId=UUID`、`correlationId=batchId`（一个调用一个）、`initiator=工具注入值`；branch/expected 取顶层（`CommandBus.submitBatch` 要求全批一致，`:273-290`）。`payloadJson` 保持字符串，与 `simos.command.submit` 同形（Core 要求逐字节不透明文本，`CommandEnvelope.java:10-13`）。
  - 校验：commands 非空、长度上限（建议 100-200；批在 `commitLock` 内跑完，`:292-305`，长批会挡住所有写者）；type 非空且在注册面内（未注册由 `submitBatch` 逐条返回 `未注册的命令类型`，`:372-377`，工具可预检给 BAD_REQUEST）；branch/expected 无缺省 expected。
  - 并发/冲突语义直接复用：同批全共享 branch+expected（不一致 ⇒ `IllegalArgumentException`，工具折 BAD_REQUEST）；head 不符 ⇒ CONFLICT + 真实 head，零 revision；分支不存在 ⇒ REJECTED；失败原子性由 `submitBatch` 结构性给出。
  - 权限：GM 桶（与 `simos.command.submit` 同）；`ToolSpec.level(DEFAULT,true,false)` + `ToolGate.Ask(SENSITIVE)`，资源 `ToolSupport.ALL_WRITE`（照 `CommandSubmitTool.java:72-93`）。**不要**进决策人桶（`addDecisionAgentWrites` 只两条，`SimosToolSource.java:579-583`；测试断言 `SimosToolsTest.java:812-833`）。它不是命令类型，故无 `GmOnlyCommand` 问题——GM 本来就能经 `simos.command.submit` 提交任何注册类型（含 GmOnly）。
  - ChangeSet/Codec/往返不变式：**无新状态类型、无新命令**，只调用既有 `submitBatch`；七个 codec 都实现 `ModuleDiffer`（`MapCodec.java:53`、`SocialCodec.java:34`、`UnitCodec.java:48`、`ActorCodec.java:60`、`SdCodec.java:48`、`EconomyCodec.java:117`、`GovCodec.java:45`），派生了多命名空间 changeset 的条件已具备（`CommandBus.java:412-419`）。
  - handler 注册与 catalog：不需要新 handler；catalog（`simos.command.catalog`）不变（工具名不是命令类型）。MCP 工具面名单与计数断言要改：`SimosToolsTest.java:352-383`（NON_NARROW_WRITE_NAMES）、`:812-827`（GM 桶 `hasSize(94)`）、`McpServerTest.java:100-131`、`McpPortTopologyTest.java:85-100`；启动日志「外发工具 N 个」随之 +1。
  - 前端/API：无需改；GUI `/api/command` 仍是单条（`GuiServer.java:1406-1421`），可选后续加批量端点。
  - 审计：建议分两步——先按现状返回 `batchId + 逐条 outcomes + submission.ref`（与组合工具同制），并**在文档里写明批不落事件**；再单独评估是否给 `CommandBus.submitBatch` 补 per-command 事件（`received`/`committed` + payloadDigest），这是 Core 改动、会影响既有 23 个组合工具的审计面，需另立任务。
- 依赖与风险：批内慢 handler 全程占 `commitLock`（`:244-246`）；通用 type 自选会把「组合工具才守的跨模块约束」交给调用方，建议定位为 GM 逃生口而非替代既有语义工具；批审计弱于单条（见上）。
- 未核实项：rev2/3/4 到底由 `simos.worldgen.initialize` 还是别的组合工具产生（按批形状与名字空间推断，未逐条比对 tool 调用日志）；`submitBatch` 与 `MutationGuard`（A6）在批路径的实际交互未跑测（代码上逐条对候选态调用，`:380-391`）。

---

## D2.迁都组合工具 `simos.gov.moveCapital`

- **结论：确认缺失**（无工具/命令；分步 workaround 部分可用，非原子；两个硬缺口：城市 `at` 不可变、人口批次读/写缺）。
- 证据（逐 workaround 步核）：
  1. **改首都区 hex**：`map.UpdateRegion` 只改目标 Region 的 `hexes`/`meta`，且 `meta` 是整体替换——`RegionOperations.java:78-98`；`MapPayloads.java:136-150` 对缺省子键填 `null`，`MapUpdateRegionTool.java:14-18` 明写「只给一个键会静默清掉其余三个」。区域重叠**允许**（`RegionOperations.java:23-27`、`:69`），没有任何跨区域排斥校验。
  2. **改城市名/归属**：`social.UpdateCity` 只认 `name/props/region`（`UpdateCityHandler.java:62-86`）；`SocialCity` 没有 `withAt` 之类入口（`SocialCity.java:69-88`），`UpdateCityHandler` 也不解析 `at` ⇒ **`at` 不可变**。新建点 `social.CreateCity` 明确拒收 `population`（`CreateCityHandler.java:32-35`、`:69`）。
  3. **搬中央 GOV**：`unit.PlaceAt` 存在（`PlaceAtHandler.java:41-56`；领域实现 `UnitOperations.java:204-220`，顺带清路线，无 map 存在性/区域隶属校验）。
  4. **搬国库**：`actor.RemitGovTreasury` 载荷 = `fromUnitId,fromQ,fromR,toUnitId,toQ,toR,grain?,cloth?,money?,reason?`（`RemitGovTreasuryHandler.java:30-42`）；源账**必须已存在**、逐资源走 `AvailableStock.available`（余额−冻结）判足量、目标缺账五参新建、只动 `accounts` 表（`:192-268`、`:270-325`）。**只支持 grain/cloth/money 三键**（`:87-90`、`:216-219`），金额必须调用方先读出并显式给。窄工具 `simos.gov.remit` 用「当刻 `effectivePosition`」折源/目标坐标（`GovRemitTool.java:206-224`、`:253-277`）。
  5. **辖权**：`unit.SetJurisdiction` 整份替换 region key 集、保留旧区域税率、新区域从 0 起（`SetJurisdictionHandler.java:23-38`、`:59-79`；`UnitOperations.java:382-431`）。
  6. **审计**：`sd.PutInfo` 可写任意 canonical 地址（`PutInfoHandler.java:78-95`），只是感知层记录。
- **账键与「账随 GOV 走」**：
  - 账键确为 `(owner, location)`：`GoodsAccountKey.java:7-12`、`:42-57`；`owner` 是 `ActorRef`（GOV 用 `(UNIT, unitId)`，`RemitGovTreasuryHandler.java:347-350`）；location 是 `HexCoord`。
  - **账不会随位置自动走**：存量账按旧坐标留在旧 key；只有**日税入账**按 GOV 当刻有效位置现算 key（`JurisdictionDailyTax.java:170-176`）。⇒ 不搬账就迁都，会把库存留在旧格，而新税流进新格的新账。
- **原首都区 hex 归谁管**：没有「hex 唯一省籍」；归属 = 各 `Region.hexes` 的**多对多**集合（`RegionIndex.java:14-23`、`:42-62`），`MapResolver.regionOfHex` 只按定义序排列、末位称「最顶层」（`MapResolver.java:133-160`），不承担归属判定。改一个 hex 必须把**所有**包含它的 Region 的整份 hex 集重写；否则会与 `西陵__P01` 重叠。重叠若两省税率都 >0，`JurisdictionDailyTax` 会逐 GOV 各征一遍（`:132-226`，无跨 GOV 去重；`chargedHouseholds` 只做计数 `:219`）。
- 现状可用 workaround（世界日志同款，运行世界事实 `/home/cna/simos-testspace/worlds/dashu-v2/logs/dashu_policy_feasibility_20261002.md:87-94`）：`map.UpdateRegion`×2 → `social.UpdateCity`×2 → `unit.PlaceAt` → `actor.RemitGovTreasury` → `unit.SetJurisdiction`（region id 不变时可省）→ `sd.PutInfo`。它可用但非原子，且**旧都 13,756 城镇人口搬不动**（缺批次读口/迁移命令；`PopulationGroup.residence` 设计上可换、`SeedGroupsHandler` 覆盖语义要求目标格已有 `populations` 序列，`SocialData.java:90-95`；`simos.social.population` 只出聚合量不出批次 id，`PopulationTool.java:14-27`）。
- 若实现（建议）：
  - 组合工具名 `simos.gov.moveCapital`（GM-only，写 map/social/unit/actor/sd）。
  - 载荷（对齐现有风格）：`{govUnitId(必填), capitalHex{q,r}(必填，须在图上), capitalRegionId(必填；目标首都区), sourceRegionIds[]?(缺省=所有当前含有该 hex 的非目标 Region), cityId?(已有城市；给了才改), oldCityId?/oldCityName?, treasuryMode: "REMIT_THREE"|"MOVE_ACCOUNT"?, expectedRevision(apply 必填), branch?, reason(必填), preview(缺省 true)}`。
  - 最小原子面（一条 `submitBatch`、恰一条 revision）：`map.UpdateRegion`×所有受影响 Region + `social.UpdateCity`×（新都/旧都归属改名）+ `unit.PlaceAt`(中央 GOV)+ `actor.MoveAccount`（D5；未实现前退化为一条 `actor.RemitGovTreasury` 搬 grain/cloth/money，并具名报告其余键未搬）+（region id 改变时）`unit.SetJurisdiction` + `sd.PutInfo`。这些**必须在同一批**：任一单独先落都会经过一个「区域/GOV/账/城市互相不一致」的窗口，且并发 `simos.advance` 一旦插进中间会把税收到错的国库 key；`expectedRevision` 冲突保证整批重试而不是半成品。
  - 实现落点：新建 `simos-app/.../tools/write/GovMoveCapitalTool.java` + `GovMoveCapitalPlan.java`（照 `ProvinceApplyPlan` 模式：纯函数 pre-scan + 固定批序），注册在 `SimosToolSource.addGmWrites`；不改 Core、不加命令类型、不进 catalog；MCP 名单独具 + 工具计数断言同上。
  - 资源声明：map/social/unit/actor/sd 五 namespace UNRESTRICTED（GM 侧 unlimited），需同步 `SimosToolsTest`/`McpPortTopologyTest` 名单。
- 依赖与风险：依赖 D5 才能「搬全部账」；依赖人口迁移（另一条线）才能搬旧都人口；hex 从源省移除的选择若默认「所有当前属主」可能动到无意区域，需 preview 明示；重叠区间的双税风险必须由工具保证互斥（或把源省税率显式置 0）。
- 未核实项：世界 head126 下 `西陵__CAP` 与 `西陵__P01` 的确切 hex 集/税率（`dashu_policy_feasibility_20261002.md:21-24` 只给摘要；未重放当前状态逐格核）；`unit.PlaceAt` 对无位置 GOV 的时间段边界行为未跑测。

---

## D3.省界修改/合并/拆分 `map.MergeRegions` / `map.SplitRegion` / `map.ReassignHexes`

- **结论：确认缺失**（三个命令/工具全无；`map.UpdateRegion` 是唯一的现成原语，且只覆盖 map 域）。
- 证据：
  - 现有能力只有整体替换：`UpdateRegionHandler.java:59-71` 解析 `regionId/hexes?/meta?` → `RegionOperations.updateRegion`（`RegionOperations.java:78-98`）；不存在按 hex 增删/合并/拆分的语义命令（`logs/command_catalog.txt:1` 的 78 个类型 + 全仓 rg 均无 `MergeRegions`/`SplitRegion`/`ReassignHexes`）。
  - **单个 CommandHandler 只能改自己命名空间**：`HandlerOutcome.Applied(ChangeSet)`，commit 时 `new WorldChangeSet(Map.of(namespaceOf(envelope.type()), changeSet))`（`CommandBus.java:716-725`；同注 `:311-314`：撤销跨命名空间因此必须走 Core 的 `submitRestore`）。⇒ 语义级「改界 + 重算辖权/城市/税率」**不可能**由一条 `map.*` 命令完成，必须是 app 组合工具批（或 Core 特批，后者不推荐）。
  - 重叠是正常态：`RegionOperations.java:23-27`；`GameMap` 只有 `terrainBlocks` 的分割不变式（`GameMap.java:60-102`），**region 之间没有 partition/互斥不变式**。`map.DeleteRegion` 也没有引用检查，只有 `RegionDeleteGuard` 拦「带 `nation:` tag / 是 Nation.homeRegion」（`RegionDeleteGuard.java:56-66`）。
- **换 hex 后下游漂移清单（存储位置 + 校验点 + 是否自动跟）**：
  1. **省 GOV 辖权**：`Unit.jurisdiction.taxRatePerMilleByRegion`（第 15 组件，`Unit.java:60-61`）+ `GovFormation`（`GovFormation.java:31-61`）。写入点 `UnitOperations.setJurisdiction`（`:382-431`；存在性校验 `:404-411`）；替换粒度是整个 region key 集，**不会**因区域 hex 变化自动增删 key。同一 region id 下换 hex：辖权 key 不变 ⇒ 税收/需求自动跟着新 hex 走；换成新 region id（拆分）：必须显式 `unit.SetJurisdiction`，否则新区域无人管。
  2. **城市 `region`**：`SocialCity.region`（`SocialCity.java:37-41`，`Optional`，可为空）；写入口 `social.UpdateCity`（`UpdateCityHandler.java:62-86`）**不校验 region 是否存在**（没有 map 访问），且 `targetPaths` 为空（`:36-49`）⇒ 受限决策人经裁决路径会被 fail-closed 拒。消费方：GUI 分组/过滤（`ApiViews.java:391-403`）、`social.ClearRegion` 命中（`ClearRegionHandler.java:101`）、`ProvinceAssignCitiesPlan` 归省（`:133`）、`RegionSeedPlan` clean gate（`:427`）。它**不参与税收/需求公式**（税收按 region hexes + `populationAt(hex)`，`JurisdictionDailyTax.java:180-222`；需求按城市 `at()`，`GovDemand.java:96-102`）。
  3. **税率键**：就是 jurisdiction 的 key 集（`Jurisdiction.java:34-45`）；`unit.SetTaxRate` 要求 key 已在辖内（`SetTaxRateHandler.java:20-25`；`UnitOperations.setTaxRate:441-474`）。删/并 region 后旧 key 变悬空：`JurisdictionDailyTax` 记 `Gap.regionMissing` 并跳过（`:158-169`），`GovDemand.of` 对查无 Region **静默跳过**（`:73-77`）⇒ 不会崩，但税收/需求会静默消失或错配。
  4. **`GovDemand` 配编**：需求是纯函数现算（`GovDemand.of(map,social,unit):57-92`），每 tick 由 `GovDaily.java:151-152` / `ClassFirstPopulationEconomyTimeParticipant.java:444-445` 算效率；**编制人数是存量**，改界后不会自动重配，需要 GM 再跑 `simos.gov.applyStaffing`（它同样现算 `GovDemand`，`GovApplyStaffingTool.java:228-252`）。
  5. **`unit.SetGovSuperior` 的中央/省关系**：住在 `GovFormation.superiorGov`（`GovFormation.java:16-32`），与 hex 无关；写入口 `UnitOperations.setGovSuperior:589-...`（自环/成环校验）。只有**拆分出新省 GOV 时**才需要同批建 GOV + SetGovFormation + SetGovSuperior。
  6. **Nation.homeRegion / 删区门**：`sd.Nation.homeRegion` 只是 RegionId（`CreateNationHandler.java:44-58` 要求带 `nation:` tag）；`RegionDeleteGuard` 挡住删 homeRegion/nation tag 区域（`:56-66`）。并/拆若删了 homeRegion，必须拒绝或显式迁移。
  7. **访问范围（自动派生，但有安全含义）**：`NationScope` 按 `nation:` tag 区域的 hex 集配 social/unit/map 前缀（`NationScope.java:76-90`），`GovScope` 按 jurisdiction 区域 hex 集配前缀（`GovScope.java:110-127`）。扩一个区域的 hex = 立即扩大对应决策人的可见/可写面；工具应把这点写进 preview。
  8. **派生件自动跟**：`Region.boundary` 构造期重算（`Region.java:35-40`）；`regionIndex()` 每次重算（`GameMap.java:170-178`）；`/api/map/hex` 的 regions（`MapResolver.regionOfHex:144-160`）；`mapHex` 视图（`ApiViews.java:4959-4963`）。**不需要**迁移；也不该缓存（两类 javadoc 明令）。
- 现状可用 workaround：对每个受影响 Region 手工 `map.UpdateRegion`（整份 hex 集）+ 对每个 GOV 手工 `unit.SetJurisdiction`（显式决定税率合并策略）+ `simos.province.assignCities`（按 hex 自动选省，`ProvinceAssignCitiesPlan.java:130-145`）+ 可选 `simos.gov.applyStaffing`；多 revision、非原子、中途失败留漂移。`simos.province.apply` 的 R2a 接缝也明说不改 `SocialCity.region`，要另调 `simos.province.assignCities`（`ProvinceApplyPlan.java:84-86`、`SimosToolSource.java:437-441`）。
- 若实现（建议）：
  - 做 **app 级组合工具**，name 建议 `simos.region.merge` / `simos.region.split` / `simos.region.reassignHexes`（与 `simos.province.apply` 同族），**不**新增 `map.MergeRegions` 命令：后者只能改 map 域，且 `map.*` 非 sd/非 GmOnly 会自动进「可嵌令 / RegisterEffect」白名单（`Shell.java:571-580`），给决策人开一条改界口子，除非再标 `GmOnlyCommand`。
  - 每个工具一个纯推导 Plan + 固定批序（照 `ProvinceApplyPlan` / `RegionClearStructuresPlan`）：`map.CreateRegion`（拆出的新 region）→ `map.UpdateRegion`（目标/源整份 hex）→（可选）`map.DeleteRegion`（被并入的源 region）→ `unit.SetJurisdiction`（每个受影响 GOV，显式税率策略）→ `unit.SetGovSuperior`/`unit.CreateUnit`+`SetGovFormation`（拆省需要新 GOV 时）→ `social.UpdateCity`（按 `at` 落在变界 hex 上的城市改 region，或复用 `assignCities` 的选择规则）→ `sd.PutInfo`。全批共享 batchId/branch/expectedRevision ⇒ `submitBatch` 一条 revision。
  - **必须由工具显式裁决、不能默认猜**：① 合并时同一 GOV 对多个源 region 的税率不同 ⇒ 需 `ratePolicy(MAX|TARGET_WINS|REQUIRE_EQUAL)`，否则拒；② 源 region 被删时其 GOV 若因此无辖权 ⇒ 拒或要求显式 `disbandGov`；③ 城市 `region` 是跟 `at` 重算还是保持；④ 重叠 hex 必须保证同一批内从所有非目标省移除（否则双税）。
  - ChangeSet/Codec/往返：无新状态类型/命令，沿用既有六域 codec+differ；无 catalog 变化。工具名单/计数断言与启动日志同上。GM-only、preview/apply 与 `conflictPreflight` 照现有组合工具。
- 依赖与风险：批全程占 `commitLock`，`social.UpdateCity` 条数可能很多（真档 21 城/国级别尚可，大区要设上限）；错误的重叠/悬空 jurisdiction 不会当场炸（静默 gap），靠工具保证；拆分出的新 region id 命名需调用方给（`map.CreateRegion` 不自增，`RegionOperations.java:29-30`）。
- 未核实项：真档 252 Region 的规模下批内 `map.UpdateRegion` × N 的耗时/锁占用（未跑）；是否存在依赖「regionId 字典序/定义序」的调用方会被新增 region 打乱（`MapResolver` 明说定义序影响「最顶层」，未逐调用方核）。

---

## D4.单格命名（铁门坎 `(34,-55)`）

- **结论：确认缺失**（无 `map.NameHex`/`SetHexLabel`/`UpdateHex`；`HexCell` 无 name/label，`GameMap` 无 hex 级 meta 组件）。
- 证据：
  - `HexCell` 只剩 height（`HexCell.java:3-16`）；`GameMap` 9 个组件 = hexes(高度)/terrainBlocks/regions/cities/terrainTypes/pathways/pathwayGroups/edges/spec（`GameMap.java:60-102`），**没有任何 hex→label/name 映射**；`Region` 有 `name` 但那是区域名（`Region.java:18-19`），`RegionMeta` 只有 color/tag/description/annexedBy 四键（`RegionMeta.java:8`）。
  - `map.UpdateRegion` 只能改 Region 的 hex 集/四键 meta（`RegionOperations.java:78-98`）；`map.RenameRegion` 只改 Region 显示名且 GM-only（`RenameRegionHandler.java:20-38`）。command catalog / MCP 工具清单均无 hex 命名工具（`logs/command_catalog.txt:1`、`logs/server.log:3`）。
  - overlay workaround 的代码边界：`map.CreateRegion` 只拒「id 已存在 / hexes 空 / 图外 hex」，与已有区域重叠不报错（`RegionOperations.java:50-63`、`:23-27`）；`map.DeleteRegion` 可删 overlay（无 nation tag 时守卫放行）；hex 的「国家归属」是**集合**：`HexOwner.nationsOf` = 含该格的区域中所有 `nation:` 前缀 tag（`HexOwner.java:42-70`）。overlay tag 写 `Landmark` 不会改国家集合；**若** overlay tag 写 `nation:*` 会**新增**一个国家归属。
  - 「最顶层区域」只是定义序末位（`MapResolver.java:133-160`），不是唯一省籍；overlay 建得晚会成为该 hex 在 `/api/map/hex` regions 列表的末位——对权限/税收没有直接效果，但读侧会多一条。
  - `map.UpdateRegion` 的 `meta` 整体替换、缺键清 null（`MapPayloads.java:136-150`，工具注 `MapUpdateRegionTool.java:14-18`）；overlay 后若要改 description，必须把四键带全。
  - 世界事实：`(34,-55)` 当前属 `西陵__CAP`，同名城市 `c-31_-60 铁门坎` 在 `(31,-60)`（运行世界事实 `dashu_policy_feasibility_20261002.md:19-20`、`:68-78`）。
  - 前端：世界视图的标签计划只收录 `meta.tag` 以 `nation:` 开头的区域（`simos-app/src/main/resources/webui/worldmodel.js:308-336`）；区域模式下的区域名计划是另一条（`map.js:506-558`，开关 `regionNamesEnabled`）。⇒ 1 格 `Landmark` overlay 的**名字不会出现在世界视图**，只在区域模式/区域列表可见。
  - 另一个「假 label」：`sd.PutInfo` 可以写任意 canonical 地址，包括 `map:<mapId>:hex.<q>_<r>`（`PutInfoHandler.java:24-30`、`:78-95`），但它是 sd 感知层 `info` 覆盖层，不参与地图渲染/解析，不构成 hex name。
- 现状可用 workaround：调 `map.CreateRegion` 建 1 格 overlay：`{regionId:"铁门坎", name:"铁门坎", hexes:[{q:34,r:-55}], meta:{color:null,tag:"Landmark",description:"…",annexedBy:null}}`。注意：id 不能与既有 Region 重名；重复调用同一 id 会被拒；`meta` 必须四键给全；须先处理 `(31,-60)` 同名城市（`social.UpdateCity` 改名）避免两个「铁门坎」。
- 若实现（建议两档）：
  - **最小档（推荐先做，零状态改动）**：app 组合工具 `simos.map.nameHex`，内部固定发一条 `map.CreateRegion`（new region id 形如 `landmark:<q>_<r>` 或调用方显式 `regionId`）或对既有 landmark region 发 `map.UpdateRegion`（改 name/description）；载荷 `{q,r,name,description?,regionId?,expectedRevision,branch?,preview?}`；GM-only；不新增命令类型/codec/往返负担。必须在 description 里写明「名字只在区域模式可见、会新增一条 Region、不会改国家归属」。
  - **真 hex label 档（较大）**：给 `GameMap` 加第 10 组件 `Map<HexCoord,String> labels`（**不要**塞进 `HexCell`——它的类注明令「只剩 height」）；`MapChangeSet` 加 `FieldDelta<String> labels` + `between/apply/isEmpty`（`MapChangeSet.java:48-117`）；key 走 `HexCoord::parse`；`MapCodec` 的整档序列化/旧档兼容（缺键 ⇒ 空表）；新增 `map.SetHexLabel` handler + 窄工具；`ApiViews.mapHex` 增 label 字段；前端要新增渲染层（`map.js`/`renderer.js` + 前端门禁计数，AGENTS §六）；`RoundTripComponentsTest` 的反射枚举会强制组件进变更集（`MapChangeSet.java:24-29`）。改动面远大于最小档，且不解决世界视图 LOD 显示问题（仍需前端）。
- 依赖与风险：overlay id 命名/冲突策略；与 `西陵__CAP` 重叠不会报错但会让 `/api/map/hex` 多一条、`regionIndex` 多一个属主；`simos.region.clearStructures` 的自动候选只认 `__P<数字>`/`__CAP` 形制，Landmark 不会被误删（`RegionClearStructuresPlan.java:35-53`）。
- 未核实项：前端是否在任何 LOD/模式把 Landmark 区域名画出（只读代码，未起 GUI 看）；`map.CreateRegion` 用非空白中文 id 的搜索/地址引用支持（`RegionId.parse` 非空白即收，理论可用）。

---

## D5.`actor.MoveAccount`（按 owner 搬账）

- **结论：确认缺失**（无命令/handler/工具；`actor.RemitGovTreasury` 只能算部分替代）。
- 证据：
  - 账键与结构：`GoodsAccountKey(owner, location)`（`GoodsAccountKey.java:7-12`、`:42-57`）；`GoodsAccount` 五组件 = balances(CommodityId→long)/money(CurrencyId→long)/frozenBalances/frozenMoney（`GoodsAccount.java:95-107`），构造期钉死 `0 ≤ 冻结 ≤ 余额`（`:184-232`），余额/冻结都保留 0 键、负数抛（`:44-46`、`:82-89`）。
  - 「可支配」唯一算法 = 余额 − 冻结：`AvailableStock.java:11-16`、`:55-77`。
  - `actor.RemitGovTreasury` 现状：载荷三资源 + 显式金额（`:30-42`），源账必须存在、逐资源按可支配量判足量、目标缺账新建、源扣目标加、源=目标拒、负数/全 0 拒（`:122-144`、`:192-268`、`:270-325`）；只动 `accounts`，不建 Actor 行（`:266-267`）。**它不能「搬全部账」**：只认 `grain/cloth/money`（`:87-90`、`:216-219`），不搬其它 CommodityId/CurrencyId；金额要调用方先读；不能把**已冻结**的部分搬走（负增量会侵占冻结而拒，`:307-324`）。
  - `actor.AdjustAccounts` 也不能替代：负增量同样受可支配（余额−冻结）约束（`AdjustAccountsHandler.java:44-64`、`:76-84` 标 `GmOnlyCommand`），全账户搬走后源冻结会悬空。
  - 缺失证明：全仓 rg 无 `MoveAccount` 命中；`logs/command_catalog.txt:1` 无 `actor.MoveAccount`；`logs/server.log:3` 111 条 MCP 工具里只有 `actor.AdjustAccounts`/`simos.gov.remit`。
- 现状可用 workaround：对 grain/cloth/money 逐项读实时余额后发一条 `actor.RemitGovTreasury`（`simos.gov.remit` 可预览可支配量）；其它商品/币种只能 `actor.AdjustAccounts` 手工一减一增（两条腿必须同批，否则悬空），冻结键仍会卡住。
- 若实现（建议）：
  - 命令 `actor.MoveAccount`（handler 落 `simos-actor/src/main/java/io/mosire/simos/actor/spi/MoveAccountHandler.java`，注册进 `Shell.java` 的 actor handler 列表 `:538-550`）。
  - 载荷两形态（建议同时支持，二选一必给其一）：
    - 单账：`{owner:{kind,id}, from:{q,r}, to:{q,r}, reason?}`；
    - 按 owner 全部：`{owner:{kind,id}, to:{q,r}, from?:[{q,r}...]}`；`from` 缺席 = 该 owner 在 `accounts` 里的全部位置；源按 `HexCoord` 自然序排序保证确定性；`to` 本身是源之一时从源集合剔除，若剔完为空 ⇒ 具名拒「没有可搬的账」。
  - 语义：源必须存在（缺 ⇒ 拒，不新建）；整本搬 = `balances/money/frozenBalances/frozenMoney` **四张表原样走**（冻结必须随行，否则违反类型不变式并让占用悬空）；目标缺账 ⇒ 五参新建；目标已有 ⇒ 逐键精确相加（溢出 ⇒ 具名拒，照 `RemitGovTreasuryHandler.addExact:327-343`），冻结逐键相加（两账各自满足 `0≤冻结≤余额` ⇒ 相加后仍满足），0 键保留；键序确定性（目标键序在前、源新增键按源表迭代序追加）；只动 `accounts`，`meta/actors` 一字不动；不建 Actor 行。
  - 不需要可支配校验（整本搬不是支出，不让余额变负）；`reason` 只保证形状，不落状态（与 Remit 同口径）。
  - 权限：建议像 `actor.AdjustAccounts` 一样标 `GmOnlyCommand`（裸账目原语，避免绕过 levy/remit 的辖权与上限口径，`AdjustAccountsHandler.java:76-84` 的先例）；如需决策人搬自己国库，应另设计带 scope 的窄命令，先不开。注册后自动被 `Shell.java:571-580` 排除出「可嵌令/RegisterEffect」，GM 直接提交照常。
  - 工具面：可选 `simos.actor.moveAccount` 窄工具（GM 桶）；命令本身已进 catalog，`CatalogTool.PAYLOAD_HINTS` 要补一条（`CatalogTool.java:418-425` 对已注册未登记 hint 会当场抛）。
  - ChangeSet/Codec/往返：只动 `accounts` 一张表，走 `ActorChangeSet.between(base, moved)`（`ActorData.withAccounts:109`），无新状态类型；`FieldDelta` 天然支持删+增；往返测试覆盖。
- 依赖与风险：D2 若要「搬全部账」依赖它；合并语义（目标已有账）与「冻结随行」需要一次明确裁定；若目标格已有同 owner 账且含冻结占用，合并后「哪个占用对应哪批货」的信息会丢（今天没有冻结的生产消费方，风险暂时低，但要在类注明写）。
- 未核实项：世界 actor 账里是否存在非 grain/cloth/money 的商品键/币种键（未逐账 dump）；冻结表是否有非零实际占用（未查）。

---

## D6.canonical 地址归一化

- **结论：部分具备**（`UnitId.parse`/`RegionId.parse` 已收 canonical 前缀；`actor.*` 的 owner/region 与若干工具入口仍是裸值；无统一入口）。
- 证据：
  - 已具备：`UnitId.parse` 去掉 `unit:` 前缀（`simos-unit/.../UnitId.java:17-28`）；`RegionId.parse` 去掉 `map:<mapId>:region.` 前缀（`simos-map/.../RegionId.java:27-47`）。region 侧多数入口已走它：`map.UpdateRegion/CreateRegion/DeleteRegion`（`MapPayloads.java:91-94`）、`social.UpdateCity/CreateCity`（`UpdateCityHandler.java:98-110`、`CreateCityHandler.java:66-68`）、`unit.SetJurisdiction/SetTaxRate`（`SetJurisdictionHandler.java:60-64`、`SetTaxRateHandler.java:49-52`）、`sd.CreateNation`（`CreateNationHandler.java:46`）、`RegionDeleteGuard.java:78`。
  - 仍未归一（`actor.*`）：
    - `actor.Seed` / `actor.AdjustAccounts` 的 `owner{kind,id}` / `actors[].id` 由 `ActorPayloads.actorRef` 直接用**裸 id** 构造：`ActorPayloads.java:392-403`；账键在 `:214-215`；seed 侧 `:135-145`、`:329-330`。传 `{kind:"UNIT",id:"unit:xxx"}` 不会去掉前缀，而是造出另一本「幽灵账」。
    - `actor.ClearRegion.regionId` 用 `new RegionId(regionId)` 而不是 `RegionId.parse`：`ActorClearRegionHandler.java:110-130` ⇒ 传 canonical `map:Map1:region.702` 会查无并拒。
    - `actor.RemitGovTreasury` 有自己的 `normalizeUnitId`，只去 `unit:`（`RemitGovTreasuryHandler.java:137-144`）；但窄工具 `simos.gov.remit` 在解析单位时用 `new UnitId(rawUnitId)`（`GovRemitTool.java:259-262`）——**工具比命令更窄**，传 canonical 会在工具层就 NOT 报「单位不存在」。
  - 其它裸值入口（同类，非 actor）：`UnitGetTool.java:64`、`MapPathTool.java:80`、`GuiServer.java:1036`/`:1088`、`RedactedQueryService.java:148`、`ToolSupport.java:294`（可见性判定）都用 `new UnitId(...)`；`CityId.parse` 只收裸值（`CityId.java:28-37`），而 `MapResolver` 的 canonical 是 `map:<mapId>:city.<id>`（`MapResolver.java:124-131`）。
  - 读侧输出形态不统一：unit 读口 canonical = `unit:<id>`（`UnitResolver.java:25`）；map 侧 = `map:<mapId>:region.<id>` / `.city.<id>`（`MapResolver.java:113-131`）；actor 产权读口的 `actor` 字段 = `ActorRef.toString()` = `UNIT:<id>`（`ApiViews.java:3655`）——写回 `actor.AdjustAccounts` 需要 `{kind:"UNIT",id:"<裸值>"}`，两边格式不同。
  - 兼容性事实：`UnitId.parse` 已经在所有走 parse 的命令上全局去掉 `unit:`；因此一个 raw id 字面量以 `unit:` 开头的单位在这些路径上今天就已经不可寻址。actor 侧若加同样的 strip，理论上会把一个真实 raw id `unit:x` 改指到 `x`——是否存在这种 id 需扫档（见末节）。
- 若实现（建议）：
  - **统一入口分两层，不要放 app 层**：
    1. 有类型 ID 的（unit/region/city/…）：**各类型的 `static parse` 是唯一归一化入口**（现状即此），所有 handler / tool / GUI 的用户输入一律改调 `parse`。本次要做的是修调用点：`ActorClearRegionHandler`、`GovRemitTool.resolveGov`、以及上列 `new UnitId(` 的读取入口。`CityId.parse` 若要支持 `map:<mapId>:city.<id>`，照 `RegionId.parse` 的写法加同一对前缀（只放宽入参，不改 `toString`）。
    2. 不透明 owner（`ActorRef`）：在 `simos-util` 加一个**不认识领域类型**的小助手（如 `io.mosire.simos.util.address.CanonicalLocalIds.withoutNamespace(String text, String namespace)`，只做「`<namespace>:` 前缀 strip + 空校验」），由 `ActorPayloads.actorRef` 在 `kind==UNIT` 时以 `"unit"` 调用。放 util 的理由：`simos-actor` 依赖 util 但**不依赖** simos-unit（`AGENTS.md` §〇 模块表），`ActorRef` 在 `actor-api`（零依赖契约层）不宜知道 `unit` 这个领域 namespace；放 app 则 `simos.command.submit` 的 GM 直提路径绕过 app 解析，治不到根。
  - 兼容性：统一为「只放宽、不收紧」——已有裸值逐字节不变；新增接受的前缀只影响本来就不可寻址的写法。唯一要裁的口子是「真实 raw id 恰以 `unit:`/`map:` 开头」：建议实现前扫一遍真档（base state 的 `units` 键、actor owner id）确认无此类 id，并在类注写明该前提（照 `GoodsAccountKey` 类注对 `|` 的前提写法）。
  - 影响面：不改命令载荷字段名/形状，不需要 ChangeSet/Codec/catalog 变化；工具描述里可提醒「canonical 与裸值都收」。回归风险集中在权限判定的 localId 派生（`ToolSupport.java:285-300`：`map.region` 用 `RegionId.parse(localId)` 已可，`unit` 用 `new UnitId(localId)` 需改 parse——改完 canonical 前缀会正确落到同一资源路径）。
- 依赖与风险：app 工具改动与 GM 直提路径可能行为不一致（工具修了、`simos.command.submit` 仍按命令 handler 的解析）；所以 actor 侧必须在 handler 载荷层修，不能只在工具层修。
- 未核实项：真档是否有 raw id 以 `unit:` / `map:` / `UNIT:` 开头（未扫全档）；`ActorRef.parseCanonical`（`UNIT:id`）与地址 `unit:<id>` 两套 canonical 是否应进一步统一（属契约层决定，本报告不建议本批动 `actor-api`）。

---

## D7.顺带核实（组合工具样例 / province.apply / DeleteNation）

- `simos.region.clearData`：固定批序 `social.ClearRegion → actor.ClearRegion → economy.ClearRegion → sd.PutInfo`，四信封共享 batchId/branch/expectedRevision，只对有命中的域下单，三域全净 ⇒ 零 revision；preview 共用同一份只读 pre-scan（`RegionClearDataTool.java:41-56`、`:249`；批序常量在 `RegionClearPlan.java:34-39`、`:152-159`）。
- `simos.region.clearStructures`：固定批序 `sd.DeleteDecisionMaker×N → unit.DisbandUnit×N → map.DeleteRegion×N → sd.PutInfo`，候选识别 = GOV 当刻有效位置在目标 Region hex 集 + id 形如 `sanitize(regionId)+__P<数字>` 且真子集的省 Region；相交但不满足的只 warning（`RegionClearStructuresTool.java:44-64`、`:289`；`RegionClearStructuresPlan.java:35-53`、`:61-67`、`:648-654`）。
- `simos.province.apply`：参数 `{regionId(必填), maxHexPerProvince?, minHexPerProvince?, capitalHex?{q,r}, capitalDistrictRadius?, namingPrefix?, staff?, policy?, allowedTools?, cadence?, providerId?, overrideOverlaps?, reason(必填), preview?, branch?, expectedRevision?}`（`ProvinceApplyTool.java:133-158`、schema `ProvinceApplyTool.java:160-210`）；固定批序 `map.CreateRegion×省 → ＋__CAP → unit.CreateUnit×(N+1) → unit.SetGovFormation×(N+1) → unit.SetJurisdiction → sd.CreateDecisionMaker×(N+1) → [provider] → sd.PutInfo`，一批一条 revision（`ProvinceApplyPlan.java:65-83`；`ProvinceApplyTool.java:568`）。它是本线所有「组合工具」最完整的参照；**边界：只落 Region+GOV+决策人，不改城市 region**（`ProvinceApplyPlan.java:84-86`）。
- `sd.DeleteNation`：**确认缺失**（`simos-sd/src/main/java/io/mosire/simos/sd/spi/` 无该 handler；全仓 rg 零命中；`sd.CreateNation` 对已存在 id 拒绝（`CreateNationHandler.java:50-52`）；`RegionDeleteGuard` 还会挡住删 `nation:` tag/homeRegion（`RegionDeleteGuard.java:56-66`））。它属 C 线，本线只记一句：原地重建国家当前只能换干净世界副本重初始化（与 `2026-10-02-mcp-admin-gaps.md:43-49` 一致）。

---

## 本线建议的实现批次与优先级（按对当前世界大蜀国策的解锁程度排序）

| 顺序 | 批次 | 内容 | 解锁/理由 | 依赖 |
|---|---|---|---|---|
| P1 | **D4-min** | `simos.map.nameHex`（overlay 包装，零状态改动） | 立刻能把 `(34,-55)` 命名「铁门坎」；低风险、无需裁定 | 无 |
| P2 | **D5** | `actor.MoveAccount` 命令（建议 GmOnly）+ 匹配 hint/工具 | 把「迁都搬国库」从三资源+显式金额升级为整本搬（含其它商品/币种/冻结）；是 D2 的账目地基 | 冻结随行语义裁定 |
| P3 | **D2** | `simos.gov.moveCapital` 组合工具 | 西陵/大蜀迁都可一条 revision 干净完成；**仍不能搬旧都人口**（另线），preview 里明确 | D5（可先退化为 Remit 三资源） |
| P4 | **D1** | `simos.command.submitBatch` GM 工具 | 给 GM 通用原子逃生口，可组合「兵力转移+守备拆出」等本线外操作；复用已验证的 `submitBatch`，成本小；须接受批审计缺口 | 无（Core 已具备） |
| P5 | **D3** | `simos.region.merge/split/reassignHexes` 组合工具 | 北谷占领区/西陵省界的合并、拆分、重划；需要税率/城市/编制三个策略先裁定 | D1 或同一套 Plan 基建 |
| P6 | **D6** | actor owner/city 归一化 + 调用点收口 | 读口 canonical 可直接写回；消除「工具比命令更窄」的不一致 | 真档 raw id 扫描 |
| — | D7 | 不新增（DeleteNation 归 C 线） | 现有组合工具模式已是本线参照 | — |

## 我没做/没验证的

- 没跑 Maven / 构建 / 测试（纪律禁止）；本文所有「已有测试把守」只是代码级引用，未运行。
- 没写任何代码/文档/世界；没调世界写接口。世界侧只做了 SQLite **只读**查询（`mode=ro`）与日志读取。
- 没逐条 replay 当前 head126 状态，因此 D2 的「西陵__CAP/P01 实际 hex 与税率」引用的是世界调查日志摘要，不是本次现算。
- D1 中 rev2/3/4 归属哪条组合工具是**按 changeset 命名空间形状推断**（六域批），未从 tool 调用日志逐条对齐；`core.SubmitBatch` 的机制本身已代码核实。
- 没扫真档确认是否存在以 `unit:`/`map:`/`UNIT:` 开头的 raw 单位/主体 id；D6 的兼容性前提因此未闭环。
- D4 前端行为（Landmark overlay 在哪些模式/LOD 实际显示）未起 GUI 验证；只读 `worldmodel.js`/`map.js` 得到「世界视图只画 `nation:` 区域名」。
- D0 未评估给 `GameMap` 加 hexLabels 组件对当前 `MapCodec` 旧档迁移/checkpoint 尺寸的实际影响（只列了改动面）。
- D3 未实测量级（252 Region / 59223 hex 下批量 UpdateRegion + N 条 UpdateCity 的锁占用与耗时）。
- D5 未查 actor 账里除 grain/cloth/money 外的键是否存在、冻结表是否有非零占用；「合并已有账」的冻结归属语义需要裁定，未裁。
- 未核对 `simos.province.assignCities` 在「同格多省」时的实际选择与工具调用者的期望是否一致（只读了纯推导代码）。
