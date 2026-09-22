# T10 修复轮 2 · 范围受限重审（rereview2）

**重审者**：独立复核会话 ｜ **日期**：2026-09-16 ｜ **分支**：`feat/m1-util-simos` ｜ **HEAD**：`54ef235`（复核期间未变）
**立场**：不采信简报、控制器、实现者报告的任何结论；每条都自己跑。
**纪律遵守**：未派子代理；未 `git add -A`；未提交；未推送；所有变异跑完即还原，收尾三重证据确认零残留。
**工作区**：scratch `/tmp/t10rr2`；`13b96a8`/`ec2f10d` 的复现用 `git archive <sha> | tar -x -C /tmp/...`（**不写 `.git`、不建 worktree**）。

---

## 总判定：**可以关账**

六项核查全部 **ADDRESSED**，与实现者报告**无事实性冲突**。另有 3 处"措辞/时效"层面的观察（见文末「不符之处」），**均不影响关账**。

---

## 逐项判定

| # | 核查项 | 判定 | 一句结论 |
|---|---|---|---|
| 1 | 基线 7 条全绿 | **ADDRESSED** | 实测 6 + 1 = 7，0 failures |
| 2 | 删 `impl:42` → 只红新用例 | **ADDRESSED** | 恰好 1 红，就是新用例；`but did not` = 0 属实 |
| 2b | `but did not` 机制说法 | **ADDRESSED** | 机制说法**经反向实验证实**，不是"说得通" |
| 3a | needle 换成版本戳措辞 → 红 | **ADDRESSED** | 红，needle 有判别力 |
| 3b | 桩版本戳改错 → 仍红（守卫换人） | **ADDRESSED** | 红，且红在版本戳守卫上 |
| 4 | `impl` 逐字节未动 | **ADDRESSED** | `git diff 13b96a8..54ef235 -- simos-util/src/main/` 空；md5 吻合 |
| 5 | `clean verify` 156 条 + 三件套 | **ADDRESSED** | 156/0；Spotless 54 clean、Checkstyle 0、SpotBugs `BugInstance size is 0` |
| 6 | ⑦ 时效性（9/18 @ `13b96a8`） | **ADDRESSED** | `13b96a8`=9/18、`54ef235`=10/20、`ec2f10d`=8/16，**逐个复现，行号全吻合** |

---

## 1. 基线：7 条全绿 ✅

命令：
```
./mvnw -q -pl simos-util -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RoundTripAssertions*Test' test
```
`-q` 下 Maven 静默、EXIT=0。**没有把"静默"当证据**——改读 surefire 报告（时间戳 22:37 为本轮实跑）：

```
Test set: io.mosire.simos.util.verify.RoundTripAssertionsDriftTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.055 s -- in …RoundTripAssertionsDriftTest
Test set: io.mosire.simos.util.verify.RoundTripAssertionsTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in …RoundTripAssertionsTest
```
**6 + 1 = 7**，与简报 ⑥ ①、实现者报告一致。

---

## 2. ★ 核心：删 `impl:42` → 只红新用例 ✅

变异用**行号定位**（`:26` 与 `:42` 两行文本逐字相同，字符串替换会打错目标）：
```
line 42 (1-based): '    checkApplied(base, target, changeSet, apply);'
line 26 (1-based): '    checkApplied(base, target, changeSet, apply);'
```
删除后：
```
=== mutant md5 ===
23c771495cf0de853a0023bf3ab856c7
=== mutant diff (sanity) ===
@@ -39,7 +39,6 @@ public final class RoundTripAssertions {
               + actual);
     }
-    checkApplied(base, target, changeSet, apply);
   }
```
**变异哈希 `23c77149…` 与控制器/实现者所报逐字相同**——这是第三次独立复现该哈希。

### 原始输出（未转述）

