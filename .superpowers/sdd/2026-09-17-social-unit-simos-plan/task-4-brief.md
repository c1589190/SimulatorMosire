# M3 Task 4 派单说明（控制器派单前扫描后）

**任务**：`SocialData` / `SocialSnapshot` / `SocialChangeSet` + 反射往返框架
**BASE**：`7f19de8`（Task 3 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 838~1244 行**（Task 4 全文，代码即权威）；M3 spec §3.1/§3.4/§6.1；`CLAUDE.md` 纪律节；M2 的 `RoundTripComponentsTest`（形制前身，`simos-map/src/test/.../change/RoundTripComponentsTest.java`）。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-4-a（API 已核验）**：`StateRef(BranchId, RevisionId)` / `BranchId(String)` / `RevisionId(long)` / `Snapshot{ref,timestamp,namespace}`（record 访问器自动满足前两个）/ `HexCoord.parse` / `FieldDelta.diff|rebuild`——全部与计划吻合，无需改。
- **R-4-b（★ m2 探针的决策规则，本任务最重要的扫描结论）** `Map.copyOf` 变异在**计划列出的全部断言下预计存活**：Task 4 的所有断言都是**序不敏感**的（`Map.equals`/`containsOnlyKeys`/record `equals` 都不看迭代序；`isSameAs` 只在 Unchanged 路径）。按键数加到 4~6 也**救不了**（序不敏感是断言形态问题，不是夹具规模问题——与 M2"夹具规模决定判别力"是**不同的**病灶）。
   ⇒ **执行规则**：① 先按计划跑 m2 探针（夹具现状）→ 记录实际结果；② 若存活（预期），把夹具加到 **≥5 键**再跑 → 记录；③ 若仍存活，**补一条"序观察点"用例**（计划意图的忠实补全——计划本就想要一个捕手）：
     ```java
     @Test
     void populationOrderFollowsInsertionOrder() {
       // 5 键、非平凡插入序；LinkedHashMap 保序 ⇒ 原版确定性绿
       // Map.copyOf 走散列槽位序 ⇒ 大概率红（M2 实测 4~6 键 0/30 保序）
       assertThat(data.populations().keySet()).containsExactly(H00, H10, H20, ...);
     }
     ```
     放进 `SocialChangeSetTest`（避免新文件）或另建 `SocialDataTest.java`（若建，进提交 A 清单）。**补完后重跑 m2 那一轮**，`.kept` 必须展示**红点落在新用例**上（证据对着终局字节）。
     依据：spec §3.1 冻结要点 1把"保序不可变、绝不用 `Map.copyOf`"列为**冻结**，但 Task 4 原有断言**零观测点**——这正是本项目"护栏必须自证"要抓的空档；计划原文的"把夹具加到 4~6 键"意图即是造捕手，此处按实测修正其**机制**（补断言而非只加键）。
     ⚠️ 若 m2 竟然意外红了：如实记录红在何处即可，**不必**补此用例。
- **R-4-c（m1/m3 形态与允许的连带）**：
  - m1 = `between` 的两个实参都传 `base.populations()`（没有真的比较两侧）⇒ 预期 `aSingleChangedEntryIsNotAnEmptyChangeSet` + `addAndRemoveTogetherIsAPatch` + 往返用例同根因红；
  - m3 = `apply` 里把 `cs.populations()` 换成 `new FieldDelta.Unchanged<>()`（apply 不吃 delta）⇒ 预期 `aSingleChangedEntryIsNotAnEmptyChangeSet` / `addAndRemoveTogetherIsAPatch` / removal 用例同根因红。**同根因连带可接受，但必须全列实测红点**（先跑后写）。
- **R-4-d（装置）** 复用 `task-3-evidence/{run.sh,mutate.py}` 拷到 `task-4-evidence/`：`ROUNDS_DIR` 换本任务、`TARGET` 换 m1/m2/m3、surefire 明说目标改 `SocialChangeSetTest`（或含新用例的类）；manifest 范围与 `-pl simos-social -am` 不变。
- **R-4-e（期望数字）**：加实现前**编译失败**（三类型不存在，唯一一次红=编译错）；加实现后 `SocialChangeSetTest` **6** + `SocialRoundTripTest` **4** = **10/10**（若补序用例 = 11）；三轮改前基线 = util 156 / map 248 / social **10**；每轮 `COMPILATION ERROR count = 0`。
- **R-4-f（往返框架照 M2 形制）**：豁免集 = `Set.of()` 且被 `theExclusionListIsEmpty` 单独钉死；两个 `switch` 的 `default` **抛**（不许温和兜底）；`changeSetHasExactlyOneComponent` 双向 subset。

**执行顺序**：照计划 Step 1~6：写用例 → 编译失败 → 实现三类型 → 全绿 → `spotless:apply` **先于**实验室 → 三轮变异（按 R-4-b/c，含补用例后的 m2 重跑）→ 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：计划 Step 6 的 5 文件（+ 若补建 `SocialDataTest.java` 则加上）；信息 `feat(social): SocialData/SocialSnapshot/SocialChangeSet + 反射往返框架（M3 Task 4）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-4-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-4-evidence/`；信息 `docs(sdd): M3 Task 4 报告 + 变异实验室证据入库`

**报告 `task-4-report.md` 要点**：① 交付面；② R-4-a~f 逐条；③ 三轮变异自证头 + 实测红点全列 + **m2 探针的完整轨迹**（现状存活？加键后？补断言后红点？附实测数字）；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不在 `SocialData` 里加第二条写路径/缓存；不用 `Map.copyOf`；不改 FieldDelta/MapChangeSet；不推送；不抹计划原文（取代说明就地追加）。
