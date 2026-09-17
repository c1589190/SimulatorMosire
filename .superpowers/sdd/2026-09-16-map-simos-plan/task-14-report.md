# Task 14 报告 —— L1~L9 逐条守卫 + 逐条变异自证

**BASE**：`49f8445`（Task 13 关账提交）　**分支**：`feat/m2-map-simos`
**交付**：两个测试文件（工作树未提交，见文末"提交物"）＋ 9 轮变异留痕（`task-14-evidence/`）

> ★ 执行经过（对账用）：本任务先后派了两个子 Agent 执行（第 1 个 glm 路由 429；
> 第 2 个 deepseek 路由被看门狗判死），**由控制器接手完成**——两份测试文件的收尾、9 轮重跑、
> 复跑自证、门禁与本文都出自控制器。留痕里的 `/tmp/m14lab` 是装置目录，产物已归档进 evidence。

## 1. 逐条守卫（用例名 / 落在哪个类 / 判据形态）

| L | 用例 | 类 | 判据形态 |
|---|---|---|---|
| L1 | `L1_edgesSurviveRoundTripOnNonRoot` | `RegressionGuardsTest` | **行为**：只有 `edges` 一个组件变的图对，`MapChangeSet.between → apply` 往返 `== target`；且 `cs.edges()` 不是 `Unchanged` 而是 `Patch`（增删并存）；其余 6 组件一律不进 diff |
| L2 | `L2_hexCellHasNoConnectivityField` | `RegressionGuardsTest` | **结构**：反射——`HexCell` 组件恰 `[terrain, height]`（冻结字面量） |
| L3 | `L3_thereIsExactlyOneDirectionTable` | `RegressionGuardsTest` | **结构＋扫描**：enum 且恰 6 项；`opposite/next/prev` 三条代数恒等式；`int[][]` 在 `simos-map` + `simos-util` 的 `src/main` 命中 **0** |
| L4 | `L4_thereIsExactlyOneRegionType` | `RegressionGuardsTest` | **扫描×2**：`simos-map/src/main` 声明类型名不含 `province/territory/zone`；`region` 包顶层类型集合恰为 `{Region, RegionId, RegionIndex, RegionBoundary, RegionMeta}` |
| L5 | `L5_regionOfIsIndexedNotScanned` | `RegionIndexGuardTest` | **计数注入**：`regionOf` 恰触发 **1** 次 `Map.get`（100 region × 1000 hex 冒烟＋`CountingMap` 注入；构造期 0 次） |
| L6 | `L6_hexCoordIsTheOnlyCoordinateType` | `RegressionGuardsTest` | **扫描＋结构**：`"_"` 剔注释后在 `simos-map/src/main` 恰 **1** 处且落在 `hex/HexCoord.java`；`HexCoord` 组件恰 `[q, r]` |
| L7 | `L7_heightAndSeedSurvivePersistence` | `RegressionGuardsTest` | **行为**：JSON 往返整图相等；`height`/`seed` 逐字段活着；键序断言只落在**构造保序**处（`GameMap` 插入序、词表定序）；生成图往返；同 seed 生成两次相同 |
| L8 | `L8_gameMapHasNoTwelveArgConstructor` | `RegressionGuardsTest` | **结构＋扫描**：`GameMap` 构造器恰 1 个、形参 8；`new GameMap(` 逐文件调用点冻结白名单（`GameMap` 9 / `MapChangeSet` 1 / `MapGenerator` 1） |
| L9 | `L9_thereIsExactlyOneTerrainCatalog` | `RegressionGuardsTest` | **结构×2＋扫描**：`KEYS` 恰 7 项且定序；阈值并集恰 `[0,1]`、逐项相接、两两不重叠；四个独占 key 不外泄（不剔注释，取更强一侧） |

扫描底座（R-14-c）：仓库根从 surefire 工作目录（模块根 `simos-map/`）向上找**自己**的
artifactId 是 `simos-parent` 的 `pom.xml`（判据：名字出现在 `</parent>` 之后——首跑实测就因
`<parent>` 块把模块根误判成根），**找不到即 fail**；`javaFilesUnder` 对**空目录 fail**（防路径写错
恒真）。四条扫描的"扫到文件数 > 0"自证因此都在。

## 2. 九轮变异（每轮：红点原文 + 自证头）

