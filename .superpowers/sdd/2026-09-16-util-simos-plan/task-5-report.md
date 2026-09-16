# Task 5 报告：外挂时态属性（`info` 包）

- 分支：`feat/m1-util-simos`（就地工作，未建分支/worktree）
- BASE：`f7785cb`（工作树干净）
- 提交：`0f14a95` feat(util): InfoEntry/InfoSystem/InMemoryInfoSystem（M1 Task 5）
- 状态：**DONE**
- 关账门禁：`./mvnw clean verify` → **BUILD SUCCESS**（Spotless + Checkstyle + SpotBugs + Surefire 全绿；
  `simos-util` **89 tests / 0 failures / 0 errors**，`simos-core` 15 tests / 0 failures；各模块 `BugInstance size is 0`）
- 未推送。提交后 `git status --porcelain -uall` 为空、`git diff --stat` 为空、`git diff HEAD` 为空

---

## 1. 实现了什么

brief Step 3 的三个类型**逐字落地**（唯一差异是 `spotless:apply` 对 record 头与中文 Javadoc 的折行，见 §6）。

| 文件 | 内容 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/info/InfoEntry.java` | `record InfoEntry(String key, Object value, TimeRange valid, SubjectId source, Optional<String> note)`。紧凑构造器：key 空白/null → IAE（`InfoEntry.key 不得为空白`）；`value` / `valid` / `source` / `note` 四条 `Objects.requireNonNull`，消息即字段名 |
| `simos-util/src/main/java/io/mosire/simos/util/info/InfoSystem.java` | 接口两方法：`Optional<InfoEntry> get(Address subject, String key, SimosTimestamp at)`、`InfoSystem put(Address subject, InfoEntry entry)`。Javadoc 点明"同一 key 重叠时插入序最后者胜""put 返回新实例" |
| `simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java` | `final` 类，字段 `Map<Address, List<InfoEntry>> bySubject`（私有构造器 + `empty()`）。`get` 线性扫描、不断覆盖 `found`（**后写胜**）、`Optional.ofNullable(found)`；`put` 写时复制（新 `LinkedHashMap` → 新 `ArrayList` → `List.copyOf` → `Map.copyOf` 后交给私有构造器），原实例的 map 与列表一个字节都不碰 |

## 2. 测试了什么、结果如何

`InMemoryInfoSystemTest` 共 **7 条**（brief 的 4 条逐字保留 + 控制器第 2 条要求补的 3 条守卫用例）。

| 用例 | 覆盖 | 来源 |
|---|---|---|
| `readsByKeyAndMoment` | 按 key 取值；同主体多 key 互不串；unknown key 为空；**别的 subject 为空**（`map:Map1` vs `map:Map1:hex.4_3`） | brief |
| `validityWindowIsHalfOpen` | 左闭右开切换点：t=9 归旧条目、**t=10 归新条目** | brief |
| `theLastInsertedOverlappingEntryWins` | 同 key 重叠有效期 → 插入序最后者胜 | brief |
| `putReturnsANewInstanceAndLeavesTheOriginalUntouched` | `put` 前后：原实例 `isEmpty()`、新实例 `isPresent()` | brief |
| `blankOrNullKeyIsRejected` | `" "` 与 `null` 两条 key 臂 → IAE，判据钉 `hasMessageContaining("key")` | 控制器第 2 条（补） |
| `nullPartsAreRejected` | `value` / `valid` / `source` / `note` 四条 `requireNonNull` → **NPE + `.withMessage(字段名)`** | 控制器第 2 条（补） |
| `putRejectsNullSubjectAndEntry` | `put(null, entry)` / `put(HEX, null)` → **NPE + `.withMessage("subject"/"entry")`** | 控制器第 2 条的精神延伸（说明见 §7） |

运行命令与结果：

```
./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test      # exit=0
  surefire: tests="7" errors="0" skipped="0" failures="0"
./mvnw clean verify                                              # exit=0，BUILD SUCCESS
  simos-util 89 tests / 0 failures / 0 errors（82 → 89，净增 7）
