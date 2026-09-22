# Task 10 独立评审：往返不变式框架（`RoundTripAssertions`）

**评审范围**：`aaa9489..ec2f10d`（单提交 `ec2f10d`，3 文件 +264，全部新增）
**评审性质**：只读。未改动工作树任何文件；全部实验在 `/tmp/t10/` 拷贝上进行，仓库外的 javac + JUnit Console 独立跑。
**评审日期**：2026-09-16

---

## 0. 判定速览

| 判定 | 结果 |
|---|---|
| **A. spec 符合性** | **符合**（逐条见 §1；签名与 spec §9.2 逐字一致，语义无偏离；两处措辞差异见 §1 备注，均不影响交付物） |
| **B. 任务质量** | **Critical 0 / Important 2 / Minor 3**（见 §5） |
| 五条守卫的判别力 | **5/5 全部具备**，且每条删掉后红的都是被保护的那一行（实测见 §2） |
| brief 核查项②（needle 是否只出现在守卫报文里） | **成立**，双向实证 |
| brief 核查项③（钉 `beta=9`/`beta=2`） | **部分成立**：不是空转，但**不具备注释所声称的"定位到 beta"的能力**（反例 W1，见 §3.2）→ Important-1 |
| brief 核查项④（`+ 1` 修正） | **成立，且比 brief 说的更要紧**：不 `+1` 时漂移用例在"根本没漂移"的世界里照样绿（零判别力），见 §3.3 |

一句话结论：**护栏本体（五条守卫）经得起删；出问题的是两处"断言的实际判别力小于它自称的判别力"的报文 needle**——与 M1 期间那十二处缺陷同一形态，但危害面窄：不动摇往返不变式本身，只影响诊断报文与后续 M2~M4 会照抄的模板。

---

## 1. A. spec 符合性（逐条核对）

| brief/spec 要求 | 实现 | 判定 |
|---|---|---|
| `assertRoundTrip(S base, S target, BiFunction<S,S,C> diff, BiFunction<C,S,S> apply)` | `RoundTripAssertions.java:23-24`，`<S, C extends ChangeSet>`，**S 无上界** | 与 spec §9.2 逐字一致 |
| `assertSnapshotRoundTrip(...)`（同形，`S extends Snapshot`） | `RoundTripAssertions.java:30-31` | 一致 |
| 位于 main 源码、**不依赖 JUnit**，失败以 `AssertionError` 抛出 | 类在 `src/main`；`git grep -n "org.junit\|junit" -- simos-util/src/main` → **空**；两处不变式违规均 `throw new AssertionError`（:36、:49） | 一致 |
| `assertSnapshotRoundTrip` 额外守"变更集必须相对于它被施加的那个 base" | `:33-41`：`declared = changeSet.baseRevision()` vs `actual = base.ref().revision()`，不等即抛 | 一致（brief 原文的 `Objects.requireNonNull` 语义逐字转写） |
| 报文含 base / target / 实际结果三份 `toString()` + 一句定位提示（spec §9.2） | `:50-59`：四份 dump（base/target/actual/changeSet）+ 末行"提示：…这正是 L1 事故的形态。" | 一致（多给一份 changeSet，属 brief 原文，spec 未禁止） |
| §9.3 故意漂移用例必须抛 `AssertionError` | `RoundTripAssertionsDriftTest`：`DriftingSnapshot(ref,timestamp,namespace,alpha,beta)` + `DriftingChangeSet(baseRevision,timestamp,alpha)`（漏 beta），base/target 在 beta 上不同 | 一致 |
| 测试类名/路径（spec §十二） | `RoundTripAssertionsTest` / `RoundTripAssertionsDriftTest`，路径与 brief 一致 | 一致 |
| "框架不关心 `S` 是什么" | **源码层面成立**（`<S, C extends ChangeSet>` 无上界，`checkApplied` 对 S 无约束）；**用例层面未被钉住**——见 §3.4 的 S1 | 部分（见 Minor-2） |

**两处措辞差异（均不影响交付物）**

