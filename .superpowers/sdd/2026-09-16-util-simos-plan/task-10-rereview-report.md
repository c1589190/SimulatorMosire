# Task 10 修复轮 1 复审报告（限域复审：`ec2f10d` → `13b96a8`）

**复审对象**：提交 `13b96a8`（`fix(util): 三态诊断与漂移定位的判别力补齐`），分支 `feat/m1-util-simos`
**复审依据**：`task-10-brief.md` 的「控制器修正说明」①–⑤ + `task-10-report.md` 的「修复轮 1」节 + `review-ec2f10d..13b96a8.diff`
**裁决建议**：**需再修一轮**（本轮交付的 5 项修复**全部核实成立**；再修项**只有 1 条**，见「新发现 Important-1」，修复量约 5 行测试）

---

## 0. 复审方法与自证（先证明探针跑到了）

### 0.1 被复审文件与提交字节一致（三份都自证过）

```bash
$ for f in ...; do git show 13b96a8:$f | md5sum; md5sum $f; done
ced4054ba48681fbe7ad4152cf69465b  -        # impl（git）
ced4054ba48681fbe7ad4152cf69465b           # impl（工作树）
2fcab9f70fd03baca6cd8b88b81e7796  -        # Test（git）
2fcab9f70fd03baca6cd8b88b81e7796           # Test（工作树）
cb66e73165f2b213c93272e1be732bb3  -        # DriftTest（git）
cb66e73165f2b213c93272e1be732bb3           # DriftTest（工作树）
$ git rev-parse HEAD
13b96a852a8c6f06abd4ecc7640fd8fd852c6d80
$ git diff HEAD --stat -- simos-util/src/main/java/io/mosire/simos/util/verify/ simos-util/src/test/java/io/mosire/simos/util/verify/
(空)
```

**「实现文件一行未动」成立**：`git show --stat 13b96a8` 只有两个测试文件（`55 insertions(+), 12 deletions(-)`），
`main/` 无改动。复审全程 `git status --porcelain` 与开工时**逐字相同**（只有既有的 `.superpowers/*` 改动与未跟踪的 `.serena/`），
**我不曾改动本仓任何文件**；所有变异都发生在 `/tmp/rr_audit` 的副本上。
唯一新增的文件是本报告 `task-10-rereview-report.md` 本身，且它**不出现在 `git status` 里**——
`.superpowers/sdd/.gitignore` 第 1 行是 `*`（与同目录下既有的 13 份 `review-*.diff` 一样，属**有意**的本地工件，不入库）。
故收工 `git status --porcelain` 与开工逐字相同，**是干净的**，不是"漏报"。

### 0.2 控制器遗留脚手架**未**采信，另起一套

按任务要求核对了 `/tmp/t10resid/src` 与 `/tmp/t10fix/src`：

```bash
$ md5sum /tmp/t10resid/src/io/mosire/simos/util/verify/*.java
ced4054ba48681fbe7ad4152cf69465b  RoundTripAssertions.java      # == 13b96a8 ✓
2fcab9f70fd03baca6cd8b88b81e7796  RoundTripAssertionsTest.java  # == 13b96a8 ✓
cb66e73165f2b213c93272e1be732bb3  RoundTripAssertionsDriftTest.java  # == 13b96a8 ✓
$ md5sum /tmp/t10fix/src/io/mosire/simos/util/verify/RoundTripAssertionsTest.java
f6c6ee1da9782ea8adb1623b65e5e4a9   # != 13b96a8（是上一轮的旧测试文件，符合其历史定位）
```

`/tmp/t10resid` 的源文件确实等于 `13b96a8`。但**我仍另起一套** `/tmp/rr_audit/`（`canon/` 直接从
`git show 13b96a8:` 落盘，`md5sum` 同上），原因有二：

1. `t10resid/resid.py` 用 `--details=none` 取结果（任务提示已警告该口径会静默吞掉汇总行），
   我改用 `--details=tree` + `--reports-dir` 解析 surefire 风格 XML，**并把汇总行是否出现本身作为探针自证**。
