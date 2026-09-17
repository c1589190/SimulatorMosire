# Task 15 报告 —— M2（MapSimos）关账

**分支**：`feat/m2-map-simos`　**判据权威**：M2 spec §1.2（`docs/superpowers/specs/2026-09-16-map-simos-design.md`）
**任务范围**：M2 计划 15 个任务里的最后一条 —— 全量门禁 + 逐条核四条判据 + 更新 `CLAUDE.md` + 报告。

> ★ 本报告的每个数字都是**当场跑出来/读出来的**；核不了的单列 §5。

## 1. 判据一：GSimulator 的 L1~L9 逐条有对应用例

九条守卫于 Task 14 交付（提交 `9152749`），本条逐条给用例名：

| L | 用例（全限定） |
|---|---|
| L1 | `io.mosire.simos.map.RegressionGuardsTest#L1_edgesSurviveRoundTripOnNonRoot` |
| L2 | `io.mosire.simos.map.RegressionGuardsTest#L2_hexCellHasNoConnectivityField` |
| L3 | `io.mosire.simos.map.RegressionGuardsTest#L3_thereIsExactlyOneDirectionTable` |
| L4 | `io.mosire.simos.map.RegressionGuardsTest#L4_thereIsExactlyOneRegionType` |
| L5 | `io.mosire.simos.map.region.RegionIndexGuardTest#L5_regionOfIsIndexedNotScanned` |
| L6 | `io.mosire.simos.map.RegressionGuardsTest#L6_hexCoordIsTheOnlyCoordinateType` |
| L7 | `io.mosire.simos.map.RegressionGuardsTest#L7_heightAndSeedSurvivePersistence` |
| L8 | `io.mosire.simos.map.RegressionGuardsTest#L8_gameMapHasNoTwelveArgConstructor` |
| L9 | `io.mosire.simos.map.RegressionGuardsTest#L9_thereIsExactlyOneTerrainCatalog` |

判别力证据 = Task 14 的 **9 轮变异**（每轮破坏一条 ⇒ 对应用例红，红点原文见
`task-14-report.md` §2 与 `task-14-evidence/rounds/m14v-*.kept`）。

## 2. 判据二：框选随机化与自动河流各有验收

★ 用例当场 `git grep` 确认存在于源码（下表行号即当场的）：

| 能力 | 交付 | ★ 验收用例（源码位置） |
|---|---|---|
| 自动河流 | Task 11 `213a8f9` | `RiverBuilderTest#riverIsAddressable`（:151）、`#branchesAreSeparatePathways`（:171）、`#sameSeedGivesSameRivers`（:184）、另 `#edgesAreConsistentWithPathways`（:223） |
| 框选随机化 | Task 12 `e62432a` | `RegionRandomizerTest#ratioIsRespectedStatistically`（:64）、`#sameSeedGivesSameResult`（:106）、`#onlyTargetRegionIsTouched`（:166） |

七条都在 §3 的 clean verify 里跑过（Surefire 汇总含 `RiverBuilderTest` / `RegionRandomizerTest`）。
两任务各自的变异自证：Task 11 **7 轮**、Task 12 **6 轮**（见 §4 表）。

## 3. 判据三：`./mvnw clean verify` 绿

当场执行（`2026-09-17T20:43→20:46`，日志 `task-15-evidence/clean-verify.txt`，完整日志 467 行在 `/tmp`）：

- **退出码 `rc=0`、`BUILD SUCCESS`**，总耗时 **03:12 min**。六模块全 SUCCESS：
  `simos-parent 13.457 s` / `simos-util 01:02` / `simos-map 01:19` / `simos-social 10.707 s` /
  `simos-unit 9.796 s` / `simos-core 16.895 s`。
- **`Tests run`：util 156 / map 245 / core 15 = 416 条，Failures 0、Errors 0**（social/unit 无测试）。
  ★ `simos-map` = **245**（Step 1 点名要的那个数）。
- **SpotBugs `BugInstance size is 0` ×5**（util/map/social/unit/core 各一次）。
- 新增守卫类在**本次门禁里真跑过**：`RegressionGuardsTest` `Tests run: 8`、
  `RegionIndexGuardTest` `Tests run: 1`，均 Failures 0。
- `^\[ERROR\]` 行数 **0**；`^\[WARNING\]` 行数 1 —— 当场核过出处：出在聚合 pom `simos-parent`
  （无源码，SpotBugs 无物可析），每次构建都有，**无害**。

## 4. 判据四：每条新护栏都有故意违规用例自证（G13）

Task 1~14 每一条都带**变异自证**（改前先绿 → 变异 → 对应用例红 → 自证头：干净世界 md5 清单、
变异体与原件字节相异、`COMPILATION ERROR` 计数为 0、测试真的跑过）。逐任务证据目录
`task-N-evidence/`：

