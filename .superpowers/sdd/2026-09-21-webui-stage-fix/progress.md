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

## T4 MCP 端口拓扑（现有口扩 GM ∪ EXTERNAL + 决策人另开一口）✅（2026-09-21，分支 `wsf/t4`，基线 `ffabc83`，实现提交 `061dcbe`）

- **范围**：只做 T4。`simos-app`（`Shell`/`ShellConfig`/`ShellMain`/`SimosToolSource`）+ 测试（新
  `McpPortTopologyTest`、改 `McpServerTest`/`McpCoverageTest`/`ShellLifecycleTest`/`ShellMainParseTest`/
  `BindAddressTest`/`SimosToolsTest` + 12 个 `withPorts`/构造器适配）。**零其他模块改动**。
- **D2/D3/D8 兑现**：现有口（`--mcp-port`，缺省 5715）= **EXTERNAL ∪ GM** 复合桶（9 读 + 3 通用写 + 3 GM 窄写
  = **15**）；新决策人口（`--decision-agent-mcp-port`，缺省 **5717**）= 仅 `DECISION_AGENT` 桶（9 读 + 2 窄写
  = **11**，无通用写、无 `SetViewScope`）；`port=0` 两口都支持。
- **★ D2 ↔ N9 记账**：D2 的"加"覆盖 N9「不给决策 Agent 通用 `submit`」——**就现有端口而言**；代码两处注释 +
  报告 §二均写明"此处以用户裁定为准，N9 就本端口不适用"；N9 在决策人口与 `allowedTools` 白名单照旧。
- **★ 边界 = 端口级、非认证级**（A′）：两口同 `mcpCaller()`（`DEFAULT` 桶），装配处留注释。
- **门禁**：`./mvnw clean verify` **第 1 次尝试 FAILURE**（SpotBugs 两条 `NP_NULL_ON_SOME_PATH` 假阳性）⇒
  加 `Objects.requireNonNull` 钉后置条件后 **第 2 次 SUCCESS rc=0**；**变异轮后复跑 rc=0**；**8/8 `SUCCESS [`**；
  **1295** = `170/368/45/259/178/124/151`（现场重算，`logs/recomputed.txt`）；`BugInstance size is 0 ×7`；
  `[ERROR]` 0；前端 **114/114 不变**（未改 JS）。**delta 干净**（基线 1288）：**只有 app 144→151 = +7**
  （`McpPortTopologyTest` 4 + `ShellMainParseTest` 3）。
- **运行时清单**：`tool-face-manifest.txt` 逐条列出两口 15/11 工具（真 socket，探针跑完即删）。
- **变异**：**11 轮 11 KILLED / 0 存活**——新靶子 t4m1（现有口丢 GM）/t4m2（决策人口错挂外部）/t4m3
  （close 漏关第二个）/t4m4（决策人口加 `SetViewScope`）+ **裁定 42 重跑** t4r-t7m1/t7m2/t11m1/t9m1/t9m2/t9m3/
  m10m2（改 `Shell.java`/`ShellConfig.java` ⇒ 从最终字节重派生）。详见 `t4-report.md` §四。
- **★ 两 server 并存有直证**（任务书说"属推断"）：`bothServersListenAndCloseReleasesBothPorts` 在两个 server
  同时监听时用两个 SDK 客户端各自 `initialize()` 并各自读工具面。
- **我未能核实的**：见 `t4-report.md` §六（`ShellMain.run` 端到端 CLI 未跑；审批层未按口区分（已知限制）；
  同瞬间并发请求未测；生产机/跨平台未测；决策人口未做逐类 catalog 覆盖）。
- **证据**：`t4-evidence/`（logs/ + mutants/ + tool-face-manifest.txt + t4-report.md）。

## T5 决策人查询面（后端只读）✅（2026-09-21，分支 `wsf/t5`，基线 `ecf9c81`）

- **范围**：只做 T5。新增 `SdQueryService`（`simos-app/.../query/`）+ `ApiViews` 决策人视图 + `GuiServer` 两条只读路由
  （`/api/sd/decision-makers`、`/api/sd/decision-makers/{id}`）+ 新测试 `SdDecisionMakerApiTest`（13 条）。**零 sd 字段新增（D13）**。
