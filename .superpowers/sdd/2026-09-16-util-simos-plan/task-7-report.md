# Task 7 报告：`TemporalSeries` 与 `SegmentedSeries`（`time` 包后半）

- 分支：`feat/m1-util-simos`（就地工作，未建分支/worktree）
- BASE：`fc4cff3`（工作树干净，仅控制器自己的 `progress.md` / `task-7-brief.md` 修改与未跟踪 `.serena/`）
- 提交：`3d314f3` feat(util): TemporalSeries 与 SegmentedSeries，四条时间语义可测（M1 Task 7）
- 状态：**DONE**
- 关账门禁：`./mvnw -pl simos-util clean verify` → **BUILD SUCCESS**
  （Spotless `spotless:check` 43 files clean + Checkstyle + Surefire + SpotBugs `BugInstance size is 0` / `Error size is 0`；
  **128 测试 / 0 failures / 0 errors**，115 → 128，净增 13，与 brief Step 5 预期一致）
- 未推送。提交后 `git diff HEAD -- simos-util/` 为空（含五轮变异测试的还原后复核，见 §4）

---

## 1. 实现了什么

brief Step 3/Step 4 的五个类型**逐字落地**（唯一差异是 `spotless:apply` 的折行，见 §6-1）：

| 文件 | 内容 |
|---|---|
| `.../util/time/TemporalSeries.java` | 接口三方法 `valueAt(SimosTimestamp)` / `segments()` / `events()`；Javadoc 点明"不做插值"与 SocialSimos 的自实现义务 |
| `.../util/time/Segment.java` | `record Segment<T>(SimosTimestamp from, T value)`；两条 `requireNonNull`，消息即字段名 |
| `.../util/time/Event.java` | `record Event<T>(SimosTimestamp at, T value, EventMode mode)`；三条 `requireNonNull` |
| `.../util/time/EventMode.java` | `enum { ADD, SET }` |
| `.../util/time/SegmentedSeries.java` | `record SegmentedSeries<T>(List<Segment<T>>, List<Event<T>>, BinaryOperator<T>) implements TemporalSeries<T>` |

`SegmentedSeries` 的四条时间语义与三条构造守卫：

- **紧凑构造器即唯一校验点**：两条 `requireNonNull`（字段级消息 `segments` / `events`）→ 两半 `List.copyOf` 防御性拷贝 →
  三段构造不变量：段非空（消息含 `anchor`）、段 `from` 严格升序（消息含 `严格升序`）、事件 `at` 非递减（消息含 `非递减`）、
  含 `ADD` 事件却未给 `addition`（消息含 `addition`）。因校验全在紧凑构造器，`of(...)` 与 record 公开的规范构造器
  走的是同一套守卫——这是控制器裁决 1 要求的形态，未额外加私有委托构造器、未弃用、未降可见性。
- `valueAt`：先 `baseValueAt` 取段值（`t` 早于第一段即取第一段值 = anchor 之前向前恒定延拓；`t == from` 即新段生效 = 左闭右开），
  再按插入序遍历全部事件，`event.at().compareTo(t) <= 0` 者依次施加（`ADD` 走注入的 `BinaryOperator`，`SET` 直接覆盖）——
  同刻先切段再施加事件、同刻多事件按插入序。**判"同刻"一律用 `compareTo`**（`SimosTimestamp` 的 `equals` 含 `calendarLabel`，
  `compareTo` 只看 `tick`，故带 label 的时间戳与裸 tick 在边界上等价，语义正确）。
- `segments()` / `events()` **不手写覆盖**：record 自动生成的访问器返回的就是紧凑构造器里 `List.copyOf` 后的不可变列表，
  已满足接口契约（brief 原注释即如此要求）。

## 2. 测试了什么、结果如何

`TemporalSeriesTest` **13 条**（brief 原 9 条 + 控制器补齐 4 条，与 brief Step 5 的枚举一一对应）：

