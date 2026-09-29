# R4-E2b 报告 —— 候选预设 → TRIALING 实际采用（进入算法收尾）

> 切片：`E2b`。起点：HEAD `5d3435d6`（E2a 已提交）。工作树起点含上一代理留下的**可编译部分实现**：
> 新 `time/EconomyEntrySettlement.java`、`EntryOutcome.java`、`EntryOutcomeFeed.java`，改
> `EconomySettlement.java`、`EconomyStateBuilder.java`、`ApiViews.java`。本片在该实现上**增量修**，未重写、未丢弃原改动。
> 只改 `src/main/java`；未写/改测试；未跑 `test`/`verify`/SpotBugs；未 commit/push。
> 最终 shaded jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `363b4747b7abb01a813690203dec37b1`。
> 固定世界：重新三国 worldgen（`simos.worldgen.initialize` × 德意志第二帝国/奥斯特马克侯国/霍赫兰伯国，
> 430/138/231 经济格、11,830,000 人、rev1–4 @ tick0、8,940 units / 8,940 shares / 44,564 allocations）；
> `/tmp/e2b-fixed0` 是后续所有 A/B 与场景的同一初态副本。证据目录：`/tmp/e2b-evidence/`。

---

## 0. 一句话结论

- 候选预设进入链路已端到端跑通：tick0 的 `SetMarketPrice`+`RegisterCandidate`+`AddDemand` → **tick1 出现 2 个
  `TRIALING` unit**（operator = 真实家户、`modeKey=cand-wool@1`、assets LAND=10,000、owner=ESTATE/operator=家户/
  kind=TENANCY）；buildDays=5、cycleDays=30：day1/5/29 无产出，day30（进入日+29）出现净产 wool 34,920,000 并
  `TRIALING→ACTIVE`。
- 空表基线 A/B：B.3a 世界 0→10 与 0→120 均 **42 组件 0 差异**；旧 B.2 形状 store 0→10 也 **42 组件 0 差异**。
- 守恒：进入前后 `Σ AssetShare` 2,286,937,777 逐值不变（仅拆出 2 条新份额、源份额减量）；货币 142,924,800 不变；
  `Σalloc ≤ available` 无违反（最大差 0）；人口与无进入对照在 tick31 逐值相同（11,854,636）。
- 确定性：同初态同命令 1 vs 8 线程终态 **42/42 组件 0 差异**。
- 性能：空表 0→120 8 线程 final 36.248 s（HEAD 基线 35.571 s，+1.9%）；带进入场景 0→120 8 线程 37.627 s。
- 缺口已修 4 处（评分量纲/缺价、重排触发语义、同会话 relations 读口、TRIALING→ACTIVE）；剩余边界见 §7/§8。

---

## 1. 原部分实现的状态 / 我补的缺口与修掉的缺陷

### 1.0 原实现已经是可编译的两段式骨架

原实现已经具备：

- `EconomyEntrySettlement.planEntries(...)`：入口建一次派生账本（`EntryContext`，O(units+shares+memberships+allocations)），
  按 (householdId, candidateId/version) 升序评估；对**副本表**（shadow `Tables`）模拟登记，真实状态一字不动；
  产出 `EntryIntent` + `EntryOutcome`；
- `execute(...)`：对每条意向以当前工作副本**重新评估**后写 industry 模板 / unit / relation / condition / 份额拆分 /
  劳动配额；先做全量前置校验、再统一写，失败条目具名拒绝；
- `EntryOutcome`/`EntryOutcomeFeed`：进程内投递读口，不落盘、不进 `EconomyData`/ChangeSet/Codec；
- `EconomySettlement.settleOneDayInto` 的 `0-entry` 段：在现扣投入/劳动再分配**之前**执行，之后重建
  `SettlementIndex`；空表（demands/candidates 任一为空）直接跳过；
- `EconomyStateBuilder.relationsOrBase()`：relations 也变成惰性工作副本；
- `ApiViews.economyHex` 新增 `assetShares[]`、`entryOutcomes[]` 与具名瞬态原因。

### 1.1 ★ 缺陷 1：评分三项量纲不一致 + 缺价按 0 计（`EconomyEntrySettlement.assess`）

- **为什么是缺陷**：原实现
  `revenue = outputMilli × price`，但 `inputCost = Σ inputMilli × price ÷ 1,000`，
  `subsistence = labor × 给养 × grainPrice ÷ 1,000,000`。三项差了 10³～10⁶ 倍 ⇒ `score<0` 几乎不可达，
  “不可行”会被静默放行；且注释明写“投入缺价按 0 计”——这与任务书 E2b 行为 ⑥“缺价 ⇒ 不可行”相反，
  是把无价成本当成免费，不是保守。
- **修法**：三项统一到同一刻度（毫商品 × 毫银/商品，只判符号）：
  `revenue = 主产出单位量 × 产出价`；`inputCost = Σ 正投入单位量 × 各自价`；
  `subsistence = laborPerUnit × RegimeRelations.subsistenceMilliPerLabor() × 粮价`。
  主产出、任一正投入、给养要用的粮**缺价一律** `PRICE_MISSING:[...]` 拒绝；`score<0` 仍 `SCORE_NEGATIVE`。
