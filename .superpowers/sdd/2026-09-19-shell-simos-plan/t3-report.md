# M5 T3 报告 —— 查询层 + 两个真 Facet

> **任务**：M5 T3（`docs/superpowers/plans/2026-09-19-shell-simos-plan.md` §二 T3）
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/m5t3`，分支 `m5/t3`，基线 `b4b015a`（PREFLIGHT 已自证）
> **日期**：2026-09-19

---

## 1 交付物

### 新增源码

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/facet/UnitsHereFacet.java` | `FacetProvider`，`facetName()=="unitsHere"`；subject 只认 canonical `map:<mapId>:hex.<q>_<r>`（Address AST 判定）；`effectivePosition(id, ctx.at())` 命中者各产出一条 `FacetEntry("unit", name, "Unit", "unit:<id>")`，按 unit id 字典序；外来主体/空格/非法坐标名 ⇒ 空列表 |
| `simos-social/src/main/java/io/mosire/simos/social/facet/PopulationFacet.java` | `FacetProvider`，`facetName()=="population"`；同 subject 形态；`FacetEntry("social", "q_r", "Population", populations().get(hex).valueAt(ctx.at()))`；无序列 ⇒ 空列表 |
| `simos-app/src/main/java/io/mosire/simos/app/query/QueryService.java` | 构造 `(CoreSimos, ResolverRegistry, FacetRegistry)`；`stateAt(QueryTarget)`（revision==null ⇒ head）/`resolve(text, target)`/`facets(text, target)`/`facetNames()`；内嵌 `record QueryTarget(BranchId, RevisionId)` + `head()/at()` 工厂 |

### 修改源码

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | `start()` 增装配：`ResolverRegistry`（Map/Social/Unit 三 resolver）+ `FacetRegistry`（unitsHere/population 两面）→ `QueryService`；新增 `queryService()`；类 javadoc 的装配清单补查询层；字段/构造器扩到四参 |

### 新增测试

| 文件 | 条数 |
|---|---|
| `simos-unit/src/test/java/io/mosire/simos/unit/facet/UnitsHereFacetTest.java` | 9 |
| `simos-social/src/test/java/io/mosire/simos/social/facet/PopulationFacetTest.java` | 8 |
| `simos-app/src/test/java/io/mosire/simos/app/query/QueryServiceTest.java` | 10 |

### 证据与装置

`t3-evidence/`：`logs/{targeted,full-verify,m1,m2}.log` + `mutants/{mut-round.sh,m1.QueryService.java,m2.Shell.java,orig/{QueryService.java,Shell.java}}`。

---

## 2 实测数字

### 定向（`./mvnw -pl simos-app,simos-unit,simos-social -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='QueryServiceTest,UnitsHereFacetTest,PopulationFacetTest' test`）

`rc=0`；`PopulationFacetTest` 8 / `UnitsHereFacetTest` 9 / `QueryServiceTest` 10 = **27 条全绿**（`logs/targeted.log`）。

### 全量（`./mvnw clean verify`，`logs/full-verify.log`）

`rc=0`；**762 = 170 / 255 / 45 / 131 / 147 / 14**（util / map / social / unit / core / app）——**7/7 reactor entries SUCCESS**；
`BugInstance size is 0` **×6**；`^[ERROR]` **0 行**；未跑 SpotBugs 的单点 goal（走的就是 `verify`）。

**对基线 735 = 170/255/37/122/147/4 的逐模块差**：

| 模块 | 基线 | 现在 | Δ | 来源 |
|---|---|---|---|---|
| util | 170 | 170 | 0 | 未动 |
| map | 255 | 255 | 0 | 未动 |
| social | 37 | 45 | **+8** | `PopulationFacetTest` |
| unit | 122 | 131 | **+9** | `UnitsHereFacetTest` |
| core | 147 | 147 | 0 | 未动 |
| app | 4 | 14 | **+10** | `QueryServiceTest` |
| **合计** | **735** | **762** | **+27** | |

前五个模块中 util/map/core 一个不动，social/unit/app 各恰好 +8/+9/+10 ＝ 三个新用例类，**与预期逐条相符**。

---

## 3 变异自证（≥2 轮，九道门禁）

装置 `mutants/mut-round.sh`（照 M4 task-17 九道门禁骨架，`ROOT` 改为本工作树；按目标类名 glob 删陈旧 `.class`，并删旧 surefire 报告逼本轮重写）。

| m | 护栏 | 变异 | 结果（轮 → 红点） |
|---|---|---|---|
| m1 | **R6** 查询参数原样转交 | `QueryService.contextAt` 把 `state.meta().timestamp()` 换成 `SimosTimestamp.of(0)` | rc=1、编译错误 0、`Tests run: 10`；红在 `QueryServiceTest.facetsForwardTheExactAddressAndContextToTheProvider:199`（`assertThat(received.at()).isEqualTo(T7)`）＋ `facetsThroughTheShellReturnUnitsHereAndPopulation:170`；工作树逐字节还原（orig=worktree=restored=`4ceb0600…`） |
| m2 | **R7** Facet 装配完整性 | `Shell` 删除 `UnitsHereFacet` 注册与 import（只留 population） | rc=1、编译错误 0、`Tests run: 10`；红在 `QueryServiceTest.facetNamesContainsBothRealFacetsInRegistrationOrder:102`（`containsExactly("unitsHere","population")`）＋ `facetsThroughTheShellReturnUnitsHereAndPopulation:165`（`containsExactly("unit","social")`）；工作树逐字节还原（orig=worktree=restored=`1325d594…`） |

