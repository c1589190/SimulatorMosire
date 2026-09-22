# src 修复轮 01 报告：两处无判别力的 `hasMessageContaining`

> 需求书：`src-fix-01-brief.md`。本报告只覆盖该文件的 §三/§四两处断言与 §五的五个变异体。
> **提交哈希（完整精确）：`35a2106998bd100a58504140792fe5176de10533`**（父：`6eb37e6695042f07942aecdd520bff780670adb2`）
> 分支 `feat/m1-util-simos`，**未推送**：远程跟踪分支为 `origin/feat/m1-util-simos`，
> `git log --oneline origin/feat/m1-util-simos..HEAD | wc -l` 在 `35a2106` 上实测 **19**
> （此后每追加一个提交 +1，现值一律现跑）。提交内**恰好 1 个文件**（`git show --stat` 实测）。

## 〇 结论摘要

| 处 | 旧断言 | 新断言 | 判别力（变异法实测） |
|---|---|---|---|
| A `duplicateRegistrationIsRejected` | `.hasMessageContaining("map")` | `.hasMessageContaining("已有解析器")` + 新增后置条件断言 | M2' 改前**绿** → 改后红；M3' 改前**绿** → 改后红 |
| B `unregisteredNamespaceIsRejectedWithNoFallback` | `.hasMessageContaining("map")` | `.hasMessageContaining("没有注册命名空间")` | M5' 改前**绿** → 改后红 |

两处缺陷均**复现成功**：需求书描述的现象（把消息正文删空/替换成输入数据，旧断言照样全绿）逐条实测成立。
另外**额外验证**了需求书 §三 的排除性主张——`namespaces()` 接不住 M3'（见 §三.4），
故后置条件确实只能钉"解析出来的仍是原先那个 resolver"这一形态，与需求书选型一致，未做替换。

---

## 一 改了什么

### 1.1 提交内 diff 原文（`git diff` / 亦即 `git show` 的 patch 部分）

```diff
diff --git a/simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java b/simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java
index 203f41c..034a775 100644
--- a/simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java
+++ b/simos-util/src/test/java/io/mosire/simos/util/resolve/ResolverRegistryTest.java
@@ -49,7 +49,16 @@ class ResolverRegistryTest {
     registry.register(resolver("map", "m-1"));
     assertThatThrownBy(() -> registry.register(resolver("map", "m-2")))
         .isInstanceOf(IllegalArgumentException.class)
-        .hasMessageContaining("map");
+        .hasMessageContaining("已有解析器"); // 消息**专有**文本，输入数据里不含（见下方自证 M2'）
+    // 失败的注册不得改动注册表：putIfAbsent→put 时此处读到 "m-2"，转红（见下方自证 M3'）。
+    assertThat(
+            registry
+                .resolve(Address.parse("map:Map1"), context())
+                .candidates()
+                .get(0)
+                .id()
+                .localId())
+        .isEqualTo("m-1");
   }
 
   @Test
@@ -58,7 +67,7 @@ class ResolverRegistryTest {
     ResolverRegistry registry = new ResolverRegistry();
     assertThatThrownBy(() -> registry.resolve(Address.parse("map:Map1"), context()))
         .isInstanceOf(IllegalArgumentException.class)
-        .hasMessageContaining("map");
+        .hasMessageContaining("没有注册命名空间");
   }
 
   @Test
```

`1 file changed, 11 insertions(+), 2 deletions(-)`。**未改动 `src/main`**（`git diff <父> <本提交> -- '*/src/main/*'` 实测为空）。

### 1.2 与需求书的两处偏差（均已核实、均为要求内的）

1. **后置条件断言的折行形态与需求书草稿不同。** 需求书给的是紧凑写法；照抄后 `spotless:apply`
   把链式调用拆成了每行一节。需求书 §三 明写"格式化交给 Spotless……**不要手工调行宽**"，
   故以 Spotless 的产物为准，未回改。语义与需求书逐字一致（`registry.resolve(Address.parse("map:Map1"), context()).candidates().get(0).id().localId()` 断言 `isEqualTo("m-1")`）。
2. **未替换 `localId()` 的读法。** 需求书允许换但要求说明；实测 `localId()` 就是接得住 M3' 的那个读法
   （见 §三.2，转红时 `expected: "m-1" but was: "m-2"`），故无替换、无需额外论证。

