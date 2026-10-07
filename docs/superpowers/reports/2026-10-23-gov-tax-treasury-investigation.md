# 2026-10-23 GOV / 行政模块：税、国库、俸禄、军俸、铸币、编制覆盖 只读排查报告

> 任务：把 F1~F4 查透，产出给用户裁定用的问题表与方案对比。
> 纪律：本报告是**唯一写盘产物**；代码、日志、文档均为只读；**未跑 Maven / 测试 / 服务**；
> 证据基线 = `docs/superpowers/reports/2026-10-23-sw19-economy-360tick-vs-p2.md`（run2）
> + `docs/superpowers/reports/2026-10-23-sw19-run5-efficiency-formula-vs-run4.md`（run5 与 run4 逐字节一致）
> + 现场日志 `/home/cna/simos-runs/2026-10-23-sw19-run5/service.log`（全 DEBUG，本文引用数字全部来自该文件）
> + `/home/cna/simos-runs/2026-10-23-sw19-run5/dumps/seg-030.json`（逐格 dump）。
> 所有“0 命中”结论都在 §11.1 给了同一命令的已知命中对照。

---

## 1. 一句话结论 + 因果链（文字版）

**一句话**：19 hex / 360 tick 的真实执行里，政府的“税—国库—俸禄—军俸—铸币”链条**每一段都单独存在，但接口口径互相接不上**：
税收公式被 `YAMEN=0` 经 `min(securityCoverage, paperworkCoverage)` 归零；两个运行期 GOV 的政府家户账户**创世零库存且没有初始注资命令**，
于是行政俸禄全额 sink 0、军俸三个周期全部 `no-payable-leg`；铸币政府 `world-silver` 只是 `economy.governments` 里的一条
`Government` 记录、**没有 `Unit` 因而没有 `GovernmentFormation`**，`simos.gov.remit / simos.gov.pay` 按单位解析所以够不着它；
而按 `GovRules` 现行常数，19 格世界一个省需要 **100 YAMEN + 139 SCRIBE/POST = 239 名吏员**，运行期 GOV 只有 2 名 `SCRIBE`。

**因果链（文字版）**

```text
F1 税
  unit.SetTaxRate ──> Jurisdiction.taxRatePerMilleByRegion[{small-world:100}]     [unit 侧长期税率，无期限/免税]
  GovDemand.of ──> 逐格 ceil(pop/500)+城市40、ceil(pop/1000)+城市60
                   => 19 格世界总需求 security=100 / paperwork=139（tick0 人口 17×200+900+500）
  GovEfficiency.of ──> securitySupply=YAMEN=0；paperworkSupply=SCRIBE+POST=2
                   => securityCoverage=0；paperworkCoverage=14‰；coverageMin=0；efficiency=0‰
  JurisdictionDailyTax.assess ──> assessed=floor(balance×100‰)
                               attainable=floor(assessed×0‰)=0
                               collected=min(attainable,available)=0
  => grainAdminShortfall=assessed；不构造 HouseholdStockDeduction；不调 StockDeductionService；
     不写 FlowRow.taxPaid；国库家户账户余额不变；TAX_DAILY_END unitsCharged=0

F2 国库 / 俸禄 / 军俸
  创世/建 GOV：social.CreateHousehold(UNIT) + economy.RegisterGovernment(0 人口/0 参与/空 issuable/0 铸币)
             + actor.EnsureHouseholdAccount（零余额账户）——**批内没有任何注资命令**
  world-silver：EconomySeeder 给 hh-gov-world-silver 的 stocks/money 都是 Map.of()；
             只有 GovernmentSeigniorage 每 120 天给 world-silver 自己记 2000 毫银（FISCAL_ISSUE）
  GovDaily.settle ──> grainNeed=staff×83=2×83=166；clothNeed=staff×floor(1000/365)=2×2=4；moneyNeed=0
  GovernmentUpkeepOracle.pay ──> available=0 => paid=0 => GOV_UPKEEP_NO_STOCK（grain/cloth 各 1 条/天）
                              => 六表 paid=0、shortfall=need => ADMIN_SUPPLY 危机信号
  军俸：MilitaryPayPolicy -> MilitaryPayRuleBridge -> HouseholdPeriodicAdjustment ->
        PeriodicHouseholdAdjustmentExecutor：payer=hh-gov-<masterGov>，AvailableStock=0 =>
        全部腿 paid=0 => status=SKIPPED gap=no-payable-leg（day2/122/242）
  缺：GOV 工具够不着 world-silver；无“国库共享预算/优先级”；P4c 未做

F3 铸币
  SmallWorld：Government(world-silver, treasury=HOUSEHOLD:hh-gov-world-silver,
              issuable={silver}, seignioragePerCycle=2000, debtIssuePerCycle=5000)
  GovernmentSeigniorage.settleCycleStart：day1/121/241 各 +2000 毫银到同一家户账户，写 FISCAL_ISSUE
  但没有 unit.CreateUnit / unit.SetGovFormation 为 world-silver 建 GOV 单位
  => GovernmentIds.unitRefOf(world-silver) 为空；GovernmentHouseholdWiring 明确跳过世界级政府；
     simos.gov.remit/pay 都要求 to/from 是带 GovernmentFormation 的 Unit => 够不着
  => world-silver 与运行期 GOV 无任何自动财政连接；M1 铸币生产方式（moneyOutputPerUnit / MintRule）
     在代码里 0 命中（见 §11.1）

F4 编制
  GovRules：500 人/YAMEN、1000 人/SCRIBE+POST、城市 +40 治安 / +60 文书
  19 格：17 农村格×200（各 1+1）+ 首都 900（42+61）+ 镇 500（41+61）
       = YAMEN 100 / SCRIBE+POST 139
  运行期 GOV：SCRIBE=2、YAMEN=0 => F1；`simos.gov.applyStaffing` 可按此公式精确配满，
  但它只改 GovernmentFormation.staff 的“账面人数”，不扣人口/劳动；编制口径本身待用户裁定。
```

---

## 2. 运行装置与证据基线（先钉住数字）

- **世界**：`SmallWorld` 19 hex（半径 2 完整六边形），1 Region，1 首都 `(0,0)`（本格 900 人），1 镇 `(0,2)`（本格 500 人），
  其余 17 格各 200 农村人口；tick0 总人口 4,800。
  代码：`simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java:117-145`（HEX_COUNT/CAPITAL/TOWN/人口常量）、
  `:173-193`（19 格表）、`:373-404`（人口/城市计划）。
- **tick0 建场**（真 GM 工具；脚本 `/home/cna/simos-runs/2026-10-23-sw19-run5/setup_tick0.py`）：
  - `simos.gov.createOffice`：`gov-central`（CENTRAL，无辖区，staff `{SCRIBE:2}`）、
    `gov-province`（PROVINCE，辖 `small-world`，staff `{SCRIBE:2}`，上级 central）；
  - `unit.SetTaxRate`：`gov-province` / `small-world` / `100‰`；
  - 两支军队 + 两条军俸（period=120, phase=1, starts=1：首都 300 粮/30 银、镇 180 粮/18 银）。
  - **没有任何注资/转账命令**（日志里 `event=ACTOR_ACCOUNTS_ADJUSTED` = 0，见 §11.1）。
- **推进**：12 段 × 30 tick（tick 0→360），`/api/advance`；eco/app/gov/social/unit 全 DEBUG，TRACE 未开。
- **run5 `service.log` 事件计数**（命令见 §11.1）：

  | 事件 | 条数 | 备注 |
  |---|---|---|
  | `TAX_DAILY_END` | 360 | 每天一条 |
  | `GOV_OFFICE_UPKEEP_EVALUATED` | 720 | 2 GOV × 360 天 |
  | `GOV_UPKEEP_NO_STOCK` | 1440 | 2 GOV × 360 天 × 2 资源（grain/cloth；moneyNeed=0 不调用） |
  | `MILITARY_PAY_BRIDGE` | 360 | 每天 units=2 policies=2 rules=2 gaps=0 |
  | `PERIODIC_ADJUSTMENT_DAY` | 3 | day2/122/242 |
  | `PERIODIC_ADJUSTMENT_RULE` | 6 | 3 个到期日 × 2 条规则，全部 SKIPPED no-payable-leg |
  | `GOV_SEIGNIORAGE` | 3 | world-silver only |
  | `GOV_DAILY_START` / `GOV_DAILY_END` | 360 / 360 | offices=2 |
  | `GOV_DEMAND_COMPUTED` | 1440 | 2 GOV × 360 天 × 2 次（税表 + GovDaily 各算一次） |
  | `TAX_UNIT_SKIPPED` | 360 | 全部 `unit=gov-central reason=no-jurisdiction` |
  | `TAX_NO_GOVERNMENT_HOUSEHOLD` | 0 | 见 §11.1 |
  | `TAX_FLOW_ROW_MISSING` | 0 | 见 §11.1 |
  | `ACTOR_ACCOUNTS_ADJUSTED` | 0 | 没有任何 GM 手工调账 |

- **逐段汇总 `GOV_ADMIN_ADVANCE_END`**（每 30 天一条，共 12 条）：
  - day30：`taxGrainAssessed=249,431,806 taxGrainCollected=0 taxSilverAssessed=207,260 taxSilverCollected=0
    upkeepPaidGrain=0 upkeepPaidCloth=0 upkeepPaidMoney=0 upkeepShortfallTotal=10,200 signals=120`
  - day360：`taxGrainAssessed=6,386,305,151 taxGrainCollected=0 taxSilverAssessed=218,377 taxSilverCollected=0
    upkeepPaidGrain=0 upkeepPaidCloth=0 upkeepPaidMoney=0 upkeepShortfallTotal=10,200 signals=120`
  - ⇒ 360 天全口径：**粮税评估 6,386,305,151 毫，实收 0；银税评估 218,377 毫，实收 0；行政实付全 0**。

- 单日原始行（run5）：
  - day1 `TAX_DAILY_END ... grainAssessed=8,804,046 grainCollected=0 grainAdminShortfall=8,804,046 ...
    moneyAssessed=6,920 moneyCollected=0 ... unitsCharged=0 householdsCharged=0 gaps=0`
  - day360 `TAX_DAILY_END ... grainAssessed=386,855,636 grainCollected=0 ... moneyAssessed=7,281 moneyCollected=0`
  - day1 `GOV_OFFICE_UPKEEP_EVALUATED unit=gov-central hex=0_0 staff=2 grainNeed=166 grainPaid=0 clothNeed=4 clothPaid=0 moneyNeed=0 moneyPaid=0`
  - day1 `GOV_UPKEEP_NO_STOCK unit=gov-central treasury=hh-gov-gov-central resource=grain requested=166 available=0`
  - day2 `PERIODIC_ADJUSTMENT_RULE rule=army-pay:unit-capital-guard:hh-unit:unit-capital-guard status=SKIPPED
    gap=no-payable-leg payer=hh-gov-gov-central payee=hh-unit:unit-capital-guard shortfallGoods={grain=300} shortfallMoney={silver=30}`
  - day1/121/241 `GOV_SEIGNIORAGE government=world-silver currency=silver amount=2000
    accountActor=HOUSEHOLD:hh-gov-world-silver`（treasuryBefore→After：0→2000、1532→3532、3556→5556）
  - `ECONOMY_SEED ... genesisEndowment={silver=67200}`

---

## 3. F1 — 税：assess→collect 断在行政效率

### 3.1 事实链（代码拼写点 + 运行时字段）

**入口与状态**