```
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.018 s <<< FAILURE! -- in io.mosire.simos.util.verify.RoundTripAssertionsTest
[ERROR] io.mosire.simos.util.verify.RoundTripAssertionsTest.aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught -- Time elapsed: 0.004 s <<< FAILURE!
java.lang.AssertionError: 

Expecting code to raise a throwable.
	at io.mosire.simos.util.verify.RoundTripAssertionsTest.aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught(RoundTripAssertionsTest.java:120)

[ERROR] Failures: 
[ERROR]   RoundTripAssertionsTest.aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught:120 
Expecting code to raise a throwable.
[ERROR] Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
```
EXIT=1（真红，非静默）。`RoundTripAssertionsDriftTest` 0 failures ⇒ **恰好 1 红，且是新用例**。

### 附带核查：`but did not` 机制说法 → **成立**（已反向证实，非"说得通"）

实现者报告 ② 声称：该变异下报文**不含** `but did not`，理由是"根本没抛异常，`assertThatThrownBy` 在自身'是否有 throwable'那一关就失败了"。

**我没有采信"通顺"，做了两件事**：

**(i) 打印完整 message**（从 surefire XML 取 `failure` 节点全文，不是被截断的 `.txt`）：
```
MESSAGE (attr): '\nExpecting code to raise a throwable.'
TEXT (full): java.lang.AssertionError:

Expecting code to raise a throwable.
	at io.mosire.simos.util.verify.RoundTripAssertionsTest.aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught(RoundTripAssertionsTest.java:120)
'but did not' occurrences in full text: 0
```
⇒ 属实，`but did not` 出现 **0** 次。

**(ii) 反向实验**（这才是真正的验证）：构造"**抛了 throwable 但 needle 失配**"的世界（即下面 3(a) 的变异），实测报文：
```
Expecting throwable message:
  "往返不变式破裂：apply(diff(base, target), base) 与 target 不等。…
to contain:
  "变更集必须相对它被施加的 base"
but did not.
```
⇒ `but did not` **出现了**。**两个世界对照 ⇒ 实现者给的机制解释正确**：`but did not` 只在"有 throwable、消息不含 needle"时才打印；删 `impl:42` 后无异常可抛，故机制性地不出现。**简报 ⑥ 要求贴 `but did not` 是简报预设的产物形态有误，实现者未漏跑、未伪造。**

---

## 3. 判别力方向性（最重要的替代解释）✅

### (a) needle 换成版本戳守卫的措辞 → **红** ✅

变异（仅第 127 行，其余一字不动）：
```
-        .hasMessageContaining("往返不变式破裂");
+        .hasMessageContaining("变更集必须相对它被施加的 base");
```
结果：`Tests run: 7, Failures: 1`，红者 = `aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught`。

**关键证据——堆栈直接把被保护的那一行点名了**：
```
Throwable that failed the check:
java.lang.AssertionError: 往返不变式破裂：apply(diff(base, target), base) 与 target 不等。
  base      = ToySnapshot[ref=StateRef[branch=BranchId[value=main], revision=RevisionId[value=1]], timestamp=SimosTimestamp[tick=0, calendarLabel=Optional.empty], namespace=toy, alpha=1, beta=2]
  target    = ToySnapshot[ref=StateRef[branch=BranchId[value=main], revision=RevisionId[value=2]], …, alpha=5, beta=9]
  actual    = ToySnapshot[ref=StateRef[branch=BranchId[value=main], revision=RevisionId[value=2]], …, alpha=5, beta=2]
  changeSet = ToyChangeSet[baseRevision=RevisionId[value=1], …, alpha=5, beta=2]
提示：ChangeSet（或 diff/apply 本身）漏了 target 比 base 多出来的字段——这正是 L1 事故的形态。
	at io.mosire.simos.util.verify.RoundTripAssertions.checkApplied(RoundTripAssertions.java:49)
	at io.mosire.simos.util.verify.RoundTripAssertions.assertSnapshotRoundTrip(RoundTripAssertions.java:42)
	at io.mosire.simos.util.verify.RoundTripAssertionsTest.lambda$aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught$13(RoundTripAssertionsTest.java:122)
```
⇒ 新用例**确实**走 `assertSnapshotRoundTrip → impl:42 → checkApplied:49`，needle 钉的是 `checkApplied` 独有的措辞，**有判别力**（若针的是通用措辞，此处会仍绿）。

