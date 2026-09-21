# T1 报告 —— 撤右栏审批 + 右下角通知栏

> 任务：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` **§T1**（本任务只做 T1）。
> 设计依据：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` **D1 / D10**（均已裁）。
> 调查依据：`docs/superpowers/specs/2026-09-21-webui-stage-fix-research.md` **§B.2 / §B.5**。
> 分支 `wsf/t1`，worktree `.claude/worktrees/wsf-t1`，基线 `92444b2`。日期 2026-09-21。

## 〇 一句话

右栏审批计数 UI 已撤；新增右下角 `position:fixed` 通知栏（`#notifications.notify-bar`），读
`GET /api/approvals` 显示待批摘要；**后端 `/api/approvals` 原样保留**（D1），`api.approvals` 保留；
全量门禁 **第 1 次尝试 rc=0**；3 个变异体全 KILLED，红点均落被保护断言。

## 一 改动清单

| 文件 | 改动 |
|---|---|
| `webui/index.html` | 删 `<h3>待批</h3>` + `<p id="approvals-count">`（原 `:210-211`）；删 `boot({… approvals: …})` 的 approvals 参数；`.wb-overlay` 内新增 `#notifications.notify-bar[hidden]` 浮层（`:234`）；引 `notifications.js` |
| `webui/app.js` | 删 `mountApprovals`（原 `:367-386`）、`boot` 里的 `if (opts.approvals)` 分支（原 `:393-395`）、`mountApprovals` 导出（原 `:450`） |
| `webui/notifications.js` | **新增**（`window.SimosNotifications`）：`pendingCount` / `summaryText` / `renderInto` / `mount` / `init`，读 `api.approvals()`，5s 轮询 |
| `webui/styles.css` | 新增 `.notify-bar`（`position:fixed; right:16px; bottom:104px; z-index:2; pointer-events:auto`）+ `[hidden]` + `.muted` |
| `test/js/notifications.test.cjs` | **新增** 6 条（静态 + 动态） |
| `test/js/run-gate.cjs` | `MIN_TESTS` 90 → **96** |
| `test/js/gate-contract.test.cjs` | `MIN_ASSERTIONS` 90 → **96**；`REQUIRED_FILES` 加 `notifications.test.cjs` |
| `test/java/.../gui/WebuiAssetsTest.java` | `WORKBENCH_SCRIPTS` 加 `notifications.js`（三资产 → 四资产） |

**未动**（按 D1 / 计划）：`GuiServer.java` 的 `/api/approvals` 代理、`Shell.java` 审批装配、
`ShellApprovalTest`、`GuiApiTest`、`webui/api.js`、`webui/modes.js`、`webui/write-allowlist.test.cjs`。

## 二 判据实测（C20 / C21）

现场命令见 `logs/criterions.txt`；关键值：

| 判据 | 期望 | 实测 |
|---|---|---|
| `index.html` 不含 `approvals-count` | 0 | **0** |
| `index.html` 不含 `<h3>待批</h3>` | 0 | **0** |
| `index.html` 不含 `approvals:`（boot 参数） | 0 | **0** |
| `app.js` 不含 `mountApprovals` | 0 | **0** |
| 通知栏元素存在 | 1 | `#notifications.notify-bar` **1**（行 234） |
| 引 `notifications.js` | 1 | **1** |
| 通知栏读 `GET /api/approvals` | 恰 1 次 | 动态用例 `renderInto-reads-exactly-one-GET-api-approvals` 绿，fetch 记录 `[{url:"/api/approvals",method:"GET"}]` |
| `api.approvals` 保留 | 在 | function + export 共 **2** 处命中 |
| `write-allowlist.test.cjs` 仍绿 | 绿 | **绿**（未改动该文件） |

## 三 门禁（`./mvnw clean verify`，**第 1 次尝试**，rc=0，51s）

日志：`logs/clean-verify.attempt1.log`（`log_md5=c57632f7756496963dd833a4931153be`），
重算：`logs/recomputed.txt`。

- **模块 8/8 `SUCCESS [`**（显示名）：`UtilSimos` / `MapSimos` / `SocialSimos` / `UnitSimos` / `CoreSimos` / `SDSimos` / `SimosApp` + 父 POM `SimulatorMosire`。
- **用例总数 1281** = `170 / 362 / 45 / 259 / 177 / 124 / 144`（现场从汇总行重算，只取无 `-- in` 的 surefire 汇总行）。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**。
- 前端：`[frontend-gate] OK tests=96 pass=96 fail=0`。

**基线对照（自己实测）**：本次未单独跑改动前全量，而是以 SDSimos 阶段 E 关账的现场重算为基线
（`170/362/45/259/177/124/144` = 1281，前端 90）。**delta 干净且可解释**：

- Java 逐模块**逐值不变**（1281 不变）——本次**零 Java 用例增删**（`WebuiAssetsTest` 只改了一个资产清单常量，用例数仍 8）。
- 前端 **90 → 96（+6）** ＝ 新增 `notifications.test.cjs` 的 6 条；两处下界同改（`run-gate.cjs` 的 `MIN_TESTS`、`gate-contract.test.cjs` 的 `MIN_ASSERTIONS`）＋ `REQUIRED_FILES`。
- ★ JS 断言由 `exec-maven-plugin` 独立跑、**不并入 surefire 合计** ⇒ **1281 不变是正确的**，"下界是真护栏"的证据**不是数字变大，是它红过**（见 §四）。