1. `unit.SetTaxRate` 命令：`simos-unit/src/main/java/io/mosire/simos/unit/spi/SetTaxRateHandler.java:22-83`
   → `UnitOperations.setTaxRate`：`simos-unit/.../ops/UnitOperations.java:733-770`
   → 只把 `Jurisdiction.taxRatePerMilleByRegion` 的一个键 upsert 为 `[0,1000]‰`；
   `Jurisdiction` 结构见 `simos-unit/src/main/java/io/mosire/simos/unit/Jurisdiction.java:42-70`。
   **没有期限、没有免税实体、没有税率历史、没有自动恢复**（§7 顺带核对）。
2. GOV 单位的“编制”与“读数”是**两处状态**：
   - 编制/政策在 **unit 片**：`GovernmentFormation.staff`（`simos-unit/.../GovernmentFormation.java:34-45,65-90`），
     角色词表 `StaffRole{SCRIBE,YAMEN,POST}`（`StaffRole.java`）。**实际有哪些角色键取决于写者给了什么**：
     run5 的 `staff` 只有 `{SCRIBE:2}`，没有 `YAMEN`/`POST` 键（`GovernmentFormation.staff` 缺角色 = 0）。
     写者：`unit.SetGovFormation`（整套替换 `staff`；`SetGovernmentFormationHandler` →
     `UnitOperations.setGovernmentFormation:812-860`）、`unit.RecruitStaff`（`staff[role] += count`，
     `RecruitStaffHandler.java:29-40` 明说“只入编、不扣人”）、`unit.RetireStaff`/`unit.DismissStaff`；
     组合口：`simos.gov.createOffice`（初始 staff）、`simos.gov.applyStaffing`（按 `GovDemand` 精确配满）。
   - 每 tick 读数在 **gov 片**：`GovOfficeState`（`simos-gov/.../GovOfficeState.java:43-55`）。
     **它没有 staff 字段**；只有六张 assessed/paid/shortfall 表 + `securityCoveragePerMille /
     paperworkCoveragePerMille / efficiencyPerMille / bonusPerMille`。
     `GovState.offices: Map<UnitId, GovOfficeState>`（`GovState.java:13-39`）。
3. `PopulationEconomyTimeParticipant` 是 app 侧编排口：
   `simos-app/.../time/PopulationEconomyTimeParticipant.java:296-301`（`withBootstrapOffices`，为每个
   `GovernmentFormation` 单位补 `GovOfficeState.empty`）、`:531-572`（税 → GovDaily）、
   `:849-881`（`efficiencyTable`，逐 GOV 现算需求/效率）。

**需求算法（GovDemand）**

- `GovDemand.of`：`simos-gov/.../GovDemand.java:62-109`；逐格公式 `:86-91`：
  - `security = ceil(populationAt(hex) / GovRules.SECURITY_PER_OFFICER) + (cityOnHex ? GovRules.CITY_SECURITY_WEIGHT : 0)`
  - `paperwork = ceil(populationAt(hex) / GovRules.PAPERWORK_PER_SCRIBE) + (cityOnHex ? GovRules.CITY_PAPERWORK_WEIGHT : 0)`
  - 城市 = `social.cities()` 的 `SocialCity.at()` 现算（`:112-118`）；人口走 `SocialData.populationAt(hex)`（唯一人口权威）。
  - 只遍历 `unit.jurisdiction().taxRatePerMilleByRegion().keySet()`；**没有 jurisdiction（如 gov-central）= 空需求表**，
    效率算 1000‰，但税侧因无管辖 region 整单位跳过。
- 常量唯一拼写点：`GovRules.java:48`（`SECURITY_PER_OFFICER=500`）、`:57`（`PAPERWORK_PER_SCRIBE=1000`）、
  `:66`（`CITY_SECURITY_WEIGHT=40`）、`:75`（`CITY_PAPERWORK_WEIGHT=60`）。
  注释明确它们是“**暂定值**，阶段 15 长跑校准”（`:20-36`）。

**效率算法（GovEfficiency）**

- `GovEfficiency.of`：`simos-gov/.../GovEfficiency.java:65-93`。逐步：
  1. `securitySupply = staff[YAMEN]`（`:95-99`）；`paperworkSupply = staff[SCRIBE] + staff[POST]`（`:101-106`）。
  2. 需求汇总 = 逐格求和（`:108-126`）。
  3. `coverage = (demand==0) ? 1000 : min(1000, floor(supply×1000/demand))`（`:128-136`）。
     **clamp 的是 coverage，不是 supply**：供给再大 coverage 也封顶 1000；供给为 0 且有需求 ⇒ coverage=0。
  4. `bonus‰` **只在两维 coverage 都 = 1000 时**计算：
     `s = floor(100×Σ剩余/Σ需求)`（需求 0 的维 skip），`bonus = min(100, floor(100×s/(s+10)))`（`:78-82,138-164`）。
  5. `coverageMin = min(securityCoverage, paperworkCoverage)`；
     `efficiency = min(1100, floor(coverageMin × (1000 + bonus) / 1000))`（`:84-90`）。
  - `Efficiency` 构造期界：coverage ∈ [0,1000]、bonus ∈ [0,100]、efficiency ∈ [0,1100]（`:195-225`）。
- **权重**：治安路和文书路在 `efficiency` 里是**等权 min 门**，没有“仅治安降级”“文书补治安”的加权；`POST` 与 `SCRIBE`
  在供给端完全同权（都是 paper work），需求端没有 POST 专项。

**征收算法（JurisdictionDailyTax）**

- `JurisdictionDailyTax`：`simos-app/.../time/JurisdictionDailyTax.java:41-85`（口径注释）、`:113-458`（主流程）。
  1. 逐 unit id 升序；`efficiencyPerMilleByUnit` 里**查不到** ⇒ 整单位跳过、不征（`:159-163`）；
     无 jurisdiction / 无 rated region ⇒ 跳过（`:169-205`）。
  2. 国库 = `GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unitId.value())`（`:238-261`）；
     即政府家户 `hh-gov-<unitId>`。
  3. 税基 = 区域内各 hex 的家户行（`householdEconomies.view().hex()`，`:135-144`），排除国库家户自身（`:310-312`）；
     每个家户按 `HouseholdId` 升序、region hex 按 `HexCoord.toString()` 排序（`:129-133,303-308`）。
  4. **算式**（`:467-484`）：
     - `assessed = floor(balance × ratePerMille / 1000)`（粮取 `balances[grain]`，银取 `money[silver]`；余额 ≤0 跳过）
     - `attainable = floor(assessed × efficiencyPerMille / 1000)`
     - `available = AvailableStock.available(inventory, asset)`（余额 − 冻结，唯一算法）
     - `collected = min(attainable, available)`
     - 恒等式：`adminShortfall = assessed − attainable`，`stockShortfall = attainable − collected`，
       `collected + adminShortfall + stockShortfall == assessed`（`:70-72,638-644`）。
  5. `collected > 0` 才构造 `HouseholdStockDeduction.transfer(household, treasury, taxGoods, taxMoney,
     DeductionReason.JURISDICTION_TAX, detail)` 并调 `StockDeductionService.deduct`（`:353-381`）。
  6. `TAX_DAILY_END` INFO（`:428-456`）逐日字段：unitsCharged / householdsCharged / grainAssessed /
     grainCollected / grainAdminShortfall / grainStockShortfall / moneyAssessed / moneyCollected /
     moneyAdminShortfall / moneyStockShortfall / gaps；逐笔 `TAX_COLLECTED` 只在 TRACE（`:387-411`）。
- **落账物理路径**：`StockDeductionService.deduct`（`simos-app/.../time/StockDeductionService.java:75-181`）
  → 影子校验 → `AccountSession.commit`；日末 `PopulationEconomyTimeParticipant` 调
  `OwnershipBooks.landAccountSession`（`OwnershipBooks.java:259-282`）把会话绝对值写回
  `actor` 切片的 `ActorData.accounts: Map<HouseholdAccountKey, HouseholdInventory>`
  （`HouseholdAccountKey` 现在 = 家户 id，`simos-actor/.../model/HouseholdAccountKey.java:13-30`）。
  GUI 的 `treasuryAccounts` 不是第二本账，只是 `/api/economy/gov` 对 `actors.accounts()` 按
  `government.treasury()` 过滤出的**只读投影**（`ApiViews.java:1596-1714`）。
- **粮/银落点相同**：都进 `hh-gov-<unitId>` 这一本账的 `balances[grain]` / `money[silver]`；
  区别只在 FlowRow：粮税会由 `EconomyDayStepper.recordTaxPaid` 写 `FlowRow.taxPaid` 并扣 `netSurplus`
  （`simos-economy/.../time/EconomyDayStepper.java:195-212`；`FlowRow.java:203-236`），
  **银税没有 FlowRow 位**（`FlowRow` 只有 `taxPaid` 一个粮口径标量，`:39-41,65-71`）。

**运行时证据（run5）**

- `TAX_DAILY_END` 360/360 天 `grainCollected=0 / moneyCollected=0`，
  `grainAdminShortfall == grainAssessed`、`moneyAdminShortfall == moneyAssessed`，`gaps=0`。
- `TAX_UNIT_SKIPPED` 360 条全部是 `unit=gov-central reason=no-jurisdiction`（gov-central 无辖区，不是故障）。
- `gov-province` 的 `GOV_DAILY_END` signals=4：`ADMIN_SUPPLY` ×2（central/province 各 1）+
  `ADMIN_SECURITY` ×1（province，hex 0_1）+ `ADMIN_PAPERWORK` ×1（province，hex 0_1）。
  dumps/seg-030.json 的 `crisisSignals` 给出面积：
  `ADMIN_SECURITY: {coveragePerMille:0, supply:0, demand:101}`、
  `ADMIN_PAPERWORK: {coveragePerMille:14, supply:2, demand:139}`（tick30 人口已从 4800 变 4970，
  所以 demand 是 101 不是 100；tick0 按常数就是 100）。

### 3.2 根因

1. **直接根因**：`gov-province` 的 `staff` 只有 `{SCRIBE:2}`，`YAMEN` 缺键 ⇒ `securitySupply=0`；
   辖区有治安需求（100 或以上）⇒ `securityCoverage=0`；`coverageMin=0` ⇒ `efficiency=0`；
   `attainable = assessed × 0 = 0` ⇒ `collected=0`，全部 assessed 记成 `adminShortfall`。
   这不是“税路断了”，而是**公式按现状正确地**把“零治安编制”解释成“零行政效率”。
2. **回答“为什么不是仅治安路降级”**：`GovEfficiency` 把两维 coverage 取 `min` 后作为**唯一**效率乘数，
   没有“治安路单独失败、文书路仍可收部分税”的分路征收；所以一维为 0 ⇒ 全口径为 0。
3. **回答“paperworkSupply 是否被 min 到 0”**：没有。`paperworkSupply = SCRIBE+POST = 2`；
   `paperworkCoverage = floor(2×1000/139) = 14‰`。被 min 的是 **coverage 两维**，不是 supply；
   而 coverage 的 `min(…,1000)` 是**上限钳制**，不是下限。
4. **制度根因（比公式更深）**：
   - `GovRules` 四个需求常量是“暂定值”，但没有任何初始化/自动配员路径；`simos.gov.createOffice` 的 `staff` 由调用方给全，
     缺省空表（`GovCreateOfficePlan.java:130-131,367-381`），没人按 `GovDemand` 自动配；
     run5 脚本给了 `SCRIBE:2` 没给 `YAMEN`（`setup_tick0.py`），于是踩中公式。
   - 税基是**家户存量余额 × 税率**，不是收入/流量；`available` 只排除冻结，不保护基本口粮——若效率修好，
     现行算式可以税收把家户扣到 0（`:467-484`）。
   - 重叠管辖按单位 id 顺序**重复征收**：同一 region 若被两个 GOV 管辖，两边各自 `assessed`，
     没有去重/分摊（`:70-74` 的具名口径；inventory 也列了这条风险）。

