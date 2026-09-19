# M9 T6 报告 —— P1 权威地形块（地基）

> 分支 `m9/t6`；基线 `0f56521`（M9 T3 合并）。本单一次性落地 `HexCell(height)` + `terrainBlocks` + 变更集/读写点/兼容回退，
> **编译耦合、一次绿**。所有证据在 `.superpowers/sdd/2026-09-19-map-perf/t6-evidence/`。

## 0 一句话结论

地形由**逐格**升为 **`Map<BlockId, TerrainBlock>` 权威块**，高度仍逐格；分割不变式在 `GameMap` 构造期强制、失败消息精确到 hex；
`BlockId` 确定性；旧存档经 `MapCodec` 就地迁移后**能读回且块正确**（真 19441 格旧档实测）。全量门禁 **867**（+17）全绿、SpotBugs 0×6。

## 1 改动（逐文件）

### 1.1 新增（`simos-map/.../map/block/`）

| 文件 | 内容 |
|---|---|
| `BlockId.java` | 记录 `(String terrain, HexCoord minHex)`；`of(terrain, hexes)` 取自然序最小 hex；`toString()=<terrain>@<q_r>`、`parse()`；`Comparable`（地形→最小 hex）。构造期拒空白/含 `@`。 |
| `TerrainBlock.java` | 记录 `(String terrain, Set<HexCoord> hexes, RegionBoundary boundary)`。**复用 `RegionBoundary`**（理由见 §3）；`hexes` 存为**自然序不可变集合**（`LinkedHashSet`，保证 `toString()` 逐字节可复现）；构造期重算边界并比对（同 `Region` 形制）；工厂 `of`。 |
| `TerrainBlocks.java` | `split(Map<HexCoord,String>)`：同地形六邻连通分量 BFS，**P6 全部建块**（无阈值、无散格）；块表 `TreeMap`（`BlockId` 全序）⇒ 确定性。`uniform(...)` 便捷。`requirePartition(hexes, blocks)`：并集/不交/键符内容，**失败消息精确到 hex**。 |

### 1.2 `simos-map` 主源码

