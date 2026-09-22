# Task 9 报告：Facet 协议与注册表（`facet` 包）

## 1. 实现了什么

按 brief 的四个文件路径，**恰好四个文件**，无新增类型 / helper / 工具类：

| 文件 | 内容 |
|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/facet/FacetProvider.java` | 跨模块可见性协议：`facetName()` + `query(Address, ResolveContext)` |
| `simos-util/src/main/java/io/mosire/simos/util/facet/FacetEntry.java` | record，四条构造守卫（三条空白 + 一条 `requireNonNull`） |
| `simos-util/src/main/java/io/mosire/simos/util/facet/FacetRegistry.java` | `LinkedHashMap` 注册表：唯一名、重复即抛、按注册序拼接、`facetNames()` 返回快照 |
| `simos-util/src/test/java/io/mosire/simos/util/facet/FacetRegistryTest.java` | 8 个用例 |

实现正文与 brief Step 3 逐字一致。铁律 3 的落点：`FacetProvider` 只依赖 `Address` + `ResolveContext`
（`util` 内部类型），`MapSimos` 侧不需要任何改动、也不可能知道 `UnitSimos` 存在。

## 2. 测试与结果

```
./mvnw -pl simos-util -Dtest=FacetRegistryTest test
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0   -- FacetRegistryTest
[INFO] BUILD SUCCESS
```

提交前跑了整个模块（brief 要求"提交前跑一次模块级"）：

```
./mvnw -pl simos-util test
[INFO] Tests run: 146, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

门禁（三段全绿）：

```
./mvnw -pl simos-util spotless:check checkstyle:check spotbugs:check
[INFO] You have 0 Checkstyle violations.
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
```

输出无杂音：无 surefire 警告、无 skip、无堆栈噪声。

## 3. TDD 证据

**RED**（Step 2，实现前）：

```
$ ./mvnw -q -pl simos-util -Dtest=FacetRegistryTest test
[ERROR] .../facet/FacetRegistryTest.java:[113,13] cannot find symbol
[ERROR]   symbol:   class FacetProvider
[ERROR]   location: class io.mosire.simos.util.facet.FacetRegistryTest
[ERROR] .../facet/FacetRegistryTest.java:[132,5] cannot find symbol
[ERROR]   symbol:   class FacetRegistry
[ERROR] .../facet/FacetRegistryTest.java:[161,16] cannot find symbol
[ERROR]   symbol:   class FacetEntry
[ERROR] -> [Help 1]
```

失败原因符合预期：三个待建类型都不存在，编译期就无法解析——**这是"编译失败"而非"断言失败"**，
正是 brief Step 2 写明的期望（`cannot find symbol: class FacetProvider`）。测试文件本身先于实现落盘，
故红是真实的、不是事后补的。

**GREEN**（Step 4，实现后）：见上，8/8。

**中途一次红的说明**：Step 4 首跑出现 `queryAllConcatenatesInRegistrationOrder` 1 条失败，
原因不是实现错误，而是 **brief 自身的一处笔误**（见 §6 关注点 1），修正那一行后即全绿。

## 4. 变更文件

- 新增 `simos-util/src/main/java/io/mosire/simos/util/facet/{FacetProvider,FacetEntry,FacetRegistry}.java`
- 新增 `simos-util/src/test/java/io/mosire/simos/util/facet/FacetRegistryTest.java`

提交：`8dc496a feat(util): Facet 协议与注册表（M1 Task 9）`（仅 `git add` 上述两个目录，未推送）。
`git diff --cached --stat` 确认过只含这 4 个文件，`.superpowers/sdd/*` 与 `.serena/` 未夹带。

## 5. 守卫自证表（G13）

不是纸上推演：每条守卫都用**变异体实跑**验证过——把守卫改坏，跑 `FacetRegistryTest`，
看是否真的转红。变异方式为临时改写源码（`/tmp` 备份 + `trap` 还原，跑完 `diff -r` 确认零残留）。