- **证据**：不可行场景 `score-negative` 现在给出
  `SCORE_NEGATIVE:revenue=10,inputCost=0,subsistence=144000`（`/tmp/e2b-evidence/infeasible-results-final.json`）；
  成功候选 score = `2000×10 − 1000×1 − 10×144×1 = 17,560 > 0`。
- **量纲依据**：`RegimeRelations.subsistenceMilliPerLabor()` 是“毫粮 / 千分劳动”（144），
  乘 `laborPerUnit`（千分劳动）得毫粮；毫粮 × 粮价与“毫商品 × 价”同刻度。

### 1.2 ★ 缺陷 2：劳动重排触发条件被全局收紧（`EconomySettlement.reallocateLaborPartitioned`）

- **为什么是缺陷**：上一代理把触发条件从旧口径“本格**任一** unit 在周期第一天就重排”
  改成“本格**全部** unit 都在周期第一天”。在 `cycleDays` 不一致的旧世界里，这会改变**空 `enteredToday`**
  时的既有重排结果，违反任务书“空集时逐值等价旧路径”。而对 E2b 新 unit 来说，更精确的需求是：
  **不让候选 unit 自己的周期边界触发整格重排**（否则它每 30 天翻篇一次，会把同格 120 天旧 unit 从半周期里
  重排一次，违反“旧经营者延续”）。
- **修法**：
  1. 触发判定恢复旧口径“任一 unit 在第一天”，但限定在**旧档/主副 unit**上；
  2. 新增 `isPresetOrigin(ProductionUnit) = !modeKey.equals(industry.value())`：候选 unit 的
     `modeKey = candidateId@version`，旧档/播种/副 unit 的 `modeKey == industry.id()`（`ProductionUnit` 类注的不变式）；
  3. `cycleStartExempt`（今天刚进入的 unit）仍整条排除在触发与集合之外；候选 unit 在本格因**旧 unit**
     触发重排时仍作为普通 unit 参与（need 照算）。
- **空集等价**：`enteredToday` 为空且世界里没有候选 unit 时，`isPresetOrigin` 全 false、触发集合与顺序逐字等于旧路径。
- **证据**：0→10/0→120 空表 A/B 42 组件 0 差异（`/tmp/e2b-evidence/ab10-diff.txt`、`perf120-diff.txt`）；
  进入场景 day30→31 同格 0 条旧配额行变化（`/tmp/e2b-evidence/day31-reallocation-final.json`）。

### 1.3 ★ 缺陷 3：同一次会话里人口回写漏读新 unit 的 relation（`EconomySettlement.applyPopulationChangeInto`）

- **为什么是缺陷**：`PopulationEconomyTimeParticipant` 在同一个 `EconomyDayStepper` 会话里先 `step(day)`、
  再做月度 `applyPopulationChange(...)`。`applyPopulationChangeInto` 原来用 `base.relations()` 建
  `SettlementIndex`；若本次会话刚由进入执行插入了新 unit/relation，这份索引会**漏掉新 unit 的 relation**。
  本场景里 `EconomicHouseholdResolver` 的①（operator 是家户 actor）会兜住，所以没当场炸，但这是潜在漂移。
- **修法**：改用 `session.sheet().relationsOrBase()`。
- **证据**：带进入场景 0→120（day30/60/90/120 各一次人口回写）无异常；终态 units/relations/conditions = 8,942/8,942/8,942，
  `max(Σalloc−available)=0`。

### 1.4 ★ 缺陷 4：首次收获不做 `TRIALING→ACTIVE`（`OperatorSettlement.advance`）

- **为什么是缺陷**：计划 §E2 明写“第一次实际收获时 TRIALING→ACTIVE”；原实现只有
  `ACTIVE, TRIALING` 的负证据分支（滞销/投入不足），正常关账后永远停在 `TRIALING`。
- **修法**：在状态机 `switch` 之后、写 `OperatorCondition` 之前，若 status 仍为 `TRIALING` 则置
  `ACTIVE`、reason `trial_complete:firstHarvest`。负证据分支优先，不被吞掉。
- **影响面**：`TRIALING` 只由 E2b 新建 unit 持有，旧档/E1 世界没有它，既有状态机语义不受影响。
- **证据**：day30 两个新 unit 的 `condition.status=active`、`lastReason=trial_complete:firstHarvest`
  （`/tmp/e2b-evidence/success-readings-final8.json` / `success-summary-final.json`）。

### 1.5 审到但不需要改的点（审计结论）

