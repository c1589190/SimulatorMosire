# Task 4 评审报告：时间基础（`SimosTimestamp` / `TimeRange`）

- 评审对象：`f7785cb`（BASE `c7d32cf`，1 commit，6 文件 / 199 行）
- 分支：`feat/m1-util-simos`
- 权威依据：`docs/superpowers/specs/2026-09-16-util-simos-design.md`（§五 / §十-D5 / §十一）> `task-4-brief.md` > `task-4-report.md`
- 评审方式：读源 + 本地真实变异 + 真实跑测 + 边界探针；**未跑全量 `verify`**（按控制器要求），改为按需跑 `-pl simos-util` 的定向门禁
- 评审期间工作树最终状态：`git diff --stat` 空、`git status --short` 空、`git status --porcelain -uall` 空

## 结论

| 项 | 结论 |
|---|---|
| **A. 规格符合性** | **✅** |
| **B. 任务质量** | **通过** |

---

## 一 独立复核的方法与自证

所有变异都在**就地真实修改生产源文件**后跑真实用例，命令固定为：

```
./mvnw -o -pl simos-util -Dtest='SimosTimestampTest,TimeRangeTest,SubjectIdTest,ResolvedSubjectTest' \
       -Dsurefire.failIfNoSpecifiedTests=false test
```

每条变异后从**变异前快照**（`/tmp/t4rev/snap/`，独立于实现者的快照）整文件覆盖恢复，并逐条 `sha256sum` + `git diff --stat` 复核。四条生产文件的 sha256 与报告 §4 自述**逐字节一致**：

```
7cee040dbfa4d3da45342afd961b8a893da6217cbce69e61b780eb97adf22cda  SimosTimestamp.java
4341eeb516fcda1a06bc3c077b60cf75dcede23fdf41222d4279832f6e1ca727  TimeRange.java
87967adc6d68149865fc45a92a74fb3e7f92e90e8cabd55e(见下注)        SubjectId.java
c7fe6b86759915231d700ee64f301bf79fd499295f30b6147a50523b3adfbc28  ResolvedSubject.java
```

> 注：`SubjectId.java` 的完整哈希为 `87967adc6d68149865fc45a92a74fb6ced2733dfd4e203fe7f92e90e8cabd55e`，与报告一致。

**报告自述的 13 条变异，我全部独立复跑了 13 条**（不止抽查 6 条），另加 3 条自设探针。全部实测结果与报告逐条吻合（唯一偏差见 §五-M7）。

### 报告顾虑 3 的核验（"exit=1 是构建红还是用例红"）

**确认实现者后续真的改用了「用例名 + 行号」口径**：§4 表 13 行**每一行**都给了转红用例名与行号，未只写 exit code。我另行独立复现了他的自曝：

- 直接删 `Objects.requireNonNull(calendarLabel, "calendarLabel");` → maven exit=1，但**无任何用例失败**，真实原因是
  `SimosTimestamp.java:[3,8] (imports) UnusedImports: Unused import - java.util.Objects.`，
  `maven-checkstyle-plugin:check (checkstyle-check)` 绑在 `validate`，构建在 surefire 之前就被打断。
  **确认为假阳性**：这是"变异没跑起来"，不是"护栏转红"。
- 副作用值得点名：构建在 test 之前中断时，`target/surefire-reports/` 会**残留上一轮的失败报告**——只看目录会读到陈旧结论。我在复核 M5 时就踩到过这个陷阱（见 §三 的诚实说明）。
- 改为"中和该行但保留 `Objects` 使用"（`Objects.requireNonNull(Long.valueOf(tick), "tick")`）后，拿到真实断言失败 `nullCalendarLabelIsRejected:37`。

---

## 二 A. 规格符合性（逐条）

### 2.1 文件与包（brief `Files:` / spec §二）

