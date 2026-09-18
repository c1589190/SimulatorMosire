# Task 11 报告：可观测性——八类事件 + `Digest` + `correlationId` 全链 + R6（2026-09-18）

**执行方式**：控制器**内联**执行（主树 `feat/adr1-core-scope`）。本轮派发的 agent 死于 **429**（账号级五小时额度，
换 agent 不解决，重置于 2026-09-18 20:19:36）。与 Task 8/9/10 同例。

**任务范围**（M4 spec §7.1/§7.3/§8.1 + 计划 Task 11）：事件类型冻结表、`EventRow`/`EventStore` 读写、
`CommandBus` 接上事件链、§7.3 的 SLF4J 日志、`correlationId` 全链可追（**判据二 / R6**）。

---

## 1. 交付物

| 文件 | 态 | 说明 |
|---|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/observe/EventTypes.java` | 新 | 八类事件常量冻结表 + `ALL`/`ALL_SET`/`isKnown` |
| `simos-core/src/main/java/io/mosire/simos/core/store/EventRow.java` | 新 | 事件行载体。**`seq` 有意缺席**（由库的自增主键给）；`ts` 是墙钟 `Instant` |
| `simos-core/src/main/java/io/mosire/simos/core/store/EventStore.java` | 新 | 读侧（`byCorrelation`/`count`）。**写侧是静态方法、参数收 `Connection`** ⇒ 它**无法**自开事务（见 §3 的 m2/m2b） |
| `simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java` | 改 | 事件链 + 四条日志。`git diff --stat` 实测 **+220 / −16** 行量级 |
| `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java` | 改 | 只增：`appendRevision(RevisionRow, List<EventRow>)` 与 `appendEvents(List<EventRow>)`。**单参版 `appendRevision` 一字未动** ⇒ Task 6/8/10 的既有守卫无需改一行 |
| `…/core/test/…/observe/EventTypesTest.java` | 新 | 6 条 |
| `…/core/test/…/observe/DigestTest.java` | 新 | 6 条 |
| `…/core/test/…/command/CorrelationChainTest.java` | 新 | 6 条（R6 的主守卫） |
| `…/core/test/…/command/CommandBusLoggingTest.java` | 新 | 6 条（§7.3 的守卫） |

★ 未新增 `core/observe/Digest.java`——**复用** agentlib 的既有类，见 §4.1。

---

## 2. 实测结论行（照抄日志，非转述）

**干净轮**（`task-11-evidence/clean-4classes.log`，rc=0；逐类数字抄自同轮写下的 surefire 报告，mtime `19:42`）：

```
CorrelationChainTest     Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
EventTypesTest           Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
DigestTest               Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
CommandBusLoggingTest    Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

⇒ **24 条全绿**。源码逐类 `@Test` 计数亦为 6/6/6/6（两处独立计数一致）。

**变异轮**：12 轮，**12 个变异体全部被杀**，每一轮六道门禁全绿（见 §3）。

---

## 3. 变异自证（逐轮）

装置：`task-11-evidence/mutants/mut-round.sh`（改写自 Task 10 版）。与 Task 10 那版的差别是**把自证项从"打印"改成"硬门禁"**——
打印了而没人看等于没有。任一门禁不过即 `exit 9`，该轮**当场作废**。六道门禁：

1. 开跑前工作树必须是干净世界（`worktree_before == orig` 的 md5）；
2. 变异体必须与原件**字节不同**（否则 javac 编的可能还是原件，三向全绿）；
3. **按白名单**推送成**规范文件名**（不是把变异体文件名拷进去——那会让"红"变成编译错误）；
4. 强制断言 `COMPILATION ERROR` 计数为 **0**（否则红的理由是"没编过"）；
5. 强制断言 `Tests run` 行 ≥ 1（否则"没红"可能只是**根本没跑到**）；
6. 跑完必须回到开跑前的工作树状态，并**再验一次 md5**。

12 轮实测（日志 `task-11-evidence/logs-postspotless/<名>.log`）：