| 审计项 | 结论 |
|---|---|
| `EntryOutcomeFeed` 静态进程内状态 | 只被 `ApiViews` 读；不在 `EconomyData`/ChangeSet/Codec，不参加守恒；`publish(mapId,day,...)` 按 (mapId,day) 覆盖、每 map 只留 8 天；`clear(mapId)` 已可用（生产路径不需要，重启/重放不会污染状态）。空表 A/B 42 组件 0 差异实测了“不进状态”。重启后读口给具名瞬态原因（`/tmp/e2b-evidence/readport-restart-final.json`）。 |
| `relations`/`settlementIndex` 重建覆盖 | `0-entry` 之后重建 `SettlementIndex`；`drawCycleInputsPartitioned`、`harvestPartitioned`、`OperatorSettlement.advance`、`HouseholdClassRule.Index.of`、`MarketRound` 都读刷新后的 `relations` 局部量或 `session.sheet().relationsOrBase()`；全仓只剩 `EconomyStateBuilder`/ChangeSet/SeedHandler 的 `base.relations()`，都不是同日结算读取点。`session.sheet().relations()` 的物化发生在 `execute` 传参处、早于重建与最终 `build()`。 |
| `enteredToday` 语义 | 只包含“执行段真的新建成功的 unit”；空集时（且无候选 unit）逐值等价旧路径；今天进入的 unit 不触发/不参与当天重排，其配额由进入显式发放。 |
| `ApiViews.assetShares[]` | 是 `data.assetShares().values()` 按 `IndustryHexKeys.hexKeyOf` 过滤 + 按 id 排序的**原始投影**，不重算、不嵌套循环（8,940 条一次遍历 + 格内小排序）；与 `economyOwnership` 读同一份 `EconomyData`（后者是账户侧视图，不暴露份额）；格内 LAND 份额合计与 `landMilliMu`/unit.assets 同源。 |
| Industry 投入表达 | `templateFor` 把 `candidate.inputPerUnit` 整体挂在 `requiredAssets` 第一个正需求 key 的 `cycleInputPerUnit` 内层；已核全仓 main 无任何路径按 `AssetKind` 分支直读 `cycleInputPerUnit`——现扣/收获/劳动 need/成本估计全部走 `Industry.inputPerUnit()` 或 `recipe()`（`grep -rn "cycleInputPerUnit()"` 只剩 `EconomyData` 归一化与 `Industry` 自身）。故挂法不漏扣/多扣；没有出现必须点名修正的 AssetKind 分支直读。 |
| `EconomyStateBuilder` | `relationsOrBase()` 未物化时不拷贝；`build()` 与 `snapshot()` 都走它，空表基线不产生额外拷贝。 |

---

## 2. 进入算法最终口径

### 2.1 意向（`planEntries`，只读）

- 触发：`base.demands()` 与 `base.candidates()` 都非空时，每个日结算日评估；空表直接返回空 Plan（连派生账本都不建）。
- 顺序：householdId 升序 × candidate(id,version) 升序；评估对建一次 `EntryContext`（unit↔operator、unit↔批次/家户、
  shares↔owner/operator、Σalloc 家户/批次），`plan` 段改的是 6 张表的浅拷贝 shadow，真实状态不动。
- 需求信号：本户所在格的 HEX 需求或命中本户的 HOUSEHOLD 需求、商品 = 候选主产出、`effectiveOn(day)`；
  剩余窗口 `expiresDay-day ≥ buildDays+cycleDays`（`expiresDay<0` 永久）→ OK；有命中但窗口太短 → `DEMAND_TOO_SHORT`；
  完全没命中 → 跳过（不产出无意义的 NO_DEMAND 行）。
- 可行性（全部只用可观察量）：
  ① **幂等**：本户不得已有同 `modeKey` 或同主产出的 ACTIVE/TRIALING unit（condition 缺失按 ACTIVE）；unit id 已存在也拒；
  ② **资产**：`requiredAssets` 为空/全 0 ⇒ `NO_ASSET_REQUIREMENT`；`acceptedRightKinds` 为空 ⇒ `NO_RIGHT_KIND`；
  自身（候选产业 id 下、accepted kind、owner/operator=本户）份额 + `assetSource` 名下**空闲**（operator==owner）、同格、
  requiredAssets 点名的份额，逐资产 `⌊available/required⌋` 取 min；不足 ⇒ `ASSET_SHORT:...`；
  ③ **劳动**：`laborPerUnit×trialScale` 同时不超本户折算后余量与所选批次余量（批次按成员份额/既有配额批次的 id 升序、
  余量大者优先），新配额后 Σalloc ≤ laborSupply；不足 ⇒ `LABOR_SHORT:...` / `LABOR_NO_SUPPLY`；
  ④ **投入/生计**：本户库存覆盖 `inputPerUnit×trialScale×(buildDays+cycleDays)`；粮还要另留
  `cumulativeRationMilli(pop, day+horizon) − cumulativeRationMilli(pop, day−1)`（= horizon+1 天）的基本口粮；
  不足 ⇒ `INPUT_SHORT:...` / `SUBSISTENCE_SHORT:...`；不借、不造；
  ⑤ **规模**：`trialScale = min(MAX_TRIAL_SCALE_PER_ENTRY=10, 资产 cap, 劳动 cap, 投入 cap)`，`<1` ⇒ `SCALE_CAP_BELOW_ONE`；
  ⑥ **价格/收益**：本格必须有市场；主产出、任一正投入、给养粮缺价 ⇒ `PRICE_MISSING:[...]`；评分三项同刻度，
  `score<0` ⇒ `SCORE_NEGATIVE:...`。
