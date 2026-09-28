# R4-B.3a-perf 性能修复报告（每步一次派生索引）

> 切片：`B.3a-perf`。基线 HEAD `24108dba`（B.3a 已提交）。本片只改 `src/main/java`，不写/改测试，不跑 `test`/`verify`，不 commit/push。
> 结论：**0→120 从 752.723 s 降到 36.023 s（同一固定 tick0 世界，8 线程）**；固定夹具与 `/tmp/b3a-store` 的 0→10 完整领域状态 A/B 均 **0 差异**；1 vs 8 线程完整领域状态 **0 差异**。

---

## 1. 索引结构 / 构建时机 / 并发安全；改了哪些文件/方法

### 1.1 新增 `SettlementIndex`（`simos-economy/.../time/SettlementIndex.java`，495 行）

一次结算内只读的不可变派生快照，字段全部是 `Collections.unmodifiableMap` / `List.copyOf` / `unmodifiableSet`：

| 字段 | 口径（与旧实现逐值同一来源） |
|---|---|
| `Map<ProductionUnitId, Map<AssetKind, Long>> usableAssetsByUnit` | 从 `AssetShare` 按 `(industry, operator)` 一次聚合；同一 `(industry, operator)` 下的多个 unit 各存同一张表（旧 `usableAssets` 每 unit 现扫，同 scope 同值）。聚合仍用 `Math::addExact`，键序 = 份额首次出现序。 |
| `Map<ProductionUnitId, Long> capacityScaleByUnit` | `min over industry.capacityPerUnit: ⌊usable[k] ÷ capacityPerUnit[k]⌋`；缺项 = 0、空表 = 0，与 `ProductionUnitBook.capacityScaleOf` 同式。 |
| `Map<ProductionUnitId, List<LaborAllocation>> allocationsByUnit` | 全局配额表序按 `activity` 归组。 |
| `Map<ProductionUnitId, List<LaborAllocationId>> allocationIdsByUnit` | 缩放路径用 id 回活表取当前 record，避免缓存旧值。 |
| `Map<PeopleLotId, List<LaborAllocationId>> allocationIdsByGroup` | 人口回写按批次缩配额，不再逐 `LotChange` 扫全量配额。 |
| `Map<ProductionUnitId, List<HouseholdId>> householdsByUnit` | `householdKeysOf` 的精确口径：按 `activity` 收集 `household`、去重、只留真有行、按 `HouseholdId.value()` 升序。 |
| `Map<HouseholdId, Set<ProductionUnitId>> unitsByHousehold` | 只认现存 unit 与现存行；序 = 全局配额表首次出现序（旧 `unitsOfHouseholds` 同）。 |
| `Map<PeopleLotId, List<ProductionUnitId>> unitsByGroup` | 人口回写用；与旧 `unitsOf` 构造同序同过滤。 |
| `Map<String, Long> laborByUnit` | 旧 `laborByUnit(allocations)` 的同值表。 |
| `Map<ActorRef, HouseholdId> householdByActor` | 旧 `householdActorsOf` 同值。 |
| `Map<String, List<ProductionUnitId>> unitsByHex` | 按 unit 表序给每格 unit；`participantsFor` / 无配额家户兜底 / 人口回写兜底共用。 |
| `Map<ProductionUnitId, String> hexByUnit` | 供只读查询。 |
| `Map<String, List<IndustryId>> industriesByHex` | 与 `EconomySettlement.industriesByHexMap` 同值。 |
| `Map<HouseholdId, List<Debt>> debtsByDebtor` | 按债务表序分组；状态机只查本户债务。 |

构建时机与并发：

