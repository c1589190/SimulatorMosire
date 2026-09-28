# 经济系统「演化能力边界」调查报告（只读）

> **性质**：**只读代码调查** —— 回答用户目标在当前代码里的实现边界；**不含设计方案、不改生产代码、不跑整年**。
> **基线**：`docs/superpowers/reports/2026-09-28-economy-system-flow-report.md`、`2026-09-28-economy-investigation.md`。
> **代码态**：`ts/m1` @ `a7fbfe46`（生产代码与 `5b6b2be7` 逐字节同源）。
> **目标原文（用户目标，本文单列，不与代码事实混写）**：
> 「新产品需求出现时，底层家户可能尝试采用候选生产方式；生产持续扩大后，家户因生产资料占有和劳动关系变化而分化。
> 反过来，一个地区的生产若长期失去订单，经营者可能缩产、负债或退出，相关家户可能转业、失去生计，最终影响人口。
> 整个过程不能凭空增加或删除人口、商品、货币和资产。」
> **证据草稿**（逐条 `文件:行` 与探针原始输出都在这四份里）：
> `.superpowers/sdd/2026-09-28-investigation-2/{E-new-commodity-industry,F-class-occupation,G-competition-numbers,H-operator-household-trace}.md`

---

## 1. 新商品与新产业能否在运行中进入

| 项 | 现状 | 关键代码位置 | 运行期可写？ |
|---|---|---|---|
| `CommodityId` | 仅拒 null/空白，**无格式/注册表/白名单** | `CommodityId.java:8-27` | **可任意新建**（但只是"能造出这个 id"） |
| 商品词表 `EconomyVocabulary` | **编译期常量 + 纯函数**，不是状态；`allCommodityIds()` 全仓 main 只在读口 `ApiViews.java:592` 被读；创世器不拿它当白名单 | `EconomyVocabulary` | **不可写**（但也不拦新商品） |
| 家户需求（35 天目标库存） | `householdLifeReserveOf` → `selfNeedOf` **只认 GRAIN/CLOTH**，其余商品**静默 0 且不落键** | `MarketSettlement.java:96-106/1727-1738/1792-1801` | **写不进**：Fiber/Tool/Iron/Wood/任何新商品的家户买单**不会生成**（探针：`selfNeedOf(100,nectar,35)=0`） |
| 经营者"必要投入" | 遍历 `industry.inputPerUnit()` | `MarketSettlement.java:1699-1725` | **按商品可扩展**（配方里写什么就买什么） |
| `BuyOrder` | 市场轮只遍历 `market.prices().entrySet()`；**无价即无订单** | `:467-489`、`planOrders:419-422`、`ordersFor:679-688` | 订单是瞬时的；没有外部注入面 |
| 市场参考价 | 只有**播种载荷** + 默认关闭的自适应定价（只改已有定价行）；**无 GM 设价命令** | `EconomySeeder/EconomyPayloads.market:366-370`、`EconomyData.java:560-566`、`MarketSettlement.java:178/574-626`、`Shell.java:439` | **既有市场不能运行期加价** |
| 产业创建 | 唯一 handler = `economy.Seed`；对**经济已占用格整份拒绝** | `EconomyPayloads.industry:587-670`、`EconomySeedHandler.java:74-81/105-115` | 只能对**新格**追加 |
| 经营者账户 | 创世 `HouseholdSeeder` + 运行期 `actor.Seed`（只对"该格没有任何账户行"的格）；另有结算副作用：首次正向产出计提经 `OwnershipBooks.apply` 自动建商品账 | `ActorSeedHandler.java:75-94/104-110`、`OwnershipBooks.java:149-190`、`EconomySettlement.java:4177-4195` | 新格可建；既有格不可加账 |

