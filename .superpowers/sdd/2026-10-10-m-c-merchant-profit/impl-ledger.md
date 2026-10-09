# M-C 实现架构账本：跑商门槛（工具消耗）+ 纯商号自运免运费 / 顺便跑商独立计运费 + 商号利润算式

> 责任区：**M-C**。权威设计书 = `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md`
> **§12（H-A..H-G、§12.4 H-1..H-5）、§13（I-A..I-E）、§14**、`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`
> **§2.4（M0b/M1/M3）、§3 I-C1/I-C2/I-C3、§5 T3/T7、§6.2 V-21/V-22、§7 Q-14..Q-17/Q-23**；
> 前置批 = `.superpowers/sdd/2026-10-10-m-a1-capacity-pool/impl-ledger.md`、`.superpowers/sdd/2026-10-10-m-a2-capacity-price/impl-ledger.md`。
> 本文件是实现方自己写的**实现架构账本**（AGENTS §一.8），只记决策相关事实。

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| S-1 | `MarketSettlement.java:6064-6110`（`CARRIER_FEE` 唯一铸腿处）、`:6379-6449`（`carrierChargeSplit`） | 运费**付款人恒为买方**（`buy.buyer.actor` 铸腿）、收款人 = 该条承运条目的提供家户；自承运（carrier == buyer）被跳过 | 免运费必须同时看**货主**（买方家户）与**承运方**（条目提供者）；池在**发货格**（G-2）⇒ 跨格结构下两者恒不同户 ⇒ "buyer == carrier" 式的自运**结构上不可达**，判据只能是身份而不是同一户 |
| S-2 | `EconomySettlement.java:1807-1813`（池装配）、`MarketSettlement.java:3755`（worker 副本恒 `empty()`）、`:3922`（回放恒 `route == null`） | `route != null` 的成交**只在协调器串行路径**发生 | 工具扣减 / 运费豁免 / 利润累加可以像 `taxItems` 一样只在协调器写，worker 副本丢弃 |
| S-3 | `EconomySettlement.java:5478-5480`（`recordInputDraw`：`setStock` 直扣 + 记账）、`MarketSettlement.java:6028`（`deductBuyerLossNoTransfer` + `ledger.addLoss`） | 本仓已有"**货物离开账户但未换手**"的合法落点：`setStock` 直扣 + `ledger.addLoss(账, 商品, 量)`（守恒式 = Σ余额 + losses） | 工具烧毁照这条先例落地（账户减 + 损耗账加），**不新造写口、不碰铁律 2** |
| S-4 | `EconomySeeder.java:3540`（`householdStocks.put(id, Map.of())`）+ `:3396-3408`（只有作坊主拿工具） | 创世里"选了跑商"的家户（城镇 `rich_peasant`→`merchant.self_employed`、`landlord`→`merchant.principal`，见 `productionRuntimePositionId`）**一件工具都没有** | ★ 硬门槛会从第 1 天起掐死跨格贸易 ⇒ 必须补一份**创世启动工具**（§4 D-3，本批最大的数值变化） |
| S-5 | `HouseholdClassMembership.java:136-143`（`effectivePositionIds`）、`:45-53`（`currentPositionId` / `participatingPositionIds`） | 主业 / 副业**已在既有状态里表达**（§13.3），无需新字段 | 纯商号判据只用既有位置表；`MerchantIdentity` 是唯一拼写点 |
| S-6 | `MarketTaxBook.java:115-187`（三层税唯一计税点）、`MarketSettlement.java:5978-5985/6142-6160`（逐层真收） | 三层税 = `MarketTaxBook.Charge`（层 / 政府 / 币 / 额），**只对买方收** | 利润读数的税腿在**税腿铸点**按 `(买方家户, 层, 币, 额)` 累加（`MarketReport.TaxItem` 不带买方 ⇒ 不可事后归户） |
| S-7 | `MarketReport.Fill`（`goodsPaymentMilli` / `freightMilli` / `arrivalTick` / `lossMilli`） | 差价 / 运费支出 / 损耗 / 在途天数都能从逐笔 Fill 或铸腿处读到 | 利润读数每条腿都指得出数据来源，无需新增状态 |
| S-8 | `DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE = 20`、`ExpectedProfitBook.DEFAULT_MERCHANT_CYCLE_DAYS = 120` | "本钱占用"的机会成本率**已有单一拼写点**，不必另拍一个利率 | 本钱占用 = `支出 × 在途天数 × 20‰ ÷ (1000 × 120)`（微毫表达，见 §3 J-3） |
| S-9 | `grep 'MerchantCapacityPool\|MerchantCapacity' simos-*/src/test` = **0 命中** | 本批对既有测试**没有编译破坏** | 风险全在**行为面**（§5.5） |
| S-10 | `clearOncePerCycle` 的生产调用点**只有一处**（`EconomySettlement:1940`），另有两个委托重载 | 给市场轮多传一个"纯商号集合"代价极小（1 个新形参 + 1 个上下文字段） | 货主身份的判据不必塞进 `MarketRound`/状态里（零持久状态） |

