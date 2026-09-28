# B.2c 劳动 ±1 毫偏差定位与修复报告（2026-09-29）

> 切片：R4 计划 §2.B.2 收尾（ProductionUnit + AssetShare 接线后，旧档续跑的逐值等价修复）。
> 约束遵守：**只改 `src/main/java`（本切片只动 1 个文件）**；未写/改任何测试；未跑 `test`/`verify`；未 `git commit`/push；
> 未做 B.3/E 切片；一次一个 Maven（每次先 `pgrep -af "surefirebooter|classworlds.launcher"`）。
> 起点：`6170760f` + B.2/B.2b 未提交工作树；**基线不重做**（沿用 `/tmp/b2-base-run`、`/tmp/b2-base.json`、`/tmp/b2-tick0-fixture`）。

---

## 0. 结论先行

- **根因不是 map 迭代序**，而是**同一 `(unit, group, household)` 的劳动配额出现两行**：
  - `LegacyHouseholdMigration.canonicalizeAllocationActivities` 只改 activity/actor、**保留旧 id**（`alloc-<产业>-<批次>-<家户>`）；
  - 新代码 `EconomySettlement.addLabor` 却只按**新 unit 型 id**（`alloc-<unit>-<批次>-<家户>`）查重 ⇒ 旧档续跑时同一语义键再发一条新 id；
  - `scaleLaborOfGroup` / `scaleLaborOfUnit` 对**每一行**分别 `x * after / before`（整数 floor）⇒ 两行各取整一次，
    比旧口径单行 `floor(ΣA × r)` 少 1 毫劳动；月度死亡缩放发生时，799 条旧档 farm 配额中有 388 条恰好跨整数边界，各少 1。
- **修复**：`addLabor` 在按新 unit 型 id 找不到时，用**旧口径产业型 id** 再查一次（并核对 `activity == 本 unit id`），
  命中就合并进既有行、保留旧 id。**1 个文件、约 15 行**，无随机/无 HashMap 迭代序、无时间依赖。
- **结果**：重跑新代码 0→30 tick，26 格抽样比较（忽略 `units` / `reason` id 措辞 / `subsistenceObligations`）**26/26 零差异**；
  全量规范状态（42857 行，含 units/classes/flows/laborSupply/shares）与基线**逐行相同**；守恒不变量无违反。
- `tools/mvn-lock.sh -DskipTests compile` 绿；`spotless:check` 绿；`-Dmaven.test.skip=true package` 绿；
  只有新代码一侧重跑（新 shaded jar md5 `3805190e8871233a44fc36bad041c4a7`）。

---

## 1. 定位方法、探针与规范 diff

### 1.1 先证明差异只可能来自 revision 5

`revisions` 表逐条 md5（`changeset_json`）：

| revision | tick | base md5 | new（修复前）md5 | 结论 |
|---|---|---|---|---|
| 1 | 0 | `fcd30b46…` | `fcd30b46…` | 同 |
| 2 | 0 | `ebb613df…` | `ebb613df…` | 同 |
| 3 | 0 | `d0439e68…` | `d0439e68…` | 同 |
| 4 | 0 | `817d0e3a…` | `817d0e3a…` | 同 |
| 5 | 30 | `acacacf5…` | `37e117b0…` | **唯一不同的批** |

⇒ 全部数值偏差编码在 revision 5（`AdvanceTime` 0→30）里；revision 1–4 与夹具完全同字节。

### 1.2 探针（均落 `/tmp`）

1. `/tmp/b2c_pycanon.py`（主探针，JDBC 读 revision → `changeset_json` → 按 `FieldDelta.rebuild` 语义逐批 apply 到空表
   → 规范 TSV）：产出
   - `/tmp/b2c-base.pycanon`（基线）、`/tmp/b2c-new-prefix.pycanon`（修复前新代码）、`/tmp/b2c-new2.pycanon`（修复后新代码）；
   - 行内含 `alloc/supply/unit/class/flow/share/condition` 七类；`alloc` 按 `(industry, group, household, actor)`
     聚合 `laborMilli`（新侧 `activity` 是 unit id ⇒ 经 `units` 映射回 industry；基侧 `activity` 是旧标签 ⇒ 经 actor id 映回 industry id），
     并打印 `(值, 行数)`；`unit` 行对基侧从 `Industry` 末尾兼容位读出 progressDays/cycleLaborMilli/cycleInputUsedMilli（因基档是旧形状）。