1. brief Step 3 的 `Expected: cannot find symbol: class RoundTripAssertions` 措辞错误。我独立复现（把实现文件从 `/tmp` 拷贝中移除后编译）：javac 报 **8 处** `cannot find symbol`，全部是 `symbol: variable RoundTripAssertions`（`RoundTripAssertionsTest.java` 的 23/28/43/61/69/80/92 行 + `RoundTripAssertionsDriftTest.java` 的 26 行）。**实现者未改代码迁就 brief 的错误期望，做法正确。**
2. spec §9.2 散文写"变更集必须相对**于**它被施加的**那个** base"，实现报文写"必须相对它被施加的 base"。散文给的是语义，needle 钉的是运行时文案，二者不构成偏离。

---

## 2. B-1 五条守卫逐条的判别力（我自己跑的变异，不是转述）

方法：把 `simos-util/src` 整体拷到 `/tmp/t10/`，从仓库**原始文件重置后**逐条施加"删除式"变异，重新编译，用 `junit-platform-console-standalone-1.11.4` + `assertj-core-3.27.7` 跑 5 条用例，读 XML 里的失败类型/报文。基线（无变异）**5/5 绿**。

| # | 守卫 | 位置 | 盖它的断言 | 删掉后实测 | 红的理由是否即被保护的那一行 |
|---|---|---|---|---|---|
| 1 | `diff` null（通用） | `RoundTripAssertions.java:25` | `RoundTripAssertionsTest.java:59-64`（`hasMessageContaining("diff 返回 null")`，:64） | 红 1 条：`aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages` | **是**。实抛 NPE `Cannot invoke "...ToyChangeSet.baseRevision()" because "<parameter1>" is null`（栈顶在 `ToySnapshot.apply`），不含 needle → 红在 :64 这条断言上。`isInstanceOf(NullPointerException.class)`（:63）仍满足，说明**是消息 needle 在承重**，与 brief 注释一致 |
| 2 | `diff` null（快照） | `RoundTripAssertions.java:32` | `RoundTripAssertionsTest.java:78-83`（同方法第 3 段，needle :83） | 红 1 条：同一方法 | **是**。实抛 NPE，栈顶 `at ...RoundTripAssertions.assertSnapshotRoundTrip(RoundTripAssertions.java:33)`（即 `changeSet.baseRevision()`），不含 needle → 红在 :83。**该守卫在两个公开方法里各写一遍，是两条独立断言分别盖的**，brief 对"同文件内不对称即是证据"的处理正确 |
| 3 | 版本戳 | `RoundTripAssertions.java:33-41` | `RoundTripAssertionsTest.java:41-50`（`isInstanceOf(AssertionError.class)` :49 + needle `"必须相对它被施加的 base"` :50） | 红 1 条：`aMisStampedChangeSetIsRejected` | **是**。实抛的是**兜底往返破裂报文**（`actual` 的 ref 是 `RevisionId[value=1000]`，因为 `apply` 照用了错戳 999 再 +1），该报文**不含** `必须相对它被施加的 base` → 红在 :50 |
| 4 | `apply` null | `RoundTripAssertions.java:47` | `RoundTripAssertionsTest.java:67-72`（`isInstanceOf(NullPointerException.class)` :71） | 红 1 条：`aNullReturningDiffOrApplyIsRejectedWithFieldLevelMessages` | **是**。实抛 `AssertionError`（报文里 `actual    = null`），异常类型就不对 → 红在 :71，与 brief 注释"删掉它不会抛 NPE 而是抛 AssertionError 并指错方向"逐字吻合 |
| 5 | 往返破裂 | `RoundTripAssertions.java:48-60` | `RoundTripAssertionsTest.java:90-100`（`:100` 的 `"actual"` needle）**与** `RoundTripAssertionsDriftTest.java:24-33` | 红 **2 条**：两条用例均 `Expecting code to raise a throwable.` | **是**（两条都红在"该抛却没抛"） |

补充：把版本戳守卫**方向反转**（只在相等时抛）→ 红 2 条（`aCorrectRoundTripPasses` 的**快照**那半 + `aMisStampedChangeSetIsRejected`），说明该守卫被双向钉住，不是只钉了一个方向。

**结论：五条守卫 5/5 覆盖，且每条被删时红的都是被保护的那一行。** 这一条 brief 与实现者报告的结论**我独立复现，成立**。

---

## 3. 四条核查项的独立验证

### 3.1 核查项②：`"必须相对它被施加的 base"` 是否只出现在版本戳守卫的报文里 —— **成立**

