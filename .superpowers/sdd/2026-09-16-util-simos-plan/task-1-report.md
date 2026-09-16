# Task 1 报告：地址段类型与 canonical 渲染

- 分支：`feat/m1-util-simos`
- 提交：`b40b28f` — `feat(util): 地址段类型与按需加引的 canonical 渲染（M1 Task 1）`
- 状态：**DONE_WITH_CONCERNS**（一处被测试强制的实现偏离，见"自审发现"；一处需后续裁决的往返缺口，见"遗留顾虑"）

## 实现了什么

`simos-util/src/main/java/io/mosire/simos/util/address/` 下 6 个新文件：

| 文件 | 内容 |
|---|---|
| `AddressText.java` | 包内可见词法工具：`isBareWord` / `quote` / `quoteIfNeeded`（不对外暴露） |
| `AddressSegment.java` | `sealed interface AddressSegment permits Namespace, Entity, Index, Property`，唯一方法 `String canonical()` |
| `Namespace.java` | 第 1 段，构造期要求裸词 |
| `Entity.java` | `(Optional<String> kind, String name)` + 工厂 `of(name)` / `of(kind, name)`；kind 非缺省时必须裸词 |
| `Index.java` | `(List<Integer> coords)`，`List.copyOf` 防御拷贝，空列表构造期抛 IAE |
| `Property.java` | 第 ≥3 段裸词，构造期校验 |

`equals`/`hashCode`/`toString` 全部由 record 提供，无手写。未新增任何依赖（`simos-util` 仍只有 jackson-databind + slf4j-api）。`AddressText` 保持包内可见。

## 测了什么与结果

`AddressQuoteTest`（6 用例，包内可见类、英文方法名、中文 Javadoc，沿用 M0 风格）：

- `bareWordNamesAreNotQuoted`：`hex.4_3`、`region.Nation.区域A` 不加引
- `namesWithStructuralCharactersAreQuoted`：`:` / `[ ]` / 首尾空白 / 空串四例加引
- `quoteCharacterIsDoubled`：`A"B` → `region."A""B"`
- `kindlessDottedNameIsQuoted`：缺 kind 且含 `.` 加引；`Map1` 不加引
- `kindMustBeBareWord`：kind 合法/缺省/非法（含 `.`、含空白）四断言
- `namespaceIndexAndPropertyRenderCanonically`：`map`、`[4,3]`、`[7]`、`population_growth` + 两处 IAE

## TDD 证据

### RED（Step 2）

命令：`./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test`

输出摘要：

```
[ERROR] COMPILATION ERROR :
[ERROR] .../AddressQuoteTest.java:[14,16] cannot find symbol
[ERROR]   symbol:   variable Entity
[ERROR] .../AddressQuoteTest.java:[48,20] cannot find symbol
[ERROR]   symbol:   class Namespace
[ERROR] ... [ERROR] -> [Help 1]
EXIT=1
```

共 38 处 `cannot find symbol`（`Entity` / `Namespace` / `Index` / `Property`），0 测试执行。为什么这个失败是预期的：本任务的全部产物就是这些新类型；Java 里"给尚不存在的类型写第一个测试"的第一个红灯必然是编译错误，编译不过则一个用例也不会跑（surefire 未启动），换言之红灯来自"行为未实现"而非"环境问题"。

### 中途 RED 2（计划原文实现的忠实复现，额外证据）

把 brief Step 3 的代码**逐字**落盘后，编译通过但：

```
[ERROR] Tests run: 6, Failures: 2, Errors: 0, Skipped: 0
[ERROR]   AddressQuoteTest.kindMustBeBareWord:43
Expecting code to raise a throwable.
[ERROR]   AddressQuoteTest.namespaceIndexAndPropertyRenderCanonically:53
Expecting code to raise a throwable.
EXIT=1
```

