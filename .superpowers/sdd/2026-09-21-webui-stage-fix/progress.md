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
