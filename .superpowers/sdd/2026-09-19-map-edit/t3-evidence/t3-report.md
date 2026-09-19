# M8 T3 报告 —— `map.SetTerrain`（块感知）

> 分支 `m8/t3`（worktree `.claude/worktrees/m8t3`）；基线 `16e8c13`。证据全在 `.superpowers/sdd/2026-09-19-map-edit/t3-evidence/`。
> ★ 本报告只写**实测值**；推断单列（§8）。

## 0 一句话结论

`map.SetTerrain`（一条命令多个 hex）落在 `simos-map`，语义是**改块 + 整体重切分**（合并/拆分自然发生），**不逐格写地形**；
词表 fail-closed、图外/空集拒绝；变更集只有 `terrainBlocks` 非 `Unchanged`、`hexes`（高度）恒 `Unchanged`。
全量门禁 **890** 绿、SpotBugs 0×6、ERROR 0；真档 19441 格实测（副本）通过；4 个变异体全杀。

## 1 改动（逐文件 + 行）

### 1.1 新增（`simos-map`）

| 文件 | 内容 |
|---|---|
| `map/ops/TerrainOperations.java`（78 行） | `setTerrain(GameMap, Set<HexCoord>, String) → MapChangeSet`。校验序：判空 → `TerrainCatalog.of`（词表 fail-closed）→ `hexes` 非空 → 每格 `base.hexes().containsKey`。目标格按 `HexCoord` **自然序**覆盖进 `base.terrainIndex()` 的副本，再 `TerrainBlocks.split(...)` **整体重切**，最后 `MapChangeSet.between(base, base.withTerrainBlocks(next))`（触发 `GameMap` 构造期的分割不变式）。 |
| `map/spi/SetTerrainHandler.java`（55 行） | `type()="map.SetTerrain"`；`MapSnapshots.of(state).map()`（装配故障当场炸）→ `MapPayloads` 解析 → 调 `TerrainOperations`；`IllegalArgumentException` 折 `HandlerOutcome.Rejected`。 |
| `map/spi/MapPayloads.java`（95 行） | map 命令共用的载荷解析（`parse`/`requireText`/`requireHexes`），坐标形 `{q,r}`（与 `unit.PlaceAt` 同形）。空数组在**形状层**合法，由领域层判"至少一格"。 |
| `map/spi/MapSnapshots.java`（26 行） | 从 `SimulationState` 取 `map` 切片，非 `MapSnapshot` 即 `IllegalStateException`（与 `UnitSnapshots` 同口径）。 |

### 1.2 修改

| 文件 | 改动 |
|---|---|
| `simos-app/.../Shell.java` | import `SetTerrainHandler`；handler 列表首项加 `new SetTerrainHandler()`（装配处注册，9 个 handler）。 |
| `simos-app/src/test/.../SimosToolsTest.java` | `EIGHT_COMMAND_TYPES` ⇒ `EXPECTED_COMMAND_TYPES`（+`map.SetTerrain`）。 |
| `simos-app/src/test/.../McpCoverageTest.java` | 预期类型 9 个 + `map.SetTerrain` 最小载荷（`{"hexes":[{"q":1,"r":3}],"terrain":"plains"}`）；head 断言 9→10、advance 10→11、fork 11。 |

### 1.3 新增测试

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `simos-map/.../ops/TerrainOperationsTest.java` | 11 | 改 1 格/多格、合并、拆分、分割不变式 + 块表==`split(terrainIndex())`、块键规范全序、确定性逐字节、词表外 5 例、词表内 7 类全接受、空集、图外。 |
| `simos-map/.../spi/SetTerrainHandlerTest.java` | 7 | type、多格 Applied、去重、词表外/图外/空集、7 种坏载荷形态。 |
| `simos-core/.../MapSetTerrainEndToEndTest.java` | 3 | 真 CoreSimos：多格提交+重放+逐值；两次独立运行逐字节；负例三连不留 revision。 |

★ 未改 `TerrainBlocks`/`BlockId`/`TerrainBlock` 的模型（T6 定稿）；未给 `HexCell` 加回 `terrain`；未加依赖；未动前端。

## 2 重切分算法说明

- **不逐格写**：`HexCell` 只剩 `height`（编译期即挡）；地形唯一权威是 `GameMap.terrainBlocks`。
- **覆盖**：取 `base.terrainIndex()`（派生、`LinkedHashMap`），按 `HexCoord` 自然序把 `hexes` 每格设为 `terrain`。
- **整体重切**：`TerrainBlocks.split(terrainByHex)` 做同地形**六邻连通分量 BFS**，块表存 `TreeMap`（`BlockId` 全序）⇒
  - **合并**：原被异地形隔开的同地形块，中间的格改成同地形后并入同一分量；
  - **拆分**：一整块里某些格改走 ⇒ 剩余同地形格裂成多个分量；
  - **P6**：无阈值、无散格，每一格恰属一块。
