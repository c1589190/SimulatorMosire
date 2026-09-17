# SDD ledger — plan: docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md

> **本文件是 M3（SocialSimos + UnitSimos）的台账与恢复地图。**
> M1 台账在 `.superpowers/sdd/2026-09-16-util-simos-plan/`，M2 台账在 `.superpowers/sdd/2026-09-16-map-simos-plan/` ——
> 只读，不要写回去。本目录是本轮唯一可写台账。
> **台账记裁定与结论，不记取证过程**（CLAUDE.md 纪律；体量不得压过代码本身）。

**上游**：M3 spec `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`（**已获用户批准，2026-09-17**）；
总纲 `2026-09-16-simos-master-design.md`；M1 spec（已执行）；M2 spec 与计划（已关账，`origin/feat/m2-map-simos` @ `9e4c1cb`）。

**执行基线**：分支 `feat/m3-social-unit-simos`，BASE = `1b0397e`（计划提交）。执行者 = 控制器 + 实现者子代理。

**跨里程碑承接约束**（M1/M2 关账时补记）：
1. 含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 的 `addition`（各写各的 lambda ⇒ 假红）。
2. `Map.copyOf` / `Set.copyOf` 迭代序非内容纯函数 ⇒ 保序一律 `LinkedHashMap` + `unmodifiableMap`。
3. 变异自证五形态（CLAUDE.md 纪律节）：字节不同先自证 / 白名单推成目标类名 / 每轮干净世界 / `COMPILATION ERROR` 计数为 0 / 红点必须在被保护那行。

---

## 任务地图（13 任务）

