# P-T5 民间 FX 簿 + 家户自动换汇循环 —— 实现架构账本（写码 Agent）

> **依据（约束设计书）**：`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.3 / §2.6 / §3（I-C1/I-C5/I-C6）/ §5（T4/T5/N1/N7）/ §6.2（V-25/V-26）；
> `docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §18（C-1..C-4）/ §19 / §20（F-1..F-5 + §20.4 前置依赖）/ §20.3（走市场自然议价）；
> `docs/superpowers/reports/2026-10-10-port-merchant-consistency-check.md` §19–§21；`AGENTS.md` §一.9（日志）/§一.11（旧档不兼容）。
> **基线**：工作树 `07edabfe`（3c 订单可选币之后、工作树干净）。
> **本批范围**：`simos-economy/src/main`（+ `simos-economy` 内既有的日志来源表）。**不做**：P-T1e 币种挂单过滤（本批接受集恒真）、P-T1d 政府采购优先级、M1–M5、**3c 的"按购买力给商品订单选币"**（见 §4 偏离 3）。
> **纪律**：只写生产代码、只到编译过；不写/不改测试；不跑 `test`/`verify`；不 `git commit`；一次一个 Maven；旧档不兼容（§一.11）。

---

## 0. 一句话

`pairs`（家户挂单的锚）**唯一来源是政府窗口**（`FxSettlement:112-122` 改前）⇒ 没有窗口就一条家户 FX 单都不生成（报告 §19 C-1）；
本批把家户侧改成**自报价的民间簿**：逐户按 F-1 购买力（付清自己的 `naturalNeeds` 要付多少）**弱换强、挂单全部、限价按同一份价格口径**，
窗口照旧进簿但**不再是唯一报价源**；缺价 ⇒ 不换（不猜、不 1:1）；单币世界/无价可比 ⇒ 逐值不变。

---

## 1. 关键调查结论（`file:line` 为**改动前**的行号，逐条核过）

| # | 事实（改动前） | 结论 | 影响 |
|---|---|---|---|
| C1 | `FxSettlement:113` `pairs` 只由 `input.windows()` 生成；`:283` 家户单要求 `pair.quote.equals(local)` 且簿存在 | **C-1**：没有官方报价 ⇒ 民间 FX 通道为空 | 本批要动的主因 |
| C2 | `FxSettlement:103` `!input.isActive() ⇒ FxRoundResult.none()` 整段跳过 | 没窗口连"民间自愿"都没有 | 必须拆开"有没有窗口"与"有没有外汇面" |
| C3 | `FxSettlement:293` 卖外币门槛 = `spendableQuote == 0`（外币完全花不出去）；`:302-307` 买外币门槛 = `surplus = spendableQuote − reserveMoney > 0` 且按官方 `ask` 算量 | 旧两条规则与 F-1/F-3/F-4 全冲突（§19.3 表逐条点名） | 两条旧规则**整体退役**（含 `FX_SELL_DISTRESS_PER_MILLE`） |
| C4 | `FxSettlement:100-101` 签名只拿到 `round/markets/topology`；`MarketRound.householdMoney` 是 `private final`（`MarketSettlement:1329`） | 民间簿要读"手里有哪几种币"，但外汇段拿不到持币表 | 给 `MarketRound` 加**窄读口** `moneyOf`（不许在 FX 侧另拼"我有多钱"） |
| C5 | `MarketSettlement:1429-1445`（改前）次序 = 商品撮合 → `creditRound` → `FxSettlement.match`；`releaseAllFreezes` 在信用之前 | F-5 的时点（FX 在信用之后）**现状即符合**；此刻冻结已释放 ⇒ "可花额 = 余额" | 时点一个字不改（用户裁定的"挂完生产需求后"） |
| C6 | `MarketTopology.ofZones`（`:524`）来自持久 `marketZones`；`MarketTopologyBook:236-242` 逐区建 `MarketNode(numeraire = zone.legalTender, anchor = zone.anchor)` | "**该币法定区**的价表" 有现成落点 = `region.numeraire() == 该币` 的区、取 `markets.get(region.anchor())` 的 `prices` | F-1 的价格来源不必新造状态 |
| C7 | `MarketSettlement.moneyReserveOfHousehold:7483-7509` 的算式 = `Σ uncovered×price÷1000 + 人均缓冲` | F-1 的"付清需求要付多少"与它**同量纲、同取整**，但篮子口径不同（见 §3 判断 2） | 沿用它的除法/取整先例，不沿用它的"扣库存" |
| C8 | `MarketSettlement.orderCurrencyFor:2035` 恒返回 `market.numeraire()`；其类注写明"P-T5 接进来的唯一改动点"；3c 账本 §5.2 列出**6 处**非本格币折算面（`cashAffordable`×2 / `requestedMoneyOf` / `affordableQuantity` 运费 / `totalCostAtMost` 运费 / `Fill.freightPerUnitMilli`） | 3c 的"机制 + 缺省中性"已落，**但策略面（按购买力选币）连带 6 处折算未做** | 本批**不碰**（见 §4 偏离 3，须控制方裁定） |
| C9 | `FxRejectReason.NO_OFFICIAL_RATE` 改前已是零产出者；`acceptsCurrency` 恒真（3c 注释） | 本批不得新造"没有官方汇率 ⇒ 拒"的路径 | 未成交归因沿用 `NO_COUNTERPARTY`/`NO_CROSS` |
| C10 | `EconomyLogSource.ECONOMY_FX:73` 已是 fx 分类唯一拼写点；`MarketSettlement:1506` 注释写"没有官方汇率 ⇒ 整段跳过" | 注释与本批新口径冲突（§四：机制性描述回代码核） | 一并更正 4 处过时注释（见 §2.3） |

