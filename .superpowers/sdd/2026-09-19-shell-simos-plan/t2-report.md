# M5 T2 报告：core 只读扩展（spec §S8）

> worktree `.claude/worktrees/m5t2`（分支 `m5/t2`，基线 `be1dad6`）；`JAVA_HOME` 空、普通 `./mvnw`（Java 21）。
> 执行者：子代理（T2）。日期：2026-09-19。

## 1. 交付物

| 文件 | 变更 |
|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java` | **新增** `public Set<BranchId> branches()`、`public Optional<RevisionId> head(BranchId branch)`；javadoc 明写「只读、零写面，唯一写入口仍是 `submit`」。**未触碰** `sealIfNeeded` / `submit` / `replay` / `close` 的任何一行 |
| `simos-core/src/test/java/io/mosire/simos/core/CoreSimosTest.java` | **新增 2 条用例** + 3 个夹具（`revisionRowCount` / `independentBranches` / `independentHead`） |
| `.superpowers/sdd/2026-09-19-shell-simos-plan/t2-evidence/**` | 绿轮日志 + 变异轮日志（含自指 md5）+ full-verify 日志 + 变异体/原件快照 |
| 本报告 | `.superpowers/sdd/2026-09-19-shell-simos-plan/t2-report.md` |

`branches()` / `head()` 都是**一行委托**到构造期即存在的 `timeline` 字段：

- 数据在 `SqliteStore` 那条连接上，**与 seal 状态无关** ⇒ 封存前后都可调用（`branchesAndHeadWorkBeforeSealAndAfterSeal` 直证）。
- 两方法**不调用** `sealIfNeeded()` ⇒ 读不会触发封存（用例在未封存时先读、随后 `register` 仍成功，直证）。
- `head(branch)` 对 `branch` 做 `Objects.requireNonNull(branch, "branch")`（与 `submit`/`replay` 同风格），其余原样透传 `Timeline.head`。

## 2. 测试与门禁实测

### 2.1 定向测试（`-Dtest='CoreSimosTest'`）

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  -- io.mosire.simos.core.CoreSimosTest
```

原有 5 条 + 新增 2 条 = **7**。新增：

- `branchesAndHeadDelegateToTimelineWithoutWriting`：走门面建创世 + 信封提交到 `(main,2)` + `ForkBranch` 出 `b2` ⇒ `branches()` 含 `main`/`b2`；`head(b2) = (b2,1)`、`head(main) = (main,2)`、未知分支 ⇒ 空；并与**关库后独立 `Timeline`** 对拍逐字段相等；**读调用前后 `revisions` 行数**（3）不变。
- `branchesAndHeadWorkBeforeSealAndAfterSeal`：未封存态先读（`branches()`/`head(main)`），再 `register` 仍成功（证明读未封存）；`submit` 封存后再读，`head(main)` 随新提交变为 `(main,2)`。

### 2.2 全量 `./mvnw clean verify`

```
rc=0；6/6 模块 SUCCESS；BUILD SUCCESS
Tests run: 170 / 255 / 37 / 93 / 147   （util / map / social / unit / core）
合计 702
BugInstance size is 0   ×5
^\[ERROR\] 行数 = 0
```

**对差**（基线 700 = 170/255/37/93/145）：前四模块一字未改；**core 145 → 147，恰 +2 = 本任务两个新用例**。日志 `t2-evidence/logs/full-verify.log`。

### 2.3 `spotless:apply`

`./mvnw -q spotless:apply` rc=0（格式化后行宽由 google-java-format 决定，未手工调宽）。

## 3. 变异自证（1 轮）

- **变异体** `t2-m1`：`Timeline.head(BranchId)` 忽略 `branch` 参数，改取**全局 `MAX(revision)`**（跨全部分支）。
- **日志**：`t2-evidence/logs/t2-m1.log`；装置 `t2-evidence/mutants/mut-round.sh`（九道门禁：干净世界 md5 / 字节不同 / 删陈旧 `.class` / `Tests run≥1` / `COMPILATION ERROR=0` / surefire 报告 mtime 落本轮 / rc≠0 / `cp` 还原核 md5 / 自指 md5 追加进日志）。
- **md5**：`orig=d0f7bf384c741d211c44f761e3879b05`、`mutant=1eed09065894f5a0e760d97d06a54406`、`worktree_restored=d0f7bf384c741d211c44f761e3879b05`（逐字节还原，`cp` 回原件，未用 `git checkout --`）。
- **结果：杀死**。`rc=1`、`COMPILATION ERROR` 0 行、`Tests run: 7, Failures: 2`。

**确切的变红断言**：

```
[ERROR]   CoreSimosTest.branchesAndHeadDelegateToTimelineWithoutWriting:439
          [b2 的 head 是它的 revision 1，不是全局最大 revision]
[ERROR]   CoreSimosTest.forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint:366
```

第一条正是本任务新增的 `head(b2)` 断言（判别力锚点）；第二条是既有分岔用例的连带变红（它同样断言 `b2` 的 head/冲突报告），说明变异确实改变了 `head` 的可观测行为而不只是编译噪声。

## 4. 偏离

1. **`head(BranchId)` 加了一行 null 守卫**（`Objects.requireNonNull(branch, "branch")`）。计划的 "只读委托" 未提守卫；这是与 `submit`/`replay` 一致的防御，对合法输入零行为差异。属**加强**、非语义偏离，记此备查。
2. **测试的独立对拍在门面仍持有连接时打开第二棵 store**（现有 `row()` 夹具是**关库后**才读）。本机 WAL 下实测可用且全部门禁绿；这是对既有夹具惯例的一处扩展。若未来并发写场景下第二连接读不稳定，改法是把对拍移到 `try` 块之后，但那样就拿不到"读前后行数"的同段对照。
3. 用例数取 2 条（brief 允许 2–4）。

## 5. 我未能核实的

- **并发下只读**：`branches()`/`head()` 与并发 `submit` 同跑的行为未测。读路径是 `SqliteStore.inTransaction` 的既有语义（每个事务经同一把私有锁串行），本任务不改它，也不在 T2 契约内。
- **store 已关闭后调用**：`close()` 之后 `branches()/head()` 会经 `SqliteStore.ensureOpen` 抛 `IllegalStateException`——**未加用例钉住**（外于本任务范围）。
- **第二个连接的读一致性**：§4.2 的对照在 WAL 单机同文件下实测可读且数字正确；这是**实测**，不是对 SQLite 并发语义的书面保证。
- **消费方端到端**：无 GUI/查询层消费者调用这两个方法的真路径（归 T3/T8）。本任务只证到门面层。

## 6. 证据索引

| 路径 | 内容 |
|---|---|
| `t2-evidence/logs/full-verify.log` | 全量 `clean verify` 绿轮（702 条、BugInstance 0×5、ERROR 0） |
| `t2-evidence/logs/t2-m1.log` | 变异轮：红断言 + 自指 md5 补记 |
| `t2-evidence/mutants/mut-round.sh` | 九道门禁装置（ROOT 指向 m5t2 worktree） |
| `t2-evidence/mutants/t2-m1.Timeline.java` | 变异体（`head` 忽略 branch，全局 MAX） |
| `t2-evidence/mutants/orig/Timeline.java` | 原件快照（`d0f7bf38…`，与工作树逐字节一致） |
