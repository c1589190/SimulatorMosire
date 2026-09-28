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