2. **我在本轮踩到过"污染基线"的坑，这正是必须另起一套的直接原因**：
   我把一次探针输出 `| head -140`，管道截断导致 Python 收到 SIGPIPE **中途死亡**，
   变异未还原，随后一次运行的 `ORIG` 就把"已变异的文件"当成了基线。
   为此 `audit.py` 的 `restore()` **只从 `canon/` 覆盖**（绝不从当前文件快照还原），
   并让每次运行打印被变异文件的 md5 —— 下面的结果表里每个变异都有一个**互不相同且不等于 canon 的 md5**，
   这就是"变异真的落到了盘上、且跑的是它"的当场证据。

### 0.3 跑测命令（每条结果都出自它）

```bash
javac -encoding UTF-8 -cp "<out>:<simos-util/target/classes>:<junit-platform-console-standalone-1.11.4.jar>:<assertj-core-3.27.7.jar>" -d out $(find src -name '*.java')
java -jar <junit-platform-console-standalone-1.11.4.jar> execute \
     -cp "<out>:<simos-util/target/classes>:<junit>:<assertj>" \
     --select-package=io.mosire.simos.util.verify --details=tree --reports-dir=<reports>
```

**基线（自证跑到了）**：

```
### BASE 基线   [impl.md5=ced4054b]
  编译=OK | 汇总行自证=1 | total=6 成功=6 失败=0
  转红 0 条: (全绿)
```

`汇总行自证=1` 表示控制台确实打印了 `[ 6 tests successful ]`（`--details=tree` 下会打印），
**不是**"空输出被当成通过"。全部 20+ 次运行的 `汇总行自证` 均为 1。

---

## 1. 逐条结论

### 结论 1：四行 dump 的 needle **各自都有牙**（逐行删式变异实测）

命令：`python3 /tmp/rr_audit/m_del2.py`（log: `/tmp/rr_audit/del2.log`）

| 变异 | 被删的行（impl 行号） | 变异后 impl md5 | 转红条数 | 红在谁 / 为什么红 |
|---|---|---|---|---|
| D1 | `+ "  base      = "` + `+ base`（51–52） | `41b7e62d` | **1** | `aBrokenRoundTripReportsAllThreeStates`；needle `  base      = ToySnapshot[…]` 失配（报文中 base 行整条消失） |
| D1b | 只删 `+ base`（52，只删对象、留标签） | `36335756` | **1** | 同上（标签后面直接接 `\n  target    = `，needle 的前缀对得上、对象对不上） |
| D2 | `+ "\n  target    = "` + `+ target`（53–54） | `41d36e13` | **1** | 同上；needle `  target    = ToySnapshot[…]` 失配 |
| D2b | 只删 `+ target`（54） | `92386b45` | **1** | 同上 |
| **D3** | `+ "\n  actual    = "` + `+ applied`（55–56） | `1ef20132` | **2** | 见结论 2 |
| D3b | 只删 `+ applied`（56） | `0d29a560` | **2** | 见结论 2 |
| D4 | `+ "\n  changeSet = "` + `+ changeSet`（57–58） | `085001c0` | **1** | 三态用例；needle `  changeSet = ToyChangeSet[baseRevision=RevisionId[value=1], …, alpha=5, beta=2]` 失配 |
| D4b | 只删 `+ changeSet`（58） | `dcb348d8` | **1** | 同上 |
| D5 | 末行提示（换成 `+ ""` 保语法，59） | `2c11b758` | **1** | 三态用例；needle `这正是 L1 事故的形态` 失配 |

**结论**：**四行 dump + 提示行，五行全部承重，且红的理由都是被保护的那一行本身**（AssertJ 输出均为
`to contain: "<needle>" / but did not.`，needle 就是被删行的内容）。原判 I-1 与 M-1 已修实。

**附带把 I-1 的"改前确实空转"也当场复现了**（脚本内联，命令见 log 前身）：

```
### P2 复现：旧 needle + 删掉 target 行   [impl.md5=41d36e13]
  编译=OK | 汇总行自证=1 | total=6 成功=6 失败=0
  转红 0 条: (全绿)
```

