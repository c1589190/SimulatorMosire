# M3 Task 13 派单说明（控制器派单前扫描后 — M3 关账）

**任务**：M3 关账（全量门禁 + 四条判据逐条 + CLAUDE.md + 计划取代说明回填 + 报告）
**BASE**：`1a75a72`（Task 12 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 4114~4173 行**（Task 13 全文）；spec §1.1（四条判据）；本目录 `progress.md`（12 个任务的关账记录）；各 `task-N-brief.md` / `task-N-report.md`。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-13-a（门禁顺序：必须先于一切代码/测试改动之后跑）** 计划 Step 1 把 `clean verify` 放最前，但 Step 3 可能**新增用例**（见 R-13-b）⇒ **门禁必须跑在"最终字节"上**：先做 Step 2~5 的核查与可能的补测，**最后**跑 `./mvnw clean verify`（或先跑一遍摸底、补测后**再跑一遍并以第二遍为准**）。报告写明门禁对应的是哪个字节（最终提交字节）。日志**存 `task-13-evidence/`**（不能只留在 /tmp——这是 M2 Task 5 的口径）。
- **R-13-b（★ 判据二的"27.5 / −5"中间值：需要补一条直接断言）** 现套件的可观察量（`currentHex=H12` + `remaining=5000`）**联合**可推出 27500（=32500−5000），但**没有对 27500 / −5000 的直接断言**。按计划 Step 3 的★：**补一条用例**（放 `UnitMovesTest`）`criterionTwoArithmeticMatchesTheSpecTable`：
  - 用**生产代码**取出 `budget = speed×1000×(at−departedAt)` 与两步 `costMillis`（12500 / 32500），断言 `budget − firstCost == 27500L`（"40−12.5=27.5"）与 `budget − firstCost − secondCost == −5000L`（"27.5−32.5=−5"），并把 `remaining == secondCost − (budget − firstCost) == 5000` 一并钉住；
  - 期望值写**字面量**（27500 / −5000 / 5000），不许写成恒等式（`a−b==a−b` 无判别力）。
  - 补后 `UnitMovesTest` = **10**、unit 模块 = **68**；配**一轮变异** `m13v-1`：`TerrainMovementCost` 的 `scale(type.moveCost() * 1000L, …)` → `scale(type.moveCost(), …)`（丢 ×1000）⇒ 新用例必红（连同 `TerrainMovementCostTest.stepCosts…` 同根因连带，如实列）。装置复用 `task-12-evidence/{run.sh,mutate.py}`（`-pl simos-unit -am`、manifest util+map+unit）。
  - 若实现者判断"联合可推"已足够并**不补**，则报告必须把 27500 / −5000 **如实标成"未直接核实"**（计划 Step 3 给了这个口径；二选一，不许含糊）。
- **R-13-c（判据四汇总：以证据目录为准）** 逐任务列**变异轮数**与**红点是否落声明靶子**；**轮数以证据目录的 `rounds/*.kept` 文件数为准**（与台账文字若不一致**并列如实**）。已知的**非红/存活项**必须如实列（这些是实测结论不是失败）：Task 4 的 m2 探针（先存活、补序断言后红）、Task 8 的 m1（数学等价，无分叉输入）、Task 9 的 m1×2 与 m3（性能护栏）、Task 12 的 m3b（根筛 vs 全量筛，无被测输入）。**不许**把"没验"写成"验过"。
- **R-13-d（CLAUDE.md 更新范围）** 只动两处：①「当前状态」表加 **M3 行**（照 M2 行的格式：`✅ 已完成（13/13，2026-09-17）` + spec/计划/台账路径 + 四条判据核对结论一行 + 挂起项摘要）；②「推送状态」行更新为本分支 `feat/m3-social-unit-simos`。**不重构其他段落**。
- **R-13-e（★ 计划文件必须回填取代说明）** M3 执行期有一批**计划缺陷/取值校正**（Task 4 的 `isSameAs`、Task 6 的 `legalReparent` 夹具、Task 8 的 m1 前提与 minStep 边界、Task 9 的绕行夹具与数字、Task 10 的 m1 前提、Task 11 的两处夹具断言、Task 12 的 `.` 守卫）——它们目前只在 brief/report/台账里。按计划自己的要求（"保留草图原貌 + 加取代说明"），**在计划文件末尾（`计划自审` 之后）追加一节 `## 执行期取代说明汇总（Task 1~12 关账时回填）`**：逐条 = 任务号 + 计划原文所在（Step/行号或代码锚点）+ 取代后写法一句 + 详情出处（brief/report 文件名）。**不重写/不删除任何原有草图**。条目从本目录各 `task-N-brief.md` 的"扫描结论"与 `progress.md` 的关账节里摘（两边都读过再摘，不许凭记忆）。
- **R-13-f（报告必含"我未能核实的"段）** 至少覆盖：`GameMap` 无 id ⇒ `mapId` 只回显不校验（挂起项 1）；属性段地址不服务（挂起项 2）；materialize 写回不在 M3（挂起项 3）；人口 cache 未做（挂起项 5）；A\* 规模未测（挂起项 9）；链式定位与含点名字/ID 的取舍（Task 12 取代）；**加上执行期新发现**：m3b 存活面、A\* 决定论的跨 JVM 面（同进程可测、跨实现不可测）、`Route`/`Movement`/`UnitSnapshot` 构造守卫的测试覆盖弱（Task 6 关切）等。**凡"推导出来但我没跑"的一律进此段**。
- **R-13-g（提交与推送）**：报告写明四条判据逐条结论；`progress.md` 追加 `## Task 13 关账 —— ★ M3 关账完成` 节（格式照 M2 Task 15 节）；然后：
  ```
  git add CLAUDE.md docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md
  git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/progress.md \
             .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-13-report.md \
             .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-13-evidence/
  ```
  先 `git diff --cached --stat` 扫；信息 `docs(sdd): M3（SocialSimos + UnitSimos）关账：判据逐条核过、台账与报告入库（M3 Task 13）`；**推送** `git push origin feat/m3-social-unit-simos`（本分支已跟踪远程；该推就推）。不加 Co-Authored-By。

**执行顺序**：Step 2（判据一）→ Step 3（判据二，含 R-13-b 补测 + m13v-1 变异）→ Step 5（判据四汇总）→ R-13-e（计划回填）→ R-13-d（CLAUDE.md）→ Step 1/4（最终 `clean verify` 在最终字节上）→ 报告 + 台账 + 提交 + 推送。

**报告 `task-13-report.md` 要点**：① 全量门禁逐项（rc、六模块、五个 `Tests run`、`BugInstance size is 0` 次数、ERROR/WARNING 行数与出处）；② 判据一逐条（用例名+期望值+跑出来的痕迹）；③ 判据二逐行（含 R-13-b 的处置）；④ 判据三（rc=0 + 日志路径）；⑤ 判据四逐任务表（轮数=证据目录计数、红点/存活如实）；⑥ CLAUDE.md 与计划回填的落点；⑦ **"我未能核实的"** 段；⑧ 关切清单。

**MUST NOT**：不改任何 src/main 或既有测试的断言（R-13-b 只**新增**一条用例）；不重写计划原文（只追加汇总节）；不把存活变异写成红；不 `git add -A`；不把 `__pycache__` 等噪音入库。
