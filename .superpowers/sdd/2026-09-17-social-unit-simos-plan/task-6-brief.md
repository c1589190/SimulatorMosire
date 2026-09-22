# M3 Task 6 派单说明（控制器派单前扫描后）

**任务**：`UnitId` / `Route` / `Movement` / `Unit` / `UnitState` / `UnitSnapshot` + `effectivePosition` + R5/R6/R7
**BASE**：`091bd4d`（Task 5 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 1564~2238 行**（Task 6 全文，代码即权威）；M3 spec §4.1/§4.2、§六 R5/R6/R7；形制参照 `RegionId`/`SocialData`/`SocialSnapshot`。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-6-a（SpotBugs 风险，有本仓实测先例）** 计划的 `Unit` 用 helper `frozenEquipment()` **返回** `unmodifiableMap` ⇒ 赋值处是 `equipment = frozenEquipment(equipment);`。本仓已实测过这一形态：**SpotBugs 只认它看得见的 `Collections.unmodifiable*`，藏在私有方法里会报 `EI_EXPOSE_REP`**（出处：`simos-map/.../GameMap.java:62-65` 注释，M2 Task 5 实测 7 条）。**处置（两步）**：先按计划原文实现并跑门禁；若 `verify`（spotbugs）报 `EI_EXPOSE_REP`/`EI_EXPOSE_REP2`，**按 GameMap 先例改为**：helper 只做"拷贝 + 逐键值查 null"（更名如 `copyEquipment`），**赋值处**写 `equipment = Collections.unmodifiableMap(copyEquipment(equipment));`。行为一字不变，取代说明就地写。
- **R-6-b（★ m2 的精确形态与红点，计划的文字有双解）** 计划写"换成'合并所有边查环'（或直接改成 return）⇒ 前者红"——**两种形态红点不同**：
  - **合并图形态（首选，本任务必须做）**：把所有单位的**全部段边**并进一张图查环 ⇒ **唯一红点 = `legalReparentAcrossTimeIsNotACycle`**（t<10 与 t≥10 各自合法的改编被误报）；`cycleAcrossUnitsThrowsAtConstruction` 在合并图下**仍绿**（合并图确实有环、该用例断言的就是"抛"）——**如实记录这个反直觉结果**，它正是"为什么不得用合并图"的判别力证据。
  - "直接 return"（= 删校验）形态：红点 = `cycleAcrossUnitsThrowsAtConstruction`（可做可不做，不做不影响关账）。
- **R-6-c（m1/m3/m4 形态）**：m1 = 删 `requireNoEvents` 的 `events().isEmpty()` 判断 ⇒ `parentAndPositionRejectEvents` 红；m3 = `effectivePosition` 改为"先问父、后问己" ⇒ **唯一**红 `ownPositionWinsOverParent`（父链两用例仍绿）；m4 = 删 `Unit` 构造器里的自环校验 ⇒ `parentPointingToItselfThrows` 红。同根因连带可接受、全列实测红点。
- **R-6-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **12/12**（UnitIdTest 2 + UnitTest 5 + UnitStateTest 5）；四轮改前基线 = util 156 / map 248 / **unit 12**；每轮 `COMPILATION ERROR count = 0`。
- **R-6-e（装置）**：复用 `task-5-evidence/{run.sh,mutate.py}` 拷到 `task-6-evidence/`：`-pl simos-unit -am`；manifest 范围 = **`simos-util/src simos-map/src simos-unit/src`**（reactor 三模块）；surefire 明说抽取改成**能展示实际失败类**（本轮红点跨 `UnitTest`/`UnitStateTest`，建议按 `.kept` 里出现的 `FAILURE! -- in io\.mosire\.simos\.unit` 行抽取）；`ROUNDS_DIR`、`TARGET`（m1/m2/m3/m4）换本任务。
- **R-6-f（形制纪律）**：`Route`/`Movement` 住 `unit` 根包（与 `Unit` 同包，原因见计划）；`UnitState` 无环校验**按关键时点逐点查**（不得用合并图，见 R-6-b）；`units`/`equipment` 保序不可变（`LinkedHashMap` + `unmodifiableMap`，绝不用 `Map.copyOf`）；`effectivePosition` 正常路径查无此人 ⇒ 空、撞环 ⇒ `IllegalStateException`。
- **R-6-g（顺带核过）**：`UnitId` 与 `RegionId` 同形；`Route` 校验五条（waypoints ≥2 / path ≥2 / 首尾一致 / 子序列 / 相邻距离 1 / 无重复格）逐条对 spec §4.5；`Movement.speedAtDeparture|mobilityAtDeparture ≥ 1`；`Unit` 数值下限（member ≥0、speed ≥1、mobility ≥1）。

**执行顺序**：照计划 Step 1~6：三个测试类（先写）→ 编译失败 → 六个实现类 → 12/12 → `spotless:apply` **先于**实验室 → 四轮变异（按 R-6-b/c）→ `verify`（含 SpotBugs；按 R-6-a 处置）→ 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：计划 Step 6 的 9 文件；信息 `feat(unit): UnitId/Route/Movement/Unit/UnitState 编制树与位置继承（M3 Task 6）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-6-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-6-evidence/`；信息 `docs(sdd): M3 Task 6 报告 + 变异实验室证据入库`

**报告 `task-6-report.md` 要点**：① 交付面；② R-6-a~g 逐条（R-6-a 写明最终形态与门禁结果、R-6-b 写明合并图的实测红点与"cycle 仍绿"）；③ 四轮变异自证头 + 实测红点全列；④ 门禁数字（含 spotbugs 计数）；⑤ 关切/未能核实清单。

**MUST NOT**：不用合并图查环；不用 `Map.copyOf`；不在 UnitState 之外重实现无环校验；不把 `Route`/`Movement` 放进 `...unit.move`；不推送；不抹计划原文（取代说明就地追加）。