- **★ 落点 = GUI 端点，不加 MCP 读工具**：读工具是**三桶共享的 9 条**，加一条牵动 3 处冻结清单
  （`SimosToolsTest.EXTERNAL_UNION_GM_TOOL_NAMES` 15 条 + `subList(0,9)/subList(9,15)` + `hasSize(15)`、
  `McpPortTopologyTest.READ_TOOLS` 9 条）；而**没有任何 T5~T10 的消费者需要它**（都经 GUI/`Shell`）⇒ 选零牵动的 GUI 端点。代价已在报告 §一量化。
- **★ 零 `Shell` 改动**：`GuiServer` 构造器内自建 `SdQueryService`（仍用既有 `queryService`）⇒ 构造签名不变 ⇒ 不触发对 T4 变异轮的重跑。
- **铁律 3**：只经 `state.module("sd")` → `SdSnapshot.state()` 的公共 record 访问器取数；**未在 sd 新增访问器**。
- **fail-closed**：未知 kind / 缺冒号 / 空 id ⇒ **400**；详情查不到 ⇒ **404**；**合法 kind + 不存在 id ⇒ 200 空列表**（与"查询坏掉"不同结果）。
  **`due` 恒 `null` 占位**（不填 `false`；D7 公式归 T9）。
- **门禁**：`./mvnw clean verify` **rc=0、实现轮第 1 次尝试**、变异轮后复跑第 1 次尝试 rc=0；**8/8 `SUCCESS [`**；
  **1308** = `170/368/45/259/178/124/164`（现场重算，只取模块汇总行）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **114/114 不变**。
  **delta 干净**（基线 **1295** = `…/151`）：只有 app **151→164 = +13**（= `SdDecisionMakerApiTest`），其余逐字不变。
- **判据逐条实测**：空库 `200 {"decisionMakers":[]}`；`nation:n1` ⇒ **恰 `dm-nation`**；`army:a1` ⇒ **恰 `dm-army`**；
  详情与重放出的 `SdState` 逐值一致；`POST` 列表端点 ⇒ **405 + `Allow: GET`**；两次列表响应**逐字节相同**（探针原文见 `t5-report.md` §四）。
- **★ 探针当场抓到的就地校正**：未排序时 `dm-army.allowedTools` 回的是**载荷插入序的反序**（状态里是 `Set`，迭代序不是内容的纯函数）
  ⇒ `ApiViews` 出口**字典序排序**。这是 T5 唯一一处实现期校正。
- **变异**：**5 轮 5 KILLED / 0 存活**——t5m1（不筛 affiliation）/ t5m2（详情缺 `cadence`）/ t5m3（空库抛 500）/
  t5m4（未知 kind 改成**空集合冒充**）/ t5m5（列表路由整段去掉）。★ **裁定 42**：`ApiViews` 在 m2 后被改过（加排序）⇒ **只重跑 m2**（新 `orig_md5=4722545…`，KILLED）；
  m1/m3/m4/m5 靶子 `SdQueryService`/`GuiServer` 字节未变 ⇒ 证据不作废。装置 `t5-evidence/mutants/java-round.sh`（九道门禁）。
- **我未能核实的**：见 `t5-report.md` §八（跨 JVM 逐字节决定论未独立复现；真档未验；`?branch=&revision=` 未逐值实测；多值/URL 编码 id 未测）。
- **证据**：`t5-evidence/`（logs/ + mutants/ + probe-*.out + t5-report.md）。

## T6 redaction 洞收口 ✅（2026-09-21，分支 `wsf/t6`，基线 `c221736`）

- **范围**：只做 T6。`simos-app`（`query/RedactingQueryService.java` + `gui/GuiServer.java`）+ 测试（扩 `RedactingQueryServiceTest` 3→9 + 新
  `RedactionApiTest` 10）。**零其他模块改动、零 `Shell` 改动、零前端改动**。
- **洞 1 修好**：`RedactingQueryService` 新增 `applyRedactedFields`（递归按名剔除）、`seesHex/seesRegion/seesUnit`（按地址取单实体的
  fail-closed 谓词）、`verdicts(actor,target)`（`adjudicationDisclosure` 三档：`FULL` 全字段 / `PERCEPTION_ONLY` 去 `payload`+`meta` /
  `WITHHELD` **空列表**）。`mapOverview`/`units` 出口也接 `redactedFields`。
- **洞 2 修好**：`handleGet` 先解析 `as=`，再逐端点分派。**11 个读端点带 `as=` 走 redaction**（`state/resolve/facets/map.overview/
  map.hex/map.region/{id}/timeline/units/unit/{id}/social.population` + **新增 `/api/sd/verdicts`**）。
