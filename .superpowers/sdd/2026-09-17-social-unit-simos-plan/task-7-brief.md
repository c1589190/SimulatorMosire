# M3 Task 7 派单说明（控制器派单前扫描后）

**任务**：`UnitChangeSet` + 反射往返框架
**BASE**：`9aaa2a4`（Task 6 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 2242~2368 行**（Task 7 全文）；**Task 4 的最终提交**（`2815463` + `229664f` 的测试文件，`simos-social/src/test/.../change/{SocialChangeSetTest,SocialRoundTripTest}.java`）作为平移母本；M3 spec §3.4/§4.7、§6.1。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-7-a（平移母本 = Task 4 的最终形态，不是计划原稿）** 计划说"照 Task 4 的同名方法逐条平移"，但 Task 4 执行期有两处裁定后的最终形态，**平移以最终形态为准**：
  1. `applyOfUnchangedKeepsTheBaseMapIdentical` 用 **`containsExactlyEntriesOf`**（不是 `isSameAs`——`UnitState` 构造期总是冻结拷贝，`isSameAs` 必红）；
  2. **追加 `unitOrderFollowsInsertionOrder`**（Task 4 的 R-4-b 序观察点之 unit 版）：`spec §4.1 冻结要点 1`（`units` 保序不可变、绝不用 `Map.copyOf`）在 Task 6/7 原用例下**零观测点**。
     ★ **夹具必须先量**：`UnitId` 是 String 系 record 键，`Map.copyOf` 的迭代序是否与插入序分叉**依 JVM 盐而定**（M1/M2 实测量过：record 键集可分叉、String 键集在某些盐下"看起来保序"）。做法：写一个 20 次独立 JVM 的探针（照 `task-4-evidence/copyOf-slot-order-20jvm.txt` 的形态）量 ≥5 键的 `Map.copyOf` 是否打乱；**量到打乱**才用这把夹具，量不到就换键集再量（键数 5→6），并把实测数字存档进 `task-7-evidence/`。
- **R-7-b（m3 = 序用例的故意违规）** 加一轮 **m3**：`UnitState` 的 `Collections.unmodifiableMap(copy)` → `Map.copyOf(copy)` ⇒ 期望**唯一红** = `unitOrderFollowsInsertionOrder`（与前两轮的红点集合不重叠）。若 m3 存活（夹具没量准），**不许**硬凑：如实记录并换夹具重跑。
- **R-7-c（m2 的形态，计划文字含糊）** 计划 m2 = 把 `changedOf` 的 `default -> throw` 改成 `default -> true`，并"当场造一个'新增组件'的形态验证它会漏"。
  - **m2-A（必做）**：实验室副本给 `UnitState` **加第二个组件**（如 `String tag`）+ **旧签名二级构造器**（`public UnitState(Map<UnitId,Unit> units) { this(units, ""); }`，保全部旧调用点可编译）⇒ 未改测试应**红**（`changeSetHasExactlyOneComponent` 的组件数/双向 subset + `everyUnitStateComponentParticipatesInTheChangeSet` 的 `mutate` 抛"未登记的组件"）——**这证明框架抓得住"新增状态组件忘了进变更集"**（这正是铁律 5 的机械落地）。
  - **m2-B（可选，尽力）**：在 A 基础上再把 `changedOf` 的 `default` 改成 `true` **并**给 `mutate` 注册 `"tag" -> base.withUnits(oneUnit())` ⇒ 应**绿**（泄漏：tag 从未被真正校验却"参与"了）。做不出来就如实写"未验证"，**不许**写成已验证。
- **R-7-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **11/11**（UnitChangeSetTest 6 + UnitRoundTripTest 4 + R-7-a 序用例 1）；改前基线 = util 156 / map 248 / social 30 / **unit 23**（12 + 11）；每轮 `COMPILATION ERROR count = 0`。
- **R-7-e（装置）**：复用 `task-6-evidence/{run.sh,mutate.py}` 拷到 `task-7-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、surefire 抽取（本轮红点跨 `UnitChangeSetTest`/`UnitRoundTripTest`/`UnitStateTest`，用"实际失败类"形态）；`ROUNDS_DIR`/`TARGET` 换 m1/m2/m3。
- **R-7-f（形制）**：变更集**不校验编制树不变量**（只做逐组件 diff + 重建；夹具因此必须是合法树）；`UnitChangeSet` 全委托 `FieldDelta`；两个 `switch` 的 `default` **抛**；豁免集空且被 `theExclusionListIsEmpty` 单独钉死；key = `UnitId.toString()` / `UnitId.parse`。

**执行顺序**：照计划 Step 1~6：写两个测试类（含 R-7-a 的两处最终形态）→ 编译失败 → 实现 → 11/11 → `spotless:apply` **先于**实验室 → 序夹具 20-JVM 测量 → 三轮变异（m1 / m2-A [+B 可选] / m3）→ `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：计划 Step 6 的 3 文件；信息 `feat(unit): UnitChangeSet + 反射往返框架（M3 Task 7）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-7-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-7-evidence/`；信息 `docs(sdd): M3 Task 7 报告 + 变异实验室证据入库`

**报告 `task-7-report.md` 要点**：① 交付面；② R-7-a~f 逐条（序夹具的 20-JVM 实测数字、m2 的形态与结果、m2-B 做没做）；③ 三轮变异自证头 + 实测红点全列；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不用 `Map.copyOf`（除 m3 变异体外）；不给 `default` 留温和兜底；不在变更集里校验编制树；不推送；不抹计划原文（取代说明就地追加）。