| 要求 | 实测 | 判定 |
|---|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java` | 存在，包名 `io.mosire.simos.util.time` | ✅ |
| `simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java` | 存在，同包 | ✅ |
| `simos-util/src/test/java/io/mosire/simos/util/time/SimosTimestampTest.java` | 存在 | ✅ |
| `simos-util/src/test/java/io/mosire/simos/util/time/TimeRangeTest.java` | 存在 | ✅ |

### 2.2 `SimosTimestamp` 组件与签名（spec §五 / brief `Interfaces:`）

| 要求 | 实测 | 判定 |
|---|---|---|
| `record SimosTimestamp(long tick, Optional<String> calendarLabel)` | 逐字一致 | ✅ |
| `implements Comparable<SimosTimestamp>`（spec §五） | 有 | ✅ |
| `static SimosTimestamp of(long)` | 有，`new SimosTimestamp(tick, Optional.empty())` | ✅ |
| `static SimosTimestamp of(long, String)` | 有，`Optional.of(calendarLabel)` | ✅ |
| `SimosTimestamp plus(long delta)` 保留 label、负值即回拨 | 有，`new SimosTimestamp(tick + delta, calendarLabel)` | ✅ |
| `compareTo` 只看 `tick`（spec §五"比较只看 tick"） | `Long.compare(tick, other.tick)` | ✅ |
| 无 setter / 无复写入口（spec §五 / 总纲 §4.9） | 无 setter、无 `withXxx`、无可变字段 | ✅ |
| `Optional` 组件被 `requireNonNull` | 紧凑构造器 `Objects.requireNonNull(calendarLabel, "calendarLabel")` | ✅ |

### 2.3 `TimeRange` 组件与签名（spec §十-D5 / brief）

| 要求 | 实测 | 判定 |
|---|---|---|
| `record TimeRange(SimosTimestamp from, Optional<SimosTimestamp> to)` | 逐字一致 | ✅ |
| `static TimeRange since(SimosTimestamp)` | 有 | ✅ |
| `boolean contains(SimosTimestamp)` | 有 | ✅ |
| 左闭右开 `[from, to)`（spec §十-D5） | 左 `t.compareTo(from) < 0` 返 false；右 `t.compareTo(end) < 0` | ✅ |
| `to` 缺省 = 无上界（brief 细则 1 的语义面） | `to.map(...).orElse(true)` | ✅ |
| `to` 必须严格晚于 `from`，否则构造期 IAE（brief 计划期新增细则 1） | `end.compareTo(from) <= 0` ⇒ IAE，中文消息 | ✅ |
| 两个组件都 `requireNonNull` | `requireNonNull(from, "from")` + `requireNonNull(to, "to")` | ✅ |

### 2.4 异常类型与消息

| 要求 | 实测 | 判定 |
|---|---|---|
| 空/倒置区间抛 `IllegalArgumentException` | 是，消息 `TimeRange 的 to 必须晚于 from（左闭右开区间不得为空）：<from> .. <end>` | ✅ |
| null 组件抛 `NullPointerException`（brief 用 `Objects.requireNonNull`） | `TimeRange(null, …)` → `NPE msg=from`；`TimeRange(…, null)` → `NPE msg=to`（运行时探针实测） | ✅ |
| 消息中文化 | 全中文，与仓内既有风格一致 | ✅ |

### 2.5 record 自动 `equals`/`hashCode`（spec §十一"**禁止**给这些类型手写 `equals`"）

- `grep -rn 'public boolean equals\|public int hashCode\|public String toString' simos-util/src/main/java/` → **零命中**。
- `time/` 包内唯一 `@Override` 是 `SimosTimestamp.compareTo`。
- **未违反 §十一**。`equals` 含 `calendarLabel`、`compareTo` 只看 `tick` 的口径差（brief 细则 2）是**有意**的，且与 spec §五"同 tick 视为同一时刻，判同刻用 `compareTo`"一致。

### 2.6 Javadoc 中文与口径说明

- `SimosTimestamp` 类注释点明：`tick` 是第一序、`calendarLabel` 仅作展示、时间戳与版本正交、**排序与相等口径不同是有意的**、判"同刻"用 `compareTo == 0`、无 setter、推进只经 `plus`。与 spec §五 逐条对得上。✅
- `TimeRange` 类注释点明：左闭右开、`to` 缺省 = 无上界、`to` 必须严格晚于 `from`。与 spec §十-D5 + brief 细则 1 对得上。✅
- `plus` / `since` / `contains` 各有中文方法注释，`contains` 显式指明"同刻判定用 `compareTo`"。✅

### 2.7 与 brief 代码块的差异（逐处核对，**无未披露差异**）

| # | 差异 | 性质 |
|---|---|---|
| 1 | `SimosTimestamp` 类注释折行位置不同（`时间戳与版本正交 （…）` 的接缝空格） | `spotless:apply`（google-java-format）照字符数重排，CLAUDE.md 明文预期。**非语义偏差** |
| 2 | `SimosTimestampTest` 的 `import java.util.List;` 消失 | brief 自带的无用 import（`Collectors` 用的是全限定名），spotless 删除。报告 §7.1 已披露 |
| 3 | `SimosTimestampTest` +`nullCalendarLabelIsRejected`（4→5 用例） | 控制器第 2 条要求的 **T4 范围内自加项**，报告 §2 已披露 |
| 4 | `TimeRangeTest` +`nullPartsAreRejected`（3→4 用例） | 同上，报告 §2 已披露 |
| 5 | `TimeRangeTest.nullPartsAreRejected` 第二臂加 `.withMessage("to")` | 控制器裁决 ② 要求，报告 §4-A 已披露 |
| 6 | `identity/` 两测试文件各 +1 null 用例、断言加 `hasMessageContaining(...)` | **控制器携带项 ③**，见 §五。报告 §5/§6 已披露 |
| 7 | `SimosTimestamp.java` / `TimeRange.java` 生产实现 | 与 brief Step 3 **逐字一致**（除差异 1） |

**未发现任何未披露的改动。** 提交共 6 文件，`git show --stat f7785cb` 与评审包 `review-c7d32cf..f7785cb.diff` 逐条对上；`f7785cb^ == c7d32cf` 确认 BASE 无误。

### 2.8 全局约束

| 约束 | 实测 | 判定 |
|---|---|---|
| 不新增依赖（编译期 jackson-databind + slf4j-api；测试 junit-jupiter + assertj） | `git diff --stat c7d32cf..f7785cb -- '**/pom.xml'` **空**；`simos-util/pom.xml` 无变化 | ✅ |
| 注释/Javadoc 中文、折行交给 google-java-format | 是；`spotless:check` BUILD SUCCESS | ✅ |
| 门禁 | `-pl simos-util spotless:check checkstyle:check` → exit 0（0 violations）；`-pl simos-util spotbugs:check` → exit 0（BugInstance size is 0）；`-pl simos-util clean test` → **82 tests / 0 failures / 0 errors / 0 skipped**；`simos-core` surefire = `AgentLibAvailabilityTest` 15 / 0 | ✅ |
| 提交纪律（不 `git add -A`、不推送） | 无 push 痕迹；工作树干净 | ✅ |
| 护栏自证（纪律条） | 见 §三 | ✅ |

> 82 = AddressParseTest 38 + AddressQuoteTest 9 + AddressTolerantParseTest 15 + QueryResultTest 2 + ResolvedSubjectTest 5 + SubjectIdTest 4 + SimosTimestampTest 5 + TimeRangeTest 4。`surefire-reports` 逐文件核过，与报告自述一致。

---

## 三 B. 任务质量：13 条变异实验的独立复核

**下表每一行的"实测"都是我自己跑出来的**（非转抄报告）。所有转红均为 `Failures`/`Errors` 计数下的**断言失败**，**没有一条是编译失败或 Checkstyle 打断**（除 M5-raw 这条我特意复现的假阳性，已单列）。

| # | 我的变异（与报告等价） | 报告自述转红 | **我的实测转红（用例名 + 行号）** | 恢复 |
|---|---|---|---|---|
| M1 | `compareTo` 同 tick 时改比 `calendarLabel.hashCode()` | `orderingUsesTickOnly:14` + `equalityStill…:30` | **`SimosTimestampTest.orderingUsesTickOnly:14`**、**`SimosTimestampTest.equalityStillIncludesTheLabelWhereasOrderingDoesNot:30`**（Failures: 2, Errors: 0） | cmp OK |
| M2 | `plus` 丢 label（`calendarLabel` → `Optional.empty()`） | `plusAdvances…:22` | **`SimosTimestampTest.plusAdvancesAndRewindsWhileKeepingTheLabel:22`**（Failures: 1） | cmp OK |
| M3 | 删 `to.ifPresent(...)` 整块 | `emptyOrInverted…:32` | **`TimeRangeTest.emptyOrInvertedIntervalIsRejected:32`**（Failures: 1，"Expecting code to raise a throwable"） | cmp OK |
| M4a | `contains` 左端 `t.compareTo(from) < 0` → `<= 0` | `intervalIsHalfOpen:17` + `absentToMeansUnbounded:25` | **`TimeRangeTest.intervalIsHalfOpen:17`**、**`TimeRangeTest.absentToMeansUnbounded:25`**（Failures: 2） | cmp OK |
| M4b | `contains` 右开 `t.compareTo(end) < 0` → `<= 0` | `intervalIsHalfOpen:19` | **`TimeRangeTest.intervalIsHalfOpen:19`**（Failures: 1） | cmp OK |
| M4c | 无上界分支 `.orElse(true)` → `.orElse(false)` | `absentToMeansUnbounded:25` | **`TimeRangeTest.absentToMeansUnbounded:25`**（Failures: 1） | cmp OK |
| M5-raw | **直接删** `requireNonNull(calendarLabel, …)` | 报告自曝：**假阳性**（Checkstyle UnusedImports） | **复现成功**：exit=1 但 `Tests run: 5, Failures: 0` —— `Unused import - java.util.Objects. [UnusedImports]`，`checkstyle-check` 在 validate 打断。**是构建红，不是用例红** ✔ 报告披露属实 | cmp OK |
| M5 | **中和**该行（`Objects.requireNonNull(Long.valueOf(tick), "tick")`） | `nullCalendarLabelIsRejected:37` | **`SimosTimestampTest.nullCalendarLabelIsRejected:37`**（Failures: 1，"Expecting code to raise a throwable"） | cmp OK |
| M6 | 删 `requireNonNull(from, "from")` | `nullPartsAreRejected:43` | **`TimeRangeTest.nullPartsAreRejected:43`**（Failures: 1） | cmp OK |
| M7 | 删 `requireNonNull(to, "to")` | `nullPartsAreRejected:48` | **`TimeRangeTest.nullPartsAreRejected:47`**（Failures: 1）—— 行号见 §五-M7 | cmp OK |
| M8 | 去掉 `SubjectId.namespace` 的 null 臂 | `SubjectIdTest.nullPartsAreRejected:31` | **`SubjectIdTest.nullPartsAreRejected:31`**（Failures: 1） | cmp OK |
| M9 | 去掉 `SubjectId.localId` 的 null 臂 | `SubjectIdTest.nullPartsAreRejected:34` | **`SubjectIdTest.nullPartsAreRejected:34`**（Failures: 1） | cmp OK |
| M10 | 去掉 `ResolvedSubject.canonicalAddress` 的 null 臂 | `nullAddressOrTypeNameIsRejected:46` | **`ResolvedSubjectTest.nullAddressOrTypeNameIsRejected:46`**（Failures: 1）—— 断言失败报文正是 `Expecting throwable message: "地址不得为空" to contain: "canonicalAddress"`，**证明消息判据确有判别力** | cmp OK |
| M11 | 去掉 `ResolvedSubject.typeName` 的 null 臂 | `nullAddressOrTypeNameIsRejected:47` | **`ResolvedSubjectTest.nullAddressOrTypeNameIsRejected:47`**（Failures: 1） | cmp OK |

**自设探针（报告未做，用于回答"某条断言是否空转"）**

| 探针 | 变异 | 实测转红 | 结论 |
|---|---|---|---|
| A | `compareTo` 恒 `return 0;`（自然序退化为恒等） | `SimosTimestampTest.orderingUsesTickOnly:15`、**`SimosTimestampTest.sortingWorks:47`**、`TimeRangeTest.absentToMeansUnbounded:27`、`TimeRangeTest.intervalIsHalfOpen`（构造期 IAE 逃逸成 Error） | **`sortingWorks` 有判别力**，不是纸面断言 |
| B | `to.ifPresent` 判据 `<= 0` → `== 0`（只拒空区间、不拒倒置） | **`TimeRangeTest.emptyOrInvertedIntervalIsRejected:35`** | 倒置那半条断言**独立有判别力**，不是被空区间那半条顺带覆盖 |
| C | 给 `SimosTimestamp` **手写** `equals`（只比 tick）——即违反 spec §十一 | **`SimosTimestampTest.equalityStillIncludesTheLabelWhereasOrderingDoesNot:31`** | §十一"禁手写 `equals`"这条**在测试层可见**；报告用 M1 做的"等效证明"成立 |

### RED/GREEN 证据复核（TDD）

- **RED 独立复现**：把两个生产文件临时移出后跑 brief Step 2 原命令 → `COMPILATION ERROR: cannot find symbol … class/variable SimosTimestamp`，与 brief 预期、报告 §3 的 `cannot find symbol` 一致。（跑完由 `trap` 无条件还原，`sha256` 复核通过。）
- **GREEN**：恢复后 `Tests run: 5 / 4, Failures: 0, Errors: 0`。

### 复核期间的一次**我自己的**操作失误（诚实记录）

我在 M2 的恢复上漏了一步（用 `do.sh` 跑 M3 时，`do.sh` 只恢复它自己那条命令的目标文件 `TimeRange.java`，未恢复 `SimosTimestamp.java`），导致 M3 首轮混入了 M2 的污染（多出一条 `plusAdvancesAndRewindsWhileKeepingTheLabel:22`）。我在两分钟内发现并**整树恢复后重跑了 M3**，得到干净的 `emptyOrInvertedIntervalIsRejected:32`。**报告的表里没有这个问题**——它的每条都 `cmp` 过快照。记录在此，是为了说明"恢复并确认 `git diff --stat` 为空"这一步确实管用，不是走过场。

---

## 四 空转护栏（hollow guardrail）单列一节

**结论：没有发现"真空转"（缺陷级）的护栏；只有 2 处"行为冗余"（可接受，点名如下）。**

### 4.1 行为冗余（可接受，点名）

1. **`SimosTimestampTest.java:24`** —— `assertThat(t).isEqualTo(SimosTimestamp.of(10, "第 10 日")); // 原实例不变`
   - **空转**：record 的组件是 final 字段，`plus` 返回新实例，`t` 在 21 行之后不可能被改写。我在 13 条变异 + 3 条探针里从未见它转红，也想不出任何能让它**单独**转红的变异（连"`equals` 手写为引用相等"都会先在 22 行翻车）。
   - **定性**：行为冗余（等价于"Java 语义保证"），保留有文档价值（钉住"不加 setter / 不原地改"的纪律），**不构成缺陷**。