## 2. 实现架构（组件拆分 / 数据流 / 调用次序）

### 2.1 新增类型（`simos-economy/src/main/java/io/mosire/simos/economy/time/`，均在 `time` 包内）

**`MerchantIdentity`（包内）—— 「跑商 / 纯商号」判据的唯一拼写点**

```
selectsMerchant(standing, positions)  = effectivePositionIds() 里任一位置 modeId == merchant  ← J-A 池成员判据（M-A1 原式搬入）
isPureMerchant(standing, positions)   = 主业 currentPositionId 的位置 modeId == merchant      ← §12.4 H-2 退役商号行后的判据
pureMerchants(standings, positions)   = 本轮纯商号集合（保序不可变、按家户 id 升序 → I7）
「顺便跑商」= 池成员 ∧ ¬纯商号（同一事实的补集，不另开判据）
```

**`MerchantHaul`（public）—— 跑商门槛（工具）唯一拼写点**

```
TOOL_COMMODITY        = CommodityId("tool")（EconomyVocabulary.TOOL_COMMODITY_ID 的唯一引用点）
TOOL_MILLI_PER_HAUL   = 1_000 毫工具（= 1 商品单位 = 20 毫银；理由见类注："一趟 = 承运 10 商品单位走 3 hex ⇒ 运费 ≈ 20 毫银"）
TOOL_BURN_ACCOUNT     = IndustryId("market-merchant-haul")（与 market-transport 分开的损耗账）
affordsRun(toolMilli) / runsAffordable(toolMilli)
```

**`MerchantProfitBook`（包内）—— 每轮算出来的利润读数（不落状态，Q-23）**

```
逐户腿（全部保序 LinkedHashMap；币一律分列，禁跨币求和 I-C10）：
  marginByCurrency(+卖收/−买付) freightEarned freightPaid
  laborCost(微毫→毫)  capitalOccupancy(微毫)  lossValue  taxByLayer(层×币)
  读数：runs / toolBurnMilli / laborHoursMilli / waivedFreightByCurrency / pureMerchant（标签）
利润_币 = 差价收入 + 运费收入 − 运费支出 − 劳动力成本 − 本钱占用 − 损耗 − Σ三层税
logRoundSummary(day, merchants)：INFO MERCHANT_PROFIT_TOTAL（含 recordedHouseholds = 记录面 vs households = 商号面）
                                 TRACE MERCHANT_PROFIT_HOUSEHOLD（逐户逐腿）
```

### 2.2 改动的既有类型

**`MerchantCapacityPool`**
- 成员判据改用 `MerchantIdentity.selectsMerchant`（删掉私有副本）；条目新增 `pureMerchant`（= `MerchantIdentity.isPureMerchant`）；
- 条目新增**工具预算** `remainingToolMilli`（装配时点该户 `tool` 商品存量）+ 计数 `toolBlockedRuns`；
- `select`：工具不够一趟 ⇒ **该次跑商不成立**（该条目不产生、计数、需求转成"运力未获服务"）；成立 ⇒ 扣一次 `TOOL_MILLI_PER_HAUL`；
- `CarrierChoice` 加 `pureMerchant` + `toolMilli`（★ 旧签名不保留：本类零测试引用，S-9）；新增 `laborHoursOf(户, 耗用)`、`remainingToolMilliOf`、`toolBlockedRuns()`、`householdIds()`；
- 日志：DEBUG `MERCHANT_CAPACITY_HOUSEHOLD` 加 `pureMerchant/toolRemainingMilli/runsAffordable`；DEBUG `MERCHANT_CAPACITY_LANE_TRUNCATED` 的 `reason` 改成**多因拼接**（`tool-short` / `out-of-derived-radius` / `sub-unit-residual` / `capacity-exhausted` / `no-merchant-household-in-shipping-hex`）；INFO `MERCHANT_CAPACITY_POOL` 加 `toolBlockedRuns/toolMilliRemaining/toolMilliPerHaul`。

