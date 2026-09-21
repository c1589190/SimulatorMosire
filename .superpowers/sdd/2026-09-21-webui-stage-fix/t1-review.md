# T1 审查结论 —— 撤右栏审批 + 右下角通知栏

> 审查对象：`wsf/t1` = **`d9df5b3`**（worktree `.claude/worktrees/wsf-t1`，基线 `92444b2`，**未并入** `feat/adr1-core-scope`）。
> 审查方式：**只读 + 独立复现**（不依赖实现者装置）。审查方**未改任何生产/测试字节**；另跑 4 个独立变异体（见 §五），跑完逐字节还原。
> 日期 2026-09-21。控制器派单里那处可疑点（88 vs 90）**前提不成立**，见 §一。

## 〇 一句话

**T1 的干净轮前端门禁是 `tests=96`，不是 88**（控制器疑点**前提为假**；88 是 M9 期旧值）。
下界是**升**（90→96）不是降。隐藏断点**保留未删**、由 m3 兑现。通知栏可用；后端**零改动**。
实现者 3 变异全 KILLED、红点落被保护断言。**未发现需修的代码缺陷**；仅一处**原始日志杂乱哈希**（§六.2，已独立复现证伪该哈希）。
本轮**无生产/测试字节改动 ⇒ 无需提交代码**（仅新增本审查报告与台账一行）。

## 一 ★ 88 vs 90 的真相：**前提为假（既非 A 也非 B）**

| 事实 | 实测证据（文件:行 / 命令） |
|---|---|
| T1 干净轮前端门禁 = **96** | `t1-evidence/logs/clean-verify.attempt1.log:2170` = `[frontend-gate] OK tests=96 pass=96 fail=0`；`clean-js-gate.log:587`、`recomputed.txt:26` 同值 |
| **T1 证据根里根本没有 `clean-verify.log`** | `find …/t1-evidence -name 'clean-verify*'` ⇒ 只有 `clean-verify.attempt1.log`。控制器引用的文件名不存在 |
| 当前字节现场复跑 = **96** | `node --test --test-reporter=tap simos-app/src/test/js/*.test.cjs` ⇒ `1..96 / # tests 96 / # pass 96 / # fail 0` |
| 下界 = **96**（升，不是降） | `run-gate.cjs:19` `MIN_TESTS = 96`；`gate-contract.test.cjs:30` `MIN_ASSERTIONS = 96` |
| **88 的出处** | 全是**旧值**：`m10deploy`/`m9t11`/`uet9` 等 worktree 的 M9-T11 与 sd 期日志（`tests=88`），**与 T1 无关** |
| **90 的出处** | SDSimos 阶段 E / unit-ext T10（`sde` worktree `e-evidence/logs/clean-verify.log:2126` = `tests=90`） |

**增减清单（90 → 96，净 +6，无删减）**：唯一变化是**新增** `notifications.test.cjs` 的 **6 条**
（`notifications.test.cjs:34/40/48/53/75/87` 六个 `test(`），逐文件 `^\s*test\(` 计数合计 = **96**
（block 6 / gate 2 / map-edit 10 / geometry 9 / modes 13 / notifications **6** / region-boundary 9 / region-view 11 / timeline 14 / unit-tree 9 / write-allowlist 7）。
审批侧**没有删任何断言**——`write-allowlist.test.cjs` 未改（md5 `ac…` 不在 `orig-md5` 表内说明它本来就不在靶内，但工作区实测该文件未变）。

**新断言确实被跑到（不是"文件在但没进 gate"）**：`gate-contract.test.cjs:50` 的
`assert.deepEqual(testFiles(), REQUIRED_FILES)` 钉死**文件集合恰好相等**，且 `notifications.test.cjs` 在
`REQUIRED_FILES`（`gate-contract.test.cjs:9-20`）内；`node --test` 现场跑出 96 且含 `notifications` 的 6 条
（TAP 编号 44/45/46 即其静态/动态断言）。

⇒ **结论：不是 (A)，也不是 (B)。T1 没有把下界调低、没有让新文件溜出 gate；数字 96 是升了 6。**

## 二 隐藏断点（`write-allowlist.test.cjs:112`）

**处置：保留 `api.approvals`、不改该文件**（计划的"不动"路线，D1 已裁"后端保留"）。

