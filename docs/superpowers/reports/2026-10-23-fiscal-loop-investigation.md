# 税制/财政闭环只读排查：D1 辖区互斥 / D2 预算 oracle 与实扣 / D4 军俸部分支付 / 中央—省闭环

> 状态：**只读排查**。未改代码、未跑 Maven/测试/服务、未 `git add`/commit；唯一写盘 = 本报告。
> 事实来源：仓库代码（`file:line`）+ run6 现场日志/转储 + run4/run5 对照；用户 2026-10-23 裁定见 spec §23。
> 相关必读：`docs/superpowers/reports/2026-10-23-gov-batch-run6-vs-run5.md`、`docs/superpowers/specs/2026-10-23-gov-service-mode-design.md` §7/§9/§11/§23、
> `.superpowers/sdd/2026-10-23-gov-service-mode-design/{z3a,z3b,z3c1,z5}-impl-ledger.md`。

---

## 0. 一句话结论

**D1** 推荐 **A：把中央座位格 `(0,0)` 从 `small-world` 拆成独立 `capital-district` 区域**，省辖区只留 `small-world`（18 格）；中央 GOV 自辖该 district 但**初始税率设 0**（只给行政/视野，不自动抽税）。
**D2** 的根因**不是**账户键/冻结口径不同——两边都用 `HouseholdAccountKey(HouseholdId)` 与 `AvailableStock(余额−冻结)`；真正的根因是三条叠加：① D1 让省按日抽中央国库（run6 抽了 173 天）；② 国库家户被经济市场当普通家户生成卖单，run6 day50 市场把中央粮腿一次性清零；③ 预算桥按“价值”授权、执行器按“逐腿”实扣，且军俸规则在执行前就被裁剪掉零腿，导致 `authorized=0`、`treasuryLimited=0`、部分腿支付与原始请求丢失并存。
**D4** 的 `EXECUTED + 空粮腿` 不是执行器“部分支付”判错：day122 首都卫队规则在预算桥里已只剩银腿，执行器看到的规则本来就没有粮腿；应把**原始规则 + 逐腿授权**一起交给执行器，状态增加 `PARTIAL`，并逐腿记 `requested/authorized/paid/shortfall`。
**闭环** 推荐 **A（省按当日实收税的 ‰ 上缴中央，税后→remittance→预算）+ C（已有 `actor.RemitGovTreasury` / `simos.gov.transferTreasury` / `simos.gov.pay` 手工兜底）+ D（铸币留后续）**；B（中央自辖 district 抽税）作为可选，建议初始税率 0。D3（存量税 + 每日评估 = 特性）不动。

---

## 1. D1 辖区互斥

### 1.1 事实链（代码）

1. **辖区 = `Jurisdiction` 的 `Map<RegionId, Long>` 键集；空格 = 无管辖**：`simos-unit/.../Jurisdiction.java:40-45`，类注 `:12-14`。
2. **省辖区的税率表是在创世由 `unit.SetJurisdiction` 写入的**：`GovWorldBootstrap.java:336-350` 只在 `GovernmentLevel.PROVINCE` 分支发 `SetJurisdiction`/`SetTaxRate`；中央 GOV **没有** jurisdiction（z5 台账 §3.1/§3.2 同证）。
3. **取格口径只有一个：`map.regions().get(regionId).hexes()`**：`JurisdictionDailyTax.java:310-315` 逐 `region.hexes()`；`regionHexes` 按 `HexCoord::toString` 排序（`:312-313`），保证确定性。
4. **征税跳过“自己”的国库家户，但不跳过别人的国库家户**：`JurisdictionDailyTax.java:316-318` 只 `if (household.equals(treasury)) continue;`；`treasury` 是**本 GOV** 的 `hh-gov-<unitId>`（`:244-267`）。
5. **`SmallWorld` 是单 region `small-world`，含全部 19 格**：`SmallWorld.java:126`（`REGION_ID`）、`:180-199`（`HEXES` 含 `(0,0)`）、`:375-400`（`map()` 只 put 一个 Region）；类注 `:80` 明写“1 个 Region：全部 19 格都归属它”。
6. **两级 GOV 座位**：中央 `(0,0)`、省 `(0,1)`（`GovWorldBootstrap.java:115-118`）；两格都在 `small-world`。
7. **政府家户的经济落点 = 座位格**：`economy.RegisterGovernment` 的 `q/r` 来自 `GovWorldBootstrap.java:535-542`；新登记时写入 `HouseholdEconomy.view() = q_r|...`（`EconomyRegisterGovernmentHandler` 的 hex-view 分支），Social 侧位置另为 `UNIT:<govId>`（z5 §3.2）。⇒ **税基定位读 economy view 的 hex，不读 Social 位置**；`hh-gov-gov-central` 因此被算在 `(0,0)`、落进省辖区。
8. **同格还有世界级政府家户与首都民户**：`SmallWorld.java:84` 明写世界级政府家户 `hh-gov-world-silver` 落在首都；run6 day30 dump 显示 `(0,0)` 有 **13 个 economy 家户行 / 902 人口**（11 个民户 + `hh-gov-world-silver` + `hh-gov-gov-central`，后两者 population=0）。

### 1.2 run6 证据

- `service.log` 逐日：
  `HOUSEHOLD_STOCK_DEDUCTED ... household=hh-gov-gov-central reason=jurisdiction_tax to=hh-gov-gov-province`
  共 **173 条，day 1~173**（`grep ... | wc -l` = 173；首/末日 = 1/173）。
- 同一模式也命中世界级政府家户：`household=hh-gov-world-silver reason=jurisdiction_tax` 共 60 条，日期段为 **day 1~29、121~149、241~242**（与 `world-silver` 的周期铸币/发债再补给节奏吻合；见 §4.8）。
- day1 `TAX_DAILY_END unitsCharged=1 householdsCharged=107 grainAssessed=8,905,333 grainCollected=2,226,320 ...`；day360 `grainAssessed=124,004,669 grainCollected=31,001,118`，360 天 `grainCollected` 合计 **2,040,450,189 毫**，全部落省国库（run6 报告 §3）。
- 中央 `GOV_BUDGET_PLAN day=1 unit=gov-central availableGrain=975000`——刚被省抽走 25,000 粮（100‰ × 250‰ = 2.5% 存量）；day49 plan 仍 `availableGrain=271393`；day50 plan 直接 `availableGrain=0`（见 §2）。
- 省税基份额（day30 dump `seg-030.json`）：`hexes["0,0"].grainStock=14,045,324`，19 格合计 **80,239,848**，占比 **≈17.5%**；`(0,0)` `actorMoneyTotal=67,299`，19 格合计 **269,200**，占比 **≈25.0%**。⇒ **A/B 这种逐 hex 切法会把首都整格 902 人及两个政府国库整体移出省税基**；具体到 day360 的税基份额未按格拆分（`TAX_DAILY_END` 只有 19 格汇总），无法从静态日志精确给出，人口占比是下界。

### 1.3 三个实现方案