2. **`SimosTimestampTest.java:30`** —— `assertThat(SimosTimestamp.of(3)).isEqualByComparingTo(SimosTimestamp.of(3, "第 3 日"))`
   - 与同文件 14 行（`orderingUsesTickOnly`）**同型重复**；M1 下两条一起红。该用例的**判别力实际落在 31 行**（`isNotEqualTo`，探针 C 证实独有）。
   - **定性**：行为冗余（跨用例重复），用例本身不空转。

### 4.2 已修复的空转（报告 §4-A，我独立确认为**真**，且修复**有效**）

`TimeRangeTest` 第二臂原本只写 `assertThatNullPointerException().isThrownBy(…)`：删掉 `requireNonNull(to, "to")` 后，下一行 `to.ifPresent(...)` 对 null 解引用**同样抛 NPE**，异常类型无从判别 ⇒ 该断言对"这一行在不在"完全不敏感，**是真真空转**。
加上 `.withMessage("to")` 后，M7 稳定转红（实测报文：`Expecting message to be: "to" but was: "Cannot invoke "java.util.Optional.ifPresent(...)" because "to" is null`）。**处置正确、口径与身份包 T3-M4 先例一致。**

### 4.3 未被覆盖但 brief 也未要求的路径（非空转，仅备案）

- `contains(null)`：运行时 NPE（`Cannot invoke …SimosTimestamp.compareTo… because "t" is null`）。brief 未要求守卫。
- `SimosTimestamp.of(tick, null)`：`Optional.of(null)` 抛**无消息 NPE**（`msg=null`）；而 `new SimosTimestamp(tick, null)` 抛 `NPE("calendarLabel")`。见发现 1。
- `compareTo(null)` / `since(null)`：NPE，未单独设用例；`since` 走的是同一个 `from` 守卫（M6 已覆盖）。
- `plus` 溢出：`Long.MAX_VALUE + 1` 静默回绕为 `-9223372036854775808`（探针实测）。spec/brief 均未表态，报告 §8/§9.3 已自曝并留给 M4。