即 `Entity.of("a b", "x")` 与 `new Property("a b")` 未抛异常。原因：计划给的 `isBareWord` 只判 `!s.equals(s.strip())`（仅首尾空白），而 `' '` 不在 `: . [ ] "` 的 switch 里，于是内部含空白的字符串被判为裸词。这证明**计划自己的 Step 4 验收（6 用例全过）与该计划代码互相矛盾**，详见"自审发现"。

### GREEN（Step 4）

命令：`./mvnw -q -pl simos-util -Dtest=AddressQuoteTest test` → `EXIT=0`

非静默复跑的输出：

```
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.616 s -- in io.mosire.simos.util.address.AddressQuoteTest
[INFO] BUILD SUCCESS
```

全模块门禁（额外自证，超出 brief 要求）：`./mvnw -q -pl simos-util verify` → `EXIT=0`（Spotless + Checkstyle + SpotBugs + Surefire 全绿；`target/spotbugsXml.xml` 于 17:58 重新生成，0 条 `BugInstance`）。`./mvnw -q spotless:apply` 已跑，格式改动只有 google-java-format 的折行（如 `AddressQuoteTest` 第 33 行合行、`Namespace`/`Property` 的短 Javadoc 收成一行）。

## 改动文件

提交 `b40b28f`，`git diff --cached --stat` 确认**恰好 7 个文件、197 insertions、无删除**：

```
simos-util/src/main/java/io/mosire/simos/util/address/AddressText.java      | 49 +
simos-util/src/main/java/io/mosire/simos/util/address/AddressSegment.java   |  8 +
simos-util/src/main/java/io/mosire/simos/util/address/Namespace.java        | 16 +
simos-util/src/main/java/io/mosire/simos/util/address/Entity.java           | 34 +
simos-util/src/main/java/io/mosire/simos/util/address/Index.java            | 20 +
simos-util/src/main/java/io/mosire/simos/util/address/Property.java         | 16 +
simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java | 54 +
```

未 `git add -A`；未推送；`progress.md` 未改（我的指令未授权，留给控制器）。

## 自审发现

