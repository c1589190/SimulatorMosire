# W2 实现架构账本 —— 清算与价格（债务式清算 + §2.1 动态价 + 生产方式内含口粮）

> 责任区：约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.4 §6.1 的 W2 行**
> （① 债务式清算 ② §2.1 动态价 + D/S 口径 + 0 价 + α 挂 MarketZone ③ S-10 生产方式内含口粮）。
> 写码 Agent 台账（AGENTS §一.8）：① 关键调查结论 ② 实现架构 ③ 关键判断 ④ 偏离记录。
> 证据纪律：本账本只记决策相关事实；命令与输出在报告里（同一批）。

---

## 一、关键调查结论（file:line → 结论 → 影响）

| # | 证据（开工时 HEAD `c0b587a0`） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:2161-2174`（D 累加器） | D 被**折回**"按当前参考价付得起的量"（`safeMulDiv(payableMoneyOf(...))`）；那段注释自述是 2026-10-09 为压住"需求对价格不敏感 ⇒ 乘性暴涨"加的红字补丁 | W2 的 α 封顶取代它 ⇒ **删除折回**，D 恢复全额需求 |
| 2 | `MarketSettlement.java:5886-5912`（`pairUp`） | 两道支付能力封顶：`affordableQuantity`（按可花+冻结反解量）+ `exactAffordableUpTo`（货款+运费+税 ≤ 可付的二分精确封顶）；`payable<=0` ⇒ 置 `noMoney` + `NO_BUDGET` 出局 | 债务式清算要的就是拆掉这两道；只保留"运费+税"的现金约束（见 §三-3） |
| 3 | `MarketSettlement.java:2999-3010`（`commitFreezes` 买方支） | `total = min(承诺, 可花)`；`total<=0` ⇒ 整组买槽标 `NO_BUDGET`（"没钱就出局"） | 改成"承诺可为 0，仍可赊购"；`NO_BUDGET` 从"买不起货"**具名改义**为"现金付不出运费/税" |
| 4 | `MarketSettlement.java:6876-6892`（`executeTrade` 钱腿） | 货款腿**无条件**铸 `payment`，`applyTransfer` 在余额不足时 fail-closed 抛（非发行源不得透支） | 未付价款必须**在铸腿前**就拆出来 ⇒ 现金腿 = `min(货款, 可花−运费−税)`，差额走债务 |
| 5 | `DebtContractBook.java:100` `DebtContractId.idOf(debtor, creditor, unit, terms)` | 合同身份含 `terms` ⇒ **同条款的赊购与借款自动合并成一个未偿额** | D-023 §3「不做分别核算、合同只有一个未偿额」由**同一份 `MARKET_CREDIT_TERMS`** 结构性满足，不新建债账 |
| 6 | `MarketSettlement.java:3352-3450`（`executeMoneyCredit` 等） | 已有"借钱买货 / 借实物"两条信用腿（D-030 §1–§3），写的就是 `ctx.debts` 同一本账 | **保留不动**（§4.1 判 ✅ 有效）；W2 之后它们在"需求已被全额满足"的世界里大多空转，见 §三-6 |
| 7 | `EconomySettlement.java:7361-7399`（旧 `repayDebts`） | 保留量 = `naturalNeeds[GRAIN] × DEBTOR_SUBSISTENCE_RESERVE_DAYS(=1)`（**只对粮**）；介质序 = 钱优先（D-030 §4） | 两处都要按 S-10 / S-5 换掉（保留量 → 生产花费；介质序 → 估值升序） |
| 8 | `EconomySettlement.java:2965`（旧调用点） | `repayDebts(..., householdPrices = null, ...)` ⇒ 估值一律回落"市场区默认价目表" | 传入 `HouseholdValuationBook`（逐 tick 现算）⇒ 债务折价与介质序改按**该户自己的保留价** |
| 9 | `HouseholdValuationBook.java:373-392`（旧 `applyPressure`/`blend`） | 两处 `Math.max(1L, …)` 的"≥1 微"地板 | 放开到 0（§5.2 的具名清理项） |
| 10 | `DebtValuation.java:298-306`（`commonUnitValue`） | 它本身**没有数值地板**：直接返回 `pricing.priceOf(...)`（0 价商品已经返回 0）；"地板"是调用方的 `<=0 ⇒ unpriced` 哨兵 | 不改它的算式；新增 **public** 读口 `estimatedUnitValueMilli` 供 S-5 排序用（同一条 switch，不复制算式） |
| 11 | `MarketSettlement.java:2051-2058`（`adaptPrices` 逐区循环） | W1 之后价表**每区一张**，改价 = 本区一次 upsert ⇒ α 的读点只需一个：按 `MarketZoneId` 查 `MarketZone` | α 挂 `MarketZone` 即可，不必新开组件（§3.1-4 的判断） |
| 12 | `MarketSettlement.java:1504-1510`（入口） | `MatchContext` 只带 `Map<MarketZoneId, MarketTable>`，**没有** `MarketZone` | 需要给入口/上下文补一份区表（照 `pureMerchantHouseholds` 的"构造后赋值"先例，见 §二-3） |
| 13 | `MarketSettlement.java:4439 / 4581-4601`（R2 并行内核） | 区内撮合在 **worker 的账户副本**上先跑一遍（`recordFillIntents = true`），协调器随后用**同一条 `executeTrade`** 回放 | ★ 债务 upsert 必须**只在回放侧**落（`ctx.debts` 是共享表）——否则同一笔赊购记两次 + 数据竞争 |
| 14 | `MarketSettlement.java:2519`（旧买盘目标） | 家户的 `baseTarget = life`（只口粮前瞻）；`necessary`（unit 必要投入）**只进卖侧保留、不进买盘** | S-10 的落点：`baseTarget = 生产花费 = necessary + life`（"只预估生产花费并购买"） |
| 15 | `MarketSettlement.java:2385`（旧卖侧保留） | `sellable = stock − frozen − necessary − (life + demandTarget)` | 数值上与新的 `stock − frozen − (生产花费 + demandTarget)` **逐值相同** ⇒ 卖侧零行为变化 |
| 16 | `EconomySettlement.java:2225-2250, 8872-8882`（诊断读口） | `BuyerOutcome.desiredQty` 与 `gapQty` 已经是"逐户逐商品的缺口"读数 | 把 `desired` 换成生产花费 ⇒ `gapQty` **就是**"生产花费未满足量"（W3 要的同一个量），不造第二个读口 |
| 17 | `MarketZone.java:74-80`（旧形状）+ 仅 2 处 `new MarketZone(`（两个 handler） | 加一个组件便宜：`EconomyDefineMarketZoneHandler:237`（立区）、`EconomyMergeMarketZonesHandler:179`（合并） | 立区可走缺省；**合并必须显式带过 α**，否则是"手写构造器静默丢组件"的同族形态 |
| 18 | `EconomySetMarketNumeraireHandler.java`（全文） | 逐区 GM 写口的完整形制：载荷 → 具名拒 → `base.withMarketZones*` → `EconomyChangeSet.between` → INFO/DEBUG/Rejected | 新 α 命令**逐条照抄**这个形制（含幂等拒与 GM-only） |
| 19 | `CatalogTool.java:925-930` | `PAYLOAD_HINTS` 要求覆盖**全部已注册命令类型**，缺项构造期抛 | 新命令必须同时登记载荷提示，否则启动即抛 |

---

## 二、实现架构（组件拆分 / 类设计 / 数据流）

### 2.1 ① 债务式清算（机制本体）

```
ordersFor（订单生成）
  └─ 家户：quantity = desiredQuantity(baseTarget = 生产花费（内含口粮）, demandParts, 可用, 在途)   ← 不按现金折回
     经营者 / 无债务副本的世界：allocateQuantity(..., cashAffordable)                            ← 旧口径回退
commitFreezes（买方支）
  └─ frozenRemaining = min(承诺, 可花) 的按需分配；**total ≤ 0 只置 0、不再标 NO_BUDGET**
pairUp（逐 (买方,卖方) 配对）
  ├─ deferralPossible(ctx, buy, sell)?
  │    真 ⇒ quantity 封顶 = exactChargeAffordableUpTo（**只算运费 + 税**，货款不封顶）
  │    假 ⇒ quantity 封顶 = exactTotalCostAffordableUpTo（旧口径：货款 + 运费 + 税 ≤ 可付）
  └─ executeTrade(...)
        paidCash = min(货款, max(0, 可花 − 运费 − 税))
        deferred = 货款 − paidCash
        钱腿只铸 paidCash；运费/税腿照旧；deferred > 0 ⇒ recordDeferredPaymentDebt(...)
recordDeferredPaymentDebt（唯一写口）
  └─ DebtContractBook.upsert(ctx.debts, 买方户, 卖方户, DebtUnit.Money(买方支付币), MARKET_CREDIT_TERMS, deferred, day, dueCycle)
       ★ 只在协调器/回放侧执行（!ctx.recordFillIntents）
```

- **只有一本债账**：`EconomyData.debtContracts`（`ctx.debts` 就是它的当日工作副本）；没有新表、没有第二套口径。
- **债务单位的选取**：与现金腿**同一种钱**（`buy.currency`）—— 不做任何折算（世界无汇率，I17/I24）。
- **回退口径（fail-closed）**：`deferralPossible` 四条（有债务副本 / 买方是家户 / 卖方是家户 / 买方不是"只按授权下单"的国库户）
  任一不真 ⇒ 该笔按**旧口径**封顶（货款也封顶），因为那种成交没有地方记欠款。`executeTrade` 另有一条契约断言：
  无赊购能力却出现 `deferred > 0` 时具名抛（绝不铸一笔无处记账的欠款、也不静默少付卖方）。

### 2.2 ② 价格形成（§2.1 + D/S + 0 价 + α）

```
EconomyData.marketZones ─→ clearOncePerCycle(..., marketZones) ─→ MatchContext.marketZones（worker 副本照抄）
adaptPrices
  ├─ D = Σ 买槽 order.quantity() − min(量, capacityTruncatedMilli)      ← 全额需求（折回已删）；M-A1 的运力维保留
  ├─ S = Σ 卖槽 sellable − min(量, capacityTruncatedMilli)              ← 只算真能上架的（保持现状）
  └─ adaptiveNextPrice(p, D, S, α)   α = MarketZone.priceAlphaPerMille（缺省 50；没有区表 ⇒ 出厂缺省）
       D > S ⇒ r=(D−S)/max(1,S)；step=min(α,⌊α·r⌋)；p'=⌊p×(1000+step)/1000⌋        （无上限）
       S > D ⇒ r=(S−D)/max(1,D)；step=min(α,⌊α·r⌋)；p'=max(0,⌊p×(1000−step)/1000⌋) （触底 0）
       D = S ⇒ p' = p                                                              （缺省中性）
       全程 long/BigInteger；禁浮点；乘法越界 ⇒ 饱和 Long.MAX_VALUE（价无上限）
```

- **α 的落点**：`MarketZone.priceAlphaPerMille`（持久组件，第 7 个）+ 六参构造器给缺省 + `withPriceAlphaPerMille` 写形 +
  新 GM 命令 `economy.SetMarketZonePriceAlpha` + app 窄工具。**铁律 5 三件套**见 §五。
- **0 价**：`MarketTable.isFree` 路径一字未改（有定价行且为 0 ⇒ 货款 0、运费照付）；放开的是估值侧的"≥1 微"地板。

### 2.3 ③ 生产方式内含口粮（S-10）

```
MarketSettlement.householdProductionCostOf(round, participant)      ← ★ 唯一量
  = necessaryInputsOf(round, participant)                            // 本户名下 unit：recipe.inputPerUnit × 计划规模
  ∪ householdLifeReserveOf(row)                                      // 口粮：naturalNeeds × MARKET_LIFE_RESERVE_DAYS
合用点：
  ① ordersFor：baseTarget（买）= 生产花费；retention（不卖）= 生产花费 + 需求目标
  ② collectBuyerOutcomes：desired = 生产花费 + 需求目标 ⇒ gapQty = 生产花费未满足量
  ③ MarketReport.unmetProductionCostByHousehold()（W3 读口）
  ④ EconomySettlement.productionCostsByHousehold(...)：还债保留量（逐商品）
```

- **数量口径仍从 Social 来**（`naturalNeeds` 是 app 逐户注入的当日物化视图，人口需求系数的唯一权威是 `SocialProvisioning`）；
  经济侧只把它**归入生产花费**，不再单列"口粮"这条独立下单路径。
- **"无业"也是一种生产方式**：名下没有 unit 的家户，第一项为空 ⇒ 生产花费 = 基本生理需求（同一个算式，无第二分支）。

---

## 三、关键判断（为什么这样拆 / 推翻过什么）

1. **未付价款的通道只覆盖"货款"，不含运费与税**。运费/税的收款人是**承运家户**与**政府国库户**（两条独立的钱腿），
   不是卖方；让它们也走债务就要为每个承运人/政府各开一条债务（超出 W2，且会改动 A2 的服务成交口径）。⇒ 口径定为
   **"买不起货款照买（记债），付不起运费/税则不成交"**；同格无税时现金腿恒 0 ⇒ **结构上永不因缺钱被拒**（判据 W2-① 的那一支）。
   落地方式：`chargesAtMost`（只看现金腿）+ `exactChargeAffordableUpTo`（二分，与 `totalCostAtMost` 共用同一批算式，不可能漂开）。
2. **`pairUp` 里仍然要有一道"按钱收数量"的路径**（`exactTotalCostAffordableUpTo`）：没有债务能力的世界（夹具、
   没注入债务副本的入口）不许凭空赊账。★ 我一度把 `totalCostAtMost`/`exactAffordableUpTo` 整族删掉，随后发现
   `recordDeferredPaymentDebt` 在这些世界会具名抛 ⇒ **把旧口径以 `exactTotalCostAffordableUpTo` 之名请回来**，只在
   `deferralPossible == false` 时走。这是本批唯一一次自我推翻。
3. **冻结保留为"承诺/上限"，但**不**取消那张表**：`frozen ≤ 余额` 是会话内核的构造期不变式（`FreezeIntent` 的类注、
   `applyTransfer` 的越冻结守卫）。"没钱可冻"的正确形态不是"冻一个负数/超额"，而是
   **冻结退化为 0 且不再是成交的前置条件** ⇒ 删掉的是 `total<=0 ⇒ NO_BUDGET` 那条**拒绝语义**，不是冻结机制。
4. **α 挂 `MarketZone` 而不是新组件**：W1 之后"每区一张价表"已成立，逐区字段天然有宿主；`EconomyData` 有 39 个组件且
   刻意没有"设置/政策"组件（§3.1-4）。★ 合并两区时 α 冲突 ⇒ **具名拒 `price-alpha-conflict`**（照 `official-rate-conflict`
   的形制）：静默取一边 = 静默丢一个政策。
5. **0 价商品"退出免费态"的例外保留**：§2.1 的乘法式在 `p = 0` 上恒为 0 ⇒ 白送出去的商品**永远涨不回来**，与
   "缺货逐步涨"及判据 W2-⑦ 直接冲突。保留既有那条**唯一**例外（`p=0 ∧ D>S ⇒ 下一轮从 1 毫起步`），其余一切走上式。
6. **D-030 的信用腿保留不动**：它在 W2 之后大多**空转**（现金撮合已把需求满足到供给上限 ⇒ `remaining` 通常为 0），
   但删除它超出 W2 的明示范围、且 §4.1 判它 ✅ 有效 ⇒ 保留 + 在代码注释与报告里**具名记下"空转"**这一后果。
7. **还债保留量的口径 = 生产花费（逐商品）**，不是"只粮 × 1 天"：S-10 要一个量贯穿买卖/还债/缺口。副产物是
   `DEBT_REPAYMENT_SHARE_PER_MILLE`（出厂 1000‰ = 全还）从"只对粮"扩到**所有可动商品**（出厂值下逐值等价）。
8. **债务只在回放侧落**（§一-13）：worker 副本若也 upsert，同一笔赊购记两次且写共享表。判据 = `!ctx.recordFillIntents`
   （与 `FillIntent` 同一条"worker 只产意向"的纪律）。
9. **读口不另造**：`BuyerOutcome.gapQty` 自 W2 起就是"生产花费未满足量"，另加一个 `MarketReport.unmetProductionCostByHousehold()`
   只做**按家户求和**（不重算任何东西）。

---

## 四、偏离记录（与约束设计书不一致之处，主动记录）

1. **§2.1 的 D 声明"含被运力截断的"，实现**保留** M-A1 的扣除**。理由：任务书 §1② 只点名"口岸闸拦下的量、买不起的量"
   两处要进 D，并明说 S 侧"运力截断的不算，**保持现状**"；M-A1（V-20）是 2026-10-09 的用户裁定（不可服务的跨格买卖
   不许把本格价格推上去）。⇒ 按"最新指令 + 不推翻既有用户裁定"取，**这是与控制方的一句话冲突，请复核**。
2. **§2.1 没有"0 价退出"这一支**，实现保留了它（§三-5）。
3. **"口岸闸拦下的量不进 D"这句现状描述与代码不符**：D 的原料是**买槽的 `order.quantity()`**（全额需求），口岸闸拦下的是
   `remaining`（撮合期事实）⇒ 那部分**本来就在 D 里**；把它再加一次会重复计数。⇒ 只做了"删除买不起折回"，未新增口岸项。
   （同族：卖方被拦下的量本来就在 S 里。）请控制方按"机制性描述回代码核"复核这条。
4. **§2.1 的 `⌊p×(1000±step)/1000⌋` 在小价位上等于不动**：`p < ⌈1000/step⌉`（α=50 ⇒ `p < 20` 毫）时乘性步长被整数
   除法抹平 —— 真实世界的**1 毫粮价将永远不涨**（只有 `p=0` 的退出免费态例外）。我按**冻结公式**实现，未自造
   "step>0 ⇒ 至少 1 毫"的规则；**请控制方裁定**是否补这条例外（这会改判据 ⑥ 的逐值表）。
5. **§6.1 W2 行写"取消…预算封顶"**：实现上预算仍参与冻结与现金腿的口径（`payableMoneyOf` 读 `Budget`），但它**不再限制
   成交数量**（需求全额进撮合）。⇒ 具名口径：**"预算"从数量上限降级为现金承诺上限**。
6. **α 的"不设上下限"与 0 的语义**：`MarketZone` 只拒负值；`α = 0` 是合法状态（该区价格不随供需变动），与"没设过 ⇒ 50‰"
   是两件事（六参构造器给缺省、七参给显式值）。
7. **`simos-economy-api` 的 `MarketZone` 六参构造器保留**：它是"不表态 α"的缺省形态（立区/合并的既有调用点），
   **不是**旧档兼容位（AGENTS §一.11 的"缺省语义中性"）。
8. **还债保留窗口 = `MARKET_LIFE_RESERVE_DAYS`（35 天）**：这是我定的口径（"下轮生产花费"里的口粮前瞻沿用市场侧同一窗口）。
   副作用：保留量比旧的"1 天口粮"大一个量级 ⇒ **还债额会显著下降**。这是本批最大的单点数值变化，请控制方与测试批重点核。
9. **`DebtValuation.commonUnitValue` 的 `<=0 ⇒ unpriced` 哨兵未改**：它没有数值地板（0 价商品本来就返回 0），
   把"0 价"与"缺价"合并会改变债务排序与债务能力语义（违反"缺价 ⇒ 不折算、不静默付 0"）。⇒ 只放开
   `HouseholdValuationBook` 的两处 `Math.max(1L, …)`。
10. **GM 写口只给 α**：`MarketZone` 的其它字段（半径等）仍只有立区/改划/合并三条命令；本批不扩面。

---

## 五、铁律 5 落点（α 这一个新持久字段的三件套）

| 环节 | 落点 |
|---|---|
| 形状 | `simos-economy-api/.../api/market/MarketZone.java`：第 7 个组件 `long priceAlphaPerMille`（守卫 `≥ 0`）+ `DEFAULT_PRICE_ALPHA_PER_MILLE = 50` + 六参缺省构造器 + `withPriceAlphaPerMille` |
| 写口（economy） | `EconomySetMarketZonePriceAlphaHandler`（`economy.SetMarketZonePriceAlpha`，`GmOnlyCommand`）：`base.withMarketZones(zones)` → `EconomyChangeSet.between(base, projected)` |
| ChangeSet | `EconomyChangeSet.marketZones : FieldDelta<MarketZone>`（W1 已存在）；`FieldDelta.diff` 按 record 相等判变化 ⇒ 新组件**自动**参与（`EconomyChangeSet.java:172/320/372/433/479`） |
| Codec | Jackson record 绑定（`EconomyData.marketZones` 的既有键空间 `MarketZoneId`）；无 mixin、无手写字段表 ⇒ 新组件自动往返 |
| 往返 | `apply(changeSet, base)` 逐字段重建由同一条 `FieldDelta.rebuild` 保证；测试批按 §6.3-2 补用例（W2 不写测试） |
| 合并区的 α | `EconomyMergeMarketZonesHandler`：两区 α 不同 ⇒ 具名拒 `price-alpha-conflict`；相同 ⇒ **显式带过**（不走缺省构造器） |
| GM 工具（app） | `simos.economy.setMarketZonePriceAlpha`（`EconomySetMarketZonePriceAlphaTool`，薄写工具）+ `Shell`/`SimosToolSource` 注册 + `CatalogTool.PAYLOAD_HINTS` 载荷提示 |
| 派生市场 | 没有 `MarketZone` 可挂 ⇒ `priceAlphaPerMilleOf` 取出厂缺省；命令对派生市场**具名拒 `zone-missing`**（不静默建区） |

---

## 六、未完成 / 未验证项（如实记）

1. **没跑测试**（§一.5 硬纪律）：`test` / `verify` / `package` 一律未跑；`test-compile` 也**没**跑 ⇒ 测试树当前**大概率编不过**
   （`CapacityTruncationPricingAcceptanceTest` 用 3 参 `adaptiveNextPrice`）。由测试批按 §6.3 迁移。
2. **没跑真实 world**（0→360 价格路径、崩溃/抢手场景、B2 分段等价、Revision Δ、耗时）：本批只到"编译过"。
   ⇒ 判据 W2-②③④⑥⑦⑧⑩ 的**逐值数字全部未验**。
3. **`ctx.recordFillIntents` 之外还有没有别的"worker 会写共享表"的新落点**：只逐点核了债务这一处（§三-8），
   未做穷举（该判据的理论依据是"worker 只产 FillIntent"这条既有纪律）。
4. **还债保留窗口（35 天）的数值后果未测**（§四-8）：这是本批最大的单点风险，需要真实 world 读数才能判定
   "还债是否结构性归零"。
5. **0 价成交是否真的出现**未测：撮合路径结构上支持（`hasPrice && priceOf==0 ⇒ 货款 0、运费照付`），
   但要真出现需要"滞销逐轮跌到 0"的真档场景，未跑。
