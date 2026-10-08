# 调查报告：地图侧行政区建模 + gov/unit 抽税与军俸现状

> **调查性质**：只读。未改任何生产代码/测试，未跑 Maven，未起服务，未 `git commit`。
> **核对基线**：工作树 `HEAD = 5ae583e1`（`git status --porcelain` 空；提交信息 "docs: HANDOFF-2026-10-23 保存工作状态…"）。
> **纪律**：AGENTS §四「台账/计划措辞一律回代码核」——凡机制性描述，本文一律以**当前工作树代码**为准；
> 台账/设计书/报告里的措辞与行号只作线索，**不当现状**。凡未核到者单列 §7，不写成事实。
> **检索写法**（回应派单书对「glob pathspec 静默 0 命中」的提醒）：本次**全部**用显式目录 + `--include=*.java`
> （如 `grep -rn … simos-map/src/main/java`）逐模块检索，**未**使用 `git grep -- 'simos-*/src/main'` 这类 glob pathspec；
> 每条"零命中"结论在 §8 或正文里写明检索的目录与关键词。

---

## 0. 一句话结论

**行政区（`map.Region`）在几何与读口层面已经能表达"重叠 / 包含 / 半包含"（多对多从属 + `simos.map.overlaps` 的
`equal|aContainsB|bContainsA|partial` 判定），但税收语义层没有接住它——`JurisdictionDailyTax` 对"同一本家户账落在 N 个
已定税率区域"的处理是**逐区域依次累加征收**（无去重、无顶层优先、无跨 GOV 去重），因此现实世界靠"把首都格拆成互斥的
`capital-province`"来规避重复征税；军俸与抽税**共用同一条落账原语**（`StockDeductionService` +
`HouseholdStockDeduction` + `AvailableStock` + `DeductionReason` 词表），但**推导/计算/预算层是两条独立实现**，
另有一整条"一次性抽取"家族（`levyRegion` / `raiseUnit`）走 `actor.AdjustAccounts` 裸增量，完全不经落账原语。**

---

## 1. 本次调查的最高权威：用户 2026-10-08 裁定（逐字）

本报告引用的两条裁定为派单书原样转录，逐字如下（未改写、未缩写）：

> ①「行政区、市场区不能搞混，两者理论上不但可以重叠、包含，甚至可以半包含、部分重合等，因为市场区代表的是使用同一个货币的市场，行政区代表的是一个 GOV 单位直接抽税、管理的地方」

> ②「军俸本质上只是单次的一种抽税，税收可以理解为长期的军俸——总之就是国家剥削家户，机制上是这样」

**留痕状态（如实记，附实际检索写法）**：这两条原话**尚未见于仓库任何文档**——于 HEAD `5ae583e1` 在仓库根跑：
`grep -rn "行政区、市场区不能搞混\|半包含\|部分重合" --include=*.md .` → 仅 1 命中（`docs/superpowers/specs/2026-10-02-architecture-design-source.md:213` 的"部分重合"，讲的是 `simos.map.overlaps` 的读口，**不是**本次裁定）；
`grep -rn "军俸本质上\|长期的军俸\|国家剥削家户" --include=*.md .` → **0 命中**。
本报告是它们在本仓的首次落盘引用。§4 会说明它们与仓库里 2026-10-23 那批裁定（D1「辖区互斥」）的**张力**，并把它列为待裁定，不代替用户/控制方裁定。

---

## 2. 逐条回答（派单书 8 问）

### Q1. `map` 的 `Region` 是什么形状（字段/键/层级/父子/overlap 校验）？

**字段（5 个，纯几何 + 元数据，无层级）**

- `simos-map/src/main/java/io/mosire/simos/map/region/Region.java:18-19`：
  `record Region(RegionId id, String name, Set<HexCoord> hexes, RegionBoundary boundary, RegionMeta meta)`
  - `Region.java:28`：`hexes = Set.copyOf(hexes)`（集合语义，**不保序**）
  - `Region.java:36-40`：**唯一一条构造期不变式**是"`boundary` 必须等于由 `hexes` 重算的结果"，不等即抛。
    ⇒ 这条钉子只管"边界与成员格一致"，**与区域之间是否相交无关**。
  - `Region.java:48-50`：正常代码走 `Region.of(...)`（边界算出来，不手写）。
  - `Region.java:61-63`：改 `hexes` 必须走 `withHexes`（重算边界）。
- `simos-map/src/main/java/io/mosire/simos/map/region/RegionMeta.java:8`：
  `record RegionMeta(String color, String tag, String description, String annexedBy)`，四字段**都可为 null**。
  `annexedBy` 是**自由字符串**（不是 `RegionId`），且在生产代码里只被"视图 / 整体回填"读取两处
  （`simos-app/.../gui/ApiViews.java:4884`、`simos-app/.../tools/write/WorldgenInitializeTool.java:1016`）——**没有任何语义读取**（见 Q7）。

**键与容器**

- `simos-map/src/main/java/io/mosire/simos/map/GameMap.java:65`：`Map<RegionId, Region> regions` 是 `GameMap` 9 组件之一；
- `GameMap.java:177-179`：`regionIndex()` 是**派生件**（每次重算，不进组件/变更集/存档）。

**层级 / 父子：没有**

- `Region.java:18-19` 的全部字段即上；检索 `parentRegion|childRegion|subRegion|topmost` 于 `simos-*/src/main/java`
  只有 `ProvinceAssignCitiesPlan.java:156`（一个**方法参数名** `Optional<RegionId> parentRegion`，来自 id 命名约定，见 Q7）与
  `RegionIndex/MapResolver` 里"最顶层 = 定义序末位"的注释——**类型上没有 parent/level 字段**。
- `RegionIndex.java:21-22` 自述："**本类不承载层次**（V3）"。

**overlap 校验：明确不做，且是有意不做**

- `simos-map/src/main/java/io/mosire/simos/map/ops/RegionOperations.java:30-31`：
  "★★ **重叠一律允许**（M8-U1，用户原话：「hex 只是地形块，应当兼容多种从属」）…本类**没有任何"与已有区域相交就拒绝"的校验**，也**不裁剪** hex 集合——重叠是正常状态。"
- `RegionOperations.java:477`：创建/改写的唯一几何校验是"hex 在不在图上"，**绝不校验"与别的区域是否相交"**。
- 同口径还有 `simos-map/.../spi/CreateRegionHandler.java:30`、`UpdateRegionHandler.java:31`、`ReassignHexesHandler.java:31`。

### Q2. 一个 hex 能否属于多个 Region？互斥不变式/去重在哪？重叠的后果？