**特别核实①（为什么只写 `naturalNeeds`/`effectiveDemand` 不形成订单）**：
`naturalNeeds` 唯一写点 = `EconomySettlement.withDailyNeed`（`:4321-4340`，消费步 `:2431` 先覆写后读），行为读者只有消费粮/布、低粮触发、社会压力；
`effectiveDemand` 在 main 里**零行为读者**（只读口显示）；
两者**都不在订单输入路径上**——订单输入是 `lifeReserves`（`householdLifeReserveOf`/`operatorLifeRetentionOf`）与 `necessaryInputs`（均不读这两字段）。
⇒ 只写这两个字段不仅"不形成订单"，`naturalNeeds` 还会被**下一结算日覆写**。

**特别核实②（35 天目标库存在哪、能否按商品扩展）**：常量在 `MarketSettlement.java:89-106`（5+30）；实际读取方法是 `householdLifeReserveOf`（`:1727-1738`），内部 `selfNeedOf`（`:1792-1801`）对非粮/布**默认返回 0**。
⇒ 以现有方法形态，**家户需求这一维不能按新商品扩展**（除非改这个方法本身；经营者必要投入那一维可以）。

**发现的一处旁路（代码事实，非设计建议）**：`EconomySeedHandler` 的占用判定只按 entry 的 `q/r` 算（`:105-115`），**不校验 `industries[].id` 里的格是否等于 entry 格** ⇒ 用"未占用 entry + 指向已占用格的产业 id"可以覆盖既有产业/把新 id 加进已占用格（探针复现：`occupiedHexKeys`/`merge` 真实调用、合并后 `EconomyData` 构造通过；完整 `command.submit → handler → ChangeSet → revision` 未跑，权限层行为未验）。

---

## 2. 家户能否真正改变阶层与生产关系

**结论：不能。** 阶层不是属性而是**主键的一部分**：

```
CohortKey(格, 居住类型, 阶层)  →  家户 actor id  q_r:residence:stratum  →  账本键 (actor, 格)
```

| 需要同时迁移的东西 | 现状与阻断点（`文件:行`） |
|---|---|
| 阶层行与人口 | `EconomyData.classes` 与 `ClassRow.key` 强一致；行只由创世/`applyPopulationChange` 改**已存在行**的人口，**无行创建/删除/拆分**入口 |
| social 人口组 | `PopulationGroup` **没有"阶层"维**（`:35-42`）；批次→行始终摊到四个 `SocialClassId`（`EconomySettlement.java:2254-2289`）；social 的 level 改动**不会对账回经济行**（月度只回写 `LotBirths/Deaths`：`PopulationDynamics.java:238-249`、`EconomySettlement.java:1184-1198`） |
| 劳动 | `LaborSupply`/`LaborAllocation` 的键是 `PeopleLotId`，**不带 cohort/阶层**；`reallocateLabor` 只改 `allocations` 一张表（详见下） |
| 账户权益 | 账户键 `(actor, 格)` 由 actor id 派生 ⇒ 换阶层 = 换三样键；没有"转账/迁移"命令；`economy.Seed` 与 `actor.Seed` 对已有格都整份拒绝（`EconomySeedHandler.java:74-81`、`ActorSeedHandler.java:78-94`） |
| 债务 | `Debt.debtor/creditor` 都是 `CohortKey`，且 `DebtId` 由四元组拼死（`EconomySettlement.java:3066-3077`）；没有改债权人/债务人、拆本金的入口 |
| 生产关系 | `ProductionRelation.recipient` 的 `ToCohort` 在创世写死（`RegimeRelations.java:493-523`），运行期只读 |

**"改劳动配额"不等于"家户转业"（代码事实）**：`reallocateLabor`（`EconomySettlement.java:2840-2933/2992-3044`）只删/缩/增 `LaborAllocationId`：`group(PeopleLotId)` 不变、`actor` 换成同格目标产业；`rows` 形参在方法体内 **0 次使用**；`laborSupply` 明文不改（`:2837-2838`）；`classes`/`flows`/`debts`/`relations`/账户/人口**全不动**。
副作用只有"批次级换雇主"（`householdKeysOf` 从配额反推"哪些行属于哪个产业"），行键、阶层、账户、主体身份都不变 ⇒ 代码里**没有"职业/阶层"状态可写**。

