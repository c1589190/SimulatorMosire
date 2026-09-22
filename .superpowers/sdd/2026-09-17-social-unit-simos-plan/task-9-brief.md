# M3 Task 9 派单说明（控制器派单前扫描后）

**任务**：`PathFinder`（A\*）+ R8 对拍与决定论
**BASE**：`4780d47`（Task 8 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 2680~2979 行**（Task 9 全文）；M3 spec §4.4（C2 冻结）；`HexCoord` 源码（`neighbors()` / `distanceTo` 已核实存在）。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-9-a（★ 计划夹具几何不成立 + 数字错位，两处都要就地校正）**
  1. **几何**：计划的"绕行" `H00(0,0)→H11(1,1)→H21(2,1)→H20(2,0)` **第一步就不相邻**——`distanceTo((0,0),(1,1)) = 2`（cube 公式实测），不是合法路径；该图上从 H00 出发**唯一**可走的邻格是 H10（山）。计划的"绕行 3000"前提整体不成立。
     **取代写法（最小校正）**：把 `H21` 换成 **`H01 = (0,1)`（平地）**，绕行 = `H00→H01→H11→H20`（三步全部相邻：0,0→0,1、0,1→1,1、1,1→2,0 各 distanceTo=1，已核）。走廊共 5 格不变。
  2. **数字**：`MoveFixture.unit()` 的 `mobilityPerMille = 500`（Task 8 冻结夹具）⇒ `costMillis = moveCost×500`（平坦 500、山 5000）。计划的"11000/3000"是按 ‰1000 写的。**从冻结规则重算**：直线 `H00→H10→H20 = 5000+500 = 5500`；绕行 `= 3×500 = 1500`。断言与注释按 **1500 / 5500** 写，并加取代说明。**不许**改 `MoveFixture.unit()` 去迁就旧数字。
- **R-9-b（m1 高估启发：先实测、再升级到会分叉的倍数）** 计划要求"当场实测找到会分叉的夹具"。校正后的夹具上：**×2 高估预计仍得最优**（追踪：绕行 f 更小先展开、终点 1500 先弹出）——**先跑 ×2，如实记录**；再**升级到 ×1000 级别的高估**（近似贪心），预期 `aStarDetoursAroundExpensiveTerrain` 或 `matchesDijkstraOnCost` 红（会返回直线 5500）。两级倍数各自实测结果都写进报告（"x2 存活 / x1000 红"这类形态）。
- **R-9-c（m2 决定论：很可能存活，如实写理由）** 去掉平局项后，`sameInputTwiceGivesTheSamePath` **预计仍绿**——`PriorityQueue` 对固定插入序列的行为是确定的（同进程重跑同序），平局项分的是**跨实现/跨 JVM** 的序，不是同进程的序。按计划补"对称夹具（两条等成本路径）"再试；红/绿都写实，绿则写明理由（并注：全序比较器仍是 spec 冻结形态，保留）。
- **R-9-d（m3 去 closed 判重：如实写）** 一致性 + `bestG` 守卫下，去 `closed.add` 判重**可能**仍得同一条最优路径（判重主要是防重复展开的性能护栏）——红/未红都写实，不许假装。
- **R-9-e（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 `PathFinderTest` **6/6**；改前基线 = util 156 / map 248 / social 30 / **unit 30** → 加 6 后 **36**；每轮 `COMPILATION ERROR count = 0`。
- **R-9-f（形制）**：平局定序 `(f,h,q,r)` 全序**不得删**（spec §4.4 冻结）；起点/终点不在图 ⇒ 空；`start==goal` ⇒ 单元素；不可达 ⇒ 空；`NoHeuristic` 包装只改 `minStepCostMillis→0`（对拍用）；实现里的搜索结构照计划（`bestG`/`cameFrom`/`closed`/`PriorityQueue`）。
- **R-9-g（装置）**：复用 `task-8-evidence/{run.sh,mutate.py}` 拷到 `task-9-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、实际失败类抽取；`ROUNDS_DIR`/`TARGET` 换 m1(×2)/m1'(×1000)/m2/m3。

**执行顺序**：照计划 Step 1~6（夹具按 R-9-a 校正）：写测试 → 编译失败 → 实现 → 6/6 → `spotless:apply` **先于**实验室 → 四轮变异（m1×2 → m1'×1000 → m2 → m3；红/未红都写实）→ `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：`git add simos-unit/src/main/java/io/mosire/simos/unit/move/PathFinder.java simos-unit/src/test/java/io/mosire/simos/unit/move/PathFinderTest.java`；信息 `feat(unit): PathFinder（A*，启发与成本同源）+ Dijkstra 对拍与决定论守卫（M3 Task 9）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-9-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-9-evidence/`；信息 `docs(sdd): M3 Task 9 报告 + 变异实验室证据入库`

**报告 `task-9-report.md` 要点**：① 交付面；② R-9-a~g 逐条（R-9-a 的两处校正 + 取代说明；R-9-b 两级倍数实测；R-9-c/d 的红/未红与理由）；③ 各轮变异自证头 + 实测红点全列；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不改 `MoveFixture.unit()` 的 speed/mobility；不删全序平局项；不给 A\* 加 reopen；不推送；不抹计划原文（取代说明就地追加）。