**能，且是设计允许的（多对多）**

- `simos-map/src/main/java/io/mosire/simos/map/region/RegionIndex.java:14-16`："**从属是多对多**（M8-U1，用户裁定）：一个 hex 可**同时属于多个区域**…不存在"重叠时谁赢"。"
- `RegionIndex.java:42-56` `of(...)`：区域按 id 字典序处理、逐格**追加**（不去重、不覆盖、不报错）；
  `RegionIndex.java:59-62` `regionOf(hex)` 返回**全部**归属（无归属返回空列表）。
- `simos-map/.../resolve/MapResolver.java:176-203` `regionOfHex`：从属**全部保留**，只把排列改成 `GameMap.regions()` 的**定义序**，
  末位 = "最顶层区域"（**这只是定义序约定，不是层级语义**）。

**互斥不变式/去重：全仓没有通用件；只有两处"局部"约束**

| 位置 | 性质 | 证据 |
|---|---|---|
| `simos-app/.../world/SmallWorld.java:437-455` | **某个世界自己**造两个互斥省级区（`small-world` 18 格 + `capital-province` 1 格）；互斥靠手写 hex 集 | `SmallWorld.java:437-446` 注释"省级辖区互斥" |
| `simos-app/.../tools/write/GovWorldBootstrap.java:234-237` | 上面那个世界的 **bootstrap 期断言**：两个省级辖区有交集即抛 `IllegalStateException` | 原文"Z7a 要求两级省级辖区互斥，但 … 存在重叠 hex" |

⇒ 这是**per-world 断言**，不是 `map` 模块的不变式：`map.CreateRegion` / `UpdateRegion` / `ReassignHexes` 仍照常允许重叠。

**重叠的具体后果（逐条给代码证据）**

1. **同一 unit 的多区域重叠 ⇒ 同一家户被征多次。**
   `simos-app/.../time/JurisdictionDailyTax.java:314-427` 的循环嵌套是
   `for (区域 : 该单位已定税率区域)` → `for (hex : region.hexes())` → `for (家户 : 该 hex 上的家户行)`，
   循环体内**没有**任何"这家户本 tick 已为本单位交过"的判据：
   - 唯一的 `Set<HouseholdId> chargedHouseholds`（`:155`）只在 `:399` **add**、在 `:444/:459` **计数**，
     从未被查询用于跳过（`grep -n "chargedHouseholds" JurisdictionDailyTax.java` = 4 处，无一处判重）；
   - 且每条扣除**立即落账**（`:378-394` 调 `StockDeductionService.deduct`，服务内部 `accounts.commit`，
     `StockDeductionService.java:138-141`），因此第二个区域看到的是**第一个区域税后**的余额（指数叠加，不是各自按原余额算）。
   ⇒ 同一单位管辖 `{A, B}` 且 `A ∩ B ∋ h` ⇒ 家户 `h` 本 tick 被扣**两次**（各按该区域税率）。
2. **跨 GOV 同辖一格 ⇒ 两个 GOV 各自全额征一遍（重复征税）。**
   `JurisdictionDailyTax.java:129-130` 单位按 `UnitId.value()` 升序，`:162-437` 依次征；类注 `:73` 明说
   "**重叠管辖**：同一天多单位命中同一区域/同一本账 ⇒ 按单位 id 升序依次征，后者见前者税后余额"。
   本仓**已登记**为待裁定缺口：`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:70`
   （"Region 允许重叠，两 GOV 同辖一格且税率 >0 会重复征税"）与 `:192`（"14. Region 互斥/重复征税：是否加互斥不变式或跨 GOV 去重"）。
3. **真实世界已踩过**：`docs/superpowers/reports/2026-10-23-fiscal-loop-investigation.md:13` 与 `:257`——
   "D1 上游：省辖区包含中央座位，按日抽中央国库 173 天"；`docs/superpowers/HANDOFF-2026-10-23-gov-fiscal-batch.md:73`
   记 "中央国库 tick360：run6 **被省抽税 173 天**"。
   机制上就是：省辖区含中央座位格 ⇒ 中央国库家户 `hh-gov-gov-central` 落在省的税率区域内 ⇒
   被 `JurisdictionDailyTax` 当普通家户抽走（税里只排除**本 GOV 自己**的国库户，`:321-323`）。
4. **行政需求侧对重叠是"去重"口径，与税侧不一致**：
   `simos-gov/.../GovDemand.java:92-93` 对重叠区域同格 `demand.putIfAbsent(...)`，
   注释"重叠 Region 的同一格需求相同…putIfAbsent 只保证首次出现的键序"。
   ⇒ 同一格在 A、B 两个区域里，**需求只算一次**，**税却收两次**——两条链对同一份重叠给出两种口径。
5. **读口已有"包含/半包含"判定，但只是只读工具**：
   `simos-app/.../tools/read/MapOverlapsTool.java:43`（工具名 `simos.map.overlaps`，GM 专用只读）、
   `:300-314` `relation(...)` 返回 `equal` / `aContainsB` / `bContainsA` / `partial`。
6. **世界生成/建议器明确把重叠当常态**：`simos-app/.../gov/ProvinceDivider.java:52-54`
   "**重叠**：`overlappingRegions` 列出…只作信息，**不影响建议** —— 区域重叠 ≠ 省籍，是否 override 由调用方显式决定"；
   `simos-app/.../tools/write/ProvinceApplyPlan.java:46-47` 也写"区域重叠是正常状态，由 GM 后续用 `unit.SetJurisdiction` / 清空工具调整"。

### Q3. GOV 的「辖区 / jurisdiction」怎么表示？

**是 `Unit` 上的一个富结构，键是 `RegionId`（不是 hex 集合、不是 Region 对象引用、不是城市集）**

- `simos-unit/src/main/java/io/mosire/simos/unit/Jurisdiction.java:40-45`：
  `record Jurisdiction(Map<RegionId, Long> taxRatePerMilleByRegion, long levyGrainCapPerCommand, long levyMoneyCapPerCommand, long levyManpowerCapPerCommand, long administrationPerMille)`
- `Jurisdiction.java:13-14`：**"`taxRatePerMilleByRegion` 的 key 集就是管辖区域集**，空 map = 无管辖——不把管辖权写回 Region 的 tag（用户裁定：管辖挂在 Unit 上）"。
- `Jurisdiction.java:24-25, 47-71`：构造期校验（键值非 null、税率 ∈ [0,1000]、三个 cap ≥ 0）。
- `simos-unit/.../Unit.java:98`：`Optional<Jurisdiction> jurisdiction` 是 `Unit` 的第 15 组件；持久化见
  `simos-unit/.../codec/UnitCodec.java:36`（线格式多一个 `"jurisdiction"` 键）。