| 维度 | A 拆独立 `capital-district` region | B `Jurisdiction` 逐 hex 排除/白名单 | C 中央自辖该 region |
|---|---|---|---|
| 改动面 | `simos-app` 的 `SmallWorld.map()/state()`、`WorldRegistry`、`run-small-world.sh`、文档；若同时做 C，`GovWorldBootstrap` 增一个 region 参数 + 中央一条 `SetJurisdiction` | `simos-unit` 的 `Jurisdiction` record + `UnitCodec` + `UnitOperations.setJurisdiction` + `SetJurisdictionHandler`/`UnitPayloads`；`simos-app` 的 `JurisdictionDailyTax`、`GovScope`；测试构造函数多处 | 不是独立方案：必须叠在 A（或 B 的逐 hex 白名单）上；A 下只是 bootstrap 多发一条中央 `SetJurisdiction`（可选 `SetTaxRate`），改动最小 |
| map/unit | 只改 map 区域构造；unit 零形状改动 | unit 新增一个组件（region→排除 hex 集），map 不变 | 不改形状；改中央 jurisdiction 状态 |
| 市场拓扑 | **无影响**：`MarketTopologyBook.java:102-137` 同币种走 `singleRegion(economy.markets())`，城市 region 不参与；node 仍是 social 城市 | 无影响（同上） | 无影响 |
| 城市 | **必须同步**：`SmallWorld.state():250-253` 目前给两座城都传 `region.id()`；首都 `c-small-capital` 的 `SocialCity.region` 要改为 `capital-district` | 城市 region 不变；首都仍在 `small-world` 名下 | 同 A |
| seed | 逐 hex 数据不变；`SmallWorld` 只是 region 表多一条、去掉 `(0,0)`；确定性常量 | 不变 | 多一条固定命令 |
| 既有 run | run6 store 非空、bootstrap 不重跑 ⇒ 不受影响；run7 必须换空 store（同 Z5 §7.8） | 同左 | 同左 |
| 旧档 | **零 Codec 改动、零迁移**；旧 `small-world` 库仍是 1 region | 旧档缺新字段 ⇒ 构造函数归一成空集，行为逐字不变；但新 JSON 旧代码读不了（不在承诺内） | 同 A |
| 幂等/确定性 | 常量 + 固定顺序；`SmallWorld.state` 两次 equals 自然成立 | 集合必须规范序（建议按 `HexCoord` 自然序冻结），否则 JSON/变更集不稳 | 同 A |
| 与“中央座位不入省辖”贴合度 | **最高**：map 事实、税、视野（`GovScope` 取 jurisdiction region 集）三者一致 | 中：税/视野可排除，但 map 事实/`RegionIndex`/Nation 归属仍把座位算在 `small-world`；且要同时改 `GovScope` 才不“税排了、视野没排” | 高（在 A 之上补行政权） |
| 主要风险 | 首都整格 902 人 + 两个政府国库离开省税基；`world-silver` 铸币收益不再被省抽走；文档/测试“1 Region”文案要改 | `Jurisdiction` 形状/Codec/测试面大；排除/白名单若用“空集=全region/无region”会语义歧义；是“第三个空间真相” | 税率 >0 时中央把首都存量快速抽干（D3 同型），且与 remittance 叠加会双重抽取 |

> 独立新增方案 D（**仅家户级国库守卫**，不在 A/B/C 原列，但本排查必须报出）：
> 不拆 region，只在 `JurisdictionDailyTax` 里跳过“**别国政府的国库家户**”（或“上级政府国库家户”）——首都 902 个民户仍留在省税基，只保护 `hh-gov-gov-central` / `hh-gov-world-silver`。
> 它改动最小、不触 map/Codec，但**不实现“辖区互斥”**（座位仍在省 region、视野仍属省），且与用户 §23“不采用政府家户一律免税”措辞需严格区分（守卫对象是“下级不得征上级国库/他国国库”，不是“政府家户免税”）。若用户本意只是“中央国库不被省抽”，D 比 A/B 更贴合；若本意是“座位不属于省”，则必须 A/B。

### 1.4 推荐

**主推 A（district 仅含 `CAPITAL_AT=(0,0)`，或由用户裁定是否含必要邻格）+ C（中央 `SetJurisdiction capital-district`、初始 rate 0）。**
理由：① map region 是空间事实的唯一权威（铁律 3），A 不新增 unit 状态、不新增 Codec；② 税、视野、区域读口自然一致；③ C 让中央在“无全视野”前提下（AGENTS §十.2）拥有可读可管的直辖 district，但 rate 0 不自动抽税，把中央收入交给闭环 A/C 明确定夺；④ 若用户只想保护国库而不动首都民户税基，则选 **D 守卫**，并放弃“座位不入省辖区”的语义。

**实施注意（A+C）**：
- `small-world` 的 hex 集必须**不含** `(0,0)`（这是 run7 的第一断言：`map.regions().get(small-world).hexes()` 不含 `CAPITAL_AT`）。
- 首都城的 `region` 必须同步为 `capital-district`；镇仍 `small-world`。
- 若中央初始 rate=0，则 `SetJurisdiction` 之后**不发** `SetTaxRate`；中央决策人可后续用现有 `simos.unit.setTaxRate` 调。
- 旧 `SmallWorld` 世界文档 `docs/superpowers/specs/2026-10-23-smallworld-19hex-and-economy-360tick-design.md:42/52` 的“1 Region”为历史口径：不回头改旧文，在新设计书/裁定条目里写“已被 Z7a 取代”。

---

## 2. D2 预算 oracle 与实扣不一致

### 2.1 先排除假设：账户键/冻结不是根因（代码事实）

| 问题 | 代码事实 |
|---|---|
| `HouseholdAccountKey` 是否含 hex/位置 | **不含**：`HouseholdAccountKey.java:28-47` 唯一组件是 `HouseholdId`，`toString()=household.value()`，类注 `:12-14` 明写“没有 HexCoord，位置从 Household.location 派生”。 |
| 会话如何取账 | `AccountSession.householdAccount(HouseholdId)` 只按家户索引取（`AccountSession.java:246-250`）；`registerHousehold` 另存 `householdLocations`，但它是“分区/转移 location 核对”，**不参与身份**（`:186-223`）。 |
| 税扣款如何取账 | `JurisdictionDailyTax` 先按 economy row 的 `view().hex()` 找税基（`:135-144`），再 `accounts.householdAccount(household)`（`:319`），收款方 = `GovernmentHouseholdResolver.requireGovernmentHousehold(unit, unitId)` → `hh-gov-<unitId>`（`:244-267`）。 |
| 预算 oracle / 工资 / 军俸如何取账 | `GovBudgetExecutionBridge.treasuryAvailable` 同样走 `GovernmentHouseholdResolver` + `accounts.householdAccount(treasury)`（`:1177-1200`）；`GovernmentUpkeepOracle`、`PeriodicHouseholdAdjustmentExecutor`、`StockDeductionService.shadowInventory` 也全是同一个 `HouseholdId` 解析（`GovernmentUpkeepOracle.java:60-150`；`PeriodicHouseholdAdjustmentExecutor.java:229-252`；`StockDeductionService.java:248-278`）。 |
| `AvailableStock.available` 的冻结/保留语义 | 全仓唯一算法：`available = 余额 − 冻结`（`AvailableStock.java:60-62`）；**没有**“必要生产投入/生活保留/最低现金”第三项（类注 `:21-34`）。冻结只来自市场挂单，轮末释放（`MarketSettlement` 的 release 路径）。税、预算、`RemitGovTreasuryHandler` 都读同一算法。 |
| `hh-gov-*` 的 Social 位置 = UNIT(gov) 与 economy view hex | Social 位置用于社交/户口一致性；税基/市场/预算读的是 economy row 的 `view().hex()`，对 `hh-gov-*` 等于 RegisterGovernment 的座位格。run6 day1 税扣款命中的 `hh-gov-gov-central`，在 economy 里就是 `(0,0)`。 |