| # | 守卫 | 覆盖它的断言 | 变异体（改坏方式） | 实跑结果 |
|---|---|---|---|---|
| 1 | `requireNonNull(provider, "provider")` | `hasMessage("provider")` | 删除该行 | 🔴 转红，见下方"消息原文" |
| 2 | `facetName()` 空白/null 守卫 | `register(provider("unit"," "))` / `provider("unit",null)` → IAE 含"不得为空白" | 删除整条守卫 | 🔴 转红（两条断言各一次） |
| 3 | 重复 facetName 守卫 | `duplicateFacetNameIsRejected` → IAE 含 `"unitsHere"` | 删除 `putIfAbsent` 判空分支 | 🔴 转红 |
| 4 | `requireNonNull(subject, "subject")` | `hasMessage("subject")` | 删除该行 | 🔴 转红（"Expecting code to raise a throwable"——注册表为空，循环体不执行，无守卫则彻底静默） |
| 5 | `requireNonNull(ctx, "ctx")` | `hasMessage("ctx")` | 删除该行 | 🔴 转红（同上） |
| 6 | 提供者返回 null 守卫 | `aProviderReturningNullIsRejected` → ISE 含 `"unitsHere"` | 删除 `if (fromProvider == null)` 块 | 🔴 转红（退化为 `addAll(null)` 的 NPE，与断言的 ISE 不符） |
| 7 | `FacetEntry.namespace` 空白守卫 | `new FacetEntry(" ", …)` / `(null, …)` → 含 `"namespace"` | 把消息换成 label 的 | 🔴 转红 |
| 8 | `FacetEntry.label` 空白守卫 | `new FacetEntry("unit"," ",…)` / `("unit",null,…)` → 含 `"label"` | ① 消息换成 typeName 的 ② `label != null && label.isBlank()` | 🔴 均转红 |
| 9 | `FacetEntry.typeName` 空白守卫 | `new FacetEntry("unit","label"," ",…)` / `(…,null,…)` → 含 `"typeName"` | `typeName != null && typeName.isBlank()` | 🔴 转红 |
| 10 | `requireNonNull(value, "value")` | `hasMessage("value")`（精确） | 删除该行 | 🔴 转红 |
| 11 | `facetNames()` 返回**快照**（`List.copyOf`） | `assertThat(names).containsExactly("unitsHere")`（取后再注册） | 改为 `keySet` 的**不可改视图**（`AbstractList` 代理 `providers.keySet()`） | 🔴 转红（第 142 行） |
| 12 | `facetNames()` 返回**不可改** | `names.add(...)` → `UnsupportedOperationException` | 改为 `new ArrayList<>(providers.keySet())`（可变快照） | 🔴 转红（第 137 行） |

第 11/12 行两格是**互相独立**的两个变异体，各打掉一条断言、另一条仍绿——控制器裁决 2 说的
"不可改"与"是快照"是两条独立主张，实测坐实：`AbstractList` 视图变异体满足"不可改"却输给"是快照"。

**控制器裁决 1 的实证**（这是本任务最有价值的一条）：删掉 `requireNonNull(provider, "provider")`
后，实跑的异常消息是

```
Cannot invoke "io.mosire.simos.util.facet.FacetProvider.facetName()" because "provider" is null
```

`hasMessage("provider")` 要求**全等**，故转红；而它**确实含** `"provider"`——所以
`hasMessageContaining("provider")` 在守卫存废两种实现下都绿，判别力为零。裁决 1 成立，`hasMessage` 保留。

## 6. 自检发现与对 brief 的三处偏离（请复核）