**`MarketSettlement`**
- `clearOncePerCycle(6 参)` → **7 参**（新增 `Set<HouseholdId> pureMerchantHouseholds`）；4/5 参重载传 `Set.of()`（夹具 / 纯状态读者 ⇒ 谁都不豁免 ⇒ 逐值退回 M-A2）；
- `MatchContext` 新增 `merchantProfits`（逐轮瞬态）与 `pureMerchantHouseholds`（构造后由入口赋值，与 `recordFillIntents` 同款；worker 副本照抄）；
- `carrierChargeSplit`：**自运自货**（`choice.pureMerchant() && buyerIsPureMerchant`）整条跳过（两条分摊分支都跳）⇒ 该票不铸 `CARRIER_FEE`；`FreightCharge` 加 `pureMerchant` 标志；
- `executeTrade`：在**已有的**钱腿/税腿/损耗/运费腿落点旁记录利润腿（不新造写口、不改任何判据）；承运分配提到 `allocation` 变量；成交尾部调 `settleHaulRuns`；
- 新增 `settleHaulRuns`：逐条 ① 烧工具（`setHouseholdStock` + `ledger.addLoss(TOOL_BURN_ACCOUNT, tool, 实扣)`）② 记免运费读数（自运自货；同一张 `freightUnitMilli` + 该户限价）③ 记劳动成本/工具/免运费进利润簿 + TRACE `MERCHANT_HAUL_RUN`；
- 新增 `recordToolBurnValue`（烧掉的工具按该户所在格牌价折钱进**损耗腿**；无价/无市场 ⇒ 具名 `MERCHANT_HAUL_TOOL_UNPRICED`，不猜价）与 `capitalOccupancyMicro`；
- 轮末（`4a0b` 之后）新增 `4a0c`：`ctx.merchantProfits.logRoundSummary(day, carrierPool.householdIds())`；
- 区内即时 Fill 的 `freightPerUnitMilli`：**实收为 0 时报 0**（与在途路径 `reportedFreightPerUnit` 同口径）。

**`EconomySettlement`**
- 市场调用点现算 `MerchantIdentity.pureMerchants(session.sheet().classMemberships(), base.classPositions())` 并传进 7 参入口。

**`app: EconomySeeder`**
- 新增 `MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI = 12 × MerchantHaul.TOOL_MILLI_PER_HAUL`（= 12,000 毫工具 = 12 商品单位 = 240 毫银）；
- `urbanCohort` 把这份工具按**位置目录判据**（`isMerchantClassSlot`：位置 modeId == merchant）分给对应阶层的家户 —— 不写死槽位号；
- ★ 链路已核：`Seed.householdStocks()` → `HouseholdSeeder.payload` → `actor.Seed` → `HouseholdInventory` ⇒ 市场轮的 `householdGoods` 里读得到（S-4 的补法确实生效）。

### 2.3 一 tick 内的次序（本批落点）

```
① 生产/欠租（不动）
② 运力池装配（M-A2 不动）+ 本批新增：逐户纯商号标志 + 工具预算（装配时点 tool 商品存量）
   ＋ 纯商号集合（EconomySettlement 现算 ⇒ 7 参入口）
③ 撮合：跨格逐笔 select ⇒ 工具不够一趟的条目**不成立**（具名 tool-short）⇒ 需求未获服务（收缩成交）
   成交尾：settleHaulRuns（烧工具 + 记账 + 利润运行腿 + TRACE）
   carrierChargeSplit：自运自货 ⇒ 该条免运费（不铸腿）；其余照 M-A2 逐条按限价收
④ 轮末：MERCHANT_CAPACITY_POOL*（INFO，含 toolBlockedRuns）+ MERCHANT_PROFIT_TOTAL（INFO）/ _HOUSEHOLD（TRACE）
⑤ 信用轮 / FX 轮（不动）
```

