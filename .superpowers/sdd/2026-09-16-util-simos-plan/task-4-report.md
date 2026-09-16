# Task 4 报告：时间基础（`SimosTimestamp` / `TimeRange`）

- 分支：`feat/m1-util-simos`（就地工作，未建分支/worktree）
- BASE：`c7d32cf`
- 提交：`f7785cb` feat(util): SimosTimestamp 与 TimeRange（M1 Task 4）
- 状态：DONE
- 关账门禁：`./mvnw clean verify` → **BUILD SUCCESS**（Spotless + Checkstyle + SpotBugs + Surefire 全绿；`simos-util` 82 tests / 0 failures，`simos-core` 15 tests / 0 failures）
- 未推送。工作树 `git status --short` 为空、`git diff --stat` 为空

---

## 1. 实现了什么

brief Step 3 的两个 record，逐字落地（唯一差异是 `spotless:apply` 对中文 Javadoc 的折行，见 §7）：

| 文件 | 内容 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java` | `record SimosTimestamp(long tick, Optional<String> calendarLabel) implements Comparable`。构造器 `requireNonNull(calendarLabel)`；`of(long)` / `of(long, String)` / `plus(long)`（保留 label，负值即回拨，原实例不变）；`compareTo` 只看 `tick`——**排序与 `equals` 的口径不同是有意的**（spec §十一：禁止给这些类型手写 `equals`，往返断言的判据就是它） |
| `simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java` | `record TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to)`。构造器两条 `requireNonNull` + `to.ifPresent(...)` 的**严格晚于**校验（`end.compareTo(from) <= 0` ⇒ IAE）；`since(from)`；`contains(t)` = 左闭 `t.compareTo(from) >= 0` 且 右开 `t.compareTo(to) < 0`，`to` 缺省即无上界 |

`Objects.requireNonNull` 用的是 JDK 默认 NPE（无自定义消息），因为 brief 给的签名如此——null 是"忘了写"，NPE 与"值非法"的 IAE 分属两档，这正是 §2 补的两条用例所钉的。

## 2. 测试了什么、结果如何

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `SimosTimestampTest` | 5 | `compareTo` 只看 tick（含跨 label 的比较、负数 tick）、`plus` 前进/回拨/原实例不变、**同 tick 不同 label：排序相等但不 equals**、**null label 拒收**、`sorted()` 可用 |
| `TimeRangeTest` | 4 | **左闭右开四边界**（9 / 10 / 19 / 20）、`since` 无上界、空区间与倒置区间拒收、**null 的 `from` / `to` 拒收** |
| `SubjectIdTest`（T3 携带项 +1） | 4 | 既有 3 条 + **null namespace / null localId 拒收** |
| `ResolvedSubjectTest`（T3 携带项 +1） | 5 | 既有 4 条 + **null canonicalAddress / null typeName 拒收** |

两条 brief 之外的用例（`nullCalendarLabelIsRejected`、`TimeRangeTest.nullPartsAreRejected`）是控制器第 2 条要求补的 null 分支断言，用 `assertThatNullPointerException()`；`SubjectIdTest` / `ResolvedSubjectTest` 两条是控制器第 3 条的 T3 携带项（详见 §6，**有一处与预期不符，已点名**）。

## 3. TDD 证据

### RED（brief Step 2 原命令）

```
./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test     # exit=1
[ERROR] /root/SimulatorMosire/simos-util/src/test/java/io/mosire/simos/util/time/TimeRangeTest.java:[36,23] cannot find symbol
[ERROR]   symbol:   class TimeRange
[ERROR]   symbol:   variable SimosTimestamp
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.16.0:testCompile ... Compilation failure
```
与 brief 预期一致（`cannot find symbol`；此时 `time/` 生产目录为空）。

### GREEN（同一命令，Step 4）

```
./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest' test     # exit=0
  SimosTimestampTest  Tests run: 5, Failures: 0, Errors: 0
  TimeRangeTest       Tests run: 4, Failures: 0, Errors: 0
