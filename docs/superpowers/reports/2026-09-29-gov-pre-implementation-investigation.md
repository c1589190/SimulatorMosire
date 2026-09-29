# GOV 前置调查报告与设计定型建议（只读，2026-09-29）

> **性质**：只读调查，**不改生产代码、不建模块、不写命令**。目标是：在动手前把「谁有权征／谁实际交了多少／粮食在哪个仓」三件事
> 落到现有接口上，并把需要用户裁定的设计点列清楚。
> **代码态**：`HEAD = a3dbcbdc`（生产代码；之后只有 `fdcd417b` 文档提交），分支 `ts/m1-r3b2`，工作树干净。
> **已完成相关能力**：B.2/B.3a/B.3a-perf/B.3b/B.4/E1/E2a/E2b 已提交并验收；E3（经验）、E4（梯度消费）未做。
> **一句话结论**：**G1 可以直接上**——生产分配、家户/经营者账户、粮食市场、区域国家标签、决策人/命令白名单都已具备；
> 但有 **5 个硬缺口必须先定型**：政府主体账户的开立路径、税基采集时点、仓储物理真相、Unit 承载官署的方式、管辖权的显式状态。
> E3/E4 未完成不构成 G1–G3 的依赖。

---

## 0. 结论先行（答辩版）

1. **“谁有权征”**：现在没有税权状态。需要新增显式 GOV 状态：中央税率令（版本＋生效日）、地方官署（管辖权＋席位＋税率执行权）、
   授权链（中央→地方）。现有 `Nation`/`Region`/`NationTag`/`NationScope` 可作管辖权的解析来源，但不能替代显式管辖状态。
2. **“谁实际交了多少”**：税基所需的“生产关系分配后的实际粮食所得”**已经在经济结算里产生**——`FlowRow.income` 是逐商品、
   按周期的实际入账；`FlowRow.taxPaid` 字段已预留但**没有写入者**。需要决定应税主体、采集时点和“粮不够留欠额”的状态形状。
3. **“粮食存在哪个仓”**：物理粮食的唯一真相只能是 actor 切片的 `GoodsAccount(owner, location)`（家的账、经营者的账都已走它）。
   GOV 侧只应存“哪个官署、哪本账、哪个席位、归谁管、被授权做什么”，**不能再造第二本粮账**。
4. **Unit 承载官署**：Unit 有 `parent/position` 层级与 `CreateUnit/ReparentUnit/AttachUnit` 命令，但没有 kind/role；
   `Army(nationId, rootUnitId)` 已经示范了“国家→根单位”的外挂归属法。建议 **G1/G2 不改 Unit 形状**：官署 = Unit 树节点 +
   GOV `Office` 状态（keyed by UnitId / ActorRef），管辖权/税权/仓账都在 GOV 状态里；Unit 只用层级和席位。
5. **管辖权**：map 的 `Region(hexes)+RegionMeta.tag+NationTag(nation:<id>)` 与 app 层 `HexOwner/NationScope` 已经能把
   hex→区域→国家算出来；但一个 hex 可同属多区域/多国家（M8-U1 裁定），所以 **GOV 必须把 office→jurisdiction 显式落状态**，
   map 标签只在创建/修订官署时作解析来源，不在每个税期临时猜。

---

## 1. 现有接口清单（按“征税三件事”组织）

### 1.1 生产—分配—所得：税基从哪里来

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| 生产关系规则（租/分成/工资/给养） | `simos-economy-api/.../relation/ProductionRelation.java`、`CompensationRule`、`RuleType`/`Pool`/`Weight`；`model/RegimeRelations.java` | 决定粮在谁之间怎么分；GOV 税基必须在**分配之后**按各受方实际所得计 |
| 逐规则实付读数 | `ProductionSettlement.RuleSettlement(rule, commodity/currency, dueAmount, paidNow)`（`ProductionSettlement.java:190-230`）；欠额 `Arrear.Kind = WAGE/RENT/SUBSISTENCE/OTHER`（`:302-330`） | 已有“应付/实付/欠”的读数形状；**没有 TAX 档**，税欠额要么扩 `Arrear.Kind`，要么另建 GOV 状态 |
| 收获分配写入家户所得 | `EconomySettlement` 收获/分配段；`income` 累加器 → `FlowRow.income`（约 `:1186-1215`；`FlowRow.java:75-86`） | `FlowRow.income[grain]` 就是“本周期实际粮食所得”；按周期第一天归零、关账日读到整周期 |
| 税字段与净盈余 | `FlowRow.taxPaid`、`netSurplus = income[grain] − consumed[grain] − taxPaid − interestDue`；`EconomySettlement.java:1192` 自述“税要等 government 切片” | `taxPaid` 就是预留写口；G1 写它，`netSurplus` 当场反映税负 |
| 经营者/家户账 | `EconomyData` 的 `classes/flows/assetShares/units/relations`；`OwnershipBooks` 的 household/operator 账户会话 | 收税=真实扣粮；家户有 `FlowRow`，经营者（ESTATE/WORKSHOP）只有 actor 账，**税基口径要先统一** |