- `api.js:252-253` `function approvals(){ return getJson("/approvals"); }` **在**；`api.js:277` 导出 **在**（实测命中 2 处）。
- 该文件 `write-allowlist.test.cjs:112` `await api.approvals();` **原样在**（未删、未改、未换）。
- **没有偷偷删断言**；且"删了会红"由**实现者 m3** + **审查方复核**两路兑现：删 `api.approvals` ⇒
  `not ok 95 - dynamic-write-functions-hit-only-allowed-endpoints`（`dynamic-write-functions-hit-only-allowed-endpoints` 内的 `await api.approvals()` 抛 `TypeError`）。

## 三 通知栏可用性

- **能收到并显示**：`notifications.js:38 renderInto` 调 `api.approvals()`（:41）；动态用例
  `renderInto-reads-exactly-one-GET-api-approvals` 用记录型 fetch 断言 **恰一次 `GET /api/approvals`**、
  `textContent === "待批 2"`、`hidden === false`。**真** `/api/approvals` 现场 = `200 {"pending":[]}`（§五 served 复现）。
- **`pointer-events:auto` 在**：`styles.css:1218`。★ 但须知：通知栏**没有任何点击处理器**，`pointer-events`
  只影响命中测试、**不影响显示**——所以"不设就点不到"这条在此**无行为后果**（设了是稳妥，不是必需）。
- **不挡底栏时间线**：`styles.css:1203-1218`（`position:fixed; right:16px; bottom:104px; z-index:2`）。
  时间轴 `.timeline-bar` 为 `.wb-overlay` 流内 `flex:0 0 auto`（`styles.css:982-991`），按 CSS 静态阅读
  其高约 `10+10+1+~30 ≈ 51px` ⇒ `104px` 有余量。★ **但（`M7d`/`M7g` 布局意图）没有任何进 Maven 门禁的测试守着**
  （`grep` 全部 `*.test.cjs` 只有通知栏字符串，无 `barBottom/timeline-bar/wb-overlay` 断言）；M7d/M7g 的守护是
  **浏览器 e2e**，不在 CI。报告 §七.1/§七.3 **如实披露**了"浏览器未测 / 多行时间轴未测"。
- **资产在册**：`WebuiAssetsTest.java:48-50` `WORKBENCH_SCRIPTS` 加 `notifications.js` ⇒ 存在 + 非空 + **classpath 可读**（`:87-95`）。

## 四 后端未误伤（核过）

`git diff --name-only 92444b2..HEAD`（去 `.superpowers`）**只有** 4 个 webui 资产 + `WebuiAssetsTest.java` + 3 个 JS 门禁文件；
`GuiServer.java`、`Shell.java`、`ShellApprovalTest`、`GuiApiTest` **逐字节未动**（`git diff --stat` 对它们**空输出**）。
`/api/approvals` 实测 `200 {"pending":[]}`。**结论：后端原样保留（D1 兑现）。**

## 五 变异

**实现者 3 体（复核通过，红点落被保护断言）**——原日志 `.log.gate` 复核：

| 体 | 靶 | 红点（实测 gate 原文） |
|---|---|---|
| m1 | `index.html` 加回审批块 | `not ok 46 - index.html-has-notification-element-and-no-right-panel-approvals` |
| m2 | `notifications.js` 不读 approvals | `not ok 44` + `not ok 45`（`renderInto-reads-exactly-one-GET-api-approvals` / `…-degrades-when-approvals-unavailable`） |
| m3 | 删 `api.approvals` | `not ok 44` + `not ok 95 - dynamic-write-functions-hit-only-allowed-endpoints` |

每轮自指 md5 齐（`orig_md5/clean_world_md5/mutant_md5/restored_md5`），`restored_md5 == orig_md5`，**无存活**。

**审查方独立补跑 4 体**（装置 `/tmp/opencode/t1review/rev-mut.sh`，不依赖实现者脚本；日志 `/tmp/opencode/t1review/rev.log`）：