```

## 4. G13 变异实验（13 条护栏，全部真实删改 + 真实运行）

方法同 T3：`/tmp/g13/mutate.py` 精确串删（锚点必须命中且唯一，否则退出），跑
`./mvnw -q -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest,SubjectIdTest,ResolvedSubjectTest' test`
（合计 18 条用例），读 surefire 与 maven 输出；每条后从**变异前快照**恢复并 `cmp`。
**全部实验在 `spotless:apply` 之后的最终字节上重跑过**（快照 sha256：`SimosTimestamp.java 7cee040d…`、`TimeRange.java 4341eeb5…`、`SubjectId.java 87967adc…`、`ResolvedSubject.java c7fe6b86…`）。

| # | 变异（删/改哪一处） | 期望转红用例 | 实测 | 恢复 |
|---|---|---|---|---|
| M1 | `compareTo` 改为把 label 纳入比较（同 tick 时比 `calendarLabel.hashCode()`） | `orderingUsesTickOnly` + `equalityStillIncludesTheLabelWhereasOrderingDoesNot` | exit=1，Failures: 2 —— `SimosTimestampTest.orderingUsesTickOnly:14`、`SimosTimestampTest.equalityStillIncludesTheLabelWhereasOrderingDoesNot:30` | cmp OK |
| M2 | `plus` 丢 label（`calendarLabel` → `Optional.empty()`） | `plusAdvancesAndRewindsWhileKeepingTheLabel` | exit=1，Failures: 1 —— `plusAdvancesAndRewindsWhileKeepingTheLabel:22` | cmp OK |
| M3 | 删 `TimeRange` 构造器里的 `to.ifPresent(...)` 整块 | `emptyOrInvertedIntervalIsRejected` | exit=1，Failures: 1 —— `emptyOrInvertedIntervalIsRejected:32` | cmp OK |
| M4a | `contains` 左端 `< 0` → `<= 0`（左闭变左开） | （控制器未点名，补做） | exit=1，Failures: 2 —— `intervalIsHalfOpen:17`、`absentToMeansUnbounded:25` | cmp OK |
| M4b | `contains` 右开 `t.compareTo(end) < 0` → `<= 0` | `intervalIsHalfOpen` | exit=1，Failures: 1 —— `intervalIsHalfOpen:19` | cmp OK |
| M4c | `contains` 无上界分支 `orElse(true)` → `orElse(false)` | `absentToMeansUnbounded` | exit=1，Failures: 1 —— `absentToMeansUnbounded:25` | cmp OK |
| M5 | **中和** `requireNonNull(calendarLabel, …)`（保留 `Objects` 使用） | `nullCalendarLabelIsRejected` | exit=1，Failures: 1 —— `nullCalendarLabelIsRejected:37`（首轮假阳性见 §4-B） | cmp OK |
| M6 | 删 `requireNonNull(from, "from")` | `TimeRangeTest.nullPartsAreRejected`（第 43 行的 `from` 分支） | exit=1，Failures: 1 —— `nullPartsAreRejected:43` | cmp OK |
| M7 | 删 `requireNonNull(to, "to")` | `TimeRangeTest.nullPartsAreRejected`（第 48 行的 `to` 分支） | 首轮 **exit=0 未转红**（空转），补 `withMessage("to")` 后 exit=1，Failures: 1 —— `nullPartsAreRejected:48`（见 §4-A） | cmp OK |
| M8 | 去掉 `SubjectId.namespace` 的 null 臂（`== null \|\|` → `!= null &&`） | `SubjectIdTest.nullPartsAreRejected` | exit=1，Failures: 1 —— `nullPartsAreRejected:31` | cmp OK |
| M9 | 去掉 `SubjectId.localId` 的 null 臂 | `SubjectIdTest.nullPartsAreRejected` | exit=1，Failures: 1 —— `nullPartsAreRejected:34` | cmp OK |
| M10 | 去掉 `ResolvedSubject.canonicalAddress` 的 null 臂 | `ResolvedSubjectTest.nullAddressOrTypeNameIsRejected` | exit=1，Failures: 1 —— `nullAddressOrTypeNameIsRejected:46`（消息判据起效：`Address.parse(null)` 抛的是"地址不得为空"，类型判据抓不到） | cmp OK |
| M11 | 去掉 `ResolvedSubject.typeName` 的 null 臂 | `ResolvedSubjectTest.nullAddressOrTypeNameIsRejected` | exit=1，Failures: 1 —— `nullAddressOrTypeNameIsRejected:47` | cmp OK |