| Task | 范围 | 自证轮数 | 证据 |
|---|---|---|---|
| 1 | `hex` 几何 | 15（`R-M1..M8` 8 轮 + 第二轮 `m1..m7` 7 轮） | `task-1-evidence/log-*.txt`、`mutants/` |
| 2 | `terrain` 词表 | 11（`m1..m9` + `m4a/m4b`） | `task-2-evidence/log-m*.txt` |
| 3 | `region` | 13（台账原话；目录 12 份 `log-m3v-*.txt`） | `task-3-evidence/` |
| 4 | `pathway` | 6（`m4v-1..6`） | `task-4-evidence/log-m4v-*.txt` |
| 5 | `map` 核心 record | 10 | `task-5-evidence/rounds/` |
| 6 | `MapChangeSet` | 7 | `task-6-evidence/rounds/` |
| 7 | 往返框架 | 9 | `task-7-evidence/rounds/` |
| 8 | `GenerationSpec` | 12 | `task-8-evidence/rounds/` |
| 9 | `TerrainClassifier` | 6 | `task-9-evidence/rounds/` |
| 10 | `MapGenerator` | 8 | `task-10-evidence/rounds/` |
| 11 | `RiverBuilder` | 7 | `task-11-evidence/rounds/` |
| 12 | `RegionRandomizer` | 6 | `task-12-evidence/rounds/` |
| 13 | `MapResolver` | 6 | `task-13-evidence/rounds/` |
| 14 | L1~L9 守卫 | 9 | `task-14-evidence/rounds/` |

★ **口径**：轮数 = 证据目录里变异运行的日志/轮次文件数（preR1 基线、recheck 一类**计入与否**按目录
实存；Task 1/2/3 是脚本期形态，用日志数），个别任务的台账另有文字数字（如 Task 3 的"13 轮"）——
两者不一致处**以证据目录为准**并如实并列。每轮的"红点是否落在声明靶子"由各任务关账节逐条核过
（Task 1~14 的关账节全在本台账）。

**按推导记录、未补轮的项**（各任务关账时逐条裁定，非遗漏）：Task 2 §"跨进程序迭代序"、
Task 4 §5.4/§5.5（构造器守卫的正向抛用例）、Task 5 同类项 —— 理由统一是"被守护字段在构造器内
无下游解引用，形态 2（NPE 消息同名遮蔽）够不着"，依用户红线**不为推得到的风险补装置**。

## 5. 我未能核实的

1. **L1 的"非 root"是类比**：GSimulator 的病灶是"对非 root 子节点写连通性"，simos 的对应物被定形为
   "只有一个组件变的变更集"——我核实了该形态的往返，**没有**逐字节复现 GSimulator 当年的失败现场。
2. **四条源码扫描不覆盖 `src/test`**（Task 14）：测试里再写第二份 `q_r` 拼接或 `int[][]` 表不会被抓。
   spec/R-14-c 的口径即"主源码"，我核实的是主源码。
3. **L3 的 `int[][]` 扫描只含 `simos-map` + `simos-util`**：social/unit/core 当前无地图实现，
   将来若长出方向表不被本守卫抓到。
4. **`seed = 0` 的图没有守卫**：`GenerationSpec.defaults(0L)` 与"用户显式 seed=0"在断言层面同形
   （L7 用非零 42 避开歧义）。
5. **跨机字节级复现只有单机证据**（Task 10 挂起观察）：构成条件已齐（两个随机源只吃 seed、枚举序显式
   排序、无环境量），但**没有**在第二台机器上跑过加强版 —— "可复现"目前是单机实测 + 推理。
6. **`GameMap` 无 id** ⇒ `map:<mapId>` 的 mapId 只回显、不可校验（Task 13 挂起项，见 §6）。
7. **旧 key → 新 key 的地形映射表**（M6 老存档导入器用）未裁决：4 个孤儿 + `plains` 双射 + `hills → low_hills`
   偏弱 —— U1 已作废旧表，本任务**不替 M6 编映射**（Task 2 的裁定）。

## 6. 挂起项与遗留（随 M2 关账一并记录）

1. **`GameMap` 无 id** ⇒ `map:<mapId>` 的 mapId 只回显不可校验（Task 13）；将来 `GameMap` 有 id 时应收紧。
2. **重建河流会整份覆盖 `EdgeTags`**（Task 11）：`edges` 的 Upsert 按边 key 换整份值 ⇒ 与既有标注
   **不合并**。合并在语义上属 Command 层；**M2 之后的编辑流实现者必须处理**。
3. **`PathwayGroup("river")` 无人注册**（Task 11 R-11-e）：消费方别假设生成变更集里有组定义。
4. **水系密集无阈值**（Task 11 R-11-f）：半径 6 图 30 条河；稀疏化阈值将来作 `GenerationSpec` 参数，
   届时夹具钉死的数字要重测。
5. **`Region.hexes` 的序不可依赖**（Task 14）：`Set.copyOf` 的迭代序随哈希槽位与 JVM 盐漂
   （当场 20 个独立 JVM：9/20 翻转）；若将来需要"按序返回 hexes"，得先换掉 `Set.copyOf`。
6. **`GenerationSpec.contourCacheMax` 保留、当前无消费者** —— **本任务裁决：保留**。理由：它是 spec
   §6.4 的正式组件，**删它 = 改 spec 形状**，超出 M2 关账的权限；且有守卫（`>= 1` 校验 +
   `contourCacheMaxZeroThrows` 用例 + m8v-8 变异自证）。代价 = 一个无消费者的参数（Javadoc 已注明）。
   若将来确认 contour 查询引擎归 CoreSimos，迁移属**那时的 spec 裁决**。
7. **`0.8660254`（√3/2 七位截断）**（Task 10）：沿自 GSimulator 且与 WebUI 渲染同款 ⇒ 将来标定几何须两处同改。

## 7. 提交物

- `task-15-report.md`（本文）
- `task-15-evidence/clean-verify.txt`（门禁留痕：rc、六模块、用例数、BugInstance、WARNING 出处）
- `CLAUDE.md` 的 M2 行更新（`🔄 进行中` → `✅ 已完成（15/15）`）
