# M8 T4 报告 —— 区域三命令 `map.CreateRegion` / `map.UpdateRegion` / `map.DeleteRegion`

> 分支 `m8/t4`（worktree `.claude/worktrees/m8t4`）；基线 `30328c2`（M8 T3 关账）。证据全在 `.superpowers/sdd/2026-09-19-map-edit/t4-evidence/`。
> ★ 本报告只写**实测值**；推断单列（§8）。

## 0 一句话结论

三条区域命令落在 `simos-map`（`ops/RegionOperations` + 三个 `spi` handler），语义是**只换 `regions` 组件**：
`Create` 对重复 id 拒绝、`Update` 至少给 hexes/meta 之一、`Delete` 对不存在拒绝；**`RegionId` 由调用方给**（Q3）；
★ **重叠一律允许**（M8-U1：hex 可同时属多个区域，不校验、不裁剪）；边界一律经 `Region.of` 重算（不手造）。
`hexes` 非空、每格在图上。门禁 **924** 绿、SpotBugs 0×6、ERROR 0；真档 19441 格重叠正例经真 Shell/HTTP 通过；4 个变异体全杀。

## 1 改动（逐文件 + 行）

### 1.1 新增（`simos-map` 生产码）

| 文件 | 行 | 内容 |
|---|---|---|
| `map/ops/RegionOperations.java` | 137 | 三个纯函数：`createRegion` / `updateRegion` / `deleteRegion`；唯一变更集路径 `MapChangeSet.between(base, base.withRegions(next))`；`requireNonEmptyHexesInMap`（自然序报第一个坏格）；**无任何"禁止重叠"校验**。 |
| `map/spi/CreateRegionHandler.java` | 50 | `type()="map.CreateRegion"`；解析 `{regionId,name,hexes,meta?}`；域规则 IAE 折 `Rejected`。 |
| `map/spi/UpdateRegionHandler.java` | 47 | `type()="map.UpdateRegion"`；解析 `{regionId,hexes?,meta?}`（缺席与空数组分开）。 |
| `map/spi/DeleteRegionHandler.java` | 42 | `type()="map.DeleteRegion"`；解析 `{regionId}`。 |

### 1.2 修改

| 文件 | 改动 |
|---|---|
| `simos-map/.../spi/MapPayloads.java` | `+52`：新增 `requireRegionId`、`optionalHexes`（缺席/null ⇒ null）、`optionalMeta`（四子字段各自可选）、`optionalText`；import `RegionId`/`RegionMeta`。 |
| `simos-app/.../Shell.java` | `+6`：注册三个 handler（9 → **12**）。 |
| `simos-app/src/test/.../SimosToolsTest.java` | `EXPECTED_COMMAND_TYPES` +3（9 → 12）。 |
| `simos-app/src/test/.../McpCoverageTest.java` | `EXPECTED_COMMAND_TYPES` +3、`MINIMAL_PAYLOADS` +3（Create→Update→Delete 合法序）、head 断言 10→13 / advance 11→14 / fork 14。 |

### 1.3 新增测试

| 文件 | 条数 | 覆盖 |
|---|---|---|
| `simos-map/.../ops/RegionOperationsTest.java` | 16 | Create 正常/重叠（核心正例）/重复 id/空集/图外/meta；Update 改 hexes（重算边界）/只改 meta/都给/改成重叠/缺失/二者皆无/空集/图外；Delete 正常（不悬空）/缺失。 |
| `simos-map/.../spi/RegionHandlersTest.java` | 15 | 三 type；Create 有/无 meta、重叠；Update hexes+meta、只 meta；Delete；11 种坏载荷/域规则负例。 |
| `simos-core/.../MapRegionEndToEndTest.java` | 3 | 真 CoreSimos：重叠 Create→(main,2) + 重放 + `MapResolver.regionOfHex` 双属；Update→Delete 全链 + 删除不悬空；负例五连不留 revision。 |

★ 未改 `Region`/`RegionBoundary`/`RegionIndex` 模型（T1 定稿）；未加"禁止重叠"校验；未改前端；无新依赖。

## 2 三命令语义

