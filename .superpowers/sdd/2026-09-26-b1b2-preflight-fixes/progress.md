# SDD ledger — plan: docs/superpowers/plans/2026-09-26-b1b2-preflight-fixes.md

Spec: docs/superpowers/specs/2026-09-26-s1-actor-property-design.md（已读，§十 是权威）

## Setup

- 工作区：**就地**（本会话配置为 worktree 之外就地工作；分支 ts/m1，非 main ⇒ 不需要额外同意）
- 脚本源有 CRLF，已清理到 job tmp 后使用（sdd-workspace / task-brief / review-package；**源目录无 task-done**，其 ledger 行按技能描述的格式手写）

## 任务清单

- [ ] Task 1  B1 —— 让所有有人的 cohort 都参与压力计算
- [ ] Task 2  B2 —— 危机指标的时间基准一致
- [ ] Task 3  最小 probe —— 证明两个 bug 已消失

## Pre-flight scan（任务间接口）

| 检查 | 结果 |
|---|---|
| Task 1 Produces `EconomySettlement.industriesAt(EconomyData, HexCoord)` ↔ 谁消费 | **无**。Task 2 只碰 `CrisisMonitor`，Task 3 无源码改动 |
| Task 2 Produces（无新公开 API，只改口径）↔ 谁消费 | **无** |

**Pre-flight: no shared interfaces**（Task 1 与 Task 2 互不相干，可独立验证）

### 自洽性行（我读计划时逐条核过）

| # | 行 | 发现 |
|---|---|---|
| S1 | Task 1 Step 1 断言用 `PopulationDynamics.FERTILE_MIN_DAYS` | ✅ 该类注为 `public static final` |
| S2 | Task 1 Step 1 用 `economy.laborSupply()` | ✅ `EconomyData.laborSupply()` 存在 |
| S3 | Task 1 Step 1 用 `g.id().value().contains(":b")` 判新生儿 | ✅ 与实测 id 形状一致（`…:FEMALE:FEMALE:b1`）；坐标是数字，不会误命中 |
| S4 | Task 1 Step 3 说 `industriesAt` 在 `EconomySettlement.java` ~598 行且是 `private static` | ✅ 已读代码确认 |
| S5 | Task 2 Step 1 的 `EconomyTestWorld.of(...)` | ⚠️ **未验**该工厂方法是否存在 ⇒ 执行 Task 2 时先读该类真实 API（计划已注明"按现有 API 填，不许改它的公开 API"） |
| S6 | Task 2 Step 3 用 `Industry.progressDays()` | ✅ 读口实测有 `progressDays` |

### Task 1

- `Task 1: Ruling: 计划 Step 2 的命令不可用` —— `-pl simos-app -am` 会把 `-Dtest=PopulationR4Test` 应用到**每个**依赖模块，
  在 `simos-util` 就 `No tests matching pattern` ⇒ BUILD FAILURE。改用
  `-Dsurefire.failIfNoSpecifiedTests=false`，**并以 `simos-app/target/surefire-reports/` 的报告核对真的跑了该类**
  （AGENT.md §三：`-q`/过滤会让类名写错也静默通过，故必须看报告）。
  — 代价：若类名写错会静默跳过 ⇒ 每步都核报告，不只看 rc。
- `Task 1: Ruling: industriesAt 必须 public，不是计划说的"包内可见"` —— 调用方
  `PopulationEconomyTimeParticipant` 在 `io.mosire.simos.app.time`，与被调方
  `io.mosire.simos.economy.time` **不同包且不同模块**，package-private 不可见。
  — 代价：它成了公开 API 面（已在 javadoc 注明两处共用与理由）。

**Task 1: complete** (commits 021e3c4..032e5bd, tests: `./mvnw -pl simos-app -am test -Dtest=PopulationR4Test -Dsurefire.failIfNoSpecifiedTests=false` → 8/8 pass)

验收数字（修前→修后）：minorStressMax 0→1373 · newbornStressMax 0→1217 · totalDeaths 44→66
变异自证：两条断言各自被看到失败（第 ② 条借"临时停用第 ① 条"跑到）⇒ 反向重写 ⇒ 复绿

### Task 2

