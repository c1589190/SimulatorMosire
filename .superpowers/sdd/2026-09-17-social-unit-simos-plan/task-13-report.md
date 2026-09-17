# M3 Task 13 关账报告 —— M3（SocialSimos + UnitSimos）关账

**日期**：2026-09-18（跑批时刻 03:01:44 +08:00）
**分支**：`feat/m3-social-unit-simos`，BASE = `1a75a72`（Task 12 关账）
**判据**：M3 spec §1.1 四条；派单 `task-13-brief.md`（R-13-a~g）；计划 Task 13（第 4114~4173 行）。
**证据目录**：`.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-13-evidence/`（本任务全部日志在库，不在 /tmp）。

---

## 一 全量门禁（终局字节；R-13-a）

**字节说明**：门禁跑在**最终提交字节**上——`UnitMovesTest` 的 R-13-b 新用例先落盘并自证绿（§三），计划回填与 CLAUDE.md 随后落盘，最后跑 `./mvnw clean verify`。此前另有一遍摸底跑（补 CLAUDE.md 之前，`gate-clean-verify-recon.log`），两遍数字**完全一致**（docs 不进构建）；**以终局遍为准**，日志 `gate-clean-verify.log`。

| 项 | 实测（终局遍） |
|---|---|
| 退出码 | **rc=0**，`BUILD SUCCESS`，Total time 19.161 s |
| 六模块 | 父 + **UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos 全部 SUCCESS**（Reactor Summary 六行，日志 506~511 行） |
| `Tests run` 五数 | **util 156 / map 248 / social 30 / unit 68 / core 15**（合计 517；M2 关账时 social/unit 是 0，★ 期望"从 0 变实际条数"兑现） |
| 模块测试类数 | util 17 / map 26 / social 4 / unit 10 / core 1（`Running io.mosire.simos.<m>.` 计数） |
| `BugInstance size is 0` | **×5**（util/map/social/unit/core 各一次） |
| `^\[ERROR\]` 行数 | **0** |
| `^\[WARNING\]` 行数 | **1** —— 出处：`spotbugs:4.10.4.1:spotbugs @ simos-parent`（"No files found to generate report on"，父 POM packaging=pom 无类可报；历次里程碑同源） |

unit 68 = 原有 67 + R-13-b 新用例 1（`UnitMovesTest` 9→10）。

## 二 判据一：人口种子表逐值 ✅

- 跑批痕迹（`step2-populationseries.log`）：`PopulationSeriesTest` **Tests run: 10, Failures: 0, Errors: 0**，BUILD SUCCESS。
- 字面断言在案（`step2-grep.txt`，`git grep` 实测）：
  - **18036** → `PopulationSeriesTest.java:39` `.isEqualTo(18036L)`（种子表整表：[0,20)+4000→14000；[20,45)+3500→17500；t=45 −800→16700；[45,53)+1336→**18036**）；
  - **6300** → `:57` `.isEqualTo(6300L)`（t=10 先切段 2100 再施事件 +4200）；
  - **15000** → `:77` `.isEqualTo(15000L)`（同刻双事件按插入序）；
  - 另 `10000` 等表值在该类断言中（Task 3 关账时实测行号 39/57/77/84/146 在台账）。
- 三轮变异（m3t3v-1/2/3）红点全落这些值上（见 §五），判别力已自证。

## 三 判据二：移动逐值表 ✅（含 R-13-b 处置）

### 3.1 逐行对应（行号 = 当前工作树实测，`step3-grep.txt`）

| spec §4.5 表行 | 直接断言 | 痕迹 |
|---|---|---|
| 成本 12500（25×1000 按 ‰500） | `TerrainMovementCostTest:21` `.hasValue(12500L)` | 7/7 绿 |
| 成本 32500（65×1000 按 ‰500） | `TerrainMovementCostTest:24` `.hasValue(32500L)` | 7/7 绿 |
| 预算 40000 → **27500**（40−12.5） | **`UnitMovesTest:73` `isEqualTo(27500L)`（R-13-b 补条）** | 10/10 绿；m13v-1 下 expected 27500 / was 39987 红 |
| 27500 付不起 → **−5000**（27.5−32.5） | **`UnitMovesTest:74` `isEqualTo(-5000L)`（R-13-b 补条）** | 同上 |
| evaluate 的 **remaining = 5000** | `UnitMovesTest:50`（既有 `atTwentyHours…` `.hasValue(5000L)`）+ **`UnitMovesTest:77` `isEqualTo(5000L)`（补条第三断言）** | 两处皆直证 |
| currentHex=[1,2]、nextHex=[1,3]、IN_TRANSIT | `atTwentyHoursIsInTransitWithFiveMpRemaining:46-51` | 既有 |