| type | 载荷 | 语义要点（源码为准） |
|---|---|---|
| `map.CreateRegion` | `{regionId, name, hexes, meta?}` | `regionId` 已存在 ⇒ **拒绝**（不静默覆盖）；`hexes` 非空、每格在图上；`meta` 缺席 ⇒ `RegionMeta.empty()`；**重叠允许**。 |
| `map.UpdateRegion` | `{regionId, hexes?, meta?}` | 二者**至少给一个**；目标不存在 ⇒ **拒绝**；给 hexes ⇒ 非空 + 图内 + **经 `Region.of` 重算边界**；改 hexes 使与别区重叠 ⇒ **允许**。 |
| `map.DeleteRegion` | `{regionId}` | 不存在 ⇒ **拒绝**（**不做静默幂等**）；删除后 `RegionIndex` 该 hex 从属**少一个**。 |

★ 三者都**只换 `regions` 组件**（`withRegions`），故 `hexes`（高度）与 `terrainBlocks` 恒 `Unchanged`（逐组件独立性，测试逐条断言）。
★ `Region` 构造期强制 `boundary.equals(RegionBoundary.of(hexes))`，三条命令一律走 `Region.of`（**不手造 boundary**）。

## 3 ★ 重叠正例实测（真档副本，非合成）

真档 = `/tmp/m6-import-verify/test_integration` 的**副本** `/tmp/t4-real-archive`。真 `Shell`（真 GUI/HTTP 45861、真 MCP 46211）+ 真 `CoreSimos/CommandBus`。

| 指标 | 实测值 |
|---|---|
| `hexCount` / `regionCount` | 19441 / 2 |
| 选格（`test_nation` 前 3，自然序） | `[-18_0, -18_1, -17_-1]` |
| 这三格的 genesis 从属 | `[test_annex_target, test_nation]`（**真重叠**） |
| 提交 | `map.CreateRegion {regionId:"t4_overlap", hexes:[这 3 格]}` ⇒ **`Committed[main@2]`** |
| `revisions` | 1 → 2 |
| `regions`（重放后） | `[test_annex_target, test_nation, t4_overlap]` |
| 重叠格从属（重放后） | `(-18,0)` ⇒ `[t4_overlap, test_annex_target, test_nation]`（**3 个**，字典序） |
| `replayRegionsByteIdentical` | true（两次 `replay` 的 regions 逐字节相同） |
| 原档 md5 | 跑前 = 跑后 = `2348b9365e5b107945a305d06fad8fab` |
| 5817/5818 | 全程未动（`ss -ltnp` 实测两者仍在监听，pid 414342/414344） |

### 3.1 `/api/map/hex` 原始 JSON 片段（重叠格，revision=2）

```
API_URL=http://127.0.0.1:45861/api/map/hex?q=-18&r=0&revision=2
API_JSON=status=200 {"q":-18,"r":0,"terrain":"low_hills","height":0.6000000000000001,
  "regions":["t4_overlap","test_annex_target","test_nation"],
  "terrainType":{"key":"low_hills","name":"低矮丘陵","color":"#A8B36A",...},"facets":[]}
```

★ `regions` 同时列出**三个**区域（新加的重叠区 + 原有两区），字典序；这是"多从属、不存在谁赢"的端点级证明。

### 3.2 ★ 重叠正例的**失败反证**（方向性护栏 = 变异 m3）

重叠正例不是"恰好通过"：**变异 m3 给 `createRegion` 加一条"与已有区域相交就拒绝"的校验**后，
`RegionOperationsTest.createRegionOverlappingAnExistingOneIsAllowed:75` **红**（`IllegalArgument 区域与已有区域重叠: r1`）、
`RegionHandlersTest.createRegionHandlerAllowsOverlap:97` **红**（`Rejected` 无法 cast 成 `Applied`）。
⇒ 该用例确实在守"重叠允许"这一裁定；**若实现逆着裁定加校验，它必红**（m3 日志在案）。

## 4 负例实测（含 rows）

### 4.1 真档探针（副本，`rows` 前后）

```
negDuplicate     = Rejected[reason=区域已存在: test_nation]
negUpdateMissing = Rejected[reason=区域不存在: nope]
negDeleteMissing = Rejected[reason=区域不存在: nope]
negEmpty         = Rejected[reason=hexes 不得为空：一个区域至少要有一格]
negOutside       = Rejected[reason=hex 不在图上: 9999_9999]
rowsUnchanged=true rows=2
```

