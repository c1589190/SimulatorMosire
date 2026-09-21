# T8 报告 —— GM 交互界面（底栏按钮 + 全屏，仅显示 GM MCP 工具使用）

> 分支 `wsf/t8`（基线 = 当前 HEAD `d10f5bd`）；worktree `.claude/worktrees/wsf-t8`。
> 计划 §T8 / spec §七.4 `C22` / research §B.5。日期 2026-09-21。只做 T8。

## 一 交付（用户原话 → 落地）

| 用户要求 | 落地 |
|---|---|
| 「应该在最底部那一栏**额外做一个按钮**」 | `index.html` 的 `#timeline-bar` → `.timeline-actions` 内新增 `<button id="gm-open">GM 界面</button>` |
| 「点一下就**全屏一个GM交互界面**」 | `<section id="gm-overlay" class="gm-overlay" hidden>`：`position:fixed; inset:0; pointer-events:auto`（复用 M7g 全屏浮层机制）；`gm.js` 接管开关 |
| 「这个GMAgent界面**只显示GM MCP的Tool使用**」 | 面板读 `GET /api/gm/tool-usage`（**新造的只读面**，数据源见 §二），逐条渲染 `工具名 · 结果 · 时刻` |
| 「**不做对话**」（一阶段） | 界面**无** `input`/`textarea`/`contenteditable`（`gm-panel.test.cjs` 静态断言钉住） |
| 不许挡底栏时间线 / 关闭恢复原视图 | 全屏层 `position:fixed` + **默认 `hidden`** ⇒ 不参与文档流、不把底栏挤出视口；关闭 `setOpen(false)` 复原（`aria-expanded=false`） |

## 二 ★ 数据源：为什么新造一个只读面（附「取不到就空态」）

计划 §T8 的裁决是「**先核实既有读面；若无 ⇒ 加只读端点 `GET /api/gm/tool-usage`**」。现场核实结论：

- **`simos-core` 的 `events` 表**：只记**命令链事件**（`received/rejected/conflicted/committed/…` 八类冻结），**没有工具调用事件**（`EventTypes` 八类型逐条核对）；且 app 层**没有**读 events 的面。
- **`EventStore` / `AgentToMcpServer`**：`AgentToMcpServer.handleCall` **不留任何工具调用痕迹**（其上游 `ToolCallAuthorizer.execute` 五段也不写事件——类注明写"不写事件"）。
- **`/api/timeline`**：有 `initiator` + `commandType`，但只是**写命令的 revision**——不含读工具、不含失败、且 GM 口与决策人口**同 initiator**（`agent:external-mcp`）⇒ 按它做"GM MCP 工具使用"会把两个口混在一起，且读操作整个缺失。
⇒ **无真正可用的"工具使用"读面** ⇒ 按计划加**只读端点**，数据源 = **GM 口工具执行的进程内留痕**：

- 新 `app/gm/GmToolUsage.java`：**有界（200）**、线程安全、**不落盘、不进 revision**（它是观测面，不是世界状态；铁律 2 不适用）。
- 新 `app/gm/RecordingToolSource.java`：`ToolSource` 装饰器，**只改 `execute`**，其余（`name/description/jsonSchema/spec/gate/resources/ledgerArgs`）逐字转交；`Shell` **只包现有 MCP 口**（`EXTERNAL_WITH_GM`），**不包**决策人口。
- `Shell`：建 `GmToolUsage`，用它包 GM 口，并把同一实例交给 `GuiServer`。
- `GuiServer`：`GET /api/gm/tool-usage`（`as=` ⇒ **400 fail-closed**；空记录 ⇒ **200 `{"entries":[]}`**，明确空态）。

**口径边界（写清，避免读成"全量调用日志"）**：被权限硬拒 / 审批未放行 / **工具不存在**的调用**不记**——记录点在 `AgentTool.execute`，这些在执行之前就被 `ToolCallAuthorizer` 挡下（`GmToolUsageApiTest.doesNotRecordCallsThatNeverReachATool` 钉住）。**不编造数据**：从没调用过 ⇒ 空数组。

## 三 判据 `C22` 实测

| 观测点 | 实测 |
|---|---|
| 底栏有 GM 按钮 | `GET /` 200（18349 B）含 `id="gm-open"`×1，且落在 `#timeline-bar` 内（`gm-panel.test.cjs` 截取 footer 段断言） |
| 点它 ⇒ 全屏 GM 界面 | `gm.js mount` 接线：点按钮 ⇒ `overlay.hidden=false` + `aria-expanded=true`（`mount-wires-open-close-and-refresh`）；served `/` 含 `id="gm-overlay"` |
| 显示 GM MCP 工具使用（工具名 + 结果） | 面板 `renderInto` → `GET /api/gm/tool-usage` 恰一次；`renderPlan` 逐条 `工具名 · 成功/失败（code） · 时刻`；**Java 侧**：真工具执行 ⇒ 端点 `entries` 含 `{tool, ok, code, atEpochMs}`（`GmToolUsageApiTest` 7 条） |
| 不含对话输入框 | 全屏层 HTML 内无 `input`/`textarea`/`contenteditable` |
| 空态 | `GET /api/gm/tool-usage` ⇒ `200 {"entries":[]}`；面板显示 `暂无 GM MCP 工具调用记录。` |

