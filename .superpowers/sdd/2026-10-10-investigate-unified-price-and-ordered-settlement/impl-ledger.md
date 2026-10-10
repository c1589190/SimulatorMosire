# 调查账本：区内统一价表 + 有序结算（只读调查，2026-10-10）

> 只读交付：**未改任何生产文件、未跑 Maven、未起世界**；结论全部回工作树 HEAD `232d9bdc` 核过（AGENTS §四）。
> 本文件「交账」= 19 条（★ 上限 30 行）；其后是**证据索引附录**与**用户原话附录**，不计入交账。

## 交账

1. **价表形状 = 逐 hex**：`record Market(CurrencyId numeraire, Map<CommodityId,Long> prices)` `model/Market.java:46`；`EconomyData.markets` 键 = `HexCoord` `EconomyData.java:309`（写口 `:2706`；GM `spi/EconomySetMarketPriceHandler.java:108-114` 逐格 upsert）；区的形状 = `anchor + hexes + legalTender + officialRates` `api/market/MarketZone.java:74-80`，`MarketRegion(node, members)` `api/market/MarketRegion.java:26`；成员格唯一权威 = `MarketZone.hexes`，`EconomyData.java:1911-1919` 判"一格至多一区"。
2. **区内成交价其实已经是"锚格一价"**：区内撮合取 `anchorMarket.priceOf` `MarketSettlement.java:4025/4034`（串行）、`:4192/4201`（RegionClone）；跨区取**卖方格**价（同格多卖方取最大）`:7588-7596`（调用点 `:5259-5271`）；限价 bid/ask 取**本格**价 `:2160-2162`（参与者→市场 `:3376-3385`）；种子共享同一张表 `EconomySeeder.java:905/914-916` ⇒ 逐格差价只可能来自 GM 命令。
3. **⇒ 结论/要动什么（Q1）**：语义上"区内一价"已成立、形状仍是逐格；`adaptPrices` 每轮把锚价写进成员格 `:2048-2059`，**但 `next == reference` 时 `:2045-2047` 直接 continue ⇒ 成员格漂移不会被修**；成员格缺该商品定价行则该格不交易（`:2050-2052` 跳过 + `:2147-2149` 不挂单）。最小改法 = 写回前加一次"区内对齐 pass"（含 `next==reference` 分支）；换形状则动：`markets` 键改区、**31 文件 / 95 处**读点、`change/EconomyChangeSet.java:145/342/387` + Codec + 往返不变式、`SetMarketPrice`/`SetMarketNumeraire` 语义、`Market.numeraire`（逐格）与 `MarketZone.legalTender`（逐区）的权威（现在只 DEBUG 报漂 `MarketTopologyBook.java:231-233`）。
4. **Q1 副作用**：**不会消灭区内套利** —— 套利口径本就是"本户保留价 vs 市价"，类注明文"单市场区内逐格价表恒同价 ⇒ 不存在买低格卖高格" `TradeArbitrageActivity.java:15-24`；lane/运力/口岸闸走区与运力池（区内跨格合成路线 `:5506-5538`、跨区配对 `:4777+`、闸预算 `:4784/4813-4822`），与价表键无关；与 #6"运费仍按距离"一致（`:5506-5538` 即"即时结算 + 按距离运费"）。
5. **Q2 家户侧"发达程度"读数**：持久量有 `FlowRow`（本周期 income/consumed/netSurplus/unmetNeed）`model/FlowRow.java:87-100`、`OperatorCondition`（cashReserve / debtPrincipal / cycleRevenueByCurrency / cycleFilledQty）`:48-72`、`OwnershipStake`（资产份额，按 `AssetKind`）、`DebtStock.principalByDebtor`（**跨单位直加、不折共同价值**）`:37`、`DebtCapacity`（毫粮口径 `model/DebtCapacity.java:98`，`existingDebt/unpricedDebt`）；纯派生有 `ExpectedProfitBook.assetScaleOf`（private、逐产业）`:756-779`、`EnterpriseProfitBook.EnterpriseProfit`（逐组织/关账周期、进程内累加器）`:86-100`。
6. **Q2 区侧"总 GDP" ⇒ 现在没有可用读数**：`MarketReadout.RegionReadout:600-624` 无金额/成交额字段；成交额只能从**进程内** `MarketReport` 的 Fill（`from/to` + `quantity` + `unitPriceMilli`，`MarketReport.java:609-625`）现算，而类注明写"不落盘、重启即失" `MarketReadout.java:46-48`。⇒ 要"区内按发达程度、区间按总 GDP"排序，必须（a）用持久量现算（区内加总 FlowRow / OperatorCondition / OwnershipStake；注意 P-T4 **禁跨币相加**）并定 tie-break，或（b）落新状态组件（**铁律 5**：组件 + ChangeSet + 往返不变式测试）。现在没有任何"区级总额"权威。
7. **Q3 阶层流动机制**：`ModeMigrationPolicy.plan` `:273`（逐源户 `:448-599`）用 `ExpectedProfitBook.prospect` 的**预期**单位劳动净收益差当权重（`:522-527`）；"速度"概念**存在但只有两档常量**：基线 `MIGRATION_PER_MILLE = 10‰/周期` `:82`、A 规则 `A_RULE_TRANSFER_SPEED_PER_MILLE = 1000‰` `:85`，用法 `moveable = min(人口, max(1, 人口×速度÷1000))` `:577-588`（A 规则 `:531-545`）；真实利润账 `EnterpriseProfitBook.Book` 只被 null 检查、**不参与决策**（D-024，`:285/299`，注释 `:462-463`）。
8. **Q3 触发时机与日序**：只在**关账日**执行（`anyCycleClosed` 置真 `EconomySettlement.java:2054`），位置在 5c 阶层写回 `:3238`、流水 `:3326` **之后**、DAY_END 日志 `:3573` 之前：`:3433` 录日 → `:3454-3470` 计划 → `:3535` 执行 → `:3566` 重置。参照日序：0-entry.4 `:1585`、现扣周期投入 `:1739`、区域市场清算 `:2191`、借粮 `:2788`、偿还 `:2865`、饿死 `:2933`、计息 `:3037`、经营者状态机 `:3074`、清算/阶层下滑 `:3175`、阶层写回 `:3238`。
9. **⇒ Q3 要动什么**：① 频率从"关账日一次"改成"每 tick 最末"（用户的 #1）—— 必然改数值行为；② 新增"按债务/资产规模估速度"公式（逐户，落点 `:3174` 之后 / `:3566` 处）；③ 债务规模口径要选一个（`DebtStock.principalByDebtor` 混单位 / `DebtCapacityBook.capacitiesForState` 毫粮、现只在借粮一步用 `:2783-2786`），**不许造第二本债账**；④ 人口/劳动真值在 Social ⇒ 落地走 app 的 `MigrationSocialBridge` outbox（AGENTS 记载，本轮**未核**）。
10. **Q4 铸币现状**：`GovernmentSeigniorage.settleCycleStart` `:74-147` 在**周期首日**把 `seignioragePerCycle` 直接加进国库**家户账户**（`:101-106`，**不产生 Transfer、不走 applyTransfer**），只写 `MoneyIssuanceRecord(FISCAL_ISSUE)` 审计 `:108-121`（幂等 id `:156+`）；入口 `EconomySettlement.java:1295-1297`（周期首日、任何转移/市场之前）；触发判据 `isCycleStart` `:50-63`。`debtIssuePerCycle` = `GovernmentDebtIssuance.issueCycleStart` `:51-120`（向家户真借、真转移、写 `DebtContract`；借不满记 shortfall）。
11. **Q4 政府的钱从哪来 / 怎么花**：国库户 = `government.treasury()` 解析出的家户账户（`GovernmentSeigniorage:149-153`）；来源 = 铸币（上条）+ 发债（上条）+ 税/上缴；**花钱只能**靠政府授权挂单：`EconomyData.govMarketMandates` `:359/1988-2021` → `ordersFor` 国库分支 `MarketSettlement.java:2175-2198`、`planGovMandateOrders:2454+` → 正常撮合 `executeTrade` ⇒ 用户 #3"投入市场必须走市场结算"**已成立、无旁路**（国库户不生成任何自动订单，`:2175-2198`）。
12. **⇒ Q4 要动什么**：需要"上一轮印了多少"读数 —— 现在只有**全期累计** `MoneyStock.cumulativeIssuance` `time/MoneyStock.java:48-51`（且含创世 `INITIAL_ENDOWMENT`）；分周期量可由 `moneyIssuances` 的 `day/period` 过滤现算（`api/money/MoneyIssuanceRecord.java:32-40`），**但没有这个读口**。★ 冲突：2026-10-08 用户已取消"累计发行量 × 500‰ 的 FX 储备上限"（`FxRoundInput.java:85-99`）⇒ 新上限必须写明管的是"新投入"而非国库总余额（铸币/税/债同账 ⇒ 不按周期记账就不可分辨）。
13. **Q5 自适应 D/S 口径**：`adaptPrices` `:1989-2064`；demand = Σ_buy `min(挂单量, 按参考价付得起的量)`（付得起在 `:2009-2013`，`payableMoneyOf` `:7777-7781`）再减**运力截断** `:2017-2018`；supply = Σ_sell `sellable − 运力截断` `:2024-2025`；口径注 `:1977-1987`；公式 `adaptiveNextPrice` `:2076-2115`（α=50‰ `:221`，开关 `:214`，0 价特例 `:2082-2084`）；V-20 的剔除**只在运力三处**（`:5446`、`:7059`、`:7118-7132`）。
14. **Q5 ⇒ 统一价 + 排队下要调的两支**：① 口岸闸拦下的量**不进** `capacityTruncated`（`:5409-5423` 只写 `blocked`/`portBlocked`）⇒ 制度性拦下**会**推价；② "有需要但完全买不起"不进 D（`:2009-2013` 折回付得起的量；`needsButCannotAffordMilli` 只作读数 `MarketReadout.java:635`）⇒ 现状"越穷越不推价"。⇒ 要"短缺推涨价"就必须先裁定：未满足的合格需求是否全进 D、买不起那支是否进 D（现在是队列按成本档 + `ProportionalSplit` 比例配给 `:4594-4662`）。
15. **Q6 逐商品预估价值**：唯一拼写点 `HouseholdValuationBook.reservationMicro` `:324-356`（= 市价 × 压力(30 天目标缺口/饱和，`NECESSITY_PREMIUM 500‰ / SATURATION_DISCOUNT 400‰`)，微 numeraire / 商品单位 `:99`；`implements DebtValuation.HouseholdPriceTable` `:47`，接口 `DebtValuation.java:79-86`，`applyPressure` `:373-383`）；**逐 tick 派生、不入状态**，唯一生产派生点 `LaborQueueSettlement.java:203-205`（另 `MarketSettlement` 卖方保留价槽位字段 `:9389` 同源，用法 `:5736/5804`）。`life` / `necessary` / `retention` 是市场侧量（`planFor` `:8737-8761`、用法 `:2199-2227`、生活保留 `:8457+`），**不在**估价簿里；`HouseholdPurchasingPower` 是"**钱**的价格"（逐币×区，`MarketPayChoice:125-175`、`FxSettlement:328-398`），不是商品估价。
16. **Q6 ⇒ 实现"按预估价值最低优先偿还"**：偿还现状 = 介质序"货币余额降序 → 商品余额降序"（**钱永远第一位、商品按数量不按价值**）`EconomySettlement.java:7363-7385`，债序按共同价值升序 `:7257`，家户价目表参数**传 null** `:2870/2886` ⇒ 走市场默认价。**接口与算式已在**：`DebtValuation.choosePayment/commonUnitValue` `:297-305/:325-406`、`Pricing`（家户表优先、计价币不同则不混用）`:511-529` ⇒ 改动 = 把定额簿传进去 + 介质序改成"本户 `commonUnitValue` 升序"（**不造第二套权威**）。★ 缺口：`commonUnitValue` 对**非本币 money 返回 0**（`:300-303`）⇒ 多币区里外币介质会判"缺价跳过"，需裁定；且 `HouseholdValuationBook` 的 numeraire 是**全世界取第一个格**（`:246/259-261`），跨区多币时不足以当共同尺。
17. **与六条裁定的冲突点（点名）**：① **#1 时机/频率**：现状关账日一次（`:3437`）≠"当轮/每 tick 最末" ⇒ 频率与口径都要改；② **#2"上一轮就上一轮"**：与"周期首日铸币"一致**仅当**"轮" = 产业周期（120 天），若"轮" = tick 则冲突；③ **#3**「铸币方法事实上没做」指的是 **gov 模块 F3**（`.superpowers/sdd/2026-10-23-fiscal-loop-design/z7e3-impl-ledger.md:114` "没做：F3 中央铸币/发债"），economy 侧 `GovernmentSeigniorage` 是已实现的另一条线 ⇒ 需澄清以哪条为"铸币"权威；④ **#4 排序**：现状区内 = 拓扑区序（`MarketZoneBook.zones` 按 zoneId 升序，`MarketZoneBook.java:65-73`）× 商品序 × 槽位插入序（`:3698`），写口 = `CommitOrder(stage,partition,canonicalKey,intraIndex)` `CommitOrder.java:24-50` ⇒ 改按发达程度/GDP 排序**会改稀缺货分配与冻结中间态**；⑤ **#5 与"钱永远第一位"直接冲突**（见第 16 条）；⑥ **#6 形状 vs 语义**：语义已区内一价，形状仍逐格（第 3 条）；且 `Market.numeraire`（逐格）vs `MarketZone.legalTender`（逐区）现在只报漂 ⇒ 统一价表必须一并定 numeraire 权威。
18. **最大风险 / 最难的一处**：把 **GDP/发达程度放进结算顺序 = 顺序依赖本轮结果（自指）** —— 若 GDP 含当日成交则"先结算者 GDP 更高" ⇒ 序不可复现，破 I7"同 revision 两跑逐值相同"（市场内已有同款明文 `:4866`）。只能取"上一轮/上周期"持久读数或开盘前快照；次难 = `markets` 形状变更（铁律 5 + 31 文件/95 处 + GM 命令语义 + Codec 往返）。
19. **没核到的**：① 未跑 Maven/任何测试 ⇒ 不知道会红哪些既有测试（只给受影响面）；② `markets` 95 处读点只抽核 4 处（MarketReadout / MarketTopologyBook / EconomySettlement / FxSettlement），未逐处判行为等价；③ `MigrationSocialBridge` 与 Social 侧写口细节未核（只据 AGENTS 记载）；④ 播种路径（`ThreePowersMarketZones` / `RegionSeedPlan` / `marketFor(CurrencyId)` 调用方）未逐条核"逐格价恒同值"，只核了 `EconomySeeder` 的共享实例；⑤ GUI/MCP 读口对"区级价表"的期望形状未核；⑥ `MarketZone` 多币区里各成员格 `Market.numeraire` 是否真的与 `legalTender` 一致，只有 DEBUG 漂移计数（`MarketTopologyBook.java:231-233`），未在真档上量过。