### 3.3 候选方案（改动面 / 影响 / 与铁律 2/3/4 的关系 / 风险）

| 方案 | 改动面 | 影响 | 与铁律 2/3/4 的关系 | 风险 |
|---|---|---|---|---|
| **F1-A 配员（不改公式）** | 运行期：GM 调 `simos.gov.applyStaffing`（`GovApplyStaffingTool.java:269-278` 目标 `YAMEN=securityDemand, SCRIBE=paperworkDemand, POST=0`）；世界侧：`SmallWorld`/`createOffice` 调用方补 `YAMEN` | 立即让 `gov-province` 达到 100/139，efficiency=1000‰；税开始收 | 工具提交 `unit.SetGovFormation` 批 → 走 Command→ChangeSet→Revision，符合铁律 2；unit 仍拥有 staff，gov 只算读数，符合铁律 3；app 只做组合，符合铁律 4 | 编制只是**账面人数**：不扣人口/劳动/装备；239 人≈4.98% 人口，经济上没有成本，容易“纸面养官”；不解决 F4 口径 |
| **F1-B 效率公式加权/分路** | `simos-gov/GovEfficiency.java` | 把 min 门改成加权/几何平均，或治安/文书各自收一部分税；YAMEN=0 时仍能收部分税 | 纯函数改动，不新增写口，不影响铁律 2/3/4 | 掩盖“零治安”事实；与类注“治安/文书互不通用”“两维都满才有 bonus”的既有语义冲突；需要重写 golden 测试期望；口径要用户定 |
| **F1-C 税基/最低口粮保护** | `simos-app/time/JurisdictionDailyTax.assess` + 可能 `FlowRow`；需要 Social 的“最低口粮”权威 | 税收不再能把家户扣到 0；收入下降 | 仍在 app 日结算的账户会话内，属 advance revision 的一部分，不绕铁律 2；app 同时认识 social/actor，符合铁律 4；Social 仍是需求权威，符合铁律 3 | “最低口粮”口径未定；可能抑制税基；需要处理冻结/欠税 |
| **F1-D 重叠管辖去重/分摊** | `simos-unit`（管辖互斥或 `Jurisdiction` 变更）+ `JurisdictionDailyTax` | 消除重复征税 | 管辖是 unit 侧数据，符合铁律 3 | 既有世界可能依赖重叠；需要迁移/旧档口径 |
| **F1-E 税率期限/免税实体** | 见 §7；`Jurisdiction` + 征收读取当日有效税率 | 支持“免税半年、到期恢复” | 政策归 unit；写入走新/既有命令 | 见 R9 |

### 3.4 分类

- **实现缺口**：银税无 FlowRow 位；没有按 `GovDemand` 自动配员/初始化路径；没有税率期限/免税/历史回滚。
- **参数标定**：`GovRules` 的 500/1000/40/60 与 19 格世界的现实不匹配；`simos.gov.createOffice` 的 staff 缺省空表；
  run5 只配 `SCRIBE:2`。
- **待用户裁定**：一维零覆盖是否应当全税归零；税基是存量还是流量、是否保护最低口粮；重叠管辖如何处理。

---

## 4. F2 — 国库空 + 日常俸禄 0 支付 + 军俸 no-payable-leg

### 4.1 GOV 家户的账户到底存在哪

- **`HouseholdEconomy` 不是账**：它是 economy 片的人口/劳动/参与率/需求行；AGENTS §〇 明写
  “`HouseholdEconomy.population/laborMilli/naturalNeeds` 是 app 从 Social 展开后注入的当日物化视图”，
  并且 goods 不在阶层行里（裁定 D3-C/K1，见 `EconomySeeder.java:76-90`）。
- **物理账 = actor 片**：`ActorData.accounts`，键 `HouseholdAccountKey(HouseholdId)`、值 `HouseholdInventory`
  （商品/货币/冻结四张表）。政府家户的键就是 `hh-gov-<unitId>`；账户由 `actor.EnsureHouseholdAccount` 建
  （零余额、幂等；run5 日志 `event=HOUSEHOLD_ACCOUNT_ENSURED created=true`）。
- **推进期唯一会话**：`OwnershipBooks.loadAccountSession(economy, actor)`（`OwnershipBooks.java:217-256`）
  把 `economy.classes` 的每个家户对应的 actor 账装进 `AccountSession`；缺账直接 fail-closed
  （“家户 actor / 账本缺失 …”），不把缺账当 0。日末 `landAccountSession`（`:259-282`）按绝对值写回 actor。
- **`treasuryAccounts` 是什么**：`ApiViews.economyGovernment` 的只读视图字段
  （`simos-app/.../gui/ApiViews.java:1596-1714`）：对 `EconomyData.governments` 逐个政府，把它
  `government.treasury()` 指向的 actor 在 `actors.accounts()` 里**过滤出来**的 `HouseholdInventory` 投影
  （goods/money/frozen/available）。它**不是第二本账**，也不做写入；F2 报告里“treasuryAccounts 是空账”即
  该投影的 `{goods:{}, money:{}}`。

### 4.2 创世时有没有初始库存 / 注资路径

- **world-silver（小世界内置政府）**：`EconomySeeder.seedGovernmentHousehold`
  明确 `householdStocks.put(householdId, Map.of())`、`householdMoney.put(householdId, Map.of())`
  （`simos-app/.../world/EconomySeeder.java:3424-3425`），行也是 `population=0 / laborMilli=0 /
  participationPerMille=0 / naturalNeeds={} / effectiveDemand={}`（`:3449-3457`）。
  ⇒ **创世没有粮/布/银库存**；`GovernmentSeigniorage` 之后每 120 天记 2000 毫银（见 F3），但没有任何商品。
- **运行期 GOV（`simos.gov.createOffice`）**：`GovCreateOfficePlan.commandTypes()` 的顺序是
  `unit.CreateUnit → social.CreateHousehold → actor.EnsureHouseholdAccount → unit.SetGovFormation →
  economy.RegisterGovernment → [unit.SetJurisdiction] → sd.CreateDecisionMaker → …`
  （`GovCreateOfficePlan.java:327-343`）；`registerGovernmentPayloadJson` **不传**
  `population/labor/issuable/seigniorage/debt`（`:419-425`），handler 新建取 0/空集
  （`EconomyRegisterGovernmentHandler.java:318-325`）。⇒ **建 GOV 与注资刻意分开，本批没有任何自动注资**。
- **现有可用的“注资/转移”路径**（都是 COMMAND，不是直接改账）：
  1. `actor.AdjustAccounts`（GM-only 裸账目原语；工具 `ActorAdjustAccountsTool` 在 GM 桶，
     `SimosToolSource.java:644`；缺账只允许纯正增量新建，`AdjustAccountsHandler.java:146-190`）——
     目前唯一能不碰 GOV 语义、直接给任意家户账户加/减库存的宽路径。
  2. `actor.RemitGovTreasury`（**载荷已是 `fromHousehold`/`toHousehold`**
     `RemitGovTreasuryHandler.java:149-157`，非 GmOnly）——GM 可用
     `simos.command.submit` 直接发（`CommandSubmitTool` 只加在 GM 分桶，`SimosToolSource.java:477,494`）。
  3. `simos.gov.remit`（GM 窄工具；`SimosToolSource.java:649`）和 `simos.gov.pay`（决策人窄工具；
     `:790`）都只解析**GOV 单位**：见 4.4。
  4. `simos.gm.periodicAdjustment`（GM 桶，`:741`）可建任意两 household 的周期转移规则
     （`GmPeriodicAdjustmentTool.java:54-95`），能从 world-silver 转银给 GOV —— 但 world-silver 没有粮/布，
     所以这条也注不了行政/军俸需要的实物。
  5. `simos.unit.levyRegion`（一次性抽取，受 `Jurisdiction.levy*CapPerCommand` 上限；
     run5 的 `levy*Cap` 全 0 ⇒ 本世界不可用；`LevyRegionPlan.java:156-167`）。
- **结论**：运行期 GOV 家户在创世是**零余额账户**；world-silver 只有**银**且只进自己账；
  **没有一条 GOV 语义下的自动注资或上缴路径**把 world-silver 的银/粮送进运行期 GOV。

### 4.3 GovDaily / 办公室 upkeep 的结算链

- 日循环顺序（`PopulationEconomyTimeParticipant.java:529-602`）：**税（收入）→ GovDaily（支出）→ 军俸派生+执行**，
  全部共用同一个 `AccountSession` 和 `SettlementStage.TAX_AND_UPKEEP`。
- `GovDaily.settle`（`simos-gov/.../GovDaily.java:125-458`）：
  - 逐 office 按 `UnitId` 升序；每个 office 先算 `GovDemand` + `GovEfficiency`（`:224-225`）；
  - 有效位置为空 ⇒ 只更新效率读数、六表清空、不发信号（`:227-245,424-440`）；
  - 有位置 ⇒ 读 `OfficePolicy`（`:249`），`totalStaff = Σ staff`（`:488-494`），
    `grainNeed = totalStaff × policy.grainPerStaffPerTick()`，
    `clothNeed = totalStaff × floor(policy.clothPerStaffPerCycle / daysInYear)`，
    `moneyNeed = totalStaff × policy.moneyPerStaffPerTick()`（`:251-254`）。
    默认政策 `grainPerStaffPerTick=83`（`EconomyVocabulary.dailyRationMilli(1,1)`）、
    `clothPerStaffPerCycle=1000 / 年`、`moneyPerStaffPerTick=0`
    （`OfficePolicy.java:61-81`）。⇒ staff=2 ⇒ 166 粮 / 4 布 / 0 银，与日志逐值一致。
  - 逐资源按 grain→cloth→money 调 `PaymentOracle.pay`（`:256-258,502-524`），实付必须 ∈ [0,requested]；
  - 六表写 assessed/paid/shortfall；差值发 `ADMIN_SUPPLY`；coverage<1000 各发 `ADMIN_SECURITY/ADMIN_PAPERWORK`
    （`:294-345`）。
- **付款扣款路径**：`GovernmentUpkeepOracle`（`simos-app/.../time/GovernmentUpkeepOracle.java:50-182`）：
  - 从 `GovernmentHouseholdResolver.requireGovernmentHousehold` 拿 `hh-gov-<unitId>`，查会话账户；缺账/解析失败 → `IllegalStateException`（不静默）；
  - `available = AvailableStock.available(inventory, resource)`；`paid = min(available, requested)`；
  - `paid<=0` ⇒ DEBUG `GOV_UPKEEP_NO_STOCK`，字段 `day/unit/treasury/resource/requested/available`（`:99-117`）；
  - `paid>0` ⇒ `HouseholdStockDeduction.sink(treasury, goods, money, DeductionReason.ADMIN_UPKEEP, detail)`
    + `StockDeductionService.deduct`（`:131-148`）。**行政俸禄当前是明确 sink**：扣了库存但没有收款家户
    （类型与注释见 `HouseholdStockDeduction.java:12-30,120-130`；`DeductionReason.java` 的 `ADMIN_UPKEEP` 注释：
    “付款方 = 政府家户，当前无可信对端 ⇒ 明确 sink”）。
- **零支付的具名日志**（run5 全 360 天）：
  - `GOV_OFFICE_UPKEEP_EVALUATED` DEBUG 720 条：`staff=2 grainNeed=166 grainPaid=0 clothNeed=4 clothPaid=0 moneyNeed=0 moneyPaid=0`；
  - `GOV_UPKEEP_NO_STOCK` DEBUG 1440 条：grain/cloth 各 720，`available=0`；
  - `GOV_DAILY_END` INFO 360 条：`dues=6 signals=4 changed=true`；
  - crisis signal 折进 economy 状态（`putCrisisSignal`，`:572`；`toCrisisSignal` 映射表 `:883-901`）。
  - 默认 INFO 级没有“为什么付 0”的逐笔行；`GOV_UPKEEP_NO_STOCK` 是 DEBUG，需要 DEBUG 日志级别才看得到（run5 已开）。
