# M4 Task 17 —— 关账报告（**M4 完成**）

日期：2026-09-19　主树：`feat/adr1-core-scope`　执行：控制器 + 子代理（`deepseek-flash-go`）
本报告是 **M4（CoreSimos）的完成关账**。★ 同目录的 `task-17-report.md` 是 **2026-09-18 08:00 的按期收口**（当时只完成 1~5），是历史档案，**未被本报告取代其事实**——它记的是那次时间盒的真实状态。

---

## 〇 完成度

**17/17**：Task 1~12（内联/子代理按台账）已于 2026-09-18 关账（见台账）；本会话（2026-09-19）完成并关账 **Task 13（装配门面）/ 14（判据一端到端）/ 15（判据三零改动复核）/ 16 Step 3（判据四端到端）/ 17（本报告）**。
本会话提交链：`4a8b945` → 合并 `d7148b4` → `21f33ce`（13 关账）→ `e17604a` → 合并 `a2d015a` → `33f1b5d`（14 关账）→ `da2178e`（15 关账）→ `f011650` → 合并 `96e17f3` → `cbf7352`（16.3 关账）→ 本关账提交。全部已推送，`origin/feat/adr1-core-scope` 与 HEAD 同步。

## 一 最终门禁（关账权威证据）

| 项 | 实测值 |
|---|---|
| 命令 / 工作树 | `./mvnw clean verify`（主树，`clean` 排除陈旧 `target/`）；`./mvnw -q spotless:apply` 先行（无 diff） |
| 返回码 / 结论 | **rc=0 / `BUILD SUCCESS`** |
| 用例 | **700 = 170 / 255 / 37 / 93 / 145**（util/map/social/unit/core），Failures 0 / Errors 0 / Skipped 0 |
| SpotBugs | `BugInstance size is 0` × **5**（五个 jar 模块全清） |
| `[ERROR]` 行 | **0** |
| 日志 | `task-17-evidence/completion-full-verify.log`（pom 补记后重跑；补记前那轮留档 `completion-full-verify-before-pom-fix.log`） |
| 四判据定向轮 | `task-17-evidence/criteria-green.log`（26 条：TimeAdvanceTest 16 + CorrelationChainTest 6 + RealmEffectEndToEndTest 2 + BranchingEndToEndTest 1 + OptimisticConcurrencyTest 1） |

## 二 四条判据逐条（实测值，不是"通过了"）

| # | 判据 | 用例 | 实测值（当场跑过） |
|---|---|---|---|
| ① | 时间线能分岔 | `BranchingEndToEndTest`（R8） | fork `(main,2)`→`(b2,1)`：parent=`(main,2)`、变更集空、tick 继承；两侧真 `RenameUnit` 各推一格（main→3、b2→2）；main 再推（→4）后 **b2 head 仍 = 2**、重放状态 `equals` 推进前快照（name/member/equipment/speed/mobility/position 逐值）；跨分支过期 expected ⇒ 各自 `Conflict` 真 head；父链 `[b2@2, b2@1, main@2, main@1]` |
| ② | `correlationId` 全链 | `CorrelationChainTest`（R6）+ `TimeAdvanceTest.realRouteProducesTheFrozenR6Sequence` | 信封支：**恰** `received + committed` 2 条 + `revisions` 恰 1 行；真 route：**恰** `received + started + 2×proposal（alpha、beta 字典序，注册序相反）+ finished + committed` 6 条 + `revisions` 恰 1 行；correlationId 逐字节（故意 ≠ commandId） |
| ③ | CONFLICT 有真实并发 | `OptimisticConcurrencyTest`（R7，K=50） | **50 轮**，每轮**恰 1 Committed / 恰 1 Conflict**；败者 `current` == 胜者新 ref；每轮 head = expected+1；自证：`barrierPasses == 100（2K）`、`maxSimultaneous ≥ 2`（m3 实测这两条是承重的：handler 挪进锁内 ⇒ 正确性断言全绿、只有自证红） |
| ④ | 推进有新 revision + 真实领域效果 | `RealmEffectEndToEndTest`（R16） | 真 `UnitTimeParticipant` 经 `CoreSimos`：`position [1,1]→[1,3]`、`movement` 清空（逐值 + 前提自证在**重放出的**创世态上）；重放 `(main,2)` == 独立重建期望（M3 纯函数 + §9.1 手工规则，不经被测参与者）；unit 切片带新 ref/timestamp、map 切片保留创世坐标；R10 事件：reads 恰 4（字典序）、writes 恰 1 |

