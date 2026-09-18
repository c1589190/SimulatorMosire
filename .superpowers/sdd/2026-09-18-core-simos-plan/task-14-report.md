# Task 14 报告：判据一——分岔端到端（2026-09-19）

**执行方式**：**派发 agent 在隔离 worktree** 执行。worktree `/home/cna/SimulatorMosire/.claude/worktrees/b14`，
分支 `m4/b14`，基线 `21f33ce`（preflight 实测与 brief 给的串逐字节一致）。**未推送、未碰主树、未改 `progress.md`、
未动任何既有 main/test 源、无 pom 变更、无 `mvn install`。**

**任务范围**（计划 Task 14 + spec §1.1 判据① / §3.4 分岔语义 / R8）：`BranchingEndToEndTest`（**真域命令**的
分岔端到端）+ m1/m2 变异自证 + 本报告。**test-only 任务**——`git status` 实测只有 1 个新 test 文件与 1 个新 evidence 目录。

---

## 1. 交付物

| 文件 | 态 | 行数 | 出货 md5 |
|---|---|---|---|
| `simos-core/src/test/java/io/mosire/simos/core/BranchingEndToEndTest.java` | 新 | 318 | `13997cfd4c90ac973d361bc8379f44fb`（**1 条**用例） |
| `.superpowers/sdd/2026-09-18-core-simos-plan/task-14-evidence/` | 新 | — | 变异装置 + 2 轮日志 + 全量绿轮日志 + 原件快照 |
| `.superpowers/sdd/2026-09-18-core-simos-plan/task-14-report.md` | 新 | 本文件 | — |

★ **零 main 源改动**：`git status --short` 只有上面两个 untracked 条目。`Timeline.java` / `Replay.java` 在变异轮
跑完后经 `md5sum` 实测**逐字节还原**（`d0f7bf…` / `146fd3…`，与 `orig/` 快照相同）。

---

## 2. 判据一落点（`BranchingEndToEndTest`，1 条用例）

夹具：创世 `(main,1)` 的 state 里只有 `unit` 切片，含单位 `u-1`（"第一连"）；用**真** `UnitCodec` 写创世 checkpoint，
再构造 `CoreSimos`（N=4）并注册**真** `UnitCodec` + **真** `RenameUnitHandler`（Task 16 Step 1/2 已落地 ⇒
**无替身、无取代说明**）。

场景：`main` 先改一次名 → 从 `(main,2)` 分岔 `b2` → 两侧各推一次真改名（不同新名）→ 捕获 `b2` head + 状态 →
回到 `main` 再推一次。断言三件事 + 一条端到端口径：

| # | 断言 | 形态 |
|---|---|---|
| ① | 两侧各自推进互不影响 | 四个 `CommandResult.Committed` 逐坐标 + 两侧重放出的单位名 `isEqualTo` 各自的值，且两者 `isNotEqualTo` 对方（防"恒真"对照） |
| ② | `b2` 的父链指回 `(main,2)` | **行级**：`(b2,1)` 的 `parent` `contains((main,2))`、`revision==1`、`commandType==core.ForkBranch`、tick 继承父、空变更集；`chainToGenesis((b2,2))` `containsExactly` `[(b2,2),(b2,1),(main,2),(main,1)]` |
| ③ | 回到 `main` 推进，`b2` 的 head 与状态**一字不变** | head revision `isEqualTo(b2HeadBefore)`；重放状态 `isEqualTo(b2StateBefore)`（完整 `equals` 快照）；再逐值钉 `ref`/`timestamp`/`id`/`name`/`member`/`equipment`/`speed`/`mobilityPerMille`/`movement`/`position` |
| e2e | 跨分支过期 `expectedRevision` ⇒ `Conflict` 报**该分支自己的**真 head | `Conflict((b2,2))`，在 `main` 最后一次推进**前后各断言一次** |

★ **判据一③ 的 head 断言写在 try 内、且在重放 `(b2,2)` 之前**——这是**为隔离 m1 的咬点**，不是风格：m1（`head` 忽略
分支）会让 `b2` 的改名当场冲突、`(b2,2)` 根本不存在；若先重放它，变异轮会以一次**异常**收场，红点就不落在被保护的那条
断言上（形态 1：红点必须落在被保护的那一行）。实测 m1 正是红在第 141 行的 head 断言（见 §4）。