### 3.2 R-13-b 的处置（二选一选了"补"）

关账前状态：27500 / −5000 只有**联合**可推（currentHex=H12 ∧ remaining=5000），**无直接断言** ⇒ 按计划 Step 3 ★ **补一条**：

- `UnitMovesTest.criterionTwoArithmeticMatchesTheSpecTable`（`simos-unit/src/test/.../move/UnitMovesTest.java:62-78`）：
  - 预算从**生产代码**取：`movement.speedAtDeparture() * 1000L * (at.tick() - movement.departedAt().tick())`（与 `UnitMoves.evaluate:42` 同式）；
  - 两段成本从**生产代码**取：`TerrainMovementCost.INSTANCE.costMillis(H11,H12,…)` / `costMillis(H12,H13,…)`；
  - 期望值全是**字面量**：`27500L` / `-5000L` / `5000L`——无恒等式（写 `a−b==a−b` 没有判别力）；
  - `UnitMovesTest` 9→**10**，unit 模块 67→**68**（终局门禁 `Tests run: 68` 实证）。

### 3.3 变异轮 m13v-1（装置 = `task-13-evidence/{run.sh,mutate.py}`，继承 Task 12）

- `run.sh init`：md5 清单 **141 个 .java**（新测试已就位后建）。
- **改前**（`rounds/m13v-1.kept` 5~63 行）：干净世界 OK（rsync 全新副本、清单逐文件一致、清单外 .java=0）；**156/248/68 全绿**，`COMPILATION ERROR count = 0`，BUILD SUCCESS。
- **变异体**：`TerrainMovementCost.costOf` 的 `scale(type.moveCost() * 1000L, …)` → `scale(type.moveCost(), …)`（丢 ×1000）；md5 `cd20ce3e…` → `b03e4871…`（**字节不同自证 OK**）；并集自证（实际改动 == 声明集合）OK。
- **改后**：`COMPILATION ERROR count = 0`；simos-unit 真跑 10 个测试类；`Tests run: 68, Failures: 6`，BUILD FAILURE。
- **红点 6 个，全列如实**（`m13v-1.kept` 132~144 行）：
  1. **`UnitMovesTest.criterionTwoArithmeticMatchesTheSpecTable:73`** —— expected `27500L` / was `39987L`（40000−13）＝**声明靶子，红在被保护的那行**；
  2. `TerrainMovementCostTest.stepCostsMatchTheFrozenFixture:21` —— expected `12500L` / was `OptionalLong[13]`（**同根因连带**，派单预告内）；
  3. `UnitMovesTest.atTwentyHoursIsInTransitWithFiveMpRemaining:48` —— 两段成本 13+33 被预算付清 ⇒ ARRIVED H13，expected `1_2`（**预告外连带**，同根因：成本单位错 ⇒ 行程判定跟着错）；
  4. `UnitMovesTest.atTwentyTwoHoursIsOneThousandShort:84` —— 同上（预告外连带）；
  5. `PathFinderTest.aStarDetoursAroundExpensiveTerrain:121` —— expected `1500L` / was `6L`（预告外连带：A* 的实际边成本同走 `costMillis`）；
  6. `PathFinderTest.matchesDijkstraOnCost:137` —— 两侧成本 3 vs 6 不等（预告外连带，同根因）。
- 判读：红点全部**同根**（costOf 的成本单位），靶子红在声明的断言行上 ⇒ 护栏自证成立。minStep（自己的 ×1000L 调用点）与 impassable（scale 之前 return empty）如预告仍绿。

## 四 判据三：全量门禁绿 ✅

rc=0 + 全绿日志：`task-13-evidence/gate-clean-verify.log`（终局遍，最终提交字节）+ `gate-numbers.txt`（逐项摘录）+ `gate-clean-verify-recon.log`（摸底遍，数字与终局遍一致）。逐项数字见 §一。

## 五 判据四：每条护栏的故意违规自证 ✅（G13 汇总）

**轮数口径**：证据目录 `rounds/*.kept` 文件数。Task 1~12 共 **46** 个 `.kept`；加本任务关账轮 m13v-1 = **47 轮**，其中**红 40 轮 / 未红 7 轮**（未红全部有实测理由，见下）。逐任务：