**税基关键结论**：`FlowRow.income[grain]` 是现成的**家户口径**“分配后实际所得”；但聚合经营者（ESTATE/WORKSHOP）的粮入账只落在
`GoodsAccount`，没有 `FlowRow`。所以“每份粮只征一次”的干净做法是：在三处入账腿（`RuleSettlement` 的实物付款、收获产出计提、
同格/市场转入）旁记录一个**周期内应税所得累加器**（瞬态、按主体×商品），关账时结算成应征/实收/欠税；
第一版若只征家户行，可直接读 `FlowRow.income[grain]`，但必须明确“经营者直营所得暂不征/或经 `EconomicHouseholdResolver` 归户”，
不能假装已覆盖全部主体。

### 1.2 账户与仓储：粮食的物理真相

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| 政府主体种类已存在 | `ActorKind.GOVERNMENT`（`simos-actor-api/.../ActorKind.java`） | 官署/官仓可以用 `ActorRef(GOVERNMENT, id)` |
| 账按“主体＋格”开 | `GoodsAccountKey(ActorRef owner, HexCoord location)`（`simos-actor/model/GoodsAccountKey.java:42`）；`GoodsAccount` 内含商品余额/货币/冻结 | 官仓余额就是某 `GOVERNMENT` 主体在某格那本账的 grain 余额；“粮在哪个仓”由此回答 |
| 账户初始播种 | `actor.Seed`（`simos-actor/spi/ActorSeedHandler.java`）：空库首次整播；已激活后**按格追加，且该格已有库存行就整份拒绝**（`:100-115`） | **硬缺口**：现有 799 格都已播种 ⇒ 不能给已存在世界新开官仓账。需要 `actor.OpenAccount`（只加零余额账户、不造粮/钱）或一次性迁移 |
| 唯一转移写口 | `EconomySettlement.applyTransfer(...)`；`TransferReason` 现只有 7 档（`simos-economy-api/.../transfer/TransferReason.java`） | 税粮/转运/投放需要新增具名 reason（如 `TAX_PAYMENT`/`GOV_TRANSFER`/`GOV_RELEASE`），并走同一写口 |
| 在途批次 | `ShipmentBatch(route, commodity, dispatchTick, arrivalTick, quantity, allocations)`（`simos-economy-api/.../market/ShipmentBatch.java:27-33`）；到货逐票扣 loss（`EconomySettlement.java:1267-1269`）；`LossBearer` | G2 的“在途/到达/损耗”形状已存在；**现有创建入口只在市场成交内**，需要一条非市场的官粮转运入口（新命令或 GOV 自建批次） |

### 1.3 市场：采购与投放

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| 市场/价格 | `Market(numeraire, prices)`（`model/Market.java:43`）；`economy.SetMarketPrice` 已可运行期定价 | G3 可直接在现有市场买卖粮 |
| 参与者集合 | `MarketSettlement.participantsFor`（`:2977-3017`）：本格家户 + **本格 unit 的 operator**（且账在会话里） | 纯 `GOVERNMENT` actor 没有 unit/家户行 ⇒ **不会自动成为市场参与者**；G3 要么给官署建经营单位，要么扩展 participant 类型 |
| 订单/需求路径 | `ordersFor`、`DemandTargets`、E2a 的 `AddDemand`（HEX/HOUSEHOLD） | 采购/投放可复用“买/卖订单＋预算＋价格过滤”；但 GOV 不是家户，需要新的 GOV 需求/供给入口 |
| 缺粮信号 | `FlowRow.unmetNeed[grain]`、`MarketReadout` 低库存追加轮（`MARKET_LOW_STOCK_TRIGGER_DAYS=10`）、`HouseholdCondition.grainCoveragePerMille`（E1） | G3“投放不足仍有人挨饿”的验收可直接读这些既有信号，不必新造 |

