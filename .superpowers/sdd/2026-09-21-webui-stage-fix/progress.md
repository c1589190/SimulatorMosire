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