## 3. 关键判断（为什么这样拆 / 为什么不走另一条路线 / 推翻过什么）

- **J-1 ★★ 免运费 = "自运自货"（承运方是纯商号 ∧ 货主是纯商号）**。逐条对齐冻结书：item 2「纯商号…**为自己的货**付运力时不计运费」（货主一侧）、
  item 3「**非纯商号承担的运力** ⇒ 运费独立计算」（承运方一侧的 else 分支）、item 4「"免运费"只对其**自运自货**成立」（合取）、
  item 5「**只有纯商号能免运费拿货**」（货主必须是纯商号）。⇒ 三条同时成立只有合取式；且它保住 M-A2 的运费经济
  （非商号买家的跨格进口照付运费 ⇒ "运力作为特殊商品…自动算收益"仍有落点）。
  ★ **"纯商号"取主业**（`currentPositionId ∈ merchant.*`，§13 I-A/I-D）：若取"任一有效位置"，池里**所有**提供者都成为纯商号
  ⇒ H-F 的"顺便跑商"（副业跑商）永远不可达。
  ★ 结构约束（S-1）：池在发货格 ⇒ 跨格时货主与承运方恒不同户 ⇒ 判据只能用**身份**（位置），不能用"同一户"。
- **J-2 门槛 = 每条承运条目（= 一次跑商）扣一次固定量，而不是按量计费**：H-1 问"单次跑商消耗多少 tool"、H-5 的失败语义是
  "**该次**跑商不成立"⇒ 固定门槛才给得出这个语义。★ 工具既是**产能维**（M-A1 算式）又是**门槛维**：V-22 的防重复计账落成
  "两处各有具名算式、**互不冲抵**"——产能读**装配时点**存量（每轮重算，H-E 不累积），消耗只减**存量**且不回改本轮运力预算；
  跨轮传导路径唯一：存量降 ⇒ 下一轮产能降。`AssetKind.TOOL` 产权份额**零触碰**（本批 diff 里 `grep AssetKind.TOOL` = 0）。
- **J-3 利润的"本钱占用"只算**在途占款**（微毫）**：跨轮库存持有的占用需要跨轮状态，而 Q-23 明写"不落状态"。
  ★ 世界利率 20‰/周期 ⇒ 该腿在毫级通常为 0 —— 读数按**微毫**列出、TRACE 里可见，绝不静默吞。
- **J-4 劳动成本 = 劳动小时 × 具名小时机会成本（10 微毫银/毫小时）**：推导 = 一人一周期口粮 10 毫银 ÷ (120 天 × 8,000 毫小时) ≈ 10.4。
  ★ 用户裁定的前提就是"家户有**剩余**劳动力"⇒ 用"被雇工资档"（`FIXED_MONEY_WAGE` 1,000 毫/周期·规模）会与前提相反。
  劳动小时 = `耗用运力 × 该户劳动 ÷ 该户运力`（劳动在运力算式里的份额，向上取整）。币 = **买方支付币**（与它被免掉的运费同币，逐币可加）。
- **J-5 "运费支出"腿：算式补齐（解释性偏离，见 §4 D-1）**：用户只否掉"运费**收入**"腿；漏掉"运费支出"会把付了运费的户读高。
- **J-6 工具"计成本"落在损耗腿**：账上工具烧毁就记在 `losses[market-merchant-haul]` ⇒ 读数的损耗腿 = 货损价值 + 工具烧毁价值，
  与账本口径一致（不新造"工具成本"腿，避免与冻结算式并列第二套成本口径）。
- **J-7 读数范围 = 跑商家户**：成交点记录**所有**家户的腿（记录与成交同址、不判身份），汇总时只出**池成员**（= 跑商家户）的读数
  ⇒ 世界里没有跑商家户时本读数**一行不打**（缺省语义中性 I-C2）；INFO 里 `recordedHouseholds` vs `households` 把两者分开。
- **J-8 在途/区内 Fill 的 `freightPerUnitMilli` 实收为 0 时报 0**：与在途路径由实收额反解同一口径；不改它 ⇒ 免运费条目的
  `landedUnitPriceMilli` 会把没付的运费算进去（读口失真）。
