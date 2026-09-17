# M3 Task 8 报告：`MovementCost` + `TerrainMovementCost` + 判据二夹具

**执行者**：Sisyphus-Junior（实现者）　**BASE**：`e3a2ca2`　**分支**：`feat/m3-social-unit-simos`
**权威资料**：派单说明 `task-8-brief.md`（R-8-a~f）＞ 计划 2372~2676 行 ＞ spec §4.3。

---

## 〇、交付面

| 文件 | 性质 | 内容 |
|---|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/move/MovementCost.java` | 新增（main） | 接口：`costMillis(from,to,unit,map) → OptionalLong` + `minStepCostMillis(unit,map) → long`。A\* 启发与成本出自同一实现的落点（spec §4.3） |
| `simos-unit/src/main/java/io/mosire/simos/unit/move/TerrainMovementCost.java` | 新增（main） | v1 实现：`public static final INSTANCE`、私构造、无状态；毫 MP 定点成本 `scale(v,‰)=floorDiv(v×‰+500,1000)` |
| `simos-unit/src/test/java/io/mosire/simos/unit/move/MoveFixture.java` | 新增（test） | 判据二冻结夹具：`[1,1]/[1,2]/[1,3]` 走廊、自建地形 25/65/999（**不进 TerrainCatalog**）、单位 `u-f82a`（speed 2、mobility ‰500）。`final class`、包级私有、私构造（R-8-f） |
| `simos-unit/src/test/java/io/mosire/simos/unit/move/TerrainMovementCostTest.java` | 新增（test） | **7 用例**（原计划 6 + fix-1 转正 1，见 §四#1 与 §五 fix-1 节） |

执行顺序照派单：夹具+失败测试 → 编译失败（唯一一次红）→ 实现（R-8-c）→ 6/6 → `spotless:apply` → 四轮变异 → `verify` → 提交。

---

## 一、R-8-a~f 逐条

### R-8-a（m1 前提的代数问题：先测量、再补真变异）★

**① 代数结论（与派单一致，实测确认）**：本设计里 `scale` 的入参 `v` 恒为 `moveCost×1000`（1000 的整倍数）⇒ `v×‰` 已被 1000 整除 ⇒ `+500` **永不进位** ⇒ floorDiv 形态恒等于**精确乘法** `moveCost×‰`，没有任何舍入发生。

**② 实测扫描**（`task-8-evidence/m1-scale-scan.txt`，A 侧 = floorDiv 计划式、B 侧 = `Math.round(v*‰/1000.0)` 变异式）：

| (moveCost, ‰) | A = floorDiv(v×‰+500, 1000) | B = round(v×‰/1000.0) | 分叉? |
|---|---|---|---|
| (25, 500)（冻结夹具 1） | 12500 | 12500 | 否 |
| (65, 500)（冻结夹具 2） | 32500 | 32500 | 否 |
| (7, 333)（计划点名） | 2331 | 2331 | 否 |
| (1, 501)（计划点名） | 501 | 501 | 否 |
| (998, 500) / (1, 1) | 499000 / 1 | 同 | 否 |
| (998, 2147483647)（int 上界） | 2143188679706 | 2143188679706 | 否 |

全域扫描：`moveCost 1..998 × ‰ 1..1000` + 每行大值边界 `{1001, 333, 501, 999999999, 2147483647}` ⇒ **checked = 1 002 990，diverge = 0**。（无分叉是必然的：`v×‰ ≤ 998000×(2³¹−1) ≈ 2.14×10¹⁵ < 2⁵³`，double 两侧全精确；商 ≤ 2.14×10¹² 亦精确。）**如实记录：m1 是等价变体、无判别输入——这是关于公式的实测结论，不是失败。**

**③ m1'（替代真变异，已跑，.kept 在案）**：取派单给的第一个形态——`costOf` 里 `scale(type.moveCost() * 1000L, …)` → `scale(type.moveCost(), …)`（丢 ×1000，单位错误）。**红点 = `stepCostsMatchTheFrozenFixture:21`**（实际收到 `OptionalLong[13]`，期望 12500）。`impassableTerrainHasNoCost`（在 scale 之前 return empty）与 `minStepCost…`（用自己的 ×1000L 调用点）仍绿——与预测一致。

### R-8-b（m2/m3 形态）

- **m2**：`costOf` 的 `>=` → `>` ⇒ **红点 = `impassableTerrainHasNoCost:29`**（"Expecting an empty OptionalLong but was containing value: **499500L**"，正是 scale(999000,500)）。其余 5 用例绿。
- **m3**：删 `minStepCostMillis` 的"跳过不可通行"守卫。**首轮实测：计划自带的 `minStepCostIsTheCheapestTraversableTerrainScaled` 不红（0 红点）**。派单预测的"999 会赢，499500≠12500"在算术上不成立：min 对**更大**的值不敏感——夹具里 25 缩放后 12500 恒最小，把 499500 加进候选集不改变 min；且 `map(STEEP_65)` 的三个格**根本没有 999 格**（999 只在词表里）。999 唯一能"赢"的场景 = **全图不可通行**（无可通行格 ⇒ 正确实现返回 0，变异体返回 499500）。
  - 首轮以实验室专用探针（全 999 图 ⇒ 断言 `minStepCostMillis == 0`，spec §4.3 第 6 条）兑现了 m3 的红（`expected 0L, but was 499500L`），并在报告 §四#1 上报"该冻结行为提交套件零覆盖"。
  - **fix-1（控制器裁定转正，最终形态）**：探针转正为提交用例 `TerrainMovementCostTest.allImpassableMapHasZeroLowerBound`（夹具新增 `MoveFixture.allImpassableMap()`），对**最终字节**重跑 m3 轮 ⇒ **红点 = `TerrainMovementCostTest.allImpassableMapHasZeroLowerBound:73`（expected: 0L, but was: 499500L）**，落在**提交在案**的用例上；计划自带 minStep 用例仍绿（无判别输入，符合判别力分析）。`rounds/m3t8v-4.kept` 已被本轮取代（首轮探针形态的 .kept 不再保留，轨迹在本节与 fix-1 节）。
- 各轮 `COMPILATION ERROR count = 0`、改前基线全绿（见 §三；fix-1 后基线 unit = 30）。

### R-8-c（实现纪律）✅

- 计划草图 `minStepCostMillis` 对每格调 `terrainOf(cell)` 两次 → **实现合并为一次查表**（循环体先取 `TerrainType type`，判可通行与取最小同用）；`costMillis` 里草图的 `containsKey(to)` + `get(to)` 两次查 hexes 同理合并为一次 `get` + null 判（`GameMap` 构造期保证值非 null）。
- 不可通行判据两处（`costOf` 与 `minStepCostMillis`）全部**引用 `TerrainType.IMPASSABLE_MOVE_COST`**，unit 侧无第二份 999 字面量（`grep -rn "999" simos-unit/src` 的命中全部在测试侧：夹具自建词表的 key/value 与注释，`src/main` 零命中）。

### R-8-d（期望数字）✅

- 加实现前：**编译失败**（`cannot find symbol: MovementCost / TerrainMovementCost`，`pre-implementation-compile-failure.log`，COMPILATION ERROR = 1）——唯一一次"红是编译错"。
- 加实现后：`TerrainMovementCostTest` 首 **6/6**（`post-implementation-6of6.log`：`Tests run: 6, Failures: 0`）；fix-1 转正第 7 条后 **7/7**。七条 = 12500/32500 两值 + 不可通行 empty + 图外 empty + 非相邻/自环 IAE + 缺词表 key IAE + minStep 12500 + 全不可通行 ⇒ 0（fix-1）。
- 改前基线（每轮 before 实测）：util **156** / map **248** / social **30** / **unit 29**（23 存量 + 6 新增）——首轮四轮完全一致；fix-1 重跑 m3 轮时基线为 **unit 30**（29 + 1 转正）。
- 各轮变异 `COMPILATION ERROR count` 全部 = **0**；每轮 simos-unit 测试类实跑（首轮 6 类，m3 探针轮与 fix-1 轮 6 类单变异文件）。

### R-8-e（装置）✅

`task-8-evidence/{run.sh, mutate.py}` 为 Task 7 装置的拷贝：`-pl simos-unit -am`、manifest（util+map+unit = **131 个 .java**）、干净世界三连（逐文件 md5 + 文件数 + 无清单外 .java）、字节不同自证、并集自证、`COMPILATION ERROR count=0` 断言、实跑断言、红点抽取（测试名+行号）+ surefire 实际失败类摘要（FAILURE! 行 + *.txt 去栈帧）。`ROUNDS_DIR` = `task-8-evidence/rounds`、`/tmp/m3t8lab`、TARGET 换 m3t8v-1(m1)/-2(m1')/-3(m2)/-4(m3)。

### R-8-f（形制）✅

`MovementCost` 公开接口；`TerrainMovementCost` `public final class` + `public static final INSTANCE` + 私构造；`MoveFixture` = `final class`、包级私有、私构造，**测试目录**；夹具地形三个 `TerrainType` 全部夹具自建（形参序照源码：key, name, color, minHeight, maxHeight, food, gold, stone, moveCost, description），`TerrainCatalog` 一字未动；非相邻/自环 ⇒ IAE（调用方 bug），图外 `to` ⇒ empty。

---

## 二、测试清单（6 用例与门禁数）

| 用例 | 断言 | 结果 |
|---|---|---|
| `stepCostsMatchTheFrozenFixture` | 25→**12500** 毫、65→**32500** 毫 | 绿 |
| `impassableTerrainHasNoCost` | 999 格 ⇒ empty | 绿 |
| `hexOutsideTheMapHasNoCost` | `(9,9)` 图外 ⇒ empty | 绿 |
| `nonAdjacentStepsAreACallerBug` | `H11→H13`（距离 2）与 `H11→H11` ⇒ IAE | 绿 |
| `unknownTerrainKeyThrows` | 词表被抽空的图 ⇒ IAE | 绿 |
| `minStepCostIsTheCheapestTraversableTerrainScaled` | minStep = **12500** | 绿 |
| `allImpassableMapHasZeroLowerBound`（fix-1 转正） | 三格全 999 ⇒ minStep = **0**（spec §4.3 第 6 条） | 绿 |

**`./mvnw verify`（`gate-clean-verify.log`，fix-1 后重录）**：rc=0，BUILD SUCCESS；Tests run：util **156** / map **248** / social **30** / unit **30** / core **15**；`BugInstance size is 0` **×5**；Spotless/Checkstyle 全过。（fix-1 前的首轮门禁 = unit 29，其余同。）

---

## 三、变异实验室四轮（实测红点全列）

每轮自证头（.kept 在案）：干净世界 OK（131 个 .java 逐文件 md5 一致、清单外 .java = 0）→ 改前全绿（156/248/29、CE=0）→ 变异体与原件**字节不同**（md5 对）→ 实际(改动∪新增) == 声明集合 → 改后 CE=0 → simos-unit 测试类实跑。

| 轮 | 变异 | 改后红点（实测全列） | 判读 |
|---|---|---|---|
| m3t8v-1（m1 原形态） | `scale`：floorDiv → `Math.round(v*‰/1000.0)` | **红点数 = 0**（156/248/29 全绿） | **存活**。R-8-a 预期兑现：等价变体、无判别输入（§一 R-8-a ②的 1 002 990 对实测 0 分叉为据） |
| m3t8v-2（m1' 真变异） | `costOf` 丢 `* 1000L` | `TerrainMovementCostTest.stepCostsMatchTheFrozenFixture:21`（OptionalLong[**13**] ≠ 12500） | **1 红**，恰落在冻结成本数上；impassable/minStep 绿符合结构预测 |
| m3t8v-3（m2） | `>=` → `>` | `TerrainMovementCostTest.impassableTerrainHasNoCost:29`（empty 变 **499500L**） | **1 红**，单一来源哨兵判据被废时唯一守它的用例红 |
| m3t8v-4（m3，首轮） | 删 minStep 的跳过守卫 + 落探针 | 提交套件 **0 红**；探针 `AllImpassableMinStepProbeTest.allImpassableMapHasZeroLowerBound:36`（expected **0L**, but was **499500L**） | 计划自带 minStep 用例对 m3 **无判别输入**（min 对更大值不敏感 + 夹具无 999 格）；探针红的就是被保护的那行（spec §4.3 第 6 条）——该发现上报后由 **fix-1 转正**（下行） |
| m3t8v-4（m3，**fix-1 重跑**） | 删 minStep 的跳过守卫（对**最终字节**） | `TerrainMovementCostTest.allImpassableMapHasZeroLowerBound:73`（expected **0L**, but was **499500L**） | **1 红，落在提交在案的用例上**；计划自带 minStep 用例仍绿（无判别输入，符合分析）。基线改前 156/248/**30**、CE=0、单文件变异并集自证 OK |

红点与 Task 7 各轮零重叠、各轮之间也互不重叠（m1'/m2/m3 各 1 红，落点互不相同）。

---

## 四、关切 / 未能核实（不挡关账，供裁定）

1. **★ 已转正（fix-1，控制器裁定）**：首轮报告发现 spec §4.3 第 6 条冻结的"全图不可通行 ⇒ 返回 0"**提交套件零覆盖**（计划的 6 条是定盘；探针只在实验室）。控制器裁定**转正**：`TerrainMovementCostTest.allImpassableMapHasZeroLowerBound`（+ `MoveFixture.allImpassableMap()`）入提交套件（7 条），并对最终字节重跑 m3 轮——红点落在提交用例上（§三末行）。此关切**关闭**。
2. **R-8-b 的预测与算术的出入**（已如实记录）：派单预测 m3 ⇒ 计划自带 minStep 用例红（"999 赢，499500≠12500"）；实测该用例绿。原因 = min 不因加入更大的候选而变 + `map(STEEP_65)` 无 999 格。m3 的判别力最终由**提交在案**的 `allImpassableMapHasZeroLowerBound` 兑现（红点实测在案）。**未跑**"改夹具让该用例红"的变体——那等于改计划定盘的用例，超出本任务授权。
3. m1 的扫描程序是**纯算术复刻**（与 `scale` 逐字符同式的两个公式，`/tmp` 里跑、输出入库），不是对编译后 `TerrainMovementCost.class` 的反射调用；m1 变异体对冻结夹具的"两侧同值"则由 m3t8v-1 的套件实跑（12500/32500 断言仍绿）实打实压过。两者合起来覆盖"等价"结论的两侧。
4. `spotless:apply` 在实验室之前跑过且四文件零改动（`git status` 干净；fix-1 后复跑亦零改动）；实验室全程在 `/tmp/m3t8lab`，工作树未被触碰（run.sh 末尾 `git status --short` 仅 `.omo/` 与本任务新增目录/改动文件）。

---

## 五、fix-1 记录（控制器裁定转正，2026-09-17）

- **裁定**：§四#1 的发现成立——"无任何可通行格 ⇒ 返回 0"是 spec §4.3 第 6 条的**真实冻结行为**，探针只活在实验室 = 该行为无提交在案的守卫。转正。
- **改动**：`MoveFixture` 增 `allImpassableMap()`（三格全 `IMPASSABLE_999`，同探针构造）；`TerrainMovementCostTest` 增 `allImpassableMapHasZeroLowerBound`（断言 `minStepCostMillis == 0`）。**实现一字未动**（原件本就绿）。
- **验证**：`TerrainMovementCostTest` **7/7** 绿；对最终字节重跑 m3 轮（`rounds/m3t8v-4.kept` 已替换）：干净世界 131 个 .java、改前 156/248/**30** 全绿、单文件变异字节不同（md5 `cd20ce…` → `df5bc9…`）、并集自证 OK、CE=0，红点 = **`allImpassableMapHasZeroLowerBound:73`（expected: 0L, but was: 499500L）**。
- **门禁**：`./mvnw verify` rc=0（156/248/30/**30**/15，`BugInstance size is 0` ×5），日志重录入库。
- **装置同步**：`mutate.py` 的 m3t8v-4 删去探针落盘分支（`files_of` 回到单文件）、`run.sh` 的 TARGET 文案更新为 fix-1 形态；首轮探针形态的 `.kept` 按裁定**不再保留**（轨迹保留在本报告 §一 R-8-b 与本节）。
- **提交**：A `test(unit): minStep 全不可通行边界转正（Task 8 fix-1）`；B `docs(sdd): M3 Task 8 fix-1 报告与证据更新（-f 越过 .superpowers/sdd/.gitignore）`。

---

## 六、结论

Task 8 交付完成（含 fix-1）：接口 + 单例实现 + 判据二夹具 + **7/7**；五轮变异 CE=0、改前全绿，红点实测为 m1 存活（等价，0 红）、m1' 1 红、m2 1 红、m3 首轮探针 1 红 → **fix-1 转正后红点落在提交用例上**（提交套件判别力闭合）；`./mvnw verify` 全绿（156/248/30/30/15，SpotBugs 0 ×5）。
