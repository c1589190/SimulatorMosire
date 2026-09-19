# M7 T1 报告 —— Core 只读扩展 + app 只读 API

> 任务：WebUI 可视化骨架的第一个任务（**纯增量**）。工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7t1`，
> 分支 `m7/t1`，基线 `5294f85`。设计见 `docs/superpowers/specs/2026-09-19-webui-design.md` §三 / §七，
> 计划见 `docs/superpowers/plans/2026-09-19-webui-plan.md` T1。

---

## 一 改了什么 / 为什么

| # | 文件 | 改动 | 为什么 |
|---|---|---|---|
| 1 | `simos-core/.../timeline/Timeline.java` | 新增 `BY_BRANCH_SQL` 常量 + `public List<RevisionRow> listRevisions(BranchId)`（`SELECT <ALL_COLUMNS> FROM revisions WHERE branch = ? ORDER BY revision ASC`） | spec §3.1 的节点清单。列清单复用 `ALL_COLUMNS`、行映射复用 `mapRow`——**不另写一套**（两套列清单必然漂移，铁律 5 的事故形态）。只读，走 `store.inTransaction` |
| 2 | `simos-core/.../CoreSimos.java` | 新增 `public List<RevisionRow> revisions(BranchId)`（纯委托） | spec §3.1。与 `branches()`/`head()` **同口径**：不封存、不写盘、不触发 `sealIfNeeded()` |
| 3 | `simos-app/.../gui/ApiViews.java` | 新增 `timeline(branch,head,rows)`；`mapOverview` 的 region 项加 `meta`；`mapHex` 增参 `region`/`terrainType`；新增 `regionDetail(region)` 与私有 `regionMeta(meta)` | spec §3.1/§3.2/§3.3。`terrainType` 由调用方从**状态里的** `map.terrainTypes()` 取，本类不查 `TerrainCatalog` |
| 4 | `simos-app/.../gui/GuiServer.java` | `GET_ROUTES` 加 `/api/timeline`；`handleGet` 加 `/api/timeline` 与 `/api/map/region/` 前缀分支；`allowedMethod` 认 `isRegionDetail`；`mapHexReply` 接线 `MapResolver.regionOfHex` + `map.terrainTypes().get(...)`；新增 `regionReply`/`timelineReply` | spec §3.1/§3.2/§3.3 的 HTTP 面 |
| 5 | `simos-core/src/test/.../TimelineTest.java` | 新增 `listRevisionsIsPerBranchAndAscending`（+1 条） | R3 的库侧护栏：两分支隔离 + 升序 + parent 与 fork 一致 |
| 6 | `simos-app/src/test/.../gui/GuiApiTest.java` | 夹具 `corridorMap` 加两个区域（r-1 全量 meta 覆盖 H11/H13；r-2 空 meta 覆盖 H12）+ 新增 4 条用例 + `appendRevision`/`regionById` 夹具 | R3/R5 的 HTTP 侧护栏（timeline 形状与 404、hex 的 region/terrainType、overview 的 meta、region 详情与 404） |

**未改**：`QueryService`（计划步骤 6 是"若需"——本任务不需要，GuiServer 直接取 state 的 `GameMap`）；
前端 `simos-app/src/main/resources/webui/**`（**零改动**，`git status` 实证）；`SqliteStore` 的 DDL；
`CommandBus`/`Timeline.fork`/`ForkBranch`；任何既有端点/字段的形状或语义。**无新增依赖、无新模块。**

### 纯增量核对（硬约束 §6）

- `/api/map/overview` 的 `terrainTypes` **仍是 `[key…]`**（未动 `ApiViews.mapOverview` 那一行），并由
  `mapOverviewRegionItemsCarryMeta` 末尾的断言 `terrainTypes[0] == "desert"` **钉住**——形状切换是 T2 的原子改动。
- `/api/map/hex` 只**增**两个字段（`region`/`terrainType`），既有 `q/r/terrain/height/facets` 一字未动。
- overview 的 region 项只**增** `meta`，既有 `id/name/hexCount` 一字未动。

---

## 二 实测数字

### 定向（`./mvnw -pl simos-core,simos-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TimelineTest,GuiApiTest' test`）

- rc=0；`TimelineTest` **16** 条、`GuiApiTest` **16** 条，全绿。日志 `logs/targeted.log`。

### 全量（`./mvnw clean verify`）

rc=0、**830** 条、7/7 模块 SUCCESS、`BugInstance size is 0` ×6、`[ERROR]` 0 行。日志 `logs/full-verify.log`。

| 模块 | 本任务 | 基线（M5/M6 关账） | delta | 来源 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | — |
| MapSimos | 255 | 255 | 0 | — |
| SocialSimos | 45 | 45 | 0 | — |
| UnitSimos | 131 | 131 | 0 | — |
| CoreSimos | **154** | 153 | **+1** | `TimelineTest` 新增 1 条 |
| SimosApp | **75** | 71 | **+4** | `GuiApiTest` 新增 4 条 |
| **合计** | **830** | **825** | **+5** | — |

> ★ 前四个模块**一个都没动**（与"唯一 Core 改动 + app 只读面"的范围一致）；core/app 的 delta 恰等于新增用例数。
> 基线 825 已按派单书要求**自己跑出来核对**：`170/255/45/131/153/71`（M5 关账值）。

### R1 扫描（`AppWritePathGuardTest`）

全量绿（2/2）。注意一个**易踩的边界**：`GuiServer`/`ApiViews` 新增了
`import io.mosire.simos.core.timeline.RevisionRow;`——该扫描器禁的是**大写 `Timeline`**（`SqliteStore`/`Timeline`/
`CheckpointStore`），包名 `timeline`（小写）不在禁列。**本次实测确认扫描器仍绿**，未放宽任何规则。

---

## 三 变异表（九道门禁逐条）

装置：`mutants/mut-round.sh`（照 M4 Task 17 骨架，`ROOT` 指向本 worktree，module/test 参数化）。
清单：`mut-manifest.md5`。

| 轮 | 护栏 | 变异 | 红点（实测） | rc | CE | Tests run | 报告 mtime 落轮内 | 还原 md5 |
|---|---|---|---|---|---|---|---|---|
| **m1** | **R3** | `listRevisions` 去掉 `WHERE branch = ?`（连同它的绑定参数） | `TimelineTest.listRevisionsIsPerBranchAndAscending:161` —— `Expecting ["main","b2","main"] to contain exactly ["main","main"]`（**分支隔离断言**） | 1 | 0 | 16（1 败） | ✅ | ✅ |
| **m2** | **R5** | `GuiServer` 把 `regionOfHex` 换成常返空 | `GuiApiTest.mapHexCarriesRegionAndFullTerrainDefinition:219 [H11 属 r-1（MapResolver.regionOfHex）]`（**region 断言**） | 1 | 0 | 16（1 败） | ✅ | ✅ |

- **m1 红点落"被保护的那行"**：断言的是 `listRevisions(main)` 的**分支集合**，红因是 `b2` 混入（`WHERE` 失效），
  不是参数越界/异常。为此变异体**必须同时去掉 `statement.setString(1, …)`**——只删 `WHERE` 会让
  `setString(1)` 越界抛 `SQLException`，红成"事务失败，已回滚"而不是隔离断言（形态 1："红的理由必须是被保护的那行本身"）。
- **m2 首轮当场作废（值得记下）**：第一版变异体只改 `regionOfHex` 那一行、留着 `MapResolver` 的 import。
  `test` 阶段会**先跑 Checkstyle**，`UnusedImports` 在测试之前红 ⇒ `Tests run=0`、surefire 报告是**上一轮的陈旧文件**
  （mtime < round_start）⇒ 装置按门禁 5/6 **判本轮作废**（rc=2，日志 `logs/m2.log` 被随后覆盖前的内容即此）。
  第二轮**机械地**去掉那个不再被引用的 import 后才真正落到 region 断言。这正是"装置产物自己带状态 / 先怀疑自己的读取"
  那条纪律的又一实例。
- 九道门禁逐条：① 变异体字节不同（md5 见清单）② 干净世界（`orig_md5 == worktree_before`）③ 按目标路径推成目标类名
  ④ 删陈旧 `.class` ⑤ `COMPILATION ERROR`=0 且 `Tests run≥1` ⑥ surefire 报告 mtime 落轮内 ⑦ 红点落被保护断言
  ⑧ `cp` 逐字节还原（脚本内**无** `git checkout --`）⑨ 本轮 md5 追加进日志本身（两轮日志末尾均有"装置补记"段）。

---

## 四 偏离 / 取代说明候选

1. **m1 变异体范围细化**（非设计偏离）：派单书写"去掉 `WHERE branch = ?`"，执行期实测**必须**连同绑定参数一起去掉，
   否则红点不是被保护的断言。记此一处，避免后来者照抄半截变异体。
2. **m2 变异体需去 import**：同上，是 `test` 阶段含 Checkstyle 的直接后果。
3. **spec §3.2 的范围收窄**：spec §3.2 把 `/api/map/overview` 的 `terrainTypes` 从 `[key…]` 改为完整定义；
   **T1 派单书 §6 硬约束要求 T1 保持 `[key…]`**（切换与前端重写同批归 T2）。**以 T1 派单书为准**，
   并加断言钉住当前形状。T2 执行者按计划 T2 步骤 6 切换时，本断言需同步改。
4. **`terrainType` 的来源选择**：派单书 §2.4 要求"取自状态里实际的 `map.terrainTypes()`"；计划 T1 步骤 4 允许
   `TerrainCatalog.of(key)` **或** `map.terrainTypes().get(key)`。本实现取**前者**（状态来源），
   与派单书一致、比计划更严。**没有**第三个变异体去区分这两者（见 §五-2）。
5. **未动 `QueryService`**：计划步骤 6 是"若需新方法"——实测**不需要**（GuiServer 已持有 `state` 的 `GameMap`）。
   这是一处"计划给了口子但没用上"的记录，不是偏离。

---

## 五 我未能核实的

1. **`/api/map/region/{id}` 的 hex 排序只在 2 格夹具上测过**（H11/H13）。真实 19441 格地图上
   `region.hexes()` 的规模、以及排序在大量坐标下的稳定性**未测**。`Comparator.comparingInt(q).thenComparingInt(r)`
   是纯函数，风险低，但**没有实测**。
2. **`terrainType` 的测试不能区分** `map.terrainTypes().get(key)` 与 `TerrainCatalog.of(key)`：
   夹具里区域的地形定义就是 `TerrainCatalog.of("desert")`，两条路给出同一份字节。要区分需在夹具里放一个
   **与词表不同的自定义 `TerrainType`**——未做（计划明确允许两条路，故不视为缺陷）。
3. **`/api/timeline` 的多分支形状未在 HTTP 层测**：`GuiApiTest` 只打了单分支 `main`；"分岔后出现第二条线"
   由前端（T3）拼 `/api/state` 的 branches + 各分支 `/api/timeline` 实现。**库侧**的分支隔离由 `TimelineTest` 直证，
   **HTTP 侧的多分支**未证。
4. **`listRevisions` 的性能未测**：一次 `SELECT` 全量行 + 逐行 `mapRow`。revision 数是推进次数（非 hex 数），
   当前规模下无感；**没有实测**大分支。
5. **`/api/map/region/{id}?revision=` 指向**中间** revision 的行为未单独测**：只测了 head（无 `revision` 参数）。
   `stateAt` 的中间版本重放由既有 `QueryServiceTest` 覆盖，本任务未加新条。
6. **本机 `nproc=8`**（与 `CLAUDE.md` 记的 `nproc=2` 不同——换机了）。全程仍遵守"一次只跑一个 Maven"，
   未跑 `mvn install`。
7. **未测 `/api/timeline` 的 405**：`allowedMethod` 认 `/api/timeline` 为 GET（`GET_ROUTES` 命中），
   但"POST /api/timeline ⇒ 405 + Allow: GET"这条**未单独断言**（既有 `unknownApiPathIs404AndWrongMethodIs405`
   用的是 `/api/command`）。机制同一份代码，风险低。

---

## 六 证据索引

```
.superpowers/sdd/2026-09-19-webui-plan/t1-evidence/
├── logs/
│   ├── targeted.log       # 定向 rc=0，TimelineTest 16 / GuiApiTest 16
│   ├── full-verify.log    # 全量 rc=0，830 条 170/255/45/131/154/75，BugInstance 0 ×6，[ERROR] 0
│   ├── m1.log             # R3 变异轮（含装置补记 md5）
│   └── m2.log             # R5 变异轮（含装置补记 md5；含首轮作废后的第二轮）
├── mutants/
│   ├── mut-round.sh       # 九道门禁装置（worktree 适配、module/test 参数化）
│   ├── orig/Timeline.java # 原件备份（md5 91b0c2ec…）
│   ├── orig/GuiServer.java# 原件备份（md5 7b1b10ab…）
│   ├── m1/Timeline.java   # 去 WHERE（md5 e6fa059b…）
│   └── m2/GuiServer.java  # regionOfHex→空（md5 82a15371…）
├── mut-manifest.md5       # orig/mutant/工作树 三方 md5 清单
└── t1-report.md           # 本报告
```

**关键 md5（自指）**：`Timeline.java` 原件 `91b0c2ecfe64a0b3978b51d88d860ca1`（m1 后工作树还原同值）；
`GuiServer.java` 原件 `7b1b10ab2c87458ee8a801865da1e264`（m2 后工作树还原同值）。