- **J-9 ★ 自我推翻（本批最重要的一次纠正）**：初版把免运费挂在**承运方一侧**（"纯商号承运 ⇒ 一律免"）。它让利润算式的
  "纯商号无运费收入腿"逐值自洽、但违反 item 5（非商号的货也免运费 ⇒ 谁都拿得到免运费货），也与 item 4 的"自运**自货**"不符。
  ⇒ 改成**合取式**（J-1），代价是纯商号为非商号的货承运时会真收运费（⇒ §4 D-1 的算式偏离）。

## 4. 偏离记录（与冻结口径不一致处，主动记录）

| # | 偏离 | 原因 |
|---|---|---|
| D-1 | 利润算式**多一条"运费支出"腿**；且**"运费收入"腿按事实计入**（冻结算式对纯商号不列运费收入腿） | ① 用户只否掉"运费**收入**"腿，没说支出——漏掉支出会把付了运费的户读高；② 合取式（J-1）下纯商号**确实**会因承运非商号的货收到运费，抹掉它会把真实收入读丢（用户要求"得算利润"）。★ 纯商号"自运自货"那一部分恒为 0（免运费）⇒ 冻结算式在**自营场景**逐值成立 |
| D-2 | 免运费判据取"承运方 ∧ 货主都是**主业** merchant 的家户" | H-2 在商号行退役后只剩位置判据；取**主业**是与"H-F 顺便跑商可达"自洽的唯一读法（J-1）。★ 若控制方裁定改回"任一有效位置"，改动面 = `MerchantIdentity.isPureMerchant` 一处 |
| D-3 | **改了创世初值**：跑商家户新增 12,000 毫工具/户的启动存量 | S-4：不补它，硬门槛从第 1 天起掐死跨格贸易（不是"门槛高"，是"门槛封死"）。★ 本批最大的数值变化，见 §6 的补货断点 |
| D-4 | `CarrierChoice` 加字段（旧签名**不保留**） | 本类零测试引用（S-9）；加字段是"每条承运条目带自己的门槛与身份"的唯一表达 |
| D-5 | `MerchantCapacityPool.select` 就地扣**工具预算**（与它已就地扣运力预算同款） | 门槛必须在**分配那一刻**判（H-5）；实扣发生在成交点 `settleHaulRuns`（S-3 的落点） |
| D-6 | 区内即时 Fill 的 `freightPerUnitMilli` 从"计划值"改成"实收为 0 报 0" | J-8；只影响实收为 0 的条目 |
| D-7 | `clearOncePerCycle` 6 参 → 7 参（多一个纯商号集合） | 免运费要判**货主**身份，而市场上下文里没有阶层表（S-10：生产调用点唯一 ⇒ 代价极小；零持久状态） |

## 5. 实施记录

### 5.1 改动文件（相对 `ef923d5a`）

| 类别 | 文件 | 处置 |
|---|---|---|
| 新增 | `time/MerchantHaul.java` | 门槛常量 `TOOL_MILLI_PER_HAUL` + 工具商品 + 损耗账（唯一拼写点） |
| 新增 | `time/MerchantIdentity.java` | 跑商 / 纯商号 / 纯商号集合 判据的唯一拼写点 |
| 新增 | `time/MerchantProfitBook.java` | 逐户逐腿利润读数 + INFO 汇总 + TRACE 逐户 |
| 改 | `time/MerchantCapacityPool.java` | 判据迁出、纯商号标志、工具预算与门槛、读数与日志 |
| 改 | `time/MarketSettlement.java` | 7 参入口、免运费跳过、利润腿记录、`settleHaulRuns`、工具烧毁、本钱占用、轮末汇总 |
| 改 | `time/EconomySettlement.java` | 现算纯商号集合并传给市场轮 |
| 改 | `app: world/EconomySeeder.java` | 跑商家户的创世启动工具（按位置目录判据分发） |

### 5.2 命令与结果（两条，本仓锁；只到编译过 —— 派单纪律）

```
$ tools/mvn-lock.sh -q spotless:apply        → exit=0
$ rm -rf simos-economy/target/classes simos-app/target/classes
$ tools/mvn-lock.sh -DskipTests compile      → exit=0（Compiling 196 + 327 source files；BUILD SUCCESS，16 模块）
```

### 5.3 会改变数值行为的清单（含缺省中性论证）