- **root cause**：两个 GOV 家户账户都是**空账**（grain/cloth/silver 全 0），不是“付款 oracle 没接线”或“gov 读数错”；
  `GovernmentUpkeepOracle` 的 min(available,requested) 正确地把实付置 0 并让 `GovDaily` 记 shortfall + 发信号。

### 4.4 `simos.gov.pay` / `simos.gov.remit` 现状与可达性

- `simos.gov.remit`（GM-only，`GovRemitTool.java:69-435`）：
  - 参数是 `fromGovUnitId / toGovUnitId`；`resolveGov` 要求单位存在、带 `GovernmentFormation`、有当刻有效位置
    （`:260-283`）。底层提交 `actor.RemitGovTreasury`（`fromHousehold/toHousehold` 载荷，`:286-301`）。
  - ⇒ world-silver **没有 Unit**，无法作为 from/to；两个运行期 GOV 之间可转（但都空账 ⇒ 域层会拒“源国库账不存在/可支配量不足”）。
- `simos.gov.pay`（决策人，`GovPayTool.java:72-480`，在决策白名单 `DecisionCallerFactory.java:152`）：
  - 付款人 = `identity → sd 决策人 → Affiliation.Gov → GOV 单位`（`:237-272`），载荷不能指定付款人；
  - 收款人也必须是带 `GovernmentFormation` 的 Unit（`:279-302`）。⇒ 同样够不着 world-silver。
- **够得着 world-silver 的路径**（GM 侧）：
  - `simos.command.submit` + `actor.RemitGovTreasury`（载荷 household 级；GM 分桶才有通用 submit，
    `SimosToolSource.java:477,494`）；
  - `actor.AdjustAccounts` 窄工具（`SimosToolSource.java:644`；GM-only）；
  - `simos.gm.periodicAdjustment`（`:741`；周期把 world-silver 的银转给 GOV，但只有银）。
  - 这些都是**通用账目原语**，没有“政府预算/上缴/优先级”语义；`GovRemitTool` 的类注自己写明
    “GM 允许任意两个 GOV 之间转移（不要求 to 是 from.superiorGov）；决策人路径的 superior 校验留 R3b”。
- **回答“simos.gov.remit 为什么够不着”**：不是 `actor.RemitGovTreasury` 域层不支持 household（它支持），
  而是 `simos.gov.remit` 的解析面把两端**限制为 GOV Unit**，而 world-silver 不是 Unit、没有 `GovernmentFormation`。
  即“域命令可达、GOV 工具面不可达”。

### 4.5 军俸链与 no-payable-leg

- `MilitaryPayPolicy`：`simos-unit/.../MilitaryPayPolicy.java:52-96`（period/phase/starts/expires + 逐家户 grain/cloth/money 三张表，
  逐家户显式，不按人口平均）。
- `MilitaryPayRuleBridge.deriveReport`：`simos-app/.../time/MilitaryPayRuleBridge.java:85-251`：
  逐 Army 单位（id 升序）→ policy enabled → masterGov → payer=`GovernmentHouseholds.of(masterGov)` →
  校验 social 家户、`economy.governments[gov-unit-<masterGov>]`、treasury 恰好等于 payer、
  `economy.classes` 有行 → 逐政策家户生成 `HouseholdPeriodicAdjustment(id=army-pay:<unit>:<household>,
  reason=MILITARY_SALARY, policySource=army:<unit>)`。
- 执行器：`PeriodicHouseholdAdjustmentExecutor.executeOne`（`simos-app/.../time/PeriodicHouseholdAdjustmentExecutor.java:212-325`）：
  - payer/payee 账户不存在 ⇒ 具名 skip；
  - 逐腿 `paid = min(requested, AvailableStock.available(payerInventory, asset))`（`:237-267`）；
  - `paidGoods.isEmpty() && paidMoney.isEmpty()` ⇒ `skip(rule, "no-payable-leg", ...)`（`:269-271,328-368`）；
  - 有实付 ⇒ `HouseholdStockDeduction.transfer(payer, payee, ...)` + `StockDeductionService.deduct`（`:273-290`）。
- run5：`MILITARY_PAY_BRIDGE units=2 policies=2 rules=2 gaps=0` ×360；
  `PERIODIC_ADJUSTMENT_DAY` 3 条（day2/122/242）；`PERIODIC_ADJUSTMENT_RULE` 6 条全部
  `SKIPPED gap=no-payable-leg`，`shortfallGoods={grain=300|180}`、`shortfallMoney={silver=30|18}`。
- **共享预算/优先级缺失**：税→行政→军俸是 app 里的**固定顺序**，但三条路径各自直接看 payer 活账余额，
  没有“同一国库的预算池/预留/优先级/按比例分配”。多规则时执行器按 `rule.id.value()` 升序执行（`:141-142`），
  先到者拿得到钱；这正是 inventory 的 P4c 待办：“军俸与 GOV 行政俸禄共享国库的预算/缺口优先级 | 现为顺序扣：
  税 → GovDaily → 军俸，无共享池”（`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:42-44`）。
- **P4b 已落地、P4c 未做**：`AGENTS.md:67` 已更正 P4b（`bfb246a3`：第 4 组件 + `unit.SetArmyPayPolicy` +
  `MilitaryPayRuleBridge`），GM 工具 `simos.gm.armyPayPolicy` 由 D4 补（`:741-742`）；P4c 仍开放：
  决策人受限军俸工具/审批白名单、军俸 FlowRow/ledger 维度、共享国库预算优先级。

### 4.6 候选方案

| 方案 | 改动面 | 影响 | 与铁律 2/3/4 的关系 | 风险 |
|---|---|---|---|---|
| **F2-A 注资：现有原语** | 不写代码；GM 用 `actor.AdjustAccounts` / `simos.command.submit(actor.RemitGovTreasury)` / `simos.gm.periodicAdjustment` | 可立即给两个 GOV 家户注入粮/布/银；验证 F2 的下游（upkeep paid>0、军俸 APPLIED） | 全部走 Command→ChangeSet→Revision，符合铁律 2；GM 是组合面，符合铁律 4 | 不是 GOV 财政语义：没有预算/审计 reason（`actor.RemitGovTreasury` 的 reason 可选且不进状态）；不能自动持续；runtime GOV 仍无商品来源 |
| **F2-B 新增 `simos.gov.householdTransfer`（计划 W2 §3.2）** | `simos-app` 新 GM 窄工具（底层复用 `actor.RemitGovTreasury`，household 级已支持）；`SimosToolSource` 注册 | GM 可明确“从 world-silver/任意政府家户 → 任意 GOV 家户”，多资源、原子、具名 reason | 命令路径不变；app 同时认识 unit/actor，符合铁律 3/4；不需要 gov 模块依赖 actor | 要裁 GM-only/决策人可达性、是否允许跨级；仍解决不了 world-silver 无粮/布的问题 |
| **F2-C 建 GOV 时显式注资** | `simos-app`（`GovCreateOfficePlan` 加一个可选注资命令/初始 stock 参数，或 world bootstrap 注入） | 新建 GOV 即有初始粮/布/银，upkeep 与军俸可立即跑 | 必须走命令（`actor.AdjustAccounts` 或专用命令），符合铁律 2；app 是组合根，符合铁律 4 | 资金来源与审计口径要定；“建 GOV 自动送粮”可能造出凭空库存；与 world-silver 的关系要说明 |
| **F2-D 共享预算/优先级** | `simos-app/time`（顺序/预留/比例） + 可能 economy-api 规则字段/FlowRow | 同一国库下行政与军俸的缺额可解释、可复算 | 仍在日结算 advance revision 内，符合铁律 2；规则字段归 economy-api 契约，符合铁律 3；app 编排，符合铁律 4 | 优先级是政治设计；改顺序会改变现有行为；需要新增具名 shortfall/欠额读数 |
| **F2-E 行政俸禄收款方** | 若改成付给 staff 家户：新增/runtime 吏员家户模型 + `GovernmentUpkeepOracle` 从 sink 改 transfer | 俸禄变成家户收入；国库支出有对端 | 改 app 结算，符合铁律 2/4；吏员家户身份归 social/economy，符合铁律 3 | 当前没有“吏员家户”实体；可能大幅扩大范围；若继续 sink 要显式记录“付给编制”而非“付给某人” |

### 4.7 分类

- **实现缺口**：运行期 GOV 无初始注资/持续上缴路径；GOV 工具无法寻址 world-silver；行政俸禄无收款对端（sink）；
  无共享预算/优先级；军俸无 FlowRow/ledger 维度。
- **参数标定**：`OfficePolicy.defaults()` 的 money=0、`levy*Cap=0`；run5 未给 GOV 任何库存。
- **待用户裁定**：world-silver 给运行期 GOV 注资还是各 GOV 自筹；行政俸禄是 sink 还是付给吏员家户；
  军俸与行政的优先级/欠薪规则。

---

## 5. F3 — 铸币政府与运行期 GOV 无财政连接；M1 现状

### 5.1 创世 world-silver 的完整链

- `SmallWorld.state`：`SmallWorld.java:223-226` 传 `governmentRef = EconomySeeder.GENESIS_GOVERNMENT_ID`（= `world-silver`），
  并把同一份 plan 喂给 `economy.Seed` / `actor.Seed`（`:227-275` 的命令序）。
- `PopulationSeeder` 按 `governmentRef` 稳定建 `hh-gov-world-silver`（`PopulationSeeder.java:165-194`）。
- `EconomySeeder`：
  - `seedGovernmentHousehold` 追加 `HouseholdEconomy` 空行 + 空 stocks/money（`EconomySeeder.java:3393-3460`）；
  - `governmentHouseholdGovernment` 建 `Government(GENESIS_GOVERNMENT_ID, "world",
    HouseholdActors.of(hh-gov-world-silver), issuable={silver},
    seignioragePerCycle=2000, debtIssuePerCycle=5000)`（`:3464-3472`；常量 `:701-718`）；
  - `economy.Seed` 的 `moneyIssuances` 落 `INITIAL_ENDOWMENT`（`genesisEndowment`；run5 `ECONOMY_SEED` 显示 `{silver=67200}`）。
- `GovernmentSeigniorage.settleCycleStart`（`simos-economy/.../time/GovernmentSeigniorage.java:74-147`）：
  触发日 = day1 或 `(day−1)%max(cycleDays)==0`（`:50-63`）；对每个 `seignioragePerCycle>0` 的政府，
  把 `seignioragePerCycle` 直接加到 `government.treasury()` 对应账户，写
  `MoneyIssuanceRecord(kind=FISCAL_ISSUE)`（`:101-121`）；调用点 `EconomySettlement.java:769-775`。
  - run5 证据：`GOV_SEIGNIORAGE` 3 条，`government=world-silver`，`currency=silver amount=2000`，
    `accountActor=HOUSEHOLD:hh-gov-world-silver`。
  - 报表里的“`fiscalIssue=6000`”是**口算/展示简称**：实际是 3 条 `MoneyIssuanceKind.FISCAL_ISSUE`
    记录之和；全口径 `netIssuance` 由 `MoneyStock`/`ApiViews` 从 `moneyIssuances` 现算
    （`simos-economy/.../time/MoneyStock.java:47-82`；`ApiViews.java:1716-1747`）。
    run5 全口径 = INITIAL_ENDOWMENT 67,200 + FISCAL_ISSUE 6,000 = 73,200 毫银（与 run2 报告一致）。