⇒ **run6 报告 §4-D2 里“`treasuryAvailable` 与税扣款/执行器落账的账户键/冻结/最小保留口径有偏差”的假设不成立**。两边同账、同算法；真正的差异在**逐腿 vs 价值、原始请求 vs 裁剪后规则、以及收入侧（税/市场）在日间的移动**。

### 2.2 根因 1：D1 让省按日抽中央国库

- 省 jurisdiction=`small-world`，中央座位 `(0,0)` 在其中；税基按 economy view 命中的 `hh-gov-gov-central` 不在“本 GOV 自己国库”的排除项里（排除项 = 省的 `hh-gov-gov-province`），于是被征。
- 税率 100‰ × 省效率 250‰ = **2.5%/日**的中央存量粮/银，连续 173 天后中央粮先见底、银继续被抽。
- 这与 §1 是同一事实：D1 是 D2 的**上游抽水机**。

### 2.3 根因 2：国库家户被市场当普通家户清算（run6 day50 粮腿归零的直接触发）

- 市场开市判据：`MarketSettlement.triggerFor`：`day % 5 == 0` 例行轮（`MarketSettlement.java:615-626`）；run6 day50 是市场日。
- 订单生成：`ordersFor` 对每个参与者算
  `sellable = max(0, stock − frozen − necessary − retention)`（`MarketSettlement.java:1243-1250`）。
  `retention = life + demandTarget`；`hh-gov-*` 是 **0 人口、自然需求空、participation=0** 的家户行（economy `RegisterGovernment` 写入），其 office unit 的 `outputPerUnit={}`、`cycleInputPerUnit={}`（`GovWorldBootstrap.java:452-470`）⇒ `necessary≈0`、`retention=0` ⇒ **国库的全部粮/布都是 sellable**。
- 参与者构建不排除政府国库：`participantsFor` 先按本格家户行建 `Participant`（`MarketSettlement.java:4551-4600`），`hh-gov-*` 有 economy row 就会入市。
- run6 证据（同日顺序）：
  - `service.log:40138` day50 `MARKET_REPORT ... fills=99 ...`；
  - `service.log:40260` day50 `TAX_DAILY_END ... grainCollected=448074 ...`；
  - `service.log:40263` day50 `GOV_BUDGET_PLAN unit=gov-central availableGrain=0 availableCloth=0 availableSilver=27756`（day49 plan 是 `availableGrain=271393 availableCloth=0 availableSilver=28220`，`service.log:39482`）。
  - day50 `SETTLEMENT_END_DETAIL` 的 `byReasonGoods` 除 `LOAN_PRINCIPAL=0` 外，只有 `MARKET_TRADE=2,701,784`（毫），没有其它商品移动原因；day50 `INPUTS_CONSUMPTION consumedQuantities=335,004` 与前后日同量级，不是中央专属消耗。
  - store tick60 的 actor 账户（`revisions.revision=7` 的 `actor.accounts` upsert）显示 `hh-gov-gov-central`：`balances={}`、`money={silver=21241}`、`frozenBalances={grain=0,cloth=0}` ⇒ **粮是真被转走，不是冻结**。
- 结论：**day49→day50 的 271,393 粮归零，与 day50 市场成交是唯一一致的静态解释**；粮被换成少量银（day50 plan 银从 28,220 微升到 27,756，扣除当日税后净增 ≈ 279 毫），与市场价量级吻合。**未逐户证到成交对手方**——run6 未开 TRACE，逐笔成交日志未落盘；这是本报告的有意边界。

### 2.4 根因 3：`allocate` 按价值授权、`takeLegs` 逐腿实现、`treasuryLimited` 用标量差

- 分配算法：`GovBudgetExecutionBridge.allocate`（`:245-294`）
  - `authorizedValue = min(capEffective, spendable)`（`:274-275`），是**三腿 1:1 合计的价值**；
  - `takeLegs(requested, remaining, authorizedValue)` 按固定腿序 `grain→cloth→silver` 逐腿取（`:297-306`）；
  - `treasuryLimited = capEffective − authorizedValue`（`:280`）——这里用的是**标量授权值**，不是 `takeLegs` 实际取到的向量价值。
- ⇒ 当 `authorizedValue` 被“银腿的价值”满足、但请求的腿是粮/布时，`takeLegs` 实际取到空向量，而 `treasuryLimited=0`、`capLimited=0`、`floorUnmet=0`，缺口**没有任何分类字段**。
- **最小静态复现（可直接按文件行核对）**：
  1. `dumps/seg-060.json → hexes["0,0"].dashboard.crisis.signals[kind=ADMIN_BUDGET_SHORTFALL].evidence`：
     `requestedValue=522, authorizedValue=32, shortfallValue=490, capLimitedValue=0, treasuryLimitedValue=0, floorUnmetValue=0, categories=2`。
  2. `grep "GOV_BUDGET_PLAN.*day=60 unit=gov-central" service.log`：
     `availableGrain=0 availableCloth=0 availableSilver=21273`；stipend 请求 `grain166+cloth4`、salary 请求 `grain320+silver32`；`authorizedValue={ADMIN_STIPEND=0, ADMIN_SALARY=32}`。
  3. 按 `allocate` 逐类演算：stipend `authorizedValue=170`、`takeLegs` 取 `(0,0,0)`、`treasuryLimited=170−170=0`；salary `authorizedValue=352`、`takeLegs` 取 `(0,0,32)`、`treasuryLimited=352−352=0`；⇒ 490 的缺口分类全 0。
  4. 真正的价值见底出现在 **day175**：`GOV_BUDGET_PLAN day=175 unit=gov-central availableGrain=0 availableCloth=0 availableSilver=0`；`dumps/seg-180.json` 同信号变成 `requested=522, authorized=0, treasuryLimitedValue=522`。
- 这也直接纠正 run6 报告 §4-D2 的一句：`ADMIN_BUDGET_SHORTFALL` 从 day3 起有警报没错，但 **day3~day174 的 `treasuryLimitedValue` 是 0**（run6 day30 dump：requested=170、authorized=166、treasuryLimited=0，缺口是 4 毫布腿），不是“从 day3 起报 treasury-limited”；信号 reason 字符串却一直写“国库预算不足”。

### 2.5 “oracle 先见底而税仍能扣 / authorized=0 与部分腿支付并存”怎么解释

- `treasuryAvailable` 与税都读同一本账的**逐腿**可用量：day50 起中央 `grain=0, cloth=0, silver>0`，所以
  - 预算 oracle 对**粮/布请求**“见底”；
  - 税 `assess`（`JurisdictionDailyTax.java:473-490`）仍能对**银腿** `collected=min(attainable, available)`，于是 `jurisdiction_tax` 继续扣到 day173；
  - `authorized=0` 只发生在粮/布腿的类别/腿上；银腿的工资 `authorized=32`、军俸 `authorized=30` 仍会执行，所以 log 里出现“某些腿仍支付”。