- 产出：可行 → `EntryIntent(household, candidate, industryId=<candidateId>@<q>_<r>, trialScale,
  expectedDay=day+cycleDays−1, grants（只含需从 assetSource 拆的腿）, one-cycle inputPlan, horizonNeed, laborLot,
  laborMilli, laborPeriod)`；不可行 → `EntryOutcome(accepted=false, reason=...)`。

### 2.2 执行（`execute`，写工作副本）

- 对每条意向在当前工作副本上**重新评估**（重跑同一 `assess`）；通过后按固定序写：
  1. Industry 模板（`putIfAbsent`，id=`candidateId@q_r`；`capacityPerUnit=requiredAssets` 的正数项、
     `cycleDays/outputPerUnit/laborPerUnit/regime` 照候选；投入表挂法见 §1.5；slots 用既有四阶层模板、
     allocation 用中性 Split(500,500)——本片 relation 空规则 ⇒ 全归 residualOwner，两栏不参与结算）；
  2. `ProductionUnit(id=unit-<industry>-<householdActor>, operator=家户, modeKey=candidate.modeKey(),
     progressDays=0, cycleLaborMilli=0, cycleInputUsedMilli={})`；
  3. `ProductionRelation(operator=家户, rules=[], residualOwner=家户, inputSupplier=ToHousehold(家户),
     laborSource=candidate.laborSource)`；
  4. `OperatorCondition(TRIALING, reason="entry:"+modeKey, 其余读数 0)`；
  5. 资产拆分：源份额按 id 升序、每资产取到 need 为止；源份额 quantity 减 take（减到 0 删行），
     新份额 `AssetShare.idOf(候选产业, asset, 源 owner, 家户, grantKind, 最小空闲 sequence)`——**owner 不变、
     operator=家户、kind 按 acceptedRightKinds（TENANCY→COMMUNAL→OWNED 优先）、Σ 份额不变**；
  6. 新 `LaborAllocation`（id=`LaborAllocation.idOf(unit, lot, household)`，actor=家户 actor，activity=unit id，
     laborMilli=trialScale×laborPerUnit，period=该批次 supply 的 period）。
- 失败语义：所有可失败依赖（源份额仍在且够、unit id 空闲、allocation id 空闲、批次 room 够）在**任何写之前**全量校验；
  失败 ⇒ 当条具名拒绝、一个字不改。没有半建、没有再回滚的对象。

### 2.3 触发日 / 当天进入本周期

- 触发日 = 表非空时每个日结算日都评估；在 `settleOneDayInto` 的**现扣投入与劳动再分配之前**执行。
  新 unit 当天 `progressDays=0`，因此当天 `drawCycleInputs` 会为它扣一次投入，`reallocateLabor` 除外（`enteredToday`）。
- `expectedDay = 进入日 + cycleDays − 1`（进入当天记 progress 第 1 天）；第一次实际收获时 `TRIALING→ACTIVE`。
- `buildDays` 只进“需求剩余窗口”和“投入/口粮覆盖窗口”两个门槛，**不推迟 progress/收获**——这是与计划的差异，见 §7。

---

## 3. 编译 / spotless 原文

```text
$ tools/mvn-lock.sh spotless:check
[INFO] Spotless.Java is keeping 255 files clean - 0 needs changes to be clean, 0 were already clean, 255 were skipped because caching determined they were already clean
...
[INFO] EconomySimos ....................................... SUCCESS [  0.523 s]
[INFO] SimosApp ........................................... SUCCESS [  0.009 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  0.936 s
（exit 0，全文 /tmp/e2b-evidence/spotless-check.log）

$ tools/mvn-lock.sh -DskipTests compile
...
[INFO] You have 0 Checkstyle violations.
...
[INFO] EconomySimos ....................................... SUCCESS [  2.323 s]
[INFO] SimosApp ........................................... SUCCESS [  0.954 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  4.210 s
（exit 0，全文 /tmp/e2b-evidence/compile.log）

$ tools/mvn-lock.sh -q -Dmaven.test.skip=true package
[frontend-gate] OK tests=297 pass=297 fail=0
（exit 0，jar md5 363b4747b7abb01a813690203dec37b1）
```

---

## 4. 成功采用端到端证据（固定世界）

世界与命令序列见 `/tmp/e2b-evidence/commands.md`；固定世界 rev1–4 见 `/tmp/e2b-evidence/fixed0-revisions.json`；
tick0 宏观见 `/tmp/e2b-evidence/tick0-totals.json`（8,940 units / 8,940 shares / Σ份额 2,286,937,777 /
44,564 allocations Σ5,586,267,122 / silver 142,924,800）。

### 4.1 场景

hex `(-55,-65)`：`SetMarketPrice wool=10` → `RegisterCandidate cand-wool@1`（output=wool 2000/单位规模、
input=fiber 1000、requiredAssets LAND=1000、labor=10、buildDays=5、cycleDays=30、regime=tenant、
laborSource=TENANT、acceptedRightKinds=[TENANCY]、assetSource=ESTATE:farm@-55_-65）→ `AddDemand`
（HEX RECURRING TOTAL 10,000/周期，永久）。命令落 rev5/6/7；随后 0→1→5→29→30→31 分段推进（rev8–12）。

### 4.2 tick0 → tick1（进入日）