- 全仓检索（`git grep`，避开 ugrep 的静默漏搜）：该片段在**源码**中只出现两处——`RoundTripAssertions.java:37`（守卫自己的报文）与 `RoundTripAssertionsTest.java:50`（needle）。兜底报文（`:50-59`）不含它。
- 实证两个方向：
  - 删守卫（G3）→ 实抛兜底报文，needle 找不到 → 红（报文已逐字打印核对，其中确实含 `ToyChangeSet[baseRevision=RevisionId[value=999], ...]`）。
  - **把 needle 改成 `"baseRevision"`，守卫在也绿、守卫删也绿**（T1 / T1+G3 两次实测均 5/5 绿）→ 证明 brief 那段论证成立：钉 `"baseRevision"` 的断言在**两个世界里都满足**，是空转护栏。
- 结论：**brief 关于 `"baseRevision"` 的论证成立，`"必须相对它被施加的 base"` 这条 needle 有完整判别力。**

### 3.2 核查项③：漂移用例钉 `beta=9` / `beta=2` 的钉法 —— **部分成立（这是本次评审的主要新发现之一）**

**成立的部分**：`beta=9` 只在 target 的 dump 行里出现，`beta=2` 只在 base 与 actual 的 dump 行里出现；两者都是 record `toString()` 的真实片段，不是字段名的空转，且**删掉 target 行会立刻转红**（P2 实测：红的是漂移用例，needle `beta=9` 找不到）。所以"只钉 `beta` 字段名没有判别力"这一判断本身正确（整份 dump 两侧都含 `beta` 字样）。

**不成立的部分**：这对 needle **不具备注释所声称的"定位到 beta"的能力**，因为它分别由 **target 行**与 **base 行**满足，而这两行是**无条件打印**的。反例（W1）：把漂移从 `beta` 挪到 `alpha`（`DriftingChangeSet` 带上 `beta`、漏掉 `alpha`，`apply` 仍 `+1`），往返照样破裂，报文为

```
  base      = ... alpha=1, beta=2]
  target    = ... alpha=5, beta=9]
  actual    = ... alpha=1, beta=9]     ← 真正的差异在 alpha
```

两条 needle **同时被 base 行与 target 行满足** → 用例**全绿**。也就是说：只要报文里打印了 base 与 target，这对 needle 就必然满足，无论红的字段是不是 beta。

注释原文（`RoundTripAssertionsDriftTest.java:32-33`）写的是"两者必须同时出现，才证明红的是 beta"——**该断言在它自称失效的世界里没有失效**。这是 M1 那十二处缺陷的同一形态。修法已实证可行：改成只有 actual 行才可能满足的整段（如 `hasMessageContaining("alpha=5, beta=2]")`）后，
- 预期世界（漂移在 beta + `+1`）→ 绿（W2 实测）；
- 反例世界（不漏字段 + 计划原版 apply）→ 红（W5 实测，needle 找不到，报文已逐字核对）。

**结论：needle 有判别力但**没有**注释所声称的**定位力**；建议按 W2/W5 的形态收紧，或删掉注释里那句过强的断言。**

### 3.3 核查项④：`+ 1` 修正 —— **成立，且比 brief 说的更要紧**

| 变异 | 结果 |
|---|---|
| V1：`ToySnapshot.apply` 退回计划原版（`= changeSet.baseRevision()`，不 `+1`） | 红 1 条：`aCorrectRoundTripPasses`，报文即 `往返不变式破裂：...`，其中 `target` 的 `revision=2` vs `actual` 的 `revision=1` → 证实 brief"`target = ref(2)` 永不可达、Step 5 期望 PASS 的用例必然红" |
| V2：`DriftingSnapshot.apply` 退回计划原版（漂移仍在） | **5/5 全绿**——漂移用例**照样通过**（这符合 brief 预期：红得不是理由） |
| V3：漂移用例改成**不漏字段**（`ChangeSet` 带上 beta），`apply` 保持 `+1` | 红 1 条：`aChangeSetThatDropsAFieldMustBeCaught`（`Expecting code to raise a throwable.`）→ **这是漂移用例真正具备判别力的证据** |
| **V4 = V3 + `apply` 退回计划原版**（不漏字段 + 不 `+1`） | **5/5 全绿 —— 假绿**：`ChangeSet` 根本没漏字段，用例却通过 |
| W6（= V4，二次确认） | 同上，5/5 全绿 |
| W5：V4 + needle 换成 actual 行独有的 `alpha=5, beta=2]` | 红 1 条 → 说明收紧 needle 也能堵住这个假绿 |