每次变异**只让目标用例转红**，其余用例保持绿（18 条总数不变，说明失败由该行引起、非连带）。
最终恢复确认：四条生产文件与快照 `cmp` 逐字节一致（sha256 同上），提交后 `git diff --stat` 为空、
`git status --short` 为空、`git diff HEAD` 为空 → **工作树 == HEAD == 变异前快照**。

### 4-A M7：首轮是**空转护栏**（本任务实际抓到的一个，已就地修判别性）

删掉 `Objects.requireNonNull(to, "to")` 后，`new TimeRange(SimosTimestamp.of(1), null)` 仍然抛 NPE——
因为下一行 `to.ifPresent(...)` 对 null 解引用同样 NPE，**异常类型无从判别**，brief 要求的
`assertThatNullPointerException()` 因此对"这一行在不在"完全不敏感。

实测两条路径的差别（`/tmp/g13t4/ProbeTo.java`，javac 直编 + 运行）：

```
有守卫   -> java.lang.NullPointerException msg=to
删守卫后 -> java.lang.NullPointerException msg=Cannot invoke "java.util.Optional.ifPresent(...)" because "<parameter2>" is null
```

**处置**：按身份包 M4 的既有 house style，把该断言从"只钉类型"改成"钉类型 + 消息"
（`assertThatNullPointerException().isThrownBy(...).withMessage("to")`），改后 M7 稳定转红（证据见上表）。
`new TimeRange(null, Optional.empty())` 那条不受影响（`from` 为 null 且 `to` 为空时，
`to.ifPresent` 不会执行 ⇒ 删掉 `requireNonNull(from)` 就直接构造成功，M6 实测转红）。

### 4-B M5：首轮 exit=1 是**假阳性**（Checkstyle，不是用例转红）

第一次直接删掉 `Objects.requireNonNull(calendarLabel, "calendarLabel");`，maven exit=1，但**没有任何用例失败**——
逐字读日志才看到真实原因：

```
[ERROR] src/main/java/io/mosire/simos/util/time/SimosTimestamp.java:[3,8] (imports) UnusedImports: Unused import - java.util.Objects.
[ERROR] Failed to execute goal ...maven-checkstyle-plugin:3.6.0:check (checkstyle-check) ... 1 Checkstyle violation
```

删掉唯一使用点后 `import java.util.Objects;` 变成未用，checkstyle 在 `validate` 阶段先把构建打断了。
**这是"变异没跑起来"，不是"护栏转红"**——若只看 exit code 就会把它误记成一次成功的自证。
改成"中和该行但保留 `Objects` 使用"（`requireNonNull(Long.valueOf(tick), "tick")`）后重跑，才拿到
真实的 `nullCalendarLabelIsRejected:37` 转红。
**给控制器的观察**：本仓 `mvn test` 会跑 Checkstyle（`checkstyle-check` 绑在 validate），所以"删一行导致 import 变未用"这类变异会以构建失败的形式伪装成转红；变异实验必须读 surefire 报告而不能只看 exit code。

### 关于 record 自动生成的 `equals`（控制器点名的"删不掉"情形）

`equalityStillIncludesTheLabelWhereasOrderingDoesNot` 的 `isNotEqualTo` 判据是 record 自动生成的
`equals`，语言上无法"删掉"去证明它空转。按控制器给的办法做等效证明：M1 把 `compareTo` 改成也看 label 后，
**该用例立刻转红** ⇒ 它确实在区分"排序口径"与"相等口径"，不是纸面断言。
（该用例同时锁住 §五 的"同 tick 视为同一时刻"与 §十一 的"禁手写 equals"两条约束的交点。）

