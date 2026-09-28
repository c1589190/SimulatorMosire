# 调查单第 2、3 题：结算资源竞争与顺序、固定输入重跑一致性、bid/ask 口径

> **调查人**：只读调查代理 B（未改 `src/**`、`docs/**`、既有台账；未 `git add/commit`）
> **代码态**：仓库 `/home/cna/SimulatorMosire`，分支 `ts/m1`。
> ★ **HEAD 实测 = `c8520f34`，不是任务书写的 `5b6b2be7`**：`git diff --stat 5b6b2be7..HEAD`
> 只多出 `docs/superpowers/reports/2026-09-28-economy-system-flow-report.md` 与
> `docs/superpowers/reviews/2026-09-28-m2-one-year-run-report.md` 两处文档（243 行插入，0 删除）⇒
> **生产代码与 `5b6b2be7` 相同**。下文行号按工作树 `HEAD` 给。
> ★ 本报告里的“实测”= 本轮真跑过；“代码事实”= 回代码核对过；“推断”会逐处标注。
> ★ 所有临时物在 `/tmp`（`/tmp/det-a`、`/tmp/det-b`、`/tmp/store-det-a`、`/tmp/store-det-b`、
> `/tmp/store-probe`、`/tmp/probe`、`/tmp/state-det-a`、`/tmp/state-det-b`、`/tmp/fill-probe-all.txt` 等）。

---

## 〇、结论摘要（先看这里）

### 第 2 题

1. **会竞争同一主体商品/货币/冻结额的结算操作**（按默认日序）：
   到货装载 → 投入计提 → 消费 → 收获/契约分账 → 市场撮合 → 借粮 → 还债 → 饿死/劳动缩放 → 计息。
   其中**商品**被到货/投入/消费/收获/市场/借粮/还债共同读写；**货币**被契约分账的货币档、市场货款/运费、
   承运费读写（借粮/还债只动粮）；**四张 `frozen*`** 只有市场撮合在写；**`shipments`** 被到货装载（出）与
   市场跨区成交（入）写。逐项表见 §一。
2. **市场同价订单没有“按价格排序”这一步**：区内所有成交价 = 集散节点参考价，限价只做过滤。并列时
   “谁先”由**名单序**决定：格按 `(q,r)` 升序 → 家户行按 `(阶层 value, 居住类型)` → 经营者按 `industryId`
   字典序；**同余数时最大余数法按下标升序**（`ProportionalSplit`）。这套次序是内容的纯函数、可复现；
   `simos-economy/.../economy/time` 下**没有裸 `HashMap` 迭代**（`grep -rn "HashMap"` 201 命中全是
   `LinkedHashMap`；`grep -v LinkedHashMap` = 0），裸 `HashSet` 只用于 membership。
   ★ 但“影响结果”要分开说：**总量**在最大余数法下基本不变（只差 ±1 最小单位的并列残差），
   **哪一对买卖双方配对、`MarketReport.Fill` 的先后**会随名单序变；**跨区**还受“先买的区先拿稀缺供给”
   影响，但买方格/卖方格都是 `(q,r)` 排序，故照样可复现。细节与判定见 §二。
3. **固定输入重跑：最终状态逐字段一致（实测）**。两边各自从 `store-m2` 的 **同一份字节**（md5
   `eef6889b…`）复制、两个独立 JVM（GUI 5847/5857、MCP 5745/5755、审批 5743/5753）、
   各执行一次 `advance 360→390`：
   - 新 revision `12` 的 `changeset_json` **逐字节相同**（`ee81091287cb8716cc546a7f8b1a084e`，
     canonical md5 `ac5b857d9bf6f86a3fdc39bb355d4023`）；只有 `command_id` / `correlation_id`
     两个随机 UUID 不同（不是状态）。
   - 用 `Replay` 各自重建 revision 12 的 **六个模块全量快照** 后，`SimulationState.equals == true`，
     `map/social/unit/sd/economy/actor` 六个 `Snapshot.equals` 全 `true`。
   - **`state-det-a/ownership.json` 与 `state-det-b/ownership.json` 逐字节相同**
     （`02c0c109615198d4dc1af5a972dced84`）：全 8191 本 `GoodsAccount`（actor / goods / money /
     frozenGoods / frozenMoney）都覆盖；冻结额实测全 0。
   - `/api/economy/ownership` 三国 799 格全量输出也逐字节相同（`97b20164c774845e6fba1b2550858511`）。
   - **表示层有两处不一致**（状态一致、字节不一致）：① `h6sim_dump` 原始 JSON 首个差异在
     `social.crisis[].evidence` 的 **对象键序**（`CrisisMonitor.Light` 构造器 `Map.copyOf`，
     `CrisisMonitor.java:305`）；② 全量快照 JSON 的原始字节差异**全部**是 227 个
     `map.regions.*.hexes` 的**数组排列**（`Region.hexes = Set.copyOf`，`Region.java:28`）。
     两者的语义内容都逐值相同（canonical JSON / 递归 diff 0 处值差异；把 map 区域 hex 数组排序归一后
     md5 两边同为 `2872b4717875ec024ae403c405c28212`）。
   ⇒ **结论：状态逐字段一致；原始 dump / 快照字节不一致，但差异全部是“表示不同”，不是“状态不同”。**
4. 对比覆盖面与未覆盖面见 §三.5。

### 第 3 题

- `Market.bidPriceOf` / `askPriceOf` 是**由参考价现算的两个限价**，不是参考报价本身，也不是成交价：
  - `bidPriceOf` = **卖方**底价 = `max(1, ⌊p × 990‰⌋)`（`Market.java:87-90`）→ 直接填进
    `SellOrder.minPrice`（`MarketSettlement.java:706-713`）；
  - `askPriceOf` = **买方**最高价 = `max(bid+1, ⌈p × 1010‰⌉)`（`Market.java:104-111`）→ 直接填进
    `BuyOrder.maxLandedPrice`（`MarketSettlement.java:737-745`）。
  ★ 命名与常规“bid=买方出价、ask=卖方报价”相反，读代码/读表时必须按本仓口径读；`bid` 是卖方的、`ask`
  是买方的。
- **成交价 = 参考价**：区内 = 集散节点格参考价（`MarketSettlement.java:865-904`，`matchGroup(..., price, null)`）；
  跨区 = 该路线卖方格里最高的参考价（`unitPriceOf`，`MarketSettlement.java:1415-1423`）。
  `executeTrade` 只拿这个 `unitPrice` 算 `payment = ⌈quantity × unitPrice ÷ 1000⌉`（`:1186-1196`）。
- **同一参考价下仍有成交**是因为 `bid ≤ p ≤ ask` 恒成立：卖方愿收到 ≥ `bid`（比 p 低约 1%），
  买方愿付到 ≤ `ask`（比 p 高约 1%），而系统定的成交价 `p` 落在两者之间 ⇒ **两边都比自己的限价占优，
  两边都接受 p**；中间没有做市商/库存截留价差（差价的记账不存在）。
- 实测一笔成交（探针在 tick 360 状态上从 361 真跑到 365，取第 365 日 `PERIODIC` 的
  `MarketReport.Fill`；详见 §五.2 / §五.3）：
  - 布：`from=-20_-83 → to=-19_-83`，seller `HOUSEHOLD:-20_-83:rural:middle_peasant`，
    buyer `HOUSEHOLD:-19_-83:urban:landlord`，`quantity=44`，`unitPrice=5`，
    `seller[ref,bid,ask]=[5,4,6]`，`buyer[ref,bid,ask]=[5,4,6]`，`payment=1`，`freight=0`，即时。
    撮合判据：卖 `minPrice=4 ≤ 5`，买 `maxLandedPrice=6 ≥ 5` ⇒ 按 `p=5` 成交。
  - 粮：同区，`quantity=1388`，`unitPrice=1`，双方 `[ref,bid,ask]=[1,1,2]`，`payment=2`。
    判据：`1 ≤ 1 ≤ 2`。