## 附录 A：用户原话附录（逐字照录，2026-10-10）

> 1、就按你说的做吧，我的想法是直接根据债务/资产规模估算向其他阶层流动的速度就行，这个速度的结算必须在当前经济操作搞完之后最后再结算，至于这个速度在什么时候算，其实也是在最后，当轮算出这个家户的债务规模后进行；
> 2、上一轮就上一轮！
> 3、政府自己获得资金，用于政府家户的购买等行为，完全可以放在最开始，但是政府印的钱投入市场，必须老老实实走市场结算——因为铸币方法事实上没做，理论上来说政府这轮能新投入多少取决于上一轮印了多少；
> 4、不是，我说的是单个家户有就买/借啊，家户之间的顺序还是得排序的啊；另外，同一市场区内的结算顺序也可以根据经济发达程度排序，乃至市场区之间的结算顺序也可以根据市场区的总GDP排序；
> 5、偿还顺序不是先还钱或者先还粮还工具之类的，而是只取决于这个家户的哪个种类商品，预估价值最低；
> 6、我想了一下，价格表还是市场区内统一吧，单个家户没必要维护一张独立的价格表，事实上市场行为发生的时候，除了运费根据距离不同而不同（实际上单位运费还是相同的），商品成交价格总是在当前差不多；

