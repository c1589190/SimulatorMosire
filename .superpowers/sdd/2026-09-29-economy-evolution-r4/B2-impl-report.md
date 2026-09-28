# B.2 ProductionUnit + AssetShare 生产接线 —— 实施报告（2026-09-29）

> 切片：R4 计划 §2.B.2（R3B.2）。执行者：唯一写代码代理。
> 约束遵守：只改 `src/main/java`；未写/改任何测试；未跑 `test`/`verify`/`package`；未 `git commit`/push；未起服务。
> HEAD 起点：`6170760f`（R3B.1，13 组件、生产仍读 `Industry.capacity`）。
> 结果：`tools/mvn-lock.sh -DskipTests compile` **BUILD SUCCESS（exit 0）**。

---

## 1. 改了哪些文件（按类别）

### 1.1 状态 / 契约
| 文件 | 改动 |
|---|---|
| `simos-economy-api/.../id/ProductionUnitId.java` | 新增 `idOf(IndustryId, ActorRef)`：`unit-<industry>-<operator.kind>-<operator.id>`，含 `.` 即抛；`parse` 仍 opaque |
| `simos-economy-api/.../relation/ProductionRelation.java` | `activity` 类型 `IndustryId → ProductionUnitId`；新增 `withActivity(...)`（迁移对齐用） |
| `simos-economy-api/.../relation/SubsistenceObligation.java` | `activity` 类型改 `ProductionUnitId`（给养义务随之挂 unit） |
| `simos-economy-api/.../labor/LaborAllocation.java` | 新增 `idOf(ProductionUnitId, PeopleLotId, HouseholdId)`（`alloc-<unit>-<lot>-<hh>`）；`activity` 文档改"unit id" |
| `simos-economy/.../model/ProductionUnit.java` | **新增**第 14 组件的值类型：id/industry/operator/modeKey/progressDays/cycleLaborMilli/cycleInputUsedMilli + 构造守卫 + `withCycleState` |
| `simos-economy/.../model/Industry.java` | **只留模板**：移除 `operator/progressDays/capacity/cycleLaborMilli/cycleInputUsedMilli`（连守卫与 `cycleSeedUsedMilli` 一并删） |
| `simos-economy/.../model/RegimeRelations.java` | `defaultRelation` / `defaultSubsistenceObligations` 改 `(regime, activity(unit), industry(模板/地点), operator, residences)` |
| `simos-economy/EconomyData.java` | 新增第 14 组件 `units`；`empty()/withUnits` + 全部 `withX` 同步；跨表守卫（见 §3）；迁移器接线扩到 units/relations/operatorConditions |
| `simos-economy/change/EconomyChangeSet.java` | 第 14 组件 `units`；`relations`/`operatorConditions` 键解析器改 `ProductionUnitId::parse` |
| `simos-economy/time/EconomyStateBuilder.java` | `units()` 惰性工作副本；`operatorConditions()` 键改 `ProductionUnitId`；`build` 带出 units |
| `simos-economy/time/ProductionUnitBook.java` | **新增**：`usableAssets(unit, shares)`、`capacityScaleOf(unit, industry, shares)`、`plannedCapacityScaleOf(...)`（唯一的 AssetShare→规模派生点） |

### 1.2 结算
| 文件 | 改动 |
|---|---|
| `simos-economy/time/EconomySettlement.java` | 生产主体由 industries 改 units：逐 unit 投入/劳动/进度/收获；`laborByUnit`（按 `activity`）；`ClosedUnit`/`HarvestWork` 带 unit+模板；`scaleOf/plannedCapacityScaleOf/inputShortfallOf` 从 AssetShare 派生；劳动再分配按 unit；`householdKeysOf/unitsOfHouseholds/cycleDaysByHousehold/householdKeysOfLot/scaleLaborOfUnit` 改 unit 口径；删 `withCycleState(Industry…)` / `capacityScaleOf(Industry)` |
| `simos-economy/time/OperatorSettlement.java` | 条件表全部 unit 键；`Exit` 带 unit+industry；`advance/accumulateMarketEvidence/evidenceOf/belongsTo/selfUsableOf` 改 unit.operator + unit 的 AssetShare 规模；cost 估计走 unit |
| `simos-economy/time/HouseholdClassRule.java` | 活动键/资产事实改 unit：`AssetShare(unit.operator/owner)` 折到 unit；`②a/②b` 的产能近似改读 `ProductionUnitBook.usableAssets`（删除 `Industry.capacity` 的两处读取）；关系/劳动表键改 `ProductionUnitId` |
| `simos-economy/time/HouseholdCondition.java` | `livelihoodOf` 按 `allocation.activity` 找 unit 关系 |
| `simos-economy/time/ProducerCostBook.java` | `estimate(unit, industry, shares, market, relation)`；资产租按 unit 的 capacityScale 摊（不再读 `Industry.capacity`） |

