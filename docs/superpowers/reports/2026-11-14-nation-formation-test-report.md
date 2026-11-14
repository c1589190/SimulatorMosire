# 国家与完整军队编制编辑 — 统一测试报告

> **状态**：测试完成；**full gate PASS**（`tools/gate.sh full` rc=0，新日志 `/tmp/simos-nation-formation-full5.log`）。
> **日期**：2026-11-14（+08:00）。
> **任务书**：`/tmp/simos-nation-formation-test-task.md`。
> **设计**：`docs/superpowers/specs/2026-11-14-nation-formation-editor-design.md`（§1 阶段 1–7、§6 正负验收）。
> **生产台账**：`docs/superpowers/reports/2026-11-14-nation-formation-impl-ledger.md`。
> **边界**：只写 `simos-{gov,army,unit,app}/src/test/**` 与本报告/进度；不 commit/push；生产代码冻结。
> **外部探针**：真实 Shell 长跑/Chrome/MCP 由控制方执行；本报告不把它们的结论冒称 JUnit/full。

## 1. 结论摘要

| 项 | 结果 |
|---|---|
| Java 测试（fresh Surefire XML，18 模块合计） | **3847 tests / 0 failures / 0 errors / 7 skipped（517 个报告）** |
| 前端门禁（同一次 full 运行） | **497 tests / 497 pass / 0 fail / 0 skip** |
| 新 Java 测试 | `NationFormationEditorEndToEndTest` 5 条、`FormationNodeAndCreateUnitTest` 7 条 |
| 新 JS 测试 | `nation-formation-editor.test.cjs` 15 条 |
| 变异自证 | 2 项均“红 → 恢复 → md5 对拍” |
| `tools/gate.sh full` | **PASS（rc=0）**；clean verify + validate 全 19 个 reactor 条目 SUCCESS，含 Spotless/Checkstyle/SpotBugs/Surefire/前端/shade |
| 生产 SpotBugs | 19 模块 `BugInstance size is 0`（app 最后一条为 0）；无新增盲抑制、无 MUTANT |
| 生产 bug / 阻塞 | 无（上一轮 app EI 已由生产修复：`RenderService` 只暴露 `Function pngPublisher()`，`mapReferences` 恢复精确共享实例注解；本轮未改 main） |

**本报告结论：full gate 通过**。真实 Chrome/MCP/长跑仍由控制方外部探针承担，本报告不把它们冒称 JUnit/full。

## 2. 目标阶段覆盖

| spec 阶段 | 本轮测试证据 |
|---|---|
| 1 定位/冻结目标 | 真 Shell + 固定 HTTP 桥：`POST /api/editor/nation`、`POST /api/editor/formation` 的 expectedRevision/冻结目标；JS 宿主 `writeEditorTool` 覆盖 mode-denied/bad-target/迟到响应。 |
| 2 领域计划 | 一次国家 JSON → map/unit/gov/social；一次编制 JSON → social/unit/army；测试断言命令类型有序批，不逐节点写。 |
| 3 完整校验/原子提交 | 后序 handler 实际拒绝（非 plan 前置）后 head/epoch 与八个相关模块不变；独立 Core `submitBatch` 合法前半 + 非法后半整批 Rejected。 |
| 4 真实世界状态 | 重放真实 `SimulationState` 断言 Nation/Region/Unit/Social/Army；economy/actor/ga 切片前后相等；冷启动同 store 重读。 |
| 5 共同移动 | 创建缺省子坐标写实父位置；root 默认 attached=false、子默认 true；detach 后 attached 后代同步、detached 子树不动、兄弟/祖先不动。 |
| 6 读回/显示 | `simos.gov.nations` 并集/颜色/名称/GOV；`simos.army.formation` 树/直接与子树人数/attached；PNG 真解码、尺寸/像素/assetId sha256，摘要数字与树一致。 |
| 7 网页一体化 | 两模块载入导出可提交 JSON、保存走共享 host、同次 completedTarget 自动读回、失败/迟到隔离；大图 dialog fit/原图/±缩放/关闭 + 原生 dialog Esc。 |