### (b) 桩版本戳改错（守卫换人）→ **仍红，但红在版本戳守卫上** ✅

变异（仅第 119 行）：
```
-        new ToyChangeSet(base.ref().revision(), target.timestamp(), target.alpha(), base.beta());
+        new ToyChangeSet(new RevisionId(999), target.timestamp(), target.alpha(), base.beta());
```
结果：`Tests run: 7, Failures: 1`，红者仍是新用例，报文：
```
Expecting throwable message:
  "变更集必须相对它被施加的 base：changeSet.baseRevision()=RevisionId[value=999]，base.ref().revision()=RevisionId[value=1]"
to contain:
  "往返不变式破裂"
but did not.
'but did not' count: 1
```
⇒ 守卫换人后**不再被静默接受**，needle 清楚地把两条守卫分开。**这与我的期望一致，也与简报 ⑥ 表格的方向一致。**

### (c) 【我加的】反向变异：删 `impl:26`（通用入口那份）→ 新用例**不该红**

这是"红必须红在被保护的那一行上"的**另一侧**证据：若新用例分不清两个入口，删 `impl:26` 时它也会红。
变异 md5 = `4f9006c9c3ffb109554e8c1c073e2f60`。结果 `Tests run: 7, Failures: 3`：

```
TEST-…RoundTripAssertionsDriftTest.xml  tests=1 failures=1 errors=0
   RED: aChangeSetThatDropsAFieldMustBeCaught | Expecting code to raise a throwable.
TEST-…RoundTripAssertionsTest.xml       tests=6 failures=2 errors=0
   RED: aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages | Expecting code to raise a throwable.
   RED: aBrokenRoundTripReportsAllThreeStates | Expecting code to raise a throwable.
```
⇒ **新用例不在其中，它是绿的。** 这证明新用例**专门**钉 `impl:42`，与 `impl:26` 无关——"它是 `impl:42` 的唯一守卫"这一结论**双向成立**。

**变异编译状态**：三条变异（`impl:42` 删、needle 换、版本戳改错、`impl:26` 删）**全部编译通过**（`BUILD_FAILURE` 均发生在 surefire 阶段且 surefire 输出行齐全），无一条因编译失败被误记为"红"。

---

## 4. `impl` 逐字节未动 ✅

```
=== md5 impl ===
ced4054ba48681fbe7ad4152cf69465b  simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java
=== git diff 13b96a8..54ef235 -- simos-util/src/main/ ===
(end diff, empty above means clean)
=== git diff --stat 13b96a8..54ef235 ===
 .../simos/util/verify/RoundTripAssertionsTest.java     | 18 ++++++++++++++++++
 1 file changed, 18 insertions(+)
```
md5 = `ced4054ba48681fbe7ad4152cf69465b`，**与要求值逐字吻合**；`main/` diff 空；该提交只碰一个测试文件。

**额外**：我把提交里的新用例与简报 ⑥ 的 java 围栏做了**程序化逐行比对**（脚本抽取围栏，非目测）：
```
fence line count: 17
committed block starts at test line (1-based): 112 -> 128
MATCH: True
```
⇒ "逐字取自简报围栏"属实。插入位置也属实（`theFrameworkDoesNotRequireSnapshotImplementations` 在 :130，新用例在 :112-128，**在其之前**）。

---

## 5. `clean verify`：156 条 + 三件套 ✅

命令：`./mvnw -pl simos-util clean verify` → **EXIT=0**