即：**把 needle 退回旧的 `hasMessageContaining("target")` / `("actual")`，再删掉 target 行，六条全绿**。
这直接证实简报 ② 里 P2 的描述（报文首行 `往返不变式破裂：apply(diff(base, target), base) 与 target 不等。`
**无条件含 "target"**），也证实本轮由"裸词 needle"改"整行 needle"是**必要的**而非修饰。

### 结论 2：`V3` 红 2 条**是正确后果，不是新缺陷**；两条用例名如下

**两条用例名**：

1. `RoundTripAssertionsTest.aBrokenRoundTripReportsAllThreeStates`
2. `RoundTripAssertionsDriftTest.aChangeSetThatDropsAFieldMustBeCaught`

**它们确实各自都钉了同一行**（impl 的第 55–56 行，`"  actual    = " + applied`）：

- `RoundTripAssertionsTest.java:106` → `.hasMessageContaining("  actual    = " + expectedActual)`（`expectedActual` 是 `ToySnapshot`）
- `RoundTripAssertionsDriftTest.java:38` → `.hasMessageContaining("  actual    = " + expectedActual)`（`expectedActual` 是 `DriftingSnapshot`）

D3 的两条失败报文（`/tmp/rr_audit/del2.log` 原文节选）：

```
  ===== aBrokenRoundTripReportsAllThreeStates =====
    | to contain:
    |   "  actual    = ToySnapshot[ref=StateRef[…revision=RevisionId[value=2]], …, alpha=5, beta=2]"
    | but did not.
  ===== aChangeSetThatDropsAFieldMustBeCaught =====
    | to contain:
    |   "  actual    = DriftingSnapshot[ref=StateRef[…revision=RevisionId[value=2]], …, alpha=5, beta=2]"
    | but did not.
```

**两条红的理由都是"被删的 `actual` 行本身失配"，没有一条是"因错误的原因转红"**（两条都不是
"Expecting code to raise a throwable" 之类的兜底失败，`isInstanceOf(AssertionError.class)` 也都通过了）。
⇒ **红 2 正确，实现者的判断成立。**

### 结论 3：漂移用例的定位力**已真正补上**，且旧 needle 的空转被反向证实

命令：`python3 /tmp/rr_audit/m_drift.py`（变异内容 = brief ③ 的 W1 形态：把漂移从 `beta` 挪到 `alpha`）

**V6a（漂移挪到 alpha，用本轮的新 needle）**：

```
### V6a 漂移 beta→alpha（needle=新 actual 整行）   [impl.md5=ced4054b]
  编译=OK | 汇总行自证=1 | total=6 成功=5 失败=1
  转红 1 条: ['aChangeSetThatDropsAFieldMustBeCaught']
  ===== aChangeSetThatDropsAFieldMustBeCaught =====
    | Expecting throwable message:
    |   "往返不变式破裂：…
    |   base      = DriftingSnapshot[…, alpha=1, beta=2]
    |   target    = DriftingSnapshot[…, alpha=5, beta=9]
    |   actual    = DriftingSnapshot[…, alpha=1, beta=9]
    |   changeSet = DriftingChangeSet[…, beta=9]
    | 提示：…"
    | to contain:
    |   "  actual    = DriftingSnapshot[…, alpha=5, beta=2]"
    | but did not.
```

**红的理由正是 `actual` 那一行**：报文里 `actual` 是 `alpha=1, beta=9`（漂移在 alpha），
而 needle 钉的是**期望的** `alpha=5, beta=2` —— 前缀 `  actual    = DriftingSnapshot[` 对得上、字段对不上，
唯一失配点就是被保护的那一行。**不是**别的行、也不是兜底失败。

**V6b（同一变异，只把 needle 换回旧的 `beta=9`/`beta=2`）—— 决定性反证**：

```
### V6b 漂移 beta→alpha（needle=旧 beta=9/beta=2）   [impl.md5=ced4054b]
  编译=OK | 汇总行自证=1 | total=6 成功=6 失败=0
  转红 0 条: (全绿)
```

V6a 与 V6b 的**唯一差别是 needle**（impl md5 都是 `ced4054b` = 未动）。
⇒ **旧 needle 在"漂移挪位"的世界里确实全绿（空转），新 needle 转红且红在 `actual` 行。修复成立。**

