# Task 5 报告 —— `SqliteStore`（M4 / Batch C1）

执行者：M4 Task 5 实现者（B3 工作树）。日期：2026-09-18。

## 1. 第 0 步门禁（实际输出）

```
$ cd /home/dev/SimulatorMosire/.claude/worktrees/b3 && git log --oneline -1
b43d115 docs(m4): M4 计划/ spec / SDD 台账 入库 + CLAUDE.md 两处工具陷阱修正
```

门禁通过，才开始动工。

## 2. 交付物与边界

- 新增 `simos-core/src/main/java/io/mosire/simos/core/store/SqliteStore.java`（独占文件）
- 新增 `simos-core/src/test/java/io/mosire/simos/core/store/SqliteStoreTest.java`（独占文件）
- **未改任何 `pom.xml`**（sqlite-jdbc 3.53.4.0 main scope 已由控制器在 `ce98212` 加好）
- 未触碰 `Envelope.java` / `state/**` / `EventStore.java` / `timeline/**` / `test/resources/**`。
  ★ `Envelope` 至本任务收口时**不在我的工作树里**（B2 并行在途）——Task 5 零依赖（`Consumes: 无`），
  实际没有用到它，不需要等。

## 3. 落地的 schema 与 spec 的对应

以 spec §3.2 / §6.1 为权威（计划草图是计划期产物），用 `sqlite_master` 逐项断言
（用例 `schemaIsExactlyTwoTablesAndFourIndexes`，`containsExactly` 6 个对象，多一张少一张都红）：

| 对象 | 出处 | 落地 |
|---|---|---|
| `revisions` 表（11 列 + `PRIMARY KEY (branch, revision)` + 复合 FK 指向自身主键） | spec §3.2 逐字 | 文本块 `REVISIONS_DDL`，含 spec 原文的 `--` 列注释 |
| `idx_revisions_correlation (correlation_id, branch, revision)` | spec §3.2 | ✓ |
| `idx_revisions_parent (parent_branch, parent_revision)` | spec §3.2 | ✓ |
| `events` 表（6 列，`payload`/`correlation_id` `NOT NULL DEFAULT ''`） | spec §6.1 = agentlib 逐字（已拆 agentlib `SqliteEventStore.java:32` 原文比对，列名/类型/默认值全同） | ✓ |
| `idx_events_type_correlation_id_seq (type, correlation_id, seq)` | spec §6.1（agentlib 同款） | ✓ |
| `idx_events_correlation_id_seq (correlation_id, seq)` | spec §6.1 补（〇.3 第 3 条：agentlib 那条以 type 为前导列，服务不了"只按 correlationId 查"） | ✓ |

三条 PRAGMA 在打开时、任何事务之前设：`journal_mode = WAL`、`busy_timeout = 5000`、
`foreign_keys = ON`（用例 `theThreePragmasAreActuallyInEffect` 经 store 的私有连接读回
`wal` / `5000` / `1`）。关闭时 `PRAGMA wal_checkpoint(TRUNCATE)` 再 `Connection.close()`。

## 4. 实测命令与结论摘要

| 命令（均 `-pl simos-core -am` + `-Dsurefire.failIfNoSpecifiedTests=false`） | 结果 |
|---|---|
| `-Dtest='SqliteStoreTest' test` | `Tests run: 8, Failures: 0, Errors: 0`，`BUILD SUCCESS` |
| `verify`（关账轮，日志 `/tmp/task5-verify2.log`） | rc=0；util 159 / map 248 / social 30 / unit 68 / **core 23**（`SqliteStoreTest` 8 + `AgentLibAvailabilityTest` 15）＝ 全 reactor 528 条 0 失败 0 错误；`BugInstance size is 0`（含 core）；`grep -cE '^\[ERROR\]'` = **0**；`^\[WARNING\]` = **1**（`No files found to generate report on`，报告型插件的常态噪音，与 M3 关账同形） |
| 每轮变异后恢复 | 恢复原件 + md5 比对 + 干净世界重跑 8/8 绿（见 §5） |

## 5. 变异自证（五形态逐条走）

**落盘自证**：每轮变异体由 python 正则从原件派生到 /tmp，**断言 md5 与原件不同**后才安装到
规范路径 `SqliteStore.java`（白名单目标类名）；**每轮开跑/收口都恢复原件并 md5 比对**
（基线 md5 见表），恢复后重跑全绿才进下一轮；每轮日志 `grep -c "COMPILATION ERROR"` 均为 0。