- 主入口：`EconomySettlement.settleOneDayInto` 在取完当天工作副本后**构建一次** `settlementIndex`（`SettlementIndex.build(...)`），随后传入各阶段；`EconomyDayStepper.step` 逐日调用它。
- `ProductionUnit`/`Industry`/`AssetShare` 在本步内不改写 ⇒ 资产、产能、格、产业视图全程只建一次。
- `LaborAllocation` 会在 `reallocateLaborPartitioned` 里被再分配、`Debt` 会在借粮/偿还/计息里被改写 ⇒ 在改写完成后的阶段边界用 `withLabor(...)` / `withDebts(...)` 只重建对应子视图。这是**阶段边界重建**（每天 ≤3 次），不是逐查询重建；若入口一份用到尾，`householdKeysOf`/债务压力会在再分配后读到旧值，反而改数值语义。
- 并行 worker 只读同一份不可变快照；`MarketSettlement.MarketRound` 持有 `index`，R2 的 worker 不再接触任何可变派生表。索引不进 `EconomyData`/ChangeSet/Codec，也不是 `ThreadLocal`/静态缓存。

### 1.2 改动文件 / 方法

| 文件 | 改动要点 |
|---|---|
| `time/SettlementIndex.java`（新增） | 上述全部索引、`build`/`withLabor`/`withDebts`、只读访问器。 |
| `time/ProductionUnitBook.java` | `usableAssets(unit, index)`、`capacityScaleOf(unit, industry, index)`、`plannedCapacityScaleOf(unit, industry, index, condition)`；私有 `capacityScaleOf(usable, industry)` 是两个重载共用的唯一算式。旧签名保留。 |
| `time/EconomySettlement.java` | 入口构建索引；`householdKeysOf`/`householdKeysOfLot`/无配额家户兜底/`cycleDaysByHousehold`/`reallocateLabor`/`laborNeedOf`/`drawCycleInputs`/`surveyInputDemands`/`supplierAccountsOf`/`inputShortfallOf`/`harvest`/`scaleOf`/`scaleLaborOfUnit`/`scaleLaborOfGroup` 改走索引；`reallocateLabor` 的 `ids.contains` 换成 `Set` O(1) 判定。旧重载保留给旧读口/测试源码。 |
| `time/MarketSettlement.java` | `MarketRound` 加只读 `index`；`participantsFor` 按格查 unit；`necessaryInputsOf`/`operatorLifeRetentionOf`/`inputShortfallNear`/`costEstimateOf`/`sellerSelfUsable` 改索引；`inputShortfallNear` 用已有 `MarketIndexes.participantsByHex`，删掉每次全扫参与者的 `indexesParticipantsOf`。 |
| `time/OperatorSettlement.java` | `advance` 接收索引与按债务人视图；`selfUsableOf`/成本估计改用索引；`accumulateMarketEvidence` 从“逐 unit 扫整份报告”改为“报告一次摊到 unit”（累加顺序逐 unit 不变）。 |
| `time/ProducerCostBook.java` | 新增索引重载；旧重载与索引重载共用私有 `estimate(..., LongSupplier capacityScale)`，产能规模仍延迟到旧实现同一位置取值，异常/取整顺序不变。 |
| `time/HouseholdClassRule.java` | `Index` 新增可选 `SettlementIndex`；`operatedActivities` 的可用资产与租权推定 LAND 改查索引；旧 `Index.of(...)` 签名保留（`settlementIndex == null` ⇒ 旧逐份额路径）。 |
| `time/MarketReadout.java` | `MarketRound` 构造补只读索引（读口每次现建一份 O(unit + 配额 + 份额) 快照，避免逐格全表扫描）。 |

**没有改**：`StressPolicy`、价格、粮布保留、租率、`SECONDARY_PER_MILLE`、人口常量、关系规则、`EconomyData`/`EconomyChangeSet`/`EconomyCodec` 的组件形状、命令、镜像/落盘格式。

---

## 2. 编译 / spotless 原文（最终修订）

