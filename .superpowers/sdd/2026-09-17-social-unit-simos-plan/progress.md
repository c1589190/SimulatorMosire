# SDD ledger — plan: docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md

> **本文件是 M3（SocialSimos + UnitSimos）的台账与恢复地图。**
> M1 台账在 `.superpowers/sdd/2026-09-16-util-simos-plan/`，M2 台账在 `.superpowers/sdd/2026-09-16-map-simos-plan/` ——
> 只读，不要写回去。本目录是本轮唯一可写台账。
> **台账记裁定与结论，不记取证过程**（CLAUDE.md 纪律；体量不得压过代码本身）。

**上游**：M3 spec `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`（**已获用户批准，2026-09-17**）；
总纲 `2026-09-16-simos-master-design.md`；M1 spec（已执行）；M2 spec 与计划（已关账，`origin/feat/m2-map-simos` @ `9e4c1cb`）。

**执行基线**：分支 `feat/m3-social-unit-simos`，BASE = `1b0397e`（计划提交）。执行者 = 控制器 + 实现者子代理。

**跨里程碑承接约束**（M1/M2 关账时补记）：
1. 含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 的 `addition`（各写各的 lambda ⇒ 假红）。
2. `Map.copyOf` / `Set.copyOf` 迭代序非内容纯函数 ⇒ 保序一律 `LinkedHashMap` + `unmodifiableMap`。
3. 变异自证五形态（CLAUDE.md 纪律节）：字节不同先自证 / 白名单推成目标类名 / 每轮干净世界 / `COMPILATION ERROR` 计数为 0 / 红点必须在被保护那行。

---

## 任务地图（13 任务）

| # | 任务 | 状态 |
|---|---|---|
| 1 | `FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制 + M2 侧委托 + R1 | ✅ `0de76ed`+`b9ff9b8` |
| 2 | `TerrainType.IMPASSABLE_MOVE_COST` + R2 | ⏳ |
| 3 | `PopulationSeries`：积分语义 + R3 + R4 | ⏳ |
| 4 | `SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返 | ⏳ |
| 5 | `SocialResolver` + R12/R13 的 social 半 | ⏳ |
| 6 | `UnitId` / `Unit` / `UnitState` + `effectivePosition` + R5/R6/R7 | ⏳ |
| 7 | `UnitChangeSet` + 往返 | ⏳ |
| 8 | `MovementCost` + `TerrainMovementCost` + 判据二夹具 | ⏳ |
| 9 | `PathFinder`（A\*）+ R8 | ⏳ |
| 10 | `Route` / `Movement` / `MovementState` / `UnitMoves` + R9/R10 | ⏳ |
| 11 | `UnitOperations` 8 项 + R11 | ⏳ |
| 12 | `UnitResolver` + R12/R13 的 unit 半 | ⏳ |
| 13 | M3 关账 | ⏳ |

---

## 执行日志

（逐任务关账时追加）


## Task 1 关账（2026-09-17）

- **交付** `0de76ed`（代码 9 文件，+138/−104，含 53% rename 检出）+ `b9ff9b8`（报告 + 证据，6 文件 +978）。
- **评审形态 = 控制器自读 diff**（不派评审者）。核过：迁移逐字（`diff`/`rebuild` 逻辑与原件一致，接口内 `new Unchanged<>()` 等短路写法正确）；类 Javadoc 三处改写 + C7 新增段落位；`MapChangeSet` 委托完整、4 行无用 import 已清（R-1-c）；import 面 7 文件全对（终局 `git grep "map.change.FieldDelta" -- 'simos-map/src'` 空）；R1 用例照计划（`rawLines`、`containsExactly`、`entry` 导入已补）。
- **控制器独立复核**（与实现者证据相互独立）：`RegressionGuardsTest` **9/9 绿**（BUILD SUCCESS，本机当场跑）；变异轮 `m3t1v-1.kept`：干净世界 113 .java/extras=0、改前 156+246 绿、变异体 md5 `a1f1e486…`、`COMPILATION ERROR count = 0`、25 类真跑、红点原文 `R1_thereIsExactlyOneFieldDelta:402`（actual 带出多余键 `LegacyFieldDelta.java`）；`gate-clean-verify.txt` rc=0、util 156 / map **246** / core 15、`BugInstance size is 0` ×5。
- **裁定（执行期，取代计划 Step 8 第 3 条）**：R1 变异体的**声明名必须是 `FieldDelta`**（包级私有，落 `LegacyFieldDelta.java`），**不是** `LegacyFieldDelta`——守卫是逐行子串 `interface FieldDelta`，改名副本会**假绿**。依据：javac 探针（包级私有可编译，rc=0）+ 本任务实跑。计划原文的"确保类名与文件名一致（LegacyFieldDelta）"是计划缺陷。
- **关切（不挡关账）**：① R1 用 `rawLines` 不剔注释 ⇒ 对注释里出现该字面同样敏感（只会更严；将来若 Javadoc 里出现会假红，届时可改 `codeLines`，一行的事）；② 变异体命中 2 次（注释 1 + 声明 1），决定性命中是声明行；③ `simos-social`/`simos-unit` 的 src/main 现只有 `package-info.java` ⇒ R1 对这两模块**结构性活着**、暂无真实代码可护；④ `.omo/` 为先前已存在的未跟踪目录，未触碰。