| 轮 | 变异体（删什么） | 基线 md5 → 变异 md5 | 红的用例 | 红的那一行 / 机制 | COMPILATION ERROR |
|---|---|---|---|---|---|
| m1（计划要求） | `PRAGMA foreign_keys = ON` 那条 execute | `8bd2efd5` → `b70085b3` | `foreignKeysRejectAMissingParent`（"Expecting code to raise a throwable"：幽灵父行**插进去了**）+ `theThreePragmasAreActuallyInEffect`（expected "1" but was "0"） | 被保护行本身：FK PRAGMA 一删，外键成装饰 | 0 |
| m2（计划要求） | catch 里的 `rollbackQuietly();` | `8bd2efd5` → `2fc36d4c` | `notNullViolationLeavesNoResidue`（R13）+ `foreignKeysRejectAMissingParent`，均为 ERROR：残事务未回滚 ⇒ 后续事务 `BEGIN` 起不来，AfterEach 的 `close()` 也被 `SQLITE_LOCKED` 卡住 | 被保护行本身：没有回滚 ⇒ 残留打开的事务 | 0 |
| m3（自加） | `COMMIT_TXN` 的 execute | `8bd2efd5` → `383352d8` | `inTransactionCommitsAndReturnsTheWorkResult`（目标）+ 另 5 条连锁 | 被保护行本身：无 commit ⇒ 事务留在连接上，后续 BEGIN 撞 `cannot start a transaction within a transaction` | 0 |
| m4（自加） | `PRAGMA wal_checkpoint(TRUNCATE)` 的 execute | `8bd2efd5` → `4c3cde11` | **存活（8/8 绿）**——如实存档：SQLite 在最后一次连接干净关闭时自己也会清 `-wal`，"close 后文件不存在"的断言对这一行**无判别力**（见 §7 未核实 1） | — | 0 |
| m5（自加） | `inTransaction` 的 `synchronized (lock)` → 裸块 | `3af2c71e` → `cf6ccfd6` | `concurrentTransactionsSerializeOnThePrivateLock`（"Expecting value to be false but was true"：乙在 200ms 窗口内完成了） | 被保护行本身：私有锁一删，单连接上两事务交叠 | 0 |
| m1b（复证） | 同 m1，在签名收窄后的**新基线**复跑 | `3af2c71e` → `5902f718` | 同 m1 的 2 红 | 同 m1 | 0 |

存活项：仅 m4 一条（如实存档，不粉饰）。三轮计划内/外变异全灭。

## 6. 取代说明（计划/spec 与本轮实测冲突之处）

1. ★ **C23 / spec §6.2 / 计划 Step 2 的事务写法在 sqlite-jdbc 3.53.4.0 上不成立**（当场探针，/tmp，
   不进仓库）：
   - 探针 1（`setAutoCommit(false)` 后执行 `BEGIN IMMEDIATE`）→
     `[SQLITE_ERROR] (cannot start a transaction within a transaction)`——该驱动在
     autoCommit=false 时**由驱动自己开事务**（字节码可见 `DB.execute(sql, autoCommit)` 传标 + 
     `JDBC3Connection.tryEnforceTransactionMode()`）；
   - 探针 2（保持 autoCommit=true）→ 显式 `BEGIN IMMEDIATE` 可行；`conn.commit()` /
     `conn.rollback()` 抛 `database in auto-commit mode`，显式 SQL `COMMIT` / `ROLLBACK` 可行。
   - **落地**：保持 autoCommit 出厂值不动，用显式 SQL `BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK`
     驱动事务边界。C23 的语义（单事务、写锁前置、显式边界、异常即回滚）**一样不少**，
     变的只是 JDBC 层的手段。类 Javadoc 里写明。**这一条建议控制器回填 spec §6.2 与台账。**
2. **`SqlFunction<T>` 的落点**：计划只给了名字，Task 5 的 `Files:` 只允许两个文件
   ⇒ 落成 `SqliteStore` 的**嵌套公开函数式接口**（`SqliteStore.SqlFunction<T>`）。
   签名收窄为 `throws SQLException`（`throws Exception` 被 SpotBugs fb-contrib
   `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION` 在 threshold=Low 下打掉，首跑 verify 实测）；
   非受检异常照旧由 `inTransaction` 的 `catch (Exception)` 收口，回滚语义不变。
   Task 6~8（Timeline/CheckpointStore/Replay）将以 `SqliteStore.SqlFunction` 消费它。
