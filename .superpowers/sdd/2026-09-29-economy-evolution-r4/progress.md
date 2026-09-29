# R4 经济演化切片进度台账（2026-09-29）

> 台账性质：每片完成后追加，不覆盖历史；详细实现报告在 `.superpowers/sdd/2026-09-29-economy-evolution-r4/*-report.md`。
> 基线：`6170760f`（R3B.1）；工作树干净。事实/缺口表：`docs/superpowers/reports/2026-09-29-economy-evolution-r4-code-facts.md`；
> 计划：`docs/superpowers/plans/2026-09-29-economy-evolution-r4-plan.md`。

---

## B.2 ProductionUnit + AssetShare 生产接线 —— 已完成（未提交→已提交见 git）

### 实现了什么
- 第 14 个经济状态组件 `ProductionUnit`：`(id, industry, operator, modeKey, progressDays, cycleLaborMilli, cycleInputUsedMilli)`；
  `ProductionUnitBook` 是 `AssetShare → usableAssets → capacityScale` 的唯一派生点。
- `Industry` 只留模板；旧字段（operator/capacity/progress/cycleLabor/inputUsed）退化为**旧档反序列化兼容位**，构造期归一化成
  默认 unit + 整额 `OWNED` AssetShare 后清空；生产路径不再读它们。
- `ProductionRelation.activity` 与 `operatorConditions` 键改 `ProductionUnitId`；结算/市场/读口/分类器全部按 unit。
- 旧档兼容：`Timeline.readChangeSet`（核心的第四台 mapper）直接严格绑定 record，因此兼容位必须留在 `Industry` 上；
  `EconomyData` 构造期归一化保证 codec/Timeline/载荷三条读入路径一致。
- 真实缺陷修复（B.2c）：迁移保留旧配额 id，而 `addLabor` 只按新 unit 型 id 查重 ⇒ 同一 `(unit, lot, household)` 出现双行；
  tick30 死亡缩放逐行 floor 时 388 个键各少 1 毫劳动。修复：`addLabor` 先按 unit 型 id、再按产业型旧 id 查重并合并
  （仅当 `activity == 本 unit`），保留旧行 id。

### 领域验收证据（1 线程，同一 tick0 夹具，0→30 tick）
- 夹具：由基线 jar 三国 worldgen 生成（799 经济格），复用 `/home/cna/SimosData/simos-dev/r4-tick0-fixture-20260929` 的副本。
- 编译：`tools/mvn-lock.sh -DskipTests compile` BUILD SUCCESS；shaded jar packaged。
- 旧档重放：`Timeline.readChangeSet` 读旧 industry 形状 changeset `OK`；Probe3 apply rev2/3/4 到 `EconomyData.empty()`：
  units 数 == 有正 capacity 的 industry 数（1799=1799）；`Σ AssetShare(industry,asset) == 旧 capacity` 1799 键全等；
  relations/conditions 键全为真实 unit；memberships 12912→17016 逐行保留（B.2b 修掉了会重算成 9896 条的旧行为）。
- A/B：基线 0→30 = 41.0 s；B.2 修复后 0→30 = 38.7 s；26 个均匀抽样格 + 3 个重点格：
  **tick0 逐值相等；tick30 忽略 `units`/派生 reason 措辞/subsistenceObligations 后 26/26 零差异**；
  全量规范状态 42,857 行（alloc/supply/unit/class/flow/share/condition）diff 完全一致；
  人口 11,854,635、alloc 总量 5,584,737,374、births/deaths 60,165/35,530、AssetShare 总量均与基线相等；
  `Σalloc ≤ available` 无违反（最大差 −1260）。
- 计划对照：原计划要求“单 unit 旧世界行为逐值等价”**已达成**（在 R3B.1 可读档范围内；见阻断点 1）。

### 与计划不同的地方
1. 旧兼容位移到 `Industry` record + `EconomyData` 构造期归一化，而不是只留在 `EconomyCodec`；原因是 `Timeline.readChangeSet`
   不经过模块 codec，这是计划写作时未识别到的架构事实。
2. `Industry` 物理上保留 5 个兼容位（生产路径禁读），而非在 B.2 一次性删除；删除留到 V 的静态审计后。
3. 旧 changeset 的 `Remove` 键（relations/conditions 旧 industry id）仍不会命中 unit 键、删除变 no-op；当前 main 无删除路径。

### 剩余阻断点 / 已知风险
1. **S1 之前的旧档（如 M2 `store-m2`）在 HEAD 基线上本来就不可读**：旧 changeset 的 `useRights` 组件在 Timeline
   严格绑定下 `UnrecognizedPropertyException`（B.2 前后同样失败，不是本片回归）。R3 决策单原文允许 S1 前归档不可开；
   V 阶段若要支持，需要与 Industry 兼容位同款的 `useRights → assetShares` record 级兼容层。
