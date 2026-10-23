# T 责任区测试交账：AgentLib 决策包迁移（2026-10-17）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/agentlib-dock`  
> 范围：只改 `src/test`、测试夹具与 `docs/`；未持久修改任何 `src/main`、`pom.xml`、AgentLib 或主树；未做 git 写操作。变异自证中的生产文件只做临时改坏，已按 md5 原样恢复。  
> 任务板：task-3 已由 lead 代为标 completed。  
> 最终门禁：lead 在测试收口后执行 `tools/mvn-lock.sh -q clean verify` → **rc=0**；**3328 tests / 0 failures / 0 errors / 7 skipped**；前端 412/412；SpotBugs/Spotless/Shaded guard 全过。

## 1. 基线侦察（真实命令）

第一次 `tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am` 的真实首站：

- `simos-ga-spi/.../SpiContractInvariantsTest.java:67,87`：`DecisionProposable` 已删，旧测试未改（3 个 cannot find symbol）。
- 修 ga-spi 后第二站：`simos-ga/.../GaRoundTripTest.java:231,272,297,302,303,306,588,604,734`：
  `Map<DecisionPacketId, DecisionPacket>` vs `DecisionRecord`，以及私有 `DecisionPacket` 构造器。
- 修 ga 后第三站 `simos-app`：12 个编译错误，全部指向已退役类型/工具：
  `ProposeCallTool`、`GmDecidePacketTool`、`GmPacketExecuteTool`、`ProposalCatalog`、`DecisionProposable`、`ProposalDeclarationAudit`。
- 清理旧编译面后，`ShellConfig.withPorts` 从 1 参审批端口收为 2 参，约 60 个测试按新 API 批量适配；随后才进入运行期旧语义失败。

## 2. 测试改动

### 新增（§10 关键判据）

| 文件 | 覆盖 |
|---|---|
| `simos-app/src/test/.../decision/DecisionPackageTestSupport.java` | 决策包测试共享夹具：只造值/读 head，不复制状态机 |
| `.../decision/DecisionTurnQueueTest.java` | D1–D6（队列/同 tick 四轮 append/纯文本/控制面/冻结/abort 载体） |
| `.../decision/DecisionPackageGmPortTest.java` | G1–G6、stale/frozen/空包/全驳/重复 commit/旧命令旁路、CONFLICTED 回执状态机、GmPacketsTool 只读不迁状态 |
| `.../decision/DecisionPackageCommitConflictTest.java` | S2c：effect 批 commit 冲突 core E2E → CONFLICTED、零 effect/零 outcome、并发改包 stale 不覆盖 |
| `.../decision/DecisionPackagePersistenceTest.java` | P1–P3：三次重启后 read/decide/commit；P2 生产代码无内存表构造/字段 |
| `simos-ga/.../GaRoundTripTest.java` | 扩展 P3/P4：PROPOSED/DECIDING/DECIDED/COMMITTED/EXPIRED 快照 + 七表 JSON/ChangeSet 往返 |

### 按新语义改写/更新

- `DecisionAgentRunnerTest`：Allow 直执行、Ask 入队零 world revision；冻结面七个包工具由 `propose` 改为 `request`；S1c 增加 DECIDING/终态启动守卫（零 LLM、零会话写、零 queue 创建）。
- `GmActAsToolTest`：不再调用已退役 propose/DRAFT 流程；改为验证代打身份、同 tick 包写、GM-only/编排族不可绕过。
- `ShellApprovalTest`：HTTP 审批面退役（H1/H2）、旧 GM 审批工具不注册；非包 Ask fail-closed 方向的配套在 `AgentLibDocsWiringTest`。
- `DecisionPacketToolGateScanTest`：DM 控制面 source scan 改 `toolRoles`，新增 `request` 必须 Ask；GM 读面断言改为只读投影/packageState。
- `SimosToolsTest`、`McpCoverageTest`、`McpServerTest`、`McpPortTopologyTest`、`ToolClassAuditAssemblyTest`、`ShellAssemblySmokeTest`、`AdjudicateTickToolTest`、`DecisionCallerFactoryTest`、`DecisionFaceGolden`：按五工具、`ga.ApplyDecisionPackage`、181 GM 桶、194 并集、141 命令类型重算。
- `AgentLibAvailabilityTest`：移除硬编码的已删除审批类，加入 AgentLib 0.2.3 决策包五工具/读口/状态机/队列类型。
- `GuiApiTest`：`/api/approvals` 改为 404 退役断言。
- `GovPayToolD5Test`、`GovToolsZ6Test`、`GmToolUsageApiTest`、`DecisionMakerScopeEndToEndTest`、`StartDecisionEndToEndTest`：补角色维/绑定队列/退役审批面语义。
- 其余约 40 个测试仅做 `withPorts(0,0,0) → withPorts(0,0)` 机械适配。