**V4/W6 是本节最要紧的结果**：照抄计划原版时，漂移用例在"ChangeSet 没漏任何字段"的世界里**依然全绿**，即**零判别力**——它证明不了"抓得住漏字段"这件事本身。控制器的 `+ 1` 修正**确实救回了判别力**（V3 红 / 基线世界绿），且比 brief 的表述更关键：brief 只说"红得不是理由"，实际是"**连红都可能不是真的**"。

同时确认控制器对 `aCorrectRoundTripPasses` 的承重判断也对：不 `+1` 时红在 `assertThatCode(...).doesNotThrowAnyException()` 那条断言（V1 实测）。

### 3.4 我对 brief 核查项之外的两处新发现（见 §5 Important-1 / Minor-1、Minor-2）

---

## 4. 我独立做了什么（可复核）

环境（全部在仓库外，工作树零改动）：

```
/tmp/t10/src/{main,test}       ← 拷贝 simos-util 源码（37 main + 2 test）
javac 21.0.12 + junit-platform-console-standalone-1.11.4 + assertj-core-3.27.7
/tmp/t10/mutate.py             ← 变异驱动器：重置→变异→编译→跑→解析 XML
/tmp/t10/mutations{,2,3}.py    ← 变异清单（G*/P*/S*/T*/V*/W*）
```

| 实验族 | 内容 | 结果要点 |
|---|---|---|
| 基线 | 无变异跑 5 条用例 | 5/5 绿（与实现者一致） |
| G1~G5 | 五条守卫删除式变异 | 见 §2 表；G5 红 2 条 |
| G3b | 版本戳守卫方向反转 | 红 2 条（含 happy path 的快照那半） |
| P1~P6 | 逐行删掉兜底报文的组成（base / target / actual / changeSet / 提示 / 首行） | **P1（base 行）、P4（changeSet 行）、P5（提示行）、P6（首行）删掉后 5/5 全绿**；P2 只红漂移用例（靠 `beta=9`）；P3 只红 `aBrokenRoundTripReportsAllThreeStates`（靠 `"actual"`） |
| S1 | 给 `assertRoundTrip` 的 `S` 加 `extends Snapshot` 上界 | **5/5 全绿**——"框架不关心 S 是什么"没有任何用例钉住 |
| T1 / T1+G3 | needle 换成 `"baseRevision"`（守卫在 / 删） | 两个世界都绿 → 空转，印证 brief 论证 |
| T2 / V5 | 漂移 needle 只钉 `"beta"` | 绿（含"红得不是 beta"的世界）→ 字段名零判别力 |
| V1~V4、W2、W5、W6 | `+ 1` 修正的承重 | 见 §3.3；V4/W6 假绿是核心证据 |
| W1 / W1b | 漂移挪到 alpha | 用例仍绿；W1b 把报文原样打印出来核对 needle 来源 |
| X1 | 删实现文件后编译 | 8 处 `cannot find symbol`，全部 `symbol: variable RoundTripAssertions`（23/28/43/61/69/80/92 + 26） |

门禁的独立复核（同样在仓库外，未跑 `spotless:apply`）：

- **Spotless 等价检查**：`google-java-format` 1.28.0 与 1.30.0 分别 `--dry-run` 三个交付文件 → **无待改**（正向对照：故意改成未格式化的副本会打印文件名，证明工具确实在跑）。
- **Checkstyle**：用仓库的 `config/checkstyle.xml`（checkstyle 10.26.1）跑三个文件 → **0 违规**（正向对照：加一个 `import ...*;` 立刻报 `AvoidStarImport`，证明配置是活的）。四条规则人工复核亦通过（无 star import、无冗余/未用 import，import 全被用到）。
- **SpotBugs / enforcer / 全仓 reactor**：**未独立复核**，采信控制器的 `./mvnw clean verify` 实测（BUILD SUCCESS、0 ERROR、BugInstance 0）。
- **交付物与评审包一致性**：把评审包 diff 的 `+` 行还原成文件后与已提交文件逐字节比对，**3/3 完全一致**；`git show --name-only ec2f10d` 确认只有那 3 个文件（`.superpowers/*` 与 `.serena/` 未入库）；父提交 `ec2f10d^` = `aaa9489`。
- **计数旁证**：`simos-util/target/surefire-reports` 现存报告合计 **154**，其中 `RoundTripAssertionsTest` tests=4、`RoundTripAssertionsDriftTest` tests=1（4+1 与报告一致）。仓库里 `@Test` 注解 120 个 + 3 个参数化，展开为 154 是自洽的。

