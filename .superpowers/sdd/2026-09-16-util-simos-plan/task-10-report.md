# Task 10 报告：往返不变式框架（`verify` 包）

**状态：DONE**
**commit：`ec2f10d`** `feat(util): 往返不变式框架 + 漂移自证（M1 Task 10）`（**未推送**；提交时 HEAD 领先 `origin/feat/m1-util-simos` 11 个提交）
**surefire 计数：149（改前，实测）→ 154（改后，实测）＝新增 4 + 1**
**一行测试结论：`./mvnw -pl simos-util clean verify` 全绿（154 tests / 0 failures；Spotless 0 待改；Checkstyle 0 违规；SpotBugs BugInstance 0）**

---

## 交付物

| 文件 | 行数 | 说明 |
|---|---|---|
| `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java` | 62 | 框架本体（main 源码，不依赖 JUnit） |
| `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java` | 136 | 4 条用例 |
| `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsDriftTest.java` | 66 | 1 条漂移自证用例 |

三个文件均为 brief 原文的逐字转写；`git diff --cached --stat` 为 **3 files changed, 264 insertions(+), 0 deletions(-)**，
`--name-only` 确认只含这三个文件（`.superpowers/*` 的既有改动与未跟踪的 `.serena/` **未**入库）。

---

## 各步实跑记录

### 前置：接口核对（未改任何既有文件）

逐个读了 brief 声明"Consumes"的 5 个类型，签名全部对得上，无需改动：

- `Snapshot.ref()` / `timestamp()` / `namespace()` ✓（`state/Snapshot.java:12-17`）
- `ChangeSet.baseRevision()` ✓（`state/ChangeSet.java:9`）
- `RevisionId(long value)` + `compareTo` ✓、`BranchId(String value)`（空白校验）✓、`StateRef(BranchId, RevisionId)` ✓
- `SimosTimestamp.of(long tick)` ✓

`io.mosire.simos.util.verify` 包在 Task 10 之前**不存在**，本次为全新建包。

### Step 3：确认失败（期望编译失败）

```
./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test
```

退出码 **1**，编译失败。为给出精确计数，我把实现文件临时移出后复跑了一次：

- `cannot find symbol` 共 **8 处**（**在 `ec2f10d` 上**的实测值；**已更正**，时效限定见文末「M-3 更正」），
  全部形如 `symbol: variable RoundTripAssertions`（`RoundTripAssertionsTest.java` 的 23/28/43/61/69/80/92 共 7 处
  + `RoundTripAssertionsDriftTest.java` 的 26 共 1 处）。

**⚠️ 与 brief 的一处措辞偏差（现象级，无实质影响）**：brief Step 3 写的期望是
`cannot find symbol: class RoundTripAssertions`，实际 javac 报的是 **`variable`** 而非 `class`——
因为 `RoundTripAssertions` 在用例里只作为静态方法调用的**限定符**出现，javac 由此把它归类为变量符号。
红的性质与 brief 预期一致（符号不存在），只是名词不同。**未改动任何代码来迁就它。**

### Step 5：确认通过

```
./mvnw -q -pl simos-util -Dtest='RoundTripAssertions*Test' test     # 退出码 0
```

`-q` 抑制了 surefire 的逐类汇总，故计数取自 surefire 报告（非标准输出）：

```
io.mosire.simos.util.verify.RoundTripAssertionsTest      Tests run: 4, Failures: 0, Errors: 0
io.mosire.simos.util.verify.RoundTripAssertionsDriftTest Tests run: 1, Failures: 0, Errors: 0
```

5 条用例名（从 `TEST-*.xml` 读出，与 brief 一一对应）：

1. `aCorrectRoundTripPasses`
2. `aMisStampedChangeSetIsRejected`
3. `aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages`
4. `aBrokenRoundTripReportsAllThreeStates`
5. `aChangeSetThatDropsAFieldMustBeCaught`（漂移自证）

### Step 6：格式化与提交