## 3. 正向测试（真实链路）

`simos-app/src/test/java/io/mosire/simos/app/NationFormationEditorEndToEndTest.java`（5 条）：

1. **一次建国家/GOV/整师**：真 `Shell`（blank-editor）+ `map.ImportRaster` 建图 + 固定 HTTP 桥；一次 `editNation` 只 +1 revision，命令恰为 `map.CreateRegion → gov.CreateNation → unit.CreateUnit → unit.SetGovFormation → unit.SetJurisdiction → social.SubmitHouseholdWorkOrder`。断言 Nation.name/homeRegion/颜色、精确 `nation:demo-nation` tag、4 格并集、GOV Unit/GovernmentFormation/jurisdiction、0 人 `hh-gov-demo-gov` 家户真实存在且 location=UNIT(demo-gov)。一次 `editFormation` 只 +1 revision，9 节点整师（2 旅各 3 营）真实 Unit/Social/Army，root 本级 60、子树 3040；economy/actor/ga 前后逐字段相等；国家/编制 PNG 真解码且有非背景像素；关闭 Shell 重开同 store 后树/人数/领土/图仍可读。
2. **update 保留 + 改编**：改名、装备、人数增/减、加营、改隶属、detach 第 2 旅到 (2,1)；未提及节点 `demo-bde-1-bn-3` 的 Unit 记录除 masterGov 外逐字段保留，军俸政策保留；detached 旅的未提及 attached 后代跟到 (2,1)，兄弟/祖先不动；`masterGov` 显式变更扩散到未提及节点的 ArmyFormation 且不丢政策；直接人数求和 = 子树合计（3340），无重复人口。
3. **两类后序 handler 拒绝 + Core 批**：editNation 的 `unit.SetGovFormation` 因既有 unit 悬空占用政府家户被 UnitState 拒；editFormation 的 `social.SubmitHouseholdWorkOrder` 因同名家户已存在被 Social 拒；两者均 422、head 不变、map/social/unit/gov/army/economy/actor/ga 全部不变。另直接向 `CoreSimos.submitBatch` 提交合法 `map.CreateRegion` + 非法 `unit.SetJurisdiction`，整批 `Rejected`、零 revision、区域未落。
4. **多区域国家**：两个同 tag 区域并集按格去重（重叠格只算一次）；默认 overlapPolicy=reject 返回 400 且列出冲突国家；显式 transfer 才从邻国区域扣目标格且保留非空实体；update 把并集集中到 homeRegion，其他同 tag 区域只解除 tag、保留 ID/几何/description；非国家区域逐字不动；名称/颜色/PNG 摘要一致；未知 territoryMode/overlapPolicy、dryRun 重复/未知格均 400。
5. **具名负向**：重复节点/小数/负数/溢出、attached 跨格、图外坐标、深度 17、513 节点、masterGov 不存在、改 root、越其他 Army；国家坏色值/重复格/新 GOV 无位置/新 GOV 图外；stale revision 返回 409 且不推进。

`simos-unit/src/test/java/io/mosire/simos/unit/spi/FormationNodeAndCreateUnitTest.java`（7 条）：

- `CreateUnit` 省略子坐标 ⇒ 写实父位置、attached=true、ChangeSet 往返；
- 父无位置时省略子坐标 ⇒ 具名拒；
- `UpdateFormationNode` 改 parent/attached 即使位置相同也清旧路线；
- 只同步该节点的 attached 后代，detached 子树/兄弟/祖先位置保持；
- 改 name/equipment/speed/mobility 时其余 17 组件全保留，ChangeSet 完整往返；
- `FormationNodePatch` 输入 List 事后修改不穿透、输出集合不可写、`null` 仍表示“保持”。

## 4. 负向/判别力要点