- 写口（两条，都是 unit 域命令）：
  - `unit.SetJurisdiction` → `simos-unit/.../ops/UnitOperations.java:686-732`：区域集合**整体替换**、保留区域税率 upsert 保留、**新区域从 0 起**（`:716-719`）、区域不存在**具名拒**（`:706-715`）；
  - `unit.SetTaxRate` → `UnitOperations.java:747-786`：只改一个键的值，region 不在管辖里 ⇒ 具名拒并指路 `SetJurisdiction`（`:759-763`）。
- 消费方（四处）：`simos-gov/.../GovDemand.java:78`（逐格行政需求）、
  `simos-app/.../access/GovScope.java:93-97, 108-115`（**决策人权限范围**：授辖区的 map/social/actor 前缀）、
  `simos-app/.../tools/read/GovInfoTool.java:735-744`（告警可见 hex）、
  `simos-app/.../time/JurisdictionDailyTax.java:194`（征税区域表）。
- `Jurisdiction.java:27-30` 自述：`administrationPerMille` **已退役、生产路径零读取**（长期税的行政效率改由 `simos-gov` 每 tick 的
  `GovOfficeState.efficiencyPerMille` 提供）；`Jurisdiction.java:16-18` 自述：三个 `levy*CapPerCommand` 是**一条抽取命令**的上限，
  **没有**"每周期已抽多少"的累计账本。

### Q4. 税收链路：税率存哪、按什么聚合、落到哪个账户、周期？

**逐步骤（file:line）**

1. **税率存储**：`Unit.jurisdiction().taxRatePerMilleByRegion`（unit 切片源状态；`Jurisdiction.java:41`，`Unit.java:98`，
   持久化 `UnitCodec.java:36`）。写口见 Q3。
2. **日循环入口**：`simos-app/.../time/PopulationEconomyTimeParticipant.java:709-733`
   （`govActive` 分支内；`:726-733` 调 `JurisdictionDailyTax.collect(stepper.accounts(), stepper.householdEconomies(), units, map, day, computed.efficiencyPerMilleByUnit())`）。
3. **无 GOV 读数 ⇒ 整单位不征**：`JurisdictionDailyTax.java:162-166`（查不到 efficiency 直接 `continue`，不读退役字段、不补 0）。
4. **逐区域取已定税率区域**：`:193-212`（rate = 0 整段跳过）；**区域不在 `map.regions()`** ⇒ 记 `GapKind.REGION_MISSING` 后跳过（`:216-241`，`GapKind` 定义 `:561-572`）。
5. **国库落点 = 政府家户**：`:247-291`，`GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unitId)`；
   其 id 形状与判据见 `simos-app/.../household/GovernmentHouseholdResolver.java:34-64`（`hh-gov-<unitId>`，且必须恰一个、逐字等于单位 id）；
   账户不在账户会话里 ⇒ `GapKind.TREASURY_ACCOUNT_MISSING`（`:271-291`）。
6. **座位要求**：`:292-309`，`units.effectivePosition(unit.id(), tick)` 为空 ⇒ `GapKind.NO_POSITION`、本单位不征（"无座位的 GOV 不征"）。
7. **税基定位**：家户行按 `HouseholdEconomy.view().hex()` 反索引到 hex（`:131-144`），再按 region 的 hex 集取该格家户（`:319-320`）；
   **排除本 GOV 自己的国库户**（`:321-323`）。
8. **算式（纯推导）**：`:488-505` `assess(inventory, money, ratePerMille, efficiencyPerMille)`：
   - `assessed = floor(balance × rate‰)`；`attainable = floor(assessed × efficiency‰)`；`collected = min(attainable, available)`；
   - `available` = `AvailableStock.available(...)`（余额 − 冻结，唯一算法）；
   - `scalePerMille` 溢出饱和（`:511-521`）；效率**不封顶**（`:167-177` 只保 ≥ 0，注释引 C3 与用户"都不封顶"）。
   ⇒ **税基 = 家户当刻存量（粮 / 银两维），不是流量/所得**；gov 侧设计书亦如此冻结：
   `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md:381`（"D3 = 税基保持现状：存量税 + 每日评估（指数抽干式）…本轮不做最低口粮保护/流量税"）。
9. **聚合口径**：**没有按人口/产出聚合**。逐单位 → 逐区域 → 逐格 → 逐户，逐户各算一次；
   报告汇总为"逐户粮税明细"与"逐 unit 实收粮/银"（`:428-448`，`Report` 定义 `:609-682`）。
   恒等式 `collected + adminShortfall + stockShortfall == assessed` 逐维成立（类注 `:70-72`）。
10. **落账（唯一原语）**：`:366-394` 构造 `HouseholdStockDeduction.transfer(家户 → 国库家户, reason = JURISDICTION_TAX, detail=…)`，
    调 `StockDeductionService.deduct(accounts, …, tick)`；
    服务语义（整批先校验、后一次 commit；余额/冻结两条具名拒；账户必须在会话里登记）见
    `simos-app/.../time/StockDeductionService.java:40-181`。
11. **周期 = 每 tick（每日）**；阶段日志 `TAX_DAILY_END`（`:449-477`），逐笔 TRACE（`:400-424`）。
12. **上缴（remittance，另一条链，不属"税"本身）**：
    - 比例存在 **gov** 侧：`GovBudgetPolicy.remittancePerMilleToSuperior`（`simos-gov/.../GovBudgetPolicy.java:43`，0..1000，0 = 抗税/不上缴）；
    - 执行：`simos-app/.../time/GovRemittanceBridge.java:104-290`；周期 = **本周期实收税 × 比例**，
      而"周期"锚定**经济产业周期关账日**（`:57-58`、`:138` 由 `cycleClosed` 决定；调用点传
      `stepper.lastCycleClosed()`，`PopulationEconomyTimeParticipant.java:750-762`）；
    - 落账同一条原语：`:251-266` `StockDeductionService.deduct(HouseholdStockDeduction.transfer(省国库 → 上级国库, reason = GOV_REMITTANCE))`；
    - 关账日一律清零累计（`:63-64` 注释、`:149/159/170/176/185/…` 逐分支 `afterCycleClose`），rate=0 ⇒ 不转移不告警（`:148-152`）。

### Q5. 军俸链路：怎么抽、从哪个账户、与税收是否共用机制？

**结论：与抽税共用"落账原语"，不共用"推导/计算/预算"层——是两条独立实现（详见 Q6）。**