```
./mvnw -q spotless:apply                       # 退出码 0
git add simos-util/src/main/java/io/mosire/simos/util/verify/ simos-util/src/test/java/io/mosire/simos/util/verify/
git diff --cached --stat                       # 3 files, 264 insertions(+), 0 deletions(-)
git commit -m "feat(util): 往返不变式框架 + 漂移自证（M1 Task 10）"
```

`spotless:apply` **确实改动了文件**——按 CLAUDE.md 既有约定，google-java-format 按字符数重排了中文
Javadoc/注释的折行（例如 `ToySnapshot.apply` 上方注释里 `**base 的**` 被折到了下一行）。
这是预期行为，**我没有手工回调行宽**；格式化后复跑 Step 5 的 5 条用例仍全绿。

---

## surefire 计数（实测两个数）

| 时点 | 命令 | 结果 |
|---|---|---|
| 改前（T9 关账态） | `./mvnw -pl simos-util test` | **Tests run: 149**, Failures: 0, Errors: 0 |
| 改后 | `./mvnw -pl simos-util test` | **Tests run: 154**, Failures: 0, Errors: 0 |

改前基线是**实跑**得到的，不是从陈旧报告推断的（虽然 `target/surefire-reports` 里的旧报告恰好也合计 149）。
差值 5 = 新增 4（`RoundTripAssertionsTest`）+ 1（`RoundTripAssertionsDriftTest`），与 brief Step 5 的期望一致。

---

## 附加验证 A：5 处守卫的判别力（变异测试）

控制器已在 worktree 外预跑过，我**独立重做了一遍**——这是本里程碑最贵的教训，值得二次确认。
方法：对 HEAD 版本的实现依次施加 **5 次"删除式"变异**（每次只删一处守卫、跑完即从 git 还原），
再读 surefire XML 判定**哪条用例转红**及**实际失败报文**（后者用来判定"红的是不是被保护的那一行本身"）。
脚本在 `/tmp/mutate.py`、`/tmp/mutate_msg.py`（**仓库外**）。

| 变异 | 被删的守卫 | 转红用例 | 实际失败报文要点 → 红在哪一行 |
|---|---|---|---|
| M1 | `assertRoundTrip` 的 `diff 返回 null` | 1 条：`aNullReturningDiffOr...WithFieldLevelMessages` | 实抛 NPE `Cannot invoke "...ToyChangeSet.baseRevision()" because "changeSet" is null`，**不含** `diff 返回 null` → 红在第 1 条断言（正是被保护的那条） |
| M2 | `assertSnapshotRoundTrip` 的 `diff 返回 null` | 1 条：同上方法 | 实抛 NPE `Cannot invoke "...ChangeSet.baseRevision()" because "changeSet" is null`，栈顶 `at ...RoundTripAssertions.assertSnapshotRoundTrip` → 红在第 3 条断言（正是那份被删的守卫） |
| M3 | 版本戳守卫 | 1 条：`aMisStampedChangeSetIsRejected` | 实抛的是**往返破裂**报文（其 `ToyChangeSet[...baseRevision=...]` 含 "baseRevision"），但**不含** `必须相对它被施加的 base` → 印证 brief 注释：needle 必须钉守卫自己的文案 |
| M4 | `checkApplied` 的 `apply 返回 null` | 1 条：`aNullReturningDiffOr...WithFieldLevelMessages` | 实抛 `AssertionError`（非 NPE）→ 红在 `isInstanceOf(NullPointerException.class)`，与 brief 注释预测一致 |
| M5 | 往返破裂断言 | **2 条**：`aChangeSetThatDropsAFieldMustBeCaught` + `aBrokenRoundTripReportsAllThreeStates` | 两条均为 `Expecting code to raise a throwable.` → 红在被保护的那一行 |

**结论：5 处守卫全部具备判别力，且每次红都红在被保护的那一行本身。**

