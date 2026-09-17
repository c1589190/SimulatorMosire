# M3 Task 2 关账报告：`TerrainType.IMPASSABLE_MOVE_COST` 具名常量 + R2 守卫

**执行者**：实现者（本会话） **BASE**：`96ce348` **分支**：`feat/m3-social-unit-simos`
**权威**：派单说明 `task-2-brief.md`（与计划冲突处以简报为准）→ 计划 350~447 行 → spec §〇.2 C5 / §六 R2。

---

## ① 交付面

| 交付物 | 内容 |
|---|---|
| `TerrainType.java`（改） | record 体最前新增 `public static final int IMPASSABLE_MOVE_COST = 999;`（带计划 Step 3 逐字 Javadoc：唯一判据、与词表同源由 R2 钉住、不参与算术）；类 Javadoc 的哨兵句改为"（**具名常量** {@link #IMPASSABLE_MOVE_COST}，海洋取的就是它）" |
| `ImpassableSentinelTest.java`（新建，32 行） | 2 条用例：`oceanUsesTheImpassableSentinel`（词表 ocean == 常量）、`everyOtherTerrainIsBelowTheSentinel`（KEYS 过滤掉 ocean后逐项 ≤ 常量−1；R-2-a 修正见②） |
| 提交 A | `ca1284a` `feat(map): TerrainType.IMPASSABLE_MOVE_COST 具名常量 + 词表一致性守卫（M3 Task 2）`（2 文件 +42 −2） |
| 证据 | `task-2-evidence/{run.sh,mutate.py,rounds/m3t2v-1.kept,rounds/m3t2v-2.kept}` |

**无行为变化**：`TerrainCatalog` 的取值一字未动（999 仍只在 `TerrainCatalog.java` 的 ocean 行）；本任务只把"那个数"升格为具名常量并用 R2 守卫钉住（spec C5：unit 侧 Task 8 将读该常量，不在 unit 复制哨兵）。

## ② R-2-a ~ R-2-e 逐条落点

- **R-2-a（计划缺陷已按简报修正）** 计划 Step 1 草图的 `everyOtherTerrainIsBelowTheSentinel` 对 `TerrainCatalog.KEYS` 全集跑 `allSatisfy`，而 KEYS **含 `"ocean"` 本身**且海洋的 moveCost 恰是哨兵 999 ⇒ 按原文会对 ocean 断言 `999 <= 998` 必失败——与用例名/Javadoc"除海洋外"自相矛盾，属草图漏了排除项。
  **取代写法**：`assertThat(TerrainCatalog.KEYS)` 后加 `.filteredOn(key -> !"ocean".equals(key))`，行尾注释"海洋取的就是哨兵本身，本条管其余项"，其余照计划原文。**连带效应已坐实**（简报预警的两轮确定性绿，见③两轮实测）：m1 下非海洋最大值 12 ≤ 997 绿；m2 下 ocean 被过滤绿。红点唯一性因此成立。
- **R-2-b** 执行前实测 `TerrainCatalog.KEYS` 为 public（`simos-map/src/main/java/.../TerrainCatalog.java:24`）⇒ 照计划主写法用 `KEYS`，未改字面量清单；计划 Step 1 的"若无 KEYS 则…"分支不触发。
- **R-2-c** 执行前 `git grep -n 999 -- simos-map/src` 复核：map 源码仅 `TerrainCatalog.java` 两处（注释 42 行 + ocean 值 45 行），`TerrainCatalogTest` 不硬编码 999（只断言 ocean ≥ plateau_mountains）⇒ m2 红点唯一成立（③实测确认）。
- **R-2-d** 装置 = Task 1 已本机化 harness 的拷贝（`ROOT=/home/cna/SimulatorMosire` 保留）。改动仅四处：`LAB=/tmp/m3t2lab`、`ROUNDS_DIR=…/task-2-evidence/rounds`、`TARGET` 换成 m1/m2 两条、`mutate.py` 换成两条**就地数值替换**（999→998，`replace_exactly_once` 强制恰命中 1 处，否则该轮当场作废）。另有一处**最小适配**（报告里声明）："surefire 明说"的抽取文件从 `*RegressionGuardsTest.txt` 换成 `*ImpassableSentinelTest.txt`（Task 1 的目标是抓 R1 守卫的断言消息，本任务对应物就是本测试类）；干净世界 extras=0、并集自证、`COMPILATION ERROR count=0`、红点抽取全部原样保留。
- **R-2-e 期望数字全部兑现**：加常量前目标用例**编译失败**（`cannot find symbol IMPASSABLE_MOVE_COST`，本任务唯一一次"红=编译错"，加常量后消失）；加常量后 `Tests run: 2, Failures: 0`；两轮变异改前基线 = **util 156 / map 248 全绿**（BUILD SUCCESS ×2）。

## ③ 两轮变异自证 + 红点

### m1（`m3t2v-1`）：常量 999→998