---

## 2. 实现架构

### 2.1 新增：`HouseholdPurchasingPower`（F-1/F-2/F-4 的**唯一拼写点**）

`simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdPurchasingPower.java`（包内可见、无状态、不进任何持久结构）：

| 方法 | 口径 |
|---|---|
| `zoneTables(markets, topology)` | 币种 → `ZoneTable(currency, anchor, market)`：遍历拓扑区，取 `market.numeraire == region.numeraire` 且锚格有市场者；**同一币多种区 ⇒ 锚格 (q,r) 最小者**（纯函数，I7） |
| `tableFor(currency, ownRegion, tables, markets)` | 优先**家户自己所在区**的锚格价表（"我在自己市场上要付多少"），否则回落到规范选取；两边都没有 ⇒ `null`（缺价） |
| `needCostMilli(row, table)` | **F-1 算式**：`Σ_{need>0} need × price ÷ 1000`（毫该币）；篮子 = `naturalNeeds`；**任一 need>0 的商品无定价行 ⇒ `OptionalLong.empty()`**（缺价 ⇒ 整币不可比）；空篮子 ⇒ 空读数 |
| `strengthOrder(costMilli)` | **F-1/F-2 破平**：`(cost 升序, 币种 id 升序)` ⇒ 首项 = **最强** |
| `weakestFirstOrder(costMilli)` | **F-2 挂单顺序**：`(cost 降序, 币种 id 升序)` ⇒ 首项 = **最弱先换** |
| `limitPerMille(costBase, costQuote)` | **F-4**：`⌊1000 × cost(quote) ÷ cost(base)⌋`；分母/分子 ≤ 0 或溢出 ⇒ **0 = 不挂单**（不猜） |

★ "定价为 0"（明确免费）与"从未定价"用 `Market.hasPrice` 区分（`Market:81` 既有口径）：前者照算，后者整币不可比。

### 2.2 改：`FxSettlement`（民间簿）

```
match(round, markets, topology)                                  // FxSettlement:118
  ├─ 早退：input == null || markets.isEmpty() ⇒ none()            // 不再看 isActive()
  ├─ ① 窗口侧**一字未改**：pairs/quotesByPair/window 单进簿
  ├─ ② 窗口簿建簿（原样）
  ├─ ②b planHouseholdOrders(round, markets, topology, books)      // :319  ★ P-T5 新增
  │     ├─ hasMultipleCurrencies? 否 ⇒ 空计划（单币世界一个数都不动）// :639
  │     └─ 逐 hex(q,r) → 逐户：跳过市场排除集/国库户/无需求/持币<2
  │          ① 逐币 needCostMilli ⇒ power（缺价 ⇒ 记入 unquotable，不参与）
  │          ② target = strengthOrder 首项（最强）
  │          ③ weaker = 其余币，按 weakestFirstOrder（最弱先换、同强度 id 升序）
  │          ④ placeHouseholdOrder(...)                           // :495
  │     DEBUG：FX_HOUSEHOLD_POWER（逐户判据：成本/缺价/目标/挂几条/为什么）+ FX_ORDER_PLAN（本轮汇总）
  ├─ ③ 逐簿 matchBook（**撮合序一字未改**：价格优先 → ownerRank = canonical 序）
  ├─ ④ attributeResidue：窗口残量 INFO（原样）；**家户残量 TRACE**（本批日志口径）
  └─ finish(...)：INFO FX_ROUND 加上 households/orders/pairs/householdFills/householdBaseVolumeMilli
```