---

# 第一部分：第 2 题（资源竞争、顺序、固定输入重跑）

## 一、哪些结算操作会竞争同一主体的商品、货币或冻结额

### 1.0 默认日序（这是竞争的“时间轴”）

主入口 `EconomySettlement.settleOneDay(...)`：`EconomySettlement.java:624`。默认旋钮
`PLANTING_DRAWS_BEFORE_CONSUMPTION = true`（`:358`）、`FAMINE_MORTALITY_PER_MILLE = 0`（`:345`）。
日序（括号里是调用点行号）：

```
0b 到货                     deliverShipments                        :719-720
0c 现扣周期投入 + 劳动再分配  drawCycleInputs + reallocateLabor       :722-736（默认先于消费）
1  消费（各家吃自己的）       consumeOwnStock                         :743
   （若 plantingDrawsFirst=false，则投入挪到这里：:745-759）
3  进度/周期末收获+关系分账   harvest + ProductionSettlement.settle   :810-843（harvest 调用 :820）
4  区域市场                  MarketSettlement.clearOncePerCycle       :853-880（触发 :873-876）
4b 借粮（最后手段）           lendDeficits                            :889-908
4c 偿还（仅关账日）           repayDebts                              :910-925
4d 饿死 + 死亡后劳动缩放      applyFamine + scaleLaborOfIndustry       :928-962
5  周期末计息（仅关账日）     chargeInterest                          :964-970
6  流水收口                  每行 FlowRow 重建                        :972-1024
```

**同一主体的资源是串行经过这条链的**；下面逐项列“动哪张表 / 与谁撞”。

### 1.1 逐操作矩阵

| 操作 | 默认时点 | 动哪些表 | 关键行号 | 竞争/顺序要点 |
|---|---|---|---|---|
| **到货装载** `deliverShipments` | 每天第 0b 步，最先 | `shipments`（遍历到期批次后 `iterator.remove`）、`householdGoods` 或 `operatorGoods`（+净到货）、ledger（在途损耗） | `EconomySettlement.java:1070-1106`；`addStock` `:1096`；`addOperatorStock` `:1098`；`iterator.remove` `:1104` | 把在途货在消费/投入/市场之前落回目的地，所以“当天到货当天能吃/能卖”；不与在途表其它写者同日竞争（当天新发的在途在傍晚市场才写入） |
| **投入计提** `drawCycleInputs`（3 遍式：调查→配给→落账） | 周期第一天；默认在消费前 | `householdGoods`（供方 debit；同格池可能从别人家户账取）、`operatorGoods`（经营者自己那层 debit，或转移的 credit 端）、`consumedGoods`、`ProductionLedger`（input） | 入口 `:1507-1543`；调查 `surveyInputDemands` `:1685-1758`；同格争用开池+比例配给 `rationContestedInputs` `:1762-1793`；落账 `drawOneIndustryInputs` `:1805-1923`；`recordInputDraw` `:2127-2170`（`applyTransfer` `:2152`，直接扣 `:2164`）；`recordOperatorInputDraw` `:2179-2190` | **同格多产业争同一种中间品**：只有 `quantity>=2` 且 relation 指了名才开池（`:1766-1770`），按“需求比例 + 最大余数法”配给（`:1785-1789`），`Σquota==池存量`；取用次序固定为“自己的经营者账 → relation 名下家户账（按人口分派）→ 同层按持仓降序补齐 → 池里别人名下按持仓降序”（`:1855-1920`）。与消费争的是同一本家户粮/布账，但**投入在消费前**（默认）⇒ 种子/原料先划走，剩下的才吃饭 |
| **消费** `consumeOwnStock` | 第 1 步，紧跟投入 | `householdGoods`（-粮、-布）、`consumedGoods`、`unmetToday`/`deficitToday`、`rows`（写当日 naturalNeeds） | `:2423-2463`；`setStock` `:2439/:2447` | 与投入、与市场卖单争同一本家户账；市场卖单生成时会扣 `necessary`/`life`（见 §1.2） |
| **收获 + 契约分账** `harvest` + `ProductionSettlement.settle` | 周期末（第 3 步） | `householdGoods`/`operatorGoods`（净产 `creditOutput`，然后按 relation 实付转移）、`householdMoney`/`operatorMoney`（货币档规则）、本行 `income` 累加器、ledger（gross/loss/outputAccrual/transfer/ruleReading） | `harvest` `:3135-3235`；`creditOutput` `:4177-4200`；`ProductionSettlement.settle` `:332-390`；付款次序 `inPaymentOrder` `:526-530`；货币档 `settleMoneyRule` `:461-...`；转移落账 `:3215` | **契约分账内部按 `priority` 升序、同 priority 按 relation.rules 表内先后**（稳定排序）；`OPERATOR_SURPLUS` 档读“已付”，所以**同一优先级交换顺序会改实得数**（注释 `:45`、`:616` 明说）。同一 operator 的 **货币**在同一步里被多条规则按序分（`paidMoney` 累减），当天后面市场只能花剩下的 |
| **市场撮合** `clearOncePerCycle` | 每 5 天一轮 + 粮覆盖<10 天时窗口第 3 天追加（`triggerFor`） | 四张余额 `householdGoods/Money`、`operatorGoods/Money`（货腿+钱腿，经唯一 applier）；**四张冻结** `householdFrozenGoods/Money`、`operatorFrozenGoods/Money`（挂单冻结/成交释放/轮末还原）；`shipments`（跨区成交入在途）；`unmetToday`（家户买到即冲减）；ledger | 入口 `MarketSettlement.java:435`；建单 `:454-491`；冻结 `commitFreezes` `:769-826`；释放 `releaseAllFreezes` `:827-836`；`refresh*Frozen` `:838-857`；区内 `matchWithinRegions` `:865-904`；跨区 `matchAcrossRegions` `:935-1004`；一笔成交 `executeTrade` `:1186-1353`（货腿 `applyTransfer` `:1213`，跨区装载 `loadInTransit` `:1228`/`:1355-1381`，钱腿 `applyTransfer` `:1245`，运费 `applyTransfer` `:1266`，`shipments.put` `:1321`） | **四张冻结表只有这里写**。买方的买单调 `spendableMoneyOf`（余额−冻结货币）分预算；同一 owner×currency 的多张买单若请求和 > 可花，按请求额比例切（`:806-809`）。卖单冻结同一 `(seller, commodity)` 的全部剩余（`:838-847`）。成交时先释放该笔冻结再走 `applyTransfer`，轮末 `releaseAllFreezes` 归位 |
| **借粮** `lendDeficits` | 市场之后、还债之前（最后手段） | `householdGoods`（债权人 debit、债务人 credit 后立刻 consume）、`debts`（按 `(周期,债务人,债权人,粮)` 聚合本金）、`consumedGoods`、`unmetNeed`/`borrowing`（冲减缺口/累加） | `:2507-2620`；贷款转移 `applyTransfer` `:2588`；立刻消费 `consumeFromHousehold` `:2593`；`debts.put` `:2601`/`:2614`；`rows.put` 挂 debtId `:2612` | 同一债务人同周期多次借入累加到同一条 `Debt`；可借额度三路取小（缺口、债权人余粮、信用线），放贷序列“地主→富农→中农、同档行序”（`:2536-2544`）⇒ 债权人耗尽可能让后面的债务人借不到 |
| **还债** `repayDebts` | 借粮之后、仅关账日 | `householdGoods`（债务人 debit、债权人 credit）、`debts`（本金减少）、`repaid` 累加器 | `:2752-2805`；`applyTransfer` `:2794`；`debts.put` `:2801`；`repaid.merge` `:2802` | 预算 = min(本日到手粮所得×200‰, 当前粮库存)（`:2770-2773`，常量 `:297`）；再逐条按行内 `debts` 引用序偿还（`:2774`） |
| **计息** `chargeInterest` | 第 5 步，仅关账日、在偿还之后 | `debts`（本金并入利息）、`interestToday` 累加器 | `:3864-3882`；`debts.put` `:3879`；`interest.merge` `:3880` | **不动任何商品/货币/冻结/在途**。本金取“当日起始快照”（`:660-665`，在借/还之前建）⇒ 当天新借不计息；当天还掉的本金**当天照样计息**（`repay` 在前、interest 读旧快照） |
| **人口回写** `applyPopulationChange` | social 月度结算后由 app 协调器调用（每 30 天；不是日链固定位） | `rows`（人口∓、行 labor 按存活比缩）、`flows`（births/deaths）、`allocations` + `laborSupply`（按批次存活比例缩）、其余表原样带过 | 入口 `:1134-1218`；`rows.put` `:1195`；`flows.put` `:1197`；`scaleLaborOfGroup` `:1200`；被应用的 `scaleLaborOfIndustry` `:1320-1340`、`scaleLaborOfGroup` `:1359-1395` | 不动商品/货币/冻结/在途（`:1215-1217` 明写原样带过）。它通过改 **行人口** 影响下一日消费/市场生活保留，通过改 **配额** 影响下一日投入与生产 |
| **饿死 + 劳动缩放** `applyFamine` + `scaleLaborOfIndustry` | 市场→借→还全部走完后（第 4d 步） | `rows`（人口↓、行 labor 同比例）、`deathsToday`、`allocations`、`laborSupply` | `applyFamine` `:3804-3833`；`rows.put` `:3830`；缩放调用 `:955-961` | 只把已经“所有救济都没补上的缺口”折成死亡率（默认致死率 0）；不改商品/货币 |
| **app 落盘（账务收口）** `PopulationEconomyTimeParticipant` 日循环 | 每个 step 之后 | `ActorData` 的 `accounts`：先 `OwnershipBooks.fold(ledger)` 折“非市场转移 + 产出计提”再 `apply`；随后按会话副本**绝对值**写回 `landHouseholdGoods` → `landHouseholdMoney` → `landOperatorGoods` → `landOperatorMoney` | 日循环 `PopulationEconomyTimeParticipant.java:209-253`；`fold` `OwnershipBooks.java:117-147`；`apply` `:149-200`；`land*` `:264/:360/:495/:547` | ★ **`MARKET_TRADE` 明确不折**（`OwnershipBooks.REASONS_NOT_FOLDED`，`:90`）：市场效果已由会话副本绝对值覆盖，再折一次会在“买方×卖方格”上造异地幽灵账（跨区/在途）。四个 `land*` 的**商品先、货币后**不能反（同一本 `GoodsAccount` 的两个余额表） |