**政策（unit 侧，唯一权威）**

- `simos-unit/src/main/java/io/mosire/simos/unit/MilitaryPayPolicy.java:52-59`：
  `record MilitaryPayPolicy(periodDays, phaseDay, startsOnDay, OptionalLong expiresOnDay, grainPerHouseholdPerCycle, clothPerHouseholdPerCycle, moneyPerHouseholdPerCycle)`；
  三张表是 `HouseholdId → 金额`（**逐户显式定额，不按人口折算**，`:15-16`）；
  全空 = `disabled()` 停发态（`:61-63, 136-145`）。
- 挂载：`simos-unit/.../ArmyFormation.java:47`（第四组件，JSON 键 `militaryPayPolicy`；缺键/旧档 ⇒ `disabled()`，`:79-82`）。
- 键必须 ⊆ `Unit.households()`：`simos-unit/.../UnitState.java:332-348`。
- 写口：`unit.SetArmyPayPolicy`（`simos-unit/.../spi/SetArmyPayPolicyHandler.java:50`；
  领域实现 `UnitOperations.java:948-964`，无 `ArmyFormation` 即具名拒）；**GM 窄工具** `simos.gm.armyPayPolicy`
  （`simos-app/.../tools/write/GmArmyPayPolicyTool.java:51`，只进 GM 桶：`SimosToolSource.java:777`）。

**推导（桥，纯函数、不写状态）**

- `simos-app/.../time/MilitaryPayRuleBridge.java:85-251`（`deriveReport`）：
  - 逐 Army 单位（`:90-91` id 升序）；policy 空/disabled ⇒ 跳过（`:102-104`）；
  - `masterGov` 空 ⇒ 具名 gap（`:106-109`）；**payer = `GovernmentHouseholds.of(masterGov.value())`**（`:110-113`），
    并逐条校验"payer ∈ Social 家户表 / ∈ economy.classes / `economy.governments[gov-unit-<masterGov>].treasury == payer`"（`:124-179`）；
  - 逐政策家户（`:184-185` id 升序）生成 `HouseholdPeriodicAdjustment(id = army-pay:<unitId>:<hh>, payer = 国库家户, payee = 军户, reason = MILITARY_SALARY, period/phase/starts/expires 取自 policy)`（`:224-238`）。
  ⇒ **钱从"该军队认领的 GOV 的国库家户"出，发给军户**（不是从军户抽走；见 Q6 第 10 点的措辞问题）。

**预算裁剪（Z3c，共用国库池）**

- `simos-app/.../time/GovBudgetExecutionBridge.java:132-151`：军俸规则里当日到期者按 gov 汇总成 `militaryDemand`；
  `:199-201` 作为 `GovBudgetCategory.MILITARY_STIPEND` 的 demand 参与按序 min/cap 分配（`:250-303` 分配算法）；
  `:232` + `:530` `capBudgetedRules(...)` 把"原始请求 + 逐腿授权"装成 `BudgetedRule`。
  周期冻结：`GovBudgetExecutionBridge.java:89` `BUDGET_CYCLE_DAYS = 1`（每日口径）。

**执行（唯一执行体，与税共用落账原语）**

- `simos-app/.../time/PeriodicHouseholdAdjustmentExecutor.java:151-159` `applyBudgeted(...)` → `:176-252` 唯一执行体 →
  `:267-372` `executeOne(...)`：payer/payee 账户存在性（`:272-280`）→ 逐腿 `min(authorized, available)`（`:294-328`）→
  构造 `HouseholdStockDeduction.transfer(payer → payee, reason = 规则自带 reason)`（`:338-342`）→
  `StockDeductionService.deduct(...)`（`:344-345`）。
- 调用点（同一天、同一账户会话、税之后）：`PopulationEconomyTimeParticipant.java:819-824`
  （`applyBudgeted(budget.budgetedLedgerRules(), stepper.accounts(), day)`）。

**周期**：军俸 = policy 的 `periodDays/phaseDay`（逐户到期由 `isDue` 判，`PeriodicHouseholdAdjustmentExecutor.java:255-264`）；
官吏工资 = 每日（`GovSalaryRuleBridge.java:78-81` `periodDays=1/phaseDay=0/startsOnDay=1`）。

### Q6. 若把「抽税」和「军俸」统一成同一个「国家从家户抽取」原语，现在哪些地方是重复实现？

**先给判断口径**：今天代码里真正的"共享原语"只有一层——**落账**：
`HouseholdStockDeduction`（家户 → 家户 / sink，带封闭 `DeductionReason`）+ `StockDeductionService.deduct/deductAll`
（整批先校验、后一次 commit、影子副本、`AvailableStock` 唯一减法）。证据：`StockDeductionService.java:29-62, 93-181`；
词表 `simos-economy-api/.../stock/DeductionReason.java:9-24`（自述五档有写者：`JURISDICTION_TAX`/`ADMIN_UPKEEP`/`MILITARY_SALARY`/`ADMIN_SALARY`/`GOV_REMITTANCE`，`CORVEE` 是留位）。

**重复点清单（逐条带证据）**

1. **"算能收/能付多少"这件事有两份实现。**
   - 税：`JurisdictionDailyTax.java:488-505` 自算 `assessed/attainable/collected`，自己取 `AvailableStock.available`；
   - 军俸/工资/俸禄：`PeriodicHouseholdAdjustmentExecutor.java:294-328` 自算 `paid = min(authorized, available)`；
   - 俸禄（`GovernmentUpkeepOracle.java:205-223`）再自算一次 `paidTotal = min(available, requested)` + 瀑布分摊。
   ⇒ 三处各自 `min(…, available)`，三处各自构造 `HouseholdStockDeduction`（`JurisdictionDailyTax.java:378-394`、
   `PeriodicHouseholdAdjustmentExecutor.java:338-345`、`GovernmentUpkeepOracle.java:148-165 / 253-275`）。
2. **税不走规则引擎，因此无法进入预算类别。**
   税是"即算即扣"的内联循环（`JurisdictionDailyTax.java:314-427`）；军俸/工资是
   `HouseholdPeriodicAdjustment` + 预算授权（`GovBudgetExecutionBridge.java:132-232`）。
   ⇒ 同一个国库的"支出侧"有优先级/帽/缺口账本，"收入侧"没有对应概念（也没有"本周期已抽多少"的账本）。