- **为什么没有 `GovernmentFormation`**：`world-silver` 是 economy 的 **Government 记录**，不是 Unit；
  `EconomySeeder` 只追加 `HouseholdEconomy` 与 `Government`，没有 `unit.CreateUnit` / `unit.SetGovFormation`；
  `SmallWorld` 也不建这个 Unit。`GovernmentHouseholdWiring` 明确“世界级政府（world-silver 这类非单位派生 id）
  不在本类射程内”，反向校核只处理 `gov-unit-<unitId>`（`GovernmentHouseholdWiring.java:44-45,180-206`）。
  `GovernmentIds.unitRefOf(world-silver)` 返回空（`GovernmentIds.java:35-55`）。
- **为什么 `simos.gov.remit` 够不着**：见 F2 §4.4——`GovRemitTool.resolveGov` 要求 Unit + `GovernmentFormation`，
  world-silver 两个都没有；`GovPayTool` 的付款人/收款人同样限定 GOV Unit。
  `actor.RemitGovTreasury` 命令本身是 household 级、GM 可用通用 submit 直接发；但那是“裸账目”，不是 GOV 财政工具。
- **结论**：world-silver 的铸币只增发**自己账上的银**；没有任何规则把它转给运行期 GOV，也没有“中央→省”的自动上缴/预算。
  在 F1 修好之前，运行期 GOV 既收不到税（F1），也收不到上级/铸币政府注资（F3），两个空国库互为因果地锁死。

### 5.2 M1 小世界铸币计划现状（逐条对照 `docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md`）

| 计划项 | 代码现状 | 证据 |
|---|---|---|
| 货币产出表示 A：`Industry.moneyOutputPerUnit` | **未实现**。`grep -rF moneyOutputPerUnit simos-*/src/main` = 0 | §11.1-B |
| 货币产出表示 B：独立 `MintRule`/`MintOperation` | **未实现**。`grep -rF MintRule simos-*/src/main` = 0 | §11.1-C |
| `mint` 生产方式 / `MINT` mode | **未实现**。`DefaultProductionModes` 只有 8 个 mode：tenancy_fixed_kind / tenancy_share / tenancy_cash / wage_farm / handicraft_workshop / family_farm / merchant / displaced；`grep -rF '"mint"'` = 0 | `DefaultProductionModes.java:89-113`；§11.1-D |
| 每单位铸币的劳动/工具系数、周期天数 | **未实现**（计划 §4.3/§4.6 的待决策；代码无 mint 配方/系数） | 同上 |
| 规模 = mint `AssetShare`/`ProductionUnit`，产能上限 | **未实现**；无 mint 产业，故无 capacity/targetScale | 同上 |
| 政府家户人口/劳动/初始工具与国库库存 | **当前分别是 0 / 0 / 空 / 空**（`seedGovernmentHousehold` 空行空账；`createOffice` 也不注资） | `EconomySeeder.java:3424-3425,3449-3457`；`EconomyRegisterGovernmentHandler.java:318-325` |
| `simos.economy.gov.policy` / `simos.economy.mintScale` / `simos.gov.householdTransfer` | **均未实现**（工具名 0 命中） | §11.1-E |
| GUI 写面 `/gov` 或 mint 面板 | **未实现**；只有只读 `GET /api/economy/gov`（政府/国库/发行审计），无 `/gov` 页 | §11.1-G；`GuiServer.java:298-320`；inventory §1.4 的 D4.1 |
| 过渡兼容：`GovernmentSeigniorage` + `FISCAL_ISSUE` | **已实现并在跑**，即当前 world-silver 的唯一“铸币”方式 | `GovernmentSeigniorage.java:74-147`；run5 `GOV_SEIGNIORAGE` ×3 |
| 非法仿制/伪币 | **未做**，计划 §4.5 明留 TODO | inventory §1.1“非法仿制/伪币” |
| 日志 | `GOV_SEIGNIORAGE` INFO 已有；M1 计划中的 `MINT_*` 事件**未实现** | §11.1-C 的 grep |

⇒ **M1 不是“参数没调好”，而是生产方式整体未实现**；当前只有 `seignioragePerCycle` 兼容路径。
计划 §9 的用户待裁点（A/B、系数、规模/targetScale、产能硬上限、家户人口/劳动/初始库存、GUI 写面）**全部仍未裁**。

### 5.3 候选方案

| 方案 | 改动面 | 影响 | 与铁律 2/3/4 的关系 | 风险 |
|---|---|---|---|---|
| **F3-A 让 world-silver 成为真 GOV 单位** | `SmallWorld` / `createOffice` 风格：`unit.CreateUnit`+`unit.SetGovFormation`（+ 可能 jurisdiction）；但现有 `world-silver` Government 记录 id 与 `gov-unit-world-silver` 会撞/并存，需要迁移决策 | 之后 `simos.gov.remit/pay` 可达；但 world-silver 会同时有行政需求/俸禄，语义变化大 | 命令路径合法；unit 拥有编制，符合铁律 3；app 组合，符合铁律 4 | 身份冲突、旧档、中央政府全视野/额外 upkeep、可能违背“中央政府不设全视野”的用户原话；“世界级发行主体”与“GOV 单位”是两个概念，合并要用户裁定 |
| **F3-B 保留 world-silver 为世界级财政主体，新增 household 级 GOV 工具** | `simos-app`（见 F2-B）；不碰 unit/world-silver 身份 | GM 可把 world-silver 的银转给运行期 GOV；运行期 GOV 仍须自己解决粮/布（税或注入） | 同 F2-B | 需裁“世界级政府是否可被 GM/决策人付款”；决策人可达性要限 scope |
| **F3-C 运行期 GOV 完全自筹（税 + 本地铸币/发债）** | 不新增跨政府转移；先修 F1 与 F2 的税/编制；world-silver 只做创世审计/发行主体 | 财政重心落在省/中央 GOV；world-silver 不再被当“国库” | 不需要新跨主体工具；符合“税→国库”链 | 若税基/编制不修，仍锁死；中央 GOV 无辖区时也没有收入 |
| **F3-D 实现 M1 铸币生产方式** | 按计划 A/B 选一；`simos-economy`（Industry/mode/settlement）、`simos-economy-api`（契约）、`simos-app`（seeder/tools/GUI） | 政府家户用劳动+工具真正铸币；货币发行有生产来源与产能上限 | 生产结算走 advance revision（铁律 2）；经济数据归 economy（铁律 3）；app 组合（铁律 4）；若扩 `Industry` 组件，必须满足铁律 5 往返 | 大改动；系数/产能/人口/劳动/工具全待裁；铸币与物价/货币需求模型耦合，可能通胀 |

### 5.4 分类

- **实现缺口**：M1 生产方式（A/B 两种表示、`mint` mode、系数、产能、GUI 工具）全部未实现；
  world-silver 没有 Unit/`GovernmentFormation`；GOV 工具不能寻址 world-silver。
- **参数标定**：`GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI=2000`、`GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI=5000`
  是 seeder 具名默认（`EconomySeeder.java:705-718`），不是 M1 系数。
- **待用户裁定**：world-silver 与运行期 GOV 的关系；M1 A/B；系数/产能/人口/劳动/初始库存；GUI 写面。

---

## 6. F4 — 编制覆盖系数：19 格世界复算

### 6.1 逐格复算（代码常数）

`GovDemand` 公式（`GovDemand.java:86-91`）+ `GovRules` 常量（`GovRules.java:48,57,66,75`）：

| 格 | 人口 | 城市 | 治安 = ceil(pop/500)+城市40 | 文书 = ceil(pop/1000)+城市60 |
|---|---:|---|---:|---:|
| 17 个农村格（每个） | 200 | 否 | 1 | 1 |
| 首都 `(0,0)` | 900（200 农村+700 城镇） | 是 | ceil(900/500)=2 +40 = **42** | ceil(900/1000)=1 +60 = **61** |
| 镇 `(0,2)` | 500（200 农村+300 城镇） | 是 | ceil(500/500)=1 +40 = **41** | ceil(500/1000)=1 +60 = **61** |
| **19 格合计** | **4,800** | 2 城 | **17+42+41 = 100 YAMEN** | **17+61+61 = 139 SCRIBE+POST** |

- **POST 没有独立需求**：`GovEfficiency.paperworkSupply = SCRIBE + POST`（`:101-106`），需求只有 `paperwork` 一项；
  `GovApplyStaffingTool` 的目标是 `YAMEN=securityDemand, SCRIBE=paperworkDemand, POST=0`
  （`GovApplyStaffingTool.java:269-278`）。所以“覆盖 139 名文书”可以由 SCRIBE 或 POST 任意组合完成，
  但代码没有“多少驿传”的口径。
- **运行期现状**：gov-province `{SCRIBE:2}` ⇒ `securityCoverage=0`、`paperworkCoverage=floor(2×1000/139)=14‰`、
  `efficiency=0`；gov-central 无辖区 ⇒ 两维 demand=0、coverage=1000、efficiency=1000，但税整单位跳过。
  tick30 dump 的 crisis evidence 与复算一致：`ADMIN_SECURITY {demand:101, supply:0, coverage:0}`、
  `ADMIN_PAPERWORK {demand:139, supply:2, coverage:14}`（tick30 人口 4970，治安需求 101；tick0 为 100）。

### 6.2 “正常覆盖”的几种口径与影响面

| 口径 | YAMEN / SCRIBE+POST | 总编制 | 复算/影响 |
|---|---|---:|---|
| **A. 现行公式、完整覆盖** | 100 / 139 | **239**（≈4.98% 人口） | 与 `GovRules` 和 `GovApplyStaffing` 一致；efficiency=1000‰；但“编制”只是 `GovernmentFormation.staff` 账面人数，不扣人口/劳动/装备 |
| **B. 本批临时配员（run2 §6 建议量级）** | 20 / 30 | 50 | securityCoverage=200‰、paperworkCoverage=215‰、coverageMin=200‰ ⇒ efficiency=200‰（无 bonus）；税收到 assessed 的 20% |
| **C. 只按城市节点** | 83 / 122（仅 2 城，且保留城市加项） | 205 | 与 A 接近，因为 40/60 城市加项占大头；若同时取消城市加项、只按城市人口：3 / 2（总 5） |
| **D. 按 Region 人口总量**（取消逐格 ceil） | ceil(4800/500)=10 / ceil(4800/1000)=5 | 15 | 需要改 `GovDemand` 公式；所有世界同时受影响 |
| **E. 小世界/profile 标定**（示例：5000 人/YAMEN、10000 人/SCRIBE、城市 +4/+6） | 17+5+5=27 / 17+7+7=31 | 58 | 需要 `GovRules` 的 profile/world 覆写（新参数面）或新 profile；比 A 轻 4 倍，但仍保留“每格 1 人”的底 |
| **F. 只给部分配员、接受低效率** | 任意 | 任意 | 无需改公式；efficiency 按 coverageMin 降；税按比例收；适合作为 F1 修复前的过渡读数 |

- **影响面**：
  - A/B/C/F 是**运行期配员/初始化问题**，不改公式：只影响本世界/本存档能收到多少税（F1）、发多少 ADMIN_* 信号。
  - D/E 是**公式/常量口径**问题：改变所有世界、所有旧档的效率与税收；E 若做成 profile 覆写，还会新增状态/配置组件，
    必须满足铁律 5 的 ChangeSet/Codec 往返与旧档兼容。
  - A 的 239 人若将来要真扣人口/劳动，需要 Social/Economy 侧的“吏员人口”口径；当前 `GovernmentFormation.staff` 只是账面数，
    没有人口扣减，也没有谁来“招募”这 239 人（`RecruitStaffHandler` 自己注释“只入编、不扣人”，
    `RecruitStaffHandler.java:29-40`）。
