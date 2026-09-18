# Task 13 报告：`CoreSimos` 装配门面（2026-09-18）

**执行方式**：**派发 agent 在隔离 worktree** 执行（本机 `nproc` 实为 8，与本项目其它机器的 `nproc=2` 不同；
仍然串行跑 Maven，不并发）。worktree `/home/cna/SimulatorMosire/.claude/worktrees/b13`，分支 `m4/b13`，
基线 `d9f7be6`（preflight 实测与该串逐字节一致）。**未推送、未碰主树、未改 `progress.md`。**

**任务范围**（计划 Task 13 + M4 spec §1.2 / C19 / C24 / C28 + 裁定 32/34/46）：`CoreConfig` + `CoreSimos`
装配门面 + Post-commit checkpoint（C24）+ R8 前半（分岔机制面）+ 封存规则 + `CoreSimosTest` + 变异自证。

---

## 1. 交付物

| 文件 | 态 | 行数 | 出货 md5 |
|---|---|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/CoreConfig.java` | 新 | 40 | `a265608bcd654df6e8daf89f1b7f383f` |
| `simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java` | 新 | 279 | `5477e4c25655dbf6abc368513fc5208e` |
| `simos-core/src/test/java/io/mosire/simos/core/CoreSimosTest.java` | 新 | 519 | `e45fecd2342d9981ff0a365ff3677dbf`（**5 条**） |
| `.superpowers/sdd/2026-09-18-core-simos-plan/task-13-evidence/` | 新 | — | 变异装置 + 5 轮日志 + 全量绿轮日志 + 原件快照 |
| `.superpowers/sdd/2026-09-18-core-simos-plan/task-13-report.md` | 新 | 本文件 | — |

★ **只新增两个 main 文件**——**未动**任何既有 main 源（`CommandBus` / `Timeline` / `Replay` /
`CheckpointStore` / `CheckpointEncoder` / `TimeAdvance` / `CommandRegistry` / `SqliteStore` / `Envelope` /
`EventTypes`）。`CoreSimosTest` 的 5 条全是新增。

---

## 2. 装配形状对账（计划 Step 1 → `CoreSimos.java`）

| 组件 | 装配来源 | 落点 | 实测要点 |
|---|---|---|---|
| `SqliteStore` | Task 5 | 构造器 | `<storeDir>/simos.db`（本任务选的文件名，常量 `DB_FILE_NAME`） |
| `Timeline` | Task 6 | 构造器 | 周期 N 取自 `CoreConfig.checkpointInterval()` |
| `CheckpointStore` | Task 7 | 构造器 | `<storeDir>/checkpoints/` |
| `Replay` | Task 8 | **封存时** | 收全部 codec |
| `CommandRegistry` | Task 10 | **封存时** | 构造期不可变 ⇒ 必须先收集完 handler |
| `TimeAdvance` | Task 12 | **封存时** | 真 route；`stateLoader = this::load` |
| `CommandBus` | Task 9/10/11 | **封存时** | 真 route + 真 `StateLoader` |

★ **封存（seal）规则**：三类 `register(...)` 收集进可变表；第一次 `submit` / `replay` 时**一次性**建成全部组件并
封存；**封存后再注册一律抛 `IllegalStateException`**（绝不静默忽略——静默会让一次装配错误表现为"这个 handler 永远
不生效"且不报错）。封存用 `synchronized` 只做一次；`replay` / `bus` 字段 `volatile`，读侧不必进锁。

★ **Post-commit 只做 Core 自己的动作**（C24）：按 C19 写 checkpoint，**无模块钩子**。三种命令的分工：

| 命令 | checkpoint 行为 |
|---|---|
| `ForkBranch` | 提交后写**分岔点** `(source, expectedRevision)`（C19 第②项） |
| `CommandEnvelope` | 提交后写新坐标，**仅当** `Timeline.hasCheckpoint(新坐标)` 为真（r % N == 0） |
| `AdvanceTime` | **不写**——真 `TimeAdvance` 的 ⑥ 已写过（Task 12），本类不重复 |

★ **绝不写比 C19 谓词更多的文件**：`maybeWriteCheckpoint` 先问 `hasCheckpoint`。多写一份不会更正确，却会让 R3 的
"判定与磁盘逐条一致"在**反方向**破（磁盘有、判定说没有）。写失败一律 `LOG.warn`、**不上抛**（C18 / §3.4 ④）。

---

## 3. 判据落点（`CoreSimosTest`，5 条）

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `envelopeBranchCommitsAndReplaysThroughTheRealWiring` | ★ **信封支端到端 + 真 Replay 装配**：真 store + 真 `CommandBus` 写下的 revision 被真 `Replay` 逐字段重建；且 `(main,2)` **不写** checkpoint（C19 谓词为假） |
| 2 | `envelopeBranchWritesACheckpointWhenTheIntervalHits` | 信封支**正向**：N=2，`(main,2)` 命中 ⇒ 写档；**删创世档后仍能重放** ⇒ 读的正是它写出的档 |
| 3 | `advanceTimeWritesACheckpointThatReplayReadsBack` | 推进支：N=4，`(main,4)` 命中 ⇒ `TimeAdvance` ⑥ 写档；删创世档后仍能重放；再删 `(main,4)` ⇒ 必须抛（C18 回退到头） |
| 4 | `forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint` | **R8 前半**：`(b2,1)` parent 指回 `(main,2)`、空变更集、tick 继承父；★ **分岔点 checkpoint 文件存在**（m1 咬点）；两侧各推进一格、各用过期期望值 ⇒ 各自 `Conflict` 报各自真 head |
| 5 | `registeringAfterTheFirstUseThrows` | **封存护栏**：首次使用后三类 `register(...)` 一律抛（m3 咬点） |

★★ **本任务偿还两条挂了很久的欠账**（台账"给下游的硬接缝"）：
① `Replay` **首次**在真由 `CommandBus` 写下的 revision 上跑（Task 8 §5①）；
② `StateLoader` 的真实装配**首次**在真状态上跑（Task 9 §5"`replay::replay` 从未在真状态上跑过"）。
两条都通过用例 1 的端到端路径兑现。

★ `CoreSimosTest` 的夹具与 `ReplayTest` 同法（**独立**打开 `SqliteStore` 种创世行 + 写创世 checkpoint，关掉，
**再**构造 `CoreSimos`）。**未给 `CoreSimos` 加"创世 API"**——计划没要求，也没有消费者。

---

## 4. 实测结论行（照抄日志，非转述）

**最终全量绿轮**（`task-13-evidence/logs/full-verify.log`，mtime `2026-09-19 01:21:10`，rc=0）：

```
Tests run: 170, Failures: 0, Errors: 0, Skipped: 0     （UtilSimos）
Tests run: 255, Failures: 0, Errors: 0, Skipped: 0     （MapSimos）
Tests run:  37, Failures: 0, Errors: 0, Skipped: 0     （SocialSimos）
Tests run:  93, Failures: 0, Errors: 0, Skipped: 0     （UnitSimos）
Tests run: 142, Failures: 0, Errors: 0, Skipped: 0     （CoreSimos）
BugInstance size is 0        ×5（五个 jar 模块各一次）
[ERROR] 行数：0
BUILD SUCCESS；6/6 模块 SUCCESS
```

⇒ 合计 **697** 条。★ 与 Task 12 绿轮（692 ＝ 170/255/37/93/**137**）**逐模块对差**：前四个模块一字未动，
core 137 → 142 **恰 +5 ＝ 本任务新增的 `CoreSimosTest`（5 条）**。增量可归因，不是"总数变大"。

---

## 5. 变异自证（逐轮，日志 `task-13-evidence/logs/t13-mN.log`）

装置：`task-13-evidence/mutants/mut-round.sh`（Task 12 版逐字沿用其九道门禁 + 自指补记），**两处本机化改动**：
① **不 `export JAVA_HOME`**（本机无 `$HOME/.local/opt/jdk-21`，那行会让 `mvnw` 直接起不来；plain `./mvnw` 即可）；
② **加一道报告 mtime 门禁**（CLAUDE.md 形态 1 第五例）：开跑前删目标报告，跑完断言报告存在且 mtime 落在本轮内。

**5 轮，5 个变异体全部被杀**；每轮实测 `COMPILATION_ERROR_lines=0`、`Tests run_lines≥1`、报告 mtime 落在本轮内、
工作树逐字节还原。逐轮自证项（`orig_md5` / `mutant_md5` / 推送后 md5）已追加进各日志本身。

| 变异 | 打的位置 | 变异体 md5 | 实测红在哪（用例:行） |
|---|---|---|---|
| m1 | `CoreSimos`：删掉 `ForkBranch` 的分岔点 checkpoint 写入 | `7b2efa2f…` | `forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint:375`（分岔点 checkpoint 断言） |
| m2 | `Timeline.fork`：新分支 revision 从 `1` 改 `0` | `c5e57bcd…` | 同用例 `:346`——`core.replay((b2,1))` 抛 `IllegalArgument: b2@1 不在时间线上`（★ 见 §7-D4） |
| m3 | `CoreSimos`：`requireNotSealed` 改成静默 no-op | `f6788e93…` | `registeringAfterTheFirstUseThrows:403` |
| m4 | `CoreSimos`：删掉 `maybeWriteCheckpoint` 的 `hasCheckpoint` 守卫（多写文件） | `908d6022…` | `envelopeBranchCommitsAndReplaysThroughTheRealWiring:217`（不该有 `(main,2)` 文件） |
| m5 | `CoreSimos`：信封支命中 C19 却**不写** checkpoint（正向行为被删） | `231e8880…` | `envelopeBranchWritesACheckpointWhenTheIntervalHits:238` |

★ **原件快照**（还原源）：`CoreSimos` = `5477e4c2…`、`Timeline` = `d0f7bf38…`；五轮跑完后工作树两文件 md5
与快照**逐字节一致**（装置门禁 7 + 事后 `md5sum` 双核）。

★ **m2 的红不是字面断言，而更早**：`forkStartsAtRevisionOneAndItsForkPointGetsACheckpoint` 里第一处触到
`(b2,1)` 的是 `b2Before = core.replay(ref("b2",1))`（`:346`），它先抛了，所以 `forkResult == Committed((b2,1))`
那条字面断言没轮到执行。**红的理由仍是被保护的行为本身**（新分支必须从 revision 1 起），如实记下这一形态。

---

## 6. 本任务抓到并修掉的东西

### 6.1 ★★ 门禁抓到的真缺陷：SpotBugs 3 条（`EI_EXPOSE_REP`×2 + `IS2_INCONSISTENT_SYNC`×1）

写完两份 main 后第一次 `clean verify` **rc=1**：`simos-core` 报 **3 条**（其余 4 模块 0；用例本身全绿）：

```
Medium: CoreConfig.mapper() may expose internal representation by returning CoreConfig.mapper  EI_EXPOSE_REP
Medium: new CoreConfig(Path,int,ObjectMapper) may expose internal representation by storing an externally
        mutable object into CoreConfig.mapper                                                  EI_EXPOSE_REP2
