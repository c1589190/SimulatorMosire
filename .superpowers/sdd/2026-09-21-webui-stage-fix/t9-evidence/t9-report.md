# T9 报告 —— 「建议/待决」信号（服务端计算 + 列表显示）

> 分支 `wsf/t9`（worktree `.claude/worktrees/wsf-t9`），基线 = **当前 HEAD `14add68`**（T1~T8 已合并）。
> 计划 `docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` § T9；spec `…-design.md` §四.1 / §六.2 / §〇.1 **D7（已裁）**；
> research §C.6（实测：该信号**不存在**）。**只做 T9**；未改 spec / 计划正文；未落 revision；未做 T10+。
> 日期 2026-09-21。

---

## 〇 结论摘要

- **服务端按 D7 公式算出 `due`**：`due = (当前 tick − 该 dm 最近一次落 Directive 的 tick) ≥ decisionCadenceTicks`；
  **首次（无任何 Directive）恒 `due`**。落在 **T5 建的 `SdQueryService`**（只读面，风格/落点一致），经 `ApiViews` 并入
  `GET /api/sd/decision-makers`（列表）与 `…/{id}`（详情）：新增字段 **`due` / `lastDirectiveTick` / `ticksSinceLast`**。
- **只读派生**：只查 `SdState.directives()` / `SdState.decisionMakers()` 的**公共访问器**（铁律 3：走 sd 模块自己的口，不在 app 层内省）；
  **不写状态、不落 revision**（铁律 2：本类没有写入口）。当前 tick 取**被查询快照**的 `meta().timestamp().tick()`。
- **前端**：★ **T7 早已把展示位建好且绑的就是 `maker.due`**（`pendingStatusText(maker.due)` / 左栏 `decisionMakerFields().pending`）
  ⇒ **T9 无前端生产改动**，把服务端真值接上后列表项即显示「待决 / 非待决」；T9 的前端交付 = **新 `pending-signal.test.cjs` 6 条护栏**。
- **门禁**：`./mvnw clean verify` **rc=0**（实现轮 **第 3 次尝试**——attempt2 仅因新增 Javadoc 折行触发 Spotless，`spotless:apply` 后绿；
  ★ **attempt1 亦 rc=0**，在补 C17 集合判据之前）；变异轮后复跑 **rc=0**（**第 1 次尝试**）。
  **8/8 `SUCCESS [`**；**1337** = `170/368/45/259/178/124/193`（现场重算，只取模块汇总行）；`BugInstance size is 0` ×7；`[ERROR]` 0；
  前端 **159/159**。
- **delta 干净**：基线（T8）**1331** = `…/187` ⇒ 终态 **1337** = `…/193`，**只有 app 187→193 = +6**（`SdDecisionMakerApiTest` 13→19），
  其余六模块逐字不变；前端 **153→159 = +6**（新 `pending-signal.test.cjs`，两处下界 + `REQUIRED_FILES` 同改）。
- **变异 13 轮 13 KILLED / 0 存活**：T9 自身 **6 个 Java + 2 个 JS**，另按 **裁定 42** 重跑 T5 旧靶 **5 个**（T9 改了 `SdQueryService`/`ApiViews`/既有测试）。
  逐轮 `cp` **逐字节还原**（`restored_md5 == orig_md5`）；装置日志**自指**（`self_md5` 非空断言）。

---

## 一 落点与设计（逐条对 D7）

| 决定 | 落地 | 依据 |
|---|---|---|
| 公式 | `since = tick − max(该 dm 所有 Directive 的 tick)`；`last == null ⇒ due=true`；否则 `due = since >= cadence` | D7 逐字 |
| "最近一次" | 显式求 **max**（`directives()` 是**插入序表**，R4 只保证 `(dm,tick)` 唯一，不保证按 tick 有序） | R4 不变量（`IssueDirectiveHandler:129-143`、`SdState:253-267`） |
| 首次 | `lastDirectiveTick`/`ticksSinceLast` 置 **`null`**（"没有基准"= 无穷大），**不填 0/-1** | 避免把"从未决策"伪装成"刚决策过" |
| 当前 tick | `state.meta().timestamp().tick()`（推进只改它；决策标记只由命令落） | 铁律 2 |
| 只读 | 新代码**没有任何** `withXxx(...)` / `submit` / 写盘 | 铁律 2 |
| 读 sd | `state.module("sd")` → `SdSnapshot.state()` 的公共 record 访问器 | 铁律 3 |