3. **"一次性抽取"是完全独立的第三条路（连落账原语都不走）。**
   `simos-app/.../tools/write/LevyRegionTool.java:42-44, 382-410, 436-499`：
   组一条 `actor.AdjustAccounts`，**各来源家户负增量 + 国库正增量**（`:436-499`），
   `AdjustAccountsHandler` 自述是"给组合工具用的**裸账目原语**，上限/管辖/人力口径都在 `simos.unit.levyRegion` 里"
   （`simos-actor/.../spi/AdjustAccountsHandler.java:81-85`，实现 `GmOnlyCommand`）。
   它**没有** `DeductionReason`、**不走** `AvailableStock` 之外的共享语义（它用 `RegionAllocations` 自己的瀑布，见第 8 点）。
4. **"一次性抽取"还有第二家**：`simos-app/.../tools/write/RaiseUnitTool.java:63, 216, 533`
   （组军用同款 `actor.AdjustAccounts` 负增量）。
5. **国库间转移还有第三条命令级路径**：`actor.RemitGovTreasury`
   （`simos-actor/.../spi/RemitGovTreasuryHandler.java:83`），被
   `GovRemitTool` / `GovPayTool` / `GovTransferTreasuryTool` 三条工具复用（三者均提交该类型）。
   ⇒ "同一批资源从 A 账到 B 账"在代码里有 **三种** 载体：`StockDeductionService`、`actor.AdjustAccounts`、`actor.RemitGovTreasury`。
6. **军俸桥 vs 工资桥：两份几乎同形的"政策 → 周期规则"派生。**
   `MilitaryPayRuleBridge.java:85-251`（逐户定额）vs `GovSalaryRuleBridge.java:98-240`（承诺劳动 × 每承诺小时费率）；
   两者都产出 `HouseholdPeriodicAdjustment`、都要解析国库付款方、都各自维护 gap 清单。
7. **俸禄（ADMIN_UPKEEP）与工资（ADMIN_SALARY）也是两条实现。**
   俸禄：`GovDaily.settle`（`simos-gov/.../GovDaily.java:151-157`）经 `PaymentOracle` 回调
   `GovernmentUpkeepOracle.java:91-131 / 176-283`（按 GOV_SERVICE 承诺份额把**总量**瀑布分摊，`ProportionalSplit`）；
   工资：`GovSalaryRuleBridge.java:149-230`（逐户按承诺小时 × 费率定额），走规则引擎。
   两条都是"国库 → 官吏户"，`DeductionReason.java:37/43` 也承认 `ADMIN_UPKEEP` 与 `ADMIN_SALARY` "语义分离"。
8. **分摊/瀑布口径两套，都在共享层，但不通用。**
   `RegionAllocations`（`simos-app/.../tools/write/RegionAllocations.java:20-40`：降序瀑布、总量不足**整条拒**）
   只服务 `levyRegion`/`raiseUnit`；`ProportionalSplit`（俸禄切分）只服务 `GovernmentUpkeepOracle`；
   税则两者都不用（逐户各按存量比例，不做瀑布）。
9. **重叠口径两套**（见 Q2 第 4 点）：税侧逐区域累加，需求侧 `putIfAbsent` 去重。
10. **`DeductionReason` 的词面与实现的语义漂移（文档 vs 代码，AGENTS §四 的形态）。**
    `DeductionReason.java:9-14` 把"辖区税 / 行政俸禄 / **军队俸禄** / 徭役"描述成"同一笔**从家户账上扣走**的粮/钱"，
    `MILITARY_SALARY` 条目（`:26-27`）写"军队走到哪、由决策人给下一 tick 的**扣除计划**"；
    但当前实现里军俸是 **payer = 政府国库家户 → payee = 军户** 的**支付**（`MilitaryPayRuleBridge.java:229-230`
    + `PeriodicHouseholdAdjustmentExecutor.java:338-342`），2026-10-23 口径也是"俸禄 = 发给官吏户的口粮"
    （`docs/superpowers/specs/2026-10-23-fiscal-loop-design.md:55-57`；`GovernmentUpkeepOracle.java:41-44`）。
    ⇒ **词表 javadoc 是旧口径的留存，不是实现描述**；统一原语时要连词面一起收口，否则新代码会照旧措辞写错方向。
11. **统一原语目前缺的接口形状**：税需要"按率 + 按基数 + 按效率"从**每户**算一次；军俸/工资需要"逐户定额 + 周期 + 预算帽"。
    两者今天唯一的公共面是 `HouseholdStockDeduction`（一条已经算好的金额）。
    ⇒ 要真正统一，需新增"**征收/给付评估器**"（rate/base/efficiency/period/额度源）这一层，而不是把税塞进
    `HouseholdPeriodicAdjustment`（后者是**定额**语义，表达不了 `balance × rate`）。

### Q7. 行政区与「城市 / 省 / 中央」的层级关系怎么表达？支持嵌套 / 半包含吗？

**分三层看，逐层给证据**

1. **几何层：能表达，且能判定关系，但不存储层级。**
   - 多对多从属 + 允许重叠：`RegionIndex.java:14-16, 42-62`；`RegionOperations.java:30-31`；
   - "包含 / 半包含"的判定是**读口工具**算出来的：`MapOverlapsTool.java:300-314`
     （`equal` / `aContainsB` / `bContainsA` / `partial`），输入是两份 hex 集，**不进状态**；
   - "最顶层区域"只是 `GameMap.regions()` 的**定义序末位**（`MapResolver.java:176-203`，`ApiViews.java:4739`
     同口径），`RegionIndex` 明确不承载层次（`RegionIndex.java:21-22`）。
2. **行政层：层级挂在 Unit 上，不在 Region 上。**
   - `simos-unit/.../GovernmentLevel.java:9-16`：只有 **两档** `CENTRAL` / `PROVINCE`；
   - `GovernmentFormation.java:63` `Optional<UnitId> superiorGov`（中央为空，多数省直接指中央；`GovernmentFormation.java:27-28`）；
   - 沿这条链走的具体机制：remittance 取 `formation.superiorGov()` 找上级国库
     （`GovRemittanceBridge.java:143-146, 181-216`）；决策人权限范围也按 GOV 单位算（`GovScope.java:93-145`）。
   - 城市 → 区域是**单值指针**：`simos-social/.../city/SocialCity.java:41` `Optional<RegionId> region`
     （写入口 `CreateCityHandler.java:72-74, 89`、`UpdateCityHandler`）；
   - `simos-sd` 的 Nation 也是**单值** `homeRegion`（`simos-sd/.../model/Nation.java:17`），
     且其 `adminBudgetPerTick` **无任何消费方**（`Nation.java:13` 自述）。