**关于"删往返破裂断言打红 2 条"的分歧**：控制器的解释**我确认为正确**。我的 G5（删往返破裂断言）红 2 条（`aBrokenRoundTripReportsAllThreeStates` + `aChangeSetThatDropsAFieldMustBeCaught`），我的 V1（把 `ToySnapshot.apply` 退回计划原版，即控制器所述的变异 5）红 **1** 条（`aCorrectRoundTripPasses`）。两组变异不同，两个记录各自都对，不是分歧。

---

## 5. 发现清单（分级）

### Critical（0）

无。核心不变式（往返破裂必须响、五条守卫）经删式变异验证**全部承重**；护栏不是装饰。

### Important（2）

**I-1｜`aBrokenRoundTripReportsAllThreeStates` 没有钉住它名字里承诺的三态——`base` 完全无护栏，`target` 的 needle 是空转的**

- 位置：`simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java:99-100`（断言）与 `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java:51-54`（被保护的 base / target 两行）。
- 具体失败场景（**实测**）：
  - **P1**：删掉报文里的 `+ "  base      = " + base`（impl:51-52）→ **5 条用例全绿**。spec §9.2 要求报文含 base/target/实际结果三份 `toString()`，`base` 那份**没有任何用例在守**。
  - **P2**：删掉 `+ "\n  target    = " + target`（impl:53-54）→ `aBrokenRoundTripReportsAllThreeStates` **仍然绿**（唯一转红的是漂移用例，靠 `beta=9`）。原因：needle `hasMessageContaining("target")`（Test:99）被报文**首行** `apply(diff(base, target), base) 与 target 不等。` 里的 "target" 字样满足——**这条断言在任何情况下都满足**，是空转的。
- 建议：把两条 needle 改成钉 dump 行的形状（如 `"  base      = "` / `"  target    = "` / `"  actual    = "`），或按 P1/P2 的变异自证一遍。测试名与 spec §9.2 的承诺就会与实际判别力对齐。

**I-2｜漂移用例的 needle 对"差异出在哪个字段"没有定位力，注释里的断言过强**

- 位置：`simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsDriftTest.java:32-33`（needle 及其注释）。
- 具体失败场景（**实测 W1**）：把漂移从 `beta` 挪到 `alpha`（`DriftingChangeSet` 带 `beta`、漏 `alpha`，`apply` 保持 `+1`）→ 往返照样破裂、报文里 `base alpha=1,beta=2` / `target alpha=5,beta=9` / `actual alpha=1,beta=9`，两条 needle 分别被 **base 行**与 **target 行**（无条件打印）满足 → **用例全绿**。即：断言在"红得不是 beta"的世界里没有转红，而注释写的是"两者必须同时出现，才证明红的是 beta"。
- 缓解事实（必须一并记录）：该用例的**核心判据是承重的**——`assertThatThrownBy` 本身在"不漏字段"的世界里转红（V3 实测），所以这条不是空转护栏，只是**自称的判别力高于实际判别力**。
- 建议（已实证两种修法都可行）：把 needle 换成只有 actual 行能拼出的整段（如 `"alpha=5, beta=2]"`，W2 绿 / W5 红），或删掉注释里"才证明红的是 beta"这类过强断言并明确"本用例只证明漏字段会被抓到，不定位到具体字段"。**这一条要紧，因为 M2~M4 的 `XxxDriftTest` 会照抄这个模板。**

### Minor（3）

**M-1｜报文里 spec 要求的那"一句定位提示"与 changeSet dump 无护栏**
- 位置：impl:57-58（changeSet 行）、impl:59（提示行）。
- **实测 P4 / P5**：分别删掉后 **5 条用例全绿**。spec §9.2 明写报文须含"一句定位提示"，删掉它无人察觉。建议顺手把提示行纳入 needle（如钉 `"这正是 L1 事故的形态"`）。