### 1.2 按“同一主体”的竞争面归纳

- **家户 `(CohortKey)` 商品**：到货(+)、投入(-)、消费(-)、关系分账(±)、市场卖(-)/买(+，跨区成交立刻 `loadInTransit` 再 -)、借粮(债权人-、债务人+ 后立即-)、还债(债务人-、债权人+)。同一天这些操作按 §1.0 的串行链读写同一本家户账；市场卖单的“可卖量”在当天链末重新按
  `max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)` 现算（`MarketSettlement.java:703-713`），所以前面投入/消费的扣减会被市场看见。
- **家户/经营者货币**：契约分账的货币档（`settleMoneyRule`，`ProductionSettlement.java:461`）先付；市场买方再按 `余额 − 冻结货币` 算预算（`ordersFor` 里 `:723` 调 `spendableMoneyOf`，实现在 `:1889-1893`；同 owner×currency 的多张买单在 `commitFreezes` `:784-793` 合起来封顶），成交钱腿和运费各自 `applyTransfer`；借粮/还债/计息都不动货币。货币没有“透支”通道（`validateApplyTransfer` 会先问 `MoneyIssuance.requireIssuerOf`，本批恒抛，`EconomySettlement.java:3427-3438`）。
- **经营者商品**：投入第③零层先从经营者自己的账取（`drawOneIndustryInputs` `:1845-1857`）；收获净产 `creditOutput` 加回经营者账（`:4177-4200`）；契约分账的实物/货币腿从 operator 账扣（`harvest` 调 `applyTransfer` `:3215`）；市场买卖也吃同一本经营者账。⇒ operator 的账是“投入、产出、分账、市场”四方竞争面。
- **四张冻结**：只有市场写（`commitFreezes`/`releaseAllFreezes`/`refresh*Frozen`）；`applyTransfer` 的校验会在扣到冻结以下时抛（`validateApplyTransfer` `EconomySettlement.java:3370-3535`，冻结判定在 `:3398-3402` 家户商品、`:3437-3441` 家户货币、`:3477-3481` 经营者商品、`:3517-3521` 经营者货币）。
  ★ **代码观察（未构造冻结非空状态实测）**：`consumeOwnStock`、`consumeFromHousehold`、`recordInputDraw`、`recordOperatorInputDraw` 是绕过 `applyTransfer` 的直接扣减路径，它们不读冻结表。若推进开始时 actor 侧带着持久冻结（真实 M2 档没有，实测全 0），这些直接扣减可能把余额扣到冻结额以下，随后 `land*` 回写时 `GoodsAccount` 的“冻结 ≤ 余额”构造守卫会抛。由于日链里市场在消费/投入之后，当轮市场自己产生的瞬时冻结不会遇到这个路径；风险只在“基态就有冻结”的形态。
- **`shipments`**：到货装载（只出）、市场跨区成交（只入/合并）；同 `(from,to,commodity,arrivalTick)` 合并为一批（`MarketSettlement.java:1309-1325`），`shipmentId` 由 `sh-<day>-<seq>` 按成交插入序生成（`:1316-1320`）。
- **`debts`**：借粮（本金+、新条/聚合）、还债（本金−）、计息（本金并息）。默认顺序是先借、再还、最后按**当日起始**本金计息 ⇒ 同一天“借的先拿到、还的不减今天利息、利息只按昨天本金算”。

### 1.3 一个容易忽略的竞争：同格中间品“池子”

`drawCycleInputs` 的 H6-lite 是本次调查里唯一显式处理“同格多需求方争同一商品”的机制：
- 争用判据：同格、同商品、`demanders.size() >= 2`、每个 demander 的 relation 至少指名一本账（`EconomySettlement.java:1766-1770`）；只有这些商品才开池。
- 池 = 各 demander `ownKeys` 的并集（保序去重，`:1771-1776`）；需求方“可供量”改成“自己经营者账 + 池内余额”（`:1779-1792`），规模按池子算。
- 配给：`D > P` 才按需求比例 + 最大余数法切（`:1785-1789`），否则按各自 need；第三遍取用次序是“自己账 → relation 指定账（人口比例）→ 同层持仓降序补齐 → 池里别人账持仓降序”（`:1855-1920`）。
⇒ 这条是“同价/同需求者并列”的代码判据来源：**不是先到先得**，是比例配给；但残差与补齐的先后仍由名单序决定。

---

## 二、市场同价订单与劳动配额的遍历顺序是否影响结果

### 2.1 市场：没有价格排序，只有名单序 + 最大余数法

**订单生成**（`MarketSettlement.ordersFor`，`:679-757`）：

