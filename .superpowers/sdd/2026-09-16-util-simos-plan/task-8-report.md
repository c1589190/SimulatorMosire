# Task 8 报告：`Resolver` SPI 与唯一映射注册表（`resolve` 包）

## 状态

**完成**。7 条用例全绿；`./mvnw -pl simos-util clean verify` 全绿（Spotless + Checkstyle + SpotBugs + Surefire），
模块用例总数 131 → **138**，SpotBugs `BugInstance size is 0`。

## 提交

| 短 SHA | 标题 |
|---|---|
| `954cf2b` | `feat(util): Resolver SPI 与唯一映射注册表（M1 Task 8）` |

完整 SHA：`954cf2b6adaa2cf44e5b763850b560a496da1638`（分支 `feat/m1-util-simos`，**未推送**）

## 改动文件清单

新增 4 个文件，共 228 行；**未改动本任务之外的任何文件**（`git show --stat` 已核）。

| 文件 | 行数 | 内容 |
|---|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolveContext.java` | 18 | `record ResolveContext(SimulationState state, SimosTimestamp at)` + 两条紧凑构造器守卫 |
| `simos-util/src/main/java/io/mosire/simos/util/resolve/Resolver.java` | 17 | SPI：`namespace()` / `resolve(Address, ResolveContext)` |
| `simos-util/src/main/java/io/mosire/simos/util/resolve/ResolverRegistry.java` | 46 | `LinkedHashMap` 唯一映射；`register` / `namespaces` / `resolve` |
| `simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java` | 147 | 7 条用例 |

生产源码逐字节 = brief Step 3 给的实现（仅 `spotless:apply` 的 Javadoc 折行差异，见"格式"一节）。

## 逐条用例与所覆盖守卫

| # | 用例 | 覆盖的守卫 / 语义 |
|---|---|---|
| 1 | `dispatchesToTheResolverOfTheAddressNamespace` | 按 `Address.namespace()` 分发到对应解析器；`namespaces()` 是**注册序而非排序**（`unit` → `map`，故意取反字母序） |
| 2 | `duplicateRegistrationIsRejected` | 重复注册立即 `IllegalArgumentException`，消息含命名空间 |
| 3 | `unregisteredNamespaceIsRejectedWithNoFallback` | 未注册命名空间**抛异常、不兜底**（与 GSimulator 静默遮蔽相反） |
| 4 | `blankNamespaceIsRejected` | 空白命名空间与 **null 命名空间**走同一条守卫，均为 `IllegalArgumentException`（不是 NPE） |
| 5 | `nullArgumentsAreRejectedWithFieldLevelMessages` | `register` 的 `resolver` 守卫、`resolve` 的 `address` 与 `ctx` 守卫；**字段名精确匹配** |
| 6 | `namespacesIsDefensivelyCopiedAndImmutable` | 返回值不可改（`UnsupportedOperationException`）**且是快照而非视图** |
| 7 | `resolveContextRejectsNullParts` | `ResolveContext` 的 `state` / `at` 两条 record 守卫 |

## 变异证据（G13 自证）

**方法**：`git checkout --` 仅对本任务已提交的生产文件生效（提交在变异前完成，见上表 SHA）；
每条变异用锚点唯一性检查（出现次数 ≠ 1 即报 `MUTATION-ANCHOR-FAIL` 并中止），
跑 `./mvnw -pl simos-util -Dtest=ResolverRegistryTest test`，随后 `git checkout --` 还原并打印还原后 sha256。

变异前后基线：

```
688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138  ResolverRegistry.java
15aec43c212dced700c9e0d44e910f24f9aeeb8a6162c8a77d559d25d300ec14  ResolveContext.java
```

### M1 删掉 `Objects.requireNonNull(resolver, "resolver")`

```
### MUTATION: M1 drop null guard on resolver
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.065 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.nullArgumentsAreRejectedWithFieldLevelMessages:85
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

**这条是控制器裁定 1 的直接实证**。同一次变异下断言的实际差异（逐字）：

```
[ERROR]   ResolverRegistryTest.nullArgumentsAreRejectedWithFieldLevelMessages:85
Expecting message to be:
  "resolver"
but was:
  "Cannot invoke "io.mosire.simos.util.resolve.Resolver.namespace()" because "resolver" is null"

Throwable that failed the check:

java.lang.NullPointerException: Cannot invoke "io.mosire.simos.util.resolve.Resolver.namespace()" because "resolver" is null
	at io.mosire.simos.util.resolve.ResolverRegistry.register(ResolverRegistry.java:20)
```