★ **负例四连**（重复 id / 不存在 / 空 hexes / 图外）逐条 `Rejected` + `revisions` 行数不变（`rows=2`）。
★ 单测层 `MapRegionEndToEndTest.negativeCommandsAreRejectedAndLeaveNoRevision` 另断言 `head` 仍 `RevisionId(1)`。

### 4.2 删除后不悬空（`RegionIndex` 从属变化）

`MapRegionEndToEndTest.updateThenDeleteSurviveTheRealCommandPathAndDeleteDoesNotDangle`（真 CoreSimos）：
- 前置：`seed={H00,H10}`；`Create t4={H20}` ⇒ `regionOfHex(H20)=[t4]`；
- `Update t4={H10,H20}` ⇒ `regionOfHex(H10)=[seed,t4]`（**改成重叠，允许**）；
- `Delete t4` ⇒ `regions==[seed]`；★`regionOfHex(H20)` **空**（只属 t4 ⇒ 删除后无从属，**不悬空**）；`regionOfHex(H10)==[seed]`（共有格仍留一属）。

`RegionOperationsTest.deleteRegionRemovesItAndDoesNotDangleOwnership`：同形断言（纯算子层）。

## 5 变异（4 轮 × 九道门禁，全杀）

装置 `mutants/mut-round.sh`（干净世界基线 → 变异体字节不同 → 白名单推成目标类名并清 `.class` → 断言
`COMPILATION ERROR=0` 且 `Tests run≥1` → surefire 报告 mtime 落本轮 → 红点落被保护断言 → `cp` 逐字节还原（绝不 `git checkout --`）→ 日志自指并先断言聚合 md5 非空）。

| m | 护栏 | 变异 | 红点（实测原文片段） | 结论 |
|---|---|---|---|---|
| m1 | 重复 id 拒绝 | 删 `createRegion` 的"已存在 ⇒ 抛"块（**静默覆盖**） | `RegionOperationsTest.createRegionRejectsDuplicateId:91` + `RegionHandlersTest.rejectsDuplicateCreate:151` | **KILLED**（hits=2） |
| m2 | 删除存在性 | 删 `deleteRegion` 的"不存在 ⇒ 抛"块（**静默成功**） | `RegionOperationsTest.deleteRegionRejectsMissingTarget:261` + `RegionHandlersTest.rejectsDeleteOfMissingRegion:162` | **KILLED**（hits=2） |
| m3 | ★ **重叠允许** | **加**一条"与已有区域相交就拒绝" | `RegionOperationsTest.createRegionOverlappingAnExistingOneIsAllowed:75`（`IllegalArgument 区域与已有区域重叠: r1`）+ `RegionHandlersTest.createRegionHandlerAllowsOverlap:97` | **KILLED**（hits=2） |
| m4 | 边界自洽 | `Create` 绕过 `Region.of`，塞入 `RegionBoundary.of(Set.of())` | `RegionOperationsTest.createRegionAddsItAndOnlyTheRegionsComponentChanges:48`（`IllegalArgument boundary 与 hexes 不一致：…传入的是 RegionBoundary[rings=[]]`）+ 另 3 处 | **KILLED**（hits=2+，另含 ERROR） |

门禁逐轮实测：`clean_rc=0`、`clean_compilation_error=0`、`mut_compilation_error=0`、`Tests run: 31`、
`protected_assertion_hits≥1`、`restored_md5 == orig`、日志自指 md5 非空。存活项：**无**。

★ m4 的机理：`RegionBoundary.of(Set.of())` 得空环表，`Region` 构造器重算非空边界、不等即抛——**U2 的钉子在构造期就挡住了**，故"绕过 `Region.of`"这条变异必以异常面世。

## 6 门禁数字

`./mvnw clean verify`：**rc=0、924 条 = 170/321/45/131/161/96、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 行**
（日志 `logs/full-verify-final.log`；变异前的同值绿轮 `logs/full-verify.log`）。

| 模块 | 基线（890） | T4 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | 未动 |
| MapSimos | 290 | **321** | **+31** | `RegionOperationsTest` 16 + `RegionHandlersTest` 15 |
| SocialSimos | 45 | 45 | 0 | 未动 |
| UnitSimos | 131 | 131 | 0 | 未动 |
| CoreSimos | 158 | **161** | **+3** | `MapRegionEndToEndTest` 3 |
| SimosApp | 96 | 96 | 0 | `McpCoverageTest`/`SimosToolsTest` 改断言，条数不变 |
| **合计** | **890** | **924** | **+34** | |