`placeHouseholdOrder` 的定向与量（**"并入窗口定向"**）：

| 情形 | 定向 (base, quote) | 家户单 |
|---|---|---|
| 存在窗口簿 `弱\|强` | `(弱, 强)` | **SELL** base，量 = 该弱币全部可花额（F-3） |
| 只存在窗口簿 `强\|弱` | `(强, 弱)` | **BUY** base，量 = `⌊可花额 × 1000 ÷ 限价⌋`（"这点钱能买多少 base"） |
| 两者都没有 | 新建**民间簿**：`base = id 小者` | 按上面同一判据卖或买 |

限价两种情形**同一个公式**：`limitPerMille(power(base), power(quote))`（F-4；不用家户估值表）。
fail-closed：限价 ≤ 0 ⇒ 不挂；量 < `FX_MIN_LOT_BASE_MILLI` ⇒ 不挂（两处都具名进 DEBUG 计数）。

### 2.3 文件清单

| 文件 | 改动 |
|---|---|
| `time/HouseholdPurchasingPower.java` | **新增**（§2.1） |
| `time/FxSettlement.java` | 民间簿（§2.2）；删旧两条家户规则与 `FX_SELL_DISTRESS_PER_MILLE`；`rejectOnce` 加 `(event, info)` 两级；`finish` 加 `HouseholdPlan`；类注重写 |
| `time/MarketSettlement.java` | `MarketRound.moneyOf(HouseholdId)`（:629，窄读口）；4 处过时注释更正（`:381/:676/:712/:1506`） |
| `time/FxRoundInput.java` | `isActive()` 的注释更正（它**不再**等于"有没有外汇面"） |
| `time/EconomySettlement.java` | `:1840` 注释更正（没有窗口 ≠ 没有外汇面） |
| `EconomyLogSource.java` | `ECONOMY_FX` 的类注与中文说明补上"家户民间簿"（**id 不改**，`economy-fx` 原样） |

---

## 3. 关键判断（为什么这样拆；调查中推翻过什么）

1. **"该币法定区"取拓扑区的锚格价表，不新造"区价表"来源。** `MarketRegion` 已经携带 `(numeraire = legalTender, anchor)`（`MarketTopologyBook:236-242`），
   `Market.prices` 就是"区内参考价"；另造一份区级价表会与 §一.4"机制性描述回代码核"对着干。锚格没市场 / 计价币漂开 ⇒ 当作**缺价**（不拿别人的价表冒充）。
2. **F-1 的篮子用"全部自然需求"，**不扣库存。§20.5 明写"需求篮子 = Social 的 naturalNeeds"；`moneyReserveOfHousehold` 的 `uncovered = need − 库存` 是**另一个问题**
   （"还要花多少现钱补货"），混进来会让"购买力"依赖商品面。★ 取舍：两种口径对**排序**（谁最强）通常同向，但**限价的比值**会不同；本账本按用户原话的"**付清**需求"取全量。
3. **换汇方向 = 只换成"最强的**那一种**"（不链式 W1→W2→S）**：用户说"兑换成购买力强的"，且跨轮循环已覆盖部分成交；链式会让一户口同时挂两条同币单、互相竞争。
4. **同强度（cost 相等）的币也参与换汇**：F-2 的"同强度按币种 id 升序"如果只在"严格更弱"的币之间破平，那么平局币就永远不参与 —— 破平键会退化。
   取"**除目标外全部可比币都换成目标**"：平局币的限价恰为 1:1，只有在**对手方按不同篮子估值**时才成交 —— 这正是"市场自然议价"要的东西。
   ★ 已知取舍：会多出一些 1:1 限价的单（成交稀少，噪声由 TRACE 承担）。
5. **"并入窗口定向"而不是"每个方向各建一本簿"**：簿是逐 `base|quote` 一本、买卖两侧在同一本里交叉；并入现成定向才能让家户单**直接与政府窗口撮合**（否则窗口的买/卖盘看不见家户单 = 白挂）。
   代价如实记：**同一无序币对若被窗口以两种相反定向各挂一次，今天仍是两本簿**（改前就如此，本批不合并 —— 合并要把窗口报价取倒数，会静默改窗口侧的数值行为）。