2. `MarketReadout` 仍无逐 unit 行（unit 行在 `ApiViews.economyHex.units[]`）；`EconomyResolver` 未加 `productionUnit` 地址 kind。
3. 多 unit 世界（同 industry 多 operator）尚未实测；B.3 的首要验收。
4. 1/4/8 线程与全年性能、峰值内存未测；V 阶段统一做。
5. 测试源码仍按旧 `Industry` 形状构造，V 阶段必须成批适配（本片按纪律未动测试）。

### 下一步
B.3：新世界多 unit 播种（庄园自营 + 佃耕 + 家户自用）、资产转移/显式停业命令、c1 孤儿债对账；验收同 hex ≥2 unit 各自
progress/产出账、`Σ 份额 == 旧 capacity`、无孤儿债、资产变化先于产出归属变化。

---

## B.3a 新世界多 unit 播种 —— 已完成（含一项性能阻断）

### 实现了什么
- `EconomySeeder` 把每格三个产业的产能与劳动按确定规则拆成“主 unit + 家户副 unit”（只拆不加）：
  - farm：庄园自营（ESTATE，700‰ LAND，feudal 默认关系）+ 每个农村劳动家户一条 TENANCY tenant unit（合计 300‰ LAND，
    owner=农村 landlord 家户，规则=毛产 grain 300‰ 付 owner，residual=佃户，laborSource=TENANT）；
  - weave：主 unit（HOUSEHOLD:weave@hex，700‰ TOOL）+ 每个农村劳动家户一条 OWNED weave unit（合计 300‰，
    空 rules 全自留，laborSource=FAMILY）；
  - craft：作坊自营（WORKSHOP，700‰）+ 每个城镇劳动家户一条 TENANCY artisan unit（合计 300‰，
    规则=毛产 cloth 300‰ 付作坊主，inputSupplier=作坊主，laborSource=TENANT）。
- 常量集中在 `EconomySeeder`：`SECONDARY_PER_MILLE=300`、`TENANT_RENT_PER_MILLE=300`；劳动/资产拆分走最大余数法，
  并列按 `HouseholdId.value()` 升序；新 unit 显式发 `relation`，主 unit 沿用默认关系。
- 自租退化（operator 恰为 landlord 家户时租规则自环被 Transfer 守卫拒）退回 owner/受方 = `ESTATE:farm@hex`，规则类型/费率不变。

### 领域验收证据
- tick0 对 B.2 同一三国 worldgen：AssetShare 1799 个 `(industry, asset)` 键 **0 差异**、总量 2,286,937,777 逐值相等；
  配额按 `(group, household)` **0 差异**、总量 5,586,267,122 逐值相等；人口 11,830,000、货币 142,924,800、商品库存逐值相等。
- 多 unit：8,940 个 unit（farm 3,995 / weave 3,995 / craft 950），1,799 个产业最少 4 条 unit；抽 8 格每格 farm/weave
  主+4 户、craft 4–5 条；`Σ unit.assets == 旧 capacity` 逐项成立；8,940 条 relation 与 unit.operator 0 不一致、自环 0。
- 0→120 tick（8 线程）：提交成功无异常；AssetShare 总量与货币守恒；人口 11,830,000 + 出生 99,472 − 死亡 306,747 =
  11,622,725 逐值闭合；无负余额；`max(Σalloc − available) = −1260` 无超发；`operatorConditions` 0→8,940 条。
- tick121 读口：主/副 unit 均 `progressDays=1`，副 unit 有 `cycleInputUsedMilli`，farm/weave 副户有
  `lastCycleRevenueMilli/unsoldStockMilli`，产出落到各 actor 账。
- 旧档路径不受影响：B.2 的 `Probe3`（Timeline changeset 重放 rev2/3/4）PASS=60 FAIL=0；`/tmp/b2-tick0-fixture` Python 重放成功。

### 与计划不同的地方 / 剩余阻断
1. **性能阻断（必须下一片解决）**：0→120 实测 **752.723 s（12 分 32.7 秒）**，超过计划 ≤3 分钟冒烟上限。
   多 unit 放大了既有的两个串行 O(units×allocations) 面：`householdKeysOf` 每 unit 扫全量配额（8940×44564）、
   `ProductionUnitBook.usableAssets` 每 unit 扫全量份额（8940×8940）；8 线程无法覆盖这段串行面。
   证据：报告 §4.2 + `/tmp/b3a-jstack.txt`。
2. 55 个 craft 小容量格只建出 3 个匠户副 unit（分不出 ≥1 份资产的户被跳过，份额/劳动留在主 unit；守恒）。
3. craft artisan 的 300‰ 布租未逐笔核对落到 WORKSHOP 账；商品未做完整“产出−投入−消费±转移”总账。
4. 未跑 test/verify；未提交/未做 B.3b 命令与孤儿债。

