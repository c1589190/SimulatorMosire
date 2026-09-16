# Task 2 报告：`Address.parse` / `canonical` 与冻结样例

**Status:** DONE
**分支:** `feat/m1-util-simos`（直接在其上工作，未建分支/worktree）
**提交:** `e1eac8e`（测试，RED）→ `4188cc1`（实现，GREEN）

## 一 实现了什么

| 文件 | 内容 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/address/Address.java`（新建） | 段序列记录：防御性 `List.copyOf`、≥2 段、首段必须是 `Namespace`、第 ≥3 段的裸词缺 kind 实体校验（见 §五）；`namespace()`、`canonical()`（`:` 连接）、`parse()` 转发 `AddressParser` |
| `simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java`（新建） | 包内 `final class`：按 `:` 切段（引号内的不切、`""` 转义来回抵消、引号未闭合报错）、`.[` 兼容写法、按位置 + 表面形式判定段类型，无隐式兜底；错误消息带段序号与原文 |
| `simos-util/src/test/.../AddressParseTest.java`（新建） | 冻结样例 15 条（`@ValueSource`）+ 段类型判定 5 条 + 构造器护栏 2 条 + 裁决要求的 3 条（G13、内部空白往返、空名字 vs 空段），共 **24** 条 |
| `simos-util/src/test/.../AddressTolerantParseTest.java`（新建） | `.[` 兼容写法 1 条 + 11 条非法形态，共 **12** 条 |
| `simos-util/src/test/.../AddressQuoteTest.java`（修改） | **只追加**：`redundantQuotesAreNormalizedAway`（§3.4 后半归一）+ 补回 `coords()` 不可变断言（T1 修复轮 3 遗留空洞）。既有 8 条用例逐字未动，现 **9** 条 |

五项控制器裁决逐条落地：①（§3.4 条件 2 收紧）由 T1 的 `AddressText.quoteIfNeeded` 已实现，本任务补 `region."A B"` 往返用例；②见 §五；③两条往返用例已加；④不可变断言已补回；⑤`AddressQuoteTest` 仅追加、未改其它用例。

## 二 TDD 证据

**RED** — `./mvnw -q -pl simos-util -Dtest='AddressParseTest,AddressTolerantParseTest,AddressQuoteTest' test`

```
[ERROR] .../AddressParseTest.java:[35,16] cannot find symbol
[ERROR] .../AddressTolerantParseTest.java:[16,5] cannot find symbol
[ERROR]   symbol:   class Address
[ERROR] BUILD FAILURE
```

预期失败理由：实现尚未存在，`Address` 类无法解析——符合简报 Step 3 的预期（"cannot find symbol: class Address"）。

**GREEN** — 同上命令 + `./mvnw -pl simos-util verify`

```
[INFO] Tests run: 24, Failures: 0, Errors: 0 -- in AddressParseTest
[INFO] Tests run: 12, Failures: 0, Errors: 0 -- in AddressTolerantParseTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0 -- in AddressQuoteTest
[INFO] Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
[INFO] You have 0 Checkstyle violations.
[INFO] Spotless.Java is keeping 12 files clean
[INFO] Done SpotBugs Analysis....   →  BUILD SUCCESS
```

**G13 护栏自证（两次真实变异，均已恢复并复跑）**

1. 把构造器里的校验临时短路成 `if (false)` →
   `AddressParseTest.kindlessEntityOutsideRootPositionIsRejected:85` **转红**（`Failures: 1`），
   恢复后 45/45 全绿。**该用例是判别性的，不是装饰。**
2. 把 `Index` 的 `coords = List.copyOf(coords)` 临时换成 `new ArrayList<>(coords)` →
   补回的 `indexCoordsAreDefensivelyCopied` **转红**（`Failures: 1`），恢复后 `git diff` 对
   `Index.java` 为空（工作区与 HEAD 逐字节一致）。**裁决 ④ 补回的那行确实在守东西。**

## 三 改动文件清单

```
A  simos-util/src/main/java/io/mosire/simos/util/address/Address.java        (58)
A  simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java (209)
A  simos-util/src/test/java/io/mosire/simos/util/address/AddressParseTest.java (111)
A  simos-util/src/test/java/io/mosire/simos/util/address/AddressTolerantParseTest.java (44)
M  simos-util/src/test/java/io/mosire/simos/util/address/AddressQuoteTest.java (+11)
```

`79371d2..HEAD` 共 5 files, +433/-0。无其它文件被触碰（`Index.java`、T1 的四个段类型与 `AddressText` 均与 HEAD 逐字节一致）。未推送。

## 四 自审发现

- **`parse(canonical(x)) == x` 对全部合法 AST 成立**（逐类枚举核过）：缺 kind 实体在 ≥3 段只可能是"带引号渲染"的
  （如 `"Nation.区域A"`、`"A B"`、`""`、`"A""B"`），解析回来仍是同一个缺 kind 实体；不带的已被构造器拒掉（§五）。
- 额外探测（`target/classes` + 临时 `java` 单文件程序，不入库）：
  `region."河口:渡口"`、`region."A""B"`（名字含 `"`）、`region."A B"` 三者 parse→canonical 逐字恒等；
  `[ 4 , 3 ]` 宽容解析为 `[4,3]`；`map:Map1:"Nation"."区域A"` 的 canonical 为 `map:Map1:"Nation.区域A"`
  （Human 形式 → canonical 形式的归一，二次 parse 稳定）。
- 命名/结构：解析细节全在包内 `AddressParser`，对外只有 `Address.parse`，与 spec §3.1 的公开面一致；未新增/修改 T1 的公开面。

## 五 与裁决 ② 的措辞分歧（修复轮 1 已裁决：精确化保留，另加引号归一 —— 见 §七）

裁决 ② 写的是"第 ≥3 段不得是缺 kind 的 `Entity`"。**字面执行会撞上简报自己的用例**：
`parse("map:Map1:\"Nation\".\"区域A\"")` 依 spec §3.2（"左侧被引号包裹 ⇒ 无 kind"）必然产出
`Entity(∅, "Nation.区域A")` 落在第 3 段——这是 spec §3.1 明确承认的 Human 形式（类型词由 Resolver 补齐）。
字面拒掉它 = 该用例转红 + 违反 spec（"spec 优先"）。

故按裁决给出的**判别理由**（canonical 撞车）实现为**精确判据**：

```java
segment instanceof Entity e && e.kind().isEmpty() && AddressText.isBareWord(e.name())
```

即只拒"渲染成裸词"的缺 kind 实体——只有它们与同名 `Property` 的 canonical 逐字相同（都是 `member`），
才是裁决 ② 说的那种会破唯一性的情形；行首诊断模板与段序号/实体原样照抄裁决。
改名需要加引的缺 kind 实体不拒：canonical 自带引号，往返恒等，不产生歧义。
裁决给的 G13 用例（`Entity.of("U")` + `Entity.of("member")`，两名都是裸词）在此判据下**通过**且经变异验证为判别性。

**由此产生的边界行为**：`unit:U:"member"` 现在抛
`第 3 段是缺 kind 的实体——裸词主体只允许出现在根位置（第 2 段）：Entity[kind=Optional.empty, name=member]`。
该输入的 AST（裸词名字的缺 kind 实体）在 canonical 里不可表达（写出来就是 `member`，会被读回 `Property`），
spec §3.5 的宽容清单也不含它（"除上述之外不猜测"），故报错；若控制器希望改成"归一为 `Property("member")`"，
那是一行改动 + 一条用例，请示下。

## 六 其它顾虑（低）

- 简报原样带入：`map:Map1:region.` 抛的是"名字组件必须加引号（含 : [ ] \" 或空白）：``"——空名字组件的
  建议其实应是写成 `""`，消息可读性略差。未被任何用例覆盖，按"逐字实现简报"未改，留作后续裁决。
- `AddressTolerantParseTest` 的 11 条非法形态只断言异常类型（简报原文），未断言消息关键词/段序号；
  宽松点说，这 11 条不校验诊断质量（诊断模板在 §五 那条用例的路径上被间接覆盖）。

---

# 修复轮 1（控制器裁决：解析器引号归一）

**Status:** DONE ｜ **提交:** `687f732` fix(util): 第 ≥3 段引号归一为 Property（M1 Task 2 修复轮 1）

## 七 裁决内容与落地

控制器已就地更新 spec 并提交（`3ebb891`），已读 §3.2 末段与 §3.4 末段：

- **§3.2 末段**：第 ≥3 段的缺 kind `Entity` 只在 name 不是裸词时合法；校验位置就是 `Address` 构造器
  （段类型无位置信息）——**即我在 §五 精确化后的那条，保留不动**，仅补 javadoc 一句
  "解析器不再产出这种实体（`unit:U:"member"` 按 §3.4 末段归一读成 `Property`），本校验守的是直接构造"。
- **§3.4 末段**：引号只在存在未加引号的 `.` 时才参与段类型判定；第 ≥3 段**无未加引号 `.`** 的 token
  一律**先去引再判**——去引后是裸词 ⇒ `Property`，否则 ⇒ 缺 kind 的 `Entity`。

**改动只一处**（`AddressParser.toSegment` 的 `dot < 0` 分支）：

```java
String text = unquote(token);
return position >= 2 && AddressText.isBareWord(text) ? new Property(text) : Entity.of(text);
```

判定顺序仍为：`[...]` ⇒ `Index`（最先）→ 含未加引号 `.` ⇒ `Entity`（左右侧引号规则不变）→ 其余去引再判；
第 2 段行为不变（裸词与引号包裹都仍是 `Entity(∅,…)`）。`+20/-1`，未动其它任何代码。

## 八 RED / GREEN 与自证

**RED** —— `./mvnw -pl simos-util -Dtest='AddressParseTest#quotedBareWordAtPositionThreeNormalizesToProperty' test`

```
java.lang.IllegalArgumentException: 第 3 段是缺 kind 的实体——裸词主体只允许出现在根位置（第 2 段）：Entity[kind=Optional.empty, name=member]
	at AddressParseTest.quotedBareWordAtPositionThreeNormalizesToProperty(AddressParseTest.java:67)
Tests run: 1, Failures: 0, Errors: 1   →  BUILD FAILURE
```

（正是裁决要修掉的旧行为：把 `"member"` 判成 `Entity(∅,member)` 后被构造器守卫抛出。）

**GREEN** —— `./mvnw -pl simos-util -Dtest='AddressParseTest,AddressTolerantParseTest,AddressQuoteTest' test`
→ 25 + 12 + 9 = **46 全通过**；`./mvnw -pl simos-util verify` → Checkstyle 0 违规 / Spotless 干净 /
SpotBugs 无发现 / BUILD SUCCESS。

**自证（裁决要求的那条 + 我额外做的一条，均为真实变异，已恢复并复跑）**

1. 去掉第 3 步的 `isBareWord(t)` 分支（改回 `Entity.of(text)`）⇒ 新用例**转红**：
   `IllegalArgument 第 3 段是缺 kind 的实体…（:67）`。
2. 去掉 `position >= 2` 侧 ⇒ 新用例**也转红**：根侧断言 `but was: Property[ident=U]`（:70）——
   即新用例同时钉住"≥3 段归一"与"第 2 段不归一"两侧。

**既有用例是否断言旧行为**：**没有**。三个测试类全量复跑无一转红，`AddressQuoteTest`（T1）逐字未动，
故本轮**未改任何既有用例**（裁决要求的"点名"在此为空集）。

**额外边界探测**（`target/classes` + 临时 `java` 单文件程序，不入库；`stable` = 二次 parse 与首次 AST 相等）：

```
unit:U:"A B"    -> Entity      canonical=unit:U:"A B"    stable=true
unit:U:""       -> Entity      canonical=unit:U:""       stable=true
map:Map1:"[x]"  -> Entity      canonical=map:Map1:"[x]"  stable=true
unit:"U"        -> root Entity canonical=unit:U          stable=true
unit:U:member   -> Property    canonical=unit:U:member   stable=true
```

即"去引后非裸词 ⇒ Entity（canonical 重新加引）"与"去引后裸词 ⇒ Property"两侧都稳定。

## 九 本轮改动文件（`687f732`）

```
M  simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java      (+6/-1)
M  simos-util/src/main/java/io/mosire/simos/util/address/Address.java           (+3，javadoc)
M  simos-util/src/test/java/io/mosire/simos/util/address/AddressParseTest.java  (+12，新用例)
```

## 十 残留顾虑（低，均未改）

- 简报原样带入：`map:Map1:region.` 抛"名字组件必须加引号（含 : [ ] \" 或空白）：``"——空名字组件的
  建议本应是写成 `""`，消息可读性略差；无用例覆盖，未擅自改（§六 同款）。
- 本轮裁决未涉及但值得记一笔：`Address` 构造器守卫在**解析路径上已不可达**（能产出它的输入都被归一掉了），
  它是纯粹的"直接构造"防线——spec §3.2 末段正是这么定义的，故保留，非死代码（G13 用例正走这条）。

---

# 修复轮 2（审阅：1 Critical + 1 Important + 4 Minor）

**Status:** DONE ｜ **提交:** `a94400b` fix(util): canonical 条件 4 + 地址位置校验补全 + 解析契约收口（M1 Task 2 修复轮 2）
**spec 依据:** `b55be33`（§3.4 表新增条件 4；§3.5 新增 Index 宽容明文）——已读 §3.4 表与 §3.5。

## 十一 六条逐项落地

| 项 | 严重度 | 处置 |
|---|---|---|
| A | Critical | `AddressText` 增**条件 4**：带 kind 的 name 以 `.` 开头/结尾或含连续 `..` ⇒ 加引（新私有 `hasEmptyComponent`）。这补全了"不加引即可回解析"的充要面：不引时每个 `.` 组件都必须是裸词，故空组件必须走引号。`hex."1..2"` 现在可回解析 |
| B | Important | `Address` 构造器循环改为 **i ≥ 1 三查**：`Namespace` 出现在任意 i ≥ 1 即抛；`Property` 出现在 i == 1 即抛；缺 kind 裸词 `Entity` 出现在 i ≥ 2 即抛（原判据不动）。三者都渲染成裸词、彼此撞 canonical。消息沿用原模板（带段序号 + 段本身） |
| C | Minor | 删掉 `splitTolerantDot` 里不可达的 `right.isEmpty()`（`. ` 后必有 `[`），并把可达的左侧判据交给新用例 `tolerantDotWithEmptyLeftSideIsRejected`（`map:Map1:.[4,3]` ⇒ IAE 且消息含"不得为空"）；消息随之改为"左侧不得为空"（**原文是"兼容写法 `.` `[` 的两侧不得为空"**，无用例依赖该文案） |
| D | Minor | 见 §十二 的既有用例点名 |
| E | Minor | `toIndex` 增 `position` 形参，三条消息（未闭合 / 空 Index / 坐标不是整数）都带段序号与原文；非数字坐标仍包成 IAE。`strip()` 宽容**保留**（spec §3.5 已明文：空白、`+` 号、前导零可容忍） |
| F | Minor | 冻结样例第二列补两个守卫：`frozenFullRowsParseToExpectedSegments`（5 条全写式行整条 `segments()`）+ `frozenIncrementalRowsEndWithExpectedSegment`（`@MethodSource`，9 条增量式行末段）。15 条样例中 `map:Map1` 由既有构造器用例覆盖 |

## 十二 被改动的既有用例（点名 + 原文）

**`AddressTolerantParseTest.malformedAddressesAreRejected` 的 `@ValueSource` 列表**：按裁决 D 把
`"map:\"a", // 引号未闭合` 这一条**移出**列表（11 条 → 10 条），改为专门的
`unclosedQuoteIsRejected()`，断言 `.hasMessageContaining("引号未闭合")`。
覆盖的输入集合不变（仍是同 11 个输入），差别只在把该输入从"仅断言异常类型"升级为"断言消息具备判别性"。
其余既有用例（含 T1 的 `AddressQuoteTest` 全部 9 条）**逐字未动**。

## 十三 自证（六次真实变异）与穷举扫描

**RED（修复前的失败）** —— `./mvnw -pl simos-util -Dtest='AddressParseTest,AddressTolerantParseTest,AddressQuoteTest' test`

```
Tests run: 62, Failures: 3, Errors: 1     (AddressParseTest 2 F + 1 E, AddressTolerantParseTest 1 F)
emptyNameComponentsRoundTrip:81 » IllegalArgument 名字组件必须加引号（含 : [ ] " 或空白）：``（地址：`map:Map1:hex.1..2`）
namespaceOutsideFirstPositionIsRejected:169
propertyAtRootPositionIsRejected:176
nonNumericIndexCoordinateIsRejected:69     （消息里没有段序号）
```

**GREEN** —— 同上 → **62 全通过**；`./mvnw -pl simos-util verify` → Checkstyle 0 违规 / Spotless 干净 /
SpotBugs 无发现 / BUILD SUCCESS。

**变异自证（每条都是"删掉被测那行 ⇒ 对应护栏转红"，恢复后逐文件 `cmp` 与备份一致）**

| 变异 | 目标护栏用例 | 结果 |
|---|---|---|
| 删条件 4 分支（`AddressText`） | `emptyNameComponentsRoundTrip` | **RED**：`IllegalArgument 名字组件必须加引号… map:Map1:hex.1..2` |
| 删 `Namespace` 判据 | `namespaceOutsideFirstPositionIsRejected` | **RED**（无异常） |
| 删 `Property` 判据 | `propertyAtRootPositionIsRejected` | **RED**（无异常） |
| 短路 `left.isEmpty()` | `tolerantDotWithEmptyLeftSideIsRejected` | **RED**（漏出越界异常，不再是 IAE 契约） |
| 删"引号未闭合"护栏 | `unclosedQuoteIsRejected` | **RED**（下游兜住，消息不含"引号未闭合"） |
| 去掉 NFE 包装 | `nonNumericIndexCoordinateIsRejected` | **RED**（`isNotInstanceOf(NumberFormatException)` 命中） |

**穷举扫描（额外，覆盖 A/B 两类问题的整个构造空间）**：临时程序（不入库）用**公开 API** 穷举
[Ns(map), s₁] 与 [Ns(map), s₁, s₂]，s 取 18 个刁钻名字 × {缺 kind + hex/region/conn} + Index × 2 +
Property × 2 + Namespace：

```
accepted=5624 rejected=382 violations=0
```

即凡构造通过者，`parse(canonical(x)) == x` 且 canonical 稳定，零违规；382 条全由位置校验拦下。
（A 的 5 个名字 `1..2` / `1.` / `.1` / `...` / `Nation..区域A` 也逐个在 `emptyNameComponentsRoundTrip` 里钉住。）

## 十四 本轮改动文件（`a94400b`，+159/-19）

```
M  simos-util/src/main/java/io/mosire/simos/util/address/AddressText.java         (+16/-2，条件 4)
M  simos-util/src/main/java/io/mosire/simos/util/address/Address.java            (+20/-7，i ≥ 1 三查)
M  simos-util/src/main/java/io/mosire/simos/util/address/AddressParser.java       (+31/-19，right 死分支 / toIndex 段序号)
M  simos-util/src/test/java/io/mosire/simos/util/address/AddressParseTest.java    (+76，A/B/F 用例)
M  simos-util/src/test/java/io/mosire/simos/util/address/AddressTolerantParseTest.java (+35/-1，C/D/E 用例)
```

## 十五 残留顾虑（低，未改）

- 简报原样带入：`map:Map1:region.` 的消息仍建议"加引号"而非写成 `""`（无用例覆盖，未擅动）。
- 本轮的 `Namespace` / `Property` 位置消息是本轮新写的（裁决只给"沿用缺 kind 那条模板"），
  文案为「第 N 段是命名空间——命名空间只允许出现在第 1 段：…」「第 2 段是属性——第 2 段是该命名空间的根主体（Entity）：…」，
  如与控制器预期不符可一行改掉。
- 冻结样例 `agent:map:Map1:region.Nation.区域A` 的第二列，本轮按 §3.2 位置表的机械结果钉为
  `[Ns(agent), Entity(∅,map), Property(Map1), Entity(region,Nation.区域A)]`（§3.7：语法层不做特殊处理，
  内嵌地址的解释权在 M5 的 Resolver）。若 M5 需要另一种承载方式，这层断言会挡路——届时按 spec 改即可。
