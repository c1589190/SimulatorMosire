# M3 Task 6 报告：UnitId / Route / Movement / Unit / UnitState / UnitSnapshot + effectivePosition + R5/R6/R7

日期：2026-09-18。BASE `091bd4d`，分支 `feat/m3-social-unit-simos`。
权威资料：计划第 1564~2238 行（代码即权威）、派单说明 task-6-brief.md（R-6-a~g）、M3 spec §4.1/§4.2/§六。

## 〇 结论速览

- 交付 9 文件（6 主 + 3 测试），`UnitIdTest` 2 + `UnitTest` 5 + `UnitStateTest` 5 = **12/12**。
- `./mvnw verify` **绿**（rc=0；Spotless/Checkstyle/SpotBugs/Surefire 全过；`BugInstance size is 0` ×5 模块）。
- 四轮变异全部按预期落红、每轮**恰 1 个红点**、`COMPILATION ERROR count = 0`；实验室在**最终代码字节**上重跑过一遍（见 §三）。
- **两处计划原稿矛盾，实测后按 spec 就地校正，取代说明写在代码注释里**（未动计划文件）：
  1. R-6-a：equipment 冻结位置（helper 内 → 赋值处）；
  2. **计划自相矛盾（本报告 §二.2，派单未预见）**：`legalReparentAcrossTimeIsNotACycle` 的**测试数据**与计划**自己的实现**互相矛盾——按计划原数据，计划原实现也必抛（已实测），故修数据、实现一字未改。

## 一 交付面

| 文件 | 内容 |
|---|---|
| `unit/UnitId.java` | 与 `RegionId` 同形：裸值 toString / static parse / 空白即抛（R-6-g 核过同形） |
| `unit/Route.java` | spec §4.5 六条校验：waypoints ≥2、path ≥2、首尾一致、waypoints 为 path 子序列、相邻格 distanceTo=1、无重复格；两侧 `List.copyOf` |
| `unit/Movement.java` | route/departedAt 非 null，speedAtDeparture/mobilityAtDeparture ≥ 1（出发时刻冻结，Javadoc 说明"不时间反演"） |
| `unit/Unit.java` | 9 组件；R5（parent/position 的 events 必空）；自环校验（段值==自身 id 即抛）；member ≥0 / speed ≥1 / mobilityPerMille ≥1；equipment 拷贝+逐键值校验+赋值处冻结（R-6-a 最终形态）；movement 非 null（空用 `Optional.empty()`） |
| `unit/UnitState.java` | `id → Unit` 表（LinkedHashMap+unmodifiableMap 保序不可变，无 `Map.copyOf`）；**逐关键时点**无环校验（关键时点 = 所有 parent 段 `from` 的集合）；`empty()`/`withUnits`；`effectivePosition`：自身优先→向父递归，查无此人 ⇒ 空，撞环 ⇒ `IllegalStateException` |
| `unit/UnitSnapshot.java` | 逐字照 `SocialSnapshot` 形制，`namespace() == "unit"` |
| 三个测试类 | 计划 Step 1 原文（`UnitStateTest.legalReparentAcrossTimeIsNotACycle` 的数据按 §二.2 校正） |

## 二 R-6-a ~ R-6-g 逐条

### R-6-a（SpotBugs）——**命中，已按 GameMap 先例改**
两步处置如实执行：先按计划原稿（`frozenEquipment` 在 helper 内 `unmodifiableMap` 并返回）跑 `verify` ⇒ **实测报 `EI_EXPOSE_REP` 1 条**（`Unit.equipment() … At Unit.java:[line 20]`，证据 `task-6-evidence/r6a-spotbugs-ei-expose-rep.log`）。照 GameMap 先例改为：helper 更名 `copyEquipment` 只做拷贝+逐键值校验，赋值处写 `equipment = Collections.unmodifiableMap(copyEquipment(equipment));`（就地注释说明）。行为一字不变。**复跑 verify rc=0、SpotBugs 0 条**。