3. **计划"四张 CREATE"的计数口径**：实为 4 组 6 条 DDL（2 表 + 4 索引），与 spec 一致，照 spec 落。
4. **DDL 文本块保留了 spec 印出的 `--` SQL 注释**：spec 是 schema 权威；与 agentlib"一字不差"
   的比对按**语句**口径（SQL 注释不改变语句），拆 agentlib 原文逐列核对过。
5. 计划草图之外的少量**加固**（AutoCloseable 契约所需，各 1~4 行）：`close()` 幂等（`closed` 标志）、
   关闭后 `inTransaction` 抛 `IllegalStateException`、open 失败路径 `closeSilently` 回收半初始化连接
   （agentlib 同款注释里自陈的"checkpoint 一抛就跳过 close"缺陷没有照抄）。
   每条都有用例（`closeIsIdempotentAndStoreRejectsUseAfterClose`）。

## 7. 「我未能核实的」清单（诚实列，不凑数）

1. **`PRAGMA wal_checkpoint(TRUNCATE)` 无判别力用例（m4 存档）**：连接私有 ⇒ 无法在 close 前
   从外部持第二个连接观察 `-wal`。该行仍按 spec §6.1 保留；若控制器要求判别力，
   需要在 store 上开测试专用的观察口（新 API ⇒ 需裁决，未擅自加）。
2. **`busy_timeout = 5000` 只验了 PRAGMA 读数**：没有"另一外部写者持锁、本连接 5s 内重试成功"
   的真实场景（M4 单 store、无外部写者，C23 下这是冗余方向的安全）。
3. **`journal_mode = WAL` 的持久性未跨重开断言**：WAL 写进库文件头理论上持久，但 reopen 用例
   只断言了"不抛 + `-wal` 清理"，没断言第二次打开后 `journal_mode` 仍是 `wal`。
4. **闩锁互斥用例的 200ms 负断言是调度相关的**：结构上只可能"假绿"（乙还没被调度），
   不可能"假红"；m5 证明它真能杀掉删锁变异体，但窗口长度（200ms）是经验值。
5. **驱动的内部事务簿记**：autoCommit=true + 手动 BEGIN/COMMIT 组合下，只实测了本项目用到的
   序列（BEGIN→work→COMMIT / ROLLBACK、事务内约束失败后再 ROLLBACK、残事务再 BEGIN 报错），
   未穷尽 SAVEPOINT / 嵌套 `inTransaction` 等未使用形态。
6. **探针 2 的 FK 报错文本**：探针库的 child 表引用了非唯一列，报的是 `foreign key mismatch`
   （只证明强制开启，不是标准文本）；规范文本 `[SQLITE_CONSTRAINT_FOREIGNKEY] (FOREIGN KEY
   constraint failed)` 来自**真实 revisions 表上的 R2 用例**，两者出处不同，未混写。
7. **关账 verify 是增量构建**（非 `clean verify`）；`./mvnw clean verify` 全仓绿归 Task 17。
8. **并行边界**：本树未观察 B2/C2 的进度；`Envelope`/`EventStore` 缺位对本任务无影响
   （零依赖已实测成立），但"两批产物 merge 无冲突"要等两批都落地才能核。

## 8. 给下游（Task 6~8，C1 同批）的接口快照

- `SqliteStore.open(Path dbFile)`：静态工厂，建目录 + PRAGMA + DDL；重复打开同一文件幂等。
- `<T> T inTransaction(SqliteStore.SqlFunction<T> work)`：唯一读写入口；`SqlFunction<T>.apply(Connection)`
  抛 `SQLException`；返回值原样透传；异常统一包 `IllegalStateException("事务失败，已回滚", 原异常)`。
- `close()`：幂等；`wal_checkpoint(TRUNCATE)` + `connection.close()`；关闭后再用抛 ISE。
- 库文件名（`timeline.db` 之类）**不在本任务定**——归装配门面（Task 13 `CoreConfig`/`CoreSimos`），
  本任务的 API 只收 `Path`。