| 任务 | 轮数（kept 数） | 红点落点（:行号 = .kept 实测） | 未红/存活（如实） |
|---|---|---|---|
| 1 FieldDelta | 1 | m3t1v-1：`RegressionGuardsTest.R1_thereIsExactlyOneFieldDelta:402` ✓靶 | — |
| 2 IMPASSABLE 哨兵 | 2 | m3t2v-1 / -2：两侧互换各红 `ImpassableSentinelTest.oceanUsesTheImpassableSentinel:18`（等值两侧都钉死） | — |
| 3 PopulationSeries | 3 | m1：3 红同根（seed:39 / boundary:57 / multi:77）；m2：唯一 multi:77；m3：growthCarryingEventsIsRejected:110 | — |
| 4 SocialChangeSet | 5 | m3t4v-1：4 红（between 传错 base）；**终局 m3t4v-2：唯一红 `populationOrderFollowsInsertionOrder:76`**（补序用例后）；m3t4v-3：4 红（apply 不吃 delta） | **探针两轮存活**（m3t4v-2a 现状夹具 0 红、m3t4v-2b 加键至 5 仍 0 红）——实测否证"加键能救"，随后补序观察点杀掉：存活是过程不是失败 |
| 5 SocialResolver | 3 | m1：absentHex:89；m2：colonMapIdKeepsQuotesInCanonical:159；m3：wrongSliceTypeThrows:143 | —（m2 的 hex 断言因 JUnit 首败即停未单独执行，台账按"1 实测 + 1 同机制"如实记） |
| 6 Unit/UnitState | 4 | 每轮恰 1 红：m1 parentAndPositionRejectEvents:50；m2 legalReparent:96（合并图）——**cycleAcrossUnitsThrowsAtConstruction 仍绿（反直觉结果如实记录，正是"不得用合并图"的判别力证据）**；m3 ownPositionWinsOverParent:124；m4 parentPointingToItselfThrows:90 | — |
| 7 UnitChangeSet | 4 | m1：4 红（对调）；m2-A：2 红（组件数 + mutate 抛）；m2-B：changeSetHasExactlyOneComponent:81 仍红（**every…由红转绿＝泄漏已验证**）；m3：唯一红 unitOrderFollowsInsertionOrder:87 | — |
| 8 MovementCost | 4 | m1'：stepCostsMatchTheFrozenFixture:21；m2：impassableTerrainHasNoCost:29；m3（fix-1 重跑）：allImpassableMapHasZeroLowerBound:73 | **m1 存活**（Math.round 形态＝等价变体：`v=moveCost×1000` 恒使 +500 不进位；1 002 990 对扫描 0 分叉，`m1-scale-scan.txt` 在库）——实测结论，非失败 |
| 9 PathFinder | 4 | m1'（×1000）：2 红（aStarDetours:121 + matchesDijkstra:137）；m2（删平局项）：红 sameInputTwice:160（对称夹具钉死精确路径，决定论护栏有真判别力） | **m1（×2 高估）存活**（校正夹具上 ×2 仍最优，与预判一致）；**m3（删 closed 判重）存活**（一致性下它是性能护栏）——均实测 |
| 10 UnitMoves | 5 | 补边界用例后重跑 m1：唯一红 exactBudgetArrivalIsArrived:81；m2：mobilityChange:141；m3：impassableNextStep:95；m4：noRouteOrEarlier:150。**补测后零存活** | **m1 首轮存活**（m3t10v-1，8 测试世界：at=23 仍 ARRIVED）——正是它逼出 R-10-b 的等值边界补条 |
| 11 UnitOperations | 6 | m1 createRejectsDuplicateId:63；m2a 两 disband 用例（同根因）；m2b 唯一 disbandIsTimeSensitive:219；m3 planRouteRequires:179；m4 placeAtClearsInTransitRoute:154；m5 **3 红**（reparentAppends:96 + reparentOntoItself:114 + disbandIsTimeSensitive:217——预告外连带如实记录：append 是新父值进入对象的唯一载体） | — |
| 12 UnitResolver | 5 | m1：2 红（R13 靶 chainFormCanonicalisesToTheId:94 + canonical 连带 ：108）；m2：唯一红 chainFollowsTheParentAtTheQueryTime:159；m3：唯一红 chainWithMultipleHits:108；m4：equipmentResolvesOnlyWhenTheNameExists:133 | **m3b 存活**（"根候选改所有单位"：唯一分叉输入"链首非根名"未被测，R-12-c 预言在先）——已知未测面，如实存档 |
| **13 关账（本任务）** | **1** | **m13v-1：6 红，靶子 criterionTwoArithmeticMatchesTheSpecTable:73（§三.3）** | — |

台账文字与证据目录的一致性核对：Task 10 台账写"四轮红点"、证据目录有 **5** 个 `.kept`——并列如实：台账按**变异体**计（m1/m2/m3/m4），证据按**轮次**计（m1 在补边界用例前/后各跑一轮）。其余任务两口径一致。

## 六 CLAUDE.md 与计划回填的落点（R-13-d / R-13-e）