### 下一步
B.3a-perf：为结算加**每次推进一次**的派生索引（AssetShare→unit 可用资产/规模、allocation→unit/家户），
消除 O(units×allocations) 串行扫描；验收 0→120 ≤3 分钟且与 B.3a 同初态逐值等价。
之后 B.3b：`economy.TransferAssetShare` + c1 孤儿债对账（`DebtReferenceReconciler`）。

---

## B.3a-perf 结算索引化 —— 已完成

### 实现了什么
- 新增 `simos-economy/time/SettlementIndex.java`：**每步一次**构建的只读派生索引（不进 EconomyData/ChangeSet/Codec）：
  `usableAssetsByUnit`、`capacityScaleByUnit`、`allocationsByUnit`、`householdsByUnit`、`unitsByHousehold`、
  `unitsByHex`、`debtsByDebtor` 等；协调器单线程建、并行 worker 只读。
- `ProductionUnitBook`/`EconomySettlement`/`MarketSettlement`/`OperatorSettlement`/`HouseholdClassRule`/`ProducerCostBook`
  的热点调用点改查索引；算式、顺序、并列规则、`StressPolicy`、价格、常量未动。

### 领域验收证据
- 0→10 tick 完整领域状态 A/B（同一固定 tick0，8 线程）：基线 45.7 s vs 新 7.3 s，**canonical state 0 差异**；
  另一固定世界（B.3a 自租修复后的 rev4 裁出）同样 0 差异。
- 0→120 tick（8 线程）：**36.0 s**（另一次 34.8 s），对照 B.3a 752.723 s 约 **20.9×**；峰值 RSS ≈1.53 GiB。
- 1 vs 8 线程 0→10：完整领域状态 **0 差异**。
- tick120 守恒读数与 B.3a 报告逐项相同（人口/alloc/AssetShare/货币/goods/debts/conditions、`max(Σalloc−available)`）。
- 旧档迁移 Probe3：PASS=60 / FAIL=0。
- 编译/spotless 绿；shaded jar md5 `2cf86215762fb90807074133249bf281`。

### 与计划不同的地方 / 阻断
1. 任务书默认的 `/tmp/b3a-store` 实为 B.3a 自租修复**前**的世界（799 条自环租规则，0→120 第 120 天会被 Transfer 自环守卫拒）；
   性能/守恒/固定夹具 A/B 改用 `/tmp/b3a-store2` 的 rev4（自租修复后世界）。自租修复已在 B.3a 代码中，不是新问题。
2. 未做 0→120 的完整基线逐值 A/B（基线 0→120 进程被环境回收）；0→10 的完整逐值 A/B 已覆盖行为不变性。
3. 未做全年 1/4/8、峰值内存正式协议、JFR；V 阶段统一。

### 下一步
B.3b：`economy.TransferAssetShare` 命令 + c1 孤儿债对账（`DebtReferenceReconciler`）。
`CloseProductionUnit` 与 `ReclassifyHousehold` 分别并入 E1（退出处置）与 B.4（纯派生），不在此片造半成品出口。

---

## B.3b 资产转移命令 + c1 孤儿债对账 —— 已完成

### 实现了什么
- 新 `economy.TransferAssetShare` handler：`share` 必填；`quantity` 缺省=原量；owner/operator/kind 至少一项变化；
  整条转移删旧行+新 id，部分拆分原行扣减+新行；新 id 用同 tuple 现有 id 尾段最大 sequence+1（不可解析 fail-closed）；
  非法 quantity/无差异/重复 id 一律 Rejected；只写 `assetShares`，不动商品/货币/债务/劳动/关系。
- 新 `DebtReferenceReconciler`：以债务表为权威、按 debtor 分组 canonical 排序重建 `ClassRow.debts`；debtor/creditor 行缺失具名 fail-closed；
  principal/defaulted 不动；一致时 no-op。调用点在 `EconomyData` 构造期（LegacyHouseholdMigration 之后、跨表守卫之前）。
- 放宽“非 EXITED unit 必须有同 industry 份额”的守卫：unit 允许 0 份额（规模=0），这是退出/闲置/资产全转走的合法状态；
  unit↔relation/condition 的 operator/industry 一致性仍守。
- 注册 `Shell` handler 与 `CatalogTool.PAYLOAD_HINTS` 条目（启动期 fail-closed 覆盖全部 type）。
- `CloseProductionUnit` 留 E1（必须与退出资产/劳动/库存处置一起）；`ReclassifyHousehold` 不做（B.4 纯派生，显式改标签会破坏）。

### 领域验收证据（8 线程，shaded jar md5 2872058a…）
- 编译/Checkstyle/spotless 绿。
- Transfer E2E：整条改 operator committed（旧 id 消失、新 tuple/quantity 正确、原 unit 0 份额）；部分拆分 committed（quantity−1 + 新行 1，sequence=max+1）；
  quantity=0/超量/无差异三次 REJECTED、head 不变；rev7/8 changeset 只动 assetShares；推进 121→122 6.2s，0 份额 unit 不抛、逐 (industry,asset) 总量不变；15/15 守恒/身份断言 PASS。