---

## 五 边界与一致性核查

| 检查项 | 实测结果 | 判定 |
|---|---|---|
| record 自动 `equals`/`hashCode`，未手写（spec §十一） | 全仓 `simos-util/src/main` 零手写；探针 C 证明该约束测试可见 | ✅ |
| `contains(null)` | NPE（JDK 消息），brief 未要求守卫 | ✅ 无偏差 |
| `TimeRange(t10, Optional.of(t10))` 同刻 | IAE，消息含 from/to 实际值 | ✅ |
| `TimeRange(t20, Optional.of(t10))` 倒置 | IAE | ✅ |
| `TimeRange(t10, Optional.of(t10, "第 10 日"))` 同刻不同 label | **也 IAE** —— 走 `compareTo`，与 spec §五"同 tick 视为同一时刻"口径**一致**（同刻就是同刻，label 不参与） | ✅ 一致性正确 |
| `contains` 上界为"同刻不同 label" | `contains(of(20))` = `false`（右开，走 `compareTo`） | ✅ 与 spec §五一致 |
| `SimosTimestamp.of(tick, null)` | NPE（无消息，来自 `Optional.of`） | ⚠ 见发现 1（非偏差） |
| `new SimosTimestamp(tick, null)` | NPE("calendarLabel") | ✅ |
| `Optional` 组件 `requireNonNull` | `calendarLabel`、`to` 均有；`from` 非 Optional 也已守卫 | ✅ |
| `TimeRange.since(null)` | NPE("from") | ✅ |
| 无 setter / 无多余便捷方法（未加 `before/after`、`bounded/emptyFixed`） | 确认无 | ✅ 无越界 |
| 无 `package-info.java` 新增 | 确认无 | ✅ |
| 提交未夹带他物（`git show --stat`） | 6 文件，与 diff 包一致；根目录 `2026-09-16-012332-gsimulator.txt` 未入库（`.gitignore` 有 `*-gsimulator.txt`） | ✅ |