- **CLAUDE.md**（只动两处）：「当前状态」表在 M2 行后**新增 M3 行**（✅ 已完成（13/13，2026-09-17）+ spec/计划/台账路径 + 四条判据核对结论 + 挂起项摘要）；「推送状态」行更新为本分支 `feat/m3-social-unit-simos`。其余段落未动。
- **M3 计划**（只追加）：文末（计划自审之后）新增 `## 执行期取代说明汇总（Task 1~12 关账时回填）` 一节，**七条**索引（Task 4 `isSameAs` / Task 6 `legalReparent` 夹具 / Task 8 m1 前提 + minStep 边界 / Task 9 绕行夹具与数字 / Task 10 m1 前提 / Task 11 两处夹具断言 / Task 12 `.` 守卫），每条 = 计划原文所在 + 取代后写法一句 + 详情出处（brief/report/台账文件名）。**计划草图原文一字未改**。

## 七 我未能核实的（R-13-f，照实清单）

1. **`GameMap` 无 id** ⇒ `social:<mapId>` / `map:<mapId>` 的 mapId **只回显、不可校验**（spec 挂起项 1，M2 遗留同源）。核过的是"回显行为"，**没核过也无法核**"id 与地图的一致性"——该校验要等 GameMap 有 id。
2. **属性段地址不服务**：`social:…:population` / `:population_growth`、`unit:…:member` / `:speed` / `:hex` 的值读取与 Info 挂载**不在 M3**（spec 挂起项 2）——本轮只核了"空候选"口径，属性访问面整体未实现、未测。
3. **materialize 写回状态不在 M3**（spec 挂起项 3）：`UnitMoves.evaluate` 是纯函数，"抵达点写回 position 段 + 清空 movement"的语义归 M4；**写回路径一行都不存在，谈不上核过**。
4. **人口 cache 未做**（spec 挂起项 5）：`PopulationSeries` 每次查询重算；性能面（重算代价）**无任何实测数据**。
5. **A\* 规模未测**（spec 挂起项 9）：v1 无权重缓存、无双向搜索；大地图（数十万格）性能**零实测**。
6. **A\* 决定论的跨 JVM / 跨实现面**（执行期新发现）：`sameInputTwiceGivesTheSamePath` 与 m2 平局项护栏只在**同进程同 JVM** 下实测；换 JVM、换 `PriorityQueue` 实现是否仍稳定，**本仓没有跨 JVM 的测试装置**，未测也不可能在单机单轮里穷证。
7. **链式定位与含点名字/ID 的取舍**（Task 12 取代说明的余面）：删 `.` 守卫后，"名字本身含 `.`" 的单位会按 `\.` 拆段进链式匹配——该形态**没有提交在案的用例**（`u-ghost` 连带核过的是"根层无人叫此名"）；含点名字是只走 ID、还是裁移义语法，留 M4（spec 挂起项 8）。
8. **m3b 的存活面**（Task 12，执行期实测结论）：当**链首是非根名**时"根筛无父"与"全量筛"分叉——这个输入**没有被任何提交用例覆盖**，故 m3b 存活是**已知未测面**而非"已证等价"；要杀它需扩用例面，本轮按裁定不做。
9. **`Route` / `Movement` / `UnitSnapshot` 构造守卫的测试覆盖弱**（Task 6 关切的余面）：三者构造期校验（如 `Movement` 的路线非空 / `Route` 相邻性依赖）在 Task 6 时无直接变异靶子；后续 Task 8/10/11/12 压到的是**消费侧**行为，**构造守卫本身至今没有逐条的变异自证**——它们坏了会不会红，属推导推断，未实测。
10. **R-13-b 补条第三断言（remaining=5000）的独立判别力**：它与第一/第二断言同吃 m13v-1 一个靶子，**没有为它单独跑过变异**（"同一靶子已覆盖"是推断不是实测）；若未来 `evaluate` 的 remaining 公式单独漂移而成本式不漂，本条是否独立红，未验证。
11. **Task 10 m1 的编译失败日志是复现件**（Task 10 关切沿承）：`pre-implementation-compile-failure.log` 系按记录复现，非首轮原始留痕。

## 八 关切清单（不挡关账）

- **存活变异 7 轮**全部是"实测 + 有理由"（§五未红列）：Task 4 探针×2（已被补序用例杀掉，终局红）、Task 8 m1（数学等价，无分叉输入）、Task 9 m1（×2 在校正夹具上仍最优）、Task 9 m3（性能护栏）、Task 10 m1（补测前，随后红）、Task 12 m3b（已知未测面）。
- 判据二的终局数字以**终局门禁**（§一）为准；两遍门禁数字一致（156/248/30/68/15、BugInstance ×5、ERROR 0、WARNING 1）。
- 工作树里 `.omo/` 为先前已存在的未跟踪目录，未触碰、未入库；实验室变异全程在 `/tmp` 副本上做，工作树只进过 R-13-b 的一条新用例（`git status` 实证，`m13v-1.kept` 末段在案）。