```

## 3. TDD 证据（brief Step 2 / Step 4 原命令）

**RED**（生产目录为空时）：

```
./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test      # exit=1
[ERROR] .../InMemoryInfoSystemTest.java:[114,16] cannot find symbol
[ERROR]   symbol:   class InfoEntry
[ERROR] .../InMemoryInfoSystemTest.java:[104,5] cannot find symbol
[ERROR]   symbol:   class InMemoryInfoSystem
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.16.0:testCompile ... Compilation failure
```

与 brief 预期一致（`cannot find symbol`）。

**GREEN**（三类型落地后）：`exit=0`，7/7 通过（见 §2）。

## 4. G13 变异实验（15 条，全部真实删改 + 真实单跑）

**方法**：`/tmp/t5/run_mutations.py` 单文件精确串替换（锚点必须命中且**唯一**，否则脚本中止）；
每条变异后只跑**目标类**：`./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`。
每次运行**先删 `simos-util/target/surefire-reports/`**（避开 T4 评审点名的"陈旧报告"陷阱），
再从 `.txt` 报告与 maven 输出里取**转红用例名 + 失败行号**——所有行号都是**实测读出来的**，不是推算的。
每条变异后从 `cp` 快照整文件恢复，并 `sha256sum` 逐字节复核 + `git diff --stat` 复核（下表"恢复"列）。

变异前快照 == 最终提交内容（提交后复核）：

```
876ed3c40b0ef26716cdaede474f4ecdcb2a30e3ca34d9fceae0b26017f616ba  InfoEntry.java
226aaed348623562a53c9f37f0a14178e6f6a3521715350d7bb2273ec4846b51  InfoSystem.java（未被变异触碰）
84e7386c4813ac8a740e86d10a6cb28fb194e0212a413613c872b64d5f9f759a  InMemoryInfoSystem.java
6b908301b6d798137fe00b555cf12c73d7cc4039b9c542733bca0aa60d5afd98  InMemoryInfoSystemTest.java
```

| # | 变异（删/改哪一处） | 期望转红 | **实测（用例名:行号）** | 计数 | 恢复 |
|---|---|---|---|---|---|
| M1 | `get` 去掉 `entry.key().equals(key)`（条件只剩 `entry.valid().contains(at)`） | `readsByKeyAndMoment` | **`readsByKeyAndMoment:27`**（unknown key 取到了 icon 条目） | Failures: 1 | sha256 OK，diff 空 |
| M2 | `get` 去掉 `entry.valid().contains(at)`（条件只剩 key 匹配） | `validityWindowIsHalfOpen` | **`validityWindowIsHalfOpen:46`**（t=9 返回"新名"而非"旧名"） | Failures: 1 | sha256 OK，diff 空 |
| M3 | **后写胜 → 先写胜**（条件加 `found == null &&`） | `theLastInsertedOverlappingEntryWins` | **`theLastInsertedOverlappingEntryWins:58`**（返回"先写的"） | Failures: 1 | sha256 OK，diff 空 |
| M4 | **复合**：`empty()` 改持可变 `LinkedHashMap` + `put` 就地改它 + `return this` | `putReturnsANewInstanceAndLeavesTheOriginalUntouched` | **`putReturnsANewInstanceAndLeavesTheOriginalUntouched:66`**（报 `Expecting an empty Optional but was containing value: InfoEntry[key=alias, value=甲, …]`——原实例被污染） | Failures: 1 | sha256 OK，diff 空 |
| M5 | **对照**（非复合）：仅 `Map.copyOf(next)` → `next` | —— | **未转红：exit=0，7/7 全绿** | Failures: 0 | sha256 OK，diff 空 |
| M6 | 删 `Objects.requireNonNull(value, "value")` | `nullPartsAreRejected`（value 臂） | **`nullPartsAreRejected:87`**（报 `Expecting code to raise a throwable`——构造成功） | Failures: 1 | sha256 OK，diff 空 |
| M7 | 删 `Objects.requireNonNull(valid, "valid")` | 同上（valid 臂） | **`nullPartsAreRejected:90`** | Failures: 1 | sha256 OK，diff 空 |
| M8 | 删 `Objects.requireNonNull(source, "source")` | 同上（source 臂） | **`nullPartsAreRejected:93`** | Failures: 1 | sha256 OK，diff 空 |
| M9 | 删 `Objects.requireNonNull(note, "note")` | 同上（note 臂） | **`nullPartsAreRejected:96`** | Failures: 1 | sha256 OK，diff 空 |
| M10 | key 校验 `key == null \|\| key.isBlank()` → `key == null`（去掉空白臂） | `blankOrNullKeyIsRejected`（空白臂） | **`blankOrNullKeyIsRejected:75`**（`" "` 未被拒） | Failures: 1 | sha256 OK，diff 空 |
| M11 | 同一行 → `key.isBlank()`（去掉 null 臂） | `blankOrNullKeyIsRejected`（null 臂） | **`blankOrNullKeyIsRejected:79`**（抛 NPE 而非 IAE） | Failures: 1 | sha256 OK，diff 空 |
| M12 | 删 `put` 的 `Objects.requireNonNull(subject, "subject")` | `putRejectsNullSubjectAndEntry`（subject 臂） | **`putRejectsNullSubjectAndEntry:106`**（报 `Expecting message to be: "subject" but was: null`——消息判据起效） | Failures: 1 | sha256 OK，diff 空 |
| M13 | 删 `put` 的 `Objects.requireNonNull(entry, "entry")` | 同上（entry 臂） | **`putRejectsNullSubjectAndEntry:107`** | Failures: 1 | sha256 OK，diff 空 |
| M14 | `Optional.ofNullable(found)` → `Optional.of(found)` | 取值路径的"未命中"分支 | **`readsByKeyAndMoment:31`**、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:66`（NPE） | Errors: 2 | sha256 OK，diff 空 |
| M15 | `bySubject.getOrDefault(subject, List.of())` → `bySubject.get(subject)`（去掉缺省分支） | 同上 | **`readsByKeyAndMoment:32`**、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:66`（NPE `because the return value of "java.util.Map.get(Object)" is null`） | Errors: 2 | sha256 OK，diff 空 |

**没有一条转红是"构建红"**：15 行里 14 行是 `Tests run: 7` 的 surefire 断言失败/错误；M5 是真绿。
变异全程**没有再撞上 Checkstyle 假阳性**——所有变异都保留了 `Objects` / `Map` / `LinkedHashMap` 的其余使用点
（M4 的复合变异特意把 `LinkedHashMap` 留在 `empty()` 里、`Map` 留在字段类型上，就是为了不触发 UnusedImports）。

### 4-A 为什么"put 返回新实例"必须用**复合变异**（M4 vs M5 对照）

控制器预判的坑，本次实测坐实：

- **M5（单点变异）——不转红**：把 `Map.copyOf(next)` 换成直接交 `next`，7/7 全绿。原因：`next` 本身是
  `new LinkedHashMap<>(bySubject)` 的新实例，元素列表也已经 `List.copyOf` 隔离；`empty()` 用的又是不可变
  `Map.of()`。"少写一次 copy" 在这个实现里**观察不到**，拿它当自证成功就是自欺。
- **M4（复合变异）——转红**：只有把"内部结构可变"（`empty()` 持可变 `LinkedHashMap`）与"`put` 就地改、
  `return this`"**同时**做出来，`base` 才会被 `next = base.put(...)` 污染，`:66` 的 `isEmpty()` 判据才响。
  **它判别的是"put 是不是写时复制 + 返回新实例"这一条契约本身**（不可变契约的违反者），而不是某一行 copy
  在不在——这正是 spec §十-D4 要钉的东西（"否则 `equals()` 往返断言无从谈起"）。

### 4-B 消息判据不是装饰（M12 / M13）

删掉 `put` 的 subject 守卫后，`Map.copyOf(next)` 面对 null 键**仍然抛 NPE**（消息为 `null`）——
只钉异常类型的断言在这里会**空转**（T4-M7 的同型陷阱）。实测报文：

```
Expecting message to be:
  "subject"