- tick0 命令后读口：`demands` 有该条、`candidates` 有 `modeKey=cand-wool@1`、`entryOutcomes=null` +
  具名瞬态原因、无新 unit。
- tick1 读口：**恰好 2 个 TRIALING unit**：
  `unit-cand-wool@-55_-65-HOUSEHOLD-hh--55_-65-rural-middle_peasant`、
  `unit-cand-wool@-55_-65-HOUSEHOLD-hh--55_-65-urban-middle_peasant`；
  operator 是真实家户、`modeKey=cand-wool@1`、assets `{LAND:10000}`、condition `trialing` /
  reason `entry:cand-wool@1`、`cycleInputUsedMilli={fiber:9220}`（计划 10,000；同格 fiber 池当日被旧 unit
  与试产 unit 同时争用，H6-lite 配给 9,220 ⇒ 实际 scale=⌊9220/1000⌋=9）、`cycleLaborMilli=100`。
- `entryOutcomes.day=1`，8 行：2 条 `entered:trialScale=10,expectedDay=30`；其余 6 条具名拒绝：
  4×`LABOR_SHORT`（householdRoom 负/不足）、2×`SUBSISTENCE_SHORT`（grainStock < 18.87M / 10.48M）。
- 份额：源 `share-farm@-55_-65-LAND-ESTATE-...-OWNED-0` 2,170,000 → 2,150,000（−20,000）；
  新增 2 条 `share-cand-wool@-55_-65-LAND-ESTATE-farm@-55_-65-HOUSEHOLD-<户>-TENANCY-0`，各 10,000，
  owner=ESTATE、operator=家户、kind=TENANCY；该格 15 → 17 行，Σ quantity 3,100,853 逐值不变。
- 劳动：新增 2 条 `alloc-unit-cand-wool...-FEMALE:1-...`，各 100；rev4→rev8 世界 Σalloc 5,586,267,122 →
  5,586,267,322（+200）；`max(Σalloc−available)=0`。
- 证据：`/tmp/e2b-evidence/success-readings-final8.json`、`success-summary-final.json`、`success-shares-final.json`、
  `/tmp/e2b-evidence/day1-allocation-vs-empty-final.json`。

### 4.3 buildDays / cycleDays 产出

| 时点 | progress | cycleInputUsed | condition | 本格 wool |
|---|---:|---|---:|---:|
| tick1 | 1 | fiber 9220 | trialing | 0 |
| tick5（buildDays） | 5 | fiber 9220 | trialing | 0 |
| tick29 | 29 | fiber 9220 | trialing | 0 |
| tick30（进入日+29） | 0 | `{}`（已关账清零） | **active** / `trial_complete:firstHarvest` | **34,920,000** |
| tick31 | 1 | `{}`（新周期缺 fiber，未开工） | active | 34,920,000 |

- 毛产 = 2 unit × scale 9 × outputPerUnit 2000 × 1000 = 36,000,000 毫；扣 3% 饲料+折旧（FEED 10‰ + DEPRECIATION 20‰）
  ⇒ 净 34,920,000 毫，逐值出现在该格 actor 侧 goods（`/tmp/e2b-evidence/success-summary-final.json`）。
- tick31 新周期未开工是因为全格 fiber 库存已在 day1 被旧 unit+新 unit 分光（见 §4.5），不是进入算法缺陷。

### 4.4 守恒（进入 rev4→rev8，及 tick31）

| 量 | tick0 | tick1（进入后） | tick31 | 无进入对照 tick31 |
|---|---:|---:|---:|---:|
| 人口 | 11,830,000 | 11,830,000 | 11,854,636 | 11,854,636 |
| AssetShare 行 / Σ | 8,940 / 2,286,937,777 | **8,942 / 2,286,937,777** | **8,942 / 2,286,937,777** | 8,940 / 2,286,937,777 |
| 逐资产 LAND / TOOL / WORKSHOP | 2,286,386,400 / 525,036 / 26,341 | 逐值相同 | 逐值相同 | 同 |
| allocations 行 / Σ | 44,564 / 5,586,267,122 | 33,795 / 5,586,267,322 | 33,795 / 5,584,732,513 | 33,793 / 5,584,732,314 |
| max(Σalloc−available) | 0 | 0 | 0 | 0 |
| silver | 142,924,800 | 142,924,800 | 142,924,800 | 142,924,800 |
| debts | 0 | 0 | 0 | 0 |
| units | 8,940 | 8,942 | 8,942 | 8,940 |

> 说明：`Σ AssetShare(industry,asset)` 的**逐 (industry,asset)** 分布由设计改变（farm LAND −20,000、
> cand-wool LAND +20,000），**逐资产全局总量/行内 owner/quantity 总和守恒**；“拆分只改 operator/quantity 分布”
> 在 owner 维度成立（owner 不变），industry/operator/kind 按进入模板改变。证据
> `/tmp/e2b-evidence/success-conservation-final.json`。
> 商品不是守恒量（生产/消费/成交会改变它）：rev4→rev8 该格 goods 的 grain/fiber 下降来自日耗与投入、
> tick30 的 wool +34,920,000 来自新 unit 净产入账；货币与 AssetShare 是守恒量，逐值不动。债务本片为 0。