**另补一条核心判据的承重性（N7）**：让漂移彻底消失（`changeSet` 带上 `beta` 走完全程）→

```
### N7 漂移消失（changeSet 带上 beta 走完全程）   [impl.md5=ced4054b]
  转红 1 条: ['aChangeSetThatDropsAFieldMustBeCaught']
    | Expecting code to raise a throwable.
```

⇒ 简报 ③ 的「缓解事实」成立：本用例**不是空转护栏**，`assertThatThrownBy` 这条核心判据是承重的；
同时它红的理由**是核心判据**，而 I-2 之前"定位力"那半边是空的 —— 两者现在都补齐了。

### 结论 4：`theFrameworkDoesNotRequireSnapshotImplementations` **真的守住了"`S` 无上界"**

变异：把 `assertRoundTrip` 的签名改回 `<S extends Snapshot, C extends ChangeSet>`。
命令：`python3`（见 log `### S1`），原始 javac 输出：

```
/tmp/rr_audit/src/io/mosire/simos/util/verify/RoundTripAssertionsTest.java:122: error: method assertRoundTrip in class RoundTripAssertions cannot be applied to given types;
                RoundTripAssertions.assertRoundTrip(
                                   ^
  required: S,S,BiFunction<S,S,C>,BiFunction<C,S,S>
  found:    PlainState,PlainState,PlainState::diff,PlainState::apply
  reason: inference variable S has incompatible bounds
    upper bounds: PlainState,Snapshot
    lower bounds: PlainState
  where S,C are type-variables:
    S extends Snapshot declared in method <S,C>assertRoundTrip(S,S,BiFunction<S,S,C>,BiFunction<C,S,S>)
    C extends ChangeSet declared in method <S,C>assertRoundTrip(S,S,BiFunction<S,S,C>,BiFunction<C,S,S>)
1 error
```

```
### S1 给 assertRoundTrip 的 S 加回 upper bound `extends Snapshot`   [impl.md5=26c6fe83]
  编译失败 compiled=False，1 条 `: error:` 行；去重 (文件,行) 后 1 处
```

**结论**：编译失败，且错误**正落在新用例第 122 行的调用上**（`upper bounds: PlainState,Snapshot` 是关键句）。
M-2 缺口已堵住；这是本轮唯一一条**编译期**护栏，形态与其余用例不同但成立。

### 5. ★ ⑤「残留边界」的更正：**控制器是对的，简报原文那句确实错误**

brief ⑤ 原文声称「把 `+ applied` 误写成 `+ changeSet`，本用例**抓不住**」。
**我独立重跑，结论与控制器一致：抓得住。** 命令：`python3 /tmp/rr_audit/m_swap.py`（log `swap.log` / `swap56.log`）

| 变异 | 变异后 impl md5 | 转红条数 | 红名 |
|---|---|---|---|
| **R1 `actual` 行对象 `applied`→`changeSet`** | `72f9740b` | **2** | `aBrokenRoundTripReportsAllThreeStates`、`aChangeSetThatDropsAFieldMustBeCaught` |
| R2 `actual` 行对象 →`target` | `a1827043` | 2 | 同上 |
| R3 `actual` 行对象 →`base` | `ce984775` | 2 | 同上 |
| R4 `changeSet` 行对象 →`applied` | `68baf5a9` | 1 | `aBrokenRoundTripReportsAllThreeStates` |
| R5 两行**对象**对调 | `d90cfe73` | 2 | 同 R1 |
| R6 两行**标签**对调 | `04b4be8e` | 2 | 同 R1 |
| R7 标签拼错（`actual` 后多一空格） | `32bc6e73` | 2 | 同 R1 |

R1 原始输出：

```
### R1 actual 行对象 applied→changeSet   [impl.md5=72f9740b]
  编译=OK | 汇总行自证=1 | total=6 成功=4 失败=2
  转红 2 条: ['aBrokenRoundTripReportsAllThreeStates', 'aChangeSetThatDropsAFieldMustBeCaught']
  ===== aBrokenRoundTripReportsAllThreeStates =====
    | to contain:
    |   "  actual    = ToySnapshot[ref=StateRef[…], …, alpha=5, beta=2]"
  ===== aChangeSetThatDropsAFieldMustBeCaught =====
    | to contain:
    |   "  actual    = DriftingSnapshot[ref=StateRef[…], …, alpha=5, beta=2]"
```