**⚠️ 与控制器描述的一处偏差**：控制器说"每次都**恰好**打红一条用例"，实测 **M5 打红了 2 条**。
我判定这是**正确行为而非缺陷**——`aBrokenRoundTripReportsAllThreeStates` 与漂移用例本就都依赖往返破裂断言，
删掉它两条都该红。M1/M2/M4 打红的是**同一个方法**（该方法含 3 条断言，分别盖 3 处守卫），
故"方法级"不唯一、但"断言级"各自唯一——报文证据已逐条确认。

> 另注：`aCorrectRoundTripPasses`（happy path）不被这 5 次变异中的任何一次打红，符合预期。
> 它的判别力由附加验证 B 证明。

---

## 附加验证 B：控制器修正 `+ 1` 是否真的承重（instruction #2）

**B-1（计划原版的 `ToySnapshot.apply`）**：把 `new RevisionId(changeSet.baseRevision().value() + 1)`
改回计划草图的 `new RevisionId(changeSet.baseRevision().value())` →
**`aCorrectRoundTripPasses` 果然转红**，报文为 `往返不变式破裂：apply(diff(base, target), base) 与 target 不等。`。
**证实 instruction #2 的判断**：照抄计划草图会让 `target = ref(2)` 永远不可达，Step 5 声称 PASS 的那条必然红。

**B-2（漂移用例的判别力是否被救回）**：用探针程序（`/tmp/probe/DriftProbe.java`，编译于 `simos-util/target/classes`）
把两种 `apply` 的抛出报文原样打出来对比：

| apply 版本 | 报文里 `target` vs `actual` 的差异 |
|---|---|
| 控制器修正版（`+ 1`） | `target.revision=2` vs `actual.revision=**2**`（**相同**），差异**只有** `beta=9` vs `beta=2` |
| 计划原版（`= baseRevision()`） | `target.revision=2` vs `actual.revision=**1**`，差异**多了一个 ref 不匹配** |

**证实 brief 注释的说法**：计划原版下漂移用例**仍然是红的**（`beta=9`/`beta=2` 两个 needle 照样满足），
但红的理由里平白多了一个 ref 不匹配——"抓到了 beta 漂移"这件事就不再被证明。
控制器的 `+ 1` 修正**确实救回了这条用例的判别力**，两半（Toy 的 happy path、Drift 的判别力）都承重。

---

## 关账前的全量门禁（顺手为 T11 跑）

```
./mvnw -pl simos-util clean verify
```

**BUILD SUCCESS**，明细：

- Surefire：**Tests run: 154**, Failures: 0, Errors: 0, Skipped: 0
- Spotless：`54 files clean - 0 needs changes to be clean`
- Checkstyle（validate 阶段，`failOnViolation=true`）：`You have 0 Checkstyle violations.`（单独复跑 `validate` 确认它确实执行了，非"静默跳过"）
- SpotBugs：`BugInstance size is 0` / `Error size is 0`

**注意**：这是 `-pl simos-util` 的单模块 verify，**不是全仓 verify**（见下方未核实清单第 2 条）。

---

## 我**没能核实**的事项（如实列出，勿当作已验）

1. **控制器所述"用 junit-platform-console-standalone-1.11.4 + assertj-core-3.27.7 预跑"这一事实本身未核实**。
   我只是转写后经 Maven/Surefire 复跑；**没有**去核对本机 classpath 上实际的 JUnit / AssertJ 版本号是否就是那两个。
   brief 的代码在我这次实跑下全绿，但"与控制器预跑环境完全同一套版本"这点我无法背书。
2. **未跑全仓 `./mvnw clean verify`**（只跑了 `-pl simos-util`）。因此我**不能**声称整个 reactor 是绿的。
   尤其：CLAUDE.md 记载**本机（`/home/cna`）`~/.m2` 里的 `agentlib-mosire` 仍是 49 类的陈旧构建**
   （未按 118 类重建），故 `simos-core` 的 `AgentLibAvailabilityTest` 很可能红——**这一点我未实测，
   属推测，请勿引用为结论**。T11 关账跑全仓前，建议先按 CLAUDE.md 的提示在本机重建 agentlib。