### 1.4 管辖、地域、国家

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| 区域=hex 集+元数据 | `Region(id,name,hexes,boundary,meta)`（`simos-map/.../Region.java:18-19`）、`RegionMeta(color,tag,description,annexedBy)`（`RegionMeta.java`） | 辖区可按 Region 表达；`map.UpdateRegion` 可改 meta.tag |
| 国家标签约定 | `NationTag.PREFIX="nation:"`；`sd.CreateNation` 要求 homeRegion 有国家 tag（`CreateNationHandler.java`） | 国家→区域的地图侧绑定已存在 |
| 国家状态 | `Nation(NationId, name, RegionId homeRegion, int adminBudgetPerTick)`（`simos-sd/.../Nation.java:16`）；注释自述 **adminBudgetPerTick 当前无消费方**，worldgen 置 0（`WorldgenInitializeTool`） | “行政能力”字段已预留但未用；GOV 可用它限制征粮/官署动作，但要先定语义并给非 0 来源 |
| hex→国家派生 | app `HexOwner.nationsOf(map,coord)`；`NationScope` 扫同 tag 区域做决策人可见范围 | 可作为官署辖区的**解析来源**；但一个 hex 可多归属，**不能替代显式 jurisdiction 状态** |
| 无“省/县”层 | 只有 Region 和国家；`Nation.homeRegion` 仅一个 | 地方官署层级需要 GOV 自建（Office 树 + jurisdiction 集） |

### 1.5 Unit 层级：能不能承载官署

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| Unit 字段 | `Unit(id,name,parent,position,member,equipment,speed,mobilityPerMille,movement,status,attached,offset,rejoinTarget,visionRadius)`（`simos-unit/Unit.java:36-50`） | 有 `parent/position` 层级和位置；**没有 kind/role**，字段偏军事编制 |
| 层级命令 | `unit.CreateUnit/ReparentUnit/AttachUnit/DetachUnit/DisbandUnit/SetStatus...`（`simos-unit/spi/`；均实现 `CommandTargets`） | 官署树可以复用；但“这是官署不是军队”只能放在 GOV `Office` 状态里 |
| 国家↔单位归属范式 | `Army(ArmyId, NationId, UnitId rootUnit, name)`（`simos-sd/.../Army.java`）：sd 只存归属，编制仍在 unit | 官署也可用“GOV 存 Office→rootUnitId 归属，Unit 存树”的同一范式 |
| 行政能力自动划署 | 目前无。`Nation.adminBudgetPerTick` 是唯一相关字段 | 需新增 `gov.PlanOffices` 或 app 规则：按辖区 hex/人口、行政预算、邻接关系确定官署数与辖区 |

### 1.6 决策人、命令与权限

| 事实 | 代码位置 | 对 GOV 的意义 |
|---|---|---|
| 决策人 | `DecisionMaker(id, affiliation, allowedTools, accessLimit, decisionCadenceTicks, providerId, generation)`（`simos-sd/.../DecisionMaker.java:40-47`）；`Affiliation.Nation/Army` | 国家决策人已存在；G3 GM 下令可走它 |
| 令与命令 | `Directive(commands[], effects[], verdict, status)`、`DirectiveCommand(type,payloadJson)`（`simos-sd/model/`） | 一条令可携带多条域命令 |
| 命令白名单 | `DirectiveWhitelist`：注册期收全量 commandTypes，排除 `sd.*` 与 `simos.command.submit` | 新注册 `gov.*` 命令后，决策令自动可携带它们 |
| 决策命令执行 | `AdjudicateTickTool` 把 `directive.commands()` 过范围/目标检查后 `core.submitBatch`（一批一条 revision）；`Effect.Action.EnqueueUnitCommand(type,payloadJson)` 实际是**通用命令载荷**，`SdCommandDrain` 经 `core.submit` 执行 | G3“GM 下令”路径已通；自动规则还缺“库存低于阈值”触发 |
| 触发词表 | `Trigger = AtOrAfterTick/AfterTicks/UnitAtHex/ThresholdKills/OutcomeSelected/And/Or`（`simos-sd/model/Trigger.java:22-31`） | 没有“官仓低于 X/缺粮高于 Y”触发；自动调控需扩 Trigger 或在 GOV 侧自建策略轮 |
| 管辖权限约束 | `CommandTargets`（handler 声明目标资源）＋ app `NationScope`（决策人可见范围）＋ `AdjudicateTickTool` 的 precheck | 新 `gov.*` handler 应实现 `targetPaths`（官署/区域/hex），否则决策令无法按辖区越权判 |
| GM 通用写 | `simos.command.submit`（sensitive，审批闸）；`CatalogTool.PAYLOAD_HINTS` 构造期要求覆盖全部注册 type | G1/G2 可先 GM 下令；新命令必须同步 Shell 注册与 catalog hints |

