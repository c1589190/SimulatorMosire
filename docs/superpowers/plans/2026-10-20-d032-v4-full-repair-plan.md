# D-032 v4 完整修复计划（父 v2 标准农业支撑）

> **状态：修复计划；本文先冻结目标运行顺序、责任区、验收边界；生产代码尚未修改。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 分支：`parallel/side-module`
> 不碰主树：`/home/cna/SimulatorMosire`
>
> **权威顺序**：
> 1. 用户本会话原话（见 §2 附录）；
> 2. `docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md`（v4，当前最新专题设计）；
> 3. `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`（父 v2，S0–S8 标准农业目标架构）；
> 4. `docs/superpowers/plans/2026-10-20-d032-two-position-rural-class-mobility-dispatch-plan.md`（上一轮派单计划，本文在其上修正“父 v2 可作为以后收尾”的错误收窄）；
> 5. `.agents/skills/target-runtime-design/SKILL.md` 与 `docs/agents/15-十四-目标运行设计与子设计嵌入.md`。

---

## 1. 目标运行序列（修复后）

### 1.1 总链

```text
S0 Social
  - 出生/死亡/年龄/劳动能力/实际温饱
  - 逐户、逐成员批次计算当前实际劳动与口粮需求（年龄档 × 性别 × provisioning）

S1 经济载入
  - 载入活家户、土地、当前实际 labor/need 投影、class position

S2 进入/迁移/组织
  - R1 同 key 合并
  - 刷新生产组织/名册/雇佣位置
  - 不把创世 plannedLabor 当当前权威

S3 生产位置选择
  - LANDED：operator/selfLand，必要时 hiredLand
  - LANDLESS：只能作为 hired_laborer 响应；未受雇 = idle
  - 每个雇主、每个 hiredLand 独立形成雇佣位置
  - 按当前实际家户劳动与预期工资选择

S4 生产日结
  - 每户每 tick 一张 DailyDemandBill
  - BASE_SUBSISTENCE / PRODUCTION_DAILY_EXTRA / PRODUCTION_INPUT 同一对象
  - 实际播种/实际日耗/实际劳动/日效率
  - 生产轮不结算债务

S5 收获/毛产分配
  - 按实际参与本周期生产的土地 u/a 计 B_cycle
  - 毛产按参与土地份额给 owner
  - 雇工不拿产品份额
  - S5 不二次扣库存

S6 工资
  - 每 tick 按实际劳动结算
  - 只现金；未付成普通 0‰ 货币债
  - 本 tick 新工资债不进入本 tick S7-M2

S7 市场（市场日）
  S7-M2 债务清算（货币 → 库存 → 标量 LAND）
  S7-A 商品 + LAND 同一订单簿
       - 需求来自 S4 后同一 DailyDemandBill 的 marketPurchaseNeed
       - 卖实际盈余 = 当前库存 − 本户同一账单需保留项
       - 商品与 LAND 同一撮合入口
  S7-B PostLandTradeClassCheck
       - LANDLESS 买方按 hex 把本轮 LAND + 比例人口/钱货债转到目标 LANDED
       - 同一 revision 清零临时 LAND
  S7-C HouseholdFlowBook
       - 同 hex LANDED → LANDLESS
       - LAND 不随人走
       - 1% 量级、带 hysteresis/cooldown

S8 Social 事实回传
  - 回传本 tick S4 已发生的实际温饱/缺口
  - S7 买入只成为下一 tick 未来库存
```

### 1.2 本修复必须闭合的四个“同一事实唯一来源”

```text
实际家户劳动/需求：Social 是唯一权威；
生产计划/雇佣位置：production 物理状态归 production，economy 只读；
市场购买/实际盈余：同一 DailyDemandBill；
迁移：人口/钱/货/债/经验随人走，劳动承诺按剩余人口保留，LAND 不随人走。
```

### 1.3 运输服务的边界

父 v2 明确：

> 跨 hex 先走确定性 `transportCapacity/price` 函数，不新增运输生产方式。

因此修复后：