装置：`task-14-evidence/run.sh` + `mutate.py`（在 `/tmp/m14lab/repo` 的副本上变异，**工作树一字未动**）。
每轮顺序 = 干净世界校验（副本逐文件对 113 个 `.java` 的 md5 清单，**含"清单之外的新增 .java = 0"**）
→ 改前跑（护栏必须绿）→ 变异 → md5 自证（修改：与原件字节不同；新增：落盘非空）→ **并集自证**
（实际`修改 ∪ 新增` == 声明集合）→ 改后跑 → 断言 `COMPILATION ERROR count = 0` 且测试真的跑过。

**九轮逐字相同的自证头**（每轮 `.kept` 里都有这一段，逐字一致）：

```
干净世界 OK（副本逐文件与 /root/SimulatorMosire 的 md5 清单一致，共 113 个 .java；清单之外的 .java = 0 个，上一轮新增的变异文件已清）
...
改前 COMPILATION ERROR count = 0
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0     ← simos-util
[INFO] Tests run: 245, Failures: 0, Errors: 0, Skipped: 0     ← simos-map（改前基线 401 条全绿）
自证：修改的文件与原件字节不同 / 新增的文件已落盘且非空 OK
自证：实际（修改 ∪ 新增）== 声明集合，别无其它改动 OK
```

| 轮 | 变异（病灶形态） | 原件 → 变异体 md5 | 改后 | 红点（原文） |
|---|---|---|---|---|
| m14v-1 | `MapChangeSet.between` 摘掉 edges 比较 | `ca83cb16…` → `065bc77c…` | 245 中 5 红 | `RegressionGuardsTest.L1_edgesSurviveRoundTripOnNonRoot:139 [edges 必须真的进了 diff（不是 Unchanged）]`　`Expecting value to be true but was false`；连带既有用例 `MapChangeSetTest.applyRebuildsTargetExactly:378`、`emptyDiffStillEntersApply:442`、`everyComponentIsComparedIndependently:326->assertOnlyComponentChanged:344`、`RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:88` |
| m14v-2 | `HexCell` 加回第三组件 `edgeTags`（旧签名二级构造器保留，不致编译错） | `8df1ac9a…` → `68b4347c…` | 245 中 2 红 | `RegressionGuardsTest.L2_hexCellHasNoConnectivityField:165 [HexCell 不含任何连通性字段（主存储只有 GameMap.edges 一份）]`；连带既有用例 `HexCellTest.hasNoConnectivityField:61` |
| m14v-3 | 新增 `hex/LegacyDirTable.java`（第二份 `int[][] DIRS`） | （新增）→ `4366af38…` | 245 中 1 红 | `L3_thereIsExactlyOneDirectionTable:196 [src/main 里不许有第二个 int[][] 方向表（HexDirection 是唯一一份）]`　`Expecting empty but was: {"…/hex/LegacyDirTable.java"=1L}` |
| m14v-4 | 新增 `region/Province.java`（第二个 region 概念） | （新增）→ `9d24a5f9…` | 245 中 1 红 | `L4_thereIsExactlyOneRegionType:217 [simos-map 里不许再长出 Province/Territory/Zone 任何一个名字的第二个 region 概念]`　`Expecting empty but was: ["Province"]` |
| m14v-5 | `RegionIndex.regionOf` 改线性扫描（答案不变） | `44dea904…` → `eb39e40b…` | 245 中 2 红 | `RegionIndexGuardTest.L5_regionOfIsIndexedNotScanned:56 [一次 regionOf = 恰一次 Map.get（线性扫描是 0 次或 N 次，都不是 1）]`　`expected: 1 but was: 0`；连带既有用例 `RegionIndexTest.regionOfIsConstantTime:82` |
| m14v-6 | `City` 加 `atKey()`（手写 `q + "_" + r`） | `b9a6fe30…` → `69429f54…` | 245 中 1 红 | `L6_hexCoordIsTheOnlyCoordinateType:244 [q_r 拼接全 simos-map/src/main 恰 1 处（多了就是第二份坐标串实现）]` |
| m14v-7 | `MapGenerator` 结果不带 spec（`defaults(0L)`） | `95eb6f4f…` → `85fcf821…` | 245 中 3 红 | `L7_heightAndSeedSurvivePersistence:299`　`expected: 42L but was: 0L`；连带既有用例 `MapGeneratorTest.specIsCarriedOnTheResult:66`、`generatedMapRoundTripsThroughChangeSet:147` |
| m14v-8 | `GameMap` 加 12 参数二级构造器 | `fc1431f9…` → `a114a3ad…` | 245 中 1 红 | `L8_gameMapHasNoTwelveArgConstructor:315 [GameMap 只有规范构造器（12 参数的复制形态不许回来）]`（实参列表里就是那个 12 参构造器） |
| m14v-9 | 新增 `terrain/BackupTerrainTable.java`（第二份词表） | （新增）→ `9329f068…` | 245 中 1 红 | `L9_thereIsExactlyOneTerrainCatalog:376 [四个独占 key 不得出现在 TerrainCatalog 之外的任何文件（连注释都算）]`　`Expecting empty but was: {"…/terrain/BackupTerrainTable.java 含 low_hills"=1L, …含 mountains=1L, …含 plateau=1L, …含 plateau_mountains=1L}` |