自证头（`.kept` 原文）：
```
干净世界 OK（副本逐文件与 /home/cna/SimulatorMosire 的 md5 清单一致，共 114 个 .java；清单之外的 .java = 0 个，…）
改前 COMPILATION ERROR count = 0
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 248, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
  simos-map/.../terrain/TerrainType.java  md5 原件=dac90cdeb78cb4d6f6615fdd84a99062  变异体=da8a23d42adcac62402d0606215a7861
自证：修改的文件与原件字节不同 / 新增的文件已落盘且非空 OK
自证：实际（修改 ∪ 新增）== 声明集合，别无其它改动 OK
COMPILATION ERROR count = 0
simos-map 跑过的测试类数 = 26
[ERROR] Tests run: 248, Failures: 1, Errors: 0, Skipped: 0
```
红点（唯一，1 条）：
```
ImpassableSentinelTest.oceanUsesTheImpassableSentinel:18 [海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）]
org.opentest4j.AssertionFailedError:
[海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）]
expected: 998
 but was: 999
```
**判读**：红在被保护的那行——守卫读的是**常量**而非记死字面量；`everyOtherTerrainIsBelowTheSentinel` 确定性绿（12 ≤ 997）⇒ 两条用例各管一件事，不冗余（计划 Step 5 的"大概率"按简报坐实为"必绿"）。

### m2（`m3t2v-2`）：词表 ocean 999→998

自证头：
```
干净世界 OK（…共 114 个 .java；清单之外的 .java = 0 个，…）
改前 COMPILATION ERROR count = 0
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 248, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
  simos-map/.../terrain/TerrainCatalog.java  md5 原件=ae5f4575f33bdbb5bc56579dd16a11ab  变异体=e9adff7e46e914d271fc2fd666cb8f12
自证：修改的文件与原件字节不同 / 新增的文件已落盘且非空 OK
自证：实际（修改 ∪ 新增）== 声明集合，别无其它改动 OK
COMPILATION ERROR count = 0
simos-map 跑过的测试类数 = 26
[ERROR] Tests run: 248, Failures: 1, Errors: 0, Skipped: 0
```
红点（唯一，1 条）：
```
ImpassableSentinelTest.oceanUsesTheImpassableSentinel:18 [海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）]
org.opentest4j.AssertionFailedError:
[海洋的 moveCost 必须就是那个具名哨兵（不是另写的字面量）]
expected: 999
 but was: 998
```
**判读**：红在同一条被保护行——守卫钉住的是**词表与常量同源**；`everyOtherTerrainIsBelowTheSentinel` 因 R-2-a 的 `filteredOn` 确定性绿，`TerrainCatalogTest` 亦未红（R-2-c 预测兑现）。两轮红点**方向相反**（998/999 互换），说明守卫真在比较两侧，不是碰巧红。

## ④ 门禁数字

| 门禁 | 结果 |
|---|---|
| 加常量前（TDD 红） | testCompile 失败：`cannot find symbol IMPASSABLE_MOVE_COST`（2 处：19,31 / 31,53） |
| 加常量后（定向） | `ImpassableSentinelTest` **Tests run: 2, Failures: 0, Errors: 0** |
| 两轮改前基线 | util **156** / map **248** 全绿，BUILD SUCCESS ×2 |
| 两轮变异后 | map 248 run / **Failures: 1**，红点唯一（见③）；`COMPILATION ERROR count = 0` ×2；测试类数 26 ×2 |
| spotless | 提交 A 前已跑 `./mvnw -q spotless:apply`（类 Javadoc 折行为其按字符折行的既知形态） |
| 工作树 | 实验全程未被触碰（结束时 `git status --short` 仅 `.omo/` 未跟踪） |

测试数台账：M2 关账 util 156 / map 246 → 本任务 +2 ⇒ map **248**，与简报 R-2-e 一致。

## ⑤ 关切 / 未能核实清单

1. **Harness 的"surefire 明说"抽取目标**从 `RegressionGuardsTest` 换成了 `ImpassableSentinelTest`——这是 R-2-d"其余不动"之外唯一一处额外触碰（已见②声明）；不改的话该节在 Task 2 会静默空转（找不到对应 .txt），改成对应测试类才能把断言消息留在证据里。
2. **`mutate.py` 的 `replace_exactly_once` 是我新加的自证**（Task 1 版是整文件重写用不上）：替换目标若因未来重构改写而命中 0 或 2 处，该轮当场 `AssertionError` 作废而非静默假绿。属装置内加固，不在简报禁改清单内。
3. **R2 的"unit 侧消费"本任务不可核实**——`IMPASSABLE_MOVE_COST` 的真正读者（Task 8 的成本函数）还不存在；本任务的守卫只钉住 map 内部的同源性。跨模块消费是否真用该常量，要到 Task 8 才能验证。
4. **常量 999 与词表 999 仍是两个物理字面量**——常量在 `TerrainType`、词表在 `TerrainCatalog` 各写了一次 999。C5 的意图是"判据单一来源"，本任务交付的是**守卫**（两处必须相等）而非物理合并（把 `TerrainCatalog` 的 999 换成引用常量会造成 record 构造参数依赖静态常量的初始化顺序问题，且计划/简报都未要求）。若后续要物理同源，属新裁决。
5. **spotless 折行**：类 Javadoc 里 `{@code passable}` 被 google-java-format 按字符数折到了行首（diff 可见），与 CLAUDE.md"折行由它决定、不手工调"的口径一致，未手工回改。

## 提交

- **A（代码）**：`ca1284a` `feat(map): TerrainType.IMPASSABLE_MOVE_COST 具名常量 + 词表一致性守卫（M3 Task 2）`
- **B（报告+证据）**：见本文件同批 `git add -f` 提交（`docs(sdd): M3 Task 2 报告 + 变异实验室证据入库`）
- 未推送（按简报）。