- **★ 新增 `/api/sd/verdicts` 的理由（诚实披露）**：`adjudicationDisclosure` 的语义是"判决对某角色可不可见"，但**既有任何读端点都不携带判决内容**
  （`resolve` 只回候选 id/type；`facets` 只有 unitsHere/population）⇒ 不加读面就无法让该字段"真正生效"。复用既有 `sdState()`，**不新增 sd 数据**。
- **★ 未覆盖端点清单（fail-closed，逐条）**：`/api/map/path`、`/api/sd/decision-makers`、`/api/sd/decision-makers/{id}` 带 `as=` ⇒ **400 拒绝**（不是静默全量）；
  `/api/approvals` = AgentLib 透明代理（非 sim 状态读，`as=` 无语义）；写端点不在 redaction 范围。★ **计划 §T6 清单未列 `/api/map/region/{id}`，T6 补接**（否则"hex 关了、region 还开着"）。
- **门禁**：`./mvnw clean verify` **rc=0、实现后第 1 次尝试**（`logs/clean-verify.attempt1.log`）；**8/8 `SUCCESS [`**；
  **1324** = `170/368/45/259/178/124/180`（现场重算，只取模块汇总行，`logs/recomputed.txt`）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **114/114**。
  **基线本树实测 1308**（`logs/baseline-verify.log`）⇒ **delta 干净**：只有 app **164→180 = +16**（`RedactingQueryServiceTest` +6、`RedactionApiTest` +10）。
  **变异轮后复跑 rc=0**（`logs/clean-verify.after-mutants.log`，1324）。
- **变异**：**7 轮 7 KILLED / 0 存活**——t6m1（`WITHHELD` 不隐藏）/ t6m2（`redactedFields` 不生效）/ t6m3（未接端点不再拒绝 `as=`）/
  t6m4（`PERCEPTION_ONLY` 恒 FULL 字段集）/ t6m5（单格可见性失效）+ **裁定 42 重跑** t6r-t5m5（`GuiServer` 决策人路由，**重派生**：旧靶串已被 T6 重写）/
  t6r-d4m1（`RedactingQueryService` hex 过滤，靶串逐字未变）。红点全落被保护断言；`orig_md5==restored_md5` 逐字节。
- **裁定 42 范围**：T6 改了 `GuiServer`（← T5）与 `RedactingQueryService`（← SDSimos D4）⇒ 各重跑其变异轮；T5 的 t5m1~t5m4 靶文件
  `SdQueryService`/`ApiViews` 字节未动 ⇒ 不重跑。
- **我未能核实的**：见 `t6-report.md` §六（前端未验（后端改动）；`/api/sd/verdicts` 夹具直接种入 `Verdict`、未经真 adjudicator；
  `redactedFields` 是"按名"不是"按路径"；`resolve`/`facets` 只做字段级、**未做实体级可见性**；`/api/approvals` 的 `as=` 透传未验；跨 JVM 字节稳定未复现；真档未验）。
- **带裁定的遗留**：`t6-report.md` §七（L1 范围声明：`resolve`/`facets` 不做实体级可见性 = 无 spec 依据；L2 未接端点若要支持 `as=` 须先补设计；
  L3 `/api/approvals` 透传；L4 `PERCEPTION_ONLY` 字段粒度是**实现期裁定**）。
- **证据**：`t6-evidence/`（logs/ + mutants/（含 orig/ 与七轮日志）+ t6-report.md）。

## T7 决策模式（一模式两子页）+ 决策人交互 ✅（2026-09-21，分支 `wsf/t7`，基线 `62a0184`）

- **范围**：只做 T7。**零 Java 生产改动**；Java 只动 `WebuiAssetsTest`（`MODE_LABELS` +「决策」、`allFiveModes…`→`allSixModes…`）。
  前端：`modes.js` / `index.html` / `api.js` / `app.js` / `panels.js` / `map.js` / `styles.css`；JS 测试：`modes.test.cjs` +1、
  **新 `decision-mode.test.cjs` 23 条**、`write-allowlist.test.cjs`（审批端点显式化）、两处下界 114→138、`map-edit-suboptions.test.cjs` 5→6。