```text
$ tools/mvn-lock.sh -q spotless:apply
exit 0
$ tools/mvn-lock.sh -q spotless:check
exit 0
$ tools/mvn-lock.sh -DskipTests compile
[INFO] EconomySimos ....................................... SUCCESS [  0.546 s]
[INFO] SimosApp ........................................... SUCCESS [  0.795 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  3.920 s
$ tools/mvn-lock.sh -Dmaven.test.skip=true package
[INFO] BUILD SUCCESS
[INFO] Total time:  5.760 s
```

最终 shaded jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `2cf86215762fb90807074133249bf281`，23,441,454 B。
基线 jar（`/tmp/b3a-perf-base` worktree 构建）：md5 `fd8faa9286c23cb3d816472844a85294`。

---

## 3. 行为逐值不变（A/B）

### 3.1 比较方法

- 基线：`git worktree add --detach /tmp/b3a-perf-base 24108dba` + 在该 worktree 内 `tools/mvn-lock.sh -Dmaven.test.skip=true package`。
- 新 jar：当前工作树 package。
- 每条链路：同一 store 副本、同一端口组、8 线程（除 1 vs 8 项）、`simos.advance`。
- 比较：Python 重放两份 `simos.db` 的**全部 revision changeset**（脚本 `/tmp/b3a_perf_state.py`），把 actor/economy/social/map/sd/unit 六个模块的每个组件 final map 规范化为 `json.dumps(sort_keys=True)` 后逐组件字符串相等；不同于只比宏观汇总。economy 的 14 个组件（meta/industries/classes/debts/flows/laborSupply/allocations/relations/markets/shipments/memberships/assetShares/operatorConditions/units）、actor 的 meta/actors/accounts 等都在内。
- 0→10 证据目录：
  - `/tmp/b3a-perf-run-final-prefix-{base,new}`（`/tmp/b3a-store` 副本）
  - `/tmp/b3a-perf-run-final-ab-{base,new}`（`/tmp/b3a-store2` rev4 固定夹具副本）

### 3.2 `/tmp/b3a-store` 0→10（任务书指定夹具）

| | 基线 24108dba | 新 jar |
|---|---:|---:|
| 墙钟 | 45.736 s | 7.340 s |
| 完整领域状态差异 | — | **0 / 0 组件** |

### 3.3 固定 tick0 世界（`/tmp/b3a-store2` rev4）0→10

> 见 §6 的夹具说明：`/tmp/b3a-store` 实际是 B.3a 自租修复前的 store1（799 条自环规则），0→120 会在第 120 天关账的租规则处被 `Transfer` 自环守卫拒。固定 tick0 世界用 `/tmp/b3a-store2` 的 rev4（B.3a 最终代码产物）复制并裁到 head4；共 799 经济格、8940 units。

| | 基线 24108dba | 新 jar |
|---|---:|---:|
| 墙钟 | 45.230 s | 6.954 s |
| 完整领域状态差异 | — | **0 / 0 组件** |

---

## 4. 性能读数（0→120）

固定 tick0 夹具（`/tmp/b3a-store2` rev4 副本），8 线程，`simos.advance {from:0,to:120}`：

| 指标 | 基线 | 新 jar |
|---|---:|---:|
| 墙钟 | **752.723 s**（B.3a 报告 §4.2） | **36.023 s**（另一次 34.780 s） |
| 峰值内存 | 未量（B.3a 未测） | `VmHWM = 1,607,752 kB`（约 1.53 GiB）；结束时 `VmRSS = 1,536,780 kB` |
| 提升 | — | **≈ 20.9×** |

推进日志：`/tmp/b3a-perf-run-latest120/advance-0-120.log`（`wall=36.023s, error=None, revision 5`）。
峰值内存：`/tmp/b3a-perf-run-latest120/server.pid` 对应进程 `/proc/<pid>/status` 的 `VmHWM/VmRSS`（已在环境回收前读取；同进程日志见上）。

tick120 宏观守恒核对（新 jar 重放，对照 B.3a 报告 §4.2 逐项）：