**改动文件**（3 生产/测试 + 3 前端/门禁）：

| 文件 | 改动 |
|---|---|
| `simos-app/.../app/query/SdQueryService.java` | `pending(...)` + `tickOf(...)`；`DecisionMakerInfo` 加 `pending` 字段 + 新 `PendingSignal` record；`sdState` 改收 `SimulationState`（一次取状态，tick 与 sd 切片同源） |
| `simos-app/.../app/gui/ApiViews.java` | `decisionMaker` 暴露 `due` / `lastDirectiveTick` / `ticksSinceLast`（**取代** T5 的 `due=null` 占位） |
| `simos-app/src/test/.../gui/SdDecisionMakerApiTest.java` | 既有 1 条断言按新语义改（`due` null→true）+ **6 条新用例** + 夹具助手 |
| `simos-app/src/test/js/pending-signal.test.cjs` | **新增 6 条**前端护栏 |
| `simos-app/src/test/js/run-gate.cjs` + `gate-contract.test.cjs` | 下界 **153→159**（两处同改）+ `pending-signal.test.cjs` 进 `REQUIRED_FILES` |

★ **`GuiServer` / `Shell.java` 零改动**（路由与装配签名不变）⇒ **不触发** T6/T8 的 `GuiServer` 重跑；`panels.js` / `map.js` / `modes.js` / `index.html` / `app.js` / `api.js` **逐字节未动** ⇒ T7 的变异证据**不因 T9 作废**（`panels.js` md5 仍为 T7 终态 `29019a49af240d637dd891acfd761892`，与 `t7-report.md` 在案值一致）。

---

## 二 判据逐条实测

### 2.1 spec `C16`（推进 ≠ 决策）

`advancingTimeChangesDueWithoutCreatingDecisionMarkers`：`pendingFixture()`（4 决策人 + 4 条 `sd.IssueDirective`）后
`POST /api/advance {from:7,to:9}` ⇒

| 观测 | 实测 |
|---|---|
| `sd.directives().size()` | 推进前 **4** → 推进后 **4**（**不产生**任何决策标记） |
| `core.head(main)` | `revisionBefore + 1`（推进本身恰加 1 条 revision） |
| `dm-wait.ticksSinceLast` | **2**（9−7），`due=false`（2 < cadence 5） |
| `dm-first.due` | **true**（无 Directive ⇒ 恒待决） |

### 2.2 spec `C17`（`due` 集合 = 离线公式）

`dueSetMatchesTheOfflineFormulaAfterAdvancing`：推进到 tick 9 后，列表里 `due=true` 的 id 集合
**逐值等于**离线手算集合（冻结字面量）`["dm-due","dm-first","dm-max"]`：

| dm | cadence | Directive ticks | 最近 tick | 间隔（tick 9） | due | 离线推导 |
|---|---|---|---|---|---|---|
| `dm-first` | 5 | — | null | ∞ | **true** | 无上次 ⇒ 恒待决 |
| `dm-wait` | 5 | 7 | 7 | 2 | false | 2 < 5 |
| `dm-due` | 2 | 7 | 7 | 2 | **true** | 2 == 2（**边界**） |
| `dm-max` | 2 | 4, 6 | **6** | 3 | **true** | 取 max；3 ≥ 2 |

### 2.3 三个分支（任务书显式判据）