★ **与 `CoreSimosTest` 的分工**（不重复它）：那个类验 R8 的**机制面**（分岔行字段 / 分岔点 checkpoint /
`expectedRevision` 隔开两侧），全替身 codec/handler。本类向上扩一层：真域命令 + 两侧真各推一格 + 逐值"一字不变"。
**未改** `CoreSimosTest` 或任何既有文件。

---

## 3. 实测结论行（照抄日志，非转述）

全量门禁 `./mvnw clean verify`（`task-14-evidence/logs/full-verify.log`，mtime 落本轮）：

```
rc=0；6/6 模块 SUCCESS
每模块用例：170 / 255 / 37 / 93 / 143（total 698）
BugInstance size is 0 命中 5 次（5 个模块各一次）
[ERROR] 行数 0
```

★ **逐模块对差基线 697（170/255/37/93/142）**：前四个模块**一字未动**，core 142→**143** 恰 **+1** ＝ 本任务的
`BranchingEndToEndTest`。与 Task 13 的 697 差为 +1，与"test-only 任务只该加自己用例数"一致。

目标轮（`logs/t14-m1.log` / `t14-m2.log`，surefire 报告 mtime 落在各自本轮内）：

| 轮 | `Tests run` 行 | `COMPILATION ERROR` | rc |
|---|---|---|---|
| m1 | 2 | 0 | 1 |
| m2 | 2 | 0 | 1 |

---

## 4. 变异自证（逐轮，日志 `task-14-evidence/logs/t14-mN.log`）

装置照搬 `task-13-evidence/mutants/mut-round.sh`（九道门禁逐字沿用：干净世界 md5、字节不同自证、白名单推成**目标
类名**、`COMPILATION ERROR`==0、`Tests run`≥1、surefire 报告 mtime 落本轮、红点落位、逐字节还原、**日志自指**
——装置补记把 `orig_md5`/`mutant_md5`/推送后 md5 追加进日志本身）。

| 轮 | 变异 | 做法 | 结果 | 红在哪（实测行） |
|---|---|---|---|---|
| **m1**（计划指定） | `Timeline.head` **忽略 `branch` 参数**，取全局 `MAX(revision)` | `orig=d0f7bf38… mutant=59d86c45…` | **杀死** | `BranchingEndToEndTest.forkEndToEndWithRealRenameCommands:141 [③ b2 在分岔后自己推进一格，head 必须是 2]` |
| **m2**（本任务新增逐值断言的变异体） | `Replay` 收集 path 后**不施加**变更集（只 `applyCount++`） | `orig=146fd336… mutant=dbc71179…` | **杀死** | `BranchingEndToEndTest.forkEndToEndWithRealRenameCommands:163 [① main 的重放状态只看见 main 自己的改名]` |

★ **m1 的"为什么红"**：`head` 忽略分支后，`b2` 的改名（expected 1）在 ① 入口检查处读到"全局 MAX"（此时是 `main` 的
2）⇒ 不相等 ⇒ `Conflict` ⇒ `(b2,2)` 不落。第 141 行的 head 断言随后读到"全局 MAX"（此时是 `main` 的 4）而非 2 ⇒ 红。
**红点正是被保护的那条**（判据一③），不是重放异常。

★ **m2 的"为什么红"**：`Replay` 跳过 `applyWorld` 后，`(main,4)` 的重放结果退回 checkpoint 时的名字（创世"第一连"），
而期望是"main 三改" ⇒ 第 163 行红。这条变异体打的是本任务**新增的逐值断言**（①），证明它不是"看它没报错"。

★ **m2 的来历（正向缺口自检）**：初版设计只打算跑计划指定的 m1。自问"判据一①/③ 的逐值断言有没有自己的变异体"——
答案是没有（m1 只打 ③ 的 head）。故补 m2 打 ① 的重放施加路径，否则那些 `isEqualTo("main 三改")` 可能是装饰。

