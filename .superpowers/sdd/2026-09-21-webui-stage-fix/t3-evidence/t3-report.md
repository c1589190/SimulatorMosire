# T3 报告 —— 连通性手势对齐 GSimulator + 往返守卫覆盖连通性 + 词表「默认 + 可自定义」

> 计划：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` § T3
> spec：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` §三（编辑线）、§七.1（C1~C5/C34）
> research：`...-research.md` §A.2（手势）、§A.3（约束）、§A.4（往返守卫）
> 分支 `wsf/t3`，基线 `12fb1f0`（T2 合并）。证据根 `.superpowers/sdd/2026-09-21-webui-stage-fix/t3-evidence/`。

## 〇 三个要点与落地

| 要点 | 落地 | 判据 |
|---|---|---|
| **A 手势** | `map.js`：waypoint 状态机 `edgeChainResult`（同格结束 / 非相邻跳过）、12px 命中 `edgeHitAtWorldPoint`、左键点拖删 `edgeDeletePlan`、缺格/非相邻可见提示、双向由 `edgeKeyOf` 规范序保证 | C3 |
| **B 词表默认+自定义** | `EdgeOperations.KINDS` 删除；kind 校验 = `base.pathwayGroups().keySet()`（`resolveKind`）；默认两组经 `PathwayGroup.defaults()` 供给（DemoWorld + 测试夹具）；新命令 `map.RegisterPathwayGroup` + `PathwayGroupOperations.register` | C34 |
| **C 往返守卫** | `RoundTripComponentsTest.connectivityRoundTripsWithNonEmptyEdges`（非空 edges 逐值）；反射循环原有 edges 覆盖 | C4 |

## 一 文件清单

**生产**
- `simos-map/.../pathway/PathwayGroup.java`：+`defaults()`（river `#3295D2` / road `#8B7355`）。
- `simos-map/.../ops/EdgeOperations.java`：删硬编码 `KINDS`；`resolveKind(base, kind)` 从 `base.pathwayGroups().keySet()` 派生化（大小写不敏感，返回组 id 原文）。
- `simos-map/.../ops/PathwayGroupOperations.java`（新）：`register(base, group)`，重复 id fail-closed。
- `simos-map/.../spi/RegisterPathwayGroupHandler.java`（新）：`map.RegisterPathwayGroup`。
- `simos-map/.../spi/MapPayloads.java`：+`requirePathwayGroup`（id/name/color 必填；description/visible/properties 可选）。
- `simos-app/.../Shell.java`：注册新 handler（40 → 41）。
- `simos-app/.../tools/read/CatalogTool.java`：`PAYLOAD_HINTS` 补 `map.RegisterPathwayGroup`（构造期强制）。
- `simos-app/.../demo/DemoWorld.java`：corridorMap 补 `PathwayGroup.defaults()`。
- `simos-app/.../gui/ApiViews.java`：overview 新增 `pathwayGroups`（组定义）+ `edges`（全图边表，`[{edge,pathways}]`，EdgeRef 自然序、pathways 字典序）——前端 kind 候选与左键删边的唯一数据来源。
- `simos-app/.../webui/map.js`：手势状态机 / 12px 命中 / 左键删边 / 缺格提示 / 动态 kind 候选 / 边渲染。
- `simos-app/.../webui/index.html`：连通性帮助文案（左键点/拖删边）。

**测试**
- `simos-map`：`EdgeOperationsTest`（+5：自定义组可用/不放宽/大小写/重复注册/只动 pathwayGroups）、`RoundTripComponentsTest`（+1：非空 edges 往返）、`SetEdgeHandlerTest`（夹具补默认组）。
- `simos-core`：`MapSetEdgeEndToEndTest`（+1：真 store 注册 canal → SetEdge 成功；未注册拒 + 无 revision）。
- `simos-app`：`McpCoverageTest`（+1 载荷、41 type、head 42/43）、`SimosToolsTest`（+type、扫描 41）。
- 前端：`map-edit-tools.test.cjs`（+6）、`run-gate.cjs`/`gate-contract.test.cjs` 下界 108 → 114。

