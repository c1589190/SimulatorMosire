# M3 Task 8 派单说明（控制器派单前扫描后）

**任务**：`MovementCost` + `TerrainMovementCost` + 判据二夹具
**BASE**：`e3a2ca2`（Task 7 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 2372~2676 行**（Task 8 全文）；M3 spec §4.3（C5/C6/U4 冻结）；`TerrainType` 源码（形参序）；`GameMap` 构造形制。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-8-a（★ m1 的前提有代数问题，先测量再定）** 计划的 `scale(v,‰)=floorDiv(v×‰+500,1000)` 在 v1 里**恒为精确乘法**：本设计里 `v` 永远是 `moveCost×1000`（整数），而 `moveCost×1000×‰+500` 的 `+500` **永远不跨界**（因为第一项已被 1000 整除）⇒ `scale = moveCost×‰` 精确、**没有任何舍入发生**。因此：
  - `Math.round(v*‰/1000.0)` 与 floorDiv 形态**数学等价**（正数、范围内无分叉输入）——m1 按计划形态预计**存活**。
  - **执行规则**：① 先按 m1 原形态跑（含计划建议的"换输入"尝试：**实测扫描**一组 (moveCost,‰)（至少覆盖两个冻结夹具 + 计划举的 7/333、1/501 等）记录两侧结果；② 若确无分叉（预期），**如实记录"等价变体、无判别输入"**（这是关于公式的实测结论，不是失败）；③ 随后**必须再跑 m1'（替代真变异）**：`costOf` 里 `scale(type.moveCost() * 1000L, ...)` → `scale(type.moveCost(), ...)`（去掉 ×1000，单位错误）**或** `unit.mobilityPerMille()` → `1000`（跳过机动缩放，二选一）⇒ 期望 `stepCostsMatchTheFrozenFixture` 红。**m1' 的 .kept 必须有。**
- **R-8-b（m2/m3 形态）**：m2 = `costOf` 的 `>=` 改 `>`（或删整条判断）⇒ `impassableTerrainHasNoCost` 红；m3 = 删 `minStepCostMillis` 的"跳过不可通行"⇒ `minStepCostIsTheCheapestTraversableTerrainScaled` 红（999 会赢，499500≠12500）。同根因连带可接受、全列实测红点。
- **R-8-c（Step 3 的实现纪律）**：草图对每格 `terrainOf(cell)` 调两次——**实现时合并为一次查表**（计划 §Step 3 的★取代说明已写）；`costOf` 的不可通行判据**引用 `TerrainType.IMPASSABLE_MOVE_COST`**（不得复制字面量 999）。
- **R-8-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 `TerrainMovementCostTest` **6/6**（12500/32500 两值 + 不可通行 + 图外 + 非相邻/自环 IAE + 缺词表 key IAE + minStep 12500）；改前基线 = util 156 / map 248 / social 30 / **unit 29**（23 + 6）；每轮 `COMPILATION ERROR count = 0`。
- **R-8-e（装置）**：复用 `task-7-evidence/{run.sh,mutate.py}` 拷到 `task-8-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、surefire 实际失败类抽取；`ROUNDS_DIR`/`TARGET` 换 m1/m1'/m2/m3。
- **R-8-f（形制）**：`MovementCost` 接口 + `TerrainMovementCost` 单例（`public static final INSTANCE`、私构造）；`MoveFixture` 是**测试夹具**（`final class`、包级私有、私构造）；夹具地形**不进 `TerrainCatalog`**（自建 `TerrainType`——判据与词表解耦）；非相邻/自环 ⇒ 抛（调用方 bug），图外 `to` ⇒ 空。

**执行顺序**：照计划 Step 1~6：夹具 + 失败测试 → 编译失败 → 实现（按 R-8-c 合并查表）→ 6/6 → `spotless:apply` **先于**实验室 → 四轮变异（m1 测量 → m1' 真变异 → m2 → m3）→ `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：计划 Step 6 的 4 文件；信息 `feat(unit): MovementCost/TerrainMovementCost 毫 MP 定点成本 + 判据二夹具（M3 Task 8）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-8-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-8-evidence/`；信息 `docs(sdd): M3 Task 8 报告 + 变异实验室证据入库`

**报告 `task-8-report.md` 要点**：① 交付面；② R-8-a~f 逐条（R-8-a 的等价性实测表 + m1' 的红点）；③ 各轮变异自证头 + 实测红点全列；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不在 unit 侧复制第二份 999 哨兵；不给 `MoveFixture` 进 `src/main`；不改 `TerrainCatalog`；不推送；不抹计划原文（取代说明就地追加）。