| # | 变化 | 缺省中性（无跑商家户 / 无跨格运力 / 无纯商号） |
|---|---|---|
| 1 | ★ **跨格承运现在需要工具存量**：装配时点 `tool ≥ 1,000 毫`，每条承运条目扣 1,000 毫；不足 ⇒ 该条目不成立（`tool-short`） | 池空 ⇒ `select` 无条目 ⇒ 一字不变；**有跑商家户但工具为 0** 的世界 ⇒ 跨格货走不动（具名 `LOGISTICS_CAPACITY` + DEBUG `tool-short`）—— 这就是 H-D/H-5 的 fail-closed 语义，**不是**缺省中性 |
| 2 | ★ **自运自货（双方都是纯商号）⇒ 该票不铸 CARRIER_FEE** | 无纯商号集合（夹具 5 参入口传 `Set.of()`）⇒ 谁都不豁免 ⇒ 逐值退回 M-A2 |
| 3 | 工具烧毁：`householdGoods[tool] -= 实扣` + `ledger.losses[market-merchant-haul] += 实扣` | 无跨格承运 ⇒ 不扣、不记（守恒式 Σ余额 + losses 不变） |
| 4 | ★ **创世**：城镇 `rich_peasant` / `landlord` 家户各多 12,000 毫工具 | 非 `production-runtime` 创世路径 / 无城镇人口的格 ⇒ 0（不落键，`putStockIfPositive`） |
| 5 | 区内即时 Fill 的 `freightPerUnitMilli`：实收 0 时报 0（免运费条目） | 有运费实收 ⇒ 逐值同改前；`route == null` 早已是 0 |
| 6 | 新增日志（`MERCHANT_PROFIT_*` / `MERCHANT_HAUL_*` / `MERCHANT_CAPACITY_*` 新字段） | 没有跑商家户 ⇒ 利润簿一行不打、池空 ⇒ 池汇总一行不打 |
| 7 | 零持久状态改动 | `EconomyData` / `ActorData` / `SocialData` / 变更集 / Codec / 命令面 / GM 工具 **全零改动**（Q-23：读数不落状态） |

### 5.4 具名常量（一行）

```
TOOL_MILLI_PER_HAUL = 1,000 毫工具/次（= 1 商品单位 = 20 毫银；理由："一趟 = 承运 10 商品单位走 3 hex ⇒ 运费 ≈ 20 毫银"）
MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI = 12 × TOOL_MILLI_PER_HAUL = 12,000 毫工具/户
LABOR_OPPORTUNITY_COST_MICRO_PER_HOUR = 10 微毫银/毫小时（= 10 毫银 ÷ (120 天 × 8,000 毫小时)）
本钱占用率 = DebtTerms.LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE(20‰) ÷ ExpectedProfitBook.DEFAULT_MERCHANT_CYCLE_DAYS(120)
```

### 5.5 会让既有测试失效的清单（**静态核对，未跑** —— 派单纪律只到编译）

| 类别 | 文件 | 原因 |
|---|---|---|
| **编译：无** | —— | 全仓 `src/test` 对 `MerchantCapacity*` / `CarrierChoice` / `CarrierAllocation` 的引用 = **0 命中**（`grep` 实测，S-9） |
| 行为：**可能变**（`EconomySettlement` 生产路径 + 有跑商家户 + 跨格） | `simos-app`：`ProductionRuntimeSeedSmokeTest`、`CompactThreeNationsWorld` 系；`simos-economy`：迁移/前瞻探针（`ProbeEconomy`、`HomeModeMigrationProbeTest`、`ExpectedProfitBookTest`） | ① 跨格现在要工具（创世给 12 次/户 ⇒ 约 12 次跑商后停）；② 自运自货的运费腿变 0 ⇒ 运费读数、在途 Fill 的 `freightPerUnitMilli`、利润读数变化 |
| 行为：**不变** | 夹具世界（`MarketSettlementFixtures` 的 4 参入口 + `MerchantCapacityPool.empty()` + `Set.of()`） | 池空 ⇒ 跨格不建、工具门槛与免运费判据都不可达（M-A1/M-A2 已如此） |
| 不受影响（核对过） | `MarketSettlementSingleHexLossTest`、`TransportTariffP4Test`、`MarketDemandBookTest`、`EconomyRoundTripTest` | 同 hex 成交 `route == null` ⇒ 不走运力、不烧工具；本批零持久状态 ⇒ 往返不变式维度不变 |

