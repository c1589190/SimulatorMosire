# M3 Task 12 派单说明（控制器派单前扫描后）

**任务**：`UnitResolver` 的 `unit:` 寻址（ID 形 + 链式定位，canonical 只回 ID）+ R12/R13 的 unit 半
**BASE**：`8252c52`（Task 11 关账）。工作树除未跟踪 `.omo/` 外干净。
**权威资料**：计划 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md` **第 3715~4109 行**（Task 12 全文）；M3 spec §4.8（U2）、§六 R12/R13；形制参照 `SocialResolver`。

**控制器扫描结论（与本节冲突处以本节为准）**：

- **R-12-a（★ 计划实现与自己的测试自相矛盾，必改）** `resolveRootLevel` 的 `if (!name.contains(".")) return empty();` **与 `chainWithMultipleHitsIsOrderedByUnitId` 冲突**：`unit:"同名连"` 是**单元素链**（无点），按该守卫直接空候选，而测试期望 **2 个候选**。spec §4.8 的链式定位不要求链"必须含点"（单元素链 = 在根层按 name 匹配）。
  **取代写法**：**删掉该守卫**——ID 未命中即走链式（`return resolveChain(state, List.of(name.split("\\.", -1)), at);`）。检查过连带：`unit:u-ghost` 在链式下根层无人叫 "u-ghost" ⇒ 仍空候选 ✅；其余用例不受影响。**类 Javadoc 的"未命中且名字含 . 才走链式定位"同步改为"未命中即走链式定位"**（并加取代说明；"ID 优先 = 身份优先于名字"的理由仍成立）。★ 若实现者发现别的语义更对（例如测试该改），**先停下来在报告里写明分歧**，不许两头硬凑。
- **R-12-b（m2 的靶子夹具，计划建议的时点写错了）** 计划 m2 建议"T0 时 A→B、T10 时 A 无父"——**查询时刻是 TS=5**，valueAt(5) 仍等于首段值 ⇒ 不分叉。**改用**：让某单位的 `parent` 段在**查询时刻之前**变化，如 `1连指挥部` 的 parent = `[T0→1营, TS→空]`（严格升序 T0<T5 合法）⇒ `valueAt(TS)=空` 而首段值 = 1营 ⇒ 分叉。**补一条用例** `chainFollowsTheParentAtTheQueryTime`：该状态下解析 `unit:"高地人旅指挥部.1营指挥部.1连指挥部"` ⇒ 期望**空候选**（1连在 TS 已脱挂、不再是 1营 的子级）；`childrenAt` 改用"首段值"的变异体下该链会被错误解析成功 ⇒ 红。
- **R-12-c（m1/m3/m4 形态；m3 换掉计划那个没靶子的）**：
  - m1 = `resolveChain` 的 canonical 改成"链原样回显" ⇒ `chainFormCanonicalisesToTheId` 红（R13 靶子）；
  - **m3 = 删 `hits.sort(...)`（多解按 UnitId 字典序保序）** ⇒ `chainWithMultipleHitsIsOrderedByUnitId` 红（夹具插入序 u-b 在前、期望 u-a 先 ⇒ 必红）。★ 计划原建议的"根候选改所有单位"**在当前用例下很可能存活**（唯一能分叉的输入"链首是非根名"未被测）——可**选做**并如实记录绿/红；主靶用 sort。
  - m4 = 删 equipment 的 `containsKey` ⇒ `equipmentResolvesOnlyWhenTheNameExists` 红。
- **R-12-d（期望数字）**：加实现前编译失败（唯一一次红=编译错）；加实现后 **9/9**（计划 8 条 + R-12-b 补条）；改前基线 = util 156 / map 248 / social 30 / **unit 67**（58+9）；每轮 `COMPILATION ERROR count = 0`。
- **R-12-e（形制红线）**：草图里的 `AT.get()` 占位**必须改为 `ctx.at()` 传参**（类内**不得有可变静态状态**——计划已用★点名）；`resolveRootLevel`/`resolveChain`/`resolveChild` 签名都带 `at`；`stateOf` 先于形状判定（认领的 `unit:` 一律先过装配故障关）；非 unit 命名空间空候选不抛；canonical 全由 AST 产出；链式地址多解按 UnitId **字典序**保序。
- **R-12-f（装置）**：复用 `task-11-evidence/{run.sh,mutate.py}` 拷到 `task-12-evidence/`：`-pl simos-unit -am`、manifest（util+map+unit）、实际失败类抽取；`ROUNDS_DIR`/`TARGET` 换 m1/m2/m3/m4（m3b 可选）。

**执行顺序**：照计划 Step 1~6：写 9 条测试（含 R-12-b 补条）→ 编译失败 → 实现（R-12-a 取代 + `at` 传参）→ 9/9 → `spotless:apply` **先于**实验室 → 四轮变异 → `verify` → 提交。

**提交**（两组；先 `git diff --cached --stat`，绝不 `git add -A`，不加 Co-Authored-By）：
- A：`git add simos-unit/src/main/java/io/mosire/simos/unit/resolve/UnitResolver.java simos-unit/src/test/java/io/mosire/simos/unit/resolve/UnitResolverTest.java`；信息 `feat(unit): UnitResolver 的 unit: 寻址（ID 形 + 链式定位，canonical 只回 ID）（M3 Task 12）`
- B：`git add -f .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-12-report.md .superpowers/sdd/2026-09-17-social-unit-simos-plan/task-12-evidence/`；信息 `docs(sdd): M3 Task 12 报告 + 变异实验室证据入库`

**报告 `task-12-report.md` 要点**：① 交付面；② R-12-a~f 逐条（R-12-a 的计划矛盾与取代、R-12-b 的夹具时点修正）；③ 各轮变异自证头 + 实测红点全列（含疑似存活项的绿/红记录）；④ 门禁数字；⑤ 关切/未能核实清单。

**MUST NOT**：不在类里用可变静态状态（`AT.get()` 一律改传参）；不重实现 canonical 加引；不把链式地址扩展到三段以上；不推送；不抹计划原文（取代说明就地追加）。
