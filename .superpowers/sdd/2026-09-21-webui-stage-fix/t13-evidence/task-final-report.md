# WebUI 阶段修复 —— T13 关账报告

> 分支 `wsf/t13`，worktree `.claude/worktrees/wsf-t13`，基线 `d0e37ab`（T12 合并提交，`git log` 实测）。
> spec：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md`（判据 §七 `C1`~`C34`；§〇.1/§〇.2；§八 挂起/盲区）。
> 计划：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md`（§〇 通则 + §T13）。
> 台账：`.superpowers/sdd/2026-09-21-webui-stage-fix/progress.md`。证据：`.superpowers/sdd/2026-09-21-webui-stage-fix/t13-evidence/`。
> ★ **T13 零生产 / 零测试字节改动**（唯一改动：`CLAUDE.md` 本行 + 本报告 + 提交 4 份阶段文档）。判据逐条实测、门禁现场重算。

---

## §〇 诚实披露（先说没做到 / 没验的）

1. **真浏览器 e2e 全阶段未跑**（T1~T10 各自已记；T13 **仍未跑**）：本机 `~/.cache/ms-playwright` 只有
   `chromium-1234`，而可用 playwright 1.63/1.64 分别要 revision **1243/1246**（T7 实测）⇒ 不匹配。
   ⇒ 所有 **UI 判据**（C1/C2 的"切换后拖动"、C3 手势像素层、C10~C15 交互、C20/C22 点击）
   都只证到**纯函数 + 静态结构 + served HTML / node 渲染夹具**三层，**没有一层是"真浏览器点了一下"**。
2. **真档（19441 / 59223 格）未验**：全部 e2e / API 用例跑在 **`--demo` 合成小世界**（3 格 + 合成夹具）。
   本机**无** `test_integration` 真档。**唯一**的真档证据来自 T11 自产（`v17levant` 资源 × `RichWorld`/`Replay`）。
3. **`D14` 未裁 ⇒ `C5` 未兑现**（如实记，不粉饰）：spec §八.2 给 `D14` 的**默认建议**是"**不自动建格、加相邻校验**"，
   而 T3 **只做了前半**（缺格不静默建），**后端相邻校验没加**（`SetEdgeHandler` 至今只校验"两端点在图上"，
   `grep` 无相邻判定）。⇒ `map.SetEdge` 经 MCP/命令路径**仍可连非相邻格**，`C5` **不成立**。
4. **`sd.StartDecision` 的"用户点也走审批"没有单行变异形态**（T10 §四.2 已记）：审批只装配在 MCP 工具的
   `ToolCallAuthorizer` 路径、GUI 走 `core.submit`；本报告的 C18-② 变异杀的是 **GM 路径的 `gate()=Ask`**
   （用户路径不经它）。⇒ "用户路径不经审批"由 **`pendingApprovals` 为空** 的断言钉住，**未**构造等价变异体。
5. **T13 变异点验装置自己出过一次事故（留痕）**：首版脚本的 Java 命令拼接有 bug（`-Dtest=@T@` 未替换），
   导致一轮 `mvnw` 以"没跑到测试"收工，同时该轮在崩溃前**已把 `MapChangeSet.java` 写成变异体却未还原**
   —— 是"**装置的产物自己带状态**"（CLAUDE.md 形态）的又一例。**当场靠 `md5sum -c` 检出**（`git status` 干净
   但 md5 与基线不符），**按源字节反向替换还原并逐文件 `md5sum -c` 全部 OK**（`/tmp/opencode/t13pristine/baseline.md5`）。
   ⇒ 修正后重跑，10 个点验全部 KILLED。**最终字节与门禁轮逐字节相同**。
6. **门禁轮与点验轮的时序**：最终绿轮 `logs/clean-verify.attempt1.log` 在**点验之前**跑；点验期间 8 个被触碰文件
   **逐个 `md5sum -c` 校验与基线一致**（见 §三），故**该绿轮对最终字节仍然成立**。T13 **未**为过门禁改任何断言。
7. **`[待裁]` 项从未经用户逐项裁定**：spec §〇.2 的 11 项（`D1`/`D3`~`D11`/`D13`）**全部按 spec 的"默认建议"落地**
   （见 §五.1），**不是**用户裁定。凡与默认不同处（`D14`）已如上标注。
8. **本报告的行号** = worktree `wsf/t13` **当前字节**的行号；引用的**变异轮次**来自各任务证据目录（未在本关账轮重跑
   的部分，一律标"**记录**"；本关账轮重跑的 12 + 4 个标"**T13 新鲜**"）。

---

## §一 门禁（现场重算，不引用任何文档现成数字）

**命令**：`./mvnw clean verify`（worktree 前台、独占；`nproc=8`，本轮**无被杀轮**）。
**结果**：**rc=0、第 1 次尝试**。日志 **`logs/clean-verify.attempt1.log`**（3388 行，`md5=c40766dcd0d8734b31413b88a6d5c594`）
= **最终绿轮**；`logs/clean-verify.attempt1.rc` = `0`；重算 `logs/recomputed.txt`。