2. `/tmp/ProbeB2c.java`（Java 权威探针，照 `Probe3.java` 的方法）：`Timeline.readChangeSet(changeset_json)` →
   取 `economy` 模块 → **真实 `EconomyChangeSet.apply`** 到 `EconomyData.empty()`；产出
   `/tmp/b2c-base.canon`、`/tmp/b2c-new-prefix.canon`、`/tmp/b2c-new2.canon`。
3. `/tmp/b2c_tickdiff.py` + `/tmp/b2c_ticks.py` + `/tmp/b2c_run_ticks.sh`：把「一次 30 tick」拆成逐 tick revision，
   对基线 jar（`/tmp/b2c-base-tick`，revision 5..34）与修复前新代码（`/tmp/b2c-new29-tick`，revision 5..34）逐 tick 对比。
4. `/tmp/b2c_floorproof.py`：只取 tick29（死亡缩放前）与 tick30（缩放后）的 rows，按每组的 floor 方程反解比例，验证取整损失。

### 1.3 规范 diff 原文（表 / 键 / 差多少）

**修复前**：`diff -q /tmp/b2c-base.pycanon /tmp/b2c-new-prefix.pycanon` 不同；差异**只有 799 条 alloc 行**，其余
`supply/class/flow/share/condition/unit` 全部相同。原文示例（`alloc` 行 = `值<TAB>行数`）：

```
-alloc  farm@-19_-83  rural:-19_-83:MALE:1  hh--19_-83-rural-poor_peasant  ESTATE:farm@-19_-83  1584726  1
+alloc  farm@-19_-83  rural:-19_-83:MALE:1  hh--19_-83-rural-poor_peasant  ESTATE:farm@-19_-83  1584725  2
```

逐项统计：

| 表/键 | 修复前 | 修复后 |
|---|---|---|
| `allocations` 语义键 | 22475 个键都在；**799 个键行数 2 vs 1**（全部是 farm/ESTATE/农村 MALE） | 22475 个键、每键 1 行，与基线逐行相同 |
| `allocations.laborMilli` | 上述 799 键中 **388 个键比基线少 1**（全部是 `-1`；总差 −388） | 0 |
| `classes`（6392） | 0 | 0 |
| `flows`（6392） | 0 | 0 |
| `laborSupply`（4000） | 0 | 0 |
| `memberships`（31758） | 0 | 0 |
| `shipments`（31）、`markets`（799）、`assetshares`（1799）、`relations`（1799）、`meta` | 0 | 0 |
| `units`（1799，progressDays/cycleLaborMilli/cycleInputUsedMilli/operator/modeKey） | 0（用兼容位读基侧） | 0 |
| `operatorConditions`（0）、`debts`（0） | 0 | 0 |

原始行的样子（同一语义键）：

```text
基线：
  alloc-farm@-19_-83-rural:-19_-83:MALE:1-hh--19_-83-rural-poor_peasant = 1584726（1 行）
修复前新代码（2 行）：
  alloc-farm@-19_-83-...-hh--19_-83-rural-poor_peasant          =  295072
  alloc-unit-farm@-19_-83-ESTATE-farm@-19_-83-...-hh--...       = 1289653
  Σ = 1584725（比基线少 1）
修复后新代码（1 行，保留旧 id，值回到基线）：
  alloc-farm@-19_-83-...-hh--19_-83-rural-poor_peasant = 1584726
```

### 1.4 逐 tick 定位：差异恰好发生在唯一的月度死亡缩放 tick

`/tmp/b2c_tickdiff.py /tmp/b2c-base-tick/simos.db /tmp/b2c-new29-tick/simos.db` 的关键输出：