3. **未核实 M5 打红 2 条与控制器所述"恰好一条"的差异究竟源于何处**（是否控制器当时对 M5 用的是另一种变异、
   或把两条算作了一处）。我只实测到"打红 2 条，且两条都红得合理"，未去追溯控制器当时的记录。
4. **未做除这 5 处守卫之外的变异**（例如对 `assertRoundTrip` 的泛型重载缺少版本戳守卫这一点未做变异探查）。
   brief 明确该重载不做版本戳校验（"通用：任何状态类型"），我按原样转写，未质疑该设计。
5. **未验证实现计划文档 `:2765-2781` 的行号内容**是否与控制器描述的字面一致
   （我按 instruction #2 的指示**未照抄**该草图，而是以 brief 为准；但我没有去读那份计划文档的对应行做比对）。

---

## 方法与纪律记录

- **未派任何子代理**；未派评审。
- **未 `git add -A`**；提交前扫过 `git diff --cached --name-only`，确认只含目标三个文件。
- **未推送**（HEAD 领先 origin/feat/m1-util-simos 11 个提交，全部为本任务之前就存在的本地提交）。
- 变异/探针脚本全部写在**仓库外**（`/tmp/`），且每次变异后用 `git checkout --` 或移文件还原；
  任务结束时 `git diff HEAD -- simos-util/` **为空**，工作树对该模块零残留。
- 全仓检索一律用 `git grep`（按本仓纪律，本机 `grep` 是 ugrep，会静默尊重 `.gitignore` 并跳过隐藏目录）。
- 本报告涉及的每个"实测"数字都来自命令原始输出；凡是推断/推测均已显式标注。

---

# 追加：T10 评审 fix round 1（I-1 / I-2 / M-1 / M-2 / M-3）

**状态：DONE** ｜ **commit：`13b96a8`** ｜ 单提交，**未推送**
**一行结论：`./mvnw -pl simos-util clean verify` 全绿——`RoundTripAssertionsTest` 5 条 + `DriftTest` 1 条 = 6 条，模块总数 154 → 155（对 T9 基线即 149 → 155）；7 条变异自证逐条符合预期。**

## 本轮改了什么

评审判定：`RoundTripAssertions` 本体**一行不动**（五条守卫 5/5 承重），缺陷全在**断言自称的判别力 > 实际判别力**，
且全部出自控制器四次修订的简报文本。故本轮只**重写两条断言 + 新增一条用例**：

| 文件 | 改动 | 对应 |
|---|---|---|
| `RoundTripAssertionsTest.java` | `aBrokenRoundTripReportsAllThreeStates` 改钉**整条 dump 行**（4 行 + 提示行） | I-1 / M-1 |
| `RoundTripAssertionsTest.java` | 新增 `theFrameworkDoesNotRequireSnapshotImplementations` + `PlainState` / `PlainChangeSet` | M-2 |
| `RoundTripAssertionsDriftTest.java` | 改钉 **`actual` 整行**（`expectedActual`） | I-2 |

**转写方式**：本轮我**没有手工打字**，而是用脚本（`/tmp/extract.py`）从简报里**逐字抽取 fenced java 代码块**直接落盘，
以杜绝空白/折行转写误差；随后 `./mvnw -q spotless:apply` 重排折行。抽取后先与 HEAD 逐一 diff 确认改动范围，
并确认**简报的 impl 代码块与 HEAD 的 impl 只差 Javadoc 折行**（即 impl 无需改动，与"一行不动"一致）。
实测：`git diff --stat -- simos-util/src/main/` 在全程恒为空。

## 七条变异自证（本轮核心，逐条**看实际失败报文**）

每条独立施加、跑完立即还原；脚本 `/tmp/mutate7.py`、`/tmp/mutate7_detail.py`、`/tmp/v6_only.py`（**仓库外**）。

