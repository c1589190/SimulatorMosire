# M3 Task 10 报告：`MovementStatus` / `MovementState` / `UnitMoves.evaluate` + R9 + R10（判据二）

**BASE** `631db04`（Task 9 关账）。派单说明：`task-10-brief.md`（控制器扫描结论 R-10-a~f，
与本报告冲突处以派单为准）。计划依据：`docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md`
Task 10（第 2984~3295 行）；判据二权威：M3 spec §4.5 冻结夹具与逐值表。

---

## 〇、交付面（4 文件，全部新建）

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementStatus.java` | 枚举 `IN_TRANSIT / ARRIVED / NEED_REPLAN`（spec §4.5 原文三态） |
| `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementState.java` | record，**构造期拒自相矛盾组合**：`IN_TRANSIT` ⇒ `nextHex` present 且余量**严格 > 0**；`ARRIVED` / `NEED_REPLAN` ⇒ 两者皆空；四个字段全部 `requireNonNull` |
| `simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java` | `evaluate(unit, at, map, cost)`：**纯函数**（无写回、无缓存、无副作用）；预算模型 `speedAtDeparture × 1000 × (at.tick − departedAt.tick)`；**机动性冻结口在内部副本**（`frozen` 单位把 `mobilityPerMille` 换成 `movement.mobilityAtDeparture()`，`MovementCost` 签名一字未动）；无路线 / `at` 早于出发 ⇒ IAE |
| `simos-unit/src/test/java/io/mosire/simos/unit/move/UnitMovesTest.java` | **9 条测试**（计划列出的 8 条 + R-10-b 要求的第 9 条等值边界）；未动 `MoveFixture`（边界用例经 `inTransitWithSpeed` 自建单位） |

---

## 一、R-10-a~f 逐条

### R-10-a（冻结数字）✅
实现按 spec §4.5 逐值表落地，`atTwentyHours…`（remaining **5000**、current H12、next H13）、
`atTwentyTwo…`（remaining **1000**）、`atTwentyThree…`（**ARRIVED**）、`impassable…`（**NEED_REPLAN**、
current H12）四条用例全绿，与 spec §4.5 / 派单数字逐条一致。成本来源（`moveCost 25/65`、‰500 ⇒
12500/32500）由 Task 8 的 `TerrainMovementCost` 提供，本任务未触碰。

### R-10-b（m1 前提不成立 + 等值边界）✅ 实测成立
1. **m1 存活实测**：在计划的 8 条测试世界（unit 44 条用例）上跑 m1（`budget >= edgeCost` → `>`）：
   **红点数 = 0，全绿存活**（`rounds/m3t10v-1.kept`）。为什么没红：at=23 时第一步付清后余 **33500**，
   `33500 > 32500` 仍成立 ⇒ 第 2 段照样付清 ⇒ `ARRIVED` 不变；且 8 条用例无一落在"预算恰等于段成本"
   的边界上（at=22.5 非整数 tick，不可达）。控制器扫描的判断被实验证实。
2. **补边界用例**：`exactBudgetArrivalIsArrived`——自建 `speed = 5` 的在途单位
   （`Movement.speedAtDeparture` 同步 = 5，mobility 仍 ‰500），`at = T0+9` ⇒ 预算
   `5×1000×9 = 45000` **恰好** = 12500+32500 ⇒ 断言 `ARRIVED`、current H13、next/remaining 皆空。
   未动 `MoveFixture`（helper `inTransitWithSpeed` 在测试类内部拼单位）。
3. **m1 重跑**（9 测试世界）：**红点恰落在 `exactBudgetArrivalIsArrived:81`**，红理由 =
   `IllegalArgument: IN_TRANSIT 必须有下一格且未付清的余量严格 > 0: OptionalLong[0]`——`>` 把
   第 2 段判成"付不起"，试图构造 `remaining = 0` 的 `MovementState`，被构造期守卫拒绝
   （"恰够也是够"）。`MovementState` 的 `remaining > 0` 守卫由此获得**变异级自证**。
4. 测试数 = **9**，与计划 Step 4 的 `Tests run: 9` 相符（计划正文只列了 8 条 @Test，第 9 条即边界）。

### R-10-c（m2/m3/m4 形态）✅ 实测红点与预期一一对应
- **m2**（frozen 视图整块删除、成本函数改读原 `unit`）⇒ 红点**只有**
  `mobilityChangeAfterDepartureDoesNotChangeTheResult:141`（R10 第二靶：mobility 1000 ⇒ 成本
  25000/65000 ⇒ remaining 50000 ≠ 冻结视图的 5000，等值断言失败）。为什么只有它红：其余用例的
  单位 `mobilityPerMille == mobilityAtDeparture`（‰500），`frozen` 与原单位无差别，结构上不可判别。
- **m3**（`step.isEmpty()` 分支改 `continue`，跳过不可通行段）⇒ 红点**只有**
  `impassableNextStepNeedsReplan:95`，红在第一条断言"卡住前所在格"（`continue` 让单位走过 999 的
  H13 并以 `ARRIVED`、current H13 收场，而期望 `NEED_REPLAN`、current H12）。
- **m4**（删 `at < departedAt` 守卫）⇒ 红点**只有** `noRouteOrEarlierThanDepartureIsACallerBug:150`
  （**第二个**断言：`T0-1` 不再抛 IAE，预算 −2000 走 `IN_TRANSIT` 返回）。无路线那半（第一断言）
  不受影响，仍绿——红点落在被保护的那一行上。

### R-10-d（期望数字）✅ 全部对上
- 实现前唯一一次红 = **编译失败**（`COMPILATION ERROR`，`cannot find symbol` ×3 类）；
  证据 `pre-implementation-compile-failure.log`（见 §四 的诚实说明）。
- 实现后 **9/9**（`post-implementation-9of9.log`：`Tests run: 9, Failures: 0, Errors: 0`）。
- 改前基线（实验室改前日志实测）：**util 156 / map 248 / unit 45**（8 测试世界时 unit 44）。
  注：实验室跑 `-pl simos-unit -am`，reactor 只含 util+map+unit 三模块（social 不是 unit 的依赖，
  不进 reactor）——156/248/45 与 R-10-d 逐项一致；social 30 / core 15 出自门禁 `clean verify`。
- 每轮 **COMPILATION ERROR count = 0**（改前、改后各断言一次，共 10 次）。

### R-10-e（形制）✅
- `MovementState` 构造期拒四组自相矛盾组合——计划测试只断言了其中两组，本实现把
  `movementStateRejectsSelfContradictoryCombinations` 扩到**四组各断言一次**（补：
  `IN_TRANSIT` + `remaining 0`；`NEED_REPLAN` + next present）。测试方法数不变（仍 9）。
  这是执行期对计划草图的就地加严（R-10-e 列了四组，计划草图只钉两组），非分歧。
- `evaluate` 纯函数：无写回、无缓存、无副作用；无状态工具类（私有构造）。
- 机动性冻结口在 `UnitMoves` 内部副本（`frozen`），`MovementCost` 签名未动。
- 无路线 / `at` 早于出发 ⇒ IAE（m4 轮证明第二个守卫真的会响）。

### R-10-f（装置）✅
`task-9-evidence/{run.sh,mutate.py}` 拷入 `task-10-evidence/`，改动仅：`LAB=/tmp/m3t10lab`、
`ROUNDS_DIR=…/task-10-evidence/rounds`、manifest 仍 util+map+unit（137 个 .java）、
TARGET/ORDER 换 `m3t10v-1 / m3t10v-1r / m3t10v-2 / m3t10v-3 / m3t10v-4`。转写后与 Task 9 原件
diff 复核：除上述目标差异外装置零漂移（diff 曾抓出一处抄写笔误并已修正）。

---

## 二、变异实验室五轮（判读全列，无一条省略）

每轮自证头四件套（`.kept` 逐轮留痕）：① 干净世界（rsync 全新副本、md5 逐文件一致、
清单外 .java = 0）→ ② 改前全绿（含 `COMPILATION ERROR count = 0`）→ ③ 变异体与原件
**字节不同**（md5 对照）且实际改动 == 声明集合 → ④ 改后 `COMPILATION ERROR count = 0`、
simos-unit 测试类数 = 8（真的跑到了断言）。

| 轮次 | 变异（原件 md5 `abbe46e3…` → 变异体） | 实测红点 | 判读 |
|---|---|---|---|
| m3t10v-1（m1，8 测试世界） | `>=`→`>`（`370029bf…`） | **无（红点数 = 0）** | **存活**。why：33500 > 32500 仍 ARRIVED；8 条用例无一在等值边界上。R-10-b 第 1 步预期被证实 |
| m3t10v-1r（m1 重跑，9 测试世界） | 同上（`370029bf…`） | `exactBudgetArrivalIsArrived:81`（IAE：余量严格 > 0，得 `OptionalLong[0]`） | 红 = "恰够也是够"被违反；`MovementState` 的 `remaining > 0` 守卫自证会响 |
| m3t10v-2（m2） | 删 frozen 块 + 改读原 unit（`41e8533d…`） | 仅 `mobilityChangeAfterDepartureDoesNotChangeTheResult:141` | R10 第二靶（`mobilityAtDeparture` 冻结）自证；其余用例 ‰500 下 frozen≡unit，结构上不可红——红点隔离干净 |
| m3t10v-3（m3） | isEmpty 分支改 `continue`（`0aafdb70…`） | 仅 `impassableNextStepNeedsReplan:95` | NEED_REPLAN（路径保留、就地暂停）自证；跳段变 ARRIVED 被钉 |
| m3t10v-4（m4） | 删早于出发守卫（`cf1aa870…`） | 仅 `noRouteOrEarlierThanDepartureIsACallerBug:150`（第二断言） | 调用方 bug 前置校验自证；无路线那半不涉该守卫，仍绿——红点恰在保护线上 |

**存活 mutant 清单（诚实汇报）**：仅 m3t10v-1 一条，且其存活正是本任务发现并补掉的
（补 `exactBudgetArrivalIsArrived` 后同变异即红）。补测后五轮中再无任何存活。

## 三、门禁数字（`./mvnw clean verify`，rc=0，BUILD SUCCESS）

| 模块 | Tests run | SpotBugs |
|---|---|---|
| simos-util | 156 | BugInstance size is 0 |
| simos-map | 248 | BugInstance size is 0 |
| simos-social | 30 | BugInstance size is 0 |
| **simos-unit** | **45**（36+9，与 R-10-d 一致） | BugInstance size is 0 |
| simos-core | 15 | BugInstance size is 0 |

Spotless / Checkstyle 在同一 `verify` 里通过。证据：`gate-clean-verify.log`。

## 四、关切 / 诚实说明

1. **`pre-implementation-compile-failure.log` 是复现件**：首次真编译失败发生在测试文件只有 8 条时，
   当时只往会话里抓了 grep 摘要、没留全量日志。为不伪造证据，实现三文件被临时移出后用同一命令
   复现（此刻测试文件已是 9 条，失败形态相同：三主类 `cannot find symbol`），移回后 **md5 对照
   自证三文件字节未动**。失败本身真实、日志未篡改，但"8 条时刻"的原生日志不存在——特此说明。
2. **实验室分两段世界**：m3t10v-1 跑在 8 测试世界（清单 137 文件、unit 44 条），其余四轮跑在
   9 测试世界（重跑 `init` 重建清单）。这是 R-10-b"先实测存活、再补边界、再重跑"的机械化；
   派单 MUST DO 里"write all 9 tests upfront"若照办，存活将无从实测，故按 R-10-b 的时序执行
   （R-10-b 优先）。两次 init 的 manifest 文件数相同（137），差别只在 `UnitMovesTest.java` 内容。
3. **计划测试草图的唯一就地偏差**：`movementStateRejectsSelfContradictoryCombinations` 从 2 组
   断言加严到 4 组（R-10-e 明列四组）；`inTransit` 重构为委托 `inTransitWithSpeed`（边界用例需要）。
   两者都不改变测试方法数与计划断言语义。
4. **ARRIVED 的余量不体现**（spec §4.5：at=23 时剩余 1000 不出现在结果里）由
   `atTwentyThreeHoursArrives` 的 `isEmpty()` 断言钉住；m1 变体在 9 测试世界里红的是边界用例
   而非它——与 R-10-b 的预测一致（33500 > 32500 该用例确实管不着 `>=`/`>` 的分叉）。
5. `evaluate` 对 `(at.tick − departedAt.tick)` **同刻为 0** 的情形：预算 0，若首段成本 > 0 则返回
   `IN_TRANSIT`（remaining = 全程首段成本）。spec 未单列此情形，实现按公式自然落位，未额外设卡。

## 五、证据清单（`task-10-evidence/`）

- `run.sh` / `mutate.py`（装置，与 Task 9 diff 复核零漂移仅目标差异）
- `rounds/m3t10v-{1,1r,2,3,4}.kept`（五轮自证头 + 改前改后摘要 + 红点全列）
- `pre-implementation-compile-failure.log`（复现件，见 §四.1）
- `post-implementation-9of9.log`（`Tests run: 9, Failures: 0`）
- `gate-clean-verify.log`（`./mvnw clean verify` 全量）
- 实验室全量日志（各轮 before/after）在 `/tmp/m3t10lab/logs/`（跨会话不保留，.kept 已含判读所需全部内容）
