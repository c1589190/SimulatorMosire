# Simos 外壳（M5）实现计划

> **上游**：`docs/superpowers/specs/2026-09-19-shell-simos-design.md`（**已获用户批准 2026-09-19**）。
> **基线**：`feat/adr1-core-scope @ 22888d0`（M4 关账 + M5 spec）。
> **执行机制**：照主计划 §六 五步流水线；子代理 + 每任务自带变异自证；关账门禁 `./mvnw clean verify`。
> **排序裁示**：用户 2026-09-19「**尽快把 WebUI 整出来**」⇒ 执行序按 **WebUI-first** 重排（见 §一）。

---

## 一 执行顺序（WebUI-first）

```
Batch A: T4 ‖ T2        （互不相交，2 路上限）
Batch B: T1             （app 骨架 + 装配，一次注册全部 8 个 handler）
Batch C: T3             （查询层 + 两个真 Facet）
Batch D: T8 → T9        ★ WebUI 出形（服务器 + /api → 前端三页 + Canvas）
Batch E: T5 ‖ T6 → T7   （工具集 / 审批装配 → MCP 服务）
Batch F: T10 → T11 → T12（AgentBinding → 判据端到端 → 关账）
```

**理由**：WebUI 的最小前置 = ①单元命令面（T4，否则 /unit 表单只有改名）②core 只读面（T2）③app 骨架（T1）④查询层（T3）。此后立即 T8/T9；AgentLib/MCP 侧（T5~T7）与 GUI 无依赖，排在 WebUI 之后。

**跨任务约束**（沿 M4 纪律，逐条照旧）：
- 本机 `nproc=2`：**并行上限 2**；控制器不在 agent 活着时跑 Maven；同一文件被两个任务改 ⇒ **串行**。
- 并行任务用 `git worktree`（`.claude/worktrees/m5tN`，分支 `m5/tN`，基线 = 派单时的 HEAD）；派单要求第 0 步自证基线。
- **变异自证九道门禁**照 CLAUDE.md/形态清单（自证字节不同、白名单推成目标类名、干净世界、`COMPILATION ERROR=0`、`Tests run≥1`、报告 mtime 落轮内、红点落位、逐字节还原、日志自指）。
- 禁 `mvn install`；`git add` 显式列文件、**绝不 `-A`**；提交信息不加 `Co-Authored-By`；**该推就推**。
- 台账 `.superpowers/sdd/2026-09-19-shell-simos-plan/`（本计划唯一可写台账）；证据入 `t<N>-evidence/`。

---

## 二 任务

### T4: unit 命令面补齐（7 条，spec §四）

**Files**：
- New: `simos-unit/src/main/java/io/mosire/simos/unit/spi/{CreateUnit,ReparentUnit,SetStrength,PlaceAt,PlanRoute,CancelRoute,DisbandUnit}Handler.java` + 共享解析助手 `UnitPayloads.java`
- Test: `simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`（复用 `SpiFixture`）

**Consumes**：`UnitOperations`（M3）、`UnitChangeSet`、`CommandHandler`（Task 2 契约）
**Produces**：7 个 `CommandHandler`，`type()` = `unit.<Op>`；`at` 一律取 `state.meta().timestamp()`

- [ ] Step 1 载荷约定落 `UnitPayloads`：`id/name/position/hex/parent/waypoints/member/equipment` 的解析与校验（坏载荷 ⇒ `HandlerOutcome.Rejected` 带可读原因）
- [ ] Step 2 七个 handler（与 `RenameUnitHandler` 同形：自反序列化 → `UnitOperations.*` → `UnitChangeSet.between`；`IllegalArgumentException` 折 `Rejected`）
- [ ] Step 3 测试：每命令 happy + 关键拒绝（id 重复 / 查无此人 / 路线非法 / 负人数）+ `CreateUnit` 初始段时刻 = base 时间戳的逐值断言
- [ ] Step 4 变异自证（≥2 轮）：
  | m | 做法 | 期望红 |
  |---|---|---|
  | m1 | `SetStrength` 忽略 `equipment` | 对应逐值断言 |
  | m2 | `CreateUnit` 初始段时刻写 `T0` 而非 base 时间戳 | 时刻断言 |