**R1 红 2，红名与控制器所述逐字一致。** 原理也与控制器给的解释一致：needle 是
`"  actual    = " + <对象>`，误写 `+ changeSet` 后该行渲染成 `  actual    = ToyChangeSet[…`，
**前缀对得上、类名对不上**，needle 落空。

**⇒ 简报 ⑤ 那句"抓不住"已被证伪，控制器在 ⑤ 里的更正成立；我另加了 R2/R3/R4/R5/R6/R7 五条互换/拼错变异，
无一条留下边界。** 这与控制器"未穷尽但未找到边界"的措辞一致，**我不把"不存在边界"当作已证**。

### 6. `M-3` 的数字更正：**双打印机制属实；但"8 处"只对 `ec2f10d` 成立，对 `13b96a8` 是 9 处**

为了在不碰工作树的前提下复现 Step 3，我把整个仓库（仅 5.6M）复制到 `/tmp/m3check`，
删掉 impl 后跑与报告完全同形的命令：`./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test`

**（甲）双打印机制 —— 证实**。在 `ec2f10d` 的文件集上：

```bash
$ grep -c "cannot find symbol" /tmp/m3check_ec.txt
16
$ grep "cannot find symbol" /tmp/m3check_ec.txt | sed 's/^.*verify\///' | sort -u
RoundTripAssertionsDriftTest.java:[26,17]  cannot find symbol
RoundTripAssertionsTest.java:[23,17]       cannot find symbol
RoundTripAssertionsTest.java:[28,17]       cannot find symbol
RoundTripAssertionsTest.java:[43,17]       cannot find symbol
RoundTripAssertionsTest.java:[61,17]       cannot find symbol
RoundTripAssertionsTest.java:[69,17]       cannot find symbol
RoundTripAssertionsTest.java:[80,17]       cannot find symbol
RoundTripAssertionsTest.java:[92,17]       cannot find symbol
$ grep "cannot find symbol" /tmp/m3check_ec.txt | sort | uniq -c | awk '{print $1}' | sort | uniq -c
      8 2          # 每个位置恰好出现 2 次
```

⇒ **`{2: 8}` 属实**：8 个不同 (文件,行) 位置、每条被 Maven 打印两遍 = 16 行。
简报 ⑤ 的 M-3 与报告「M-3 更正」在**它测量的那个状态上逐字正确**（含 `DriftTest` 26 这个行号）。
「`symbol: variable RoundTripAssertions`（而非 `class`）」也核实属实：

```
  symbol:   variable RoundTripAssertions
  location: class io.mosire.simos.util.verify.RoundTripAssertionsTest
  location: class io.mosire.simos.util.verify.RoundTripAssertionsDriftTest
```

**（乙）但"8 处"对当前提交 `13b96a8` 已不成立 —— 现在是 9 处 / 18 行**：

```bash
$ grep -c "cannot find symbol" /tmp/m3check_out.txt          # 13b96a8 的文件集
18
$ grep "cannot find symbol" /tmp/m3check_out.txt | sort | uniq -c | awk '{print $1}' | sort | uniq -c
      9 2
# 位置：Test 23/28/43/61/69/80/99/122（8 处）+ DriftTest 35（1 处）
```

原因：本轮新增第 5 条用例（+1 个调用点 = 122 行）、且三态用例的注释块把原 92 行推到 99 行、
`DriftTest` 的调用点从 26 推到 35。**计数本身没错，是"哪个提交"变了。** 详见「新发现 Minor-1」。

---

## 新发现

### Important-1：`assertSnapshotRoundTrip` 里对 `checkApplied` 的调用**是零判别力行**（删除后 6 条全绿）

**证据链（三重）**：

**(a) 变异**：删掉 impl 第 42 行的 `checkApplied(base, target, changeSet, apply);`（`assertSnapshotRoundTrip` 尾行）：