| 项 | 实测值 | 取数方式 |
|---|---|---|
| `BUILD SUCCESS` | `[INFO] BUILD SUCCESS` | `grep BUILD` |
| 模块 | **8/8 `SUCCESS [`**：`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` | `grep 'SUCCESS \['` |
| 用例总数 | **1358** | `grep -E '^\[INFO\] Tests run:' \| grep -v -- '-- in' \| sed … \| paste -sd+ \| bc` |
| 逐模块 | `170 / 368 / 45 / 259 / 178 / **129** / **209**` | 只取**模块汇总行**（逐类行会双重计数） |
| SpotBugs | `BugInstance size is 0` **×7** | `grep -c` |
| `[ERROR]` | **0 行** | `grep -c` |
| 前端门禁 | `[frontend-gate] OK tests=164 pass=164 fail=0` | 日志原文；另 `node run-gate.cjs` 现场复跑 **164/164** |
| 第几次尝试 | **第 1 次** | 无失败轮、无被杀轮 |

**delta 与 T12 终态一致**（`170/368/45/259/178/129/209` = 1358）⇒ **T13 零生产/零测试改动**下逐值不变（合理）。
★ 本值域只取 `^\[INFO\] Tests run:` 且**排除 `-- in`** 的行；`clean verify` 的 **增量编译**不改变 surefire 汇总口径。

---

## §二 spec §七 判据 `C1`~`C34` —— 逐条实测值 + 证据锚

> `文件:行` 为 worktree 当前字节。**"变异保证"**列：`记录` = 各任务已跑并存档的变异轮；`T13 新鲜` = 本关账轮
> 在当前字节上重跑、KILLED（日志 `logs/guard-checks/`）。**无占位符**；`C5` 是唯一**未兑现**项（见 §〇.3）。

### 七.1 编辑线

| # | 实测值 | 证据锚（文件:行） | 变异保证 |
|---|---|---|---|
| **C1** | `#map-edit-subtools` ×1、`name="map-edit-subtool"` ×2（`terrain`/`connectivity`）；`mapEditPanelVisibility` 三面板恰一个可见（`terrain`⇒`{t:1,c:0,r:0}`、`river`/`road`⇒`{t:0,c:1,r:0}`、`randomize`⇒`{t:1,c:0,r:1}`）；未知工具⇒三者全 false | `index.html:93-95`；`map.js:4025`（`mapEditPanelVisibility`）；测试 `map-edit-suboptions.test.cjs:120` | **记录** T2 m1；**T13 新鲜**（`terrain: true`）⇒ `panel-visibility-is-mutually-exclusive` 红 |
| **C2** | `mapEditWriteAllowed("terrain","map.SetEdge")=false`、`("connectivity","map.SetTerrain")=false`；三个写点各调 `mapEditWriteGate`（`commitBrush`/`submitRandomize`/`commitEdge`） | `map.js:4047`（gate）、`:2760`/`:3109`/`:2991`/`:3054`（四个写点） | **记录** T2 m2/m3；**T13 新鲜**（`mapEditPanelVisibility`）覆盖同族 |
| **C3** | 手势纯函数：`edgeChainResult`（同格结束 / 非相邻跳过端点不动）、`edgeHitAtWorldPoint`（12px 阈值）、`edgeDeletePlan`（删边 = `replace` 剩余集）、`edgeKeyOf`（`(q,r)` 规范序去重）逐条绿 | `map.js:4093`/`:4180`/`:4215`/`:4070`；测试 `map-edit-tools.test.cjs` | **记录** T3 `sm_chain_end`/`sm_no_canon`/`sm_delete_keep`/`sm_hit_inf` 全 KILLED |
| **C4** | `apply(MapChangeSet.between(base,target),base)` 逐值 == `target`（含非空 `edges`）；`cs.edges()` 非 `Unchanged` | `RoundTripComponentsTest.java:108`；`MapChangeSet.java:63-74` | **记录** T3 `jm_apply`；**T13 新鲜**（`edges` 恒 `Unchanged`）⇒ `connectivityRoundTripsWithNonEmptyEdges` 红 |
| **C5** | ★ **未兑现**：`SetEdgeHandler` 只校验"两端点在图上"，**无相邻校验**（`grep` 无命中）⇒ MCP 路径可连非相邻格 | `SetEdgeHandler.java`（全文）；`EdgeOperations.java:71-75`（只判 `hexes().containsKey`） | **无变异体**（判据本身未实现，故无可杀对象）；`D14` 未裁 |
| **C34** | `EdgeOperations` **无硬编码 `KINDS`**；`resolveKind(base,kind)` 从 `base.pathwayGroups().keySet()` 派生（大小写不敏感、未注册 fail-closed）；新命令 `map.RegisterPathwayGroup` | `EdgeOperations.java:116-124`；`RegisterPathwayGroupHandler.java:32`；`PathwayGroup.defaults()` | **记录** T3 `jm_hardcode`/`jm_allow_all`；**T13 新鲜**（两处：前端候选 + 后端硬编码）均 KILLED |