### 1.7 读口与观测

- 现有：`simos.economy.hex`（含 `flows.taxPaid`/`unmetNeed`、units、markets）、`simos.economy.ownership`、`simos.social.population`、
  `simos.map.region`、`simos.unit.*`、`simos.command.catalog`、`MarketReadout`。
- `FlowRow.taxPaid` 已在 GUI 读口输出（`ApiViews.java:1656`），但恒 0。
- 缺：税率/生效、官署与辖区、应征/实收/欠税、官仓余额/在途、地区间投放与缺粮对照。

---

## 2. 能力缺口表（判定口径同前：现成可用／只有状态容器／连状态都缺）

| # | GOV 子能力 | 现状 | 判定 |
|---|---|---|---|
| 1 | 谁有权征（税令/授权链） | 无税权、无税率、无授权；只有 Nation/Region/tag | **连状态表达都缺** |
| 2 | 地方按辖区执行 | Region/hex/NationTag/NationScope 可解析；无官署、无辖区绑定 | **只有解析容器、缺 GOV 状态** |
| 3 | 实际交了多少（应征/实收/欠税） | `FlowRow.income` 现成；`taxPaid` 字段预留无写者；`Arrear.Kind` 无 TAX | **只有半个状态容器** |
| 4 | 粮进官署账户 | `ActorKind.GOVERNMENT`、`GoodsAccount` 已有；`actor.Seed` 不能给已占格开账 | **缺账户开立命令** |
| 5 | 粮食在哪个仓 | actor 账有 `location`；无官仓元数据/读口 | **只有物理账、缺 GOV 绑定** |
| 6 | 转运在途/到达/损耗 | `ShipmentBatch` 形状现成；无官粮非市场创建入口 | **形状现成、缺运行期入口** |
| 7 | 采购/投放/定向发粮 | 市场买卖现成（但 GOV 不是参与者）；转移唯一写口现成 | **缺 PARTICIPANT 扩展与 GOV 命令** |
| 8 | 行政能力限制 | `Nation.adminBudgetPerTick` 已预留但无消费方、worldgen=0 | **只有状态容器、缺语义与消费方** |
| 9 | 自动调控规则 | DecisionMaker/cadence 有；Trigger 无库存/缺粮档 | **缺策略状态或触发扩展** |
| 10 | 官署上下级组织 | Unit parent/position/命令现成；无 kind | **可承载、需外挂 Office 状态** |
| 11 | 权限/越权 | `CommandTargets`＋`NationScope`＋审批闸现成 | **现成机制，需新命令接入** |
| 12 | official 阶层 | `SocialClassId.OFFICIAL` 仅 COMMUNAL 权利一条证据；GOV 俸禄/雇佣未建模 | **词表可表达、缺生成路径** |

---

## 3. 设计定型建议（建议直接按此冻结，改动前请确认）

### D1. GOV 状态放哪：**G1/G2 先落 `simos-economy` 的显式 GOV 组件，R4a 模块拆分时整体迁出**

- 理由：税基（`FlowRow.income`）、粮账（`GoodsAccount` 会话）、市场、运输（`ShipmentBatch`）都在 economy 会话与
  `EconomyDayStepper` 的写口里；G1 要最短闭环，跨模块“读 economy 私有模型”会立刻违反模块边界。
- 建议新增组件（第 17–21 个，名称待定）：
  ```
  GovTaxDecree   : (id, nation/authority office, ratePerMille, effectiveFromDay, effectiveToDay?, version)
  GovOffice      : (id, parentOffice?, unitId?, seatHex, jurisdiction: Set<HexCoord>,
                    granaryAccount: ActorRef, adminCapacityPerCycle, authorized)
  GovTaxAssessment: (id, cycle, office, subject(HouseholdId|ActorRef), baseGrain,
                     ratePerMille, assessedGrain, collectedGrain, lastAttemptDay, status)
  GovTaxPayment  : (id, assessment, day, amount, fromAccount, toAccount)   // 审计行
  GovTransfer    : (id, office, fromAccount, toAccount, quantity, dispatchDay, arrivalDay?, lossPerMille, status)
  ```