- **更正 run6 报告的措辞**：中央不是“day49 之后 311 天全 0”——
  - `GOV_ADMIN_SALARY_DAY unit=gov-central`：day1~49 `paidGrain=320/paidSilver=32`；day50~173 `paidGrain=0/paidSilver=32`；day174 `paidGrain=0/paidSilver=18`；day175+ 全 0。
  - `GOV_OFFICE_UPKEEP_EVALUATED unit=gov-central`：day1~49 `grainPaid=166`；day50+ `grainPaid=0`、`clothPaid` 一直 0（布在 day3 市场就清掉了）。
  - 即：**粮腿 day50 停，银腿 day173/174 才停**；不存在同一腿“oracle 说 0 但实扣仍扣”。
- **D2 修复不应去改账户键**；要修的是：
  1. **收入侧**：禁止政府国库家户进入市场订单生成（或至少不生成卖单），见 §2.6；
  2. **授权账本**：按价值授权改为“逐腿授权 + 逐腿缺口分类”，让 `treasuryLimited` 只表示价值约束，新增 `legStockLimited`/逐资产缺口；
  3. **原始请求贯穿**：执行器必须拿到原始规则 + 授权向量，不能只看裁剪后的规则（见 §3）。

### 2.6 D2 修复方向（建议）

| 修复 | 落点 | 形状/守卫 | 验证 |
|---|---|---|---|
| **F2-1 国库户退出市场** | `simos-economy`：`EconomySettlement` 从 `base.governments()` 汇总 `Set<HouseholdId> treasuryHouseholds`，传入 `MarketRound`；`participantsFor`/`ordersFor` 对该集合**不生成买单/卖单**（或至少在 `ordersFor` 的 sell 分支跳过） | 只新增一个只读集合；`Government.treasury()` → `HouseholdActors.householdOf`；覆盖 `hh-gov-world-silver` 与所有 GOV 单位国库；不变量“财政库存不是市场商品” | 单测：国库有粮/布/银但 `planOrders` 无卖单；run7：市场日 `GOV_BUDGET_PLAN unit=gov-central availableGrain` 不再被市场清零 |
| **F2-2 逐腿授权/缺口账本** | `GovBudgetExecutionBridge.allocate`：在 `takeLegs` 之后算 `shortfallByLeg=requested−authorized`；`treasuryLimitedValue` 仅在 `spendable<capEffective` 时计；新增 `legStockLimitedValue`（或逐资产 map）与 reason（`leg-stock-limited:grain` 等）；evidence 增 `requestedByLeg/authorizedByLeg/shortfallByLeg` | 不改账户语义，只改读数/分类；`CategoryAllocation` 可加字段或新增 `LegShortfall` record；保序/冻结 | 单测：复现 day60 的 522/32/490 场景 ⇒ 分类不再全 0、reason 点名 grain；负向：价值不足场景 `treasuryLimited` 仍正确 |
| **F2-3 oracle 与实扣同源（规则层）** | `capBudgetedRules` 保留每条到期规则的**原始请求**与**逐腿授权**；`DayBudget` 把这两张表一起交给执行器；执行器 `paid=min(available, authorized, requested)`，不 pre-drop 腿（详见 §3） | 唯一授权账本；税/remittance 之后的 `availableAtPlan` 是唯一收入侧快照；`StockDeductionService` 仍是唯一落账口 | 单测：plan 授权合计 ≤ 当前可用；执行后实付 ≤ 授权；`GOV_BUDGET_PLAN → PERIODIC_ADJUSTMENT_RULE` 逐值可对账 |
| **F2-4 告警口径** | `ADMIN_BUDGET_SHORTFALL` 的 reason 不再一律写“国库预算不足”；按 `cap/floor/value/leg` 四类给出，逐腿列出；不自动注资/调计划/调税（保持 §7“只告警”） | 复用现有信号；如需独立告警类，再加 `HexCrisisSignal.Kind` | 负向：无缺口的类不报；不构造任何自动命令 |

---

## 3. D4 军俸“EXECUTED + 空腿”

### 3.1 事实链

1. 政策（run6 脚本 `setup_tick0.py:31`）：首都卫队 `grain=300, money=30`；镇戍部队 `grain=180, money=18`；`periodDays=120, startsOnDay=1, phaseDay=1`（day2/122/242 到期）。
2. 桥接纯函数确实把两条腿都放进规则：`MilitaryPayRuleBridge.java:195-240`（policy 的 grain/cloth/money 逐户 put；空腿家户才不生成规则）。
3. 但预算桥在执行前**先裁剪、再丢零腿**：
   - `capBudgetedRules`：`taken = minVector(requested, availableForCategory)`（`:585-587`），`if (!taken.isEmpty()) cappedRules.add(cappedRule(...))`（`:595-597`）——**一整条被裁到空的规则会直接从执行器输入里消失**；
   - `cappedRule`：只把 `taken>0` 的腿写回规则（`:603-628`）——**零腿（如 grain）不在规则里**。
4. 执行器只看到裁剪后的规则：`PeriodicHouseholdAdjustmentExecutor.executeOne`（`:229-342`）
   - `paid = min(requested, AvailableStock.available)`（`:256-288`）；`shortfall = requested − paid` 只相对**它收到的规则**；
   - 所有 `paid*` 都空 ⇒ `SKIPPED no-payable-leg`（`:286-288`）；
   - 至少一腿 `paid>0` ⇒ `EXECUTED`（`:334-341`），状态枚举只有 `EXECUTED/SKIPPED`（`:455-459`）。
5. day122 静态复现：
   - `GOV_BUDGET_PLAN day=122 unit=gov-central`：`availableGrain=0 availableCloth=0 availableSilver=3453`；`authorizedValue={ADMIN_STIPEND=0, MILITARY_STIPEND=30, ADMIN_SALARY=32}`；
   - 首都卫队 `PERIODIC_ADJUSTMENT_RULE day=122`：`status=EXECUTED paidGoods={} paidMoney={silver=30} shortfallGoods={} shortfallMoney={}`；
   - 同天镇戍部队（付款方省国库，粮充足）：`paidGoods={grain=180} paidMoney={silver=18}`；
   - 中央工资（salary bridge 自己保留了原始请求）：`GOV_ADMIN_SALARY_DAY day=122 ... requestedGrain=320 requestedSilver=32 paidGrain=0 paidSilver=32 shortfallGrain=320 shortfallSilver=0`——**同一预算下，工资路径能报粮 320 缺口，军俸路径报不出**，差别就在 `SalaryBook` 保了原始请求、军俸没有。
6. run4 对照（`/home/cna/simos-runs/2026-10-23-sw19-run4/service.log`，`no-payable-leg` 6 条）：
   `PERIODIC_ADJUSTMENT_RULE day=2 rule=army-pay:unit-capital-guard... status=SKIPPED gap=no-payable-leg paidGoods={} paidMoney={} shortfallGoods={grain=300} shortfallMoney={silver=30}`。
   run4 是“原规则两腿都在、都付不出” ⇒ SKIPPED + 双缺口；run6 是“粮腿先被预算桥删掉” ⇒ EXECUTED + 空粮腿无缺口。**不是同一个判据。**

### 3.2 结论

- `EXECUTED + 空粮腿` **不是执行器的部分支付判错**；执行器对“它收到的规则”的逐腿 `min`/`shortfall` 是自洽的。
- 缺陷在**预算桥与执行器之间的信息丢失**：
  1. 零腿规则被直接剔除，执行器/日志里可能完全没有这条到期规则；
  2. 被裁掉零腿的规则，执行器无法知道原始请求，故 `shortfallGoods` 为空；
  3. `ADMIN_BUDGET_SHORTFALL` 的标量分类又把这类缺口记成“未分类”（见 §2.4）。

