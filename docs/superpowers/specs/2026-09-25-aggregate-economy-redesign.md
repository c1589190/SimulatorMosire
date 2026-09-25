# 聚合式社会经济系统（产业 → 阶层）—— 取代逐主体设计

**日期**：2026-09-25
**性质**：用户交付的新政治经济学资料（本节即其落成的工程口径）；**本文档取代** `POLITICAL_ECONOMY_DESIGN.md`
的逐主体部分（取代范围见 §1），并取代 `2026-09-25-increment2-population-property-ledger-design.md`。
**核心原则**（原样保留）：不模拟家庭/个人的经济行为；人口以"人"为计量单位；**产业 → 阶层**聚合；
生产制度决定允许哪些阶层；每阶层只存少量核心状态；生产/消费/负债/阶层流动走**周期结算**；
**复杂度不随人口线性增长**。

## 一、与既有实现的关系（保留 / 取代 / 退役）

**保留（不动）**：
- **日 tick 底座**（增量 1a）：`1 tick = 1 天`、`AdvanceTime` 恰好一天、`time_base=DAY` 门禁、存档格式版本。
- **多切片原子提案**（增量 1b-1）：`WorldTimeProposal` —— 一次日推进 = 一条 revision、多切片一起提交。
- 铁律与工程纪律：ID 三件套、`Command → ChangeSet → Revision`、逐组件往返不变式、enforcer 模块边界、
  `ApiViews` 单视图层（GUI/MCP 共用）、MCP 工具面按"一个读口/命令 ⇒ 一条工具"。