## Task 2 关账（2026-09-17）

- **交付** `ca1284a`（`TerrainType.java` +12/−2，`ImpassableSentinelTest.java` 新建 32 行）+ `577baf9`（报告 + 证据）。
- **评审 = 控制器自读 diff**：常量落 record 体最前、Javadoc 照计划；类 Javadoc 三处字面量改写到位；用例 = 计划原文 + **R-2-a 的 `filteredOn(key -> !"ocean".equals(key))` 修正**（注释在案）。
- **控制器独立复核**：`ImpassableSentinelTest` **2/2 绿**（本机当场跑，BUILD SUCCESS）；两轮 `.kept` 红点**各自唯一**落 `oceanUsesTheImpassableSentinel:18`（m1 `expected: 998 / but was: 999`，m2 反向——两侧互换证明守卫真在比较两侧）；两轮改前 **156+248 全绿**、`COMPILATION ERROR count = 0`；R-2-c 兑现（`TerrainCatalogTest` 未受 m2 波及）。
- **裁定（执行期，取代计划 Step 1 第二用例原文）**：`everyOtherTerrainIsBelowTheSentinel` **必须排除 ocean**——`KEYS` 含 ocean 自身（999），原样 `allSatisfy` 会对自己取哨兵的海洋断言 `999 <= 998`、**恒红**（计划 Step 4 的"2/2 绿"按原文到不了）。修正 = `filteredOn`，保用例名与 Javadoc 的原意（"除海洋外"）。
- **关切（不挡关账）**：① 常量与词表仍是**两个物理字面量**（守卫钉等值；物理单一化=新裁决）；② unit 侧对它的消费要等 Task 8 才可核实（届时勿复制第二份哨兵）；③ 装置本轮做了两处必要触碰（surefire 抽取目标、`replace_exactly_once` 自证），已记入报告。
- **下一任务** Task 3（`PopulationSeries` + R3 + R4）。

## Task 3 关账（2026-09-17）