- 参考价 `reference = market.priceOf(commodity)`；`bid = market.bidPriceOf`、`ask = market.askPriceOf`
  （`:684-685`）。每个参与者每个商品**最多一条卖单 + 一条买单**：
  - 卖：`sellable = max(0, stock − frozen − necessary − life)`；`SellOrder.minPrice = bid`（`:703-713`）；
  - 买：缺口 `gap = max(0, target − available − incoming)`；`quantity = min(gap, budget×1000/reference)`；
    `BuyOrder.maxLandedPrice = ask`（`:714-745`）。
- 代码里**没有** `SellOrder`/`BuyOrder` 的按价格 `sort` 或 `Comparator`。`bid/ask` 只做 `match` 里的
  `>=`/`<=` 过滤。所谓“同价”不是撮合出来的，而是所有订单都用同一个参考价派生的限价。

**参与者/订单名单序**（谁先）：

1. 格：`clearOncePerCycle` 把 `markets.keySet()` 拷出来按 `(q,r)` 升序排（`:454-455`），
   与 `markets` 这个 `LinkedHashMap` 的插入序无关；
2. 每格参与者：`participantsFor`（`:1655-1682`）先按 `keys` 顺序放家户，再追加经营者；
   `keys` 来自 `rowsByHex(round.rows.keySet())`（`EconomySettlement.java:3972-3993`）：
   - 格键自然序（`hexKeys.sort(Comparator.naturalOrder())`，`:3983`）；
   - 同格行序 = `(stratum.value 字典序, residence)`（`:3987-3989`）；
   - 经营者来自 `IndustryHexKeys.at(...)`，该方法把该格全部产业 id **按 id 字典序**排（`IndustryHexKeys.java:69-76`）⇒ 经营者顺序也与 `industries` 表插入序无关。
3. 每格每商品：`ctx.buys`/`ctx.sells` 的追加顺序 = 参与者顺序（每个参与者卖单先于买单）。同一商品的相对顺序**不受 `market.prices()` 键序影响**，因为撮合按 `(region, commodity)` 过滤，商品循环本身用 `orderedCommodities` 的 id 字典序（`:1937-1945`）。

**区内撮合**（`matchWithinRegions` `:865-904`，`matchGroup` `:906-933`）：

- 价格 = **集散节点格的参考价**（`:871-876`）；
- 买方过滤 `maxLandedPrice >= price`（`:882`）、卖方过滤 `minPrice <= price`（`:892`）⇒ 正常时全过；
- 双方各自按“可负担量/剩余量”为权重，`ProportionalSplit.byDenominator(matched, weights, demand)` /
  `(matched, sellWeights, supply)` 切（`:928-929`）；
- `pairUp`（`:1130-1184`）按买方表序逐个消耗，卖方按卖方表序逐个消耗（`sellerIndex` 单向）；
- `executeTrade`（`:1186-1353`）不再看限价，成交价就是传入的 `price`。

**最大余数法的并列断法**（`ProportionalSplit.java`）：

- `parts[i] = total×w[i] ÷ Σw` 先向下取整（`:53-58`）；
- 残差先人人摊 `remainder/n`，再按**余数降序、同余数下标升序**给前 `remainder%n` 名 +1（`:69-99`）。
  ⇒ **同权重/同余数的并列按该数组的下标序**，即上面的名单序；**不是**按 actor id、也不是 HashMap 序。

**跨区撮合**（`matchAcrossRegions` `:935-1004`）：

- 商品按 id 字典序（`:942`）；买方格、卖方格都按 `(q,r)` 升序（`:949`、`:972`）；
- 每条路线 `matchRoute`（`:1006-1128`）按轮数、运力、限价、ETA 过滤，成交单价 = 卖方格参考价的最大值
  `unitPriceOf`（`:1415-1423`）；
- 承运人 `carrierOf` 在多个 `ORGANIZATION` 时按 actor id 字典序取第一个（`:1925-1935`）；
- 同一条路线内部也是 `ProportionalSplit` + `pairUp`。

**明确判定**：

| 问题 | 判定 |
|---|---|
| 同价时谁先？ | 先由**名单序**决定：格 `(q,r)` → 家户行 `(stratum, residence)` → 经营者 `industryId` 字典序；同余数按数组下标升序（`ProportionalSplit`）。价格本身不参与排序。 |
| 会不会影响结果？ | **对总量**：最大余数法保证 `Σparts==total`；同权重下交换顺序只可能把 ±1 最小单位的残差换给另一个同余数项 ⇒ 各主体总量最多差 1 个最小单位。**对成交配对/读数**：会——`pairUp` 按表序配对、`ctx.fills` 按执行序追加，换名单序会改变“哪个买方与哪个卖方”的 `Fill`，但同一 `(region,commodity)` 的总成交、总付款不变（除并列残差）。**跨区**：买方格/卖方格按 `(q,r)` 排序，先处理的买方格先拿稀缺供给 ⇒ 排序序就是口径，不会随 JVM/hash 变。 |
| 可复现吗？ | **给定同一份状态与代码，是**。相关顺序全部来自显式排序或保序表；`simos-economy/.../economy/time` 没有裸 `HashMap` 迭代（见 §2.2 末尾的 grep 结果）。**换一份“逻辑相同但 map 插入序不同”的输入**时：市场部分仍然复现（`rowsByHex`/`IndustryHexKeys.at` 都排序）；劳动再分配不是完全插入序无关（见 §2.2）。 |

### 2.2 劳动配额：哪些并列、怎么断

**`reallocateLabor`**（`EconomySettlement.java:2840-2940`，只在周期第一天做）：

1. 产业按格分组：`industriesByHexMap(industries)`（`:1245-1251`）**按 `industries` 表的插入序**遍历并建 `ids` 列表，**没有排序**；
2. `allocated`：遍历 `allocations.values()` 按 actor id 求和（`:2858-2865`）——求和与顺序无关；
3. `need[i] = laborNeedOf(industry, allocated)`（`:2867-2868`）；
4. 保留/回池：遍历 `new ArrayList<>(allocations.keySet())`，每个 `(批次,产业)` 保留 `min(配额, need−已保留)`，超出回 `pool`（`:2872-2894`）；`pool` 是 `PeopleLotId → 量` 的 `LinkedHashMap`，键序 = 配额表遇到次序；
5. 回池分配：`byGap` 先按 `-缺口` 降序、**同缺口按 `IndustryId.value()` 字典序**（`:2897-2899`）⇒ 缺口并列的断法是**产业 id 字典序**，可复现；
6. `lastResort` = `ids` 中**第一个** `recipe.outputPerUnit().containsKey(GRAIN)` 的产业（`:2902-2906`）⇒ 若同格有多个产粮产业，这里断的是 **`ids` 的插入序**（`industries` 表序），不是 id 序；这是本步唯一一处“插入序会咬人”的地方；
7. `pool` 里每个批次按 `byGap` 顺序尽量填，填不完的给 `lastResort`，再填不完 = 失业（`:2909-2933`）；
8. `addLabor` 对已有 `(批次,产业)` 累加；没有则按 `LaborAllocation.idOf(industry, group)` 新建并追加到 `allocations` 表尾（`:2991-3019`）；新条目的 `activity`/`period` 从该产业的既有条目抄（`templateAllocationOf`，`:3025-3032`，按 `allocations.values()` 序取第一条）。

⇒ **判定**：劳动配额的“缺口大者先得”并列已按 id 字典序钉死（可复现）；但“最后雇主”在多个产粮产业时按产业表插入序，`addLabor` 新条目的插入位置也受遍历序影响。真档每格通常只有一个产粮产业（`farm@`），所以实测复现；这是“约定可复现”而不是“结构性顺序无关”。

