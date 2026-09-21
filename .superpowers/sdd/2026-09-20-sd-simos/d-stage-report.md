# SDSimos 阶段 D（Directive + 判决 + 渠道）执行报告

> 分支 `sd/d`，worktree `.claude/worktrees/sdd`，基线 `e5b96ac`（阶段 C 合并后）。
> 计划：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md` 阶段 D（D1~D7）+ §〇 通则。
> spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md` §三/§七/§八/§十一/§十三。
> 证据：`.superpowers/sdd/2026-09-20-sd-simos/d-evidence/`（`logs/`、`mutants/`）。

## 一 门禁（实测）

`./mvnw clean verify`（前台、独占）**rc=0**，日志 `d-evidence/logs/clean-verify.log`、`verify-rc.txt`。

- 模块 **8/8 SUCCESS**（显示名）：`SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp`。
- 用例 **1281** = `util 170 / map 362 / social 45 / unit 259 / core 177 / sd 124 / app 144`。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**；前端 `[frontend-gate] OK tests=90 pass=90 fail=0`。
- **第几次尝试**：**第 1 次 FAILURE**（SpotBugs 9 条，见 §四），修后**第 2 次 SUCCESS**；变异轮后**第 3 次**复跑仍 rc=0（`clean-verify.log` 为第 3 次；第 2 次留档 `clean-verify.attempt2.log`）。★ 第 1 次的失败日志被第 2 次覆盖，未单独留档（如实记）。

### 基线自测与增量（自己实测，不引用文档数字）

本树基线（阶段 C 合并后 `e5b96ac`）= **1232** = `170/362/45/259/177/91/128`。delta 干净：
前五模块逐字不变，**sd 91→124（+33）**、**app 128→144（+16）**，合计 **+49 = 1281**。

- **sd +33**：`IssueDirectiveHandlerTest` 10 + `AdjudicationTest` 10 + `SubmitVerdictHandlerTest` 6 + `SetViewScopeHandlerTest` 4 + `ChannelAdmissionTest` 3。
- **app +16**：`SdVerdictFreezeEndToEndTest` 4 + `SdSetViewScopeEndToEndTest` 2 + `RedactingQueryServiceTest` 3 + `DecisionChannelTest` 3 + `AdjudicatorRunnerTest` 3 + `SimosToolsTest.roleBucketsNeverCarryGenericWrite` 1。

## 二 各步落地与判据

### D1 Directive + R4 + `sd.IssueDirective`

- 新文件：`sd/spi/DirectiveWhitelist`、`sd/spi/IssueDirectiveHandler`；`Shell` 注册（在 handler 循环之后用**完整** `commandTypes` 构造白名单）；`CatalogTool.PAYLOAD_HINTS` +1。
- 判据：同 dm 同 tick 第二条 ⇒ 拒绝（理由含 `R4 违反` + dm + tick + 首条 id）、状态不变；白名单外命令 ⇒ 拒绝；`sd.*` 自指 ⇒ 拒绝；`dm` 不存在 / `effects[]` 悬空 / `target` 非法 ⇒ 拒绝；执行原文落 `sd:directive.<id>` 的 INFO（key `intent`），`intentInfoKey` 只记 key。
- 测试 10 条（`IssueDirectiveHandlerTest`）。**N12 同事务**由 `CommandBus` 既有事务边界承担（R4 在 handler 内、与发 revision 同一事务）；**并发两条同 tick 的机制级判据沿用 M4 Task 10 的 `OptimisticConcurrencyTest`**（未新增，见 §六）。

### D2 `DecisionAdjudicator` SPI + 8 断点 schema + N13 降级

- 新文件：`sd/adjudication/{Judgement,DecisionAdjudicator,AdjudicationRequest,LlmRequest,LlmClient,Breakpoints,AdjudicationSchemas,LlmDecisionAdjudicator}`。
- 判据：`Judgement` 三态 + 空理由拒；`AdjudicationRequest` 四分量非空；`Breakpoints.callCount()=7`、`sharesCall(D1,D3)=true`；`acceptsVerdictSubject` 只认 D1/D3/D6 + `sd:combat.*`；schema 逐断点必填/枚举；**N13**：超时（client 抛）⇒ `Failed`、非法 JSON ⇒ `Failed`、显式弃权 ⇒ `Abstained`、合法 ⇒ `Accepted`（**异常不逃逸**）。
- 测试 10 条（`AdjudicationTest`），**全部 `FakeLlmClient`（lambda），零网络**。

### D3 判决冻结 + `sd.SubmitVerdict`（N7/N8）