## 四 变异（九道门禁的 JS 适用子集；3 体全 KILLED）

装置：`mutants/mut-round.sh` + `mutants/make-mutant.py`；逐轮日志 `mutants/logs/{m1,m2,m3}.log`。

| 体 | 靶 | 变异 | 结果 | 红点（被保护断言） |
|---|---|---|---|---|
| **m1** | `index.html` | 把右栏审批块加回 | **KILLED** | `not ok 46 - index.html-has-notification-element-and-no-right-panel-approvals` |
| **m2** | `notifications.js` | `renderInto` 不读 `/api/approvals`（改 `Promise.resolve({pending:[]})`） | **KILLED** | `not ok 44 - renderInto-reads-exactly-one-GET-api-approvals`（连带 45） |
| **m3** | `api.js` | 删 `api.approvals`（function+export），**不改** `write-allowlist.test.cjs` | **KILLED** | `not ok 95 - dynamic-write-functions-hit-only-allowed-endpoints`（`write-allowlist.test.cjs`，连带 44） |

**隐藏断点结论（research §B.2 / 计划 §T1）**：m3 证明 `write-allowlist.test.cjs:112` 的
`await api.approvals()` **确实会因删 `api.approvals` 而红**（`not ok 95`）。⇒ **本任务选择保留
`api.approvals`、不动该文件**（计划的"不动"路线），隐藏断点由 m3 当场兑现、跑完即逐字节还原。

**九道门禁逐条落点**（`mutants/logs/*.log` 自指）：① 干净世界（`clean_world_md5==orig_md5` 才开跑）；
② 变异体字节不同（`mutant_md5 != orig_md5`）；③ 推成**规范名目标文件**（非 variant 名）；④ 清陈旧
`.class` —— **N/A**（node 直读源，无编译产物）；⑤ `Tests run>=1`（断言 `# tests 96`）；⑥ surefire mtime
—— **N/A**（无 surefire），改以**本轮专属** `mutants/logs/mN.log.gate`；⑦ 红点落被保护断言（逐轮列出）；⑧ `cp`
**逐字节**还原（`restored_md5==orig_md5`，**未用 `git checkout`**）；⑨ 日志自指（三个 md5 写入本日志，读取处
`req()` **先断言非空**）。

**还原复核**（独立于装置）：9 个被触碰文件 live md5 == pristine md5，全部 `OK`（见 §五 命令）。

## 五 交付后运行时核（served assets，非浏览器）

`logs/served-e2e.log`：以 shade jar 在**独立端口**（5871/5875/5873）起 `--demo`：

- `GET /` → **200**，`id="notifications"` ×1、`notify-bar` ×1、`src="notifications.js"` ×1、`approvals-count` **×0**。
- `GET /api/approvals` → **200** `{"pending":[]}`（后端保留，D1 兑现）。
- `GET /notifications.js` → **200**，**md5 与源逐字节相同**（`4d6f7bb0…`）⇒ 新资产确实进了产物并被服务。
- 自起实例已停（**只 kill 自己的 pid 79070**）；**`5818` 实例（pid 64974）全程未动**。

## 六 取代说明（执行期）

1. 计划 §T1 的 `<div id="notifications" class="notify-bar" hidden>` 示例**已采用**；额外加 `aria-live`/`aria-label`。
2. 通知栏**初始 `hidden`**；`renderInto` 在首次读数（成功或不可达）后置 `node.hidden=false` ⇒
   可达时显示 `待批 N`、不可达时显示 `审批未接入（…）`，避免"永远隐藏"。
3. 通知栏在 `.wb-overlay`（`pointer-events:none`）内，故**必须** `pointer-events:auto`（已加）；
   底距 `bottom:104px` 抬到时间轴之上（时间轴仍在 `.wb-overlay` 流内、未被推离视口）。
4. `notifications.js` 以 `DOMContentLoaded` **自动挂载**（不改 `app.js` 的 `boot` 去调用它）——
   `app.js` 保持"纯撤除"最小改动。

## 七 我未能核实的

1. **浏览器内的布局/点击未实测**：`bottom:104px` 是否在**所有**分支/多行时间轴形态下都清空时间轴、
   `pointer-events:auto` 是否真的可点，均来自 **CSS 阅读**（research §E.3 同样只到 CSS 阅读）。
   本机无可用 `playwright` 模块（`~/.cache/ms-playwright` 有 chromium，但 `require('playwright')` 解析失败），
   未跑浏览器 e2e。
2. **`/api/approvals` 有非空待批时的通知栏渲染**未在真待批状态下实测（served 轮为 `{"pending":[]}`）；
   动态用例用记录型 fetch 喂了 `{pending:[{...},{...}]}` ⇒ "待批 2" 的逻辑层证明，非真实审批夹具。
3. **多分支/多行时间轴**下 `bottom:104px` 是否仍不遮挡未测（demo 为单分支）。
4. **`api.js` 未被本次改动**，但 m3 证明了它被改时隐藏断点会响；**未测**其它模块是否也有类似的
   "测试直接调 api.X" 隐藏耦合（只核实了 `write-allowlist.test.cjs:112` 这一处）。
5. 门禁基线**未在本 worktree 改动前单独重跑**（以 SDSimos 阶段 E 现场重算为基线，见 §三）；
   其"逐值不变"是建立在**零 Java 用例增删**这一结构性事实上的。