### 1.3 被测实现（只读，未改）

`simos-util/src/main/java/io/mosire/simos/util/resolve/ResolverRegistry.java`
- `register`：`resolvers.putIfAbsent(namespace, resolver) != null` → 抛消息含 `"已有解析器"`（:25-27）
- `resolve`：`resolver == null` → 抛消息含 `"没有注册命名空间"`（:40-43）

---

## 二 自证表

**装置**：`/tmp/simos-fix01/`（全部在 `/tmp` 副本上做，工作树 `src/` 只在 §一 那一处被编辑）。

```bash
# 一次配置 = 一条命令；<MUT> ∈ {none,M0,M2p,M3p,M4,M5p}，<PHASE> ∈ {pre,post}
bash /tmp/simos-fix01/run.sh <MUT> <PHASE>
```

`pre` = HEAD 的测试文件（`md5 = ad0867ace7bd3217fce79dc1dec6f317`，179 行）；
`post` = 修复后的测试文件（`md5 = 5a327a926ec5f41e18499ffe6377e4eb`，188 行）。
被测 class 从一份 `/tmp` 的整仓副本经 `mvn -pl simos-util -am test -Dtest=ResolverRegistryTest` 编译，
**每次先 `rm -rf target/classes target/test-classes` 强制全量重编译**，杜绝陈旧 class。

| # | 变异 | 改前 | 改后 | 需求书预期 | 实测 | 结论 |
|---|---|---|---|---|---|---|
| M0 | 删 `ResolverRegistry.java:25-27` 整块 `if (...) throw` | 红 | 红 | 红/红 | 红/红 | ✅ G13 底，**非**判别力证据 |
| M2' | 处 A 消息正文删空 `throw new IllegalArgumentException(namespace);` | **绿** | 红 | 绿/红 | **绿**/红 | ✅ 新 needle 有判别力 |
| M3' | `putIfAbsent` → `put` | **绿** | 红 | 绿/红 | **绿**/红 | ✅ 新后置断言有判别力 |
| M4 | 删 `ResolverRegistry.java:40-43` 的 `if (resolver == null) throw` | 红 | 红 | 红/红 | 红/红 | ✅ G13 底，**非**判别力证据 |
| M5' | 处 B 消息改为 `throw new IllegalArgumentException(address.namespace());` | **绿** | 红 | 绿/红 | **绿**/红 | ✅ 新 needle 有判别力 |

五行**全部与需求书预期一致**。M2'/M3'/M5' 的原始输出（改前 + 改后）逐条抄录如下。

### 2.1 M0（G13 底）—— 改前红 / 改后红

命令：`bash /tmp/simos-fix01/run.sh M0 pre` 与 `… M0 post`

改前原始输出摘录：
```
[ERROR] Tests run: 8, Failures: 3, Errors: 1, Skipped: 0, Time elapsed: 0.064 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.dispatchesToTheResolverOfTheAddressNamespace:32
[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:50
[ERROR]   ResolverRegistryTest.namespacesIsDefensivelyCopiedAndImmutable:104
[ERROR]   ResolverRegistryTest.forwardsTheCallersAddressAndContextVerbatim:131 » IllegalArgument 没有注册命名空间 map 的解析器（已注册：[]）
[ERROR] Tests run: 8, Failures: 3, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```
改后原始输出摘录：
```
[ERROR] Tests run: 8, Failures: 3, Errors: 1, Skipped: 0, Time elapsed: 0.060 s <<< FAILURE! -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:50
[ERROR] Tests run: 8, Failures: 3, Errors: 1, Skipped: 0
[INFO] BUILD FAILURE
```
**结论色：红 / 红。** `duplicateRegistrationIsRejected` 在两次都失败于 `:50`（即
`assertThatThrownBy` 那一行本身）——`Expecting code to raise a throwable.` 即"根本没抛出"。
改前改后都红 ⇒ 这条用例不是装饰，但它**不能**作为"新 needle 有判别力"的证据（需求书 §五 原话）。

### 2.2 M2'（处 A 消息正文删空）—— 改前绿 / 改后红 ★

命令：`bash /tmp/simos-fix01/run.sh M2p pre` 与 `… M2p post`