but was:
  null
```

`.withMessage(...)` 是把这两条守卫变成判别性护栏的**唯一**依据；`InfoEntry` 的四臂同理（M6–M9 的失败报文全是
`Expecting code to raise a throwable`，说明这四处**除了守卫本身没有第二条路径**会抛 NPE）。

## 5. 改动文件清单（提交前 `git diff --cached` 已逐行审过；`git add` 逐个路径，未用 `-A`）

4 个文件，206 行，**全部为新增**（未改任何既有文件、未动 `pom.xml`、未加依赖）：

```
新增  simos-util/src/main/java/io/mosire/simos/util/info/InfoEntry.java            28 行
新增  simos-util/src/main/java/io/mosire/simos/util/info/InfoSystem.java           19 行
新增  simos-util/src/main/java/io/mosire/simos/util/info/InMemoryInfoSystem.java   46 行
新增  simos-util/src/test/java/io/mosire/simos/util/info/InMemoryInfoSystemTest.java  113 行
```

仓库根那个 `2026-09-16-012332-gsimulator.txt` 未触碰（它已被 `.gitignore` 的 `*-gsimulator.txt` 忽略）。

## 6. 与 brief / spec 的偏差

1. **折行**：`InfoEntry` 的 record 头被 google-java-format 拆成两行（5 个组件 + 中文 Javadoc 超宽），
   类注释中段按字符宽重排。CLAUDE.md 明文预期，**非语义偏差**；其余两个生产文件与 brief **逐字一致**。
2. **`InMemoryInfoSystemTest` 的断言折行 2 处**（`icon` 那条、`theLastInserted…` 那条）由 spotless 决定，
   断言内容未变。
3. **新增 3 条用例**（brief 4 → 7）：见 §2 表后三条，属控制器第 2 条要求（外加 `put` 两条守卫，见 §7）。
   **brief 的 4 条用例逐字保留，一条断言未改**；public 签名、字段名、既有断言全未动。
4. **`import` 变化**：测试文件比 brief 多两条静态导入（`assertThatNullPointerException` / `assertThatThrownBy`），
   是补守卫用例所需；brief 未给、也未被 spotless 删。

### 冲突裁决（控制器点名项）

| 冲突面 | 内容 | 裁决 |
|---|---|---|
| brief vs `util-simos-design.md` | **无冲突**。spec §十二 的 `InMemoryInfoSystemTest` 覆盖项（"按 key + 时刻取值；有效区间左闭右开；put 返回新实例且原实例不变"）与 brief 四条用例一一对上；spec §十-D4 正是 brief 那两条偏离（`get` 增 key、`put` 返回新实例）的**授权来源**；spec §十一 的 `List.copyOf` 防御性拷贝约定与 `put` 实现一致 | 按 brief 执行，无需改判 |
| brief/spec vs **总纲草案**（`2026-09-16-simos-master-design.md` §4.6，第 257–265 行） | 总纲草案写的是 `get(Address subject, SimosTimestamp at)` 与 **`void put(...)`**，与本次实现的 `get(…, String key, …)` / 新实例返回**不一致**。brief 已把自己的偏离标注为"对总纲的偏离（spec §十-D4，本节落地）"，spec §十-D4 状态列写着"本 spec 修订" | **spec 优先**：实现按 spec §十-D4 + brief。**注意**：总纲 §4.6 的代码块**尚未回填**这一修订（回填由 Task 11 负责，与 T4 的 `TimeRange` 收紧同批）——本任务不越权改总纲 |

## 7. 自审发现 / 超出控制器第 2 条的补充（点名）

- **多补了 `put` 的两条守卫用例**（`putRejectsNullSubjectAndEntry`）。控制器第 2 条只点名 `InfoEntry` 的
  key + 四条 `requireNonNull`；但 `put` 的两条 `requireNonNull` 与本任务同样"没有 brief 用例"，按 G13 也是
  空转护栏——本任务既然在补守卫，就不该留下一半。**已做变异自证**（M12/M13）：两条都真转红，且都必须
  钉消息才判别（§4-B）。若控制器认为超出范围，删掉该用例即可，生产代码不依赖它。
- **`InMemoryInfoSystem` 没有 `equals`/`hashCode`**（brief 代码如此，未加）。见 §8 顾虑 1——这是**下游**问题，
  本任务不擅自扩签名。
- **越界检查**：未新增依赖（仍只有 jackson-databind + slf4j-api + junit-jupiter + assertj，`pom.xml` 零改动）；
  未加 `package-info.java`；`InfoSystem` 接口未加任何多余方法（无 `remove` / `subjects()` —— 等 T6/T7 真需要再说）；
  生产代码无 setter、无可变字段暴露（`put` 的三层 copy 是唯一写路径）。
- **`get` 是 O(n) 线性扫描**：一个 subject 的条目数在 M1 阶段不构成问题；且"后写胜"语义要求按插入序扫描，
  换成 `HashMap` 会把语义埋掉。不改。
- 测试输出干净：无 stdout 噪音、无 `@Disabled`、无用例间共享可变状态（每条用例自建 `empty()`）。

## 8. 顾虑 / 给控制器的裁决点

1. **`InMemoryInfoSystem` 用身份相等（identity `equals`），T6/T11 的往返断言会撞上它**（**最值得看的一条**）。
   `SimulationState(StateMeta, Map<String, Snapshot>, InfoSystem info)`（spec 第 232 行）把 `InfoSystem` 放进
   状态组件，而铁律 5 的往返判据是 `equals()` 逐字段重建。两个内容等价、分别 `put` 出来的 `InMemoryInfoSystem`
   目前**不 equals**——T6 的 `apply(changeSet, base)` 或 Task 11 的 `RoundTripAssertions` 一旦直接比 `info`
   字段就会红。**三个选项**：(a) 给 `InMemoryInfoSystem` 加值语义 `equals`/`hashCode`（会偏离 brief 逐字，
   且 spec §十一 的"禁手写 equals"只约束 record，需要一次明文裁决）；(b) 往返断言对 `InfoSystem` 走"按地址+key+时刻
   逐条比对"的专用比较器（要 `InfoSystem` 暴露遍历能力，接口要扩）；(c) 把 `info` 从 `SimulationState` 的
   `equals` 里排除（等于承认它不可往返，与铁律 5 的精神相悖）。**建议 T6 之前裁决**，不要等到断言红了再定。
2. **"删除/撤销一条 Info"没有表达能力**：现有接口只有 `put`。要结束一条信息只能新开一条覆盖（如有效期
   收到某个 `to`），要表达"从此没有值"则无写法。spec/brief 均未表态，M1 不做，记录备案。
3. **同一 key 的多条重叠条目的存储代价**：`get` 只返回最后一条，旧条目永久留在列表里。语义上"历史可查"
   是好事（铁律 1 的精神），但若 M4 的存储要把 Info 序列化进快照，列表会单调增长。需不需要"同 key 同有效期
   去重/替换"策略，留给 M4。
4. **`InfoEntry.value` 是裸 `Object`**：record 的 `equals` 依赖 `value` 自身的 `equals`。若 T6/T7 往里放
   `double[]`、`Map` 等无值语义的载体，往返断言会在**没有编译期提示**的情况下静默变脆——建议 M4 序列化那步
   明文限定允许的 value 形态（spec §十三 已把 Jackson 多态处理交给 M4）。
5. **变异方法论的一条复用结论**：本任务的 M5（单点 copy 变异不转红）说明——"不可变契约"类护栏的变异**必须
   把违反方写出来**（复合变异）才判别得了；只删一行 copy 得到的绿，不能记成"护栏有效"。建议后续任务沿用
   "先做单点、不红就升级为复合、并把对照行留在表里"的写法。

---

## Fix round 1（控制器裁决 T5 顾虑 1：`InMemoryInfoSystem` 改 record）

- 提交：`baeeb95` refactor(util): InMemoryInfoSystem 改为 record 以获得值语义（M1 Task 5 fix-1）
- 落在 `f7ac329`（控制器只改文档的提交）之上；只动 `info/` 的两个文件
- 关账门禁：`./mvnw clean verify` → **BUILD SUCCESS**（`simos-util` **93 tests / 0 failures**，89→93；
  `simos-core` 15 / 0；各模块 SpotBugs `BugInstance size is 0`；Spotless 0 待改）
- 未推送；提交后 `git status --porcelain -uall` 为空、`git diff --stat` 为空

### 1. 改了什么（diff 摘要：2 文件 / +69 / −7）

| 文件 | 改动 |
|---|---|
| `InMemoryInfoSystem.java` | `public final class` → `public record InMemoryInfoSystem(Map<Address, List<InfoEntry>> bySubject) implements InfoSystem`；私有构造器 → 紧凑构造器 `bySubject = Map.copyOf(bySubject);`；`empty()` 签名不变；`put` 末句由 `new InMemoryInfoSystem(Map.copyOf(next))` 改为 `new InMemoryInfoSystem(next)`（拷贝移到紧凑构造器，**语义等价**：仍是新实例、仍是防御拷贝）；两条 `requireNonNull(subject/entry)` 原样保留；Javadoc 补"值语义来自 record（spec §十一 禁手写 equals）、往返断言依赖它、`bySubject()` 暴露不可变结构" |
| `InMemoryInfoSystemTest.java` | **纯新增 58 行 / 0 删除**——既有 7 条用例逐字未动（含 brief 的 4 条与首轮补的 3 条守卫用例） |

**没有手写 `equals`/`hashCode`/`toString`**（spec §十一）：`grep -n "public boolean equals\|public int hashCode" simos-util/src/main/java/io/mosire/simos/util/info/` 零命中——值语义完全由 record 组件比较提供。

### 2. 新增的 4 条相等性 / 防御拷贝用例

| 用例 | 行号（断言） | 钉什么 |
|---|---|---|
| `instancesWithSameContentAreEqual` | 127 / 128 | 两条**不同构建路径**（链式 `put` vs 直接传 `HashMap`）得到的内容相同实例 `isEqualTo` + `hasSameHashCodeAs`；先 `isNotSameAs`（126）确认不是同一对象，相等性才有意义 |
| `emptyInstancesAreEqual` | 136 / 137 | 两个 `empty()` 相等且同哈希（126→135 同样先钉非同对象） |
| `instancesWithDifferentContentAreNotEqual` | 144 / 145–146 / 147–150 | 多一条 / 值不同 / 主体不同 → 都不相等（防"恒真 equals"式的过宽） |
| `mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem` | 163 / 164–165 | 构造后改调用方自己的 `HashMap`（改值 + `remove`）不影响本实例——钉紧凑构造器那层 `Map.copyOf` |

### 3. G13 三件套（17 条变异，全部真实删改 + 单跑 `-Dtest=InMemoryInfoSystemTest` + 快照 sha256 复核）

变异前快照（== 提交内容）：

```
876ed3c40b0e…  InfoEntry.java          226aaed34862…  InfoSystem.java
e699f69da349ed300f2d73cb51c33295456ebf996308bcfad1b3acd81830e10e  InMemoryInfoSystem.java
2a5fab6a4813c724c6d1d7af05a65602e1983932cdfd7def5b1ce59d9fd25752  InMemoryInfoSystemTest.java（测试源）
```

| # | 变异 | **实测转红（用例名:行号）** | 计数 | 恢复 |
|---|---|---|---|---|
| M1 | `get` 去掉 key 匹配 | `readsByKeyAndMoment:30` | Failures: 1 | sha256 OK |
| M2 | `get` 去掉 `valid().contains(at)` | `validityWindowIsHalfOpen:49` | Failures: 1 | sha256 OK |
| M3 | 后写胜 → 先写胜 | `theLastInsertedOverlappingEntryWins:61` | Failures: 1 | sha256 OK |
| M4 | **复合**：紧凑构造器不拷贝 + `empty()` 持可变 `LinkedHashMap` + `put` 就地改并 `return this` | `putReturnsANewInstanceAndLeavesTheOriginalUntouched:69`，连带 `mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:163`、`instancesWithDifferentContentAreNotEqual:144` | Failures: 3 | sha256 OK |
| M6 | 删 `requireNonNull(value)` | `nullPartsAreRejected:90` | Failures: 1 | sha256 OK |
| M7 | 删 `requireNonNull(valid)` | `nullPartsAreRejected:93` | Failures: 1 | sha256 OK |
| M8 | 删 `requireNonNull(source)` | `nullPartsAreRejected:96` | Failures: 1 | sha256 OK |
| M9 | 删 `requireNonNull(note)` | `nullPartsAreRejected:99` | Failures: 1 | sha256 OK |
| M10 | key 校验去 isBlank 臂 | `blankOrNullKeyIsRejected:78` | Failures: 1 | sha256 OK |
| M11 | key 校验去 null 臂 | `blankOrNullKeyIsRejected:82` | Failures: 1 | sha256 OK |
| M12 | 删 `put` 的 subject 守卫 | `putRejectsNullSubjectAndEntry:109`（栈帧另含 :108） | Failures: 1 | sha256 OK |
| M13 | 删 `put` 的 entry 守卫 | `putRejectsNullSubjectAndEntry:110` | Failures: 1 | sha256 OK |
| M14 | `Optional.ofNullable` → `Optional.of` | `readsByKeyAndMoment:34`、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:69` | Errors: 2 | sha256 OK |
| M15 | 去掉 `getOrDefault` 缺省分支 | `readsByKeyAndMoment:35`、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:69` | Errors: 2 | sha256 OK |
| **F1** | **record → final class**（构造器与 `implements` 不变，只剩身份相等） | **`instancesWithSameContentAreEqual:127`、`emptyInstancesAreEqual:136`**，另因相等断言连带 `mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:165` | Failures: 3 | sha256 OK |
| **F2** | 互补对照：给 record 塞一个恒真 `equals` | **`instancesWithDifferentContentAreNotEqual:144`** | Failures: 1 | sha256 OK |
| **F3** | 单点去掉紧凑构造器的 `Map.copyOf`（防御拷贝） | **`mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:163`** | Failures: 1 | sha256 OK |

**点名：F1 下不转红的用例** —— `instancesWithDifferentContentAreNotEqual` 在 F1（身份相等）下**不转红**：
身份相等天然满足"不相等"，这条变异抓不到它。它**不是空转**，而是"过宽相等"的镜像守卫，由 **F2**（恒真
`equals`）证明其判别力（F2 下 :144 转红，且只此一条红）。同理 `mutatingTheBackingMapAfterConstruction…`
的**防御拷贝**那一臂由 **F3** 证明（:163 转红），F1 下它的红来自:165 的相等断言，属另一条臂。

**首轮 M5 的对照结论在本轮被推翻（正面）**：首轮"单点去掉 `Map.copyOf` 不转红"说明当时**没有**用例覆盖
构造期拷贝；本轮补了专属用例后，同一个单点变异（= F3）**转红**。也就是说 fix round 不只是把类改成 record，
还顺手把一条原本空转的护栏变成了判别性护栏。

### 4. 两次操作事故（诚实记录，均未污染结论）

1. **变异脚本把测试文件误拷进 `main/java`**（我的 harness bug：`restore()` 只认 main 目录）。首条变异（M1）
   本身是有效的（`readsByKeyAndMoment:30` 真实转红），但 M1 的"恢复"把 `InMemoryInfoSystemTest.java` 写进了
   `src/main/java/io/mosire/simos/util/info/`，此后 13 条全在 `main` 编译测试类而中断。**harness 把这类情况
   一律报成"无 surefire 报告（构建在 test 前中断）"，没有一条被误记成转红**。处置：删掉误入的文件、harness 拆成
   `MAIN`/`TEST` 两个目标目录并加硬断言（"生产目录里绝不允许出现测试类"）、用快照 sha256 + `cmp` 复核 4 个文件
   逐字节一致后，**整张表 17 条全部重跑**（上表均为重跑结果）。
   副作用说明：本轮 `git diff --stat` 恒为 3 行——那是我未提交的 fix 改动本身（相对 `f7ac329`），不是变异残留；
   恢复判据因此改用快照 sha256/`cmp`，而不是"diff 为空"。
2. **M3 首轮变异串拼错**（`if (found == null && if (entry.key()…`），Checkstyle 的解析器直接拒绝该文件
   （`NoViableAltException … InMemoryInfoSystem.java:33:27`），harness 报"构建中断"而非转红。**这是控制器点名的
   "构建红 ≠ 用例红"陷阱的第二个真实样本**（第一个见 §4-B）。改用正确锚点重跑后拿到 `theLastInsertedOverlappingEntryWins:61`。

### 5. 顾虑更新

- **顾虑 1（身份相等）→ 已解除**：record 化后值语义自证（F1/F2），`SimulationState.info` 具备可往返的判据。
- **新增顾虑 6：紧凑构造器只做浅拷贝**（本轮按控制器指令只写 `Map.copyOf`）。`Map.copyOf` 拷贝的是"映射"，
  不拷贝值对象：把**可变 `List`** 直接交给规范构造器（现在是 public API）后，外部改动会穿透进来。探针实测
  （`/tmp/t5/probe/Probe.java`，javac 直编 + 运行）：

  ```
  before      : Optional[甲]
  after clear : Optional.empty        # 调用方 mutable.clear() 之后
  ```

  除了取值被改写，`hashCode()` 也会随外部改动漂移——record 值语义 + 被当 map key 用时是隐患。可达路径只有
  "绕过 `empty()`/`put()` 直接 new"（本仓与已知下游都走两个工厂，`put` 每次 `List.copyOf`），但 T6 的
  `ChangeSet.apply` 若自行拼 `Map` 直接构造就会踩到。**一行可修**：紧凑构造器里补
  `bySubject.replaceAll((k, v) -> List.copyOf(v));`（或改 `Collectors.toUnmodifiableMap`）。**我未擅自加深**
  （指令明写只做 `Map.copyOf`），请控制器裁决是否现在补；补的话 F3 之外再加一条"内层列表防御拷贝"用例。
- 其余顾虑（无"撤销/删除 Info"表达能力、同 key 条目单调增长、`value` 裸 `Object` 的相等脆弱性、总纲 §4.6
  待回填）**不变**。

---

## Fix round 2（控制器裁决 T5 fix-1 顾虑：构造期**深**拷贝）

- 提交：`2630e3f` fix(util): InMemoryInfoSystem 构造期深拷贝内层列表（M1 Task 5 fix-2）
- 落在 `baeeb95`（fix-1）之上；只动 `info/` 的两个文件（2 文件 / +38 / −3）
- 关账门禁：`./mvnw clean verify` → **BUILD SUCCESS**（`simos-util` **95 tests / 0 failures**，93→95；
  `simos-core` 15 / 0；各模块 SpotBugs `BugInstance size is 0`；Spotless 0 待改）
- 未推送；工作树干净；提交内容与变异前快照 sha256 逐字节一致

### 1. 改了什么

紧凑构造器：

```java
  public InMemoryInfoSystem {
    Objects.requireNonNull(bySubject, "bySubject");
    Map<Address, List<InfoEntry>> copy = new LinkedHashMap<>();
    bySubject.forEach((key, entries) -> copy.put(key, List.copyOf(entries)));
    bySubject = Map.copyOf(copy);
  }
```

`put` 的既有写法**未动**（多一次拷贝可接受）。Javadoc 补第二段：`bySubject()` 访问器不设防、防线在构造期、
浅拷贝下 `clear()` 能改掉本实例的值并连带 `hashCode()` 漂移。

### 2. 新增的 2 条用例（既有 11 条逐字未动；本轮测试改动为纯新增 +29 / −0）

| 用例 | 断言行号 | 钉什么 |
|---|---|---|
| `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem` | 180 / 181–182 / 183 | 构造后**清空调用方的 list**：`get` 仍取到原值（180）、`equals` 仍等于独立构建的等值实例（181–182）、`hashCode()` 不漂移（183） |
| `theExposedMapIsImmutable` | 190 / 192 / 194 | 访问器不设防 ⇒ 必须拿不到可变结构：`bySubject().put(...)` 抛 `UnsupportedOperationException`（190）、内层列表 `add(...)` 同样（192）、内容仍为 1 条（194） |

第二条是**我主动加的**（控制器只点名第一条）：控制器指令里"最后那层 `Map.copyOf(copy)`"若不设用例，
本身就是一条空转护栏——访问器是 public API，没有它，"暴露的是不可变结构"这句 Javadoc 无人守卫。
若控制器认为超范围，删掉该用例即可，生产代码不依赖它。

### 3. G13 三件套（20 条变异；控制器点名的 F4 在表首）

方法同前：精确串替换（锚点唯一）→ 单跑 `./mvnw -q -pl simos-util -Dtest=InMemoryInfoSystemTest test`
（每次先删 surefire 报告）→ 读报告取用例名 + 行号 → 从快照整文件恢复 →
**三重复核**：`sha256` 逐字节 + "测试类绝不在 main 目录"硬断言 + `git diff --name-only` 必须恰为预期待改的两个文件。
变异前快照（== 提交内容）：

```
876ed3c40b0e…  InfoEntry.java   226aaed34862…  InfoSystem.java
c8d9af59d5a536fd68482345733ab2663be37c21938e2c18f0b0569e5810f821  InMemoryInfoSystem.java
c5ddfd6c721112e03f41eeb41daf6ed165f36804c86f1ef04afde303630651d9  InMemoryInfoSystemTest.java
```

| # | 变异 | **实测转红（用例名:行号）** | 计数 | 恢复 |
|---|---|---|---|---|
| **F4** | **构造期退回浅拷贝**（整块 → `Map.copyOf(bySubject)`）——控制器点名 | **`mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180`** | Failures: 1 | 三重复核 OK |
| F5 | 只去掉最后那层 `Map.copyOf(copy)` → `copy` | `theExposedMapIsImmutable:190` | Failures: 1 | 三重复核 OK |
| F6 | 逐值拷贝退化为 `copy.putAll(bySubject)` | `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180` | Failures: 1 | 三重复核 OK |
| F7 | 整块构造期拷贝删除（外层也不拷） | `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180`、`mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:164`、`theExposedMapIsImmutable:190` | Failures: 3 | 三重复核 OK |
| M1 | `get` 去掉 key 匹配 | `readsByKeyAndMoment:31` | Failures: 1 | 三重复核 OK |
| M2 | `get` 去掉 `valid().contains(at)` | `validityWindowIsHalfOpen:50` | Failures: 1 | 三重复核 OK |
| M3 | 后写胜 → 先写胜 | `theLastInsertedOverlappingEntryWins:62` | Failures: 1 | 三重复核 OK |
| M4 | 复合：构造期不拷贝 + `empty()` 持可变 map + `put` 就地改并 `return this` | `putReturnsANewInstanceAndLeavesTheOriginalUntouched:70`、`mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:180`、`mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:164`、`theExposedMapIsImmutable:190`、`instancesWithDifferentContentAreNotEqual:145` | Failures: 5 | 三重复核 OK |
| M6–M9 | 删 `InfoEntry` 四条 `requireNonNull`（value/valid/source/note） | `nullPartsAreRejected:91 / :94 / :97 / :100` | Failures: 1 | 三重复核 OK |
| M10 / M11 | key 校验去 isBlank 臂 / 去 null 臂 | `blankOrNullKeyIsRejected:79 / :83` | Failures: 1 | 三重复核 OK |
| M12 / M13 | 删 `put` 的 subject / entry 守卫 | `putRejectsNullSubjectAndEntry:110 / :111` | Failures: 1 | 三重复核 OK |
| M14 | `Optional.ofNullable` → `Optional.of` | `readsByKeyAndMoment:35`、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:70` | Errors: 2 | 三重复核 OK |
| M15 | 去掉 `getOrDefault` 缺省分支 | `readsByKeyAndMoment:36`、`putReturnsANewInstanceAndLeavesTheOriginalUntouched:70` | Errors: 2 | 三重复核 OK |
| F1 | record → final class（保留 `bySubject()` 以保持 API 形状，只剩身份相等） | `instancesWithSameContentAreEqual:128`、`emptyInstancesAreEqual:137`，另连带 `mutatingTheBackingListAfterConstructionDoesNotChangeTheSystem:182`、`mutatingTheBackingMapAfterConstructionDoesNotChangeTheSystem:166`（两条防御拷贝用例的 equals 臂） | Failures: 4 | 三重复核 OK |
| F2 | 互补对照：给 record 塞恒真 `equals` | `instancesWithDifferentContentAreNotEqual:145` | Failures: 1 | 三重复核 OK |
| **M16** | **对照：去掉 `put` 自身的 `List.copyOf`** | **未转红：13/13 全绿** | Failures: 0 | 三重复核 OK |

**20 条里 19 条真转红，1 条（M16）是有意做的"冗余对照"**——见下。

### 4. 点名：一条**冗余**（非空转）的防御

M16（把 `put` 里的 `next.put(subject, List.copyOf(entries))` 改为直接放 `entries`）**不转红**：
构造期深拷贝之后，`put` 自己那次拷贝已观察不到——属 T4 报告 §4.1 同口径的"**行为冗余（可接受）**"，
不是判别性护栏。三轮演进记在这里，免得后人误以为它还在扛事：

| 阶段 | `put` 的 `List.copyOf` | 构造期 copy | 可观测？ |
|---|---|---|---|
| T5 首轮（final class） | 是唯一防线 | 外层 `Map.copyOf`（浅） | 是（但无用例 → 当时空转） |
| fix-1（record） | 唯一防线 | 仍浅 | 是（仍无用例） |
| **fix-2（本轮）** | **冗余** | **深拷贝（真防线）** | **否（M16 实测）** |

是否删掉它属风格取舍（留着是纵深防御、且 `put` 语义自洽），**生产代码本轮未动**——控制器明写"`put` 的既有写法
保持不变即可"。

### 5. 顾虑更新

- **fix-1 的顾虑 6（浅拷贝穿透）→ 已解除**：F4/F5/F6/F7 四条变异分别钉住"逐值拷贝""整体不可变""退回浅拷贝"
  与"整块删除"四个层次，两层防御各有专属用例。
- 无新增顾虑。原 park 项（无"撤销 Info"表达能力、同 key 条目单调增长、`value` 裸 `Object`）不变，属 M4/领域 spec。

---

## Fix round 3（评审 Minor 1 + Minor 3；评审结论：规格符合性 ✅ / 质量通过）

- 提交：`6e4c826` test(util): 补 InMemoryInfoSystem 的 null 守卫自证用例并校正 Javadoc（M1 Task 5 fix-3）
- 落在 `2630e3f`（fix-2）之上；2 文件 / +14 / −2（生产仅 Javadoc 5 行，测试纯新增 11 行）
- 关账门禁：`./mvnw clean verify` → **BUILD SUCCESS**（`simos-util` **96 tests / 0 failures**，95→96；
  `simos-core` 15 / 0；各模块 SpotBugs `BugInstance size is 0`；Spotless 0 待改）
- 未推送；工作树干净；提交内容与变异前快照 sha256 逐字节一致

### 1. Minor 1：`bySubject` 守卫原本**空转**，已补自证

评审实测：删掉紧凑构造器的 `Objects.requireNonNull(bySubject, "bySubject")` 后 13/13 全绿——守卫被下游
`Map.copyOf` 顶包（都抛 NPE，消息不同）。处置按 T3/T4 先例：**保留守卫 + 把判据钉到字段级消息**。

| 项 | 内容 |
|---|---|
| 新用例 | `nullBySubjectIsRejected`（`assertThatNullPointerException().isThrownBy(() -> new InMemoryInfoSystem(null)).withMessage("bySubject")`，断言在 :204/:205） |
| 变异 | 删除紧凑构造器里那行 `Objects.requireNonNull(bySubject, "bySubject");` |
| **实测转红** | **`nullBySubjectIsRejected:204`、`nullBySubjectIsRejected:205`**（`Tests run: 14, Failures: 1, Errors: 0`） |
| 失败报文 | `Expecting message to be: "bySubject" but was: "Cannot invoke "java.util.Map.forEach(java.util.function.BiConsumer)" because "bySubject" is null"`——**恰恰证明"只钉类型不钉消息"就是空转**，钉消息后才有判别力（与 T4-M7、T5-M12 同型，这是本计划第三次撞到） |
| 恢复 | 快照 sha256 逐字节一致 + `git diff --name-only` 恰为两个预期待改文件 |

（该行删除**没有**触发 Checkstyle 假阳性：`Objects` 在 `put` 里仍在用。）

### 2. Minor 3：Javadoc 如实化

原文把 `put` 里的 `List.copyOf` 写成与构造期深拷贝并列的一道防线；fix-2 的 M16 已实测删掉它 13/13 全绿
（不可观测）。改为：

> `bySubject()` 访问器不设防，防线在构造期：逐值 `List.copyOf` 再整体 `Map.copyOf`——**深**拷贝……这是**唯一可观测**的防线
> （删掉它有用例转红）。`put` 里的 `List.copyOf` 是不可观测的**纵深防御**：构造期已深拷贝，删掉它没有用例会转红——
> 留着只为写路径自成一体，别当成承重墙。

（与 fix-2 报告 §4 的"行为冗余"点名同口径；注释里不再声称该行可被观测。）

### 3. Minor 2：按裁决不改代码

`theExposedMapIsImmutable` 的后两条断言（内层列表 `add` 抛 UOE、`hasSize(1)`）只能被复合变异抓住——
评审已记档，本轮不处理，生产代码未动。

### 4. 三轮综述（T5 全量护栏盘账）

| 轮次 | 提交 | 关键变更 | 变异数 / 真转红 |
|---|---|---|---|
| 首轮 | `0f14a95` | `InfoEntry`/`InfoSystem`/`InMemoryInfoSystem`（final class）+ 守卫用例 | 15 / 14（1 条对照不红，促成 fix-2 的用例） |
| fix-1 | `baeeb95` | record 化取值语义 | 17 / 17 |
| fix-2 | `2630e3f` | 构造期深拷贝内层列表 | 20 / 19（1 条对照 = `put` 拷贝冗余） |
| fix-3 | `6e4c826` | `bySubject` 守卫自证 + Javadoc 如实化 | 1 / 1 |

累计被点名并处置的**空转 / 假阳性**：T4-M7 型消息陷阱（首轮 `TimeRange.to`、T5 的 `put` 两臂与本轮的
`bySubject`）、Checkstyle 假阳性（T4-M5）、"单点 copy 变异不转红"的对照结论（首轮 M5 → fix-2 的 F4 补上专属用例）、
以及本轮评审独立发现的 `bySubject` 守卫空转。**T5 的 14 条用例逐条都有可复现的转红记录**。