- `Task 2: Ruling: 计划的测试夹具设想不成立` —— ① `EconomyTestWorld` **没有 `of(...)`** 工厂
  （计划里那行是猜的），它只有 `genesis()`/`data()`（**创世**状态）；② 而 B2 的 bug 恰恰是**相位**问题
  ⇒ 必须有"同一周期的不同天"的 economy 状态。③ 既有能力在 `PopulationR4Test` 的 private 夹具里
  （`seeded()` + `advanced(fixture, days)`，真播种器 + 真协调器）。
  **决定**：把它提取成包内共享的 `PopulationEconomyFixture`（public，供 `app.crisis` 包的测试用），
  两处共用 —— 与 B1 的"共用同一个方法"同一原则（两处各写一遍必然漂）。
  — 代价：动了既有测试文件的内部结构（纯搬移，行为不变）。**已验**：提取后 `PopulationR4Test` **8/8 仍绿**。
- `Task 2: Ruling: 测试的相位窗口选错了（不是代码错）` —— 首版取**第 1 周期**的第 1/60/119 天，
  但沙漠格在**第 1 周期靠创世库存过活**（真实测：修后 day1=["CLOTH"] 而 day119=["CLOTH","FOOD"]）
  ⇒ "第 1 天就该缺粮"的前提不成立，三个相位**本来就不该一致**。
  **决定**：窗口改到**第 2 周期**（tick 121/180/239）—— 那时创世库存已耗尽，才是真正的"持续缺粮"。
  判别力不受影响：第 2 周期第 1 天真实满足率为 0，旧分母（120 天）会读成 ≈99.2% ⇒ 不报 FOOD ⇒ 红。
  — 代价：无（纯夹具窗口修正，不改被测口径）。
- `Task 2: Ruling: 断言范围收窄到"由满足率判定"的类别` —— 首版断言"三个相位类别集合**完全相同**"过强：
  `MORTALITY` 的分子是"本周期**至今累计**死亡"、分母是**当前人口**，它**本来就该随相位增长**
  （实测 day119=["MORTALITY","CLOTH","FOOD"] vs day1=["CLOTH","FOOD"]）⇒ 那是**正确行为**，不是 B2。
  B2 只出在"分子至今累计、分母整周期"的类别（FOOD/CLOTH/DEBT）。**决定**：断言只覆盖这三类。
  — 代价：DEBT 若在某些世界形态下不触发，则该类无覆盖（本夹具下 DEBT 未触发，属已知弱处）。

**Task 2: complete** (commits 032e5bd..85542c6 —— 含 ruling 引入的夹具提取 6faa09c，tests: `-Dtest=CrisisMonitorPhaseTest` 1/1 + `-Dtest=PopulationR4Test` 8/8 → 全绿)

验收数字（第 2 周期，沙漠格）：
| 相位 | 修前 | 修后 |
|---|---|---|
| 第 1 天 tick121 | FOOD **不报** | FOOD **报** |
| 第 60 天 tick180 | 报 | 报 |
| 第 119 天 tick239 | 报 | 报 |
变异自证：分母退回 cycleDays ⇒ `expected ["CLOTH","FOOD"] but was []` ⇒ 还原复绿

**Task 3: complete** (commits 85542c6..861883e, tests: 实档 probe —— 判据① 0-14/新生儿压力 min 762/478（修前恒 0）；判据② elapsedDays=1 vs cycleDays=120 且红灯照报)

产物：`.superpowers/sdd/2026-09-26-b1b2-probe/`（readings.md + probe2.py + probe2_crisis.json）
探针实例已用 PID 停（12664），store 在 job 目录

## 收尾

`./mvnw clean verify`（前台）→ **BUILD SUCCESS**，Reactor **11 个模块全 SUCCESS**：
SimulatorMosire / UtilSimos / MapSimos / EconomyApiSimos / SocialSimos / UnitSimos /
CoreSimos / SDSimos / LedgerSimos / EconomySimos / SimosApp

**Tests run: 4594, Failures: 0, Errors: 0, Skipped: 0**（SimosApp 模块 667）
本轮两个新测试类：`CrisisMonitorPhaseTest` 1/1、`PopulationR4Test` 8/8（报告 mtime 13:49/13:50，落在本轮）
Spotless / Checkstyle / SpotBugs / 前端门禁（297 通过）全过。