**改前**（旧 needle `"map"`）原始输出：
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.061 s -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
→ **绿：缺陷复现。** 变异体抛的是 `IllegalArgumentException("map")`，而 `"map"` 恰好就是输入数据里的命名空间，
旧断言由输入数据满足，对消息正文存废零判别力。

**改后**（新 needle `"已有解析器"`）原始输出：
```
[ERROR] io.mosire.simos.util.resolve.ResolverRegistryTest.duplicateRegistrationIsRejected -- Time elapsed: 0.040 s <<< FAILURE!
java.lang.AssertionError:

Expecting throwable message:
  "map"
to contain:
  "已有解析器"
but did not.

Throwable that failed the check:

java.lang.IllegalArgumentException: map
	at io.mosire.simos.util.resolve.ResolverRegistry.register(ResolverRegistry.java:26)
	at io.mosire.simos.util.resolve.ResolverRegistryTest.lambda$duplicateRegistrationIsRejected$0(ResolverRegistryTest.java:50)

[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:52
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```
**结论色：绿 / 红 ✅。** 转红落在 `:52`，**正是新 needle 那一行**——判别力可归因到该行本身，
不是被别的断言顺带接住。

### 2.3 M3'（`putIfAbsent` → `put`）—— 改前绿 / 改后红 ★

命令：`bash /tmp/simos-fix01/run.sh M3p pre` 与 `… M3p post`

**改前**（无后置条件断言）原始输出：
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.061 s -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
→ **绿：缺陷复现。** `put` 返回被顶掉的旧值（非 null），异常照抛，故旧断言无感；
而注册表已被换成 m-2。

**改后**（新增后置条件断言）原始输出：
```
[ERROR] io.mosire.simos.util.resolve.ResolverRegistryTest.duplicateRegistrationIsRejected -- Time elapsed: 0.038 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

expected: "m-1"
 but was: "m-2"
	at io.mosire.simos.util.resolve.ResolverRegistryTest.duplicateRegistrationIsRejected(ResolverRegistryTest.java:61)

[ERROR]   ResolverRegistryTest.duplicateRegistrationIsRejected:61
expected: "m-1"
 but was: "m-2"
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```
**结论色：绿 / 红 ✅。** 转红落在 `:61`，**正是新后置断言那一行**，且失败消息
`expected: "m-1" but was: "m-2"` 直接读出"注册表里现在装的是 m-2"——这正是新断言要钉的东西。

### 2.4 M4（G13 底）—— 改前红 / 改后红

命令：`bash /tmp/simos-fix01/run.sh M4 pre` 与 `… M4 post`

改前原始输出摘录：
```
[ERROR] io.mosire.simos.util.resolve.ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback -- Time elapsed: 0.006 s <<< FAILURE!
java.lang.AssertionError:

Expecting actual throwable to be an instance of:
  java.lang.IllegalArgumentException
but was:
  java.lang.NullPointerException: Cannot invoke "io.mosire.simos.util.resolve.Resolver.resolve(io.mosire.simos.util.address.Address, io.mosire.simos.util.resolve.ResolveContext)" because "resolver" is null
	at io.mosire.simos.util.resolve.ResolverRegistry.resolve(ResolverRegistry.java:40)
	at io.mosire.simos.util.resolve.ResolverRegistryTest.lambda$unregisteredNamespaceIsRejectedWithNoFallback$1(ResolverRegistryTest.java:59)
	at org.assertj.core.api.ThrowableAssert.catchThrowable(ThrowableAssert.java:66)

[ERROR]   ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback:60
```
改后原始输出摘录：
```
[ERROR]   ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback:69
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```
**结论色：红 / 红。** 需求书 §五 的预测逐字命中——删掉守卫后抛的是 JDK 21 的**热心 NPE**，
不是 `IllegalArgumentException`，故 `isInstanceOf` 转红。同 M0，改前改后都红 ⇒ 非判别力证据。

### 2.5 M5'（处 B 消息改为 `address.namespace()`）—— 改前绿 / 改后红 ★

命令：`bash /tmp/simos-fix01/run.sh M5p pre` 与 `… M5p post`

**改前**（旧 needle `"map"`）原始输出：
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
→ **绿：缺陷复现。** 变异体抛 `IllegalArgumentException("map")`，`"map"` 来自
`Address.parse("map:Map1")` 的命名空间段，旧断言由输入数据满足。