3. **"省 / 首都区"的父子关系今天靠 id 命名约定，且**两套约定并存**。**
   - 旧约定（`ProvinceApply` 系，世界生成/建省批量）：
     `ProvinceAssignCitiesPlan.java:43-49`（`__P` 省标记、`__CAP` 首都后缀、`__` 前缀分隔符）、
     `:177-185`（"候选 id 以 `<parentId>__` 开头" = 父子判据）；
     `ProvinceApplyPlan.java:59-60, 161, 877`（建议省 id = `sanitize(regionId)+"__P"+数字`；首都区 = `…+ "__CAP"`）。
     ⇒ 这是**字符串前缀**推断出的层级，`Region` 里没有 parent 字段，也没有任何校验保证它自洽。
   - 新约定（Z7a 小世界）：`capital-province` 与 `small-world` **平级**、互斥，靠 `SmallWorld.java:437-455` 手写 hex 集
     + `GovWorldBootstrap.java:234-237` 断言；中央辖 `capital-province`、省辖 `small-world`
     （`GovWorldBootstrap.java:363-385`，注释与 `run-small-world.sh:92` 一致）。
   - 两条约定今天**没有同一份权威**：一个是 `<nation>__CAP`，一个是 `capital-province`。

**结论（Q7）**：**几何上**支持嵌套/半包含（并有只读判定工具），**语义上不支持**——"谁管谁/谁向谁抽税"只有
unit 级 `superiorGov` 一条链 + 两档 `GovernmentLevel`，区域之间没有父/子、没有优先级、没有"顶层区域赢"的规则；
而抽税恰恰需要这条规则（见 §4）。

### Q8. 缺口清单（三档，每条带 file:line）

#### A. 有代码（可跑、可读、有验收痕迹）

| 能力 | 证据 |
|---|---|
| Region 多归属 + 重叠 + 边界自洽 | `Region.java:18-40`；`RegionIndex.java:42-62`；`RegionOperations.java:30-31, 477` |
| 重叠关系只读判定（equal/包含/partial） | `MapOverlapsTool.java:43, 300-314` |
| 日税（存量 × 税率 × 效率，粮/银两维）落政府国库家户 | `JurisdictionDailyTax.java:113-505` |
| 辖区 → 决策人权限范围 / 行政需求 | `GovScope.java:93-145`；`GovDemand.java:62-109` |
| 周期上缴 + 抗税（rate=0） | `GovRemittanceBridge.java:104-290`；`GovBudgetPolicy.java:43` |
| 预算五类有序 + 逐腿账本 PARTIAL/SKIPPED | `GovBudgetExecutionBridge.java:114-242`（分类需求与 min/cap 分配）、`:523-602`（规则裁剪）；`PeriodicHouseholdAdjustmentExecutor.java:143-159`（applyBudgeted 入口）、`:501-602`（BudgetedRule = 原始请求+逐腿授权）、`:604-664`（RuleReadout = 逐腿四组读数 + EXECUTED/PARTIAL/SKIPPED） |
| 军俸政策 → 周期规则 → 预算裁剪 → 执行 | `MilitaryPayPolicy.java:52-59`；`MilitaryPayRuleBridge.java:85-251`；`PeriodicHouseholdAdjustmentExecutor.java:267-372` |
| 官吏俸禄（实物粮/布，按承诺份额）与官吏工资（按承诺小时×费率） | `GovernmentUpkeepOracle.java:91-283`；`GovSalaryRuleBridge.java:98-240` |
| 一次性抽取（粮/钱/布/人） | `LevyRegionTool.java:42-44, 382-499`；`LevyRegionPlan.java:105-259`（plan 入口 + 国库解析 + 逐维分摊）；`RegionAllocations.java:20-40` |
| 组军同款抽取 | `RaiseUnitTool.java:63, 216, 533` |
| 落账唯一原语 + 封闭原因词表 | `StockDeductionService.java:63-181`；`DeductionReason.java:9-24, 27-52` |

#### B. 半成品（有形状、语义不完整或只覆盖一半）

| 缺口 | 现状与证据 |
|---|---|
| **重叠的税收语义** | 能重叠、能检测，但征税逐区域累加、无去重/顶层优先/跨 GOV 去重（`JurisdictionDailyTax.java:314-427`，`chargedHouseholds` 只计数 `:155/399/444`）；需求侧却 `putIfAbsent` 去重（`GovDemand.java:92-93`）。已登记待裁定：`status/2026-10-23-planned-not-implemented-inventory.md:70, 192` |
| **一次性抽取 vs 周期税，两套上限语义** | `levy*CapPerCommand` 只限"一条命令"，**无周期累计账本**（`Jurisdiction.java:16-18` 自述）；周期税无任何 cap 概念 |
| **层级只到"中央/省"两档**，区域层级靠 id 约定且两套并存 | `GovernmentLevel.java:9-16`；`ProvinceAssignCitiesPlan.java:43-49` vs `SmallWorld.java:142, 448-455` |
| **军俸欠饷逃亡未做** | `specs/2026-10-23-fiscal-loop-design.md:102`（"军队户欠饷的同类逃亡留后续（本批只做 GOV 官吏户）"） |
| **军俸 FlowRow / ledger 维度** | `HANDOFF-2026-10-23-gov-fiscal-batch.md:129`（"❌ 仍开：军俸仍走瞬态规则 + 账户扣款"） |
| **决策人侧军俸工具 / 审批链** | `HANDOFF-2026-10-23-gov-fiscal-batch.md:130`（"❌ 仍开：只有 GM 的 `simos.gm.armyPayPolicy`"）；窄工具只在 GM 桶：`SimosToolSource.java:777` |
| **免税 / 税率历史 / 到期恢复** | `status/2026-10-23-planned-not-implemented-inventory.md:69`（只有 `unit.SetTaxRate` 单点写口） |
| **区划变更的下游同批重算（P2-F1）** | `status/2026-10-23-planned-not-implemented-inventory.md:123`（`map.MergeRegions/SplitRegion/ReassignHexes` 已是 GmOnly，但 Shell 之外 0 调用，无 jurisdiction/城市/税率/编制同批重算） |
| **`sd.Nation.adminBudgetPerTick`** | 字段在、无消费方（`Nation.java:13` 自述） |

#### C. 完全没有