- **当前 GM 可用的精确配满工具**：`simos.gov.applyStaffing`（GM-only，`SimosToolSource.java:654`），
  目标就是 A 口径（`GovApplyStaffingTool.java:269-278`）。运行 run5 的 `gov-province` 上调用它即可把 `YAMEN=100,
  SCRIBE=139` 一次性写入（一条 revision），但它仍是“账面编制”，且需要用户先裁 A 是否可接受。

### 6.3 候选方案与分类

| 方案 | 改动面 | 影响 | 铁律关系 | 风险 |
|---|---|---|---|---|
| **F4-A 用 `applyStaffing` 配满** | 不写代码；GM 工具 | 直接修 F1 的零覆盖；239 名账面吏员 | 走 `unit.SetGovFormation` 批 → 合法 | 不扣人口/劳动；F4 口径仍待裁；未来改口径会推翻 |
| **F4-B 改全局 `GovRules` 常数** | `simos-gov/GovRules.java` | 所有世界税收/信号变化 | 纯常量；无写口；铁律 3/4 不受影响 | 全局平衡回归；旧档读数变化；需要长跑校准 |
| **F4-C 小世界/profile 覆写** | 新参数面（`GovRules` 或 `GovDemand` 的 profile 输入）+ 可能的 state/codec | 只有小世界/指定 profile 变 | 新状态/配置要走 ChangeSet/Codec（铁律 5）；政策所有者要定 | 范围扩大；旧档兼容 |
| **F4-D 改需求粒度** | `simos-gov/GovDemand`（城乡/节点/总量口径） | 所有世界 | 纯函数；铁律 3/4 不受影响 | 改变语义；需要重写测试期望；可能与“每格治安”的设计意图冲突 |

**分类**：参数标定（500/1000/40/60、staff 缺省空表）+ 待用户裁定（口径 A~F）。
**明确不是**：不是数据缺失、不是 `GovDemand` 没跑到（`GOV_DEMAND_COMPUTED` 1440 条）、不是日志问题。

---

## 7. 顺带核对

### 7.1 免税/税率期限实体（老清单）

- **现状**：只有 `unit.SetTaxRate`（长期税率 upsert，`SetTaxRateHandler.java` / `UnitOperations.setTaxRate:733-770`）。
  **没有**：免税公告实体、税率期限（starts/expires）、税率历史、到期自动恢复原值、自动执行器。
- 文档证据：`docs/superpowers/plans/2026-10-02-undeveloped-features.md:44-47`（“免税半年没有‘免税/期限’实体”；
  缺：免税公告/期限实体、税率历史回滚、自动执行器）；`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:57`
  （“长期税率命令在（`SetTaxRateHandler`），但无免税/税率历史/到期恢复”）。
- `sd.RegisterEffect` 的 `SCHEDULED + enqueue_unit_command unit.SetTaxRate` 是一条可能的排程，但**不随 `simos.advance` 自动执行**，
  必须 GM 显式 `sd.AdjudicateTick`（同文档）。
- 代码 0 命中自证：§11.1-F。

### 7.2 决策包 GUI / `/gov` 面板 与 决策人版军俸工具（D4/D5 留出）

- **`/gov` 页面：没有**。全仓 `"/gov"` 字面量 0 命中（§11.1-G）；`webui/` 只有 index/map/social/unit 等页；
  政府读面只有 `GET /api/economy/gov`（`GuiServer.java:298-320`，视图 `ApiViews.economyGovernment:1596-1657`）。
  inventory §1.4 D4.1 明确：“无 `/gov` 页；webui 中 packet/上报 0 命中；决策包审阅/上报与 GM 参数面板全缺”
  （`2026-10-23-planned-not-implemented-inventory.md:82`）；D4 计划 §4 说 GUI 面板留 D4.1
  （`2026-10-22-d4-gm-tools-reports.md:11,73-78`）。
- **决策人版军俸工具：没有**。现有 `simos.gm.armyPayPolicy`（GM-only，`GmArmyPayPolicyTool.java:51`；
  注册 `SimosToolSource.java:742`）；命令 `unit.SetArmyPayPolicy` 本身不是 GmOnly，但**不在** `DecisionCallerFactory.WHITELIST`
  （`DecisionCallerFactory.java:105-152` 只含 `GovPayTool` 一条 GOV 财政写）。inventory §1.1/P4c 与
  `2026-10-22-d2-decision-packet.md` 的补记都写明：“GM 侧 `simos.gm.armyPayPolicy` 已补；
  决策人可 propose 的军俸工具未定/未做。”

### 7.3 `simos-gov` 有没有 command handler？耦合关系

- **确认：0 个**。`grep -rlF CommandHandler simos-gov/src/main/java | wc -l` = **0**；对照同命令在 `simos-unit/src/main/java` = 34 个文件（§11.1-A）。
- **耦合关系**：
  - `simos-gov` 的代码是 `GovRules` / `GovDemand` / `GovEfficiency` / `GovDaily`（纯函数/结算函数）
    + `GovState`/`GovOfficeState`/`GovSnapshot`/`GovChangeSet`/`GovCodec`（读数状态树与往返）+ `GovLog`。
  - 它依赖 `util` / `map` / `unit` / `social` / `economy-api`（pom `simos-gov/pom.xml:30-52`），
    enforcer 禁 `core`/`app`/`agentlib`/`sd`/`economy`/`actor`（pom `:57-75`）。
  - app 的 `PopulationEconomyTimeParticipant` 是唯一编排者：读 `GovState` → `withBootstrapOffices` → 调
    `GovDemand/GovEfficiency`（`efficiencyTable`）→ `JurisdictionDailyTax` → `GovDaily.settle` → 把
    `SignalDraft` 折进 `economy.crisisSignals` → 用 `GovChangeSet.between` 写回 `GovState`（`:672-676`）。
  - 输入状态（staff 编制、税务政策、管辖）由 **unit 命令**写（`unit.SetGovFormation/SetTaxRate/SetJurisdiction/...`）；
    账户/发行由 **economy/actor 命令**写；gov 自己只拥有“每 tick 读数”。
  - 结论：**“gov 模块 0 handler”是当前模块边界的自然结果，不是漏做**；如果将来需要“gov 自己的命令面”，
    要么新增 gov SPI handler 并让 gov 依赖 spi 契约（但写账户仍需 app/actor/economy），要么继续用 unit/economy/actor 命令。
    这与铁律 3（各模块只拥有自己的数据）和铁律 4（core/app 只组合）一致。

### 7.4 税收/俸禄/军俸 与 `actor.DeductHouseholdStock`、`StockDeductionService`、`FlowRow` 的关系

| 路径 | 落账实现 | actor 账户变更 | FlowRow | ProductionLedger | 主要日志（run5 级别） |
|---|---|---|---|---|---|
| 日税 grain | `StockDeductionService.deduct` + `HouseholdStockDeduction.transfer`（`JURISDICTION_TAX`） | 是（会话 → 日末 land 回 actor） | 有：`FlowRow.taxPaid` / `netSurplus`（仅粮） | 否 | `TAX_DAILY_END` INFO；`TAX_COLLECTED` TRACE |
| 日税 money | 同上 | 是 | **无**（`FlowRow` 没有银税位） | 否 | 同上 |
| 行政俸禄 | `StockDeductionService` + `HouseholdStockDeduction.sink`（`ADMIN_UPKEEP`） | 是（只有付款方，无收款方） | 无 | 否 | `GOV_OFFICE_UPKEEP_EVALUATED` DEBUG；`GOV_UPKEEP_NO_STOCK` DEBUG；`GOV_DAILY_END` INFO |
| 军俸 | `StockDeductionService` + `HouseholdStockDeduction.transfer`（`MILITARY_SALARY`，payer=GOV 家户，payee=军户） | 是 | 无（inventory §1.1 列为 P4c 待办） | 否 | `PERIODIC_ADJUSTMENT_DAY/RULE` INFO（SKIPPED 也是 INFO） |
| `actor.DeductHouseholdStock` 命令 | `StockDeductionOperations.deductAll` 在 actor 状态上物化，走 `ActorChangeSet.between` | 是（命令 → revision） | 无 | 否 | `HOUSEHOLD_STOCK_DEDUCTED` INFO |
| 市场/生产 | `AccountSession` + `ProductionLedger` + `TransferMint` | 是 | 部分字段（income/consumed/…） | 是 | economy settlement/market/debt/trace |

- **没有账/只有瞬态的**：行政俸禄（sink）、军俸、税银——它们只有账户余额变化 + 读数字段；没有
  `ProductionLedger` 的逐笔发生额，也没有军民俸的 FlowRow 维度。税粮是唯一有 FlowRow 位的财政流。
- `actor.DeductHouseholdStock` 当前**没有窄工具**：只在 `CatalogTool.PAYLOAD_HINTS` 有说明，
  走通用 `simos.command.submit`；税/行政/军俸的日路径**不走**这条命令，而是走 `StockDeductionService`
  （两者共用 `HouseholdStockDeduction`/`AvailableStock` 语义，见 `StockDeductionOperations.java:22-50`）。

### 7.5 其他发现

- `actor.RemitGovTreasury` 的类注释（`:36-44`）仍写旧载荷 `{fromUnitId,fromQ,fromR,...}`，
  实际 `parse` 读的是 `{fromHousehold,toHousehold,grain,cloth,money,reason}`（`:149-157`）——**类注与代码不一致**，
  属于文档漂移（台账/注释 ≠ 代码；建议随 G1 一并修文案）。
- run2 报告 F3 写“world-silver 持有粮 382,960 / 布 9,003”，但 run5 日志里 world-silver 没有任何商品事件，
  代码 `seedGovernmentHousehold` 明确给它空 stocks/money，run5 的 `ECONOMY_SEED` 也只显示 `{silver=67200}`；
  run2 setup 脚本也没有注资命令。**该粮/布数字在 run5 未复现，来源未核到**（可能来自 run2 当时另一次手工操作/旧树），
  本报告只把它当作“world-silver **可能**被手工注资”的边界，不作为机制结论。

---

## 8. 分类总表（实现缺口 / 参数标定 / 待用户裁定）

| # | 问题 | 分类 | 关键代码/文档 |
|---|---|---|---|
| 1 | `YAMEN=0` 导致效率 0 → 税 100% 行政损耗 | **参数标定**（本场配员）+ **待裁定**（效率语义） | `GovEfficiency.java:84-90`；`GovRules.java:48`；run5 `TAX_DAILY_END` |
| 2 | 税基 = 存量余额、无最低口粮保护 | **待用户裁定** | `JurisdictionDailyTax.java:467-484` |
| 3 | 重叠管辖重复征税 | **实现缺口** | `JurisdictionDailyTax.java:70-74`；inventory |
| 4 | 银税无 FlowRow 位 | **实现缺口** | `FlowRow.java:39-41,65-71` |
| 5 | 运行期 GOV 零初始库存、无持续注资 | **实现缺口**（但可用现有 GM 原语临时绕过） | `GovCreateOfficePlan.java:327-343,419-425`；`EconomySeeder.java:3424-3425` |
| 6 | `simos.gov.remit/pay` 寻址不到 world-silver | **实现缺口**（工具面） | `GovRemitTool.java:260-283`；`GovPayTool.java:237-302` |
| 7 | 行政俸禄是 sink、无收款家户 | **待用户裁定** | `GovernmentUpkeepOracle.java:131-148`；`HouseholdStockDeduction.java:120-130` |
| 8 | 军俸 `no-payable-leg`：国库空 | **参数/初始化标定**（直接） + **实现缺口**（共享预算） | `PeriodicHouseholdAdjustmentExecutor.java:269-271`；run5 `PERIODIC_ADJUSTMENT_RULE` |
| 9 | 税→行政→军俸无共享预算/优先级，P4c 未做 | **实现缺口** | `PopulationEconomyTimeParticipant.java:529-602`；inventory §1.1 |
| 10 | world-silver 不是 Unit，无 `GovernmentFormation` | **待用户裁定**（架构关系） | `GovernmentIds.java:35-55`；`GovernmentHouseholdWiring.java:44-45` |
| 11 | M1 铸币生产方式整体未实现（A/B、系数、产能、人口/劳动/库存、GUI） | **实现缺口**（大）+ **待用户裁定**（口径） | `DefaultProductionModes.java:89-113`；§11.1-B/C/D |
| 12 | `GovRules` 19 格世界需求 239 人，小世界覆盖过重 | **参数标定** + **待用户裁定**（口径 A~F） | §6；`GovRules.java:20-36` |
| 13 | 无免税/税率期限/历史/自动恢复 | **实现缺口** | `SetTaxRateHandler.java`；`plans/2026-10-02-undeveloped-features.md:44-47` |
| 14 | 无 `/gov` 面板；决策包 GUI 留 D4.1 | **实现缺口** | `GuiServer.java:298-320`；inventory §1.4 |
| 15 | 无决策人军俸工具；GM 版已有 | **实现缺口** | `GmArmyPayPolicyTool.java:51`；`DecisionCallerFactory.java:105-152` |
| 16 | gov 模块 0 command handler | **架构事实**（不是缺口） | §11.1-A；`simos-gov/pom.xml:57-75` |