**改后**（新 needle `"没有注册命名空间"`）原始输出：
```
[ERROR] io.mosire.simos.util.resolve.ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback -- Time elapsed: 0.006 s <<< FAILURE!
java.lang.AssertionError:

Expecting throwable message:
  "map"
to contain:
  "没有注册命名空间"
but did not.

Throwable that failed the check:

java.lang.IllegalArgumentException: map
	at io.mosire.simos.util.resolve.ResolverRegistry.resolve(ResolverRegistry.java:41)
	at io.mosire.simos.util.resolve.ResolverRegistryTest.lambda$unregisteredNamespaceIsRejectedWithNoFallback$1(ResolverRegistryTest.java:68)

[ERROR]   ResolverRegistryTest.unregisteredNamespaceIsRejectedWithNoFallback:70
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```
**结论色：绿 / 红 ✅。** 转红落在 `:70`，正是新 needle 那一行。

### 2.6 基线（未变异）—— 改前绿 / 改后绿

命令：`bash /tmp/simos-fix01/run.sh none pre` 与 `… none post`

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] BUILD SUCCESS
```
两份均 8/8 绿 ⇒ 修复后的测试文件在**正确实现**下不会误报（不是"恢复成万能红"）。

---

## 三 变异体自证（**先证字节不同，再读测试结果**）

装置在 maven 跑完后**先**算 md5、比对，**相同即 `exit 1` 且不打印测试结果**——
把"变异没进去"机械地挡在读结果之前。复核块同时写进 `logs/<MUT>-<PHASE>.log`。

### 3.1 md5 对照表

参照 = 未变异源码经**同一套工具链**编译出的 class：
`/tmp/simos-fix01/ref-ResolverRegistry.class.md5` = **`ab0bf2cb2fc6192c5338e73d3030f735`**
（由 `none` 轮写入，`none/post` 轮复算得到**同一个值**，参照自洽）。

| 变异 | 变异**源** md5 | 原件源 md5 | **产物 class** md5 | vs 参照 | 结论 |
|---|---|---|---|---|---|
| 参照（原件） | `2f26557fa06bef5cc72e20836eaee39c` | 同左 | `ab0bf2cb2fc6192c5338e73d3030f735` | — | — |
| M0 | `d54b9ed0077a74bee10b2eff20eff9db` | `2f2655…` | `a710686290fbca4865da873ee678a83f` | ≠ 参照 | **不同** ✅ |
| M2' | `8859093468f872469d3eef1419b0ad7b` | `2f2655…` | `40fdd5fa14d229e19dfc15e99461c819` | ≠ 参照 | **不同** ✅ |
| M3' | `16f9f8487f38064e9f427ab7865ad19b` | `2f2655…` | `fd8a5f0142750d7e504c30883a9ea850` | ≠ 参照 | **不同** ✅ |
| M4 | `43509d64e3354696f452affb9db95267` | `2f2655…` | `40d9b9ecc36d4fb001c7d76f8fbd7506` | ≠ 参照 | **不同** ✅ |
| M5' | `39eee06becf3988ada785cc69dec4da4` | `2f2655…` | `e022266199246f1a99a8026de8223bc7` | ≠ 参照 | **不同** ✅ |

**五个变异体的产物 class 与原件参照全部字节不同**；且五个产物两两互不相同。
每个变异体的**源**文件 md5 也与原件不同（同一份证据的另一端）。
生成变异源时脚本对每个锚点做了 `assert count == 1`，锚点不唯一即中止。

**可复现性**：`post` 五个变异体各跑了**两轮**（第二轮的 md5 复核块已落盘到 log），
**10 个 md5 值全部逐字节复现**，五行颜色也全部复现——不是一次性的巧合。

### 3.2 被测 class 确实是变异体（`javap` 证据）

每个变异体在**读测试结果之前**先 `javap -p -c` 该 class，逐条比对关键指令：

| 变异 | javap 实测到的关键指令 | 与原件差异 |
|---|---|---|
| 原件 | `Map.putIfAbsent` + `InvokeDynamic #0:makeConcatWithConstants`（两处拼接） | 基准 |
| M0 | `register` 里**只剩** `requireNonNull` / `isBlank` / `IllegalArgumentException`（空白的守卫），**`putIfAbsent` 与其后的 `throw` 整块消失** | ✅ 块被删掉 |
| M2' | `Map.putIfAbsent` 仍在，但其后 `new IllegalArgumentException` 后**直接 `invokespecial <init>`**，**无 `InvokeDynamic` 拼接** | ✅ 正文变空、只剩 `namespace` |
| M3' | `Map.put`（**不再是 `putIfAbsent`**），其后 `InvokeDynamic` 拼接仍在 | ✅ 只换了方法 |
| M4 | `resolve` 里 `requireNonNull`×2 之后**直接** `Address.namespace()`，**无 `new IllegalArgumentException`、无 `if` 分支** | ✅ 守卫块被删掉 |
| M5' | `resolve` 里 `Address.namespace()` 之后**直接 `invokespecial IllegalArgument…<init>`**，**无 `String.valueOf`、无 `InvokeDynamic` 拼接** | ✅ 正文变成裸 `address.namespace()` |

