# SDSimos 阶段 E（关账）报告

> 分支 `sd/e`，worktree `.claude/worktrees/sde`，基线 `c49668e`（阶段 D 合并后，= 主树 HEAD）。
> spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（§十一 判据 / §十二 挂起 / §十四 未核实）。
> 计划：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md`（阶段 E + §〇 通则 + §附 推演表）。
> 台账：`.superpowers/sdd/2026-09-20-sd-simos/progress.md`。
> 证据：`.superpowers/sdd/2026-09-20-sd-simos/e-evidence/`（`logs/clean-verify.log`、`logs/recomputed.txt`、`logs/attempts.txt`、`task-final-report.md`）。
> ★ 本报告**不改 spec / 计划正文**；执行期取代说明见 `progress.md` 与 `d-stage-report.md` §三、`c-stage-report.md` §三、`a3a6-report.md` §五。

---

## 一 门禁（实测，第 1 次尝试）

**命令**：`./mvnw clean verify`（前台、独占——本机 `nproc=8`，门禁期间控制器未并发跑任何命令）。
**结果**：**rc=0、第 1 次尝试**。日志 `e-evidence/logs/clean-verify.log`（2212 行，`md5=50cea6463a49e0179d64f0a086abe29c`）。

| 项 | 实测值 | 取数方式 |
|---|---|---|
| `BUILD SUCCESS` | `[INFO] BUILD SUCCESS` | `grep BUILD` |
| 模块 | **8/8 SUCCESS**（**显示名**）：`SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp` | `grep 'SUCCESS \['` |
| 用例总数 | **1281** | 现场重算：`grep -E '^\[INFO\] Tests run: …, Skipped: 0$' \| grep -oE 'Tests run: [0-9]+' \| grep -oE '[0-9]+' \| paste -sd+ \| bc` |
| 逐模块 | `util 170 / map 362 / social 45 / unit 259 / core 177 / sd 124 / app 144` | 见 `logs/recomputed.txt`（按 `Building <显示名>` 定位） |
| SpotBugs | `BugInstance size is 0` **×7**（util/map/social/unit/core/sd/app；父 POM 跳过分析） | `grep -c` |
| `[ERROR]` | **0 行** | `grep -c '^\[ERROR\]'` |
| 前端门禁 | `[frontend-gate] OK tests=90 pass=90 fail=0` | 日志原文 |

**delta 干净**：阶段 E 是**纯关账轮**（**零生产 / 零测试改动**）⇒ 本值与阶段 D 合并后同值；上面的 **1281** 是**本轮现场重算**，不是引用任何文档里的现成数字（`CLAUDE.md` 与台账里的数**只用来发现分歧**，不作结论——实际与它们一致）。

★ **SpotBugs 分析面**：7 个模块各自跑了 `spotbugs:spotbugs` + `spotbugs:check`（日志各模块有 `--- spotbugs:...:spotbugs @ <mod>` 与 `BugInstance size is 0`）。本机 `nproc=8`、全量约 **48s**，无被杀轮（与 CLAUDE.md 记载的 `nproc=2` 机器的"摘后台/被杀"结论**不适用本机**，如实记）。

---

## 二 spec §十一 判据 —— 逐条实测值（15 条，无占位符）

> 纪律：**判据不断言 LLM 的具体选择**（N14）。每条给 `实测值 + 证据锚（文件:行） + 变异保证`。
> 行号是 **worktree `sd/e` 当前字节**的行号。

| # | 判据 | 实测值 | 证据锚（文件:行） | 变异（去掉被保护那行 ⇒ 红） |
|---|---|---|---|---|
| **1** | 模块边界自证：sd 可依赖 agentlib + 四模块；**core main 不得依赖 sd**（enforcer） | sd `bannedDependencies.excludes` **恰 `{simos-core, simos-app}` 两条**、**不含 `agentlib-mosire`**；core main `excludes`（+`includes` 按 scope 放行领域模块）**含 `simos-sd`**；本关账轮 enforcer 在两模块均 **passed**（构建 rc=0） | `simos-sd/pom.xml:58-62`；`simos-core/pom.xml:92-99`（exclude simos-sd）、`:100-104`（test-scope includes） | **a1-m1**：core 加 sd 依赖 ⇒ `BannedDependencies failed`、红点 `io.mosire:simos-sd ... <--- banned`；**a1-m2**：sd 加 core 依赖 ⇒ `io.mosire:simos-core ... <--- banned`。两轮 `COMPILATION ERROR=0`、`cp` 逐字节还原、干净树对照 `passed=2`（`a1-evidence/`） |
| **2** | R4 单指令：同 dm 同 tick 第二条 `sd.IssueDirective` ⇒ 拒、`revisions` 行数不变 | 第二条被拒，理由含 `R4 违反` + `dm1` + `3` + 首条 id `d1`；**第一条未被覆盖**（`directives` 仍 size 1）；另一 tick 允许第二条（size 2）；**状态层**构造期同样抛 `"R4 违反：决策人 dm1 在 tick 0 已有 Directive"` | `IssueDirectiveHandlerTest.java:63`（命令期）、`:74`（另一 tick）、`:33`；`SdStateInvariantTest.java:33`（状态期，`hasMessage` 精确匹配） | **d1m1**：删 R4 唯一性校验 ⇒ 红在 `rejectsSecondDirectiveForSameMakerAndTick`（`d-evidence/mutants/mut-run.log`） |
| **3** | N7 判决冻结：含判决的推进后 Replay ⇒ SdState 逐字段相同、`FakeLlmClient` 调用计数 = 0 | 先证**非零基线**：`adjudicate` 一次 ⇒ `llmCalls=1`（`isPositive`）；提交判决（revision **2**）后 Replay：`verdict.payloadJson` **逐字节 == 输入**、`atRevision=1`、`model=prompt-1`；**两次 Replay 相等**；`llmCalls` **== baseline（增量 0）** | `SdVerdictFreezeEndToEndTest.java:76-83`（非零基线）、`:91-97`（冻结 + `=0`）、`:101`（两次 Replay 相等）；结构上 `Replay`/`CoreSimos.replay` **不引用**任何 adjudicator/`LlmClient`（`LlmClient` 只出现在 `sd/adjudication` 与 `AdjudicatorRunner`） | **d7m1**：D1/D3 拆成两次调用 ⇒ 红（含 `d1AndD3AreOneCallAndTheVerdictIsFrozen`）；**d2m1**：让 LLM 异常逃逸 ⇒ 红。★ N7 无独立变异体（"回放调 LLM"需改 `Replay`——非本任务代码）；冻结面由 d3m1/d3m2 与 d7m1 覆盖 |
| **4** | N8 `verdictMeta`：判决行 `model/promptVersion/inputBriefDigest` 非空 | `VerdictMeta` 构造期**逐字段** `isBlank()` 即抛（三字段各一条消息）；handler 经 `new VerdictMeta(requireText(model), requireText(promptVersion), requireText(inputBriefDigest))` ⇒ 任一空白即拒；端到端实测空白 `model=" "` ⇒ `Rejected`、理由含 `model`、head 不动 | `VerdictMeta.java:8-19`；`SubmitVerdictHandler.java:71-81`（`requireMeta`）；`SubmitVerdictHandlerTest.java:58`；`SdVerdictFreezeEndToEndTest.java:110` | **d2m2**：删 schema 必填约束 ⇒ 红在 `schemaValidatesRequiredFieldsPerBreakpoint`；meta 三字段由 `VerdictMeta` 构造期钉住（A2-m1 同族：删 `weight>0` 即红） |
| **5** | N1 条件驱动：`AtOrAfterTick` 与 `ThresholdKills` 两条件各实测推进；上一 exit == 下一 entry 不满足 ⇒ 命令拒绝 | `AtOrAfterTick(5)`：tick 3→4 **不动**（`currentStage=S1`）、5→6 **推进到 S2**；`ThresholdKills(10)` + 损失记录 `-10` ⇒ 累计击杀 10 ≥ 阈值 ⇒ S2。端到端（真 store + 真 participant）：`rev5=s1`、`rev6=s2` | `SdTimeParticipantTest.java:93`（AtOrAfterTick）、`:104`（ThresholdKills）；`SdCombatEndToEndTest.java:79-83`。链校验：`SdCombatHandlersTest.java:82`（`addStageRejectsBrokenChain`）、`SdStateInvariantTest.java:75`（`阶段链断裂`） | **c4m3**：阶段推进时间硬编码 ⇒ 红在 `stageAdvancesWhenAtOrAfterTickExitIsSatisfied`；**c1m1**：断链校验 ⇒ 红在 `addStageRejectsBrokenChain` |
| **6** | N2 categorical：outcomeTable 权重归一、恰选一个；同一 revision 两次读出逐字节相同 | 恰一个：提交 `o2` ⇒ `selectedOutcome` 含 `o2`；**再提交 ⇒ 拒（`已选定结局`）**、被拒不写行（head 仍 7）；同一坐标两次 `Replay` **逐字段相等**、两次 `encodeSnapshot` **逐字节相同**；不在该阶段表 ⇒ 拒（`不在该阶段的 outcomeTable 里`）。权重：`weight > 0` 构造期强制（`weight=0` 抛）、空表拒；**"归一"落为 categorical 权重表（`OutcomeTable` javadoc），选定由 LLM/人选定（不采样）⇒ 无数值归一化**——见 §〇 诚实披露 | `SdCombatEndToEndTest.java:92-97`（恰一个）、`:86-89`（逐字节）；`SdCombatHandlersTest.java:147/154/161`；`OutcomeOption.java:20-21`、`OutcomeTable.java`（空表） | **c2m1**：删表内校验 ⇒ 红在 `commitOutcomeRejectsOutcomeFromAnotherStage`；**c2m2**：已选过不覆盖 ⇒ 红在 `commitOutcomeRejectsSecondSelection`；**a2-m1/m2**：weight/空表守卫 |
| **7** | N3 双轨 + 上界：Δpersonnel/Δequipment 逐项为负且 \|Δ\| ≤ 当前；越界拒绝；`LossRecord` 可回放 | 正 Δ 拒（`personnel 必须 ≤ 0`）；`-101 > 100` 拒（`人员战损超出当前值`）；**恰好 `-100`（= 当前值）接受**（差一杀点）；装备 `-51 > 50` 拒、`-1` 接受；未知装备键拒（`未知装备键`，不视作 0）；未知单位拒；`atRevision=1` 落记录；drain 侧越界战损被 unit 拒、unit 未改（100 不变） | `SdCombatHandlersTest.java:176-238`；`SdCommandDrainTest.java:92-105`；unit 侧 `unit.ApplyCasualties` 见 unit-ext `UnitOperationsTest` | **c3m1**：删上界 ⇒ 红在 `recordCasualtiesRejectsPersonnelOverBound`；**c3m2**：上界差一 ⇒ 红在 `recordCasualtiesAcceptsExactPersonnelBound`；**c3m3**：`atRevision` ⇒ 红在 `recordCasualtiesStoresDeltaWithRevision` |
| **8** | R6 延期效果：条件未达成不产生；达成后只产生一次 | `PLANNED` + 区间 3→4（trigger=AtOrAfterTick 5）⇒ 变更集 `isEmpty()`、状态仍 `PLANNED`；区间 4→5 ⇒ `FIRED`；已 `FIRED` 再推进 5→6 ⇒ 变更集 `isEmpty()`（`effectDoesNotRepeatOnLaterTick`）；`PutInfo` 型 action 已 FIRED 再推进 ⇒ **不追加第二条 INFO**（`firedPutInfoEffectDoesNotAppendAgain`，size 仍 1） | `SdTimeParticipantTest.java:63/71/78/146` | **c4m1**：条件未达成即产生 ⇒ 红在 `effectDoesNotFireBeforeTrigger`；**c4m2**（重复处理 FIRED 的 `EnqueueUnitCommand`）为**等价存活**（状态无差异），补 **c4m2b**（`PutInfo` 型有差异）**KILLED** |
| **9** | 变更集往返：`SdChangeSet` 全组件（含 `info`）`assertRoundTrip` 绿；`ArchitectureGuardsTest` 计数 = 5 | 反射枚举 `SdState` **10 组件逐组件**：变更集非空、`between` 报 changed、`apply` **往返 == target**；豁免清单**为空**；`SdChangeSet` **恰 10 组件**且与 `SdState` 组件名**双向子集**；`empty→full` 整fixture 往返绿；`SdCodec` 编码→解码→**再编码逐字节相同**（`SdCodecTest` 5/5）；`ArchitectureGuardsTest` **`containsExactly` 恰 5 条** `implements ChangeSet`（World/Map/Social/Unit/**Sd**） | `SdRoundTripTest.java:24/41/46/57`；`ArchitectureGuardsTest.java:27`；`SdCodecTest.java`（5 条，surefire `Tests run: 5`） | **a3-m1**：`between` 的 info 恒 Unchanged ⇒ 红；**a3-m2**：`apply` 不重建 combats ⇒ 红；**a3-m3**：删 sd 路径 ⇒ 红（实得 5 ≠ 期望 4）。**a3-m4**（删 `NationId` 键反序列化器）为**等价存活**（单 String record 默认回退已可读回），补 **a3-m4b**（裸 cast）**KILLED** |
| **10** | R10/N6 可见性：两个不同 `ViewScope` 的 dm 调同一读端点 ⇒ 返回不同数据；`sd.SetViewScope` 写入 revision | 同一 `mapOverview` 端点：`dm-a → hexes ["1_1"]`、`dm-b → ["1_2"]`，两响应 **`isNotEqualTo`**；`units`：A 见 `u-1`、B **空**；未知 actor **fail-closed 到空范围**；`ChannelAdmission.redactedBrief`：briefA 含 `u-a/r-a` **不含 `u-b`**、briefB 反之、两 brief 不同；`sd.SetViewScope` ⇒ `revisions` size **2**、Replay 后 `visibleHexes == {(2,3)}` | `RedactingQueryServiceTest.java:79/92/103`；`ChannelAdmissionTest.java:46`；`SdSetViewScopeEndToEndTest.java:54` | **d4m1**：删 hex redaction 插桩 ⇒ 红在 `twoScopesSeeDifferentHexesOnTheSameEndpoint`（"去掉插桩即相同"由它**间接证**，未另写对照用例——见 §六）；**d4m2**：`SetViewScope` 不落新范围 ⇒ 红 |
| **11** | N9 窄工具：GM 与决策 Agent 工具有效集里**都没有** `simos.command.submit`；有 `sd.*` 窄工具 | `toolsFor(GM)`：**不含** `simos.command.submit`、**含** `sd.IssueDirective`/`sd.SubmitVerdict`/`sd.SetViewScope`；`toolsFor(DECISION_AGENT)`：**不含** 通用写与 `SetViewScope`、含两条决策窄工具；`toolsFor(EXTERNAL)`：**含** 通用写、不含三条 sd 窄工具 | `SimosToolsTest.java:241`（`roleBucketsNeverCarryGenericWrite`） | **d6m1**：GM 桶塞入通用写 ⇒ 红在 `roleBucketsNeverCarryGenericWrite` |
| **12** | N12 配额同事务：并发两条同 tick 指令 ⇒ 恰一条成功、另一条拒/冲突、无半写 revision | **机制级**：R4 在 `IssueDirectiveHandler` 内（与发 revision 同一命令路径）、handler 在 ② 锁外跑、`commitUnderLock` 的 ③ 锁内复查 + ④ 与事件**同一事务**（`appendRevision(row, events)`）；核心并发护栏 `OptimisticConcurrencyTest`（**K=50 轮**，同 `expectedRevision` 两线程 ⇒ **恰一个 Committed / 恰一个 Conflict**，胜者 ref = expected+1、败者 current == 胜者 ref、库 head 前进一步；含"真的并发过"**自证** `barrierPasses==2K`、`maxSimultaneous≥2`）。**未新增 `sd.IssueDirective` 专属并发端到端**（其对象是替身 `RenameUnit`）——见 §六 | `CommandBus.java:320-331`（③④ 同锁）、`:243-255`（guard/handler 在锁外）、`:347-379`（revision+事件同事务）；`IssueDirectiveHandlerTest.java:63`；`OptimisticConcurrencyTest.java:104` | **a6-m3**：guard 调用挪到 handler 之后 ⇒ 红在 `CommandBusGuardTest.rejectingGuardShortCircuitsBeforeTheHandler`；`OptimisticConcurrencyTest` 的 m1/m3（M4 Task 10，日志 `task-10-evidence/`）。**sd 专属并发无变异体**（同一残余） |
| **13** | §九 守卫：带 Nation tag 的 Region `map.DeleteRegion` ⇒ 拒；去 tag ⇒ 放行 | 端到端（真 `Shell` 装配）：带 tag `r1` ⇒ `Rejected`、理由含 `r1` + `国家 tag`、head **不动（rev 1）**；去 tag `r2` ⇒ `Committed`（head → 2）；**homeRegion 引用（无 tag）同样拒**；非 `map.DeleteRegion` 不受影响；同一输入两次调用结果相同 | `SdRegionDeleteGuardEndToEndTest.java:78/88/95`；`RegionDeleteGuardTest.java:26/33/38/48/62` | **a6-m1**：删 `Shell` 守卫装配 ⇒ 红在 `taggedRegionDeleteIsRejectedAndLeavesNoRevision`（区域被删成功）；**a6-m2**：恒拒 ⇒ 红在 `untaggedRegionIsAllowed` |
| **14** | N13 降级：`FakeLlmClient` 抛超时 / 非法 JSON ⇒ 本 tick 明确降级、不卡死、后续 tick 继续 | 超时（client 抛）⇒ `Judgement.Failed`、理由含 `timeout`、**异常不逃逸**；非法 JSON ⇒ `Failed`；schema 违规 ⇒ `Failed`；显式弃权 ⇒ `Abstained`（非 Failed）；合法 ⇒ `Accepted` 且 client **恰调一次**。tick 级：D2 断点失败 ⇒ 该断点 `Failed`、**tick 继续**（D1 仍 Accepted）、只有 D1 判决落盘 | `AdjudicationTest.java:85/99/106/113/124`；`AdjudicatorRunnerTest.java:97` | **d2m1**：让 LLM 异常逃逸 ⇒ 红在 `llmTimeoutDegradesToFailedWithoutEscaping`；**d7m2**：断点失败中断 tick ⇒ 红在 `failedBreakpointDoesNotStopTheTick` |
| **15** | §五.3 drain：跨模块效果经 `SdCommandDrain` 落成真 revision；幂等（重放不重复提交） | 推进后 drain ⇒ **恰 1 条** `Committed`、`revisions` 行数 **+1**、head 5、unit member **100→90**；**再次 drain ⇒ 空**（幂等）、head/行数不变；被拒 drain（`-9999`）⇒ 行数不变、unit 未改（100）、sd 侧 effect 仍 `FIRED`（**中间态可见**）、下次 drain 重试（size 1） | `SdCommandDrainTest.java:65`（真 revision + 幂等）、`:92`（被拒中间态）；幂等键 G6 = `"drain:"+effectId`（`c-stage-report.md` §三.2） | **c5m1**：drain 幂等键 ⇒ 红在 `crossModuleEffectBecomesARealRevisionAndIsIdempotent`；**c5m3**：drain 不判 `FIRED` ⇒ 同红 |

**判据合计：15/15 有实测值，无占位符。** 三条有**判别力说明**（非"未实现"）：#6 的"权重归一"是措辞、#12 的 sd 专属并发未独立测、#3 的"推进后"未与判决同测——逐条记 §〇。

---

## 三 R / N 点验

> `R7/R8/R11/R12/R15` 是**已被取代/合并的旧提案编号**（spec §〇.1 表下注记），**不是未决项**；`N4` 与 §〇.3 其余项已由用户 2026-09-20「开始吧」采纳为裁定。

### R 系列（10 条在案）

| # | 裁定 | 落点 / 实测 |
|---|---|---|
| R1 | NAA = SDSimos | 模块 `io.mosire.simos.sd`、artefactId `simos-sd`、显示名 `SDSimos`；全仓无 `naa` 包 |
| R2 | 新增 `simos-sd`；Core 看不见它 | 判据 #1；`simos-sd/pom.xml` + `simos-core/pom.xml` enforcer |
| R3 | `Directive` 命名 | `sd/model/Directive.java` + `DirectiveCommand`；信封仍是 Core 的 `Command`，二者不撞名 |
| R4 | 一决策人一 tick 至多一条 | 判据 #2（命令期 + 状态期两处） |
| R5 | agentlib 下放到 sd | `simos-sd/pom.xml` main 依赖含 `agentlib-mosire`；sd enforcer exclude **不含**它（判据 #1） |
| R6 | 延期效果 = 新增 `TimeParticipant`，不入 ③Resolve | `SdTimeParticipant`（`namespace="sd"`）+ `TriggerEvaluator`；判据 #8；`reads/writes` 显式 canonical（`SdTimeParticipantTest:85`） |
| R9 | 判决 = sd 的 tick 级流程；人与 Agent 写同一落点 | `AdjudicatorRunner` + `DecisionChannel` ⇒ 都落 `sd.IssueDirective`/`sd.SubmitVerdict`；`DecisionChannelTest:56`（`GuiDecisionChannel` 写同一 `IssueDirective` 落点） |
| R10 | 工具白名单 + 新建数据 redaction 层；GM 可为每决策 Agent 定制范围 | 判据 #10；`sd.SetViewScope` 写入 revision；GM 窄工具判据 #11 |
| R13 | Nation 区域 = 带 tag 的 Region | `NationTag`（`"nation:"+id`）；`CreateNationHandlerTest.rejectsRegionWithoutNationTag`；判据 #13 |
| R14 | INFO 纳入 sd 的 ChangeSet | `SdChangeSet.info`（10 组件之一）；`SdRoundTripTest` 覆盖 `info`；`SdPutInfoEndToEndTest`（真 `CoreSimos`+真 `Replay`，3 条） |

### N 系列（18 条）

| # | 裁定 | 落点 / 实测 |
|---|---|---|
| N1 | 条件驱动阶段 + 链式条件 | 判据 #5 |
| N2 | categorical 结局表 + LLM 生成候选/选定、恰一个 | 判据 #6 |
| N3 | 战损双轨 + delta + 代码侧上界 + 保留记录 | 判据 #7 |
| N4 | 编制两层（command_chain + Formation） | **unit-ext 范围**（unit-ext T1~T10 已关账）：`CommandChain`/`Unit.rejoinTarget`；sd 侧只读 unit 强度（判据 #7） |
| N5 | 独立 order-latency 时钟（v1 每命令一个延迟值） | `Effect.readyAtTick` 承载；**HQ 积压/饱和挂起**（spec §十二） |
| N6 | per-role redaction 必做 | 判据 #10 |
| N7 | 判决冻结、回放/分岔不重跑 LLM | 判据 #3 |
| N8 | `verdictMeta` 三字段 | 判据 #4 |
| N9 | 专用窄工具；决策 Agent 无通用写 | 判据 #11 |
| N10 | `DecisionAdjudicator` SPI，key/配额/重试/超时留模块外 | `sd/adjudication/{DecisionAdjudicator,LlmClient,LlmRequest,LlmDecisionAdjudicator}`；测试全用 `FakeLlmClient`（lambda），**零网络** |
| N11 | GM = 配权实体、不握通用写；配权是数据；过审批留痕 | 判据 #11 + `sd.SetViewScope` 落 revision（判据 #10）；审批复用 AgentLib `ApprovalCoordinator`（M5 既有） |
| N12 | 配额服务端强制、与发 revision 同事务 | 判据 #12（机制级；见 §六） |
| N13 | LLM 失败降级 | 判据 #14 |
| N14 | 判据不断言 LLM 的具体选择 | 全量 LLM 相关用例只断言**结构 / 计数 / 降级 / 冻结**；**无一处断言 outcome**（`AdjudicationTest`、`AdjudicatorRunnerTest`、`SdVerdictFreezeEndToEndTest` 逐条核过） |
| N15 | 决策人地址不可用 `agent:`，走 `sd:` | `SdResolver` canonical 八形态（含 `sd:combat.<c>:stage.<s>` 3 段 / `...:outcome.<o>` 4 段）；`SdResolverTest` 8 条 |
| N16 | 身份由渠道声明 + **模块侧**校验 | `ChannelAdmission.requireRepresentable`（伪造 actor ⇒ 抛、含 `N16`）；`ChannelAdmissionTest:27`；端到端 `DecisionChannelTest:71`（伪造 actor ⇒ 抛、无 revision） |
| N17 | 视图按 actor `viewScope` 取、渠道拿不到全量 | `ChannelAdmission.redactedBrief`（两 scope 简报不同、互不含对方项）；`DecisionRequest` **无 free-text/无视图数据**（`d-stage-report.md` §三.6） |
| N18 | 留痕：渠道 id + actor | revision 行 `initiator = "player:gui:dm1"`（`DecisionChannelTest:66`） |

**R/N 点验结论**：全部**有落点**；`N5` 的 HQ 积压/饱和与 `N4` 的一部分（编制）按 spec 归挂起/姊妹模块，**不是未实现**。

---

## 四 变异轮汇总（A + C + D，共 46 个变异体）

| 阶段 | 变异体数 | KILLED | 等价存活（已配可杀同族） | 装置 / 日志 |
|---|---|---|---|---|
| A1 | 2 | 2 | 0 | `a1-evidence/mutants/`（enforcer 构建期） |
| A2 | 3 | 3 | 0 | `a2-evidence/model-mutants.log` |
| A3~A6 | 13 | 12 | **1**（a3-m4 `NationId` 键注册） | `a3-evidence/mutants/mut-round.py`；**a3-m4b KILLED** 提供可杀 codec 变异体 |
| C1~C6 | 15 | 14 | **1**（c4m2 重复 FIRED） | `mutants/mut-run.py`；**c4m2b KILLED**（补 `firedPutInfoEffectDoesNotAppendAgain`） |
| D1~D7 | 13 | 13 | 0 | `d-evidence/mutants/mut-run.py` |
| **合计** | **46** | **44** | **2（均等价、均有可杀同族）** | — |

九道门禁逐轮落实（每阶段报告有 `orig/mutant/pushed/restored` **四处 md5 自证**、`COMPILATION ERROR=0`、surefire 报告 mtime 落本轮、`cp` 逐字节还原、日志自指）。**存活项如实存档，未伪造红点**：
- **a3-m4**：`NationId` 是单 String 分量 record ⇒ 显式键反序列化器**非承重**（Jackson 默认回退已可读回）。**不是护栏失效**，注册保留（既有形制）。
- **c4m2**：`EnqueueUnitCommand` 型 action 重复处理把 `FIRED→FIRED`，状态无差异。补 `PutInfo` 型（有差异）的 `c4m2b` 后 KILLED。
- **D 装置两次误判已更正**：`d1m3`（期望方法名写错）、`d7m1`（模块写错致 `Tests run=0`），按 id 过滤重跑均 KILLED（`d-stage-report.md` §五）。

★ **本关账轮不做新变异**（计划 E1 明写）：无生产/测试字节改动 ⇒ 各阶段变异体所对的字节**逐字节未变**。`AgentLibAvailabilityTest` 与 `~/.m2` 的 agentlib 构件无改动。

---

## 五 遗留 / 挂起清单 —— 逐条处置

> 来源：spec §十二、计划 §六.1（G1~G7）/ §六.2、各报告 §"我未能核实"。

### 5.1 spec §十二 挂起 / 不实现（14 条）

| 项 | 处置 | 状态 |
|---|---|---|
| "hex 的对应状态可变" | 只列计划、不实现（用户原话） | **未消**（设计外，不属本里程碑） |
| 撤销 / 重做 | 不做；靠回退分叉 | **未消**（有意不实现） |
| 预制 command | 不做 | **未消**（有意不实现） |
| "决策立刻生效"的 Command | 不做 | **未消**（有意不实现） |
| 跨 revision 的 drain 原子性 | 挂起；v1 以幂等 + 可重放补偿 | **未消**（中间态**故意可见**并有断言：`SdCommandDrainTest:92`） |
| `RECOVERABLE` 回池速率 | 挂起（v1 只记类别） | **未消**（`LossClass.RECOVERABLE` 只落类别） |
| HQ 积压/饱和 | 挂起（v1 order-latency 每命令一个值） | **未消** |
| per-session MCP 身份 | 挂起（现状无 per-session） | **未消**（渠道 `representableActors` = 当前世界全部决策人） |
| `LlmClient` 真实后端/key/重试/超时 | 挂起（归 app） | **未消**（只用 `FakeLlmClient`） |
| CRT / Lanchester 具体数值真值 | 不引入 | **未消**（有意） |
| EBO / EBAO 框架 | 不认 | **未消**（有意） |
| 全局 `InfoSystem` 写路径（选项 b） | 不做（R14 选 sd 自有 ChangeSet） | **未消**（有意） |
| `agent:` 命名空间的 resolver | 不新建 | **未消**（有意；决策人走 `sd:`） |
| spec §十四"未核实项"（§A.2/§A.3 行号、AgentLib 内部语义、per-hex resource scope、order-delay 公式、RAND 播客、`P(t)`、§B 未回源） | 见 §六 | **未消**（外部/研究盲区） |

### 5.2 计划 §六.1 待裁（G1~G7）

| # | 处置 | 状态 |
|---|---|---|
| G1 国家区域 tag 约定 | **已裁**：`"nation:"+nationId`，`NationTag` 常量集中一处，`CreateNation` 与 `RegionDeleteGuard` 引用同一常量（A4 落地） | **已消** |
| G2 `MutationGuard` 注册 API 形状 | **已裁**：`CoreSimos.register(MutationGuard)` + `CommandBus` 5 参构造；既有 4 参构造保留（空守卫） | **已消** |
| G3 N5 order-latency 字段落点 | **已裁**：`Effect.readyAtTick`；v1 不引入 Command Ops 数值 | **已消** |
| G4 `DecisionChannel.available()` 是否强制 | **已裁**：可选方法、默认 `true`、装配层可 `markUp`（`DecisionChannelTest:67` 实测 `available()==true`） | **已消** |
| G5 GUI 决策渠道端点形态 | **取代**：D5 未启用 `POST /api/sd/decision`；渠道为 app 层四实现（GUI/MCP/CLI/HTTP），落点统一经 `sd.*`。GUI 侧经既有 `/api/command`（`d-stage-report.md` §三.6） | **已消（形态变更，已记）** |
| G6 drain 幂等键确切形态 | **已裁（执行期）**：`"drain:"+effectId`，以 revision 行 `commandId` 判"已 drain"（进程重启仍成立） | **已消** |
| G7 AI 断点输出 schema 字段名 | **已裁（执行期）**：`AdjudicationSchemas` + `Breakpoints`（7 组）；字段名见 `AdjudicationTest` | **已消** |

### 5.3 带裁定的遗留（各报告 §"我未能核实"，未消项）

| # | 项 | 状态 / 为什么未消 |
|---|---|---|
| L1 | **`agentlib-mosire` 外部依赖盲区**（不在本仓；D 只用 lambda 假客户端，未接真实 LLM / 未联网 / 未核其 LLM API） | **未消**——外部依赖，超出关账范围 |
| L2 | **N12 sd 专属并发端到端**（沿用的 `OptimisticConcurrencyTest` 对象是 `RenameUnit` 替身，非 `sd.IssueDirective`） | **未消**——机制级已证（R4 + ③④ 同锁），**不新开测试**（"关账只收口不扩面"） |
| L3 | **D4 redaction 只接 2 端点 + 2 读工具**（`map.overview`/`unit.list`；其余 7 条读工具仍直连） | **未消**——计划写的 9 条未全接，已记 `d-stage-report.md` §三.5 |
| L4 | **D5 per-session MCP 身份**仍挂起；渠道 actor 声明非按 Agent 绑定 | **未消**（同 spec §十二） |
| L5 | **D5 CLI / HTTP 渠道实现无独立判据**（只经编译；GUI/MCP 被真测） | **未消** |
| L6 | **D6 角色桶是"可测结构"而非运行时路由**（运行中 MCP 仍发外部桶 12 工具含通用写） | **未消**（spec §八.3 把外部桶收敛列为挂起） |
| L7 | **D7 只自动落 D1/D3/D6 判决**；subject 由调用方给；**未挂进真实 tick 循环** | **未消**——`AdjudicatorRunner.run` 由测试显式调用 |
| L8 | **判据 #10 的"去掉插桩则两响应相同"**只由 d4m1 间接证；未另写对照用例 | **未消**（间接证已足够，未扩面） |
| L9 | **N14：LLM 选得对不对不在任何判据内** | **未消（设计如此）** |
| L10 | **`SdInfoEntry.value` 结构化值往返不满足**（裸 `Object`；只覆盖标量） | **未消**（A5-b；M4 裁定 38 同口径） |
| L11 | **守卫只覆盖 `map.DeleteRegion`**（§九 v1 范围） | **未消**（有意） |
| L12 | **`ThresholdKills` 是全局累计**（非 per-combat）语义 | **未消**（spec `Trigger.ThresholdKills(int)` 无 combat 引用） |
| L13 | **`AfterTicks`/`UnitAtHex`/`OutcomeSelected`/`And`/`Or` 无独立判据** | **未消**（计划只要求 AtOrAfterTick 与 ThresholdKills 各一条） |
| L14 | **`minDurationTicks`/`maxDurationTicks` 推进逻辑未使用** | **未消**（构造期校验有，FATHM 时长上下限未实现） |
| L15 | **C2 多 `CombatState` 指向同一 `Combat` 的语义未定**（`stateOrNull` 取首个） | **未消** |
| L16 | **真档（19441 格）与 A\* 代价未上**（sd 夹具全是合成小图） | **未消**（与 M8/M9 同一条开口项） |
| L17 | **`SdState` 第 5 条不变量（损失上界）构造期不可判**，落命令期 C3 | **已裁定**（A3-e，可判子集落地） |
| L18 | **`SdCodec` mixin 与 `changesetsWithoutDerivedPredicates()` 的 `empty` 冗余** | **未触发**（有意保留） |
| L19 | **A1 的独立全量 verify 未单独跑**（只跑定向 compile + enforcer 变异；8/8 由合并后全量覆盖） | **已由本轮全量覆盖**，但 A1 单轮数字仍非独立关账轮 |
| L20 | **A3-m4 等价性只对 `NationId` 实测**（未逐个 ID 复验；同形 record 属推断） | **未消**（C 阶段 `CommandChainId` 同类，姊妹模块已再证一次：T5-L3） |
| L21 | **跨 JVM 编码字节稳定性未测** | **未消**（仓内既有口径） |
| L22 | **`shell`/`[WARNING]` 数未逐条清点** | **未消**（只记 `[ERROR]`=0；本项目门禁不把 WARNING 当红） |

★ **本关账轮未发现需要"就地修"的新缺陷**（判据 15 条全部有实测支持；变异 46 个中 2 个等价存活均有可杀同族；门禁一次绿）。三条**判别力说明**（非缺陷）见 §〇.3。

---

## §〇 诚实披露（我没能核实的 / 我没跑的 / 我推断而非实测的）

1. **我没跑的**：本关账轮只跑了一次 `./mvnw clean verify`（**第 1 次尝试即绿**）。**没跑**：
   - 另一台机器 / 从模块目录起跑的门禁（CLAUDE.md 形态：护栏要在**每种环境形态**各证一次）；
   - `./mvnw -pl simos-sd -am verify` 等限域门禁（全量已绿，未另跑）；
   - e2e / 浏览器（sd 阶段**零前端改动**，前端仅由 `exec-maven-plugin` 的 90 条 JS 断言覆盖）；
   - 真档（19441 格）与 A\* 代价（L16）；真实 LLM（L1）。
2. **我推断而非实测的**：
   - **"`Replay` 不调用 LLM"** 除了 `llmCalls` 计数不变这条**实测**外，还有一条**结构推断**（`LlmClient` 只出现在 `sd/adjudication` 与 `AdjudicatorRunner`，`Replay` 不引用）。**推断部分未逐调用点验证**。
   - **N12 的"并发恰一条成功"**：机制由 `CommandBus` 源码 + `OptimisticConcurrencyTest`（替身 `RenameUnit`）**实测**；**`sd.IssueDirective` 专属并发未实测**（L2）。
   - **a3-m4 / c4m2 的"等价"**：根因已实测（默认回退 / 状态无差异），但**未对全部 ID 类型/全部 action 类型**逐一复验（L20）。
   - **"余下 7 条读工具未脱敏"** 取自 `d-stage-report.md` §三.5 的**自述**，本关账轮**未逐条 grep 复核**调用点。
3. **我没能核实的**：见 §五 的 L1~L22（逐条）。
4. **我改了什么**：**零生产 / 零测试代码改动**；只新增 `e-evidence/`（本报告 + 日志）并更新 `progress.md` 与 `CLAUDE.md` 的 SDSimos 行。
5. **三个"判据措辞 vs 实现"的差异（如实记，未改 spec/代码）**：
   - **#6 "权重归一"**：实现是"`weight > 0` 的 categorical 权重表 + 恰选一个（由 LLM/人选定，不采样）"，**没有把权重数值归一化到和为 1**。spec §三.3 的原文只要求 `weight > 0` 与 categorical，**归一化是实现外**；判据措辞偏严，**判为措辞差异、非缺口**。
   - **#3 "含判决的推进后 Replay"**：N7 由 `SdVerdictFreezeEndToEndTest`（有判决、无推进）实测；C6 `SdCombatEndToEndTest`（有推进、无判决）实测逐字节冻结。**"判决 + 推进"的合取未在同一条用例里测**——两组分都证到了，合取属**未独立覆盖**。
   - **#12**：见 L2。
6. **`E` 的证据不引用任何文档里的现成数字作结论**：门禁总数、逐模块数、BugInstance、ERROR 全部由 `logs/clean-verify.log` **现场重算**（命令见 §一）；与 `CLAUDE.md`/台账的记载一致**只用于发现分歧**（"跨文档一致只证明抄得整齐"）。
7. **本机环境**：`nproc=8`、内存 11G，`clean verify ≈ 48s`，无被杀轮。**"前台 + 独占"照做**（门禁期间未并发跑其它命令）。

---

## §六 我未能核实的（逐条）

即 §五.3 的 **L1~L22**（未消项）。其中**与判据直接相关**的是：
- **L2（N12 sd 专属并发）**——判据 #12 只到**机制级**；
- **L3（redaction 未全接）**——判据 #10 的可测面由 2 端点 + 2 读工具 + `ChannelAdmission` 满足，**其余 7 条读工具未接**；
- **L4/L5/L6/L7**——渠道/工具面的运行时路由未端到端验；
- **L1/L16/L21**——外部依赖、真档、跨 JVM 三条盲区。

**结论**：spec §十一 的 15 条判据**全部有实测值**；上述残余是**范围/环境**限制（外部依赖、真档、运行时路由、并发专属用例），**不是判据未实现**。

---

## 七 提交

- 本报告与 E 证据：见本提交（`docs(sd): SDSimos 关账报告与判据实测（E1）`）。
- 更新：`.superpowers/sdd/2026-09-20-sd-simos/progress.md`（台账收口）、`CLAUDE.md`（SDSimos 行）。
- 实现提交短 SHA：**无**（阶段 E 零代码改动）。基线 `c49668e`（阶段 D 合并后）；本关账提交短 SHA 见 `progress.md` 记账。