- 判据优先读**重放后的真实状态**，不读工具回显；一次工具调用只允许 +1 revision 是硬断言。
- 原子回滚选的是“plan 全过后、后序 handler 实际拒绝”的路径（GOV 家户占用、Social 家户重复），不是只测前置校验。
- 国家并集按格去重、默认冲突拒绝、transfer 不划空、非国家区域不动都有逐字段断言。
- Unit 原语：父无位置拒、缺省坐标写实、parent/attached 变化清 movement、只同步本节点附属后代、全字段保留。
- JS：载入导出的 JSON 只含可提交字段；保存用 host 返回的 completedTarget（新 revision/epoch）自动读回；迟到响应不写新节点；失败不自动读回；DOM 不 innerHTML；大图复用同一 PNG。

## 5. 精确统计（同一次 full 运行/本轮探针）

| 计数 | 值 | 证据/位置 |
|---|---:|---|
| GM 运行时桶 | **196** | `ShellAssemblySmokeTest`、`McpPortTopologyTest`、`McpServerTest` |
| 工具三分类并集 | **209** | `ToolClassAuditAssemblyTest` |
| 类1 GM_ONLY / 类2 DM_APPROVAL / 类3 DM_FREE | **145 / 25 / 39** | `ToolClassAuditAssemblyTest` |
| 共享（GM+DM） | **51** | `ToolClassAuditAssemblyTest` |
| 命令类型 catalog/注册 handler | **156** | `SimosToolsTest`、`McpCoverageTest` |
| 磁盘 `*Tool.java` 扫描面 | **186** | `SimosToolsTest` |
| 新工具 | `simos.gov.editNation`、`simos.gov.nations`、`simos.army.editFormation`、`simos.army.formation`（均 GM-only，无新增命令计数之外的工具桶） | `SimosToolsTest`/MCP 测试 |
| 新命令 | `gov.UpdateNation`、`army.UpdateArmy`、`unit.UpdateFormationNode` | `EXPECTED_COMMAND_TYPES`/`CatalogTool.PAYLOAD_HINTS` |
| 模块 webui manifest | map(version 3, 5 assets)、gov(1, nation-editor.js)、army(1, formation-editor.js) | `WebuiAssetsTest` |
| Java fresh Surefire | 3847 / 0 fail / 0 err / 7 skip（517 个测试类文件） | 见 §7 |
| JS | 497 / 497 / 0 fail / 0 skip | `[frontend-gate] OK tests=497` |

历史“3835 Java / 482 JS”只作沿革；本轮实际为 **3847 Java / 497 JS**。

## 6. 变异自证（2 项，均已恢复）

| # | 变异 | 预期靶 | 实际结果 | 证据 |
|---|---|---|---|---|
| M1 | 临时移除 `CreateUnitHandler` 的“省略子坐标 ⇒ 写实父位置”赋值 | `FormationNodeAndCreateUnitTest#createUnitWithOmittedChildPositionWritesParentPositionAndRoundTrips` | 测试红：`NoSuchElementException: No value present`（Handler:107） | `/tmp/simos-mutation-1.log`；恢复后 md5 `bae81b855c147a6989ed38f37e35481d` |
| M2 | 临时移除 `GovEditNationPlan.transfer` 的 `overlapPolicy=reject` 守卫 | `NationFormationEditorEndToEndTest#updatesMultiRegion…` | 测试红：`expected: 400 but was: 200`（默认冲突被静默提交） | `/tmp/simos-mutation-2.log`；恢复后 md5 `307354686a79404ae597a007fcc363b0` |

两次均从备份恢复并用 `md5sum -c`/双 `md5sum` 对拍；未把变异留在工作树。

## 7. full gate 实际执行

```bash
cd /home/cna/SimulatorMosire
tools/gate.sh full > /tmp/simos-nation-formation-full5.log 2>&1
echo $? > /tmp/simos-nation-formation-full5.rc   # 0
```

