# M3 Task 2 派单说明（控制器派单前扫描后）

**任务**：`TerrainType.IMPASSABLE_MOVE_COST` 具名常量 + R2 守卫
**BASE**：`96ce348`（Task 1 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 350~447 行**（Task 2 全文）；M3 spec §〇.2 C5、§六 R2。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-2-a（计划缺陷，必改）** 计划 Step 1 的 `everyOtherTerrainIsBelowTheSentinel` **按原文会红**：`TerrainCatalog.KEYS` **含 `"ocean"` 本身**（实测，KEYS 第一项就是 ocean），而海洋的 `moveCost` 恰是哨兵 `999` ⇒ `allSatisfy` 对 ocean 断言 `999 <= 999−1` **必失败**，Step 4 的"Tests run: 2, Failures: 0"按原代码**到不了**。用例名与 Javadoc 说的都是"**除海洋外**"⇒ 这是草图漏了排除项。
  **取代写法（最小修正，保原意）**：在 `assertThat(TerrainCatalog.KEYS)` 后加一行 `.filteredOn(key -> !"ocean".equals(key))`（注释写明"海洋取的就是哨兵本身，本条管其余项"），其余照计划原文。
  ⚠️ 连带修正 Step 5 的预期：修正后 **m1（常量改 998）与 m2（词表 ocean 改 998）都只红 `oceanUsesTheImpassableSentinel` 一条**，`everyOtherTerrainIsBelowTheSentinel` 两轮**确定性仍绿**（m1 下非海洋最大值 12 ≤ 997；m2 下 ocean 被过滤）。计划写的"大概率仍绿"按此坐实为"必绿"，红点唯一性因此成立。
- **R-2-b（计划条件分支不需要）** `TerrainCatalog.KEYS` **是 public**（实测）⇒ 照计划主写法用 `KEYS`，**不要**改成显式七 key 字面量。
- **R-2-c（连带面已核）** m2 改 `TerrainCatalog` 的 ocean `999→998`：已实测 `TerrainCatalogTest` **不硬编码 999**（它只断言 `ocean.moveCost >= plateau_mountains.moveCost`，998 ≥ 12 仍绿）；全仓 `git grep 999` 在 map 源码仅 `TerrainCatalog.java` 一处 ⇒ m2 的红点**唯一**落在 `oceanUsesTheImpassableSentinel`。
- **R-2-d（装置）** 直接复用 **Task 1 已本机化的装置** `.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-1-evidence/{run.sh,mutate.py}`（已含 `ROOT=/home/cna/SimulatorMosire`）：拷贝到 `task-2-evidence/`，改 `ROUNDS_DIR` 为本任务证据目录、`TARGET` 换成 m1/m2 两条（m1 改 `TerrainType.java` 常量 999→998；m2 改 `TerrainCatalog.java` ocean 的 999→998），其余（干净世界 extras=0、并集自证、`COMPILATION ERROR count = 0`、红点抽取）不动。
- **R-2-e（期望数字）** 加常量前：目标用例**编译失败**（计划 Step 2 的预期，本任务唯一一次"红=编译错"）；加常量后：`Tests run: 2, Failures: 0`；两轮变异的改前基线 = 整模块（util 156 / map **248** = 246+2）全绿。

**执行顺序**：照计划 Step 1~6。TDD 形制：先写用例 → 确认编译失败（唯一一次）→ 加常量 + 改类 Javadoc → 用例 2/2 绿 → `spotless:apply` **先于**变异实验室 → 两轮变异 → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A（代码）：`git add simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java simos-map/src/test/java/io/mosire/simos/map/terrain/ImpassableSentinelTest.java`；信息 `feat(map): TerrainType.IMPASSABLE_MOVE_COST 具名常量 + 词表一致性守卫（M3 Task 2）`
- B（证据/报告）：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-2-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-2-evidence/`；信息 `docs(sdd): M3 Task 2 报告 + 变异实验室证据入库`

**报告 `task-2-report.md` 要点**：① 交付面；② R-2-a~e 逐条落点（R-2-a 要写明计划原文错在哪、取代写法为何）；③ 两轮变异自证头 + 红点原文（每轮红点唯一）；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不改 `TerrainCatalog` 的取值（除变异自证外）——本任务**无行为变化**；不写 `passable` 布尔或第二份判据；不推送；不抹计划原文（取代说明就地追加）。