- [ ] Step 5 `spotless:apply` + 定向测试 + 报告 `t4-report.md`

---

### T2: core 只读扩展（spec §S8）

**Files**：Modify `simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java`；Test `CoreSimosTest.java`（增条）

- [ ] Step 1 `branches()` / `head(BranchId)` 只读委托（javadoc 注明"零写面"）
- [ ] Step 2 测试：与 `Timeline` 同源一致；**无副作用**（调用前后 `revisions` 行数不变）；未封存/已封存两态都可用
- [ ] Step 3 变异：`head` 忽略 `branch` 参数（全局 MAX）⇒ 用例红
- [ ] Step 4 定向测试 + 报告 `t2-report.md`

---

### T1: `simos-app` 骨架与装配门面（spec §3）

**Files**：
- Modify: `pom.xml`（`<modules>` 加 `simos-app`）
- New: `simos-app/pom.xml`；`simos-app/src/main/java/io/mosire/simos/app/{ShellConfig,Shell,ShellMain}.java`
- Test: `simos-app/src/test/java/io/mosire/simos/app/ShellSmokeTest.java`

**Consumes**：T4 的 7 个 handler、`RenameUnitHandler`、`UnitTimeParticipant`、三 codec、`CoreSimos`
**Produces**：`Shell.start(ShellConfig)` / `close()` / `coreSimos()` / 端口读回占位；装配顺序照 spec §3.2 的 1~2 步与 5~6 步中**不依赖审批/MCP 的部分**（审批/MCP/GUI 由 T6/T7/T8 接入）

- [ ] Step 1 app pom（依赖表 spec §2.1；**不设 enforcer**）；父 POM 加模块
- [ ] Step 2 `ShellConfig`（缺省 5711/5715/`/mcp`/5713/`agent:external-mcp`；`port=0` 支持）
- [ ] Step 3 `Shell`：`CoreSimos` 装配（codec 3 + handler **8** + participant 1，`MovementCost` 注入）；`close()` 次序骨架
- [ ] Step 4 `ShellMain`（参数解析：`--store --gui-port --mcp-port --approval-port`）
- [ ] Step 5 `ShellSmokeTest`：起壳（@TempDir、端口 0）→ `coreSimos().submit(RenameUnit)` → `replay` 逐值 → `close()` 后 store 关闭
- [ ] Step 6 全量 `clean verify`（**七模块**首次）+ 报告 `t1-report.md`

---

### T3: 查询层 + 两个真 Facet（spec §5）

**Files**：
- New: `simos-unit/src/main/java/io/mosire/simos/unit/facet/UnitsHereFacet.java`；`simos-social/src/main/java/io/mosire/simos/social/facet/PopulationFacet.java`
- New: `simos-app/src/main/java/io/mosire/simos/app/query/QueryService.java`；`Shell` 增注册
- Test: `UnitsHereFacetTest` / `PopulationFacetTest` / `QueryServiceTest`

- [ ] Step 1 两个 facet（只读 `ctx.state()` 对应切片；value 用 `List<String>` / `Number`）
- [ ] Step 2 `QueryService`（spec §5.1）+ `Shell` 注册 `ResolverRegistry`（Map/Social/Unit）与 `FacetRegistry`（两面）
- [ ] Step 3 测试：`unitsHere` 在走廊 `[1,1]` 命中 `u-1`；`population` 取值 = `PopulationSeries.valueAt`（**对拍**：与直接调领域 API 逐值相等）；`QueryService` 的 resolve/facets/未注册名失败
- [ ] Step 4 变异（R6/R7）：转发时改一个字段（R6）；注册表摘掉一个面（R7）
- [ ] Step 5 定向 + 报告 `t3-report.md`