九道门禁逐项落位：① 字节不同（orig_md5 ≠ mutant_md5，见各轮日志与终端）；② 干净世界（备份存在时要求与工作树同 md5，不一致即当轮作废）；③ 变体**按目标类名**推到目标路径（`QueryService.java` / `Shell.java`）；④ `COMPILATION ERROR`=0 且 `Tests run:`≥1；⑤ surefire 报告 mtime（m1 `1789764740`≥`1789764736`，m2 `1789764749`≥`1789764743`）落在本轮；⑥ 红点落在被保护断言（上表行号）；⑦ `cp` 还原并核 md5（**未用** `git checkout --`）；⑧ 本轮推送 md5 与还原 md5 **追加进日志本身**（各 `.log` 末的"装置补记"段）；⑨ 目标类名白名单落地（非按变异文件名拷入）。

---

## 4 取代说明 / 偏离

1. **spec §5.2 `unitsHere` 的值形态被 T3 派单细化（取代说明候选）**：spec 表写"该格上的单位摘要（`List<String>`：`unit:<id> <name>` 形）"，派单 §3.1 明确改为**一单位一条** `FacetEntry`，`value = "unit:<id>"`、`label = unit.name()`、按 unit id 排序。已按**派单**落地（一条一个 facet 条目对 GUI/MCP "结构化 value"更有用；spec §〇.3-5 的类型契约仍是 String）。**回填计划 §四的取代说明汇总。**
2. **`PopulationFacet` 的 `label` 未在 spec 指定**：取 hex 的展示形 `q_r`（如 `1_1`）。非偏离，仅补白。
3. **facet 只服务 canonical `hex.<q>_<r>`（Entity 段），Index Human 形 `map:Map1:[1,1]` ⇒ 空列表**：facet 输入契约定为 canonical 主体（spec §5.2）。**连带语义**：`QueryService.facets` 按 R6 要求**不**改写转交的 `Address`，故调用方传 Human 形会得到空 facet；`resolve` 才会 canonical 化。T5/T8 应先 `resolve` 或直接传 canonical hex（报告在此明记）。
4. **非法坐标名（`map:Map1:hex.xyz`）⇒ 空列表而非抛**：与 `MapResolver` "认领 kind 的名字解析失败即抛"不同；这里遵 facet 契约"空列表 = 没有内容，不是错误"。已在两个 facet 的测试里各钉一条。
5. **`QueryTarget` 内嵌于 `QueryService.java`**（spec §5.1 把它画成并列 record）：派单只列 `QueryService.java` 一个新文件，故内嵌，不另立文件。
6. **`facetNames()` 是当前 FacetRegistry 唯一的读面**：util 的 `FacetRegistry` 只有 `register/facetNames/queryAll`，**没有按名查询**，故 `QueryService` 层不存在"未注册 facet 名"这条路径——R7 落地为"注册集合完整性 + queryAll 结果"。

---

## 5 我未能核实的

1. **spec §5.1"未注册 namespace / 未注册 facet ⇒ 明确失败"的 facet 一半不可执行**：`facetNames()` 是唯一读面、`queryAll` 不接收 facet 名；"未注册 facet 名"在本层**无 API 可达**（见 §4.6）。未注册 namespace 的明确失败已由 `QueryServiceTest.unknownNamespaceFailsExplicitly` 直证。
2. **`stateOf`/`dataOf` 的"切片类型不对"分支未测**：只测了切片缺席（抛）。要测类型不对需造一个 `namespace="unit"` 却非 `UnitSnapshot` 的替身 `Snapshot`——本任务未做，属已知缺口。
3. **每查询重放的代价未测**（spec §13.4 未决项）：小规模夹具下未量时间；缓存不做。
4. **`FacetEntry.value`（Long/String）在 GUI/MCP 的 JSON 落形未验**：无消费者（T5/T8）；本任务只保证它是 `Number`/`String`。
5. **两个 facet 在同一 hex 上的装配顺序**（unit 先、social 后）已由 `facetNames()`/`facets()` 钉住注册序，但 spec 未要求某种业务序；GUI 若需别的分组自定。
6. **人口 label 取 `q_r`** 未经 GUI 评审（§4.2）。

---

## 6 证据索引

| 证据 | 路径 |
|---|---|
| 定向测试日志（27 绿） | `t3-evidence/logs/targeted.log` |
| 全量门禁日志（762 绿、BugInstance 0×6、ERROR 0、7/7 SUCCESS） | `t3-evidence/logs/full-verify.log` |
| 变异轮 m1（R6） | `t3-evidence/logs/m1.log` |
| 变异轮 m2（R7） | `t3-evidence/logs/m2.log` |
| 变异装置 | `t3-evidence/mutants/mut-round.sh` |
| 变异体 / 原件备份 | `t3-evidence/mutants/{m1.QueryService.java,m2.Shell.java,orig/}` |
