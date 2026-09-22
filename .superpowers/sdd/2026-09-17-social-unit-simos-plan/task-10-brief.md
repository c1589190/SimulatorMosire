# M3 Task 10 派单说明（控制器派单前扫描后）

**任务**：`MovementStatus` / `MovementState` / `UnitMoves.evaluate` + R9 + R10（判据二）
**BASE**：`631db04`（Task 9 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 2984~3295 行**（Task 10 全文）；M3 spec §4.5 的**冻结夹具与逐值表**（判据二）；`MoveFixture`（Task 8）。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-10-a（数字已逐条核过，全部对得上）**：`map(STEEP_65)` + `unit()`（speed 2、‰500）⇒ 段成本 12500 / 32500；at=20：预算 40000 → 27500 → 付不起 32500 ⇒ `IN_TRANSIT`、current H12、next H13、remaining **5000**；at=22：44000 → 31500 ⇒ remaining **1000**；at=23：46000 ≥ 45000 ⇒ `ARRIVED`；`map(IMPASSABLE_999)`：第二步空 ⇒ `NEED_REPLAN`、current H12、next/remaining 空。**与 spec §4.5 冻结表逐值一致**。
- **R-10-b（★ m1 前提不成立 + 补边界用例）** 计划的 m1（`>=`→`>`）**不会让 `atTwentyThreeHoursArrives` 红**：at=23 时第二步 33500 > 32500，仍 `ARRIVED`；提交套件里**没有任何用例落在"预算恰等于段成本"的等值边界上**（at=22.5 非整数 tick，不可达）。⇒
  1. 先按 m1 原形态跑：**预计存活**，如实记录（含 why）；
  2. **必补一条等值边界用例** `exactBudgetArrivalIsArrived`：自定义**speed = 5** 的在途单位（`Movement` 的 `speedAtDeparture` 也 = 5），`at = T0+9` ⇒ 预算 `5×1000×9 = 45000` **恰好** = 12500+32500 ⇒ 期望 `ARRIVED`、current H13、next/remaining 空。**再重跑 m1**：此时 `>` 变体会把第 2 段判成"付不起"并试图构造 remaining=0 的 `MovementState` ⇒ 构造期 IAE（红点落在边界用例上，理由 = "恰够也是够"）。
  3. 补后测试数 = **9**（恰好等于计划 Step 4 写的 9——计划列出的 @Test 实为 8 条，第 9 条应是这个边界）。
- **R-10-c（m2/m3/m4 形态）**：m2 = `frozen` 视图改回原 `unit` ⇒ `mobilityChangeAfterDepartureDoesNotChangeTheResult` 红（R10 第二靶）；m3 = `step.isEmpty()` 分支改 `continue` ⇒ `impassableNextStepNeedsReplan` 红；m4 = 删 `at < departedAt` 守卫 ⇒ `noRouteOrEarlierThanDepartureIsACallerBug` 第二个断言红。同根因连带可接受、全列实测红点。
- **R-10-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **9/9**；改前基线 = util 156 / map 248 / social 30 / **unit 45**（36+9）；每轮 `COMPILATION ERROR count = 0`。
- **R-10-e（形制）**：`MovementState` 构造期拒四组自相矛盾组合（`IN_TRANSIT` 必须 next present + remaining 严格 > 0；`ARRIVED`/`NEED_REPLAN` 两者皆空）；`evaluate` 纯函数（不写回）；**机动性冻结口在 `UnitMoves` 的内部副本**（不改 `MovementCost` 签名）；无路线/早于出发 ⇒ IAE。
- **R-10-f（装置）**：复用 `task-9-evidence/{run.sh,mutate.py}` 拷到 `task-10-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、实际失败类抽取；`ROUNDS_DIR`/`TARGET` 换 m1/m1重跑/m2/m3/m4。

**执行顺序**：照计划 Step 1~6（含 R-10-b 的边界用例）：写测试（8 + 边界 1）→ 编译失败 → 实现 → 9/9 → `spotless:apply` **先于**实验室 → 变异（m1 → 补边界 → m1 重跑 → m2 → m3 → m4）→ `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：计划 Step 6 的 4 文件；信息 `feat(unit): UnitMoves.evaluate 按时刻物化移动（判据二逐值表）+ 出发值冻结（M3 Task 10）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-10-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-10-evidence/`；信息 `docs(sdd): M3 Task 10 报告 + 变异实验室证据入库`

**报告 `task-10-report.md` 要点**：① 交付面；② R-10-a~f 逐条（R-10-b 的 m1 存活实测 + 边界用例 + 重跑红点）；③ 各轮变异自证头 + 实测红点全列；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不改 `MoveFixture`（边界用例自建单位，不动共享夹具）；不给 `evaluate` 加副作用/缓存；不改 `MovementCost` 签名；不推送；不抹计划原文（取代说明就地追加）。