### 发现清单（对报告与交付物的意见）

- **[Minor]** `SimosTimestamp.java:25`（`of(long, String)`）— 传 null 时 `Optional.of` 抛**无消息 NPE**（实测 `msg=null`），而 canonical 构造器抛 `NPE("calendarLabel")`；静态工厂路径绕过了本任务在别处坚持的"字段级消息"纪律。brief 逐字如此，**非偏差**，但值得在 Task 11 回填 spec 时一并裁量。
- **[Minor]** `task-4-report.md` §4 表 M7 行 — 转红行号记作 `nullPartsAreRejected:48`，实测 surefire 把失败帧归于 **`TimeRangeTest.java:47`**（`withMessage` 在 48 行，AssertJ 归因于断言链起始的 `isThrownBy`）。结论（断言失败、非构建失败）不受影响，仅行号口径差 1 行。
- **[Minor]** `SimosTimestampTest.java:24` 与 `:30` — 两处行为冗余断言，见 §4.1。不构成缺陷。
- **[Minor]** `task-4-report.md` §9.1 仍把"null 该是 IAE 还是 NPE"列为待控制器裁决的开放点 —— 控制器裁决 ① 已判定**保持 IAE**，该请求已过期；交付物本身已满足裁决，无需动作。

**Critical：无。Important：无。**