---

### T8: GUI 服务器与 `/api`（spec §8.1/8.2；★ WebUI 出形 1/2）

**Files**：New `simos-app/src/main/java/io/mosire/simos/app/gui/{GuiServer,StaticHandler}.java` + 各 handler；Test `GuiApiTest`

- [ ] Step 1 JDK `HttpServer`（虚拟线程 executor、端口 0 可读回）+ 静态资源（classpath `/webui/...`，无缓存头策略注记）
- [ ] Step 2 `/api/state|resolve|facets|map/overview|map/hex|units|unit/{id}|social/population`（全部经 `QueryService`）
- [ ] Step 3 写端点 `/api/command|advance|fork`（`initiator=player:gui`；`commandId=correlationId=新 UUID`）
- [ ] Step 4 审批代理端点（`/api/approvals*` → 5713；**T6 未接入时**返回 503，接入后透传）
- [ ] Step 5 测试：只读端点逐值（对拍 QueryService）；写端点经 `CoreSimos`（R1 行为面）；**扫描护栏 R1**（`io.mosire.simos.app` 不出现 `SqliteStore`/`Timeline`/`CheckpointStore`）+ 变异（加一行 `SqliteStore.open` ⇒ 红）
- [ ] Step 6 定向 + 报告 `t8-report.md`

---

### T9: GUI 前端三页（spec §8.3；★ WebUI 出形 2/2）

**Files**：New `simos-app/src/main/resources/webui/{index.html,map.html,unit.html,social.html,app.js,api.js,styles.css}`；手工验收记录

- [ ] Step 1 先**估规模**（旧 render/hex-math 面向 `MapData`，适配 simos 读取模型的改动量）；超预算则降级为"网格色块 + 单位标记"最小只读图，并记取代说明
- [ ] Step 2 `/map`：Canvas 只读渲染（地形/路径/单位标记）+ 点击查询（`/api/facets`）
- [ ] Step 3 `/unit`：列表 + 详情 + 8 命令表单（提交 `/api/command`）
- [ ] Step 4 `/social`：格人口取值 + 简表；首页含待批计数（轮询 `/api/approvals`）
- [ ] Step 5 手工验收：起壳 → 浏览器三页走查（截图留证）→ 记录到 `t9-evidence/manual-qa.md`
- [ ] Step 6 报告 `t9-report.md`

---

### T5: 工具集（spec §7.1）

**Files**：New `simos-app/src/main/java/io/mosire/simos/app/tools/{SimosToolSource,读工具×9,写工具×3}.java`；Test `SimosToolsTest`

- [ ] Step 1 只读工具 9 条（经 `QueryService`；spec/gate/resources 照 §7.1 表）
- [ ] Step 2 写工具 3 条（`CommandEnvelope`/`AdvanceTime`/`ForkBranch` → `CoreSimos.submit`；`ToolResult` 折叠；`sensitive=true` + `Ask`）
- [ ] Step 3 `SimosToolSource`（id `"simos"`）+ `Shell` 经 `McpSourceBridge.bind` 注册
- [ ] Step 4 测试：catalog 与注册面一致（R5 载体）；身份注入 `agent:…`（R4）；`noExport` 内部工具不外发（R2 载体）
- [ ] Step 5 变异：某 type 返回 unsupported（R5）；initiator 写死（R4）
- [ ] Step 6 报告 `t5-report.md`

---

### T6: 审批装配（spec §7.3/S5）

**Files**：`Shell` 增审批链；Test `ShellApprovalTest`

