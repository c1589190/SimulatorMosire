# R1~R18 证据点验（草案，2026-09-19，子代理检索）

> 本文件是**只读取证**的产物：不跑 Maven、不改源码、不提交。
> 每个断言都指向本次会话**实际打开或 grep 过**的路径。"自证过"要求存在**变异轮**证明该用例在被保护行被删/改后真的红；
> 只有报告表、没有落盘日志的，在备注里单独标注（**报告在案 ≠ 日志在案**）。
> ★ 先记一个**结构性异常**：Task 1/2、Task 4、Task 5 **没有 `task-N-evidence/` 目录**
> （`task-1-2-report.md`/`task-4-report.md`/`task-5-report.md` 的变异日志分别留在 `/tmp` 或仅以表格存在）。
> ⇒ R1 / R2 / R13 / R15 / R18 的"故意违规自证"目前**只有报告文字，没有入库的 Maven 日志**。

| # | 护栏（spec §11 原文摘要） | 实现用例（file:method） | 故意违规自证（变异轮证据路径） | 状态 | 备注 |
|---|---|---|---|---|---|
| R1 | main 源码里 `implements ChangeSet` 的实现者恰 4 个（World/Map/Social/Unit） | `simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java:24` `changeSetHasExactlyFourMainSourceImplementors` | `task-4-report.md` §4 **m1b**（删 `SocialChangeSet implements ChangeSet` + 连带删 orphan import）⇒ 红在 `ArchitectureGuardsTest.java:40`（`containsExactly` 缺 SocialChangeSet） | 自证过 | **仅报告表，无落盘日志**（报告 §4 自陈实验台 `/tmp/m4t4/`；无 `task-4-evidence/`）。core 侧 `RepoSourceScan` 已在本任务改为**只看相对路径段**，worktree（路径含 `.claude`）下不再空扫；m1 原形因 Checkstyle UnusedImports 先红被作废，m1b 才是有效轮 |
| R2 | `revisions` 父指针完整性：插一条 parent 不存在的行 ⇒ 抛（证明 `PRAGMA foreign_keys=ON` 生效） | `simos-core/src/test/java/io/mosire/simos/core/store/SqliteStoreTest.java:98` `foreignKeysRejectAMissingParent` | `task-5-report.md` §5 **m1**（删 `PRAGMA foreign_keys = ON` 那条 execute）⇒ 红在 `foreignKeysRejectAMissingParent` + `theThreePragmasAreActuallyInEffect` | 自证过 | **仅报告表，无落盘日志**（无 `task-5-evidence/`；报告 §4 日志在 `/tmp/task5-verify2.log`）。报告 §7.6 明确：规范 FK 错误文本来自本用例，探针库的 `foreign key mismatch` 是另一出处 |
| R3 | checkpoint 可派生性：一批 `(b,r)` 上 `hasCheckpoint` 与磁盘文件存在性逐条一致 | `simos-core/src/test/java/io/mosire/simos/core/store/CheckpointStoreTest.java:144` `diskFilesMatchHasCheckpointRowByRowAcrossABatch` | **无专打该不变量的变异体**。`task-7-evidence/mut-m2.log:189` 是**连带红**（m2 改的是 `read` 缺失即抛，非 R3 逻辑）；`task-13-evidence/logs/t13-m4.log` 打的是 `CoreSimos` 多写 checkpoint，红在 `CoreSimosTest` 而非本用例 | 未自证 | 用例在案且有"四真三假"防退化断言（测试源码 `:172`），但没有一条变异体证明它真会因 R3 被破坏而红。task-13 m4 证的是**反方向**（磁盘多文件）且载体不同（CoreSimosTest） |
| R4 | 重放对拍：同一 `(b,r)`，"从最近 checkpoint 重放"与"从创世全量重放"结果 `equals` | `simos-core/src/test/java/io/mosire/simos/core/store/ReplayTest.java:272` `replayFromTheNearestCheckpointEqualsReplayFromGenesis` | `task-8-evidence/m2.log:266`（m2：`Replay` 读到 checkpoint 后 `path.clear()`，直接返回 checkpoint 状态）⇒ 红在本用例（含对拍判别力断言） | 自证过 | 日志落盘在案；另见 `m2.log` 6 failures 波及 `replayRebuildsTheIndependentlyConstructedTruthAtEveryCoordinate:250` |
| R5 | 重放步数 ≤ N：任取 target，走到 checkpoint 前的 `apply` 次数不超过 N | `simos-core/src/test/java/io/mosire/simos/core/store/ReplayTest.java:295` `replayStaysWithinTheCheckpointIntervalEvenAcrossAFork` | `task-8-evidence/m1.log:290`（m1：`Timeline.hasCheckpoint` 去掉第②项「分岔点强制」）⇒ 红在跨分支 ≤N 断言 | 自证过 | 日志落盘在案。★ `task-8-report.md` §5.2：R5 上界**条件成立**——依赖 C19 应当有的 checkpoint 都在；劣化形态（文件缺失）会超 N，用例已把 `isEqualTo(8)` + `isGreaterThan(N)` 一起写成断言 |
| R6 | 判据二：一条命令的 `correlationId` 在两张表上的类型序列逐条断言 + `revisions` 恰 1 行 | `simos-core/src/test/java/io/mosire/simos/core/command/CorrelationChainTest.java:92` `successfulCommandLeavesReceivedAndCommittedUnderItsCorrelationId` | `task-11-evidence/logs-postspotless/t11-m1.log`（m1：事件 `correlationId` → `commandId`）⇒ 6 条中 3 红，含本用例 `:103` | 自证过 | 日志落盘在案（12 轮全杀）。★ 真 `TimeAdvance` 的整条冻结序列在 `task-12-evidence/logs/t12-m6.log` 佐证（`TimeAdvanceTest.realRouteProducesTheFrozenR6Sequence`）；Task 11 用替身 route 的那条**只算机制级**，已被 Task 12 真 route 取代。★ `ForkBranch` 支**不发事件**（`task-11-report.md` §4.2 / `task-14-report.md` §6.1）⇒ 判据二现在覆盖不到分岔 |
| R7 | 判据三：真实并发 K=50 轮，每轮恰一 `Committed` 恰一 `Conflict`，`Conflict.current == Committed.ref` | `simos-core/src/test/java/io/mosire/simos/core/command/OptimisticConcurrencyTest.java:104` `twoThreadsWithTheSameExpectedRevisionProduceExactlyOneCommitAndOneConflict` | `task-10-evidence/m1.log:138`（删 ③ 锁内复查 ⇒ 两个 Committed）、`task-10-evidence/m3.log:170`（handler 挪进锁内 ⇒ 自证断言实得 0 次）；Task 11 重跑 `t10-m1..m4`（`task-11-evidence/logs-postspotless/t10-m*.log`） | 自证过 | Task 15 **零改动关账**（台账 `progress.md:1336-1340`，**裁定 54**：Task 10 已全量满足，核验 log `task-15-evidence/verify.log`：`Tests run: 1, rc=0`）。★ 报告 §5.1：本任务的锁**只保证 `CommandBus` 自身状态机**，注入的 `StateLoader`/`AdvanceRoute` 是否并发安全**未验** |
| R8 | 判据一：分岔后两侧独立；父链指回 `(main,r)`；`main` 再推进不影响 `b2` | `simos-core/src/test/java/io/mosire/simos/core/BranchingEndToEndTest.java:86` `forkEndToEndWithRealRenameCommands` | `task-14-evidence/logs/t14-m1.log:141`（`Timeline.head` 忽略 branch ⇒ 判据一③ head 断言红）、`t14-m2.log:163`（`Replay` 不施加变更集 ⇒ 判据一① 逐值断言红） | 自证过 | 日志落盘在案（2 轮 0 存活）。机制面另由 `CoreSimosTest.forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint` 承担（`task-13-evidence/logs/t13-m1/m2.log`） |
| R9 | 写-写 ⇒ 拒绝，且不留 revision（拒绝必须原子） | `simos-core/src/test/java/io/mosire/simos/core/advance/TimeAdvanceTest.java:286` `writeWriteConflictIsRejectedAndLeavesNoRevision` | `task-12-evidence/logs/t12-m2.log`（m2：写-写改成"记警告并放行"）⇒ 红在本用例 | 自证过 | 日志落盘在案（9 轮全杀；`run-all-rounds.out` 逐轮显示 m2 2 failures 含本用例） |
| R10 | 读-写 ⇒ 放行，且事件里的地址列表按字典序 | `simos-core/src/test/java/io/mosire/simos/core/advance/TimeAdvanceTest.java:324` `readWriteOverlapIsCommittedAndItsEventAddressesAreSorted` | `task-12-evidence/logs/t12-m4.log`（地址列表不排序）、`t12-m5.log`（`namespaces` 也排序 = 真实犯过错的复原体）；另 `task-16-evidence/step3/logs/round-m2.log:224`（真实参与者 `writes` 恒空） | 自证过 | 日志落盘在案。★ `namespaces` **有意不排序**（裁定 49，C15 只管地址）；m5 正是该真缺陷的复原体 |
| R11 | 不透明载荷原样转交：替身 handler 收到的 `payloadJson` 与信封逐字节相同 | `simos-core/src/test/java/io/mosire/simos/core/command/CommandBusDispatchTest.java:151` `payloadJsonIsForwardedByteForByte` | `task-9-evidence/m1-red.log:169`（m1：分派路径 `payloadJson().trim()`）⇒ 13 条里恰红这 1 条 | 自证过 | 日志落盘在案。★ m1 的判别力**全部来自夹具**：12 条用 `"{}"` 的用例全绿，只有带首尾空白 + 非字典序键的那条红 |
| R12 | `CommandHandler` 同 `type` 注册两次 ⇒ 构造期抛 | `simos-core/src/test/java/io/mosire/simos/core/command/CommandRegistryTest.java:40` `duplicateTypeFailsAtConstruction` | `task-9-evidence/m2-red.log:44`（m2：删重复检查、改 `table.put` 静默覆盖）⇒ 6 条里恰红这 1 条 | 自证过 | 日志落盘在案。m2 首轮曾因忘 export JAVA_HOME 未启动 Maven 而被作废，报告 §3.1 记录并补了 `Tests run ≥ 1` 门禁 |
| R13 | 事务原子性：写一行 revision + 一条违反 NOT NULL 的事件 ⇒ 抛且 `revisions` 不留残行 | `simos-core/src/test/java/io/mosire/simos/core/store/SqliteStoreTest.java:123` `notNullViolationLeavesNoResidue` | `task-5-report.md` §5 **m2**（删 catch 里的 `rollbackQuietly()`）⇒ 红在本用例 + `foreignKeysRejectAMissingParent`（残事务未回滚，后续 BEGIN 起不来） | 自证过 | **仅报告表，无落盘日志**（无 `task-5-evidence/`）。Task 11 另用互补对（先 revision / 先事件）在 `CorrelationChainTest` 上验同一事务性（`t11-m2/m2b.log`），但那是 R6 的载体、不是 R13 |
| R14 | 未注册 namespace 的 proposal ⇒ `Rejected` 且不留 revision | `simos-core/src/test/java/io/mosire/simos/core/advance/TimeAdvanceTest.java:367` `proposalWithoutARegisteredCodecIsRejected` | **无变异体打到这里**。逐轮 grep `t12-m1..m9.log` 的 `TimeAdvanceTest.*` 方法名，九轮均未出现本用例；`run-all-rounds.out` 9/9"杀死"但杀点分别是 ③ 单向、写-写放行、④ 第 0 项、地址序、namespaces 序、参与者注册序、无条件折冲突、checkpoint m8/m9 | 未自证 | 用例在案（`task-12-report.md` §3 列为 R14 落点，且属"④ 五项各有故意违规用例"之一），但**没有针对"未注册 codec 该拒"的变异轮**。★ 注意区分：**"故意违规用例"（用例自己造违例输入）≠"变异自证"（删实现行看用例红不红）**，本表要的是后者 |
| R15 | 全仓 `assertSnapshotRoundTrip` 0 处 | `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java:103` `snapshotRoundTripAssertionIsGoneFromTheWholeRepo` | `task-1-2-report.md` §3 **Task 1 m3 第 3 轮**（注回空体 stub）⇒ 红在 `:116 isEmpty()`，命中清单恰为被变异文件本身 | 自证过 | **仅报告表，无落盘日志**（无 `task-1-2-evidence/`）。★★ 台账 `progress.md:144` **裁定 23**：R15 扫描装置按**绝对路径**判隐藏段，worktree 路径含 `.claude` ⇒ 曾**恒绿**（主树 hits=44 / worktree hits=0，已当场复现）；修于 `50921bc`，修后主树 44 / worktree 45。**worktree 里跑出的旧 R15 绿一律按空真处理**（`progress.md:157,168`）。本次扫到的落盘证据里没有该四格实测日志 |
| R16 | 判据四：`AdvanceTime` 推进后位置真的变、`movement` 真的清，且重放结果与原状态 `equals` | `simos-core/src/test/java/io/mosire/simos/core/RealmEffectEndToEndTest.java:119` `realAdvanceMovesUnitAndReplayRebuildsItFromTheNewRevision` | `task-16-evidence/step3/logs/round-m1.log:148`（已抵达不清 `movement`）、`round-m2.log:224`（真实参与者 `writes` 恒空，R10 真实链路） | 自证过 | 日志落盘在案（2 轮 0 存活）。★ `task-16-step3-report.md` §6.5 如实记：spec §11 R16 的"**原状态**"指代含混，本步解读为"重放 == 不调 participant 独立重建的期望状态"，无 ④ applied 中间态可比 |
| R17 | checkpoint 路径分支名安全：含 `/`、`\`、`..` 或空 ⇒ 抛 | `simos-core/src/test/java/io/mosire/simos/core/store/CheckpointStoreTest.java:120` `rejectsBranchNamesThatAreNotFilenameSafe` | `task-7-evidence/mut-m1.log:127`（m1：删 `fileFor` 里分支名路径字符校验）⇒ 恰红本用例 | 自证过 | 日志落盘在案。★ m1 的红因是"**尝试**写穿"非"成功写穿"（本机沙箱令 `writeString` 仍 ENOENT，`task-7-report.md` §4.7）；"空分支名"分支实不可达（`BranchId` 已禁 blank），保留为纵深防御 |
| R18 | `util.state.ChangeSet` 无抽象方法（标记接口）、`Command` 仍是 SAM（恰 1 个抽象方法） | `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java:28` `changeSetIsAMarkerAndCommandIsStillASingleMethodInterface` | `task-1-2-report.md` §3 **Task 1 m1**（给 `ChangeSet` 加回 `baseRevision()`）红在 `:33 hasSize(0)`；**m2**（给 `Command` 加第二个抽象方法）红在 `:36 hasSize(1)` | 自证过 | **仅报告表，无落盘日志**（无 `task-1-2-evidence/`）。m1 初稿曾把方法插到接口体**外**（会成编译错误），staging 阶段发现即重造未推入 |

---

## 检索说明

本次实际打开/检索过的路径（只读）：

**spec / 计划**
- `docs/superpowers/specs/2026-09-18-core-simos-design.md`（§十 收尾段 540-573、**§十一 护栏清单 575-599**）
- `.superpowers/sdd/2026-09-18-core-simos-plan/progress.md`（grep：`裁定 54` / `Task 15` / `RepoSourceScan` / `R15` / 绝对段 / 相对段；读了 45、144、157、168、1336-1340 各行）

**任务报告（全文读过）**
- `task-1-2-report.md`、`task-3-report.md`、`task-4-report.md`、`task-5-report.md`、`task-6-report.md`、`task-7-report.md`、`task-8-report.md`、`task-9-report.md`、`task-10-report.md`、`task-11-report.md`、`task-12-report.md`、`task-13-report.md`、`task-14-report.md`、`task-16-report.md`、`task-16-step3-report.md`

**实现用例（`git grep --untracked` 定位方法名 + 逐条读文件片段）**
- `simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java`（:24）
- `simos-core/src/test/java/io/mosire/simos/core/store/SqliteStoreTest.java`（:98、:123）
- `simos-core/src/test/java/io/mosire/simos/core/store/CheckpointStoreTest.java`（:120、:144；读 140-200 段）
- `simos-core/src/test/java/io/mosire/simos/core/store/ReplayTest.java`（:272、:295）
- `simos-core/src/test/java/io/mosire/simos/core/command/CorrelationChainTest.java`（:92）
- `simos-core/src/test/java/io/mosire/simos/core/command/OptimisticConcurrencyTest.java`（:104）
- `simos-core/src/test/java/io/mosire/simos/core/command/CommandBusDispatchTest.java`（:151）
- `simos-core/src/test/java/io/mosire/simos/core/command/CommandRegistryTest.java`（:40）
- `simos-core/src/test/java/io/mosire/simos/core/advance/TimeAdvanceTest.java`（:286、:324、:367）
- `simos-core/src/test/java/io/mosire/simos/core/BranchingEndToEndTest.java`（:86）
- `simos-core/src/test/java/io/mosire/simos/core/RealmEffectEndToEndTest.java`（:119、:196）
- `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java`（:28）
- `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java`（:103）

**变异证据（落盘日志 grep，非转述报告）**
- `task-7-evidence/mut-m1.log`、`mut-m2.log`
- `task-8-evidence/m1.log`、`m2.log`
- `task-9-evidence/m1-red.log`、`m2-red.log`
- `task-10-evidence/m1.log`、`m3.log`
- `task-11-evidence/logs-postspotless/t11-m1.log`
- `task-12-evidence/logs/t12-m1..m9.log`、`logs/run-all-rounds.out`、`mutants/t12-m3.TimeAdvance.java`
- `task-13-evidence/logs/t13-m4.log`（用于 R3 的对照判定）
- `task-14-evidence/logs/t14-m1.log`、`t14-m2.log`
- `task-16-evidence/step3/logs/round-m1.log`、`round-m2.log`
- `task-15-evidence/verify.log`
- **目录清点**：`find .superpowers/sdd/2026-09-18-core-simos-plan/task-*-evidence -type f` —— 确认 **无** `task-1-2-evidence/`、`task-4-evidence/`、`task-5-evidence/`

## 汇总（子代理判读，供 Task 17 关账参考）

- **自证过（有落盘日志）**：R4、R5、R6、R7、R8、R9、R10、R11、R12、R16、R17（11 条）
- **自证过（仅报告表，日志未入库）**：R1、R2、R13、R15、R18（5 条）
- **未自证（用例在案，无针对它的变异轮）**：R3、R14（2 条）
- **未找到**：无
- **明确记载的等价变体/存活**：与 R1~R18 直接相关的**无**；Task 5 自加轮 m4（`PRAGMA wal_checkpoint`）实测存活（`task-5-report.md` §5/§7.1），但打的**不是** R1~R18 的任何一条
- **需要 Task 17 裁定的两项缺口**：① R3、R14 缺变异自证；② Task 1/2、4、5 的变异日志未入库（是否补跑/补档，由控制器裁定）

---

## ★ 控制器补证与裁定（2026-09-19，关账当场）

**① R3 / R14 已当场补证（不再是"未自证"）**——装置 `task-17-evidence/mutants/mut-round.sh`（九道门禁 + 报告 FQN 路径 + mtime 纳入判定）：

| 轮 | 变异 | 目标 | 红在哪（照抄日志） | 门禁 |
|---|---|---|---|---|
| `r14` | `TimeAdvance.validate` 未注册 namespace **放行**（`Validation.fail(...)` → `Validation.ok(Map.of())`） | `TimeAdvance.java` | `TimeAdvanceTest.proposalWithoutARegisteredCodecIsRejected:377`（`isInstanceOf(Rejected)` 断言） | rc=1、`COMPILATION_ERROR=0`、`Tests run=1`、报告 mtime `1789753910 ≥` 轮起点 `1789753907`、逐字节还原 `bc5072b1…` |
| `r3` | `CheckpointStore.write` **不落盘**（校验照做、去掉 `Files.writeString`） | `CheckpointStore.java` | `CheckpointStoreTest.diskFilesMatchHasCheckpointRowByRowAcrossABatch:188 [文件存在性 == hasCheckpoint: main@1]`（跨件一致性断言本身） | rc=1、`COMPILATION_ERROR=0`、`Tests run=1`、报告 mtime `1789753923 ≥` 轮起点 `1789753916`、逐字节还原 `c993cdb9…` |

还原后定向复跑两方法 **rc=0、2/2 绿**（`logs/green-after-r3-r14.log`）。
★ 装置首跑实测一处**假阴性**并当场修掉：surefire 报告按**全限定名**落盘，按短名取文件读到 mtime=0（"没读到"被当"没查"）——修法写进 `mut-round.sh` 头注。**先怀疑自己的读取**。

**② 裁定 56 —— Task 1/2、4、5 的变异日志未入库（R1/R2/R13/R15/R18）：接受为"报告在案"，不补跑。**
理由：五条 R 的**用例在案且当前全绿**，其变异轮红点/消息已在各自报告表逐条记；补跑要**重造已消失的变异体**（task-1/4/5 的 mutants 未入库）并动四个已关账任务的产物，成本远超收益（纪律推论"修复比描述还短"在此不适用）。⇒ **如实存档**：这五条的证据等级是"报告在案、日志未入库"，**不假装**与有落盘日志的 11 条同级。后续若重审这些护栏，须重跑。
★ 本条同时记入 M4 关账报告的"带裁定的遗留条目"与"我未能核实的"。