- 有 `trade` / haul 生产链的世界：可继续走 `haul` 服务商品腿；
- 没有 haul 生产链的标准农业世界：运力池必须走**简单生产性运力 + 既有 CARRIER_FEE 腿**，
  不允许出现“池里有运力、交付时要求 haul 商品”的混合态；
- `HAUL_SERVICE_DELIVERY_FAULT > 0` 不得被 status=OK 吞掉；要么修到 0，要么按契约故障 fail-closed。

---

## 2. 用户原话附录（逐字）

### 2.1 本会话新增

> 2我认为是主要问题，单个家户的实际总提供劳动力、实际总口粮需求等，都是要根据据家户的人员规模（甚至根据Social的设计，还得分年龄段）来算的，这是不是没做？

> 5 可以适当减小，减到1%这个数量级

> 7 是之前一连串错误的必然结果，暂时不直接修

> A1 A2是什么东西？最新的架构设计不是V4吗？理论上应该按V4架构完整实现，但是你先告诉我怎么回事

> 留着，后面处理，0人就0人

> 还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick

### 2.2 v4 R1–R6（2026-10-20）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣

R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程

R3：完全可以

R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护

R5：是的，随着人走

R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```

### 2.3 v4 补充时序

来源：同上，§1.1。

```text
理论上来说，在进行计算的时候，市场轮买卖结束、阶级跃迁没有开始的时候，阶级性为agri/landless的家户账面库存上是由土地的，正因如此下一阶段阶级跃迁要根据土地交易的出价方，以把人口按照土地交易的出价来源家户，按比例转移人口以达到把账面土地全部转移走的作用——如果这个家户的其他属性和出价家户一样，那么对应比例人口就转移到购买的土地所属hex的对应有地农民家户，只不过后面单个家户可能还有文化属性，如果文化属性不同，恐怕涉及文化属性判断，乃至要创建新家户，因此这个合并也是一个需要进行额外判断计算的过程，目前自然是直接合并添加
```

### 2.4 本计划的直接解释

- “按 V4 架构完整实现”= v4 的 R1–R6 完整实现 + 其父 v2 的 S0–S8 生产/市场支撑；
- v4 §8 同阶级整组织交易仍是 v4 自己声明的**独立后续**，不在本计划；
- #7 土地价格/供给侧算法**暂不直接修**；
- #5 S7-C 使用 10‰（1%）；`P < 100` 时按整数 floor 可以 0 人，不引入余数累计器。

---

## 3. 当前代码审计（回代码核过）

### 3.1 已对齐的部分

- `ClassNature` / `ProductionRole.classNature` / mode `supportedClassNatures` 已存在；
- `HouseholdCulture.label` + `HouseholdCultureKey` + economy-api lookup 已存在；
- `RuralHouseholdSeeder` / `Rural32HexWorld` 已有 32hex、每 hex 160/40、1L+1LL、无 displaced；
- `MAX_HOUSEHOLD_POPULATION` 在 main 中 0 命中；
- `EconomyData` 已有 37th `householdFlowTables`；
- `PostLandTradeClassCheck` 和 `HouseholdFlowBook` 已在 S7-B/S7-C 顺序上；
- `DebtFirstSettlement` 已有 LANDLESS 债权人跳过 LAND 腿；
- `ClassNatureBook` 已从 current position 派生 class nature；
- `HouseholdMergeBook/Settlement` 已有 R1 key/survivor/rebind 代码。

### 3.2 核心未闭合

| 问题 | 代码证据 | 影响 |
|---|---|---|
| 实际家户劳动/需求没有成为生产计划权威 | `ProductionPlanBridge.effectivePlannedLaborMilliPerDay:186-201` 优先返回历史 `plannedLabor`；`ProductionRuntimeSeeder:392-465` 每个 farm 固定一个 hired participant | 人口迁移后 target 劳动无法进入生产 |
| 劳动承诺缩水公式方向错误 | `HouseholdTransferSettlement:388` `after = before * population / sourcePopulationBefore` | 迁走 10% 人口，source 只保留 10% 承诺 |
| S7-B 买地单太小 | `IndustrySeeder:67` `capacityPerUnit[LAND]=1000`；`MarketLandIntentBook:300-313` 只买 1 个最小块；`PostLandTradeClassCheck:54-55` `MIN_LAND_PER_PERSON=2667` | 2,047 笔 LAND 成交全部 1,000，2,031 次 post-land 全部 0 迁移 |
| S7-C 触发无 hysteresis | `HouseholdFlowBook:271-273,346-352` 当前 `100‰`，任何债务正增量都触发 | 1,219 次 flow、5,201 人累计迁出 |
| S7 仍用 S3 预期 intent | `EconomyDayPipeline:118-121` 用 `expectedProductionPlan.marketIntents()`；main 无 `DailyDemandBill` 类型 | 市场订单不是 S4 后实际盈余/实际需求 |
| 运输 fallback 与交付腿不一致 | `MerchantCapacityCore:108-151` 无 haul 货也造 productive capacity；`MarketShipmentBook:295-330` 交付仍要求 haul 商品；`MarketRoundRunner:600-615` 只记 ERROR 不 fail-closed | 72 个市场日 16,271 条运输 ERROR；市场 unfilled 21,987 |
| 生产仍是旧 per-household roster/公式 | main 无 `HiredLaborPosition` / `selfLand` / `hiredLand` / `DailyDemandBill` / `B_cycle` / `sownLandByOwner` | 父 v2 生产/市场核心未实现 |

### 3.3 本轮 360 现象（仅作修复前基线）

- `status=OK=360`、`REJECTED=0`；
- `HAUL_SERVICE_DELIVERY_FAULT=16,271`（全部 72 个市场日）；
- `HAUL_SERVICE_SETTLED` 全年 produced/delivered/expired 全 0；
- `HOUSEHOLD_FLOW_APPLIED=1,219`，累计 `population=5,201`；
- `POST_LAND_CLASS_CHECK=2,031`，全部 `migrants=0`；
- `LAND_TRADE_SETTLED=2,047`，全部 `quantityMilliMu=1000`；
- harvest grain：2,053,488,000 / 591,264,000 / 316,176,000；
- `MARKET fills=3,380`，`unfilled=21,987`（槽位计数）。

---

## 4. 责任区与顺序

> 一个责任区 = 一个生产代码 writer；只写生产代码、只过 `-DskipTests compile`；
> 不写/不跑测试、不 commit、不碰主树；每块先落任务书/约束设计书，再派单。
> 测试与变异自证最后由 T2 owner 统一做。

### RC8a：运输 service-market 边界与交付契约（P0，先做）

**目标**

- 让“有 haul 生产链的 hex 走服务商品腿，没有 haul 生产链的 hex 走简单 CARRIER_FEE 腿”成为显式判据；
- 禁止 `servicePriced` 下用 productive fallback 生成容量、但交付仍强制扣 `haul` 商品；
- 或者：无 haul 服务货时，将 servicePriced 改为简单运力 + CARRIER_FEE；
- `HAUL_SERVICE_DELIVERY_FAULT` 不再静默 status=OK。

**建议落点**

- `ShipmentSettlement.haulServiceHexes(...)`：只把**当前有 haul 产出能力/有 trade 产业**的 hex 作为服务成市 hex；
- `SettlementDayMarketPipeline.prepareMarketRound` 使用该过滤；
- 或给 carrier/capacity 增加 `fallback` 标志，并让 `MarketShipmentBook`/`MarketTradeExecutor` 的运费腿同源分流；
- `MarketRoundRunner` 的 delivery fault 处理：修到 0；若仍存在则 fail-closed 或明确业务拒绝降级。

**验收锚点**

- 32hex 360：每个市场日 `HAUL_SERVICE_DELIVERY_FAULT=0`；
- 若走 CARRIER_FEE：`HAUL_SERVICE_SETTLED` 不再出现“有容量、0 交付、大量 fault”；
- 市场 `LOGISTICS_CAPACITY` / `NO_ADJACENT_SUPPLY` 不再是 unfilled 主因；
- 出现未交付时 advance 必须 REJECTED，不能 status=OK。

### RC8b：实际家户劳动/需求接入生产 + 劳动承诺修复 + 动态雇佣（P0）

**目标**

- 不重算 Social 的年龄/性别公式；消费 Social 已提供的 `householdLaborMilli` 与 `householdNaturalNeeds`；
- `ProductionRoster.plannedLabor` 不再作为当前生产权威；
- source organizer 使用当前户 actual labor；
- target landless 户的当前 actual labor 可以进入 hired 位置；
- 每个 farm 的 hired 位置能随同 hex 无地户人口/劳动变化而出现/更新；
- 修 `HouseholdTransferSettlement` 的保留比例；
- 迁移后 target 需要承接劳动时，显式建立 target 位置/承诺，不能只缩 source。

**范围收敛**

- 本责任区先完成“当前模型下实际劳动/需求 → 生产计划/劳动队列/迁移承诺”的闭合；
- 单 hex 聚合与 `B_day/B_cycle` 的完整公式化放在 RC8d/RC8e，不在本 RC 里混改数值公式。

**验收锚点**

- Social 的逐户 labor/naturalNeed 与 economy 投影逐户一致；
- 160 人迁 16 人，source 生产承诺约 84,093 → 75,684（而不是 8,408）；
- source/target 人口变化后，hired/operator 实际 labor 与生产计划同步；
- 不再出现 hiredLaborMilli 长期固定 54–70 万而 target 人口 180+。

### RC8c：DailyDemandBill / 实际盈余卖盘（父 v2 P0-01/P0-02，P1）

**目标**

- 增加 transient `DailyDemandBill`（每户每 tick 唯一副本）；
- 账单同时含 `BASE_SUBSISTENCE`、`PRODUCTION_DAILY_EXTRA`、`PRODUCTION_INPUT`；
- S4 记录 `providedFromStock`，S7 在同一对象上派生 `marketPurchaseNeed`；
- S7-A 卖出 `当前库存 − 同一账单需保留项` 的实际盈余，不再用 S3 的预期 intent 替换 S7 订单；
- 保留“无供给⇒unmet 不记债；有成交现金不足⇒普通货币债”。

**验收锚点**

- main 中只有一张 `DailyDemandBill` 类型/一个当前 tick 副本；
- S7 商品订单数量来自 S4 后的实际库存/实际账单，不再来自 `expectedProductionPlan.marketIntents()`；
- 不存在独立 naturalNeeds 市场表与生产 input 表分别驱动同一需求；
- 360 中 `MARKET_DEFERRED_PAYMENT`/`MARKET_SELF_MATCH_SKIPPED` 的结构性异常下降。

### RC8d：S7-B 买地规模与财富门（P0）

**目标**

- 无地户买地单至少覆盖 `minLandPerPersonMilliMu`；
- `maxByWealth = floor(landlessWealth / entryCostPerPerson)` 落地，不再 `Long.MAX_VALUE`；
- `minLandPerPerson` 从 production/Social 定义推导，去掉硬编码 2667；
- 保持 S7-B 同 revision 清零临时 LAND；
- 不在本 RC 直接改 #7 的 land price/supply 算法。

**验收锚点**

- 存在足够 LAND 时，`POST_LAND_CLASS_CHECK` 出现 `migrants > 0`；
- 不再出现 2,047 笔全部 1,000、2,031 次全部 0 迁移；
- 多 hex 最大余数拆分守恒；
- LAND 总量守恒、LANDLESS 临时持地不落盘。

### RC8e：S7-C 1% flow + hysteresis（P1）

**目标**

- `HouseholdFlowBook.MAX_FLOW_PER_MILLE = 10`（1%）；
- `P < 100` 时按整数 floor 可为 0；不引入余数累计器；
- 增加债务增量/hysteresis 判据，避免任何小额债务反弹都触发；
- 保持同 hex、同 mode/culture、LAND 不随人走、source ≥ 1、每市场轮最多一次。

**验收锚点**

- flow 事件显著低于 1,219；
- 累计迁移人口显著低于 5,201；
- 债务回落后的小幅反弹不再触发整轮 1% 流动；
- source/target class nature 与 LAND 守恒不变。

### RC8f：父 v2 单 hex 聚合 / selfLand / hiredLand / B_day / B_cycle（P1，最后）

**目标**

- 在 RC8b/RC8c 的实际 labor/need 与账单稳定后，补 v2 §4.2–4.6 的聚合与效率公式：
  - `u` = 本周期实际参与生产土地；
  - `B_day = Σ_i(a_i/u)e_i`；
  - `B_cycle = Σ_d(u_d×B_day_d)/Σ_d u_d`；
  - `selfLand` / `hiredLand` / `HiredLaborPosition`；
  - 毛产按 owner 参与土地份额；
- 该 RC 会改生产计划/产出公式，必须单独跑 360，不允许与 RC8b/RC8c 混在一个 writer 里静默改数值。

**验收锚点**

- 一个 hex 只有一个 `standard_agriculture` 运行聚合；
- 实际参与土地 `u` / 各 owner `a_i` 与播种、LAND 持有一致；
- `B_day/B_cycle` 由逐日记录累计，而不是另一个公式；
- owner 毛产份额、雇工工资、种子/日粮责任与 v2 §4.6 表一致。

### T2：统一测试 owner（最后）

- 全仓 `tools/gate.sh full`；
- 32hex 360；
- 测试/验收必须检查：
  - `HAUL_SERVICE_DELIVERY_FAULT == 0`
  - `HAUL_SERVICE_SETTLED` 的 produced/delivered/expired 与库存/成交一致
  - `POST_LAND` 在足够 LAND 时 `migrants > 0`
  - source 迁移后 labor commitment 保留比例正确
  - `DailyDemandBill` 是唯一账单
  - `selfLand/hiredLand`/`B_cycle` 的验收值
- 四类关键变异：守恒、不丢失身份/operator、静默付 0、断粮；变异必须在独立工作树/隔离副本做，原树 md5 复原。

---

## 5. 依赖与暂停点

```text
RC8a
  ↓