### 七.2 端口拓扑与权限

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C6** | 现有口 = **15** 条（9 读 + 3 通用写 + 3 GM 窄写），逐条在 `tool-face-manifest.txt` | `McpPortTopologyTest.java:83`；`_TOOLS` 常量 `:42-60` | **记录** T4 `t4m1`；**T13 新鲜**（`EXTERNAL_WITH_GM` 丢 `addGmWrites`）⇒ `existingPortExposesExternalUnionGmToolFace` 红 |
| **C7** | 决策人口 = **11** 条（9 读 + `sd.IssueDirective`/`sd.SubmitVerdict`），**不含**通用写、**不含** `sd.SetViewScope` | `McpPortTopologyTest.java:97` | **记录** T4 `t4m2`/`t4m4` KILLED |
| **C8** | 两口同时监听、`boundPort` 相异（实测 45717/46471）；`close()` 后两口均可再绑 | `McpPortTopologyTest.java:117`（**两 SDK 客户端各自 `initialize`**） | **记录** T4 `t4m3` KILLED |
| **C9** | 决策人口调 `simos.command.submit` ⇒ SDK `McpError`「Unknown tool」，`revisions` 不变 | `McpPortTopologyTest.java:140` | **记录** T4 `t4m2` KILLED |

### 七.3 决策模式与三件事

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C10** | 模式栏 **恰 6** 个 `data-mode`（含 `decision`）；`modes.js` 白名单 = `["sd.StartDecision"]`（T10 起，**非**空） | `index.html`（6 个 `data-mode`）；`modes.js`（`decision` 模式）；`WebuiAssetsTest.allSixModes…` | **记录** T7 m3/m5 KILLED |
| **C11** | 子页控件 ×2（`view`/`approval`）；`subpage-visibility-is-mutually-exclusive`（未知 id ⇒ 两页全 false） | `index.html:190-204`；`panels.js` | **记录** T7 m4 KILLED |
| **C12** | `nationRegionIds(regions,"nation:n1") == ["r-a","r-b"]`（**长度 2**、`notDeepEqual ["r-b"]`） | `map.js:2218`；`decision-mode.test.cjs` | **记录** T7 m2 KILLED |
| **C13** | 左栏 `dl[data-decision-maker-id=…]`；字段投影 id/归属/cadence/allowedTools/viewScope 6 项/待决 | `panels.js`；`SdDecisionMakerApiTest.java:187`（过滤同值） | **记录** T7 m7；**T13 新鲜**（`matches` 恒 true）⇒ `filterByNationReturnsOnlyThatNationsDecisionMakers` 红 |
| **C14** | 根 `u-root` 与后代 `u-child`/`u-grand` 都解析到 `dm-army-a`；无 ⇒ 文案含「无决策人」 | `panels.js`（`decisionMakerForUnit`） | **记录** T7 m7 KILLED |
| **C15** | kinds `["nation","army"]`、组内 id 字典序、**总长 == 输入长**（未知 kind 落"其它"桶不丢） | `panels.js`（`decisionMakerGroups`） | **记录** T7 m1 KILLED |
| **C16** | 推进后 `sd.directives().size()` 4→4（**不产生**决策标记）、head +1、`due` 随 tick 更新 | `SdDecisionMakerApiTest.java:356` | ★ **无独立变异体**（"推进自动产生决策"的变异点落在 `TimeAdvance`/`SdTimeParticipant`，非本阶段改动文件）⇒ **证据级**（断言真实） |
| **C17** | 推进到 tick 9 后 `due=true` 集合 **逐值 == 离线冻结集** `[dm-due,dm-first,dm-max]` | `SdDecisionMakerApiTest.java:382`（`dueSetMatchesTheOfflineFormulaAfterAdvancing`）、`:306`/`:322`/`:340` | **记录** T9 m1/m3/m4/m5；**T13 新鲜**（`since >= cadence` 改恒 true）⇒ 到点用例红 |
| **C18** | ① 用户 GUI ⇒ 200 `committed`、head +1、`pendingApprovals` 空、`initiator=player:gui`；② GM 口 ⇒ 未批拒（head 不动）、批后 +1；③ 决策人口 `toolsFor(DECISION_AGENT)` **不含**该工具 | `StartDecisionEndToEndTest.java:152`/`:195`/`:228`；`GuiServer.java:765` | **记录** T10 m1/m2/m3/m8/m9；**T13 新鲜**（用户 `initiator` 改坏；GM `gate` 改 `ALLOW`）均 KILLED |
| **C19** | `sd.StartDecision` **不写 `Directive`** ⇒ 同 tick 随后的 `sd.IssueDirective` 仍 `committed`（R4 名额不被占） | `StartDecisionEndToEndTest.java:243`；`StartDecisionHandler.java:29-37` | **记录** T10 m5 KILLED |