| # | 变异体 | 打的是哪条 | 期望类 | 实际变红的方法 |
|---|---|---|---|---|
| 1 | `t11-m1` | 事件的 `correlationId` → `commandId`（判据二的全部） | `CorrelationChainTest` | `successfulCommandLeavesReceivedAndCommittedUnderItsCorrelationId` +2 |
| 2 | `t11-m2` | `Timeline` 拆两事务，**先 revision 后事件** | `CorrelationChainTest` | `failedEventInsertLeavesNoRevisionRowBehind` |
| 3 | `t11-m2b` | 同上前提，**先事件后 revision** | `CorrelationChainTest` | `failedRevisionInsertLeavesNoEventRowsBehind` |
| 4 | `t11-m3` | §7.3：删掉「命令提交」那条日志 | `CommandBusLoggingTest` | `allFourLifecycleLinesAreEmitted` + `commitAndRejectLinesCarryTheirOwnActionableDetail` |
| 5 | `t11-m4` | §7.3：冲突日志印**期望值**而非真实 head | `CommandBusLoggingTest` | `conflictLineReportsTheRealHeadNotTheExpectedOne` |
| 6 | `t11-m5` | §8.1：提交日志带上**载荷明文**（哨兵串） | `CommandBusLoggingTest` | `noLogLineCarriesThePayloadPlaintext` |
| 7 | `t11-m6` | C20：`ALL` 里混进不存在的 `simos.revision.created` | `EventTypesTest` | `allHoldsExactlyTheEightFrozenTypes` + `revisionCreatedIsDeliberatelyNotAType` |
| 8 | `t10-m1` | 判据三：删掉 ③ 锁内复查 | `OptimisticConcurrencyTest` | `twoThreadsWithTheSameExpectedRevision…`（实得**两个 Committed**） |
| 9 | `t10-m2` | ① 的「过期快失败」半边改恒 false | `CommandBusDispatchTest` | `staleExpectedRevisionConflictsWithRealHead`（**`» IllegalState 本用例不该跑到 handler`**） |
| 10 | `t10-m2b` | ① 的「分支存在性」半边（去掉 `isEmpty` 短路） | `CommandBusDispatchTest` | `missingBranchIsRejectedNotConflicted`（`» NoSuchElement`） |
| 11 | `t10-m3` | C17：把 handler 挪进锁内（命名存实亡） | `OptimisticConcurrencyTest` | `twoThreads…`，红在**自证①**（屏障到达 0 次 ⇒ 有轮次被串行化） |
| 12 | `t10-m4` | revision 号 `+1` → `+2` | `CommandBusDispatchTest` | `appliedChangeSetBecomesOneRevisionRow…` + `committedRevisionInheritsParentTimestamp` |

★ **第 2/3 轮是设计出来的互补对**：`t11-m2`（先 revision）被 `failedEventInsert…` 杀，`t11-m2b`（先事件）被
`failedRevisionInsert…` 杀。**单有前者是假绿**——理由见 §4.5，那是我在动手变异之前先推理出来的一个洞。

★ **第 9 轮的红值得单说**：它红在**炸药替身 handler 抛异常**，不在断言值上。这不是缺陷，恰恰是 ① 的「过期快失败」
**存在**的证明：变异体让 ① 恒 false，命令于是**走到了 handler**——而按设计它压根不该走到那里。这正是
Task 10 裁定 41 记下的那个坑（我当时读漏了这个炸药替身，差点把"已被钉死"写成"等价变体"），本轮它当场兑现。

★ **第 10/11 轮的红是异常而非断言失败**，均落在被保护行为上：`t10-m2b` 去掉空值短路 ⇒ 无守卫的 `.get()` 当场爆
（用例本期望一个干净的 `Rejected`）；`t10-m3` 把 handler 罩进锁 ⇒ 屏障自证归零。两者都不是"编译不过"。

★ **`t10-*` 五轮是重跑**：Task 10 跑过且都红（日志在 `task-10-evidence/`）。重跑的理由是 Task 11 大改了
`CommandBus.java`（+220 行），旧证据的**对象已经变了**。五轮全数复现被杀。

---

## 4. 取代说明（计划 / spec vs 实测，以实测为准）

### 4.1 裁定 45：**不造** `core/observe/Digest.java`，复用 agentlib 的类

总纲 §8.1 行 503「参数摘要 | **复用 `AgentLibMosire` 的 `Digest`**（sha256 前 16 字节）——**不记明文**」、
§10.5 行 692、M4 spec §7.1 行 451 三处同口径。⇒ Task 11 **不新建** `Digest`，测试改为**钉住这个外来类的契约**
（`DigestTest`，6 条）。这不是装饰：总纲 §10.5 自己记着该类在旧的 49 类 agentlib 构件里**整个缺席**过，
且那次事故的形态是**编译失败**（比"测试变红"更早一步）。期望值全是**当场跑探针**得到的字面量（形态 5），
其中 `sha256("abc")` 的 32 位 hex 段 `ba7816bf8f01cfea414140de5dae2223` 正是公开 SHA-256("abc") 的前 16 字节
——"前 16 字节"这条口径由它**独立佐证**，不是只看长度像。

### 4.2 裁定 46：只有**信封支**的事件链归 `CommandBus`

