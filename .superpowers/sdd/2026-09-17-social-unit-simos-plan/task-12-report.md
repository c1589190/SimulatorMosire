# M3 Task 12 报告：`UnitResolver` + R12/R13 的 unit 半

**执行者**：实现者（Sisyphus-Junior）　**日期**：2026-09-18　**BASE**：`8252c52`
**权威资料**：派单说明 `task-12-brief.md`（取代计划处已逐条落实）→ 计划 L3715~4109 → spec §4.8 / §六 R12、R13 → 形制参照 `SocialResolver`。

---

## 〇 结论

**完成。** 交付 `UnitResolver implements Resolver`（`unit:` 命名空间：ID 形、链式定位、equipment 子实体；canonical 一律回 ID）+ `UnitResolverTest` 9 条（计划 8 条 + R-12-b 补条）。实现前编译失败（唯一一次允许的红）；实现后 9/9；变异实验室 5 轮（4 必做 + 1 选做），4 个主靶全部红在被保护行为上，选做的 m3b 如 R-12-c 预言**存活**（绿记录已存档）；`./mvnw verify` 全绿。

---

## 一 交付面

| 文件 | 内容 |
|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/resolve/UnitResolver.java` | `unit:` 解析器（主 + 3 个私有定位方法 + `stateOf` 装配故障关） |
| `simos-unit/src/test/java/io/mosire/simos/unit/resolve/UnitResolverTest.java` | 9 条用例（R12 空候选 vs 抛逐条 + R13 canonical 只回 ID） |
| `.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-12-evidence/` | 实验室装置（run.sh / mutate.py）+ 5 轮 .kept + 门禁日志 |

行为面（spec §4.8 逐行落地）：

- **ID 形**（两段、第 2 段缺 kind 的 Entity）：`unit:<unitId>` ⇒ `SubjectId("unit", id)` / `"Unit"` / canonical `unit:<id>`；查无此人走链式（R-12-a 取代，见 §二）。
- **链式**：ID 未命中即按 `.` 切分，从查询时刻 `at` 的**无父者**逐级按 `name` 匹配；末级多解按 `UnitId` 字典序保序；任一级无命中 ⇒ 空候选；每个命中的 canonical 由 AST 构造回 `unit:<id>`（R13/U2）。
- **equipment**：仅 `unit:<id>:equipment.<名>`（ID 形），装备表 `containsKey` 不过 ⇒ 空候选；`SubjectId("unit.equipment", "<id>/<名>")` / `"Equipment"`。
- **空候选**：非 `unit` 命名空间、属性段（`:member` 等）、4 段以上、链式后接子实体、其余 kind——一律空候选不抛。
- **抛只有装配故障**：无 `unit` 切片 / 切片类型不对（`stateOf` **先于**形状判定）；`UnitId.parse` 自己的 IAE 不包不吞。
- **红线**：无任何可变静态状态（计划草图的 `AT.get()` 占位已按 R-12-e 全部改为 `at` 传参，`resolveRootLevel`/`resolveChain`/`childrenAt` 签名都带 `at`）；canonical 全由 `Address` AST 构造后 `canonical()` 产出，§3.4 加引规则零重实现。

## 二 R-12-a~f 逐条

- **R-12-a（计划实现与自己的测试自相矛盾）**：**采纳派单的取代写法——删掉 `if (!name.contains(".")) return empty();` 守卫**，ID 未命中一律 `resolveChain(state, List.of(name.split("\\.", -1)), at)`。理由与派单一致：`chainWithMultipleHitsIsOrderedByUnitId` 的 `unit:"同名连"` 是单元素链（无点），原守卫直接空候选而测试期望 2 个候选；spec §4.8 的链式定位不要求含点。**我核对过派单给出的语义后认为它就是正确的**（单元素链 = 根层按 name 匹配，是链式定位的自然退化；"ID 优先 = 身份优先于名字"仍成立），无分歧，未走"停下来写分歧"分支。连带核查：`unit:u-ghost` 在链式下根层无人叫 `u-ghost` ⇒ 仍空候选（m1 轮改前的全绿与该用例的实现后绿都实证了这一点）；其余 8 条用例不受影响。类 Javadoc 的"未命中且名字含 `.` 才走链式"已改为"未命中即走链式定位"并附取代说明（R-12-a 名号在案）。
- **R-12-b（m2 靶子夹具的时点修正）**：计划建议"T0→A、T10 无父"在 TS=5 查询下不分叉（单段序列 valueAt(5) = 首段值）。**照派单改为**：`1连指挥部` 的 parent = `[T0→1营, TS→空]`（严格升序合法），补条 `chainFollowsTheParentAtTheQueryTime`：该状态下解析三级链 ⇒ 期望空候选（1连在 TS 已脱挂）。分叉性由 m2 轮实证：`childrenAt` 改恒取首段值的变异体下该链被错误解析成功 ⇒ 红（唯一红点，见 §三）。测试里附了取代说明的 Javadoc。
- **R-12-c（变异形制）**：m1 = canonical 链回显 ⇒ `chainFormCanonicalisesToTheId` 红 ✓（连带 `chainWithMultipleHitsIsOrderedByUnitId` 红，同根因：`get(0)` 断言吃的也是 canonical——派单"预计连带红"命中）；m2 = 见上；**m3 主靶 = 删 `hits.sort(...)`** ⇒ `chainWithMultipleHitsIsOrderedByUnitId` 红 ✓（插入序 u-b 在前、期望 u-a 先；同轮还删了 `Comparator` import 防 checkstyle UnusedImports 挂编译）；m4 = 删 equipment `containsKey` ⇒ `equipmentResolvesOnlyWhenTheNameExists` 红 ✓。
- **R-12-d（期望数字）**：实现前编译失败 ✓（`pre-implementation-compile-failure.log`，`cannot find symbol: UnitResolver`；中途还有一次测试夹具自身的重载歧义 `unit(null)` 三参调用两义——改 `unitWithParentSegments` 命名后消除，这属于**测试代码的编译错**，仍落在"实现前编译失败"阶段内）；实现后 **9/9** ✓；基线 = util 156 / map 248 / social 30 / **unit 67**（58+9）✓；每轮 `COMPILATION ERROR count = 0` ✓（改前改后都是 0）。
- **R-12-e（形制红线）**：`AT.get()` 占位未进实现——`ctx.at()` 在 `resolve()` 取一次后逐层传参；类内唯一的 `static final` 是 `NAMESPACE` 常量（不可变）；`stateOf` 先于形状判定（装配故障关）；非 unit 命名空间空候选；canonical 只经 AST；多解 UnitId 字典序。
- **R-12-f（装置）**：`run.sh`/`mutate.py` 从 `task-11-evidence/` 拷贝，改动仅：`LAB=/tmp/m3t12lab`、`ROUNDS_DIR=task-12-evidence/rounds`、TARGET 换 m1/m2/m3/m4/m3b、头注与目标描述。manifest 范围不变（util+map+unit，141 个 .java，含本任务两个新文件 ⇒ 单一世界五轮共用）。`COMPILATION ERROR=0` 断言、实际失败类抽取、干净世界三连（md5 逐文件 + 文件数 + 无清单外 .java）全部保留。

## 三 变异实验室（5 轮，每轮自证头 + 实测红点全列）

每轮通用自证（5 轮全部通过，.kept 原文在案）：干净世界 OK（副本与 md5 清单逐文件一致、141 个 .java、清单外 .java=0）→ **改前全绿**（156/248/67、BUILD SUCCESS、`COMPILATION ERROR count = 0`）→ 变异体与原件**字节不同**（md5 原件 `8d512d12…` ≠ 变异体，各轮值见 .kept）→ 实际（修改∪新增）== 声明集合 → 改后 `COMPILATION ERROR count = 0` 且 simos-unit 10 个测试类真的跑过。

| 轮 | 变异（都在 UnitResolver.java 上，工作树零触碰） | 实测红点 | 判读 |
|---|---|---|---|
| **m1** | resolveChain 的 canonical 改成链原样回显（`Entity.of(String.join(".", names))` 经 AST 加引） | **2 条红**：`chainFormCanonicalisesToTheId:94`（期望 `unit:u-co1`）＋ `chainWithMultipleHitsIsOrderedByUnitId:108`（期望 `unit:u-a`）；其余 65 条绿 | R13 的靶子红了，**红的理由正确**：canonical 不再回 ID；连带红与派单预判一致（同吃 canonical 断言），不是误伤——该用例的**保序断言**在同轮被 m3 单独钉住 |
| **m2** | childrenAt 的 `valueAt(at)` 改恒取首段值 | **唯一红**：`chainFollowsTheParentAtTheQueryTime:159`（1连在 TS 已脱挂，变异体仍把它算作 1营 子级 ⇒ 链被错误解析成 1 候选） | R-12-b 夹具的分叉性实证：唯一红点恰是补条，其余 8 条全绿——**时点敏感性被单独钉住** |
| **m3** | 删 `hits.sort(Comparator.comparing(...))`（连 `Comparator` import 一起删） | **唯一红**：`chainWithMultipleHitsIsOrderedByUnitId:108`（插入序 u-b 先、期望 u-a 先） | 多解字典序保序的判别力来自夹具插入序与 UnitId 序的**错位**；红了且理由正确 |
| **m4** | 删 equipment 的 `containsKey` 判断 | **唯一红**：`equipmentResolvesOnlyWhenTheNameExists:133`（"没有该装备 ⇒ 空候选"断言收到 1 候选） | 装备存在性的负向断言正好落在被删那行上 |
| **m3b**（选做） | 计划原建议：根候选改"所有单位"（不筛无父） | **0 红——存活** | R-12-c 预言命中：唯一能分叉的输入是"链首是非根名"（如 `unit:"1营指挥部.1连指挥部"`），当前 9 条用例的链首全是根名，frontier 扩大不改变任何命中。**如实记录存活**；未补新用例——派单明说主靶用 sort、m3b 可选，补用例属于扩大测试面而非守卫本任务已裁决的行为，留给控制器裁定 |

## 四 门禁数字（全部实测）

- 实现前：`-Dtest=UnitResolverTest` 编译失败（`UnitResolver` 不存在）。
- 实现后迭代：`./mvnw -q -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UnitResolverTest test` ⇒ **Tests run: 9, Failures: 0, Errors: 0**（surefire 报告在案）。
- `./mvnw -q spotless:apply`：零改动（实现与测试一次写成即 gjf 形态），**先于**实验室执行。
- `./mvnw verify`（`gate-clean-verify.log`）：**rc=0，BUILD SUCCESS**；测试数 util **156** / map **248** / social **30** / unit **67** / core **15**；`BugInstance size is 0` ×5；Spotless/Checkstyle/Enforcer 全过。

## 五 关切与未能核实清单

1. **m3b 存活（已如实记录，未加用例）**："链首是非根名"在 spec §4.8 里**不在服务范围**（链"自顶向下"、从无父者起）——变异体把它也收进来是**扩大服务面**而非改变已裁决行为；要不要用负向用例把它钉死（当前实现下"链首非根名"若首级恰好无命中会得到空候选，行为其实正确），属于加测面问题，不是缺陷。留给控制器。
2. **含点 ID 的取舍**（spec §4.8 已记录在案）：ID 含 `.` 时 `name.split` 会把 ID 形也切碎——但 ID 形**先查表命中**，只有未命中才切分，故已知 ID 不受影响；受影响的只是"用不存在的含点 ID 打地址"会多走一次必然失败的链式（结果仍是空候选）。行为正确，代价为零。
3. **`UnitId.parse` 对任意非空白串放行**：链式名字先过 `parse`（不会因含点抛），这正是 R-12-a 取代写法成立的前提之一；若将来 parse 收紧格式，链式路径需要重审（届时 parse 抛 IAE 落在"认领路径名字解析失败"的既有口径里，口径不变）。
4. 本轮未动 plan/spec/CLAUDE.md/其他模块——取代说明只写在实现与测试的就地 Javadoc 和本报告里。