6. **§四-1/§四-4 两条待控制方复核**（D 的运力维、小价位 α 无效）—— 我按"最新指令 + 冻结公式"实现，未自行改口径。
   ★★ **两条均已于 2026-10-11 由控制方裁定**（设计书 v3.5 §2.1 两条 ★★ + §7）：§四-1 = **D 不扣**（S 仍扣，删掉 M-A1 的需求侧扣除）；
   §四-4 = **补整数毫位最小步进**。落地见下面的「W2-fix」一节（同一文件、同一批）。

---

# W2-fix（v3.5 三条裁定落地）

> 责任区：把控制方对 W2 的三条口径裁定落地（约束设计书 `2026-10-10-unified-price-and-ordered-settlement-design.md`
> **v3.5 §2.1 两条 ★★ + §7-① ② ③**）。写码 Agent 台账（AGENTS §一.8）。
> ★ 本批**只改一个生产文件**：`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java`
> （W2 上一轮的 13 个文件在工作树里原样保留，未回退、未重做）。源文件 md5（收尾态）= `d58199393365c769ef4bc1d2aba587e8`。

## 一、① D 不扣"运力截断量"（v3.5 §2.1 第一条 ★★；闭 W2 账本 §四-1 的控制方复核）

| 落点 | 改成什么 |
|---|---|
| `MarketSettlement.java:2258-2269`（`adaptPrices` 买槽循环） | D 侧累加器改回**全额** `order.quantity()`：删掉 `- Math.min(quantity, buy.capacityTruncatedMilli)`。注释写明 v3.5 的 ★★ 与"不对称是刻意的"（若两侧同扣 ⇒ 被拦下的量从 D 与 S 同时消失 ⇒ 比值不变 ⇒ 价格不动，与用户"缺货价格应该逐步增加"矛盾） |
| `MarketSettlement.java:2271-2279`（卖槽循环） | **S 侧一字不动**：`sellable - Math.min(sellable, sell.capacityTruncatedMilli)` 保留；只把注释改写成"这就是 v3.5 的刻意不对称，两侧口径不许被改回对称" |
| `MarketSettlement.java:2211-2244`（`adaptPrices` 方法注） | 旧注释还在讲 `z = (D−S)/(D+S)` 与"M-A1 两侧都剔" ⇒ 整段重写为 §2.1 冻结式 + D 全额口径 + "口岸闸拦下的量本来就在 D 里、不新增不重复计" |
| `MarketSettlement.java:2093`（`roundStatsOf` 的 D/S 说明） | 同步改口径（原来也写着"折回按当前价付得起的量"） |
| `MarketSettlement.java:2306 / 2340-2390`（新增 DEBUG 日志） | `logDemandComposition(...)`：逐区逐商品一条 `MARKET_DEMAND_COMPOSITION`，把 **D 的构成**打成一行三量 —— `demandMilli`（全额）/ `unservedByCapacityMilli`（其中运力未服务，`Σ min(挂单量, capacityTruncatedMilli)`）/ `blockedByPortMilli`（其中口岸拦下）/ `supplyMilli`（S，仍扣截断）/ `alphaPerMille` |
| `MarketSettlement.java:10557 / 5569-5579 / 5879-5885`（新增只读累加器） | `MatchContext.portBlockedMilliByZoneCommodity`（逐 买方区 × 商品）+ 唯一写点 `recordPortBlockedDemand(...)`，写在 `matchRoute` 的"闸用尽且还有合格需求"那一支（上限 = `min(该 lane 合格需求合计, 未满足买余)`）。**只作日志读数**：被拦下的量本来就在 D 里，回加进 D 就是重复计数 |

**语义**：买家的"想要" = 全额需求（运力没运走 ≠ 没人要）⇒ 缺货压力**只能**从这条不对称里来 —— 这正是用户
"缺货价格应该逐步增加"的通道。★ 两个"其中"**互不重叠**：运力截断走 `capacityLeft <= 0` 那一支、口岸拦下走
`portLeft <= 0` 那一支（判据不同），一份未服务量不会同时进两项。

## 二、② 整数毫位的最小步进（v3.5 §2.1 第二条 ★★；闭 W2 账本 §四-4）

| 落点 | 改成什么 |
|---|---|
| `MarketSettlement.java:2451-2458`（`adaptiveNextPrice` 缺货支） | `step > 0` 时返回 `max(p + 1, ⌊p×(1000+step)/1000⌋)`（`incrementOneMilli` + 既有 `multiplyByPerMille`）。`p == 0` 仍走旧的"退出免费态 ⇒ 1"；滞销侧**不加对称例外**（原样 `max(0, ⌊p(1000−step)/1000⌋)`）—— `p=1` 实测已到 0 |
| `MarketSettlement.java:2473-2487`（新增 `incrementOneMilli`） | `Math.addExact(p, 1)`，溢出（`p` 已到 `Long.MAX_VALUE`）⇒ 返回原值（与乘法式同一条"价无上限"的饱和纪律：饱和而不是抛、不回退） |
| `MarketSettlement.java:2324-2398`（方法注） | 写明"+1 毫 = 整数粒度下最小的逐步，**不是第二条公式**"（方向/上限 ≤ α/无上限仍由乘法式定），并具名 α=0 这一支 |

## 三、③ 还债保留窗口（v3.5 §7-③；**不改代码**）

> ★★★ **落地前发现的设计冲突（上报项，见下面「冲突」小节）**：本条的裁定口径 **v3.5 §7-③（35 天）已被同一份设计书的
> 更新版本取代** —— 工作树里的 **v3.6**（2026-10-11，**用户第六批原话**）明文：「**还债不得设保留窗口**」，
> 并**逐条点名作废**："D-030 §4 的『一日口粮保留』与 **v3.5 裁定的『35 天窗口』一并作废**"、
> "**我 v3.5 那条裁定是错的**"。⇒ 下面按任务书原样记录的条目**只作留痕**，**不得**被当成待实现的口径；
> 正确口径是 **v3.6：有库存就还、没库存记债、无任何保留窗口**（唯一前瞻扣除在**卖侧**，0–8 第 5 条）。

- **代码一字未动**（与任务书 §1③ 一致：本批只写账本）：保留窗口常量 `MARKET_LIFE_RESERVE_DAYS = 35` 原样留在代码里。
- **按任务书原样记录**（v3.5 §7-③）：把 `MARKET_LIFE_RESERVE_DAYS = 35` **具名**为**「保留窗口参数」**
  （一个可调的标定/政策参数，**不是**从冻结算式推导出来的 —— W2 账本 §四-8 自述它是"我定的口径"）；
  并把「**600 天还债额 / 债务存量是否结构性归零**」列为 **W6 标定首查项**（控制方给的输入，不是本批验收项）。
- ★ **取代后的实际状态（v3.6 生效）**：按 v3.6，正确的落点是**根本没有保留窗口** ⇒ 上面那个"保留窗口参数"
  **连同它的 W6 标定项一起失去标的**（不该再有"窗口留多少"的标定）。代码里那个 35 天常量因此是一个
  **待删除项**（W2 剩余 / 下一批），**不是**待标定项。
- ★ **本批未改它**（越权）：任务书明确"不改代码"，且删窗口会改动 `EconomySettlement` 的还债保留量算式
  （W2 范围②的一部分，触及"会改变数值行为"的清单）⇒ 留给控制方裁定后另派。

## 四、偏离记录（与 v3.5 不一致之处，主动记录）

1. **`α = 0` 时缺货侧不动（`+1` 毫不越过它）**：v3.5 的公式只写了"缺货且 `p > 0` ⇒ `p' = max(p+1, …)`"，
   **没有**给 `α = 0` 单列一支；而 W2 的具名口径是"`α = 0` 是合法状态 —— 该区价格不随供需变动"（W2 账本 §四-6），
   且 §2.1 的 α 一句是"**不设上下限**（除 p ≥ 0）"⇒ 0 在定义域内。若让 `+1` 越过 `α = 0`，
   "GM 把某区价格钉死"这个语义就被**静默收窄**（只剩"每轮 +1 毫"）。⇒ 按 **fail-closed 取"钉死"**
   （缺货侧 `step <= 0 ⇒ return price`，与滞销侧同一道早退），并在**方法注 + 代码注释里具名**这是本批的具名例外。
   ★ **请控制方复核**：若裁定 α=0 也要 +1，改动是"把 `if (step <= 0L) return price;` 换成 `step = 0` 继续走 `max(p+1, p)`"一行。
2. **"被口岸拦下的量"的读数粒度**是按 **买方区 × 商品**（不是按 hex、不按区对），且它**只在"闸用尽且还有合格需求"
   那一支**记账（`portLeft = 0` 且 `demand > 0` 且仍有未满足）。W2 账本 §四-3 的结论不变：口岸拦下的量**本来就在 D 里**，
   本批只把它读出来、**不新增口岸项**（v3.5 明令）。
   ★ 副作用（如实记）：**没有口岸政策的世界里这张表恒空** ⇒ `blockedByPortMilli=0`（不是"没拦下"，是"没有闸"）。

## 五、W2 上一版 → 本批的"会改变数值行为"增量（只报增量）

| # | 条目 | 方向与幅度 |
|---|---|---|
| 1 | **D 不再扣运力截断量** | D 变大（幅度 = 该区该商品 `Σ min(挂单量, capacityTruncatedMilli)`）。**只对 D > S 的判据有影响**：D 变大 ⇒ `r` 变大 ⇒ `step` 更快打满 α；且**"原本 D 被扣到 ≤ S"的组合现在会变成 D > S ⇒ 从"不动"变成"涨"**（这是行为类别变化，不只是幅度） |
| 2 | **缺货侧 p=1..19 每轮至少 +1 毫** | 低价位从"恒等不动"变成"每轮 +1 毫"。α=50‰ 时 `p < 20` 全额受影响；`p ≥ 20` 只在下取整落到 `p` 时才可能 +1（实测 p=20 走乘法式 = 21，与旧式同值） |
| 3 | **新增 `MARKET_DEMAND_COMPOSITION`（DEBUG，逐区逐商品一行）** | **零数值影响**（只读、不写状态、不改算式）；级别 = DEBUG（缺省关） |
| 4 | **`portBlockedMilliByZoneCommodity`** | **零数值影响**（逐轮瞬态、只作日志；不进 `EconomyData` / 变更集 / 落盘，不进任何算式） |
| 5 | 未受影响 | S 侧口径、滞销侧算式、`p = 0` 退出免费态、`D = S` 中性、α 的读点与缺省（50‰）、`stepPerMille` / `multiplyByPerMille` 一律未动 |

## 六、判据自检（本批实测，非推演）

- **② 逐值（真方法，不是抄一份）**：临时驱动（`/tmp/w2fixcheck/`，**不落仓、不进 `src/test`**）以同包 + classpath
  调用**真的** `MarketSettlement.adaptiveNextPrice`（α=50‰，`D=2000 / S=1000` ⇒ `r=1` ⇒ `step=50`）：
  - `p = 1..19`：每轮 **+1**（`1→2→…→19→20`，20 轮逐轮 delta 全 = 1，单调）；
  - `p = 20`：`20 → 21`（乘性式 `⌊20×1050/1000⌋ = 21`，与旧式同值 ⇒ `p ≥ 20` 不被这条例外改变）；
  - `D = S`：`p ∈ {0, 1, 19, 20, 12345, Long.MAX_VALUE}` **一律不动**（缺省中性未破）；
  - 边界：`p=0, D>S ⇒ 1`；`p=0, S>D ⇒ 0`；`p=1, S>D ⇒ 0`（滞销侧不加例外）；`p=1000, S>D ⇒ 950`；
    `α=0 ⇒ 不动`；`p=Long.MAX_VALUE, D>S ⇒ Long.MAX_VALUE`（饱和不回退）。
- **① 真实 world 证据**（新播 `--world=small-world`，**用 `target/classes` 直跑，未 `package`**，
  store = `/tmp/w2fix-world/db`，`-Dsimos.economy.logLevel=DEBUG`，推进到 tick 40）：
  - `day=3 grain demandMilli=141406570 unservedByCapacityMilli=7300140 supplyMilli=81836041`
    ⇒ **D 是全额**（若旧口径，D 会是 `134106430`），而运力未服务的 `7300140` 只作读数 ⇒ 口径改对了；
  - 价格路径（`GET /api/economy/hex?q=-1&r=-1&revision=N`）：`rev1(tick 0)` grain=1 / cloth=5 / iron=10 ·
    `rev2(tick 10)` grain=5 / cloth=9 / iron=6 · `rev3(tick 40)` grain=17 / cloth=21 / iron=0
    ⇒ **低价位真的涨起来了**（旧公式在 `p < 20` 恒等不动），且 `fiber/iron` 触底 **0**（白送侧未破）；
  - 全程 **无异常**（`MARKET_DEMAND_COMPOSITION` 24 行、`MARKET_REPORT` 4 行、未出现 `BUILD`/契约抛错）。
    ★ 本世界**没有口岸政策** ⇒ `blockedByPortMilli=0` 全程（`recordPortBlockedDemand` 那一支**未被跑到**）。

## 七、未完成 / 未验证项（如实记）

1. **`blockedByPortMilli` 非 0 的场景未构造**（§六 的 small-world 无口岸政策 ⇒ 那一支的读数**没有真样本**）；
   我只静态核了它的写点判据（`portLeft <= 0`）与两个"其中"的互斥性。
2. **没跑测试**（§一.5 硬纪律）：`test` / `verify` / `package` / `clean` 一律未跑；测试树里
   `CapacityTruncationPricingAcceptanceTest` 仍在用 3 参 `adaptiveNextPrice` ⇒ 测试树**大概率编不过**（W2 账本 §六-1 已记）。
3. **600 天价格路径 / 跨区贸易量 / 运费实收**未验（那是 W6 的判据；本批只跑到 tick 40）。
4. **滞销侧"不加对称例外"只证了 `p=1 ⇒ 0`**，没有逐档扫（设计书明说不需要，故未展开）。
5. **§四-1（α=0 的 +1 取舍）待控制方复核**：我按 fail-closed 取"钉死"，已具名记偏离。
6. ★★★ **③ 的裁定口径已失效（上报项）**：任务书点名的 v3.5 §7-③「35 天保留窗口」**已被工作树里的 v3.6
   （用户第六批原话「为啥要设还债窗口，应当是有库存就还，没库存就不还啊」）明文作废**，且控制方在同一条里自记
   "我 v3.5 那条裁定是错的"。⇒ **③ 的正确落点是"删掉保留窗口"，与任务书交办的"具名 35 天 + 列为 W6 标定项"相反。**
   我**没有**按 v3.6 去改代码（任务书 §1③ 明令不改代码、§2 限定可改范围），只把两条口径都留在账本里并标出取代关系；
   **请控制方裁定**：(a) 另派一批删窗口（触及 `EconomySettlement` 还债保留量算式，会改变数值行为），
   或 (b) 明确 35 天继续有效（则须覆盖 v3.6 的用户原话，我个人认为这不应该）。
7. ★★ **旁证：工作树里还有 v3.7 的 S-11（用户第七批原话）**："供不应求才扣产能" —— 它**又给 W2 加了一件事**
   （实际产能 = 计划产能 × 最短缺投入的满足率），**本批未做**（不在任务书三条之内）。⇒ 派下一批时注意：
   W2 的范围在工作树里已经比任务书引用的 v3.5 §6.1 W2 行**更大**了。

---

# W2-fix2（删还债保留窗口 + S-11 产能满足率）