### 七.4 通知栏与审批

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C20** | `index.html` `approvals-count` **0**、`<h3>待批</h3>` **0**；`#notifications.notify-bar` ×1（`:280`），读 `GET /api/approvals`（恰一次） | `index.html:280`；`notifications.js:41`；`notifications.test.cjs:53` | **记录** T1 m1/m2；**T13 新鲜**（`renderInto` 不读 approvals）⇒ `renderInto-reads-exactly-one-GET-api-approvals` 红 |
| **C21** | 后端 `GET /api/approvals` 保留（`GuiServer` 未删）；`write-allowlist.test.cjs:145` 的 `await api.approvals()` **原样在**（删则红，T1 m3 证） | `write-allowlist.test.cjs:145`；`notifications.js:41` | **记录** T1 m3（`not ok 95`）KILLED |
| **C22** | 底栏 `#gm-open` ×1 落在 `#timeline-bar` 内；全屏 `#gm-overlay`（`position:fixed`、默认 `hidden`）；面板读 `GET /api/gm/tool-usage`；**无** `input`/`textarea`/`contenteditable`（`gm.js` 命中 0） | `index.html:272`/`:284`；`gm.js`（`api.gmToolUsage`）；`GmToolUsageApiTest`（7 条） | **记录** T8 `t8m1`~`t8m6` KILLED |

### 七.5 富世界与文档

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C23** | `hexCount=59223`；`provinces=98`；直方图 `ocean 14927 / plains 28347 / desert 746 / low_hills 14107 / mountains 1096`（合 59223，lossy 算术 `11315+16933+99`）；河流 **240** 条全 `river` 且相邻 | `RichWorldTest.java:63`/`:98`；**T13 重跑 `check_v17levant_import.py` = 14 PASS**（`hexes.count`/`provinces.count`/`edges.count`/`histogram.matches-lossy-merge`） | **记录** T11 m1/m4b/m5/m6；**T13 补** m7/m8/m9（见 §四.1） |
| **C24** | tag `Nation 97 + 王国 1 = 98`；多对多样例 `(-105,67) -> ['区域14','石冠诸部']`（81 格多属） | `checker` `regions.tags`/`regions.multi-owner-sample`；`RichWorldTest.java:86`/`:115` | **记录** 无；**T13 补** m7（tag）/ m10（多对多）（见 §四.1） |
| **C25** | `shouldSeedGenesis(true,{main})=false`；`bootstrapGenesis` 二次创世抛「库非空」 | `RichWorldTest.java:145`；`ShellMainSeedGuardTest` | **记录** T11 m2 KILLED |
| **C26** | 资源信封 `info={"bySubject":{}}`；`RichWorld.state().info() == InMemoryInfoSystem.empty()`；`sdInfoEntryCount=0`（且 `hexCount=59223` 证"非空世界之后的空"） | `RichWorldTest.java:...`（`infoStaysEmpty`）；`T12InfoRedlineTest.java:47`/`:118`；**T13 重跑 `check_v17levant_docs.py` = 36 PASS** | **记录** T11 m3、T12 m3/m4/m5 KILLED |
| **C27** | **615** = `narrative 170 / map 318 / internal 67 / factions 52 / worldview 6 / characters 2`；字数 **319,630**；产出目录**只有 `.md`**（非 md = 0）；**16 有名 / 82 噪声** | `docs/worlds/v17levant/`（7 个 `.md`）；**T13 重跑 checker = 36 PASS / 0 FAIL** | **记录** T12 m1/m2/m6 KILLED |

### 七.6 可见性（redaction）

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C28** | 两 `ViewScope` 同一端点返回**不同**数据；`WITHHELD` ⇒ 判决 `List.of()`（整条消失） | `RedactingQueryService.java:178`/`:190`；`RedactionApiTest.java:166` | **记录** T6 t6m1/t6m4；**T13 新鲜**（删 `WITHHELD` 早退）⇒ `verdictDisclosureGatesTheVisibleContent` 红 |
| **C29** | `redactedFields=["position"]` ⇒ 单位读数无 `position`，另一 actor 同请求**在** | `RedactingQueryService.java:136`（`applyRedactedFields`）；`RedactionApiTest.java:133` | **记录** T6 t6m2 KILLED |
| **C30** | 未接端点（`/api/map/path`、`/api/sd/decision-makers{,/{id}}`）带 `as=` ⇒ **400**（`rejectAs`）；`/api/approvals` = AgentLib 代理（非 sim 读，N/A） | `GuiServer.java:414`（`rejectAs`）；`RedactionApiTest.java:221` | **记录** T6 t6m3；**T13 新鲜**（`rejectAs` 恒不抛）⇒ `endpointsWithoutRedactionRejectTheAsParameter` 红 |

### 七.7 门禁（每任务）

| # | 实测值 | 证据锚 | 变异保证 |
|---|---|---|---|
| **C31** | 见 §一（rc=0、8/8、1358、BugInstance ×7、ERROR 0、前端 164/164） | `logs/recomputed.txt` | **T13 新鲜**（本关账轮即门禁轮） |
| **C32** | `run-gate.cjs:25 MIN_TESTS=164`、`gate-contract.test.cjs:40 MIN_ASSERTIONS=164`（**两处同值**）；新 JS 文件全在 `REQUIRED_FILES`（含 `map-edit-tools`/`notifications`/`decision-mode`/`pending-signal`/`gm-panel`） | `run-gate.cjs:25`；`gate-contract.test.cjs:40`、`:11-23` | **记录** 逐任务 m5（T2 等）"漏文件即红" |
| **C33** | `McpCoverageTest.EXPECTED_COMMAND_TYPES` = **42** 条（`shell` 注册同源）、逐 type 真提交 + head **42→43→44**；`SimosToolsTest.catalogCoversEveryCommandHandlerImplementation` | `McpCoverageTest.java:105-108`/`:315`/`:346`；`SimosToolsTest.java:210`/`:230` | **记录** T3/T4/T10 各轮 KILLED |
| **C34** | 见 七.1（词表可自定义） | 同上 | **T13 新鲜** KILLED |