RC8b
  ↓
RC8c
  ↓
RC8d
  ↓
RC8e
  ↓
RC8f
  ↓
T2
```

- RC8a 先做，因为它决定后续 360 是否是可解释的经济数据；
- RC8b 是 #2 的主责任区；
- RC8c 与 RC8d/e 可在 RC8b compile 后串行；
- RC8f 最后，避免公式改动掩盖前面的接线错误；
- 每块停手后才允许下一块改同一批文件；一个文件一个 owner。

---

## 6. 非目标

- 不实现 v4 §8 同阶级整组织交易（v4 自己列为独立后续；需独立约束设计）；
- 不直接修改 #7 的土地价格/供给侧算法（`MarketPricer` / `collectSellDrafts` 不改）；
- 不恢复四阶层 rural slot、租佃/rent/pledge/抵押、财富自动向上；
- 不把 LAND 做成 block；
- 不做文化传播/同化；
- 不写/不跑测试、不 commit/push、不碰主树。

---

## 7. 验收总则

- 每个 writer 只写生产代码，`tools/mvn-lock.sh -q -DskipTests compile` 绿后交账；
- 每个 writer 的 `impl-ledger.md` 必须写：
  - 本责任区在 S0–S8 的锚点；
  - 实际数据流/调用次序；
  - 与文档差异及原因；
  - 对父序列影响（顺序/预算冻结/身份持久化/失败/日志/测试）；
  - 未做/未验证/blocker；
- 任何 `HAUL_SERVICE_DELIVERY_FAULT`、`role-unknown`、`LANDLESS+LAND`、负库存、未提交 advance 一律不得写成 DONE；
- 最终以真实 32hex 360 + 关键 ERROR 为零 + 经济不变量为验收，不以 `status=OK` 单独作为通过。

---

## 8. 开始修复的顺序

1. 先写 `RC8a` 任务书/约束设计书，派一个 transport writer；
2. RC8a compile 绿后，写 `RC8b` 任务书，派一个生产/劳动 writer；
3. 依次 RC8c/RC8d/RC8e/RC8f；
4. 全部停手后派 T2 测试 owner；
5. T2 绿后重跑 32hex 360，并核对本计划 §7 的异常计数。

---

## 9. 执行记录（2026-10-21）

### 已完成并 compile 绿

- **RC8a**：运输服务成市判据 + haul 交付契约 + fail-closed；账本
  `.superpowers/sdd/2026-10-20-d032-rc8a-transport-contract/impl-ledger.md`。
- **RC8b**：当前户 actual labor 接入生产/劳动/工资；迁移承诺按剩余人口保留；动态同 hex hired 候选；
  账本 `.superpowers/sdd/2026-10-20-d032-rc8b-actual-labor/impl-ledger.md`。
- **RC8d/RC8e（mobility）**：S7-B 动态 minLand/财富门/0 人口 LANDED 壳；S7-C 10‰ + hysteresis；
  账本 `.superpowers/sdd/2026-10-20-d032-rc8d-mobility/impl-ledger.md`。
- **RC8e（enterprise-roster sync）**：迁移/合户后 enterprise.laborSources = roster.households()；
  账本 `.superpowers/sdd/2026-10-20-d032-rc8e-enterprise-roster-sync/impl-ledger.md`。
- **RC8c**：transient `DailyDemandBill`、S4 provided、S7 账单 BUY/SELL、actual surplus；
  账本 `.superpowers/sdd/2026-10-20-d032-rc8c-daily-demand-bill/impl-ledger.md`。
- **T2**：economy/app 测试迁移与新增 RC8 验收；economy 509/0/0/0，app 1022/0/0/7，前端 412/412；
  账本 `.superpowers/sdd/2026-10-20-d032-t2-rc8-tests/`。

### 最终 32hex 360（2026-10-21）

- `status`: `OK=360 / REJECTED=0`
- `HAUL_SERVICE_DELIVERY_FAULT=0`
- `POST_LAND_CLASS_CHECK_FAILED=0`
- `HOUSEHOLD_FLOW_APPLIED=0`（当前债务压力未触发 1% flow）
- harvest grain：`3,065,856,000 / 3,065,856,000 / 3,065,856,000`
- final population：`7287`
- final debt principal：`1612`
- final `marketTrades=77 / marketUnfilled=262`
- CSV sha256：`0ce99115e06941b580104d1d6f4145442856a1efbc4a4f57332e163a3875c0b0`
- MD sha256：`60b1243f36f10b85a393d55b4f6c3ad5d318a937fbdb96e62ae35262437e854f`

### 未完成 / 不在本次范围

- **RC8f 父 v2 的 `B_day/B_cycle`、单 hex `standard_agriculture` 聚合、`selfLand/hiredLand` 持久视图**
  仍未按 v2 公式重写；当前 360 使用现有 production runtime efficiency 模型。若“完整 v4”包含该父 v2
  公式层，需要另开责任区。
- v4 §8 同阶级整组织交易未做（v4 自己列为独立后续）。
- #7 土地价格/供给侧算法未直接修（用户裁定暂不修）。
- 变异自证本轮未做（T2 已做测试迁移与新验收）。

### 最终门禁与 360（2026-10-21 02:0x）

- `tools/gate.sh full`：**PASS**（耗时 300s）
  - 全仓 surefire：**tests=3647 / failures=0 / errors=0**
  - 前端门禁：**412/412**
  - shaded jar：**28,993,955 B**
  - `validate`：rc=0，Checkstyle 0 violations
- 最终 32hex 360：
  - `status`: **OK=360 / REJECTED=0**
  - `HAUL_SERVICE_DELIVERY_FAULT=0`
  - `POST_LAND_CLASS_CHECK_FAILED=0`
  - `MARKET_SELF_MATCH_SKIPPED=0`
  - harvest grain：**3,065,856,000 / 3,065,856,000 / 3,065,856,000**
  - final population：**7287**
  - final debt principal：**1612**
  - `MARKET_FILLS=77 / MARKET_UNFILLED=262`
  - CSV sha256：`0ce99115e06941b580104d1d6f4145442856a1efbc4a4f57332e163a3875c0b0`
  - MD sha256：`60b1243f36f10b85a393d55b4f6c3ad5d318a937fbdb96e62ae35262437e854f`
