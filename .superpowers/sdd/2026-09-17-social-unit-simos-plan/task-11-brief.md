# M3 Task 11 派单说明（控制器派单前扫描后）

**任务**：`UnitOperations` 编制树操作面 8 项 + R11
**BASE**：`bbd60a6`（Task 10 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 3299~3711 行**（Task 11 全文）；M3 spec §4.6（8 项操作，U5）。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-11-a（★ 计划用例前提错误，必改）** `planRouteRequiresAStartThatMatchesTheEffectivePosition` 里"无位置的单位（COMPANY 整链都没给位置）：抛"**不成立**：夹具里 COMPANY 的 parent 是 BRIGADE，而 BRIGADE 在 T0 就有位置 H11 ⇒ `effectivePosition(COMPANY, T10)` **继承出 H11**（Task 6 的 R7 语义）⇒ `planRoute(COMPANY, route=[H11,H12], T10)` 起点**对得上**、应成功，计划却断言抛 ⇒ 该断言必红。
  **取代写法（保原意：测"无位置 ⇒ 抛"）**：在用例里临时造一个**真的无位置单位**，如 `UnitState withLost = UnitOperations.create(twoUnits(), unit("u-lost", Optional.empty(), Optional.empty()))`，对它 `planRoute(withLost, new UnitId("u-lost"), route, T10)` ⇒ 整链无位置 ⇒ 抛 ✅（消息含"位置"）。原三条断言照留（BRIGADE 成功 / 起点对不上抛 / ghost 抛）。
- **R-11-b（★ m4 需要先补用例，spec §4.6 第 5 条的靶子）** 计划的 `placeAtAppendsAPositionSegmentAndClearsTheRoute` **名不副实**：它没先 `planRoute`、也没断言 `movement()` 清空。**先补一条** `placeAtClearsInTransitRoute`：`planRoute(BRIGADE, [H11,H12], T10)` → `placeAt(BRIGADE, H12, T10)` → 断言 `movement()` 为空（且位置段已追加）。然后 m4 = `placeAt` 的 `Optional.empty()` 换回 `unit.movement()` ⇒ 红点落该用例。
- **R-11-c（m1/m2/m3/m5 形态）**：m1 = 删 `create` 的同 id 抛 ⇒ `createRejectsDuplicateId` 红；m2a = 删 `disband` 的"有下属"循环 ⇒ 两个 disband 用例红（同根因）；**m2b** = 把 `valueAt(at)` 换成"取末段值"形态 ⇒ **唯一**红 `disbandIsTimeSensitive`（时点敏感判别力证据，计划点名要）；m3 = 删 `planRoute` 起点相等判断 ⇒ `planRouteRequiresAStartThatMatchesTheEffectivePosition` 红；m5 = `reparent` 不追加段（或 `append` 直接返回原 series）⇒ `reparentAppendsASegmentAndRejectsUnknownParents` 红。红/未红都写实、全列实测红点。
- **R-11-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **13/13**（计划 12 条 + R-11-b 的 1 条）；改前基线 = util 156 / map 248 / social 30 / **unit 58**（45+13）；每轮 `COMPILATION ERROR count = 0`。
- **R-11-e（形制）**：8 项全纯函数；**不做**"操作直接拼变更集"的第二条路径；`create`/`reparent` 校验父存在、**成环交给 `UnitState` 构造期**（不重复实现）；`disband` 按 `at` 时刻判下属；`placeAt`/`disband` 顺带清路线；`withUnit` 覆盖时保键位（LinkedHashMap 语义）。
- **R-11-f（装置）**：复用 `task-10-evidence/{run.sh,mutate.py}` 拷到 `task-11-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、实际失败类抽取；`ROUNDS_DIR`/`TARGET` 换 m1/m2a/m2b/m3/m4/m5。

**执行顺序**：照计划 Step 1~6：写 13 条测试（含 R-11-a 修正 + R-11-b 补条）→ 编译失败 → 实现 → 13/13 → `spotless:apply` **先于**实验室 → 六轮变异 → `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：`git add simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`；信息 `feat(unit): UnitOperations 编制树操作面 8 项（M3 Task 11）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-11-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-11-evidence/`；信息 `docs(sdd): M3 Task 11 报告 + 变异实验室证据入库`

**报告 `task-11-report.md` 要点**：① 交付面；② R-11-a~f 逐条（R-11-a 的计划缺陷与取代写法、R-11-b 的补条）；③ 各轮变异自证头 + 实测红点全列；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不实现第二写路径（操作直接产出变更集）；不在操作面重复环校验；不把速度/机动性编辑塞进操作面；不推送；不抹计划原文（取代说明就地追加）。