## 7 与派单/spec 的分歧（源码为准）

1. ★ **派单 §4.1 的基线分布写 `890 = 170/289/45/131/158/96` 是错的**：`170+289+45+131+158+96 = 889 ≠ 890`。
   源码/T3 关账报告（`t3-report.md` §6）实测 **MapSimos = 290**（890 = 170/290/45/131/158/96）。本报告按 **290** 计 delta（+31）。
2. **`simos-core` 未改生产码**（只 test 侧新增 `MapRegionEndToEndTest`）——派单把 `simos-core` 列为可能改动模块；命令规则全在 `simos-map`，Core 只转发信封（铁律 4 的预期形态）。
3. **派单 §5 的 m4 期望红点措辞**为"边界校验用例红"：实测红在 `createRegionAddsItAndOnlyTheRegionsComponentChanges` 的**边界等式断言**（以及其它 Create 正例的异常），与"边界校验"一致。
4. **派单 §2.1 的 Create 载荷写 `{regionId, name, hexes, meta?}`**：与源码一致（`name` 必填，因为 `Region` 构造器要求非空白）；**Update 不接收 `name`**（spec §二 的 Update 载荷只有 `regionId`/`hexes?`/`meta?`）——改名不在本任务范围。

## 8 实测 vs 推断

| 项 | 类型 | 依据 |
|---|---|---|
| 全量 924 绿、SpotBugs 0×6、ERROR 0 | **实测** | `logs/full-verify-final.log` |
| 真档 19441 格 / 重叠 Create `main@2` / `/api` 三从属 / 重放逐字节 | **实测** | `probe/real-archive-probe.out` |
| 原档 md5 前后不变、5817/5818 未动 | **实测** | `md5sum` + `ss -ltnp` |
| 负例四连拒绝 + `rows` 不变 | **实测** | 探针 + `MapRegionEndToEndTest` |
| 删除不悬空 | **实测** | `RegionOperationsTest` + `MapRegionEndToEndTest` |
| 4 变异全杀 + 九门禁 | **实测** | `logs/m{1,2,3,4}.log` |
| 基线 890（MapSimos=290） | **引用** | `t3-report.md` §6 + 本 worktree HEAD `30328c2`；delta 算术一致（290+31=321、158+3=161） |
| GUI 前端区域编辑交互 | **推断** | UI 归 T10；本任务只证端点/MCP 面可达 |
| `>2` 区域同属的重叠在浏览器渲染 | **推断** | 本任务端点已实测 3 从属；浏览器绘制归 T9/T10 |

## 9 我未能核实的

1. **浏览器内**点选/高亮 3 从属区域的渲染（前端归 T9/T10；本任务只到 HTTP 端点 JSON）。
2. **`UpdateRegion` 改 `name`** 没有入口（spec 载荷不含 name），故"改区域名"未测。
3. **超大区域**（如 701 格全量替换）的 `RegionBoundary.of` 重算耗时未单测。
4. **跨 JVM** 的 `regions` 序列化字节稳定性未跑（同 JVM 两次 `replay` 逐字节已测）。
5. 变异只覆盖 `RegionOperations` 一个生产文件；三个 handler 的**纯转发**未单独做变异（其类型/解析由 15 条 handler 用例覆盖）。

## 10 证据索引

```
t4-evidence/
  logs/full-verify.log                 # 变异前全量（924）
  logs/full-verify-final.log           # 还原后全量（924，最终）
  logs/m1.log / m2.log / m3.log / m4.log   # 4 轮变异（含字节自指与红点原文）
  mutants/mut-round.sh                 # 变异装置（九门禁）
  mutants/m{1,2,3,4}.RegionOperations.java
  mutants/orig/RegionOperations.java   # md5 2002fbbd23528f0d85fb9a5fd9ca234b
  probe/T4RealArchiveProbe.java        # 真档探针（真 Shell/HTTP）
  probe/real-archive-probe.out         # 真档实测结果（含 API JSON）
  probe/cp.txt                         # 探针 classpath
```