## 二 门禁（`./mvnw clean verify`）

| 项 | 值 |
|---|---|
| 第 1 次尝试 | **rc=0**（`logs/clean-verify.log`，`Finished` 后 `rc=0`） |
| 变异轮后复跑 | **rc=0**（`logs/clean-verify-after-mutants.log`） |
| 模块 | **8/8 `SUCCESS [`**（`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp`） |
| 用例总数 | **1288** = `170/368/45/259/178/124/144`（现场重算，只取模块汇总行；`logs/recomputed-total.txt`） |
| `BugInstance size is 0` | **×7** |
| `[ERROR]` | **0 行** |
| 前端门禁 | `[frontend-gate] OK tests=114 pass=114 fail=0` |

**delta（对 T2 合并基线 1281 = 170/362/45/259/177/124/144）**：Map **+6**（362→368）、Core **+1**（177→178）、App **0**、其余逐值不变 ⇒ **+7** = 1288。

## 三 判据逐条实测

- **C1/C2（T2 的，未回归）**：子选项互斥与写门控由 `map-edit-suboptions.test.cjs` 12 条 + `map-edit-tools.test.cjs` 静态门控继续钉住；本次只把 connectivity 的**工具集**从静态 `["river","road"]` 改为 `registeredEdgeKindList()`（默认仍是那两组）。
- **C3 手势**（`map-edit-tools.test.cjs` 纯函数层）：
  - 右键拖动 ⇒ `edgeChainEdges` 产出规范边键，宿主合成**一条** `map.SetEdge`（`commitEdge`）。
  - 左键点/拖 ⇒ `edgeDeletePlan` 产出 `replace` 载荷（该 kind 其余边）；`edgeDeletePlan-replaces-with-the-surviving-set` 逐值。
  - 起点→续点→**同格 ⇒ 结束**：`edgeChainEdges-same-hex-as-the-anchor-ends-the-path` + `edgeChainResult-reports-ending-and-skipped-non-adjacency`（`ended=true`）。
  - 非相邻续点 ⇒ **不产出、端点不动**、`nonAdjacent=true` 供可见提示：`edgeChainEdges-skips-a-non-adjacent-jump-and-keeps-the-anchor`。
  - **12px 阈值**：`edgeHitAtWorldPoint-honours-the-12px-threshold-and-kind`（阈值内命中、阈值外 `null`、kind 过滤）。
  - **双向**：`edgeKeyOf-is-canonical-by-q-then-r` + `edgeChainEdges-dedupes-a-repeated-pair`。
  - **缺格**：渲染器 `edgeAt` 只并入图内格；`edgeChainResult` 的非相邻/缺样本只记 `nonAdjacent`，宿主给提示（`commitEdge` 文案）。
- **C4 往返**：`connectivityRoundTripsWithNonEmptyEdges` —— `apply(between(base,target),base)` 逐值等于 `target`（含非空 edges + props）。**变异 jm_apply** 让 `between` 的 edges 恒 `Unchanged` ⇒ 该断言 + 反射循环 `everyGameMapComponentParticipatesInTheChangeSet` 双双红。
- **C34 词表**：
  - 单元：`registeredCustomGroupBecomesAValidKind`（注册 canal 后 `setEdge(canal)` 成立）、`registrationDoesNotWidenTheVocabularyBeyondRegisteredGroups`（默认两组仍可、未注册 `rail` 仍拒）、`customGroupKindMatchesCaseInsensitivelyAndKeepsRegisteredId`、`duplicateRegistrationIsRejected`、`registrationOnlyChangesThePathwayGroupsComponent`。
  - 端到端（真 store/replay）：`MapSetEdgeEndToEndTest.registeredCustomGroupEnablesTheCommandWhileUnregisteredKindStaysRejected` —— 未注册 canal ⇒ `Rejected` 且 `revisions` 行数不变；注册后 `SetEdge{canal}` 落 `(main,3)`；默认 river/road 不受影响。
  - MCP 面：`McpCoverageTest` 逐 type 真提交，`map.RegisterPathwayGroup` 落 `(main,42)`、`map.SetEdge` 落 `(main,26)`；catalog 41 type。