```
[INFO] BUILD SUCCESS
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 1, … -- in io.mosire.simos.util.verify.RoundTripAssertionsDriftTest
[INFO] Tests run: 6, … -- in io.mosire.simos.util.verify.RoundTripAssertionsTest
[INFO] --- spotless:3.10.2:check (spotless-check) @ simos-util ---
[INFO] Spotless.Java is keeping 54 files clean - 0 needs changes to be clean, 54 were already clean, 0 were skipped because caching determined they were already clean
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-util ---
[INFO] You have 0 Checkstyle violations.
[INFO] --- spotbugs:4.10.4.1:spotbugs (spotbugs) @ simos-util ---
[INFO] Done SpotBugs Analysis....
[INFO] --- spotbugs:4.10.4.1:check (spotbugs-check) @ simos-util ---
[INFO] BugInstance size is 0
```
**156**（基线 155 + 1），与报告一致。按要求用的是 `verify`（`mvn test` 不跑 SpotBugs）。

**SpotBugs 不是装饰的旁证**（避免"静默跳过"误判）：
- `pom.xml:202-213` 把 `spotbugs:check` 绑到 `verify` 阶段，`effort=More` / `threshold=Low`，**全仓 POM 无 `spotbugs.skip` / `<skip>`**；
- 实跑确有 `Done SpotBugs Analysis....`，且 `simos-util/target/spotbugsXml.xml`（15580 B）里含 `RoundTripAssertions` 字样 ⇒ 分析**确实覆盖了本次的类**。

---

## 6. ⑦ 时效性：三提交计数 ✅（**独立复现，非照抄**）

方法：`git archive <sha> | tar -x -C /tmp/...` 取出该提交整棵树 → 移走 `RoundTripAssertions.java` → 跑
`-Dtest='RoundTripAssertions*Test' test` → 按 `(\w+\.java):[行,列]` 去重。三次均 `COMPILATION ERROR` ≥1（确认真编译失败，非静默）。