| 分支 | 用例 | 实测值 |
|---|---|---|
| **首次恒 due** | `firstTimeAlwaysDueWithNullLastDirective` | `due=true`，`lastDirectiveTick=null`，`ticksSinceLast=null` |
| **未到 cadence 不 due** | `dueIsFalseBeforeCadenceAndTrueExactlyAtCadence`（tick 7） | `lastDirectiveTick=7`，`ticksSinceLast=0`，`due=false` |
| **到点 due** | 同上（推进到 tick 9） | `ticksSinceLast=2`，`due=true`（**恰好 == cadence**） |
| 附加：「最近一次」取 **max** | `lastDirectiveTickUsesTheMaximumTick` | ticks 4、6 ⇒ `lastDirectiveTick=6`（取 4 的实现算成 3 ≥ 2 ⇒ 红） |

### 2.4 R4 口径（回归，既有不变量）

`(dm, tick)` 唯一由**既有**用例钉住，无需新增：`IssueDirectiveHandlerTest:69`（`rejected(second)` 含「R4 违反」）；
`SdStateInvariantTest:40`（状态期 `hasMessage("R4 违反：决策人 dm1 在 tick 0 已有 Directive")`）。本轮 T9 未改 R4 路径。

### 2.5 列表 ↔ 详情一致

`listAndDetailAgreeOnDue`：逐条比对 `GET /api/sd/decision-makers` 与 `…/{id}` 的 `due` / `lastDirectiveTick`，**全一致**。

---

## 三 门禁

| 项 | 值 |
|---|---|
| `./mvnw clean verify` | **rc=0**（实现轮 attempt3；attempt2 仅 Spotless 折行 ⇒ `apply` 后绿；attempt1 亦 rc=0）；变异轮后复跑 **rc=0（第 1 次尝试）** |
| 模块 | **8/8 `SUCCESS [`**（`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` + 父 POM） |
| 用例总数 | **1337** = `170/368/45/259/178/124/193`（现场重算，`grep '^\[INFO\] Tests run:' … | paste -sd+ | bc`，只取模块汇总行） |
| 基线 delta | T8 **1331** = `…/187` ⇒ **只有 app +6**，其余逐字不变 |
| SpotBugs | `BugInstance size is 0` **×7** |
| `[ERROR]` | **0 行** |
| 前端门禁 | `[frontend-gate] OK tests=159 pass=159 fail=0`（153→159，两处下界同改 + 新文件入 `REQUIRED_FILES`） |
| 机器 | `nproc=8`、单轮 `clean verify` ≈ 58 s，**无被杀轮** |

证据：`logs/clean-verify.attempt1.log` / `attempt2.log`（Spotless 红）/ `attempt3.log` / `clean-verify-after-mutants.log` + 各自 `-rc.txt`。
`recomputed-total.txt` 为 attempt1 的现场重算（1336）；终值为 1337（attempt3 / after-mutants 的模块汇总行现场相加）。

---

## 四 变异（13 轮 13 KILLED / 0 存活）

装置：`mutants/mut-java.sh`（九道门禁）+ `mutants/mut-js.sh`（前端版）+ `mutants/make-mutant.py` / `py/*.py`；
pristine = **最终字节**；逐轮 `cp` 逐字节还原并断言 `restored_md5 == orig_md5`；`self_md5` 写进日志（读取处先断言非空）。

### T9 自身（6 Java + 2 JS）

| 变异体 | 靶子 | 语义 | 期望红点 | 判定 |
|---|---|---|---|---|
| **t9m1** | `SdQueryService.pending` | `due` 恒 `true`（忽略 cadence） | `dueIsFalse…` / `lastDirectiveTickUses…` / `dueSetMatches…` / `advancing…` | **KILLED** |
| **t9m2** | `SdQueryService.pending` | "最近一次"取**最小** tick | `lastDirectiveTickUsesTheMaximumTick` | **KILLED** |
| **t9m3** | `SdQueryService.pending` | 首次**不** due | `firstTimeAlwaysDue…` / `dueSetMatches…` / `advancing…` / `listReturns…` | **KILLED** |
| **t9m4** | `SdQueryService.pending` | 到点写成严格 `>` | `dueIsFalse…`（边界）/ `dueSetMatches…` | **KILLED** |
| **t9m5** | `ApiViews.decisionMaker` | `due` 写死 `false` | 5 条 | **KILLED** |
| **t9m6** | `SdQueryService.pending` | `ticksSinceLast` 差一 | `dueIsFalse…` / `advanced…` / `lastDirectiveTickUses…` | **KILLED** |
| **t9js-m1** | `panels.js` 列表项 | 待决文本写死「待决」 | `right-list-renders-pending-from-server-due` | **KILLED** |
| **t9js-m2** | `panels.js` `pendingStatusText` | 非布尔分支返回「非待决」 | `pending-status-text-maps-only-real-booleans…` | **KILLED** |