- **交付** `381c705`（`PopulationSeries.java` 170 行 + `PopulationSeriesTest.java` 153 行/10 用例 + `package-info.java` 顺改）+ `e0f8dea`（报告 + 证据）。
- **评审 = 控制器自读 diff**：五步算法逐字对上 spec §3.2（切分点收集/anchor 前恒定/先切段后事件/列表序施加）；构造校验三条（growth 首段晚于 anchor、growth 带事件、events 非递减）+ null 三条；冻结数字 **18036 / 6300 / 15000 / 10000** 全在断言里（实测行号 39/57/77/84/146）。
- **控制器独立复核**：`PopulationSeriesTest` **10/10 绿**（本机当场跑，BUILD SUCCESS）。
- **三轮红点（实测，先跑后写）**：m1（删 `cuts.add(t)`）**3 红同根因**——seed 18036→16700、boundary 6300→2100、multi 15000→5000（尾段不施）；m2（反转 `applyEventsAt` 施加序）**恰 1 红**——multi 15000→15300；m3（删 growth 事件校验）**恰 1 红**——`growthCarryingEventsIsRejected`。各轮改前 156/248/10 全绿、`COMPILATION ERROR count = 0`。
- **追认（执行期）**：计划第 703 行消息里的 ASCII 引号确会截断字符串字面量（实现者 hexdump 实证），按计划 809 行自带处置改为「同刻多事件」——两处均在计划授权内。
- **关切（不挡关账）**：① m1 的连带红已全列——形态是"同根因"而非"判别力不足"；② `mutate.py` 注释里 m1 机制的初稿预测值（20875）与实测（16700）不符，已按实测订正入库——`.kept` 证据本就是实测值；③ simos-social 首个测试目录建立，surefire 正常识别；④ 实现者途中拦下一个误入的 `__pycache__/*.pyc`（未入库）。
- **下一任务** Task 4（`SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返测试）。

## Task 4 关账（2026-09-17）

- **交付** `2815463`（3 类型 + 2 测试类，5 文件 +354）+ `229664f`（报告 + 证据，10 文件）。
- **评审 = 控制器自读 diff**：三类型形制照计划（SocialData 冻结拷贝 + 逐键查 null；SocialSnapshot namespace 固定；SocialChangeSet **全委托** FieldDelta、不重实现）；往返框架照 M2 形制（豁免集空被单独钉死、default 抛、双向 subset）。
- **控制器独立复核**：`SocialChangeSetTest` 7 + `SocialRoundTripTest` 4 = **11/11 绿**（本机当场跑）。
- **m2 探针轨迹（R-4-b 决策规则完整兑现，实测）**：① 现状夹具 → **存活**（0 红，印证扫描结论"断言序不敏感是形态问题"）；② 加键至 5 → **仍存活**（0 红，实测否证"加键能救"）；③ 补 `populationOrderFollowsInsertionOrder`（`containsExactly` 钉插入序）→ **唯一红点落新用例 :76**；独立 20 JVM 实测该夹具 `Map.copyOf` **20/20 打乱**（8 种槽位序）——新护栏判别力已量过。
- **裁定（执行期，取代计划第 945 行的 `isSameAs`）**：计划测试与计划自带实现**自相矛盾**——`SocialData` 构造期总是冻结拷贝 ⇒ `apply` 返回的必是新实例，`isSameAs` 不可满足（首轮实测即红）。改为 `containsExactlyEntriesOf`（**含迭代序**，更贴 spec §3.4 的"原样（连键序）"冻结语义）；实现一字未改。**追认**。
- **裁定（执行期，补 R-4-b 序观察点）**：spec §3.1 冻结要点 1 的"保序不可变"在 Task 4 原断言下**零观测点**；补一用例（5 键非平凡序）使其可被 m2 变异捕获。依据 = 计划原文"加键造捕手"的意图 + G13 护栏自证纪律。
- **m1/m3 红点**：m1 = 4 红（恒 Unchanged 同根因）、m3 = 4 红（apply 不吃 delta 同根因），各轮 CE=0、改前全绿。
- **关切（不挡关账）**：① m1/m3 的 `.kept` 摄于 S0（11 用例补之前），红点集合不变、序用例不调用 between/apply，已如实记入报告；② m2 变异须连删 `Collections` import（否则 UnusedImports 让红变构建红）——装置已记。
- **下一任务** Task 5（`SocialResolver` + R12/R13 的 social 半）。

## Task 5 关账（2026-09-17）

- **交付** `a5755ba`（`SocialResolver.java` 127 行 + `SocialResolverTest.java` 168 行/9 用例）+ `c229eef`（报告 + 证据）。
- **评审 = 控制器自读 diff**：形制逐条对上 `MapResolver`（namespace 前置空候选、`dataOf` 先于形状判定、`segments.get(1)` 根主体检查、>3 段空候选、Index 恰 2 元走查格、canonical 全经 AST）；`Address` 构造期 ≥2 段（实测）保证 `get(1)` 无越界——与计划核验一致。
- **控制器独立复核**：`./mvnw verify` 整仓 **BUILD SUCCESS**；Tests run util 156 / map **248** / social **30** / core 15；`BugInstance size is 0` **×5**（★ 实现者报告写"×4 模块"，独立跑为准 ×5——已更正口径）；三类 Spotless/Checkstyle 全过。
- **三轮红点（实测）**：m1 = `absentHexIsAnEmptyCandidateNotAnError:89`；m2 = `colonMapIdKeepsQuotesInCanonical:159`（`expected "social:\"m:1\"" but was "social:m:1"`——**R-5-b 靶子判别力兑现**，全部 `Map1` 用例不红）；m3 = `wrongSliceTypeThrows:143`（ClassCastException ≠ IAE；`missingSliceThrows` 不红符合预期选形）。
- **裁定（执行期，补 R-5-b 靶子）**：m2 若无 `:`-mapId 用例会**存活**（手写拼接与 AST 对无引号输入逐字相同）；补 `colonMapIdKeepsQuotesInCanonical`（根 + hex 双断言）后 m2 有唯一落点。已实测。
- **关切（不挡关账）**：① m2 的 hex 断言因 JUnit 首败即停未单独执行——报告按"1 实测 + 1 同机制"如实记（同根因：同一对手写拼接丢同一对引号）；② 实验室改前基线 social=30（21 是任务前存量，口径差已说明）；③ 本轮 verify 日志未单独入库（实现者只在报告记数字）——控制器独立跑已复核，下不为例（后续任务要求 gate 日志入库）。
- **下一任务** Task 6（UnitSimos 状态层：`UnitId`/`Unit`/`UnitState` + `effectivePosition` + R5/R6/R7）。

## Task 6 关账（2026-09-17）

- **交付** `a62c5a8`（6 实现类 + 3 测试类，9 文件 +658）+ `4e369b9`（报告 + 证据，9 文件）。
- **评审 = 控制器自读 diff**：`Route`/`Movement` 在根包；`UnitState` 关键时点逐点查环（非合并图）；`units`/`equipment` 保序不可变；`effectivePosition` 自身优先 → 父链 → 空，撞环 `IllegalStateException`；`UnitId` 三件套同形。
- **控制器独立复核**：三测试类 **12/12 绿**（本机当场跑，2+5+5）；`verify` 由实现者跑过（rc=0、`BugInstance size is 0` ×5、util 156 / map 248 / social 30 / unit 12 / core 15）。
- **★ 计划缺陷（执行期实测发现，取代说明在案）**：计划自己的 `legalReparentAcrossTimeIsNotACycle` **数据与实现自相矛盾**——b 单段 `[T0→a]` 按"向前恒定延拓"在 t≥10 仍指向 a，与 a→b 同时在场 ⇒ 真环，计划自己的实现必抛（首轮实测确实抛）。修正 = **改测试数据**（b 补 `[T10→空]`，合法改编要求双方同刻各改一段），**实现一字未动**。修正后 m2（合并图）预测精确兑现。
- **R-6-a 命中（实测先例兑现）**：计划原稿 `frozenEquipment` 返回 `unmodifiableMap` ⇒ SpotBugs **`EI_EXPOSE_REP` ×1**（`Unit.java:20`，日志入库）⇒ 按 GameMap 先例改"helper 只拷贝校验 + 赋值处冻结"，复跑 rc=0。
- **四轮红点（实测，每轮恰 1 红）**：m1 = `parentAndPositionRejectEvents:50`；m2（合并图）= `legalReparentAcrossTimeIsNotACycle:96 » 合并编制图成环`——且 `cycleAcrossUnitsThrowsAtConstruction` **仍绿**（反直觉结果如实记录，正是"为何不得合并图"的判别力证据）；m3 = `ownPositionWinsOverParent:124`；m4 = `parentPointingToItselfThrows:90`。各轮 CE=0、改前 156/248/12 绿。
- **关切（不挡关账，报告 §五）**：`Route`/`Movement`/`UnitSnapshot` 的构造期守卫**本轮无直接测试目标**（12 条用例是计划定盘），后续任务（8/10/11/12）会实打实压到它们——届时按需补变异靶子。
- **下一任务** Task 7（`UnitChangeSet` + 往返框架）。

## Task 7 关账（2026-09-17）

- **交付** `6d805b3`（UnitChangeSet 39 行 + 两测试类 273 行，3 文件 +312）+ `787f6db`（报告 + 证据 11 文件）。
- **评审 = 控制器自读 diff**：全委托 FieldDelta；两个 switch 的 default 抛；豁免集空被单独钉死；`applyOfUnchanged` 用 `containsExactlyEntriesOf`。
- **控制器独立复核**：11/11 绿（本机当场跑，7+4）；`verify` rc=0（util 156 / map 248 / social 30 / unit 23 / core 15）。
- **序夹具实测量（20 独立 JVM/组，存档）**：5 键 17/20 SHUFFLED（不达标弃用）→ **6 键 20/20 SHUFFLED（采用）**；附带发现双键 {u-1,u-2} 13/7 随盐漂 ⇒ `applyOfUnchanged` 退回单键 base——**"夹具键集要当场量"的又一条实测**。
- **四轮红点（实测）**：m1（diff 对调）4 红（含"对调后 Upsert 携带旧值"）；m2-A（加 tag 组件）恰 2 红（组件数/双向 subset + mutate 抛"未登记的组件"）——铁律 5 机械落地的直接证明；m2-B（changedOf default→true + 注册 tag）**`every…` 由红转绿 = 泄漏已验证**（温和兜底废掉判别力的实测证据）；m3（Map.copyOf）**唯一红 = `unitOrderFollowsInsertionOrder:87`**、与前轮零重叠。各轮 CE=0、改前全绿。
- **裁定（执行期，R-7-a）**：平移母本取 Task 4 **最终形态**（`containsExactlyEntriesOf` + 序观察点）；unit 序用例的夹具按 20-JVM 实测量定（6 键）。
- **下一任务** Task 8（`MovementCost` + `TerrainMovementCost` + 判据二夹具）。

## Task 8 关账（2026-09-17，含 fix-1）

- **交付** `7587606`（MovementCost 24 行 + TerrainMovementCost 86 行 + MoveFixture 80 行 + 测试 68 行）+ `ad8b45d`（报告 + 证据）；**fix-1** `cc8d66c`（minStep 全不可通行边界转正：fixture +21 / 测试 +7）+ `41e9287`（报告与证据更新）。
- **评审 = 控制器自读 diff**：单例私构造；`costOf` 引用 `TerrainType.IMPASSABLE_MOVE_COST`（**unit 侧 src/main 零 999 字面量**）；`minStepCostMillis` 一次查表合并（R-8-c 兑现）；非相邻/自环抛、图外空。测试 7/7。
- **控制器独立复核**：`TerrainMovementCostTest` **7/7 绿**（本机当场跑）；实现者 `verify` rc=0（util 156 / map 248 / social 30 / unit 30 / core 15、BugInstance 0×5）。
- **★ 计划缺陷两处（实测发现）**：① **m1 前提不成立**——`scale` 对 `v=moveCost×1000` 恒为精确乘法（`+500` 永不进位），`Math.round` 形态是**等价变体**；实现者以 **1 002 990 对 (moveCost,‰) 全域扫描 0 分叉**实证，随后按 R-8-a 跑 m1'（丢 ×1000）真变异红在冻结夹具。② **m3 前提不成立**——计划自带的 minStep 用例用 `map(STEEP_65)`，**无 999 格** ⇒ 删跳过守卫在提交套件上 0 红；判别输入是"全不可通行图 ⇒ 0"（spec §4.3 第 6 条），**该边界原无提交在案用例** ⇒ 控制器裁定**转正**（fix-1）并重跑 m3 轮，红点落 `allImpassableMapHasZeroLowerBound:73`（expected 0 / was 499500）。
- **四轮红点（实测）**：m1 存活（等价，已证）；m1' `stepCostsMatchTheFrozenFixture:21`；m2 `impassableTerrainHasNoCost:29`；m3（fix-1 重跑）`allImpassableMapHasZeroLowerBound:73`。
- **下一任务** Task 9（`PathFinder` A\* + R8 对拍/决定论）。

## Task 9 关账（2026-09-17）

- **交付** `4355e9e`（`PathFinder.java` 118 行 + `PathFinderTest.java` 219 行）+ `151ccbb`（报告 + 证据）。
- **评审 = 控制器自读 diff**：全序 `(f,h,q,r)` 平局项在；`bestG`/`cameFrom`/`closed`/`PriorityQueue` 结构照计划；`NoHeuristic` 只改 `minStepCostMillis→0`；起终点/单元素/不可达边界齐。
- **控制器独立复核**：`PathFinderTest` **6/6 绿**（本机当场跑）；`verify` rc=0（util 156 / map 248 / social 30 / unit 36 / core 15）。
- **★ 计划夹具两处就地校正（已实测在案）**：① **几何不成立**——计划"绕行"首步 `(0,0)→(1,1)` 的 `distanceTo=2`（不相邻），原图从 H00 只能走山格；改 `H21`→`H01=(0,1)`，绕行 = `H00→H01→H11→H20`（三步各 distanceTo=1）。② **数字错位**——计划按 ‰1000 写 11000/3000，而夹具单位 ‰500 ⇒ 按冻结规则重算 **5500/1500**。`MoveFixture.unit()` 未动。
- **四轮红点（实测）**：m1(×2 高估) **存活**（与预判一致）；m1'(×1000) **双红** `aStarDetours…:121`（1500 vs 5500）+ `matchesDijkstra…:137`；m2（删平局项）**红** `sameInputTwice…:160`——对称夹具钉死精确路径 `[(0,0),(1,-1),(2,-1)]`，把"平局项决定跨实现序"变成**可观测输出**（比预期更强，决定论护栏有真判别力）；m3（删 closed 判重）**存活**（一致性下它是性能护栏，如实记）。各轮 CE=0、改前全绿。
- **裁定（执行期）**：`unreachableGoalIsEmpty` 的"只留 H00"会退化成 goal-缺席前置检查（不测搜索穷尽）⇒ 改双孤岛（H00/H20 都在图、不相邻），真走穷尽路径。追认。
- **关切**：m1(×2)/m3 两个存活变异是**实测结论**（非失败），已连理由入报告；`sameInputTwice` 现在同时承担"重跑相等 + 全序输出"两职。
- **下一任务** Task 10（`Route`/`Movement`/`MovementState`/`UnitMoves.evaluate` + R9/R10，判据二）。

## Task 10 关账（2026-09-17）

- **交付** `c69a42b`（MovementStatus/MovementState/UnitMoves + 测试 9 条，4 文件 +308）+ `8ed53f0`（报告 + 证据）。
- **评审 = 控制器自读 diff**：判据二逐值（40000→27500→−5000⇒remaining 5000；22h⇒1000；23h⇒ARRIVED；不可通行⇒NEED_REPLAN@H12）全对冻结表；`MovementState` 构造期拒矛盾组合（加严到 **4 组**）；`evaluate` 纯函数、机动性冻结走内部副本。
- **控制器独立复核**：`UnitMovesTest` **9/9 绿**（本机当场跑）；`clean verify` rc=0（unit 45）。
- **★ 计划缺陷（m1 前提不成立）+ 处置（R-10-b）**：`>=`→`>` 在 at=23 **仍 ARRIVED**（33500 > 32500），套件无等值边界用例 ⇒ m1 存活（实测在案）；**补 `exactBudgetArrivalIsArrived`**（speed=5、at=T0+9 ⇒ 预算恰 45000）后重跑 m1 ⇒ 红点落该用例（`OptionalLong[0]` 触发构造期 IAE，"恰够也是够"）。补后测试数 9 = 计划 Step 4 写的 9。
- **各轮红点（实测）**：m1 存活（8 测试世界）→ 补边界后重跑唯一红；m2（frozen→原 unit）唯一红 `mobilityChange…:141`（R10 第二靶）；m3 唯一红 `impassableNextStep…:95`；m4 唯一红 `noRouteOrEarlier…:150`。**补测后零存活变异**。
- **关切**：`movementStateRejectsSelfContradictoryCombinations` 由 2 组断言加严到 4 组（R-10-e 的四组全钉，方法数不变）；编译失败日志为复现件（已注明）。
- **下一任务** Task 11（`UnitOperations` 8 项 + R11）。