`AdvanceTime` 是**原样交给注入的 `AdvanceRoute`** 的，它那一支的整条链（`received` → `started` → N×`proposal` →
`finished` → `committed`）由 **route 写**。理由不是分工好看，是**事务边界**：只有 route 能开那个事务。
若 `CommandBus` 替它写 `received`，那条事件必然落在**另一个事务**里，Step ④ 要的"revision 行 + 全部事件行同一事务"
当场就破了。

`ForkBranch` 支**不发事件**——**已知缺口**，理由与代价写在 `CommandBus#fork` 的注里（发事件要把
「新分支的 revision 恰为 1」这条 `Timeline` 的内部知识复制进 `CommandBus`）。如实记，不粉饰。

### 4.3 日志放在锁**外**：`dispatch` 拆成 `dispatch` + `routeEnvelope`

`commit` 是在 `commitLock` **内**跑的（Task 10 的硬接缝）。若把日志写在 `commit` 里，会有两个后果：
持有锁去做 IO；以及**若 appender 抛异常，它会在提交成功之后逃出 `submit`**——调用方看到异常、以为失败，
实际已提交。故把日志抬到 `dispatch` 里、锁外，`routeEnvelope` 只做分派。
`logOutcome` 用**穷尽 switch 且不写 `default`**，将来 `CommandResult` 加一个 case 会编译不过。

### 4.4 §7.3 的日志守卫差点写成**假绿**（探针实录）

测试类路径上确有 log4j2 绑定（`log4j-slf4j2-impl` + `log4j-core`，均 test scope），故可以真捕获。
但**探针实测**：**没有配置文件时 log4j2 的 root level 是 `ERROR`**——只挂 appender、不抬 `LoggerConfig` 的 level，
捕获结果是**空的**。⇒ 若本类只写"日志里不含明文"这一条否定式断言，它会在**空捕获**上**假绿**
（一条都没收到，"没有明文"当然成立，而且**没有任何症状**）。
故 `logLinesAreActuallyCaptured()` 是**前提断言**，先证明装置真在收，后面每一条才有意义。
（另有一处 API 细节靠 `javap` 现场判定：`removeAppender` 只在 `AbstractConfiguration` 上，不在 `Configuration`
接口上。）

### 4.5 ★★ 一个在**动手之前**靠推理抓到的假绿洞

`failedRevisionInsertLeavesNoEventRowsBehind` 的失败点在 **revision 写**。若谁把 `appendRevision` 拆成两个事务、
且**先写 revision 后写事件**，这条用例**照样绿**——revision 先炸，事件压根没轮到写，"事件一行不剩"当然成立。
⇒ 补了另一半 `failedEventInsertLeavesNoRevisionRowBehind`（失败点在**事件写**）：

- 拆成"先 revision 后事件" ⇒ **后者**红（revision 已提交，事件才炸，残行留下）；
- 拆成"先事件后 revision" ⇒ **前者**红。

§3 的第 2/3 轮正是这两条**各自**被杀——互补性由实测确认，不是推想。
手法：事件清单里塞一个 `null` 且**放在第二位**，好让第一位那条**已写进库**，从而真正考验"同一事务内的前一笔写会不会跟着回滚"。
（`SqliteStore.inTransaction` 的 `catch (Exception)` 收口了非 SQL 的运行时异常——**源码实测**，不是假定。）

### 4.6 ★ 装置的产物带状态：**第五个实例**——这次是 surefire 报告

写报告前我去读 surefire 数字，`CommandBusLoggingTest.txt` 赫然写着 **`Tests run: 6, Failures: 1`**。
差一点当成实测结论抄进 §2。**它是变异轮留下的陈旧报告**：该文件 mtime `19:35:56`，正是第 5 轮（`t11-m4`）的时间；
干净轮在 `19:42` 重跑后才把它覆盖成 `Failures: 0`。

⇒ 与 CLAUDE.md 形态 1 里那条 `target/classes` 陈旧 `.class` **同族，但载体不同**：`mut-round.sh` 还原的是**源文件**，
`target/` 下的一切（`.class`、`surefire-reports/*.txt`）**一律不还原**。
⇒ **纪律**：读 surefire 数字必须**先跑干净轮**，并且**核对报告 mtime 落在这一轮内**；不能拿"上次留下的绿"或
"上次留下的红"当本轮结论。两种方向都会骗人——M2 Task 1 那次是**假发现**，这次险些是**假失败**。

### 4.7 spotless 只动了注释——**用剥注释骨架实测**，不用"看着像注释"

12 轮变异跑完后我跑了 `spotless:apply`，它改了三个被变异的目标文件。**"看着只动了注释"不算证据**
（门禁 4 的教训：红的理由必须是被保护的那行本身）。故写了个剥掉 `/* */`、`//` 与全部空白后比对骨架的检查：