| 缺口 | 依据 |
|---|---|
| **Region 互斥不变式 / 跨 GOV 去重 / 顶层优先** | `map` 侧**明确不做**（`RegionOperations.java:30-31, 477`）；唯一"互斥"是 per-world 断言（`GovWorldBootstrap.java:234-237`） |
| **"国家从家户抽取"统一原语** | 今天只有落账原语共享（`StockDeductionService.java`）；推导层三份（Q6 第 1/2 点）、载体三种（Q6 第 3/5 点） |
| **区域删除对 `unit.jurisdiction` 的守卫** | 全仓唯一 `MutationGuard` 是 `simos-sd/.../guard/RegionDeleteGuard.java:30-69`，只查 `nation:` tag 与 `Nation.homeRegion`；`Region` 被 `Jurisdiction` 引用时**无守卫**，删了之后只在日税里记 `GapKind.REGION_MISSING`（`JurisdictionDailyTax.java:216-227`）、`GovDemand`/`GovScope` 跳过该区域 |
| **流量税 / 最低口粮保护** | 明确本轮不做（`specs/2026-10-23-gov-service-mode-design.md:381`，D3） |
| **铸币 / 发债（F3）** | `HANDOFF-2026-10-23-gov-fiscal-batch.md:116-117`；连带结论"中央独立财政收入 = 0"（同文件 `:78`） |
| **市场区与行政区的映射/转换** | 两者是**不同类型**，代码里**无**互转（见 §3） |

---

## 3. 「行政区 ≠ 市场区」在代码里的落点（用户裁定 ① 的对应）

代码上这已经是**两个独立的类型、两条独立的派生链**，不存在"搞混"的实现：

| | 行政区 | 市场区 |
|---|---|---|
| 类型 | `io.mosire.simos.map.region.Region`（`Region.java:18-19`） | `io.mosire.simos.economy.api.market.MarketRegion`（`MarketRegion.java:26`） |
| 来源 | GM 编辑的持久状态（`GameMap.regions`，`GameMap.java:65`） | **纯派生件**：`MarketTopology` 由城市 + 半径现算（`MarketRegion.java:11-19`） |
| 归属 | **多对多**、重叠全部保留（`RegionIndex.java:14-16`） | 一个 hex 归**最近**节点（`MarketRegion.java:14-16`）；`MarketTopology` 里 `Map<HexCoord, MarketRegion> regionByHex` 是**单值**（`MarketTopology.java:88-89`）⇒ 天然互斥 |
| 语义轴 | 抽税/管辖（`Jurisdiction.java:13-14`） | **币种**：`MarketRegion.numeraire()`（`MarketRegion.java:51-54`）——正对用户"使用同一个货币的市场" |
| 谁读它 | `GovDemand` / `GovScope` / `JurisdictionDailyTax`；**另有命令面一处**：`economy.ClearRegion`（GM-only）按目标 Region 的 hex 集清逐格经济记录（`simos-economy/.../spi/EconomyClearRegionHandler.java:43-44, 142-175, 489-500`） | `MarketSettlement` / `MarketReadout` / `MarketDemandBook` / `ExpectedProfitBook` |
| 与对方的耦合 | **只有上面那一处命令面耦合**（只读目标 Region 的几何，不读市场区）；`Jurisdiction` / 税 / 军俸都不读市场区 | **零**：`simos-economy/.../time/MarketTopology.java` 全文不 import `map.region`；`MarketTopologyBook.java:103-104, 153-162` 从城市 + `market.numeraire()` 现算 |

⇒ 裁定 ① 的"不能搞混"在**类型层已满足**；但 ① 的后半句"行政区**可以**重叠/包含/半包含"只落到**几何与读口**，
**没有**落到"抽税语义"（见 §4）。

---

## 4. 与 2026-10-23「D1 = 辖区互斥」的张力（**待裁定，本文不裁定**）

事实并列（都回代码核过）：

- **2026-10-23 裁定（仓库文档）**：`docs/superpowers/specs/2026-10-23-gov-service-mode-design.md:379`
  "**D1 = 辖区互斥：中央座位不入省辖**（用户当选）。实施方式（独立 central 区域 vs 辖区逐 hex 排除）由 Z7 排查/设计定；
  **不采用**"政府家户一律免税""。落到代码 = 独立 `capital-province`（`SmallWorld.java:437-455`）+ 互斥断言（`GovWorldBootstrap.java:234-237`）。
- **2026-10-08 裁定（本次派单书，逐字）**：行政区与市场区**理论上可以重叠、包含、半包含**。
- **代码事实**：`map` 模块允许重叠（`RegionOperations.java:30-31`），但**税**在重叠下逐区域累加（`JurisdictionDailyTax.java:314-427`），
  且**只排除本 GOV 自己的国库户**（`:321-323`）⇒ 重叠的辖区会导致重复征税（run6 实证：省抽中央国库 173 天，
  `reports/2026-10-23-fiscal-loop-investigation.md:13, 257`）。
- **已经存在"顶层"概念的**只有读口约定（`MapResolver.java:194` 定义序末位），**税里一次都没用**。

**待裁定的具体问题（留给控制方/用户，不自行选择）**：
若行政区允许重叠/包含/半包含，那么"同一 hex 上多个 GOV 都有税率 > 0"时，抽税应当按什么口径？
（a）各自全额（现状）；（b）只最顶层收；（c）加总上限；（d）不重叠才允许多税率……**代码今天实现的是 (a)**。
在口径未定之前，"D1 互斥"是使现状语义自洽的必要规避，而不是与 ① 冲突。

---

## 5. 我对「抽税 / 军俸」的明确回答（交账项 ③）

**问：抽税与军俸在代码上是两套独立实现吗？**
**答：是"两套独立推导 + 一条共享落账原语"。**

- 共享的只有最后一跳：`HouseholdStockDeduction` + `StockDeductionService`（+ `AvailableStock` 唯一减法 + `DeductionReason` 词表）。
  证据：`JurisdictionDailyTax.java:378-394` 与 `PeriodicHouseholdAdjustmentExecutor.java:338-345` 各自构造同一种 deduction 后调同一个服务；
  服务语义单点 `StockDeductionService.java:40-181`。
- 不共享的是：政策存储（`Jurisdiction.taxRatePerMilleByRegion` vs `MilitaryPayPolicy`）、推导时机（日循环内联 vs 派生成周期规则）、
  到期/周期（每 tick vs `periodDays/phaseDay`）、预算参与（无 vs `MILITARY_STIPEND` 类别 + 逐腿授权）、
  读账形状（`Report` 逐户粮税 vs `RuleReadout` 逐腿四组读数）。
- 另有**两条完全独立**的资源移动载体，连落账原语都不走：`actor.AdjustAccounts`
  （`LevyRegionTool` / `RaiseUnitTool` 的一次性抽取）与 `actor.RemitGovTreasury`（国库间转移，三条工具复用）。