### 1.3 市场
| 文件 | 改动 |
|---|---|
| `simos-economy/time/MarketSettlement.java` | `MarketRound` 增 units + assetShares；`Participant.industries → units`；`participantsFor/industryForSeller→unitForSeller/necessaryInputsOf/operatorLifeRetentionOf/inputShortfallNear/collectSellerOutcomes/sellerCannotReproduce` 全改 unit.operator；同一 operator 多 unit 的列表按 unit id canonical 排序 |
| `simos-economy/time/MarketReport.java` | `SellerOutcome.industryId: Optional<IndustryId>` → `unitId: Optional<ProductionUnitId>`（进程内报告的归属改 unit） |
| `simos-economy/time/MarketReadout.java` | `MarketRound` 构造补 units/assetShares（其余读数逻辑不变） |

### 1.4 迁移 / 编解码
| 文件 | 改动 |
|---|---|
| `simos-economy/migrate/LegacyHouseholdMigration.java` | 七表迁移：units 原样带过 + **键/activity 对齐**（relation key↔activity、condition key↔unit.industry、allocation activity↔actor=unit.operator）；旧产业存在但无 unit ⇒ 按"产能 0 等价"丢弃关系/条件；产业不存在 ⇒ 原样留给守卫 fail-closed；**不再**从 AssetShare 反推 unit（防停产复活） |
| `simos-economy/codec/EconomyCodec.java` | 注册 `ProductionUnitId` 键/值反序列化器；snapshot 旧档 reshape（旧 Industry 实例字段→默认 unit + capacity→整额 OWNED 份额；relations/conditions/allocations 键与 activity 对齐）；changeset 旧档 reshape（摘旧键 + units 增量带进度/劳动/投入；播种批按需补 assetShares 增量） |

### 1.5 播种 / 载荷
| 文件 | 改动 |
|---|---|
| `simos-economy/spi/EconomyPayloads.java` | 新形状 `industries(模板) + units[] + assetShares[]`；`IndustrySpec` 兼容旧键并合成默认 unit/份额；unit 的 id/operator/modeKey 缺省；配额 activity→unit 对齐；旧 useRights 仍 opaque 语义 |
| `simos-economy/spi/EconomySeedHandler.java` | 按格追加时 merge units（漏了会静默丢新 unit） |
| `simos-app/world/EconomySeeder.java` | 播种显式发出 unit（一产业一 unit、modeKey=industry id、owner=operator=旧 operator 的行为等价）与 AssetShare（capacity 逐项，**含 0 值**）；industry 节点只写模板；allocation 的 id/activity 按 unit、actor=unit.operator |

### 1.6 读口 / 组合根
| 文件 | 改动 |
|---|---|
| `simos-app/gui/ApiViews.java` | `economyHex` 每产业增 `units[]`（operator/modeKey/progress/assets/condition/relation）；旧 `operator/progressDays/capacity/cycleInputUsedMilli` 兼容字段从 unit 汇总（首条 unit 的关系供 `subsistenceObligations`） |
| `simos-app/crisis/CrisisMonitor.java` | 相位天数改读 unit.progressDays（模板只给 cycleDays） |
| `simos-app/time/OwnershipBooks.java` | 经营者账户格改从 units 派生（`operatorLocations`） |
| `simos-app/tools/read/CatalogTool.java` | `economy.Seed` 载荷提示改新形状 + 旧键可读说明 |

### 1.7 spotless 附带（零语义）
`AssetShareId.java`、`AssetShare.java`、`EconomyMigrateHouseholdHandler.java` 只有 `spotless:apply` 的重排换行（HEAD 上本就不 compliant）；无逻辑改动。

---

## 2. 实际编译命令与最终输出

```text
$ pgrep -af "surefirebooter|classworlds.launcher"    # 无命中，未与别的 Maven 抢 target/
no-maven-running
$ tools/mvn-lock.sh -q spotless:apply                 # exit 0（/tmp/r4-b2-spotless.log）
$ tools/mvn-lock.sh -DskipTests compile               # exit 0（/tmp/r4-b2-compile.log）
```

最终输出原文（尾部）：

