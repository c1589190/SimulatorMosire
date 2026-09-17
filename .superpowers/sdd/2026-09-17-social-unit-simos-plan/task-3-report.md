# M3 Task 3 报告：`PopulationSeries` 分段积分时态序列 + 判据一手算表 + R4

日期：2026-09-17。分支 `feat/m3-social-unit-simos`，BASE `c78e8e6`。
权威依据：派单说明 `task-3-brief.md`（冲突处以其为准）；计划 Task 3（第 450~835 行，代码即权威）；M3 spec §3.2/§3.3/§3.6。

## ① 交付面

| 文件 | 性质 |
|---|---|
| `simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java` | 新增。积分型时态序列 record（`implements TemporalSeries<Long>`），模块级 `ADDITION = Long::sum`，构造期校验 ×4（三组件非 null/冻结、growth 首段不晚于 anchor、growth 不得带事件、事件非递减），五步 `valueAt` 算法逐字照 spec §3.2，`withGrowthSegment` / `withEvent` 返回新值 |
| `simos-social/src/test/java/io/mosire/simos/social/population/PopulationSeriesTest.java` | 新增。simos-social 首个测试类，10 条用例 |
| `simos-social/src/main/java/io/mosire/simos/social/package-info.java` | 修改（R-3-f 顺手项）："不完善的测试模块"句改为指向 `population.PopulationSeries` |
| `.superpowers/sdd/2026-09-17-social-unit-simos-plan/task-3-evidence/{run.sh,mutate.py,rounds/*.kept}` | 变异实验室（提交 B） |

计划草图的两处就地处置（均有出处）：
- **取代说明 1（计划已自带）**：spec §3.3 把"事件非递减"记在 `SegmentedSeries` 名下，但本类型的事件是裸 `List`，不经过它——校验责任由 `PopulationSeries` 构造器承担（`requireNonDecreasing`），约束不变。Javadoc 已照计划落"取代说明"。
- **引号修正**：计划第 703 行消息里的"同刻多事件"内侧引号实为 ASCII `"`（hexdump 实证 `22`），会截断 Java 字符串字面量。按计划第 809 行自带的处置改写为「同刻多事件」。约束与消息语义不变。

## ② 判据一逐值（spec §3.6 冻结表）与 R4 逐条落点

期望值全部从 spec 冻结表**抄入**测试（不跑出来再填）；实现跑出来与之一致：

| 用例 | 冻结期望 | 实测 |
|---|---|---|
| `seedExampleMatchesTheHandComputedTable`（种子例 `valueAt(53)`） | **18036**（10000+4000+3500−800+1336，每区间一次舍入） | ✅ 绿 |
| `segmentBoundaryAndEventAtTheSameTick`（先切段再施事件） | **6300**（2000 → 切段 → +100=2100 → round(2100×0.2×10)=4200） | ✅ 绿 |
| `multipleEventsAtTheSameTickFollowInsertionOrder`（同刻按插入序） | **15000**（2000 → +100 → SET 5000 → +10000） | ✅ 绿 |
| `beforeAnchorIsConstant`（anchor 前恒定延拓） | **10000**（不增长、不施事件） | ✅ 绿 |
| `segmentsIsExactlyTheAnchor`（spec 偏离 1） | segments 恰为 `List.of(anchor)` | ✅ 绿 |

R4（构造期校验）逐条落点：`growthFirstSegmentStartsAfterAnchorThrows`（首段晚于 anchor ⇒ IAE 含 "growth"）；`growthCarryingEventsIsRejected`（growth 带事件 ⇒ IAE 含 "growth"）；`eventsMustBeNonDecreasing`（`withEvent` 回退 ⇒ IAE 含 "非递减"，走的是取代说明里的 `requireNonDecreasing`）；`nullComponentsAreRejected`（三组件逐个 null ⇒ IAE）。`withGrowthSegmentAndWithEventReturnNewValues` 同时断言**旧值不变**（record 值语义，R-3-g 要点）。

**恒真风险自检（R-3-g）**：实现里唯一的提前返回是 `t < anchor.from()`——那是 spec 冻结语义本身，不是按查询点猜结果；无任何常量 return、无缓存、无 per-tick 物化。10 条用例的红因各落在不同行：种子/边界/multi 三条各钉 `valueAt` 算法的不同侧面（切分结构、切段先于事件、施加序），`beforeAnchor` 钉提前返回分支，`segments` 钉 `segments()` 覆写，R4 四条各钉一条校验，`with*` 钉两个编辑方法 + 值语义。没有"永远绿"的用例——三轮变异的每个红点都实测得出（见③），无一条是推理出来的。

## ③ 变异自证（G13，3 轮）

装置：`task-2-evidence/{run.sh,mutate.py}` 拷贝到 `task-3-evidence/`，按 R-3-d 恰改四处（`-pl simos-social -am`；manifest 范围含 `simos-social/src`；digest 的 Running 过滤改通用前缀 `io\.mosire\.simos\.`；surefire 明说目标 `PopulationSeriesTest`）——另把"真的跑过"检查的 `Running io.mosire.simos.map` 与 surefire 路径同步换成 social 侧（与④同一意图，不计为第五处语义改动）。干净世界 extras=0、并集自证、字节不同自证、`COMPILATION ERROR count = 0`、`.kept` 格式全部继承。