---

## 3. 市场能否识别"竞争失利"

**撮合规则（`文件:行`）**：
- **区内**：`matchWithinRegions`(`:865-903`)/`matchGroup`(`:906-931`) 把**同区同商品的全部买卖单**放进一个池，按"卖方剩余 / 买方 min(剩余, 可买量)"比例切分，`pairUp`(`:1130-1182`) 逐笔配对 ⇒ **不同经营者确实竞争同一个买方**（按比例，不是先到先得）。
- **跨区**：`matchAcrossRegions`(`:935-999`) 只对**直接邻接区**、且在区内撮合之后；按 `buyerHex (q,r) 序 × sellerHex (q,r) 序`逐路线尝试，`matchRoute`(`:1006-1120`) 每路线最多 4 个运力窗口 ⇒ **路线坐标顺序**是结构性的"先到优势"。
- **排序**：**没有价格/成本排序**——只有 hex `(q,r)`（`:454/949/972`）、家户行 `(stratum,residence)`、经营者 `industryId`、商品 id、承运人 id；并列走 `ProportionalSplit` 的"余数降序、同余数下标升序"（`:69-99`）。
- **成交价/运费不参与"选谁"**：区内成交价 = 集散节点参考价；跨区 = 卖方格参考价最大值（`unitPriceOf:1415-1423`），只用于限价过滤（`:1079`）；`freightPerUnitOf` 只进可买量与总价复核（`:1450/1488-1496`）；`costPerUnit = 距离×moveCost` 只进路线读数（`:1027`）。

**四件事的区分（证据）**：

| 说法 | 现状 |
|---|---|
| "生产成本较高而失去订单" | **代码里没有"每卖方成本"这一维**（`SellOrder`/`BuyOrder`/`Industry`/`Fill` 均无货币成本字段；`EconomySeeder.java:422` 自述"固定价不看成本"）⇒ **无法表达** |
| "买方没有货币" | 有明确门：`ordersFor:723-733`、`commitFreezes:792-798`、`affordableQuantity:1443-1455`（含 2 毫 margin `:159`）、`pairUp:1162-1173` |
| "买方已有库存" | 有明确门：`gap = max(0, target − available − incoming)`（`:717-722`），家户 target = 35 天生活保留 |
| "卖方因固定名单顺序吃亏" | 区内是**按比例**（不是先到先得）；**跨区路线坐标顺序**才是先到优势；换序影响**未构造变异体实测** |

**布市场 @tick360 的例子（★ 关键）**：`sell no_budget` **不能证明"生产方式竞争失败"**——该标签由 `collectUnfilled:1581-1597` 的**全局 `anyBuy`** 决定：**只要世界上还有一笔同商品未成交买单，所有剩余卖单都盖 `no_budget`**（不定位买方、不查预算/限价）。
A 干跑同一轮内：**11 笔布成交同时来自 家户 7 / weave 3 / craft 1（全价 5）**，而 `sell:cloth:NO_BUDGET = 4,724` ⇒ 标签与"某种生产方式被挤出"相矛盾；@360 布买方残量 267 笔全部是 `algorithm_uncovered`。

**缺的可观察数据（只列，不提方案）**：逐卖方成交率（含零成交；`MarketReadout:344-360` 把 seller/buyer 聚合掉、`MarketReport` 瞬态不落盘）；逐卖方货币生产成本/单位成本口径；被选中的对手/候选集合（只有赢家 `Fill`，没有输家与名单位置）；开市时点的逐单 actor/hex/预算/限价/原因；每轮 `sellable` 的 stock/frozen/necessary/life 构成；多轮历史；"生产方式 ↔ 卖方身份"的稳定读数组件；读时与开市时的窗口对齐。

