# Task 10 报告：乐观并发两处检查（C17）+ R7（判据三）（2026-09-18）

**执行形态**：**控制器内联**。理由：本轮的 agent 死于 **503（CPU 过载）**——`nproc=2`，而我当时正在跑
Maven（这是我造成的，不是 agent 的问题）。且 Task 10 与 Task 11 **都要改 `CommandBus.java`**，
本就必须串行；既然机器容不下"Maven + agent"并存，串行执行的次序只能是**先做完 Task 10（我独占 Maven），
再把冻结后的 `CommandBus.java` 交给 Task 11**。步骤按计划 Task 10 的 Step 1~3 逐条走完。

## 1. 交付物

| 文件 | 状态 | 说明 |
|---|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java` | 改 | 新增 `commitLock` 字段 + `commitUnderLock(envelope, changeSet)`；类注重写 |
| `simos-core/src/test/java/io/mosire/simos/core/command/OptimisticConcurrencyTest.java` | 新增 | 1 条用例（K=50 轮）、2 条自证断言 |
| `.superpowers/sdd/.../task-10-evidence/**` | 新增 | 日志 + 变异装置 + 变异体 + 原件参照 |

计划的 Files 行**只列了前两个**，本轮没有第三个源文件被改——`CommandBus.java` 是唯一被改的 main 源。

## 2. 实测结论行（照抄日志）

**关账全量绿轮**（`task-10-evidence/full-verify.log`，`./mvnw clean verify`）：

```
rc=0
Tests run: 170 / 255 / 37 / 93 / 89   （util / map / social / unit / core）＝ 合计 644
BugInstance size is 0  ×5
[ERROR] 行数：0
```

★ **与上一绿轮（643 ＝ 170/255/37/93/88）逐模块对差**：前四个模块**一个都没动**，core **88→89**，
恰 +1 ＝ `OptimisticConcurrencyTest`。

**★ 红在前（加锁之前，`red-before-lock.log`）** —— 这是本任务最要紧的一行证据：

```
rc=1
OptimisticConcurrencyTest.twoThreads...:129 » Execution java.lang.IllegalStateException: 事务失败，已回滚
Caused by: org.sqlite.SQLiteException: [SQLITE_CONSTRAINT_PRIMARYKEY]
            A PRIMARY KEY constraint failed (UNIQUE constraint failed: revisions.branch, revisions.revision)
```

⇒ **两个线程真的同时算出了同一个 revision 号、都去写**。这正是 Task 9 报告 §5 记的那条硬接缝
（"`commit` 用 `base.revision().value()+1`，并发下两提交会算出同一个 revision 号"）**当场兑现**。
不是我想出来的风险，是跑出来的。

## 3. 变异自证（逐轮）

装置：`task-10-evidence/mutants/mut-round.sh`（复用 Task 8 的那套，只把 `EVID` 改到 task-10）。
每轮自证四件事：① 开跑前工作树 md5 **必须等于**原件；② 变异体 md5 **必须不等于**原件；
③ 按**白名单**推成目标类名；④ 跑完还原到开跑前状态并校验 md5（**不是 `git checkout --`**）。
五轮的 `COMPILATION_ERROR_lines` **全为 0**、`Tests_run_lines` **全为 3**（maven 的三行汇总）。

★ **五轮全部跑在最终提交的 artifact 上**（`worktree_restored=ce353f91a37583363a4dbbff6567e2a4`，
即 spotless 格式化之后的规范源）。中途因为 spotless 改了注释换行，我把五个变异体**从格式化后的源码重新生成、
五轮全部重跑**了一遍——剔除注释行后代码**逐字节相同**（已当场 diff 验证），但"跑在最终 artifact 上"这句话
不该带保留。

| 轮 | 变异 | 期望红在哪（计划） | 实际红在哪（实测） |
|---|---|---|---|
| m1 | 去掉 ③ 锁内复查（只留 ①） | R7（会出现两个 `Committed`） | ✅ `OptimisticConcurrencyTest:138`，消息 `第 0 轮（expectedRevision = 1）：必须**恰一个** Committed … 实得 [Committed[…2], Committed[…3]]` |
| m2 | 掐掉 ① 的「**过期快失败**」那一半 | 「**不一定**让 R7 红……若存活，如实记为等价变体」 | ✅ **被 Task 9 的既有用例杀掉**：`CommandBusDispatchTest.staleExpectedRevisionConflictsWithRealHead:181 » IllegalState 本用例不该跑到 handler: unit.RenameUnit`。★ R7 那 1 条**确实通过**（与计划的判断一致）——见 §4.4 |
| m2b | 掐掉 ① 的「**分支存在性**」那一半 | （计划未列，见 §4.5） | ✅ `CommandBusDispatchTest.missingBranchIsRejectedNotConflicted:191 » NoSuchElement No value present` |
| m3 | ② 的 handler 挪进锁内 | R7 **仍应通过**，但"同时进入数"断言会暴露串行化 | ✅ **只有自证断言抓得住**：`OptimisticConcurrencyTest:170`，`自证①：… 实得 0 次`。★ **主体正确性断言（138/141/150/153/156 行）全绿**，红的确实只有自证那一条；耗时 **100.7 s** ＝ 50 轮 × 2 s 屏障超时，与设计逐字吻合 |
| m4 | 步长 `+1` → `+2` | （计划未列，见 §4.6） | ✅ `OptimisticConcurrencyTest:150`（新加的 +1 直证），第 0 轮即红 |

**★ m3 是这一轮最有价值的一条**：它证明那条"同时进入数"自证断言**是承重的**——
没有它，m3 就是一个**存活变异体**，"锁把 handler 罩住、并发名存实亡"会被静默地当成并发正确。
计划说"这条变异是检验 Step 2 那条自证断言有没有用的唯一办法"，实测确认了。

**零存活**：五轮全部被杀，且每轮都红在**被保护的那一行**上。

## 4. 取代说明（计划/派单函 vs 实测，以实测为准）

### 4.1 计划 Step 1 的 ④ 写"revision 行 **+ 全部事件行**，同一事务"——事件行不在本任务范围

C20/裁定 10 把事件行判给 **Task 11**，Task 10 只落 revision 行（`CommandBus.commit` 的现状）。
⇒ **这是 Task 10 与 Task 11 之间的一道硬接缝，Task 11 必须看见**：

> `commit` **只在 `commitLock` 内被调用**。事件行要与 revision 行**同一事务**（计划 ④ 的原话），
> 故事件写入应当落在 `commit` 里（那就天然在锁内）；**若把它放在 `dispatch` 里 ③④ 之外的某个位置，
> 原子性就破了**。本类里 `commit` 是私有方法且只有一个调用点，这条约定由结构本身守住。

### 4.2 新增 `commitUnderLock`，`commit` 保持"只写不查"

③ 与 ④ 拆成两个方法：`commitUnderLock` 拿锁 → **复查 head** → 调 `commit`。理由：把复查写进 `commit`
会让"锁罩住了什么"说不清（一个方法里既有锁内的复查又有可被复用的写）。`commit` 的 javadoc 已注明
"**只在 `commitLock` 内被调用，本方法自己不复查**"。

### 4.3 ★ `base` 改用 **③ 复查过的那个 head**，不用 ① 读到的那个

计划没规定这一点。两者在成功路径上**相等**，但语义是"**现在**"——`commit` 据 `base` 算 `revision + 1`，
这正是 §2 那条主键冲突的收口处。若沿用 ① 读到的 base，那么 ① 与 ④ 之间的窗口依旧敞着，
两个线程仍会算出同一个 revision 号（只是这次会被锁串行化成一次真冲突而不是主键崩溃）。
**这是 Task 9 报告 §5 那条硬接缝的实际修法。**

### 4.4 ★★ m2 的预测被推翻——而推翻它的是我自己的一个错

计划说 m2"**不一定**让 R7 红……**执行者若发现 m2 存活，如实记录为等价变体**"。**实测：m2 不存活。**
R7 那 1 条**确实通过**（计划这部分判断对了），但 `CommandBusDispatchTest` 里那条
`staleExpectedRevisionConflictsWithRealHead` **把它抓住了**。

★ **我差点把一个"已被钉死"写成"等价变体"**：我在跑 m2 之前先读了那条用例（170~184 行），
看到 `busWith(handler("unit.RenameUnit"))`，就下结论"它只断言结局是 `Conflict`，没有断言 handler 没跑"，
并据此预告 m2 会存活。**错了**——`handler(String)` 返回的是**带炸药的替身**，`handle` 一进去就抛
`IllegalStateException("本用例不该跑到 handler: …")`（`CommandBusDispatchTest$3.handle:393`）。
**handler 本身就是那条断言。** 我下结论时只读了用例主体、没读它引用的替身工厂。
⇒ 这是**形态 5** 的又一实例：**"我记得是这样"混进了"我验过了"**。记在这里，因为它的代价恰好是
"把一条已经存在的护栏漏报成不存在"——与 CLAUDE.md 里 ugrep / `git grep` 那一族**同形**。

**结论**：m2 不是等价变体。① 的"过期快失败"那一半**已经被 Task 9 钉住**，Task 10 没有留下缺口。

### 4.5 补跑 m2b：① 其实有**两半**，要分开量

发现 m2 的杀点来自 ① 的另一半之后才看清：① 的条件是 `head.isEmpty() || head.get().compareTo(expected) != 0`，
**两个析取项是两件事**——

- `compareTo != 0`：**过期快失败**（纯优化，省掉一次昂贵的 handler）；
- `head.isEmpty()`：**分支不存在**（不只是优化——`head.get()` 在空 Optional 上会炸）。

m2 只掐了前者，所以 `missingBranchIsRejectedNotConflicted` 在 m2 下**照常通过**。补 m2b 掐后者，
确认它被那条既有用例钉死（`NoSuchElementException`）。⇒ **① 的两半各有护栏，都不是装饰。**

### 4.6 补跑 m4：新加的 `+1` 直证必须自带故意违规用例

`m1` 让我注意到用例**没有直接钉住"一轮恰好前进一个 revision"**——那条算式只被"恰一个 Committed"
**间接**约束着（若步长是 +2 而仍然只有一个 Committed，旧断言不会响）。故补了一条直证：

```java
assertThat(winner.revision()).isEqualTo(new RevisionId(expected + 1));
```

按形态 1，**新增护栏必须配故意违规用例**，否则是装饰。m4 就是它（`+1` → `+2`），实测红在该行、第 0 轮即红。

### 4.7 spotless：**第三次**踩同一个坑

`CommandBus.java` 被我按语义手工断行的中文 Javadoc 被判格式违规（首跑 `full-verify.log` 里 63 行 `[ERROR]`）。
CLAUDE.md 写着"写注释不要手工调行宽"——我还是犯了。**代价不只是重跑**：格式器改了源码 ⇒ 五个变异体的
原件基线全变 ⇒ 变异轮必须重新生成、重跑（见 §3）。

## 5. 我未能核实的（不许当结论引用）

1. ★ **注入件的并发安全不在本任务的锁里**。`StateLoader` 与 `AdvanceRoute` 是注入的；R7 用的替身 loader
   **返回一个常量**，`advanceRoute` 一进去就抛（本用例不走那一支）。**真装配是 `replay::replay`（Task 13）**
   ⇒ "`Replay` 在并发下安全吗"**本任务没有验**，归 Task 15。**本任务的锁只保证 `CommandBus` 自己的状态机。**
2. **`ForkBranch` 支与 `AdvanceTime` 支没有并发用例**。推理是"`fork` 的检查与插入在同一事务（Task 6），
   且它写的是**新分支**、动不了 `head(source)`，故不需要本任务的锁"——**但这是推理，不是实测**。
   `AdvanceTime` 支的目标（Task 12）此刻还不存在，无从测。
3. **R7 的替身 handler 不写状态**，故"两个 handler 同时跑会不会互相污染 `SimulationState`"**没有验**。
   C17 要求 handler 是纯函数，但"纯函数"这条约束没有任何用例在**并发**下验过。
4. **K = 50 是计划定的，不是量出来的**。没有测"多少轮才足以稳定暴露 m1 那类缺陷"——m1 在**第 0 轮**就红了，
   就 m1 而言 K=50 是**过量**的。K 的选取没有敏感性分析。
5. **单机 2 核**：R7 的 2 线程是本机（`nproc=2`）能给的**最大真并发**。更宽的并发形态
   （>2 线程、跨多个分支同时提交）未测。
6. **屏障 2 秒超时的选择没有做敏感性分析**。m3 实测 100.7 s ＝ 50×2 s，说明超时确实按设计触发；
   绿轮跑完 50 轮的实测耗时是 **0.473 s**（`full-verify.log`，门禁轮）/ **0.649 s**（`green-final.log`，迭代轮），
   **零次超时**——正常路径下屏障即刻开闸、几乎不花钱。但"2 秒是否够"在机器负载极高时**理论上**可能假红，
   本任务**没有**在高负载下测过 R7。
   ★ 更正：本项初稿把绿轮耗时写成"3.6 s"，那是**加 `+1` 直证之前**那一轮的数；最终 artifact 上的实测值如上。
7. **锁粒度论证依赖 `SqliteStore` 的实现**：类注里"只锁 ③④ 就够"的理由是
   `SqliteStore.inTransaction` 用**一把私有锁 + 一条共享连接**把每个事务串行化了（Task 5/C23）。
   ★ 更正：本项初稿写"没有任何用例钉住它"——**说重了**。该性质**已被钉住**：
   `SqliteStoreTest.concurrentTransactionsSerializeOnThePrivateLock`（第 158 行）实测"甲在事务内持锁期间，
   乙的事务不可能完成"（乙的 future 在 200 ms 后仍未 done；放行后返回 42L）。**没被钉住的是推论的一半**：
   "因为事务被串行化 ⇒ `CommandBus` 只需锁 ③④ 即可"——这条**由 Task 10 的 R7 间接支撑**（m1 红、加锁后绿），
   但**没有一条用例直接断言"① 与 ② 在锁外同时执行是安全的"**。若将来 store 换成连接池/多连接，
   `concurrentTransactionsSerializeOnThePrivateLock` 会先红（那是好事），而本类该锁到哪里需要重估。
8. **`OptimisticConcurrencyTest` 只在主树跑过**。它不碰文件系统（`@TempDir` 由 JUnit 给绝对路径）、
   不依赖 cwd ⇒ **据理**不受 CLAUDE.md 形态 1 那条"目录形态"陷阱影响，但**本任务没有在 worktree 里跑过它**。
9. **"提交失败之后，同一个 store 还能继续被用吗"——只测到了一半。**
   ★ 更正：本项初稿写"这是实测到的（后续 49 轮照跑）"——**那是错的**：m1 与加锁前那轮都**在第 0 轮**就中断了，
   根本没有"后续 49 轮"。实测到的只有：**回滚之后 store 仍可读**（`SqliteStoreTest.foreignKeysRejectAMissingParent:118`
   与 `notNullViolationLeavesNoResidue:140` 都在事务失败之后调 `countRevisions()` 并成功）；
   **"回滚之后同一个 store 仍能接受新的提交"没有任何用例钉住**——包括本任务。

## 6. 证据清单（`task-10-evidence/`）

| 文件 | 是什么 |
|---|---|
| `full-verify.log` | ★ 关账全量绿轮（644 条、rc=0、`[ERROR]` 0） |
| `red-before-lock.log` | ★ **红在前**：加锁前的 `SQLITE_CONSTRAINT_PRIMARYKEY` 现场 |
| `green-final.log` | 最终 artifact 上的迭代绿轮（14 条 = R7 1 + Task 9 的 13） |
| `m1.log` ~ `m4.log` | 五轮变异的 maven 日志（红在哪、消息是什么） |
| `mutants/mut-round.sh` | 变异装置（自证四件事，见 §3） |
| `mutants/{m1,m2,m2b,m3,m4}.CommandBus.java` | 五个变异体 |
| `mutants/orig/CommandBus.java` | 原件参照（**spotless 格式化后**的那一版，还原源） |