**判据合计：`C1`~`C34` 共 34 条，逐条有实测值/证据锚；无占位符。唯一未兑现 = `C5`（`D14` 未裁，见 §〇.3）。**
`C16` 无独立变异靶子（已如实标"证据级"，见 §四.1）。

---

## §三 关键不变量护栏的**现场判别力点验**（T13 新鲜，跑在当前字节上）

> 方法：把被保护那行改成违规形态 → 断言 `mutant_md5 != orig_md5` → 跑**该判据自己的用例** → 断言
> 期望用例名出现在 `not ok`/`<<< FAILURE` → `cp` **逐字节还原** → `md5sum -c` 复核。
> 装置 `/tmp/opencode/t13_guard_check.py`；日志 `logs/guard-checks/*.log`（自指 `orig_md5`/`mutant_md5`/`rc`）。
> **结果：12/12 KILLED。** 点验后 8 个被触碰文件全部 `md5sum -c` = **OK**（与 `baseline.md5` 一致）。

| 不变量 | 变异（违规形态） | 期望红点 | 结果 |
|---|---|---|---|
| 地形/连通性**两条编辑线** | `mapEditPanelVisibility.terrain` 恒 `true` | `panel-visibility-is-mutually-exclusive` | **KILLED** |
| 词表**默认 + 可自定义**（前端候选） | `setRegisteredEdgeKinds` 忽略服务端、恒回默认 | `registered-edge-kinds-default-then-follow-the-server` | **KILLED** |
| 词表=**已注册组**（后端） | `resolveKind` 写回硬编码 `Set.of("river","road")` | `registeredCustomGroupBecomesAValidKind` | **KILLED** |
| **往返守卫覆盖 `edges`** | `between` 的 `edges` 恒 `Unchanged` | `connectivityRoundTripsWithNonEmptyEdges` | **KILLED** |
| **端口拓扑**：现有口=`EXTERNAL∪GM` | `EXTERNAL_WITH_GM` 丢 `addGmWrites` | `existingPortExposesExternalUnionGmToolFace` | **KILLED** |
| **决策人查询只读**（按 affiliation 过滤） | `OfNation` 分支恒 `true` | `filterByNationReturnsOnlyThatNationsDecisionMakers` | **KILLED** |
| **redaction 生效**（`WITHHELD`） | 删 `WITHHELD` 早退（返回全量判决） | `verdictDisclosureGatesTheVisibleContent` | **KILLED** |
| **redaction 端点 fail-closed** | `rejectAs` 恒不抛（未接端点静默全量） | `endpointsWithoutRedactionRejectTheAsParameter` | **KILLED** |
| **T9 `due` 三分支**（到点 `==cadence`） | `since >= cadence` 改恒 `true` | `dueIsFalseBeforeCadenceAndTrueExactlyAtCadence` | **KILLED** |
| **T10 用户直发 vs GM 过审批**（用户） | 用户路径 `initiator` 改 `agent:t13-probe` | `guiUserPathCommitsDirectlyWithoutApproval` | **KILLED** |
| **T10 用户直发 vs GM 过审批**（GM） | 窄写工具 `gate()` 由 `Ask` 改 `ALLOW` | `gmAgentPathIsDeniedWithoutApprovalAndCommitsAfterApproval` | **KILLED** |
| **通知栏读 `/api/approvals`** | `renderInto` 不调 `api.approvals()` | `renderInto-reads-exactly-one-GET-api-approvals` | **KILLED** |

**判别力来源（"为什么这行删了会红"）**：每个红点都落在**断言该行为本身**的用例上（不是"顺带红"）——
例如 C4 的红是 `assertThat(apply(cs,base)).isEqualTo(target)`（往返逐值），C34 的红是
`registeredCustomGroupBecomesAValidKind`（注册 `canal` 后 `setEdge(canal)` 成立）。**未为造红放松任何判据。**

★ **逐字节还原自证**：12 轮全部 `restore=OK`；另 `md5sum -c baseline.md5` 对 8 个文件全 `OK`
（`map.js`/`notifications.js`/`MapChangeSet.java`/`EdgeOperations.java`/`SimosToolSource.java`/
`SdQueryService.java`/`RedactingQueryService.java`/`GuiServer.java`），`git status` 对 tracked 文件**空**。

---

## §四 控制器欠的 6 条账 —— 逐条结论

### 四.1 ★ T11 只有 6 个变异体 ⇒ 哪些判据没有变异靶子

**先更正前提**：**"T1~T10 都是 20+"为假**。实测各任务变异轮数（含裁定 42 重跑）：
T1 = **7**（实现 3 + 审查 4）、T2 = **5**、T3 = 15、T4 = 11、T5 = **5**、T6 = 7、T7 = 11、T8 = 22、
T9 = 13、T10 = 13、T11 = **6 KILLED + 1 等价**、T12 = 6。
⇒ **T2/T5（各 5）比 T11（6）更少**；T11 不是异类。12 任务合计 **~122 轮**。