```
CommandBus   剥注释后骨架相同=True  (len 7420 vs 7420)
Timeline     剥注释后骨架相同=True  (len 9191 vs 9191)
EventTypes   剥注释后骨架相同=True  (len 1037 vs 1037)
```

三处**逐字节相同**（长度也相同）⇒ 代码未动。**但我没有据此免除重跑**：装置的第一道门禁是"开跑前必须是干净世界"，
快照是 pre-spotless 的字节 ⇒ 门禁 1 会对 post-spotless 的工作树**报错**，装置就**不可重跑**了。
故**刷新 `orig/` 快照 + 重新生成全部 12 个变异体**（`gen-mutants.py` 的 `edit()` 在锚点未恰好命中一次时硬失败，
且拒绝产出与原件字节相同的变异体），**再整体重跑 12 轮**。§3 的表是**对着出货字节**的那一遍。

---

## 5. 我未能核实的（不许当结论引用）

1. **★★ R6 的真序列生产者是 Task 12 的 `TimeAdvance`，本任务手里没有它。** `advanceSequenceShapeIsWhatR6Requires`
   用的是**替身 route**——它钉的是"落盘 + 排序 + 一条 SQL 追全链"这条**通路**，以及把 R6 的**冻结形状**
   写成一个 Task 12 必须满足的目标。**它不证明真 `TimeAdvance` 会产出该序列。** 用例的 javadoc 与类注都显式
   把"真代码路径"（信封支）与"机制级"（替身 route）分开写——混起来写会变成同义反复（"我写进去的能读出来"）。
2. **`ForkBranch` 支不发事件**——已知缺口（§4.2），代价与理由在源码注里。判据二若要覆盖分岔，**现在覆盖不到**。
3. **总纲 §8.1「每条命令固定记录」的九项里，M4 记不全四处**（源码 `received` 的注里逐条写明）：
   目标地址（Human 与 canonical，Core 只看得到不透明 `payloadJson`）/ 耗时（无计时器）/ 产出 ChangeSet 摘要
   （**有意不复制**——变更集全文在 `revisions.changeset_json`，复制就是同一事实两个来源）/ 结果与拒绝原因
   （由各自的事件类型承载）。**这四处是"留缺"，不是"没写"**；将来要补，补的是装配点不是字段。
4. **§8.1 的九固定字段装不进 events 表的五列**（`seq/ts/type/agent/payload/correlation_id`）⇒ 它们**骑在
   `payload` 里**。故 `Digest` 的参数摘要有一个真实的消费方，不是为了用而用。
5. **判据二只在 M4 的存储形态下成立**：`SqliteStore` 是**单连接 + 私有锁**（C23），"同一事务"由它保证。
   **换连接池 / 多连接时这条还成不成立——没验**，也不在 Task 11 范围内。
6. **`EventRow.ts` 是墙钟**（`Instant.now()` 形态），**不是模拟时间**。执行期裁定。跨机/跨时区重放的语义
   **未验**——`Replay` 用的是 revision 号与 `SimosTimestamp`，不是这张表的 `ts`。
7. **§7.3 点名的六项日志，本任务只覆盖四项**（接收/拒绝/冲突/提交）。"推进起止"归 Task 12、
   "checkpoint 写入与缺失回退"归 Task 7/8——**本任务没有验过后两项真的发得出来**。

---

## 6. 证据清单（`task-11-evidence/`）

| 路径 | 内容 |
|---|---|
| `clean-4classes.log` | 干净轮（4 类 24 条）的 Maven 输出，rc=0 |
| `run-all-rounds.sh` | 12 轮的驱动脚本（对着 post-spotless 字节） |
| `mutants/gen-mutants.py` | 12 个变异体的生成器。锚点未恰好命中一次即硬失败；拒绝产出与原件字节相同者 |
| `mutants/mut-round.sh` | 六道硬门禁的单轮装置（`exit 9` = 作废；`exit 8` = 变异体存活） |
| `mutants/orig/{CommandBus,Timeline,EventTypes}.java` | **post-spotless** 原件快照（门禁 1 的比对基准） |
| `mutants/t{10,11}-*.java` | 12 个变异体 |
| `logs-postspotless/t11-*.log`、`t10-*.log` | 12 轮的完整输出：md5 三重比对、`COMPILATION_ERROR` 计数、`Tests run` 计数、变红方法名、还原校验 |
| `t11-*.round.txt` | 各轮 surefire 的原始报告副本 |

★ 上一轮（pre-spotless）的日志**仍在原地**（`t11-*.log` 等，本目录下），未删——它们与 §3 的差异仅为
注释折行（§4.7 已实测），保留以便对照。**引用时以 `logs-postspotless/` 为准。**