## 三 关账抓到并修掉的一处 spec 未兑现（pom）

**发现**：spec §二 / 〇.3 第 8 条要求 `simos-core` 的 pom **显式声明** `jackson-databind` 与 `sqlite-jdbc`（理由原文："虽然 jackson 可经 `simos-util` 传递得来，但**依赖传递不是契约**"），而 `jackson-databind` **从未落盘**（`git log -S "jackson-databind" -- simos-core/pom.xml` 查无；`ce98212` 只加了 sqlite-jdbc / log4j；台账无任何相关裁定记录）。Core 的 main 源码确实**直接 import** `com.fasterxml.jackson.databind.*`（CoreConfig / CoreSimos / CommandBus 等）⇒ 现状只靠传递依赖。
**处置**（按纪律"修复比描述还短 ⇒ 当场修"）：补上显式声明（版本由父 POM `jackson-bom` 提供，enforcer 不受影响），顺手修正一行过时注释（"sqlite-jdbc 到需要时再加"——早已加）。**`clean verify` 重跑 rc=0（700 条）**。
★ 影响面：pom 声明变化**不改变 classpath**（jackson 本就在），故既有 .java 变异证据不受影响；门禁按纪律重跑并留档。

## 四 R1~R18 点验

**点验表**：`task-17-evidence/R1-R18-sweep.md`（子代理只读检索底稿 + 控制器补证与裁定）。结论汇总：

- **18 条 R 的实现用例全部在案**（逐条给了 `file:method`）。
- **自证等级分三档（不混同）**：
  - **有落盘变异日志的 11 条**：R4/R5/R6/R7/R8/R9/R10/R11/R12/R16/R17；
  - **当场补证 2 条（本轮新增）**：**R3、R14**——检索发现它们有用例却从未被变异轮咬过，据此当场造两个变异体 + 九道门禁轮：`r14`（未注册 namespace 放行）红在 `TimeAdvanceTest.proposalWithoutARegisteredCodecIsRejected:377`；`r3`（`CheckpointStore.write` 不落盘）红在 `CheckpointStoreTest.diskFilesMatchHasCheckpointRowByRowAcrossABatch:188`；两轮均 rc=1 / `COMPILATION_ERROR=0` / `Tests run=1` / 报告 mtime 落在轮内 / 逐字节还原（日志 `logs/r14.log`、`logs/r3.log`；还原后定向复跑 2/2 绿）；
  - **报告在案、日志未入库的 5 条**：R1/R2/R13/R15/R18（Task 1/2、4、5 **没有 `task-N-evidence/` 目录**，报告表里记了红点与消息，但没有可复核的 Maven 日志）——**裁定 56：接受为"报告在案"，不补跑**（补跑要重造已消失的变异体、动四个已关账任务的产物；如实标档，不假装与有日志者同级）。
- **明确记载的存活/等价变体**：与 R1~R18 直接相关的**无**；Task 5 自加轮 m4（`PRAGMA wal_checkpoint`）实测存活，但它不属于 R1~R18。
- **两条须随判据一起读的口径**：R5 的上界**条件成立**（依赖 C19 应有档都在；劣化形态会超 N，用例已断言）；R15 的扫描器在 **worktree 里曾恒绿**（裁定 23，`50921bc` 修复），**worktree 里的旧 R15 绿按空真处理**、主树结论才权威。
- ★ 装置首跑测到一处**假阴性并当场修掉**：surefire 报告按全限定名落盘，短名匹配读到 mtime=0 ⇒ 已把 FQN glob + mtime 纳入判定写进 `mut-round.sh`（"先怀疑自己的读取"）。

## 五 带裁定的遗留条目（跨任务汇总，M4 不做，如实存档）