### 4.5 旧经营者延续（同格对照）

- 用同一初态另跑一个**无进入**世界到 tick1，和进入世界 tick1 比较同格旧 unit：
  - progress 全部相同；condition.status 全部 ACTIVE；进入动作没有直接改写旧 unit 的任何字段；
  - 配额：57 条旧配额中**只有 2 条相差 ±1,000**：
    `unit-farm@...ESTATE` 的 MALE:1/poor 1,343,545→1,344,545，
    `unit-weave@...HOUSEHOLD-weave@...` 的 MALE:1/poor 60,612→59,612；
  - 原因：同日同格 fiber 池当天被**全部耗尽**（无进入世界旧 unit 取走 27,900,000 = 该格家户 fiber 总库存；
    进入世界旧 unit 取 27,881,560、新 unit 取 18,440，二者严格零和）。这是 H6-lite“同格按需求配给 + 农业最后雇主”
    的既有语义，不是进入算法对旧 unit 的直写；旧 unit 的 need 因此少 1,000，最后雇主把这 1,000 挪回农田主 unit。
  - 市场成交证据的自然差异：tick31 两个佃农 farm unit 的 `cycleOfferedQty/cycleFilledQty/cycleUnfilledQty`
    与无进入对照相差约 0.1%–0.7%（例如 `hh--55_-65-rural-landlord` 的 filled 10,337,064→10,345,065，
    `...rich_peasant` 的 filled 8,658,968→8,646,376）；这是“投入被配给 → 产出/供给变化 → 成交读数”的链式结果，
    不是进入段写旧 unit 的账。status/连续计数保持 ACTIVE/0。
  - 新 unit 自己的周期边界不再触发整格重排：day30→31 同格 **0 条配额行变化**（`day31-reallocation-final.json`）。
- 证据：`/tmp/e2b-evidence/day1-allocation-vs-empty-final.json`、`day1-fiber-pool-final.json`。

---

## 5. 不可行 / 幂等证据

每个场景独立 store、独立服务：`SetMarketPrice wool=10` →（可选）`RegisterCandidate` → `AddDemand` → 0→1。
结果（全文 `/tmp/e2b-evidence/infeasible-results-final.json`）：

| 场景 | unit 数 | 需求仍在 | 具名 entryOutcomes（rural-middle 户样例） |
|---|---:|---:|---|
| 不注册 candidate | 0 | 是 | 无候选可评估 ⇒ `entryOutcomes` 空 items；demand 保持 |
| requiredAssets LAND=100,000,000 | 0 | 是 | `ASSET_SHORT:required={LAND=100000000},own={},source={LAND=2170000}` |
| laborPerUnit=100,000,000 | 0 | 是 | `LABOR_SHORT:householdRoom=259908,need=100000000` |
| inputPerUnit fiber=100,000,000 | 0 | 是 | `INPUT_SHORT:commodity=fiber,stock=6510000,needPerScalePerWindow=3500000000` |
| requiredAssets={} | 0 | 是 | `NO_ASSET_REQUIREMENT` |
| acceptedRightKinds=[] | 0 | 是 | `NO_RIGHT_KIND` |
| outputPerUnit wool=1、labor=1000 | 0 | 是 | `SCORE_NEGATIVE:revenue=10,inputCost=0,subsistence=144000` |
| demand expiresDay=10（horizon 35） | 0 | 是 | `DEMAND_TOO_SHORT` |
| inputPerUnit wood=1000（wood 无价） | 0 | 是 | 先被 `INPUT_SHORT:commodity=wood,stock=0,...` 拦下（本世界 wood 无库存；见 §7.4） |

**幂等**：成功世界 day5/29/30/31 读口 `candUnits` 恒为 2；`entryOutcomes` 每天给两条
`ALREADY_ENTERED`，没有第三个 unit（`/tmp/e2b-evidence/success-idempotency-final.json`）。
同一 (household,candidate) 若已有同 modeKey/同主产出 ACTIVE/TRIALING，`assess` 幂等拒绝；
旧 unit 已 EXITED 时即便 condition 不再拦，也会被 `UNIT_ID_EXISTS` 拦（仍不产生第二个 unit）。

---

## 6. 空表 A/B、1 vs 8、0→120 性能、回归

### 6.1 空表 A/B（42 组件全状态逐组件）

- 基线：`git worktree add --detach /tmp/e2b-base 5d3435d6` + `tools/mvn-lock.sh -q -Dmaven.test.skip=true package`
  ⇒ md5 `c804cda68044cbeb496a1d0e080df0b6`。
- 新：md5 `363b4747b7abb01a813690203dec37b1`。
- 0→10（8 线程，同一 tick0 副本）：
  baseline 5.311 s / 5.260 s（两次），new **最终 5.460 s**（5.749 s 是同为空表的早一轮重复；两次都 42 组件 0 差异）；
  **42/42 组件 0 差异**（`/tmp/e2b-evidence/ab10-diff.txt`）。