Final review：已派独立评审者（最 capable 模型、全新上下文），评审范围 `021e3c4..861883e`。

## Final review（独立评审者，最 capable 模型，范围 021e3c4..861883e）

**Verdict：不可关账（先修 C1）** —— 评审抓到一个我引入的**回归**，独立复现后确认成立。

### Final: fixed C1（Critical）—— 关账日 progressDays==0 而流水是整周期

- **失效场景**：tick 120/240/360/600（关账日）`progressDays` 已被收获那一支归零，
  而 `FlowRow` 的清零在**次日** ⇒ 流水是**整周期**的。我的 `Math.max(1L, progressDays)`
  把"整周期缺口 ÷ 1 天需求"算成 **0‰** ⇒ **全图假阳性红灯**，次日又消失（一天一闪）。
- **既有证据**（评审引用，我逐条核过）：`EconomyFlowCycleTest` 的
  `theClosingDayCarriesTheWholeCyclesIncome`（"关账后进度归零" + income 是整周期毛产）
  与 `theFirstDayOfANewCycleStartsEveryFieldFromZero`（清零在次日）。
- **修法**：`progressDays == 0 → cycleDays`（创世那一支 unmet == 0 ⇒ 满足率恒 1000‰，行为不变）
- **验证**：新增 `closingDayReadsTheWholeCycleNotOneDay` —— **RED**（`expected 120L but was 1L`）
  → 修 → **GREEN**；`CrisisMonitorPhaseTest` 2/2、`PopulationR4Test` 8/8
- ★ **为什么原测试没抓到**：三个相位 121/180/239 全在周期**内部**，探针读 121→239→241
  也**绕开了 240**；而计划 Review Focus ④ 把 `progressDays == 0` 当成"周期第一天"，那个前提本身错了。

### Final: fixed I1（Important）—— DEBT 的键名与语义脱节

`cycleNeedMilli` 装的是"至今需求"，名字却在说整周期。改为 `elapsedNeedMilli` + 补 `elapsedDays`/`cycleDays`，
并更正 `Kind.DEBT` 与类注表两处 javadoc。（评审核过仓内无消费方。）

### Final: fixed I3（Important）—— 兜底在热路径上全表扫产业

`industriesByHex(EconomyData)` 建一次索引、放在日循环外；`industriesAt` 拆成两个重载
**共用同一行取值逻辑**（"某一格有哪些产业"仍只有一个答案来源）。无配额批次 ≈ 6,263 且逐月累积。

### Final: fixed I2（Important）—— `readings.md` 对档0/档1 差异的解释站不住

更正为"**两类批次读的是不同的产业行**"（有配额按 LaborAllocation 可跨格、无配额按居住格），
并标注"新生儿与其母亲同压"只是**近似**、且这是 **S1 cohort 边界的前置**。

### Final: minor (deferred) —— 6 条（按纪律不进 fix pass，交用户定夺）

1. `CrisisMonitor` 里我把错因写成"cumulativeRationMilli 要绝对日号"—— 真实错因是**分子分母基准不同**（该函数是线性的）
2. `industriesAt` 与既有 `IndustryHexKeys.at(industries,q,r)` 是同一件事的两份实现（该类注恰好警告过这种漂移）
3. `elapsedDaysSeen/cycleDaysSeen` 取 max 且只进 FOOD/CLOTH（多产业 cycleDays 不同时只报最大值）
4. `Map.copyOf(evidence)` 不保序，而 javadoc 写"键序保序"（pre-existing，本次首次让它有变动可能）
5. `probe2.py` 把 job 绝对路径写死并提交，与 readings.md 指向的不是同一份
6. `readings.md` 对 `urban 新生儿 min=0` 只给了一种解释；代码上还有第二条路径（有产业但无对应阶层行 ⇒ need==0 ⇒ 满足率 1000‰ ⇒ 压力消退到 0）

### 评审对五条裁定的复核

裁定 1/2/3/5 **站得住**；**裁定 4 站得住但不完整**（窗口全在周期内部，正是 C1 成因）—— 已按 C1 补第 4 个相位。

### 修复后的最终 verify

`./mvnw clean verify` → **BUILD SUCCESS**，11 模块全 SUCCESS；
**Tests run: 4596, Failures: 0, Errors: 0, Skipped: 0**。