---

## 4. 订单减少如何传递到破产与生存（120 天追踪）

**对象**：区 `c--34_-65`（tick360 布卖方未成交最大：297,603,582 毫 / 40 笔、成交 0），经营者 `HOUSEHOLD:weave@-34_-65`（初始布 23,815,333 / 纤维 577,571 / 银 2）。
**方法**：/tmp 同包探针 replay tick360，逐日 `EconomyDayStepper.step(361..480)`（120 天，含 social 月度回写），墙钟 398.4 s；逐日 JSONL 为证据。

### 4.1 经营者侧的后果（全部为探针实测）

| 项 | 事实 |
|---|---|
| 投入来自谁 | day361 现扣 fiber **10,254,338**：自己账 577,571 + relation 代理的**农村家户账 9,676,767**（同格 craft 还从同一户拿了 7,326,792） |
| 产品归谁 | day480 收获 scale=340、gross=10,200,000、loss=306,000、net=9,894,000；4 条 `OUTPUT_SHARE` **due=paid=3,614,582、owed=0**；operator 自留 **6,279,418** |
| 卖不出去的库存 | 留在 operator 自己的 `GoodsAccount`；day480 关账后卖单 **30,002,409 毫布**仍全额 `NO_BUDGET` |
| 货币收入 | 120 天 48 个市场日只卖出布 **92,342 毫**（69 笔，收款 **503 毫银**）；随即买纤维 **475,682 毫**（付款 503 毫银）；银全程 **2–22**、终值 2 |
| 固定成本/工资 | 制度是实物分成，**照常支付**（due=paid；owed=0）；没有"货币工资 + 刚性成本"这一结构 |
| **能否借债/破产/退出** | ★ **没有这个通道**：`Debt` 两端与 `lendDeficits` 的键都是 `CohortKey`；operator 是无 cohort 形状的 `ActorRef`（实测 `HouseholdActors.cohortOf(weave operator)` **抛异常**、`operatorInHouseholdOfActor=false`）；`Debt.defaulted` **无写 true 路径**；`Industry` **无 active/closed 状态**；唯一 economy 命令 `economy.Seed` 对已占用格整份拒绝、`withIndustries` main 零调用 |

⇒ **订单减少的传导结果 = 库存堆积 + 货币收入趋零，生产与实物分成照常；"破产/退出"在代码里没有状态可落。**

### 4.2 劳动家户侧的后果

- 关联 4 个农村 cohort（`householdKeysOf`）**全周期 `unmetNeed={}`**、`newBorrowing=repaid=interestDue=0`（实物分成照常，所以没有断粮）。
- 同格**农村地主是 4 个城镇 cohort 的债权人**：120 天新借出 **55,164,004 毫粮、0 笔偿还**；day480 计息并入约 2,125,071；债权本金 **52,041,553 → 109,330,627**，`defaulted` 仍 false。
- 月度回写 390/420/450/480：全球 births 232,364 / deaths 161,560；本格 407/308；`applyFamine` 默认致死率 0 ⇒ **这些死不是饿死**（4 行 unmet 全空）。
- 城镇债务人 `FlowRow` 未抓取，**"为什么 0 偿还"不可判定**（已标）。

### 4.3 证据断点（★ 不许推测"已经破产"）

| 层 | 可观测性 |
|---|---|
| 持久 store | 只能追到 **revision 边界**的存量（rev11 changeset 可逐值读到 operator 的 goods/money 与 industry 的 progress/inputUsed 等） |
| `ProductionLedger` 的 transfers/ruleSettlements、`MarketReport` 逐笔买卖方、逐日 `FlowRow`、会话副本 | **只在进程内**（探针可见；重启/回读旧 revision 即失） |
| "经营者已破产/退出" | **今天完全不可观测**（无状态、无事件、无落盘） |
| 本追踪的边界 | 只跑 1 个经营者 / 1 个周期；无"订单减少 vs 正常"的受控对照；末次才 `land*`、未逐日 fold/apply；全量 8,191 账户未全核；城镇债务人细节未实测 |