JDK 21 热心 NPE 的消息**确实含 `resolver` 这个词**——若写成 `hasMessageContaining("resolver")`，
此变异**会全绿**，护栏归零。精确匹配是本条护栏可自证的必要条件，不是格式偏好。

### M2 删掉 `Objects.requireNonNull(address, "address")`

```
### MUTATION: M2 drop null guard on address
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.074 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.nullArgumentsAreRejectedWithFieldLevelMessages:88
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

### M3 删掉 `Objects.requireNonNull(ctx, "ctx")`

```
### MUTATION: M3 drop null guard on ctx
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.062 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.nullArgumentsAreRejectedWithFieldLevelMessages:90
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

### M4 整条重复注册守卫换成 `if (false)`（顺带令 `putIfAbsent` 不再执行）

```
### MUTATION: M4 disable duplicate guard
mutated ok
[ERROR] Tests run: 7, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.068 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.dispatchesToTheResolverOfTheAddressNamespace:31
[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:49
[ERROR]   ResolverRegistryTest.namespacesIsDefensivelyCopiedAndImmutable:103
[ERROR] Tests run: 7, Failures: 3, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

### M4b 保留写入、只去掉重复拒绝（隔离"重复注册"这一条）

```
### MUTATION: M4b duplicate allowed, registration still works
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.060 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:49
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

### M5 未注册命名空间**改为静默兜底**（`return new QueryResult(List.of())`，即 GSimulator 行为）

```
### MUTATION: M5 silent fallback instead of throw
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.061 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback:58
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

**这条正是"不兜底"口径的判别力证明**：把抛出换成返回空结果，用例立刻红。

### M6 空白守卫只写 `isBlank()`（丢掉 `namespace == null`）

```
### MUTATION: M6 isBlank only, null not guarded
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.067 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.blankNamespaceIsRejected:71
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

红在第 71 行——即 `blankNamespaceIsRejected` 里为 null 命名空间补的那一例，正是它抓到了这个变异。

### M7 整条空白守卫删掉

```
### MUTATION: M7 blank guard removed entirely
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.072 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.blankNamespaceIsRejected:66
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

### M8 `ResolveContext` 删掉 `state` 守卫

```
### MUTATION: M8 drop state guard
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.060 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.resolveContextRejectsNullParts:129
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 15aec43c212dced700c9e0d44e910f24f9aeeb8a6162c8a77d559d25d300ec14
```

### M9 `ResolveContext` 删掉 `at` 守卫

```
### MUTATION: M9 drop at guard
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.066 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.resolveContextRejectsNullParts:132
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 15aec43c212dced700c9e0d44e910f24f9aeeb8a6162c8a77d559d25d300ec14
```

M8/M9 与注释里的断言一致：删掉任一条守卫，record 只照存 null、什么都不抛，`assertThatThrownBy` 直接红。

### M10 `namespaces()` 返回"不可改的**视图**"而非快照

变异内容：加一个活体 `keys` 列表、`register` 里同步 `keys.add(namespace)`、
`namespaces()` 改成 `Collections.unmodifiableList(keys)`。

```
### MUTATION: M10 unmodifiable live view instead of snapshot
mutated ok
mutated ok
mutated ok
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.063 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.namespacesIsDefensivelyCopiedAndImmutable:103
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

红在第 103 行（`assertThat(names).containsExactly("unit")`，"快照而非视图"那条），
而第 99 行的 `UnsupportedOperationException` 断言**仍绿**——证明这两条断言各管各的、没有互相顶替。

### M11 `LinkedHashMap` → `TreeMap`（排序实现）

```
### MUTATION: M11 sorted map instead of insertion order
mutated ok
mutated ok
[ERROR] Tests run: 7, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.070 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.dispatchesToTheResolverOfTheAddressNamespace:31
[ERROR]   ResolverRegistryTest.namespacesIsDefensivelyCopiedAndImmutable:104
[ERROR] Tests run: 7, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
### restored: 688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138
```

**这条是控制器裁定 2 的实证**：`unit` → `map` 的注册序让任何排序实现转红；
若按字母序注册（`map` → `unit`），TreeMap 也会全绿，那条断言就是空转的。