### 删除（旧状态机/旧工具面专用，已由新测试取代）

- `DecisionPacketEndToEndTest`（旧 propose→confirm→GmDecide→GmExecute）
- `RunDecisionEndToEndTest`（旧 DRAFT/PENDING 状态机）
- `ProposalCatalogDeclarationTest` / `ProposalCatalogPlanOnlyTest` / `ProposalCatalogPlanOnlyScanTest`
- `ProposalDeclarationAuditTest` / `ProposeWriteThenThrowGuardTest`

## 3. 最终命令与计数

```text
tools/mvn-lock.sh -q -pl simos-app -am test
rc=0
surefire 418 份报告（本轮 mtime）
3328 tests / 0 failures / 0 errors / 7 skipped
```

分模块：app 960、unit 518、map 379、economy 360、core 245、util 217、social 217、actor 130、army 109、economy-api 50、ga-spi 44、calendar 33、gov 33、ga 22、actor-api 8、social-api 3。

```text
tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-app -am
rc=0
```

定向测试组合（16 个关键类）共 161 跑，0 失败/0 错误；其中新增五类：GmPort 9、Persistence 2、Queue 5、CommitConflict 2、GaRoundTrip 13。

## 4. 变异自证（全部临时改坏 → 目标红 → 原样还原 → md5 复核）

| # | 变异 | before md5 | mutant md5 | after md5 | 目标测试 | 结果 |
|---|---|---|---|---|---|---|
| 1 | `RequestTool.gate` 由 `new ToolGate.Ask` 改为 `ToolGate.ALLOW` | `aaffec3fd30fb34c0507cad61488a953` | `20ae60cc14bbcc780c35e496ca28bb0a` | 同 before | `DecisionPacketToolGateScanTest#requestToolIsAskAndDmOnly` | 1/1 红；失败在 “request 必须返回 Ask” |
| 2 | `CoreDecisionSubmissionPort` 的已提交 receipt 幂等早返条件对 COMMITTED 失效 | `db73a42319f6bb5370ff8212ccb60c83` | `cb921a4b84b5ba7d9424d5c60a8dab6e` | 同 before | `DecisionPackageGmPortTest#decisionAndCommitAreSingleRevisionAndCommitIsIdempotent` | 1/1 红；重复 commit 不再成功 |
| 3 | `GaCodec` 的 DecisionPacketId key reader 读成 `MUTATED-<key>` | `264f444c0e355084f2bcf2b56263053e` | `671bb6dde54b98beccfe86cceb7e25e7` | 同 before | `GaRoundTripTest#decisionRecordsRestoreProposedDecidingDecidedAndCommittedSnapshots` | 1/1 红；解码期 “决策包键必须等于值内 id” |
| 4 | `DecisionTurnFinalizer` append 起始 index 去掉 `+1` | `b7fd13faaadcf646801e8cdd5ff84461` | `8449d312a67e6e836b67ec9767bfc274` | 同 before | `DecisionTurnQueueTest#fourRoundsAppendToTheSamePacketWithStableIncreasingIndexes` | 1/1 红；第二轮回 `INVALID_ITEM_INDEX` |
| 5 | `CoreDecisionReadAudit.detailRead` 早退 no-op | `736bbea594c5a2b1c6fde6eb605e392a` | `313ecfa762269abaf1f24dcfe8c30506` | 同 before | `DecisionPackageGmPortTest#listIsCurrentTickOnlyGetAuditsFullReadsAndDomainProjectionIsReadOnly` | 1/1 红；full=true 未落审计 revision |
| 6 | S1c：`DecisionAgentRunner.run` 的启动守卫调用被 `if(false)` 包掉 | `9b8c389576ba86455dc65705d631cd52` | `52f0d5961bee51aaca578918c1b15dc2` | 同 before | `DecisionAgentRunnerTest#frozenPackageRejectsRunBeforeLlmConversationOrQueueCreation` | 1/1 红；实际走到 FakeLlm，证明“零 LLM”判别有效 |
| 7 | S2c：`CoreDecisionSubmissionPort` 的 CONFLICT 落定分支被关掉 | `32efb64d1f4ec6c7f6e45194d8e2a754` | `08f6dfc8346383caaccff71fee34c641` | 同 before | `DecisionPackageCommitConflictTest#commitConflictIsPersistedAsConflictedWithZeroEffectAndNoOutcome` | 1/1 红；commit 不再返回 CONFLICTED |