1. **brief 笔误（必须改，已改）**：`queryAllConcatenatesInRegistrationOrder` 里
   `assertThat(registry.facetNames()).containsExactly("unit", "social")` 与 brief 自己的 helper 矛盾——
   `provider(namespace, facetName, …)` 的**第一个参数 `namespace` 是死参数**（函数体从不使用），
   `facetName()` 返回的是第二个参数。故注册进去的名字是 `"unitsHere"` / `"population"`。
   三处独立证据钉死 helper 侧才是对的、断言是笔误：
   - `facetNamesIsDefensivelyCopiedAndImmutable` 注册 `provider("unit","unitsHere")` 后断言
     `containsExactly("unitsHere")`；
   - `duplicateFacetNameIsRejected` 断消息含 `"unitsHere"`（注册表消息里插的是 `facetName`）；
   - `nullArgumentsAreRejectedWithFieldLevelMessages` 断 `provider("unit"," ")` 被拒——若第一个参数才是
     facetName，此处 facetName 为 `"unit"`（非空白），根本不抛，该断言必红。

   故只改了这一行断言为 `containsExactly("unitsHere", "population")`，**未动 helper**（两个用例钉着它）。
   注册序仍满足裁决 3：`unitsHere → population` 恰与字母序（`population → unitsHere`）相反，
   LinkedHashMap 绿、任何排序实现红；控制器的说明性注释按此改名后原样保留。
2. **补了 2 条断言（新增，非改写）**：`blankOrNullPartsAreRejected` 原本只给 `namespace` 配了 null 用例，
   `label` / `typeName` 只有空白用例。变异体 `label != null && label.isBlank()` **实跑全绿**——
   即这两个守卫的 null 半边当时是装饰。补上 `new FacetEntry("unit", null, …)` 与
   `("unit","label",null,…)` 两条后，同一变异体转红（§5 第 8/9 行）。
3. **纯格式化**：`context()` 里从 brief 抄来的 `StateMeta(...)` 一行 101 字符，超 google-java-format 的
   100 列上限，`spotless:apply` 自动折行。这是 Step 5 自己的动作，非手改。

低价值观察（未改，留给裁决）：`facetNamesIsDefensivelyCopiedAndImmutable` 的注释写
`Collections.unmodifiableList(providers.keySet())`，而 `keySet()` 是 `Set`，该调用实际编不过；
注释要表达的是"不可改但仍是视图"这一类实现，论断本身经 §5 第 11 行实证为真，故保留 brief 原文。

## 7. 关注点 / 结论

- **无阻塞项**。三个类型即 brief 所定，未加类型、未加依赖（仍是 jackson-databind + slf4j-api /
  junit-jupiter + assertj-core），main 与 test 均未触碰 `java.io` / `java.nio.file`。
- 唯一需要复核的是 §6 的偏离 1 与 2：偏离 1 是不改就**跑不绿**的硬矛盾（brief 内部自相矛盾，
  非风格选择）；偏离 2 是纯增量的覆盖补强，只会让套件更严，不会放松 brief 的任何既有要求。
- 纪律交接：`8dc496a` 已提交、**未推送**；`.superpowers/sdd/*` 与本报告未入提交。

---

# 8. 修复附录：T8/T9 评审批（BASE `8dc496a` → 提交 `3bcfe6b`）

控制器裁决的六项逐项落地，**main 侧一行未动**（`git diff --stat simos-util/src/main/` 为空）——
本轮全部是测试加固，只有两个测试文件进提交。

| 项 | 内容 | 落点 |
|---|---|---|
| 1 | 新增 `queryAllResultIsImmutable`（只断不可改） | `FacetRegistryTest:154` |
| 2 | 新增 `forwardsTheCallersAddressAndContextVerbatim` | `ResolverRegistryTest:109`（+ `import java.util.ArrayList`） |
| 3 | 新增 `forwardsTheCallersSubjectAndContextVerbatim` | `FacetRegistryTest:168` |
| 4 | 两处"引用不存在的代码"的注释改为纯文字 | `FacetRegistryTest:147`、`ResolverRegistryTest:102` |
| 5 | 删掉 `provider(...)` 的死参数 `namespace`，9 处调用点同步 | `FacetRegistryTest:194` |
| 6 | §5 第 11/12 行的行号更正 | 见本附录 §8.4 |

## 8.1 Item 1：为什么没加快照断言

按裁决不加。`queryAll` 每次调用都新建局部 `ArrayList` 累加器、返回时复制它，**没有任何被保留的字段
可能别名**，故"快照性"在此不是一条性质，断言它等于断言空气。该理由已写进用例注释，防止后人"对齐"
`facetNames()` 时顺手补一条空转断言。

## 8.2 G13 自证：三次变异实跑（命令与原始输出）