```text
tick= 0  rows b/n=22392/22392  dups b/n=0/0    total b/n=5586267122/5586267122  deaths=0 births=0
tick= 1  rows b/n=22475/23274  dups b/n=0/799  total b/n=5586267122/5586267122  deaths=0 births=0
...
tick=29  rows b/n=22475/23274  dups b/n=0/799  total b/n=5586267122/5586267122  deaths=0 births=0
tick=30  rows b/n=22475/23274  dups b/n=0/799  total b/n=5584737374/5584736986  deaths=35530 births=60165  FIRST-DIFF diffs=388 delta_sum=-388
```

- 新代码在 **tick1（第一次周期首日重排）** 由 `addLabor` 为 799 个已存在的旧 id 语义键各多发一条 unit 型 id ⇒ 799 个键变 2 行；
- tick1..29 没有死亡，规范值（按语义键求和）与基线**逐值相等**（map 序变化不进入数值）；
- tick30 是 30 天里唯一发生人口出生/死亡（death=35530、birth=60165，走 `applyPopulationChange` → `scaleLaborOfGroup`）的 tick，
  差异第一次出现且恰好 388 条、全部 −1。

`/tmp/b2c_floorproof.py` 在 tick29/tick30 上的输出：

```text
tick29 canonical key sets equal: True value diffs at tick29: 0
duplicate canonical keys at tick29 (new): 799 base: 0
feasible ratio keys: 799 infeasible: 0
total old-semantics minus new-semantics over duplicate keys: 388
  （每条：old=floor(A×r)，new=floor(a1×r)+floor(a2×r)，A=a1+a2，差恰为 1）
non-duplicate keys with any difference: 0
```

---

## 2. 根因

### 2.1 代码位置

| 位置 | 事实 |
|---|---|
| `simos-economy/.../migrate/LegacyHouseholdMigration.java:407-436`（`canonicalizeAllocationActivities`） | 旧配额只改 `activity`/`actor`，**保留旧 id**（B.2b 的既定口径）；旧 id 形如 `alloc-<industry>-<group>-<household>` |
| `simos-economy/.../time/EconomySettlement.java:4053-4095`（`addLabor`，修复前） | 新发/累加配额只按 `LaborAllocation.idOf(unit.id(), group, household)` 找既有行；查不到就 `put` 一条新的 unit 型 id ⇒ 与迁移保留的旧 id 行并存 |
| 同文件 `:2244-2273`（`scaleLaborOfUnit`）、`:2280-2307`（`scaleLaborOfGroup`） | 死亡缩放逐行执行 `laborMilli * after / before`（整数 floor），**行数越多，舍入损失越多** |
| 同文件 `:3900-4005`（`reallocateLabor`） | 周期首日"保留 + 回池 + 按缺口重发"会调用 `addLabor`；这正是新 id 第一次出现的地方 |

### 2.2 为什么新代码的"序"不同？——不是序，是行数

- 修复前第一版怀疑是 `FieldDelta.rebuild` / 迁移重排导致 `allocations` 迭代序变化，从而改变 `pool`/`byGap` 的分配结果。
  逐 tick 证据**排除了它**：tick1..29 两侧规范值逐值相等（`total` 完全相同、388 个差异在 tick30 才出现）；
  且 388 个差异全部可由"同一比例 r 下两行 floor 多丢一次"解释（`b2c_floorproof.py` 的 799/799 可行比例）。
- 取整损失的量纲：

```text
旧： floor( A × r )                       // A = a1 + a2
新： floor( a1 × r ) + floor( a2 × r )    // ≤ 旧值，差 ∈ {0,1}
```

  本批 799 个双行键中 388 个差 1、411 个差 0；388 个键的 household 全部在 tick30 有死亡（`flows.deaths > 0`，
  合计 6966 人），且全部是 `farm/ESTATE/rural MALE`——正是旧档 farm 配额被首次重排后多出一行的那批键。

### 2.3 排除的其他候选

- **map 迭代序**：tick1..29 规范值相等已证否；且 `addLabor` 的重复行是按 id 确定性出现的，不需要哈希/随机。
- **`EconomySeeder` 的新 id**：本基线是旧 jar 播的固定夹具，revision 1–4 两侧逐字节相同；问题只出在**旧档续跑**。
- **`need`/产能/投入公式**：tick29 两侧单位 `progressDays/cycleLaborMilli/cycleInputUsedMilli` 及 allocation 语义总和完全相同。