**这堵住了需求书 §五 点名的那个坑**（"变异装置算出了变异源码却从没写到磁盘"）：
md5 证明字节变了，`javap` 证明变的是**预期的那条指令**，两者都发生在读测试结果之前。

### 3.3 `mvn test` 前强制全量重编译

脚本每次运行前 `rm -rf simos-util/target/classes simos-util/target/test-classes`
**并把主源码 `touch`**（需求书 §五 提醒的"恢复后必须强制重编译"，否则 javac 拿旧 class 骗人）。
`rm -rf` 比 `touch` 更强：源码被删过、class 目录被清过，不存在可复用的陈旧产物。

### 3.4 额外自证：需求书 §三 的排除性主张（`namespaces()` 接不住 M3'）

需求书说："最初拟的是 `assertThat(registry.namespaces()).containsExactly("map")`，**那是错的**……
`namespaces()` 仍返回 `["map"]`，**照样绿**。" 这是可实测的主张，我实测了：

构造 probe 测试文件（post 版，把新后置断言**换成** `assertThat(registry.namespaces()).containsExactly("map")`，
`md5 = 1822b091bcb0289a8ab69cfd99237257`），配 M3' 跑：

```
命令: bash /tmp/simos-fix01/run.sh M3p probe
变异源 md5 = 16f9f8487f38064e9f427ab7865ad19b  产物 class md5 = fd8a5f0142750d7e504c30883a9ea850
自证通过: 变异产物 ≠ 原件参照 (ab0bf2cb2fc6192c5338e73d3030f735)，字节不同
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.util.resolve.ResolverRegistryTest
[INFO] BUILD SUCCESS
```

**结论：`namespaces()` 版后置条件在 M3' 下照样全绿（实测），需求书的主张成立。**
`put` 换的是值不是键，`namespaces()` 看不见。⇒ 唯一接得住的是钉"解析出来的仍是原先那个 resolver"，
与需求书选型一致。

---

## 四 门禁实跑

命令（工作树、提交 `35a2106998bd100a58504140792fe5176de10533` 的内容）：

```bash
./mvnw clean verify
```
日志：`/tmp/simos-fix01/verify.log`

### 4.1 退出码与六模块结果

**退出码 = 0。** Reactor Summary 原文：

```
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO]
[INFO] SimulatorMosire .................................... SUCCESS [  1.070 s]
[INFO] UtilSimos .......................................... SUCCESS [  4.154 s]
[INFO] MapSimos ........................................... SUCCESS [  0.984 s]
[INFO] SocialSimos ........................................ SUCCESS [  0.978 s]
[INFO] UnitSimos .......................................... SUCCESS [  3.199 s]
[INFO] CoreSimos .......................................... SUCCESS [  1.485 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  11.950 s
[INFO] Finished at: 2026-09-16T23:08:54+08:00
```

六个模块**全部 SUCCESS**（Spotless + Checkstyle + SpotBugs + Surefire 全过）。

### 4.2 测试计数

- `simos-util` 模块汇总：**`Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`**
- 其中 `ResolverRegistryTest`：**`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`**
  （8 条，与改前**同数**——本轮只改既有断言的取值并新增断言，**未新增测试方法**）
- `simos-core`（AgentLibAvailabilityTest 所在模块）：`Tests run: 15, Failures: 0, Errors: 0, Skipped: 0`

### 4.3 SpotBugs `BugInstance size`