**`appendAllocation`**（创世/发配额；`simos-app/.../world/EconomySeeder.java:1117-1169`）：

- 权重 = 该批次的 `participationAdjustedLaborMilli` × `ACTIVITY_SEX_WEIGHT_PER_MILLE[activity][sex]`（`:1129-1140`）；
- 份额 = `splitProportional(total, weights)`（`:1146`），即
  `ProportionalSplit.byDenominator(total, weights, Σweights)`（`EconomySeeder.java:1752-1778` 的 `split`）；
- **预算封顶按 `workers` 顺序逐个做**：`share = min(shares[i], budget 剩余)`，多出来的留在预算里（`:1150-1154`）。
  ⇒ 预算不足时“分不满”的缺口落在**池里靠后的批次**上；`workers` 顺序 = 传入 `pool` 的顺序（每格的 `ruralPool`/`urbanPool`，来自 social 批次表顺序）。
- 并列残差同样走 `ProportionalSplit` 的“同余数下标升序”。

**`apportionToHouseholds` + `byStockDescending`**（投入第三遍内部）：

- `apportionToHouseholds(plan.ownKeys, rows, remaining)`：权重 = 行人口，`ProportionalSplit.byDenominator`（`:2069-2093`）⇒ 残差按下标（`plan.ownKeys` 顺序）断；
- `plan.ownKeys` 来自 `supplierAccountsOf`：`ToCohort` 单元素；`ToActor` 若命中家户 actor 单元素；聚合主体走 `householdKeysOf`，其顺序 = `SocialClassId.all()` × `ResidenceKind.all()` 的固定枚举序（`:2237-2253`、`:2258-2272`），与配额表条数无关；
- `byStockDescending`（`:2099-2107`）：持仓降序，**并列保持传入序**（`List.sort` 稳定）；
- `drawFromKeys` 按这个序列取料（`:1928-1958`）。

**`grep` 结果（用户点名的命令）**：

```text
$ grep -rn "HashMap" simos-economy/src/main/java/io/mosire/simos/economy/time
→ 201 命中，全部是 `LinkedHashMap`（导入/变量/注释）；`grep -v LinkedHashMap` = 0 命中。
$ grep -rn "HashSet" simos-economy/src/main/java/io/mosire/simos/economy/time
→ 只有 `EconomySettlement.java:41` 的 `import java.util.HashSet` 与 `:763`/`:783` 两个局部
  `new HashSet<>()`（`newCycleIndustries` / `newCycleHouseholds`）；两处只做 `add`/`contains`/
  迭代都在 `contains` 上（`:792`、`:939`、`:980`、`:1011`），没有“拿 HashSet 当顺序”的迭代。
其余集合都是 `LinkedHashMap`/`LinkedHashSet`，或在排序前显式 `sort`。
```

⇒ `economy/time` 内没有 `HashMap` 迭代序泄漏到结算结果。**跨 JVM 的字节差异来自别的切片**：
`simos-map` 的 `Region.hexes = Set.copyOf`（快照数组序）与 `simos-app` 的
`CrisisMonitor.Light.evidence = Map.copyOf`（视图对象键序），都不是状态值差异（§三实测）。

---

## 三、固定输入重复运行的最终状态是否逐字段一致（实测）

> 结论先说：**状态逐字段一致（逐模块 `equals` 为真、全量 ownership 逐字节相同）；原始 dump/快照
> 字节有两处“表示层”差异。** 下面把装置、命令、产物、差异定位全部列出。

### 3.1 装置与输入

- 输入：`store-m2`（`.superpowers/sdd/2026-09-27-m2-general-market/store-m2`），
  原始库 `simos.db` 大小 158,875,648 B，md5 `eef6889bbff0091a168847bca1022c25`；
  时间线 12 行（复制时 rev 11 tick 360；rev 1 创世，rev 2-4 worldgen，rev 5 tick 180，rev 6-11 每 30 天到 360）。
- 两份副本：`cp -a` 到 `/tmp/store-det-a`、`/tmp/store-det-b`，起跑前两边 `simos.db` md5 与源相同
  （都是 `eef6889b…`）。
- 运行态 jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`
  （mtime 2026-09-28 01:25:54；`find src -newer jar` 无命中 ⇒ 用现有 jar，未跑 Maven、未重新打包）。
  服务以**仓根**为 CWD 起（worldgen 配置路径用；本轮没有跑 worldgen，store 已是播种后的态）。
- 端口/进程：
  - A：`tools/run-shaded.sh <jar> --store /tmp/store-det-a --gui-port 5847 --mcp-port 5745 --approval-port 5743`；
  - B：同 jar，`--store /tmp/store-det-b --gui-port 5857 --mcp-port 5755 --approval-port 5753`；
  - 两边都在 `curl /`、`/index.html`、`/styles.css`、`/api/map/overview`、`/api/units`、`/api/timeline`
    全 200 后才推进。
- 推进：各执行一次 `python3 /tmp/det-{a,b}/v3curve_advance.py 390`（就是一次
  `simos.advance{from:360,to:390}`）：
  - A 用时 189.4 s，返回 revision 12 tick 390；
  - B 用时 182.7 s，返回 revision 12 tick 390。
  - 两边都只有一个新 revision（没有拆成多段）：`rev 12 tick 390 core.AdvanceTime`。

### 3.2 逐层比对结果

#### (1) 新 revision 的 `changeset_json`：逐字节相同

用 Python `sqlite3` 读 `revisions` 表（本机没有 `sqlite3` CLI；`.schema` 只有
`revisions`/`events`/`store_meta` 三张表，**没有 snapshot 列**——DB 里存的是每个 revision 的
`changeset_json`，不是全量快照）：

```text
rev=12 tick=390
changeset_json 长度 = 9,866,607 B（两边相同）
raw md5 = ee81091287cb8716cc546a7f8b1a084e（A、B 相同）
canonical（json.loads + sort_keys）md5 = ac5b857d9bf6f86a3fdc39bb355d4023（A、B 相同）
列差异只有 command_id / correlation_id：
  A: ff785a4a-...   B: ea2be913-...（都是本次命令随机 UUID，不是状态）
