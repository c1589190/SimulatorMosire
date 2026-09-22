# M3 Task 3 派单说明（控制器派单前扫描后）

**任务**：`PopulationSeries` 分段积分时态序列 + 判据一手算表 + R3/R4
**BASE**：`c78e8e6`（Task 2 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 450~835 行**（Task 3 全文，代码即权威）；M3 spec §3.2/§3.3/§3.6（**冻结数字 18036 / 6300 / 15000 / 10000**）；`CLAUDE.md` 纪律节。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-3-a（API 已逐条核验，计划代码可直接用）**：`Segment(from,value)` / `Event(at,value,mode)` / `EventMode.{ADD,SET}` / `SegmentedSeries(segments,events,addition)` 三参构造（非空段、段严格升序、事件非递减、**仅含 ADD 时才要求 addition 非 null**⇒ `null` 合法）/ `TemporalSeries` 三方法（`valueAt`/`segments`/`events`，record 的 `events()` 访问器自动满足）/ `SimosTimestamp.of(long)` + `tick()` + `compareTo`——全部与计划草图吻合，无需改。
- **R-3-b（m1 的精确形态 + 允许同根因连带红）** 计划 Step 5 的 m1 建议"任选一种能改变结果的"。**指定主形态：删掉 `cuts.add(t)` 那一行**（查询点不进切分点）。理由：种子表的数字在"每区间一次舍入"下**全是整数**（4000/3500/1336 精确），故任何"舍入挪位/改成复利"类变异**产不出差异**（存活性 mutant，白做）；能改结果的只有**切分结构或基数**。预期：`seedExampleMatchesTheHandComputedTable` 红且消息含 `expected: 18036L`；**同根因连带**红（boundary/multi 两条同因）为**可接受**，但**必须如实列全实际红点**（先跑出来再写，不许按推理写）。
- **R-3-c（m2 优选唯一红形态）** 主形态：**反转 `applyEventsAt` 的施加序**（同刻按插入序的破坏）⇒ 期望**唯一**红 `multipleEventsAtTheSameTickFollowInsertionOrder`（种子的单事件不受影响）。备选（计划原文）：SET/ADD 分支对调——会**连带 seed 红**（ADD 事件被当 SET 处理），若选它如实记录连带。选其一并在报告写明理由。
- **R-3-d（装置改造，四处必改）** 复用 Task 1/2 已本机化的装置（`task-2-evidence/{run.sh,mutate.py}` 拷到 `task-3-evidence/`）：
  1. `run_tests` 的 `-pl simos-map -am` → **`-pl simos-social -am`**；
  2. manifest 范围 → **`simos-util/src simos-map/src simos-social/src`**（`repo_java_files` 与 `init` 两处同步）；
  3. `digest` 的 `^\[INFO\] Running io\.mosire\.simos\.map` 过滤 → 通用前缀 **`io\.mosire\.simos\.`**（本轮 util 与 social 的用例都会出现在日志）；
  4. "surefire 明说"抽取目标 → `PopulationSeriesTest`。
  其余（干净世界 extras=0、并集自证、`COMPILATION ERROR count = 0`、红点抽取、`.kept` 格式）**不动**。
- **R-3-e（期望数字）**：加实现前目标用例**编译失败**（本任务唯一一次红=编译错）；加实现后 `PopulationSeriesTest` **10/10**（数过：种子 1 + 边界 1 + 同刻多事件 1 + anchor 前 1 + segments 1 + R4 四条 + with* 一条 = 10）；三轮改前基线 = util **156** / social **10** 全绿；每轮变异后 `COMPILATION ERROR count = 0`。
- **R-3-f** `package-info.java` 的改写是"顺手但不强求"——做与不做都行，做了就进提交 A 的清单。
- **R-3-g（恒真风险自检，写进报告）**：本轮用例的判别力藏在"数字对不上就红"上——**不许**在实现里对任何输入提前 return 常量；`withGrowthSegmentAndWithEventReturnNewValues` 必须真的断言**旧值不变**（record 值语义）。若实现后 10/10 全绿，问一句"每个用例失败时红因是哪一行"。

**执行顺序**：照计划 Step 1~6：写用例 → 确认编译失败 → 实现 → 10/10 → `spotless:apply` **先于**实验室 → 三轮变异（m1/m2/m3 照 R-3-b/c + 计划 m3=删 growth.events 校验）→ 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：`git add simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java simos-social/src/test/java/io/mosire/simos/social/population/PopulationSeriesTest.java`（+ 若改了 `package-info.java` 也加上）；信息 `feat(social): PopulationSeries 分段积分时态序列 + 判据一手算表与构造校验（M3 Task 3）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-3-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-3-evidence/`；信息 `docs(sdd): M3 Task 3 报告 + 变异实验室证据入库`

**报告 `task-3-report.md` 要点**：① 交付面；② 判据一逐值（18036 / 6300 / 15000 / 10000）与 R4 逐条落点；③ 三轮变异自证头 + **实际红点全列**（含连带，若有）；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不改 spec 冻结数字去迁就实现（对不上=实现/计划有错，记录取代说明）；不缓存、不物化网格；不在实现里写任何"按查询点猜结果"的分支；不推送；不抹计划原文（取代说明就地追加）。