```
### N1 删 assertSnapshotRoundTrip 里对 checkApplied 的调用（impl:42）   [impl.md5=23c77149]
  编译=OK | 汇总行自证=1 | total=6 成功=6 失败=0
  转红 0 条: (全绿)
```

变异后方法体（确认是"删掉那一行"而非写坏）：

```java
  public static <S extends Snapshot, C extends ChangeSet> void assertSnapshotRoundTrip(
      S base, S target, BiFunction<S, S, C> diff, BiFunction<C, S, S> apply) {
    C changeSet = Objects.requireNonNull(diff.apply(base, target), "diff 返回 null");
    RevisionId declared = changeSet.baseRevision();
    RevisionId actual = base.ref().revision();
    if (!actual.equals(declared)) { throw new AssertionError("变更集必须相对它被施加的 base：…"); }
  }        // ← checkApplied 调用没了，方法到此为止
```

**(b) 独立探针**（`/tmp/rr_audit/probe/io/mosire/simos/util/verify/ProbeN1.java`，不依赖 JUnit，也不在工作树里）：
用一个**版本戳正确、但真的漏了字段**的往返对调用两个入口。**原版 impl** 下：

```
往返真的破了吗: target.equals(applied) = false
  target  = PState[…revision=RevisionId[value=2]], …, alpha=5, beta=9]
  applied = PState[…revision=RevisionId[value=2]], …, alpha=5, beta=2]
  变更集版本戳 = RevisionId[value=1] / base.ref().revision() = RevisionId[value=1] (应当通过版本戳守卫)
assertSnapshotRoundTrip(破裂往返) -> 抛 AssertionError: 往返不变式破裂：apply(diff(base, target), base) 与 target 不等。
assertRoundTrip(破裂往返)         -> 抛 AssertionError: 往返不变式破裂：apply(diff(base, target), base) 与 target 不等。
```

**N1 变异后**（同一探针、同一份输入）：

```
===== N1 变异后 (23c77149) =====
往返真的破了吗: target.equals(applied) = false
  变更集版本戳 = RevisionId[value=1] / base.ref().revision() = RevisionId[value=1] (应当通过版本戳守卫)
assertSnapshotRoundTrip(破裂往返) -> **未抛异常**
assertRoundTrip(破裂往返)         -> 抛 AssertionError: 往返不变式破裂：…          ← 变异是局部有效的
```

**(c) 为什么现有 6 条用例全都盖不住它**：`assertSnapshotRoundTrip` 只在三个地方被调用 ——
`aCorrectRoundTripPasses`（往返**正确**，不需要 checkApplied 响）、
`aMisStampedChangeSetIsRejected`（被版本戳守卫**先**拦下，走不到 checkApplied）、
`aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages` 第 3 条（只到 `diff 返回 null` 守卫）。
**没有任何一条用"版本戳正确 + 往返破裂"的输入走快照入口。** 漂移自证用例 `DriftTest` 用的是**通用入口** `assertRoundTrip`。

**为什么定 Important**：
- 这正是本任务存在的理由 —— 删掉一行无人察觉的护栏等于装饰（CLAUDE.md 纪律）。
- `assertSnapshotRoundTrip` 是 spec 里给 `Snapshot` 用的**专用入口**，M2~M4 会优先用它；
  谁哪天重构时删掉这一行，**往返不变式在快照路径上直接消失，全套用例仍然全绿**。
- brief ① 声称「核心不变量（往返破裂必须响）经删式变异证明是承重的」—— **这句话对快照入口不成立**。
- 形态与本里程碑刚清理过的 `FacetRegistry.facetNames()` 钉住 / `queryAll()` 漏掉**同源**（同文件内的不对称）。

**最小修复项（约 1 个断言块、5 行测试，不动 impl）**：让漂移用例**也走一次快照入口**。
`DriftingSnapshot` 已经 `implements Snapshot`，直接加一段即可：

```java
    assertThatThrownBy(
            () ->
                RoundTripAssertions.assertSnapshotRoundTrip(
                    base, target, DriftingSnapshot::diff, DriftingSnapshot::apply))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("  actual    = " + expectedActual);
```