- **确定性**：起点按自然序、块表 `TreeMap`、`TerrainBlock.hexes` 自然序 ⇒ 同一输入逐字节相同。**无 HashMap 迭代序参与。**
- **提交前把关**：`GameMap` 构造期 `TerrainBlocks.requirePartition` 强制并集==全 hex、两两不交，失败消息精确到 hex。

## 3 端到端实测（真档副本，非合成）

真档 = `/tmp/m6-import-verify/test_integration` 的**副本**（原档 md5 跑前/跑后均 `2348b9365e5b107945a305d06fad8fab`；
checkpoint `1.json` 均 `9e13d8563adfc56a48d824190c8e8121`）。探针 `probe/T3RealArchiveProbe.java`（真 `CoreSimos` + 真 store + 真 codec）。

| 指标 | 实测值 |
|---|---|
| `hexCount` / `blockCount` | 19441 / 44 |
| 直方图（前） | `{low_hills=3330, mountains=886, ocean=4506, plains=10719}` |
| 提交载荷 | 前 10 个 hex（自然序 `-5_-59 … -5_-50`），`plains → ocean` |
| `submitResult` | `Committed[main@2]`，`elapsedMs=226.88` |
| `changedHexes` | 10 |
| `allTargetsTerrain` | true |
| `heightMismatches` | **0**（高度逐值不变，19441 格全查） |
| `partitionAfter` | OK（`requirePartition`） |
| 直方图（后） | `{low_hills=3330, mountains=886, ocean=4516, plains=10709}`（ocean +10 / plains −10，算术对上） |
| `blockCountAfter` | 45（改一格 ocean 从块中切出，+1 块） |
| `replayByteIdentical` | true（同坐标两次 `replay` 的块表 `toString()` 逐字节相同） |
| `twoRunsByteIdentical` / `twoRunsBlockIdSetsEqual` | true / true（两个独立副本各跑一次） |
| `blockTableHeadA` == `blockTableHeadB` | `[low_hills@-64_72, low_hills@-50_-10, low_hills@-42_67]` |
| 负例三连 | `Rejected[hexes 不得为空…]` / `Rejected[未知地形类型: forest]` / `Rejected[hex 不在图上: 9999_9999]`；`revisionsRowsUnchanged=true rows=2` |

★ `5817`/`5818` 全程未动（`ss -ltnp` 实测两者仍在监听，pid 399622/399624）；我只用了副本。

## 4 负例三连实测（单测层）

`MapSetTerrainEndToEndTest.negativePayloadsAreRejectedAndLeaveNoRevision`：三条各断言 `CommandResult.Rejected` +
理由片段 + `core.revisions(MAIN)` 行数不变 + `head` 仍为 `RevisionId(1)`。真档探针亦复现同一三连（§3）。

## 5 变异（4 轮 × 九道门禁，全杀）

装置 `mutants/mut-round.sh`（干净世界基线 → 变异体字节不同 → 白名单推成目标类名并清 `.class` → 断言 `COMPILATION ERROR=0` 且
`Tests run>=1` → surefire 报告 mtime 落本轮 → 红点落被保护断言 → `cp` 逐字节还原（绝不 `git checkout --`）→ 日志自指并先断言聚合 md5 非空）。

| m | 护栏 | 变异 | 红点（实测原文片段） | 结论 |
|---|---|---|---|---|
| m1 | 词表 fail-closed | `TerrainOperations` 删 `TerrainCatalog.of(terrain)` | `TerrainOperationsTest.rejectingTerrainOutsideTheCatalog`（Expecting code to raise a throwable）+ `SetTerrainHandlerTest.rejectsTerrainOutsideTheCatalog`（Applied 而非 Rejected，载荷 `forest` 被收） | **KILLED**（hits=2） |
| m2 | 必须重切分 | 只改"目标格所在块"的 terrain 字段、不重切 | `GameMap` 构造期 `地形块键 desert@1_0 与块内容不符（内容派生得 plains@1_0）`（分割不变式红） | **KILLED**（hits=3） |
| m3 | 确定性（规范全序） | 重切后用打乱序的 `LinkedHashMap` 装块 | `blockTableOrderIsTheCanonicalBlockIdOrder`（键序非 `BlockId` 全序） | **KILLED**（hits=1） |
| m3b | 确定性（`HashMap`） | `TerrainBlocks.split` 的块表 `TreeMap → HashMap` | `blockTableOrderIsTheCanonicalBlockIdOrder` | **KILLED**（hits=1） |

门禁逐轮实测：`clean_rc=0`、`clean_compilation_error=0`、`mut_compilation_error=0`、`Tests run: 18`、`restored_md5 == orig`、日志自指 md5 非空。
存活项：**无**。