| # | 任务 | 状态 |
|---|---|---|
| 1 | `FieldDelta` 上移 util + `diff`/`rebuild` 提为静态机制 + M2 侧委托 + R1 | ✅ `0de76ed`+`b9ff9b8` |
| 2 | `TerrainType.IMPASSABLE_MOVE_COST` + R2 | ⏳ |
| 3 | `PopulationSeries`：积分语义 + R3 + R4 | ⏳ |
| 4 | `SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返 | ⏳ |
| 5 | `SocialResolver` + R12/R13 的 social 半 | ⏳ |
| 6 | `UnitId` / `Unit` / `UnitState` + `effectivePosition` + R5/R6/R7 | ⏳ |
| 7 | `UnitChangeSet` + 往返 | ⏳ |
| 8 | `MovementCost` + `TerrainMovementCost` + 判据二夹具 | ⏳ |
| 9 | `PathFinder`（A\*）+ R8 | ⏳ |
| 10 | `Route` / `Movement` / `MovementState` / `UnitMoves` + R9/R10 | ⏳ |
| 11 | `UnitOperations` 8 项 + R11 | ⏳ |
| 12 | `UnitResolver` + R12/R13 的 unit 半 | ⏳ |
| 13 | M3 关账 | ⏳ |

---

## 执行日志

（逐任务关账时追加）


## Task 1 关账（2026-09-17）

- **交付** `0de76ed`（代码 9 文件，+138/−104，含 53% rename 检出）+ `b9ff9b8`（报告 + 证据，6 文件 +978）。
- **评审形态 = 控制器自读 diff**（不派评审者）。核过：迁移逐字（`diff`/`rebuild` 逻辑与原件一致，接口内 `new Unchanged<>()` 等短路写法正确）；类 Javadoc 三处改写 + C7 新增段落位；`MapChangeSet` 委托完整、4 行无用 import 已清（R-1-c）；import 面 7 文件全对（终局 `git grep "map.change.FieldDelta" -- 'simos-map/src'` 空）；R1 用例照计划（`rawLines`、`containsExactly`、`entry` 导入已补）。
- **控制器独立复核**（与实现者证据相互独立）：`RegressionGuardsTest` **9/9 绿**（BUILD SUCCESS，本机当场跑）；变异轮 `m3t1v-1.kept`：干净世界 113 .java/extras=0、改前 156+246 绿、变异体 md5 `a1f1e486…`、`COMPILATION ERROR count = 0`、25 类真跑、红点原文 `R1_thereIsExactlyOneFieldDelta:402`（actual 带出多余键 `LegacyFieldDelta.java`）；`gate-clean-verify.txt` rc=0、util 156 / map **246** / core 15、`BugInstance size is 0` ×5。
- **裁定（执行期，取代计划 Step 8 第 3 条）**：R1 变异体的**声明名必须是 `FieldDelta`**（包级私有，落 `LegacyFieldDelta.java`），**不是** `LegacyFieldDelta`——守卫是逐行子串 `interface FieldDelta`，改名副本会**假绿**。依据：javac 探针（包级私有可编译，rc=0）+ 本任务实跑。计划原文的"确保类名与文件名一致（LegacyFieldDelta）"是计划缺陷。
- **关切（不挡关账）**：① R1 用 `rawLines` 不剔注释 ⇒ 对注释里出现该字面同样敏感（只会更严；将来若 Javadoc 里出现会假红，届时可改 `codeLines`，一行的事）；② 变异体命中 2 次（注释 1 + 声明 1），决定性命中是声明行；③ `simos-social`/`simos-unit` 的 src/main 现只有 `package-info.java` ⇒ R1 对这两模块**结构性活着**、暂无真实代码可护；④ `.omo/` 为先前已存在的未跟踪目录，未触碰。

## Task 2 关账（2026-09-17）

- **交付** `ca1284a`（`TerrainType.java` +12/−2，`ImpassableSentinelTest.java` 新建 32 行）+ `577baf9`（报告 + 证据）。
- **评审 = 控制器自读 diff**：常量落 record 体最前、Javadoc 照计划；类 Javadoc 三处字面量改写到位；用例 = 计划原文 + **R-2-a 的 `filteredOn(key -> !"ocean".equals(key))` 修正**（注释在案）。
- **控制器独立复核**：`ImpassableSentinelTest` **2/2 绿**（本机当场跑，BUILD SUCCESS）；两轮 `.kept` 红点**各自唯一**落 `oceanUsesTheImpassableSentinel:18`（m1 `expected: 998 / but was: 999`，m2 反向——两侧互换证明守卫真在比较两侧）；两轮改前 **156+248 全绿**、`COMPILATION ERROR count = 0`；R-2-c 兑现（`TerrainCatalogTest` 未受 m2 波及）。
- **裁定（执行期，取代计划 Step 1 第二用例原文）**：`everyOtherTerrainIsBelowTheSentinel` **必须排除 ocean**——`KEYS` 含 ocean 自身（999），原样 `allSatisfy` 会对自己取哨兵的海洋断言 `999 <= 998`、**恒红**（计划 Step 4 的"2/2 绿"按原文到不了）。修正 = `filteredOn`，保用例名与 Javadoc 的原意（"除海洋外"）。
- **关切（不挡关账）**：① 常量与词表仍是**两个物理字面量**（守卫钉等值；物理单一化=新裁决）；② unit 侧对它的消费要等 Task 8 才可核实（届时勿复制第二份哨兵）；③ 装置本轮做了两处必要触碰（surefire 抽取目标、`replace_exactly_once` 自证），已记入报告。
- **下一任务** Task 3（`PopulationSeries` + R3 + R4）。

## Task 3 关账（2026-09-17）

- **交付** `381c705`（`PopulationSeries.java` 170 行 + `PopulationSeriesTest.java` 153 行/10 用例 + `package-info.java` 顺改）+ `e0f8dea`（报告 + 证据）。
- **评审 = 控制器自读 diff**：五步算法逐字对上 spec §3.2（切分点收集/anchor 前恒定/先切段后事件/列表序施加）；构造校验三条（growth 首段晚于 anchor、growth 带事件、events 非递减）+ null 三条；冻结数字 **18036 / 6300 / 15000 / 10000** 全在断言里（实测行号 39/57/77/84/146）。
- **控制器独立复核**：`PopulationSeriesTest` **10/10 绿**（本机当场跑，BUILD SUCCESS）。
- **三轮红点（实测，先跑后写）**：m1（删 `cuts.add(t)`）**3 红同根因**——seed 18036→16700、boundary 6300→2100、multi 15000→5000（尾段不施）；m2（反转 `applyEventsAt` 施加序）**恰 1 红**——multi 15000→15300；m3（删 growth 事件校验）**恰 1 红**——`growthCarryingEventsIsRejected`。各轮改前 156/248/10 全绿、`COMPILATION ERROR count = 0`。
- **追认（执行期）**：计划第 703 行消息里的 ASCII 引号确会截断字符串字面量（实现者 hexdump 实证），按计划 809 行自带处置改为「同刻多事件」——两处均在计划授权内。
- **关切（不挡关账）**：① m1 的连带红已全列——形态是"同根因"而非"判别力不足"；② `mutate.py` 注释里 m1 机制的初稿预测值（20875）与实测（16700）不符，已按实测订正入库——`.kept` 证据本就是实测值；③ simos-social 首个测试目录建立，surefire 正常识别；④ 实现者途中拦下一个误入的 `__pycache__/*.pyc`（未入库）。
- **下一任务** Task 4（`SocialData` / `SocialSnapshot` / `SocialChangeSet` + 往返测试）。