- **不塞进 Unit/ClassRow/Nation**：Unit 只给层级与席位；ClassRow 只保留 `taxPaid` 流量；Nation 只保留国家身份与预算。
- 迁移：旧档缺这些键 ⇒ 空表；默认“无税令/无官署 ⇒ 税率 0、行为逐值不变”；R4a 抽模块时只迁键与命名空间，不改语义。

### D2. 物理粮真相：**只认 actor 的 `GoodsAccount`**

- 官仓 = `(GovOffice, ActorRef granaryAccount, HexCoord seat)`；余额读 actor 账，不在 GOV 复制一份粮数。
- 新增 `actor.OpenAccount`（幂等、只建零余额账、不造粮/钱；允许在已占用格为已有/新 actor 追加一本账），
  或把 `actor.Seed` 的“按格占用即拒”放宽成“按 `(actor, location)` 键判重、允许追加新键”。**建议新增窄命令，改动面小且语义清楚。**
- 所有粮移动（税、转运、投放）都经 `applyTransfer` 或 `ShipmentBatch`；新增 `TransferReason.TAX_PAYMENT/GOV_TRANSFER/GOV_RELEASE`。

### D3. 税基：**分配后实际所得、按受方各征一次；首版用“周期所得累加器”，不另造第二本收入账**

- 应税主体：家户行 `HouseholdId` ＋ 经营者 `ActorRef`（若其能经 E1 `EconomicHouseholdResolver` 归户则归户，否则作为独立主体）。
- 计税时点：关账日，收获/关系分配/市场到货之后，下一次消费之前。
- 计算：
  ```
  base(subject, grain) = 本周期实际入账粮（关系实付 + 产出计提 + 市场/同格转入）
  assessed = floor(base × ratePerMille / 1000)
  collected = min(assessed, max(0, 可用粮 − 保护性口粮))
  arrear   = assessed − collected        // 只记欠额，不允许凭税率在官仓造粮
  ```
- **每份粮只征一次**：地租是地主所得 ⇒ 对地主征；佃户留成是佃户所得 ⇒ 对佃户征；租约转移本身不再征。
  这正好实现用户例子：收获 100、租 30/留 70、税率 10% ⇒ 地主 3、佃户 7、官仓 +10。
- 采集实现：第一版可读 `FlowRow.income[grain]`（覆盖家户）；经营者直营收入需要“收获/规则分配处的周期累加器”。
  建议在 `ProductionSettlement`/`harvest` 的入账腿旁加一个**瞬态** `TaxBaseAccumulator`（不落盘、关账结算成 assessment），
  这样经营者与家户同口径，且不新增第二份持久收入状态。
- **保护性口粮**：是否在征收前为纳税主体保留基本口粮，是一个会改变模拟目标的实质选择，建议默认“留够下一周期基本口粮后再征，
  不足部分进欠税”，并交由用户裁定（见 §5 待裁定项）。

### D4. 官署、管辖权与行政能力

- `GovOffice` 显式存 `jurisdiction: Set<HexCoord>`（创建/修订时由 Region/NationTag/HexOwner 解析后**固化**）；
  同一国家内官署辖区应互斥；发现重叠 ⇒ 建署命令拒绝或按确定性优先顺序只征一次（建议拒绝，避免重复征）。
- 层级：`parentOffice` 或 Unit 树二选一；建议两者都有但以 GOV 为准：`parentOffice` 为逻辑层级，`unitId` 为空间/读口承载。
- 行政能力：把 `Nation.adminBudgetPerTick` 解释为**每 tick 行政动作预算**（它本来就是“日制”），每建/每次征收/每次转运消耗一个预算单位；
  预算不足 ⇒ 该周期部分 assessment 不执行、留欠税并具名 `admin_budget_exhausted`。worldgen 当前置 0 ⇒ G1 必须提供 GM 命令/配置把它设为非 0，
  否则税永远收不上（这是“行政能力有物质约束”的关键）。