**T11 判据逐条有无靶子（原始 6 体）**：

| T11 判据 | 原始靶子 | 结论 |
|---|---|---|
| C23 河流 240 边 | m1 / m4b / m5 | 有 |
| C23 地形直方图 | m6 | 有 |
| C23 `hexCount` / `provinces` 计数 | **无** | ★ 缺 |
| C24 tag 分布（97+1） | **无** | ★ 缺 |
| C24 多对多样例（81） | **无** | ★ 缺 |
| C25 非空库不覆盖 | m2 | 有 |
| C26 绝不录 Info | m3 | 有 |

**T13 补齐（4 个体，全 KILLED，装置 `t11_supplement*.py`，日志 `logs/guard-checks/T11x-*`）**：
- **m7**：`"tag": province.get("tag")` → `"tag": "Nation"` ⇒ `checker regions.tags` FAIL（`{'Nation': 98}`）。
- **m8**：province 循环切 `[:97]` ⇒ `provinces.count` + `regions.tags` FAIL。
- **m9**：hex 写入上限 59222 ⇒ `hexes.count` FAIL（`resource=59222 source=59223`）。
- **m10**：从 `石冠诸部` 的 hex 集 `discard((-105,67))` ⇒ `regions.multi-owner-sample` FAIL（`['区域14']`）。
⇒ **T11 现在每条判据都有可杀靶子**。**仍无独立靶子的判据**：`C16`（"推进自动产生决策"的变异点在
`TimeAdvance`/`SdTimeParticipant`，非本阶段文件）⇒ **如实记为"证据级"**（断言真实，但无专门变异体）。

### 四.2 ★ D11 的目录名是否仍是旧的 `logdemo`

**结论：不是；spec 无需更正。** spec **§〇.1 D11** 已写 ``docs/worlds/v17levant/``（`-design.md:53`）；
全文仅两处出现 `logdemo`，都是**"控制器早期记错、已更正"的注记**（`:25` 与 `:256`，均写"不是 `logdemo`"）。
⇒ **spec 与实际产出（`docs/worlds/v17levant/` 7 个 `.md`）一致**，**不存在笔误需就地改**。
（这正是 T12 报告 §七 已记的口径。）

### 四.3 ★ T4 的"两个 MCP server 同时并发"有没有直证

**结论：有直证（不再是推断）。** `McpPortTopologyTest.bothServersListenAndCloseReleasesBothPorts`
（`McpPortTopologyTest.java:117`）：两个 `AgentToMcpServer` **同时监听**、`boundPort` 相异（实测 45717/46471），
**两个 SDK 客户端各自 `initialize()` 成功**并各自读到工具面（一个含 `sd.SetViewScope`、一个不含），
`close()` 后两口**均可再绑**。**仍属未测**：**同一瞬间**两客户端的并发请求处理、高并发/长连会话。

### 四.4 ★ T3 的连通性手势有没有进 Maven 门禁

**结论：进了。** `map-edit-tools.test.cjs` 在 `gate-contract.test.cjs` 的 `REQUIRED_FILES`（`:11-23`，`grep` 命中 3 次），
且 `run-gate.cjs` 的下界注释**逐字记录** `T3 起 108 → 114（map-edit-tools.test.cjs 新增连通性手势/命中/删边/词表 6 条）`；
T13 现场跑 `node run-gate.cjs` = `tests=164 pass=164 fail=0`（含该文件）。⇒ 手势判据**在 CI 内**，非"证据级"。

### 四.5 ★ 两台实例：`5818` 是否还活着、要不要重启

**只读探测（未 kill）**：
- `ss -ltnp`：**仅 `5818` 有监听**（`127.0.0.1:5818`，pid **64974**）；**无 `5817`** 监听。
- `ps`：`java -jar simos-app-…-shaded.jar --store /tmp/sept-gen --demo --gui-port 5818 --mcp-port 5816 --approval-port 5814`，
  **启动于 15:09**（早于 T4/T11 的合并）。
⇒ **该实例用旧端口方案（5816/5814）、没有决策人口（5717）、也没有富世界语义**（`--demo` 是当时版本）。
**动作留控制器**：若用户要"统一到 5817"/体验 T4/T11，应**重启**（新缺省：GUI 5711 / 审批 5713 / 现有 MCP 5715 / 决策人 5717）；
**T13 未 kill 任何实例**。

### 四.6 ★ 各任务的"存活变异"理由是否成立