---

## 5. 两个数字口径与百分比分母（修正上一份报告）

### 5.1 `2,114` vs `5,483`（已用既有探针逐值复现）

| 装置 | 起始状态 | 是否步进 361–364 | 轮次日 | 成交笔数 |
|---|---|---|---|---|
| A（`AProbe`） | tick360 快照 | **否**（直接干跑） | day365 `PERIODIC` | **2,114**（grain 2057/fiber 46/cloth 11；订单买 1822/卖 10002） |
| B（`FillProbe`） | 同一 tick360 快照 | **是**（`step(361..364)`，含 day363 `LOW_GRAIN_STOCK` 一轮 4,706 笔） | day365 `PERIODIC` | **5,483**（grain 2816/cloth 2168/fiber 499） |

⇒ 两数**同日、同触发**，差别只在**轮开始时的状态**；**都不是 day360 的实轮**（@360 实轮布成交 196,119，来自 `m2t360.json` 201 区全局去重）。
**上一份报告的口径修正**：§1.4 的 2,114 应读作"**在 tick360 快照上直接干跑 day365（未经 361–364）**"；§3 的 5,483 应读作"**从同一快照真步进到 day365 后取的例行轮**"。

### 5.2 为什么"前五项百分比"相加 >100%

三个分母不可混用：**总墙钟**（JFR 201.9 s / 无 JFR ≈175 s 推导值）、**嵌套计时**（step 140.229 + apply 29.147 + land 0.346 = **169.722 s**）、**JFR 采样**（16,275 样本，其中日循环 15,776 = 96.93%）。

| 项 | 用的分母 | 正确表述 |
|---|---|---|
| 1 区域市场轮 126.03 s | 169.722 s（嵌套） | = 74.3% 的**日循环+落账**；= 89.9% 的 **step**；≈72% 的总墙钟（175 s）；= 66.95% 的 **JFR 样本** |
| 2 `apply` 29.147 s | 169.722 s | 17.2% |
| 3 每日末态构造 | **JFR 样本** 1,644/16,275 = 10.1%；墙钟估算 15–20 s/169.722 s = 9–11% | 只能二选一、并标明分母 |
| 4 Replay 5.1–6.1 s | 169.722 s（**但它在 day-loop 之外**，口径错配） | 应改为对 175–202 s 总墙钟的占比 |
| 5 非市场日其余阶段 | 是 step 的**切片**（已扣第 3 项） | 与第 1 项同分母但**不互斥**，不可与第 1 项直接相加 |

**可加的只有同一分母下的互斥划分**（示例）：开市日 step 74.26% + 非开市日 step 8.37% + `apply` 17.17% + `land*` 0.20% = **100%**。
**未估算任何并行加速比。**

---

## 6. 逐项判定表（目标子能力 × 现状）

> 判定口径：**现成可用** = 状态与运行期路径都在、有守卫；**只有状态容器、缺运行期转移** = 数据形状能表达、但没有合法写入口/转移路径；**连状态表达都缺** = 连字段/维度都不存在。