- c1：合成三案例 PASS=13/0（补引用、幂等、principal/defaulted 不变、debtor/creditor 缺失 fail-closed、重复/多余引用重建）；
  真实 tick120 旧档组件探针：332 债 / **62 条孤儿 → 0**、principal 10,495,732 不变、defaulted 未动、Σ引用=332、幂等，PASS=7/0。
  该旧档整体经 Timeline 仍因 R3 前 `useRights` 严格绑定不可读（记录为旧档兼容阻断，未越界修）。
- 回归：B.2 Probe3 PASS=60/0、Probe5 PASS=11/0、Probe2 两条 OK；B.3a 固定世界 0→10 6.8s 无异常。

### 与计划不同的地方 / 剩余阻断
1. `CommandTargets` 未声明 unit 地址（与 `economy.MigrateHousehold` 同款），GM `simos.command.submit` 可用；directive 内会被 fail-closed 拒。
2. 真实 c1 存档不可经完整 command/advance 路径重放（旧 `useRights` 绑定问题），对账用同包探针在组件层验证。
3. 未跑 test/verify/SpotBugs/1-4-8/全年。

### 下一步
B.4：删除 `HouseholdClassRule` 的 `slotCapFallback/feasibleStratum`，确认分类只写 `ClassRow.view`、不改 participationPerMille/劳动/资产/账户/债务；
验收同状态重放分类一致、无富农证据就输出 0、账户/劳动先变标签后变。

---

## B.4 阶层纯派生收口 —— 已完成（未提交）

### 实现了什么
- `HouseholdClassRule`：删除 `feasibleStratum/fallbackOrder/slotCapOf/slotCapText/slotCapsByHex/universalSlotCaps/LEGACY_TIER_STRATA`
  与 `Index` 构造期的 slots 建表；`classify` 直接返回纯派生 `stratum`，reason 不再拼槽位回退段。
- `EconomyData`：删除 classes 的 `requireStratumAllowed` 调用与 `participationPerMille ≤ slot.laborParticipationPerMille`
  守卫；flows 侧 helper 退化为 `requireIndustryRegistered`（只判“该格有产业”）；删除 `CREATION_SLOT_STRATA` 与相关 import。
- 未改 `EconomySettlement`/`MarketSettlement`/`EconomySeeder`/`SettlementIndex`/`EconomyChangeSet`/`EconomyCodec`/
  `Industry`/`ClassSlot` 形状；`Industry.slots` 线格式不变。

### 领域验收证据
- 编译/spotless 绿（`/tmp/b4-final-compile.log` 等）；shaded jar md5 `3000a539c240300408996568bc61eafd`。
- 纯函数：同一 tick0 `EconomyData` 6,392 行，每行同 Index 连续 2 次 + 独立 Index 第 3 次 `Classification`
  逐行相等（0 不一致）；分类前后 14 组件 SHA-256 指纹不变（tick0/tick120 各一份）。
- 0→120（8 线程，35.27 s；同初态）：与 B.4 前 8 线程关账 store（B.3a-perf final120）全状态 diff，
  **唯一不同组件 = `economy.classes`**；6,392 键相同，799 行只改 `view`，非 view 字段 0 差异，
  其余组件（allocations/assetShares/debts/账户等）逐值相同。
- 前后分布：baseline `{middle 2196, poor 1452, …}` → B.4 `{middle 2995, poor 653, …}`，恰好 799 行
  `poor 950‰ 派生 middle` 不再被降回 poor；landlord/artisan/rich 不变。
- 活跃富农 = 0 的证据：`ownLand>0 & netLaborSold<0 & 无租权` 的行数 = 0；总数 598 的 `rich_peasant`
  全部是 `retainedCurrentView:noObservableEvidence`（既有语义，不是新造标签）。
- 1 vs 8 线程 0→120（1t 38.62 s）：canonical 全状态 0 组件差异，classes/classifications 分布逐值相同。
- 回归：Probe2 world 两条 OK；Probe3 `PASS=60/0`；Probe5 `PASS=11/0`。

### 与计划不同 / 剩余阻断
1. `requireStratumAllowed` 的 flows 调用点保留为 `requireIndustryRegistered`（只判产业存在）；classes 侧
   “该格有产业”校验随调用一并删除。
2. `Index.of(...)` 的 `industries` 形参保留但不读（不改结算/读口签名）。
3. 既有测试仍写着旧槽位上限断言（V 统一适配）；`ClassRow`/`EconomyMigrateHouseholdHandler` 有陈旧注释。
4. 未做 4 线程/全年/峰值内存/JFR/TransferAssetShare 命令 E2E；未跑 test/verify；未 commit。

详见 `.superpowers/sdd/2026-09-29-economy-evolution-r4/B4-report.md`。