- 自动划署（Unit 系统优化）建议算法（先 GM 触发，后 app 规则）：
  ```
  输入：本国 tag 区域集、每区域 hex 数/人口、区域邻接、adminBudgetPerTick
  输出：国家根官署 + 若干地方官署（parentOffice 树、seatHex、jurisdiction、capacity）
  规则：每区域至少一署；hex 数/人口超阈值且预算够 ⇒ 按邻接拆分子辖区；预算不够 ⇒ 合并相邻区域；
        并列按 regionId / hex 键升序；每署 capacity = floor(预算 × 该辖区权重)
  ```
  验收：改 `adminBudgetPerTick` ⇒ 官署数/辖区/可征户数变化；税率不变时各地按同一令执行；无人可凭空造粮。

### D5. Unit 承载官署：**不改 Unit 形状（G1/G2）**

- 每个官署建一个 Unit 节点（根=首都，子=地方），只用 `parent` 表层级、`position` 表席位；
  `member=1, equipment={}, speed=1, mobilityPerMille=1, status=RESTING` 等中性值；
  官署的 kind/role/jurisdiction/仓账全在 `GovOffice`。
- `Army(nationId, rootUnitId)` 是现成范式；官署可仿照，但不要求把官署塞进 sd。
- 若后续 R4 Unit 优化要加 `UnitKind.ADMINISTRATIVE`，再迁移读口；G1 不被这个改动阻塞。

### D6. G3 的市场操作：**先 GM 令，后策略轮；物理粮必须真的扣**

- 官署成为市场参与者需要二选一：
  - (a) 扩展 `MarketSettlement.participantsFor`，把 `GovOffice.granaryAccount` 的 `GOVERNMENT` actor 纳入（只读增加）；
  - (b) 给官署建一个 ProductionUnit/经营者身份（不建议，徒增生产语义）。
  建议 (a)，并为 GOV 参与者定义买卖目标来源（`gov.ProcureGrain` / `gov.ReleaseGrain` 命令写 GOV 授权，GOV 时间参与者转成订单）；
- 投放可以走市场卖单，也可以对指定辖区/家户做**定向转移**（真实扣官仓、真实入家户账），两种都要留。
- 自动规则（“储备低于 X 不许放 / 缺粮达 Y 建议放”）建议先做 **GOV 侧策略状态 + 时间参与者**：
  `GovReleasePolicy(office, minReserve, triggerUnmetPerMille, maxReleasePerPeriod, targetScope)`；
  不急着扩 sd `Trigger`（现有触发只有 tick/单位位置/击杀/战斗结果，加库存触发是另一条线）。
- 调控必须有物质约束：官仓没粮 ⇒ 投放命令 Rejected 或缩量；市场投放要通过真实卖单/转移；亏空留在官仓，不凭空补。

### D7. 观测与验收口径

- 新读口：税率/生效日、官署与辖区、逐主体应征/实收/欠税、官仓余额、在途批次、地区缺粮/投放对照；
  `FlowRow.taxPaid` 开始非 0；`netSurplus` 自动反映税负。
- G1 验收：改税率 ⇒ 下一税期各地同政策；不同所得家户缴粮不同；官仓真实增粮；提高税率 ⇒ 家户留粮减少、欠税/生计压力出现；
  欠税不得由系统凭空补粮。
- G2 验收：地方仓留粮或向上转运；在途有到达时间和损耗；中央不能瞬间动用仍在村仓的粮；转运前后逐商品守恒。
- G3 验收：用实际官仓库存执行收购/投放/定向发粮；分别读官仓余额、市场供给、家户缺粮、地区间差异；投放不足仍会有人挨饿。
- 最有意思的验收：丰收区征粮入仓、歉收区发缺粮信号；国家选择留/转/投；观察税负、运输时间、官仓存量如何改变两地家户生计。

---

## 4. 决策人系统与 Unit 系统优化的接口结论（用户点名部分）

### 4.1 决策人系统

- 现成可用：`DecisionMaker`（Nation/Army 隶属、allowedTools、AccessLimit、cadence）、`Directive(commands[])`、
  `Effect + RegisterEffect`、`AdjudicateTickTool`（一批执行多条令命令、范围与目标双重检查）、`SdCommandDrain`（执行已触发效果）。
