# Task 12 报告：两阶段推进六步（C25）+ R9/R10/R14（2026-09-18）

**执行方式**：控制器**内联**执行（主树 `feat/adr1-core-scope`）。与 Task 8/9/10/11 同例——本轮之前的派发先后死于
**429**（账号级五小时额度）与 **503**（CPU 过载：本机 `nproc=2`，跑 Maven 会杀掉活着的 agent，已实测两次）。

**任务范围**（计划 Task 12 + M4 spec §5.1/§5.3/§5.4 + C14/C15/C25/C28 + 裁定 43/44/47/48）：两阶段推进的六步、
③ Resolve 的冲突判定、④ Validate 的五项、⑤ 一个事务落 revision + 全链事件、⑥ 按 C19 写 checkpoint，以及 R9/R10/R14。

---

## 1. 交付物

| 文件 | 态 | 行数 | 出货 md5 |
|---|---|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java` | 新 | 510 | `bc5072b13e76cc4a955335c7751f654c` |
| `…/core/advance/TimeProposalResolver.java` | 新 | 137 | `6bb957b6537ecaddf982f5b2ee9eaf5e` |
| `…/core/advance/AdvanceConflict.java` | 新 | 93 | `d51856a6362ab03616585dda2b177b20` |
| `…/core/store/CheckpointEncoder.java` | 新（★ **计划外**） | 75 | `7251bebf0f51b5fd90b8e75fc9c8ea52` |
| `…/core/test/…/advance/TimeAdvanceTest.java` | 新 | 784 | `d0e4b9f654f6c52d3546b8ada867fcbe`（**16 条**） |
| `…/core/test/…/advance/TimeProposalResolverTest.java` | 新 | 206 | `6431778d069ae05f2ddb9394536f3ae6`（**8 条**） |
| `…/core/test/…/command/CorrelationChainTest.java` | 改 | — | `c6a06738c4073e68159b55c83cf8806d` |

★ **`CheckpointEncoder` 是计划外新增**（计划的 Files 行只列了 `advance/` 那三个）——**不写它 Task 12 落不了地**：
⑥ 要写 checkpoint，而 `Envelope.encode` **在 main 侧从来没有生产调用点**（`Envelope.decode` 有，`Replay` 在用；
`encode` 只有测试在调）⇒ "状态 → 信封"这条通路**在本任务之前从未在生产代码里跑过**。**与裁定 38/39 同族：
两处各自正确的东西之间缺一个装配点。** 补在 `store` 包而不是 `advance` 包（后者是层次倒置），并让它与解码侧
**逐条对称**（模块表按 namespace 字典序等），见其类注释。

★ 最后一个是**纯注释改动**（撤掉 Task 11 给 `advanceSequenceShapeIsWhatR6Requires` 挂的"机制级、不许当证据"告示）：
剥注释骨架 md5 改前改后**同为 `9324ae37298a47fc3757acd310e0663e`**（当场实测，不是"看着像注释"）。

★ `Either` 未新造——**裁定 43**。③ 的返回类型是 `TimeProposalResolver.Outcome`（sealed，两个分支
`Blocked` / `Resolved`），与全仓既有风格一致。

---

## 2. 六步对账（spec §5.1 逐行 → `TimeAdvance.java` 行号）

| 步 | 落点 | 实测要点 |
|---|---|---|
| ④ 第 0 项（**有意提到最前**） | `141–143` | 缺 `range.to` ⇒ `Rejected`。**它不查库、不碰状态**，故排在 ① 之前——见 §4 的炸药用例 |
| ① Prepare | `145–167` | `timeline.head` 乐观并发检查（`148` 过期 / 分支不存在两分）；`154–156` 算 `base`/`target`/`newMeta`；`167` `stateLoader.load(base)` |
| ② Propose | `169–177` | 逐参与者 `simulate(state, range)`，**同一份 base、纯函数**；`174–177` 落 `started` + N×`proposal` 事件 |
| ③ Resolve | `179–195` | 写-写 ⇒ `Blocked` ⇒ `conflict` 事件 + `Rejected`（**不产生 revision**）；读-写 ⇒ 逐条 `conflict` 事件、放行 |
| ④ Validate | `197–201` → `validate()` `273–` | 第 1~4 项；第 0 项在上面 |
| ⑤ Commit | `203–207` | `finished` + `committed` 与 revision 行**同一个事务**（裁定 47）。失败的两条路 `208–228` |
| ⑥ Post-commit | `230–231` | **只剩 checkpoint**（C19 命中才写），它失败不影响已落盘的事实；编码走 `CheckpointEncoder.encode`（`store` 包） |

**参与者清单排序**（C25 / 裁定 44）在**构造器** `121–129`：`TreeMap` 显式排序 + 同名 `namespace` 当场
`IllegalArgumentException`。★ 不靠 `Set`/`Map` 的迭代序——那**不是键集的纯函数**（M2 Task 5 实测 30 次）。

---

## 3. 判据落点（R9 / R10 / R14）与"④ 五项各有故意违规用例"

| # | 断言 | 用例（`TimeAdvanceTest`，16 条中的哪条） |
|---|---|---|
| R9 | 写-写 ⇒ `Rejected`，**且 `revisions` 行数不变** | `writeWriteConflictIsRejectedAndLeavesNoRevision` |
| R10 | 读-写 ⇒ **放行**，事件里的地址列表**按字典序** | `readWriteOverlapIsCommittedAndItsEventAddressesAreSorted` |
| R14 | 未注册 namespace 的 proposal ⇒ `Rejected` 且不留 revision | `proposalWithoutARegisteredCodecIsRejected` |
| ④ 第 0 项 | 缺 `to` ⇒ `Rejected`，且**在 ①/② 之前** | `missingRangeUpperBoundIsRejectedBeforeAnythingElseRuns`（★ 自带两个**自爆装置**：`neverLoad` 与 `neverSimulate`，一旦走到装配或模拟就炸） |
| ④ 第 1 项 | 无已注册 codec | 同上 R14 那条 |
| ④ 第 2 项 | `encodeChangeSet` 抛 ⇒ 拒 | `codecThatCannotEncodeTheChangeSetIsRejected` |
| ④ 第 3 项 | `apply` 抛 ⇒ 拒 | `codecThatThrowsOnApplyIsRejected` |
| ④ 第 4 项（C28） | codec **照抄 base 坐标** ⇒ 拒 | `codecThatCopiesTheBaseCoordinatesIsRejected` |

★ ④ 第 4 项**不是装饰**：`validate()` 的 `313–314` 逐字比对 `namespace()` 与键、`ref()`/`timestamp()` 与 Core 给的
`newMeta`。codec 若不用 Core 给的 `newMeta`（最容易犯的错就是照抄 base），这里当场判 `Rejected`。

★ 另三条守裁定 48 的用例（都在 `TimeAdvanceTest`）：`staleExpectedRevisionIsAConflictCarryingTheRealHead`、
`primaryKeyCollisionWithAMovedHeadIsFoldedIntoConflict`（**动了 ⇒ 折成冲突**，报真实 head，且链从 `received`
重新起算、`finished` **不补发**）、`failureThatIsNotARaceIsRethrownInsteadOfDisguisedAsConflict`（**没动 ⇒ 原样重抛**，
连"head 都问不出来"的第三态也覆盖：重读失败挂 `suppressed`，绝不声称冲突）。

★ Task 11 给本任务的硬接缝已兑现：`realRouteProducesTheFrozenR6Sequence` 用**真 `TimeAdvance` + 真 store**跑出
`received → started → N×proposal → finished → committed` 的冻结序列、`revisions` 恰 1 行，并逐行核 payload。
⇒ Task 11 那条"机制级"告示随之撤除（见 §1）。

---

## 4. 实测结论行（照抄日志，非转述）

**最终全量绿轮**（`task-12-evidence/logs/full-verify-final.log`，mtime `21:03:05`，rc=0，`20:58:11 → 21:03:05`）：

```
Tests run: 170, Failures: 0, Errors: 0, Skipped: 0     （UtilSimos）
Tests run: 255, Failures: 0, Errors: 0, Skipped: 0     （MapSimos）
Tests run:  37, Failures: 0, Errors: 0, Skipped: 0     （SocialSimos）
Tests run:  93, Failures: 0, Errors: 0, Skipped: 0     （UnitSimos）
Tests run: 137, Failures: 0, Errors: 0, Skipped: 0     （CoreSimos）
BugInstance size is 0        ×5（五个 jar 模块各一次）
[ERROR] 行数：0
BUILD SUCCESS；6/6 模块 SUCCESS
```

⇒ 合计 **692 条**。★ 与 Task 11 的绿轮（668 ＝ 170/255/37/93/**113**）**逐模块对差**：前四个模块一字未动，
core 113 → 137 **恰 +24 ＝ 本任务两个新用例类（16 + 8）**。这不是"总数变大了"，是**增量可归因**。

advance 两个用例类在同轮的逐类数字：`TimeProposalResolverTest` **8/8**、`TimeAdvanceTest` **16/16**。

**变异自证**：**9 轮，9 个变异体全部被杀**（计划要求 4 个，本任务实做 9 个，见 §5）。

---

## 5. 变异自证（逐轮，日志 `task-12-evidence/logs/t12-mN.log`）

装置：`task-12-evidence/mutants/mut-round.sh`（Task 11 版**逐字沿用**，只换 `EVID` 常量，外加本任务的一条补记，见 §6.3）。
九道门禁全绿才判"杀死"：干净世界 / 变异体与原件字节不同 / 白名单推送 / `COMPILATION ERROR` 为 0 / 真的跑到用例 /
期望类真红 / 工作树还原。**每轮实测 `Tests run: 24`（8 + 16），`COMPILATION_ERROR_lines=0`。**

| 变异 | 打的位置 | 变异体 md5 | 实测红在哪 |
|---|---|---|---|
| m1 | ③ 只查 `reads(A) ∩ writes(B)` 一个方向 | `994eaffd…` | 4 条：`onlyTheReverseReadWriteDirectionIsReported`、`bothReadWriteDirectionsAreReportedAsTwoWarnings`、`warningsOrderIsAFunctionOfContentNotOfInputOrder`、`TimeAdvanceTest` 那条读-写链（`该链上应恰有一条 timeline.conflict`） |
| m2 | 写-写改成"记警告并放行" | `6275a2f0…` | 2 条：`writeWriteIntersectionIsBlocked`、`writeWriteConflictIsRejectedAndLeavesNoRevision`（R9） |
| m3 | ④ 去掉第 0 项（缺 `to` 也放行） | `05393b58…` | 1 条：`missingRangeUpperBoundIsRejectedBeforeAnythingElseRuns`——红在**炸药消息**"缺 to 的推进不该走到 ① 的装配状态"上，即红的理由正是"第 0 项没先做" |
| m4 | 冲突事件的地址列表**不排序** | `65c1b81c…` | 2 条序断言（`…AreSortedLexicographicallyEvenWhenGivenOutOfOrder` 与真 route 那条的 `x1,x2,x3,x4`） |
| m5 | `namespaces` **也排序**（Task 12 真实犯过的错的复原体） | `63d4f9cf…` | 3 条有向对断言（`bothReadWriteDirections…`、`…OutOfOrder` 的 `[zulu, alpha]`、真 route 那条的 `[beta, alpha]`） |
| m6 | 参与者按**注册序**而非 namespace 字典序（C25/裁定 44） | `1301c911…` | 2 条：`participantOrderIsFixedAtConstructionNotByRegistrationOrder`、`realRouteProducesTheFrozenR6Sequence` |
| m7 | 提交失败**无条件**折成 `Conflict`（裁定 48 的要害） | `e1b39829…` | 2 条：`failureThatIsNotARaceIsRethrownInsteadOfDisguisedAsConflict`、`primaryKeyCollisionWithAMovedHeadIsFoldedIntoConflict` |
| m8 | checkpoint 只写动过的模块（丢掉未触碰的） | `513db738…` | 1 条：`checkpointIsWrittenWhenDueAndKeepsUntouchedModulesAtTheirOwnCoordinates` |
| m9 | checkpoint 信封的 meta 用 `base.meta()` | `4a5e8f2f…` | 1 条：同上（`信封的坐标是新 revision`） |

★ **m4/m5 各跑了两遍**（对着两份先后出货的字节），理由与结果见 §6.2。

★ **m6 首轮曾作废**：改用 `LinkedHashMap` 后 `import java.util.TreeMap;` 变成未使用，**Checkstyle 的 UnusedImports
在跑用例之前**就把构建打掉（`Tests_run_lines=0`）⇒ 装置的门禁"一条用例都没跑到 ⇒ 这不叫红"当场把它判成**作废**。
修法是把那行 import 一并删掉，重跑即杀死。**这正是那道门禁存在的理由**：它把"红在 Checkstyle"与"红在断言"分开了。

★ **m7 用正则整段吞**（不是逐字节锚点）：它跨了 12 行中文注释，而中文注释的折行由 google-java-format 决定
（CLAUDE.md 明写"改完跑 `spotless:apply`"）⇒ 逐字节锚点会在下次格式化后**静默失效**。非贪婪 + DOTALL 只认两端代码行。

---

## 6. 本任务抓到并修掉的三个东西

### 6.1 ★★ 真产品缺陷：`AdvanceConflict` 把 `namespaces` 也排了序（用例当场抓住）

首轮跑用例：**2 failures / 24**。其中一条是**产品错**，不是用例错：

- 现象：`TimeProposalResolverTest.bothReadWriteDirectionsAreReportedAsTwoWarnings:114` 红。
- 根因：`AdvanceConflict` 的构造器对 `namespaces` 与 `addresses` **都**调了 `sortedCopy`，而它自己的 javadoc
  写着 `namespaces` 是 `[读方, 写方]`（**有向**）。排序后 `[alpha,beta]` 与 `[beta,alpha]` 落成**同一个值**
  ⇒ **两条本应独立保留的风险，在事件表里变成两条逐字节相同的行**，读事件的人再也分不出方向。
- **裁定依据是 spec 原文，不是记忆**：回查 §5.3，C15 **只**要求"冲突报告按字典序排序后落事件"——**只针对地址**。
  ⇒ 排序 `namespaces` 是我**多做**的，而且做错了。
- 修法：`namespaces` 改保序拷贝；`addresses` 保持排序。两条规则不同，写进了类注释（"凡'顺手也排一下'的字段，
  先问它承载的是**内容**还是**关系**"）。
- 判别力：这条错**活不过一轮用例**，因为 m5 就是它的复原体——把排序加回去，3 条断言立刻红。
- ★ 另一条 failure（`participantOrderIsFixedAtConstructionNotByRegistrationOrder:267`）是**夹具错**：同一分支串行
  两次推进，第二次的 `expectedRevision` 写了 1 而 head 已是 2 ⇒ 走了过期检查、拿到 `Conflict`、**没有 proposal 事件**
  ⇒ 空列表与第一次的 `[alpha,beta]` 一比就红。已在夹具里**留痕**说明"这是夹具错不是产品错"，以免后人重踩。

### 6.2 ★★ 门禁抓到的真缺陷：SpotBugs 的 `EI_EXPOSE_REP`×2，以及它的判定机理

全量 `clean verify` 首轮 **rc=1**：`SpotBugs` 在 `simos-core` 报 **2 条** `EI_EXPOSE_REP`（其余 4 模块 0；用例本身全绿）。

- **第一假设**（"SpotBugs 认 `Collections.unmodifiableXxx` 不认 `List.copyOf`"）**被探针否定**：
  把辅助方法的返回换成 `Collections.unmodifiableList` 后**仍报 2 条**（`logs/probe1.log`、`logs/repro-ei.log`，
  都指到构造器那一行）。
- 对照同模块的 `WorldChangeSet`（同 record 形态、**从不报**）发现差别只在**包装写在哪**：它写在构造器体里。
- **探针 2**（`logs/probe2.log`）：把包装**内联进构造器体**（`List.copyOf`）⇒ 五个 jar 模块**全**
  `BugInstance size is 0`（含 `simos-core`）。
- 机理：**`EI_EXPOSE_REP` 只认构造函数体内直接可见的包装调用，不做跨过程分析。**
- 修法：保留 `List.copyOf`（强不可变优于视图），只把包装搬进构造器体；两个辅助方法诚实改名
  `immutableCopy`→`checkedCopy`、`sortedCopy`→`sortedCheckedCopy`（它们不再保证不可变）。已升为 **CLAUDE.md 形态 7**。

★ **字节改了两次 ⇒ m4/m5 的变异轮重跑了两次**：先因上面这次修法（`AdvanceConflict.java` 改字节），后因 §6.2 的注释
措辞校正（见下）。每次都是"更新 `orig/` 快照 → 重新生成 → 重跑"，且每次**只重跑目标文件被改的那两轮**
（m1/m2/m3/m6/m7/m8/m9 打的是 `TimeProposalResolver.java` / `TimeAdvance.java`，两文件全程 md5 未变）。

★ **注释措辞的校正（形态 5 的一次自我纠正）**：修完 SpotBugs 后我在类注释里写了"**同一句**搬进构造器体 ⇒ 0"。
回查证据发现**不成立**：两次探针**同时换了两个因素**（① `Collections.unmodifiableList` + 辅助方法 ⇒ 2；
② `List.copyOf` + 构造器体 ⇒ 0），**不能**据此断定"换个 API 放进构造器体也判 0"——那一格**没测过**。
已把注释改成只陈述实测过的两格。**代码行一字未动**（剥注释骨架 md5 两侧同为 `e432669cf3e93fc2affdb8b3281444e4`）。

### 6.3 ★ 装置缺陷：变异轮的日志**证不了它跑的是哪份字节**（形态 1 第六例）

核对"哪些旧变异轮还成立"时发现：`mut-round.sh` 的门禁把自证项（`orig_md5`/`mutant_md5`/推送后 md5）**只打到终端**，
`grep -c 'mutant=' <某轮日志>` 实测 **0** ⇒ 日志本身**无法自指**，只能靠推导（"源文件没动 ⇒ 重新生成的变异体必然相同"）。

★ 同一次还撞到**我的核对脚本自己造出假红**：`was=$(grep -oE 'mutant=[0-9a-f]{32}' 日志)` 读到**空串**，与 `now`
一比即"不一致"，**7 个变异体全被判"必须重跑"**——先把读取修好才发现那 7 轮本来就没问题。
⇒ **先怀疑自己的读取，别先怀疑被测物**（与"`grep -c` 返回 0 先怀疑正则"同型）。两条都已升为 **CLAUDE.md 形态 1 第六例**。

**修法**：给 `mut-round.sh` 加了"装置补记"段——Maven 跑完即把三处 md5 **追加进日志本身**（写在门禁 3/4 之前，
即使这一轮被作废也留着）。**9 份日志现在逐份自指**，例如 `t12-m4.log` 里
`mutant_md5=65c1b81c9c5922c8fa639741a68c2672` 与 `worktree_after_push=65c1b81c…` 双向对上。

### 6.4 ★ 报告自身的一处漏报（提交前自查抓到，留痕）

本报告初稿的 §1 交付物表写着 **3 个 main 文件**——**漏了 `CheckpointEncoder.java`**。它是提交前扫 `git status` 时才
露出来的：它一直是 **untracked**（`git ls-files` 查无、`git grep` 也搜不到**引用它的已入库文件**，因为 `TimeAdvance`
当时也还没入库），于是任何"只在已入库文件里搜"的手段**都看不见它**。

★ 与形态 1 那一族同源（`git grep` 默认只看已入库文件 ⇒ 开发期刚写的文件全是 untracked ⇒ **静默返回空**），
**这次它伪装成的不是"不存在"，而是"不属于本次交付"**。⇒ **交付物清单必须对着工作树实测生成，不能凭计划或记忆列。**
若按初稿提交，远程仓库会**缺一个编译必需的源文件**（clone 即编不过），而任务看起来是"全绿关账"的。

---

## 7. 裁定 35 复核（Task 9 指定"Task 12 落地后回来复核"，本任务结）
裁定 35 是**推定**：领域命令（信封支）落 revision 时 `SimosTimestamp` **继承父行**。复核结果——**三条路径实测一致，推定成立**：

| 路径 | 出处（实测行号） | 时刻来源 |
|---|---|---|
| 信封支（领域命令） | `CommandBus.java:325` | `SimosTimestamp timestamp = baseRow.timestamp();` ⇒ **继承父行** |
| 推进支（`AdvanceTime`） | `TimeAdvance.java:156` | `new StateMeta(target, cmd.range().to().orElseThrow())` ⇒ **推进终点**（唯一移动时钟的路径） |
| 重放 | `Replay.java:189`、`196` | 一律取 **revision 行自己的 `timestamp`** ⇒ 两条路径在重放时**同一口径**，无需分支 |

⇒ 与 spec §五 不冲突，**无取代说明**。裁定 35 由"推定"转为"实测确认"。

---

## 8. 我未能核实的（不许当结论引用）

1. **真并发没测**。裁定 48 说"靠 `(branch, revision)` 主键挡并发"，而三条守它的用例都是**单线程演出**
   （用例自陈"并发可以用单线程、确定性地演出来（不必真起线程）"：`TimeAdvanceTest:531`）——那个"抢先者"是替身
   `StateLoader` 在 ① 里插一行，不是另一个线程。⇒ **两个真线程同时 `run()` 同一个 `TimeAdvance` 会怎样，本任务没验。**
2. **`TimeAdvance` 写出的 checkpoint 被 `Replay` 读回**，这条**端到端没跑过**。本任务验的是"checkpoint 内容对"
   （坐标、未触碰模块、信封 meta），`Replay` 读 checkpoint 的通路是 Task 8 用**夹具直接落盘的行**验的
   （Task 8 报告 §5① 那条"`Replay` 从未在真状态上跑过"**依然成立**）。⇒ 归 Task 13 装配。
3. **参与者全是替身**（`ToyParticipant`/`ToyCodec`）。"真 route"指的是真 `TimeAdvance` + 真 store；**真 codec 只有
   Task 16 的 `UnitTimeParticipant`**，本任务没碰。⇒ R6 的端到端在**装配层**是否仍成立，Task 13/15 要看。
4. **`Collections.unmodifiableList` 放在构造器体里判不判 0**：**没测过**（见 §6.2 末）。CLAUDE.md 形态 7 已按"只有
   两格实测"如实写。
5. **m7 的正则锚点在下次格式化后是否仍命中**：本轮命中唯一（`re.subn` 断言 `n == 1`），但**没有**"格式化后重跑"的验证。
6. **C19 周期与 `Replay` 步数上界（R5）的联动**：本任务只按 C19 写 checkpoint，**没有**跑"推进多次 → 重放同一分支"
   这类跨任务场景（属 Task 13 装配后的事）。
7. **`CheckpointEncoder` 没有专属变异轮**：m8/m9 打的是 `TimeAdvance` 的**调用点与参数**。它的覆盖面是**间接**的——
   `checkpointIsWrittenWhenDueAndKeepsUntouchedModulesAtTheirOwnCoordinates` 把它的产物经 `Envelope.decode`
   + 逐模块 `decodeSnapshot` **读回并逐字段断言**（坐标、未触碰模块保留自己的坐标、变更集真的 apply 了），
   且 m8/m9 已证明这些断言会咬；但**编码侧自己的规则**（如"模块表按 namespace 字典序"这条与解码侧的对称要求）
   **没有一条专打它的变异体**。⇒ 要更强的保证，Task 13 装配时可补。

---

## 9. 证据清单（`task-12-evidence/`）

```
logs/clean-baseline.log              干净基线轮
logs/full-verify-final.log           ★ 最终全量绿轮（21:03:05，rc=0，692 条）
logs/full-verify-prefinal.log        注释校正前的那轮绿（留档，不与之混）
logs/t12-m1…m9.log                   ★ 九轮变异，每份自带"装置补记"的 md5 自指段
logs/run-all-rounds.out              九轮的总控输出（9×杀死 / 0 存活）
logs/probe1.log                      第一假设被否定（unmodifiableList 在辅助方法里 ⇒ 仍 2 条）
logs/repro-ei.log                    同一形态的完整 Maven 复现（仍 2 条）
logs/probe2.log                      包装内联进构造器体 ⇒ 5 模块全 BugInstance size is 0
mutants/{gen-mutants.py,run-all-rounds.sh,mut-round.sh}
mutants/orig/{AdvanceConflict,TimeAdvance,TimeProposalResolver}.java   ★ 出货字节快照（m4/m5 现对着 d51856a6…）
mutants/t12-m1…m9.<目标类>.java       九个变异体
```

★ 工作树三文件在九轮跑完后**逐字节还原**（md5 与 `orig/` 快照一致，`mut-round.sh` 的门禁 7 逐轮核过）。
★ `CheckpointEncoder.java` **没有**对应的变异体（理由见 §8.7），它不在变异装置的覆盖范围内。