### R-6-b（m2 合并图形态）——**红点与派单预测完全一致，含反直觉结果**
合并图变异体（所有单位全部段边并进一张图查环，消息仍含"成环"）实测：**唯一红点 = `legalReparentAcrossTimeIsNotACycle`**（`IllegalArgument 合并编制图成环，环上含 a`）；**`cycleAcrossUnitsThrowsAtConstruction` 在合并图下仍绿**——合并图确实有环、该用例断言的就是"抛"。这个反直觉结果正是"为什么不得用合并图"的判别力证据：合并图连**该报的**和**不该报的**都无法区分（它对两者都"响"，只是响得不对）。

### R-6-c（m1/m3/m4 形态）——全部按预测落红（§三）
m1 红 `parentAndPositionRejectEvents`；m3 **唯一**红 `ownPositionWinsOverParent`（父链两用例仍绿）；m4 红 `parentPointingToItselfThrows`。

### R-6-d（期望数字）——全部命中
实现前编译失败（唯一一次红 = 编译错，`cannot find symbol` ×N，证据 `pre-implementation-compile-failure.log`）；实现后 12/12；改前基线 **util 156 / map 248 / unit 12**（每轮 .kept 实测）；每轮 `COMPILATION ERROR count = 0`。

### R-6-e（装置）
`task-5-evidence/{run.sh,mutate.py}` 拷至 `task-6-evidence/`，五处按派单改：`/tmp/m3t6lab` 与 `task-6-evidence/rounds`；`-pl simos-unit -am`；manifest 范围 = `simos-util/src simos-map/src simos-unit/src`（124 个 .java）；surefire 明说抽取 = 日志 `FAILURE! -- in io\.mosire\.simos\.unit` 行 + 三个测试类 .txt 摘要（红点实际跨 `UnitTest`/`UnitStateTest`，均如实展示）；TARGET 四条 m1~m4。

### R-6-f（形制纪律）
`Route`/`Movement` 住 `unit` 根包 ✓；无环校验逐关键时点、最终代码无合并图 ✓；`LinkedHashMap`+`unmodifiableMap`，全模块无 `Map.copyOf` ✓；`effectivePosition` 查无此人 ⇒ 空、撞环 ⇒ `IllegalStateException` ✓。

### R-6-g（顺带核过）
`UnitId` 与 `RegionId` 同形 ✓；`Route` 六项校验逐条对 spec §4.5 ✓（派单写"五条"但列了六项，六项全实现）；`Movement` 两个 ≥1 ✓；`Unit` 三个下限 ✓。

## 二.2 ★ 计划自相矛盾的实测与校正（派单未预见，需控制器知悉）

计划 Step 1 的 `legalReparentAcrossTimeIsNotACycle` 给 b 配的是**单段** `[T0→a]`（走 `unit(...)` helper），只给 a 配了两段。按 M1 时间语义"段值向前恒定延拓"，t≥10 时 **a→b 与 b→a 同时在场**——这是真环。计划的**实现**（逐关键时点查，spec §4.2"含 anchor 之前的恒定延拓"）对这份数据**必抛**：首次落地 12 条测试时实测红了 `legalReparentAcrossTimeIsNotACycle`（`编制树在 SimosTimestamp[tick=10 …] 成环`）。即**计划的测试与计划的实现互相矛盾**，其用例注释"任一时刻都是链"对原数据不成立。

校正：**实现一字未改**（spec §4.2 逐字），修**测试数据**使其兑现自己的注释——合法改编要求**双方同刻各改一段**（子单位挂到新父、旧父脱离）：b 补 `[T10→空]` 段。校正后该用例在 t<10 是链 b→a、t≥10 是链 a→b，任一时刻无环，"各自合法的改编"语义与 R-6-b 的预测完全吻合（合并图恰好把两个不相交时间窗的边并成假环）。取代说明写在用例注释里；计划文件未动。