---

## 9. 待用户裁定清单（问题 / 选项 / 我的建议）

> “我的建议”= 调查代理的技术建议，不是用户裁定；用户可推翻。

1. **F1 效率语义：一维零覆盖是否应当把税归零？**
   - 选项：A 维持 `min` 门（YAMEN=0 ⇒ 0% 税）；B 两维加权/几何平均（部分税）；C 保留 `min` 但设最低征收率；
     D 按维分别征税（治安/文书各自有收税能力）。
   - **建议**：短期按 A 并**强制世界/建 GOV 时把 YAMEN 纳入默认配员**；中期改 B 或 D，避免“漏配一个角色 = 财政瞬间归零”的悬崖。
2. **F1 税基：存量税还是流量税？是否保护最低口粮？**
   - 选项：A 现行存量×‰；B 存量税 + 最低口粮保护；C 流量/收入税；D 粮按流量、银按存量。
   - **建议**：最小改动先做 B（防征税制造饥荒），C 留作后续“财政能力”议题；B 需要 Social/基础口粮权威。
3. **F1/F4 覆盖口径与标定：239 名吏员是“正常”吗？**
   - 选项：A 保留全局 `GovRules`，要求 239 人（用 `simos.gov.applyStaffing` 配满）；B 改全局常数；
     C 小世界/profile 覆写；D 改需求粒度（只按城市/按 Region 总量）。
   - **建议**：不要把 19 格世界的平衡写进全局常数；选 C（或 D+C 组合），A 只做本批过渡；若选 A，明确“编制人数是否扣人口/劳动”。
4. **F2 注资路径：用哪个口？**
   - 选项：A 现有 `actor.AdjustAccounts`/`simos.command.submit(actor.RemitGovTreasury)`；B 新 `simos.gov.householdTransfer`
     （或泛化 `GovRemitTool`）接受 government/household 两端；C `createOffice` 时显式初始注资；D 不注资，等税收。
   - **建议**：B（具名、可审计、保留 raw 原语）+ C 的“显式命令注资”用于小世界 bootstrap；A 只作临时。
5. **F2 行政俸禄收款方：继续 sink 还是付给吏员家户？**
   - 选项：A 继续 sink（当前）；B 建吏员家户并真实支付；C 付给上级国库再分配；D 资本化为欠薪/债务。
   - **建议**：短期 A + 明示“付给编制（sink）”；若要做政治经济闭环，单独立项 B（先裁编制=人口的关系）。
6. **F2/F3 军俸与行政的优先级：国库不够时先给谁？**
   - 选项：A 维持顺序（税→行政→军俸，先到先得）；B 同一预算池 + 显式优先级；C 允许欠薪资本化/延付；
     D 上级/中央兜底。
   - **建议**：B + C（预算池 + 欠额读数）；优先级请用户定（行政/军俸/治安的先后是政治设计）。
7. **F3 world-silver 与运行期 GOV 的关系**
   - 选项：A 升格为真 GOV 单位；B 保留世界级财政主体 + 新增 household 级 GOV 工具；C 运行期 GOV 完全自筹；
     D 把 world-silver 铸币收益按规则转给运行期 GOV。
   - **建议**：B（最小改动、不增加中央政府视野/额外 upkeep）；若想要真实铸币生产，另立 G5（M1）。
8. **F3 M1 铸币生产方式**
   - 选项：A `Industry.moneyOutputPerUnit`（计划推荐）；B 独立 `MintRule`；C 不做生产、保留 `seignioragePerCycle`；
     D 先做 GM policy 工具（可调 seigniorage/debt），生产后置。
   - **建议**：先 D 过渡、再做 A；必须同时裁系数（劳动/工具/周期）、产能上限、政府家户人口/劳动/初始工具、GUI 写面。
9. **F3 税率期限/免税实体**
   - 选项：A `Jurisdiction` 内 per-region 税率期限/免税 + 征收读当日有效值；B `sd.RegisterEffect` + 自动执行器
     （先重裁“禁止自动筛选 DM”旧裁定）；C 只加历史审计；D 保持手动。
   - **建议**：A（政策归 unit，征收读有效值）；B 作为通用调度后续；D 不足以表达“免税半年”。
10. **F4 顺带：`simos.gov.applyStaffing` 是否需要进入世界 bootstrap / 决策人工具？**
    - 选项：A 只 GM 手动；B world bootstrap 默认调用（世界/存档级）；C 决策人可在审批链内 propose。
    - **建议**：B 作为小世界 profile 的可选初始化；C 留 P4c 统一做。
11. **GUI/工具面：只读面板先行还是写面一起？**
    - 选项：A 先做只读 `/gov`（复用现有 `/api/economy/gov`）；B 同时做 GM 写面板；C 只补 MCP 工具，不做 GUI。
    - **建议**：A + 决策人军俸工具（P4c）单独立项；GUI 写面必须能映射到已有命令，别给 GUI 开旁路。

---

## 10. 建议批次 / 责任区（Zone G1…Gn）

> 遵守 AGENTS §一.5（一个责任区一个写代码代理、只写到编译过、测试最后统一）、§一.8（先落约束设计书再派单）、
> §一.10（只给目标/边界/六要素，不给逐文件施工图；compile 只是入口门，总验收看真实行为）。
> 每区必须有独立可编译目标；下面只给方向与模块范围，不预写类/方法拆分。

### G1 — 财政工具可达面（F2/F3 前置）

- **目标**：GM 能通过一个具名窄工具，把 grain/cloth/money 从任意政府家户（含 `hh-gov-world-silver`）转给任意 GOV 家户，
  一条命令 = 一条 revision、原子、具名 reason；缺账/不足具名拒。
- **依赖**：R4/R7 先裁；不依赖 F1 公式。
- **可动模块/范围**：`simos-app` 的 `tools/write`、`SimosToolSource`、`CatalogTool` 文案；
  底层已存在 household 级 `actor.RemitGovTreasury`（`RemitGovTreasuryHandler.java:149-157`），不新增领域命令即可完成。
- **验收**：真小世界 preview/apply；world-silver→gov-province 转银后两边余额逐值变化；
  负数/全零/缺账/不足有具名拒；决策人不可达（除非 R4 明确开）。
- **不要求**：不要求 GUI、不要求自动预算。

### G2 — 小世界 GOV bootstrap 与配员（F1/F2 临时口径）

- **目标**：新世界/新 GOV 按用户裁定的口径获得初始粮/布/银与足量 staff（至少让 `gov-province` 的 YAMEN>0）；
  用真命令落盘，可审计。
- **依赖**：R3/R4 + G1（若注资走 G1 工具）。
- **可动模块/范围**：`simos-app` 的 `world/SmallWorld`、`tools/write`（bootstrap/组合工具）；
  不要求改 `simos-gov`。
- **验收**：新 19 格世界 tick0 后 `TAX_DAILY_END grainCollected>0`、`GOV_OFFICE_UPKEEP_EVALUATED paid>0`、
  军俸到期日 `status=EXECUTED`（或至少不是 no-payable-leg，取决于 R6）。

### G3 — 税收效率语义与税基（F1/F4 公式）

- **目标**：按 R1/R2/R3 改公式/口径：是否 min 门、是否保护最低口粮、是否按维加权、需求粒度是否改。
- **依赖**：R1/R2/R3；G2 提供基线读数。
- **可动模块/范围**：`simos-gov`（`GovRules/GovDemand/GovEfficiency`）+ `simos-app/time/JurisdictionDailyTax`
  （税基/保护/重叠）；若新增 profile 参数，还要动状态/Codec（铁律 5）。
- **验收**：19 格世界复算 `assessed/attainable/collected/adminShortfall/stockShortfall` 恒等式；
  负向：YAMEN=0 行为按裁定、重叠 region 不重复征、最低口粮不被扣穿。
- **不要求**：不要求军俸/GUI。

### G4 — 国库预算与军俸/俸禄优先级（F2）

- **目标**：同一国库的行政俸禄与军俸有明确预算/优先级/缺额语义；军俸可付时不再因顺序偶然性出现 no-payable-leg；
  缺额有具名读数。
- **依赖**：G1/G2/G3 + R5/R6；若新增 FlowRow/ledger 维度，需 economy-api 契约裁定。
- **可动模块/范围**：`simos-app/time`（顺序/预算/执行器接入）+ `simos-economy-api`（如规则字段/欠额读口）；
  `simos-gov` 仍保持纯函数（除非裁定把预算口径放 gov）。
- **验收**：同一国库多规则到期日的支付顺序可复算；欠额/资本化读数可对账；负向：无预算时不会静默付 0。

### G5 — M1 铸币生产方式（F3）

- **目标**：按 R8 实现 A 或 B；铸币投入=劳动+工具，产出=该 GOV 货币，发行有 `MoneyIssuanceRecord`，
  产能有上限，政府家户有可配置人口/劳动/工具/初始库存；有 GUI/工具读面。
- **依赖**：R8 + G1/G2；若改 `Industry`，先写约束设计书（§一.8）并覆盖铁律 5 往返。
- **可动模块/范围**：`simos-economy`（mode/industry/settlement）、`simos-economy-api`（契约）、
  `simos-app`（seeder/tools/gui）、可能 `simos-unit`（政策）；不把铸币塞进 `simos-gov`。
- **验收**：真世界一个产业周期；货币总量守恒式、政府家户库存、工具/劳动消耗逐值可解释；
  负向：工具/劳动不足写具名 `MINT_SHORTFALL`，不静默少发。

### G6 — 税率期限 / 免税实体（顺带）

- **目标**：按 R9 实现“税率期限/免税公告/到期恢复原值”；征收读当日有效税率。
- **依赖**：R9；可与 G3 并行（但都改 tax 语义时需先冻结接口）。
- **可动模块/范围**：`simos-unit`（`Jurisdiction` + 新/旧 handler）、`simos-app/time`（读取有效税率）、
  可能的 `simos-sd`（若选排程方案）。
- **验收**：设置“免税 N 天”后第 N+1 天恢复原税率的逐日 `TAX_DAILY_END` 可对账；负向：未知/过期期限不静默忽略。

### G7 — GUI / 决策人工具（D4/D5 留出）

- **目标**：只读 `/gov` 面板（国库/政策/发行/最近转移）与决策人军俸工具（P4c）；写操作只映射到已有命令。
- **依赖**：G1/G4 的读口/预算；只读面板可先行。
- **可动模块/范围**：`simos-app` 的 `gui`/`webui`、`tools`、`access`（白名单/审批链）。
- **验收**：GUI 读数与 `/api/economy/gov` 逐值一致；决策人军俸工具在审批链内可用；
  负向：非 GOV 归属/越 scope/无审批被拒。