**改前基线（每轮相同，`m3t3v-*.kept` 改前段）**：util **156** / map **248** / social **10** 全绿，改前 `COMPILATION ERROR count = 0`。

### m3t3v-1（m1）＝删 `cuts.add(t);`（查询点不进切分点；R-3-b 指定主形态）

- 自证头：PopulationSeries.java md5 原件 `21949f67…` → 变异体 `6b7a3f92…`（字节不同）；实际（修改 ∪ 新增）== 声明集合。
- `COMPILATION ERROR count = 0`；simos-social 跑过的测试类数 = 1。
- **实际红点全列（3 个，含同根因连带）**：
  - `seedExampleMatchesTheHandComputedTable:39` — expected: **18036**L but was: **16700**L
  - `segmentBoundaryAndEventAtTheSameTick:57` — expected: **6300**L but was: **2100**L
  - `multipleEventsAtTheSameTickFollowInsertionOrder:77` — expected: **15000**L but was: **5000**L
- 红因即被保护行：删查询点 ⇒ 末事件/末切分之后的增长整段不施（种子例：切分集剩 {0,20,45}，[45,53) 不形成）。三条红同一根因（各自的查询点都不再是切分点），连带形态与 R-3-b 预告一致，且每条的"was"都对得上该机制，无来路不明的红。

### m3t3v-2（m2）＝反转 `applyEventsAt` 施加序（倒序索引循环；R-3-c 优选形态）

- 自证头：字节不同 + 并集自证 OK；`COMPILATION ERROR count = 0`；social 测试类数 = 1。
- **实际红点（恰 1 个，与 R-3-c 预告一致）**：
  - `multipleEventsAtTheSameTickFollowInsertionOrder:77` — expected: **15000**L but was: **15300**L
- 红因即被保护行：SET 先于 ADD ⇒ t=10 基数 5000+100=5100，[10,20) 增 round(5100×0.2×10)=10200 ⇒ 15300。种子的单事件不受倒序影响（seed/boundary 仍绿）⇒ 该变异只打"插入序"这一件事。选此形态而非备选（SET/ADD 分支对调）的理由：备选会把种子的 ADD 事件当 SET 处理、连带 seed 红（R-3-c 已预告），判别力混叠。

### m3t3v-3（m3）＝删 `growth.events().isEmpty()` 校验（计划原文）

- 自证头：字节不同 + 并集自证 OK；`COMPILATION ERROR count = 0`；social 测试类数 = 1。
- **实际红点（恰 1 个）**：
  - `growthCarryingEventsIsRejected:110` — `java.lang.AssertionError: Expecting code to raise a throwable.`
- 红因即被保护行：校验删掉后非法构造不再抛。红落在"抛"这一点上，而非编译或其它用例。

## ④ 门禁数字

- TDD 红：实现前 `PopulationSeriesTest` **编译失败**（`cannot find symbol: class PopulationSeries`）——本任务唯一一次红=编译错（R-3-e）。
- 实现 + `spotless:apply`（apply 零改动：落盘即 gjf 形态）后：`PopulationSeriesTest` **10/10** 绿。
- 三轮改前基线：util 156 / map 248 / social 10 全绿 ×3；每轮变异后 `COMPILATION ERROR count = 0` ×3。
- 终检 `./mvnw clean verify`：**BUILD SUCCESS**，Tests run：util 156 / map 248 / **social 10** / core 15，全 0 失败；SpotBugs `BugInstance size is 0`（全部模块）；Spotless + Checkstyle 过（verify 内含）。
- 全仓用例总数 419 → **429**（util 156 / map 248 / core 15 不变，social 0 → 10）。

## ⑤ 关切 / 未能核实

1. **mutate.py 注释里 m1 机制的预测值曾写错（已订正）**：初版注释按"[45,70) 并成一段"推了 20875；实测 was=**16700**——删查询点后 70 根本进不了切分集（`from ≤ t=53` 过滤），机制是"末事件之后的增长**整个不施**"。红点本身（expected: 18036L）与 R-3-b 预告一致；`.kept` 里的全是实测，注释已按实测订正后入库。
2. **spec §3.3 与实现的责任归属**：`withEvent` 的非递减校验不在 `SegmentedSeries`（本类型事件是裸 List）——计划已带取代说明、由构造器承担，测试钉的是行为而非归属。无未决。
3. **未验证项**：`ADDITION` 的"往返按身份比较"后果要等 Task 4 的 `SocialChangeSet` 往返用例才真正受力，本任务无法核实（本类型尚未进 state）；`growth()` 的负增长段（种子表第三段 −3%）只被种子例间接触及（t=53 不到 70），其在 70 之后的积分行为无独立用例——计划用例集如此，未自行加戏。
4. 变异三轮全部按预期红/绿，无"红了要问为什么"或"没红要问为什么"的存疑点。

## 提交

- A（代码）：PopulationSeries.java + PopulationSeriesTest.java + package-info.java
- B（`git add -f`）：本报告 + task-3-evidence/
