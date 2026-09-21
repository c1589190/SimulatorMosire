# WebUI 阶段修复 —— 台账（progress）

> 计划：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md`
> 证据根：`.superpowers/sdd/2026-09-21-webui-stage-fix/`

## T1 撤右栏审批 + 右下角通知栏 ✅（2026-09-21，分支 `wsf/t1`，基线 `92444b2`）

- **范围**：只做 T1。`webui/index.html` / `app.js` / `styles.css` + 新 `notifications.js` + 新
  `notifications.test.cjs` + 两处下界 + `WebuiAssetsTest.WORKBENCH_SCRIPTS`。
- **D1 / D10 兑现**：后端 `/api/approvals` 与 `api.approvals` **原样保留**；通知栏为 `position:fixed`
  右下角浮层（`pointer-events:auto`、`bottom:104px` 抬到时间轴之上）。
- **隐藏断点**：`write-allowlist.test.cjs:112` 的 `await api.approvals()` —— 本任务**保留** `api.approvals`、
  **不动**该文件；其"会红"由变异 **m3** 当场兑现（`not ok 95`），跑完逐字节还原。
- **门禁**：`./mvnw clean verify` **第 1 次尝试 rc=0**、**8/8 `SUCCESS [`**、**1281** =
  `170/362/45/259/177/124/144`、`BugInstance size is 0 ×7`、`[ERROR]` 0、前端 **96/96**（90→96，+6）。
- **变异**：m1/m2/m3 全 **KILLED**，红点均落被保护断言（详见 `t1-evidence/t1-report.md` §四）。
- **运行时核**（served assets）：独立端口起 shade jar ⇒ `/` 200 含 `#notifications` 且无 `approvals-count`、
  `/api/approvals` 200、`/notifications.js` 200 且 md5 与源相同；`5818` 实例未动。
- **裁定/结论**：通知栏初始 `hidden`、首次读数后显式 `hidden=false`（避免永久隐藏）；
  `notifications.js` 以 `DOMContentLoaded` 自挂载（`app.js` 保持纯撤除）。
- **我未能核实的**：见 `t1-evidence/t1-report.md` §七（浏览器布局/点击、非空待批真状态、多分支时间轴、
  其它 `api.X` 隐藏耦合、基线未在本树改动前单独重跑）。
- **证据**：`t1-evidence/`（logs/ + mutants/ + t1-report.md）。

## T1 审查（2026-09-21，审查方独立复现，`d9df5b3`）——**零代码改动**

- **报告**：`t1-evidence/t1-review.md`；独立变异证据 `t1-evidence/review-mutants/`。
- **88 vs 90 真相**：**前提为假**。T1 干净轮前端 = **96**（`clean-verify.attempt1.log:2170`、现场
  `node --test` 复跑 96/96、逐文件 `test(` 计数合计 96）；下界 **96**（`run-gate.cjs:19`、`gate-contract.test.cjs:30`，
  **升** 90→96，非降）。88 是 M9 期旧值，90 是 SD-E/unit-ext T10；T1 证据里**没有** `clean-verify.log` 这个文件。
- **隐藏断点**：`write-allowlist.test.cjs:112` 的 `await api.approvals()` **原样保留**；`api.js:252-253/277` 保留。
- **通知栏**：`notifications.js:38/41` 读 `GET /api/approvals`（动态用例恰一次）；`styles.css:1218 pointer-events:auto`；
  `bottom:104px`（:1206）静态阅读有余量；★ 布局**无 in-gate 守护**（报告 §七 已披露）。
- **后端**：`git diff --name-only` 证明 `GuiServer.java`/`Shell.java`/`ShellApprovalTest`/`GuiApiTest` **零改动**。
- **变异**：实现者 3 体全 KILLED、红点落被保护断言（复核）；**审查方补跑 4 体**：sx1（删通知栏）/sx3b（删一条测试）**KILLED**；
  sx2r/sx2c（只降单侧下界）**SURVIVED** —— ★ **控制器"下界被调低 ⇒ 门禁红"这条期望本身不成立**（下界是下界，改小不违反任何不变量；
  真正该证的是"删测试⇒跌破下界⇒红"，已由 sx3b 兑现）；两处下界互为冗余，强度 = max ⇒ **设计限制，不修**。
- **报告质量**：§七 在；**一处原始日志杂音哈希** `68d1c35f2`（`served-e2e.log` 末行，数学上不可复现）——
  审查方以独立实例（5881/5885/5883）复现 `/notifications.js` 200 / `Content-length 2940` / md5 `4d6f7bb0…` = 源 / `cmp` IDENTICAL，
  证明**报告论断正确、杂音以本次为准**；`5818`(pid 64974) 未动。
- **判定**：**未发现需修的代码缺陷**，本轮**零生产/测试字节改动**。

## T2 地图编辑下挂二级子选项（地形 / 连通性）✅（2026-09-21，分支 `wsf/t2`，基线 `611465f`）

- **范围**：只做 T2（子选项框架）。`webui/index.html` / `map.js` / `styles.css` + 新
  `map-edit-suboptions.test.cjs`（12 条）+ 两处下界 96→108 + `REQUIRED_FILES`。**零 Java 改动**。
- **C1/C2 兑现**：`#map-edit-subtools`（`terrain`/`connectivity`）下挂两组面板：地形势 =
  `#terrain-tool-controls`（地形刷/圈选随机化 + 调色板 + `#randomize-controls`）、连通性 = `#edge-controls`
  （河流/道路 + merge/replace）。互斥判定抽成纯函数 `mapEditPanelVisibility`（恰一个可见）；写门控
  `mapEditWriteAllowed`/`mapEditWriteGate`（跨线拒：地形线不发 `map.SetEdge`、连通性线不发 `map.SetTerrain`），
  三个写点（`commitBrush`/`submitRandomize`/`commitEdge`）各加写门。
- **白名单不变**：`modes.js` **一行未改**（`map-edit` 仍恰 4 条）；模式栏仍 **5** 个按钮（子选项**不是**新模式）。
- **边界**：**未做**连通性手势（T3）、**未动** `EdgeOperations.KINDS`（T3）。
- **取代说明（执行期）**：原 4 工具 radio 组 `#map-edit-tools`/`name="map-edit-tool"` **拆成三个 radio 组**
  （`map-edit-subtool`/`map-edit-terrain-tool`/`map-edit-edge-kind`）⇒ 旧 id/name **消失**（served 实测 ×0）；
  `mapEditDebug` 增 `subtool`/`hostSubtool`。★ M8 期历史 Playwright e2e 的
  `input[name="map-edit-tool"]` 选择器**已过时**（历史证据，不在 CI；本任务不改历史证据）。
- **门禁**：`./mvnw clean verify` **第 1 次尝试 rc=0**、49s、**8/8 `SUCCESS [`**、**1281** =
  `170/362/45/259/177/124/144`、`BugInstance size is 0 ×7`、`[ERROR]` 0、前端 **108/108**（96→108，+12）。
  **delta 干净**：零 Java 改动 ⇒ Java 逐值不变；前端 +12 = 新测试文件。
- **变异**：m1（面板互斥失效）/ m2（地形线放行 SetEdge）/ m3（`commitEdge` 去写门）/ m4（`mapEditSubtoolOf` 兜默认）/
  m5（`REQUIRED_FILES` 漏新文件）全 **KILLED**，红点均落被保护断言（详见 `t2-evidence/t2-report.md` §四）。
- **运行时核**（served assets）：独立端口（5891/5895/5893）起 shade jar `--demo` ⇒ `/` 200 含
  `#map-edit-subtools`×1 / `#terrain-tool-select`×1 / `#edge-kind-select`×1、旧 `#map-edit-tools`×0；
  `/map.js` 200 且 md5 与源相同（`5078c2c2…`）；`5818`(pid 64974) 未动。
- **我未能核实的**：见 `t2-evidence/t2-report.md` §七（浏览器内点击/拖动未实测——C1/C2 只证到纯函数+静态+served
  三层；CSS 无像素证明；线内工具回退到默认属执行期形态决定；基线未在本树改动前单独重跑）。
- **证据**：`t2-evidence/`（logs/ + mutants/ + t2-report.md）。

## T3 连通性手势 + 词表默认可自定义 + 往返守卫覆盖连通性 ✅（2026-09-21，分支 `wsf/t3`，基线 `12fb1f0`）

- **范围**：只做 T3。`simos-map`（`EdgeOperations`/`PathwayGroup`/`PathwayGroupOperations`(新)/`MapPayloads`/`RegisterPathwayGroupHandler`(新)）+ `simos-app`（`Shell`/`CatalogTool`/`DemoWorld`/`ApiViews`overview）+ `webui/map.js`/`index.html` + 各层测试 + 两处 JS 下界。
- **A 手势**：`edgeChainResult`（waypoint 状态机：同格⇒结束、非相邻跳过端点不动）、`edgeHitAtWorldPoint`（12px 阈值）、`edgeDeletePlan`（左键删边）、`drawEdges`（看见边）、缺格/非相邻可见提示；双向由 `edgeKeyOf` 规范序。
- **B 词表**：删 `EdgeOperations.KINDS` 硬编码 ⇒ `resolveKind` 从 `base.pathwayGroups().keySet()` 派生（大小写不敏感、未注册 fail-closed）；`PathwayGroup.defaults()` 供给 river/road；新命令 `map.RegisterPathwayGroup`（`PathwayGroupOperations.register`，重复 id 拒）。
- **C 往返**：`RoundTripComponentsTest.connectivityRoundTripsWithNonEmptyEdges`（非空 edges 逐值）。
- **连带读路径**：`/api/map/overview` 新增 `pathwayGroups` + `edges`（前端 kind 候选与删边的唯一来源，只读增量）。
- **门禁**：`./mvnw clean verify` **第 1 次尝试 rc=0**（`logs/clean-verify.log`），**变异轮后复跑 rc=0**（`logs/clean-verify-after-mutants.log`）；**8/8 `SUCCESS [`**；**1288** = `170/368/45/259/178/124/144`（现场重算）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **114/114**（108→114，+6）。**delta**（T2 1281）：Map +6、Core +1，其余不变。
- **变异**：**15 轮 15 KILLED / 0 存活**（JS 10 + Java 5），红点全落被保护断言；详见 `t3-report.md` §四。★ 两处装置自证：`jm_apply` 首轮 VOID（未用 import ⇒ Checkstyle 拦）；`java-round.sh` 判定改看红点（`failure.ignore` 下 rc 恒 0）。
- **裁定 42**：改 `map.js`/`gate-contract` ⇒ 重跑 T2 的 t2m1/t2m2/t2m3/t2m5（KILLED）；T2 m4 目标串已消失 ⇒ 重派生 `sm_subtool_default`；改 `EdgeOperations` ⇒ 重放 M8 T5 m2 为 `jm_merge_replace`；改 `MapChangeSet`/`PathwayGroupOperations` ⇒ `jm_apply`/`jm_dupe`。
- **我未能核实的**：见 `t3-report.md` §五（浏览器 e2e / served-asset 未核；`replace` 删边 lossy 且删不掉最后一条；DTO 动态 radio 的 DOM 分支未覆盖；真档未验；D14 未裁故后端相邻校验未加）。
- **证据**：`t3-evidence/`（logs/ + mutants/ + t3-report.md）。