- 唯一 Maven 进程；未带任何 skip；耗时约 **302s**。
- 日志 `/tmp/simos-nation-formation-full5.log`，rc=`/tmp/simos-nation-formation-full5.rc`=**0**。
- `Bash` 原始 verify 日志：`/tmp/simos-gate-full-verify-pqUyAX.log`；validate 日志：`/tmp/simos-gate-full-validate-cjVxsS.log`。
- gate 正向证据：
  - `[frontend-gate] OK tests=497 pass=497 fail=0`
  - 全仓 Surefire `tests=3847 failures=0 errors=0`（报告 517 个）
  - shaded jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`（**32,358,355 B**）
  - Spotless / Checkstyle / SpotBugs / exec 前端 / shade / enforcer 插件均实际执行
  - 七个护栏类（ArchitectureGuards / Ga / Gov / Army / ShellAssembly / Production / Culture RoundTrip）逐类 tests>0 且 0 失败
  - `[gate] ✓ full 结论：PASS —— clean verify rc=0 + validate rc=0 + 证据齐`
- **未覆盖 full4 旧日志**；full4 是上一轮 BLOCKED 证据，保留原样。

### fresh Surefire 分模块合计

```text
simos-actor 130 · actor-api 8 · app 1094(7 skip) · army 109 · calendar 33
core 248 · culture 22 · economy 520 · economy-api 50 · ga 22 · ga-spi 44
gov 33 · map 446 · production 118 · social 217 · social-api 3
unit 526 · util 224
TOTAL 3847 tests / 0 failures / 0 errors / 7 skipped
```

## 8. 生产最终修复复核（本轮未改 main）

上一轮 full4 的 app `EI_EXPOSE_REP` 已由生产 Owner 修复并交回；本轮 full5 只验证、不改 `src/main`：

- `RenderService` 不再新暴露 public `putPng`，改为 `Function<byte[], String> pngPublisher()`（窄闭包）；`SimosToolSource` 两处注入该窄 publisher。
- `RenderService.mapReferences()` 恢复**既有** `@SuppressFBWarnings("EI_EXPOSE_REP")`，理由明确：GUI HTTP 与 MCP 工具必须拿同一服务实例（同一缓存/参考图身份），不是可复制的世界状态值；没有改成值记录、没有用宽泛 suppress。
- 本轮 record 集合输入做防御性复制（`FormationNodePatch` 等），`null` 仍表示“未填/保持”；测试新增的 Patch 复制/不可写断言已通过。
- 复核：`grep -R MUTANT` 在 `simos-{unit,gov,army,app}/src/main` 为空；新增 main 注解只有上述精确共享实例解释，没有新增盲抑制。
- 生产自证日志：`/tmp/simos-nation-formation-spotbugs-renderport.log`（全 19 模块 BugInstance 0）；本轮 full5 再次得到 app `BugInstance size is 0`。

## 9. 未做 / 未验证

- **未做** 真实 Chromium 操作、真实 MCP `tools/list`/`tools/call` ImageContent、真实长跑推进/冷启动演示：由控制方外部探针执行，本报告不冒充。
- **未改** 生产 `src/main`；未 commit/push；保留所有既有 dirty。
- **未验证** 512 节点性能/超大 PNG 可读性；单测只钉规模/深度拒绝与 9 节点图。
- **未验证** 真实 `as=` 视角下的国家/编制读口（固定桥明确拒绝 as=，HTTP 负例已测）。
- **未验证** 真实浏览器原生 Esc 行为；JS 断言的是两宿主页使用原生 `<dialog id="formation-viewer">` + 关闭按钮接线，Esc 由浏览器 dialog 语义承担。

## 10. 变更文件（测试/报告）

- `simos-app/src/test/java/io/mosire/simos/app/NationFormationEditorEndToEndTest.java`（新）
- `simos-app/src/test/java/io/mosire/simos/app/gui/WebuiAssetsTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/tools/ToolClassAuditAssemblyTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/tools/write/AdjudicateTickToolTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/ShellAssemblySmokeTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java`
- `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java`
- `simos-app/src/test/js/nation-formation-editor.test.cjs`（新）
- `simos-app/src/test/js/write-allowlist.test.cjs`
- `simos-app/src/test/js/gate-contract.test.cjs`
- `simos-app/src/test/js/run-gate.cjs`
- `simos-unit/src/test/java/io/mosire/simos/unit/spi/FormationNodeAndCreateUnitTest.java`（新）
- `simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`
- 本报告与 `/tmp/simos-nation-formation-test-progress.md`