## 四 门禁（`./mvnw clean verify`，前台独占；本机 nproc=8，单轮约 56 s）

| 轮 | rc | 模块 | 用例总数（现场重算） | BugInstance | `[ERROR]` | 前端 |
|---|---|---|---|---|---|---|
| 基线（本树改动前）`logs/baseline-verify.log` | 0 | 8/8 `SUCCESS [` | **1324** = 170/368/45/259/178/124/180 | 0 ×7 | 0 | 138/138 |
| 实现后第 1 次 `logs/clean-verify.attempt1.log` | **1** | — | — | — | SpotBugs 1 条 | — |
| 实现后 **第 2 次** `logs/clean-verify.attempt2.log` | **0** | **8/8 `SUCCESS [`** | **1331** = 170/368/45/259/178/124/**187** | 0 ×7 | **0** | **153/153** |
| 变异轮后复跑 `logs/clean-verify.after-mutants.log` | **0** | 8/8 | **1331** | 0 ×7 | 0 | 153/153 |

- **第 1 次 FAILURE 的原因（真缺陷，已修）**：SpotBugs 报 `RecordingTool.execute` `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`（catch RuntimeException 后再 `throw e`）。修法：**不再 catch/rethrow**——只记 `ToolResult`（工具契约要求把边界错误折成 `ToolResult`；异常是违约，不伪装成一条"工具使用"），并同步改 Javadoc。
- **delta 干净**（基线 1324 → 1331）：**只有 `SimosApp` 180 → 187 = +7** = 新 `GmToolUsageApiTest`（7 条）；其余六个模块逐值不变。前端 138 → 153 = +15 = 新 `gm-panel.test.cjs`（两处下界同改 138→153 + `REQUIRED_FILES` 增 `gm-panel.test.cjs`）。

## 五 变异（九道门禁；装置在 `mutants/`，逐轮 `cp` 逐字节还原 + 三 md5 自指）

**新 T8 靶子（7 轮，全 KILLED）**：

| 轮 | 靶文件 / 变异 | 红点（受保护断言） |
|---|---|---|
| t8m1 | `RecordingToolSource.execute` 不记 ⇒ 记录恒空 | `GmToolUsageApiTest.recordsSuccessfulToolCallWithToolNameAndResult` |
| t8m8 | `GuiServer.gmToolUsageReply` 不读记录（恒空壳） | 同上 |
| t8m2 | `gm.js` 丢弃服务端 `entries`（硬编码空壳） | `renderInto-renders-server-entries-from-the-response` |
| t8m3 | `gm.js` 底栏按钮不接线（点了没反应） | `mount-wires-open-close-and-refresh` |
| t8m4 | `.gm-overlay` `position: fixed → absolute`（挡底栏） | `gm-overlay-is-fixed-hidden-by-default-and-clickable` |
| t8m5 | `.gm-overlay` `pointer-events: auto → none`（点不动） | 同上 |
| t8m6 | 全屏层丢 `hidden`（一进来就盖住底栏） | 同上 |

（7 轮 = 5 前端 + 2 Java。）

**裁定 42 重跑（改了前任务的文件 ⇒ 重跑其变异轮）——15 轮，全 KILLED**：

- **`Shell.java`（T4/T9 靶）9 轮**：`r4m1`（现有口退 EXTERNAL）→ `existingPortExposesExternalUnionGmToolFace`；`r4m2`（决策人口错挂）→ `decisionPortExposesOnlyDecisionAgentToolFace`；`r4m3`/`r4t11m1`（`close()` 漏关 server）→ `bothServersListenAndCloseReleasesBothPorts`；`r4t7m1`/`r4t7m2`（authorizer 去审批 / 桶 DEFAULT→GUEST）→ `writeToolBlocksOnApprovalThenCommitsWithConfiguredInitiator`；`r4t9m1`/`r4t9m2`（删/挪 `SetStatusHandler` 注册）→ `catalogCoversEveryCommandHandlerImplementation`；`r4t9m3`（注入不可通行成本）→ `everyCatalogTypeIsReachableThroughMcpAndTakesEffect`。
- **`GuiServer.java`（T6/T5 靶）3 轮**：`r6m3`（`rejectAs` 失效）→ `endpointsWithoutRedactionRejectTheAsParameter`；`r6m5`（hex 可见性 fail-closed 失效）→ `invisibleHexIsNotFoundUnderAs`；`r6t5m5`（决策人路由整段去掉）→ `emptyLibraryGivesEmptyListNotAnError`。
- **`index.html`（T7 Java 靶 + T1/T7 前端靶）3 轮**：`r7m11`（决策按钮标签漂移）→ `WebuiAssetsTest.allSixModesAreEnabledRealControls`；`r1m1`（把 `approvals-count` 加回）→ `index.html-has-notification-element-and-no-right-panel-approvals`；`r7m3`（删决策按钮）→ `index-html-has-six-modes-and-decision-panel`。
- ★ **1 个 VOID（留档不删）**：`r1m1` 首轮锚点已随 T7 重写而失效（`replacement matched 0 times`）⇒ 判 VOID，**按当前字节重派生同一语义**（把 `approvals-count` 加回通知栏之后）后 KILLED。日志里那一行 VOID 是原始留痕。

**汇总：22 轮 KILLED / 0 SURVIVED / 1 VOID（重派生后被杀）**。每轮 `orig_md5`、`mutant_md5`、`restored_md5`、`restored_equals_orig=true`、红点方法名都在 `mutants/logs/mut-js.log` / `mut-java.log`，逐字节还原核对全 `OK`。

## 六 运行时检查（served assets，`logs/runtime-check.txt`）

独立端口起 shade jar（5861/5862/5863/5864）：`/` 200 含 `gm-open`/`gm-overlay`/`gm.js`/`gm-tool-usage` 且 `approvals-count`×0；`/gm.js` 200 且 md5 与源**逐字节相同**；`/api/gm/tool-usage` 200 `{"entries":[]}`；带 `?as=` ⇒ 400。**5818 实例未动**（跑完 kill 本实例、清临时 store）。

## 七 文件清单

- 新：`simos-app/.../app/gm/GmToolUsage.java`、`.../app/gm/RecordingToolSource.java`、`.../app/gui/GmToolUsageApiTest.java`、`.../webui/gm.js`、`.../test/js/gm-panel.test.cjs`。
- 改：`Shell.java`（3 处加法）、`GuiServer.java`（端点 + 重载构造器）、`webui/api.js`（`gmToolUsage()`）、`webui/index.html`、`webui/styles.css`、`WebuiAssetsTest.java`、`run-gate.cjs`、`gate-contract.test.cjs`。
- 证据：`.superpowers/sdd/2026-09-21-webui-stage-fix/t8-evidence/`。

## 八 ★ 我未能核实的

1. **浏览器内 UI 未实测**：本机 Playwright/Chromium revision 不匹配（T7 已记），T8 **未跑真浏览器 e2e** ⇒ 点击开合、全屏层视觉、`pointer-events` 的**像素级**效果只证到"纯函数 + 静态 + served HTML"三层（`gm.js mount` 用替身 document 测接线）。
2. **MCP socket 级端到端未在运行时探针驱动**：`/api/gm/tool-usage` 的"有记录"态只由 Java 测试（真 Shell + 真 authorizer + 真注册表 + 真 HTTP）证过，**未经外部 MCP 客户端**在运行时探针里发一次 `tools/call` 再回读端点。结构上二者同一条 `ToolCallAuthorizer.execute` 路径。
3. **只记"实际执行"**：审批拒绝 / 权限硬拒 / 工具不存在**不记**（有意口径）；`ToolResult` 之外的**意外异常**也不记（不伪装成工具使用）。
4. **记录不持久**：`GmToolUsage` 进程内、上限 200、重启即空；多分支/多世界不区分（本壳单 store 单进程）。
5. **GM 口与决策人口共用 `initiator`**（`agent:external-mcp`）：T8 的记录靠**端口/注册表**区分（只有 GM 口包了装饰器）⇒ 端点上正确；但若将来有人给决策人口的源也套装饰器，两个口会混进同一记录。
6. **没有专门的"GUI 侧写"被记录**：GUI 的写走 `player:gui` 且不经 MCP 注册表 ⇒ 不出现在本面板（符合"GM MCP 工具使用"口径）。
7. **前序重跑是"代表集"**：T8 改了 `Shell.java`/`GuiServer.java`/`index.html`，故重跑了 T4/T9 的 Shell 靶、T6/T5 的 GuiServer 靶、T7/T1 的 index.html 靶（14 轮）；**未**重跑与 T8 改动**无交集**的前序靶（如 T3 的 `map.js` 手势、T2 的子选项纯函数、T4 的 `ShellConfig` 绑定地址 `r4-m10m2`）——那些文件 T8 一个字节未动。
8. **`gm.js` 的 `window.SimosApi` 依赖**：`mount` 取数用全局 `window.SimosApi`（与 `notifications.js` 同法）；若该脚本未加载，面板静默不取数（不报错）——未加显式降级提示。