## 附录 B：证据索引（不属于交账，供实现方取用）

### B1 价表与区（Q1）

- 价表类型：`simos-economy/src/main/java/io/mosire/simos/economy/model/Market.java:46`（`numeraire` + `prices`，逐值 ≥ 0；**无该行 = 不交易 / 有行且 0 = 免费**，`:27-31/72-89`）；bid/ask 两个具名常量 `:104-131/138/145`。
- 状态组件：`EconomyData.java:187`（第 9 组件注）、`:309`（形参）、`:1288-1294`（构造期冻结）、`:2706`（`withMarkets`）；`marketZones` 第 38 组件前的区表 `:338`、`withMarketZones:3875`、跨表守卫 `:1896-1955`。
- 区的成员格权威：`api/market/MarketZone.java:74-80`（`hexes` 唯一权威、`radiusHex` 不派生成员，`:44-47`）；`EconomyData.java:1911-1919`（一格至多一区）。
- 拓扑装配：`simos-app/.../time/MarketTopologyBook.java:215-264`（持久区路径；`nodeId = zoneId.value()`、`numeraire = legalTender`）；区序 = `MarketZoneBook.zones` 按 `zoneId` 升序 `MarketZoneBook.java:56-73`；`MarketTopology.regions()` `:588-591` 保序。
- 改价三处写口（全仓 `new Market` 仅 5 处）：自适应 `MarketSettlement.java:2027-2063`、GM `EconomySetMarketPriceHandler.java:108-114`、GM 计价币 `EconomySetMarketNumeraireHandler.java:104`、种子 `EconomySeeder.java:905/914-916`、payload `EconomyPayloads.java:636`。
- 成交价与运费：区内 = 锚格价 `:4025/4034`、`:4192/4201`；跨区 = 卖方格价 `:7588-7596`/`:5259-5271`；区内跨格合成路线（`immediate = true`）`:5506-5538`，调用点 `:5660`；单 hex 物流成本（实物损耗、`costMilliPerUnit ≡ 0`）`time/HexTradeCost.java:39-88`。
- 套利口径：`TradeArbitrageActivity.java:13-68`（保留价 vs 市价；四硬边界；HOLD 不评估的证明）。
- 变更集/往返：`change/EconomyChangeSet.java:145/342/387`（`markets` 是 `FieldDelta<Market>`，键 `HexCoord::parse`）。