| # | 变异 | 期望 | **实测** |
|---|---|---|---|
| V1 | 删 impl 的 `+ "  base      = "` | 三态用例红 | ✅ **红 1 条**：报文里 base 行退化成裸对象、`  base      = ` 标签消失 → needle 失配 |
| V2 | 删 impl 的 `+ "\n  target    = " + target` | 同上红（**改前它照样绿**） | ✅ **红 1 条**：报文从 base 行直接跳到 actual 行。**改前该项目针是裸 `"target"`，被报文首行无条件满足 → 确实空转；现已转红** |
| V3 | 删 impl 的 `+ "\n  actual    = " + applied` | "同上红" | ⚠️ **红 2 条**（三态用例 **+** 漂移用例）——见下方"偏差" |
| V4 | 删 impl 的 `+ "\n  changeSet = " + changeSet` | 同上红（改前无人察觉） | ✅ **红 1 条** |
| V5 | 删末行提示（换成 `+ "");` 保语法） | 同上红（改前无人察觉） | ✅ **红 1 条** |
| V6 | **漂移 beta→alpha**（changeSet 带 beta、diff 漏 alpha、apply 保 `+1`） | 漂移用例必须红（**改前它全绿**） | ✅ **红 1 条**，且红在 actual 行 —— 见下方"V6 的决定性证据" |
| V7 | 给 `assertRoundTrip` 的 `S` 加回 `extends Snapshot` | 编译失败 | ✅ **`compiled=False`**，编译错误正落在新用例上 |

### 偏差：V3 实测红 2 条（控制器表格写"同上红"）

V3 删掉 `actual` 行后，**三态用例与漂移用例都转红**——因为 I-2 的修法让漂移用例也钉了 `"  actual    = " + expectedActual`，
两条用例都依赖这一行。我判定这是**本轮修法的正确后果，不是缺陷**（删掉被保护的行，两条依赖它的用例都该响）。
该偏差**对我有利地说明了 I-2 的修法生效**：修法前漂移用例完全不碰 actual 行，V3 时它不会红。

### V6 的决定性证据（I-2 的修法确实堵住了 W1）

V6 下漂移用例转红，报文三段（实测原文节选）：

```
  base      = DriftingSnapshot[…, revision=RevisionId[value=1], …, alpha=1, beta=2]
  target    = DriftingSnapshot[…, revision=RevisionId[value=2], …, alpha=5, beta=9]
  actual    = DriftingSnapshot[…, revision=RevisionId[value=2], …, alpha=1, beta=9]   ← 漂移在 alpha
needle: "  actual    = DriftingSnapshot[…, alpha=5, beta=2]"  ← 钉的期望值，失配
```

`actual` 与 `target` **只差 `alpha`**（5 vs 1），`beta` 两边都是 9 —— 即"差异出在 alpha"，而钉的 `expectedActual`
带的是 `alpha=5`，故失配转红。**这正是"漂移挪位后断言仍然抓得住"的直接证明。**

**同一条报文同时自证了改前的空转**：该报文里 `base` 行含 `beta=2`、`target` 行含 `beta=9`，
而**这两行是无条件打印的**——所以改前那两条 `hasMessageContaining("beta=9")` / `("beta=2")`
**在这条报文上都会被满足**，用例会**全绿**。这不需要另跑一次就能从上面报文直接读出。

### V7 的编译错误原文

```
RoundTripAssertionsTest.java:[122,36] method assertRoundTrip … cannot be applied to given types;
  reason: inference variable S has incompatible bounds
    upper bounds: …PlainState, io.mosire.simos.util.state.Snapshot
```

落在**新用例第 122 行**的调用上——正是 M-2 缺口被堵住的证据。

## M-3 更正：`cannot find symbol` 是 **8 处**，不是 16 处