```text
[INFO] Building SimulatorMosire 0.1.0-SNAPSHOT                           [1/12]
[INFO] Building UtilSimos 0.1.0-SNAPSHOT                                 [2/12]
[INFO] Building MapSimos 0.1.0-SNAPSHOT                                  [3/12]
[INFO] Building ActorApiSimos 0.1.0-SNAPSHOT                             [4/12]
[INFO] Building EconomyApiSimos 0.1.0-SNAPSHOT                           [5/12]
[INFO] Building SocialSimos 0.1.0-SNAPSHOT                               [6/12]
[INFO] Building UnitSimos 0.1.0-SNAPSHOT                                 [7/12]
[INFO] Building CoreSimos 0.1.0-SNAPSHOT                                 [8/12]
[INFO] Building SDSimos 0.1.0-SNAPSHOT                                   [9/12]
[INFO] Building ActorSimos 0.1.0-SNAPSHOT                               [10/12]
[INFO] Building EconomySimos 0.1.0-SNAPSHOT                             [11/12]
[INFO] Building SimosApp 0.1.0-SNAPSHOT                                 [12/12]
[INFO] BUILD SUCCESS
[INFO] Total time:  3.940 s
```

`exit code = 0`。中间轮也出现过并由本报告版本修掉的编译/checkstyle 错误（旧字段残留、`ProductionUnitId` 未 import、unused imports 等）——最终工作树没有半成品。

---

## 3. 哪些改动会改变数值行为（逐条）

**结论先行**：新播种/迁移后的"一产业一 unit、owner=operator=旧 operator"世界上，生产结算的算式、量纲、次序、常量一个没动（只换了主语与规模来源），结构上应逐值等价；**但本代理没有跑任何数值冒烟/测试来证明它**（见 §6）。以下逐条列出真正可能改变观测/数值的点：

1. **AssetShare 是唯一规模来源**（核心目标）：规模从 `min(usableAssets[asset] / capacityPerUnit[asset])` 派生。迁移/播种把旧 `Industry.capacity` 整额物化成同值份额 ⇒ 单 unit 逐值同；若未来某 unit 的可用份额 ≠ 旧产业 capacity，规模随之变（这正是设计）。
2. **种子/份额从 0 值也登记**：`EconomySeeder` 现在对 capacity 的**每个键**（含 0）发一条 OWNED AssetShare（0 数量合法）。这**不改变**规模（0/正值=0），但改变 state 形状/读口（多出 0 数量份额行）；也是"非退出 unit 必须有份额"守卫能过的原因。
3. **progress/cycleLabor/cycleInputUsed 从 Industry 移到 ProductionUnit**：状态 diff 的"哪张表在动"变了（旧档 industries 每关账变一次，现在 units 每天变）；读口由 `Industry` 行 + `units[]` 共同回答。算式不变。
4. **劳动归集键 `actor.id()` → `LaborAllocation.activity`（unit id）**：新播种两者的值都指向同一 unit；旧档迁移把旧 activity（`farm`/`weave` 标签或旧 actor 串）改写成默认 unit id。等价条件：`activity` 改写命中唯一 unit。
5. **`reallocateLabor` 的劳动需求**：`laborNeedOf` 的规模那一路改读 unit 的 `usableAssets`（旧读 `Industry.capacity`）。单 unit 同值；0 产能单位仍需求 0（与旧同）。
6. **`ProducerCostBook` 的"资产租摊薄"**：从 `÷capacityScaleOf(industry)` 改为 `÷capacityScaleOf(unit, industry, shares)`。单 unit 同值；多 unit/份额不同则每个经营者的单位成本估计不同（即本改动的目的）。
7. **市场参与者/必要投入/卖单归属**：由 industry 集合改 unit 集合，同一 operator 多 unit 时必要投入按 unit 逐条算再按商品累加、列表按 unit id canonical 排序。单 unit 同值；多 unit 的合作者会看到合并后的目标库存（按 unit 求和）。
8. **市场卖方证据归属**：`SellerOutcome.unitId` 由 `unitForSeller` 判（优先 operator+产出，按 unit id 取小）。同一 operator 有**两个产出同商品的 unit**时，`Fill` 的货款会分别计入两条 unit 的证据（本报告如实标为已知近似，精确归属需要卖方槽带 unit id，B.3+）。
9. **`HouseholdClassRule` 的"经营产能"证据**：旧读 `Industry.capacity`（按产业整份，即使 operator 并不持有）；新读该 unit 的 `usableAssets`（只有 unit.operator 名下份额）。单 unit + 整额 OWNED 迁移下同值；租佃/份额拆分下按新口径（这正是 R3B.4 的方向）。
10. **读口 `industry.capacity` 兼容字段**：改为"该产业各行 unit 的 usableAssets 按资产求和"，`operator/progressDays/cycleInputUsedMilli` 从第一条/最大/求和得到。单 unit 同值；多 unit 是确定性聚合、非旧语义。
11. **新发劳动配额 id**：`reallocateLabor` 新发配额 id 由 `alloc-<industry>-<lot>-<hh>` 改为 `alloc-<unit>-<lot>-<hh>`；只是确定性 id/读口差异，不影响数量。
12. **旧档迁移的"无 unit 即丢弃关系/条件"**：旧产业确实存在但一个 unit 都没有（旧档该产业实物为 0）时，关系/条件被丢弃，等价于旧口径"规模恒 0 无产出"；若产业 id 不存在则保留给守卫 fail-closed。
13. **`LegacyHouseholdMigration` 不再从 AssetShare 反推 unit**（相对拍脑袋的第一版实现）：这是**有意**的正确性问题——资产闲置/退出但有份额的合法状态不会凭空复活生产。若存在"程序化构造的、只有 AssetShare 没有 units"的旧形状夹具，它会保持无生产（旧行为会有生产）；这类夹具没有走 codec/载荷边缘，V 阶段需要改成新形状。
14. **旧档迁移 allocation.actor 对齐**：迁移重写 activity 时把 actor 也改成 unit.operator（旧档二者不一致时）——保证"劳动喂的 unit"与"收劳动主体"一致；只用旧"按 actor.id() 归集 + 产出归 operator"的口径做等价解释。
15. **`settleOperatorExits` 未扩大处置**：仍只做既有债务偿还/`defaulted`（份额/劳动/库存去向是 E1 的活）。与 HEAD 比行为未变（这是如实记的边界，不是新数值差异）。