- 新文件：`sd/adjudication/VerdictFreezer`、`sd/spi/SubmitVerdictHandler`；`Shell` 注册；`CatalogTool` +1。
- 判据：判决逐字节冻结 + `atRevision` 正确；重放两次状态相等；meta 任一空白 ⇒ 拒绝（点名字段）；subject 越面 ⇒ 拒绝；断点不产判决 ⇒ 拒绝；重复 id ⇒ 拒绝。
- sd 测试 6 条；app 端到端 4 条（真 `CoreSimos` + 真 `Replay`）：`FakeLlmClient` **非零基线**（先证裁决器确实会调 LLM，计 1 次）→ 提交 + 重放后计数**不增**（N7）。

### D4 可见性 + GM 配权（`sd.SetViewScope` / `RedactingQueryService`）

- 新文件：`sd/spi/SetViewScopeHandler`、`app/query/RedactingQueryService`；`GuiServer` 的 `/api/map/overview` 与 `/api/units` 支持 `?as=<dmId>`；读工具 `simos.map.overview` / `simos.unit.list` 支持可选 `actor`；`Shell` 注册 handler。
- 判据：`sd.SetViewScope` 写进 `DecisionMaker` 并**落 revision**（`revisions` 行 +1、重放后逐值在）；未知 dm / 非法 viewScope / 非法 disclosure ⇒ 拒绝；两个不同 `ViewScope` 的 dm 调**同一读端点** ⇒ 返回**不同** hex 集合（`dm-a→["1_1"]`、`dm-b→["1_2"]`），未知 actor **fail-closed** 到空范围。
- sd 测试 4 条；app 测试 3 + 端到端 2 条。

### D5 决策提交渠道（`DecisionChannel` + N16/N17/N18）

- 新文件：`sd/channel/{ActorId,DecisionRequest,DecisionChannel,ChannelAdmission}`；`app/sd/channel/{AbstractCoreDecisionChannel,GuiDecisionChannel,McpDecisionChannel,CliDecisionChannel,HttpDecisionChannel}`；`Shell` 装配四条渠道并暴露 `decisionChannels()`。
- 判据：**N16** 伪造 actor（不在渠道声明集合）⇒ **模块侧**拒绝且无 revision；**N17** `ChannelAdmission.redactedBrief` 按 actor `viewScope` 构造、两 scope 简报不同且不含对方可见项（渠道拿不到全量）；**N18** revision 行 `initiator = player:gui:dm1`（渠道 id + actor）；**R9** 非落点命令（`simos.command.submit`）⇒ 拒绝；合法决策落 `sd.IssueDirective` 真 revision。
- sd 测试 3 条；app 测试 3 条。

### D6 专用窄工具（N9）+ 工具面分载

- 新文件：`app/tools/write/{AbstractNarrowWriteTool,IssueDirectiveTool,SubmitVerdictTool,SetViewScopeTool}`；`SimosToolSource` 新增 `Role{EXTERNAL,GM,DECISION_AGENT}`；`Shell.toolsFor(role)`。
- 判据：**GM 桶**与**决策 Agent 桶**的工具有效集里**都没有** `simos.command.submit`；GM 有 3 条 `sd.*` 窄工具、决策 Agent 有 2 条（无配权）；外部桶保留现状（12 条、含通用写）。既有 `SimosToolsTest` 的 12 工具断言**不变**（外部桶逐条相同）。
- 测试：`SimosToolsTest.roleBucketsNeverCarryGenericWrite` 1 条（并入既有类）。

### D7 8 断点编排 + `AdjudicatorRunner`

- 新文件：`app/sd/AdjudicatorRunner`。
- 判据：due 含 D1 或 D3 ⇒ **恰一次调用**（`calls=1`）+ 判决落真 revision；8 断点 ⇒ **7 次调用**（D1/D3 合并）；`FakeLlmClient` 某断点失败 ⇒ 该断点 `Failed`、**tick 继续**、其余断点仍调用；判决落盘（D1/D6 各一条）。
- 测试 3 条（`AdjudicatorRunnerTest`）。

## 三 执行期取代说明