- **交付**：第六模式「决策」（`writes: []` 只读）+ 两子页（「决策人查看」/「审批」）+ 三处交互
  （国家 tag ⇒ 全部区域高亮 / 有决策人的单位（含后代）⇒ 军队决策人 / 右栏按类型分类列表）。子页 B = `/api/approvals` 列表 + 批准·驳回。
- **三件事模型边界**：② 待决信号（T9）**未实现**，展示位留「—」且 `pendingStatusText` 钉死"不造假"；④「开始决策」（T10）**未实现**、
  断言 index.html 不含 `sd.StartDecision`/「开始决策」。T7 只做 ③ 与子页 A 的交互。
- **门禁**：`./mvnw clean verify` **rc=0、最终字节第 3 次尝试**（前两次也 rc=0）；**8/8 `SUCCESS [`**；
  **1324** = `170/368/45/259/178/124/180`（现场重算，只取模块汇总行）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **138/138**。
  **基线本树实测 1324**（临时 detached worktree，`/tmp/opencode/baseline-verify.log`，rc=0）⇒ **delta 干净**：
  **Java 用例 +0**（T7 零生产改动）、前端 **114→138 = +24**（`decision-mode.test.cjs` +23、`modes.test.cjs` +1）。
- **变异**：**11 轮 11 KILLED / 0 存活**——t7m1 分组混排 / t7m2 国家只高亮第一个 / t7m3 模式从模式栏消失 /
  t7m4 子页两页同开 / **t7m5 决策模式被给写权限（`sd.StartDecision`）** / t7m6 `due=null` 造假 / t7m7 单位解析不上溯 /
  t7m8 点格不接高亮 / t7m9 子页可见性不自写纯函数 / t7m10 审批端点改成 `/api/evil/` / **t7m11（Java）按钮标签漂移 ⇒ `WebuiAssetsTest` 红**。
  红点全落被保护断言；`orig_md5==restored_md5` 逐字节；装置日志**自指**三个 md5。
- ★★ **装置自身的坑（当场发现并修）**：`mut-js.sh` 首版把 `file` 写成**裸文件名** ⇒ 变异体落在证据目录、真源未动 ⇒ 10 轮**假存活**。
  这是「被测对象不是我以为的那份字节」同族；改成绝对路径后 10/10 KILLED。**如实记**：首版假存活轮未留档（无有效证据）。
- ★ **`panels.js` 在首轮变异后被改过**（加 render 导出）⇒ 刷新 pristine 并**重跑全部 10 轮**（t7m11 同）⇒ 最终字节上 11/11。
- **写权限边界**：审批子页的写是 `POST /api/approvals/{id}`（审批裁决，**非命令写**），作为**单独一条**显式列在
  `write-allowlist.test.cjs`（`/api/approvals/` 前缀 + 非空 id，配三个反例证明不是通配）；**未放宽 `isWriteAllowed`**。
- **我未能核实的**：见 `t7-report.md` §五（**无真实浏览器 e2e**——本机 chromium revision 1234 与可用 playwright 1.63/1.64 期望的 1243/1246 不匹配；
  未在真 sd 档上验；"后代也算有决策人"无 spec 逐字依据；审批列表无轮询；触摸/HiDPI 未测）。
- **带裁定的遗留**：`t7-report.md` §六（L1 后代口径 = 设计选择；L2 T10 若加命令须同样显式登记；L3 `due` 恒「—」待 T9；L4 e2e 归 T13；L5 `决策` 一词多处）。
- **证据**：`t7-evidence/`（logs/ + mutants/ + t7-report.md）。

## T8 GM 交互界面（底栏按钮 + 全屏，仅显示 GM MCP 工具使用）✅（2026-09-21，分支 `wsf/t8`，基线 `d10f5bd`）

- **范围**：只做 T8。`simos-app`：新 `app/gm/GmToolUsage.java` + `app/gm/RecordingToolSource.java` + `app/gui/GmToolUsageApiTest.java` +
  `webui/gm.js` + `test/js/gm-panel.test.cjs`；改 `Shell.java`（3 处加法）/`GuiServer.java`（端点 + 重载构造器）/`webui/api.js`/
  `webui/index.html`/`webui/styles.css`/`WebuiAssetsTest.java`/`run-gate.cjs`/`gate-contract.test.cjs`。**零其他模块改动**。