> **★ 时效限定（复审 Minor-1 / 简报 ⑦）：以下"8 处"是 **`ec2f10d` 上**的实测值，不是当前状态。**
> 该计数**随测试文件的新增用例而变**：`13b96a8` 上是 **9 个位置 / 18 行**，
> 当前 `54ef235` 上是 **10 个位置 / 20 行**（详见文末「修复轮 2」一节的三提交实测对照）。
> 引用本节的数字时**必须带上是哪个提交**。

上文 Step 3 原写"**16 处**…`DriftTest` 多处"，**计数与措辞均不准**，已就地改为 8 处并标注。更正依据（已实测）：

- **不同 (文件, 行) 位置数 = 8**（**在 `ec2f10d` 上**）：`RoundTripAssertionsTest.java` 23/28/43/61/69/80/92（7 处）
  + `RoundTripAssertionsDriftTest.java` 26（1 处）——**与评审的 8 处逐行吻合**。
- 我原先数到的 **16 是"包含 `cannot find symbol` 的原始行数"**：实测这 8 条错误**每条被 Maven 打印了两遍**
  （同一字符串出现次数分布恰为 `{2: 8}`），8 × 2 = 16。故两个数字都能解释，**8 才是"处"的正确含义**。
- 行号与"javac 报 `variable` 而非 `class`"这两个关键现象**均属实**，原报告这部分无需改动。

## 本轮实测计数

| 时点 | 命令 | 结果 |
|---|---|---|
| 本轮改前（= `ec2f10d`） | `./mvnw -pl simos-util test` | 154 tests / 0 failures |
| 本轮改后 | `./mvnw -pl simos-util test` | **155 tests / 0 failures** |
| 本轮改后（门禁全量） | `./mvnw -pl simos-util clean verify` | **BUILD SUCCESS**；Spotless 54 files clean / 0 待改；Checkstyle 0 违规；SpotBugs `BugInstance size is 0` |

`verify` 包内：`RoundTripAssertionsTest` **5** 条 + `RoundTripAssertionsDriftTest` **1** 条 = **6**，与简报 Step 5 一致。

## 本轮未核实事项

1. **仍未跑全仓 `./mvnw clean verify`**（只跑 `-pl simos-util`）。故我**不能**声称整个 reactor 是绿的；
   `simos-core` 的 `AgentLibAvailabilityTest` 是否因本机 49 类陈旧 agentlib 而红，**我本轮同样未实测**（推测，勿引用）。
2. **未核实评审的 W1 / P1 / P2 / P4 / P5 / S1 / W6-V4 原始记录本身**。本轮我**重做**了对应的变异（V1~V7），
   结果**与评审结论方向一致**（尤其 V6 与 V2 两条"改前空转"的，我实测确已转红），但我没有去比对评审当时的原始输出。
3. **未验证简报"控制器修正说明"⑤节的残留边界记账**（`actual` 与 `changeSet` 两份 dump 在本用例里都含
   `alpha=5, beta=2]`，若把实现里的 `+ applied` 误写成 `+ changeSet` 本用例抓不住）——**我未做该变异**，
   故对该边界是否确实存在**未核实**（控制器明示"不修，记账"）。
4. **未做 V1~V7 之外的变异**（例如把 impl 里某行拼**错对象**而非删行）。
5. `git show ec2f10d` 的提交信息**缺 `Co-Authored-By` 尾注**，与本仓 20 条内 17 条的惯例不符——**这是我的疏漏**，
   本轮 `13b96a8` 已补上。**未回改 `ec2f10d`**（避免改写已提交历史），如需统一请控制器裁决。

## 本轮方法与纪律

- **未派任何子代理**；未派评审。
- **未 `git add -A`**；提交前扫 `git diff --cached --name-only`，仅两个测试文件（impl 无改动故未入 staged）。
- **未推送**。
- 变异脚本全在 `/tmp/`，脚本带 `finally: restore()`；每条变异跑完即还原。收尾以**三重证据**确认零残留：
  三个文件 `md5sum` 与 `/tmp/t10_backup` 备份**逐一相同**、`git status --porcelain simos-util/` 只列两个测试文件、
  临时移走的 impl 已归位（`/tmp/impl_away.java` 不存在）。