1. **D1 执行原文落点**（spec §三.5 未定）：`intentInfo` 文本写进地址 `sd:directive.<id>` 的 INFO 条目，key 固定 `"intent"`；`Directive.intentInfoKey` 只记该 key（与效果引用分开，spec §三.6）。
2. **D1 白名单来源**（spec §四）：从**注册面** `commandTypes` 派生，去掉 `sd.*`（禁自指）与 `simos.command.submit`（禁通用写）；`Shell` 的注册顺序据此为"先注册普通 handler → 收全量 commandTypes → 再注册 `sd.IssueDirective`"。
3. **D2 断点合并的表达**：`Breakpoints.callGroups()` 返回 **7 组**（D1/D3 同组），依赖合并的判据查它而非"8 个常量"；`acceptsVerdictSubject` 限定 D1/D3/D6 + `sd:combat.*`。
4. **D3 校验顺序**：`VerdictFreezer` **先判 subject 裁决面、再判 schema**——否则用非判决断点提交 D1 载荷时，报错会落在"schema 缺字段"上而非"该断点不产判决"，误导调用方。
5. **D4 范围收窄（如实记）**：redaction 已接 **GUI 两个读端点 + 两条读工具**（`map.overview`/`unit.list`）；计划 §三 D4 步骤 4 写的"9 条读工具"**未全部接线**——其余 7 条（resolve/facets/catalog/branches/map.hex/unit.get/population）仍是直连 `QueryService`。判据（§十一.10）的可测面已由端点 + 服务层满足；全量接线列为开口项。
6. **D5 `DecisionChannel.submit` 返回 void 但抛**：保留 spec §十三.1 的 `void` 签名；被拒/冲突时**抛**（调用方据此知道决策未落地），而不是静默。`DecisionRequest` 承载 `{branch, expectedRevision, commandType, payloadJson}`（**无 free-text、无视图数据**，N17）；渠道 `representableActors()` 由 `Shell` 以**动态 supplier**（当前世界的决策人）提供。
7. **D6 角色桶的落点**：`SimosToolSource` 的既有构造器委托给 `Role.EXTERNAL`（12 工具**逐条不变**）；GM/决策 Agent 桶由 `Shell.toolsFor(role)` 按需构建。**运行中的 MCP 服务仍用外部桶**（spec §八.3 明列"外部 MCP 桶是否收窄"为挂起）——角色分载是**可测的工具面结构**，不是 v1 的运行时路由。
8. **D7 自动落点范围**：`AdjudicatorRunner` 只把**产判决**的断点（D1/D3/D6）经 `sd.SubmitVerdict` 自动落盘；其余断点的接受草案**返回给调用方**，不自动发 `sd.IssueDirective`（草稿→指令装配需要白名单与更多 schema，v1 不做）。

## 四 门禁第 1 次失败（SpotBugs，如实记）

第 1 次 `clean verify` 在 `simos-app` 报 **9 条 Medium**：

- **7 条 `US_USELESS_SUPPRESSION_ON_METHOD`**：我在 `AdjudicatorRunner` / `AbstractCoreDecisionChannel` / 4 个渠道子类 / `AbstractNarrowWriteTool` 上加了 `@SuppressFBWarnings(EI_EXPOSE_REP2)`，但 SpotBugs **并未**在这些构造器上报 `EI_EXPOSE_REP2`（`CoreSimos` 是 final）⇒ 抑制是多余的。**修法**：删掉这些抑制注解。
- **2 条 `CT_CONSTRUCTOR_THROW`**：两个 **abstract** 基类（`AbstractCoreDecisionChannel` / `AbstractNarrowWriteTool`）的构造器里调了 `Objects.requireNonNull`，非 final 类构造器抛异常 ⇒ 终局化攻击面。**修法**：构造器不再抛（直接赋值，与既有 `CommandSubmitTool` 同形）。

★ 这正是"门禁不可跳过"的价值：9 条都是**新代码**引入的，`mvn test` 不会跑 SpotBugs。修后第 2 次 rc=0、`BugInstance size is 0` ×7。

## 五 变异（九道门禁）

装置 `.superpowers/sdd/2026-09-20-sd-simos/d-evidence/mutants/mut-run.py`，日志 `mut-run.log` + `logs/*.log`。每轮：**先自证** orig/mutant/pushed/restored 四处 md5（原≠变异、推入=变异、还原=原）；原地覆盖 canonical 文件；`clean test`（清陈旧 `.class`）；断言 `COMPILATION ERROR`=0 且 `Tests run ≥ 1`；判红落**被保护断言**；`cp` 逐字节还原；日志自指（本轮 md5 追加进日志）。

**13 个变异体全部 KILLED（0 存活）**：