| 提交 | 我测位置数 | 我测原始行数 | 分布 | 我测出的位置明细 |
|---|---|---|---|---|
| `ec2f10d` | **8** | 16 | `{2: 8}` | Test 23/28/43/61/69/80/92 + DriftTest 26 |
| `13b96a8` | **9** | 18 | `{2: 9}` | Test 23/28/43/61/69/80/**99**/**122** + DriftTest **35** |
| `54ef235` | **10** | 20 | `{2: 10}` | Test 23/28/43/61/69/80/**99**/**122**/**140** + DriftTest **35** |

**逐格与实现者报告的表相同，行号也逐个相同。** 简报 ⑦ 的「`13b96a8` 上是 9 个位置 / 18 行」**成立**；实现者的 `{ec2f10d:8, 13b96a8:9, 54ef235:10}` **成立**；"Maven 每条打印两遍"（分布恒 `{2: N}`）**成立**。

抽样原始输出（`13b96a8`）：
```
36:[ERROR] /tmp/…/RoundTripAssertionsDriftTest.java:[35,17] cannot find symbol
37-  symbol:   variable RoundTripAssertions
38-  location: class io.mosire.simos.util.verify.RoundTripAssertionsDriftTest
39:[ERROR] /tmp/…/RoundTripAssertionsTest.java:[23,17] cannot find symbol
40-  symbol:   variable RoundTripAssertions
```
⇒ 同时印证"Maven 打印两遍"与"javac 报 `variable` 而非 `class`"两个关键现象。

---

## 与实现者 / 控制器的说法**不符之处**

均为**措辞与时效**层面，**无一条改变判定**。

1. **简报 ⑥ 表格里 3(b) 那行的推理方向写反了（控制器的文本，非实现者过错）**。
   原文：「把桩的版本戳改错（**守卫换人**）｜新用例红 ⇒ 它分得清"是哪条守卫响的"，**排除了"因错误的原因转红"**」。
   实测 3(b) 让用例红，**红的原因恰恰是"错误的原因"（版本戳守卫响了，needle 落空）**——它证明的是**用例对"哪条守卫响的"敏感**，而不是"排除了因错误的原因转红"。
   真正排除"因错误的原因转红"的是 **3(a)**（同一代码路径、只改 needle 即转红）。**结论方向没错，但给 3(b) 挂的因果解释是反的**，与简报 ⑤ 那段"我推过≠我验过"的自我告诫同型：**这行是没跑过就写的推断**。

2. **`task-10-brief.md` Step 5 的计数自相矛盾**：原文「**⇒ 模块用例数是 149 → 156**（155 + 1）」。`155+1=156` 对，`149` 是废数。
   实测 156。实现者报告写的是 **155 → 156**，**报告比简报准**。（简报是工作区未提交的活文件，不算实现缺陷。）

3. **实现者报告 ② 引用的"简报让我贴 `but did not`"，简报 ⑥ 验收口径② 的原文是**「report 里请附上 ② 的原始输出（**红名 + `but did not` 那行**）」。**确系简报预设有误，实现者的不贴是对的**——我已用反向实验证实该措辞在本变异下机制性地不存在（见 §2）。

**未发现**任何"谎报绿/谎报红/漏跑/静默失败"的情形。三处"工具静默"的老坑（ugrep 忽略 `.gitignore`、`2>/dev/null` 吞 javac stderr、`--details=none` 全绿不打摘要）本轮**均未触发**：全部统计走的是 `grep -o | wc -l`、surefire XML 全文、以及**带 `COMPILATION ERROR` 计数的显式断言**，未依赖过任何静默行为。

---

## 零残留证据（收尾）

```
=== md5 comparison ===
ced4054ba48681fbe7ad4152cf69465b  simos-util/…/main/…/RoundTripAssertions.java
fecac703431bbaea991836a76e648c14  simos-util/…/test/…/RoundTripAssertionsTest.java
cb66e73165f2b213c93272e1be732bb3  simos-util/…/test/…/RoundTripAssertionsDriftTest.java
--- backups ---
ced4054ba48681fbe7ad4152cf69465b  /tmp/t10rr2/impl.bak
fecac703431bbaea991836a76e648c14  /tmp/t10rr2/Test.bak
cb66e73165f2b213c93272e1be732bb3  /tmp/t10rr2/DriftTest.bak
--- git status --porcelain simos-util/ (empty=clean) ---
(空)
```
三个文件与备份**逐一相同**；`git status --porcelain simos-util/` 空；`git diff --stat HEAD -- simos-util/` 空；`/tmp` 下无遗留的移走文件（已清）。还原后复跑基线：`BUILD SUCCESS` + `Tests run: 7, Failures: 0`。

**唯一的工作区改动**是本报告文件本身（新增，未提交）。

> **顺手发现（供控制器处置）**：本报告**不会**出现在 `git status` 里——`.superpowers/sdd/.gitignore` 第 1 行是 `*`，
> 该目录下**一切默认被忽略**，现有的 `task-*-report.md` 等是当年 `git add -f` 强加进去的。
> 故 `task-10-report.md` / `task-10-review.md` / `task-10-rereview-report.md` / `task-11-brief.md` **均未被跟踪**，
> 只有 `progress.md` 与若干 `task-*-brief.md`、`review-*.diff` 在版本库里。
> ⇒ 若希望本报告随 `54ef235` 一起入库，需显式 `git add -f`；**我按纪律未 add、未提交**，留给控制器裁决。
> （附注：`task-10-brief.md` 的 ` M` 状态说明它是**已跟踪**文件——所以 ⑦ 的"改报告即可"若指 brief，改的是工作区里的活文件。）

---

## 一句话总判定

**六项核查 6/6 ADDRESSED，三处不符均属措辞/时效而非事实，`54ef235` 的修复真实、承重、方向可辨 —— 可以关账。**