**M-2｜"框架不关心 `S` 是什么"没有任何用例钉住**
- 位置：impl:23-24。**实测 S1**：给 `assertRoundTrip` 的 `S` 加回 `extends Snapshot` 上界后**编译通过、5 条用例全绿**——因为三处调用点传的都是 `Snapshot` 实现。
- 缓解事实：spec §9.2 把签名逐字给定，且 M4 若真用 `SimulationState`（不是 `Snapshot`）调用，越界会在**下游编译期**爆炸。
- 归属：**brief 层面的缺口**——brief 的 `ToySnapshot` 注释自己把该性质限定成"只要它是 record 并实现 `Snapshot`"，实现者按 brief 转写无过错。若要补，加一个不实现 `Snapshot` 的玩具 record 调用 `assertRoundTrip` 即可。

**M-3｜报告 Step 3 的计数不准（不影响交付物）**
- 报告写"`cannot find symbol` 共 **16** 处…`RoundTripAssertionsDriftTest.java` 多处"，我实测是 **8 处**（`RoundTripAssertionsTest` 7 处：23/28/43/61/69/80/92；`DriftTest` **1 处**：26）。报告列出的行号与我实测一致，"报的是 `variable` 而非 `class`"这一关键现象也属实，只是总数与"多处"的措辞不准。

### 备注（不计入缺陷）

- `diff`/`apply` 返回 null 时抛 `NullPointerException` 而非 `AssertionError`，与 spec §9.2 "失败抛 `AssertionError`" 的字面有张力；但 brief（本任务的唯一权威）明确用 `Objects.requireNonNull(..., "…返回 null")` 并让用例钉 `NullPointerException`。**按 brief 实现无过错**，仅提示：M2~M4 的使用者若 `catch (AssertionError)` 捕往返失败，null 返回路径会漏网。
- `aMisStampedChangeSetIsRejected` 的 needle 与实现文案强耦合（改守卫文案即转红）：这是 brief 的有意设计（"必须钉到守卫自己的文案"），不构成缺陷。
- `RoundTripAssertions` 无实例化用例（私有构造器）——spec 未要求，不计。

---

## 6. 未核实清单（如实列出，勿当作已验）

1. **SpotBugs、maven-enforcer、全仓 `./mvnw clean verify`**：未独立跑。仅采信控制器的实测结论（BUILD SUCCESS / BugInstance 0）。我独立复核的门禁只有 Spotless 等价项（google-java-format）与 Checkstyle。
2. **本机 `~/.m2` 里 `agentlib-mosire` 的类数（49 还是 118）**：未核实。与本任务交付物无关，故未查。
3. **brief 引用的计划文档 `:2765-2781` 行号内容**：未逐字比对（我以 brief 为权威；未读实现计划对应行）。
4. **JUnit/AssertJ 版本是否与控制器预跑时"完全同一套"**：我跑的是 `junit-platform-console-standalone-1.11.4` + `assertj-core-3.27.7`（本机 `.m2` 中存在的版本），**未核实**控制器当时用的就是这两个。
5. **`simos-util` 模块 149→154 的"改前"基线**：未重跑。我只旁证了现存 surefire 报告合计 154（4+1 与新增一致），未验证改前确为 149。
6. **超出上列变异之外的别的变异**（例如对 `checkApplied` 的 `target.equals(applied)` 换成 `Objects.equals`、或异常消息编码等）：未穷尽。本节结论只覆盖我实际跑过的那 20 余个变异。
7. **漂移用例的 needle 收紧后是否真的更好**：我只实证了 W2（绿）/ W5（红）两个世界；未评估其在 M2~M4 真实快照上的效果。

---

## 7. 给关闭本任务的意见

- **A 符合**、**B 无 Critical**：`ec2f10d` 可以留在分支上，不需要返工重做。
- 若要继续收紧（推荐，代价很小）：按 I-1 让"三态报文"的断言真正钉住三行 dump、按 I-2 收紧漂移 needle 或修正其注释。两处修改都在测试文件内，不动 `main` 源码，不影响 M2~M4 的接口。
- M2~M4 照抄这套模板时，优先提醒两件事：**（一）"钉守卫自己的文案"而不是钉任何可能被兜底报文顺带满足的字段名；（二）报文的每一行都要有一条能在该行被删时转红的断言，否则测试名会承诺它守不住的东西。**