### 3.3 方案（用户口径：有问题只给 GM/决策人警报，不自动）

推荐把“预算授权”变成执行器的**第一等输入**，而不是先改规则：

1. `capBudgetedRules` 对每条到期军俸/工资规则产出一个 `BudgetedRule { originalRule, requestedByLeg, authorizedByLeg, category, gov }`；**不丢零腿、不丢零授权规则**。
2. `PeriodicHouseholdAdjustmentExecutor.applyExplicit` 改收这批 `BudgetedRule`（或等价的 `authorization` 映射）：逐腿 `paid = min(available, authorized, requested)`；`shortfall = requested − paid`；`gapReason` 逐腿写 `budget-authorized-zero` / `treasury-available-zero` / `service-rejected`。
3. `RuleReadout` 增 `requestedGoods/requestedMoney`、`authorizedGoods/authorizedMoney`，`paid*/shortfall*` 全部相对**原始请求**。
4. 状态语义（冻结建议）：
   - `EXECUTED` = 原始请求的每条腿都足额；
   - `PARTIAL` = 至少一腿 `paid>0`，且至少一条原始腿有缺口；
   - `SKIPPED` = 无任何腿 `paid>0`（含 `no-payable-leg` 与 `budget-authorized-zero`；后者是“被预算裁到 0”，要在 gap 里点名，不能与“国库没钱”混为一谈）。
5. 日志/读口：
   - `PERIODIC_ADJUSTMENT_RULE` 增原始/授权/实付/缺口四组字段；day122 首都卫队应显示 `status=PARTIAL, requestedGoods={grain=300}, authorizedGoods={}, paidGoods={}, shortfallGoods={grain=300}, paidMoney={silver=30}`；
   - `ADMIN_BUDGET_SHORTFALL` 增逐腿 evidence 与 reason（见 §2.6）；
   - `simos.gov.info`/跨日汇总沿用现有读口，V1 不新增 `GovOfficeState` 持久字段。
6. 不做：自动补款、自动调薪、自动调预算、自动招募（保持 §7“六类告警只告警”与用户“只给警报”原话）。
7. 备选（更小）：只加一条 per-rule 预算缺口日志（原始请求 vs 实付），保留 `EXECUTED`；代价是状态语义仍会把“部分支付”读成“执行成功”，不推荐。

---

## 4. 中央/省财政闭环

### 4.1 现状

- 省：辖 `small-world`（run6 含 19 格）、税 100‰、效率 250‰，税收入省国库，自付行政俸禄（166 粮 + 4 布/日）与官吏工资（320 粮 + 32 银/日），360 天足额；国库只进不出（run6 报告 §3）。
- 中央：**无辖区、无税收入**；run6 只靠创世注资 粮 1,000,000 / 布 100,000 / 银 100,000 毫/ GOV（`GovWorldBootstrap.java:124-130`）；`registerGovernmentPayload` 未给 `issuable/seignioragePerCycle`（`:535-542`）⇒ 中央无铸币。
- run6 中央被省税抽干（§1/§2），粮 day50 停、银 day173 停。
- 即使修掉 D1+D2 市场清算，中央现有注资按当前支出估算：粮 ≈ 1,000,000 ÷ (166+320+300/120) ≈ **2040 天**、银 ≈ 100,000 ÷ (32+30/120) ≈ **3100 天**、布 ≈ 25,000 天 ⇒ **360 tick（run7）能撑住，但不是长期闭环**；闭环 A/C 仍是需要的。

### 4.2 可选闭环

| 方案 | 做法 | 状态/命令改动 | 优点 | 代价/缺口 |
|---|---|---|---|---|
| **A 上级拨款/remittance（推荐主路径）** | 省按政策把**当日 `jurisdiction_tax` 实收**的一定 ‰ 上缴 `superiorGov` 国库；税后→remittance→预算；不足按可用部分转 + 告警 | 复用 `GovBudgetPolicy`（加 `remittancePerMilleToSuperior`）或新 `GovFiscalPolicy`；app 新 `GovRemittanceBridge`；`JurisdictionDailyTax.Report` 增逐 unit 实收；新 `DeductionReason.GOV_REMITTANCE` | ① 与“省征税、中央受益”的闭环语义一致；② 流量基（不是存量）⇒ 不引入第二个 D3 式指数抽干；③ 政策显式、可审计、可调；④ 复用现有 `simos.gov.setBudgetPolicy`（GM + 决策人审批链） | 需要新执行点 + 逐 unit 税报；比例/所有者要裁定；省上缴过多会挤压省自身预算（应只告警） |
| **B 中央自辖首都 district** | D1 A 之上，中央 `SetJurisdiction capital-district`；可选 `SetTaxRate` 收自己的税 | A 的 bootstrap 多一条/两条命令；不改状态形状 | 中央有独立行政辖区和（若设率）自有税基；与“中央地方博弈”直观 | rate>0 时中央按 D3 同型快速抽首都存量；与 remittance 叠加是双重抽取；rate=0 则只有行政/视野，无收入 |
| **C 显式 `simos.gov.transferTreasury` 手工拨款（兜底/已有）** | GM 从任意已有账户注资 GOV 国库；`simos.gov.remit` GM 任意两 GOV 转账；`simos.gov.pay` 决策人版本走审批链；底层 `actor.RemitGovTreasury`（非 GmOnly，可嵌决策令） | **已经实现**：`GovTransferTreasuryTool`、`GovRemitTool`、`GovPayTool`、`RemitGovTreasuryHandler`；不要新增 | 完全显式、原子、可审计、不自动 | 需要人/GM 触发；不能替代长期自动闭环，但适合兜底、危机注资、首日种子 |
| **D 铸币/铸币收益（F3，本批不做）** | 给 GOV 的 `RegisterGovernment` 配 `issuable` + `seignioragePerCycle`，走既有 `GovernmentSeigniorage`（M1 路径已存在，world-silver 出厂 2,000 毫/周期 + 发债 5,000） | 注册载荷 + 政策/命令；通胀/铸币收益归属另裁 | 长期中央收入不依赖省 | 用户已明确本批不做；会改货币总量/通胀语义 |

### 4.3 推荐组合与执行顺序

**推荐：D1 A + 中央 district（C 的 C 变体，rate=0）+ 闭环 A（remittance，主） + 闭环 C（手工兜底） + D 留后续。**
- 中央 district rate=0：中央有辖区/视野（`GovScope` 用 jurisdiction region 集，不会给中央全视野，符合 AGENTS §十.2），但不自动抽首都；财政来源由 remittance 明确定义。
- remittance 放在 **税之后、任何 GOV 预算规划之前**（同 §2 的同一 `AccountSession`）：
  `JurisdictionDailyTax.collect → GovRemittanceBridge.execute(所有 GOV，unit id 升序) → GovBudgetExecutionBridge.plan(所有 GOV) → GovDaily.settle → applyExplicit(军俸/工资) → 持久规则`。
  这样省、中央的 `treasuryAvailable` 都是**收入侧（税+remittance）完成后的同源快照**，满足“oracle 与实扣同源”。