## 5. 改动文件清单（提交前 `git diff --cached` 已逐行审过；`git add` 逐个路径，未用 `-A`）

6 个文件，199 行：

```
新增  simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java
新增  simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java
新增  simos-util/src/test/java/io/mosire/simos/util/time/SimosTimestampTest.java
新增  simos-util/src/test/java/io/mosire/simos/util/time/TimeRangeTest.java
修改  simos-util/src/test/java/io/mosire/simos/util/identity/SubjectIdTest.java      （+12：null 分支用例）
修改  simos-util/src/test/java/io/mosire/simos/util/identity/ResolvedSubjectTest.java（+13：null 分支用例）
```

生产代码只动 `time/` 两个新文件；`identity/` 包**只加断言、未改一个字节的生产代码**。
仓库根那个 `2026-09-16-012332-gsimulator.txt` 未触碰。

## 6. 携带项：T3 的 4 条 null 断言 —— **实际抛的是 IAE，不是 NPE**（点名）

控制器第 3 条要求为 T3 守卫补 null 用例并预期 NPE。**实测四处全部抛 `IllegalArgumentException`**，
不是 NPE（javac 直编探针 `/tmp/Probe.java` 的真实输出）：

```
new SubjectId(null, "h-0001")              -> java.lang.IllegalArgumentException: SubjectId.namespace 不得为空白
new SubjectId("map.hex", null)             -> java.lang.IllegalArgumentException: SubjectId.localId 不得为空白
new ResolvedSubject(id, null, "Hex")       -> java.lang.IllegalArgumentException: ResolvedSubject.canonicalAddress 不得为空白
new ResolvedSubject(id, addr, null)        -> java.lang.IllegalArgumentException: ResolvedSubject.typeName 不得为空白
```

**判定与处置**：这不是"守卫缺了那一臂"——四条守卫都在，只是 T3 的写法把 null 与空白合并成
一条 IAE（`if (x == null || x.isBlank()) throw new IllegalArgumentException("…不得为空白")`），
与 T4 用 `Objects.requireNonNull` 的 NPE 是**两套不同口径**。按控制器"不要擅改生产代码语义"的约束
（改 NPE 会连带推翻 T3 已提交的 `nullIdIsRejected` 等 IAE 判据），**就地钉实际契约**：

- 断言写成 `assertThatThrownBy(...).isInstanceOf(IllegalArgumentException.class)`；
- 并加 `hasMessageContaining("namespace")` / `("localId")` / `("canonicalAddress")` / `("typeName")`
  —— `canonicalAddress` 那处**必须**钉消息：`Address.parse(null)` 自己也抛 IAE（"地址不得为空"），
  只看类型的断言在 M10 下不会转红（T3 报告 M4 的同型问题，此处是第二次撞到）。

**给控制器的裁决点**：`SubjectId` / `ResolvedSubject` 的 null 究竟该是 IAE 还是 NPE？
两种都自洽（IAE = "值非法"，含 null；NPE = "引用缺失"）。现状是 IAE 且四臂齐全、已被 4 条新用例钉住；
若要统一到 NPE（与 T4 两 record 口径一致），那是一次**生产代码语义变更 + T3 既有断言改判**，
本任务按约束未做，请控制器裁决后另开一轮。此外，控制器第 3 条里 `new ResolvedSubject(null, ...)`
（null id）已由 T3 既有的 `nullIdIsRejected` 覆盖（IAE），本次未重复。

## 7. 与 brief / spec 的偏差

1. **`SimosTimestampTest` 的 `import java.util.List;`**：brief 的测试代码里有这条 import 但从未使用
   （`sorted()` 那里用的是全限定 `java.util.stream.Collectors`）。`spotless:apply`（google-java-format）
   直接删掉了它。属 brief 自带的无用 import，不算语义偏差。