附加尝试：仅替换/移除 `DecisionPackageSnapshot` 的 mixin 在当前 mapper 下 **等价存活**（线格式未变），故未把它算作有效变异，改用 key reader 变异并得到真红。

## 5. S1c / S2c 复核结论（lead 已实现，测试已锁住）

1. **D6 启动守卫已满足**：`DecisionAgentRunner.run` 在派生 conversationId、load/append 会话、创建 PendingDecisionQueue、调 LLM 之前调用 `requireCurrentDecisionPackageEditable`。DECIDING 与 DECIDED 两种状态的行为测试均验证：抛 `PACKAGE_CONTENT_FROZEN`、零 LLM、零会话写入、原 sentinel 队列未被替换、零 revision；另有源码顺序断言 guard 在 `new PendingDecisionQueue` 之前。
2. **commit 冲突 → CONFLICTED 已满足**：`DecisionPackageCommitConflictTest` 用规划期并发写者确定性推走 head，再走 AgentLib `decision_package_commit` → CoreSubmissionPort → DecisionEffectExecutor。断言：
   - 包终态 `CONFLICTED`；
   - `CommitReceipt.Status.CONFLICTED`、`revisionRef == null`；
   - 原 call 无 outcome，planned effect 的 INFO 地址不存在（零 effect）；
   - head 只增加“并发写者 + conflict 落定”两条，effect 批零 revision。
   另有一条并发改包用例：返回具名 `PACKAGE_VERSION_STALE`/`COMMIT_FAILED`，包保持并发写者的新 contentVersion/意图，旧 plan 不覆盖。
3. **P2 口径已按 lead 放宽**：允许 `import DecisionPackages.RejectionException` 作为 AgentLib 协议异常；测试继续断言生产代码没有 `new DecisionPackages(`、没有 `DecisionPackages` 内存表字段/参数/构造注入，并显式记录静态异常用法是允许的。
4. **GmPacketsTool 按 lead 口径保留**：只读注册允许；`DecisionPackageGmPortTest#gmPacketsStaysRegisteredAsReadOnlyProjectionAndDoesNotMigrateState` 断言调用后零 revision、决策包逐字段不变。

### 仍未关闭的注意项

- `RealLlmGovScenarioTest` 仍保留旧 propose/DRAFT 工作流提示与断言；该类由 `SIMOS_REAL_LLM=1` 门控，本轮未真跑。开真 LLM 门禁前需迁移。
- task-3 已由 lead 代为标 completed。

## 6. 未运行 / 未验证

- `clean verify` 已由 lead 执行：**rc=0**；**3328 tests / 0 failures / 0 errors / 7 skipped**；前端 412/412；SpotBugs/Spotless/Shaded guard 全过。
- 未跑 `package`、`install`、真实 LLM。
- `RealLlmGovScenarioTest` / `RealLlmUnitDecisionLoopTest` 由环境变量门控，本轮未真跑；其中 `RealLlmGovScenarioTest` 仍保留旧 propose/DRAFT 工作流的提示与断言，后续若开真 LLM 门禁需先迁移。