**1（必须裁决/知会）——`isBareWord` 有意偏离计划代码。** 计划给的实现只拒绝首尾空白，与计划自己的测试（第 43/53 行要求含空白的 kind / Property 被拒）和 spec §3.2（"**kind 必须是裸词**（不含 `: . [ ] "` **与空白**，非空）"）都不符。三份材料里两份一致、只有代码片段不一致，故判定代码片段是转录缺陷，改成拒绝**任何**空白（`Character.isWhitespace`，语义与 `strip()` 同源）：

```java
for (int i = 0; i < s.length(); i++) {
  char c = s.charAt(i);
  if (Character.isWhitespace(c)) {
    return false;
  }
  ...
```

旁证：计划 Task 3 的解析器 `componentText` 直接复用 `AddressText.isBareWord` 来判定"未加引的名字组件"，其报错文案是"名字组件必须加引号（含 : [ ] " 或空白）"——说明下游期望的正是"裸词不含任何空白"。若沿用计划原写法，那个解析器就会接受含空格的未加引组件，与它自己的文案矛盾。连带把三处异常文案的"无首尾空白"改成"无空白"（规则变了，文案必须跟着变，否则文案在骗人）。

其余均为 brief 原文，未动：`quote` 的 `'"' + s.replace("\"", "\"\"") + '"'` 保持原样（`char + String + char` 拼接），测试未做任何削弱——测试文件与 brief 逐字一致，只被 spotless 合了一行。

**2（已核）——四条段类型的契约与 spec §3.2 对齐。** `Entity` 的 `kind` 是 `Optional`（Human 形式缺类型词），`name` 不设字符限制（§3.3"name 允许任意字符"），`Index` 空列表即抛（§3.5 的 `[]` 非法）。段类型上没有 `equals` 手写。

**3（已核）——防御性拷贝与 null 处理。** `Index` 用 `List.copyOf`（null 元素/ null 列表由它抛 NPE，不再另设 `requireNonNull`）；`Entity` 对 `kind`/`name` 显式 `requireNonNull`，`AddressText.isBareWord(null)` 返回 false 而非抛 NPE，故 `new Namespace(null)` 抛的是带原文的 IAE。

**4（已核）——不多不少。** `AddressText` 的 `canonical()` 无反向解析、`Address`/`AddressParser` 属 Task 2，本任务未越界创建；`address` 包里就是本任务新建的 6 个文件（唯一的 `package-info.java` 在父包 `io.mosire.simos.util` 下，与本次无关、未触碰）。

## 遗留顾虑

**A（提请裁决，会打到 Task 3）——含内部空白的 name 存在 canonical→parse 往返缺口。** 收紧 `isBareWord` 后，`Entity.of("region", "a b").canonical()` 产出 `region.a b`（§3.4 条件 2 只对"空串/首尾空白"加引，内部空白不是加引触发条件），而 Task 3 的 `componentText` 会因 `a b` 不是裸词而抛 IAE——即 `parse(canonical(x))` 失败。这是 spec §3.4 条件 2 的措辞（"首尾含空白"）与"裸词不含空白"之间的缝隙，两种修法各有代价：

- 把条件 2 改为"含空白即加引"：往返闭合，但 canonical 会多一处引号（`region."a b"`），且与"三条之外一律不加引"的字面表述冲突；
- 保持条件 2 不动、放宽解析器：等于放弃"裸词"约束，Task 1 的测试就过不去。

本任务无权改 §3.4（Task 1 的测试也没覆盖该情形，冻结样例 §3.6 里没有含内部空白的名字，故两条路都不影响既有样例）。建议控制器在 Task 3 开写前裁决，并在 spec §3.4 与 §十二测试清单里补一条含空白 name 的往返用例。

**B（信息）——`~/.m2` 依赖。** 本次未触碰任何 `pom.xml`，enforcer 白名单未受影响；`verify` 全绿说明 SNAPSHOT 依赖在 `simos-util` 侧无影响（该模块本就不依赖 AgentLibMosire）。

---

# 修复轮 1（控制器裁决：§3.4 条件 2 改为"含任意空白"）

- 提交：`0f8d5ff` — `fix(util): 内部空白同样加引（M1 Task 1 修复轮 1）`（**新开提交，未 amend `b40b28f`**）
- 依据：控制器裁决 + spec `docs/superpowers/specs/2026-09-16-util-simos-design.md` §3.4 表格行 2（已由控制器就地改为"为空串，或含空白（**任意位置**……）"）；上一轮"遗留顾虑 A"由此关闭。

## 改了什么

| 文件 | 改动 |
|---|---|
| `AddressText.java`（`quoteIfNeeded`） | 条件 2 判定 `!name.equals(name.strip())` → `name.chars().anyMatch(Character::isWhitespace)`；Javadoc 条件描述同步为"（条件 2：为空串或含任意空白）" |
| `AddressQuoteTest.java`（`namesWithStructuralCharactersAreQuoted`） | 新增**一条**断言：`Entity.of("region", "A B").canonical()` == `region."A B"`（内部空白） |

未动 `isBareWord`（上一轮已是"不含任何空白"），未加 `parse` 往返用例（`Address.parse` 属 Task 2）。`git diff --cached --stat`：2 文件、3 insertions、2 deletions。

## TDD 证据（本轮）

**RED（先加断言，后改实现）**

命令：`./mvnw -pl simos-util -Dtest=AddressQuoteTest test`

```
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.822 s <<< FAILURE! -- in io.mosire.simos.util.address.AddressQuoteTest
expected: "region."A B""
 but was: "region.A B"
[ERROR]   AddressQuoteTest.namesWithStructuralCharactersAreQuoted:23
EXIT=1
```

为什么该失败是预期的：旧判定只对首尾空白加引，`A B` 的空白在内部 → 不加引 → canonical 输出 `region.A B`，而新断言的期望值是加引形态。这条红灯正是上一轮"遗留顾虑 A"里那个往返缺口。

**GREEN**

命令：`./mvnw -q spotless:apply && ./mvnw -pl simos-util -Dtest=AddressQuoteTest test`

```
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.752 s -- in io.mosire.simos.util.address.AddressQuoteTest
[INFO] BUILD SUCCESS
EXIT=0
```

`spotless:apply` 对本次改动无格式调整（diff 未变）。追加自证（超出本轮要求）：`./mvnw -q -pl simos-util verify` → `EXIT=0`，`spotbugsXml.xml` 0 条 `BugInstance`。

## 本轮自审与遗留

- 自审：改动就是裁决的两点，无附带改动；`Character::isWhitespace` 与 `isBareWord`、`strip()` 同口径（同为 `Character.isWhitespace` 语义），故"任何含空白名字都加引"与"裸词不含空白"现在闭合，`region."A B"` 可被解析器当引号 token 接受。
- **待控制器处理：spec 的 §3.4 修改目前只在工作区（`git status` 显示 ` M docs/.../2026-09-16-util-simos-design.md`），不在我的提交里**——我按纪律只提交了 `simos-util/` 下这两个文件，未 `git add -A`。请控制器自行提交该文档改动（或指示我提交）。
- 原"遗留顾虑 A"已关闭；"顾虑 B"（依赖）无变化。
- 上一条“待控制器处理”已由控制器完成：spec 改动已作为 `4109eb5 docs(spec): §3.4 条件 2 收紧为含任意空白即加引（M1 Task 1 裁决）` 提交。

---

# 修复轮 2（Task 审阅者发现，逐条照做）

- 提交：`424877f` — `fix(util): Index 空坐标护栏自证 + 结构化字符集去重（M1 Task 1 修复轮 2）`（新开提交，未 amend 前两个）
- `git diff --cached --stat`：2 文件、29 insertions、14 deletions

## 逐条落地

**1（Important，G13）——`Index` 空坐标护栏补"故意违规"用例。** 新增 `emptyIndexIsRejected`。注意断言关键词按实现实际文案取：`Index.java` 的消息是 `"Index 段至少一个坐标"`，**不含"索引"二字**，故用 `hasMessageContaining("至少一个坐标")`；实现未改动。这条此前确实等于装饰（`Namespace`/`Entity`/`Property` 三条都有违规用例，只缺它）。

**2（Minor）——结构化字符集去重。** 新增 `private static boolean hasStructuralChar(String s)`（`: [ ] "`），`isBareWord` 与 `quoteIfNeeded` 的条件 1 共用；`isBareWord` 同时简化为「非空 && 非 `.` && 非结构化 && 无空白」，空白循环只剩 `Character.isWhitespace(s.charAt(i))`。**未**用 `!isBareWord(name)` 代替（按警告：`.` 是否决字符但不是加引判据，否则 `region.Nation.区域A` 会被误加引）——该陷阱由既有用例 `bareWordNamesAreNotQuoted` 继续钉住。

**3（Minor）——`Index` 防御拷贝断言。** 新增 `indexCoordsAreDefensivelyCopied`：`new Index(List.of(1)).coords().add(2)` 抛 `UnsupportedOperationException`（访问器名按 record 组件实际名 `coords()`）。

**4（报告更正）——** 原文"包里唯一的遗留文件 `package-info.java` 未被触碰"已改准为"`address` 包里就是本任务新建的 6 个文件（唯一的 `package-info.java` 在父包 `io.mosire.simos.util` 下，与本次无关、未触碰）"，结论不变。

## TDD 证据（本轮）

形状是"先加测试 → 被既有用例抓住回归 → 修 → 全绿"。

**RED（去重第一版漏了 `.`）**：`hasStructuralChar` 有意不含 `.`，而审阅者给的 `isBareWord` 公式写作「非空 && `!hasStructuralChar` && 无空白」，照抄即丢掉 `.` 的否决。既有用例当场拦下：

```
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   AddressQuoteTest.kindMustBeBareWord:42
Expecting code to raise a throwable.       ← Entity.of("a.b", "x") 不再抛异常
EXIT=1
```

修法：把 `.` 的否决写回 `isBareWord`（`s.indexOf('.') >= 0`），并在 Javadoc 里写明"`.` 是否决字符但不是加引条件 1 的判据"。

**GREEN**：`/root/SimulatorMosire/mvnw -f /root/SimulatorMosire/pom.xml -q spotless:apply && ... -pl simos-util -Dtest=AddressQuoteTest test`

```
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.673 s -- in io.mosire.simos.util.address.AddressQuoteTest
[INFO] BUILD SUCCESS
EXIT=0
```

追加自证：`... -pl simos-util verify` → `EXIT=0`（Spotless + Checkstyle + SpotBugs 0 条 + Surefire）。用例数 6 → 8。

## 本轮自审

- 去重的净效果是**行为等价**（除上述被当场修回的 `.` 回归），证据：既有 6 条用例 + 新增 2 条全绿，其中 `bareWordNamesAreNotQuoted`（`region.Nation.区域A` 不加引）正是"别用 `!isBareWord` 代替"的直接反例。
- 已改用绝对路径调用（`/root/SimulatorMosire/mvnw -f /root/SimulatorMosire/pom.xml ...`）：本环境每次 bash 调用的 cwd 会被重置，`./mvnw` 不再可靠。
- 无遗留顾虑新增；"顾虑 B"（依赖）无变化。

---

# 修复轮 3（复审：上一轮的防御拷贝用例是空转护栏）

- 提交：`79371d2` — `test(util): Index 防御拷贝用例换可变源以具备判别性（M1 Task 1 修复轮 3）`（新开提交；**只改测试**，`Index.java` 与 HEAD 逐字节一致）
- `git diff --cached --stat`：1 文件、6 insertions、3 deletions

## 复审判定成立，且已实测复现

上一轮的 `indexCoordsAreDefensivelyCopied` 断言 `new Index(List.of(1)).coords().add(2)` 抛 `UnsupportedOperationException`，但 `List.of(1)` 本身就不可变、`List.copyOf(List.of(1))` 又返回**同一实例**——也就是说把 `Index.java:10` 的 `coords = List.copyOf(coords)` 整行删掉，该用例依然绿。这是标准的空转护栏（G13 的反面），且其 Javadoc 宣称的"改不动原实例"并非该用例验证的行为。

## 改了什么

`AddressQuoteTest`（唯一改动文件）：

```java
  /** 记录组件的防御性拷贝：源列表可变时，`coords` 仍是构造那一刻的副本（判别性来自可变源）。 */
  @Test
  void indexCoordsAreDefensivelyCopied() {
    List<Integer> source = new ArrayList<>(List.of(1));
    Index index = new Index(source);
    source.add(2);
    assertThat(index.coords()).containsExactly(1);
  }
```

新增 `import java.util.ArrayList;`（`java.util.List` 原已在）。判别性来自**可变**源列表：只有构造期真的做了拷贝，改源列表才影响不到 `coords()`。按记录组件实际访问器名 `coords()` 书写。

## 判别性自证（本轮核心证据）

1. 临时删掉 `Index.java` 第 10 行 `coords = List.copyOf(coords);`（工作区临时态，未提交）：

```
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[ERROR]   AddressQuoteTest.indexCoordsAreDefensivelyCopied:71
Expecting actual:
but some elements were not expected:        ← coords() 变成了 [1, 2]
EXIT=1
```

对照上一轮的用例：同一操作下它是**绿的**——两次结果差异即判别性的直接证据。

2. 从备份恢复 `Index.java`，`git diff -- Index.java` 为空（逐字节还原），再跑：

```
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.716 s -- in io.mosire.simos.util.address.AddressQuoteTest
[INFO] BUILD SUCCESS
EXIT=0
```

`spotless:apply` 对本轮改动无格式调整。无遗留顾虑新增；"顾虑 B"（依赖）无变化。