---

## B.4 阶层纯派生收口 —— 已完成

### 实现了什么
- `HouseholdClassRule`：删除 `feasibleStratum/fallbackOrder/slotCapOf/slotCapText/slotCapsByHex` 与 slots 建表；`classify` 直接返回派生档，
  reason 不再拼 `slotCapFallback`。
- `EconomyData`：删除 `ClassRow.view` 的 `participationPerMille ≤ Industry.slots` 上限守卫与相关 helper/import；保留人群/债务/成员/份额/参与率范围等守卫。
- `Industry.slots` 保留为生产方式内部角色配置，线格式不改；不做任何“为标签改 participationPerMille/劳动/资产/账户/债务”的路径。

### 领域验收证据
- 编译/spotless 绿；shaded jar md5 `3000a539c240300408996568bc61eafd`。
- 纯函数性：6,392 行同状态连续 2 次 + 独立 Index 第 3 次分类，Classification 不一致 0；分类前后 14 组件 SHA-256 指纹不变。
- 0→120（8 线程 35.27 s）：与 B.4 前同初态全状态 diff，**唯一不同组件 = economy.classes**；6,392 键相同、799 行只改 view；
  allocations/assetShares/debts/账户等逐值相同。
- 分布：middle 2196→2995、poor 1452→653；恰好 799 行 “poor 950‰ 派生 middle 被旧槽位降回 poor” 现在保留 middle；
  landlord/artisan/rich 不变。零活跃富农证据：`ownLand>0 & netLaborSold<0 & 无租权` = 0；598 个 rich 全是无证据时保留旧 view 的既有语义。
- 1 vs 8 线程 0→120：1t 38.62 s / 8t 35.27 s，全领域 canonical 状态 0 差异，classes/classifications 分布逐值相同。
- 回归：Probe2 OK、Probe3 PASS=60/0、Probe5 PASS=11/0。
- 禁止性路径审计：`slotCapFallback/feasibleStratum/slotCapsByHex/requireStratumAllowed` main = 0 命中；写回唯一写点 `row.withView(...)`。

### 剩余阻断 / 未验证
- 既有测试仍保留旧槽位断言、部分陈旧注释；V 阶段统一适配。
- 未跑 test/verify/4 线程/全年/峰值内存；未 commit（本台账追加后由控制方提交）。

### 下一步
E1：旧生产方式衰退 → 经营者/关联家户不同后果；退出时资产份额、劳动配额、库存、债务都有去向；自用可维生不判破产。

---

## E1 衰退 / 退出处置 / 家户后果 —— 已完成

### 实现了什么
- 新 `EconomicHouseholdResolver`（唯一解析点）：operator 家户 → relation 家户受方 → 份额 owner/operator 反查 → empty；
  `SettlementIndex` 一次建 `economicHouseholdByUnit/assetShareIdsByUnit`；ESTATE/WORKSHOP/聚合 weave 解析不到家户 ⇒ 不强行借债，但市场/投入压力仍可推进退出。
- `StressPolicy` 新增 `INPUT_SHORTFALL_CYCLES_BEFORE_CANNOT_REPRODUCE=3`、`UNSOLD_CYCLES_BEFORE_CANNOT_REPRODUCE=3`、`SELF_PROVISION_GUARD_DAYS=30`；
  `OperatorSettlement.canSelfProvision`：粮库存 ≥ 本周期基本口粮，或 30 天口粮 + 自用投入全覆盖。
- 状态转移新增：CONTRACTING 自用维生硬门（不进退、reason `self_provision`）；无法再生产→SUSPENDED；INDEBTED 自用恢复→CONTRACTING；
  SUSPENDED→EXITED 必须“停业够久 **且** !canSelfProvision”。
- `settleOperatorExits` 固定顺序：释放 `activity==unit id` 的劳动配额（laborSupply 不动）→ TENANCY 份额只改 `operator` 回 owner、OWNED 留 owner →
  既有债务偿还/defaulted（只对解析出的家户，走 applyTransfer）→ 库存/货币留原账 → `lastReason` 追加处置摘要。
- `HouseholdCondition` 新增只读 `grainCoveragePerMille`；`livelihoodOf` 仅在无资产、无劳动卖出、无自用覆盖时判 DESTITUTE；
  `ApiViews.economyHex` 从 actor 账取逐户粮库存输出新字段。
- **未新增 EconomyData 状态组件**；ChangeSet/Codec 未动；`OperatorCondition` 只加 `withLastReason` helper（形状不变）。

### 受控场景验收（/tmp/E1Probe.java PASS=74/0）
- A 自用维生：持续滞销+投入不足 8 周期仍止于 CONTRACTING（reason `self_provision`）；家户 TENANT / laborSold=3000 / laborSelf=3000 / unmetGrain=0 / coverage=1000。
- B 无法再生产→EXITED：配额删 2 条释放 3000；TENANCY 600 回 owner、OWNED 400 保留；债务 500 = 还 200 + 违约 300；123 毫粮留存；
  人口不变；diff `allocations removed=2, sharesChanged=1, debtsChanged=1, goodsChanged=0, moneyChanged=2`。
