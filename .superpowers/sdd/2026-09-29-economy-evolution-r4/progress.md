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