6. **早退门 = "世界只有一种币"**（市场计价币 ∪ 逐户持币）：单币世界连价表都不建 ⇒ I-C2 的"逐值不变"是结构性的，且不多花算力。
   ★ 推论（**会改数值行为**）：**多币世界但家户只持有一种币**时，旧规则会给"有余钱"的户生成**买外币**的单（旧 C3 的下半条），本批**不再生成** —— 这正是 §19.3 点名要换掉的那条，
   也是报告 §19 C-2（民间没有买外币动机）的现状：外币需求要靠 P-T1e 的"卖方能拒收外币"来产生。
7. **`rejectOnce` 加级别参数而不是新写一个方法**：去重口径（reason+actor+pair）必须两处同源，否则同一事实会一条 INFO 一条 TRACE。
8. **`MarketRound.moneyOf` 是唯一可行的窄口**：`householdMoney` 是 `private final`，FX 段在另一个类里；不给读口就只能复制一份持币/可花口径（违反"同一事实一处拼写"）。
9. **"定价为 0（明确免费）"的角落**：某币法定区把篮子里的商品明确标 0 价 ⇒ 该币 `cost = 0`（合法读数，`Market.hasPrice` 与 `priceOf` 的既有区分），它会成为"最强"，
   而**换入它的限价 = `⌊1000×0÷cost(base)⌋ = 0` ⇒ 不挂单**（fail-closed：白送不算换汇）。这一角落不会造出任何写；`FX_HOUSEHOLD_POWER` 的 `powerMilli` 里读得到 `币=0`。

---

## 4. 偏离记录（与约束设计书不一致 / 未做，如实记）

1. ★★★ **`orderCurrencyFor` 未接（3c 的策略面没做）—— 这是本批唯一需要控制方裁定的设计冲突**：
   - **冲突双方**：`docs/...2026-10-09-port-policy-and-zone-efficiency-design.md` §20.4 把"订单可选币"称为 P-T5 的**硬前置**，理由是"F-3 要把弱币全换成最强币，
     而今天买方只能用本格计价币付款 ⇒ 若最强币不是本格币，家户换完之后在本格市场反而**付不出去**"；3c 账本 §7 也把"**P-T5 自动选币**"的落点写成
     `MarketSettlement.orderCurrencyFor(Market)`。**但**本批派单的十条冻结口径里**没有**它，且 3c 账本 §5.2 同时记着"翻它要连带 **6 处非本格币折算面**"
     （`ordersFor`/`planGovMandateOrders` 的 `cashAffordable`、`requestedMoneyOf`、`affordableQuantity` 的运费腿、`totalCostAtMost`、`Fill.freightPerUnitMilli`）
     —— 那是"装折算器"的另一块活；③ 卖方侧（`receiveWith` 与买方 `payWith` 共用同一方法）要不要与买方分道，设计书未裁。
   - **我的处置**：按派单"不自行改设计"⇒ **不做**，把冲突上报（见报告 ⑦⑧）。★ 后果如实记：本批落地后，若"最强币 ≠ 本格计价币"，家户按 F-3 卖光本币换来的强币
     **在本格商品市场可能付不出去**（只能留着或再换回去）—— 这是设计书自己点名的 C-2/§20.4 缺口，不是本批新引入的错误，但**必须由控制方裁定**是补 3c 还是先接受。
   - **接缝已就位**：F-1 的算式与比较器都收在 `HouseholdPurchasingPower`（构造只需要 `round`+`participant` 就能读持币），
     将来接 3c 策略时 = 改 `orderCurrencyFor` 一处 + 补那 6 处折算，不必再动本批任何代码。
2. **未做 P-T1e（接受集恒真）/ P-T1d / M1–M5**（派单明列的"不做"）。
3. **未做 C-3（信用与 FX 合并成一本"钱的用途"簿）**：F-5 裁定"维持现状"，本批 FX 仍在信用之后。
4. **家户残量的日志级别从 INFO 改成 TRACE**（`FX_HOUSEHOLD_UNFILLED`）：本批冻结口径第 10 条 = INFO 汇总 / DEBUG 判据 / TRACE 逐笔；若按旧级别，一个多币世界里每户每币对每轮一条 INFO
   会把"本轮汇总"淹掉。★ 窗口面的拒（政策价停做/政府无对手方/政府付不出）**保持 INFO**（不动既有级别）。