| 指标 | B.3a tick120 报告 | 本片新 jar tick120 |
|---|---:|---:|
| 人口 | 11,622,725（+99,472 −306,747） | 11,622,725（+99,472 −306,747） |
| alloc 行 / Σ laborMilli | 33,793 / 5,559,098,523 | 33,793 / 5,559,098,523 |
| AssetShare 总量 / 键 / 行 | 2,286,937,777 / 1,799 / 8,940 | 同值 |
| AssetShare OWNED / TENANCY | 4,995 / 3,945 | 同值 |
| 货币（silver） | 142,924,800 | 同值 |
| debts / principal | 0 / 0 | 同值 |
| goods（grain/fiber/cloth/iron/tool） | 164,011,560,198 / 23,134,775,234 / 12,745,741,800 / 263,410,000 / 52,682,000 | 同值 |
| `max(Σalloc − available)` | −1,260 | −1,260 |
| units / relations / industries / conditions | 8,940 / 8,940 / 1,799 / 8,940 | 同值 |
| 负余额（商品/货币/人口/份额） | 0 | 0 |

> 0→120 的**完整逐值 A/B**没有做：任务书允许“新侧 + B.3a 报告读数核对”。另曾尝试起基线 0→120（`/tmp/b3a-perf-run-120base`），运行约 2 分钟后进程被环境回收/消失，未取到基线 tick120 的完整状态；故未用该次结果。0→10 的完整逐值 A/B 是本片的硬证据。

---

## 5. 并发确定性（1 vs 8）

固定 tick0 夹具，新 jar：

| | 8 线程 | 1 线程 |
|---|---:|---:|
| 0→10 墙钟 | 6.954 s | 7.871 s |
| 完整领域状态差异 | — | **0 / 0 组件** |

证据：`/tmp/b3a-perf-run-latest-fixed8/`、`/tmp/b3a-perf-run-latest-fixed1/`。
（0→120 也很快，但没有做 1 vs 8；按任务书“若 0→120 很快都跑更好”——未做，见 §7。）

---

## 6. 与计划的差异、夹具问题、未消除的热点、剩余阻断

### 6.1 `/tmp/b3a-store` 是自租修复前的夹具（必须如实记）

`/tmp/b3a-store`（mtime 03:05）重放后有 **799 条自环租规则**（`operator == 租规则受方`），例如
`unit-farm@-24_-69-HOUSEHOLD-hh--24_-69-rural-landlord`。B.3a 报告 §1.3 的“自租退化修复”只进了最终代码/`/tmp/b3a-store2`（03:25，self-rent 0）。
因此：

- `/tmp/b3a-store` 上 0→120 会在第 120 天关账的租规则转移处被 `Transfer` 自环守卫拒（本片新 jar 复现到同一错误：`HOUSEHOLD:hh--24_-69-rural-landlord`）；
- 按任务书要求的 `/tmp/b3a-store` 0→10 完整 A/B 已做且 0 差异（0→10 不关账，自环不触发）；
- 0→120 性能/守恒与固定夹具 A/B 使用 `/tmp/b3a-store2` 的 rev4（B.3a 最终世界）复制后裁到 head4 的等价固定 tick0（`/tmp/b3a-perf-latest120-store` 等）。它与 B.3a 报告 0→120 的初态同源，799 格/8940 units 与报告一致。

### 6.2 与计划的差异

1. 没有把可变 `Map` 到处传；用一个不可变 `SettlementIndex` 快照。`LaborAllocation`/`Debt` 在本步内会被改写，故在阶段边界用 `withLabor`/`withDebts` 重建**配额侧/债务侧**子视图；资产/格/产业/家户身份视图只建一次。若入口一份用到尾，再分配后的家户归属与债务压力会读到旧值，数值语义会变。
2. `capacityScaleByUnit` 已按计划预计算并保存；热路径的 `capacityScaleOf(unit, industry, index)` 仍显式接收当天的 `Industry` 模板，用索引的 `usableAssetsByUnit` 代入旧算式——这样模板来源与旧签名完全一致（不把模板复制成第二份状态）。
3. `MarketReadout` 每次读数现建一份索引；它不在日结算热路径，读口调用次数远小于 0→120 的逐 unit 扫描。
4. `accumulateMarketEvidence` 的 O(units × report) 不在任务书最初列表里，但它是 0→120 的另一个单价热点，本片一并改成 O(report × units-per-operator + units)。

