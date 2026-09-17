# M3 Task 9 报告：`PathFinder`（A\*）+ R8 对拍与决定论

**分支** `feat/m3-social-unit-simos`，BASE `4780d47`。提交 A：`4355e9e`（实现 + 测试，2 文件 337 行）；提交 B：本报告 + 证据。

## 〇、交付面

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java` | `findPath(GameMap, HexCoord, HexCoord, Unit, MovementCost) → Optional<List<HexCoord>>`。A\*：`h(n) = cost.minStepCostMillis(unit, map) × n.distanceTo(goal)`（**启发与成本同源**，C2）；优先队列比较器 `(f, h, q, r)` **全序**（spec §4.4 冻结形态）；`bestG` / `cameFrom` / `closed` / `PriorityQueue`，一致性 ⇒ 首弹即最优、无需 reopen |
| `simos-unit/src/test/java/io/mosire/simos/unit/move/PathFinderTest.java` | 6 条用例：绕行最优性（1500 毫 + 精确路径钉死）、Dijkstra 对拍（`NoHeuristic` 包装只把 `minStepCostMillis` 归 0）、决定论（绕行图 20 次重跑 + **对称夹具** 20 次重跑 + 全序输出钉死 `[(0,0),(1,-1),(2,-1)]`）、`start==goal` 单元素、起/终点图外空、双孤岛不可达空 |

接口形态、搜索结构、平局定序均按 spec §4.4 与计划 Step 3，无偏离（夹具与数字按 R-9-a 就地校正，见下）。

## 一、控制器扫描结论逐条回执

### R-9-a（★ 夹具几何不成立 + 数字错位——两处都已就地校正）

1. **几何**：实测确认 `distanceTo((0,0),(1,1)) = 2`（cube 公式），计划的绕行第一步不成立。最小校正已落：`H21 = (2,1)` 由 **`H01 = (0,1)`（平地）** 取代，绕行 = `H00→H01→H11→H20`，三步 `distanceTo` 全 = 1（实现落地后由 `aStarDetoursAroundExpensiveTerrain` 的精确路径断言 `containsExactly(H00, H01, H11, H20)` 持续钉住）。图仍 5 格。取代说明写在测试源码常量 `H01` 与 `detourMap()` 的 Javadoc 里（计划原文未抹）。
2. **数字**：`MoveFixture.unit()` 的 `mobilityPerMille = 500` ⇒ `costMillis = moveCost × 500`（平坦 500、山 5000），从冻结规则重算：直线 `5000 + 500 = 5500` 毫、绕行 `3 × 500 = 1500` 毫。断言与注释均按 **1500 / 5500** 写，取代说明在 `detourMap()` 的 Javadoc。`MoveFixture.unit()` **一字未动**。

### R-9-b（m1 高估启发：两级倍数各自实测）

| 级别 | 变异 | 实测结果 | 与预测对照 |
|---|---|---|---|
| ×2 | 两处 `minStep × d → minStep × 2 × d` | **存活**（红点数 = 0，全绿） | 与 R-9-b 预测一致。追踪核实：绕行首格 f=2500 < 直线山口 f=6000 先展开，终点 g=1500 f=1500 最先弹出 ⇒ 仍得最优 |
| ×1000 | 同两处 → `minStep × 1000 × d`（近贪心） | **红 2 例**：`aStarDetoursAroundExpensiveTerrain:121`（`expected: 1500L but was: 5500L`——山口被优先弹出，返回直线）+ `matchesDijkstraOnCost:137`（A\* 5500 ≠ Dijkstra 1500） | 与 R-9-b 预期完全一致，红点正是"高估破坏最优性"要抓的两个类 |

两级倍数的测量都入证据（`rounds/m3t9v-1.kept`、`rounds/m3t9v-2.kept`）。

### R-9-c（m2 决定论：红了，且红点说明决定论靠的是平局项本身）

变异：比较器删掉全部平局项（只留 `f`）。R-9-c 预计"很可能存活"，**实测红 1 例**——`sameInputTwiceGivesTheSamePath:160`，红的是**对称夹具的精确路径断言**：

```
Expecting actual:  [0_0, 1_0, 2_-1]
to contain exactly: [0_0, 1_-1, 2_-1]
```

理由：无平局项时两格 `(f,h)` 全相等、比较器判 0 ⇒ 堆根保持**插入序在前**的 `(1,0)`（`HexDirection.ALL` 枚举序 E 先于 NE），于是 `cameFrom[goal]` 记到 `(1,0)`，输出换成另一条等成本路径；全序 `(f,h,q,r)` 下 r 升序先弹 `(1,-1)`，输出钉死为 `[(0,0),(1,-1),(2,-1)]`。**"重跑 20 次相等"断言未红**（同进程内堆对固定插入序列确实确定）——即 R-9-c 预测的存活形态真的存在于"重跑相等"这半边；对称夹具把跨实现的序**变成可观测输出**后才让变异现形。结论：决定论不只靠堆稳定性，靠的是全序平局项——该比较器是 spec §4.4 冻结形态，保留不动。若不补对称夹具（按计划原文只重跑绕行图），m2 将假绿。

### R-9-d（m3 去 closed 判重：未红，如实写）

变异：删掉弹堆后的整块 `if (!closed.add(...)) continue;`。**存活**（红点数 = 0，全绿）。理由：一致性保证首弹即最优，`bestG` 守卫（`known <= g` 则丢）把陈旧高 `g` 节点的重复展开全部挡掉，goal 首弹即返回同一条路径——判重在此设计里确实是**性能护栏**而非正确性依赖。计划 Step 5 预期的"对拍用例红（或路径成本变差）"未发生，如实记录，不假装。

### R-9-e（期望数字：逐项核过）

- 加实现前：编译失败（`cannot find symbol: variable PathFinder` ×多处，BUILD FAILURE）——唯一一次红 = 编译错 ✓
- 加实现后：`PathFinderTest` **6/6** ✓
- 基线 → 加后：util 156 / map 248 / social 30 / **unit 30 → 36**（`verify` 实测，另 core 15）✓
- 每轮变异：改前、改后 `COMPILATION ERROR count = 0`（8 份日志全查）✓

### R-9-f（形制）

`(f,h,q,r)` 全序**在**（`ORDER` 一处定义，m2 变异证明它有判别力）；图外起/终点 ⇒ 空；`start==goal` ⇒ 单元素；不可达 ⇒ 空；`NoHeuristic` 只覆写 `minStepCostMillis → 0`、`costMillis` 原样转发；搜索结构 = `bestG`/`cameFrom`/`closed`/`PriorityQueue`，无 reopen。

### R-9-g（装置）

`run.sh` / `mutate.py` 从 task-8-evidence 拷贝改制：`LAB=/tmp/m3t9lab`、`-pl simos-unit -am`、manifest util+map+unit（133 个 .java，含本任务两份新文件）、`ROUNDS_DIR=task-9-evidence/rounds`、`TARGET` = m3t9v-1(×2)/m3t9v-2(×1000)/m3t9v-3(m2)/m3t9v-4(m3)。实际失败类抽取 = 日志 `FAILURE!` 行 + simos-unit surefire-reports 摘要，原样保留。

## 二、四轮变异自证头与实测红点全列

每轮头四件套（详见 `rounds/*.kept`）：干净世界（rsync 全新副本、md5 清单逐文件一致、133 个 .java、清单外 .java = 0）→ 改前全绿（156/248/36，COMPILATION ERROR = 0）→ 变异体与原件**字节不同**（md5 并列在 .kept）+ 实际改动 ∪ 新增 == 声明集合 → 改后 COMPILATION ERROR count = 0 且 simos-unit 7 个测试类真的跑过。

| 轮 | 变异（目标均为 `PathFinder.java`） | md5 原件 → 变异体 | 实测红点 | 判读 |
|---|---|---|---|---|
| m3t9v-1 | h ×2 高估（两处） | `73fa842b…` → `（见 .kept，×2 轮）` | **无（红点数 = 0）** | 存活：×2 在本夹具仍最优，与 R-9-b 预测一致 |
| m3t9v-2 | h ×1000 近贪心（两处） | `73fa842b…` → `94f27e1d…`（×1000 轮值） | `aStarDetoursAroundExpensiveTerrain:121`（1500 vs 5500）、`matchesDijkstraOnCost:137`（成本不等） | 高估破坏最优性，双红全落被保护的行 |
| m3t9v-3 | 比较器平局项全删 | `73fa842b…` →（m2 轮值，见 .kept） | `sameInputTwiceGivesTheSamePath:160`（对称夹具精确路径 `[0_0,1_0,2_-1]` ≠ 钉死值 `[0_0,1_-1,2_-1]`） | 决定论靠全序平局项；"重跑相等"半边未红（同进程堆确定），已如实拆开写 |
| m3t9v-4 | 删 closed 判重（整块） | `73fa842b…` →（m3 轮值，见 .kept） | **无（红点数 = 0）** | 存活：`bestG` 守卫 + 一致性下判重是性能护栏，与 R-9-d 预测一致 |

（各轮变异体的完整 md5 对已逐字留在 `rounds/*.kept` 的自证段，此处不复制数值以免手抄失真。）

## 三、门禁数字

- `./mvnw verify`：**BUILD SUCCESS**；Tests run：util **156**、map **248**、social **30**、unit **36**、core **15**；`BugInstance size is 0` ×5。
- 迭代命令（brief 指定形制）全程使用：`./mvnw -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PathFinderTest test`。
- `spotless:apply` 在实验室**之前**跑：两份新文件首写即合规，无 diff；6/6 复跑仍绿。

## 四、关切 / 未能核实清单

1. **m1 存活是"夹具级"结论，不是"×2 永远安全"**：×2 高估只在当前 5 格校正夹具上实测存活；存在会让 ×2 分叉的图（直线与绕行成本比更接近 2:1 的形态），本任务未构造、未扫描。报告口径限于此夹具。
2. **m2 红点依赖精确路径断言**：对称夹具的全序输出 `[(0,0),(1,-1),(2,-1)]` 是**本实现 + 本 JVM** 的实测钉死（先堆追踪推导、后测试验证一致）。它是全序比较器的可观测后果，但若未来有人合法改动邻居枚举序（`HexDirection.ALL` 是"索引即边序号"的冻结序，不该动），该断言会红——红得有理，但要知道红因在序约定。
3. **计划原文未改**：计划 `2026-09-17-social-unit-simos-plan.md` 第 2680~2979 行的夹具与数字保持原样，校正只落在测试源码注释与本报告（派单说明规定的"取代说明就地追加"以测试 Javadoc 承担）。若需计划文本本身加取代标注，属控制器职权，本实现者未动。
4. **不可达用例比计划原形更强**：计划用"只留 H00"的单格图，那会让 goal 缺席图、退化成"goal 不在图"前置检查，不经过搜索循环；本实现改为**双孤岛**（H00、H20 都在图上、互不相邻）真走穷尽路径。注释里写明了与计划的差异及理由。
5. 无其他未能核实项。所有红/绿结论均出自本机实跑日志，关键日志已随证据入库：`pre-implementation-compile-failure.log`（实现前编译失败，`cannot find symbol` ×22、`COMPILATION ERROR` ×1、rc=1）、`post-implementation-6of6.log`（6/6 绿）、`gate-clean-verify.log`（verify 全绿 156/248/30/36/15 + SpotBugs 0×5）；四轮 before/after 全量日志留 /tmp/m3t9lab/logs（判读所需行已全部抽入 `rounds/*.kept`）。注：编译失败日志系提交 A 后按同一状态**重演取证**（移走 PathFinder.java + 清 simos-unit/target 再跑编译，杜绝陈旧 .class 干扰），非当时原始那份日志。
