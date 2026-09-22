# M3 Task 1 派单说明（控制器派单前扫描后）

**任务**：`FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制 + M2 侧委托 + R1 守卫
**BASE**：`1b0397e`（计划提交）。工作树干净。
**权威资料（按序读）**：
1. 计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 92~348 行**（Task 1 全文 = Step 1~9，代码草图是权威；执行期发现草图问题**就地校正并记录取代说明**，不抹原文）
2. M3 spec `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md` 的 §〇.2 C7、§六 R1
3. `CLAUDE.md` 纪律节（变异自证五形态、提交纪律、中文注释、`spotless:apply`）

**控制器扫描结论（补计划的裁决；与本节冲突处**以本节为准**）**：

- **R-1-a（计划缺陷，必改）** 计划 Step 8 第 3 条写"确保类名与文件名一致（LegacyFieldDelta）"——**错的**。R1 守卫扫描的是**逐行子串** `interface FieldDelta`；`interface LegacyFieldDelta` **不含**该子串 ⇒ 改名副本放进去守卫**不会红**，变异等于白做。已实测（/tmp/r1probe，javac rc=0）：`LegacyFieldDelta.java` 里放**包级私有**（不写 `public`）的 `interface FieldDelta<T> { }` **可编译**。
  ⇒ **变异体形态（照此）**：新增 `simos-map/src/main/java/io/mosire/simos/map/change/LegacyFieldDelta.java`，内容 = package 行 + 包级私有 `interface FieldDelta<T> { }`（空体即可；可加一行中文注释）。**文件名用 LegacyFieldDelta.java，声明名必须是 FieldDelta**。
  ⇒ 同包其余文件（MapChangeSet/MapChangeSetTest/RoundTripComponentsTest）届时都带 `import io.mosire.simos.util.state.FieldDelta;`——单类型导入按 JLS 6.4.1 **遮蔽**同包同名类型 ⇒ 不引发编译错误（用 `COMPILATION ERROR count = 0` 自证；若真有编译错，如实记录并分析，不许硬凑）。
- **R-1-b（计划 Step 5 清单不完整）** 除计划列出的文件外，**`MapChangeSetTest.java`（49 处引用）/ `RoundTripComponentsTest.java`（1 处）** 在包内**无 import** 引用 `FieldDelta`，移动后必须**新增** import。实际 import 面（已实测）：
  - 需**替换**（现为 `import io.mosire.simos.map.change.FieldDelta;`）5 个：`RegionRandomizer.java` / `RiverBuilder.java` / `RegressionGuardsTest.java` / `RegionRandomizerTest.java` / `RiverBuilderTest.java`
  - 需**新增** 2 个：`MapChangeSetTest.java` / `RoundTripComponentsTest.java`
  - 终局判据：`git grep -n "map.change.FieldDelta" -- 'simos-map/src'` **为空**
  - **不动**：`CityId` / `PathwayId` / `RegionId` / `EdgeRefTest` / `PathwayGroupTest` 里 `{@code FieldDelta}` 的 Javadoc 文本
- **R-1-c（checkstyle 在 validate，会挂）** `MapChangeSet` 删掉两个私有方法后，`LinkedHashMap` / `LinkedHashSet` / `Map` / `Set` 四行 import 变**未使用**；本仓 checkstyle 开了 `UnusedImports` 且绑在 **validate**（`./mvnw ... test` 就会跑到）⇒ 必须清掉，否则不是断言红而是构建挂。`Function` / `Objects` 保留。
- **R-1-d** `RegressionGuardsTest` 现**没有** `entry` 静态导入 ⇒ 按计划 Step 7 补 `import static org.assertj.core.api.Assertions.entry;`。
- **R-1-e（装置复用 + 本机适配）** 复用 M2 Task 14 的装置：`.superpowers/sdd/2026-09-16-map-simos-plan/task-14-evidence/{run.sh,mutate.py}`。**必改三处**：`ROOT=/home/cna/SimulatorMosire`（原件写 /root，本机不存在）；`ROUNDS_DIR` 指向本任务证据目录；`TARGET`/变异体换成本轮 1 条（照 R-1-a）。清单范围（`simos-map/src` + `simos-util/src`）不变。**保留 Task 14 的两条升级**：干净世界断言"清单之外的 .java = 0"、并集自证（修改∪新增 == 声明）。日志/`.kept` 落 `.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-1-evidence/`。
- **R-1-f（期望数字）** 改前基线 = **util 156 / map 246**（245+R1）全绿；变异后 = 仅 `R1_thereIsExactlyOneFieldDelta` 红（消息带出多出的键 `simos-map/.../LegacyFieldDelta.java`）、`COMPILATION ERROR count = 0`；终局 `./mvnw clean verify` = util 156 / map **246** / core 15、`BugInstance size is 0` ×5、`BUILD SUCCESS`。

**执行顺序**：照计划 Step 1~9（可微调，结果为准）。Step 3 的 diff/rebuild 代码**逐字照计划搬**（接口里 `static` 即隐式 public）。Step 6 回归网**全绿**是加 R1 之前的硬门——红了先怀疑重构，不许改 M2 测试迁就。

**提交**（两组；先 `git diff --cached --stat` 扫，绝不 `git add -A`；不加 Co-Authored-By）：
- A（代码）：计划 Step 9 的 add 清单 **+ MapChangeSetTest / RoundTripComponentsTest 两个漏列文件**；
  信息 `refactor(util): FieldDelta 上移 util.state 并把 diff/rebuild 提为共用静态机制（M3 Task 1）`
- B（证据/报告）：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-1-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-1-evidence/`；
  信息 `docs(sdd): M3 Task 1 报告 + 变异实验室证据入库`

**报告 `task-1-report.md` 要点**（形制照 M2 `task-13-report.md`）：① 交付面（文件+行数）；② R-1-a~f 逐条落点；③ 变异轮自证头（干净世界 md5+文件数、改前绿、变异体与原字节不同、并集、`COMPILATION ERROR`=0、真跑过的测试类、红点原文）；④ 门禁要点（rc、Tests run、BugInstance）；⑤ **关切 / 未能核实清单**（没跑过的别写）。

**MUST NOT**：不改 Javadoc 文本引用；不做计划外"顺手优化"；不让变异体变成编译/checkstyle 错误；不推送；不删改计划原文（取代说明就地追加）。