| # | 目标子能力（用户目标） | 判定 | 依据（摘要） |
|---|---|---|---|
| 1 | 新商品进入（id/词表/价格/账户） | **只有状态容器、缺运行期转移** | `CommodityId` 无白名单、账户/配方/价格表都能表达；但**无 GM 设价命令**，家户需求 `selfNeedOf` 只认粮/布（新商品静默 0） |
| 2 | 新产业创建（配方/经营者/账户） | **只有状态容器、缺运行期转移**（限**新格**） | 唯一入口 `economy.Seed` 对已占用格整份拒绝；有 id-格错位旁路（未申报、未验权限层） |
| 3 | 家户"采用候选生产方式" | **连状态表达都缺** | 一个 `Industry` 只有一份配方（`recipe()` 纯派生、`RecipeId` main 零引用）；无技术/工艺选择维 |
| 4 | 阶层分化/跨阶层迁移（人口+劳动+需求+账户+债务一起） | **连状态表达都缺** | 阶层是 `CohortKey` 主键的一段 → actor id → 账户键；无行级迁移、无账户/债务迁移入口；social 批次无阶层维 |
| 5 | 生产关系变更（占有/使用/劳动来源/规则） | **只有状态容器、缺运行期转移** | `ProductionRelation` 是状态、可被变更集整值替换；但运行期只读（创世模板）、`ToCohort` 写死；`occupations/useRights/laborSource` 三面**不存在** |
| 6 | 市场识别"竞争失利"（按成本/被选中/成交率） | **连状态表达都缺**（识别维度） | 无每卖方成本字段；无候选/输家/成交率读数；`no_budget` 是全局 `anyBuy` 标签，不定位竞争 |
| 7a | 经营者**被动缩产** | **现成可用** | `capacityScaleOf = min(产能, 劳动, 实扣投入)` 自动缩（H 实测 scale=340 < capacity=643） |
| 7b | 经营者**主动缩产/借债/退出** | **连状态表达都缺** | `Debt`/`lendDeficits` 键是 `CohortKey`，operator 是无 cohort 的 `ActorRef`（`cohortOf` 抛）；`defaulted` 无写 true；`Industry` 无 active/closed |
| 8a | 家户"转业" | **连状态表达都缺** | `reallocateLabor` 只改劳动配额（批次换雇主），不改行/阶层/账户/债务；没有职业状态 |
| 8b | 家户"失去生计"的可观测面 | **只有状态容器、缺运行期转移**（可读不可迁移） | `FlowRow.unmetNeed/income/consumed`、实物分成 `RuleSettlement` 都在；但没有"生计/就业"状态，且日级流水不落盘 |
| 9 | 人口影响（饥饿→死亡/出生） | **现成可用**（但只有"饥饿/生理压力"这条链） | social 月度 `PopulationDynamics` + 经济侧压力回写（协调器）；`applyFamine` 默认致死率 0 ⇒ 实测 120 天本格死亡非饿死 |
| 10 | 不凭空增删人口/商品/货币/资产 | **现成可用**（有守卫，且本轮复核过守恒） | 铁律 2/5 + 唯一写口 `applyTransfer` + 逐工具守恒（实测 142,924,800 逐值不变）；★ 但 2026-09-28 刚修过"跨格货腿幽灵账"（已修） |

**一句话总判定**：目标里"**新需求 → 家户采用候选生产方式 → 阶层分化**"这条链，**当前连状态表达都缺**（无技术选择维、无阶层迁移、无占有/劳动来源面）；
"**长期失去订单 → 经营者缩产/负债/退出 → 家户转业**"这条链，只有**被动缩产**与**实物分成照常**是现成的，**负债/退出/转业连状态表达都缺**；
"**不凭空增删**"这条铁律是现成可用的，且本轮调查未发现新的守恒缺口（此前那处已修）。

---

## 附：证据与边界

- 四份原始草稿：`E-new-commodity-industry.md`（606 行）· `F-class-occupation.md`（396 行）· `G-competition-numbers.md` · `H-operator-household-trace.md`（585 行），每份含逐条 `文件:行`、探针脚本/输出与"我没做/没验证的"。
- **代码事实 / 推断 / 用户目标**在草稿内已分层标注；本报告沿用同一纪律：本文正文的每一条都写清了"是代码位置、探针实测、还是推断"。
- **本报告没有做的事**：无设计建议；未改任何 `src/**`；未跑完整一年；未构造"换序/成本不同/跨区供不应求"变异体；未捕获跨区或带运费成交；H 只跑 1 个经营者×1 个周期、无受控对照；E 的 id-格错位旁路未跑完整命令链与权限层。