---

## 3. 修了什么

### 3.1 改动（唯一的 1 个生产文件）

`simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`，`addLabor`：

```java
LaborAllocationId id = LaborAllocation.idOf(unit.id(), group, household);
LaborAllocation existing = allocations.get(id);
if (existing == null) {
  // ★ B.2c：旧档 id 形如 alloc-<产业>-<批次>-<家户> ⇒ 用产业型 id 再找一次；
  //          activity 必须就是本 unit（防同一产业将来有多 unit 时误并到别的 unit 的行上）。
  LaborAllocation legacy =
      allocations.get(LaborAllocation.idOf(unit.industry(), group, household));
  if (legacy != null && legacy.activity().equals(unit.id().value())) {
    existing = legacy;
  }
}
if (existing != null) {
  allocations.put(existing.id(), withLaborMilli(existing, existing.laborMilli() + amount));
  return;
}
```

行为影响：

- **旧档续跑（本次场景）**：同一 `(unit, group, household)` 始终只有一行（保留迁移带来的旧 id），
  死亡缩放按行 floor 的次数回到旧口径；388 条 −1 消失。
- **新世界/新档（没有旧 id 行）**：`allocations.get(id)` 快速路径先命中；行为与修复前逐值相同（B.3 多 unit 语义不受影响）。
- **同产业多 unit（将来 B.3）**：合并前核对 `activity == 本 unit id`，旧 id 行只归属它迁移时对应的那个 unit，
  不会把另一个 unit 的缺口并过来；两 unit 各自仍用 unit 型 id 新发行。
- **身份/id 口径**：不重写迁移保留的旧 id（守住 B.2b 的"id 是身份"），只是不再为它另发一条同语义 id；
  运行中若某语义行被整条移除后重发，仍按既有规则发 unit 型 id（`LaborAllocation.idOf(unit,...)`）。

### 3.2 为什么不选其他修法

- **迁移时把所有旧 id 改写成 unit 型 id**：会直接改旧档身份/地址，且超出 B.2b 已定的"保留旧 id"口径；不做。
- **在 `scaleLaborOfGroup`/`scaleLaborOfUnit` 里按语义键先合并再按最大余数摊**：会改变旧口径"逐行 floor"的单行行为
  （旧档一个批次本来就可能有多行：farm + weave），且影响面大于本缺陷；不做。
- **按语义键统一排序/改 map 序**：逐 tick 证据表明序不是原因，改了只会引入新的行为差；不做。

本切片生产代码只有上面这一处；`spotless:apply` 只重排了本文件的这段注释，未触碰其他文件（`find ... -newermt` 核实只有本文件 mtime 变化）。

---

## 4. 重跑读数

### 4.1 编译 / 打包

```text
$ pgrep -af "surefirebooter|classworlds.launcher"     # 无命中
$ tools/mvn-lock.sh -DskipTests compile                # BUILD SUCCESS（含 checkstyle:check，04.5s）
$ tools/mvn-lock.sh -q spotless:apply && tools/mvn-lock.sh -q spotless:check   # exit 0 / exit 0
$ tools/mvn-lock.sh -Dmaven.test.skip=true package     # BUILD SUCCESS（05.6s）
```

（日志：`/tmp/b2c-compile-2.log`、`/tmp/b2c-spotless-*.log`、`/tmp/b2c-package.log`；重建的 shaded jar
`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar` md5 = `3805190e8871233a44fc36bad041c4a7`。）

### 4.2 只重跑新代码一侧（0→30 tick，1 线程）

```text
rm -rf /tmp/b2-new-run2 && cp -a /tmp/b2-tick0-fixture /tmp/b2-new-run2
tools/run-shaded.sh simos-app/target/*-shaded.jar --store /tmp/b2-new-run2 \
    --gui-port 5811 --mcp-port 5825 --economy-threads 1
python3 /tmp/b2_ab.py 5825 new2 30 /tmp/b2-new2.json
（跑完已停服务；5825/5811 端口已释放。另：开工时发现上一次新代码服务 pid 74013 仍在跑，已先停掉再打包。）
```