### B2 发达程度 / GDP 读数（Q2）

- 家户持久量：`model/FlowRow.java:87-100`；`model/OperatorCondition.java:48-72`（`cycleRevenueByCurrency:68`、`cycleFilledQty:66`、`debtPrincipalMilli:53`）；`model/OwnershipStake.java`；`time/DebtStock.java:37`（逐债务人本金，**跨单位直加**）；`model/DebtCapacity.java:68-98`（`existingDebt/unpricedDebtAmount/headroom`，全为**毫粮**口径）。
- 纯派生：`time/ExpectedProfitBook.java:756-779`（`assetScaleOf`，private，按产业 `capacityPerUnit` 折算）；`:124-143`（`Prospect`：`feasibleScale/plannedOutput/revenue/inputCost/laborCost/rent/net/netPerLaborScaled`）；`time/EnterpriseProfitBook.java:86-100`（逐组织真实利润）。
- 区级：`time/MarketReadout.java:600-624`（`RegionReadout` 无金额字段）、`:638-650`（`CommodityReadout`）、`:689+`（`CommodityMatchReadout`，来自进程内报告）、`:46-48`（"不落盘、重启即失"）；`MarketReport.java:609-625`（Fill：`from/to/quantity/unitPriceMilli/...` ⇒ 成交额可现算）。
- 债务容量算法：`time/DebtCapacityBook.java:109-118`（`marketPriceLookup`）、`:143-235`（`capacitiesForState`）、`:259`（`capacities`）。