## 三 四轮变异（最终代码字节上重跑；每轮自证头齐全，全档在 `task-6-evidence/rounds/*.kept`）

每轮自证头（四轮同构，逐轮实测值见 .kept）：干净世界 OK（rsync 全新副本、排除 target/，124 个 .java 逐文件 md5 一致、清单之外 .java = 0）→ **改前绿**（util 156 / map 248 / unit 12，`COMPILATION ERROR count = 0`）→ 变异体与原件**字节不同**（md5 并列在档）→ 实际（修改∪新增）== 声明集合 → 改后 `COMPILATION ERROR count = 0`、simos-unit 实跑 3 个测试类。

| 轮 | 变异 | 实测红点（恰 1 个/轮） | 其余 |
|---|---|---|---|
| m3t6v-1 (m1) | `requireNoEvents` 删 `events().isEmpty()` 判断 | `UnitTest.parentAndPositionRejectEvents:50`（Expecting code to raise a throwable） | 11 绿 |
| m3t6v-2 (m2) | `requireNoCycleAtKeyTimes` 换**合并图**形态 | `UnitStateTest.legalReparentAcrossTimeIsNotACycle:96 » IllegalArgument 合并编制图成环，环上含 a`；**`cycleAcrossUnitsThrowsAtConstruction` 仍绿**（反直觉结果，R-6-b 判别力证据） | 11 绿 |
| m3t6v-3 (m3) | `effectivePosition` 先问父、后问己 | `UnitStateTest.ownPositionWinsOverParent:124`（Optional[2_2] ≠ 期望 1_1） | 11 绿（父链两用例仍绿，同派单预测） |
| m3t6v-4 (m4) | `Unit` 构造器删自环校验（连同失效的 `Segment` import，防 checkstyle 假红） | `UnitTest.parentPointingToItselfThrows:90`（Expecting code to raise a throwable） | 11 绿 |

注：第一遍实验室跑在 R-6-a 修正**前**的 `Unit.java` 上，结果与上表完全相同；因变异靶行（`requireNoEvents`/自环校验/`UnitState` 两处）与 equipment helper 无交集，结果本不受影响，仍按"证据须对得上落盘字节"重跑一遍并**以重跑档为准入库**。

## 四 门禁数字

- `./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitIdTest,UnitTest,UnitStateTest test`：**12/12**（2+5+5）。
- `./mvnw verify`（最终）：**rc=0，BUILD SUCCESS**；模块用例 util **156** / map **248** / social **30** / unit **12** / core **15**；SpotBugs `BugInstance size is 0` ×5 模块；Spotless/Checkstyle 无告警。`git status` 干净（除有意未跟踪的 `.omo/`）。

## 五 关切 / 未能核实清单

1. **`Route`/`Movement`/`UnitSnapshot` 的构造期校验本任务无用例直接覆盖**——12 条的盘子是计划定死的（且四轮变异按 R-6-b/c 只打 R5/R6/R7/effectivePosition）。这三类的守卫（Route 六条、Movement 两个 ≥1、UnitSnapshot 三 null 检 + namespace）当前无变异自证。它们在后续任务（路径规划/变更集/快照往返）会被真实使用，届时建议补靶子用例再做变异，否则就是"没钉住的护栏"。
2. **`legalReparentAcrossTimeIsNotACycle` 的数据校正**改变了计划原文的测试数据（§二.2）。若控制器认为应保计划原数据，则必须改实现语义（放弃"向前恒定延拓"），那会与 spec §4.2 冲突且让 R6 对"旧边+新边"型真环失明——我认为不可取，但这是设计裁定，不在实现者权限内。
3. R-6-a 的 helper 改名后，`Unit.java` 里 equipment 的"冻结"不再出现在一个名字里，读代码者需看赋值行才能看到不可变语义——已用就地注释压住，属可接受的形制代价（GameMap 同款）。
4. 未能核实项：无。所有红点、绿数、spotbugs 计数均为本机实测，无推导值。