（`diff` 给的 `baseRevision` 恰是 `base.ref().revision()`，版本戳守卫会放行，因此这条**必然**打在 `checkApplied` 上；
N1 变异下它会以 `Expecting code to raise a throwable.` 转红。）

### 其余护栏的覆盖情况（我自己的数据，供对照）

| 变异 | 转红条数 | 红名 | 判定 |
|---|---|---|---|
| N2 删 `assertRoundTrip` 里对 `checkApplied` 的调用（impl:26） | 3 | `aNullReturningDiffOrApplyIs…`、三态、漂移 | 承重 ✓ |
| G1 删 `assertSnapshotRoundTrip` 的 `diff 返回 null`（impl:32） | 1 | `aNullReturningDiffOrApplyIs…`（报文退化为热心 NPE，不含 `diff 返回 null`） | 承重 ✓ |
| G2 删 `checkApplied` 的 `apply 返回 null`（impl:47） | 1 | 同上（`Expecting actual throwable to be an instance of`） | 承重 ✓ |
| G3 版本戳守卫的 `if` 置假（impl:35） | 1 | `aMisStampedChangeSetIsRejected`（退化为往返破裂报文，不含守卫文案） | 承重 ✓ |
| N3 base 行对象 `base`→`target` | 1 | 三态 | 承重 ✓ |
| N4 target 行对象 `target`→`base` | 1 | 三态 | 承重 ✓ |
| N5 提示行改一个词（事故→错误） | 1 | 三态 | 承重 ✓ |
| N6 版本戳比较改用 `base` 的 ref（守卫恒真） | 1 | `aMisStampedChangeSetIsRejected` | 承重 ✓ |
| N8 三态用例改用正确 `diff`（往返不破） | 1 | 三态（`Expecting code to raise a throwable.`） | 核心判据承重 ✓ |

⇒ **除 N1 外，我构造的 17 条变异无一留下"删了不红"的行。**

### Minor-1：`M-3` 的「8 处」在 `13b96a8` 上已过期（应为 9 处 / 18 行）

- 简报 ⑤ 与报告「M-3 更正」写的 `RoundTripAssertionsTest` 7 处（…/92）+ `DriftTest` 1 处（26）= 8 处，
  **在它测量的 `ec2f10d` 上逐字正确**（我实测复现，见结论 6 甲）。
- 但本轮的修复改变了文件：同一个命令在 `13b96a8` 上得到 **9 处 / 18 行**
  （Test 23/28/43/61/69/80/**99**/**122** + DriftTest **35**）。
- 影响：无代码影响（该数字只出现在报告与简报的叙述里）。**但本仓纪律要求数字必须实测**，
  且 M2~M4 若照抄这段"8 处"会得到对不上的结果。**建议**：在报告「M-3 更正」处补一句
  「以上行号对应 `ec2f10d`；`13b96a8` 因新增用例变为 9 处（Test 23/28/43/61/69/80/99/122 + DriftTest 35）」。
  这是**改报告即可**的项，不需再修代码，也不单独构成"再修一轮"的理由。

### 复核通过、不构成新发现的两点（避免下次重复记）

- **`13b96a8` 的报告数字与门禁我独立复核为真**（在 `/tmp/m3check` 副本上，非本仓工作树）：

  ```
  Tests run: 155, Failures: 0, Errors: 0, Skipped: 0        # RoundTripAssertionsTest 5 + DriftTest 1
  Spotless.Java is keeping 54 files clean - 0 needs changes to be clean
  You have 0 Checkstyle violations.
  BugInstance size is 0
  BUILD SUCCESS
  ```

  ⇒ 「155 条」「154→155」「6 条」「Spotless/Checkstyle/SpotBugs 全绿」**均属实**。
- **提交信息带 `Co-Authored-By: Claude Code <noreply@anthropic.com>`**（`ec2f10d` 缺、本轮已补）—— 已核实。

---

## 我**未能核实**的项（如实列出，勿当作已验）