- 逐日流程（A）：
  1. 取当日各省 `grainCollectedByUnit`/`moneyCollectedByUnit`（扩 `JurisdictionDailyTax.Report`）；
  2. 对每个有 `superiorGov` 的 GOV：`requestedLeg = floor(collectedLeg × remittancePerMille / 1000)`（溢出保护，粮/银各自）；
  3. `paidLeg = min(requestedLeg, AvailableStock.available(sourceTreasury))`；
  4. `paid>0` ⇒ `StockDeductionService.deduct(HouseholdStockDeduction.transfer(provinceTreasury→centralTreasury, grain/silver, reason=GOV_REMITTANCE, detail=...))`；
  5. `requested>0 && paid<requested` ⇒ 部分支付 + `ADMIN_REMITTANCE_SHORTFALL`（或现有 `ADMIN_BUDGET_SHORTFALL` 的逐腿 evidence）+ INFO 行；**不自动注资/借款/铸币**；
  6. `superiorGov` 不存在/不是 GOV、国库账户缺失 ⇒ 具名 ERROR + `ADMIN_CONTRACT`（状态损坏 fail-closed；正常状态由 unit 写入时校验拦截）。
- 守恒/幂等/审计：
  - 守恒：`HouseholdStockDeduction.transfer` 是原子转移，源扣 == 目标加（`StockDeductionService` 整批先校验后 commit）；run7 用 `GOV_REMITTANCE_DAY` 两侧字段逐日求和；
  - 幂等：remittance 是当日税的纯函数 + 策略；无持久“上次已转”状态；同一状态同一天两次计算同值（core 一条 revision 只跑一次）；策略 rate=0 完全不产生扣款/命令；
  - 审计：INFO `GOV_REMITTANCE_DAY from/to/perMille/requestedGrain/paidGrain/requestedSilver/paidSilver/shortfall*`；TRACE 逐笔走 `HOUSEHOLD_STOCK_DEDUCTED reason=gov_remittance`；策略在 `GovInfoTool` 的 budgetPolicy 视图可见。
- **比例与所有者（待裁）**：
  - 建议 rate 由**省自己的 `GovBudgetPolicy`** 持有（省决策人可调，GM 可改）；中央要更高比例走 `sd.IssueDirective`/谈判或 GM 裁定，Fits 中央—地方博弈；若用户要“中央单方面规定比例”，则需把 rate 放中央侧并对下级适用（新命令/策略面更大）。
  - 建议默认 **0**（旧档/旧世界不自动转账）；run7 小世界 bootstrap 可显式设一个小值（如 1‰）演示闭环。按 run6 day1 省 `grainCollected=2,226,320` 毫，1‰ ≈ 2,226 毫/日，已远大于中央当日粮支出 ≈ 486 毫/日；**任何正整数 ‰ 都会让中央在几日内转正**，比例大小主要影响省的自留与债务偿还，需要用户按“财政可持续/债务累积是特性”的口径定。
  - 是否加 `remittanceReserve*`（省先留最低国库/最低口粮再上缴）：V1 可不做（D3 已明确不做最低口粮保护）；若要保护省自身预算，可后加。
- 与 D3 的相容性：remittance 基于**税收实收流量**，不改 D3 的“存量税 + 每日评估”口径，也不做最低口粮保护；债务仍按原机制累积；中央不自动兜底省债务、省不自动注资中央。
- 与“只告警”的相容性：remittance 本身是**显式策略下的自动流**（同征税），不是“自动解决缺口”；缺额只告警，不自动调率/注资/调计划/铸币。

### 4.4 命令/工具结论（对应待裁）

- **若扩 `GovBudgetPolicy`**：不用新命令；`gov.SetBudgetPolicy` 增可选字段 `remittancePerMilleToSuperior`（0..1000，缺省 0），现有 `GovSetBudgetPolicyTool`（GM + 决策人审批链，`DecisionCallerFactory.java:163`）与 catalog/read 口同步即可。`GovState`/`GovChangeSet`/`GovCodec` 形状不变（`budgetPolicies` 是整表 `FieldDelta`），旧档缺字段 ⇒ 0。
- **若新 `GovFiscalPolicy` 组件**：需 `GovState` 加组件、`GovChangeSet` 加 FieldDelta、`GovCodec`、新 `gov.SetFiscalPolicy` handler、GM/决策人工具、审批链、catalog、`GovDaily.withOffices` 拷贝纪律、测试面明显更大；好处是预算与财政政策分离，后续可加 reserve/debt 旋钮。**V1 推荐前者**。
- 手工兜底**不需要新命令**：`simos.gov.transferTreasury`（GM）、`simos.gov.remit`（GM）、`simos.gov.pay`（决策人 + `AutoApproveGate → ConfirmGate`）已在仓库。

---

## 5. 缺陷 / 参数 / 待用户裁定

### 5.1 缺陷（建议修复）

1. **D1 上游**：省辖区包含中央座位，按日抽中央国库 173 天（`JurisdictionDailyTax:310-318` + `GovWorldBootstrap:336-350`）。
2. **D2 市场泄漏**：政府国库家户作为普通 economy 家户参与市场卖单生成，财政粮/布可被市场清算（`MarketSettlement:1243-1250`；run6 day50 中央粮 271,393→0）。
3. **D2 分类**：`allocate` 标量授权 vs 逐腿 `takeLegs`，`treasuryLimited`/cap/floor 全 0 时 490 缺口无分类、reason 误写“国库预算不足”（`GovBudgetExecutionBridge:274-292`；seg-060 dump 可复现）。
4. **D4 原始请求丢失**：预算桥先丢零腿/丢零授权规则，执行器只见裁剪后规则；军俸没有 `SalaryBook` 等价的原始请求账本（`GovBudgetExecutionBridge:586-628`；`PeriodicHouseholdAdjustmentExecutor:229-342`；run6 day122 对照 salary 可报 320 缺口）。
5. **run6 报告的三处措辞需更正**（以日志/代码为准）：① 中央不是 day49 后全停，银腿到 day173/174；② `ADMIN_BUDGET_SHORTFALL` 从 day3 起有，但 `treasuryLimitedValue` 到 day175 才出现；③ 税/预算不是账户键/冻结口径不一致，而是逐腿/价值/原始请求问题。

### 5.2 参数（无需改结构，由决策人/GM 调）

- 预算类别顺序、每类 `min/cap`（`GovBudgetPolicy`）。
- 省税率、中央 district 税率（若 B/C 启用）。
- remittance ‰、remittance 所有者（省/中央）、是否加 reserve。
- 军俸政策、官吏工资率、编制计划 `P_d`、修正 `k` 等（既有工具）。
- D3：存量税 + 每日评估 + 无最低口粮保护 = **特性，保持不变**（spec §23.2）。

### 5.3 待用户裁定

见 §6。核心是：**D1 是否真的要让首都整格 902 人离开省税基**；若否，A/B 都不合适，要选 D 家户级守卫。

---