---

## 六 携带项（控制器裁决 ③）单列判定

本项**不计入 T4 规格偏差**，单独成节。

| 项 | 内容 | 判定 |
|---|---|---|
| 范围 | `identity/` 两测试文件的 4 条 null 断言（`SubjectIdTest.nullPartsAreRejected` ×2 臂、`ResolvedSubjectTest.nullAddressOrTypeNameIsRejected` ×2 臂） | 属控制器携带项，**不是** T4 brief 偏差 ✅ |
| 生产代码是否被改语义 | **没有**。`SubjectId` / `ResolvedSubject` 的 null 分支保持 `IllegalArgumentException`，四臂齐全，未被改为 NPE | ✅ 符合裁决 ① |
| 断言是否钉**字段级消息** | 四条全部 `hasMessageContaining("namespace"/"localId"/"canonicalAddress"/"typeName")` | ✅ 符合裁决 ① 的显式要求 |
| `canonicalAddress` 那条是否真的不再空转 | **是**。M10 独立复核：去掉 null 臂后，`AddressParser.parse(null)` 抛同型 IAE（`"地址不得为空"`）；**只钉类型的话不会转红**，钉消息后转红 | ✅ 裁决 ① 的载重判断正确 |
| T4 范围内的自加项 | `time/` 下 `nullCalendarLabelIsRejected`、`nullPartsAreRejected` | 属 T4 范围，已按 T4 口径计入（§二 2.7 差异 3/4） ✅ |
| 报告是否把它当成 T4 偏差 | **没有**，报告 §6 单列成节并点名 | ✅ |