- **C5（D14 `[待裁]`）**：**未加**后端相邻校验（D14 未裁决，spec §三.3 把它列为待裁；`map.SetEdge` 的域校验保持"两端点在图上"）。前端 `edgeChainEdges` 已拒绝非相邻段。
- **C33**：新 type 已喂饱 `McpCoverageTest`（双向载荷 + head 逐条前进）与 `SimosToolsTest`（扫描面 == 注册面，41）。

## 四 变异汇总（15 轮，15 KILLED / 0 存活）

装置：`t3-evidence/mutants/{make-mutant.py,js-round.sh,java-round.sh,orig/,logs/}`。九道门禁：干净世界 / 变异体字节不同 / 规范名 / 清陈旧 `.class`+reports / `COMPILATION ERROR`=0 且 `Tests run≥1`（**基线也证跑到了**）/ surefire mtime 落轮内 / 红点落被保护断言 / `cp` 逐字节还原 / 日志自指（先断言聚合 md5 非空）。

**JS（10）** — 目标 `map.js`（除 t2m5=gate-contract）：

| 轮 | 变异 | 红点（被保护断言） |
|---|---|---|
| sm_chain_end | 去掉"同格结束"分支 | `edgeChainEdges-same-hex-as-the-anchor-ends-the-path`、`edgeChainResult-reports-ending…` |
| sm_no_canon | 去掉 EdgeRef 双向归一 | `edgeKeyOf-is-canonical-by-q-then-r`、`edgeChainEdges-dedupes-a-repeated-pair` |
| sm_delete_keep | 删边计划不排除被删边 | `edgeDeletePlan-replaces-with-the-surviving-set` |
| sm_hit_inf | 命中阈值失效 | `edgeHitAtWorldPoint-honours-the-12px-threshold-and-kind` |
| sm_kinds_default | 空注册表兜回默认 | `registered-edge-kinds-default-then-follow-the-server` |
| sm_subtool_default | （T2 m4 重放）未知工具兜「地形」 | `mapEditSubtoolOf-maps-tools-and-rejects-unknown` 等 4 条 |
| t2m1 | 面板互斥失效 | `panel-visibility-is-mutually-exclusive` |
| t2m2 | 地形线放行 SetEdge | `write-allowed-is-per-subtool-and-cross-line-is-denied` |
| t2m3 | commitEdge 去写门 | `map-js-gates-every-map-write-with-the-matching-type` |
| t2m5 | REQUIRED_FILES 漏文件 | `all-required-test-files-are-present` |

**Java（5）**：

| 轮 | 变异（目标） | 红点 |
|---|---|---|
| jm_apply | `between` 的 edges 恒 `Unchanged`（`MapChangeSet`） | `connectivityRoundTripsWithNonEmptyEdges` |
| jm_hardcode | `resolveKind` 词表写回硬编码 `river/road`（`EdgeOperations`） | `registeredCustomGroupBecomesAValidKind` + core `registeredCustomGroupEnablesTheCommand…` |
| jm_allow_all | `resolveKind` 未注册恒放行 | `rejectsUnknownKind`、`registrationDoesNotWidenTheVocabularyBeyondRegisteredGroups` |
| jm_dupe | 去掉重复注册守卫（`PathwayGroupOperations`） | `duplicateRegistrationIsRejected` |
| jm_merge_replace | **M8 T5 m2 在当前字节上的重放**：merge 当 replace 使（`EdgeOperations`） | `mergeAddsTheNewTag…`、`mergeKeepsExistingProperties…`、core `mergeSurvivesTheRealCommandPath…` |

★ **两处装置自证（写清，别当"没发生"）**：
1. `jm_apply` **首轮 VOID**：最初写成"`apply` 直接沿用 `base.edges()`"，会让 `EdgeRef` 成为**未用 import** ⇒ Checkstyle 拦在 surefire 之前（日志 `jm_apply.log.mut`），**不是红**。改为"`between` 的 edges 恒 Unchanged"（可编译、checkstyle 干净、语义等价于旧仓缺陷）后重跑 ⇒ RED。★ 计划里"删 `MapChangeSet.edges` 组件"**结构性不可表达**（删组件 = 编译错误），故以本变体代替。
2. `java-round.sh` 的**判定口径修正**：变异轮带 `-Dmaven.test.failure.ignore=true` ⇒ 测试红了 `rc` 仍为 0；判据必须是**红点是否落在被保护断言**（基线已单独证绿），不能看 `rc`。首轮 `jm_apply` 因此误打 `SURVIVED`，修正脚本后重跑得 `KILLED`。