| 来源 | 存活项 | 理由 | 判断 |
|---|---|---|---|
| T1 审查方 | `sx2r`（只降 `MIN_TESTS`）/`sx2c`（只降 `MIN_ASSERTIONS`） | **下界是下界**，改小**不违反任何不变量** ⇒ 门禁不可能因"下界被调低"而红；真牙齿（"删测试 ⇒ 跌破下界"）由 `sx3b` **KILLED** 证；两处下界**互为冗余、强度 = max** | **成立**（设计限制，非缺陷；不必修） |
| T11 | `m4`（`edges` 空时不回退 `edgeTags`） | **等价**：该档 `edgeTags` 与 `riverMask` 逐格一致 ⇒ 落到 `riverMask` 回退得**同样 240 边**；`m4b`（两份都去掉）**KILLED** | **成立** |
| T12 | `m4` **首轮**（字符串 `"InfoSystem"` 非符号） | 是**变异体形态错**（AST 判据正确地不把字符串当符号），改写为真 `import` 后 **KILLED** ⇒ **终态 0 存活** | **成立**（非护栏失效） |
| T7 | 首版装置 10 轮**假** SURVIVED | 装置 bug（`file` 传裸文件名 ⇒ 变异体写进证据目录、真源未动）；修正为绝对路径后 **10/10 KILLED**；首版无有效证据、**如实不留档** | **成立**（装置自身坑） |
| T10 | 首轮 3 VOID（Checkstyle 未用 import 拦下）+ 1 误判 | 均**未跑到用例** ⇒ 按纪律不记存活；修后 **KILLED** | **成立** |
| T3 / T8 | `jm_apply` 首轮 VOID / `r1m1` VOID | 同上（编译/风格在测试前拦下）；重派生后 **KILLED** | **成立** |
| T5 / T6 / T9 / T2 / T4 | 无 | 0 存活 | — |

**终态真实存活 = 3 个**（T1 `sx2r`/`sx2c`、T11 `m4`），**各有成立理由**。

---

## §五 遗留 / 挂起 —— 逐条处置

### 五.1 spec §〇.2 的 11 项 `[待裁]` + `D14`

★ **均未经用户逐项裁定**；下表"状态"指**任务按 spec"默认建议"落实**，非用户裁决。

| # | spec 默认 | 落实 | 状态 |
|---|---|---|---|
| D1 | 保留 `/api/approvals` | T1（后端零改、`api.approvals` 保留） | **已消**（按默认） |
| D3 | 决策人口 `5717` | T4 | **已消**（按默认） |
| D4 | 子页「决策人查看」/「审批」 | T7 | **已消**（按默认） |
| D5 | 新命令 `sd.StartDecision` | T10 | **已消**（按默认） |
| D6 | 审批门链 | T10 | **已消**（按默认） |
| D7 | `due` 公式 | T9 | **已消**（按默认） |
| D8 | 复合桶 `EXTERNAL ∪ GM` | T4（`Role.EXTERNAL_WITH_GM`） | **已消**（按默认） |
| D9 | redaction 修（不记限制） | T6 | **已消**（按默认） |
| D10 | `position:fixed` 右下 | T1 | **已消**（按默认） |
| D11 | `docs/worlds/v17levant/` | T12 | **已消**（按默认；spec 本已如此） |
| D13 | 复用现有字段、不新增 sd | T5/T7 | **已消**（按默认） |
| **D14** | 不自动建格 **+ 加相邻校验** | T3 **只做前半**；后端相邻校验**未加** | ★ **未消**（`C5` 未兑现） |

### 五.2 各任务报告里的遗留（归并成主题清单；`未消` = 已知且未处理）

| 主题 | 出处 | 状态 / 为什么 |
|---|---|---|
| **真浏览器 e2e 未跑** ⇒ UI 判据只到纯函数+静态+served 三层 | T1§七 / T2§七 / T3§五 / T7§五 / T8§八 / T9§五 / T10§六 | **未消**（Chromium revision 1234 ≠ 1243/1246） |
| **真档未验**（全部 e2e 在 `--demo` 合成小世界） | T3§五 / T4§六 / T5§八 / T6§六 / T7§五 / T9§五 / T10§六 / T11§六 | **未消**（本机无真档） |
| **跨 JVM 字节决定论未测** | T3§五 / T5§八 / T6§六 / T11§六 / T12§六 | **未消**（同机稳态已证，跨解释器/哈希盐未测） |
| **同瞬间并发请求未测**（T4 只证"同时监听 + 先后 initialize"） | T4§六 | **未消** |
| **决策人口未做逐类 catalog 覆盖** | T4§六 | **未消**（C7 只证工具面） |
| `resolve`/`facets` **未做实体级可见性**（只做字段级） | T6§六.4 / L1 | **未消**（无 spec 依据，不发明语义） |
| `redactedFields` 是**按名**不是**按路径** | T6§六.3 | **未消**（保守方向：同名一律剔除，不泄露） |
| `/api/approvals` 的 `as=` **透传未验** | T6§七 L3 | **未消**（AgentLib 外部依赖） |
| `PERCEPTION_ONLY` 字段粒度是**实现期裁定** | T6§七 L4 | **未消**（`DisclosurePolicy` spec 未定字段粒度） |
| `sd.StartDecision` INFO 落点在**感知层**（要成可计算事实须迁 `SdState` 组件） | T10§七 L1 | **未消**（有意最小落地） |
| `sd.StartDecision` **重复发起未约束**；服务端**不校验 `due`** | T10§六.3/§六.4 | **未消**（"未定策略"，不发明需求） |
| `modes.js` 的 `sd.StartDecision` 白名单**不在运行期被该按钮读取** | T10§七 L3 | **未消**（声明式要求，由反向不变式覆盖） |
| `decision-mode.test.cjs` 渲染替身**无 `refreshState`**，写后刷新未在该层覆盖 | T10§七 L4 | **未消** |
| `gm.js` 的 `window.SimosApi` **依赖未加降级提示** | T8§八.8 | **未消** |
| 审批列表**无轮询**；通知栏另有 5s 轮询 | T7§五.5 | **未消**（设计选择） |
| `decisionMakerForUnit` 的**"后代也算有决策人"**无 spec 逐字依据 | T7§六 L1 | **未消**（记为设计选择） |
| `C16` 无独立变异靶子 | §三 / §四.1 | **未消**（证据级） |
| 「**建议/待决**」信号的 `lastDirectiveTick`/`ticksSinceLast` **前端未显示** | T9§六 L2 | **未消**（未越界加 UI） |
| T7 早记的 `pens` 后代口径 / `t1` 布局无 in-gate 守护 | T1§三 / T7 | **未消**（通知栏布局仅 CSS 阅读） |
| T3 左键删边经 `replace` **lossy** 且**删不掉最后一条** | T3§五.3 | **未消**（命令面缺"删单条边"，代码显式拒绝并提示，不伪造命令） |
| spec §五.5「**带洞区域是真样本**」与源档不符（98 个环数全 `{1:…}`） | T11§二 | **未消**（如实记，spec 未改） |
| spec 未区分"82 噪声里 **7 个连 map 条目都没有**" | T12§六.3 | **未消**（文档与校验器已标，spec 未改） |
| T12 `caches/` 决策原文**未纳入** | T12§六.2 | **未消**（计划未列，不自扩范围） |
| `lowland`/`swamp` **lossy → plains** | T11§二 | **已消**（已补进 `LOSSY_KEYS` + checker `lossy.*` + 文档显式标 LOSSY） |
| `tools/**` **不入 Maven reactor** | T11/T12 | **设计边界**（T13 重跑两个 checker：14 PASS / 36 PASS 0 FAIL） |