命令一律为**整个模块**（不是只跑两个类），这样"只有目标用例转红"才是被完整证明的：

```
./mvnw -pl simos-util -Dcheckstyle.skip=true -Dspotless.check.skip=true test
```

**变异 1** — `FacetRegistry.java:49` `return List.copyOf(entries);` → `return new java.util.ArrayList<>(entries);`

```
### 变异 1: FacetRegistry:49 List.copyOf(entries) → new ArrayList<>(entries)
[ERROR]   FacetRegistryTest.queryAllResultIsImmutable:163
[ERROR] Tests run: 149, Failures: 1, Errors: 0, Skipped: 0
```

**变异 2** — `ResolverRegistry.java:44` `return resolver.resolve(address, ctx);` → `resolve(address, null)`

```
### 变异 2: ResolverRegistry:44 resolve(address, ctx) → resolve(address, null)
[ERROR]   ResolverRegistryTest.forwardsTheCallersAddressAndContextVerbatim:132
[ERROR] Tests run: 149, Failures: 1, Errors: 0, Skipped: 0
```

**变异 3** — `FacetRegistry.java:41` `provider.query(subject, ctx)` → `provider.query(subject, null)`

```
### 变异 3: FacetRegistry:41 provider.query(subject, ctx) → provider.query(subject, null)
[ERROR]   FacetRegistryTest.forwardsTheCallersSubjectAndContextVerbatim:191
[ERROR] Tests run: 149, Failures: 1, Errors: 0, Skipped: 0
```

三次都是 **Failures: 1**——每个变异体只打红它该打红的那一条，零连带。还原校验：跑完
`git status --porcelain simos-util/` 只列出本轮待提交的两个 **test** 文件，两个 main 文件未被列出，
即已还原干净；且没有出现 T9 那轮的"陈旧 .class"陷阱（本轮还原用 `cp` 而非 `cp -a`，mtime 落在变异体
`.class` 之后，Maven 必重编）。

## 8.3 覆盖性测试与最终计数

```
./mvnw -q spotless:apply && ./mvnw -pl simos-util clean verify
[INFO] You have 0 Checkstyle violations.
[INFO] Tests run: 10 ... in io.mosire.simos.util.facet.FacetRegistryTest
[INFO] Tests run: 8  ... in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] Tests run: 149, Failures: 0, Errors: 0, Skipped: 0
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
```

- `FacetRegistryTest` 8 → **10**、`ResolverRegistryTest` 7 → **8**、模块 146 → **149**；
- Spotless / Checkstyle（0 violations）/ SpotBugs（0 BugInstance）全清（用的是 `verify`，不是 `test`）。

**计数勘误**：批单预期"`FacetRegistryTest` 8 → 9、模块 146 → 148"，但 **Item 1 与 Item 3 各自**给
`FacetRegistryTest` 加了一个用例（8 + 2 = 10），故正确总数是 **149**。我按六项要求如数落地，
没有为凑 148 而删掉任何一个用例。若控制器期望的是 148，那说明有一项本意只加一条用例，请指明是哪项。

## 8.4 Item 6：§5 行号更正（追加式，不改 §5 原文）

§5 第 11 行（快照）与第 12 行（不可改）当时写的是"第 142 行"与"第 137 行"。这两个数字**是变异实跑
当时**文件里的真实行号（那时 `blankOrNullPartsAreRejected` 里还没有后来补的两条 null 断言，位置更靠前），
但在**提交版 `8dc496a`** 里，两条覆盖断言的实际行号是：

| §5 行 | 覆盖断言 | §5 当时写 | 提交版实际 |
|---|---|---|---|
| 第 11 行（快照） | `assertThat(names).containsExactly("unitsHere")` | 第 142 行 ✗ | **`FacetRegistryTest.java:150`** |
| 第 12 行（不可改） | `assertThatThrownBy(() -> names.add("population"))` | 第 137 行 ✗ | **`FacetRegistryTest.java:145`** |