M8/M9/M10/M11 四条变异证明这 4 条携带项断言**全部有判别力**，不存在空转。

---

## 七 对控制器三条裁决的意见

**无异议。三条裁决的载重判断均经独立实验证实：**

- **裁决 ①**（identity 包 null 分支保持 IAE + `canonicalAddress` 必须钉字段级消息）：核实成立。M10 证明了"只钉类型 = 空转"这一判断是**真的**（`AddressParser.parse(null)` 抛同型 IAE），也证明了钉消息后护栏生效。
- **裁决 ②**（保留 `requireNonNull(to, "to")` + `.withMessage("to")` 钉判别性）：核实成立，且是**必要**的——M7 证明不加消息时该护栏是真真空转（邻行 `to.ifPresent` 兜出同型 NPE）。
- **裁决 ③**（携带项归属划分）：核实成立，归属划分与实测的断言判别力一致。

**唯一想提请注意的是一个过期项**（非裁决内容本身）：报告 §9.1 仍在向控制器征求"IAE vs NPE"的裁决，而裁决 ① 已给出答案（保持 IAE）。交付物已满足，无需新动作——只是后续任务的报告模板里，"已裁决点"宜直接从 spec 的 §〇 已裁决记录表读取，避免重复发起。

---

## 八 评审期间的仓库状态自证

```
$ git rev-parse HEAD
f7785cb2b8c72b5da97f150dfe8ba6360adc85e4
$ git rev-parse f7785cb^
c7d32cf53a192958a138093152a72d0540154c18          # == BASE，评审包范围正确

$ git diff --stat        →  (空)
$ git status --short     →  (空)
$ git status --porcelain -uall → (空)

四条生产文件 sha256（评审结束时，与变异前快照逐字节一致）：
7cee040d…  SimosTimestamp.java      4341eeb5…  TimeRange.java
87967adc…  SubjectId.java           c7fe6b86…  ResolvedSubject.java
```

**所有变异已恢复，工作树 == HEAD == 变异前快照。**