### B3 迁移（Q3）

- 计划器：`ModeMigrationPolicy.java:46-76`（类注算式与硬件约束）、`:82/85`（速度常量）、`:94-112`（`MigrationPlan`）、`:132-149`（`MigrationMove`，含 `transferSpeedPerMille` 与三个 `REASON_*`）、`:273-288`（`plan` 形参）、`:320-322`（无 `modes` ⇒ no-op）、`:448-527`（`planForSource` + 预期利润读数）、`:529-596`（A 规则 / 权重路径 + 速度用法）、`:756-779`（`assetScaleOf`）、`:925-1000`（`allocate`）。
- 执行器：`ModeMigrationSettlement.java:60-92`（类注：只执行计划、源户 mode 不改）、`:100-160`（`apply` 两个重载 + 逐源户入口）、`:785+`（`createNewHousehold`）、`:1240+`（`moveDebt`）、`:1440+`（`migrateAssetsForMove`）、`:1547-1553`（可移动资产判据）、`:1098-1130`（GOV_SERVICE 承诺 ⇒ 具名 ERROR + fail-closed）。
- 接线：`EconomySettlement.java:3430-3437`（闸门 `profitCycle != null && anyCycleClosed && !modes.isEmpty()`）、`:3444-3452`（⑦真实利润汇总）、`:3454-3470`（⑧计划）、`:3473-3535`（日志 + ⑨执行）、`:3566`（`resetForNextCycle`）；`anyCycleClosed = true` 在 `:2054`。
- 其它迁移线（本轮未接线）：`model/MigrationPolicy.java:26-70`（城市化拉力，类注明写"不被任何结算读"）、`time/LotMigrationBook.java`；阶层位置线：`EconomyModeTransitionSettlement`（`:1585` 调用）、`time/ClassTransition.java`、`EconomyLiquidationSettlement.java:94-137`（清算阈值 + 阶层下滑动作词）。