**取代**：
| 旧设计 | 新设计 |
|---|---|
| `PeopleLot`（人口批次、逐批年龄/性别/迁移/服役，`social`） | **人口年龄结构聚合**（性别×年龄档×人数）+ 每产业"有效劳动"；经济侧只看**人数与有效劳动** |
| `property`（AssetLot/AssetRight：所有权/使用权/抵押权逐块登记） | **生产资料并入阶层行**（`meansOfProduction`：土地千分亩/耕牛/工坊/机器/船只），不再逐块登记与逐笔租佃 |
| `production`（ProductionUnit/Recipe/Cycle/劳动合同，逐单位生产） | **产业整体生产**：产业表持周期/进度/日投入/产出函数；不再逐生产单位 |
| `market`（订单簿/撮合/在途运输） | **聚合供需定价**（阶层盈余=供给、有支付力的缺口=有效需求）+ 商品税/关税事件 |
| `ledger`（Account/Claim/Transfer 逐主体账户） | **阶层行**持库存/货币/**债务**；债务是"阶层 → 阶层"的聚合债权（本金/利率/到期/标的） |
| `government`（政府法人/辖区/执行覆盖/税则核定） | **政府 = 经济事件上的强制索取者 + 规则制定者**（税制挂在事件上），且**税收进入政府自己的库存/货币**（不外泄） |
| 收益索取队列（逐合同优先序） | **制度分配函数**（小农 / 封建租佃 / 手工业 / 资本主义四种结算函数） |

**退役（代码处置）**：
- `simos-ledger`（2026-09-25 刚落地）：其"逐主体账户"模型与"库存/货币/负债住在阶层行"直接冲突。
  ★ **推荐**：退役（删除模块；`economy` 一并持表 1/表 2/表 3 + 债务表）。**备选**：保留为"存量与债务的所有者"
  （表 2 + 债务表归它，表 1/表 3 归 economy）——代价是每个日/周期结算都要写两个切片。
  ⇒ **待裁 D1**。
- `simos-economy-api` 的 16 个 ID：只留 `CommodityId`（商品）与 `GovernmentId`（政府），其余（PeopleLot/Asset/
  AssetRight/ProductionUnit/Recipe/ProductionCycle/Contract/Account/Claim/Transfer/Market/Order/Shipment/
  EconomicRule）随模型退役；新增 `IndustryId`、`ClassSlotId`、`RegimeId`、`DebtId`。`ActorRef` 收敛为
  `ClassKey`（产业+阶层）与 `GovernmentId` 两类主体（旧的四类 kind 退役）。

## 二、模块划分（推荐）

| namespace | 独占的权威状态 | 不归它 |
|---|---|---|
| 既有 `social` | 人口的**性别×年龄档×人数**（聚合）、自然需求档位、出生/死亡/迁移的净流量 | 劳动投入率、产业归属、库存/货币/债务 |
| **新 `economy`** | **产业表**（制度/周期/进度/日投入/产出函数/阶层槽位）、**阶层行**（人口份额/有效劳动/劳动投入率/生产资料/库存/货币）、**债务表**、**周期流水**、分配函数、阶层流动与产业转换 | 人口总量的真实人口账（social 的）、税制本身（government 的） |
| **新 `government`** | 税制（挂事件：产出税/商品税/关税/所得税/交易税）、政府自己的库存与货币、支出规则 | 阶层的库存/货币 |
| **新 `market`** | 商品参考价格与聚合撮合结果（供给=盈余、有效需求=有支付力的缺口）、跨 Hex 运价 | 买卖双方的库存与钱（那是 economy 的阶层行） |

★ 三切片都只依赖 `util`(+`map` 的 HexCoord / `economy-api` 的 ID)，彼此不依赖；跨切片编排在 `simos-app` 的
`EconomyDayCoordinator`（一次日推进 = 一份多切片 `WorldTimeProposal`）。

## 三、状态形状（可实现的 Java 口径）

### 3.1 产业（`Map<IndustryId, Industry>`）

```java
record Industry(
    IndustryId id, String name,
    RegimeId regime,                          // 生产制度：小农 / 封建租佃 / 手工业 / 资本主义工业
    long cycleDays,                           // 生产周期（农业 120；手工业可短）
    long progressDays,                        // 当前进度 0..cycleDays
    Map<AssetKind, Long> dailyInputPerUnit,   // 每单位生产资料每日原料需求（可为空）
    long dailyLaborPerUnit,                   // 每单位生产资料每日劳动需求（千分劳动）
    Map<CommodityId, Long> outputPerUnit,     // 周期末每单位生产资料的基准产出（农业=每亩 7 粮）
    List<ClassSlot> slots,                    // 该制度允许的阶层槽位（**只含劳动投入率，不含人口占比**）
    AllocationRule allocation)                // 制度分配函数（版本化参数随规则内联）

record ClassSlot(ClassSlotId id, String name, int laborParticipationPerMille) // 劳动投入率（贫农 950 / 地主 100）

enum AssetKind { LAND, CATTLE, TOOL, WORKSHOP, MACHINE, SHIP }          // 生产资料种类（可扩展）
```

### 3.2 阶层行（表 1 + 表 2，`Map<ClassKey, ClassRow>`；`ClassKey = (IndustryId, ClassSlotId)`）

```java
record ClassRow(
    ClassKey key,
    long population,                              // 人
    long laborMilli,                              // 有效劳动（千分劳动）——由 social 的人数×年龄系数而来
    int participationPerMille,                    // 本期实际劳动投入率（≤ 槽位上限）
    Map<AssetKind, Long> meansOfProduction,       // 土地（千分亩）/耕牛/工坊/机器/船只（定点整数）
    Map<CommodityId, Long> goods,                 // 商品库存（最小计量单位）
    long money,                                   // 货币（最小币值）
    List<DebtId> debts,                           // 指向债务表
    Map<CommodityId, Long> naturalNeeds,          // 本期自然需求（生存/再生产/改善——v1 只做前两档）
    Map<CommodityId, Long> effectiveDemand)       // 有效需求（= 有支付力的那部分；§十四/§十五 的分野）
```

### 3.3 债务表（`Map<DebtId, Debt>`）与周期流水（`Map<ClassKey, FlowRow>`）

```java
record Debt(
    DebtId id, ClassKey debtor, ClassKey creditor,
    Optional<CommodityId> commodity,   // 实物债（借粮）；货币债 = empty
    long principal,                    // 本金（余额）
    int ratePerMillePerCycle,          // 每周期利率（千分数）
    long dueCycle,                     // 到期周期序号
    boolean defaulted)                 // 是否已违约（供阶层流动判据用）

record FlowRow(                        // 表 3：**本期流水**（周期结算后归档/清零）
    ClassKey key,
    long income,                       // 本期所得（实物按当周期价折一档"粮值"，口径见 §7）
    Map<CommodityId, Long> consumed,
    long taxPaid, long interestDue, long newBorrowing, long repaid,
    long netSurplus)                   // income − 消费 − 税 − 利息（+ 新借 − 偿债另列）
```

- ★ **存量/流量分离**（原资料 §八）：`ClassRow` 是存量；`FlowRow` 只记本期发生额，**结算后清零**；
  绝不用"生产成本"或"资产减少"冒充负债——**债务只能由 借入/赊购 产生**。
- `EconomyMeta`（激活标记）：沿用"`meta` 空 = 未激活"的语义（未激活时沿用简化人口查询）。
  字段：`mapId`、`activatedDay`、`lastClosedCycle`（最后关账的产业周期）、`rulesVersion`、`migrationSource`。

## 四、结算顺序（照原资料 §二十四落地）

**每日（`EconomyDayCoordinator` 的一步一步，全部在内存工作态里，最后一次性提案）**
1. `social`：年龄推进/出生/死亡/迁移净流量 → 更新各产业的人口与**有效劳动**。
2. 各产业按 `participationPerMille` 得到**实际劳动投入**；`economy` 记本期投入。
3. 各阶层按人口产生**自然需求**（生存 + 再生产；改善/奢侈留待后续）。
4. **先扣库存**满足需求（消费）；不足部分形成**有效需求**（有支付力者）与**自然缺口**（无支付力者）。
5. 各产业推进 `progressDays`（+1），消耗当日劳动与原料（原料不足 ⇒ 记录本日投入完成度）。
6. `market`：按盈余/有效需求形成买卖，产出参考价格与撮合结果；`economy` 据此改库存与货币。
7. `government`：对当日发生的**事件**征税（商品税/关税/所得税；实物税入政府库存）；税从买方/卖方实际转移。
8. 库存不足且有支付力 ⇒ 产生**融资需求**（借入/赊购），由 `economy` 写债务；无支付力 ⇒ 记缺口（供阶层流动判据）。

**周期结算（`progressDays == cycleDays` 那天）**
1. 算产业总产出（`实际投入的生产资料 × outputPerUnit × 本周期投入完成度`）。
2. `government` 先按事件税则征**产出税**。
3. 扣必要生产消耗（种子/牲畜/工具折旧，按制度参数）。
4. 按**制度分配函数**把剩余产品分给各阶层（生产资料权重 + 劳动权重，见 §5）。
5. 更新各阶层库存；`progressDays` 归零，进入下一周期。
6. 结算债务：计息（写新应付款或并入本金）、到期偿还（有库存/货币才还）。
7. 算本期盈余/赤字（`FlowRow`）。
8. 更新货币/库存/负债存量。
9. **阶层流动**：按"连续赤字 / 库存下降 / 债务率上升 / 生产资料减少"产生阶层间**人口流量**（原资料 §十八）。
10. **产业转换**：按各产业**预期收益差**产生产业间人口流量（原资料 §十九：收益差、距离、迁移成本、岗位容量、政府限制、信息传播）。
11. 更新下一周期的阶层比例与产业人口。

## 五、制度分配函数（v1 四种，版本化参数）

```java
sealed interface AllocationRule {
  record Split(int meansWeightPerMille, int laborWeightPerMille) implements AllocationRule {}  // 小农/封建租佃/手工业
  record WageFirst(long wagePerLaborMilli, Map<CommodityId,Long> ownerResidual) implements AllocationRule {} // 资本主义
}
```

- **封建租佃**：`Split(700, 300)`（生产资料占有 70% + 劳动贡献 30%）——原资料 §十二的例。
- **小农**：`Split(500, 500)`（劳动 + 自有生产资料；参数版本化，不写死）。
- **手工业**：`Split(400, 600)`（技艺权重更高）。
- **资本主义工业**：产品产权归企业（`ownerResidual`），工人拿工资（`wagePerLaborMilli`），
  商品由 `market` 卖出后扣工资/原料/税/利息得利润（原资料 §十三）。
- ★ 权重是**版本的查询口径/制度参数**，不是人的永久属性；改参数 = 改 `rulesVersion`。

## 六、不变量（构造期可判的，落成测试）

1. **守恒（跨切片，协调器/命令层保证）**：商品、货币、人口的总量在每次日推进/周期结算前后守恒
   （损耗、税入政府、债务本金转移都要显式落账）。
2. **槽位与行**（2026-09-25 修正）：同一产业内**槽位 id 不重复**；每个阶层行/流水行的 `(industry, slot)` 必须
   落在该产业 `slots` 里（**无悬空行**）；`laborParticipationPerMille ∈ [0,1000]`。
   ★ **人口比例不另存**——它是 Σ`ClassRow.population` 的**观测**（一条真相，避免两处漂移）；
   资料 §十八 的"阶层人口比例"因此由行派生，不是槽位字段。
3. **投入率**：`0 ≤ participationPerMille ≤ slot.participationPerMille ≤ 1000`。
4. **存量非负**：库存/货币/人口/有效劳动 ≥ 0；债务本金 ≥ 0。
5. **流量不污染存量**：`FlowRow` 的任何字段都不出现在 checkpoint 的长期语义里（结算后清零）。
6. **未激活**：`meta` 空 ⇒ 经济切片可以是空表，人口查询走简化口径；日推进仍要求切片在场。

## 七、量纲与取整（沿用既有）

人口 `long` 人；劳动**千分劳动**；土地**千分亩**；商品**最小计量单位**；货币**最小币值**；
权重/利率/投入率一律**千分数**；`"粮值"折算`只在**报表/流动判据**里出现（不落成第二份真相），口径入 `rulesVersion`。

## 八、实现增量（每步独立验收）

| # | 增量 | 验收判据（可执行） |
|---|---|---|
| R1 | `economy` 切片骨架 + 三张表 + 债务表 + meta（**模块化、无公式**） | 逐组件往返不变式（铁律 5）+ codec 往返 + 6 条构造期不变量用例；`clean verify` 绿 |
| R2 | 人口 → 产业/阶层（槽位生成、有效劳动） | 给定人口与制度 ⇒ 各阶层人口与有效劳动；阶层占比之和守恒；空槽位不会凭空生成地主 |
| R3 | 日结算：消费/库存/进度/日投入 | "青黄不接"样例：库存 300、日耗 3、收获 120 天后 ⇒ 第 100 天库存告罄并产生融资需求 |
| R4 | 周期结算：产出/税/生产消耗/制度分配 | 2700 亩 × 7 粮 = 18900；扣 10% 税 + 15% 消耗；按 `Split(700,300)` 分配后各阶层所得与占比逐值断言；小农/租佃两种制度结果不同 |
| R5 | 债务与利息/偿债/融资缺口 | 赤字 → 借粮 → 下期利息 → 债务陷阱（连续三周期本金单调增）；买原料用自有货币 ⇒ **负债不变**（存量/流量分离的判别用例） |
| R6 | 阶层流动 + 产业转换 | 连续亏损的中农 ⇒ 人口流向贫农；农业预期收益 6 / 工坊 12 ⇒ 出现农业→工坊的人口流量；阶跃后占比守恒 |
| R7 | 市场与政府 | 商品税/关税/产出税各自落到政府库存与货币；无支付力的自然需求**不产生**成交（原资料 §十五的判别用例） |

## 九、待裁

- **D1（唯一影响结构的）**：`simos-ledger` 退役（推荐）还是留作"表 2 + 债务表"的所有者？
- D2：`market` 第一版是否独立切片，还是先把"价格+撮合"并进 `economy`（推荐：独立，但 R3 之前只留空壳）。
- D3：阶层流动的阈值（债务率/赤字周期数/生产资料变化率）是否 v1 就做成 `rulesVersion` 参数表（推荐：是，参数默认值写场景配置）。
- D4：`social` 的人口年龄档 v1 分几档（推荐：0-14 / 15-59 / 60+ 三档，系数 0 / 1000 / 300，可版本化）。

---

# 十、验收目标 A（用户 2026-09-25 下达）：三国 **799 格全上经济** + GUI 推进可见

**范围**：worldgen 三国的**每一个 hex**（德意志 430 / 奥斯特马克 138 / 霍赫兰 231，共 **799 格**）在创世时
各得一份 `economy` 状态：农业恒有；城市格加**手工业**；霍赫兰的铁矿/铜矿格加**矿业**
（`extraFacts.hillsIronCopperHexes`）。规模很小 ⇒ 每格一份状态完全可行（799 × ~2 产业 × 4 阶层 ≈ 6.4k 行）。

**生成口径（一切数字来自场景参数，不硬编码）**：
| 参数 | 默认 | 依据 |
|---|---|---|
| 每格人口 | 该格 `social` 已种下的农村人口 + 城市人口 | `WorldgenInitializeTool` 的 `SettlementPlan`（**不重新派生**） |
| 初始阶层比例 | 贫农 450‰ / 中农 350‰ / 富农 150‰ / 地主 50‰ | 资料 §十八 的例子（版本化参数） |
| 土地 | `muPerHex`（默认 **1000 亩/格**）× 地形系数（平原 1.0 / 低丘 0.6） | 资料"每亩毛产 7 粮" |
| 有效劳动 | 人口 × 年龄系数（**D4 默认：0-14/15-59/60+ = 350/550/100‰，系数 0/1000/300‰**） | 资料 §三 |
| 消费 | **每人每农业周期 10 粮** ⇒ 每日 `83 毫粮/人·日`（取整残差按 id 分派，不丢总量） | 资料 §十四 |
| 单位 | 粮 = 1 公斤；货币 = 1 银马克最小币值；周期 = 120 天 | 版本化，可校准 |
| 初始库存 | 60 天口粮（= 人口 × 日耗 × 60） | 让"青黄不接"在第一年内可见 |
| 制度 | 农业 = 封建租佃（`Split(700,300)`）；手工业 = 手工业（`Split(400,600)`） | 资料 §十三 |

**验收判据（GUI 上直接可见）**：
1. 创世后每格有经济读数（人口/劳动/土地/库存/货币/负债/制度/周期进度）。
2. GUI 推进 1 天 ⇒ 该格**粮库存减少**当日消费量；推进 N 天 = N×日耗（同一格自证）。
3. 推进到收获日 ⇒ 一次性产粮 + 政府税 + 生产消耗 + 按制度分配（R4）；库存曲线呈现"青黄不接 → 收获"。
4. 守恒：三国全部格的粮/钱/人口之和在推进前后守恒（税入政府、损耗显式落账）。

**增量重排（先让"看得见"）**：`R2a 生成`（本节口径）→ `G1 读口`（`/api/economy/hex` + `simos.economy.hex` +
GUI 面板/模式）→ `R3a 日结算`（消费/进度/日投入）→ `R4a 周期收获与制度分配` → `R5+ 债务/阶层流动/市场/政府`。

★ 本节把 D4 用默认值钉住（R2a 不被它阻断）；**D1（ledger 退役与否）不阻断 R2a**——economy 自持阶层行，
ledger 原样留着，等 D1 裁定再动。

---

# 十一、推进语义修正（用户 2026-09-25 裁定，**取代 1a 的"每次恰好一天"**）

**用户原话**：「我点推进100天，它不直接结算到100，而是一天一天地推进！改！」

⇒ **新规矩**：
- `AdvanceTime` 允许 `to > from + 1`（**一次推进 N 天，落一条 revision**）；仍要求 `from == base 状态的时间戳`
  与 `N ≥ 1`，并加一个**理智上限**（默认 `N ≤ 36500`，越界拒绝并给出可读理由）。
- **结算语义不跳日**：各参与者必须在这次推进内部**逐日推进**（经济：逐日消费/进度/周期末收获；SD：逐日评估
  触发与阶段；unit：时间预算公式本身就是跨日累计）。判据是**等价性测试**：
  `advance(from, from+N)` 的**终态** == `N 次单日 advance` 的终态（事件链允许不同：前者一条 revision）。
- GUI/MCP 的"推进 N 天"回到**一次请求**（`to = from + N`）；1a 的"前端逐日循环"撤销。
- 保留不变：`from` 连续性校验、`timeBase=DAY` 门禁、一天一结算的**账**（消费/收获/债务都发生在正确的日）。