**判读**（"红了要问为什么红、没红要问为什么没红"）：

- 九轮的红点**都落在对应的 `L?` 用例上**；m14v-1/2/5/7 另有既有用例连带红——那是病灶同时破坏的
  旧行为，**不是充数**：每轮对应 `L?` 用例自己的断言都红了，且红的理由就是被保护的那行（上面的
  "红点原文"逐条可见：m14v-7 红的正是 `:299` 的 seed 断言 `expected: 42L but was: 0L`）。
- 九轮 `COMPILATION ERROR count = 0`、simos-map 均跑过 **25** 个测试类（改后）——红是断言红，
  不是构建挂在用例之前（m14v-2/m14v-8 特意做成**二级构造器**，旧调用点一个不动）。
- 原件 md5 与工作树逐一核对相符（`MapChangeSet`/`HexCell`/`RegionIndex`/`City`/`MapGenerator`/`GameMap`
  六个文件当场 `md5sum` 对比，全等）——变异体确实由这些字节改出。

## 3. L7 的 pass-1 作废与根因（flaky 的来龙去脉）

- **pass 1 作废**：原版 L7 断言了 `Region.hexes`（`Set.copyOf`）在 JSON 里的**字节序**，实测 flaky。
- **根因（当场复跑，20 个独立 JVM）**：`Set.copyOf` 的迭代序 = 散列槽位序，**不是内容的纯函数**
  ——探针 `rounds-pass1-flakyL7/SetOrderProbe.java` 今天重跑 20 次：第一组候选键翻转 **9/20**，
  第二组 0/20（输出见 `rounds-pass1-flakyL7/SetOrderProbe.out`）。pass-1 的 9 个 `.kept` 系旧夹具
  产物、与本表混装，按裁定整遍作废（只留探针与输出）。
- **修订（已在交付件里）**：L7 的字节序断言只落在**序由构造保证**的组件上——`GameMap` 的 7 个
  插入序 map（弃 `Map.copyOf` 是设计声明）与 `TerrainCatalog` 词表定序；`Region.hexes` 只做结构相等。
  修订已写进用例 Javadoc（"★ 保序断言的边界"）。
- **不 flaky 的自证**：见 §4。

## 4. 连跑自证（同一份交付件，20 个独立 JVM）

`./mvnw -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RegressionGuardsTest,RegionIndexGuardTest test`
连跑 **20** 次（每次一个独立 surefire JVM，日志 `task-14-evidence/l7-flaky-recheck.out`）：

- **20/20 rc=0**、`BUILD SUCCESS` **20** 次、`BUILD FAILURE` **0** 次；
- 每次 `Tests run: 8, Failures: 0`（RegressionGuardsTest）+ `Tests run: 1, Failures: 0`
  （RegionIndexGuardTest），**九条守卫 × 20 次 = 180 次执行，零红**；
- 与 §3 的根因对照：修订前那条字节序断言在 20 个独立 JVM 里有 9/20 会翻（探针实测），
  修订后同口径 **0/20**——flaky 不是靠运气躲过去的。

## 5. 门禁

当场执行，日志 `task-14-evidence/gate-clean-verify.txt`（2026-09-17T20:38:59+08:00 起）：