结果：`/tmp/b2-new2.json`，`advance_wall_s=38.681`；store `/tmp/b2-new-run2/simos.db` 共 5 个 revision，
rev5 tick30（与基线同一 revision 坐标）。

### 4.3 26 格抽样比较：零差异

```text
$ python3 /tmp/b2c_compare_readouts.py /tmp/b2-base.json /tmp/b2-new2.json
phase before: hexes=26 diffs=0
phase after:  hexes=26 diffs=0
TOTAL DIFFS (ignoring units/reason/subsistenceObligations): 0
```

对照：修复前的 `/tmp/b2-new.json` 在同一脚本下为 **91 条差异**（13/26 格，全部 ±1 毫劳动），
分布在 `population.labor.actors[ESTATE farm@...].laborMilli`、`labor.allocatedMilli`、
`classifications[].laborSoldMilli/laborHiredMilli/netLaborSoldMilli`、`householdConditions[].laborSoldMilli`；
修复后这些路径全部归零。

### 4.4 全量规范状态与守恒

```text
$ diff -q /tmp/b2c-base.pycanon /tmp/b2c-new2.pycanon
IDENTICAL          # 42857 行：alloc/supply/unit/class/flow/share/condition 全覆盖
```

（对照修复前：799 条 alloc 行不同，其中 388 条值少 1；`supply/class/flow/share/condition/unit` 全部相同。）

守恒/汇总（从各自 revision 流重放）：

| 指标 | 基线 | 修复后新代码 |
|---|---|---|
| 人口（classes Σpopulation） | 11854635 | 11854635 |
| alloc 行数 / 总 laborMilli | 22475 / 5584737374 | 22475 / 5584737374 |
| births / deaths | 60165 / 35530 | 60165 / 35530 |
| AssetShare 总量 | 2286937777 | 2286937777 |
| `max(Σalloc − available)`（4000 个 batch） | −1260 | −1260（**无 >0 违反**） |

原始 allocation id 集合仍不同（15847 条行在运行中从旧 id 重发为 unit 型 id；语义键/值逐行相同）——
这是 B.2 起"新发配额用 unit 型 id"的既定形状，不是数值差异。

---

## 5. 我没做 / 没验证的

- 只验证了 **1 线程、固定三国 worldgen 夹具、0→30 tick、经济格样本 26 格**；**未跑 4/8 线程**、未跑更长 tick（本切片要求内
  不需要，但"1-4-8 逐值一致"是 V 阶段的验收项，本轮没有做）。
- 未跑 `test` / `verify`（按任务约束：测试留到最后）；未写/改任何测试；未做变异自证（本轮约束）。
- 未 `git commit`/push；未做 B.3/E，也未动市场成交价、粮布保留、`StressPolicy` 阈值、状态词表。
- 修复后未重新生成基线（基线仍用任务给的 `/tmp/b2-base-run` / `/tmp/b2-base.json`）；仅新代码一侧重跑。
- 为定位做了**额外诊断运行**（不属于交付产物）：用基线 jar 在夹具副本上逐 tick 重放 0→30（`/tmp/b2c-base-tick`），
  以及用修复前新代码逐 tick 重放（`/tmp/b2c-new29-tick`）；两者 tick30 的 alloc 总量
  （`5584737374` / `5584736986`）与"一次 30 tick"的对应产物逐值相同，差异形态也相同（799 行双行、388 条 −1）。
- Java 探针 `ProbeB2c` 对**基线侧 `unit` 行**的进度需要从旧 `Industry` 兼容位读出：直接 `Timeline.apply` 旧 rev5 到
  "已有 unit 的状态"时，构造期归一化只在 unit 缺失时合成、不会把旧兼容位里的 progress 更新写回已存在 unit
  （这是探针里观察到的旧档重放边界，**本次未修**，不属本 ±1 任务；B.2b 的既有验收只应用到 rev4）。因此
  `unit` 行的等价结论以 `/tmp/b2c_pycanon.py`（直接读 rev5 旧changeset 的兼容位）与 26 格读数为准。
- `subsistenceObligations` 的差异按任务说明视为预期、未逐条核对；`classifications[].reason` 里的
  `farm@...` → `unit-farm@...` id 措辞差异按预期忽略，未作为差异统计。