1. **未跑全仓 `./mvnw clean verify`**。我只在副本里跑了 `-pl simos-util`。
   按 CLAUDE.md，本机（`/home/cna`）`~/.m2` 里的 `agentlib-mosire` 仍是 49 类的陈旧构建，
   故 `simos-core` 的 `AgentLibAvailabilityTest` **很可能红**——**这是我的推测，未实测，请勿引用为结论**。
   本轮改动只涉及 `simos-util` 的两个**测试**文件，未增删任何跨模块符号，我判断它对 `simos-core` **无影响**，
   但"无影响"同样是推断。
2. **变异未穷尽**。我做了 24 条变异（D1–D5 及其变体 9 条、R1–R7 7 条、V6a/V6b 2 条、S1 1 条、N1–N8 5 条），
   **不能**声称"不存在其它零判别力行"。N1 是我构造出的第 18 条变异才发现的 —— 这本身就说明"未找到"≠"不存在"。
3. **未比对控制器当时运行 `t10resid/resid.py` 的原始输出**。我做的是**独立重跑**（自建 `/tmp/rr_audit`），
   结论与控制器 ⑤ 的六个数字**逐条吻合**；但"控制器当时确实跑出了这些数"这一事实，我无法背书。
4. **未核实 `+1` 那条修正的"计划原版对照"**（即简报所说的计划文档 `:2765-2781`）。
   我只验证了 `+1` 存在时行为正确、以及 `expectedActual` 与 `actual` 的构造关系；
   "照抄计划原版会怎样"我未去读那份计划文档比对。
5. **未做"等价变换类"变异**（如 `target.equals(applied)` 改 `applied.equals(target)`、
   `!x` 改 `x == false`）。这类对 record 而言语义等价，我判断不构成缺陷，但**未实测**。

---

## 裁决建议

**需再修一轮**（一轮即可关账；本轮已交付的 5 项修复**全部核实成立**，不要再动它们）。

**最小修复项（唯一一条）**：

- 让 `RoundTripAssertionsDriftTest`（或三态用例）**也走一次 `assertSnapshotRoundTrip`**，
  使 `RoundTripAssertions.java:42` 的 `checkApplied` 调用获得判别力。
  验收口径：**删掉 impl 第 42 行 → 该用例必须转红（`Expecting code to raise a throwable.`）**；
  基线 6 条（或 7 条）全绿；`./mvnw -pl simos-util clean verify` 全绿。
- **实现文件 `RoundTripAssertions.java` 仍不应改动**（本轮它的行为是对的，缺的是测试）。

**可一并顺手改的（不构成再修理由）**：

- Minor-1：在报告「M-3 更正」处补一句行号已随 `13b96a8` 变为 9 处（Test 23/28/43/61/69/80/99/122 + DriftTest 35）。

---

## 附录：本轮复审用到的复现脚本（均在 `/tmp`，不在本仓）

| 路径 | 用途 |
|---|---|
| `/tmp/rr_audit/canon/` | 从 `git show 13b96a8:` 落盘的三份权威源（md5 已自证） |
| `/tmp/rr_audit/audit.py` | 变异/编译/跑测/解析 XML 的骨架；`restore()` 只从 `canon/` 还原，每次打印 impl md5 |
| `/tmp/rr_audit/m_del2.py` | 结论 1（D1–D5 及 D1b/D2b/D3b/D4b） |
| `/tmp/rr_audit/m_drift.py` | 结论 3（V6a / V6b） |
| `/tmp/rr_audit/m_swap.py` | 结论 5（R1–R7） |
| `/tmp/rr_audit/probe/.../ProbeN1.java` | 新发现 Important-1 的独立探针（不依赖 JUnit） |
| `/tmp/m3check/` | 仓库副本，用于结论 6（Maven 输出）与门禁复核 |
| 日志 | `del2.log` / `swap.log` / `swap56.log` / `newprobe.log` / `n7.log` / `m3check_out.txt` / `m3check_ec.txt` / `m3check_verify.txt` |

**纪律**：未派任何子代理；未 `git add`、未提交、未推送；未改动本仓任何文件
（开工与收工的 `git status --porcelain` 逐字相同；三份被复审文件 md5 与 `13b96a8` 一致）。