**依赖顺序**：G1 → G2 → G3 → G4 → G5；G6、G7 可在 G1/G3 后并行；G5 是最大且最后。

---

## 11. 自证与边界

### 11.1 “0 命中”自证（同一命令在已知命中串上生效）

以下都在仓库根 `/home/cna/SimulatorMosire` 执行（未跑 Maven/测试/服务）。

```text
[A] gov 模块 command handler
$ grep -rlF "CommandHandler" simos-gov/src/main/java | wc -l
0
$ grep -rlF "CommandHandler" simos-unit/src/main/java | wc -l      # 已知命中对照
34

[B] moneyOutputPerUnit
$ grep -rF "moneyOutputPerUnit" --include=*.java simos-*/src/main | wc -l
0
$ grep -rF "outputPerUnit" --include=*.java simos-economy/src/main | wc -l   # 已知命中对照
99

[C] MintRule
$ grep -rF "MintRule" --include=*.java simos-*/src/main | wc -l
0
$ grep -rF "ProductionModeId" --include=*.java simos-economy/src/main | wc -l # 已知命中对照
154

[D] 字面量 "mint" 作为生产 mode
$ grep -rF '"mint"' --include=*.java simos-economy/src/main | wc -l
0
$ grep -rF '"tenancy_share"' --include=*.java simos-economy/src/main | wc -l  # 已知命中对照
1

[E] 计划里的财政工具名
$ grep -rnF "simos.gov.householdTransfer" --include=*.java simos-app/src/main | wc -l
0
$ grep -rnF "simos.economy.mintScale" --include=*.java simos-app/src/main | wc -l
0
$ grep -rnF "simos.economy.gov.policy" --include=*.java simos-app/src/main | wc -l
0
$ grep -rnF "simos.gov.remit" --include=*.java simos-app/src/main | wc -l     # 已知命中对照
4

[F] 免税实体
$ grep -rniE "免税|tax.?exempt" --include=*.java simos-unit/src/main simos-gov/src/main simos-app/src/main | wc -l
0
$ grep -rniE "taxRate" --include=*.java simos-unit/src/main | wc -l           # 已知命中对照
22

[G] /gov 页面
$ grep -rF '"/gov"' simos-app/src/main | wc -l
0
$ grep -rF '"/api/economy/gov"' simos-app/src/main | wc -l                    # 已知命中对照
1
```

日志侧 0 命中（工作目录 `/home/cna/simos-runs/2026-10-23-sw19-run5`）：

```text
[H] TAX_NO_GOVERNMENT_HOUSEHOLD
$ grep -c 'event=TAX_NO_GOVERNMENT_HOUSEHOLD' service.log
0
$ grep -c 'event=TAX_DAILY_END' service.log                    # 已知命中对照
360

[I] TAX_FLOW_ROW_MISSING
$ grep -c 'event=TAX_FLOW_ROW_MISSING' service.log
0
$ grep -c 'event=GOV_ADMIN_ADVANCE_END' service.log            # 已知命中对照
12

[J] 运行期 GOV 自己的铸币
$ grep 'event=GOV_SEIGNIORAGE' service.log | grep -vc 'government=world-silver'
0
$ grep 'event=GOV_SEIGNIORAGE' service.log | grep -c 'government=world-silver'  # 已知命中对照
3

[K] 任何 GM 手工调账
$ grep -c 'event=ACTOR_ACCOUNTS_ADJUSTED' service.log
0
$ grep -c 'event=GOV_SEIGNIORAGE' service.log                  # 已知命中对照
3
```

事件数（用于本报告引用）：

```text
$ for e in TAX_DAILY_END GOV_OFFICE_UPKEEP_EVALUATED GOV_UPKEEP_NO_STOCK \
           MILITARY_PAY_BRIDGE PERIODIC_ADJUSTMENT_DAY PERIODIC_ADJUSTMENT_RULE \
           GOV_SEIGNIORAGE GOV_DAILY_START GOV_DAILY_END GOV_DEMAND_COMPUTED \
           TAX_UNIT_SKIPPED; do printf '%-38s %s\n' "$e" "$(grep -c "event=$e" service.log)"; done
TAX_DAILY_END                          360
GOV_OFFICE_UPKEEP_EVALUATED            720
GOV_UPKEEP_NO_STOCK                    1440
MILITARY_PAY_BRIDGE                    360
PERIODIC_ADJUSTMENT_DAY                3
PERIODIC_ADJUSTMENT_RULE               6
GOV_SEIGNIORAGE                        3
GOV_DAILY_START                        360
GOV_DAILY_END                          360
GOV_DEMAND_COMPUTED                    1440
TAX_UNIT_SKIPPED                       360
```

### 11.2 没核到的点（如实记）

- **未开 TRACE**：逐笔 `TAX_COLLECTED` / `HOUSEHOLD_STOCK_DEDUCTED` / `PERIODIC_ADJUSTMENT_LEG` 未抓；
  本报告所有数字都来自 INFO/DEBUG 与 dump。
- **未读 SQLite store**：没有打开 run5 的 `store/` 做发行记录逐条断言；`moneyIssuances` 的累计用
  `GOV_SEIGNIORAGE` 日志 + `MoneyStock`/`ApiViews` 读口口径推算（6000 FISCAL_ISSUE + 67200 INITIAL_ENDOWMENT = 73200）。
- **run2 的 world-silver 粮/布数字未复现**：见 §7.5；本报告不以此作为机制结论。
- **未做多世界/参数扫描**：只有 SmallWorld 19 hex 单例；未验证 RichWorld / 多省 / 重叠 Region 的相同现象。
- **未跑构建/测试/服务**：按任务纪律只读。所有“代码存在/不存在”均以 `grep` 与源码阅读为准，未以编译/运行验证。
- **未逐条审计 `GovernmentSeigniorage` 之外的发债路径**：`debtIssuePerCycle` 只在 run2 日志见到 `DEBT_STATUS`，
  本报告未展开核对其结算细节。

---

## 12. 附录：用户相关原话逐字（仓库文档）

> 先说明：用户示例里的“**Unit问题给Unit/GOV模块解决**”在全仓 `docs/**`、`AGENTS.md` 未找到原句
> （`grep -rnE "Unit问题|GOV模块|归 ?Unit|Unit ?/ ?GOV" --include=*.md`，排除 `.claude/`，0 命中）——
> **未找到原话，只有转述**。下面是与本主题直接相关、能在仓库文档中找到引号的**逐字**原话。

1. **2026-10-23，经济循环必须源自执行本身**（`docs/superpowers/specs/2026-10-23-smallworld-19hex-and-economy-360tick-design.md:6-13`）：
   > 「没啥问题；现在开始修经济循环——经济循环的问题必须源自经济循环的执行本身，你先建一个17~20hex的真实Simos世界，
   > 初始化一下经济模型，最好还掺一两个政府、军队之类的，然后跑个360tick经济执行，从日志中对照之前的问题表；
   > 如果已经有保存的测试世界，那就重新回到tick0沿用即可，先确认有没有、准备怎么做，给我看」
2. **2026-10-07，政府主体作为不生产家户**（`docs/superpowers/specs/2026-10-07-government-household-pilot.md:3`）：
   > “能不能加入政府主体作为不生产家户，其每轮投入市场的东西、需求都是可被 GM/决策人规定的……目前主要需要实现的是，
   > 如果政府选择周期性为自己添加货币，然后发行定量的债务，自身拥有定量的粮食/工坊生活消费品等需求，会怎么样？
   > GOV 缺钱直接印就行，试试看把 GOV 主体加入 1000tick 循环。”
3. **2026-09-30，辖区、税/地方债、组军**（`docs/superpowers/plans/2026-09-30-jurisdiction-tax-army-plan.md:3-6`）：
   > 「基础肯定是 MapRegion 啊，允许进行 Region 数据结构的操作，但是应当在 Unit 里搞一个对应富数据结构，
   > 挂载在单位下表示管辖；组军本质上就是从地方抽人力抽经济，做好来源、行动记录就行」
4. **2026-10-02，给 XXX 政府钱的 Tool**（`docs/superpowers/specs/2026-10-02-architecture-design-source.md:41`）：
   > 「称臣纳贡是一种特殊的外交关系，具体交多少钱给宗主国，其实也是附庸国的意愿直接决定，不要在程序上写死，
   > 只做一个给XXX政府钱的Tool暴露给决策人，这个tool同样应适用于地方政府」
5. **2026-10-02，R5 支付工具裁定**（同文件 `:175`，用户选项）：
   > 「复用 RemitGovTreasury 三资源；付款人=DM 所属 GOV（推荐）」
6. **2026-09-25，为后续政府模块预留接口**（`docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md:266`）：
   > 「当前已有模块要为后续政府模块的政策调控预留好接口」
7. **2026-09-27，首都缺粮与 GOV 职责**（`docs/superpowers/specs/2026-09-27-regional-market-design.md:6`）：
   > GOV 未写，首都粮食供应是 GOV 的协调职责（该行是对用户裁定的引用；全文见该文件）
8. **中央政府不设全视野**（`AGENTS.md:854`，§十.2）：
   > 「**中央政府不应有全视野！！！本来作用就是让中央和地方博弈**」
9. **派单粒度 / 责任区（实施纪律，与本报告 §10 直接相关）**（`AGENTS.md:117-156` §一.5、`:183-251` §一.8、`:324-357` §一.10）：
   > 用户 2026-09-27 原话：「**不是，为啥一个 M1 能拆这么多子任务，不是先写代码和过编译，等最后再搞测试吗**」
   > 「**也没让你自己写，是让你派子代理写代码、过编译，然后最后你搞测试，具体也是派测试 Agent 盯着，
   > 一个阶段拆这么多干啥？**」
   > 用户 2026-10-06 原话：「我注意到，你调用子Agent的时候，不跟它讲代码设计，只让它干测试，那它怎么可能干得好？
   > 往AGENTS.md里加上，派子Agent实现架构设计，必须先保存一份可以被阅读、验证、逻辑完全的架构设计文档，
   > 然后必须以此明确告诉子Agent新架构长什么样，现在先记、写。」
10. **铸币计划的来源说明**（`docs/superpowers/plans/2026-10-08-small-world-gov-mint-plan.md:3-10`）：
    该文件用“来源：用户 2026-10-07/08 裁定：”的**转述**列了 7 条（铸币是工匠类劳动、政府家户持有粮/钱、
    投入=劳动+工具、决策人控制规模、伪币后置、全相关日志），**没有引号原话** —— 按 §一.8.1 的口径，
    实现前应把该主题的用户原话补成独立附录；本报告只如实标注“转述，非逐字”。

---

## 13. 结论摘要（给裁定的最短版）

- F1：**公式没坏，配员错了**；`YAMEN=0` 经 `min` 门把效率归零；短期用 `simos.gov.applyStaffing` 配 100/139 或按 R1~R3 改口径/税基。
- F2：**国库不是“空转”，是“从来没注资”**；两个政府家户账户创世零库存，行政俸禄 sink、军俸全 `no-payable-leg`；
  先用 G1/G2 给一条具名注资/转移路径，再裁共享预算/优先级。
- F3：**world-silver 不是 GOV 单位**，铸币只进自己账；`simos.gov.remit/pay` 够不着；M1 生产方式整体未实现；
  先裁 G1/G5 的关系，别把“中央/世界级政府”误当 GOV Unit。
- F4：**239 人/19 格 = 现行公式的“正常覆盖”**；要么按 A 配满、要么按 R3 做小世界/profile 覆写或改需求粒度；
  不要把 19 格世界的平衡直接写进全局 `GovRules`。
