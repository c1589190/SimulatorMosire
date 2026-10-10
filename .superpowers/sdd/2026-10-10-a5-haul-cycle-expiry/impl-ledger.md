# A5 实现账本 —— 服务按周期作废（不可储存转卖）+ 日志/收益可见性收口

> 角色：**写码 Agent**（责任区 A5）。日期 2026-10-10。
> 判据来源：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` **v1.4** §3.1/§5/§6 T-H3、A4 真实 world 账本
> `.superpowers/sdd/2026-10-10-a4-real-world-verify/impl-ledger.md` **§5 新发现缺陷 1/2/3/4**。
> 纪律：只改生产代码、只到编译过（`spotless:apply` + `-DskipTests compile -am`）；未跑 `test`/`verify`/world；未 `git commit`；未碰 `src/test/**`、`pom.xml`、`docs/**`、`.superpowers/**`（除本账本）。
> HEAD：`54f93280`（A1 `a15fb3ec` → A2 `4194e90a` → A3 `cb053f1a` → 测试 `ae5698dc`+`1e35855b` → F-1/F-3 `54f93280`）。

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 证据（回代码核，§四） | 结论 | 对 A5 的影响 |
|---|---|---|---|
| S-1 | 唯一消耗点 `MarketSettlement:7147`（A2 时）= `EconomySettlement.consumeForLoss(...SERVICE_CONSUMED_ACCOUNT...)`，调用者 `deliverHaulService`（现 `:7245` 区） | 服务只在**成交那一刻**被消耗，没有轮末/周期末清扫 | 必须新增"周期边界作废"这一写点 |
| S-2 | `MerchantCapacityPool.assemble`（现 `:446-452`）供给 = `max(0, 现货 − 冻结)`，**无周期过滤**；池按家户 home hex 归组（`row.view().hex()`） | 上一周期剩的服务下一周期照样卖 | 作废后池**天然**只见本周期产出，无需给池加第二处判据 |
| S-3 | 周期关账判据在 `EconomySettlement:1994`（`progressed >= industry.cycleDays()`）；收获写入在其后 `harvestPartitioned`（`：2037` 区，内部 `accounts.snapshot()` 再 `commit(intents)`） | 关账日 = **先判定关账、后写产出** ⇒ 作废必须插在两者之间 | `expireUnservedHaulService` 的调用点 = `:2040`（`enterpriseByProcess` 之后、`harvestPartitioned` 之前） |
| S-4 | 产出入账主体 = `HouseholdRouting.subjectOf(unit, rows, enterpriseByProcess, index).all()`（`creditOutput` `:7712` 用 `apportion(subject, net, weights)`） | "谁产的服务"有**唯一拼写点**；`trade@hex` 的 relation 是最小自留 ⇒ 产出全留在经营者家户 | 作废作用域直接复用 `subjectOf(...).all()`，与入账同一批判据 |
| S-5 | `EconomySeeder:4320 trade(...)`：`outputPerUnit={haul: HAUL_PER_TRADE_UNIT_CYCLE}`、operator = 该格 merchant principal 家户 actor（`:3422-3447`） | 播种器每格一个 `trade@hex` / 一个 unit；持有服务货的户 = 该 unit 主体 | "关账 unit → 主体家户"能精确命中全部持货人（A4 实测的非零格 0_0/0_2 即两格经营者） |
| S-6 | 旧 `HAUL_SERVICE_SETTLED` 条件 `ctx.haulServiceTrades > 0`（A2 原文，已撤） | 有交付但零钱腿（全天免运费/自承运）的日子整行不刷 | 交付量与钱腿数**拆成两个量**（`deliveredMilli` / `trades`），对账行改按"服务成市"刷 |
| S-7 | `OperatorSettlement.accumulateMarketEvidence`（`:94`）只从 `report.sellerOutcomes()` / `report.fills()` 收证据；`belongsTo`（`:600` 区）要求 `industry.outputPerUnit().containsKey(outcome.commodity())` | 服务不进订单簿 ⇒ 结构上**永远**没有服务卖方槽/成交 ⇒ `trade@hex` 的 `cycleRevenueByCurrency` 恒空、`lastCycleNet` 只剩成本 | T-H3 必须另开一条**按收款家户**的归集来源 |
| S-8 | `MarketReport` 已有"record 之外的派生只读件"成例（`REGULATED_TARIFF_BY_REPORT` / `TAX_ITEMS_BY_REPORT`，identity 键 WeakHashMap）；`MarketOutcome`（`:1145`）**只在 main 内构造**（测试只用类型、不构造）；`accumulateMarketEvidence` 只有 1 个 main 调用点 | 读数可以走"交给付物"而不是改 record 形状 | 选了**显式第 4 组件**（不加静态表），零测试编译风险 |
| S-9 | `OperatorSettlement.selfUsableOf`（`:886`）只遍历 `industry.inputPerUnit()`（`trade` = `{tool:100}`）；`lastCycleRevenueByCurrency`/`lastCycleNetMilli` 的读者只有 `ApiViews:3132-3134`（GUI/tool hex 视图）+ `advance` 自身结转 | 作废服务货**不进**状态机判据；收益读数**只是读数** | "只影响 haul"与"缺省中性"可逐条论证；T-H3 的落点是**已公开标准读数面** |
| S-10 | `AccountSession.householdGoods()` 返回 `AccountView`（`:448/492-534`：`get` 现读账户、`put` 直写账户）⇒ 同一会话的多个视图是**同一张活表** | 作废可直接写活表，且必须早于收获的快照 | 用 `accounts.householdGoods()/householdFrozenGoods()`；写在 `harvestPartitioned` 之前 |

## 2. 自己的实现架构（改动文件 + 落点）

| 文件 | 落点 | 做了什么 |
|---|---|---|
| `simos-economy/.../time/HaulService.java` | `:73-92` `SERVICE_EXPIRED_ACCOUNT = "market-haul-service-expired"`；`:46-52` 类注补"不可储存"的另一半 | 作废的**具名损耗账户**（与成交消耗 `market-haul-service` 分开） |
| `simos-economy/.../time/EconomySettlement.java` | `:2022` 关账时收集"产出 haul 的 unit"；`:2040-2049` 调 `expireUnservedHaulService`（收获**之前**）；`:660-793` 新方法本体；`:2110` 捕获当日净产；`:2219` 服务成市格集只算一次；`:2413-2450` 当日对账 INFO | ★ 周期作废 + 三量对账日志 |
| `simos-economy/.../time/MarketSettlement.java` | `:1191-1236` 新 `HaulServiceRound`；`:1145-1183` `MarketOutcome` 第 4 组件；`:1793-1800` 装配；`:1690-1700` 撤旧汇总行（留痕注）；`:9662-9690` ctx 字段注（撤 `haulServicePaidByCurrency`，`:6770` 停记第二份金额） | 交付量/钱腿数/故障/逐户实收**随交出物离开市场轮** |
| `simos-economy/.../time/MerchantCapacityPool.java` | `:759-793` `freightEarnedByHousehold()` | 逐户服务实收（家户 id 升序 → 币种规范串升序） |
| `simos-economy/.../time/OperatorSettlement.java` | `:94-113` 签名 +1 参（`HaulServiceRound`）；`:166-176` 接线；`:239-301` `accumulateHaulServiceRevenue` | ★ T-H3：服务收入归入跑商 unit 的周期收入 |
| `simos-app/.../world/EconomySeeder.java` | `:4312-4322` | 陈旧注释（"haul 无牌价"）更正为 A2 现状 |

**作废口径（一行）**：产出运输服务的 unit **关账那天**（`progressed >= cycleDays`）、在**本周期产出入账之前**，把该 unit
**账户主体家户**（`subjectOf(...).all()`，与净产入账同一判据）手上 `max(0, haul 现货 − haul 冻结)` 的服务货，经既有非换手
损耗唯一写口 `consumeForLoss` 记进 `HaulService.SERVICE_EXPIRED_ACCOUNT`（账户减 + `ledger.addLoss` 同址 ⇒ Σ余额 + losses 守恒，I-H2）。

**为什么"只影响 haul"**：读写的商品键恒为 `HaulService.HAUL_COMMODITY`（无跨商品分支）；作用域是"关账跑商 unit 的主体家户"
（别的家户、别的商品一个字节不动）；无跑商 unit 的世界 ⇒ 收集表为空 ⇒ 方法首行返回、一次表查询都不做（I-H3 缺省中性）。

## 3. 关键判断（为什么这样拆 / 否决了什么）

1. **作废放在"关账判定之后、收获入账之前"**（S-3/S-10）：放在收获之后会把刚产出的新货当陈货一起作废（方向反了）；放在
   关账判定之前则拿不到"本日哪些跑商 unit 关账"。★ 否决的替代：给运力池加"本周期产出"过滤 —— 那需要**逐户逐周期的产出
   追踪**（= 新持久状态组件，铁律 5 ⇒ 停手上报），且会让"谁能卖"出现第二处判据。
2. **作用域 = unit 主体家户**（S-4/S-5）：与"谁拿到净产"同一批判据，因而**恰好**覆盖持货人；否决"整格所有家户"（会误伤
   同格非生产者）与"全世界服务货"（错峰周期下会误伤别的格的当期存货）。
3. **对账行搬到 tick 面**（S-6）：产出/作废的最全来源都在生产阶段，市场轮看不到；交付量改为随 `MarketOutcome` 交出。
   ★ 否决的替代：给 `MarketRound` 加字段 —— 测试夹具直接 `new MarketSettlement.MarketRound(...)`（`MarketSettlementFixtures:572`），
   动 record 组件会破坏既有测试**编译**（本批禁止改测试）。
4. **读数走"交出物第 4 组件"而不是 `MarketReport` 静态派生表**（S-8）：`MarketReport` 有 WeakHashMap 成例，但那条路依赖 GC
   时序且更难读；`MarketOutcome` 只在 main 构造 ⇒ 加组件**零测试编译风险**、语义显式。
5. **T-H3 落在 `OperatorCondition`**（S-7/S-9）：把服务实收按收款家户归入"它名下产出 haul 的 unit"，于是
   `industries[].condition.lastCycleRevenueByCurrency` 不再是 `{}`、`lastCycleNetMilli` 不再只剩 −104 成本；可见面 =
   **已有**的标准读数栏：hex 视图（`ApiViews.economyHex:1306` → `operatorConditionView:3121-3140`）与 ownership 视图
   （`:3616`），即 A4 所称"唯一可见的逐产业收入栏"。它是与 `EnterpriseProfitBook` **同一条钱腿**的另一读法
   （不构成第二本账，I-H5）。★ 未做（如需再报）：`EnterpriseProfitBook` 本身仍无读出口 —— 见 §6-3。

## 4. 偏离记录（主动记录，不隐瞒）

| # | 偏离 | 原因 |
|---|---|---|
| D-A5-1 | 对账 INFO 从市场轮（`MarketSettlement 4a0d`）**搬到 tick 面**（`EconomySettlement`），事件名 `HAUL_SERVICE_SETTLED` 不变 | 见 §3-3；市场轮拿不到产出/作废两量 |
| D-A5-2 | 该行字段 `serviceMilli` → **`deliveredMilli`**，并新增 `producedMilli`/`expiredMilli`；行内 `trades` 语义不变（钱腿条数） | A4 §5-2 的根因就是两量混一栏；改名让"交付量 ≠ 成交数"一眼可辨 |
| D-A5-3 | 撤 `ctx.haulServicePaidByCurrency`（第二份金额），逐币总额改为从逐户实收求和（`EconomySettlement:802`） | 同一批金额不再有两处拼写（S-2 同族纪律） |
| D-A5-4 | `MarketOutcome` 加第 4 组件、`OperatorSettlement.accumulateMarketEvidence` 加第 5 参 | 都是 main 内独占构造/调用（S-8），不动任何测试可见签名 |
| D-A5-5 | 服务收入**只归一个 unit**（多候选时取 unit id 规范序最小者 + 具名 DEBUG） | 归多个 = 同一笔钱双记（I-H5）；真实世界每格一个 trade unit，多候选结构上不可达 |
| D-A5-6 | `HAUL_SERVICE_EXPIRED`（事件行）与 `HAUL_SERVICE_SETTLED`（当日对账行）**同日都会打**（前者 total>0 才打） | §一.9 要求新状态写口有自己的 INFO；对账求和**只应用 SETTLED 行的 `expiredMilli`**（两行同源同值，别相加） |

## 5. 会改变数值行为的清单（含缺省中性论证）

| # | 改动 | 是否改数值 | 说明 |
|---|---|---|---|
| C-1 | **周期边界作废未卖出的服务货**（新状态写点） | ★ **改**（仅"产出 haul 的 `trade@hex` unit"存在时，且仅 `haul` 一个商品键） | 服务存量下降 ⇒ 可用运力下降 ⇒ 跨格成交/运费腿随之变（这正是"不可储存转卖"要的后果）。**缺省中性**：没建 `trade@hex`（或产出表不含 haul）⇒ 收集表为空 ⇒ 方法首行返回、一账不动。★ **口径选择（如实记）**：作废的开关是**配方产出**而不是"本格有没有牌价" —— 于是"产了 haul 但没牌价"的世界里，陈货也会按期作废（与改前的差别**只**落在 `haul` 这个商品的存量上：无牌价 ⇒ 池不用它、无人读它、无成交；S-9 已核）。理由：用户裁定的是**商品性质**（不可储存），不是"有没有市场"；一旦 GM 中途定价，旧周期的存货不得变成可卖。无牌价世界的日志面照旧（对账行不打，只有产出/作废事件行）。 |
| C-2 | 服务收入进 `OperatorCondition.cycleRevenueByCurrency` | **改状态读数**（不改任何判据） | 读者只有 `ApiViews:3132-3134` 与 `advance` 的结转（S-9）⇒ 状态 dump 会变，状态机/劳动/价格行为不变。缺省中性：无服务成交 ⇒ 空表 ⇒ 逐值不变 |
| C-3 | 撤 `ctx.haulServicePaidByCurrency` | **不改** | 只撤掉"同一事实的第二处拼写"；日志逐币总额改由逐户求和，数值同源同额 |
| C-4 | 撤 `MarketSettlement 4a0d` 日志行 | **不改**（日志面） | 事件名保留；`trades>0` 才刷 → "服务成市就刷"。★ 无牌价格的世界：一行不打（逐字不变） |
| C-5 | `HAUL_SERVICE_EXPIRED(_DETAIL)`、`HAUL_SERVICE_REVENUE_ATTRIBUTION` 新日志 | **不改**（日志面） | 只在真的发生作废 / 归属异常时打 |
| C-6 | `EconomySeeder` 注释更正 | **不改** | 纯文案 |
| C-7 | 新增常量/方法/形状（`SERVICE_EXPIRED_ACCOUNT` / `HaulServiceRound` / `freightEarnedByHousehold` 等） | **不改** | 无持久状态形状变化（`MarketOutcome`/`HaulServiceRound` 都是**瞬态**交出物，不进 Codec/ChangeSet/落盘） |

**没有新增/改持久状态组件**（未触铁律 5）：`EconomyData`/`SocialData`/`ActorData` 的组件与字段一字未动；`haul` 仍是既有
`Map<CommodityId,…>` 的一个键；新增的损耗账户 `market-haul-service-expired` 是 `ProductionLedger.losses` 的**键**（当日账，非状态）。

## 6. 可能的既有测试断言清单（只报不改）

1. **服务存量/运力相关**：任何断言"跨周期后 `goods.haul` 仍 > 0"或"下一周期仍能卖服务"的用例会失败（新行为 = 边界清零）——
   夹具入口 `MarketSettlementFixtures`（`carrierPool()` / 7 参 `MerchantCapacityPool.of`）不受影响（不经过 tick 面作废）。
2. **`HAUL_SERVICE_SETTLED` 日志**：grep `src/test/**` 对 `HAUL_SERVICE_*` = **0 命中**（本批实测）⇒ 无断言直接读日志。若测试
   代理后续按日志断言，"行数 = 服务成市的开市日数、字段 `deliveredMilli`/`producedMilli`/`expiredMilli`"是**新**契约。
3. **`OperatorCondition` 逐值断言**：任何对跑商 unit（`trade@*`）`cycleRevenueByCurrency`/`lastCycleRevenueByCurrency`/
   `lastCycleNetMilli` 的硬编码期望会变（原来恒 `{}`/净 −104，现在含服务收入）。`MarketCurrencyReadoutTest` 的夹具直接
   构造 `MarketReport`（16 参兼容构造器）/`OperatorCondition` ⇒ **仍编译**（未动那些签名），但若它走 `accumulateMarketEvidence`
   的等价路径，期望需按新口径更新。
4. **签名类**（会破坏测试**编译**的已全部避开，如实记）：`OperatorSettlement.accumulateMarketEvidence` +1 参、
   `MarketOutcome` +1 组件 —— 测试树里 `new MarketOutcome(` = **0**、`accumulateMarketEvidence` = **0**（实测），
   `MarketSettlement.MarketOutcome` 的 52 处全是**类型引用 + 访问器读取**（`outcome.report()/markets()/mandateFills()`）
   ⇒ 无编译影响、无断言影响。
5. **`SimosApp` 侧**：`EconomySeeder` 的常量/形状未动（只改注释），对播种断言无影响。

## 7. 未完成 / 未验证（如实）

- **未跑任何真实 world / test / verify**（本批纪律：只到编译过）⇒ C-1/C-2 的实际数值后果、日志行的真实条数与
  Σ净产 − Σ交付 − Σ作废 == 期末现货 这条对账式，**都还没有实测证据**；留给 A6/world 验证 + 测试代理。
- **`EnterpriseProfitBook` 读出口仍缺**（A4 §5-3(a) 的原始缺口）：钱腿结构上进那本账（A4 已回代码核），但工具面/GUI 没有
  enterprise/profit 键 ⇒ 本批以"等价标准读数（逐产业 condition）"满足 T-H3 的"可见"，未新增读取面。
- **已知边界（未造状态、如实记）**：① 若同一家户名下**多个** haul-producing unit 周期错峰，先关账的那个会作废该户当期
  剩余服务货（"本周期"对同一户不再唯一）；② 服务货若经迁移/规则落到**非跑商**家户，它不再进池（不可转卖），但只有该户
  又被选回跑商时才会重新可见；③ 被冻结的服务货（服务不进订单簿 ⇒ 现实中为 0）不作废，等解冻。
- 未跑 `spotbugs`/`checkstyle`/前端门禁，未跑全仓 `verify`。