其余列 branch/revision/parent_branch/parent_revision/tick/calendar_label/command_type/initiator 全同。
该 changeset 的顶层 `modules` 键 = `actor / economy / sd / social / unit` 五片（map 本轮没有被写；
map 的终态由 §3.2(2) 的全量重建覆盖）。
```

rev 1-11 是复制来的基态，逐行 `changeset_json` raw 也本就相同；rev 12 的 raw 相同意味着
**在相同基态上两条推进产生了逻辑同值的变更集**。

#### (2) `Replay` 重建的全量快照：`SimulationState.equals == true`，六模块全等

写 `/tmp/probe/.../StateCompare.java`（用同一个 shaded jar 编译到 /tmp），对两边各自
`Replay.main@revision12`，在同一进程里比较：

```text
SimulationState.equals = true
meta.equals = true
info.equals = true
actor:    present=true/true equals=true
economy:  present=true/true equals=true
map:      present=true/true equals=true
sd:       present=true/true equals=true
social:   present=true/true equals=true
unit:     present=true/true equals=true
```

覆盖的六片内容（由各自 codec 的 record 形状决定）：

| 切片 | 覆盖内容（本批实际形状） |
|---|---|
| `map` | `GameMap`：`hexes`、`terrainBlocks`、`regions`、`cities`、`terrainTypes`、`pathways`、`pathwayGroups`、`edges` |
| `social` | `SocialData`：批次 `groups`、城市 `cities` 等 |
| `unit` | `UnitState`：单位、指挥链、路线/移动状态 |
| `sd` | `SdState`：nations、armies、combats、combatStates、decisionMakers、directives、effects、verdicts、lossRecords |
| `economy` | `EconomyData` 十组件：meta、industries、classes、debts、flows、laborSupply、allocations、relations、markets、shipments |
| `actor` | `ActorData` 四组件：meta、actors、holdings、accounts |

#### (3) 全量快照 JSON 的原始字节：有“集合排列”差异，无值差异

`StateDump` 用 `CheckpointEncoder.encode(state, codecs)` 各写一份 `snapshot.json`：

```text
两边长度都 = 28,464,061 B
A md5 = b0d030da072381fcdf3162f6ae0949e2
B md5 = c33eac2fae8970aa31acb8782c1854b7  （不同）
```

用 JSON 递归 diff（对象按键匹配、数组逐项；数组若为排列则记为 permutation）：

```text
value/permutation 差异 = 227 条
全部是 /modules/map/map/regions/<区域>.hexes 的 permutation
（例：葡萄牙帝国、青丘伯国、区域1354325345234、大沪王朝……；每条长度在 12~190 之间）
dict-key-order 差异 = 0 条
```

首字节差异位置 `21,916,481`，上下文正是 `map.regions."大沪王朝".hexes` 的数组顺序不同
（A 以 `{"q":-77,...}` 开头，B 以 `{"q":-74,...}` 开头）；两边是同一元素集合的不同顺序。
`Region.hexes` 的构造是 `Set.copyOf`（`simos-map/src/main/java/io/mosire/simos/map/region/Region.java:28`），
其迭代序在跨 JVM 时不保证；这是**表示层**差异。
把两份快照的 `map.regions.*.hexes` 数组各自按 `(q,r)` 归一后再 canonical：

```text
normalized canonical 长度 = 28,376,591（两边同）
normalized md5 = 2872b4717875ec024ae403c405c28212（两边同）
```

#### (4) 全量 actor 产权账（账户/冻结）：md5 逐字节相同

`StateDump` 另写 `ownership.json`：从 revision 12 的 `ActorData.accounts()` 取**全部**
8191 本 `GoodsAccount`，按 `(location.q, location.r, owner.toString())` 排序，账户内的
`goods`/`money`/`frozenGoods`/`frozenMoney` 键再排序，字段：`q/r/actor/kind/goods/money/
frozenGoods/frozenMoney`。

```text
accountCount = 8191（A、B 相同）
A md5 = 02c0c109615198d4dc1af5a972dced84
B md5 = 02c0c109615198d4dc1af5a972dced84  ← 逐字节相同
有商品余额的账 = 5505；有货币余额的账 = 5257；frozenGoods/frozenMoney 非零的账 = 0（A、B 同）
```

对照 HTTP `/api/economy/ownership`（GUI 5847 / 5857）对三国全部区域格（430+138+231=799 格）
的完整输出：

```text
A/B 文件都是 2,048,689 B
md5 都是 97b20164c774845e6fba1b2550858511  ← 逐字节相同
A/B 各 8191 本账户（与 StateDump 的全世界账户数一致），有账户格 = 799/799
```

⇒ **账户/冻结面（含派生栏位 `availableGoods`/`availableMoney`/`moneyLayers` 等）逐字节一致**。

#### (5) 逐格经济/社会读数组件：canonical 一致，原始字节有一处键序差异

`h6sim_dump.py` 的两个副本（GUI 5847/5857）各 dump 三国 799 格的
`/api/social/population` + `/api/economy/hex`：

```text
A/B 文件都 15,240,696 B
A md5 = 6c96d06f190ecab0f5fb260640389618
B md5 = e3e4bace7461364820693442280bd15f  （原始字节不同）
canonical（ensure_ascii=False, sort_keys=True, separators=(',',':')）两边 md5 都是 464b2a2ac363634969525152bae0bc2a
按“对象键匹配、数组逐项”递归 diff：value/permutation 差异 = 0
```

原始字节的首个差异位于 `5,903,470`，上下文是 `social.crisis[0].evidence` 的**对象键序**：
A 以 `grainSatisfactionPerMille` 开头，B 以 `grainUnmetMilli` 开头；递归检查发现
**12 处** `crisis[].evidence` 的对象键序不同，全部是 `Map.copyOf` 的无序结果——
`CrisisMonitor.Light` 构造器第 `305` 行 `evidence = Map.copyOf(...)`
（`simos-app/src/main/java/io/mosire/simos/app/crisis/CrisisMonitor.java:305`）。
该 record 的 Javadoc 写“键序保序、可复现”，与 `Map.copyOf` 的实际语义不符；这是**读口表示层**
非决定性，不是状态。

读数组件覆盖的字段：`economy.hex` 的
`q/r/activated/tick/lastSettledDay/population/laborMilli/participationAdjustedLaborMilli/landMilliMu/
goods/grainStock/grainDailyConsumption/cycleNaturalNeedMilli/cycleNaturalNeedClock/
grainDailyConsumptionClock/actorMoneyTotal/moneyLayers/market/debtCount/debtPrincipal/creditCount/
creditPrincipal/commodityIds/currencyDefs/moneyInstruments/classes/industries/marketReadout/
grainDiagnosis`；`social.population` 的 `population/groups/labor/crisis`。
其中 `marketReadout` 的逐区逐商品供给/需求/成交/到货价/运费/损耗/未成交原因也被一并比较。

### 3.3 一句话把“状态不同”与“表示不同”分开

| 比较对象 | 原始字节 | 语义/状态 | 结论 |
|---|---|---|---|
| rev12 `changeset_json` | 相同（raw md5 `ee810912…`） | 相同 | **状态相同** |
| 六模块 `Snapshot.equals` / `SimulationState.equals` | — | 全 true | **状态相同** |
| `ownership.json`（8191 账） | 相同（`02c0c109…`） | 相同 | **状态相同** |
| HTTP ownership 799 格 | 相同（`97b20164…`） | 相同 | **状态相同** |
| `h6sim_dump` JSON | 不同 | canonical 相同、值 diff 0 | **表示不同**（crisis evidence 键序，`Map.copyOf`） |
| `CheckpointEncoder` 全量快照 | 不同 | `equals` true；227 处 map 区域 hex 数组排列 | **表示不同**（`Region.hexes = Set.copyOf`） |

### 3.4 必须连带说明的“dump 非逐字节”来源

- `map.regions.*.hexes`：`Region` 有意用 `Set.copyOf` 做集合语义（`Region.java:14-28` 的类注），
  跨 JVM 迭代序会变；这是**已有设计选择**，影响的是快照字节，不是 `GameMap` 的值。
- `CrisisMonitor.Light.evidence`：`Map.copyOf` 的迭代序不保证；类注却说“键序保序、可复现”。
  这是一个**可改进的读口口径问题**（要字节稳定应该用 `LinkedHashMap` + `unmodifiableMap`，
  或读口排序输出）；本次只读调查，未改代码。
- 这两处都不是“结算结果不同”：所有值相等、成交/余额/债务/人口都相等；`SimulationState.equals`
  是按 record/map/set 的值语义比较，不受迭代序影响。

### 3.5 对比覆盖了哪些状态面 / 没覆盖哪些

**覆盖：**

- **全量状态六切片**：`map/social/unit/sd/economy/actor`（通过 `Replay` + `CheckpointEncoder`，
  并逐片 `Snapshot.equals`）；
- **全量 actor 产权**：8191 本 `GoodsAccount` 的余额、货币、冻结、地点、actor；
- **三国 799 格的经济/社会读数组件**（`economy.hex` 全字段、`marketReadout`、`grainDiagnosis`、
  `social.population`、`crisis`）；
- **DB 的 `revisions` 表**：rev 1-12 全部行（新 rev12 的 `changeset_json` raw 比对），
  以及 `store_meta`（`time_base=DAY`、`format_version=1`）；
- **checkpoint 文件**：两边只有 `checkpoints/main/1.json`（创世 checkpoint，rev12 未到 interval 100），
  md5 都是 `6d9cec85b0550b73d63aa4d74be2b8c2`；
- **两边的服务日志**：除 A 起服务时 readiness 循环的一次 `Connection reset by peer` WARN 外，
  没有结算异常；两边都成功提交 rev12。

**没覆盖 / 不能从这次实验下结论的：**

- **`events` 表**：两边都是 72 行，但 `ts` 是墙钟时间、`payload`/correlation 可能含非状态信息，
  本轮没有逐字段比对（它不是模拟状态）；
- **`simos.db` 原始文件字节**：SQLite 页布局、WAL/checkpoint 时点、`command_id`/`correlation_id`
  随机 UUID、`events.ts` 都会让整库 md5 不同；本轮比的是**状态面**而不是整库字节；
- **`conversations.db`、`agentlib/config*.json`**：没有比对（非经济状态；本轮也没触发 LLM/会话）；
- **非 checkpoint 的中间 revision 全量快照**：只在 rev12 上比；rev 1-11 只比了 `changeset_json`
  和复制自带的一致性（基态 md5 相同）；
- **多次重复**：A/B 各只跑了一次（两个独立 JVM 进程）；没有做同一侧跑 3 次、也没有换机器/换 JDK；
  “跨 JVM 表示差异”只观测到 A/B 这一次，但两处机制（`Set.copyOf`、`Map.copyOf`）本身是跨 JVM
  不保证序的；
- **不同命令粒度**：只跑了 `360→390` 一次；没有复跑“30 天 × 1 次”对“10 天 × 3 次”“1 天 × 30 次”
  的路径等价（那是 M0.1 已有判据，不在本次调查单）；
- **顺序敏感性**：本节没有构造“打乱 map 插入序/名单序”的实测变异体；§二关于“换序会怎样”的判定
  是回代码得出的，不是实测。**本轮实测的是“固定输入、固定代码、两个进程”的一致性**。

---

# 第二部分：第 3 题（bid=990‰ / ask=1010‰ 的口径与一笔成交）

## 四、`bidPriceOf` / `askPriceOf` 是什么

### 4.1 代码定义

`simos-economy/src/main/java/io/mosire/simos/economy/model/Market.java`：

```text
priceOf(commodity)        : 参考价 p（毫计价货币/商品单位）；没有定价 ⇒ 0（该格不交易它）  :71-73
bidPriceOf(commodity)     : p <= 0 ? 0 : max(1, p × 990 ÷ 1000)                          :87-90
askPriceOf(commodity)     : p <= 0 ? 0 : max(bidPriceOf + 1, ⌈p × 1010 ÷ 1000⌉)          :104-111
BID_PER_MILLE = 990        :118
ASK_PER_MILLE = 1010       :125
```

- 两个数**不是**“订单里已有买卖双方各自报的价”，而是**由该格参考价现算的挂牌限价**；
- `bidPriceOf` 的口径是**卖方最低可接受价**；`askPriceOf` 的口径是**买方最高可接受价**；
- 没有做市商、没有库存、没有价差收入：类注明确“价差只表达双方愿意让步的范围，不构成一笔要落账的收入”
  （`Market.java:30-36`、`:76-104`）。

### 4.2 这两个数落到订单的哪个字段

`MarketSettlement.ordersFor`（`:679-757`）：

```text
long reference = market.priceOf(commodity);        // 参考价，成交价来源
long bid = market.bidPriceOf(commodity);           // 卖方底价
long ask = market.askPriceOf(commodity);           // 买方限价
...
new SellOrder(..., sellable, bid, round.day, SILVER_SPECIE);     // minPrice = bid      :706-713
new BuyOrder(..., quantity, ask, deadline, new Budget(...), ...); // maxLandedPrice=ask  :737-745
```

⇒ 用户问的“是订单限价（卖单 `minPrice` / 买单 `maxLandedPrice`）还是参考报价”：
**是订单限价**，而且是**由参考价派生的限价**；参考价本身另存在 `Market.prices()` 里。
限价只做过滤，不决定成交价（类注 `Market.java:96`、`MarketSettlement.java:684-685` 注释）。

### 4.3 成交价取什么

- **区内**：`matchWithinRegions` 的 `price = anchorMarket.priceOf(commodity)`
  （集散节点格的参考价；`MarketSettlement.java:865-876`），`matchGroup(ctx, buys, sells, price, null)`，
  成交即 `price`。
- **跨区**：`matchRoute` 里 `unitPrice = unitPriceOf(sells, commodity)`
  （`:1054`），而 `unitPriceOf` 取该批卖方格参考价的**最大值**（`:1415-1423`，“多价表时宁高不低，
  不让卖家亏本”），成交即这个 `unitPrice`。
- `executeTrade` 不重新比 `minPrice`/`maxLandedPrice`；它在 `match` 过滤阶段已被判过：
  - 区内：`buy.order.maxLandedPrice() >= price`（`:882`）、`sell.order.minPrice() <= price`（`:892`）；
  - 跨区：`buy.order.maxLandedPrice() < unitPrice` ⇒ 该买方这条路线被挡（`:1079`）。
- 付款：`payment = ⌈quantity × unitPrice ÷ 1000⌉`（`:1196`）；量纲 = 毫商品 × 毫钱/商品单位 ÷ 1000
  = 毫钱（整数向上取整）。
- 运费：跨区另算 `freightOf(quantity, route.unitPrice, travelTicks) = ⌈q×p×days×10‰÷10^6⌉`
  （`:1430-1434`；单位运费 `freightPerUnitOf` `:1425-1427`），由买方付；**`maxLandedPrice` 的过滤不含运费**（`:1079` 只比 `unitPrice`，
  且 `ordersFor` 里写明“限价=货款上限；跨区运费另计”，`:736-739`）——字段名 `maxLandedPrice`
  在跨区语义上其实是“货款价上限”，不是“到货价上限”。

### 4.4 同一参考价下为什么仍有实际成交（谁接受谁）

因为**限价是围绕参考价上下各让一步**：正常整数网格上

```text
bid = max(1, ⌊0.99 p⌋) ≤ p ≤ max(bid+1, ⌈1.01 p⌉) = ask          （p ≥ 1）
```

卖方订单接受任何 ≥ `bid` 的价格（`minPrice=bid`），买方订单接受任何 ≤ `ask` 的价格
（`maxLandedPrice=ask`）；系统把成交价定为参考价 `p`。于是：

- **卖方**：拿到的 `p ≥ bid` = 比“自己愿意接受的最低”高，接受；
- **买方**：付出的 `p ≤ ask` = 比“自己愿意付出的最高”低，接受；
- 双方都“比自己的限价占优”，价差没有人截留（没有做市商库存、没有价差收入账户）。

同理，限价并不是“一方报出、另一方必须匹配”的价格：订单不是限价簿撮合，价格的锚是**数据里的参考价**
（`Market` 的类注明确“价格是数据，不是公式”）。

极小价格 p=1 时：
`bid = max(1, ⌊0.99⌋) = 1`，`ask = max(2, ⌈1.01⌉) = 2`，成交价仍是 1；价差退化成“两侧各让 1 毫”，
相对幅度大于 1%，类注（`Market.java:99-101`）已如实说明。

---

## 五、一笔真实成交（探针实测，非重构）

### 5.1 探针怎么跑的

- 在 `/tmp/probe` 写同包（`io.mosire.simos.app.time`）的 `FillProbe.java`，
  用现有 shaded jar 编译；从 `/tmp/store-probe`（`store-m2` 的复制）走
  `SqliteStore.open` → `Timeline(store,100)` → `Replay` → `revision 11`（tick 360）→
  `EconomySnapshot.data()` + `ActorSnapshot.data()`；
- 用 `OwnershipBooks.loadHousehold/Operator Goods/Money/Frozen*` 把四份余额副本 + 四张冻结快照
  载入 `EconomyDayStepper`（构造器带 `MarketTopologyBook.from(state)` 的区域拓扑）；
- 在**内存里**依次 `step(361) … step(365)`，不写任何 DB；第 365 天触发 `PERIODIC` 市场
  （`triggerFor` 的每 5 天一轮），取 `stepper.lastMarketReport()` 的 `MarketReport.Fill`；
- 边界：探针直接跑 `EconomyDayStepper`（就是 app 日循环里经济结算/市场的**同一个实现**），
  没有重放 app 的 `OwnershipBooks.fold/land` 与 social 每日压力/月度结算；361-365 没有月度边界、
  也没有城市/拓扑变化 ⇒ 对第 365 天市场报告没有影响。它证明的是**引擎在同一份 tick-360 状态上的
  真实撮合输出**，不是对 shell 完整 `simos.advance` 的端到端复现。
- 输出 `/tmp/fill-probe-all.txt`（36.7 s）。

### 5.2 一笔布（区内即时）

```text
DAY 365 reportDay=365 trigger=PERIODIC fills=5483 immediate=5483 cross=0 carrierPresent=false
FILL from=-20_-83 to=-19_-83 commodity=cloth
     seller=HOUSEHOLD:-20_-83:rural:middle_peasant
     buyer =HOUSEHOLD:-19_-83:urban:landlord
     qty=44 unitPrice=5
     seller[ref,bid,ask]=[5,4,6]
     buyer [ref,bid,ask]=[5,4,6]
     payment=1 freight=0 immediate=true