## 6 门禁数字

`./mvnw clean verify`：**rc=0、890 条 = 170/290/45/131/158/96、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 行**
（日志 `logs/full-verify-final.log`）。

| 模块 | 基线（869） | T3 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | 未动 |
| MapSimos | 272 | **290** | **+18** | `TerrainOperationsTest` 11 + `SetTerrainHandlerTest` 7 |
| SocialSimos | 45 | 45 | 0 | 未动 |
| UnitSimos | 131 | 131 | 0 | 未动 |
| CoreSimos | 155 | **158** | **+3** | `MapSetTerrainEndToEndTest` 3 |
| SimosApp | 96 | 96 | 0 | `McpCoverageTest`/`SimosToolsTest` 改断言，条数不变 |
| **合计** | **869** | **890** | **+21** | |

## 7 与派单/spec 的分歧（源码为准）

1. **派单 §5 基线写 869 = 170/272/45/131/155/96** —— 与源码一致（M9 T13/T14 终态），**未写错**。
2. **派单要求"m3：受影响的块集合用 `HashMap` 迭代序重切"** —— 我按字面实现了两条：m3（结果块表打乱序）与
   m3b（`TerrainBlocks.split` 内部块表 `HashMap`），**两条都被杀**。★ 注意：`TerrainBlocks.split` 的**起点遍历**若改成
   `HashMap` 也不改变结果（BFS 覆盖的是同一连通分量，分量内顺序由邻接决定），故只有**块表容器**那一处是可表达的变异。
3. **`simos-core` 未改动生产码**（只有 test 侧新增 `MapSetTerrainEndToEndTest`）——派单把 `simos-core` 列为可能改动模块，
   实际 Core 按 ADR-1 只转发信封，命令规则全在 `simos-map`，故 **core main 零改动**（这也是铁律 4 的预期形态）。

## 8 实测 vs 推断

| 项 | 类型 | 依据 |
|---|---|---|
| 真档 19441 格 / 44 块 / 提交后 45 块 / 直方图算术 | **实测** | `probe/real-archive-probe.out` |
| 原档 md5 前后不变、5817/5818 未动 | **实测** | `md5sum` + `ss -ltnp` |
| 端到端地形逐值、高度 0 mismatch、分割不变式 | **实测** | 探针 + `MapSetTerrainEndToEndTest` |
| 两次运行逐字节相同 | **实测** | 探针 + `TerrainOperationsTest.rebuildIsByteIdenticalForTheSameInput` |
| 负例三连不留 revision | **实测** | 探针 + `MapSetTerrainEndToEndTest` |
| 4 变异全杀 + 九门禁 | **实测** | `logs/m{1,2,3,3b}.log` |
| 全量 890 绿、SpotBugs 0×6 | **实测** | `logs/full-verify-final.log` |
| 跨 JVM 的块表字节稳定 | **推断** | `BlockId` 全序 + `TreeMap` + `hexes` 自然序 ⇒ 生成确定；但**未跨 JVM 实测**（同 JVM 两次已实测） |
| 超大图（19441 格）重切的**单独耗时** | **推断** | 只测到"提交含重切共 226.88ms"；未剥离 store/replay 分量 |
| `SetTerrain` 经 GUI/MCP 端点 | **推断** | `McpCoverageTest` 经真 MCP 提交 `map.SetTerrain` 已实测 committed；但 GUI 前端未接（UI 归 T8） |

## 9 我未能核实的

1. **跨 JVM 字节稳定**：同 JVM 两次逐字节相同已测；不同 JVM 的哈希盐不参与（无 `HashMap` 迭代序进结果），但**未跨进程实测**。
2. **重切单独耗时**：226.88ms 是 `submit` 全链（handler+重切+落盘+事件），未单独量重切。
3. **GUI 写端点**：`/api/command` 发 `map.SetTerrain` 未跑（前端调色板归 T8）；只证了 MCP 工具面可达。
4. **T2 前端测试门禁**：本单未碰（T2 独立任务）。
5. **极端碎片化图**（上万块）的重切代价未测。

## 10 证据索引

```
t3-evidence/
  logs/full-verify.log                 # 首次全量（889，测试集后改，作废）
  logs/full-verify-final.log           # 最终全量（890）
  logs/m1.log / m2.log / m3.log / m3b.log   # 4 轮变异（含字节自指与红点原文）
  mutants/mut-round.sh                 # 变异装置（九门禁）
  mutants/m{1,2,3}.TerrainOperations.java / m3b.TerrainBlocks.java
  mutants/orig/{TerrainOperations,TerrainBlocks,BlockId}.java
  probe/T3RealArchiveProbe.java        # 真档探针
  probe/real-archive-probe.out         # 真档实测结果
  probe/cp.txt                         # 探针 classpath
```