**明确不动的旧口径**：市场成交价规则、粮/布 35 天保留、`ProducerCostBook` 的价/成本刻度、`OperatorCondition` 状态词表与阈值（`StressPolicy` 一个字没改）、`InputDraw` 的同格争用/池子/最大余数法、关系分账 `ProductionSettlement` 的规则表语义。

---

## 4. 受影响的硬编码字面量 / 常量清单（给测试代理当输入）

新字面量/格式：

- `ProductionUnitId.idOf` 前缀 `"unit-"`，格式 `unit-<industry>-<<operator.kind()>-<operator.id()>`；**含 `.` 抛**。
- `EconomyData.UNIT_ID_PREFIX = "unit-"`：用于"activity 看起来是 unit id 但不存在"的悬空引用 fail-closed 判据。
- `LaborAllocation.idOf(ProductionUnitId, group, household)` 格式：`alloc-<unit>-<lot>-<household>`；旧 `idOf(IndustryId,…)` 保留（旧档迁移/拆分用）。
- 新载荷 `units[]` 字段名：`id?/industry/operator?/modeKey?/progressDays?/cycleLaborMilli?/cycleInputUsedMilli?`；缺省：id=`idOf(industry, operator)`、operator=按 regime 的 `RegimeOperators.defaultOperator`、modeKey=`industry.id().value()`、进度/劳动/投入=0/0/{}。
- 旧载荷/旧档可读键：`operator/capacity/progressDays/cycleLaborMilli/cycleInputUsedMilli`（industry 节点）、`useRights[{activity,holder,…}]`；识别后合成默认 unit（modeKey=industry id）+ 整额 OWNED AssetShare（capacity 每个键，含 0）。
- `AssetShare` 合成：`kind=OWNED`、`owner==operator==旧 industry.operator`、`AssetShare.idOf(..., sequence=0)`；份额 id 仍是 `share-…`（旧 `use-…` 继续 opaque 读）。
- 规模公式（唯一派生点 `ProductionUnitBook`）：`usableAssets = Σ AssetShare{industry==unit.industry && operator==unit.operator}`；`capacityScale = min over capacityPerUnit: usableAssets[k]/capacityPerUnit[k]`；缺资产=0；`capacityPerUnit` 空表=0；`plannedScale = capacityScale × StressPolicy.plannedScalePerMille / 1000`。
- 旧档迁移触发/丢弃条件：`needed` 新增"无 units 且有 relations"；丢弃条件 = 旧产业模板存在但无 unit；ambiguous（一产业多 unit）⇒ 抛。
- 旧 changeset 的 AssetShare 增量合成**启发式**：`assetShares` 组件无 entries（unchanged/缺席）**且** `relations` 组件有 upsert entries（播种批）**且** legacy industry 节点带 capacity ⇒ 合成整额 OWNED upsert。
- 市场：`Participant.units` 按 `ProductionUnitId.value()` canonical 排序；卖方 unit 选择 = 优先 `unit.operator==seller.actor` 且模板产出该商品，按 unit id 取小。
- 读口：`ApiViews.economyHex` 发 `units[]`；兼容字段 `operator`=首条 unit、`progressDays`=max、`capacity`/`cycleInputUsedMilli`=Σ；`MarketReport.SellerOutcome` 的字段名从 `industryId` 改成 `unitId`。
- 未动的常量：`EconomySettlement.GRAIN/CLOTH`、`MILLI_PER_GRAIN`、`FEED_PER_MILLE/DEPRECIATION_PER_MILLE`、`StressPolicy.*`、`RegimeRelations` 四档规则/给养、`EconomySeeder` 的价格/人口/劳动标定、35 天保留。