## 6. 待裁定清单

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| R1 | D1 实现方式 | A 拆 `capital-district`；B `Jurisdiction` 逐 hex 排除/白名单；D 家户级国库守卫（新增） | **A**；若“只保护中央国库、不动民户税基” ⇒ **D**。B 仅在必须保持 1 region 的硬约束下故选 |
| R2 | district 的格集 | 仅 `(0,0)`；`(0,0)`+第一环 6 格；其它 | **仅 `(0,0)`**（最小手术、可后续 merge 扩）；扩大需另裁 |
| R3 | 中央 district 是否自辖、初始税率 | 无 jurisdiction；有 jurisdiction rate=0；有 jurisdiction rate>0 | **有 jurisdiction、rate=0**（行政/视野）；税率由中央决策人后调 |
| R4 | 首都整格民户（902 人）与 world-silver | A/B 会一并移出省税基；D 不动 | 若用户要“座位不入省辖”，接受 A/B 的税基迁移；world-silver 的铸币收益也不再被省抽走，需用户确认 |
| R5 | 闭环组合 | A remittance；B 中央自税；C 手工；D 铸币；任意组合 | **A 主 + C 兜底 + D 后续；B 作为 rate=0 的行政 district 可选加** |
| R6 | remittance 比例所有者 | 省 `GovBudgetPolicy`（省决策人）；中央单方面规定；GM 专属 | **省策略 + GM 可改**（保留中央—地方博弈）；若中央要强制，另开中央侧策略 |
| R7 | remittance 基/比例/顺序 | 当日**实收税**的 ‰；国库存量 ‰；固定额；顺序 | **当日 grain/silver 实收 × 0..1000‰；税后→remit→预算；默认 0，run7 bootstrap 显式设小值（如 1‰）演示** |
| R8 | remittance 命令/工具 | 扩 `GovBudgetPolicy`（复用 setBudgetPolicy）；新 `GovFiscalPolicy` + 新命令/工具 | **扩 `GovBudgetPolicy`**（V1 小面；旧档缺字段=0） |
| R9 | remittance 缺口告警 | 新 `ADMIN_REMITTANCE_SHORTFALL` kind；并入 `ADMIN_BUDGET_SHORTFALL` 逐腿 evidence | **并入现有信号 + 逐腿 evidence**（少动 `HexCrisisSignal`）；若用户要独立告警再新增 kind |
| R10 | D4 状态语义 | 新增 `PARTIAL` + 原始请求/授权/实付/缺口；只加 per-rule 日志、保留 EXECUTED | **新增 `PARTIAL` + 全账本字段**；绝不自动补款 |
| R11 | 市场排除范围 | 所有 `Government` 国库（含 world-silver）不生成买/卖单；只 GOV 单位；只排卖单 | **所有 Government 国库、买卖都不生成**（财政户不是市场主体的不变量） |
| R12 | 旧 store / run7 | run7 换空 store；旧 `small-world` 库不迁移；Codec 兼容仅限新字段缺省 | **换空 store**（同 Z5 既有口径）；旧世界要新基线只能重建 |
| R13 | 最低国库/口粮保护 | D3 已裁不做税基保护；是否给 remittance 加 reserve | **V1 不加**；若省自身预算被 remittance 挤压，先只告警 |

---

## 7. 建议责任区与依赖（AGENTS §一.5 / §一.8 / §一.10）

> 前置：**先由控制方写/更新约束设计书**（新 spec 或 spec §24，含用户原话附录 §一.8.1、状态形状、不变量、验收判据、文件所有权），经用户裁定 R1~R13 后再派实现 Agent；一个责任区一个写代码 Agent，一次只跑一个本仓 Maven。

| 区 | 目标（可独立编译验收） | 建议改动面 | 依赖 | 编译门槛 |
|---|---|---|---|---|
| **Z7a** | D1：`capital-district` 拆分 + 首都城 region 同步 + 中央 district jurisdiction(rate=0) + 文档/文案 | `simos-app`：`SmallWorld`、`WorldRegistry`、`GovWorldBootstrap`（region 参数/中央 SetJurisdiction）、`run-small-world.sh`；不触 unit/economy 形状 | 用户 R1~R4 | `tools/mvn-lock.sh -pl simos-app -am -DskipTests compile` |
| **Z7b** | D2 钱与告警账本：国库户退出市场 + 逐腿授权/缺口分类 + D4 原始请求贯穿/`PARTIAL` | `simos-economy`（`EconomySettlement`/`MarketRound`/`MarketSettlement`）；`simos-app`（`GovBudgetExecutionBridge`、`PeriodicHouseholdAdjustmentExecutor`）；可能 `simos-gov`（`GovDaily` 只读/evidence）；economy-api（若告警 kind 新增） | 用户 R10/R11；可与 Z7a 并行设计但同一文件 owner 串行 | `-pl simos-app -am -DskipTests compile` + `-pl simos-economy -am -DskipTests verify` |
| **Z7c** | 闭环 A：`GovBudgetPolicy.remittancePerMilleToSuperior` + `JurisdictionDailyTax.Report` 逐 unit 实收 + `GovRemittanceBridge` + `DeductionReason.GOV_REMITTANCE` + 读口/告警/catalog | `simos-gov`（`GovBudgetPolicy`、`GovPayloads`、`SetBudgetPolicyHandler` 文案）；`simos-economy-api`（reason）；`simos-app`（`JurisdictionDailyTax`、`PopulationEconomyTimeParticipant`、`GovInfoTool`、`GovSetBudgetPolicyTool`/catalog） | Z7b 的逐腿授权账本接口；用户 R5~R9/R13 | `-pl simos-app -am -DskipTests compile` |
| **Z7d** | run7 验证与统一测试：360 tick 空 store 真档 + 断言 + 负向 + `clean verify` | tests（新用例）/探针/run7 脚本（仓库外或 `tools/` 文档） | Z7a+Z7b+Z7c 全部生产代码落地 | `tools/mvn-lock.sh clean verify`（前台；最后一次） |

**文件所有权冲突提示**：`PopulationEconomyTimeParticipant` 会被 Z7b（预算/执行顺序）与 Z7c（remittance 插入）先后触碰；`GovBudgetExecutionBridge` 也会被两者触碰 ⇒ 必须按 Z7b → Z7c 串行，不能并写。`HexCrisisSignal.Kind` 若 R9 选新 kind，归 Z7b（或 Z7c）单独 owner。

**run7 建议断言（给 Z7d 的输入）**：
1. **D1**：`map.regions()` 两条；`small-world` 不含 `(0,0)`；`capital-district` 含 `(0,0)`；首都城 region=capital-district；省 jurisdiction 只有 `small-world`；中央 jurisdiction=capital-district。
2. **中央不被省税**：全 360 天 0 条 `HOUSEHOLD_STOCK_DEDUCTED household=hh-gov-gov-central reason=jurisdiction_tax to=hh-gov-gov-province`；`hh-gov-world-silver` 同样 0（若 R11 生效）。
3. **中央国库可持续**：`GOV_ADMIN_SALARY_DAY unit=gov-central` 360/360 `paidGrain=320 paidSilver=32`（或按策略目标足额）；`GOV_OFFICE_UPKEEP_EVALUATED` grain/cloth 足额；中央无 `ADMIN_BUDGET_SHORTFALL`（除非显式策略制造缺口）。
4. **市场日不清算国库**：每个 `day%5==0`，中央 `GOV_BUDGET_PLAN availableGrain/Cloth` 不因市场出现阶跃归零；账户 `frozen` 轮末回 0。
5. **D4 部分支付具名**：构造/遇到首都卫队粮不足场景 ⇒ `PERIODIC_ADJUSTMENT_RULE status=PARTIAL requestedGoods={grain=300} authorizedGoods={} paidGoods={} shortfallGoods={grain=300} paidMoney={silver=30}`；`ADMIN_BUDGET_SHORTFALL` evidence 逐腿点名 grain；无“EXECUTED + 空腿 + 空缺口”。
6. **remittance 守恒/幂等**：rate=0 时 0 条 remittance；rate>0 时每日 `Σ paid ≤ Σ requested`、源扣 == 目标加（逐腿）、不产生负余额；同一状态重复计算同值。
7. **D3 不变**：税 `assessed=floor(balance×rate‰)`、`collected=floor(assessed×efficiency‰)`、无最低口粮保护；债务仍跨关账日累积。