2. **断言增强 3 处**（都是"补判别性"，不改语义）：`nullPartsAreRejected` 的 `withMessage("to")`（§4-A）、
   `SubjectIdTest` / `ResolvedSubjectTest` 四条 null 断言的消息判据（§6）。
3. Javadoc 折行由 `spotless:apply` 决定（CLAUDE.md 明文），`SimosTimestamp` 的类注释按字符宽重排过。

**无 spec 冲突**：spec §五 明文"比较只看 `tick`（`calendarLabel` 仅作展示，同 tick 视为同一时刻）"
↔ brief 的 `compareTo` 一致；spec §十一"`equals` 由 record 提供、禁止手写" ↔ brief 的"equals 含 label"
一致（record 的 `equals` 天然含全部组件）；brief 的两条计划期新增细则里，"`to` 严格晚于 `from`"
是 spec §五/§十-D5 未写死的行为，**brief 收紧了**（Task 11 负责回填 spec），本任务按 brief 执行；
`TimeRange.since(...)` 与 `new TimeRange(from, Optional.of(to))` 的全部下游用法（T5 简报已逐处核对）
都不需要空区间，故收紧不伤下游。

## 8. 自审发现

- **下游一致性**：`SimosTimestamp.of(long)` / `of(long, String)` / `plus(long)` / `compareTo` 与
  `TimeRange.since(SimosTimestamp)` / `contains(SimosTimestamp)` 的签名与 T5 简报
  （`InfoEntry(..., TimeRange valid, ...)`、`entry("alias", "河口渡口", TimeRange.since(SimosTimestamp.of(0)))`）
  逐处对上；`TimeRange.since` 的静态工厂正是 T5 用例大量使用的形态。
- **`compareTo` 与"同刻"判定的交叉**：`contains` 内部用的是 `compareTo`，因此"同 tick 不同 label"
  在区间判定里被判为同一时刻——这正是 §五 要的口径。`TimeRange` 构造期的"严格晚于"也走 `compareTo`，
  所以 `[tick=10, tick=10)` 无论 label 怎么写都会被拒（M3 的 `emptyOrInvertedIntervalIsRejected` 覆盖）。
- **越界检查**：未新增依赖（仍只有 jackson-databind / slf4j-api / junit / assertj）；未加 `package-info.java`；
  未给 record 加任何便捷方法（没加 `SimosTimestamp.before/after`、没加 `TimeRange.bounded/emptyFixed` 之类），
  等 T6/T7 真需要再说。
- **`plus` 的溢出**：`tick + delta` 是裸 `long` 加法，`Long.MAX_VALUE + 1` 会静默回绕。spec/brief
  都没提，M1 阶段不引入 `Math.addExact`（保持 brief 逐字），仅记录。
- 测试输出干净：无 stdout 噪音、无 `@Disabled`、无用例间共享可变状态。

## 9. 顾虑 / 给控制器的裁决点

1. **§6 的 null 口径**（IAE vs NPE）：现状 IAE、四臂齐全、已自证；若要统一到 T4 的 NPE 口径，
   属生产语义变更 + T3 既有断言改判，需控制器裁决。
2. **`TimeRange.to` 严格晚于 `from` 的收紧**：brief 计划期新增细则 1，但**空区间在语义上是否
   永远非法值得再确认一次**——若 T5 的 `InfoEntry.valid` 将来需要"瞬时有效"（`from == to`），
   本规则会把它挡在构造期。T5 简报现有用例不需要，故本任务未放宽。
3. **`SimosTimestamp.tick` 的溢出**（§8）：`plus` 不做 `Math.addExact`，回绕静默。若 M4 的世界时钟
   会有超大 tick 往返，需另定策略。
4. **变异实验必须读 surefire 报告，不能只看 exit code**（§4-B 的假阳性）：建议后续任务的 G13 报告
   都显式给出"转红用例名 + 行号"字段，控制器可据此一眼看出 exit=1 是"用例红"还是"构建红"。