`spotbugs:check` 在每个模块都跑到且未跳过（`Skipping … report goal` 跳过的是**报告**目标，
`check` 目标本身正常执行）：

| 模块 | 日志行 | `BugInstance size` |
|---|---|---|
| simos-util | `verify.log:138` | **0** |
| simos-map | `verify.log:193` | **0** |
| simos-social | `verify.log:248` | **0** |
| simos-unit | `verify.log:303` | **0** |
| simos-core | `verify.log:390` | **0** |

`grep -o "<BugInstance " <模块>/target/spotbugsXml.xml | wc -l` 五个模块**均为 0**，与日志口径一致。

> 关账判据用的是 `verify` 而非 `mvn test`（后者不跑 SpotBugs），符合需求书 §六.4 的提醒。

---

## 五 我未能核实的

按需求书 §六.5，"宁可报核不了"：

1. **`SpotBugs` 对本轮改动本身无可核之处。** 本轮只改测试类，SpotBugs 的分析对象是主产物 class，
   测试代码不在其扫描范围内（`BugInstance size = 0` 是**全模块既有状态**，不是"本轮改动被扫过且干净"
   的证据）。我没有单独验证过 SpotBugs 会不会扫 `target/test-classes`——本轮也**不需要**这个结论。
2. **`simos-core` 的 `AgentLibAvailabilityTest` 依赖本机 `~/.m2` 的 `agentlib-mosire` 构件。**
   本机实测 15/15 绿、`clean verify` 全绿，但 CLAUDE.md 明写"`~/.m2` 不跨机同步"——
   **本报告的门禁结论只在本机成立**，换机需按 CLAUDE.md 换设备自检清单重建后再跑。
   我**没有**去核实本机那份 jar 的类数（≥118），因为本轮未触碰 `simos-core` 的任何输入，
   `AgentLibAvailabilityTest` 绿即是它当下的自证。
3. **需求书 §三 里"`localId()` 之外还有更直接的读法"我没有穷举。** 我实测了 `namespaces()`
   （§三.4，接不住），也确认了 `localId()` 接得住，但**没有**遍历其它候选读法（如反射取内部
   `Map`、增加 `ResolverRegistry` 的公开查询方法等）。后两者都会改动 `src/main`，本轮硬约束禁止。
   故"`localId()` 是**唯一**可行读法"这一更强命题**我核不了**，报告里只主张"它实测接得住 M3'"。
4. **`/tmp/simos-fix01/` 是本机一次性装置，不随仓库走。** 报告里所有 `/tmp` 路径在换机/重启后失效；
   可复现的是**方法**（§二 装置段的三步），不是那些文件本身。
5. **改前轮与改后轮之间的时间顺序**：我按派单 §四 的要求"先跑改前、再改文件、再跑改后"执行，
   **未使用 `git stash`**（派单 §四 提示工作树里有控制器未提交的台账，stash 有卷走风险）。
   该顺序在 log 的 mtime 上可见，但我**没有**做机械化的时序证明（如带时间戳的日志首行），
   只能报"我按此顺序执行"。

---

## 附录 A 硬约束遵守情况

| 约束 | 实测 |
|---|---|
| 不推送 | `git status` 后未执行任何 `git push`；分支仍为 `feat/m1-util-simos` 本地 |
| 不 `git add -A` | `git diff --cached --name-only` 在提交前实测**恰好 1 行**（那一个测试文件） |
| 只提交那一个文件 | `git show --stat 35a2106…` = `1 file changed, 11 insertions(+), 2 deletions(-)` |
| 不改 `src/main` | `git diff 6eb37e6 35a2106 -- '*/src/main/*'` 实测**为空**；变异全部在 `/tmp` 副本上做 |
| 不动 `.superpowers/**` 台账与 `.serena/` | 提交后 `git status --porcelain` 仍只剩那 6 个 `M` 台账 + 未跟踪 `.serena/`，与本任务开始时**逐字相同** |
| 不派发子代理 | 全程未 spawn 任何 agent |
| 报告不提交 | 本文件位于 `.superpowers/sdd/`（`*` 已 ignore），且**未** `git add` |
| 中文注释与文档 | 新增注释为中文，与文件既有风格一致 |
| 不用 `ugrep` 假阴性口径 | 检索隐藏目录一律用 `git grep` / `grep --hidden --no-ignore-files`；本报告的实测均以退出码与原文为准 |