- **数据源裁决（计划 §T8「若无现成读面 ⇒ 加只读端点」）**：现场核实**无**真正的工具使用读面——`EventTypes` 八类型无"工具调用"事件、
  `AgentToMcpServer.handleCall`/`ToolCallAuthorizer.execute` 不留痕、`/api/timeline` 只有写 revision 的 `initiator`+`commandType`
  （且 GM 口与决策人口**同 initiator**、不含读工具/失败）⇒ 按计划加 **`GET /api/gm/tool-usage`**，数据源 = **GM 口（`EXTERNAL_WITH_GM`）
  工具执行的进程内留痕**（`RecordingToolSource` 只装饰 `execute`，只包 GM 口；`GmToolUsage` 有界 200、不落盘、不进 revision）。
  空记录 ⇒ **200 `{"entries":[]}`**（明确空态，不编造）；带 `as=` ⇒ **400 fail-closed**。
- **口径边界**：审批拒绝 / 权限硬拒 / **工具不存在** / 意外异常**不记**（记录点在 `execute`）——`doesNotRecordCallsThatNeverReachATool` 钉住。
- **C22 兑现**：底栏 `#timeline-bar` 内 `#gm-open` 按钮；点击开 `#gm-overlay` 全屏层（`position:fixed`+`pointer-events:auto`，
  **默认 `hidden`** ⇒ 不挡底栏、关闭复原）；面板读端点渲染 `工具名 · 结果 · 时刻`；界面**无** `input`/`textarea`/`contenteditable`（一阶段不做对话）。