- `./mvnw -q spotless:apply` → **rc=0**；**两个测试文件未被改写**（前后 md5 一致，见下）——§4 的
  连跑证据对**提交字节**继续有效。
- `./mvnw -q verify` → **rc=0**、`[ERROR]` 行数 **0**（硬门禁：Spotless + Checkstyle + SpotBugs +
  enforcer + Surefire 全链）。
- 旁证（同日志）：surefire 报告 util 17 / map 26 / core 1 份；`spotbugsXml.xml` mtime
  `20:41:16`（map）、`20:41:50`（core），都落在本次门禁窗口内。
- 注：`-q` 不打印用例汇总行，用例数字见 §2 自证头（改前 156+245 = **401 全绿**）与 §4 的 20 连跑。

## 6. 评审（控制器自读 diff）

两份文件被逐行读过（`RegressionGuardsTest` 581 行、`RegionIndexGuardTest` 76 行），结论：判据形态与
R-14-b 逐条对上，无承重缺陷。两条**非阻塞观察**记录在案：

1. `isCommentLine` 只剔**整行**注释（行尾注释仍计数）——方向是**更严**（多算 → 红，不会漏），且
   冻结字面量正是在此口径下量得，L6/L9 的"恰 1 处""零命中"与之自洽。
2. L7 的 JSON 键序断言隐含依赖 Jackson `ObjectNode` 内部 LinkedHashMap 保序——实测已过；
   属第三方行为的**假设**，记录在 §7。

## 7. 我未能核实的

- **扫描不覆盖 `src/test`**：四条源码扫描（L3/L4/L6/L8/L9）只读 `src/main`——测试里再写一份
  `q + "_" + r` 或 `int[][]` 表**不会被抓**。这是 spec/R-14-c 的口径（"主源码"），我核实了"主源码里
  没有第二份"，**没有**核实"测试里也没有"。
- **扫描范围只含 `simos-map` + `simos-util`**：`simos-social`/`simos-unit`/`simos-core` 当前无
  map 领域实现，L3 的 `int[][]` 扫描未覆盖它们；将来这两个模块长出方向表不会被本任务守卫抓到。
- **`GenerationSpec.defaults(0L)` 与"真种子 0"无法区分**：m14v-7 的改写正是靠这一点红的；反过来说
  "用户显式用 seed=0 生成"与"丢了 spec"在断言层面同形——L7 用非零种子（42）避开该歧义，但
  "seed=0 的图"本身没有任何守卫。
- **L1 的"非 root"语义是类比**：GSimulator 的病灶是"对非 root 子节点写连通性"，simos 的对应物是
  "只有一个组件变的变更集"（R-14-b 的定形）；我核实了该形态的往返，**没有**（也无法）逐字节复现
  GSimulator 当年的失败现场。

## 8. Concerns

- **L8 的白名单会随合法新增调用点变红**（设计如此："新增即红"）——将来若真要加 `new GameMap(` 的
  合法调用点，必须同改测试白名单；这是**刻意**的摩擦，代价是每次都要一次人工裁决。
- **`Region.hexes` 的序不可依赖**已写进 L7 Javadoc，但它是**注释级**约束；若将来有人给
  `Region` 加"按序返回 hexes"的需求，得先换掉 `Set.copyOf`（或改成 `LinkedHashSet`/`List`）。
- **L5 的计数注入把 `regionOf` 钉成"恰一次 get"**：若将来 `regionOf` 合理地需要 2 次查表
  （如别名回退），这条会红——判定权归当时的控制器，本任务不为假设的需求留口。
- 证据目录 `task-14-evidence/` 在 `.gitignore` 下（`.superpowers/**`），以 `git add -f` 入库。

## 9. 提交物

- `simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java`（581 行，md5 `1e2ae91a2ef7a9ee61636f85a4b859ce`）
- `simos-map/src/test/java/io/mosire/simos/map/region/RegionIndexGuardTest.java`（76 行，md5 `b4f675cb578f3c9c5cd9fd9e3921b5ec`）
- `task-14-evidence/`：`run.sh`、`mutate.py`、`rounds/m14v-1..9.kept`、`rounds-driver.out`、
  `l7-flaky-recheck.out`、`rounds-pass1-flakyL7/{SetOrderProbe.java, SetOrderProbe.out, jprobe.jsh}`、
  `gate-clean-verify.txt`（§5）