- 0→120（8 线程，同一 tick0 副本）：
  baseline 35.571 s（VmHWM 1,587,084 kB），new 36.248 s（VmHWM 1,567,764 kB）；
  **42/42 组件 0 差异**；与 E1/E2a 关账读数一致（人口 11,622,725、Σ份额 2,286,937,777、
  silver 142,924,800、alloc Σ5,559,098,523）。
  （`/tmp/e2b-evidence/perf120-diff.txt`、`perf120-final-state.json`、`performance.md`）
- 旧 B.2 形状 store（`/tmp/e2b-fixture`，1799 industries 载入后 1 unit/产业）0→10：
  baseline 6.65 s / new 4.74 s，**42/42 组件 0 差异**（`/tmp/e2b-evidence/legacy-ab10-diff.txt`）。

### 6.2 确定性 1 vs 8 线程

同初态、同三条 GM 命令、同 0→1→5→29→30→31 分段推进：1 线程墙钟 2.594/3.866/6.745/5.268/4.825 s，
8 线程同分段同轮；终态 **42/42 组件 0 差异**（`/tmp/e2b-evidence/success-1vs8-diff.txt`、
`success-final1-state.json`、`success-final8-state.json`）。

### 6.3 性能

| 场景 | 墙钟 | VmHWM |
|---|---:|---:|
| 空表 0→120 8 线程 HEAD | 35.571 s | 1,587,084 kB |
| 空表 0→120 8 线程 final | **36.248 s（+1.9%）** | 1,567,764 kB |
| 进入场景 0→120 8 线程 final | 37.627 s | 1,663,356 kB |

进入评估在表非空时每天建一次 `EntryContext` + 影子表（O(state)），不是 O(units×allocations)；
单 candidate/单 demand 场景 0→120 仍 ≤45 s。未观察到性能红线级别的回退。

### 6.4 回归

- 固定 B.3a 世界 0→10 / 0→120 全状态 A/B 均 0 差异；进入场景 0→120 无异常、无退出/处置异常、
  units/relations/conditions = 8,942/8,942/8,942、`max(Σalloc−available)=0`、货币/份额守恒。
- 旧 B.2 store 0→10 A/B 0 差异（旧档 reshape/迁移路径仍可读）。
- **Probe2/Probe3/Probe5、E1 受控探针的探针源码/class/jar 在本轮 `/tmp` 中已不存在**（上一会话产物被清；
  `/tmp` 下无 `world-rev*.json`/`economy-rev*.json`），按任务书“或至少 0→120 退出/处置无异常”的兜底，
  用上述全状态 A/B + 进入场景 0→120 无异常替代；这不是“Probe 已跑”。

---

## 7. 与计划不同处 / 剩余阻断

1. **TRIAL_ABORTED 完整回滚未做**：`execute` 用“先全量校验、再统一写”避免半建；但没有“unit 建好后因后续
   条件失败而整条撤销/回滚”的路径。试产 unit 的后续失败走 E1 状态机（CONTRACTING/SUSPENDED/EXITED）。
2. **buildDays 不推迟周期**：它只进需求窗口与投入/口粮覆盖窗口；progress 从进入当天起逐日 +1，
   第一次收获在进入日 + cycleDays − 1。任务书要求“buildDays 前无产出、cycleDays 前无产出、之后有产出”在
   buildDays ≤ cycleDays 时逐条满足（本场景 day1/5/29 无产出、day30 产出）；但没有独立建设期。
3. **家户自有份额路径只认“候选产业 id 名下”的份额**：`ownAssets()` 要求
   `share.industry == candidateId@q_r`，因此 tick0 世界里没有户能在进入前命中该档；所有成功进入都走
   `assetSource` 名下空闲份额拆分。计划里“家户已有 OWNED/TENANCY 份额 ≥ requiredAssets”的**跨产业再登记**
   （把自己正在经营的 farm 份额改挂到候选产业下）未实现。剩余阻断/后续项。
4. **PRICE_MISSING 在固定世界 GM 路径不可达**：799 个市场都恰好有 grain/cloth/fiber/tool/iron 五个价，
   SetMarketPrice 只能加价不能删价；没有任何家户持有“有库存但无价”的商品（wood 无库存，先被 INPUT_SHORT 拦）。
   代码路径已实现并审查，但没有端到端触发证据；如实记。
5. **`UNMET_NO_FEASIBLE_PRESET` 的需求级标记未合成**：不注册 candidate ⇒ 读口 `entryOutcomes` 为空 items、
   demand 保持未满足；candidate 已注册但全不可行时，读口给逐 (household,candidate) 具名拒绝，不额外发需求级标记。
6. **EntryOutcomeFeed 进程内瞬态**：重启即失（读口给具名原因）；`clear(mapId)` 只有测试夹具需要，
   生产路径不调用；键只含 mapId，不含 branch（与 `ClassTransitionFeed` 同款边界）。
7. **候选 unit 不触发整格重排**：`isPresetOrigin` 让候选 unit 自己的周期边界不触发本格重排（避免扰动旧 unit），
   它自己的配额保持进入时显式发放的量；若它在旧 unit 的周期边界被判 need=0 而释放了配额，要等下一个
   **旧 unit** 周期边界才可能重新拿到劳动（不是它自己的 30 天边界）。这是既有“整格重排”机制 + 本片保护旧经营者的取舍，
   在 cycleDays 不整除/错位时可能出现，已在最终 0→120 场景中实测无异常。