---

## 5. 与计划不同的地方 / 未完成项（精确到文件/方法）

1. **默认 unit 的生成位置**：计划写在 `LegacyHouseholdMigration`（"旧档每个 Industry 生成一条默认 unit"），但 B.2 已从 `Industry` 移除 `operator` ⇒ 迁移器看不到默认经营者。实际落点：
   - snapshot：`EconomyCodec.migrateLegacyProductionComponents`（从旧 `industry.operator/capacity` 合成）；
   - 载荷：`EconomyPayloads.industrySpec` + `toData` 的旧形状分支；
   - `LegacyHouseholdMigration` 只做"键/activity 与既有 unit 对齐"与"无 unit 的旧产业丢弃"。语义等价（旧档每个旧 Industry 都会在 codec/载荷边缘得到默认 unit），但**方法落点与计划文字不同**。
2. **`LegacyHouseholdMigration` 不从 AssetShare 反推 unit**（见 §3.13）；这是相对任务书"从旧 capacity 补 OWNED 份额"的必然结果：capacity 已不在 Industry 上，补份额发生在 codec/载荷边缘。
3. **旧 changeset 的 `Remove` 键**：`FieldDelta.rebuild` 对旧 industry-id 键的 Remove 不能改写为 unit 键（removal 没有值可推 operator）⇒ 旧变更集里"删除关系/条件"会被当成 no-op。当前 main 没有任何路径删除 relation/condition（Seeder/结算都不删），未构造真样本验证。
4. **`EconomyResolver` 未新增 `productionUnit` 地址 kind**；`EconomyOwnershipTimeParticipant` / `PopulationEconomyTimeParticipant` 的读写集仍只声明 `industry` 地址（不声明 units）。功能上不影响当前"每 revision 一个经济写者"的装配，但并发冲突检测对 units 写入没有地址级表达。
5. **`settleOperatorExits` 未做资产/劳动/库存去向**：仍只按债务表还债/置 defaulted（E1 范围）。
6. **`EconomySeeder` 仍一产业一 unit**：多 unit（庄园自营/佃耕/家户自用）是 B.3，本片没有拆。
7. **`MarketReadout` 没有逐 unit 行**：unit 行在 `ApiViews.economyHex`（`units[]`）；`MarketReadout` 仍是区×商品读数，只是构造时接收 units 以保持同一订单生成口径。
8. **`Industry` 的旧字段留痕注释**（类注里仍有 `progressDays/capacity/operator` 的 `@param` 段落）没有逐行清理（AGENTS §五.4 不篡改历史留痕；只改了行为与签名）。
9. **`HouseholdClassRule` 的槽位回退/S3 派生逻辑未动**（R3B.4 范围）。

---

## 6. 明确"我没做 / 没验证的"

- **没跑任何测试**：没有 `test`、没有 `verify`、没有 `package`、没有 JUnit/变异自证、没有 1/4/8 线程对比。
- **没写/改任何测试文件**；因此 `-DskipTests compile` **不编译 test 源码**——V 阶段既有测试里的旧 `Industry(...)`/`relations`/`operatorConditions` 构造必然要改（这是本切片的预期账，留给测试代理）。
- **没做任何数值冒烟**：没有从固定 tick store 复制、没有 MCP/`dryRun`、没有 90 tick、没有 `Σ AssetShare == 旧 capacity` / `usableAssets == 旧 capacity` / tick0 逐值对账的实测。§3 的"单 unit 应逐值等价"是**结构性推导**，不是实测结论。
- **没验真旧 store**：没有加载过任何真实 `.store`/旧 revision；legacy codec 的 snapshot/changeset reshape 只有代码路径与静态审计，没有真字节样本。
- **没起服务、没写探针**（遵守任务停止条件）。
- **没 `git commit` / push**；没跑 `spotless:check` 之外的收尾门禁（spotless:apply 已跑，exit 0）。
- 未实测多 unit 世界（B.3）、未实测 TENANCY 份额的规模/分账、未实测 `EXITED` 单位的份额豁免细节。