| 文件:行 | 改动 |
|---|---|
| `HexCell.java:16` | `record HexCell(double height)`——`terrain` 移除，只剩高度（校验不变）。 |
| `GameMap.java:63` | record 加第 2 组件 `Map<BlockId,TerrainBlock> terrainBlocks`（9 组件）。`:88-89` 构造期 `TerrainBlocks.requirePartition`。`:119` `withHexes`、`:122` 新 `withTerrainBlocks`，9 个 with 方法。`:181` `terrainAt(HexCoord)`（稳定访问器，O(#块) 扫块；不存在即抛）、`:200` `terrainIndex()`（派生整表，批量读用）。 |
| `MapChangeSet.java:50` | 加 `FieldDelta<TerrainBlock> terrainBlocks`（8 组件）；`:68` `between` diff；`:96` `apply` 以 `BlockId::parse` 重建；`:109` `isEmpty` 计入。 |
| `MapCodec.java:39,63` | 注册 `BlockId` Map 键；`:166-236` **旧形状回退**（见 §4）。 |
| `MapGenerator.java:100-121` | `sampleAt` 改返回私有 `Sample(terrain,height)`；逐格产出高度 + 地形表，`TerrainBlocks.split` 建块。 |
| `RegionRandomizer.java:74-95` | 改地形 ⇒ 叠加到 `map.terrainIndex()` 后**整体重切**，产出 `terrainBlocks` 的 `FieldDelta.diff`；`hexes` 一律 `Unchanged`（高度不动）。 |
| `RiverBuilder.java:96,131` | 读地形走 `terrainIndex()`；变更集给 `terrainBlocks` 补 `Unchanged`。 |

### 1.3 读点（表示法不泄漏）

`TerrainMovementCost.java:34,43`（`terrainAt`/`terrainIndex`）、`ApiViews.java:159-162,241`、`GuiServer.java:390`、
`ToolSupport.java:307-310`、`MapHexTool.java:77`、`DemoWorld.java`（`TerrainBlocks.uniform` 建块）。
★ `webui/**` 未动（overview 仍发 `terrain` 字段）。

### 1.4 既有护栏按新语义改（不得恒真）

- `RegressionGuardsTest.L2`：`HexCell` 组件清单 `containsExactly("height")` + `doesNotContain("terrain","edgeTags","riverMask")`（**加强**，不是放松）。
- `GameMapTest`：组件 8⇒9（名字/序冻结字面量）、`noRiversNoRoadsNoTerrainBlocksNoCompressedRegions` **显式撤销** terrainBlocks 那一半（另断言 `contains("terrainBlocks")`）、`with*`/不可变/空图/反射全量。
- `RoundTripComponentsTest`：两个 `default -> throw` switch 各加 `terrainBlocks`；组件数 7⇒8；`mutate` 里 hexes/terrainBlocks 因分割不变式**原子建图**（`oneHexMap()`）。
- `MapChangeSetTest`：`betweenDetectsChangedTerrainBlocks` 新条（只改地形的单组件往返）；hexes 相关夹具改为"同键集改高度"或原子重建。
- `RegressionGuardsTest.L7`：落盘形状加块；JSON mapper 注册 `BlockId` 键；`L8` 构造器形参 8⇒9、`new GameMap(` 调用点 9⇒10。
- `MapGeneratorTest`/`RegionRandomizerTest`/`RiverBuilderTest` 等：`HexCell::terrain`/`cell.terrain()` 改走 `terrainAt`/`terrainIndex`；夹具改原子建块。
- 其余模块夹具（social/unit/core/app）全部按新形状改写（`git diff --stat`：41 文件，+1002/−414）。

### 1.5 新增测试

`TerrainBlocksTest`(10)、`MapCodecLegacyTest`(3)、`RepresentationLeakGuardTest`(2)、`MapTerrainBlockEndToEndTest`(1)。

## 2 判据：实测值与断言

### 2.1 分割不变式（正例 + 故意违规）

- 正例（真切分，半径 2、中心 ocean）：`并集 == 全部 hex` 绿、`块 hexes 总和 == 格总数`（19==19）绿，块数 2。
- 违规①（少一块）：`TerrainBlocks.requirePartition` 抛
  `hex 0_0 不属于任何地形块（分割不变式要求并集覆盖全部 hex）`（**精确匹配**）。
- 违规②（一格属两块）：抛
  `hex 5_5 同时属于地形块 plains@0_0 与 desert@5_5（分割不变式要求两两不交）`。
- 违规③（块键与内容不符）：`地形块键 desert@5_5 与块内容不符`。
- **强制点在 `GameMap` 构造期**：同一坏块表经构造器抛同一条"hex 0_0 不属于任何地形块…"。
- 带洞边界：中心异地形飞地 ⇒ 外圈块 `boundary().rings()` **恰 2 条**（外轮廓 + 洞环）；单格块 1 条。

### 2.2 确定性（P5）

同一地形表两次 `TerrainBlocks.split`：两次的 `BlockId` 集合逐项相同、每块 `hexes` 集合与**迭代序**相同、`boundary().toString()` 逐字节相同、整块
`toString()` 逐字节相同。`BlockId.of` 对乱序入参仍取最小 hex（`plains@-5_-59`），`parse` 往返。

### 2.3 往返

- `RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet`：9 组件逐组件 `apply(between(base,target),base).equals(target)` 全绿（含
  `terrainBlocks`）。
- `MapChangeSetTest.betweenDetectsChangedTerrainBlocks`：只改地形 ⇒ `terrainBlocks` 非 `Unchanged`、`hexes` `Unchanged`、往返相等。
- `MapCodecLegacyTest.holeBearingBlocksRoundTripThroughJson` + `MapCodecTest`：带洞块与变更集 JSON 往返相等。

### 2.4 端到端（真命令经 `CoreSimos`）

`MapTerrainBlockEndToEndTest`：`bootstrapGenesis` → `submit(map.RandomizeTest)`（test-only handler 调 `RegionRandomizer`，真 store/checkpoint/replay）
→ `Committed((main,2))`；重放读回的图 **== 独立重建的期望图**（`MapChangeSet.apply(randomize(genesisMap),genesisMap)`）；19 格高度**逐值不变**；
被改的格全在 `core` 内且新地形 ∈ {plains,desert}；`requirePartition` 通过；按 `terrainIndex` 重切与存档块键**集合相同**。

> 说明：基线 `0f56521` 尚未含 M8 的 `map.RandomizeRegion` 命令，故 handler 为 test-only；但走的是**真** `Command → ChangeSet → Revision` 全链。

## 3 设计判断：边界复用 `RegionBoundary`

块的"外环 + 洞环"需求与 `RegionBoundary` **同构**：它的算法（逐格逐边、邻居不在集合即暴露、按顶点走环）**天然为每个暴露边连通分量产出一条环**——
外轮廓一条、每个飞地一条，即"带洞多边形"；且它是 hex 集合的**规范纯函数**（旋到最小顶点 + 字典序取绕向，迭代序无关），顶点是整数标签。
新造平行类型只会复制这两条性质（复制正是本项目最贵的教训），故**复用**。`rings` 顺序是首顶点字典序（非"外环在前"），消费端 evenodd 无几何含义。

## 4 旧档兼容（§3.12，**选项 a + 实测证据**）

**判定：选项 (a) —— `MapCodec` 加旧形状回退**，理由是**旧档能原地读回**，无需让用户重导入（重导入会丢用户在 5818 上的编辑史）。

**实测证据（真档、非合成）**：

- 取真档 `checkpoints/main/1.json`（新版**复制**到 `/tmp/opencode/t6/`，**未动**原档；md5 `9e13d856…` 前后一致）。
- 用**新构建**的 `MapCodec.decodeSnapshot(payload)` 直接解码其 `modules.map` 段（旧形状：`hexes` 值带 `terrain`、无 `terrainBlocks`）：
  - `payload chars=949869`、`partition=OK hexCount=19441`、`blockCount=44`；
  - `histogram={plains:10719, mountains:886, ocean:4506, low_hills:3330}` —— 与旧档 Python 直读**逐值相同**（含 M6 的 `swamp→plains` 合并算术）；
  - 首格 `(-5,-59)`：`terrain=plains height=0.375`（旧档原文 `{"terrain":"plains","height":0.375}`）；样本 `BlockId=low_hills@-64_72`。
- 进一步用**新构建**的 `ShellMain --store <旧档副本> --gui-port 5819`（tmux 后台，只读旧档）启动成功、`GET /api/map/overview` **200 / 703,053 B**、
  直方图同上、**零异常**；随后**只杀我起的 5819**。`5817`/`5818` **全程未动**。
- 写保护：`/tmp/m6-import-verify/test_integration/simos.db` md5 跑前跑后均 **`2348b9365e5b107945a305d06fad8fab`**（与 T1/T2/T3 同值）；我只用了**副本**。
- **旧变更集**（`hexes` 值带 `terrain`）不可静态迁移（块切分依赖 base 全图）⇒ `MapCodec` **显式抛**并给重导入指引（`MapCodecLegacyTest.oldShapeChangeSetIsRejectedWithGuidance`）。
  本真档的创世变更集是空 `WorldChangeSet`，故不受影响。

## 5 T3 → 现在 对照（复用 `t2-evidence/harness/measure.cjs`；★ 任务写的是 `t3-evidence/harness/`，**实际在 `t2-evidence/harness/`**）

服务端 = T6 新构建、数据 = 旧档**副本**、`http://127.0.0.1:5819`。

| 指标 | T3 (`t3-evidence/run-now.json`) | T6 | 判定 |
|---|---|---|---|
| `firstInteractiveMs` | 420.7 | **441.2** | +20.5ms（~4.9%，噪声级） |
| `apiBytes` | 1,406,966 | **1,406,966** | 不变 |
| `allBytes` | 1,578,149 | **1,578,149** | 不变 |
| `apiCount` | 11 | **11** | 不变 |
| `render()` p50 / p95 (ms) | 1.65 / 3.25 | **1.75 / 2.075** | p95 更好，无退化 |
| `pageErrors` / `consoleErrors` | [] / 两个 404（同 T3） | [] / 两个 404 | 同 T3（既有 favicon 类 404） |

**结论：不退化。** `overview` 载荷字节未变（T6 只改地形来源，仍逐格发 `terrain`）。

## 6 变异（5 轮 × 九道门禁，全杀）

装置 `mutants/mut-round.sh`（每轮：`cp` 原件还原→比 md5→变异体字节不同→白名单推成规范类名→清 `classes`/`test-classes`/`surefire-reports`→跑目标用例→
断言 `COMPILATION ERROR=0`、`Tests run>=1`、surefire 报告 mtime 落轮内、红点命中被保护断言→`cp` 还原并比 md5；日志自指并先断言聚合 md5 非空）。

| m | 护栏 | 变异 | 目标用例 | 红点（实测原文） | 结论 |
|---|---|---|---|---|---|
| m1 | 分割（无遗漏） | `split` 漏掉一个 singleton ocean 分量 | `TerrainBlocksTest#splitProducesAPartition` | `[并集 == 全部 hex（漏一块 ⇒ 有格无主）]` | **KILLED** |
| m2 | 分割（不交） | plains 分量吞中心一格 | 同上 | `[块 hexes 总和 == 格总数（吞一格 ⇒ 一格属两块）] expected:19 but was:20` | **KILLED** |
| m3 | 确定性 | `BlockId` 用自增序号 | `TerrainBlocksTest#rebuildIsByteIdentical` | `[两次重建的 BlockId 集合逐项相同] [ocean@3_999,…] vs [ocean@1_999,…]` | **KILLED** |
| m4 | 变更集完整 | `between` 不 diff `terrainBlocks` | `MapChangeSetTest#betweenDetectsChangedTerrainBlocks` | `[terrainBlocks 必须进 diff] Unchanged not an instance of Unchanged` | **KILLED** |
| m5 | 访问器正确 | `terrainAt` 回退默认地形 | `GameMapTest#terrainAtIsDerivedAndMatchesBlocks` | `expected: "mountains" but was: "plains"` | **KILLED** |

存活项：**无**。门禁逐轮实测：`comp_err=0`、`tests_run=2`、`report_ok=yes`、`restored_md5` 与原 md5 一致。
另有 §1.5 的 grep 守卫**自带**故意违规自证（`RepresentationLeakGuardTest.theScannerActuallyCatchesAPlant`）。

## 7 门禁数字

`./mvnw clean verify`：**rc=0、867 条 = 170/272/45/131/155/94、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 行**。

| 模块 | 基线 | T6 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | 未动 |
| MapSimos | 256 | **272** | **+16** | `TerrainBlocksTest` 10 + `MapCodecLegacyTest` 3 + `RepresentationLeakGuardTest` 2 + `MapChangeSetTest` 新条 1 |
| SocialSimos | 45 | 45 | 0 | 未动 |
| UnitSimos | 131 | 131 | 0 | 主源码 `TerrainMovementCost` 改读访问器，测试条数不变 |
| CoreSimos | 154 | **155** | **+1** | `MapTerrainBlockEndToEndTest` 1 |
| SimosApp | 94 | 94 | 0 | 读点改访问器，测试条数不变（`DemoWorldTest` 断言改读法） |
| **合计** | **850** | **867** | **+17** | |

## 8 实测 vs 推断

| 项 | 类型 | 依据 |
|---|---|---|
| 真旧档 19441 格经回退解码、44 块、直方图逐值 | **实测** | `legacy/old-archive-probe.out`（新构建直接解码真 payload） |
| 真旧档经 `ShellMain` 起服务、overview 200/703,053、零异常 | **实测** | `logs/server-5819.log`、`legacy/overview-served-from-old-archive.json` |
| 分割不变式四条 + 违规精确消息 | **实测** | `TerrainBlocksTest`、`GameMapTest` |
| 确定性四项逐字节 | **实测** | `TerrainBlocksTest#rebuildIsByteIdentical` |
| 端到端块与高度逐值 | **实测** | `MapTerrainBlockEndToEndTest` |
| 5 变异全杀 + 九门禁 | **实测** | `logs/mutation-summary.log`、`logs/m{1..5}.log` |
| 全量 867 绿、SpotBugs 0×6 | **实测** | `logs/full-verify.log` |
| T3→T6 指标 | **实测** | `harness/measure-t6.json` vs `t3-evidence/run-now.json` |
| `RegionRandomizer` 整体重切对超大图的**服务端耗时** | **推断** | 未单独计时；overview 总耗时含它在内未退化（§5） |
| 变更集 JSON 里 `terrainBlocks` 顺序的**跨 JVM 字节稳定** | **推断** | `BlockId` 全序 + split 的 `TreeMap` ⇒ 生成确定性；但 `apply` 后顺序 = base 序 + 追加序（`Map.equals` 与既有关卡不依赖块表顺序） |

## 9 我未能核实的

1. **超大图 `RegionRandomizer` 的性能**：只测了含它的 overview 未退化，没单独量"整体重切"在 19441 格上的 ms。若将来 map 编辑走命令，需单独测。
2. **`terrainAt` 的 O(#块) 在极端碎片化图上的批量代价**：本单批量读点已走 `terrainIndex()`；`terrainAt` 只在单点读用。未构造"上万块"的对抗图。
3. **旧变更集迁移**：本真档无旧 map 变更集，故只证了"显式拒绝"路径；未构造真实旧变更集档验证拒绝（`MapCodecLegacyTest` 用的是合成 JSON）。
4. **前端未动**：`webui/**` 零改动（`git status` 可证），但没有重跑 M7 系列浏览器 e2e —— 只跑了 T2/T3 的 `measure.cjs`（含 `pickAtCenter` 功能探针）。
5. **`5` 之外的变异**：如"块边界丢洞"（`evenodd`/环数）未做独立变异；洞的存在由 `TerrainBlocksTest#boundaryCarriesHoleRings`（2 条环）与 JSON 往返钉住，但无变异自证。
6. **过程备注（透明）**：开工初我误在主工作树编辑（应直接改 worktree），随后把 13 个改动文件 `cp` 进 worktree 并 `git checkout` 还原主树；主树已回到 `0f56521` 干净态，`5817`/`5818` 未重启、未受影响。

## 10 证据索引

```
t6-evidence/
  logs/full-verify.log                  # 最终 ./mvnw clean verify（867）
  logs/mutation-summary.log             # 5 轮汇总（verdict/md5/门禁）
  logs/m{1..5}.log                      # 每轮完整日志（含 md5 自指与红点原文）
  logs/server-5819.log                  # 新构建读旧档副本的启动与服务日志
  mutants/mut-round.sh                  # 变异装置（九门禁）
  mutants/m{1..5}.<Class>.java          # 5 个变异体
  mutants/orig/*.java                   # 三个目标的原件（cp 还原源）
  legacy/OldArchiveProbe.java           # 真旧档解码探针
  legacy/old-archive-probe.out          # 真旧档解码结果（19441/44 块/直方图）
  legacy/real-old-checkpoint.json       # 真旧档 checkpoint 的副本（md5 9e13d856…）
  legacy/overview-served-from-old-archive.json
  legacy/live-db-md5.txt                # 2348b936…
  harness/measure-t6.json               # T2/T3 harness 复跑结果
```