两条都下移 8 行。位移的具体构成我**没有逐条核对**（变异跑之后该文件上方还发生过补测与 `spotless` 折行，
但我按已知编辑回推只能解释其中一部分，故不在此写未经验证的加减式）；以 `git show 8dc496a:…` 的实测
行号为准。**结论不变**：哪条变异打红哪条断言（不可改 ↔ `AbstractList` 视图变异体、快照 ↔ `ArrayList`
快照变异体）依旧成立，失准的只是 §5 里两处行号引用。

## 8.5 Item 5 的实际风险：一个编译器**看不见**的调用点

"让编译器找调用点"在 9 处里只找出 **8** 处。漏网的是原 `:105` 的 `provider("unit", null)`：在新签名
`provider(String facetName, FacetEntry... entries)` 下它**照样编译**——`null` 合法地落进 varargs 数组，
语义却从"facetName() 返回 null"变成"facetName() 返回 `"unit"`、entries 为 null"。

- 该用例**不会静默转绿**：注册成功即不抛，`assertThatThrownBy` 会报 "Expecting code to raise a throwable"，
  仍然红；但红的原因会指向 null 守卫，**看起来像 null 守卫回归，而不像漏改的调用点**——排错成本高。
- 发现方式：替换后命中数是 **9** 而非我预期的 8，这个差值就是它。已显式写成 `provider((String) null)`，
  让"只让 facetName 为 null"这一意图在源码里可见（否则 `provider(null)` 会把 `null` 同时塞进 facetName 与数组）。
- 教训（与 T9 上一轮的陈旧 `.class` 同源）：**调用点清单不能只信编译器，要拿"应有几处"的独立计数对账**。

---

## 8.6 重审对 §8.2 / §8.5 的更正（追加式，§8.2 / §8.5 原文保留不动）

### 8.6.1 §8.2 变异 3 的行号差一（Minor 1）

§8.2 写 `FacetRegistry.java:41`，实际 `:41` 是 `for` 头，被变异的调用 `provider.query(subject, ctx)` 在
**`:42`**。测试侧当时引的 `:191` 是对的（本轮收紧后该断言行号变为 `:192`）。变异本身、以及
"只打红 `forwardsTheCallersSubjectAndContextVerbatim`"这一结论都不受影响，纯引用失准。

### 8.6.2 §8.5 的 varargs 理由**事实错误**（Minor 2）

§8.5 说 `provider(null)` 的 `null`"合法地落进 varargs 数组"——**这是错的**。本轮用 javac 21.0.12
编了一个最小用例（签名 `provider(String facetName, FacetEntry... entries)`）复核：

```
javac 21.0.12
单实参 provider(null)           -> [facetName=null, entries=len0]
两实参 provider("unit", null)   -> [facetName=unit, entries=null]
```

即**单实参**的 `provider(null)` 之所以能编译，是因为 `null` 绑到**固定参数** `facetName` 上、varargs 取
**空数组**——**一个实参填不满两个位置**，`null` 根本轮不到去当数组。所以那个调用点（原 `:105`）
**本来写成 `provider(null)` 也是对的**（正是该用例想测的：facetName 为 null、entries 为空数组）；
已落地的 `provider((String) null)` 无害且调用点正确，**错的只是 §8.5 给出的理由**。

§8.5 的核心观察**仍然成立**（编译器当时确实只报了 8 处、第 9 处确实没报，靠"应为 9 处"的独立计数才发现），
但它的**机理**要按上面这个正确版本重述：不是"null 被吸进数组"，而是"该调用点在新签名下**语义对得上**、
编译器无从报警"。

**必须与"两实参静默吸收"区分开（这句给整支评审看，两者不可混为一谈）：**

| 形态 | `null` 绑到哪 | 结果 |
|---|---|---|
| **单实参** `provider(null)` | 固定参数 `facetName`，varargs = 空数组 | 语义**正确**，无须改；编译器不报警是**对的** |
| **两实参** `provider("unit", null)` | varargs **数组本身** | 语义**被静默改写**（`facetName="unit"`、`entries=null`），调用点没改却照样编译——**这才是危险形态** |

故 §8.6 **不**推翻"两个实参会被 varargs 静默吸收"那一类案例（上表第二行，实测见上方输出）。