- 简报代码块用**脚本逐字抽取**而非手工转写；impl 全程未改动（`git diff --stat -- simos-util/src/main/` 恒为空）。
- 全仓检索用 `git grep`。

---

# 追加：T10 修复轮 2（复审 Important-1：快照入口的往返判别力）

**状态：DONE** ｜ **commit：`54ef235`** ｜ 单提交，**未推送** ｜ **`impl` 一行未动**（纯测试补齐）
**一行结论**：`./mvnw -pl simos-util clean verify` 全绿——verify 包 **7** 条（`RoundTripAssertionsTest` **6** + `DriftTest` **1**），模块总数 **156** / 0 failures（155 → 156）；Spotless 0 待改、Checkstyle 0 违规、SpotBugs 0。

## 缺陷与修法

`assertSnapshotRoundTrip` 在版本戳守卫之后**自己又写了一遍** `checkApplied(...)`（`impl:42`），
与通用入口 `impl:26` 是**两份独立调用点**。删掉 `impl:42` ⇒ 快照入口的往返破裂**零护栏**，
而原有 6 条用例的三处快照入口调用点分别是（a）往返正确、（b）被版本戳守卫先拦下、（c）只到 `diff 返回 null` 守卫——
**没有一条**是"版本戳正确 **且** 往返破裂"。

**修法**：按简报 ⑥ 新增 `aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught`（`@Test` 桩版本戳**正确**、
只漏 `beta`，故版本戳守卫不会替我们拦下；needle 钉 `checkApplied` 独有的措辞"往返不变式破裂"，
排除"因错误的原因转红"）。代码**逐字取自简报 ⑥ 的围栏**（脚本 `/tmp/extract6.py` 抽取，非手敲），
用 `git diff --cached` 全文扫过后提交。插入位置：`theFrameworkDoesNotRequireSnapshotImplementations` 之前 —— 与简报要求一致。

## 验收口径三条（⑥ 要求，逐条实跑）

### ① 7 条全绿 ✅

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in …RoundTripAssertionsDriftTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in …RoundTripAssertionsTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
模块全量：`Tests run: 156, Failures: 0, Errors: 0, Skipped: 0`。

### ② 删掉 `impl:42` → 转红，且只红新用例 ✅（**哈希与控制器逐字吻合**）

```
canon md5   = ced4054ba48681fbe7ad4152cf69465b      （= 控制器给的 canon `ced4054b`）
变异 md5    = 23c771495cf0de853a0023bf3ab856c7      （= 控制器给的变异 `23c77149`）
```

原始输出：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in …RoundTripAssertionsDriftTest
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in …RoundTripAssertionsTest

### 红：aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught
    Expecting code to raise a throwable.