5. **`FX_SELL_DISTRESS_PER_MILLE` 已删**（旧折价口径无消费者；§一.11"旧设计直接删"）。旧档不兼容：FX 面本来就逐轮瞬态、不落盘，无旧档问题。
6. **"同一无序币对的两种定向不合并"**（§3 判断 5 的代价）。

---

## 5. 给测试 Agent 的输入

**会改变数值行为的清单**
1. **多币世界 + 家户持 ≥2 种币 + 各币法定区锚格都有该户篮子商品的定价** ⇒ 每轮新增家户 FX 单：卖 全部 弱币买 最强币，限价 = `⌊1000×cost_strong÷cost_weak⌋`（= F-1/F-4）。
2. **旧两条家户规则整体退役**：① `spendableQuote == 0 && spendableBase ≥ 1000 ⇒ 按 bidP×0.9 卖光 base`；② `surplus = spendableQuote − reserveMoney > 0 ⇒ 按 askP 买 base`。
   ⇒ 有窗口但**家户只持有本格币**的世界：旧规则的"余钱买外币"单**不再生成**。
3. 家户单**不再要求** `pair.quote == 本格计价币`，也不再要求"本格有该币对的窗口"。
4. **没有窗口的世界现在也跑 FX 段**（有市场就跑）：无事发生 ⇒ 空读数（与 `none()` 逐值等价）；有单无成交 ⇒ 新增 INFO `FX_ROUND`。
5. 日志：家户残量 INFO→TRACE；`FX_ROUND` 新增 `households/orders/pairs/householdFills/householdBaseVolumeMilli`；新增 DEBUG `FX_HOUSEHOLD_POWER`、TRACE `FX_HOUSEHOLD_ORDER`/`FX_HOUSEHOLD_UNFILLED`。
6. **哪些世界逐值不变（I-C2 论证）**：① 单币世界（市场计价币 ∪ 持币只有一种）⇒ `hasMultipleCurrencies=false` 在**建价表之前**返回空计划；
   ② 多币但没有一户持 ≥2 种币 ⇒ 无单；③ 有 ≥2 币但 `naturalNeeds` 空 / 篮子任一商品在某币法定区无定价 ⇒ `needCostMilli` 为空 ⇒ 不挂；
   ④ 上述三种情形下 `fills`/`rejections`/`windows` 都为空 ⇒ `FxRoundResult` 与改前的 `none()` 逐值等价（同为空记录），且不写任何账。
   ★ 唯一"结构变了但数值没变"的地方：`match()` 不再因 `!input.isActive()` 早退 —— 上列四种形态都不产生任何写与日志。
7. **受影响硬编码字面量**：`FX_MIN_LOT_BASE_MILLI = 1000`（沿用；现在同时是"卖单量的下界"与"买单量下界"）；`EconomySettlement.MILLI_PER_GRAIN = 1000`（F-1 除法分母）；
   `PER_MILLE = 1000`（F-4 分子，`HouseholdPurchasingPower` 内私有）；**删除**：`FX_SELL_DISTRESS_PER_MILLE = 100`。
8. **会让既有测试失效的清单**：本批改动前 `simos-economy` 测试树**已不编译**（3c 账本 §5.6：`MarketRegulationTest` 引用已删的 `REGULATION_QUOTA`）；
   全仓测试树对 `FxSettlement` / `FX_SELL_DISTRESS_PER_MILLE` / `GovFxWindow` / `FxFill` 的引用 = **0 命中**（实测 grep）⇒ 本批**不新增**编译失效点，但会改 FX 行为的既有断言（若有）：
   ① 任何"家户卖外币"的旧期望（限价 = bidP×0.9、量 = 全部 base、触发 = 本币可花为 0）—— 口径已整体换成 F-1/F-4；
   ② 任何"有余钱 ⇒ 出现买外币单"的期望；③ 任何 `FX_REJECTED` INFO 的条数断言（家户那部分改走 TRACE）。
9. **T4/T5 复现世界的两个前提（否则测出来是"缺价"）**：① 至少两种币，且各有**法定区**（`MarketZone.legalTender`）；
   ② 两个区的**锚格都有市场价表**且篮子商品（粮）在其中（`Market.prices`）。若锚格无市场 ⇒ 该币缺价 ⇒ 不挂单（V-7 的 fail-closed，`FX_HOUSEHOLD_POWER` 的 `unquotable` 会点名）。