| 用例 | 覆盖 | 来源 |
|---|---|---|
| `segmentBoundariesAreHalfOpen` | 9→100、10→200（切换点归新段）、1000→200（末段延伸到无穷） | brief |
| `beforeTheFirstSegmentTheValueIsExtendedBackwardsConstantly` | 10/0/**-99** → 5；依赖 `SimosTimestamp` 无非负校验 | brief |
| `atTheSwitchPointTheSegmentIsAppliedBeforeTheEvents` | 同刻 10：200 切段 + 5 → **205**（先切段后事件） | brief |
| `addAccumulatesAndSetOverrides` | ADD 累加 100→105→112；SET 覆盖为 42 | brief |
| `eventsAtTheSameMomentApplyInInsertionOrder` | 同刻两条事件，两种排列分别得 **0 / 10** | brief |
| `futureEventsAreNotApplied` | t=4 不受 at=5 影响；t=5 生效 | brief |
| `addEventsWithoutAdditionFailAtConstruction` | 含 ADD + `addition == null` → IAE 含 `addition`；SET-only 允许 null 且可算 | brief |
| `malformedSeriesAreRejectedAtConstruction` | 三条守卫三种消息：`anchor` / `严格升序` / `非递减` | brief |
| `segmentsAndEventsAreDefensivelyCopied` | 调用方 `clear()` 后行为不变 + 两个访问器均不可写 | brief |
| `nullArgumentsAreRejectedWithFieldLevelMessages` | 8 条字段级 NPE：`segments`/`events`/`from`/`value`/`at`/`value`/`mode`/`t` | 控制器 |
| `aSharedAdditionInstanceMakesStructurallyIdenticalSeriesEqual` | 同一 `static final` 实例构建的两个同构序列 `isEqualTo` | 控制器 |
| `twoEquivalentButDistinctLambdasMakeSeriesUnequal` | **先 `isNotSameAs` 自证前提**，再断言两序列 `isNotEqualTo` | 控制器 |
| `setOnlySeriesWithNullAdditionStillCompareByValue` | `addition == null` 的 SET-only 序列仍按值相等 | 控制器 |

运行命令与结果（`clean` 消掉增量编译陷阱）：

```
./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test   # exit=0（-q 静默；下方为去掉 -q 的计数）
./mvnw -pl simos-util -Dtest=TemporalSeriesTest test
  Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.util.time.TemporalSeriesTest
./mvnw -pl simos-util clean verify
  Tests run: 128, Failures: 0, Errors: 0, Skipped: 0   （模块总计，115 → 128）
  Spotless.Java is keeping 43 files clean - 0 needs changes to be clean
  BugInstance size is 0 / Error size is 0
  BUILD SUCCESS
```

基线复核：动手前跑过一次 `./mvnw -pl simos-util clean verify`，得 **115 测试 / BUILD SUCCESS / BugInstance size is 0**，
与派单所述基线**一致**（无既有偏差）。

## 3. TDD 证据（brief Step 2 / Step 5 原命令）

**RED** —— `./mvnw -q -pl simos-util -Dtest=TemporalSeriesTest test`，**编译失败**（exit≠0）：

```
[ERROR] .../TemporalSeriesTest.java:[187,63] cannot find symbol
[ERROR]   symbol:   variable EventMode
[ERROR] .../TemporalSeriesTest.java:[189,13] cannot find symbol   symbol: variable SegmentedSeries
[ERROR] .../TemporalSeriesTest.java:[206,12] cannot find symbol   symbol: variable SegmentedSeries
[ERROR] .../TemporalSeriesTest.java:[210,16] cannot find symbol   symbol: class Segment
[ERROR] .../TemporalSeriesTest.java:[214,16] cannot find symbol   symbol: class Event
[ERROR] -> [Help 1]
```

**失败原因正是预期的**：五个待建类型尚不存在，编译器对每一处引用报 `cannot find symbol`。
诚实说明：上面是 `tail -25` 截下的片段，完整错误块更长（同一级联覆盖 15+ 处引用，其中包含 brief Step 2 点名的
`cannot find symbol: class TemporalSeries`），截断处之后的条目与本片段同源，不存在"因别的原因失败"的可能——
该次运行没有任何一条错误指向既有代码或既有测试。

**GREEN** —— 写完全部五个 main 类型后，同一条命令：`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`（见 §2）。

## 4. 变异自证证据（G13：护栏必须自证）

每条变异都**只改 main**、跑同一条 focused 命令、看**是否转红**、随后 `git checkout --` 还原；
五轮全部还原，最终 `git diff HEAD -- simos-util/` 为空（工作树与 `3d314f3` 逐字节相同），末轮 `clean verify` 128 绿。

| # | 变异（临时植入 main） | 转红的用例 | 说明 |
|---|---|---|---|
| 1 | `SegmentedSeries` 手写 `equals`，**忽略 `addition`** | `twoEquivalentButDistinctLambdasMakeSeriesUnequal`（**仅此一条**） | 本项目最贵教训（`MapDiff` 手工维护、字段静默漂移）的形态；证明控制器裁决 2 的自证用例真的会响 |
| 2 | `Segment` 两条 `requireNonNull` 的**字段串互换** | `nullArgumentsAreRejectedWithFieldLevelMessages`（**仅此一条**） | 证明字段级消息断言有效——**只断异常类型的写法在此变异下仍会全绿** |
| 3 | 删掉 `segments.isEmpty()` 的 anchor 守卫 | `malformedSeriesAreRejectedAtConstruction`（**仅此一条**） | anchor 不变量确实被钉住 |
| 4 | `baseValueAt` 边界 `> 0` 改 `>= 0`（左闭右开被破坏） | `segmentBoundariesAreHalfOpen` + `atTheSwitchPointTheSegmentIsAppliedBeforeTheEvents` | "左闭右开"与"同刻先切段"确实被两条用例独立钉住 |
| 5 | 校验之后 `events = events.reversed()`（插入序倒置） | `eventsAtTheSameMomentApplyInInsertionOrder`（**仅此一条**） | 验证放在 `requireNonDecreasing` **之后**，故不会误伤时序守卫，红点唯一归属插入序 |

副产品结论：变异 4 同时红了边界与切换点两条用例，说明这两条语义不是同一条断言的重复表述。

> **⚠️ 本节的结论已被评审推翻一部分（2026-09-16，见 §8）**：上表**只**覆盖了「构造期」守卫，
> 而当时测试文件对 `valueAt(null)` 用的是 `hasMessageContaining("t")`——needle 单字符，**根本不自证**：
> 删掉该守卫后 JDK 的 helpful NPE 消息（`Cannot read field "tick" because "other" is null`）同样含 `t`，断言照绿。
> 故本次五轮变异**不能**支持"全部守卫都有自证"的结论，该结论当时即**过度声称**。§8 的修复 1 补齐了这个缺口。

## 5. 文件变更

`git diff --cached --stat`（提交前）：

```
 simos-util/src/main/java/io/mosire/simos/util/time/Event.java             |  13 ++
 simos-util/src/main/java/io/mosire/simos/util/time/EventMode.java         |   7 +
 simos-util/src/main/java/io/mosire/simos/util/time/Segment.java           |  12 ++
 simos-util/src/main/java/io/mosire/simos/util/time/SegmentedSeries.java   |  98 ++++++++++
 simos-util/src/main/java/io/mosire/simos/util/time/TemporalSeries.java    |  20 ++
 simos-util/src/test/java/io/mosire/simos/util/time/TemporalSeriesTest.java| 215 +++++++++++++++++++++
 6 files changed, 365 insertions(+)
```

全部为**新增**，无既有文件被改动。`git add` **只**加了 brief Step 6 点名的两个目录（`time` 包的 main 与 test），
提交前 `git diff --cached --name-only` 逐行核对为上述 6 个文件；未跟踪的 `.serena/` 与控制器在改的
`progress.md` / `task-7-brief.md` **未入提交**。本报告文件同样**未入提交**（台账归控制器）。

## 6. 自审发现

1. **与 brief 的逐字一致性**：把 brief 里五段 main 代码块与测试代码块**按行区间抽出来逐一 `diff`**，结论是：
   `Segment.java` / `Event.java` / `EventMode.java` **完全一致**；`TemporalSeries.java` / `SegmentedSeries.java` /
   `TemporalSeriesTest.java` 仅有 `spotless:apply` 造成的**折行**差异（google-java-format 按字符数重排中文 Javadoc、
   合并可放进 100 列的方法调用/字符串拼接），**无任何语义或断言差异**。CLAUDE.md 明确中文 Javadoc 折行以
   google-java-format 为准，故此为预期形态而非偏离。
2. **依赖与边界**：staged main 源码的 `import` 全集为 `java.util.List` / `java.util.Objects` /
   `java.util.function.BinaryOperator`——纯 JDK，**无新依赖**，不碰文件系统，`simos-util` 仍未依赖任何 simos 模块。
3. **无领域词汇**：对 staged main diff 扫 `hex|region|unit|population|terrain|java.io|java.nio|Files|Path`，**零命中**。
4. **禁手写 `equals`**：扫 `boolean equals|int hashCode` 零命中；`equals` 全部由 record 提供（变异 1 是临时植入、已还原）。
5. **测试输出洁净**：focused 与模块全量两次运行均无 `[WARNING]`、无 SpotBugs/Checkstyle 噪音、无 JVM 告警。
6. **YAGNI**：未加 brief 未要求的 API（无 `toString`、无插值、无缓存、无 `Serializable`）；`segments()`/`events()`
   由 record 生成而非手写覆盖，避免重复代码。
7. **测试非空转复核**：`eventsAtTheSameMomentApplyInInsertionOrder` 的两种排列结果**不同**（0 vs 10），
   故不可能靠"两事件都被施加"蒙混通过；`twoEquivalentButDistinctLambdasMakeSeriesUnequal` 先 `isNotSameAs` 自证前提
   （`capturingAdder()` 捕获局部变量，按 JLS §15.27.2 每次求值都是新实例），前提若塌掉会**先**红在自证那一行；
   `setOnlySeriesWithNullAdditionStillCompareByValue` 反向钉住"null 也能按值相等"。

## 7. 问题与关注点（非阻塞）

1. **`addition` 身份相等是 M2+ 的硬约束**（设计代价，非缺陷）：任何含 `ADD` 事件的序列**必须**用共享的
   `static final` 算子实例构建，否则两个语义完全相同的序列 `equals` 为假，铁律 5 的往返断言会误报。
   本任务已用两条用例把该约束钉住（正反各一），但建议 M2 写 MapSimos/SocialSimos 的 spec 时**明写**这条
   "算子须为共享常量"的调用方约定——否则下游作者会在往返断言上踩坑且难以定位。
2. **`valueAt` 的线性扫**：事件列表非递减，理论上可在 `event.at() > t` 时提前 `break`。**未改**——
   brief 要求逐字实现，且 M1 语义正确性与该优化无关；若 M2 起事件列表可能变长，再作为独立任务优化。
3. **record 规范构造器必然公开**（JLS 要求）：`new SegmentedSeries<>(...)` 与 `of(...)` 同为入口。已按控制器裁决 1
   接受；两条路径共用同一套紧凑构造器守卫，不存在绕过校验的入口（守卫测试覆盖 `of` 路径，构造器路径与之同源）。
4. **`SimosTimestamp` 无非负校验**是 brief"向前恒定延拓"用例（`of(-99)`）成立的前提。本任务**未**改动
   `SimosTimestamp`；若日后有人给它加非负校验，那条用例会转红——这是**有意**的联动，不是脆弱点。

---

## 结论

brief Step 1–6 按序执行：先写失败测试并确认失败原因（五个类型不存在）→ 逐字实现五个类型 → focused 13 绿 →
`spotless:apply` → `clean verify` 128 绿（Spotless/Checkstyle/Surefire/SpotBugs 全过）→ 只暂存两个指定目录、
扫过 `git diff --cached` 后按 brief 原文提交为 `3d314f3`，**未推送**。

**更正（2026-09-16，评审后）**：原文此处写"五轮变异证明四条时间语义与全部构造守卫都有自证"——**这是过度声称**。
五轮变异只证明了四条时间语义 + **构造期**守卫（anchor / 严格升序 / 非递减 / 字段串 / `addition` 身份）的自证；
`valueAt` 的 `t` 守卫当时**不在**其中，因为它的断言 needle 是单字符 `"t"`，删掉守卫后 JDK 的 helpful NPE 消息
依然含 `t`，断言照绿。§8 已修复并补跑变异，现版本（`e193aaf`）该守卫才真正自证。

---

# §8 评审修复报告（2026-09-16，Task 7 fix-1）

- 修复提交：`e193aaf` fix(util): 钉住 valueAt 守卫消息与相等/同刻语义的判别力（M1 Task 7 fix-1）
- 范围：**只改测试文件**。生产代码（`TemporalSeries` / `Segment` / `Event` / `EventMode` / `SegmentedSeries`）
  **一个字节未改**——修完后 `git diff HEAD -- simos-util/src/main/java/` 为空，已核验与 `3d314f3` 逐字节一致。
- 关账门禁：`./mvnw -pl simos-util clean verify` → **BUILD SUCCESS**，**130 测试 / 0 failures / 0 errors**
  （128 → 130，`TemporalSeriesTest` 13 → **15**），Spotless 43 files clean，`BugInstance size is 0` / `Error size is 0`。

## 8.1 修复 1（Important）：`valueAt(null)` 的断言是空转护栏

**改了什么** —— `nullArgumentsAreRejectedWithFieldLevelMessages` 末条由
`.hasMessageContaining("t")` 改为 `.hasMessage("t")`，并写明必须精确匹配的原因：

```java
-        .hasMessageContaining("t");
+        .hasMessage("t");
```

**为什么**（与评审诊断完全一致）：needle 只有一个字符，守不住东西。`Objects.requireNonNull(t, "t")` 的消息
**恰好**就是 `"t"`，故精确匹配是正确且唯一的判别式；而单字符前缀会被 JDK 21 的 helpful NPE 意外命中。

**变异自证（实跑）** —— 删掉 `SegmentedSeries.valueAt` 里的 `Objects.requireNonNull(t, "t")`：

```
./mvnw -pl simos-util -Dtest=TemporalSeriesTest test
  [ERROR] Tests run: 15, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!
  [ERROR] TemporalSeriesTest.nullArgumentsAreRejectedWithFieldLevelMessages
  Expecting message to be:
    "t"
  but was:
    "Cannot read field "tick" because "other" is null"
  [INFO] BUILD FAILURE
```

这条 `but was` 正是评审预判的 helpful NPE 串——它**含 `t`**（`tick` 里的 `t`），
故旧写法 `hasMessageContaining("t")` 在此变异下必然**照绿**，该守卫此前确实没有自证。现已转红。

**还原核验**：`git checkout -- simos-util/src/main/java/io/mosire/simos/util/time/SegmentedSeries.java` 之后
`git diff HEAD --quiet -- simos-util/src/main/java/` 返回 0 → 与 `3d314f3` 逐字节一致。

**其余 7 条 `requireNonNull` 断言经复核不受此问题影响**（评审结论，我复核认同）：删掉那些守卫后，
要么根本不抛异常（`assertThatThrownBy` 直接转红），要么 JDK 内部报的是 `coll` / `other` 而不是
`segments` / `events`（前缀不匹配即转红）。故只改 `t` 一条。

## 8.2 修复 2（Minor 升格）：相等语义只测了 `addition` 一个组件

**改了什么** —— 新增 `seriesDifferingOnlyInSegmentsOrOnlyInEventsAreUnequal()`：两条断言分别只让
`segments` 不同、只让 `events` 不同。此前两条相等用例的两侧 `segments`/`events` 内容**相同**，
一个"丢掉 `segments` 或 `events` 的手写 `equals`"能通过当时全部断言——这是铁律 5 判据本身的覆盖完备性缺口。

**变异自证（两半各跑一次，均为实跑）** —— 临时给 `SegmentedSeries` 加手写 `equals`：

| 变异 | 结果 |
|---|---|
| `equals` 比较 `segments` + `addition`，**丢掉 `events`** | `seriesDifferingOnlyInSegmentsOrOnlyInEventsAreUnequal` 转红（`Failures: 1`），**仅此一条** |
| `equals` 比较 `events` + `addition`，**丢掉 `segments`** | 同一条用例转红（`Failures: 1`），**仅此一条** |

两半都转红 → 新用例对 `segments` 与 `events` **各自**都有判别力，不是只钉住一侧。

## 8.3 修复 3（Minor 升格）：`compareTo` 而非 `equals` 的纪律没被钉住

**改了什么** —— 新增 `theSameInstantIsDecidedByCompareToNotEquals()`：序列侧的时间戳带 `calendarLabel`、
查询侧不带（及反向），断言 `205L` / `200L`。此前文内全部时间戳都由 `SimosTimestamp.of(long)` 构造（无 label），
`compareTo` 与 `equals` 在全部断言下行为等价——用 `equals` 判"同刻"的实现今天全绿，M2 一带 label 就会静默错。

**变异自证（实跑）** —— 把 `valueAt` 的事件判定由 `compareTo` 口径改成 `equals` 口径：
`if (event.at().compareTo(t) <= 0)` → `if (event.at().equals(t) || event.at().compareTo(t) < 0)`：

```
./mvnw -pl simos-util -Dtest=TemporalSeriesTest test
  [ERROR] Tests run: 15, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE!
  [ERROR] TemporalSeriesTest.theSameInstantIsDecidedByCompareToNotEquals
  [INFO] BUILD FAILURE
```

**仅此一条**转红（未带 label 的既有用例在该变异下行为不变，故不受影响），说明新用例精确钉住了
"同刻用 `compareTo`" 这条纪律。还原同上，`git diff HEAD --quiet -- simos-util/src/main/java/` 返回 0。

## 8.4 收尾核验

```
./mvnw -pl simos-util clean verify
  Tests run: 130, Failures: 0, Errors: 0, Skipped: 0      （模块总计，128 → 130）
  TemporalSeriesTest: Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
  Spotless.Java is keeping 43 files clean - 0 needs changes to be clean
  BugInstance size is 0 / Error size is 0
  BUILD SUCCESS
```

提交纪律：`git add` **只**加了测试文件那一个路径
（`simos-util/src/test/java/io/mosire/simos/util/time/TemporalSeriesTest.java`），
`git diff --cached --stat` 为 `1 file changed, 32 insertions(+), 1 deletion(-)`，扫过 staged diff 确认
只含"`hasMessageContaining` → `hasMessage` + 注释"与两个新用例。**未推送**。控制器在改的
`progress.md` 与各 `task-*-brief.md` 一概未入提交。

**未动的两处（评审明确要求）**：`valueAt` 遍历全部事件无提前退出（延后到 M2，且 `baseValueAt` 有 break
这一路径不对称已由评审记档）——本任务**未**顺手优化；生产代码除修复 1 的变异演练（已还原）外**一律未改**。

---

# §9 修复报告（fix round 2，2026-09-16）

- 修复提交：`aaead31` fix(util): 钉住段守卫的同刻判定口径（compareTo 而非 equals）（M1 Task 7 fix-2）
- 触发：fix round 1 重审接受（三项全 ADDRESSED，无新增破坏），另报残余缺口一处，控制器裁定收进本轮。
- 范围：**只加一个测试方法**，其余一律不动。生产代码除本轮变异演练（已还原）外**一行未改**。

## 9.1 缺口是什么

重审者在 `SegmentedSeries.java:80` 指出：`requireStrictlyAscending` 的同刻判定走 `compareTo`，
但**没有任何用例构造"同 tick、不同 `calendarLabel`"的两段**。机理是：

`malformedSeriesAreRejectedAtConstruction` 里现有的同刻用例是 `segment(10, 1L)` / `segment(10, 2L)`，
两者 `calendarLabel` 都是 `null`（`SimosTimestamp.of(long)` 走 `Optional.empty()`），于是
`compareTo == 0` 与 `equals == true` **同时成立**——`compareTo <= 0` 与
`compareTo < 0 || equals`（后者是"用 `equals` 判同刻"的变异形态）**两种写法都会抛**。
该用例因此钉不住这条守卫的**判定口径**，只是恰好也覆盖了它。

这与 §8.3 修的是**同一个缺陷**（判"同刻"必须用 `compareTo`，spec §七 的定义是 `compareTo == 0`），
只是落在段守卫而非事件判定上——事件侧上一轮已钉，段侧漏了。

## 9.2 新增用例

`TemporalSeriesTest` 新增 `sameInstantDifferentLabelsAreStillRejectedAsDuplicateSegments()`：
两段 `ticks` 同为 10 而 label 分别为 `"第 10 日"` / `"第 10 日夜"`，即 `compareTo == 0` 但 `equals == false`，
断言构造期抛 IAE 且消息含 `严格升序`。

**独立成方法**（未塞进 `malformedSeriesAreRejectedAtConstruction`）是刻意的：既有那条无 label 的同刻用例
在两种写法下都会抛，若合并在同一方法里，变异只会让整个方法红而分不清是哪条断言起了作用；独立后
变异红点唯一，见 9.3。

## 9.3 变异演练（逐字输出）

**变异**：把 `SegmentedSeries.requireStrictlyAscending` 的判定由

```java
if (segments.get(i).from().compareTo(segments.get(i - 1).from()) <= 0) {
```

改为

```java
if (segments.get(i).from().compareTo(segments.get(i - 1).from()) < 0
    || segments.get(i).from().equals(segments.get(i - 1).from())) {
```

**命令与输出（逐字）**：

```
$ ./mvnw -pl simos-util -Dtest=TemporalSeriesTest test
[ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.066 s <<< FAILURE! -- in io.mosire.simos.util.time.TemporalSeriesTest
[ERROR] io.mosire.simos.util.time.TemporalSeriesTest.sameInstantDifferentLabelsAreStillRejectedAsDuplicateSegments -- Time elapsed: 0.004 s <<< FAILURE!
[ERROR] Tests run: 16, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

**转红的只有新加的这一条**，正是本轮要的结果。**关键旁证**：既有的无 label 同刻用例
（`malformedSeriesAreRejectedAtConstruction`）在该变异下**保持绿**——因为它的两段既 `compareTo == 0`
也 `equals == true`，两种写法都抛。这恰好说明：

1. 新用例确实补上了既有用例钉不住的判定口径（否则不会是本轮唯一的红点）；
2. 缺口是真实存在的——单靠既有用例，整个测试文件在该变异下会**全绿**，即"用 `equals` 判同刻"的实现
   在 fix round 1 之后仍能蒙混过关。

**还原核验**：

```
$ git checkout -- simos-util/src/main/java/io/mosire/simos/util/time/SegmentedSeries.java
$ git diff e193aaf --quiet -- simos-util/src/main/   # exit=0
（main 与 e193aaf 逐字节一致）
```

## 9.4 验收

```
$ ./mvnw -pl simos-util clean verify
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 ... io.mosire.simos.util.time.TemporalSeriesTest
[INFO] Tests run: 131, Failures: 0, Errors: 0, Skipped: 0      （模块总计，130 → 131）
[INFO] Spotless.Java is keeping 43 files clean - 0 needs changes to be clean, 43 were already clean
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] BUILD SUCCESS
```

用例数变化：`TemporalSeriesTest` **15 → 16**，模块 **130 → 131**。Spotless / Checkstyle / SpotBugs 全绿。

提交纪律：`git add` **只**加了测试文件那一个路径
（`simos-util/src/test/java/io/mosire/simos/util/time/TemporalSeriesTest.java`），
`git diff --cached --stat` 为 `1 file changed, 18 insertions(+)`，扫过 staged diff 确认只含新增的一个 `@Test` 方法与它的注释。
提交后 `git diff HEAD --quiet -- simos-util/` 返回 0。**未推送。**

## 9.5 本轮明确未做的事（按控制器指令）

- **未**动 `TemporalSeriesTest:134-152` 那 7 条 `hasMessageContaining` 字段名 needle——重审者已独立核实它们
  **不是**空转（删掉守卫要么根本不抛、`assertThatThrownBy` 直接红；要么 `List.copyOf(null)` 抛的是**无消息**的 NPE，
  前缀匹配必红）。§8.1 结尾"只改 `t` 一条"的判断成立，本轮复核后仍成立。
- **未**给 `valueAt` 加提前退出（已 park 到 M2）。
- **未**动 `requireNonDecreasing`——`equals` 为真的前提就是 `compareTo == 0`，把 `equals` OR 进去是**恒等**变换，
  该处不存在对应缺口（与段守卫不同：段守卫要求的是**严格**升序，`<= 0` 与 `< 0 || equals` 并不恒等）。

## 9.6 缺口模式小结（供后续任务参考）

同一类缺陷在 T7 出现了**两次**（§8.3 事件侧、§9 段守卫侧），形态都是：
**断言只钉了"会抛"，没钉住"按哪个口径判同刻"**——只要 `compareTo == 0` 与 `equals == true` 在测试数据上
同时成立，两种实现就都绿。教训是：凡涉及"同刻/同一时刻"的守卫，**测试数据必须含一对 `compareTo == 0` 且
`equals == false` 的时间戳**（即同 tick、异 label），否则该守卫的判定口径没有被钉住。
`SimosTimestamp` 的 Javadoc 已经把这条纪律写死，但纪律需要测试数据来兑现。