### B4 铸币/发债/政府花钱（Q4）

- 铸币：`GovernmentSeigniorage.java:50-63`（周期开始日判据）、`:74-147`（写账户 + 审计 + INFO）、`:149-153`（国库 = 政府家户账户）、`:156+`（确定性 id）；**不产生 Transfer 的理由** `:33-35`。
- 发债：`GovernmentDebtIssuance.java:26-42`（为什么不复用市场信用）、`:51-120`（出借人筛选/上限/短缺口日志）。
- 模型：`model/Government.java:33-34`（两个旋钮字段）、`:94-99`（非负守卫）、`:41-42/67-79`（构造重载）。
- 命令：`spi/EconomyRegisterGovernmentHandler.java:53/320-323`。
- 审计表：`EconomyData.java:114/219/323`（第 24 组件 `moneyIssuances`）、`:1776-1798`（键 = 值内 id）、`:3416-3426`（当日审计写回状态）；`api/money/MoneyIssuanceRecord.java:32-40`（含 `day/period`）、`api/money/MoneyIssuanceKind.java:14-17`（`INITIAL_ENDOWMENT/FISCAL_ISSUE/WITHDRAWAL`）；回笼写口 `time/TreasuryWithdrawal.java:18-118`。
- 读口：`time/MoneyStock.java:34-70`（流通量/累计发行/累计回笼/差额）；`time/FxRoundInput.java:85-99`（**旧"累计发行量 × 500‰"上限已删**的留痕）、`:144-172`（区表路径）。
- 政府花钱：`EconomyData.java:359/1988-2021`（`govMarketMandates` 组件 + 守卫）、`time/GovernmentMarketMandatePlan.java:42-168`、`MarketSettlement.java:2175-2198`（国库户只按授权下单）、`:2454+`（`planGovMandateOrders`）、`:2775-2778`（授权生命周期，无条件调用）；GM 窄工具 `simos-app/.../tools/write/GmGovMarketMandateTool.java:27-49`。
- gov 模块侧（另一条线）：`.superpowers/sdd/2026-10-23-fiscal-loop-design/z7e3-impl-ledger.md:114`（F3 中央铸币/发债**没做**）、`z7c-impl-ledger.md:22/109/201`（上缴只走 `HouseholdStockDeduction.transfer`，不自动注资/借款/铸币/调率）。