- [ ] Step 1 `PendingApprovals` → `HttpApprovalChannel` → `Coordinator`（`AutoApproveGate`→`ConfirmGate`）→ `ToolCallAuthorizer.of(guard, coordinator)`（**非** `standard()`）
- [ ] Step 2 `ApprovalHttpEndpoint.start(approvalPort, …)` 成功后 `markUp()`；T8 代理端点接上
- [ ] Step 3 测试：写工具未批 ⇒ `APPROVAL_DENIED` 且**无 revision**（R3 载体：先拒后批再放行）；审批端点 200/404/409 透传
- [ ] Step 4 变异：写工具 gate `Ask→Allow` ⇒ "未审批也执行"用例红（R3）
- [ ] Step 5 报告 `t6-report.md`

---

### T7: MCP 服务装配（spec §7.2；判据②的前置）

**Files**：`Shell` 增 `startHttp`；Test `McpServerTest`（**官方 SDK 客户端**）；`AgentLibAvailabilityTest` 扩钉三新类

- [ ] Step 1 `startHttp("127.0.0.1", mcpPort, mcpPath, registry, …, caller, authorizer)`；`boundPort()` 读回
- [ ] Step 2 **先做实测项**：官方 `HttpClientStreamableHttpTransport` 客户端 ↔ 本服务端跑通 `initialize → tools/list → tools/call`
- [ ] Step 3 端到端：`tools/list` 无 `noExport` 工具；写调用触发审批（直批）后生效
- [ ] Step 4 报告 `t7-report.md`

---

### T10: AgentBinding（spec §6）

**Files**：New `simos-util/.../util/spi/AgentAttachPolicy.java`；`simos-map/.../spi/MapAgentAttachPolicy.java`；`simos-unit/.../spi/UnitAgentAttachPolicy.java`；`simos-app/.../binding/{AgentBinding,BindingId,AgentId,DecisionScope,BindingMode,BindingRegistry}.java`；Test `BindingRegistryTest`

- [ ] Step 1 `AgentAttachPolicy` + 两条模块实现（map：Region 且 type=Nation；unit：全部 Unit）
- [ ] Step 2 记录族 + `BindingRegistry`（canonical 化 → 问 policy → 拒绝语义）
- [ ] Step 3 测试 + 变异：跳过策略检查（R8）
- [ ] Step 4 报告 `t10-report.md`

---

### T11: 判据端到端（spec §10）

**Files**：New `simos-app/src/test/java/.../{ShellEndToEndTest,McpCoverageTest}.java`

- [ ] Step 1 ①：GUI 与 MCP 两路写 → 同一 `revisions` 表、initiator 可辨、事件链齐全（R1 行为面）
- [ ] Step 2 ②：catalog 全类型逐一经 MCP 提交生效（含 advance/fork）；反向：坏载荷 ⇒ `Rejected` 不留 revision
- [ ] Step 3 R9 生命周期：`close()` 后三端口释放、无停驻线程 + 变异（去掉一次 stop ⇒ 红）
- [ ] Step 4 全量 `clean verify` + 报告 `t11-report.md`

---

### T12: M5 关账（spec §一）

- [ ] Step 1 全量门禁（rc=0、七模块、BugInstance ×7?、ERROR 0）
- [ ] Step 2 判据 ①② 逐条实测值
- [ ] Step 3 护栏 R1~R9 点验（存量/未自证如实存档）
- [ ] Step 4 `CLAUDE.md` 状态行 + 模块表（第七模块）+ 推送状态
- [ ] Step 5 报告 `task-12-final-report.md` + 台账 + 提交推送

---

## 三 判据落点

| 判据 | 用例 | 实测值要求 |
|---|---|---|
| ① 同一 Command 路径 | `ShellEndToEndTest` | 两路写各 1 行 revision；initiator 逐字；事件链类型序列 |
| ② MCP 达任意合法状态 | `McpCoverageTest` | catalog 类型数 + 逐类提交结局（全 Committed）；坏载荷 Rejected 且 revisions 不变 |

## 四 执行期取代说明汇总（待回填）

| 任务 | 计划原文所在 | 取代后写法（一句） | 详情出处 |
|---|---|---|---|
| （待回填） | | | |