★ 测试侧口径（留给测试 Agent）：T1 门槛（装配时点无工具 ⇒ 跨格收缩 + `LOGISTICS_CAPACITY` + DEBUG `tool-short`；有 `n` 份工具 ⇒ 恰好成立 `⌊n ÷ 1000⌋` 条）；
T2 一次性（成交后 `householdGoods[tool]` 减 1000×条数、`ledger.losses[market-merchant-haul]` 等额、Σ余额+losses 不变）；
T3 自运自货免运费（纯商号买 + 纯商号承运 ⇒ `ctx.freightPaidByCurrency` 为空、无 `CARRIER_FEE` 腿、`waivedFreightByCurrency` 有值）；
T4 顺便跑商/非商号货主照收（把货主设成非纯商号 ⇒ 运费照收、`freightEarnedByCurrency` 进利润）；
T5 利润腿（差价/劳动/本钱/损耗（含工具烧毁价值）/三层税逐项；纯商号自营场景无运费腿）；T6 缺省中性（无跑商家户 ⇒ `MERCHANT_PROFIT_*` 一行不打、逐值不变）；T7 I7（池与利润簿的键序都是内容的纯函数）。

## 6. 未完成 / 未验证（如实记）

- **未跑任何测试/长跑**（派单纪律：只到编译过）⇒ §5.3 的数值变化、日志形态、门槛在真档里"咬不咬得住"**均未实测**；§5.5 的"行为：可能变"是**静态推断**。
- ★★ **工具补货路径缺失（本批最大的功能性缺口，需控制方裁定）**：家户的购买需求 = 自然需求 + 生产投入，而 `trade` 产业两者都没有
  ⇒ **跑商家户不会自发买工具**。于是创世那 12 次跑商烧完后，该户的跑商**停到它再次获得工具为止**。
  ⇒ 真档预期：跨格贸易在开世界后不久被门槛掐住（INFO `MERCHANT_CAPACITY_POOL.toolBlockedRuns` 会看得见）。
  三条可选补救（都不在本批冻结范围）：① 给 `trade` 产业加 `tool` 周期投入（会自动生成买入需求，但那是生产路径的第二处消耗）；
  ② 让跑商家户按"剩余运力 × 门槛"生成工具购买意图（M-D 的生产方式排序落点）；③ 调小 `TOOL_MILLI_PER_HAUL` / 调大创世存量。
- **待裁定 1 条**：§4 D-1 的算式偏离（运费收入腿按事实计入）——若控制方要冻结算式逐字成立，需改判免运费口径为
  "承运方是纯商号 ⇒ 一律免"（则纯商号永不收运费，算式逐字自洽，但非商号的货也免运费、与 item 5 冲突）或
  "纯商号不得为非商号的货承运"（则 item 2/3/4/5 与算式全自洽，但非商号的跨格进口在"没有顺便跑商家户"的世界里整批走不动）。
- **未做**（按冻结范围）：主业副业排序（M-D）；币种挂单过滤（P-T1e）；政府采购优先级（P-T1d）；卖家赊购（Q-26）；挂单簿持久状态。
- **具名边界（未修，如实记）**：
  ① 工具**同轮被卖单卖掉**时成交点实扣取现货上限并具名（`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT`）——门槛在装配/分配那一刻判，成交点只保证不扣成负余额；
  ② `uncollectedFreight`（被运力截断的名义运费）在免运费票上仍按**计划口径**记 —— 那笔运费本就不会收，改它要动既有读口语义，留待读口批次；
  ③ 纯状态侧的 `MerchantCapacityPool.hasCapacityAt(EconomyData, hex)` / 迁移前瞻**看不到工具**（`EconomyData` 里没有商品库存）⇒ 它给的是运力**上界**
  （M-A1 J-2 的具名偏差在本批被门槛放大：政策说"有运力"，市场可能因缺工具不成立）；
  ④ 损耗腿不含**到货日**才结算的跨区在途损耗（它在 `deliverShipments`，不在市场轮里）；
  ⑤ 跨轮库存持有的本钱占用不在读数里（J-3）。