| 体 | 靶 | 结果 | 说明 |
|---|---|---|---|
| **sx1** | 删 `<div id="notifications">` | **KILLED** | `not ok 46`（**通知栏被删 ⇒ 新断言红**，控制器点名要的那条） |
| **sx3b** | 删一条 `test(` 块（计数 95<96） | **KILLED** | `not ok 8 - assertion-count-is-not-below-the-frozen-floor`（**下界对"删测试"确有牙**） |
| **sx2r** | 只把 `run-gate.cjs` 的 `MIN_TESTS` 96→90 | **SURVIVED** | 见下 |
| **sx2c** | 只把 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS` 96→90 | **SURVIVED** | 见下 |

★ **对控制器"② 下界被调低 ⇒ 门禁红"的更正（重要）**：这条**期望本身不成立**。下界是**下**界，
把常量改小**不构成**任何被测不变量被违反 ⇒ 门禁**不可能**因"下界被调低"而红（任何 in-band 护栏都
查不了"自己的锚被改"）。**真正该证的"下界有牙"是"删掉一条断言/测试 ⇒ 计数跌破下界 ⇒ 红"**，已由
**sx3b（KILLED）**证明。另：两处下界**互为冗余**——只降一处，另一处仍卡 96 ⇒ **实际强度 = max(两处)**，
故"下界被调低"要**同时改两处**才生效，而那已是"改写护栏自身"，超出 any-in-band-guard 的范围。
⇒ **判定：设计限制，非 T1 缺陷，不修**（且不值得为它加"两常量相等"的守卫：改单侧本就不削弱强度）。

## 六 报告质量

1. ✅ **有 §七「我未能核实的」**（5 条：浏览器布局/点击、非空待批真状态、多分支时间轴、其它 `api.X` 隐藏耦合、基线未在本树改动前单独重跑）。
2. ⚠️ **一处原始日志杂乱哈希（非报告论断错）**：报告 §五/§一 说
   `GET /notifications.js` "md5 与源逐字节相同（`4d6f7bb0…`）"——**该论断正确**；但原始
   `logs/served-e2e.log` 末行是 `68d1c35f2 (src md5=4d6f7bb046ea98d39d758e968d1c35f2)`，
   这个 `68d1c35f2`（9 字符）**数学上不属于任何 webui 资产、也不在 `target/classes/webui`、无法复现**
   （已试 md5/sha1/sha256 前缀、去尾换行、CRLF、headers+body 变体，全不匹配）⇒ 是**装置读数/留痕的杂音**。
   ★ **审查方独立复现（决定性的）**：以 shade jar 起独立实例（**5881/5885/5883**，`--demo`，新 store），
   `GET /notifications.js` → **200 / `Content-length: 2940`**，落盘 md5 = **`4d6f7bb046ea98d39d758e968d1c35f2`** =
   源，`cmp` = **IDENTICAL**；`GET /api/approvals` → `200 {"pending":[]}`；**只 kill 自己的 pid 82314，`5818`(pid 64974) 全程未动**。
   ⇒ **报告论断成立；杂音哈希以本次复现为准。**
3. ✅ §三 自陈"本次未单独跑改动前全量"（以 SDSimos E 现场重算为基线），**未把推断写成实测**。
4. ✅ Java 逐模块不变（1281 = `170/362/45/259/177/124/144`）、`BugInstance size is 0 ×7`、`[ERROR]` 0、
   模块 8/8 `SUCCESS [`，均与 `clean-verify.attempt1.log` 现场重算一致。

## 七 我未能核实的

1. **未复跑全量 `./mvnw clean verify`**：仅现场复跑了**前端 `node` 门禁**（96/96，判据命脉）。全量以
   实现者已提交的 `clean-verify.attempt1.log`（rc=0、1281、`SUCCESS [` ×8、`BugInstance 0 ×7`、`[ERROR]` 0）为准——
   HEAD `d9df5b3` 自那时**未改**、worktree 干净，故该日志对象即当前字节。
2. **未做浏览器布局实测**（通知栏是否在某分支/多行时间轴下遮挡、`pointer-events:auto` 的真实可点性）：
   本机 `require('playwright')` 不可用（与报告 §七.1 同）。`104px` 的余量是 **CSS 静态阅读**。
3. **未在真"非空待批"状态**下看通知栏渲染（真 `/api/approvals` 为 `{"pending":[]}`；"待批 2"是记录型 fetch 的逻辑层证明）。
4. **未核**除 `write-allowlist.test.cjs:112` 外是否还有其它"测试直调 `api.X`"的隐藏耦合（报告 §七.4 同）。
5. 报告 §五 的 `68d1c35f2` 的**产生方式**未查明（只证了它**不是**任何可得的正确值）。

## 八 判定

- **无生产/测试缺陷**；控制器疑点（88/90、下界被降、隐藏断点被删）**逐条证伪**。
- 唯一建议性记录：**原始 `served-e2e.log` 的杂音哈希**（§六.2）+ **通知栏布局无 in-gate 守护**（§三，报告已披露）。
- **不修任何代码；不新增下界**（下界 96 已是当前真值，升/降均无据）。本审查**零字节改动**生产/测试。