- 缺口：
  1. 新增 `gov.*` 命令需注册 Shell + `PAYLOAD_HINTS`，并实现 `CommandTargets`（官署/辖区/hex 目标），否则决策令会在越权检查里 fail-closed；
  2. `Trigger` 无“官仓库存/缺粮”档；自动投放建议先走 GOV 策略 + 时间参与者；
  3. `DecisionMaker.allowedTools` 是否要显式加入 `gov.*`（取决于工具面定义，见 `Simos` 的读/写工具分组）；
  4. 国家决策人的 `NationScope` 已限制可见范围为 `nation:` tag 区域；这与“地方官署按辖区执行”天然契合——官署辖区必须落在国家区域内，
     否则决策令目标检查会拒。

### 4.2 Unit 系统

- 现成可用：`unit.CreateUnit/ReparentUnit/AttachUnit/DetachUnit/SetStatus/DisbandUnit`（均 `CommandTargets`）、Unit parent/position、
  `UnitState.effectivePosition`、`Army(nationId, rootUnitId)` 范式。
- 缺口：Unit **无 kind/role**，字段是军事编制（member/equipment/speed/mobility），没有行政能力/辖区/仓储字段。
- 建议：G1/G2 不扩 Unit；官署只是“Unit 树节点 + GOV Office 状态”。若 R4 的 Unit 优化要加 `UnitKind`，把行政官署作为其中一档，
  再把 `GovOffice.unitId` 的读口升级；**不要**把 jurisdiction/税率/粮账塞进 Unit 字段（那会制造第二份可漂移的 GOV 状态）。

---

## 5. 需要在实现前裁定的设计点（建议冻结值）

| # | 决策点 | 建议 | 影响 |
|---|---|---|---|
| Q1 | GOV 状态位置：economy 内新组件 vs 立刻新建 `simos-gov` 模块 | **G1/G2 先 economy 内显式组件，R4a 迁移**；若必须模块先行则需 app 读契约与额外时间参与者 | 决定改动面与是否引入跨模块契约 |
| Q2 | 征收前是否保护基本口粮 | 建议**保留下一周期基本口粮后再征，不足进欠税**（避免税直接制造饥荒）；用户可改“见粮先征” | 实质改变生计/人口结果 |
| Q3 | 税率改变生效时点 | 下一税期生效、不追溯；税令带 version/effectiveFrom | 影响可复现与归档口径 |
| Q4 | 应税主体范围 | 家户行 + 经营者 actor；经营者能归户则归户，否则独立主体；每份粮只征一次 | 决定经营者直营是否纳税 |
| Q5 | 欠税状态形状 | 独立 `GovTaxAssessment`（应征/实收/欠税）＋ `GovTaxPayment` 审计行；不塞进私债 `Debt` | 决定读口与后续追缴/减免 |
| Q6 | 官仓与转运实现 | 官仓=actor 账；转运复用 `ShipmentBatch`（需非市场创建入口）或 GOV 自建批次 | 决定守恒/损耗路径 |
| Q7 | 官署辖区重叠 | 同国官署辖区互斥；重叠建署拒绝（防重复征税） | 决定多归属 hex 的处理 |
| Q8 | 行政能力语义 | `Nation.adminBudgetPerTick` = 每 tick 行政动作预算；每建署/每次征收/每次转运消耗；不足⇒欠税+具名原因 | 决定“自动划署”是否有物质约束 |
| Q9 | 自动调控实现 | 先 GM 令；自动规则走 GOV 策略状态 + GOV 时间参与者；暂不扩 sd Trigger | 决定 G3 第二半的范围 |
| Q10 | official 阶层是否接 GOV | 暂不接；GOV 俸禄/雇佣关系与 `laborSource` 词表需要单独设计（GOV 收入不属于现有五档 laborSource） | 决定阶层词的生成路径 |

---

## 6. 未核实 / 需复核

1. `actor.Seed` 在“已有 actor 表但某格无库存行”时的精确行为，以及新增 `actor.OpenAccount` 对 actor meta/occupied 判据的影响（只读代码推断，未跑真档）。
2. `FlowRow.income` 是否完整覆盖“产出计提（PRODUCTION_OUTPUT）”这条腿——代码注释显示 `income` 主要是关系实付＋同格转入，
   经营者产出计提进 `ledger.outputAccruals()`；G1 若只征家户行，需明确经营者直营所得的归属/是否暂缓。