★ 三个分支各有**至少一个**能杀它的变异体：**首次** ← t9m3；**未到点** ← t9m1；**到点** ← t9m4（边界）；另 t9m2 钉 max、t9m6 钉间隔逐值。
★ 新增的 **C17 集合判据**不是装饰：被 t9m1 / t9m3 / t9m4 / t9m5 四轮**各自杀掉**。

### 裁定 42 重跑的 T5 旧靶（5 Java）

T9 改了 `SdQueryService.java`、`ApiViews.java` 与既有测试 `SdDecisionMakerApiTest.java` ⇒ 按 **裁定 42** 在**当前字节**上重放 T5 的语义
（`make-mutant.py` 中 t5m1~t5m5 的锚点已按新字节重表达）：

| 变异体 | 靶子 | 语义 | 判定 |
|---|---|---|---|
| t5m1 | `SdQueryService.listDecisionMakers` | 不筛 affiliation | **KILLED**（红 2 条过滤用例） |
| t5m2 | `ApiViews.decisionMaker` | 缺 `cadence` 字段 | **KILLED** |
| t5m3 | `SdQueryService.listDecisionMakers` | 空库抛异常 | **KILLED** |
| t5m4 | `SdQueryService.parseFilter` | 未知 kind 不抛、出空集合 | **KILLED** |
| t5m5 | `GuiServer` 列表路由 | 路由整段去掉 | **KILLED**（+10 条连带） |

汇总：`mutants/logs/mut-java-summary.txt`（逐轮 orig/mutant/restored md5 + 红点方法名）；前端 `mutants/logs/mut-js.log`。
**没为造红放松任何判据**；无 VOID / abort / RESTORE_MISMATCH（`grep -c` = 0）。

---

## 五 我未能核实的

1. **`mvn test` 不跑 SpotBugs** —— 本轮的 `BugInstance size is 0 ×7` 来自 `clean verify`（正确通道），非单点 goal。
2. **T5 旧靶的"同一语义"是重表达**：t5m3/t5m5 的锚点在 T6（GuiServer）与本轮（SdQueryService 取状态方式）之后已变，故按**当前字节**重新表达；
   判的是**语义**（空库抛 / 路由去掉），**不是**逐字节复现 T5 当年的补丁。t5m1/t5m2/t5m4 的锚点与 T5 原样一致。
3. **T7 的变异证据未在本轮重跑**：依据是 `panels.js` 等 T7 靶文件**逐字节未动**（`panels.js` md5 `29019a49…` 与 `t7-report.md` 在案值一致）。
   新增 JS 测试文件改变了 `node --test ./*.test.cjs` 的**全局 TAP 序号**，但 T7 的杀点判据是**用例名**、且其靶字节未变 ⇒ 证据成立。**未实测**"重跑 T7 仍逐条被杀"（判断为不必要）。
4. **前端"真值"只到纯函数 + 渲染夹具层**：`pending-signal.test.cjs` 用**替身 app/api/DOM** 调**真** `renderDecisionRight`/`renderDecisionLeft`，
   **未跑真实浏览器 e2e**（与 T7 同一条开口项：本机 Chromium revision 与可用 playwright 不匹配）。**列表项/左栏在真浏览器里的像素表现未验。**