```

**转红用例数 = 1**，正是新用例 ⇒ 它是 `impl:42` 的**唯一**守卫。

> **⚠️ 必须说明的一处出入（简报要求我贴 `but did not` 那行，但该行不存在）**：
> 简报 ⑥ 验收口径②让我"附上红名 + `but did not` 那行"。**实测该行在本变异下不存在**——
> 我把红用例的**完整 message（含堆栈文本）**原样打出核过：全文只有 `Expecting code to raise a throwable.` 一句，
> `but did not` 出现次数为 **0**（`/tmp/accept2_full.py`）。
> **原因是机制性的**：删掉 `impl:42` 后**根本没有任何异常被抛出**，`assertThatThrownBy` 在其**自身**的
> "是否有 throwable" 这一关就失败了，**根本走不到** `hasMessageContaining` 的比对；
> 而 `but did not` 是 AssertJ 在"**有** throwable、但**消息**不含 needle"时才打印的措辞。
> ⇒ 这条**不是**我漏跑，是简报**预设的产物形态与实际变异不符**；`but did not` 形态见上一轮的 V3/V6
> （那两条是"抛了、但 needle 失配"）。**我未为了让报告好看而改写或伪造该行。**

### ③ 还原 `impl` 后 `git diff --stat simos-util/src/main/` 为空 ✅

```
=== 验收③：impl 是否已还原 ===
(空=已还原)
=== impl md5 ===
ced4054ba48681fbe7ad4152cf69465b      ← 与 canon 一致
```
还原后复跑 7 条仍全绿。全程 `git diff --stat -- simos-util/src/main/` **恒为空**。

## M-3 时效更正（简报 ⑦）：计数随提交而变，三提交实测对照

复审指出我上轮把"某一提交的计数"写成了"当前状态"。**我本轮自己重测了三个提交**（方法同前：移走 impl、跑
`-Dtest='RoundTripAssertions*Test'`、按 `(\w+\.java):[行,列]` 去重；脚本 `/tmp/m3_counts.py`）：

| 提交 | 不同 (文件,行) **位置数** | 原始**行数** | 分布 | 位置明细 |
|---|---|---|---|---|
| `ec2f10d` | **8** | 16 | `{2: 8}` | Test 23/28/43/61/69/80/92 + DriftTest 26 |
| `13b96a8` | **9** | 18 | `{2: 9}` | Test 23/28/43/61/69/80/99/122 + DriftTest 35 |
| `54ef235`（当前） | **10** | 20 | `{2: 10}` | Test 23/28/43/61/69/80/99/122/**140** + DriftTest 35 |

- **我独立复现了 ⑦ 给的 `13b96a8` 数字（9 位置 / 18 行，行号逐个吻合）**，不是照抄。
- 每个提交上分布恒为 `{2: N}` ⇒ 再次印证"Maven 把每条错误打印两遍"，故**行数 = 位置数 × 2** 恒成立。
- 新增用例在 `54ef235` 上多带来**一个**调用点（Test:140）。**引用这些数字时必须带上提交限定**，
  报告上文 Step 3 与「M-3 更正」两处已同步加上「在 `ec2f10d` 上」的限定。

## 本轮未核实 / 未能跑成的事项

1. **仍未跑全仓 `./mvnw clean verify`**（只跑 `-pl simos-util`）。故**不能**声称整个 reactor 是绿的；
   `simos-core` 的 `AgentLibAvailabilityTest` 是否因本机 49 类陈旧 agentlib 而红，**本轮同样未实测**（推测，勿引用）。
2. **`but did not` 那行没能跑出来**——见 ② 的说明：该措辞在本变异下**机制性地不存在**，
   我给出了实际存在的等价原始输出（红名 + 完整 message），**未伪造该行**。
3. **未核实复审者与控制器那两次独立的 `ced4054b → 23c77149` 实测记录本身**。我只**独立重跑**并得到**逐字相同的两个哈希**
   （canon `ced4054b…`、变异 `23c77149…`），与该结论一致；但没去比对他们的原始输出。
4. **未验证简报 ⑤ 节"残留边界已被证伪"那段**（控制器/复审称六种对象/标签互换变异全部转红）。
   该段**不在本轮修复范围**，**我未重跑**，故对其结论**未核实**。
5. **未做 ⑦ 之外的其它报告措辞改动**；报告上文除 M-3 两处加时效限定外**未重写**。

## 本轮方法与纪律

- **未派任何子代理**；**未 `git add -A`**；提交前**逐一扫过 `git diff --cached` 全文**（18 行，仅新用例一个 hunk）；**未推送**（ahead 13）。
- 提交信息的**两段 `-m`**：第二段为 `Co-Authored-By: Claude Code <noreply@anthropic.com>`（本仓当前约定，已核对尾注确实写入）。
- 新用例**逐字取自简报围栏**（脚本抽取），未手敲。
- `impl` 全程未动：验收③ 的空 diff 为证；变异用的 canon/变异两份都由 `/tmp` 备份与还原闭环，收尾 md5 复核一致。