> 责任区：约束设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`
> **v3.6（§1 第六批 / §4.1"还债保全"行 / §6.1 W2 判据④）** + **v3.7（§2 S-11 / §6.1 W2 行第④件事 / §6.1 W3 行）**。
> 写码 Agent 台账（AGENTS §一.8）。★ 本批**只改 `simos-economy/src/main`**（两个文件），
> W2 + W2-fix 的 14 个未提交文件原样保留（未回退、未重做）。
> ★ 未写/未跑测试、未 `package`/`clean`/`verify`、未 `git commit`（§一.5 硬纪律）。

## 一、① 删掉还债保留窗口（v3.6，用户第六批）

### 1.1 落点与改成什么

| 落点 | 改动前 | 改动后 |
|---|---|---|
| `EconomySettlement.java:7608`（`repayDebts` 里算可动商品） | `spendableGoods(debtor, householdGoods, householdFrozenGoods, productionCost)` —— 第 4 个实参 = 逐户"下轮生产花费（含口粮）" | `spendableGoods(debtor, householdGoods, householdFrozenGoods)` —— **不传任何保留量** |
| `EconomySettlement.java:7929`（`spendableGoods` 签名） | `max(0, 余额 − 冻结 − productionCost[商品]) × DEBT_REPAYMENT_SHARE_PER_MILLE/1000` | `max(0, 余额 − 冻结) × DEBT_REPAYMENT_SHARE_PER_MILLE/1000`（**保留量恒 0**） |
| `EconomySettlement.java`（已删）`productionCostsByHousehold`（原 7657-7723） | `Σ_{本户名下 unit} industry.inputPerUnit(商品) × plannedCapacityScaleOf(...) + naturalNeeds(商品) × MARKET_LIFE_RESERVE_DAYS(35)` | **整方法删除** + 调用点删除（AGENTS §一.11：旧设计不留；它在本批之后已无任何读者） |
| `MarketSettlement.java:136`（`MARKET_LIFE_RESERVE_DAYS` 注） | "生活保留的总天数 = 35 天" | ★ **常量保留**（不要顺手删），注改成"**只服务买/卖两侧**（买盘目标 / 卖侧不卖保留 / 到货时限）；**不再服务还债侧**" |
| `MarketSettlement.java:164`（`householdProductionCostOf` 的"合用点"） | "买盘目标 / 不卖保留 / **还债保留** / 未满足量读口 都读它" | 删掉"还债保留"这一项，并具名写下"v3.6 起还债侧整块删除" |

**保留量从什么变成什么（一句话）**：从
`逐商品 max(0, 库存 − 冻结 − [ Σ_unit inputPerUnit×计划规模 + naturalNeeds×35天 ])`
变成
`逐商品 max(0, 库存 − 冻结)` —— **保留量 = 0**（有库存就还、没库存记债、挂账、计息、不清零）。

★ **卖侧那条前瞻扣除一字未动**（用户 0–8 第 5 条：投入市场的商品 = 扣除下一 tick 生产需要后的）：
`MarketSettlement` 的 `retention = 生产花费 + 需求目标` 与 `necessaryInputsOf` / `householdLifeReserveOf` 全未改，
本批只**摘掉了还债侧的用法**（任务书 §1① 的口径）。★ `productionCostsByHousehold` 是 `EconomySettlement` 里
与市场侧同源、但**只服务还债侧**的一份独立计算 ⇒ 它整块删除不影响卖侧。

### 1.2 日志（§一.9）

| 事件名 | 级别 | 落点 | 内容 |
|---|---|---|---|
| `DEBT_REPAY_ROUND` | **INFO** | `EconomySettlement.java:7740`（`repayDebts` 末尾，**每轮一条**） | `debtorsWithOutstanding` / `householdsRepaid` / **`householdsWithNoStockToRepay`（有几户无货可还 ⇒ 挂账）** / `repaidGrainMilli` / `debtContracts` / `reserveCommodities=0` / `why=v3.6-no-reserve-window` |
| `DEBT_REPAY_RESERVE_POLICY` | DEBUG | `EconomySettlement.java:7761` | 保留量口径"为什么一件都不扣"：`reserveCommodities=0` / `reserveMoney=0` / 两条可动额算式 / `why` |
| `DEBT_REPAY_MEDIUM_ORDER` | TRACE（既有，改字段） | `repayDebts` 逐户 | `reserveCommodities` 由"生产花费键数"改成恒 **0**（+ why 加 `v3.6-no-reserve-window`） |
| `REPAY_CRITERIA` | DEBUG（既有，改字段） | 4c 调用点 | `reserve` 由 `next-round-production-cost-including-rations` 改成 **`none`** |
| `REPAY` / `REPAY_DETAIL` | INFO/DEBUG（既有） | 4c 调用点 | 未改（`households` 仍是"本日发生还款的家户数"；新的"挂账户数"在 `DEBT_REPAY_ROUND` 里） |

★ 三个计数只做只读累加，**不进任何状态、不改任何算式**（`householdsRepaid` 用 `Set<HouseholdId>` 去重：同一户多条合同只计一次）。

## 二、② 供不应求才扣产能（S-11，用户第七批）

### 2.1 落点与公式

**落点**：`EconomySettlement.drawCycleInputs` 的**第二遍b**（`EconomySettlement.java:5585` → `applyInputSatisfaction`，
`EconomySettlement.java:5779`）——它是"生产阶段"里定本周期规模的地方；`plannedCapacityScaleOf` 的读点仍是
`EconomySettlement.java:5733`（`surveyInputDemands`），但读出来的值只当**计划规模**，实际产能由第二遍b 折算。

```
计划规模 S0 = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition)   // = 本格产能 × 状态机计划系数
计划需求 q_j = S0 × industry.inputPerUnit_j                                                   // 固定配方 / Leontief
实际到手 h_j = available_j = Σ_{k ∈ ownKeys} 库存_k,j                                          // ★ 家户手头库存**现算**，不新增状态
逐项满足率 r_j = q_j ≤ 0 ? 1000 : min(1000, ⌊h_j × 1000 ÷ q_j⌋)                                 // （EconomySettlement.java:5825）
投入满足率 r   = min_j r_j                                                                    // ★ 最短缺那一项，**不取加权平均**
实际产能 S     = ⌊S0 × r ÷ 1000⌋                                                              // capacityScale := S
```

- **只用库存，不看钱**：`h_j` 是 relation 指名账上的实物库存合计 —— 债务式清算下"没钱也能买"（未付价款记债），
  所以"拿不到"只可能是**没货/运力不足/口岸拦下**；扣产能**不因穷**。
- **口粮缺口不进这条**：本算式只读 `industry.inputPerUnit()`（生产资料），不读 `naturalNeeds`；
  `EconomySettlement` 里**没有**改人口、也没有改"可提供劳动"（劳动/人口是 Social 的唯一权威）⇒ S-11 第②半留给 W3。
- **★ 位置必须在 H6-lite 开池（第二遍）之后**：池子会把 `available` 换成"取料口径"的可供量。若按**本户自己的账**算
  （第一遍的位置），"自己账上一根料都没有、同格池子里有"的 unit 会被算成满足率 0 ⇒ 产能 0 ⇒ 不进池 ⇒ **把 H6-lite
  那条已实测修好的通道（城里作坊的纤维）堵死**。这是实现中途推翻的一版（见 §三-2）。

**实际产能怎么进生产结算**（三处，同一个数）：

| 环节 | 落点 | 用到的量 |
|---|---|---|
| 取料需求 | `EconomySettlement.InputPlan.limitOf:5674` → `usableScale × perScale_j`（争用商品 = `min(配给额, 该需求)`） | `usableScaleOf:6018` = `min(capacityScale', available_j/perScale_j)` |
| 实际扣料 | `drawOneProductionProcessInputs:5981` 逐商品扣 `≤ limitOf`，累加进 `unit.cycleInputUsedMilli` | 同上 |
| **收获（产量）** | `ProductionEfficiencyBook.evaluateHarvest:188-202`：`capPlanned = capacityScale × plannedPerMille`，`scaleBase = min(laborScale, capPlanned, ⌊drawn_j ÷ inputPerUnit_j⌋)` → `gross = scale × 数量 × 1000` 毫 | `cycleInputUsedMilli`（= 上面真扣到的量） |

★ 收获侧**刻意不重复折算**：`drawn_j / inputPerUnit_j` 就是"这一周期真到手的投入折出的规模"，再乘一次满足率 = 双扣
（见 §三-3）。

### 2.2 ★★★ 本批最重要的调查结论（机制性描述回代码核，AGENTS §四）

**S-11"实际产能 = 计划产能 × 最短缺投入的满足率"这条语义，在改前的代码里已经由"可供量那一路"实现了**：

```
改前：usableScaleOf(plan) = min( capacityScale(=plannedCapacityScaleOf),  min_j ⌊available_j ÷ inputPerUnit_j⌋ )
     = plannedCapacityScaleOf × min_j min(1, available_j ÷ (plannedCapacityScaleOf × inputPerUnit_j))
     = 计划产能 × 最短缺那一项投入的满足率      ← 与 S-11 的算式逐项同构
```
（证据：`EconomySettlement.java:6018` 的 `usableScaleOf`、`:5981` 的扣料、`ProductionEfficiencyBook.java:202` 的收获约束。）

⇒ **本批 ② 的净数值效果 = 千分比取整一级**（`⌊S0 × ⌊1000·h_j/q_j⌋ / 1000⌋` 比 `⌊h_j/a_j⌋` 最多小 1 个规模单位/unit）；
S-11 **不是新行为**，本批给它的是 ① 显式的落点与算式、② **可观测**（INFO/DEBUG 报"哪些户/哪项投入/满足率/扣了多少"）、
③ 一个以后可以收紧的**唯一**旋钮（`inputSatisfactionPerMille`）。★ 这条与任务书"预计……缺投入的户产出下降"**不一致**：
产出下降**改前就已经发生**（只是没有日志），请控制方按 §四 复核这条机制性描述。

**另有一处真差异（本批刻意不做，见 §四-2）**：`limitOf` 的配给额是按**计划规模**算的，S-11 把产能调低之后我加了一层
`min(配给额, 实际产能折出的需求)` —— 旧代码里 `quota ≤ need` 恒成立，这层 min 是恒等的；只有"取整让 `usableScale'`
比旧值小 1"时才可能削掉 1 个规模单位。

### 2.3 日志（§一.9）

| 事件名 | 级别 | 落点 | 内容 |
|---|---|---|---|
| `PRODUCTION_CAPACITY_DEDUCTED` | **INFO** | `EconomySettlement.java:5852`（`logCapacityDeduction`，满足率 < 1000‰ 时一条） | `day` / `unit` / `industry` / **`households`（ownKeys 全体）** / **`scarcestInput`** / `scarcestOnHand` / `scarcestRequired` / **`satisfactionPerMille`** / `plannedScale` / **`actualScale`** / **`deductedScale`** / `why` |
| `PRODUCTION_CAPACITY_DEDUCTED_DETAIL` | DEBUG | 同上 | `perUnit` / `onHand` 两张逐商品表（为什么是这一项短缺） |
| （既有）`PRODUCTION_EFFICIENCY_TICK` / `logProductionEfficiencyHarvest` | DEBUG | `EconomySettlement:1980/2024` | 收获日的规模与满足率（未改，未在 `evaluateHarvest` 的调用里重复折算） |

★ INFO 的量级如实记：每个**周期第一天**、每个"满足率 < 1000‰"的 unit 一条。真档上万一批 unit 同时缺料 ⇒ 可能上百行/周期日；
这是"用户用日志排查"的取舍（§一.9），级别可用 `-Dsimos.economy.logLevel` 关到 WARN。

## 三、关键判断（为什么这样拆 / 推翻过什么）

1. **删 `productionCostsByHousehold` 而不是留着不调用**：它在本批之后**零读者**（市场侧走自己的
   `householdMarketCostOf`，卖侧走 `necessaryInputsOf`），按 AGENTS §一.11"旧设计直接删"删掉；常量 `MARKET_LIFE_RESERVE_DAYS`
   **保留**（卖侧仍在用，任务书明令不要顺手删常量）。
2. **★ 推翻的一版（重要）**：② 最初实现在**第一遍**（`surveyInputDemands`）里算满足率，`capacityScale = planned × r`。
   实现中途发现它会把"本户账上没料、同格池子里有"的 unit 判成产能 0 ⇒ **堵死 H6-lite**（那条机制的实测病是
   "城里作坊第 2 周期起永久停工"，见 `drawCycleInputs` 的方法注）⇒ 改到**第二遍之后**（`applyInputSatisfaction`），
   与第三遍实际取料共用同一份 `available`。★ 这是本批唯一一次自我推翻，也是"② 不改在自家账上"的理由。
3. **收获侧不重复折算**：`evaluateHarvest` 的 `drawn_j/inputPerUnit_j` 已经是"实际到手投入"折出的规模；
   再乘一次满足率 = 双扣（且若用收获当刻的库存算满足率，还会把"周期初已经扣掉的种子"再扣一次 ⇒ 系统性压低产量）。
4. **`limitOf` 加一层 `min(配给额, 实际产能需求)`**：配给额是按**计划规模**算的，S-11 把产能调低后取料不得越过
   "实际产能需要的量"。旧代码里这层 min 恒等（`quota ≤ need` 由 `rationContestedInputs` 保证）。
5. **不给"满足率"新增持久字段**：任务书要求"优先用家户实际到手库存现算"，且新增字段触铁律 5 三件套；
   本批读的就是当刻的 `available`（会话工作副本）⇒ 不进 `EconomyData` / 变更集 / 落盘，**分段等价（G3）不受影响**。
   ★ 反面：若改用 `MarketReport.unmetProductionCostByHousehold()` 之类的市场缺口，会引入**跨日/跨 advance 的瞬态记忆**
   （`EconomyDayStepper.lastMarketReport` 每次 advance 重建）⇒ 会破 `0→360 一次 / 3 段`的分段等价，故**不用**。
6. **不按"家户合并"算满足率**：`Leontief` 的固定比例是**每条生产方式（unit）**的口径；把一户名下多个 unit 的投入并成
   一份最小满足率，会让一条产线的缺料去砍另一条产线（如织机缺纤维 ⇒ 农田也少种）。本批不做，记为待裁定（§五-1）。

## 四、偏离记录（与约束设计书 / 任务书不一致之处，主动记录）

1. ★★★ **② 的净数值效果与任务书"预计"不符（最重要的一条）**：任务书 §4 预计"缺投入的户产出下降"，而实测（静态逐值 + 代码证据）
   是**改前就已经下降**（`usableScaleOf` 的 Leontief min + 收获按 `drawn/perUnit` 缩放），本批只带来取整级差异。
   ⇒ 按 §四"机制性描述一律回代码核"，我**不改口径去凑那个预期**（那会变成"为了改数字而改语义"），而是把证据留在 §二-2.2。
2. **`available` 含**冻结**货（本批不动）**：`drawCycleInputs` 的 `available_j` 与 `h_j` 都取 `stockOf`（**不减** `frozenGoods`），
   而市场侧 `BuyerOutcome.onHand` 用的是 `max(0, 库存 − 冻结)`。⇒ 一户把粮挂卖之后，还债侧与投入侧都仍把那一份算在手头。
   ★ 本批**不**改（会系统性削减"挂卖余量"的家户的产能，属未获授权的行为改变）—— 记为一个**待裁定的收紧项**（§五-2）。
3. **`reserveCommodities=0` 是常量字段**：INFO/TRACE 里保留 `reserveCommodities` 这个字段名（值为 0）而不是删掉它，
   为的是让"上一版有保留窗口 / 这一版没有"能在同一张日志里对比（§五.4 留痕纪律：更正靠追加）。
4. **`REPAY` INFO 的触发门槛未改**（仍要 `还了粮 ∥ 还了钱 ∥ 有 skip`）；"本轮一笔都没还上"这一档由新的
   `DEBT_REPAY_ROUND`（恒发）承担 ⇒ 两条日志合起来"还了多少 / 有几户挂账"都在。

## 五、会改变数值行为的清单（增量，只报本批）

| # | 条目 | 方向与幅度 |
|---|---|---|
| 1 | **还债可用量 = 手头库存（不扣保留量）** | 还债额**显著上升**（幅度 = 上一版被扣掉的保留量：逐商品 `Σ_unit 计划规模×inputPerUnit + naturalNeeds×35天`）。真实世界的形态应是：**还债额/还清户数上升、库存被还到见底、挂账户数下降**。★ 未跑真实 world（§六-1），给的是算式级推断 |
| 2 | **`householdsWithNoStockToRepay`（挂账户）** | 读数新增；其**定义**是"可动用介质一件都没有"，不是"还不清" ⇒ 与"还债额上升"同向下降 |
| 3 | **保留量 0 引出的连带读数**：`repaid`/`repaidMoney` 进 `FlowRow.repaid*` ⇒ 阶层滑落（W4 的 f(债务/资产)）与 `DEBT_STRESS_CYCLES_BEFORE_INDEBTED` 那一路的**输入**随之变化 | 债务存量下降 ⇒ "资不抵债"判据更少触发 ⇒ 状态机（INDEBTED/SUSPENDED）触发率可能下降 |
| 4 | **② 实际产能 = 计划产能 × 满足率** | **取整级**（≤1 规模单位/unit/周期）：`usableScale' = min(⌊S0·⌊1000·h/q⌋/1000⌋, ⌊h/a⌋)` 相对旧 `⌊h/a⌋`。★ 争用格（H6-lite 池子）里 `limitOf` 新增的 `min` 同样只在取整差上生效 |
| 5 | **② 的 INFO/DEBUG 日志** | **零数值影响**（只读、不写状态、不进算式） |
| 6 | **未受影响** | 卖侧前瞻扣除（`retention`）、`MARKET_LIFE_RESERVE_DAYS` 的值与读点、`householdProductionCostOf` 的算式、S-5 介质序、D/S 与 §2.1 动态价、冻结机制、收获公式本体、population/labor 口径 |

## 六、判据自检（本批实测，非推演）

- **② 逐值（真方法，不是抄一份）**：临时驱动（`/tmp/w2fix2check/`，**不落仓、不进 `src/test`**）以同包 + classpath 调用
  **真的** `EconomySettlement.inputSatisfactionPerMille`（计划规模 100，配方 `grain 1/单位`、`fiber 2/单位`）：
  - 都够 `(100,200) → 1000‰`；一项富余 `(200,200) → 1000‰`（封顶）；
  - **最短缺那一项**：`(50,200) → 500‰`（**不是**加权平均 750‰）、`(100,50) → 250‰`、`(50,40) → 200‰`；
  - `(0,200) → 0‰`、`(0,0) → 0‰`（有需求却一根都没有 ⇒ 不把料倒进空转的产业）；
  - 取整：`(1,200) → ⌊1/100×1000⌋ = 10‰` ⇒ 实际产能 1（向下取整，不四舍五入）；
  - 缺省中性：空配方 → 1000‰、每单位需求 = 0 → 1000‰、`plannedScale = 0 → 0‰`、库存缺键 → 0‰（不猜）。
  - 全部 12 条 **ALL OK**（`[exit=0]`）。
- **① 的静态证据**：`grep -rn "productionCostByHousehold\|productionCostsByHousehold" --include=*.java .` ⇒ 只剩注释里的
  留痕 2 处，**零代码引用**；`spendableGoods(` 的调用点恰 1 处（`repayDebts`）。

## 七、未完成 / 未验证项（如实记）

1. ★★ **未跑真实 world**：还债额/库存见底/挂账户数、产出变化幅度、INFO 日志的真实行数**全部未验**（§六 是算式级推断 + 逐值单测式驱动）。
   本批只到"编译过"（§一.5）。**没有 `package`/`test`/`verify`/`clean`**（一次都没跑）。
2. **`DEBT_REPAY_ROUND` 的字段被 Spotless/`EventLog` 接受性未跑运行时**：只保证编译过与字段顺序合法，未实跑日志输出。
3. **② 在真档（争用格）的行为未验**：`limitOf` 的 `min` 在"取整让 `usableScale'` 小 1"之外的场景是否恒等，只做了静态推演
   （§二-2.1 的三处同源 + §五-4 的取整界）。
4. **`householdsRepaid` 的去重**：同一户多合同只计一次（`Set`），未做实跑。
5. **待裁定（§五 的两条收紧项）**：
   - **1**：满足率是否该**按家户合并**算（一户名下多 unit 共用一个满足率）—— 本批按 unit（Leontief 每条生产方式），
     合并会让一条产线的缺料砍另一条。请控制方裁定要不要合并。
   - **2**：`available`/`h_j` 是否该**减掉冻结量**（与市场侧 `onHand = 库存 − 冻结` 对齐）—— 会让"挂卖余量"的家户产能下降，
     属未授权的行为改变，本批不动。
6. **S-11 第②半（口粮缺口报社会）未做**：那是 W3 的通道；本批在 economy 里**没有**任何人口/劳动口径改动（§六 的边界判据不回退）。

---

# W2b 测试树迁移

> 责任区：把 `src/test` 迁移到 **W2 / W2-fix / W2-fix2 的新行为**，目标是
> `tools/mvn-lock.sh -DskipTests test-compile -am` **绿**，且**判别力不被削弱**（控制方派单书 §1/§3）。
> 允许改 `simos-economy/src/test/**`、`simos-app/src/test/**`；**不碰 `src/main/**`**；不写新测试（§三.0）；
> 不跑 `test`/`verify`/`package`/`clean`；不 `git commit`。**本批只改 1 个文件**（见 §四）。

## 一、逐类处置表（点名类 → 迁移 / 删除 / 断言语义面变更）

| 类（或文件） | 处置 | 一句理由（依据 = 代码实证，不是台账措辞） |
|---|---|---|
| `time/CapacityTruncationPricingAcceptanceTest` | **迁移**（编译 + 断言语义面变更） | 唯一**编译不过**的类（3 参 `adaptiveNextPrice`，现 4 参）；且它的 V-20 判据（两侧都剔）已被 v3.5 §2.1 的 ★★ 改写成**刻意不对称**（D 不剔 / S 剔）⇒ 期望值必须重算 |
| `time/MarketSettlementSingleHexLossTest` | 未改 | 世界里的家户**都没有 unit**、且 `MarketSettlementFixtures.round` 传 `creditConfig=null`/`debts=null` ⇒ 买盘目标（= 生产花费 = 生活保留）与"货款+运费+税 ≤ 可付"的封顶**逐值同改前**；价格断言读的是夹具的初始表（settle 不回写夹具） |
| `time/PortGateTaxAcceptanceTest` | 未改 | 同上：两侧量（卖方挂单 197,095 / 买方需求 2,905）逐值不变；税/运费断言全部按 `fill.quantity()` 现算 ⇒ 与新公式无关 |
| `time/Z7MarketExclusionTest` | 未改 | 只读 `planOrders` 的卖单排除与"正常户买单正对照"；国库户/单位户均 0 人口 0 投入 ⇒ 目标量恒 0 |
| `time/MarketPayCurrencyAcceptanceTest` | 未改 | 无债务副本 ⇒ 挂单量仍按买方支付币的现金折回（1,452 那笔的算式与前提未变：`cashAffordable` 仍在，只是"封顶"改由 `exactTotalCostAffordableUpTo` 兜底） |
| `time/ProcurementPriorityAcceptanceTest` | 未改 | 纯 `ProcurementPriorityOrder.advance` 单元测试（自造 `Slot` 记录），完全不经过市场/经济结算 |
| `time/MerchantCapacityAcceptanceTest` | 未改 | 池的纯函数 + 一个端到端截断用例；该世界的家户无 unit、卖方 0 人口 ⇒ 供需两面逐值不变 |
| `time/HaulServiceCommodityAcceptanceTest` | 未改 | 唯一带 `industryUnit` 的验收类：`merchantIndustry()` 的 `cycleInputPerUnit` 为**空**（⇒ 生产花费 0）、`ownListings…` 里的 `trade` 产业虽有投入但该户**无货币**且库存 ≥ 目标（⇒ 买盘恒 0）⇒ 卖侧保留量与改前逐值相同 |
| `time/FxPurchasingPowerAcceptanceTest` | 未改 | 全部是 `HouseholdPurchasingPower` / `ZoneTable` 的纯函数断言（W2 改的是 `HouseholdValuationBook`，不是它） |
| `time/FxHouseholdRoundAcceptanceTest` | 未改 | 走 `FxSettlement.match`（FX 段），不读 W2 的定价/清算/还债落点 |
| `time/MarketCurrencyReadoutTest` | 未改 | 值类型（`MarketReport.Fill` / `OperatorCondition`）构造 + 反射结构守卫；`NO_BUDGET` 只作枚举标签用，不承载旧语义 |
| `time/DeathDebtWriteOffTest` | 未改 | 断的是 `applyPopulationChange` 的"按存活比例删本金"算式 —— W2 未动该算式（只动 `repayDebts` / `spendableGoods`） |
| `time/MarketSettlementFixtures` | 未改 | 编译面：`new MarketZone(...)` 走的是**保留的六参构造器**（α 取缺省，§一.11"缺省语义中性"）；语义面：`creditConfig=null`/`debts=null` 是**刻意的 fail-closed 回退面**（见 §五-1，不能为了"让新路径被测到"改夹具默认值 —— 那会让全部既有夹具用例一起换口径） |
| `market/*ProbeTest`（17 个）+ `ProbeGoldenReadingsFixtureTest` | 未改 | ★★ **实测证据**：`ProbeEconomy.java` 与 `TwoRoundMarketProbeTest`/`HomeModeMigrationProbeTest` 的 `grep -n "io.mosire"` **只有 package 行**（零生产代码 import）⇒ 这一族是**自足平行模型**，W2 改的是 `io.mosire.simos.economy` 的生产类，**结构上不可能**影响它们。`ProbeGoldenReadingsFixtureTest` 更只读 `fixtures/probe-golden-readings.json`（不重跑探针）⇒ 也不变 |
| 其余 `time/*`（`ProductionEfficiency*`、`GovServiceCommitmentC7`、`ModeMigration*`、`LotMigrationBook`、`ExpectedProfitBook*`、`MarketDemandBook`、`HexTradeCost`） | 未改 | 不在 W2 改动面；`GovServiceCommitmentC7Test` 的 3 个**反射**签名（`scaleLaborOfUnit` / `requireGovServiceCommitmentsWithinBudgets` / `reallocateLabor`）实测逐字未变（运行时才炸的那一类已静态核过） |
| `simos-app/src/test/**` | 未改 | 全仓 grep：`NO_BUDGET`（2 处，均为值类型夹具）、`adaptiveNextPrice`、`CreditConfig`/`withCredit`、`MarketZone(` 在 app 测试面**0 命中**；`EconomyLoggingTest` 用**空经济** `settle(0,1)` 且断言是"至少有一条"式（`assertLevelEvent`），新增的 `DEBT_REPAY_ROUND` 不会让它红 |
| **删除** | **0 个** | 逐个核过：点名的类里**没有**"只断言旧模型本身"的用例 —— 唯一旧模型用例（V-20 的对称口径）迁移后仍有判别力（缺货涨 vs 滞销跌是可断言的新事实），故按 §一.10 的"老架构老测试直接删/迁移"取**迁移**一支 |

## 二、被改动语义的断言清单（类名 + 断言 + 旧期望 → 新期望 + 依据）

全部在 `time/CapacityTruncationPricingAcceptanceTest`（`+121 / −61`）。

| # | 断言（类#方法） | 旧期望 → 新期望 | 依据 |
|---|---|---|---|
| 1 | `adaptiveNextPrice(...)` ×7 处 | **3 参** `(p, D, S)`（α 内嵌常量） → **4 参** `(p, D, S, α)`，α = `MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE`（= 50） | 设计书 §2.1（α 逐区、可政策调）+ W2 账本 §2.2「α 的落点 = `MarketZone`」；夹具走派生市场 ⇒ `priceAlphaPerMilleOf` 取**出厂缺省**（与生产路径同一读口） |
| 2 | `truncatedCrossHex…`（改名 `…StaysInDemandButNeverEntersSupply`）：无运力案的价格 | `9,500`（D 剔光 ⇒ S>D ⇒ **跌**满 α） → **`10,500`**（D=2,905 > S=1,095 ⇒ r=1,810/1,095=1.65 ⇒ step=min(50,82)=50 ⇒ **涨**满 α） | **v3.5 §2.1 两条 ★★**（D 不扣运力截断、S 仍扣）+ 用户第三批原话「缺货价格应该逐步增加，因为这是市场机制」；`blockLaneWithoutCapacity` 逐字实证：**买卖两槽各记 `min(买余, 卖余)`**（`MarketSettlement:7743-7798`）⇒ 2,905 从 S 里剔、不从 D 里剔 |
| 3 | 同上：反例候选 | `naiveNext = aNP(p, demand, offered)` 期望 `9,921`（旧公式的"两侧都不剔"） → 该候选改名为 **`supplyNotExcluded` = `9,820`**（新公式的"S 不剔"），并**新增** `bothSidesExcluded = aNP(p, 0, offered−truncated)` **逐值钉住 `9,500`**（= 旧口径会得到的数） | 旧公式 `z=(D−S)/(D+S)` 已随 §2.1 退役；两个反例分别守住"供给侧仍要剔"与"D 侧不许再剔"两条口径 ⇒ **两侧各有一个当场红的判别点** |
| 4 | 同上：有运力对照案 | `9,921` → **`9,820`**（(D, S) = (2,905, 4,000) ⇒ S>D ⇒ step=⌊50×1,095/2,905⌋=18 ⇒ ⌊10,000×982/1000⌋） | §2.1 滞销支；两案价格仍**逐值不同**（10,500 ≠ 9,820） |
| 5 | `bothTheDemandAndTheSupplySideExclude…`（改名 `theSupplySideExcludesTheTruncatedPartWhileDemandKeepsIt`）：候选集 | 旧：4 个候选两两不等（`correct`/`demandExcludedOnly`/`supplyExcludedOnly`/`naive`） → 新：**3 个**候选（`9,890` / `9,500` / `9,640`）两两不等 | 新公式下"两侧都不剔"与"D 也剔"**同值**（都打满 α ⇒ 9,500）⇒ 第四个数不再可分辨 —— **如实记：这是本批唯一的判别力收窄**（口径本身仍两侧各有一个判别点）。★ 未用"改夹具人数"的方式硬造第四个数：那会改动夹具语义，且新的逐值字面量无法在本批实跑验证（见 §五-3） |
| 6 | 上述价格断言的形式 | 旧：期望值由**真函数**算出（`aNP(...)`）再与成交后的表比 → 新：**保持该形式**，但**另加**逐值字面量（`9,500` / `9,820` / `10,500` / `9,890` / `9,640`） | 派单书 §3-3「价格公式用例要能在把 `min(α,⌊α·r⌋)` 改成常数时红」：只靠"函数算期望"挡不住公式被换成常数（两边同时变）⇒ 必须钉字面量。字面量的算式与中间量全部写进断言消息，便于复核 |

## 三、重新基线化的探针读数表（旧 → 新）

| 探针族 | 旧读数 → 新读数 | 为什么 |
|---|---|---|
| `market/*ProbeTest`（17 个） | **无变化**（不是"没测"，是**测的不是生产代码**） | 实测：它们只 import `io.mosire.simos.economy.market.ProbeEconomy`（测试专用自足平行模型，**零 simos 生产 import**）；W2 改的是 `io.mosire.simos.economy.time.*` / `economy.api.market.MarketZone` ⇒ 结构性无关 |
| `ProbeGoldenReadingsFixtureTest` + `fixtures/probe-golden-readings.json` | **无变化** | 它不重跑探针，只断言 fixture 自身（`P0` 报告的机器可读快照）；fixture 没动 ⇒ 读数表也对得上 |
| `HaulServiceCommodityAcceptanceTest#stateDump`（两跑对照）、`PortPolicyMerchantLaneAcceptanceTest`（政策全开 vs 不注入） | **无变化**（同世界自比） | 这两族的"逐字节相同"是**自洽对照**，不是对历史快照；新公式在两侧同时生效 ⇒ 等式仍成立 |

★ 结论：**本仓没有"会随 W2 变的金标读数"需要重挂**（探针与生产代码解耦）。这条与控制方派单书 §2 的预期**不一致**，按 §四「机制性描述回代码核」如实记。

## 四、交账证据（真实命令 + rc + 关键输出）

| 轮 | 命令 | rc | 关键输出 |
|---|---|---|---|
| 基线 | `tools/mvn-lock.sh -DskipTests test-compile -am` | **1** | `BUILD FAILURE`；7 条编译错**全部**是 `CapacityTruncationPricingAcceptanceTest` 的 `adaptiveNextPrice` 三参调用（`required: long,long,long,long / found: long,long,long`），行号 115/132/150/205/206/208/211 |
| 迁移后 | 同上（`-DskipTests test-compile -am`） | **0** | `BUILD SUCCESS`；16/16 模块 `SUCCESS`；`simos-economy` **`Compiling 55 source files`**、`simos-gov` 9、`simos-army` 10、`simos-app` **`Compiling 147 source files`** |
| 收尾 | 同上（spotless 重排后） | **0** | `BUILD SUCCESS`（`EconomySimos … SUCCESS [1.856 s]`） |
| 格式 | `tools/mvn-lock.sh -q spotless:check`（**只读**先查）→ `-q spotless:apply -pl simos-economy` → 再 `-q spotless:check` | 1 → 0 → **0** | 首查只报**1 个**文件违规（本批那一个）⇒ apply 只作用于该文件；**W2 的 14 个 main 文件 mtime 未变**（`MarketSettlement.java 03:05:59` / `EconomySettlement.java 03:07:38`，早于本批 03:14 的写入）⇒ 未污染他人工作树 |

- 本批改动文件**恰 1 个**：`simos-economy/src/test/java/io/mosire/simos/economy/time/CapacityTruncationPricingAcceptanceTest.java`（md5 `bc94c8a1d07452c8488c4e2f017e87e0`）。
- `git status --porcelain` 复核：`src/main/**` 的 14 个 W2 文件 + 2 个新增文件**原样保留**，本批未回退、未重写。

## 五、未完成 / 未验证项（如实记）

1. ★★★ **W2 的债务式清算主路径在测试树里 0 覆盖（本批发现的最大缺口，须交最终测试 Agent）**：`MarketSettlementFixtures.round(...)` 用 22 参构造器传 `creditConfig=null` / `debts=null` ⇒ `MarketRound.creditEnabled() == false` ⇒ `ordersFor` 走 `allocateQuantity(..., cashAffordable)`、`pairUp` 走 `exactTotalCostAffordableUpTo`（**旧口径回退**）。全仓 `grep CreditConfig|withCredit` 在 `src/test` **0 命中** ⇒ 「现金 0 也能拿货、差额记债」「`NO_BUDGET` 具名改义」「还债 = 有库存就还 + 介质按估值升序」**一条都没被测到**。可用的入口已存在：`MarketRound.withCredit(dueCycle, debts)`（`MarketSettlement:1067`）—— 测试 Agent 只需在夹具轮上挂一份可变债务表即可；**本批不代做**（§三.0：新测试归最后的测试 Agent）。
2. ★★ **没跑任何测试**（派单书 §4 硬纪律）：`test`/`verify`/`package`/`clean` 一次都没跑 ⇒ 上述"未改"的类**只做了静态语义核对**（逐条读生产代码 + 夹具装配），**没有一条是"跑过且绿"**。凡"未改"都要读作"按代码推断不受影响，待门禁证伪"。
3. ★★ **本批手算的逐值字面量未实跑验证**：`9,500` / `9,820` / `10,500` / `9,890` / `9,640` 全部由 §2.1 算式手工推得（中间量与算式写进了断言消息）。推导依赖两条已静态核实的量：① `dailyRationMilli(1,1)=83`、`MARKET_LIFE_RESERVE_DAYS=35` ⇒ D=2,905；② `blockLaneWithoutCapacity` 把 `min(买余, 卖余)` **同时**记进买/卖两槽 ⇒ S=挂单−2,905。若门禁在这几个字面量上红，**先按这两条复核**（不是先怀疑公式）。
4. **判别力收窄一处**（见 §二-5）：新公式下"两侧都不剔"与"D 也剔"同值（都打满 α）⇒ 该反例不再可分辨；两侧口径各自的判别点（`9,820` / `9,500`）仍在。
5. **`simos-app/src/test` 只做了静态核对**：`GovToolsZ6Test`/`Z7RemittanceE2ETest`/`Z7D1CapitalProvinceTest`/`GovGenesisZ6Test`/`ShellSmokeTest` 等会推进整世界（跑得到 W2 的定价/还债/S-11 路径），本批逐条核过它们的**断言面**只有 gov/unit/social 读数与"至少一条日志"式断言，未发现 W2 依赖；但**未实跑**（见 §五-2）。
6. **`α = 0`（钉死价格）与最小步进、S-11 的产能满足率、0 价成交**：本批**没有**为它们新增或改写用例（属"新测试"范畴，§三.0）；测试树里现无覆盖。

# W2-fix3（SpotBugs 两条 + 产能扣减日志级别）

> 本批**只动生产代码 2 个文件**：`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`、
> `.../time/MarketSettlement.java`。**未碰** `src/test/**`、`simos-social`/`simos-unit`/`simos-sd`/`simos-core`、
> `docs/**`、`AGENTS.md`；未回退、未重做 W2/W2-fix/W2-fix2/W2b 的既有未提交改动。
> ★ 本节**取代**本账本 §（W2-fix2）里 `PRODUCTION_CAPACITY_DEDUCTED` / `PRODUCTION_CAPACITY_DEDUCTED_DETAIL`
> 两行的口径（原第 404-405 行的描述按 `AGENTS.md` §五.4 保留为留痕，不再是最新事实）。

## 一、关键调查结论（`file:line` → 结论 → 影响）

| # | 现场（`file:line`） | 结论 | 影响 |
|---|---|---|---|
| 1 | `EconomySettlement.java:5898`（改前行号，`logCapacityDeduction` 内）`scarcest == null ? 0L : hand.getOrDefault(scarcest, 0L)` | 三元表达式把 `Long` **拆箱**成 `long`，随即又为 `LogEvent.of` 的 `Object` 形参**装箱** ⇒ `BX_UNBOXING_IMMEDIATELY_REBOXED`（SpotBugs 的 `METHOD_CALLED` 证据就是 `Long.valueOf(J)`） | 纯性能/规范问题，无数值影响；修法必须"先在基本型上落定" |
| 2 | `MarketSettlement.java:2905` 取 `HouseholdId household = participant.household;`，唯一解引用在改前 `:3114` 的 `household.value()`，而它在 `if (MARKET.isDebugEnabled())`（改前 `:3061`）块内 | `Participant.household` 是**可空字段**（紧凑构造器只对 `actor`/`units` 做 `requireNonNull`，`:1150-1157`；全类多处按可空读：`:176` / `:2597` / `:2633` / `:2672` / `:2696` / `:2792` / `:3951` / `:9075` / `:9495`；`logPayCurrencyUnvalued` 直接兜空串 `:2855`），本方法体自己不判空 ⇒ 同一份世界"**开 DEBUG = NPE / 关 DEBUG = 静默不生成**" —— 行为取决于日志开关 | `NP_NULL_ON_SOME_PATH` 是真隐患（虽当前不可达，见下）；修法 = 方法内判空 + 具名跳过，顺带消掉"行为随日志开关变" |
| 2b | 可达性核查：`participantsFor` 三处构造点 `:9326` / `:9465` / `:9476` 都传解析出来的家户键；`planGovMandateOrders` 唯一调用点 `:2607-2608` 有 `participant.household != null` 守卫 | **今天走不到 null 分支** ⇒ 本批守卫是**纵深防御**（消隐患 + 消不确定性），**不是**修改现有可达路径 | 数值行为（可达路径）不变；报告里必须这么写，不许说成"修了一条正在发生的 bug" |
| 3 | `applyInputSatisfaction` 里 `.info(...)` 逐 unit 一条 `PRODUCTION_CAPACITY_DEDUCTED`；且 `DETAIL` 的 DEBUG 嵌在 `TRACE.isInfoEnabled()` 守卫内 | 真档一轮可能**上百行 INFO**（用户要"INFO = 这一轮发生了什么"）；INFO 关、DEBUG 开时连逐 unit 明细也没有 | 级别/粒度必须收口：INFO 一条轮汇总、逐 unit 下 DEBUG |
| 4 | `drawCycleInputs` 的调用者是**分区 worker**（`drawCycleInputsPartitioned` 的 λ，`:4148-4186`），另有"无格 unit"协调器单线程那一批（`:4211`） | ★★ 若在 `drawCycleInputs` 里就地发 INFO，行数 = **分区数**（≥1），不是"一条" | 汇总必须在**协调器**上做：worker 只记读数并交回，合并后一次发射 |

## 二、实现架构（本批怎么落）

**① 消装箱往返**（`EconomySettlement.java:5913-5921` + 事件的 `scarcestOnHand`/`scarcestRequired` 两个字段 `:5943-5946`）：

```java
long scarcestOnHand = 0L;          // 先落基本型：拆箱只发生一次、且不再回箱
long scarcestRequired = 0L;
if (scarcest != null) {
  scarcestOnHand = plan.available.getOrDefault(scarcest, 0L);
  scarcestRequired = plan.plannedScale * plan.perScale.getOrDefault(scarcest, 0L);
}
```

顺带把"最短缺那一项"的复算抽成 `scarcestInputOf(...)`（`:5874-5896`，判据与算式逐字照搬 `inputSatisfactionPerMille`）——
改前它只在 INFO 分支里算一次，现在它同时供**汇总的商品分布计数**与**逐 unit 明细**，全仓仍只有一个拼写点。

**② fail-closed 判空**（`MarketSettlement.java:2911-2914` + 新事件发射器 `logGovMandateNoHousehold`，`:3167`）：

```java
HouseholdId household = participant.household;
if (household == null) {                       // 先取到、判空、具名，再解引用
  logGovMandateNoHousehold(round, participant, hex, commodity);
  return;                                      // fail-closed：零订单（授权以家户为键 ⇒ 没有家户就没有可执行授权）
}
```

事件 `GOV_MARKET_MANDATE_PLAN_SKIPPED`（DEBUG，`io.mosire.simos.economy.market`，来源 `ECONOMY_MARKET`），
字段：`day / actor / hex / commodity / reason=no-household / why`。**没有**用 `assert`、**没有** `Objects.requireNonNull`、
**没有** `@SuppressFBWarnings`。

**★ 连带（必须一起做，否则门禁会多一条新的 High）**：守卫让 SpotBugs 在同一方法后半段推出 `participant.household` 恒非 null，
于是报出**新**的 `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE`（改后 `:3091`/`:3093`，即改前的 `:3082`/`:3084`）。
那两条空支（`participant.household != null ? productionCostHere : necessary` 与
`participant.household != null && round.creditEnabled()`）只存在于 **logging-only 的 DEBUG 证据块**里、且只服务"家户为 null"
的反事实 ⇒ 随守卫一并删除，其输入局部 `necessary` 同时删除（`:3070-3093`）。**可达路径逐值不变**：有家户时 `baseTarget` 恒 =
`productionCostHere`、信用判据恒 = `round.creditEnabled()`。★ 这条是"守卫的必然推论"，不是独立改动。

**③ 日志级别/粒度**（事件名与字段名尽量保持）：

| 事件 | 改前 | 改后 |
|---|---|---|
| `PRODUCTION_CAPACITY_DEDUCTED` | INFO（`io.mosire.simos.economy.settlement`），**逐 unit 一条**；字段 `day/unit/industry/households(列表)/scarcestInput/scarcestOnHand/scarcestRequired/satisfactionPerMille/plannedScale/actualScale/deductedScale/why` | **INFO，每轮/每天一条轮汇总**（协调器线程，`:4237` → `CapacityDeductionSummary.logSummary()` `:6039`）；字段 `day / units（被扣 unit 数）/ households（被扣家户数，去重）/ totalPlannedScale / totalActualScale / totalDeductedScale / maxDeductedPerMille（最大扣减比例，千分比）/ scarcestInputs（最短缺投入的商品分布计数）/ why`。★ **事件名保留、语义从"逐 unit"改成"轮汇总"（在此点名）**；`households` 由"列表"变为"计数" |
| `PRODUCTION_CAPACITY_DEDUCTED_DETAIL` | DEBUG，字段只有 `day/unit/perUnit/onHand`；且被嵌在 INFO 守卫里（INFO 关 ⇒ 它也没有） | DEBUG，**名字保留**；字段 = 改前 INFO 的**全部字段** + 原有两张逐商品表（`perUnit`/`onHand`）⇒ **一个字段都没丢**（`:5908-5960`） |
| `GOV_MARKET_MANDATE_PLAN_SKIPPED` | ——（新增） | DEBUG，理由判据（见 ②） |

**为什么必须"合并后再发"（③ 的关键判断）**：`drawCycleInputs` 每个分区调一次 ⇒ 就地发射 = "每分区一条 INFO"。
故把它从 `void` 改成**返回**日志读数（`:5569`/`:5604`），分区结果记录多一列（`InputDrawPartition`，`:4101`），
协调器**按分区计划序**合并（`:4208`）+ 无格那一批（`:4217`）后发一条（`:4237`）。
`SettlementExecutor.execute` 保证"结果序 = 计划序"（其类注与 `:54-76`），`scarcestInputs` 用 `TreeMap`（商品 id 升序）
⇒ 字段值与**线程调度、合并次序都无关**。★ 正面副作用：INFO 行不再由并行 worker 发出 ⇒ INFO 面的行序也不受线程调度影响。

**记录门与"日志失败不影响结算"**：`satisfactionPerMille < 1000 && (TRACE.isInfoEnabled() || TRACE.isDebugEnabled())`
（`:5806`）—— 两个级别都关 ⇒ 连"最短缺"都不复算；累加用基本型 `+=`（**不用** `Math.addExact`）⇒ 日志读数任何情况下不抛。
`CapacityDeductionSummary` 每份实例只被**一个** worker（或协调器单线程那段）触碰，跨分区靠 `merge` 在协调器上合并
⇒ 没有跨线程共享的可变状态（Future.get 提供 happens-before）。

## 三、关键判断与偏离记录

1. **②的级别选 DEBUG（任务书允许 INFO 或 DEBUG）**：它是"这一段/这一张订单为什么不成立"的**理由**行（§一.9：理由归 DEBUG），
   与同方法的 `GOV_MARKET_MANDATE_ORDER_SKIPPED`、`logPayCurrencyUnvalued` 的 `MARKET_PAY_CURRENCY_UNVALUED` **同一档**
   —— 后者在 `MarketSettlement.java:2832-2834` 有成文写的同款理由。★ **偏离提示**：若控制方按"业务拒绝 = INFO"（AGENTS 2026-10-23
   口径）要求它可见，把该 `logGovMandateNoHousehold` 里的 `.debug(` 改成 `.info(` 即可（**一个词**，不动字段）。
2. **②的可达性结论是静态结论**：三处构造点 + 唯一调用点的守卫都是逐行读出来的（见 §一-2b），没有运行期反证。
3. **删掉 RCN 那两条空支不是"替别人改设计"**：它们只在 logging-only 块里、只在"家户为 null"这个不可达分支上生效；
   不删则门禁必然多一条 **High** 新发现（第一轮自证实测：`BugInstance size is 1`，唯一一条就是它）⇒ 不删反而交不出绿门禁。
4. **①的注释里点名了改前行号 `5898`**：便于把 SpotBugs 的原始报告与代码对上；行号会随后续改动漂移，以**字段名/算式**为准。
5. **未使用"抑制"手段**：全程零 `@SuppressFBWarnings`、零 `assert`、零 `requireNonNull`（派单书硬纪律）。

## 四、交账证据（真实命令 + rc + 关键输出）

| 轮 | 命令 | rc | 关键输出 |
|---|---|---|---|
| 格式 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（`-q`）；跑完 `git status --porcelain` = 12 M + 2 ??，**与本批前逐条相同**；`src/test/**` 那个文件 mtime 仍 `03:14:28`（早于本批写入 `03:23`）⇒ spotless 未碰测试树，其余 W2 文件 mtime `02:44~02:48` 未变 |
| 编译（第 1 轮） | `tools/mvn-lock.sh -DskipTests compile -am` | **1** | `BUILD FAILURE`：`EconomySettlement.java:[4167,42] variable deductions is already defined in method drawCycleInputsPartitioned`（λ 内局部与外层同名）⇒ 改名 `partitionDeductions` |
| 编译（第 2/3 轮） | 同上 | **0** | `BUILD SUCCESS`，16/16 模块 `SUCCESS`（`EconomySimos … SUCCESS [3.853 s]`、`SimosApp … SUCCESS [2.839 s]`） |
| 自证（只读分析，**非门禁**） | `tools/mvn-lock.sh -pl simos-economy -am -Dspotbugs.failOnError=false spotbugs:check`（3 轮：守卫后 / RCN 消后 / 注释整理后复跑） | 0 | 轮 1：`simos-economy` **`BugInstance size is 1`**，唯一一条 = `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE` @ `MarketSettlement.java:[3091]`/`[3093]`（**原 BX/NP 两条都已消失**，同时暴露守卫的新 RCN）；轮 2/3：8 个模块全部 `BugInstance size is 0`，`simos-economy` `total_classes='512' total_bugs='0'`、`No errors/warnings found` |
| ①的独立旁证（与 aux classpath 无关） | `javap -p -c io.mosire.simos.economy.time.EconomySettlement`（取 `logCapacityDeductionDetail`） | 0 | `scarcestOnHand` / `scarcestRequired` 处是 `lload 8 → Long.valueOf(J)`、`lload 10 → Long.valueOf(J)`（**基本型一次装箱**），未见"`Long.longValue()` 紧跟 `Long.valueOf()`"的往返 |
| 参考文件保护 | 自证前后 `cp` 备份 → 还原 → `md5sum` 复核 | 0 | `simos-economy/target/spotbugsXml.xml` = `ecf1e9c31bcce44acdfc0ca18401ccfb`（与本批开始时**逐字节相同**）；本仓 15 个模块的 `spotbugsXml.xml` **全部还原**（逐个 md5 复核，非门禁轮产生的覆盖已撤回） |

★ **证据强度如实标注（重要）**：直调 `spotbugs:check` **不跑生命周期** ⇒ 依赖解析走了 `~/.m2` 里的**旧 SNAPSHOT jar**，
分析时有 **17 个 `simos-economy-api` 类缺失**（gate 产出的旧 XML：`missing 0 / 511 classes`；本自证：`missing 17 / 512 classes`；
`512 = 511 + 1`，多出来的正是本批新增的嵌套类 `CapacityDeductionSummary`）。⇒ 本自证**不是门禁等价**，只作"两条已消 + 无新发现"的**指示**；
权威判定归控制方的 `clean verify`（那是完整 aux classpath + `package` 后的 jar）。

## 五、会改变数值行为的清单（**空**；逐条说明为什么）

1. **③ 纯日志**：`capacityScale` 的算式与唯一写点未动（`:5800-5803`）；汇总/明细都不进算式、不写状态、不改账。
2. **② 守卫**：可达路径（`household != null`）逐字不变；不可达路径（null）由"DEBUG 开 ⇒ NPE / DEBUG 关 ⇒ 静默零订单"
   变成"两支都 ⇒ **零订单 + 具名 DEBUG 理由**" ⇒ 对**任何可达状态**无数值影响。这正是 fail-closed 的正确行为：
   订单不成立时**不写**、**不静默**、**行为与日志开关解耦**。
3. **RCN 清理**：删的是 logging-only 证据块里两条只服务"家户为 null"的空支与其输入 `necessary` ⇒ 可达路径上
   `baseTarget` / `wouldBeBuy` 逐值不变；不可达路径上"这条 DEBUG 事件不再给按 `necessary` 的反事实读数"（该分支本就到不了）。
4. **① 纯字节码**：`scarcestOnHand` / `scarcestRequired` 的取值集合与含义一字不变（只是先落 `long` 再进事件表）。

★ 结论：**本批没有任何一条会改变数值行为**；同一条世界在改前/改后应给出逐值相同的账与读数。

## 六、未完成 / 未验证项（如实记）

1. ★★ **没跑门禁**（`test` / `verify` / `package` / `clean` 一次都没跑，派单书硬纪律）⇒ SpotBugs 的**权威判定**在控制方；
   本批只交"编译绿 + 只读 SpotBugs 自证 + javap 字节码旁证"。
2. ★★ **没跑任何测试、也没实跑一个世界**：新的 INFO 汇总行**长什么样没有运行证据**（字段名/类型是代码级核实，
   行数与"每轮一条"是路径证明）。若控制方要看真实行，建议用 `-Dsimos.economy.logLevel=INFO` 跑一天真档
   并 `grep PRODUCTION_CAPACITY_DEDUCTED`（**期望恰 1 行/天**，且带 `units=`/`households=`/`totalDeductedScale=`/`scarcestInputs=`）。
3. **自证的 aux classpath 不完整**（见 §四的强度标注）⇒ "simos-economy 0 bugs"是在**非门禁等价**配置下得到的结论。
4. **②"今天不可达"是静态结论**（三处构造点 + 唯一调用点守卫），无运行期反证；要运行期证据需一个
   "把 `Participant.household` 置 null"的负向用例（属测试 Agent 范畴，本批不写）。
5. **`PRODUCTION_CAPACITY_DEDUCTED` 语义变更的下游影响**：全仓 grep（排除 `target/`、`.claude/`）该事件名只出现在
   `EconomySettlement.java` 与本账本 ⇒ **无测试、无工具、无日志消费者**按旧"逐 unit"语义解析它；但若控制方或后续
   脚本按旧字段解析，需同步（`households` 由列表变计数是最容易踩的一处）。
6. **"每轮一条 INFO"在多分区世界里未经实跑**（只有"worker 不发、协调器发一次"的代码路径证明）；
   `SettlementExecutor` 的"结果序 = 计划序"是读其类注与实现（`:54-76`）得到的，未实跑对照。


---

# W2-fix4（市场冻结修复 + 清单计数更新）

> 责任区：修 `EconomyCycleHealthTest.twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive` 的**真警报**
> （three-powers 第 1 个关账日 day=30 **成交 0 笔**）+ 4 个测试文件里的清单计数按实测更新。
> 写码 Agent 台账（AGENTS §一.8）。允许改：`simos-economy/src/main/**`、`simos-app/src/main/**`、
> `simos-app/src/test/**`、`simos-economy/src/test/**`。**未碰** `simos-social`/`simos-unit`/`simos-sd`/`simos-core`、
> `docs/**`、`AGENTS.md`、`simos-economy-api`。**未写新测试**（§三.0）、未 `git commit`、未跑 `test`（除任务书点名的
> `EconomyCycleHealthTest` 自证）、未跑 `verify`/`package`/`clean`。

## 一、关键调查结论（`file:line` 证据链 → 结论 → 影响）

> 行号 = **改前**工作树（W2-fix3 收尾态）；命令与输出见 §六。

| # | 现场 | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:1886-1893`（`creditRound(ctx, indexes)` 调用点，现金撮合与跨区撮合**之后**、未成交归因之前） | 市场信用撮合是**现金撮合之后的第二遍撮合**，不是前段门槛 | 它**只能加成交**，不可能"挡住"现金腿 ⇒ 任务书猜的"没有可借头寸就放弃这笔"**与机制不符**（见 §四-1） |
| 2 | `MarketSettlement.java:3506-3556`（`creditRound`）→ `:3558`（`creditForBuy`）→ `:3572`（`moneyCreditForBuy`）/`:3693`（`goodsCreditForBuy`） | 两条腿的候选集 = **卖单剩余**（`CreditPools` 的 `cashSellers`/`goodsLenders`），与"现金撮合为什么没成交"**无关**：被运力/口岸拦下的卖单剩余照样进池 | 现金腿明确拒绝的车道，信用腿**照买** |
| 3 | `MarketSettlement.java:3839-3888`（`executeGoodsCredit`）+ `:3891-3897`（`creditLossMilli`） | 借实物腿只铸一条 `sell.seller.actor → buy.buyer.actor` 的货腿（`sell.hex`），跨格只记一次单 hex 损耗 | **无运费、无 `carrierPool.select`、无三层税** ⇒ 与"绝不发免费的跨格货"（M-A1/A2/P-T1b 的红线）直接冲突 |
| 4 | `MarketSettlement.java:3726-3836`（`executeMoneyCredit`） | 借货币腿三条腿（出借人→买方、卖方→买方货、买方→卖方款）同样无运费/运力/税 | 同上；且对卖方是一笔"凭空由出借人垫付"的现金销售 |
| 5 | `MarketSettlement.java:3977-4000`（`creditUnfilledReason`）+ `:8019`（`collectUnfilled` 的 `creditReason` 分支） | 买方未成交归因被**第二层信用归因**接管：可借池状态 ⇒ `NO_LENDABLE_GOODS`/`NO_LENDABLE_MONEY`/`NO_CREDIT_LIMIT` | 警报里 `no_lendable_goods×400笔` 是**归因被替换**的结果，不是原因（真正的 top3 在修复后是 logistics_capacity/no_seller/no_buyer） |
| 6 | 真实 world 实测（three-powers，`-Dsimos.economy.logLevel=DEBUG`，day 3） | `fills=116`（其中**现金腿只有 64 笔**：`fills − creditMoney`）、`creditFills=279`（`creditMoney=52` + `creditGoods=227`）；带信用时 `LOGISTICS_CAPACITY=156`，关掉信用后 `=396` ⇒ **约 240 笔信用成交落在"现金撮合已判运力不足"的车道上** | 信用腿把货**绕过运力**搬走了 |
| 7 | 逐户探针（临时 `TMP_SELL_PROBE`，已删）：`hh--3_0-rural-landless_laborer-displaced` 的粮 | day 3 `stock=242,334 necessary=0 → sellable=225,989`；day 8 `stock=14,944 → sellable=0` | 卖方（无地劳工）的余粮在 day 3 被一次搬空 |
| 8 | 逐商品槽位探针（临时 `TMP_SLOT_PROBE`，已删）：粮 | day 3 `buySlots=148/277,490,450 sellSlots=52/70,968,954` → day 5 `sellSlots=29/**93,306**` → day 8 `sellSlots=**0**`，而 `buySlots=200/207,038,514` 不变 | 从 day 5 起**供给结构性消失**、需求照旧 ⇒ day 8~30 **0 笔** |
| 9 | `MarketSettlement.java:9529-9576`（`planFor` → `householdProductionCostOf`）+ `:2656-2659`（卖侧 `retention`） | 卖侧 `retention = 生产花费(necessary+life) + demandTarget`，与 W2 前逐值相同（W2 只把 `necessary` 也并进**买**侧目标） | 冻结**不是**"保留窗口"造成的：工人户的粮被搬走后**确实**低于保留量（结构性无供给），不是公式变严 |
| 10 | 实测对照：把两个 GM 开关改成 `false`（**只改这一处**，别的都不动） | `EconomyCycleHealthTest` **RC=0**：day 30 成交 33 笔，240 天每轮 24~133 笔，归因里**再无 `no_lendable_goods`** | ⇒ **根因 = 信用撮合这段代码本身**（不是"没钱买不到"、不是价格、不是保留量） |

**一句话根因**：D-030 的市场信用撮合（`creditRound` 的两条腿）在现金撮合之后**另开一条不交运费、不占运力、不交税、不看现金**的搬运通道，
把"现金腿已判运力不足"的跨格需求一次满足掉 ⇒ 卖方实物余量被瞬时搬空 ⇒ 需求仍在、供给归零 ⇒ 市场从 day 8 起冻结。

## 二、实现架构（本批怎么落）

**① 整段退役 D-030 市场信用撮合**（AGENTS §一.11「旧设计直接删」，控制方任务书 §1 明示"要么真正空转、要么删掉"）——
删除的成员（`MarketSettlement.java`，改前后行号）：

| 删掉的成员 | 改前后行号 | 说明 |
|---|---|---|
| `MONEY_LENDING_AUTO_LIST` / `MARKET_GOODS_LENDING_ENABLED` | `:290-298` | 只服务信用撮合的两个 GM 开关 |
| `creditRound` / `creditForBuy` | `:3506-3570` | 信用撮合主循环 |
| `moneyCreditForBuy` / `goodsCreditForBuy` | `:3572-3723` | 借钱买货 / 借实物 |
| `executeMoneyCredit` / `executeGoodsCredit` / `creditLossMilli` | `:3726-3897` | 两条腿的落账（含 `MARKET_CREDIT_FILL` 逐笔记录） |
| `maxQuantityForMoney` / `moneyReserveOf` | `:3908-3929` | 只服务货币信用池（家户版 `moneyReserveOfHousehold` **保留**：`GovernmentDebtIssuance:126` 仍在用） |
| `marketForParticipant` / `requireHouseholdRowInRound` / `applyMarketLeg` | `:3937-3960` | 只被信用腿调用 |
| `creditUnfilledReason` | `:3962-4003` | 第二层信用归因（`NO_LENDABLE_*` 的唯一生产者） |
| `MoneyLendOrder` / `CreditPools` | `:4006-4243` | 信用池（含 `moneyLenders`/`bestCashSeller`/`bestGoodsSeller`/`recordRemaining`） |
| `MatchContext.creditFills` / `creditMoneyRemainingByRegion` / `creditGoodsRemainingByRegionCommodity` + 访问口 `creditGoodsRemaining` | `:10614-10624`、`:10726-10731` | 信用读数 |
| `MarketReport.CreditFill` + `creditFills`（含 3 个工厂参数、紧凑构造器、旧签名构造器） | `MarketReport.java` 10 处 | 进程内报告里的信用成交表（**不是**持久状态，无铁律 5 影响） |
| `MarketReadout.creditFills`（组件 + 派生 + javadoc） | `MarketReadout.java` 4 处 | 读模型里的信用透传 |
| `EconomySettlement` 的 `creditMoney`/`creditGoods` 计数、两处日志字段、`MARKET_CREDIT_FILL` trace 段、`marketCreditFills` 字段 | 5 处 | 只服务信用成交的日志面 |

**② 保留并改名的"未付价款记债"通道**（唯一信用通道 = 用户 0–8 第 6–7 条）：

| 旧名 | 新名 | 为什么 |
|---|---|---|
| `MarketRound.CreditConfig` | `DeferredPaymentConfig` | 信用撮合退役后它只剩 `dueCycle` 一个字段，服务 `recordDeferredPaymentDebt` |
| `MarketRound.creditEnabled()` | `deferredPaymentsEnabled()` | 语义 = "本轮有没有未付价款记债能力"（`deferredPaymentConfig != null && debts != null`） |
| `MarketRound.withCredit(...)` | `withDeferredPayments(...)` | 同上（`EconomySettlement:2321` 的调用点同步） |
| `MARKET_CREDIT_TERMS` | `DEFERRED_PAYMENT_TERMS` | **值一字未改**（`DebtTerms.legacyDefault(BORROW_RATE_PER_MILLE_PER_CYCLE)`）⇒ 债务合同身份（含 terms）不变 |

**③ `deferralPossible` 保留为契约断言**（`MarketSettlement.java` 家户↔家户恒真的那条）：它**不是**挡板 ——
`pairUp` 在它为真时只按"运费 + 税"封顶数量、`executeTrade` 把差额记债；为假时退回"货款 + 运费 + 税 ≤ 可付"的旧口径，
且 `executeTrade` 的 fail-closed 断言照旧（无记债能力却出现 `deferred > 0` ⇒ 具名抛）。

**④ 现金腿语义一字未动**：`paidCash = min(货款, max(0, 可用现金 − 运费 − 税))`、运费与税**不走债务通道**
（收款人是承运家户与国库户）、`chargesAtMost`/`exactChargeAffordableUpTo` 封顶口径全部原样。

**⑤ 未成交归因**：删掉第二层信用归因后，买方归因回到 `inputShortfallNear`/`classifyNoSupply`/`NO_ADJACENT_SUPPLY`/
`PRICE_LIMIT`/`ALGORITHM_UNCOVERED` 这几档（`NO_BUDGET` 仍是"现金付不出这一笔的运费/税"的具名改义）。
`MarketUnfilledReason` 里的 `NO_LENDABLE_GOODS`/`NO_LENDABLE_MONEY`/`NO_CREDIT_LIMIT` **常量保留**
（该枚举在 `simos-economy-api`，**不在本批可改范围**；且历史 revision 的未成交档里仍有这些字面量、`parse` 必须继续认），
但**本轮结算不再产生它们**。

## 三、关键判断（为什么这样拆 / 推翻过什么）

1. **先测量、再动手**：先只把两个开关置 `false`（其余一字不动）跑真世界 —— 冻结消失（§一-10）⇒ 根因被**实测**钉在信用撮合这条路径上，
   而不是"看起来像"。随后才做删除（§二-①）。
2. **删除而不是"修好它"**：另一种路线是让信用腿也走 `carrierPool.select` + 运费 + 税（这样它就与 `deferralPossible` 那条通道重复 ⇒ 真正空转）。
   我**没走**这条路：① 控制方任务书明示"要么真正空转、要么删掉"，而"空转"在代码上就等于删除（留着 = 死分支，本仓最反对的形态）；
   ② 修好它要新增一条完整的"路线/运力/税/币种"装配（改动面比删除大一个量级，且 W2 的债务式清算已经覆盖同一诉求：付不出就欠卖方）；
   ③ §一.11 明令"旧设计直接删、不留兼容位"。**这是本批唯一的路线分歧，请控制方复核**（若要保留 D-030 的市场信用，应另开一批把它接到运费/运力/税上）。
3. **报告面一起删**：`MarketReport.CreditFill`/`creditFills` 与 `MarketReadout.creditFills` 在信用撮合退役后**结构性恒空**——
   留一个"永远空"的表正是 §一.11 说的"只留痕的结构"，故一并删除（它们是进程内/派生读数，不进 `EconomyData`、不进变更集、不落盘 ⇒ 无铁律 5 影响）。
4. **归因不许"看起来更好"**：删掉信用归因后，`ALGORITHM_UNCOVERED` 会重新露出来（段 2~8 的 top3 里 172~248 笔）。
   我**没有**为它新造档位或把它改写成语义更强的理由（那会变成"为读数好看而编归因"）；它本来就是 `collectUnfilled` 的既有兜底档，
   只是过去被信用归因盖住了。⇒ 如实记为**待收窄项**（§七-4）。
5. **探针只用一次就删**：两处 `TMP_*` DEBUG 探针（逐户卖侧量 + 逐商品槽位量）只用于定位，**已从代码里删净**（`grep TMP_` = 0）。

## 四、偏离记录（与约束设计书 / 任务书不一致之处，主动记录）

1. ★★★ **任务书的根因方向与机制不符（重要）**：任务书写"W2 的'欠卖方'这条腿**被信用市场的可借池挡在前面**"。
   实测（§一-1/-2/-6）：信用撮合跑在现金撮合**之后**，它**不能**阻止任何一笔现金成交；它的问题是**多开了一条绕过运费/运力/税的通道**，
   把卖方余量一次搬空。⇒ "修好挡板"这个方向**不可能**让 day 30 有成交；真正的修法是**拆掉那条旁路**。已按实测结论实现。
2. ★★ **与设计书 §4.1 的紧张关系（请控制方裁定）**：设计书 §4.1 记 "D-030 §1–§3（卖家未售商品即可借商品、钱优先借、借啥欠啥、利息并入本金）✅ 有效"。
   本批**删掉了这套市场信用机制本身**（只保留"未付价款 ⇒ 对该卖方的债务"这一条，它同样是 D-023 §3 的"同一本债账"）。
   ⇒ 若控制方判定 D-030 §1–§3 必须继续有效，则本批要回退"删除"、改为"把信用腿接到运费/运力/税上"（见 §三-2）。
   ★ 我按任务书的明示授权（"要么真正空转、要么删掉"）取 fail-closed 的删除一支，并在此具名上报。
3. **`no_lendable_goods` 的"准确归因"口径变了**：任务书要求"它应该只在真的没有货时出现"。删除信用撮合后它**一次都不出现**
   （没有可借池这个概念了）。我认为这满足"不再是主因"，但**不再有"真的没有货 ⇒ 记这一档"的语义**——
   真正的"没有货"现在落在 `no_seller`/`input_shortfall`/`no_adjacent_supply`（修复后 day 30 的 top3 就是它们）。如实记。
4. **改名（`CreditConfig`→`DeferredPaymentConfig` 等 4 处）**：任务书没点名要改名，但不改会让代码继续自称"信用"而实际只有"记债"。
   均为文件内私有/包内可见成员，改名的机械面在 `simos-economy` main + `EconomySettlement` 两处调用点，无跨模块契约变化。

## 五、会改变数值行为的清单（逐条具名）

| # | 条目 | 方向与幅度（真实 world 实测） |
|---|---|---|
| 1 | **删除 D-030 市场信用撮合（两条腿）** | day 3：`creditFills` 279 → **0**；现金腿本身**逐值不变**（两跑都是 64 笔）。成交量：day 3 `fills` 116 → 64、day 5 146 → 42、day 8 0 → 44、day 30 **0 → 33** |
| 2 | **卖方实物余量不再被信用腿搬空** | 粮的逐轮可售量：day 3 `70,968,954` → day 5 `93,306` → day 8+ `0`（改前）／day 5 `67,859,266` 起持续有货（改后） |
| 3 | **债务存量**（少了"借货币买货/借实物"两族合同） | day 30 债务本金 `3,154,207` → **`1,790,416` 毫**；day 240 `231,830,966`（改后） |
| 4 | **世界存量路径**（同一世界的后续演化） | day 30 粮 `70,305,786` → `69,102,061` 毫；day 120 粮 `798,551,335`、布 `1,840,343`（改后，改前该段未跑到）；人口 6,371 → 6,371（day 30 相同） |
| 5 | **未成交归因分布** | 不再产生 `NO_LENDABLE_GOODS`/`NO_LENDABLE_MONEY`/`NO_CREDIT_LIMIT`；买方残余档回到 `ALGORITHM_UNCOVERED`（段 2 起 ~220 笔/轮） |
| 6 | **日志面**：`MARKET_ROUND_START` 的字段 `creditEnabled` → `deferredPayments`；删 `MARKET`/`MARKET_REPORT` 汇总行的 `creditFills`/`creditMoney`/`creditGoods`；删逐笔事件 `MARKET_CREDIT_FILL`；删 `MARKET_CREDIT_SELF_MATCH_SKIPPED(_WHY)`（随代码） | **零数值影响**（只读） |
| 7 | **未受影响** | 现金腿算式（`paidCash`/`deferred` 拆分）、运费与税的现金约束、`deferralPossible` 四条判据、`recordDeferredPaymentDebt` 的条款与单位、`DebtContractBook.upsert` 的合并口径、D/S 与 §2.1 动态价、α 的读点、冻结机制、S-11 产能满足率、卖侧前瞻扣除 |
| 8 | **测试面（B）** | 只改断言里的**计数与名单字面量**，未放宽任何判据（`hasSize` 由实测值 +1 得到，`containsExactlyInAnyOrderElementsOf` 只加一条真实存在的工具/命令） |

## 六、交账证据（真实命令 + rc + 关键输出）

| 轮 | 命令 | rc | 关键输出 |
|---|---|---|---|
| 复现（改前，DEBUG 日志） | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false -Dsimos.economy.logLevel=DEBUG` | **1** | `Tests run: 1, Failures: 1`；`MARKET_REPORT day=3 fills=116 … creditFills=279 creditMoney=52 creditGoods=227` · `day=5 fills=146 … creditFills=130` · `day=8 fills=0 … NO_LENDABLE_GOODS=400` … `day=30 fills=0` |
| 根因对照（**只**把两个开关置 false） | 同上（改 `MONEY_LENDING_AUTO_LIST`/`MARKET_GOODS_LENDING_ENABLED`） | **0** | `Tests run: 1, Failures: 0`；240 天每轮 24~133 笔；归因 top3 = `logistics_capacity`/`no_seller`/`no_buyer`（**无 `no_lendable_goods`**） |
| 格式 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（`-q`）；跑完 `git status --porcelain` = 18 M + 2 ??（= 开工时 12 M + 2 ?? **+ 本批 6 个测试文件**，无别人的文件被格式化） |
| 编译 | `tools/mvn-lock.sh -DskipTests compile -am` | **1 → 0** | 轮 1：`Unused import - java.util.TreeSet`（删 import 后绿）；轮 2/3：`BUILD SUCCESS`（16/16 模块） |
| 测试树编译 | `tools/mvn-lock.sh -DskipTests test-compile -am` | **1 → 0** | 轮 1：`MarketCurrencyReadoutTest:200/223`、`MarketDemandBookTest:100`（旧签名少了 `creditFills` 形参 ⇒ 删掉它们多传的那个 `List.of()`）；轮 2/3：`BUILD SUCCESS` |
| **判据自证（任务书点名的那一条）** | `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false` | **0** | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 37.92 s`；摘要（`simos-app/target/economy-cycle-invariant/summary.txt`）8/8 段、每段成交 33/24/32/133/39/42/37/129 笔、逐币种货币守恒、`=== 体检通过：8 段 / 240 天，5 组不变量全部成立 ===` |
| 探针清理自证 | `grep -c "TMP_" simos-economy/.../MarketSettlement.java` | 0 | 两处临时探针**零残留** |

★ **`no_lendable_goods` 的变化（任务书 §4-2 要求）**：改前 day 30 的 top1（`no_lendable_goods×400笔/210,358,810毫`）在改后**整档消失**；
改后 day 30 的 top3 = `logistics_capacity×389笔/264,073,928毫`、`no_seller×170笔/455,307毫`、`no_buyer×89笔/130,590,007毫`
（= 真实的"运力不足 / 没人卖 / 没人买"）。

## 七、未完成 / 未验证项（如实记）

1. ★★ **只跑了 `EconomyCycleHealthTest` 一个测试类**（任务书只允许这一条自证）：(B) 的 4 个测试类（`McpCoverageTest` /
   `McpPortTopologyTest` / `McpServerTest` / `SimosToolsTest`）**没有实跑** —— 它们的计数与名单是按控制方 `clean verify`
   的失败消息里的**实测 actual**（163/163/163/173/67/163/141）逐个 +1 得到的，并用脚本解析本文件里的名单字面量复核过总数；
   但"跑起来是否绿"仍归控制方门禁。
2. ★★ **`simos-app` 其余测试类、`simos-economy` 全部测试类、SpotBugs、前端门禁、一次 `advance` 的 Revision Δ / 耗时**均未跑
   （`verify`/`package`/`clean` 一字未跑，硬纪律）。
3. **240 天的读数只有一条世界路径**：`no_lendable_goods` 消失、成交恢复是**实测**；但"每轮 24~133 笔"是否达到"市场健康"的标定目标
   （W6 的标定项）不在本批判据内 ⇒ 未评。
4. **`ALGORITHM_UNCOVERED` 未收窄**（§三-4）：段 2 起它常年占 top1~top2（172~248 笔/轮）。它是既有兜底档，本批不新造理由，
   但"这些买方为什么明明有邻接供给却没成交"值得另开一批（可能与 `enabled`/冻结/限价/路线窗口有关）。
5. **"信用腿接到运费/运力/税上"这条替代路线未实现也未测量**（§三-2）：若控制方裁定要保留 D-030 市场信用，需要另派一批。
6. **改名带来的下游文本未逐一清扫**：`docs/**`（不在可改范围）里仍有大量 `creditRound`/`MarketReport.CreditFill` 的叙述，
   按 §五.4/§一.11 属**历史留痕**（新文档另写，不回头改）。simos-economy/simos-app 的 main + test 树已 `grep` 复核为 0 残留
   （`NO_LENDABLE_*` 只剩注释里那一段具名说明 + `simos-economy-api` 的枚举常量本体）。

## 八、收尾追加（删完信用撮合后暴露的两处孤儿 + 一处不计入本批的既存孤儿）

> 本节的目的是**修正 §二 的删除清单**（追加两条）与 §六 的文件计数（12 M + 2 ?? → 本批后 19 M + 2 ??）；
> 上文按 §五.4 留痕，不改写。

1. **两处被本批"带成孤儿"的读口 ⇒ 已删**（`MarketSettlement.java`，删前 `:9143-9189`）：
   - `spendableGoodsOf(MarketRound, HouseholdId, CommodityId)`（A2a 家户实物可花额）；
   - `moneyReserveOfHousehold(MarketRound, HouseholdId, MarketTable)`（A2a 家户货币保留额 = 未覆盖自然需求按市价 + 12 毫/人）。
   两条的**唯一调用链**是 `moneyReserveOf`（MatchContext 版，已随信用撮合删）→ `moneyReserveOfHousehold` → `spendableGoodsOf`
   ⇒ 删掉信用撮合后它们是死代码（§一.11：旧设计直接删，不留"只留痕的结构"）。★ 净数值影响 = 0（无读者）。
   ★ 连带：`HouseholdPurchasingPower` 的 javadoc 原写"与 {@code MarketSettlement.moneyReserveOfHousehold} 的花钱口径逐字同源"，
   该引用随方法删除失效 ⇒ 改成"与 {@code MarketSettlement} 的花钱口径同源；那个保留额算式已随 W2-fix4 退役并删除，
   本类现在是'付清需求要付多少'的唯一拼写点"（**只改注释，零代码**）。⇒ 本批共 7 个测试/主源文件 + 3 个 W2 遗留的
   经济主源文件被本批触碰（`MarketSettlement`/`MarketReport`/`MarketReadout`/`EconomySettlement` +
   `HouseholdPurchasingPower`），4 个测试文件 + 2 个 economy 测试调用点。
2. **一处"本批之前就已孤儿"的常量 ⇒ 不动、只上报**：`MarketSettlement.MARKET_PRICE_FLOOR_MILLI`（`:277`）
   在 **W2 里就失去了全部读者**（HEAD `:2268/:2270` 的两处用点在 §2.1 换公式时消失；`grep` 全仓 = 1 命中 = 声明行）。
   它**不是本批造成的**，且名字与 §2.1"p_min = 0"的叙述仍一致（可读作文档常量）⇒ 本批不删（避免把 W2 的收尾混进本批 diff），
   在此具名上报：**W2 遗留的孤儿常量，建议随 W2 关账一并处置**。
3. **收尾复核（真命令）**：
   - `tools/mvn-lock.sh -q spotless:apply` → **0**；
   - `tools/mvn-lock.sh -DskipTests compile -am` → **0**（`BUILD SUCCESS`，16/16 模块）；
   - `tools/mvn-lock.sh -DskipTests test-compile -am` → **0**；
   - `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false` → **0**：
     `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 38.88 s`；
     摘要与 §六 逐值相同（day 30 成交 **33 笔**、top3 = `logistics_capacity`/`no_seller`/`no_buyer`，无 `no_lendable_goods`）。
   - `git status --porcelain` = **19 M + 2 ??**（19 = 开工时 12 + 本批 7：4 个 app 测试 + 2 个 economy 测试调用点 +
     `HouseholdPurchasingPower`；`MarketSettlement`/`MarketReport`/`MarketReadout`/`EconomySettlement` 本就在 12 之内）。
   - 临时对照用的 `/tmp/w2fix4-head` git worktree（HEAD 检出，**未在其中跑任何构建**）已 `git worktree remove --force` 删除，
     `git worktree list` 无残留。

4. **收尾后的最后一轮（在冻结树上，含上面两处注释整理）**：
   - `tools/mvn-lock.sh -q spotless:apply` → **0**；`tools/mvn-lock.sh -DskipTests compile -am` → **0**；
   - `tools/mvn-lock.sh test -pl simos-app -am -Dtest='EconomyCycleHealthTest' -Dsurefire.failIfNoSpecifiedTests=false` → **0**：
     `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 37.56 s`；
     摘要逐值同上（day 30 成交 **33 笔**、top3 = `logistics_capacity×389 / no_seller×170 / no_buyer×89`）。
5. **一处"退役后不可达但保留"的分支（具名，不删）**：`MarketSettlement.settlementUnitPrice` 的 `LEG_GOODS_CREDIT` 早退支
   与 `LEG_MONEY_CREDIT`/`orderKindOfLeg` 的 LENDING 映射 —— 信用撮合删除后已无调用方传入这两条腿，但 `leg` 仍是
   **币种挂单闸（P-T1e）的挂单类型词表**（`MarketOrderKind`，`PortEnforcementInput.CurrencyEnforcement` 与
   `PortGateTaxAcceptanceTest:457` 都按它分列规则）⇒ 删它们要改动那条判据面（超出本批范围）。
   已在方法注里具名"②③ 已退役、无调用方、不产生任何成交"。

# W2-fix5（孤儿清理）

> 责任区：清掉 W2 删除信用市场后留下的孤儿代码（门禁 SpotBugs 已拦下一条）。**只改 `src/main`**；
> 未跑 `test`/`verify`/`package`/`clean`；未 `git commit`；未碰 `src/test/**`。本节**追加**，不改写上文（§五.4 留痕）。
> 结构：① 关键调查结论（file:line 证据 → 结论 → 影响）② 删除清单 ③ 保留清单与理由 ④ 实现/判据装置
> ⑤ 真命令与结果 ⑥ 偏离记录 ⑦ 未完成/未验证项。

## 一、关键调查结论

1. **门禁那条是唯一的门禁可见孤儿**：`simos-economy/target/spotbugsXml.xml`（控制方 `clean verify` 的产物，md5
   `7c494222eee97d5841ba817124ab032b`）里 **`<BugInstance>` 总数 = 1**，就是
   `UPM_UNCALLED_PRIVATE_METHOD` / `MarketSettlement.moneyOf(MarketSettlement$MarketRound, MarketSettlement$Participant, CurrencyId)`
   （`abbrev='UPM'`、`priority='3'`）。⇒ 删掉它，本模块的 SpotBugs 面应当归零，且**没有第二条私有无调用方法**。
2. **`moneyOf` 的删除不会连带出新的孤儿**：它只读 `MarketRound.householdMoney`；该字段另有读者
   `spendableMoneyOf`（删后 `MarketSettlement.java:9137`）⇒ 删后重扫，孤儿集合**没有新增**（见 §四 装置自证）。
   ★ 注意同名陷阱：`MarketRound.moneyOf(HouseholdId)`（`:687`，包内、被 `FxSettlement:477/658` 读）与
   `EconomySettlement.moneyOf(Map,HouseholdId,CurrencyId)`、`AccountDelta.moneyOf(CurrencyId)` 都是**不同的方法**，
   按名字 grep 会误判 —— 判据必须签名感知。
3. **`MARKET_PRICE_FLOOR_MILLI` 的删除已被落盘文档授权**：
   `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md:309` 原话
   「W2 遗留孤儿常量 `MARKET_PRICE_FLOOR_MILLI`（已无读者）**待随 W2 关账删除**」，叠加 §一.11（旧设计直接删）。
   代码侧全仓命中 = 1（仅声明行），其余命中都在 `docs/**` 与 `.superpowers/**` 的历史叙述里。
4. **另有两类"死成员"但都不是本批造成的**（详见 §三）：`package-private` 方法与两个常量，**在 HEAD（W2 之前）
   就已零读者**。判据边界：任务书 §1.3 要的是"**信用市场删除后**变成孤儿"，故 9 项一律**保留 + 待裁定**，不猜。

## 二、删除清单（2 处，均在 `simos-economy/.../time/MarketSettlement.java`）

| # | 删前 file:line | 是什么 | 为什么是孤儿 |
|---|---|---|---|
| 1 | `MarketSettlement.java:9108-9113` | `private static long moneyOf(MarketRound, Participant, CurrencyId)` | 门禁独立报同一条（§一.1）；全仓零调用（main+test；含 `::` 方法引用与 `"moneyOf"` 字符串反射 = 0）。净数值影响 = 0（无读者）。 |
| 2 | `MarketSettlement.java:272-277`（整段 javadoc + 声明） | `static final long MARKET_PRICE_FLOOR_MILLI = 0L` | 全仓零读者（§一.3，落盘授权）。净数值影响 = 0。 |

一并删掉的只有**该常量自己的 javadoc 块**（那 5 行只描述这一个常量）；相邻的
`MARKET_ADAPTIVE_ALPHA_PER_MILLE`（活，读点 `priceAlphaPerMilleOf`）与 `LENDER_MONEY_BUFFER_PER_CAPITA_MILLI` 原样保留。

## 三、保留清单与理由（9 项；全部"HEAD 就已孤儿" ⇒ 待裁定，不猜）

判据：这 9 项都满足"全仓零调用"，但**不满足"信用市场删除后才变成孤儿"** —— 逐条用 `git show HEAD:` 核对过，
它们在 HEAD 同样只有声明、没有读者。按任务书「不确定的**保留并列为待裁定**，不要猜」处置。

| # | file:line（现） | 是什么 | 为什么保留 |
|---|---|---|---|
| 1 | `MarketSettlement.java:245` | `static final boolean MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE` | 零调用，但 HEAD`:203` 同样零调用 ⇒ **R3a 的遗留**。语义孪生体是 `MarketReport.java:82` 的 `public` 同名常量，**被 `MarketReadout.java:402` 真读**；`MarketReport` 那份的 javadoc 自述「★ R3a：旧撮合引擎删除，此常量由 `MarketSettlement` 迁来，值恒为 `true`」⇒ 这是**迁移后没删的原件**。删它要动"跨区结算读数契约"的公开面，超本批范围。 |
| 2 | `EconomySettlement.java:343` | `@Deprecated public static final int LOAN_INCOME_MULTIPLE_PER_MILLE` | 零读者（HEAD 亦零读者），但 javadoc 明写「本常量保留为**兼容别名**（旧测试/旧读数的字面量）」、`DebtCapacity.java:82` 亦如此叙述 ⇒ 是否算"留给测试用的具名入口"需裁定。§一.11 支持删（"兼容位该删就删"），但它是 `public` 且是**另一批（E4b）明确决定保留**的别名 ⇒ 上报。 |
| 3 | `EconomySettlement.java:1010` | `static EconomyData settleOneDay(6 参)` | 零调用；只**转发**到活的 8 参重载 `:3717`。HEAD`:1022` 同样零调用 ⇒ 非本批所致。 |
| 4 | `EconomySettlement.java:1029` | `static EconomyData settleOneDay(7 参)` | 同上（HEAD`:1041`）。★ 其 javadoc 自述「同上一支，但致死率可注入（**包内旋钮**，见 `FAMINE_MORTALITY_PER_MILLE`）」⇒ 有"具名入口"意图。 |
| 5 | `EconomySettlement.java:6580` | `static List<HouseholdId> householdKeysOf(3 参)` | 零调用，HEAD`:6239` 同样零调用。 |
| 6 | `EconomySettlement.java:6604` | `static List<HouseholdId> householdKeysOf(2 参)` | 零调用，HEAD`:6263` 同样零调用。 |
| 7 | `EconomySettlement.java:8432` | `static DebtContractId legacyGrainDebtId(HouseholdId, HouseholdId)` | 零调用，HEAD`:7928` 同样零调用。 |
| 8 | `EconomySettlement.java:9574` | `static MoneyIssuanceRecord withdrawFromTreasury(...)` | ★★ **明确不该删**：javadoc 自述「**P5：回笼（WITHDRAWAL）写口的包内入口**」「★ **为什么保留这个入口**：日结算/命令层的同包代码与 **P7 的 GM 编辑工具**可以只依赖 `EconomySettlement` 这一个门面；跨包调用方直接走 `TreasuryWithdrawal`（public）」「★ **P7/GM 接上之前，本批没有自动调用方**」。`docs/superpowers/reports/2026-10-08-currency-issuance-path-investigation.md:83` 同款记述 ⇒ **计划内待接线入口**，不是孤儿。 |
| 9 | `EconomySettlement.java:10505` | `static LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> emptyMoneyAccountsFor(...)` | 零调用，HEAD`:10001` 同样零调用。 |

★ **给控制方的一条系统性缺口（具名）**：SpotBugs 的 `UPM_UNCALLED_PRIVATE_METHOD` **只覆盖 `private` 成员**，
对 `package-private` 死方法**一律不报** —— §三 的 3~9 正是这一类（7 条）。门禁结构性看不见它们。
若裁定要清，建议**另开一批**并单独裁定"包内旋钮 / 待接线门面"这两类设计意图，不宜混进本批 diff。

★ **一处未修的历史瑕疵（具名上报，本批不动）**：`EconomySettlement.java:403`/`:434` 的
`{@link #settleOneDay(EconomyData, long, LinkedHashMap, boolean, int)}` 与
`{@link #settleOneDay(EconomyData, long, LinkedHashMap, boolean)}` 指向**不存在**的重载签名
（现有三支 `:1010`/`:1029`/`:3717` 的形参都不是这两个；HEAD`——`:415`/`:446` 同款）⇒ javadoc 链接是坏的。
属注释瑕疵、与孤儿无关，本批不改（避免把 W2 收尾混进无关 diff）。

## 四、判据装置（怎么确认"零调用"）

纯 `grep` 按名字会被**同名重载**骗（`moneyOf` 全仓 41 处命中里绝大多数是别的方法）。故自写**签名感知**只读扫描
（`/tmp/orphan_scan3.py`，不落仓）：

1. 方法按 **(名字, 实参个数)** 匹配调用点；`::name` 方法引用与 `"name"` 字符串（反射）单独计入；
2. **`private` 成员的可见范围 = 本文件** —— v1 曾把别的类里同名的同 arity 调用算成读者，导致
   `moneyOf` 假绿（**这正是本批最容易犯的错**：`EconomySettlement.moneyOf(...)` 恰好也是 3 参）；
3. 字段读是**裸标识符**（不是调用），必须单独收集 —— v2 只记 `name(` ⇒ 字段被**大面积**误报成孤儿
   （仅 `MarketSettlement.java`：54 个字段里 **26 个**被误报）；
4. 只扫本仓真实源码树 `simos-*/src/**`（1616 个 `.java`）；**排除 `.claude/worktrees/`** —— 那里是整份检出副本
   （25415 个 `.java`），把陈旧副本算成读者会让"零调用"永远不成立；
5. **装置自证（2/2 命中已知真值）**：它**独立复现**了门禁那一条 `moneyOf`（arity=3）与已知孤儿
   `MARKET_PRICE_FLOOR_MILLI`，且清掉大量误报（**同一批 4 个文件**上：装置缺陷版 v2 报 **68** 条 `ORPHAN`，
   修好可见范围与字段读之后 v3 只剩 **4** 条 ⇒ **64 条是装置缺陷造成的假阳性**；
   剩下 4 条 = 1 条门禁真值 + 1 条已授权常量 + 2 条"HEAD 就已孤儿"）。
6. **删后重扫**：孤儿集合 = §三 的 9 项，**没有新增** ⇒ 证实删除 `moneyOf` 未连带出新的孤儿。

扫描范围：`git status --porcelain` 里 **W2 全链路的 14 个 main 源文件**（不只是任务书点名的 4 个），
确保"信用市场删除足迹"整条都扫过。

## 五、真命令与结果

| 命令 | rc | 关键输出 |
|---|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（正常）。★ 事后核对 mtime：19 个已改文件里**只有 `MarketSettlement.java` 的 mtime 变新（04:09:37）**，其余 18 个仍是 02:34~03:50 的作者时间 ⇒ **本次 spotless 只重写了我的目标文件**，没有把 W2 工作树的其余改动格式化（AGENTS §五.1 那条"全仓工具漏文件/污染他人 diff"的风险已排除）。 |
| `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`，Reactor **16/16 SUCCESS**（EconomySimos 2.495 s、SimosApp 2.057 s），总 7.831 s。 |
| `tools/mvn-lock.sh -Dspotbugs.failOnError=false spotbugs:check -pl simos-economy`（**只读自证**） | **0** | `BugInstance size is 0` / `Error size is 0` / `No errors/warnings found` ⇒ 门禁那条 UPM 消失。 |
| `git status --porcelain` | — | **21 行 = 19 M + 2 ??**，与开工时**逐条一致**（我的改动落在原本就 M 的 `MarketSettlement.java` 里）；账本在 `.superpowers/sdd/.gitignore`（`*`）下，**不进 git 状态**。 |

### 只读 SpotBugs 自证的证据强度边界（★★ 非门禁等价）

- **aux classpath 走 `~/.m2` 旧 SNAPSHOT（实测，非推测）**：我的报告里 14 条 `AuxClasspathEntry` 全是
  `/home/cna/.m2/repository/io/mosire/...`（`simos-economy-api` 那份 jar 的 mtime = **10-07 04:11**，**早于 W2**）；
  而控制方 `clean verify` 的 XML 里 aux 是**反应堆内**的 `simos-*/target/*.jar`。⇒ 二者**不是同一装置**。
- 对**本条判定**无害：`moneyOf` 只触 `MarketRound.householdMoney`（本模块内）与 `CurrencyId`（economy-api 的 id，形状未变），
  陈旧 aux 不影响"它有没有被调用"这一结论；且 0 命中与"门禁原本只有这 1 条"**互相印证**（差值恰为被删方法）。
- 但**不能**据此宣称"本模块 SpotBugs 已全绿"：`effort=More/threshold=Low` 的完整口径、enforcer、spotless check、
  以及**其余 15 个模块**都没跑；门禁等价结论仍归控制方的 `verify`。
- ★ **`target/spotbugsXml.xml` 已按任务书要求还原**：我的 `spotbugs:check`（尽管传了
  `-Dspotbugs.outputDirectory=/tmp/spotbugs-out`，`check` 目标仍写了默认输出目录）**覆盖**了它 ⇒ 先备份、
  跑完**已 `cp` 还原**，`md5 = 7c494222eee97d5841ba817124ab032b` **与原文件逐字节一致**（已复核）。
  我的那份 0 命中报告留在 `/tmp/spotbugsXml.economy.after-fix5.xml`，备份在 `/tmp/spotbugsXml.economy.before.xml`。

## 六、偏离记录

1. **自扫范围比任务书点名的大**：任务书 §1.3 点名 `MarketSettlement.java` / `EconomySettlement.java`（+ §1.4 的
   `MarketReport`/`MarketReadout`），实扫 **W2 全链路 14 个 main 源文件**（含 `SimosToolSource`/`CatalogTool`/`MarketZone`/
   `DebtValuation`/`HouseholdValuationBook`/两个新文件等）。**只扩大只读扫描，不扩大改动**。
2. **删除范围严格小于"零调用"判据**：§三 的 9 项虽满足"全仓零调用"，但因"非本批所致 / 有具名入口意图 / 涉公开面"而
   **保留待裁定**，未按 §一.11 自行扩大删除。★ 这是**刻意的 fail-closed 选择**：任务书 §1.3 的判据限定
   "信用市场删除后变成孤儿"，且明令"不确定的保留并列为待裁定，不要猜"。
   ★ 若控制方按 §一.11 裁定"凡零读者一律删"，则 §三 的 1~7、9（8 项）可当场删；**第 8 项 `withdrawFromTreasury` 建议无论如何保留**
   （javadoc 与 2026-10-08 报告都写明它是 P7/GM 的待接线门面）。
3. **未改 `src/test/**`**：本批的删除使"零测试读者"这一事实保持成立（删前删后测试都零引用）；未新增/未放宽任何断言。
4. **未跑任何测试**（硬纪律）：`moneyOf`/`MARKET_PRICE_FLOOR_MILLI` 均零读者 ⇒ **净数值影响 = 0**，
   本批**不改变任何数值行为**、**不影响任何既有断言**、**无受影响的硬编码字面量**（给测试 Agent 的输入清单 = 空）。

## 七、未完成 / 未验证项（如实记）

1. ★★ **门禁未跑、也不能跑**：`clean verify`（SpotBugs 全模块 + enforcer + spotless check + 全部测试 + 前端门禁）
   归控制方；`test`/`verify`/`package`/`clean` 一字未跑（硬纪律）。
2. ★ **只读 SpotBugs 自证非门禁等价**（aux classpath 走 `~/.m2` 旧 SNAPSHOT，见 §五边界）⇒ 不能当"全仓 SpotBugs 绿"。
3. **§三 的 9 项待裁定**（7 个 `package-private` 死方法 + 2 个常量）：本批只**具名上报**，未删。
   ★ 其中"门禁看不见 `package-private` 死方法"是**结构性缺口**，建议控制方决定是否另开一批。
4. **未验证 `hasEffectivePrice` 等被删 javadoc 引用到的符号**是否仍有读者 —— 与本次删除无关（只删了描述性注释文本），
   `compile` 通过即证明无编译期引用；但**"javadoc 里 `{@link}` 是否断链"未跑 javadoc 插件验证**（门禁外）。
5. **`git commit` 未做**（控制方审后提交）；`src/test/**`、其他模块、`docs/**`、`AGENTS.md` 一字未改。
6. **`/tmp/orphan_scan3.py` 与 `/tmp/*.xml` 是临时装置**，不在仓内、不随批提交（复现命令见 §四/§五）。

---

# W2-fix6（历史孤儿清理：控制方裁定）

> 责任区：按控制方裁定，把 W2-fix5 §三 报上来的「HEAD 就已零读者」9 项里的 7 项（**实为 8 个符号**：
> 6/7 参两支算两条）删掉，保留 1 项计划内入口。**只改 `src/main`**；未跑 `test`/`verify`/`package`/`clean`；
> 未 `git commit`；未碰 `src/test/**`、其他模块、`docs/**`、`AGENTS.md`。本节**追加**，不改写上文（§五.4 留痕）。
> 结构：① 裁定 vs 实际 ② 连带清理（含一处**连带孤儿**与一处**裁定前提被证伪**）③ 保留项注释原文
> ④ 判据装置 ⑤ 真命令与结果 ⑥ 偏离记录 ⑦ 未完成/未验证项。

## 一、裁定清单 vs 实际（**逐条 file:line 核对后再删**）

| # | 裁定 | 删前 file:line（本批实测） | 零读者复核（自写签名感知扫描，1616 个 `.java`，排除 `.claude/`） | 处置 |
|---|---|---|---|---|
| 1 | 删 `MARKET_CROSS_REGION_SETTLEMENT_IMMEDIATE` | `MarketSettlement.java:245`（javadoc 241-244） | 读者 **0**；原始 grep 全仓只剩"声明 + 类注 `{@code}`"2 处 ⇒ **没发现读者** | **已删**（含其 javadoc）★ 孪生体 `MarketReport.CROSS_REGION_SETTLEMENT_IMMEDIATE`（`MarketReadout:401` 真读）**一字未动** |
| 2 | 删 `@Deprecated LOAN_INCOME_MULTIPLE_PER_MILLE` | `EconomySettlement.java:343`（javadoc 326-341 + `@Deprecated` 342） | 读者 **0**（main+test）⇒ **没发现读者** | **已删** ★ `DebtCapacity.java:82` **没有同款别名常量**（那里只是一句 javadoc 叙述，别名本体只此一处）⇒ 该 javadoc 按失实叙述修（§二.2） |
| 3-4 | 删 `settleOneDay` 6 参 / 7 参 | `EconomySettlement.java:1010` / `:1029` | 各 **0**：全仓 `settleOneDay` 代码命中只有声明 1010/1029/3717 与调用 1017/1037（两处都在被删方法体内）⇒ **没发现读者** | **已删**（含 javadoc）★ 但 8 参 `:3717` 因此变孤儿 ⇒ §二.1 |
| 5 | 删 `householdKeysOf(Map,ProductionUnitId,Map)` | `EconomySettlement.java:6580`（javadoc 6557-6579） | **0** ⇒ **没发现读者** | **已删** |
| 6 | 删 `householdKeysOf(ProductionUnitId,SettlementIndex)` | `EconomySettlement.java:6604` | **0** ⇒ **没发现读者** | **已删** |
| 7 | 删 `legacyGrainDebtId(HouseholdId,HouseholdId)` | `EconomySettlement.java:8432`（javadoc 8425-8431） | **0**（另 3 处命中都是 `{@link}` 文本）⇒ **没发现读者** | **已删** |
| 8 | ★ **保留** `withdrawFromTreasury(...)` | `EconomySettlement.java:9574`（改后 :9407） | 0（预期：计划内待接线） | **保留 + 加"为什么还活着"**（原文见 §三） |
| 9 | 删 `emptyMoneyAccountsFor(...)` | `EconomySettlement.java:10505`（javadoc 10498-10504） | **0** ⇒ **没发现读者** | **已删** ★ 其 javadoc 自述"服务 `settleOneDay` 短重载与 `EconomyDayStepper` 短构造器"**本来就是错的**：`EconomyDayStepper(base,accounts)` 收的是调用方载入的 `AccountSession`，从不调它 |

**结论：9 项里没有任何一项发现读者**（main / test / `::` 方法引用 / 反射字符串全为零）⇒ 未触发"有读者就停手"。

## 二、连带清理（两件）

1. ★★ **连带孤儿：`settleOneDay` 8 参 `:3717`（裁定前提被证伪）** —— 裁定书写"6/7 参只转发到**活的** 8 参 `:3717`"，
   但**实测 8 参的读者只有被删的这两支**（证据：全仓 `settleOneDay` 的代码命中 = 声明 1010/1029/3717 + 调用 1017/1037，
   两处调用都在被删方法体内；`settle(...)` 走的是 `settleOneDayInto`，从 `settleOneDay` 一路 grep 不到）。
   ⇒ 删掉 6/7 参后它**变成新孤儿**，且是 `package-private` ⇒ **SpotBugs 结构性看不见**（fix5 已报的缺口）。
   按任务书 §1.3「让别的…符号变成新孤儿 ⇒ 一并删并点名」：**已删 `:3713-3741`**（javadoc + 方法体；其 javadoc 自述
   "旧签名（测试/单模块调用方）"，正属 §一.11 点名的"为旧设计/旧测试留的入口"）。
   ★ 若控制方仍认为它是 W3 的计划内入口：恢复成本 = 一个 hunk；本批按 §一.11 处置并在此点名。
2. **因删除而失效的注释/javadoc**（本批已改的 3 个文件内一律修到与代码一致；**不在本批文件里的只上报**，见 §六.3）：
   - `{@link}` 由"有效"变"悬空"共 7 处 → 改指**活符号**：`#legacyGrainDebtId` → `DebtContractId#idOf`
     （`:111`/`:6749`/`:6796`）、`#householdKeysOf` → `SettlementIndex#householdsOf`（`:149`/`:5443`/`:6630`）、
     `#settleOneDay` → `{@code settleOneDayInto}`（`:883`，原文"上面 {@link #settleOneDay}"的"上面"已不存在）。
   - **失实判断句 3 处**（把删掉的符号说成还活着）→ 修为与代码一致：`DebtCapacity.java:81-83`（"旧的公开常量现在是
     `@Deprecated` 别名，生产代码只读这里"）、`EconomySettlement.java:7076`（"旧 `LOAN_INCOME_MULTIPLE_PER_MILLE`
     只留 `@Deprecated` 别名"）、`MarketSettlement.java:105`（类注里 `{@code ... = true}`）。
   - **fix5 §三 已点名的坏链接 2 处**（`:403`/`:434`，指向**从来不存在**的 5 参/4 参签名）：因它指向的方法名**整族被删**，
     顺带改为活入口（`settleOneDayInto` 重载 / `EconomyDayStepper` 构造器）；同时**如实标注**：那两句点名的
     `EconomySettlementTest.famineKills…` 与 `EconomySowingTest.drawingBeforeOrAfter…` **随 R3a 已不在测试树里**
     （实测 `ls` 无这两个文件；`git log -S` 命中 `b243e3d9`）⇒ 原文"逐值钉住（不是死分支）"是**先存的失实**，
     已改成"当前没有逐值用例钉住（具名缺口）"。★ 这是新发现，见 §七.4。
   - 本批 3 个文件内的**纯叙事**提及（`{@code settleOneDay}` 等）也一并改指 `settleOneDayInto`：`:944`/`:6476`/`:6859`/`:9783`/`:9793`
     与 `MarketSettlement.java:1420`。
3. **没连带出别的孤儿**：被删代码引用到的全部符号逐个数过，**代码级读者仍 > 0**（下面括号里是原始 grep 命中数，
   含注释/`{@code}` 提及，只是量级参照）—— `MarketTopology.singleHex`（39）、`SettlementIndex.householdsOf`（30）、
   `DebtContractId.idOf`（28，全在本文件外）、`DebtUnit.commodity`（26）、`DebtTerms.legacyDefault`（20）、
   `BORROW_RATE_PER_MILLE_PER_CYCLE`（3，含声明）、`GRAIN`（28）、`new EconomySession`（14）、
   `Comparator.comparing`（322）、`householdKeysOfLot`（private，读者 `:4736`）。
4. **import**：3 个文件的 import 逐条数"代码内引用次数" ⇒ **零引用 import = 0**（装置 `/tmp/w2fix6_imports.py`）。

## 三、保留项注释原文（加在 `withdrawFromTreasury` javadoc **顶部**，一行含消费者 + 批次）

```
   * ★ <b>为什么还活着（W2-fix6 裁定保留，不是孤儿）</b>：计划内待接线入口 —— 消费者 = **P7 的 GM 编辑工具**（回笼写口） 与日结算/命令层的同包代码（批次：P5
   * 立口、P7/GM 工具批接线；接上之前零自动调用方，属预期）。
```

（spotless 重排后仍是 javadoc 的第一段；其下原有的"★★ P5：回笼写口的包内入口"段一字未改。★ `target/` 下无残留产物问题：本批只 `compile`。）

## 四、判据装置（自写，落 `/tmp/w2fix6_scan.py`，不落仓）

签名感知零读者扫描（**不沿用** fix5 的 `/tmp/orphan_scan3.py`，独立重写以便交叉验证）：

1. 只扫本仓真实源码树 `simos-*/src/**/*.java`（1616 个）；**排除任何含 `.claude/` 的路径**（陈旧检出副本会冒充读者）；
   另实测 `.superpowers/sdd/**` 的 474 个证据 `.java` 对这 8 个符号**零命中**，故不影响结论。
2. 先剥注释与字符串字面量（块注释**必须保换行**——v1 的缺陷：不保换行会让后面所有行号整体前移，我用它误读了 100 行以上，
   修好后行号与 fix5 清单**逐条对上**：245 / 343 / 1010 / 1029 / 6580 / 6604 / 8432 / 9574 / 10505）。
3. 方法按 **(名字, 实参个数)** 匹配；`<...>` 泛型实参表里的逗号先掩掉（v2 缺陷：`LinkedHashMap<A, B>` 让 6 参方法算成 7 参）；
   声明行判定**从严**（`return settleOneDay(` 曾被误判成声明，方向上会**藏起读者**，已修）；实参个数解析不出时报 `UNPARSED★` 而不静默跳过。
4. **装置自证（2/2 已知真值 + 1 个已知死值）**：同一次运行里，`MarketSettlement.MARKET_ADAPTIVE_PRICING_ENABLED`
   报 **3** 个读者、`DebtCapacity.CREDIT_F_MULTIPLE_PER_MILLE` 报 **2** 个读者（删别名后重扫变 **1**，差值恰为被删那一行）；
   fix5 已确认的孤儿（`moneyOf`、`MARKET_PRICE_FLOOR_MILLI`）在本装置里同样零读者。
5. **删后重扫**：8 个目标全部"未识别到声明行"（= 已删）且读者 0；保留项 `withdrawFromTreasury` 声明仍在 `:9407`、读者 0。
6. ★ **试过但未采信的装置**：用 `javap -p` 从编译产物列成员再数读者的全类扫描（`/tmp/w2fix6_members.py`）——
   **噪声过大**（javap 擦除泛型后实参个数与源码调用点对不上，虚报 53/48 个"零读者成员"）。抽查 4 个虚报项
   （`writeOffDebtsForDeaths` 读者 `:4770`、`accumulateMandateFill` 读者 1946/1963、`householdStockOf` 读者 7491/7514、
   `reduceUnmet` 读者 6696）⇒ 全部是装置缺陷造成的假阳性，**故不采信其输出**，只保留上面的源码级判据。

## 五、真命令与结果

| 命令 | rc | 关键输出 |
|---|---|---|
| `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（正常）。★ 事后核对 mtime：工作树 22 行里**只有本批的目标文件 mtime 变新**（`EconomySettlement`/`MarketSettlement` = 04:18:52），其余 19 个 W2 文件仍是 02:34~03:50 的作者时间 ⇒ **spotless 只重写了我改过的两个文件**，没有污染他人 diff（AGENTS §五.1 那条风险已排除）。 |
| `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`，Reactor **16/16 SUCCESS**（EconomySimos 4.228 s、SimosApp 2.780 s），总 7.940 s。 |
| `git status --porcelain \| wc -l` | — | **22 行 = 20 M + 2 ??**，与开工时 21 行（19 M + 2 ??）**差 1**，就是新变的 `DebtCapacity.java`（裁定预期内："可能 DebtCapacity.java"）；`src/test/**` 的 7 个 M 全是 W2 先存改动（mtime ≤ 03:47:54，早于本批 04:12 开工）。 |
| 空白等价 hunk 检查（`git diff -U0` + 归一化比较） | — | 两个文件 90 / 179 个 hunk 里**纯重排 hunk = 0** ⇒ 本轮 spotless 没做"与内容无关的重排"。 |

## 六、偏离记录

1. **删了裁定清单外的 1 个符号**（`settleOneDay` 8 参 `:3717`），依据是任务书 §1.3 的连带清理条款 + §一.11；
   **裁定书"它是活的"这一前提被证伪**（证据见 §二.1）。这是本批唯一超出清单的删除，**点名上报**，可一 hunk 恢复。
2. **动了 `DebtCapacity.java`**：裁定书说"若有同款别名…一并删"。实测**没有同款别名**；动它是因为它的 javadoc 把已删的别名
   说成"生产代码只读这里之外的那个别名"，属**删除后的失实叙述**（§二.2）。**只改 javadoc 一句话，零代码改动**。
3. **5 处纯叙事提及未改**（不在本批的 3 个文件里，避免把 diff 扩到 W2 之外的 3 个文件）：`EconomyDayStepper.java:35`/`:387`、
   `ProductionLedger.java:630`、`EconomyStateBuilder.java:55`/`:311` 里的"旧 `{@code EconomySettlement.settleOneDay}`"。
   它们不是 `{@link}`（不产生断链），指的是"日结算那一步"这个概念（现由 `settleOneDayInto` 承担）⇒ 留作具名待办。
   另有先存且已自标"（R3a 已删除）"的历史叙述（`EconomyData:187`、`SettlementIndex:467`/`:520`、`HouseholdEconomy:18`）**按 §五.4 留痕不动**。
4. **未跑只读 SpotBugs 自证**（任务书为可选）：本批删的全是 `package-private` 成员，SpotBugs 的 `UPM` 结构性看不见它们；
   跑它只能证明"没变坏"，不能证明本批目标。⇒ 省下这一轮，如实记为未跑。

## 七、未完成 / 未验证项（如实记）

1. ★★ **门禁未跑、也不能跑**：`clean verify`（SpotBugs 全模块 + enforcer + spotless check + 全部测试 + 前端门禁）归控制方；
   `test`/`verify`/`package`/`clean` 一字未跑（硬纪律）。
2. **未写/未跑任何测试**；本批删除的符号**全部零读者** ⇒ **净数值行为影响 = 0**、**不影响任何既有断言**、
   **无受影响的硬编码字面量**（给测试 Agent 的输入清单 = 空）。`compile` 只覆盖 `src/main`；
   `src/test` 若有编译期引用只有门禁能发现 —— 但我已用源码级扫描覆盖 test 树（零读者含 test）。
3. **未验证 javadoc 断链**（`maven-javadoc-plugin` 全仓 pom 里**没有**，见 §六.3 的 grep）⇒ 本批的 `{@link}` 改指只是**静态核对**过目标存在
   （`DebtContractId#idOf` 唯一、`SettlementIndex#householdsOf` 唯一、`DebtCapacity#headroom()` 存在），**没跑 javadoc 插件验证**。
4. ★ **新发现（不属本批，具名上报）**：`:403`/`:434` 两句 javadoc 点名的
   `EconomySettlementTest.famineKillsTheStarvationShareOfThoseWhoGoHungryTheWholeCycle` 与
   `EconomySowingTest.drawingBeforeOrAfterTheDaysMealChangesWhatCanBeSown` **都不在测试树里**（随 `b243e3d9` R3a 删除）
   ⇒ 非 0 致死率与"吃饭优先"两种次序**当前没有逐值用例**。本批只把注释改成如实（不新增测试，硬纪律）。
5. **`git commit` 未做**（控制方审后提交）；`src/test/**`、其他模块、`docs/**`、`AGENTS.md` 一字未改。
6. **`/tmp/w2fix6_scan.py` / `w2fix6_imports.py` / `w2fix6_members.py` 是临时装置**，不在仓内、不随批提交。

# W2-fix7（D1 回归 + D3 跌侧 + D4 成交额口径 + D5 日志字段）

- **依据**：验收报告 `.superpowers/sdd/2026-10-11-w2-acceptance/report.md` §3（D1/D3/D4/D5）+ 约束设计书
  `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.10 §2.1 / §7**（控制方裁定）。
- **范围**：只改 `simos-economy/src/main`（9 个文件）；`src/test/**`、`docs/**`、`AGENTS.md`、其他模块**一字未改**；**不 commit**。

## 一、D1（★★ 回归，必修）：three-powers `539→540` 的 E14 fail-closed 400

### 1.1 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据 | 结论 |
|---|---|---|
| 1 | `ProductionSettlement:485-495`（改前）`requireProducibleCommodities` | E14 判"规则指名的商品必须在该产业的产出表里"，**在任何数量计算之前**判死。D1 的租腿（`FIXED_IN_KIND_RENT` grain）挂在产出表只有 `[haul]` 的 `trade@-3_3` unit 上 ⇒ 必抛。 |
| 2 | `ModeMigrationSettlement:1741-1783`（改前 `buildMigrationRelation`）走 `RegimeRelations.defaultRelation(TENANT …)` | **迁移路径直接照抄模板关系**，没有 `EconomyEnterpriseSettlement.rentCommodity`（B3）那道"租金腿商品必须能产"的判据 ⇒ 它**能造出 E2 自动组织路径明确拒绝落盘的关系**。这是 D1 的**结构性入口**（不是 W2 的 diff 造成的，是 S-10 改了轨迹后第一次走到）。 |
| 3 | `RegimeRelations:551-563` `tenantRules()` | 租佃模板**只有一条** `FIXED_IN_KIND_RENT(GRAIN, 20,000,000)`（常量，看不见产业）⇒ 目标产业不产粮时必不相容。 |
| 4 | `EconomySettlement:8712-8830` `harvest` → `ProductionSettlement.settle` | 三条 tenancy mode（fixed_kind/share/cash）在迁移里共用同一份 tenant 模板 ⇒ **任一 tenancy 迁移到不产粮的格都会卡死世界**（不止 cash）。 |
| 5 | `checkpoint rev 15`（= day 420，验收存档 `store-tp600seg`）首次出现 `hh-mig--3_3-tenancy_cash-…` | 该户在 **390→420** 段被建出来（旧代码），540 段第一次收获时 E14 抛 —— 与验收报告的"day 540 首次走到该组合"一致。 |

### 1.2 落点（file:line，前后各一行）

**(a) 迁移决策处加约束（裁定 ①）** —— `ModeMigrationPolicy.java`（`buildTargets` 内，新目标分支）

```
        HouseholdId existing =
            bestExistingTarget(source, hex, mode, modeByHousehold, positions, householdEconomies);
+       // ★★★ W2-fix7 / D1①（控制方裁定 v3.10 §7）：目标生产方式的实物腿商品拿不到 ⇒ 该户不迁入这个 mode
+       if (existing == null) {
+         String refusal =
+             inKindLegUnavailableReason(base, source, mode.id(), hex, accounts, markets, topology);
+         if (refusal != null) {
+           excluded.add(new PrimaryModeRanking.Excluded(mode.id(), hex, refusal));
+           if (weight > 0L) { EventLog…("MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED", …); }
+           continue;
+         }
+       }
        long weight =
            Math.max(0L, Math.subtractExact(prospect.netPerLaborScaled(), currentPerLabor));
-       if (weight <= 0L) { continue; }
-       HouseholdId existing =
-           bestExistingTarget(source, hex, mode, modeByHousehold, positions, householdEconomies);
```

判据本体（包内纯函数，可被探针直接钉住）：

```
+  static String inKindLegUnavailableReasonOf(IndustryId, Industry, List<CommodityId> inKind,
+      Map<CommodityId, Long> householdStock, MarketTable market, RegimeId regime) {
+    for (CommodityId commodity : inKindCommodities) {
+      if (industry.outputPerUnit().containsKey(commodity)) continue;   // ① 本产业能产
+      if (householdStock.getOrDefault(commodity, 0L) > 0L) continue;    // ② 该户手头有库存
+      if (market != null && market.hasPrice(commodity)) continue;       // ③ 目标格市场交易它（价 0 也算）
+      … 具名理由（商品 / 产业 / 产出表 / stock / market）
+    }
```

- 供它读"模板实物腿商品"的两个新拼写点（**都只有一处**）：
  `RegimeRelations.inKindCommodities(RegimeId)`（新，保序去重；`simos-economy/.../model/RegimeRelations.java:626+`）、
  `ModeMigrationSettlement.migrationTemplateRegime(...)`（`private` → **包内可见**，理由写在该方法注里；
  **不许在策略侧另写一份 mode→模板映射**）。
- **只判"新建"目标**：并入既有户不生成新关系（`ModeMigrationSettlement:414` 的 `if (newTarget && !DISPLACED)`）。

**(b) 实物租腿改三级支付链（裁定 ②）** —— `ProductionSettlement.java`

```
-      long available =
-          Math.max(0L, facts.net().getOrDefault(commodity, 0L) - paidOf(paid, commodity));
+      long available = availableOf(rule, facts, paid, commodity);
       long paidNow = Math.min(due.getAsLong(), available);

+  private static long availableOf(CompensationRule rule, Facts facts, Map<CommodityId, Long> paid,
+      CommodityId commodity) {
+    long fromUnitOutput = Math.max(0L, facts.net().getOrDefault(commodity, 0L) - paidOf(paid, commodity));
+    if (rule.type() != RuleType.FIXED_IN_KIND_RENT) return fromUnitOutput;   // 其余档逐字不动
+    long fromHouseholdStock = Math.max(0L, facts.payerStock().getOrDefault(commodity, 0L) - paidOf(paid, commodity));
+    return Math.max(fromUnitOutput, fromHouseholdStock);
+  }
```

- `Facts` 新增组件 `Map<CommodityId, Long> payerStock`（付方主体**结算前**的实物库存合计；走同一份 `requireQuantities` 校验）。
  调用点 `EconomySettlement.harvest` 在 `creditOutput`（把本周期净产计进家户账）**之后**取它 ⇒
  **①"本 unit 产出" = ②"家户库存" 的同一份数的一级**，与路由 `payerShares` 的可付量口径逐值一致（不会出现"结算要得多、路由付不出"）。
- ③ **具名缺口**：`paidNow < due` ⇒ 进 `RuleSettlement.owed`（S4 读数）→ `harvest` 的 `IN_KIND_RENT_CHAIN_GAP`（**INFO**，默认档可见）
  → 关账处 `capitalizeArrears` 折成债权（实测 day540 `CAPITALIZE_ARREARS capitalized=1 unresolved=0`）。**不静默 0、不抛。**
- 成功付清的租腿另记 `IN_KIND_RENT_CHAIN`（DEBUG，含 应付/实付/产出/库存/是否可产）。

**(c) E14 保留（裁定 ③）** —— `ProductionSettlement.requireProducibleCommodities`

```
    for (CompensationRule rule : ordered) {
      if (rule.commodity().isEmpty()) continue;
+     if (rule.type() == RuleType.FIXED_IN_KIND_RENT) continue;   // ★ D1②：租腿走三级支付链
      CommodityId commodity = rule.commodity().get();
      if (!facts.outputPerUnit().containsKey(commodity)) { throw … }
```

**(d) 执行期兜底（裁定 ①/③ 之间的缝）** —— `ModeMigrationSettlement.buildMigrationRelation`

- 计划期产业（`industriesForMode` 的第一个）与执行期实际选中的产业（第一个租赁资产凑得齐的）**可能不同** ⇒ 执行期再判一次：
  **非租腿**的实物腿商品不在该产业产出表里 ⇒ **不落这条 E14 非法关系**，退回`具名`空规则（`fallbackMigrationRelation` + ledger 审计 +
  INFO `MIGRATION_RELATION_E14_ILLEGAL_FALLBACK`）。原装配逻辑整体搬进 `planMigrationRelation`（逐字未改）。
- 租腿**不在**这条兜底里（它由三级支付链承接，欠租不是配置错误）。

### 1.3 真回执（真世界，一次一个实例、一次一个 Maven；未覆盖运行中服务的 jar —— 开工 `pgrep -af java` 只有自己的 shell）

| 项 | 值 |
|---|---|
| jar | `/tmp/w2fix7/simos-app-w2fix7.jar`，md5 **`acd8e7442e55c1e44537f081391269d6`**（`tools/mvn-lock.sh -DskipTests package -pl simos-app -am`，BUILD SUCCESS 14.4 s） |
| 装置 | 验收 Agent 的 `runner.py`（只读复用，`/tmp/w2acc/runner.py`）+ 本批自建 store/端口 10570 / 10580 |
| 命令 A | three-powers 新 store，`advance 0→600` 单次（`-Dsimos.economy.logLevel=INFO`） |
| **回执 A** | **HTTP 200**、`{"result":"committed","ref":{"branch":"main","revision":2}}`、**Δ=1**（rev 1→2）、**35.180 s**；`revisions` 表 = 创世 + 1 |
| 命令 B | 从验收存档的 **day 510**（rev 18，`store-tp600seg` 的副本）**续跑 `510→540`**（该段在验收里是 400）`logLevel=DEBUG` |
| **回执 B** | **HTTP 200**、rev **18→19**（Δ=1）、**6.126 s**；`failedAt=null` |
| **被判"不迁入"的具名样本** | **本世界无样本**（见 §三-1：three-powers 三个市场区都挂着 `grain` 行（day390 实测 `prices.grain=0` = 免费交易）⇒ "市场可得"成立 ⇒ 闸门不触发）。闸门本体由合成探针逐值钉住（§一.4-B） |
| **day 540 的具名缺口（原文）** | `event=IN_KIND_RENT_CHAIN_GAP origin=economy-settlement originKind=tick day=540 unit=unit-trade@-3_3-HOUSEHOLD-hh-mig--3_3-tenancy_cash-hh--3_3-urban-middle_peasant-0 industry=trade@-3_3 commodity=grain payee=HOUSEHOLD:hh--3_3-urban-landlord dueMilli=20000000 paidMilli=0 gapMilli=20000000 unitOutputMilli=0 payerStockMilli=0 commodityInIndustryOutput=false why=in-kind-rent-chain:unit-output-then-household-stock-remainder-is-a-named-gap (该商品不在本产业产出表里)` —— **正是验收报告 §3 D1 那条 400 的同一个 unit/同一户/同一个量** |
| 世界连续性 | 该租腿从此每关账轮照结（缺口具名 + 资本化成债权），**世界不再卡死** |

### 1.4 判据自检（合成夹具，直接调真代码；装置在 `/tmp/w2fix7/D1Chain`、`/tmp/w2fix7/D1Gate`，不落仓）

**A) 三级支付链（`ProductionSettlement.settle` 纯函数重载；产出表 = `[haul]`、租腿 = grain、应付 500）**

| 情形 | 应付 | 实付 | 欠 | 转移 | 判定 |
|---|---|---|---|---|---|
| A 产出 = 0 且库存 = 0 | 500 | **0** | **500** | 0 条 | ③具名缺口：**不抛、不静默**（改前这里 E14 抛） |
| B 产出 = 0 且库存 = 700 | 500 | **500** | 0 | 1 条 `{grain=500}` | ②**家户库存**那一级真的付款 |
| C 产出 = 500（能产） | 500 | **500** | 0 | 1 条 `{grain=500}` | ①本 unit 产出那一级 |
| D 对照：同商品挂 `OUTPUT_SHARE` | —— | —— | —— | —— | **仍 fail-closed 抛**（E14 保留；消息原文含"规则指名的商品不在该产业的产出表里（E14）"） |

**B) 迁移决策闸门（`ModeMigrationPolicy.inKindLegUnavailableReasonOf`；产业 `trade@-3_3` 产出 `[haul]`、模板实物腿 `[grain]`）**

| 情形 | 结果 |
|---|---|
| ①产业能产（farm 产出 grain） | null（可迁） |
| ②该户有库存（grain ≥ 1 毫） | null（可迁） |
| ③市场有这一行（价 7） | null（可迁） |
| ③′市场有这一行（**价 0 = 免费交易**） | null（可迁） |
| ④三者都不成立 | **拒**：`in-kind-leg-unavailable:grain(industry=trade@-3_3 outputs=[haul],stock=0,market=false):regime=tenant` |
| ④′目标格没有市场（null） | **拒**：`…,market=none):regime=tenant` |

## 二、D3（跌侧破 α）、D4（成交额口径）、D5（日志 day）

### 2.1 D3 落点与逐值表

`MarketSettlement.adaptiveNextPrice`（S > D 支）+ 新增 `multiplyByPerMilleCeiling`：

```
-    long next = multiplyByPerMille(price, Math.max(0L, 1000L - step), 0L);
-    return Math.max(0L, next);
+    long multiplicative = multiplyByPerMilleCeiling(price, Math.max(0L, 1000L - step), 0L);
+    return Math.max(0L, Math.min(Math.max(0L, price - 1L), multiplicative));
```

**逐值（真 Java 调用，非推演；`/tmp/w2fix7/D3check`）**：

| p | step=50 → p' | 实现跌幅‰ | α=0 → p' | p=0 → p' |
|---|---|---|---|---|
| 1 | **0** | 1000 | 1 | 0 |
| 2 | **1** | 500 | 2 | 0 |
| 19 | 18 | 52 | 19 | 0 |
| 20 | **19** | **50** | 20 | 0 |
| 56 | **54** | 35（≈ 判据的 −35.7‰） | 56 | 0 |
| 1000 | **950** | **50** | 1000 | 0 |

- `p ≥ 20` 的实现跌幅**恒 ≤ α**（p=20 恰 50‰、p=56 为 35‰、p=1000 恰 50‰）；
  `p < 20` 因"**至少降 1 毫**"必然 > α（p=19 → 52‰、p=2 → 500‰）—— 与涨侧 `max(p+1, …)` 的
  "p<20 涨幅必然 > α"**逐字对称**，是整数毫位的必然，不是失控（v3.10 §2.1 的字面算式即如此）。
- `α=0`（`step ≤ 0`）⇒ **不动**（v3.8 口径，缺货/滞销两侧同款）；`p=0` ⇒ 0；`D=S` ⇒ 不动（实测 p=56 → 56）。
- 对照：涨侧 `p=56 → 58`、`p=1 → 2`（既有的最小步进，未改）。

### 2.2 D4 落点（铁律 5 三件套点名）+ 旧 turnover 的读者改动清单

**(a) 持久组件形状**（`simos-economy/.../model/MarketRoundStats.java`）：record 由 7 组件 → **10 组件**，
三段窗口**每段两栏**（名义 / 现金）：

```
 lastRoundDay, lastRound, lastRoundTurnoverMilli,  lastRoundCashTurnoverMilli,
 cycleToDate,  cycleTurnoverMilli,  cycleCashTurnoverMilli,
 previousCycle, previousCycleTurnoverMilli, previousCycleCashTurnoverMilli
```

- **口径**：`turnover*` = **名义货值** = `⌊Σ(量 × 单价) / 1000⌋`（整轮一次取整，与均价同源；全额货款，含赊购）；
  `cashTurnover*` = **实付现金** = `Σ goodsPaymentMilli`（改前 `turnover` 的口径，**逐值搬到新字段**）。
  两栏**不互相约束**（逐笔货款是 `⌈…⌉` ⇒ 小额轮里现金可略大于名义；实测 day540 三区都有此现象 ⇒ **不写"现金 ≤ 名义"守卫**）。
- **三件套**：
  ① **Codec**：`EconomyCodec` 对本组件走通用 Jackson record 绑定（该文件**零** `MarketRoundStats` 引用）⇒ 新增组件自动进线格式；
     **实测往返**：新 jar 写出的 checkpoint（`store-sw120/checkpoints/main/2.json`）被**同一新 jar 重新载入**并续跑 120→150 成功（rev 2→3），
     落盘原文两栏俱在（见下）⇒ 读写两侧都验过。
  ② **ChangeSet**：`EconomyChangeSet` 的第 N 组件 + `FieldDelta.diff/rebuild(base.marketRoundStats(), …, MarketZoneId::parse)` —— 形状未变（仍是 map 级 delta），
     组件的加减由 record 自身承载；`changed()` 覆盖不变。
  ③ **往返不变式**：既有 `EconomyRoundTripTest` / `EconomyCodecTest` 的 `MarketRoundStats` 夹具用**旧 7 参构造器** ⇒
     本批保留了一个**具名**便利构造器（现金栏 = 0，javadoc 明说"不建模现金腿的夹具"），**测试源码一字未改**、语义不静默
     （"整轮全赊购 ⇒ 现金 0"本身是合法状态）。
- **落盘原文（small-world day120，`store-sw120/checkpoints/main/2.json`）**：
  `single-region day=120 本轮 名义=901852 现金=1543 ｜ 本周期 名义=0 现金=0 ｜ 上周期 名义=1243879 现金=1607`
  ⇒ **现金栏 = 1543 = 验收报告 §3 D4 表里的旧 `lastRoundTurnoverMilli`（1,543）逐值相同**；名义栏 901,852
  （报告的量×价估算 ≈896,973，差 0.5% 来自 D3 改了价格路径 ⇒ 成交量 16,098,349 vs 16,017,382）。
- **INFO 日志两栏都给**：`MARKET_ROUND_STATS_RECORDED day=120 markets=1 commodities=5 tradedMilli=16098349 turnoverMilli=901852 cashTurnoverMilli=1543 cycleClosed=true`；
  `describe()`（DEBUG `MARKET_ROUND_STATS_DETAIL`）同样两栏。

**(b) 旧 `turnover` 的读者清单（逐个改动点）**

| 读者 | 改动 |
|---|---|
| `MarketSettlement.readRoundStats`（**写口**） | 新增 `cashTurnover` 累加（`Σ goodsPaymentMilli`）；`turnover` 改由 `tradedValue`（量×价）整轮取整算出 |
| `MarketRoundStats.withRound`（**唯一逐轮写口**） | 加 `cashTurnoverMilli` 参数；三段窗口两栏同步累加/轮转 |
| `EconomySettlement:2659-2720`（写状态 + INFO/DEBUG 日志） | 传两栏；INFO 加 `turnoverMilli` / `cashTurnoverMilli` 两个字段 |
| `MarketRoundStats.describe()`（日志面） | 三段各打"名义 + 现金"两栏（改前只打一栏 ⇒ D4 的误读形态） |
| `MarketRoundStatsBook.lastRoundTurnover / cycleTurnover / previousCycleTurnover` | 语义改为**名义货值**（javadoc 点名 D4）；新增 `lastRoundCashTurnover / cycleCashTurnover / previousCycleCashTurnover` 三个现金读口 + `lastRoundCashTurnoverByMarket` |
| `MarketReadout.lastRoundTurnoverOf` | 语义改为名义；新增 `lastRoundCashTurnoverOf` |
| `ApiViews.marketTurnoverReferenceView`（GUI `moneyOutputReference`） | **未改**：它不是 `MarketRoundStats` 的读者（自己从 `fill.goodsPaymentMilli()` 现算"逐 hex/世界成交额（现金）"）⇒ 属**另一处口径**，要与统计簿同名同义得另开一批（记在 §四-3） |

### 2.3 D5 落点

`EconomySettlement.repayDebts` 增参 `long day`（调用点在 `:2860`），**这一族事件全部补 `day`**（比裁定点名的三条多两条，同因）：

| 事件 | 级别 | 改动 |
|---|---|---|
| `DEBT_REPAY_ROUND` | INFO | `+ "day", day` |
| `DEBT_REPAY_RESERVE_POLICY` | DEBUG | `+ "day", day` |
| `DEBT_REPAY_MEDIUM_ORDER` | TRACE | `+ "day", day` |
| `DEBT_REPAY_DECISION` | TRACE | `+ "day", day`（验收报告 §3 D5 点名的第三条是它；一并补齐） |
| `DEBT_REPAY_UNPRICED` | TRACE | `+ "day", day`（同族，缺 day 则按天检索仍会漏） |

## 三、偏离记录（与裁定 / 任务书不一致之处，主动记）

1. **①的判据边界（我的实现口径，控制方可推翻）**：`householdStock` 取**迁移源户**手头的库存（裁定原文"该户"=被决策的家户），
   **目标格市场**取"该格所属市场的价目表里有这一行"（含 0 价 = 免费交易；依据 §2.1"有这一行 = 交易、没有 = 不交易" +
   债务式清算下"没钱也能买"）。★ 如实记一处**既有边界**：本仓迁移**不搬实物库存**（`ModeMigrationSettlement` 的 ①–⑥ 只搬人/钱/债/资产/劳动配额）
   ⇒ "源户有库存"放行的迁移，在**新建**目标户头一轮仍可能欠一轮租 —— 那一轮由 ③ 具名缺口 + 债权资本化承接。
   ★ **后果**：three-powers 的 `grain` 在每个市场区都有价（0 = 免费）⇒ **闸门在本世界不触发**，"世界不卡死"实际由 ② 三级支付链兜住（回执见 §一.3）。
2. **①的判据范围比裁定字面宽一格**：裁定只点名"**实物租腿**商品"，我按**关系模板的全部实物腿**判（含 `OUTPUT_SHARE` / `FIXED_IN_KIND_PER_LABOR`）。
   理由：E14 对**非租腿**档仍然 fail-closed（裁定 ③）⇒ 那些腿不可产时迁过去 = 下次 harvest 整段 advance 被拒（与 D1 同款的卡死），
   与"世界不许因这种不相容而卡死"的裁定总纲冲突。★ 执行期另有 (d) 兜底，两道一起才封住这条路。
3. **`MarketRoundStats` 保留了一个旧 7 参构造器**（现金栏 = 0）：只为让既有测试夹具**不改一字**仍能编译/通过（`src/test/**` 禁改）。
   它不写状态、不被生产代码调用、javadoc 明说不静默（见 §2.2a）。★ 若控制方认为这属于"兼容位"，删它会让两个既有测试类编不过（本批只报不修）。
4. **D3 的"至少降 1 毫"必然让 `p < 20` 的实现跌幅 > α**：这是 v3.10 §2.1 字面算式的直接后果（与涨侧 `+1 毫` 逐字对称），
   **不是实现偏差**；已在方法注里写明并给逐值表（§2.1）。
5. **`RegimeRelations.inKindCommodities` 顺带把 `ModeMigrationSettlement.migrationTemplateRegime` 从 `private` 放宽到包内可见**：
   为避免"mode→模板"出现第二份映射（同一件事两处拼写）。不放宽就得在策略侧抄一份映射表 —— 那是更坏的偏离。

## 四、会改变数值行为的清单（逐条具名）

1. **实物租腿的可付量从"本周期产出"扩到"本周期产出 + 家户库存"**（`ProductionSettlement.availableOf`）⇒
   租腿应付 > 本周期产出时，**先吃家户库存**（含货币？不 —— 货币腿另路），欠款读数（`RuleSettlement.owed`）与由此资本化的租金债权**双双变小**。
   ★ 影响面：所有带 `FIXED_IN_KIND_RENT` 关系的 unit（迁移建的 tenancy 户 + 资产规则建的实物租佃）。
2. **实物租腿不再因"商品不在产出表里"抛** ⇒ 这类世界从"整段 advance 400"变成"具名缺口 + 债权"（D1 的本体）。
3. **迁移计划会少掉一部分目标**：模板实物腿商品"产业不产 + 该户无库存 + 目标格市场不交易"时不再迁入该 mode
   （`ModeMigrationPolicy`）⇒ 迁移人数/方向、随迁钱债资产、后续产业产出都可能变（**three-powers 本世界未触发**）。
4. **执行期新增一条回退路径**（`MIGRATION_RELATION_E14_ILLEGAL_FALLBACK`）：非租腿实物腿不可产时，新户关系退回**空规则**
   （产出全归 operator，不再收租/给养）—— 只在计划期没挡住时走到（three-powers 未触发）。
5. **跌侧价格路径逐毫变化**（D3）⇒ 所有 `S > D` 的轮里，价格比改前**多留 1 毫**（跌幅变小）；进而影响后续成交/迁移/债务轨迹。
6. **统计簿两栏口径**（D4）：`turnover*` 由"现金"变"名义货值"（数值变大，day120 实测 1,543 → 901,852，×584）；
   `cashTurnover*` = 旧值。★ 这是**纯读数**，不参与定价/撮合 ⇒ **不改世界轨迹**；W4 若按"上周期成交额"排序，量纲随之改变。
7. **还债事件多一个 `day` 字段**（D5）：纯日志。

## 五、交账证据（真实命令 + rc + 关键输出）

| # | 命令 | rc | 关键输出 |
|---|---|---|---|
| 1 | `tools/mvn-lock.sh -q spotless:apply` | **0** | 无输出（静默通过） |
| 2 | `tools/mvn-lock.sh -DskipTests compile -am` | **0** | `BUILD SUCCESS`，16/16 模块（8.276 s） |
| 3 | `tools/mvn-lock.sh -DskipTests package -pl simos-app -am` | **0** | `BUILD SUCCESS`（14.4 s）⇒ jar md5 `acd8e7442e55c1e44537f081391269d6` |
| 4 | `python3 /tmp/w2acc/runner.py --jar … --world three-powers --plan [advance 600] --log-level INFO` | **0** | `advance 0->600 HTTP 200 35.180s`、`revisionDelta: 1`、`headRevision: 2`、`failedAt: null` |
| 5 | 同上，`--store`(day510 存档副本) `--plan [advance 540]` `--log-level DEBUG --keep-store` | **0** | `advance 510->540 HTTP 200 6.126s`、rev 18→19、`failedAt: null`；`IN_KIND_RENT_CHAIN_GAP` ×1（原文见 §一.3） |
| 6 | 同上（small-world）`--plan [advance 120]` | **0** | 落盘 `marketRoundStats`: 名义 901852 / 现金 1543；INFO 行两栏俱在 |
| 7 | 同上（续跑 `--keep-store`）`--plan [advance 150]` | **0** | rev 2→3、tick 150；上周期轮转为 名义 795145 / 现金 1325 ⇒ **新格式 checkpoint 载入 + 续跑成功**（Codec 往返实测） |
| 8 | `java … D3TableCheck`（`/tmp/w2fix7/D3check`） | **0** | 逐值表见 §2.1（含 α=0 与 p=0 两档） |
| 9 | `java … D1ChainCheck`（`/tmp/w2fix7/D1Chain`） | **0** | A/B/C/D 四行见 §1.4-A |
| 10 | `java … D1GateCheck`（`/tmp/w2fix7/D1Gate`） | **0** | 六行见 §1.4-B |

## 六、未完成 / 未验证项（如实记）

1. **门禁一字未跑**（`test` / `verify` / `clean` 禁用；`package` 只为 D1 自证跑了一次，任务书允许）⇒ **既有测试是否仍绿未验**。
   ★ 已知需要门禁确认的点：`MarketRoundStats` 加了 3 个组件（旧 7 参构造器已保留 ⇒ 两个既有测试类**应**能编译），
   D3 改了跌侧算式（`CapacityTruncationPricingAcceptanceTest` 的期望值我逐条核过：p=10000 时新旧公式**逐值相同**，
   因为 10000×(1000−step)/1000 恒为整数 ⇒ 该类的 9500/9820/9890/9640 四个字面量都不变；**但没跑**）。
2. **①闸门没有真实世界的触发样本**（§三-1：three-powers 每区都交易 grain）⇒ 只有合成探针（§1.4-B）+ 代码路径。
   **"被挡下的迁移"在真世界长什么样，未验。**
3. **②的"家户库存那一级"没有真实世界的触发样本**：本世界 day540 那个 unit 的 `payerStockMilli=0`（新户，账户空）
   ⇒ 真世界只走到 ③（缺口）；②由合成探针钉住（§1.4-A 的 B 行）。**"租金吃老库存"的真实现场未观测。**
4. **`IN_KIND_RENT_CHAIN`（DEBUG，付清）在两次真世界跑里 0 行** ⇒ three-powers 的实物租腿只有那一条（迁移建的）；
   资产规则建的租腿在该世界全是 `SHARE`（`RENT_COMMODITY_FROM_INDUSTRY_OUTPUT` ×3 佐证）。**"付得清的租腿"样本未采。**
5. **D3 的全量影响面未量化**：只给了逐值表与"p≥20 跌幅 ≤ α"；**跌侧价格变化对 600 天轨迹的影响没做前后对照**（需另跑旧 jar 对照，超本批预算）。
6. **D4 的 W4 下游未验**（W4 未实现）；`ApiViews` 的 GUI 现金成交额口径**未统一**（§2.2b 最后一行，记作待办）。
7. **SpotBugs / enforcer / 前端门禁未跑**；本批新增的 `payerStock` 组件与 `describe()` 拼接未过静态分析器。
8. **`git commit` 未做**（控制方审后提交）；`src/test/**`、`docs/**`、`AGENTS.md`、`simos-social`/`unit`/`sd`/`core` 一字未改。
9. **装置与产物**：`/tmp/w2fix7/**`（jar、store-*、results/*.log、三个探针、plan-*.json）**全在仓外**，不随批提交；
   复用验收 Agent 的 `/tmp/w2acc/runner.py` 与 `shell*.json`（只读，未改）。`store-tp390`/`store-tp510` 是验收存档的**副本**（原件未动）；
   其中 `store-tp390` 的副本被我删过 rev > 14 的行与 checkpoint（只为重放 390→420 段，属仓外一次性装置）。
10. **`src/test` 的编译未验**（`compile` 只覆盖 `src/main`）⇒ 若门禁红，第一嫌疑是 `MarketRoundStats` 的构造器/`withRound` 调用点（已尽量保留旧形状）。

## 七、落点行号（改动后，`simos-economy/src/main`）

| 项 | file:line | 内容 |
|---|---|---|
| D1① | `time/ModeMigrationPolicy.java:840-850` | `buildTargets` 里的闸门调用（`excluded` + INFO `MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED:846`） |
| D1① | `time/ModeMigrationPolicy.java:918` / `:963` | `inKindLegUnavailableReason`（取参）/ `inKindLegUnavailableReasonOf`（判据本体） |
| D1① | `model/RegimeRelations.java:636` | `inKindCommodities(RegimeId)` |
| D1① | `time/ModeMigrationSettlement.java:1882` | `migrationTemplateRegime`（`private` → 包内可见） |
| D1(d) | `time/ModeMigrationSettlement.java:1747-1815` | `buildMigrationRelation` 的 E14 兜底（INFO `MIGRATION_RELATION_E14_ILLEGAL_FALLBACK:1800`）+ `planMigrationRelation` |
| D1② | `time/ProductionSettlement.java:195` | `Facts.payerStock` 新组件 |
| D1② | `time/ProductionSettlement.java:282` / `:504` | `settle` 改调 `availableOf` / 三级支付链本体 |
| D1② | `time/EconomySettlement.java:8370` / `:8836` | `availableGoodsOf` / `harvest` 取 `payerStock` |
| D1② | `time/EconomySettlement.java:8899` / `:8920-8975` | `logInKindRentChain` 调用 / 本体（INFO `IN_KIND_RENT_CHAIN_GAP:8941`、DEBUG `IN_KIND_RENT_CHAIN`） |
| D1③ | `time/ProductionSettlement.java:533` | `requireProducibleCommodities` 对 `FIXED_IN_KIND_RENT` 放行（其余档照旧抛） |
| D3 | `time/MarketSettlement.java:2486` / `:2545` | 跌侧 `⌈…⌉ + min(p−1, …)` / `multiplyByPerMilleCeiling` |
| D4 | `model/MarketRoundStats.java:56-66` / `:145` | 10 组件 record（三段两栏）/ `withRound(…, cashTurnoverMilli, …)` |
| D4 | `time/MarketSettlement.java:2131` / `:2137-2146` | `cashTurnover` 累加 / `turnover`（量×价整轮取整） |
| D4 | `time/EconomySettlement.java:2668-2700` | 写状态传两栏 + INFO 加 `turnoverMilli` / `cashTurnoverMilli` |
| D4 | `time/MarketRoundStatsBook.java:79-121` / `:160-170` | 三个名义读口语义改 + 三个现金读口 + 一览 |
| D4 | `time/MarketReadout.java:94-107` | `lastRoundTurnoverOf`（名义）+ `lastRoundCashTurnoverOf`（现金） |
| D5 | `time/EconomySettlement.java:7578/7646/7670/7697/7720` | 五条还债事件各加 `"day", day`；`repayDebts` 增 `long day`（`:7510`），调用点 `:2860` |