---

## 8. 自证与边界

- **只读纪律**：本报告之外无任何写盘；未运行 Maven/`test`/`verify`/服务/`git add`/commit；工作树排查开始时为 clean（`git status --short` 空）。
- **0 命中自证**：
  - `grep -c "no-payable-leg" run6/service.log` = **0**；同一写法在 `run4/service.log` = **6**（已打印 day2 两条 SKIPPED 样本，字段含 `shortfallGoods={grain=300}`）⇒ 模式/工具确实会命中，不是假阴性。
  - `grep -c "event=GOV_BUDGET_PLAN"` = **720**（2 GOV × 360 天）；`grep -c "event=TAX_DAILY_END"` = **360**；`grep -c "event=GOV_ADMIN_SALARY_DAY"` = **720**；`grep -c "hh-gov-gov-central ... jurisdiction_tax"` = **173** ⇒ 计数口径有正样本。
  - `treasuryLimited` 在 run6 `service.log` 里 0 命中；但该字段在 `dumps/seg-*.json` 的 `crisisSignals.evidence` 里存在（`seg-030.json` 4 处）——**不能拿日志 0 命中推断字段不存在**；本报告一律用 dump + 代码行。
  - `grep "MARKET_REPORT"` = 84 条（市场日），day50 在列；`grep "GOV_BUDGET_PLAN.*day=50 unit=gov-central"` 有且仅有一条。
- **未核点/未验证**：
  1. 未跑构建/测试/服务，未验证任何实现能否编译；未验证新字段 Codec 往返（只做静态推理）。
  2. **day50 市场成交的对手方未逐笔证到**：run6 未开 TRACE，`MARKET_REPORT` 只有汇总；本报告把它列为“唯一一致解释”而非“已证成交明细”。
  3. day30 dump 的 `grainStock`/`actorMoneyTotal` 是按格快照，未按 day360 复算；税基份额是**下界/近似**，不是精确 run7 预测。
  4. 未穷举 `Region` 的所有消费者；`MoveCapitalPlan`、`NationScope`/`HexOwner`（region tag）在 small-world 当前无 nation/DM 场景下按“无影响”记录，未逐工具跑。
  5. 未验证 `world-silver` 的 `GovernmentSeigniorage`（2,000 毫/周期）与 D1 A 后其国库/铸币收益走向；只从 run6 日志确认它曾被省税 60 天。
  6. 未验证用户对 R1~R13 的选择；本报告只给选项与建议。

---

## 9. 附录

### 9.1 用户原话（逐字）

- 「政府这个生产方式呢，是由政府自己预估需要的劳动力规模，然后决定的，所以情况比较复杂，需要自定义的点比较多，最好还是考虑考虑要做出什么」
- 「这里的招募工作还分扩充政府家户或是允许外来家户承担行政任务，所以最好是有问题只给gm/决策人一个警报，把主动自定义工具给写全」
- run6 后用户已选（原文）：**D1 辖区互斥（中央座位不入省辖）**、**D3 税基保持现状**、**下一批优先税制/财政闭环**。

（spec §23 的原文口径：D1 实施方式——独立 central 区域 vs 辖区逐 hex 排除——由 Z7 排查/设计定；**不采用**“政府家户一律免税”。）

### 9.2 run6 关键数字（含本报告更正）

| 项 | 值 | 出处 |
|---|---|---|
| tick0 中央/省计划·供给·效率 | 中央 (16000,16000)/(16000,16000)/1000‰；省 (32000,32000)/(16000,16000)/250‰ | run6 报告 §1；`dumps/seg-030.json` |
| 两国库注资 | 各 粮 1,000,000 / 布 100,000 / 银 100,000 毫 | `GovWorldBootstrap.java:124-130`；run6 报告 §1 |
| F1 360 天省税收 | `grainCollected` 合计 2,040,450,189 毫；day360 实收 31,001,118（25%） | run6 报告 §3；`TAX_DAILY_END day=360` |
| 省俸禄/工资 | 360/360 足额（粮 166 + 工资 粮320/银32） | run6 报告 §3；`GOV_OFFICE_UPKEEP_EVALUATED`/`GOV_ADMIN_SALARY_DAY` |
| 中央工资（更正） | day1~49 粮320+银32；day50~173 粮0+银32；day174 银18；day175+ 全0 | `GOV_ADMIN_SALARY_DAY unit=gov-central`（360 条） |
| 中央行政俸禄（更正） | day1~49 粮166；day50+ 粮0（布一直 0） | `GOV_OFFICE_UPKEEP_EVALUATED unit=gov-central` |
| 税抽中央 | 173 天（day1~173）`jurisdiction_tax` 中央→省 | `HOUSEHOLD_STOCK_DEDUCTED` 计数 173 |
| 省税也抽 world-silver | 60 天（day1~29、121~149、241~242） | 同上，`household=hh-gov-world-silver` |
| D2 告警 | `GOV_BUDGET_ALERT` 475 条（中央 358、省 117），全部 `ADMIN_BUDGET_SHORTFALL`；day3 起中央有警报，但 `treasuryLimitedValue=0`，day175 后 value 才见底 | `GOV_BUDGET_ALERT`；`seg-030/060/180.json` |
| day50 中央粮腿归零 | day49 plan 粮 271,393 → day50 plan 粮 0；day50 是市场日（`MARKET_REPORT fills=99`，`byReasonGoods={LOAN_PRINCIPAL=0, MARKET_TRADE=2,701,784}`）；tick60 账户粮 `{}`、冻结 0 | `service.log:39482/40138/40260/40263`；`revision=7` actor accounts |
| D4 day122 | 首都卫队 `EXECUTED paidGoods={} paidMoney={silver=30} shortfallGoods={}`；同天镇戍部队 `paidGoods={grain=180} paidMoney={silver=18}`；中央 plan authorized `{0,30,32}` | `PERIODIC_ADJUSTMENT_RULE`/`GOV_BUDGET_PLAN day=122` |
| 军俸总规则 | 539 条全部 `EXECUTED`；run6 `no-payable-leg`=0；run4 同模式 6 条 SKIPPED | `PERIODIC_ADJUSTMENT_RULE`；run4/run5 service.log |
| 债务/人口 | day360 债务 126,971,967 毫、人口 5,078 | run6 报告 §2 |
| (0,0) 税基占比（day30） | 粮 14,045,324 / 80,239,848 ≈ 17.5%；银 67,299 / 269,200 ≈ 25.0%；该格 902 人 / 13 个 economy 家户行 | `dumps/seg-030.json` |

### 9.3 证据路径

- run6：`/home/cna/simos-runs/2026-10-23-sw19-run6/{service.log,seg.log,dumps/seg-*.json,setup_tick0.py,store}`；store revision=7（tick60）actor accounts 可读。
- run4/run5 对照：`/home/cna/simos-runs/2026-10-23-sw19-run{4,5}/{service.log,dumps}`。
- 代码：本报告各节 `file:line`。