- C 同 owner 两 unit：退出处置不触碰存续 unit 的份额/劳动/债务/货币/状态；重放两次六张表逐值一致。
- 0→120（8 线程）34.183 s；与 B.4 8 线程 store 全模块/全组件 diff = NONE；Probe3 60/0、Probe5 11/0、Probe2 OK、分类 0 mismatch/0 non-view 写回。

### 与计划的差异 / 剩余阻断
1. 0→120 只有一个关账周期，8,940 条 condition 全 ACTIVE，**自然退出为 0**；退出路径由受控探针覆盖。
2. 未新增 `livelihoods` 持久组件（继续读时派生）；家户后果通过 coverage/status/配额/份额读数观察。
3. `economy.CloseProductionUnit` 显式命令未实现（留作独立小片或 V）。
4. 未跑全年/受控场景 1vs8（用同状态重放两次替代）/真实 c1 旧档/GOV 租赋；未跑 test/verify。

### 下一步
E2：GM 注入需求（AddDemand/SetMarketPrice/RegisterCandidate）→ 订单可见 → 合条件家户采用预设形成 TRIALING 新 unit；
无可行预设则需求未满足；旧经营者默认延续、不自动全局 ROI 切换。

---

## E2a 需求账本 + 候选预设 + GM 命令 + 订单路径 —— 已完成

### 实现了什么
- 第 15/16 状态组件：`demands: Map<DemandId,DemandEntry>`（HOUSEHOLD/HEX scope、RECURRING/ONE_OFF、TOTAL/PER_CAPITA、created/expires/priority/source）、
  `candidates: Map<CandidateId,ProductionCandidate>`（version/配方/requiredAssets/labor/buildDays/cycleDays/regime/laborSource/acceptedRightKinds/assetSource）。
  同步 `EconomyData/empty/withX/缺键空表/跨表守卫/EconomyChangeSet 键解析/Codec 键反序列化/ApiViews`。
- 四条 GM 命令：`SetMarketPrice`（可建 Silver 空市场，不造商品/货币）、`AddDemand`（无价拒绝并指名 SetMarketPrice）、`CancelDemand`、`RegisterCandidate`
  （`(id,version)` 唯一；旧 unit modeKey 不动）；Shell + CatalogTool PAYLOAD_HINTS 同步。
- 订单路径：`MarketRound/HexPlan` 只读 demands；新 `DemandTargets`（HOUSEHOLD 直归、HEX 按人口最大余数摊、TOTAL/PER_CAPITA、时间窗过滤、priority+DemandId 序）；
  `ordersFor` 家户目标 = 粮/布 35 天基线 + 有效需求，需求同时进“不卖”侧；预算先基线再按 priority 拿剩余；缺价不生成订单。
  `demands` 空表时 42 组件与旧基线逐值相同。

### 领域验收
- 编译/spotless/package 绿；shaded jar md5 `8cacdd8b…`。
- 固定世界 0→10（HEAD worktree jar vs 本片，8 线程）：42 组件 **0 差异**；0→120 与 HEAD 已关账 store：changed_components=NONE。
- GM：wool 定 10 毫 → AddDemand → D+1 `effectiveDemandMilli=10,000`；Cancel 后下一轮 0；同三时点 grain/cloth/fiber 与无需求世界逐值相等。
- 边界：无价 AddDemand Rejected 指名 SetMarketPrice；重复 `(id,version)`/版本回退 Rejected；SetMarketPrice 只写 markets、需求命令只写 demands。
- 性能 0→120 34.218 s（HEAD 34.313 s）；VmHWM 1.65 GiB；Probe2/3/5 全过。

### 与计划的差异 / 剩余阻断
1. `ONE_OFF` 不做跨轮剩余递减（有效窗口内按目标量；E4 若需要再补）。
2. `candidates` 每个 id 只保留当前版本（version 修订即覆盖旧版本行；旧 unit 的 modeKey 不变）；如需版本历史留 V/E3。
3. 未实现进入算法 `EconomyEntrySettlement`（E2b）；`CommandTargets` 未声明（GM command.submit 可用，directive 内 fail-closed）。
4. 未跑 test/verify。

### 下一步
E2b：候选预设实际采用（合条件家户形成 TRIALING unit、buildDays 前无产出、无可行预设保持未满足、旧经营者不自动 ROI 切换）。

---

## E2b 候选预设实际采用 —— 已完成