**裁定 42（改动既有被测文件 ⇒ 重跑相关既有变异轮）**：
- `map.js`/`gate-contract.test.cjs` 被改 ⇒ 重跑 T2 的 **t2m1/t2m2/t2m3/t2m5**（4 轮，全 KILLED）；T2 的 **m4** 因目标串已随本次改动消失，按"只重放语义改动"重派生为 **sm_subtool_default**（KILLED）。
- `EdgeOperations.java` 被改 ⇒ 重跑 M8 T5 的 **m2**（merge 当 replace），按当前字节重派生为 **jm_merge_replace**（KILLED）。
- `MapChangeSet.java` / `PathwayGroupOperations.java` 被改 ⇒ 新增 **jm_apply / jm_dupe** 各轮。

## 五 我未能核实的（诚实清单）

1. **浏览器级 e2e 未跑**：本机无 Playwright 装置接入本任务；C3 只证到**纯函数层 + 静态层**。真实右键拖拽/左键删除的像素级行为、`scrollIntoViewIfNeeded` 等未实测。
2. **served-asset 未核**：未起 `--demo` 实例核对 `/api/map/overview` 真的带 `pathwayGroups`/`edges`、`/map.js` 的 md5。overview 新字段的**真档体积影响未测**（本机无 19441 格真档路径）。
3. **左键删边经 `replace` 是 lossy 的**：命令面（spec §三.5）**没有删单条边的命令**，只能用 `replace`（该 kind 其余边）——
   - 会**重置其余同 kind 边的 props**（当前 simos 数据的 props 恒空，故今天无观测差异，但语义上是有损的）；
   - **删最后一条边不可能**（`replace` 不接受空集）⇒ 代码显式拒绝并给提示（`edgeDeletePlan` 的 `last-edge`），**不伪造命令**。
   这是 spec 层面的缺口，已如实记下，未越界新增命令。
4. **`renderEdgeKindOptions` 的 DOM 分支未被测试覆盖**：node 加载器无 DOM（`querySelector` 返回 null ⇒ 早返回），动态 radio 重建/重挂在**浏览器**里才生效；只做了静态审查。
5. **`edgeChainEdges` 语义变更**：由"非相邻断链重起"改为"非相邻跳过、端点不动；回到当前端点 ⇒ 结束"（对齐 GSimulator `addPathwayWaypoint`）。相应更新了 T2/M8 期的两条断言（**改成真断言、非恒真**）。旧 e2e 证据若断言旧语义，已过时（不在 CI）。
6. **D14 未裁决** ⇒ 后端相邻校验未加；MCP 仍可连非相邻格（C5 未兑现，属待裁）。
7. **真档连通性读写未验**（延续 M8 T11 / M9 的开口项）：全部 e2e 证据来自 `--demo` 3 格世界；`edges` 非空的真档、多 kind 并存、`properties` 非空的组均未在真档上跑。
8. **`edgeHitAtWorldPoint` 的性能**未测（每次命中 O(#边) × 2 段；248 边量级可接受，未在真档量）。

## 六 附：关键锚点

- `EdgeOperations.resolveKind`（词表派生）、`PathwayGroup.defaults()`、`PathwayGroupOperations.register`、`RegisterPathwayGroupHandler`。
- `map.js`：`edgeChainResult` / `edgeHitAtWorldPoint` / `edgeDeletePlan` / `setRegisteredEdgeKinds` / `drawEdges`。
- `ApiViews.mapOverview`（`pathwayGroups` + `edges`）。
- 门禁日志：`logs/clean-verify.log`、`logs/clean-verify-after-mutants.log`、`logs/recomputed-total.txt`。