### 6.3 未消除/未再压的热点（代码审视；无新 jstack）

0→120 已 36 s，余量很大，按“只做 B.3a-perf”停止条件未继续：

- `EconomySettlement.addLabor → templateAllocationOf`：新发配额时仍扫全量配额找同 unit 模板（只在周期首日/再分配时）。
- `EconomySettlement.reallocateLabor`：每个 hex 仍逐 `localAllocations` 求 `allocated`（已消除 `ids.contains` 的线性判定；份额表格局部、未再改成全局 `allocatedByUnit`）。
- `OperatorSettlement.settleOperatorExits`：每个退出者仍扫全量 debts（本夹具 exits=0）。
- `MarketSettlement.confirmedIncoming`：每个订单仍扫 shipments（本夹具 shipments 很小）。
- `MarketReadout` 的索引构建：读口按调用现建，非日结算。

### 6.4 剩余阻断

- 未做 0→120 的完整基线逐值 A/B（任务书允许替代；见 §4）。
- 未做 0→120 的 1 vs 8（0→10 已做 0 差异）。
- `/tmp/b3a-store` 本身不可用于 0→120（自环夹具问题，见 §6.1）；若上游坚持用该 store，需要先修复/迁移这 799 条规则。

---

## 7. 我没做 / 没验证的

- 未写/改任何测试；未跑 `test` / `verify`；未做变异自证。
- 未 commit/push。
- 未改 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`、命令、镜像/落盘格式、`StressPolicy`、价格、粮布保留、租率、`SECONDARY_PER_MILLE`、人口常量、关系规则。
- 未做 0→120 完整基线 A/B（见 §4/§6.4）；未做 0→120 的 1 vs 8；未做一年期/360 tick；未做更早历史档（M2 `useRights` 兼容仍以 B.2 的如实记录为准）。
- 未逐笔对 craft artisan 的 300‰ 布租做 ledger 级对账（仍沿用 B.3a 边界）。
- 未做新 jstack/JFR 耗时分解：本片用 A/B 读数与 0→120 墙钟验收，未再抓采样式栈；§6.3 的剩余热点来自代码审视，不是新测量。
- 旧档迁移只重跑了 B.2 的 `Probe3`：`PASS=60 FAIL=0`（`/tmp/b3a-perf-probe3-final.out`），未跑更早 store。

---

## 附：关键证据路径

- 索引实现：`simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementIndex.java`
- 基线 jar：`/tmp/b3a-perf-base/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（md5 `fd8faa9286c23cb3d816472844a85294`）
- 最终 jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（md5 `2cf86215762fb90807074133249bf281`）
- 编译/spotless/package 日志：`/tmp/b3a-perf-final-compile2.log`、`/tmp/b3a-perf-final-package2.log`
- `/tmp/b3a-store` 0→10 A/B：`/tmp/b3a-perf-run-final-prefix-{base,new}/`、`/tmp/b3a-perf-run-latest-prefix-new/`
- 固定世界 0→10 A/B：`/tmp/b3a-perf-run-final-ab-{base,new}/`、`/tmp/b3a-perf-run-latest-fixed8/`
- 1 vs 8：`/tmp/b3a-perf-run-latest-fixed1/`
- 0→120 性能：`/tmp/b3a-perf-run-latest120/`（`advance-0-120.log`、`state-summary.json`、`server.log`）
- Probe3：`/tmp/b3a-perf-probe3-final.out`
- state 重放/比较脚本：`/tmp/b3a_perf_state.py`