### 变异过程中撞到的一处（记录在案，非缺陷）

M11 首次尝试只替换 `LinkedHashMap` → `TreeMap` 而没换 import，构建在**编译后的 Checkstyle** 阶段就停了：

```
[ERROR] .../ResolverRegistry.java:5:8: Unused import - java.util.LinkedHashMap. [UnusedImports]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check (checkstyle-check) on project simos-util: You have 1 Checkstyle violation.
```

这是门禁按预期工作（不是本任务的缺陷）；补上 import 替换后重跑即得上述红色结果。

## 还原核验

所有变异均在**已提交**的树上进行，逐条 `git checkout -- <生产文件>` 还原。还原后逐字节核验：

```
=== git status (工作树 vs HEAD) ===
 M .superpowers/sdd/2026-09-16-util-simos-plan/progress.md
 M .superpowers/sdd/2026-09-16-util-simos-plan/task-10-brief.md
 M .superpowers/sdd/2026-09-16-util-simos-plan/task-11-brief.md
 M .superpowers/sdd/2026-09-16-util-simos-plan/task-7-brief.md
 M .superpowers/sdd/2026-09-16-util-simos-plan/task-8-brief.md
 M .superpowers/sdd/2026-09-16-util-simos-plan/task-9-brief.md
?? .serena/

=== 生产源码逐字节核验：工作树 vs HEAD blob ===
IDENTICAL  15aec43c212dced700c9e0d44e910f24f9aeeb8a6162c8a77d559d25d300ec14  ResolveContext.java
IDENTICAL  71419581ad7664f7ee4381570861382c008773467a9f83a697cb493742f0ab00  Resolver.java
IDENTICAL  688fed2c57701025a3185138a5758f8aac9940bdd5b295dbc418e174a3668138  ResolverRegistry.java
```

`simos-util/src` 下工作树与 HEAD 完全一致（`git status` 无 `simos-util/` 条目）；
上表列出的 `.superpowers/...` 修改是本任务**开始前**就已存在的（会话初始 `git status` 快照里就是这几个），
`.serena/` 为未跟踪目录，均非本任务产物，未提交。

## 验收命令与结果

```
$ ./mvnw -pl simos-util -Dtest=ResolverRegistryTest test
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] BUILD SUCCESS
```

```
$ ./mvnw -pl simos-util clean verify
[INFO] Tests run: 138, Failures: 0, Errors: 0, Skipped: 0
[INFO] --- spotbugs:4.10.4.1:check (spotbugs-check) @ simos-util ---
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
```

用例数 131 → 138（+7），与计划预期一致；Spotless / Checkstyle / SpotBugs / Surefire 全过。

## 格式

`./mvnw -q spotless:apply` 只动了 Javadoc 折行（`ResolverRegistry` 类注释的 `<p>` 段被 google-java-format
并成一行；测试里 `new ResolvedSubject(...)` 一处换行），**未手工调行宽**。逻辑代码零改动。

## 顾虑

1. **`Resolver` 接口无 `@FunctionalInterface`**：它是双方法接口（`namespace()` + `resolve(...)`），
   本就不可能函数式。测试夹具用匿名类实现，符合 brief。若后续希望各模块少写样板，
   可考虑加一个 `abstract class AbstractResolver implements Resolver` 之类的便利基类——
   但这属于 brief 未要求的 API，**未添加**。
2. **`namespaces()` 返回 `List<String>`** 而非 `Set`：这是 brief 指定（"注册序"要有意义），
   也正好让"注册序 vs 排序"这条断言可写。已按裁定 2 保持 `unit` → `map` 的注册序不变。
3. **`resolve()` 的三条守卫里 `ctx` 的位置**：`ctx` 在地址分发**之前**就校验，
   于是"地址命名空间未注册"与"ctx 为 null"同时成立时，抛出的是 `ctx` 的 NPE 而非命名空间异常。
   brief 的实现与用例都按此口径，未改动；仅记录为已观察到的次序语义。
4. **`ResolverRegistry` 非线程安全**（`LinkedHashMap`）。spec §〇 裁决 3 只要求"唯一映射、无顺序、无兜底"，
   装配期注册、运行期只读是既定用法；未加同步，也未加并发用例——若后续 Core 有并发装配需求，
   需另行裁决。