5. **`pendingStatusText` 的"非布尔 ⇒ —"** 与 T7 的 `decision-mode.test.cjs` 有重叠断言——本轮的 t9js-m2 只证新文件的判据有牙，**未逐一复核** T7 同名断言的独立判别力。
6. **真 sd 档未验**：全部经 `SdDecisionMakerApiTest` 的**合成夹具**（创世 tick=7、4 决策人）——**未在真实 `test_integration` 档上跑**（本机无档，同 T5/T7 开口项）。
7. **`due` 语义的两个边界未覆盖**：① `lastDirectiveTick > 当前 tick`（Directive 记在未来 tick）⇒ `since` 为负 ⇒ `due=false`（按公式**成立**，但**无用例**）；
   ② 同一 dm 多条 Directive 跨分支/回退后的 max 语义（`directives()` 随 revision 变化，本轮的 max 只在**同一快照**内测过）。
8. **`decisionCadenceTicks` 与推进的交互**：`AdvanceTime` 的 `to` 直接成为新 tick（`TimeAdvance:156`），跨大跨度（如 +100）的 `due` 行为**未单独测**（公式单调，判断无新语义）。
9. **未做**：T10「开始决策」入口（`sd.StartDecision`）、任何落 revision 的写（本任务纯只读派生）。

---

## 六 带裁定的遗留

- **L1（前端范围声明）**：T9 的"前端"**没有生产代码改动**——展示位是 T7 建的、绑的本来就是 `maker.due`；T9 只**接服务端真值 + 加护栏**。
  若读"T9 改过 `panels.js`"是误读（它逐字节未动）。
- **L2（`ticksSinceLast` 的展示）**：`lastDirectiveTick` / `ticksSinceLast` 已进 API 响应，但**前端未显示这两个字段**（左栏只显示「待决/非待决」）。
  计划 §T9 只要求"列表项显示待决标记"⇒ 未越界加 UI；若要摊开间隔，归后续 UI 任务。
- **L3（当前 tick 的来源）**：用**被查询快照**的 tick（`?revision=` 时即该 revision 的 tick），不是分支 head —— 这是有意的"所见即所算"，**无 spec 逐字依据**，属实现期裁定。
- **L4（C17 的离线值）**：测试里以**冻结字面量**给出离线集合（非就地重算），四 dm 的期望值见 §2.2 表。

---

## 七 证据落点

```
t9-evidence/
├── t9-report.md                         本报告
├── logs/
│   ├── clean-verify.attempt1.log        实现轮（补 C17 前）rc=0、1336
│   ├── clean-verify.attempt2.log        Spotless 折行红（新增 Javadoc）
│   ├── clean-verify.attempt3.log        最终字节实现轮 rc=0、1337
│   ├── clean-verify-after-mutants.log   变异轮后复跑 rc=0、1337
│   ├── *.attempt*-rc.txt                各轮 rc
│   └── recomputed-total.txt             attempt1 现场重算（1336）
└── mutants/
    ├── mut-java.sh / mut-js.sh           九道门禁装置
    ├── make-mutant.py                    Java 定点变异（t9m1~6 + t5m1~5）
    ├── py/t9js-m1.py / t9js-m2.py        前端定点变异
    ├── pristine/                         最终字节备份（SdQueryService/ApiViews/GuiServer/panels.js）
    └── logs/
        ├── t9m1..t9m6.log / t5m1..t5m5.log  逐轮 Maven 原文 + 装置补记（自指 md5）
        ├── mut-java-summary.txt             逐轮判定与红点
        ├── mut-js.log / t9js-m1.tap / t9js-m2.tap
        └── mut-java.log                     末次运行的控制台原文（★ 见下）
```

★ **装置产物自身的坑（如实记）**：`mut-java.sh` 开头 `: > "$ALL_LOG"` **会截断**累积日志；本轮分两次调用（先 t9m、后 t5m）⇒
`mut-java.log` 只剩**后一次**（t5m）。**逐轮 `t9m*.log` / `t5m*.log` 完整且在档**，`mut-java-summary.txt` 由逐轮日志重建 ⇒ 判定不依赖 `mut-java.log`。