**未验证（本批纪律）**：没跑 `test`/`verify`/真实 world ⇒ 没有任何真实数值证据；上面全部是逐表达式论证 + 编译证据。

---

## 6. 门禁证据（真实命令与结果）

```
① tools/mvn-lock.sh -q spotless:apply                      → exit 0（无输出）
② tools/mvn-lock.sh -DskipTests compile                    → 首轮 **FAILURE**（见下），修好后 BUILD SUCCESS / exit 0
③ rm -rf {simos-economy,simos-app}/target/classes && tools/mvn-lock.sh -DskipTests compile
   → "Compiling 188 source files …"(economy) + "Compiling 327 source files …"(app) / 16 模块全 SUCCESS / BUILD SUCCESS / exit 0
   （★ 188 = 3c 之后的 187 + 本批新增 1 ⇒ 新文件确实进了编译单元，不是拿了上一轮 class 当绿）
④ tools/mvn-lock.sh -q spotless:apply（改注释后复跑）        → exit 0
⑤ ③ 复跑（同一条从零命令）                                   → 同 ③：188 + 327 / BUILD SUCCESS / exit 0
```
★ 首轮 ② 的**真实报错**（不是推演，逐字）：
```
HouseholdPurchasingPower.java:[99,7] cannot find symbol  symbol: class MarketRegion
FxSettlement.java:[878,13] method rejectOnce … cannot be applied to given types; required: long,…,String,boolean
HouseholdPurchasingPower.java:[78,10] cannot find symbol  symbol: class MarketRegion
```
⇒ 修：补 `import io.mosire.simos.economy.api.market.MarketRegion`；`attributeResidue` 里**窗口买入**那句 `rejectOnce` 补 `("FX_REJECTED", true)`。
★ **未跑**：`test` / `verify` / `package` / 真实 world（派单纪律：写码 Agent 只到编译过）。
★ 影响面（§五.1 的核对）：`git status --porcelain` = **6 个文件**（5 改 1 新，全在 `simos-economy/src/main`），
`git diff --stat` = `EconomyLogSource 7 +/1 -`、`EconomySettlement 3`、`FxRoundInput 7`、`FxSettlement 604`、`MarketSettlement 34`，
新增 `HouseholdPurchasingPower.java`（231 行）—— **无一处溢出到 `src/test` / `pom.xml` / 别的模块**（全仓 spotless 跑过两轮）。

---

## 7. 与约束设计书的逐条对照（十条冻结口径）

| # | 冻结口径 | 落点 |
|---|---|---|
| 1 | 民间簿：家户自挂单，不依赖窗口报价；自然议价、不设特权队列 | `planHouseholdOrders`（`:319`）+ `matchBook` 原样（价格优先 → `ownerRank`） |
| 2 | 触发与时点：先挂生产需求，之后（商品+信用之后）换汇 | `MarketSettlement:1506` 位置未动 |
| 3 | F-1 购买力：付清 `naturalNeeds` 要付多少；付得最少 = 最强；法定区价表；缺价 ⇒ 不换 | `HouseholdPurchasingPower.needCostMilli`（`:131`）+ `tableFor`（`:98`） |
| 4 | F-2 顺序：最弱先换；同强度币种 id 升序 | `weakestFirstOrder`（`:174`）+ `strengthOrder`（`:160`） |
| 5 | F-3 挂单全部（不扣储备）、能换多少换多少 | `placeHouseholdOrder`（`:495`）：量 = 全部可花额 / 按限价能买到的量 |
| 6 | F-4 限价按 F-1 价格口径（非估值表） | `limitPerMille`（`:195`），唯一取价处 |
| 7 | 循环：允许部分成交、未成交下一轮继续 | 订单逐轮瞬态、每轮从余额重挂（本类无跨轮状态） |
| 8 | 守恒：两腿等值、唯一写口 | `applyLeg → EconomySettlement.applyTransfer` 原样；成交价 `FxPricing` 中间价 |
| 9 | 缺省中性：单币 / 无价可比 ⇒ 逐值不变 | `hasMultipleCurrencies`（`:639`）早退 + §5.6 的四种形态论证 |
| 10 | 日志：INFO 汇总 / DEBUG 判据 / TRACE 逐笔 | `FX_ROUND`（INFO）/ `FX_HOUSEHOLD_POWER`+`FX_ORDER_PLAN`（DEBUG）/ `FX_HOUSEHOLD_ORDER`+`FX_HOUSEHOLD_UNFILLED`+`FX_FILL`（TRACE） |