**关于出处，我必须报告一处核不到的事**：本更正被要求写成"这不推翻 `CLAUDE.md` 纪律第 1 条里那个例子"。
我核对了工作树里的 `CLAUDE.md`：**纪律一节共 5 条**（`git add -A` / 迭代只跑单条用例 / 护栏必须自证 /
密钥纪律 / 中文注释），**没有任何 varargs 的例子**；该文件 grep `varargs`、`provider(` 均 **0 命中**，
全仓 `.md` 也搜不到 `provider("unit", null)`。因此**我没有写"CLAUDE.md 纪律第 1 条"这个出处**
（核不到而写上去等于编造，正是本轮要修的那类毛病）。技术区分本身照写不误。若那个例子确在别处
（别的分支、未落盘、或我该看的是另一份文件），请给路径，我按实际出处补引。

### 8.6.3 断言收紧：让代码追上注释（Minor 3）

`ResolverRegistryTest:132-133` 与 `FacetRegistryTest:191-192`：`seenContexts` 的断言由
`containsExactly(ctx)` 改为 `hasSize(1)` + `get(0) isSameAs(ctx)`。

理由：`ResolveContext` / `SimulationState` / `StateMeta` / `InMemoryInfoSystem` **全是 record**，
`containsExactly` 走 `equals`，**一个值相等的替身照样绿**——两处注释声称的"必须是**同一个**对象"
在旧断言下是空头支票。按裁决"把断言收紧，而不是把注释改弱"。

`seenSubjects` 那半边**未动**（用 `canonical()` 字符串比，注释也没有同一性声称）。

**G13 自证（4 组变异实跑，命令 `./mvnw -q -pl simos-util … -Dtest='FacetRegistryTest,ResolverRegistryTest' test`）：**

| # | 变异 | 新断言（`hasSize`+`isSameAs`） | 旧断言（`containsExactly`） |
|---|---|---|---|
| 必做 1 | `ResolverRegistry:44` → `resolve(address, null)` | 🔴 `ResolverRegistryTest:133`（Failures: 1） | 🔴（上轮已证） |
| 必做 2 | `FacetRegistry:42` → `provider.query(subject, null)` | 🔴 `FacetRegistryTest:192`（Failures: 1） | 🔴（上轮已证） |
| 判别 3 | `ResolverRegistry:44` → 值相等的**替身** `new ResolveContext(ctx.state(), ctx.at())` | 🔴 `:133`（Failures: 1） | 🟢 **18/18 全绿，BUILD SUCCESS** |
| 判别 4 | `FacetRegistry:42` → 值相等的**替身** | 🔴 `:192`（Failures: 1） | 🟢 **18/18 全绿，BUILD SUCCESS** |

判别 3/4 就是这次 Minor 的**根据本身**：同一变异在旧断言下**完全瞎**（绿灯），在新断言下转红——
证明收紧不是装饰，而是真的补上了一条此前无人看守的性质。两条必做变异的失败行号（`:133` / `:192`）
即 `isSameAs` 所在行。

还原校验：每组跑完 `git status --porcelain simos-util/` 只剩本轮两个待提交的 **test** 文件，
两个 main 文件与另一侧文件均未被列出，即已还原干净。

### 8.6.4 计数与提交

- 用例数**未变**：`FacetRegistryTest` **10** / `ResolverRegistryTest` **8**（本轮不增删用例），
  模块仍 **149**；`FacetRegistryTest`+`ResolverRegistryTest` 合跑 **18/18 绿**；`spotless:apply` 后无再格式。
- `simos-util/src/main/` **一行未动**。
- **报告文件本身没有进提交**：`.superpowers/sdd/.gitignore` 第 1 行是 `*`，`task-9-report.md` 被忽略
  （`git check-ignore` 实锤）。该目录里只有 `task-1..5-report.md` 是被 `-f` 越过 ignore 入库的；
  `task-6/7/8-report.md` 只存在于工作树、未入库，我这份与前两轮一样也未入库。要入库请明示，
  我用 `git add -f` 补（不擅自越过仓库自己的 `.gitignore`）。