### 五.3 spec §八 的"已知限制"（本阶段接受）与"盲区"（结构性）

- §八.1：① **端口级边界、无认证**（A′）；② **审批只按 `callerKey` 桶区分**（A″）；③ **GM 界面不做对话**（P2）；
  ④ **决策人出令不自动**。⇒ **全部未消**（本阶段明确接受）。
- §八.4：① 前端护栏强度低于后端 ⇒ **本阶段已把 JS 测试接进 Maven 门禁**（部分消解：新 `*.test.cjs` 全进
  `REQUIRED_FILES` + 两处下界）；② `tools/` 不在 reactor ⇒ **未消**（靠 checker 自证）。

---

## §六 我未能核实的（逐条，T13 视角）

1. **真浏览器交互**（C1/C2 拖动、C3 手势像素、C10~C15 点击、C20/C22 开合）—— 本机 Chromium revision 不匹配，**未跑**。
2. **真档上的查询 / redaction / 地图编辑**—— 本机**无** `test_integration`（19441 格）档；全部在合成世界。
3. **`D14` 未裁的实际影响**—— 后端**仍可**连非相邻格；"要不要加校验"是需求问题，非我能裁。
4. **`agentlib-mosire` 外部依赖**（T4/T6/T10 同一条）—— 审批请求字段/MCP 构造读的是**本机另一份源码**，
   与 `~/.m2` 的 `0.1.0-SNAPSHOT` 是否一致**未核**（本仓不可见）。
5. **同瞬间并发 / 跨 JVM 字节决定论**—— 均只到机制级/同机级。
6. **`5818` 实例重启后的实际行为**—— T13 只做只读探测（未 kill）；重启动作归控制器。
7. **`tools/` 脚本的跨解释器稳定性**—— 只在本机 Python 下逐字节稳定。
8. **`T12` 产出文档的"可读性"**—— 无 LLM 读者回归，只有脚本断言（内容完整性）。

---

## §七 证据索引（`t13-evidence/`）

```
t13-evidence/
├── task-final-report.md                     本文件
└── logs/
    ├── clean-verify.attempt1.log            ★ 最终绿轮（rc=0、1358、8/8、BugInstance ×7、frontend 164/164）
    ├── clean-verify.attempt1.rc             0
    ├── recomputed.txt                       门禁现场重算（模块汇总行 + paste|bc = 1358）
    ├── start.txt                            轮次起跑时间
    └── guard-checks/                        12 条不变量点验 + 4 条 T11 补靶（逐轮自指 md5）
        ├── C1_C2_…log / C34_…(前端).log / C20_…log / C4_…log / C34_…(后端).log
        ├── C6_…log / C13_…log / C28_…log / C30_…log / C17_T9_…log / C18-①…log / C18-②…log
        └── T11x-m7_…log / T11x-m8_…log / T11x-m9_…log / T11x-m10_…log
```

★ **最终绿轮 = `logs/clean-verify.attempt1.log`**（`md5=c40766dcd0d8734b31413b88a6d5c594`，rc=**0**，**第 1 次尝试**）。
点验期间的临时变异**已逐字节还原并 `md5sum -c` 复核 OK** ⇒ 该绿轮对最终字节仍然成立。