### B5 自适应 D/S（Q5）

- 调用点：`MarketSettlement.java:1783-1786`（撮合之后无条件调用）、`:1787-1823`（`AdaptivePrices` 随 `MarketOutcome` 交出）。
- 汇总：`:1995-2019`（逐区逐商品需求；`:1997-2001` 信用世界折回付得起的量的红字修复）、`:2020-2026`（供给）、`:2029-2063`（写回锚 + 成员 + `PriceUpdate` 读数）、`:2045-2047`（`next == reference ⇒ continue`）。
- 公式：`:2066-2115`；常量：`:214`（开关 true）、`:221`（α = 50‰）、`:227`（floor = 0）、`:233`（ε = 1）。
- 截断记账：`SellSlot.capacityTruncatedMilli` 注 `:9428-9435`、`BuySlot.capacityTruncatedMilli` 注 `:9259-9263`；三处写点 `:5446`（路线窗口用尽）、`:7059`（承运分配不足）、`:7118-7132`（发货格无运力）；口岸闸归因 `:5409-5423`（**不写截断**）。
- 撮合形态（"排队而非竞价"的现状）：`:4594-4662`（成本档 + `ProportionalSplit.byDenominator`）、`:3698`（槽位插入序决定配给下标序）、`:91`（类注 ⑤ 按剩余需求/供给比例配给）。

### B6 家户估价与偿还（Q6）

- `HouseholdValuationBook.java:47`（`implements DebtValuation.HouseholdPriceTable`）、`:53-99`（`HOLD_DAYS = 30`、`NECESSITY_PREMIUM_PER_MILLE = 500`、`SATURATION_DISCOUNT_PER_MILLE = 400`、`HISTORY_WEIGHT_PER_MILLE = 300`、`MICRO_PER_MILLI = 1000`、`NO_TRADE_HISTORY`）、`:154-191`（毫/微两个读口 + 钱的价格）、`:193-280`（逐 tick 派生；`:246/259-261` numeraire 取首个有市场的户）、`:282-307`（逐户钱的价格，键集有界）、`:309-356`（保留价算式唯一拼写点）、`:358-393`（`perMilleOf/applyPressure/blend`）。
- 市场侧量：`MarketSettlement.planFor` `:8737-8761`（necessary/life/demandParts）、`:2199-2227`（订单量算式）、`:8457+`（`lifeReserveOfParticipant`）、`HouseholdEconomy.expectedNeedMilli`（AGENTS 记载：Social 物化视图）。
- 偿还：`EconomySettlement.java:7181-7221`（口径全表）、`:7222-7360`（逐户逐债）、`:7257`（债序）、`:7266`（介质序）、`:7363-7385`（介质序唯一拼写点）、`:2870-2888`（调用点，`householdPrices = null`）；`time/DebtValuation.java:79-86`（接口）、`:274-305`（共同价值 + `commonUnitValue`，money 仅认本币）、`:325-406`（`choosePayment`）、`:511-529`（`Pricing` 家户表优先/不混币）、`:460-490`（旧兼容介质序）。
- 资金价格：`time/HouseholdPurchasingPower.java:76-131/153-230`；调用方 `MarketPayChoice.java:125-175`、`FxSettlement.java:328-398/528`。
- 资源快照：`time/HouseholdResourceSnapshot.java:32-68`（`goodsOf/moneyOf/pricedCommodities`）。