```

撮合判断：
- 卖方限价 `minPrice = bid = 4`；买方限价 `maxLandedPrice = ask = 6`；区内成交价 = 集散节点参考价 = 5；
- `4 ≤ 5`（卖方接受）且 `6 ≥ 5`（买方接受）⇒ 成交；
- 付款 `⌈44×5÷1000⌉ = ⌈0.22⌉ = 1` 毫钱（整数向上取整，读出来只有 1；这是“毫”量纲下的正常结果）；
- 买卖双方同区（两个不同 hex，但同一 market region），所以 `immediate=true`、`freight=0`。

### 5.3 一笔粮（同区、极小价格网格）

```text
FILL from=-20_-83 to=-19_-83 commodity=grain
     seller=HOUSEHOLD:-20_-83:rural:landlord
     buyer =HOUSEHOLD:-19_-83:urban:landlord
     qty=1388 unitPrice=1
     seller[ref,bid,ask]=[1,1,2]
     buyer [ref,bid,ask]=[1,1,2]
     payment=2 freight=0 immediate=true
```

判断：`bid=1 ≤ 成交价 1 ≤ ask=2`；两边接受。这正好展示 p=1 时“限价退化成 1/2”的网格边界。

### 5.4 当日全量核验

对第 365 日全部 5483 笔（cloth 2168、grain 2816、fiber 499）逐笔解析：

```text
每一笔的 unitPrice == 卖方格参考价 == 买方格参考价；
每一笔 卖方 bid ≤ unitPrice ≤ 买方 ask；
freight 全 0（本日全部区内即时，cross=0）；
```

### 5.5 旁证（m2t360.json 的重构，明确标注“重构”）

用探针实测优先，下面这条只是**从落盘读数组件重构**、没有 capture 到个别 Fill：
`m2t360.json` 中，区域 `c--54_-67`（成员格之一 `(-55,-66)`，德意志第二帝国）的布：

```text
referencePriceMilli=5, bidPriceMilli=4, askPriceMilli=6
match.tradedMilli=2283, match.landedPriceMilli=5, match.freightMilli=0
```

重构判据：卖单 `minPrice=4 ≤ 5`、买单 `maxLandedPrice=6 ≥ 5` ⇒ 按参考价 5 成交，`landedPrice`=5
与 `tradedMilli>0` 一致。**此条是重构（聚合读数），不是逐笔实测**；逐笔实测见 §5.2/§5.3。

---

## 六、我没做 / 没验证的

- **没有改任何生产代码/测试/设计**：本调查只读；只写了本文件与 `/tmp` 临时物。
- **没有用 Maven**：直接用现有 `simos-app-0.1.0-SNAPSHOT-shaded.jar`（mtime 01:25:54，无源文件比它新）
  跑服务/探针；没有跑 `compile`/`test`/`verify`/`package`，因此没有本轮编译门禁或测试数。
- **Fill 探针不是完整 shell 路径**：它直跑 `EconomyDayStepper.step`（不落盘、不跑 app 的
  `OwnershipBooks.fold/land`、不跑 social 月度）；它证明的是引擎在同一 tick-360 状态上的真实撮合输出，
  不是对 `simos.advance` 全链路的端到端复现。
- **没有做多次重复**：A/B 各跑一次（两个独立 JVM）；没有同一侧跑 3 次、没有换 JDK/机器、
  没有故意改 `-XX:hashCode`/`SALT` 复现 `Set.copyOf`/`Map.copyOf` 的排列差异。两处表示差异只观测到一次，
  但机制本身是“跨 JVM 不保证序”。
- **没有整库字节比对**：没有比 `simos.db` 文件 md5（含随机 UUID 列、SQLite 页布局、`events.ts`），
  没有比 `events` 表逐行、`conversations.db`、`agentlib/config*.json`；这些不是模拟状态。
- **没有比非三国区域格**：`h6sim_dump` 只 dump 三国 799 格；但全量 `Replay` 快照覆盖全部六切片，
  全量 `ownership.json` 覆盖 8191 本账（与 HTTP 三国合计 8191 一致，未发现区域外账户）。
- **没有构造“打乱顺序”的实测变异体**：§二关于换序影响总量的判定来自代码（`ProportionalSplit`/
  `pairUp`/`reallocateLabor`），不是实测；没有把 `industries`/`rows`/`allocations` 的插入序打乱后重跑。
- **没有捕获到跨区/带运费的 Fill**：探针的第 363/365 轮 `cross=0`、`carrierPresent=false`；
  跨区逻辑（卖方格参考价最大值、maxLandedPrice 不含运费、运力分轮）是回代码核对的，不是实测 Fill。
- **没有验 `m2t180.json`**：只用了 `m2t360.json` 做旁证。
- **没有验证冻结非空路径**：真档 `frozen*` 全 0；§1.2 里“直接扣减路径不读冻结”的风险是代码观察，
  没有构造非空冻结状态实测。
- **没有验证 `bid/ask` 的时变**：自适应定价默认关（`priceMode=fixed`），本批没有开；
  若开自适应，参考价会在轮末变，但本报告的口径（限价由参考价现算、成交价=参考价）不变。
- **没有把两处表示层非决定性修掉**：`Region.hexes` 与 `CrisisMonitor.Light.evidence` 只报告，
  未改代码（按只读调查纪律）。
- **没有杀掉别的 agent 的服务**：A 起服务时系统里已有另外两个调查实例（`store-own` 5867/5765、
  `store-prof` 5837/5735），各自独立 store/端口。A 的墙钟 189.4 s、B 182.7 s；是否全程 CPU 争用
  未逐刻记录，但结算不依赖墙钟，A/B 状态仍逐字段相同。我们只按 PID 关闭自己的进程：
  A 的 Java PID 3613、B 的 Java PID 4552（都只 kill 进程，未用 `pkill -f`）。