3. `ShipmentBatch` 非市场创建后的 `deliverShipments` 是否对“买卖双方都是 GOVERNMENT 主体”的账户会话同样成立（现有补载逻辑偏向市场买卖双方）。需构造探针。
4. `AdjudicateTickTool` 的 `submitBatch` 是“多命令一条 revision”，但 GOV 建署若要“GOV 状态 + actor 账 + Unit 节点”三模块同批，
   需要 app 侧协调器；三个 handler 的原子性需专项验证。
5. `Nation.adminBudgetPerTick` 当前无消费方、worldgen=0；G1 要有非 0 来源（GM 命令/配置/世界默认），需裁定默认值。
6. 多国 hex 多归属下，官署 jurisdiction 的分配与决策人 `NationScope` 目标检查是否一致；需要构造多归属夹具。
7. 旧档：新增 GOV 组件后，旧 revision 的 Changeset/快照缺键默认空表；要按 E2a 的先例补 `EconomyChangeSet` null⇒Unchanged 与 Codec 键。
8. 审批策略：当前 `simos.command.submit` 是 sensitive/Ask；用户说 approval policy 改为 never，但 GOV 命令是否要逐条审批、GM 路径是否直通，
   需按部署策略确认（命令层不假设）。

---

## 7. 建议的下一阶段切片（设计冻结后）

| 切片 | 完成后应看到的现象 | 主要接口/新增 |
|---|---|---|
| **G1 管辖与征粮** | 国家改税率后，下个征收周期各地按同一政策计税；不同家户因所得不同缴粮不同；地方官仓真实增粮；提高税率减少家户留粮并可能产生欠税/生计压力 | `GovTaxDecree/GovOffice/GovTaxAssessment`、`actor.OpenAccount`、`gov.SetTaxRate/CreateOffice/AssessTax/CollectTax`、`CommandTargets`、读口 |
| **G2 仓储与转运** | 地方仓留粮或向上级仓转运；在途有到达时间和损耗；中央不能瞬间动用仍在村仓的粮 | `GovTransfer` 或复用 `ShipmentBatch`、`gov.TransferGrain`、运输路线/损耗读口 |
| **G3 粮食调控** | GOV 用实际库存收购/投放/定向发粮；分别观察官仓余额、市场供给、家户缺粮、地区差异；投放不足仍有人挨饿 | GOV 市场参与者扩展、`gov.ProcureGrain/ReleaseGrain`、GOV 策略状态、（可选）sd Trigger 扩展 |

**实现顺序**：G1 的“税令→应征→实收→欠税→官仓”闭环优先；G2 复用 G1 的官署与仓账；G3 最后接市场与策略。
E3/E4 不阻塞以上顺序；若同时做 E4，G3 的“投放不足”口径要与 E4 的基本/改善未满足读数对齐。

---

## 8. 附录：本报告引用的关键文件

- 经济：`simos-economy/EconomyData.java`、`time/EconomySettlement.java`、`time/ProductionSettlement.java`、
  `model/FlowRow.java`、`api/relation/ProductionRelation.java`、`model/RegimeRelations.java`、`api/transfer/TransferReason.java`
- Actor：`simos-actor-api/.../ActorKind.java`、`simos-actor/model/GoodsAccount*.java`、`simos-actor/spi/ActorSeedHandler.java`、
  `simos-app/time/OwnershipBooks.java`
- 市场/运输：`simos-economy-api/.../market/ShipmentBatch.java`、`time/MarketSettlement.java`、`model/Market.java`
- 地图/国家：`simos-map/.../region/Region.java`、`RegionMeta.java`、`simos-sd/.../model/Nation.java`、`Army.java`、
  `simos-sd/spi/NationTag.java`、`simos-app/access/HexOwner.java`、`NationScope.java`、`WorldgenInitializeTool.java`
- Unit：`simos-unit/Unit.java`、`simos-unit/spi/*Handler.java`
- 决策：`simos-sd/model/DecisionMaker.java`、`Directive.java`、`DirectiveCommand.java`、`Effect.java`、`Action.java`、`Trigger.java`、
  `spi/DirectiveWhitelist.java`、`IssueDirectiveHandler.java`、`RegisterEffectHandler.java`、`simos-app/sd/SdCommandDrain.java`、
  `simos-app/tools/write/AdjudicateTickTool.java`
- 命令/读口：`simos-app/Shell.java`、`simos-app/tools/read/CatalogTool.java`、`simos-app/gui/ApiViews.java`