### 实现了什么
- 新 `EconomyEntrySettlement`（两段式 `planEntries` 只读意向/具名拒绝 + `execute` 重新评估后写工作副本）、`EntryOutcome`（进程内审计行）、
  `EntryOutcomeFeed`（进程内按 mapId+day 投递，读口用）；`EconomyStateBuilder.relationsOrBase()`（relation 工作副本）；
  `settleOneDay` 在现扣投入/劳动再分配之前执行进入，执行后重建 `SettlementIndex`；`ApiViews.economyHex` 增 `assetShares[]`/`entryOutcomes[]`。
- 可行性：同 (household,candidate) 幂等；资产=自有份额或从 candidate.assetSource 拆分（owner 不变、operator=家户、kind 按 acceptedRightKinds，
  Σ 份额不变）；requiredAssets 空/全 0 本片判不可行；劳动/投入/生计/可观察价/score 全部用实际读数；trialScale 取 min。
- 执行：建 `<candidateId>@q_r` Industry 模板 + ProductionUnit(modeKey=candidate@version) + 空 rules 关系（residual 家户）+ TRIALING condition +
  份额拆分 + 新劳动配额；缺价/不可行不建 unit，需求维持未满足；旧经营者默认延续、不自动 ROI 切换。

### 收尾代理在原部分实现上修掉的缺陷
1. `assess` 评分量纲（收益/投入/口粮三项相差 10³–10⁶ 倍）与缺价口径（改为主产出/正投入/给养粮缺价一律 PRICE_MISSING 拒绝）。
2. `reallocateLaborPartitioned` 触发语义被上一代理收紧成“全部 unit 第一天”；恢复旧口径，并用 `isPresetOrigin` 只让候选 unit 自己的周期边界不触发整格重排。
3. 人口回写 `applyPopulationChangeInto` 改读 `session.sheet().relationsOrBase()`，不漏刚进入 unit 的 relation。
4. `OperatorSettlement.advance` 补首次收获 `TRIALING→ACTIVE`（`trial_complete:firstHarvest`），负证据分支优先。

### 领域验收
- 空表 A/B：B.3a 世界 0→10、0→120 与 HEAD 42/42 组件 **0 差异**；旧 B.2 形状 store 0→10 也 0 差异。
- 成功采用：tick1 出现 2 个 TRIALING unit（真实家户、`cand-wool@1`、LAND=10,000、owner=ESTATE/operator=家户/TENANCY）；
  day1/5/29 无产出，day30 净产 wool 34,920,000 并转 ACTIVE；份额行 15→17、Σ 2,286,937,777 逐值不变；货币不变；`max(Σalloc−available)=0`；多日不重复建 unit。
- 不可行：无 candidate/资产/劳动/投入/权利不足/score<0/需求窗口太短 ⇒ 0 unit + 具名 `entryOutcomes`。
- 确定性：同初态同命令 1 vs 8 线程 42/42 组件 0 差异。
- 性能：空表 0→120 36.248 s（HEAD 35.571 s，+1.9%，VmHWM 1.57 GB）；带进入 37.627 s。

### 与计划的差异 / 剩余阻断
1. 家户自有份额只认“候选产业 id 名下”的份额；跨产业再登记未实现（本批全部走 assetSource 拆分）。
2. `buildDays` 只进需求/投入 horizon 门槛，不推迟 progress 与收获（进入日即开始进度）。
3. `EntryOutcomeFeed` 进程内瞬态、重启即失；`TRIAL_ABORTED` 完整回滚未做（失败走既有 inputShortfall 状态机）。
4. 因前一轮 /tmp 清理，Probe2/3/5 未重跑，用三组全状态 A/B 替代（报告已如实写）。
5. 未跑 test/verify。

### 下一步
E3：实际生产实践的经验积累（挂稳定 HouseholdId × modeKey；实劳实产才计提；纯收租 0；阶层变化不丢；地区规模有界渐近）。

---

## 阶段总结（2026-09-29，E3 暂缓）

### 当前状态
- 分支 `ts/m1-r3b2`，HEAD `a3dbcbdc`；工作树干净、无 stash、无残留服务/Maven；所有提交都在本地（`origin/ts/m1` 仍停在 `6170760f`，未 push）。
- `EconomyData` 当前 **16 个组件**：meta / industries / classes / debts / flows / laborSupply / allocations / relations /
  markets / shipments / memberships / assetShares / operatorConditions / units / demands / candidates。
- 已完成切片（10 个提交，从旧到新）：
  1. `915d328b` **B.2** ProductionUnit + AssetShare 生产接线（旧档兼容 + 配额去重）；
  2. `24108dba` **B.3a** 新世界多 unit 播种（庄园自营+佃耕+家户纺织，只拆不加）；
  3. `dfee4bf7` **B.3a-perf** 结算派生索引化；
  4. `ecdfe04f` **B.3b** TransferAssetShare + c1 孤儿债对账；
  5. `dd95b8f2` **B.4** 阶层纯派生收口；
  6. `e2a48c49` **E1** 衰退/退出处置/家户后果；
  7. `5d3435d6` **E2a** 需求账本/候选预设/GM 命令/订单路径；
  8. `a3dbcbdc` **E2b** 候选预设实际采用（TRIALING 试产）；
  （另有 `76d9e792` 文档：缺口表+计划+台账）。