1. **`changeset_json` 的不透明文本偏离（裁定 39 / 52）**：现为 `WorldChangeSet` 整体 JSON + `Id.CLASS`（含全限定类名，挪包即旧档不可读；Core 内省了模块类型——C26 原意被破）。修复牵动 Task 4/6/8/9/12 产物 ⇒ **M4 不裁决**，归后续（Task 16 收口时亦未动，见裁定 52）。
2. **`ForkBranch` 支不发事件**（Task 11 记，Task 13/14 复核仍缺）：判据二覆盖不到分岔。补它要给 `Timeline.fork` 加事件参数并重跑 Task 6/9/11 变异证据。
3. **并发**：封存真并发（Task 13）未测；跨分支并发（两线程 main/b2 各推各的）与并发分岔未测（Task 14/15 记）；本机 `nproc=2` ⇒ 真实并发上限 2 线程；K=50 与屏障 2s 无敏感性分析。
4. **checkpoint 写失败 ⇒ WARN 路径**无故意违规用例（Task 12/13 记）。
5. **R5 步数上界**在装配层无直接断言（`CoreSimos.replay` 不暴露 `applyCount`）；且其上界**条件成立**（依赖 C19 应有档都在，缺失会超 N——Task 8 已断言此劣化形态）。
6. **跨 JVM 决定论**未测（checkpoint 模块表 `TreeMap` 只证进程内定序；M2 哈希盐同族）。
7. **`CoreConfig.mapper` 无消费者**（计划组件，如实留；不造假消费点）。
8. **`InfoEntry.value` 是裸 `Object`**：结构化值不满足 `equals` 往返（裁定 38 范围止于键）。
9. **`GameMap` 无 id**（M2/M3 挂起项延续）：`map:<mapId>` 的 mapId 只回显不可校验。
10. **unit 其余 7 条命令 / IN_TRANSIT 端到端分支 / 多单位提案并集**未做（U15 乙的范围明界）。

## 六 我未能核实的（不许当结论引用）

1. **四条判据的"端到端"经真 codec 的部分**：① 用真 `RenameUnitHandler`+`UnitCodec`；④ 用真 `UnitTimeParticipant`+`MapCodec`+`UnitCodec`；**②的真 route 轮仍是替身 codec/participant**（真域 e2e 归 ④ 的测试），③的 handler 是替身（并发验的是总线锁，非域逻辑）。
2. **R1/R15 那类仓源扫描器的环境形态**：主树与 worktree 均跑过（Task 13~16 的 worktree 全量绿轮），但**"从模块目录起跑"**这一形态未见实测记录。
3. **1 万级/更大规模的性能与容量**：全程小规模夹具，无基准。
4. **`OptimisticConcurrencyTest` 高负载下 2s 屏障超时是否假红**：未在高负载下测过。
5. **checkpoint 的目录/磁盘故障注入**：只测过"文件缺失回退"，没测过权限/磁盘满等写失败形态（WARN 路径无自证，见 §五.4）。
6. **子代理执行的任务（13/14/16.3）的变异轮可复现性**：日志自指与 md5 已逐轮在案（本次已抽查体例），但**未由控制器逐轮重放**（按纪律："实现者自带的变异自证已经是测试，不要在外面再套一层"）。
7. **R1/R2/R13/R15/R18 的证据等级**：只有报告表、没有入库的 Maven 日志（Task 1/2、4、5 无 `task-N-evidence/`；裁定 56 接受、不补跑）⇒ 这五条**弱于**有日志的 11 条 + 本轮补证的 2 条，引用时须知。

## 七 证据清单

| 路径 | 内容 |
|---|---|
| `task-17-evidence/completion-full-verify.log` | ★ 关账全量绿轮（pom 补记后，rc=0，700 条） |
| `task-17-evidence/completion-full-verify-before-pom-fix.log` | pom 补记前那轮绿（留档） |
| `task-17-evidence/criteria-green.log` | 四判据定向轮（26 条） |
| `task-17-evidence/R1-R18-sweep.md` | R1~R18 点验底稿（子代理检索）+ ★ 控制器补证（r3/r14）与裁定 56 |
| `task-17-evidence/mutants/mut-round.sh` | 本轮补证装置（九道门禁 + FQN 报告路径 + mtime 判定 + 自指补记） |
| `task-17-evidence/mutants/{CheckpointStore.r3-no-write.java, TimeAdvance.r14-allow-unregistered.java}` + `mutants/orig/` | 两个补证变异体 + 原件快照 |
| `task-17-evidence/logs/{r3.log, r14.log, green-after-r3-r14.log}` | 两轮补证 + 还原后定向绿 |
| `task-N-evidence/`（N=3..16） | 各任务绿轮 + 变异轮 + 自指日志（M4 全部任务） |