**重复点清单（汇总，全部见 Q6 的 file:line）**：① 三处各自 `min(…, available)` 与 deduction 构造；
② 税不走规则引擎 ⇒ 支出有预算、收入无预算；③ `levyRegion` 的裸增量路径；④ `raiseUnit` 同款；
⑤ `actor.RemitGovTreasury` 第三条转移路径；⑥ 军俸桥 vs 工资桥两份同形派生；
⑦ 俸禄 vs 工资两条"国库→官吏户"实现；⑧ `RegionAllocations` 瀑布 vs `ProportionalSplit` 切分；
⑨ 税侧"逐区域累加" vs 需求侧"putIfAbsent 去重"；⑩ `DeductionReason` 词面（"从家户扣走"）与军俸实现（国库→军户支付）方向相反。

---

## 6. 行政区能否表达「重叠 / 包含 / 半包含」（交账项 ④ 的代码证据汇总）

- **重叠**：`RegionOperations.java:30-31`（"重叠一律允许…不裁剪"）、`RegionIndex.java:42-62`（全部保留、无去重）、
  `CreateRegionHandler.java:30` / `UpdateRegionHandler.java:31` / `ReassignHexesHandler.java:31`（命令层同口径）；
  run6 实测的"省辖区含中央座位"就是这个能力被用上的后果（`reports/2026-10-23-fiscal-loop-investigation.md:13, 257`）。
- **包含 / 被包含 / 完全相等 / 部分重合**：`MapOverlapsTool.java:300-314`
  （`equal` / `aContainsB` / `bContainsA` / `partial`，输入两份 hex 集，输出行含 `overlapHexCount`）。
  ⇒ **能判定、能读**，但**不存储为关系**，也没有任何税收/行政逻辑消费这个 `relation`。
- **"最顶层"**：仅 `MapResolver.java:176-203` 的**定义序末位**约定（`ApiViews.java:4739` 同口径）；
  `RegionIndex.java:21-22` 明说不承载层次 ⇒ 这不是层级，只是可复现的排序约定。
- **反证（不是层级）**：`Region` 无 parent/level 字段（`Region.java:18-19`）；`RegionMeta.annexedBy` 是自由串且无语义读取
  （`RegionMeta.java:8`；生产读取仅 `ApiViews.java:4884`、`WorldgenInitializeTool.java:1016`）。

---

## 7. 我没核到的（未验证清单，不得当成结论）

1. **未跑 Maven、未起服务、未读 live 世界库**：本报告全部为静态阅读；§2/Q2 引用的 run6/run7 数字（"省抽中央 173 天"等）来自仓库内报告原文，**我没有复核**这些读数本身。
2. **决策人能否经"令嵌入"改自己辖区的税率**：`DirectiveWhitelist` 的白名单 = 注册面 − `sd.*` − `simos.command.submit`
   （`simos-sd/.../spi/DirectiveWhitelist.java:36-49`），而 `unit.SetTaxRate` / `unit.SetJurisdiction` / `unit.SetArmyPayPolicy`
   **都不实现** `GmOnlyCommand`（`SetTaxRateHandler.java:34`、`SetJurisdictionHandler.java:44`、`SetArmyPayPolicyHandler`），
   `GovScope` 又恰授本单位 `unit:<id>` 前缀（`GovScope.java:136`）⇒ 从这三处看**像是可以**；
   但我**未核到**决策人 catalog / 审批链 / `AdjudicateTickTool` 是否另有拦截，**不下结论**。
3. **`unit.SetArmyPayPolicy` 的审批链**：未核到；inventory/HANDOFF 记"仍开"（`HANDOFF…:130`），未独立复核。
4. **`GovRemittanceState` 周期累计在"一次推 N 天 vs N 次推 1 天"两条路径下的一致性**：只读了单日 `settle` 语义，未做 1×N 对照。
5. **`hh-unit:*` 单位户会不会被别的 GOV 征税**：代码上税只排除**本 GOV 自己的**国库户（`JurisdictionDailyTax.java:321-323`），
   因此推断"会被抽"，但**未在真实世界观测**，也没找到对应用例。
6. **两条创世路径是否有世界同时使用**：`GovWorldBootstrap`（小世界，`capital-province`）与 `ProvinceApply`（`<nation>__CAP` + `__P<n>`）
   都写 map Region + jurisdiction；我**未核** `WorldRegistry` 全表与各 world 配置，不能断言二者互斥。
7. **测试覆盖**：未跑任何测试；只读了 `simos-app/src/test/java/io/mosire/simos/app/time/GovAdminSalaryBudgetZ6Test.java` 一处税用例
   （`:833-870`，效率不封顶），**未**逐条清点 `JurisdictionDailyTax` / 军俸 / 重叠相关的用例覆盖（尤其"重叠 ⇒ 重复征税"是否有反证用例）。
8. **`MapOverlapsTool` 的实际使用**：只核到工具注册与实现，**未核**前端/GM 流程是否真的在用它做区划检查。
9. **`ProvinceDivider` 的产省结果与 `capital-province` 的关系**：只读了它的"重叠不影响建议"口径（`:52-54`），
   **未核**在实际世界生成时两套首部区约定是否撞车。
10. **行号会漂**：本报告所有行号取自 `HEAD = 5ae583e1`。顺带一个实例——`reports/2026-10-23-fiscal-loop-investigation.md:257`
    引的 `GovWorldBootstrap:336-350` 在**当前**文件里已是家户创建段（当前 234-237 才是互斥断言），
    ⇒ 引用旧报告的 file:line 时必须回代码重核（AGENTS §四）。

---

## 8. 交账速览

- **一句话**：行政区（`map.Region`）几何上支持重叠/包含/半包含且有多对多从属与只读关系判定，但**税收语义只实现"逐区域累加"**，
  缺去重/顶层优先/跨 GOV 去重；军俸与抽税**只共享最后一跳落账原语**，推导/预算/周期是三套以上独立实现，
  另有一整条 `actor.AdjustAccounts` 的一次性抽取与 `actor.RemitGovTreasury` 的国库转移各自成路。
- **缺口三档**：见 Q8（有代码 11 项 / 半成品 9 项 / 完全没有 6 项）。
- **抽税 vs 军俸**：两套独立实现 + 一条共享落账原语；重复点 10 条，见 Q6 与 §5。
- **重叠/包含/半包含**：能表达、能判定、不能作为语义被消费，见 §6。
- **用户 2026-10-08 两条原话**：本报告首次落盘引用（此前仓库 0 命中）；与 2026-10-23「D1 辖区互斥」的张力见 §4，**列为待裁定**。