### 四项目标完成度
| 目标 | 状态 | 关键证据 |
|---|---|---|
| 1 旧方式衰退 + 经营者/家户后果 | **已实现，受控验证** | E1 探针 PASS=74/0：自用维生止于 CONTRACTING；不可再生产→EXITED 后配额释放/TENANCY 份额回 owner/债务还或违约/库存留账/人口不变；0→120 与 B.4 全组件 0 差异 |
| 2 新需求→已有/新预设采用 | **已实现，端到端验证** | E2a：空表基线 0 差异；wool 需求 D+1 订单 10,000、取消后 0。E2b：tick1 出现 2 个 TRIALING unit、day30 净产 34,920,000、Σ份额守恒、1vs8 全组件 0 差异、不可行具名拒绝 |
| 3 生产实践经验 | **未实现（本轮暂缓）** | 无代码、无状态组件；E3 代理写盘前被取消 |
| 4 收入梯度消费 | **未开始** | 尚无 NeedProfile/四量/基本-改善缺口；`effectiveDemand` 仍未接消费反馈链 |
| V 最终验收 | **未开始** | JUnit/变异/4 线程/全年 360+峰值内存/10k 格/场景清单/SpotBugs 全未跑；既有测试未按新 Industry/16 组件形状适配 |

### 已核实的关键读数
- 多 unit：8,940 个 unit / 1,799 个产业，同格 farm/weave 主+4 户、craft 4–5 条；tick0 Σ 份额/配额与旧世界逐值相等。
- 性能：0→120 tick 8 线程 **36.0 s**（索引化前 752.7 s），VmHWM ≈1.6 GiB；带进入场景 37.6 s。
- 确定性：多个切片 1 vs 8 线程完整领域状态 0 差异；4 线程未跑。
- 守恒：各片均核过人口/商品/货币/资产份额/劳动配额/债务；E1/E2 处置与拆分只改归属/分布。
- 旧档：R3B.1 及之后形状可读并迁移；c1 孤儿债在真实 tick120 旧状态上 62→0、principal 10,495,732 不变。

### 主要阻断 / 缺口（按优先级）
1. **E3 经验未做**：`experiences` 组件、实劳实产计提、modeKey 全局化、地区有界系数、阶层×模式读口全部缺失。
2. **E4 梯度消费未做**：`needProfiles`、人均四量（基本/期望/有效/实际）、可支配资源、基本-改善缺口分离、`effectiveDemand` 进订单与消费反馈链全部缺失；因此“消费变化→新需求→进入/竞争/退出”完整链未复验。
3. **V 未开始**：现有测试仍按旧 `Industry`/旧组件数构造（`-DskipTests compile` 不编译 test），JUnit/往返/变异/SpotBugs 均未适配；1/4/8、全年 360、峰值内存正式协议、10k 格未做。
4. **旧档兼容边界**：S1 之前的 changeset（如 M2 `store-m2` 的 `useRights` 组件）在核心 `Timeline.readChangeSet` 严格绑定下仍不可读（B.2 前后同样失败，非本阶段回归）；
   如需支持，需与 `Industry` 兼容位同款的 `useRights→assetShares` record 级兼容层（可能很大，R3 决策单允许 S1 前归档不可开）。
5. **显式命令缺口**：`economy.CloseProductionUnit` 未实现（退出由状态机/受控探针触发）；`ReclassifyHousehold` 有意不做（B.4 纯派生）。
6. **E2 边界**：`ONE_OFF` 需求无跨轮剩余递减；candidate 只存当前版本；`EntryOutcomeFeed` 进程内瞬态、重启即失；`TRIAL_ABORTED` 完整回滚未做；
   `CommandTargets` 未声明（GM `simos.command.submit` 可用，directive 内 fail-closed）。
7. **自然长期观察不足**：0→120 只有一个关账周期，未观察到自然退出；退出/采用路径主要靠受控探针与命令注入验证；全年行为待 V。

### 恢复建议（若继续）
1. 先补 E4（用户四目标中缺口最大的一项，且独立于 E3）；E3 可在 E4 之后补，或与 E4 同批（经验 bonus 只影响预设内效率）。
2. E3 恢复时直接按 `docs/superpowers/plans/2026-09-29-economy-evolution-r4-plan.md` §E3 执行：新 `experiences` 组件挂稳定 HouseholdId×modeKey、
   收获日按实际劳动/实际产出计提、纯收租 0、有界渐近、阶层视图只读聚合、旧档缺键空表。
3. 最后统一 V：测试适配、1/4/8、全年 360+峰值内存、10k 格、场景清单、SpotBugs；再决定是否补 `CloseProductionUnit` 与 S1 前旧档兼容。