★ **未新增护栏的说明**：本任务只新增一个测试类，其中每条断言都归入判据一①②③或端到端口径，**没有额外的独立护栏**
需要单配变异轮——m1/m2 已分别覆盖 head 与重放施加两条关键路径。

---

## 5. 计划偏离与取代说明（候选，待控制器裁定）

| # | 内容 | 定性 |
|---|---|---|
| D1 | 计划 Step 2 说"若 Task 16 未落地则用替身 handler 并记取代说明"——**Task 16 Step 1/2 已落地**（`ea a8093`）⇒ 直接用真 `RenameUnitHandler`，**无取代说明** | 无需偏离 |
| D2 | 夹具只种 `unit` 一个切片（不种 `map`）——`RenameUnitHandler` 只取 `unit` 切片，种 `map` 是无关依赖 | 实现细节 |
| D3 | 增加 m2（计划只指定 m1）——为①/③ 的逐值断言配变异体，符合"新增护栏必须自带变异轮"（裁定 42） | 范围**扩展**（自证需要） |
| D4 | 判据一③ 的 head 断言**放在 try 内、重放 `(b2,2)` 之前**——为让 m1 红在断言而非异常 | 实现细节（判别力所迫） |
| D5 | `b2StaleBefore`/`b2StaleAfter` 用真 `Conflict` 当端到端口径，但 **head 读取**另走独立 store 的 `Timeline.head`（不用 `Conflict` 当读 head 的途径——它会落事件行、污染被测物） | 实现细节 |

---

## 6. 我未能核实的（不许当结论引用）

1. **`ForkBranch` 支仍不发事件**（Task 11 的带裁定遗留条目）——本任务的端到端**只断言 revision 行与状态**，
   **没有**断言分岔的事件链（因为实现本就没有）。判据二现在仍覆盖不到分岔。
2. **`changeset_json` 的线格式仍是 `WorldChangeSet` 整体 JSON + `Id.CLASS`**（裁定 39/52）——本任务的真域命令走
   它落盘/读回，**实测能往返**，但"Core 内省了模块类型"这条偏离**依然成立**，归 Task 16 收口。
3. **并发下的分岔未验**——本任务全是单线程；"两个线程同时从同一 head 分岔"的行为（谁成功、谁冲突）未测，归 Task 15。
4. **`CoreSimos` 封存的真并发未验**（Task 13 §6 的接缝）——本任务不涉及。
5. **checkpoint 写失败 ⇒ WARN 的路径没有故意违规用例**（Task 13 §6 第 2 条）——本任务未碰它。
6. **跨 JVM 决定论未测**——`Replay` 的 checkpoint 模块表用 `TreeMap`（字典序），但"同一状态 ⇒ 同一份字节"的跨 JVM
   实测未做（M2 的哈希盐教训同族）。

---

## 7. 证据清单（`task-14-evidence/`）

| 路径 | 内容 |
|---|---|
| `logs/full-verify.log` | `./mvnw clean verify` 全量绿轮（rc=0、698 条、core 143） |
| `logs/run-all-rounds.txt` | 两轮变异的总输出（含每轮九道门禁的实测行） |
| `logs/t14-m1.log` | m1 轮（`head` 忽略分支）完整 Maven 日志 + 装置补记（三处 md5） |
| `logs/t14-m2.log` | m2 轮（`Replay` 不施加变更集）完整日志 + 装置补记 |
| `mutants/mut-round.sh` | 九道门禁装置（改写自 task-13 版，路径改指 task-14） |
| `mutants/run-all-rounds.sh` | 两轮驱动脚本 |
| `mutants/gen-mutants.py` | 变异体生成器（锚点逐条断言命中） |
| `mutants/t14-m1.Timeline.java` | m1 变异体 |
| `mutants/t14-m2.Replay.java` | m2 变异体 |
| `mutants/orig/Timeline.java` | 原件快照 `d0f7bf384c741d211c44f761e3879b05` |
| `mutants/orig/Replay.java` | 原件快照 `146fd336b606ea4efcdbbcaf1f429121` |