Low:    Inconsistent synchronization of CoreSimos.bus; locked 50% of time                     IS2_INCONSISTENT_SYNC
```

- **前两条**：`ObjectMapper` **是 SpotBugs 眼里的可变对象**（此前只有集合/Date/数组的实例，这是新形态）。
  修法：`CoreConfig` 的紧凑构造器里 `mapper = mapper.copy()`（存自己的副本）、显式覆写访问器返回 `mapper.copy()`
  （不返回字段）。**因该组件当前无消费者，拷贝零语义代价**；这是**防御性拷贝**，不是 `@SuppressFBWarnings`
  （本仓无该注解依赖，且不许改 pom）。
- **第三条**：封存写在 `synchronized sealIfNeeded()` 里，而 `bus` / `replay` 在锁外读 ⇒ 半加锁。修法：两字段加
  `volatile`（volatile 本身就是"读侧不必加锁"的声明）。

★ 与 Task 7 同形：**欠账门禁补跑才抓到真 defect**——若只跑 `-Dtest=CoreSimosTest`（不跑 SpotBugs），这三条会一路
潜伏到关账。**这正是"要跑门禁就跑 `verify`"的价值。**

### 6.2 ★ 计划/台账草图的编译期错误：`replay::replay` 传不进去

计划与台账写的装配草图为 `new TimeAdvance(timeline, replay::replay, …)` / `new CommandBus(…, replay::replay)`，
但 `Replay.replay(StateRef)` 返回 **`Replay.ReplayResult`**（状态 + 步数），而 `StateLoader` 要的是
**`SimulationState`** ⇒ **方法引用类型不匹配，编译不过**。落地为 `this::load`（`replay.replay(ref).state()`），
语义与"传 `replay::replay`"完全一致。⇒ 取代说明 **D1**。

### 6.3 字节改了两次 ⇒ 变异轮**重跑**，并在补测后**扩到 5 轮**

- 第一次写完后跑了 m1~m4（4 轮全杀），随后 **6.1 的 SpotBugs 修法改了 `CoreSimos` 的字节** ⇒ 更新 `orig/`
  快照、重新生成变异体、**重跑 m1~m4**。
- 随后发现**正向缺口**（只验了"信封支不写"，没验"命中时会写"）⇒ 补了用例 5 与变异 **m5**，**5 轮全部重跑**。
- **旧证据的对象已被改掉，就不能给新字节背书**——这是本项目"同一文件被改 ⇒ 重跑"通则的又一次执行。

---

## 7. 计划偏离与取代说明（候选，待控制器裁定）

| # | 偏离 | 理由 |
|---|---|---|
| **D1** | 装配由计划草图的 `replay::replay` 改为 `this::load`（`replay.replay(ref).state()`） | 草图**编译不过**（§6.2）；`StateLoader` 要状态，`Replay.replay` 给 `ReplayResult` |
| **D2** | `CoreConfig.mapper` 加**防御性拷贝**（构造器存副本 + 覆写访问器返回副本） | SpotBugs `EI_EXPOSE_REP`×2 硬门禁（§6.1）；该组件**无消费者**，拷贝零语义代价。**计划未指定拷贝** |
| **D3** | `register(...)` 返回 `CoreSimos`（链式）；`CoreSimos implements AutoCloseable` | 计划 Produces 行只列方法与参数，**未指定返回类型/接口**；两者都不改语义 |
| **D4** | 计划 m1/m2 的"期望红在哪"写的是 **Task 14 的 R8 / Task 8 的 R5**；本任务**无 Task 14** ⇒ 红点落在本任务新增的 `CoreSimosTest` 断言上 | 本任务范围只到 R8 **前半（机制面）**，端到端归 Task 14；这是**范围收敛**不是设计改变 |
| **D5** | 库文件名自选为 `<storeDir>/simos.db` | 计划未指定；已在 `CoreSimos.DB_FILE_NAME` 文档化，夹具种同一个文件 |
| **D6** | `ForkBranch` 的 checkpoint 走**统一的** `maybeWriteCheckpoint`（带 C19 谓词），而非无条件写 | 分岔后 `hasCheckpoint(分岔点)` 必为真（C19 第②项），两者**等价**；统一路径让"绝不超谓词"只有一处实现 |

★ **未解决的既有偏离（本任务按裁定保持原状）**：`changeset_json` 的不透明文本偏离（台账**裁定 39**）——本任务
**不改**，交 Task 16 收口。

---

## 8. 我未能核实的（不许当结论引用）

1. **封存的真并发没测**。所有用例都是单线程。`synchronized sealIfNeeded()` + 两字段 `volatile` 是选型；**两个真
   线程同时首次 `submit`/`replay` 会怎样，没验。**（本任务契约不含并发封存，与 Task 15 的并发范围相接。）
2. **`CoreConfig.mapper()` 的拷贝语义没有消费者**，故"返回副本无害"只能说**无消费者因而无影响**，不等于验证过它
   在真实装配里的行为。
3. **真 codec / handler / participant 的装配未覆盖**（全是 `ToyCodec`/`ToyHandler`/`ToyParticipant`）。
   `UnitTimeParticipant` + `RenameUnitHandler` 的真装配归 **Task 16 Step 3**（裁定 20）；R6/判据四在**装配层**
   是否成立，本任务没验。
4. **`ForkBranch` 支仍然不发事件**（已知缺口，out of scope）——本任务**未碰** `CommandBus`，判据二现在仍覆盖不到分岔。
5. **`changeset_json` 的不透明文本偏离（裁定 39）仍在**（out of scope）。
6. **checkpoint 写失败只 WARN 的路径没有故意违规用例**。m1 打的是"不写"，m4/m5 打的是谓词守卫；**"写盘抛
   `RuntimeException` ⇒ WARN 且提交仍成功"这条路径没有专门用例**（靠 `maybeWriteCheckpoint` 的 `catch` 结构 +
   既有对 `CheckpointStore.write` 的 I/O 语义保证，**不是**本任务的故意违规自证）。
7. **`close()` 的幂等与"关闭后再用"没有用例**——只依赖 `SqliteStore.close()` 自身的幂等性（其用例在 Task 5）。
8. **`CoreSimos.replay` 不暴露 `applyCount`** ⇒ **R5 的步数上界在装配层没有直接断言**（`ReplayTest` 验的是
   `Replay` 本身）。装配层只验了"重放结果对"。
9. **跨进程 / 多连接并发未测**：本任务全程单连接；`SqliteStore` 的多连接行为不在本任务范围。
10. **m2 的"红的形态"**如实记（§5 末）：红点比字面断言更早，落在同一条 `(b2,1)` 行为上。

---

## 9. 证据清单（`task-13-evidence/`）

```
logs/full-verify.log                 ★ 最终全量绿轮（2026-09-19 01:21:10，rc=0，697 条）
logs/run-all-rounds.out              ★ 5 轮总控输出（5×杀死 / 0 存活）
logs/t13-m1…m5.log                   ★ 五轮变异，每份自带"装置补记"的 md5 自指段
mutants/mut-round.sh                 变异装置（九道门禁 + 自指补记 + 报告 mtime 门禁；本机化：不 export JAVA_HOME）
mutants/gen-mutants.py               五个变异体的生成器（锚点逐条断言命中恰好一次）
mutants/run-all-rounds.sh            五轮总控
mutants/orig/CoreSimos.java          ★ 出货字节快照（5477e4c2…）
mutants/orig/Timeline.java           ★ 出货字节快照（d0f7bf38…）
mutants/t13-m1…m5.<目标类>.java       五个变异体
```

★ 工作树两 main 文件在五轮跑完后**逐字节还原**（md5 与 `orig/` 快照一致）。
★ **未提交**：本任务按要求止于本 commit，**不 push**；`progress.md` 由控制器在关账时更新。