8. **候选 origin 判定**：`isPresetOrigin = !unit.modeKey().equals(unit.industry().value())`，依赖
   “旧档/播种/副 unit 的 modeKey == industry.id()、候选 unit 的 modeKey = candidateId@version” 的既有不变式；
   若未来有第三方写入口制造 modeKey≠industry 的**非候选** unit，会被误判，需同步收口。
9. **`MAX_TRIAL_SCALE_PER_ENTRY=10` 是硬编码配置**（V 阶段接 GM 参数目录后迁出），`ONE_OFF` 不跨轮递减
   （继承 E2a 边界）。
10. **进入日会把新 unit 计入 `newCycleUnits`**：按 H0 既有“家户供给的任一 unit 翻篇 ⇒ 该户 FlowRow 整行重记”的口径，
    新 unit 当天 progress=0 会让它关联家户的流水在新周期起点清零。固定世界/本片验收都在 day1 进入（所有旧 unit
    也恰在周期第一天），因此与基线无差异；若 GM 在旧 unit 周期中途注入需求、进入发生在 tick>0，该户本周期已累计
    流水会按既有口径清零 —— 这是 H0 的语义，不是进入算法新加的写者，但读场景曲线时要记得。

---

## 8. 我没做 / 没验证的

- 未写/改任何测试源码；未跑 `test`/`verify`/SpotBugs（按 AGENTS §三.0 与本片任务书）；未 commit/push。
- **Probe2/Probe3/Probe5、E1 受控退出探针没有跑**：源码/class/jar 在本轮 `/tmp` 已不存在，无法复现
  `OK`/`PASS=60/0`/`PASS=11/0`/74 断言；用固定世界 0→10/0→120 全状态 A/B、旧 B.2 store A/B 和进入场景
  0→120 无异常替代。不是“已验证 Probe 仍绿”。
- 未端到端触发 `PRICE_MISSING`（见 §7.4）；未验证家户自有份额跨产业再登记路径（见 §7.3）。
- 未验证分支/fork 下 EntryOutcomeFeed 的隔离（只键 mapId）；未验证 360/600 天、真实 c1 旧档、GOV 租赋。
- 未做正式 JFR/内存协议，VmHWM/VmRSS 是 `/proc/<pid>/status` 过程读数；未做 1/4/8 全矩阵（本片做了 1 vs 8）。
- 未做 E3 经验、E4 梯度消费；未改价格/粮布保留/`SECONDARY_PER_MILLE`/租率/既有 `StressPolicy` 阈值/
  E1 退出处置语义。
- 未跑“GM 在 tick>0 且旧 unit 处于周期中途时注入进入”的对照场景；固定世界验收都在 day1（旧 unit 也恰在周期第一天）进入，
  §7.10 的 FlowRow 清零边界未用实测数值验证。
- 读口 `assetShares[]` 的性能只按“一次 O(#shares) 遍历 + 格内排序”审查，未做 8,940 份额以上的读口压测。

---

## 9. 复现清单（证据文件）

| 文件 | 内容 |
|---|---|
| `/tmp/e2b-evidence/commands.md` | GM 命令与推进序列原文 |
| `/tmp/e2b-evidence/fixed0-revisions.json` | 固定世界 rev1–4 @ tick0 |
| `/tmp/e2b-evidence/tick0-totals.json` | tick0 宏观总量 |
| `/tmp/e2b-evidence/spotless-check.log`、`compile.log` | 编译 / spotless 原文 |
| `/tmp/e2b-evidence/ab10-diff.txt`、`perf120-diff.txt`、`legacy-ab10-diff.txt` | 三轮 A/B 差异（均 0） |
| `/tmp/e2b-evidence/success-readings-final8.json`、`success-summary-final.json` | 成功采用逐日读口 |
| `/tmp/e2b-evidence/success-shares-final.json` | 进入前后份额逐行 |
| `/tmp/e2b-evidence/success-conservation-final.json` | 守恒读数（rev4/8/12 + 无进入对照） |
| `/tmp/e2b-evidence/success-idempotency-final.json` | 幂等/ALREADY_ENTERED |
| `/tmp/e2b-evidence/day1-allocation-vs-empty-final.json`、`day1-fiber-pool-final.json` | 旧经营者延续与 fiber 池零和 |
| `/tmp/e2b-evidence/day31-reallocation-final.json` | 新 unit 周期边界不扰动旧配额（0 行） |
| `/tmp/e2b-evidence/infeasible-results-final.json` | 不可行/无预设边界 |
| `/tmp/e2b-evidence/readport-restart-final.json` | 读口 assetShares/units/entryOutcomes 与重启瞬态具名原因 |
| `/tmp/e2b-evidence/success-1vs8-diff.txt`、`success-final1-state.json`、`success-final8-state.json` | 1 vs 8 确定性 |
| `/tmp/e2b-evidence/performance.md` | 性能/内存读数汇总 |

（收工状态：无残留 `simos-shaded` 服务、无 Maven 在跑；工作树保留 4 个 modified + 3 个 untracked 生产文件，未 commit。）