- **门禁**：`./mvnw clean verify` **第 1 次 FAILURE**（SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`：catch 后再 throw）⇒
  改为只记 `ToolResult`（不 catch/rethrow）后 **第 2 次 rc=0**；变异轮后复跑 rc=0。**8/8 `SUCCESS [`**；
  **1331** = `170/368/45/259/178/124/187`（现场重算，只取模块汇总行）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **153/153**。
  基线本树实测 **1324**（`logs/baseline-verify.log`）⇒ **delta 干净**：**只有 app 180→187 = +7**（`GmToolUsageApiTest`），
  其余六模块逐值不变；前端 138→153 = +15（`gm-panel.test.cjs`；两处下界同改 + `REQUIRED_FILES`）。
- **变异**：**22 轮 KILLED / 0 SURVIVED / 1 VOID**（VOID = `r1m1` 首轮锚点随 T7 重写失效，**按当前字节重派生同一语义后 KILLED**，VOID 行留档不删）。
  新靶 7 轮（t8m1/m8 Java 记录器与端点；t8m2/m3 gm.js 空壳与不接线；t8m4/m5/m6 styles.css/index.html 挡底栏与点不动）+ **裁定 42 重跑 15 轮**
  （Shell 9：T4 六 + T9 三；GuiServer 3：T6 三；index.html 3：T7 Java 一 + T1/T7 前端二）。逐轮 `cp` 逐字节还原 `restored_equals_orig=true`，红点全落被保护断言。
- **运行时核**（served assets，独立端口 5861~5864）：`/` 200 含四处 GM 标识且 `approvals-count`×0；`/gm.js` 200 且 md5 与源**逐字节相同**；
  `/api/gm/tool-usage` 200 `{"entries":[]}`；`?as=` 400。**5818 未动**，跑完 kill 本实例 + 清临时 store。
- **我未能核实的**：见 `t8-evidence/t8-report.md` §八（浏览器 e2e 未跑（Chromium revision 不匹配，T7 已记）/ MCP socket 级运行时探针未驱动
  （"有记录"态由 Java 测试走真 Shell+authorizer+注册表+HTTP 证过）/ 记录不持久 / 两 MCP 口共用 initiator 靠端口区分 / 前序重跑是代表集，
  未重跑与 T8 改动无交集的前序靶）。
- **证据**：`t8-evidence/`（logs/ + mutants/（含 py/、pristine/、logs/）+ t8-report.md）。

## T9 「建议/待决」信号（服务端计算 + 列表显示）✅（2026-09-21，分支 `wsf/t9`，基线 `14add68`）

- **范围**：只做 T9。**服务端**按 **D7 已裁公式**算出 `due`（首次恒 due）；**只读派生、不落 revision**（铁律 2）；读 `SdState` 走 sd 模块公共访问器（铁律 3）。
  改 3 文件 + 2 门禁：`SdQueryService.java`（`pending`/`tickOf` + `DecisionMakerInfo.pending` + `PendingSignal`）、`ApiViews.java`（`due`/`lastDirectiveTick`/`ticksSinceLast`）、
  `SdDecisionMakerApiTest.java`（改 1 条断言 + 新增 6 用例）；新 `test/js/pending-signal.test.cjs`（6 条）；`run-gate.cjs`+`gate-contract.test.cjs`（153→159 + `REQUIRED_FILES`）。
- ★ **`GuiServer`/`Shell` 零改动**；★ **`panels.js` 逐字节未动**（md5 `29019a49…`，与 T7 终态一致）——T7 早把展示位绑到 `maker.due`
  ⇒ **T9 无前端生产改动**（见 t9-report §六 L1），前端交付 = 新护栏。
- **公式**：`since = tick − max(dm 的所有 Directive tick)`（`directives()` 是**插入序表**，必须显式求 max）；`last==null ⇒ due=true`；否则 `due = since >= cadence`。
  首次的 `lastDirectiveTick`/`ticksSinceLast` 置 **`null`**（"没有基准"，不填 0/-1）。当前 tick = 被查询快照的 `meta().timestamp().tick()`。
- **门禁**：`./mvnw clean verify` **rc=0**（实现轮**第 3 次尝试**：attempt1 rc=0（补 C17 前）/ **attempt2 Spotless 折行红**（新增中文 Javadoc）/ `spotless:apply` 后 attempt3 rc=0）；
  变异轮后复跑 **rc=0（第 1 次尝试）**。**8/8 `SUCCESS [`**；**1337** = `170/368/45/259/178/124/193`（现场重算，只取模块汇总行）；`BugInstance size is 0 ×7`；`[ERROR]` 0；前端 **159/159**。
  基线（T8）**1331** = `…/187` ⇒ **delta 干净**：**只有 app 187→193 = +6**（`SdDecisionMakerApiTest` 13→19），其余六模块逐字不变；前端 153→159 = +6（两处下界同改 + 新文件入册）。
- **判据逐条实测**（t9-report §二）：**首次恒 due**（`due=true`、last/since 均 `null`）；**未到点不 due**（tick7 间隔 0 < cadence ⇒ false）；**到点 due**（推进 7→9 间隔 2 == cadence ⇒ true，`>=` 口径）；
  **max**（ticks 4,6 ⇒ last=6）；**C16**（推进 directives 4→4、revision +1、due 随 tick 更新）；**C17**（推进后 `due=true` 集合逐值 == 离线冻结集合 `[dm-due,dm-first,dm-max]`）；
  **R4** 回归由既有 `IssueDirectiveHandlerTest:69` + `SdStateInvariantTest:40` 钉住。
- **变异 13 轮 13 KILLED / 0 存活**：T9 自身 6 Java（t9m1 due 恒 true / t9m2 取 min / t9m3 首次不 due / t9m4 严格 `>` / t9m5 `ApiViews` due 写死 false / t9m6 间隔差一）
  + 2 JS（t9js-m1 列表项写死文案 / t9js-m2 非布尔返回「非待决」）；**裁定 42** 重跑 T5 旧靶 5（t5m1~m5，锚点按当前字节重表达）；**逐轮 `cp` 逐字节还原 `restored_md5==orig_md5`**。
  ★ 三个分支各有能杀它的变异体；★ 新增的 **C17 集合判据**被 4 轮各自杀掉（不是装饰）。
- **我未能核实的**：t9-report §五（真浏览器 e2e 未跑（同 T7 开口项）/ 真档未验 / `lastDirectiveTick > tick` 与跨回退的 max 未测 / T7 变异未重跑（依据是靶字节未动）/ 前端"真值"只在替身夹具层）。
- **带裁定的遗留**：t9-report §六（L1 前端零生产改动 = 设计事实；L2 `lastDirectiveTick`/`ticksSinceLast` 未在前端显示；L3 当前 tick = 快照 tick 属实现期裁定；L4 C17 用冻结字面量）。
- **证据**：`t9-evidence/`（logs/ + mutants/（pristine/、py/、逐轮日志、mut-java-summary.txt）+ t9-report.md）。
- ★ **装置自身的坑（如实记）**：`mut-java.sh` 的 `: > "$ALL_LOG"` 会截断累积日志 ⇒ 分两次调用后 `mut-java.log` 只剩后一次（t5m）；
  **逐轮 `t9m*.log`/`t5m*.log` 完整在档**，判定由逐轮日志重建，不依赖 `mut-java.log`。