| id | 靶 | 红点 | 结论 |
|---|---|---|---|
| d1m1 | 删 R4 唯一性校验 | `rejectsSecondDirectiveForSameMakerAndTick` | KILLED |
| d1m2 | 白名单放行通用写 | `whitelistDropsSdSelfReferenceAndGenericSubmit` | KILLED |
| d1m3 | 白名单放行 `sd.*` 自指 | `whitelistDropsSdSelfReferenceAndGenericSubmit` | KILLED |
| d2m1 | 让 LLM 异常逃逸 | `llmTimeoutDegradesToFailedWithoutEscaping` | KILLED |
| d2m2 | 删 `rationaleText` 约束 | `schemaValidatesRequiredFieldsPerBreakpoint` | KILLED |
| d3m1 | 删 subject 裁决面校验 | `rejectsSubjectOutsideTheBreakpointSurface` | KILLED |
| d3m2 | 删重复判决校验 | `rejectsDuplicateVerdictId` | KILLED |
| d4m1 | 删 hex redaction 插桩 | `twoScopesSeeDifferentHexesOnTheSameEndpoint` | KILLED |
| d4m2 | `SetViewScope` 不落新范围 | `writesViewScopeOntoTheDecisionMaker` | KILLED |
| d5m1 | 删 actor 模块侧校验 | `forgedActorIsRejectedByTheModule` | KILLED |
| d6m1 | GM 桶塞入通用写 | `roleBucketsNeverCarryGenericWrite` | KILLED |
| d7m1 | D1/D3 拆成两次调用 | `d1AndD3AreOneCallAndTheVerdictIsFrozen`（并连带另两条） | KILLED |
| d7m2 | 断点失败中断 tick | `failedBreakpointDoesNotStopTheTick` | KILLED |

★ **装置自身的两次假"存活"（如实记，已修正后重跑）**：首轮 `d1m3` 与 `d7m1` 被判 SURVIVED，**都不是真存活**——① `d1m3` 实际被 `whitelistDropsSdSelfReferenceAndGenericSubmit` 杀掉，而装置把"期望方法名"写成了 `rejectsSdSelfReferenceCommand`（handler 层的自指守卫另有一道，故那条仍绿）；② `d7m1` 的**模块写错**（测试在 `simos-app`，装置写成 `simos-sd`）⇒ `Tests run=0`、压根没跑到。两处修正后按 id 过滤重跑，**均 KILLED**（`d1m3` 红点 `whitelistDrops…:36`；`d7m1` 红点 3/3 全红含期望那条）。**未为造红放松任何判据。**

## 六 我未能核实的（诚实清单）

- **`agentlib-mosire` 是外部依赖、不在本仓**（**盲区**）：D 阶段的 LLM 路径**只用 lambda 假客户端**，**未接真实 LLM、未联网、未核 AgentLib 是否有可用 LLM 客户端**（spec §十四 同款声明）。
- **N12 并发同 tick**：D1 只做了**机制级**（handler 内 R1 检查 + `CommandBus` 单事务）；**未新增**并发两条同 tick 的端到端用例（沿用 M4 Task 10 的 `OptimisticConcurrencyTest`，其对象是 `RenameUnit` 而非 `sd.IssueDirective`）。判据 §十一.12 的"sd 专属并发"未实测。
- **D4 redaction 只接了 2 个端点 + 2 条读工具**（§三.5）：其余 7 条读工具**未接线**；`seeOwnUnits` 的"己方整棵子树"只有合成夹具（未在真档/多国多军上验）；`hex`/`region` 级裁剪未单测（只测了 hex 集合）。
- **D5 per-session MCP 身份**仍挂起（整 server 一个 `ToolContext`）；渠道的 `representableActors` 是"当前世界全部决策人"，**不是按 Agent 绑定**（spec §七.3 的 per-session 挂起）。
- **D5 渠道的 4 个实现只有 GUI/MCP 两个被真测**（`DecisionChannelTest` 用 `GuiDecisionChannel`/`McpDecisionChannel`）；CLI / HTTP 两个实现**只经编译**，无独立判据。
- **D6 角色桶是"可测结构"而非运行时路由**：运行中的 MCP 服务仍发外部桶 12 工具（含通用写）；**"决策 Agent 经 MCP 只能看到窄工具"未端到端验**（spec §八.3 把外部桶收敛列为挂起）。
- **D7 只落判决类断点**：D2/D4/D5/D7/D8 的接受草案**不自动发指令**；`AdjudicatorRunner` 的 subject 由调用方给（未从世界推导）；**未接真实 tick 循环**（`run` 由测试显式调用，未挂进 `SdTimeParticipant`/`advance`）。
- **判据 §十一.10 的"去掉插桩则两响应相同"**：由变异体 d4m1 **间接证**（删插桩 ⇒ 断言红），**未另写一条对照用例**。
- **N14 的"绝不断言 LLM 选择"**：D2/D7 的判据只断言结构/计数/降级，**未断言具体 outcome**——符合要求，但也意味着"LLM 选得对不对"**不在任何判据内**（设计如此）。
- **前端零改动**（90/90）；D 阶段无 JS 判据。
- **本机 `nproc=8`**，`clean verify` 约 **49s**（与 CLAUDE.md 记载的 `nproc=2` 机器完全不同）⇒ 并发/被杀轮结论**不适用本机**。

## 七 提交

- 实现提交短 SHA：见 `progress.md` 的 D 段（提交后回填）。
