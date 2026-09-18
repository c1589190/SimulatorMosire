# M5 T7 报告：MCP 服务装配（startHttp(5715) + 官方 SDK 客户端端到端 + 审批经 MCP）

> 分支 `m5/t7`（worktree `.claude/worktrees/m5t7`），基线 `297fd59`（Batch E-2 关账）。
> 交付：把 T5 的 `toolRegistry()` 与 T6 的 `toolAuthorizer()` 交给 AgentLib 的 `AgentToMcpServer.startHttp`，用**官方 MCP SDK 客户端**端到端验证；三新类进 `AgentLibAvailabilityTest`。
> 范围：仅 `simos-app/**`（`Shell` / 新 `McpServerTest` / 证据与报告）+ `simos-core/src/test/.../AgentLibAvailabilityTest.java`。

## 一 交付物

| # | 文件 | 内容 |
|---|---|---|
| 1 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | spec §3.2 第 6 步：`AgentToMcpServer.startHttp("127.0.0.1", config.mcpPort(), config.mcpPath(), toolRegistry, "simos-shell", "0.1.0-SNAPSHOT", mcpCaller(), toolAuthorizer())`，排在工具桥（第 5 步）之后、GUI（第 7 步）之前；新增 `boundMcpPort()`（读 AgentLib `boundPort()`）与 `mcpCaller()`（桶选择见 §四）。`close()` 次序 = GUI → **MCP** → 工具桥 → 审批端点 → 审批通道 → CoreSimos（spec §3.3）。MCP 绑定失败 ⇒ 回滚已起的审批端点/通道；GUI 绑定失败 ⇒ 回滚 MCP + 审批端点/通道。 |
| 2 | `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java` | **新**：官方 SDK 客户端（`McpClient.sync` + `HttpClientStreamableHttpTransport`）经真 socket——`initialize`（serverInfo=`simos-shell`）/ `tools/list` 恰好 12 条（exact-set，R2 的 M5 形态）/ `simos.unit.list` 与 `simos.map.hex` 与 `QueryService` 逐值对拍 / `simos.command.submit` 经审批（DENY ⇒ `APPROVAL_DENIED` 且无 revision；APPROVE_ONCE ⇒ 提交、`initiator=config.mcpInitiator`（R4 端到端）、经 MCP 读回改名生效）。 |
| 3 | `simos-core/src/test/java/io/mosire/simos/core/AgentLibAvailabilityTest.java` | `@ValueSource` 扩钉三新类（spec §2.2）：`io.mosire.agentlib.mcp.AgentToMcpServer`、`io.mosire.agentlib.approval.ApprovalHttpEndpoint`、`io.mosire.agentlib.approval.HttpApprovalChannel`。`MIN_EXPECTED_CLASSES=118` 保持下界。 |
| 4 | `.superpowers/sdd/2026-09-19-shell-simos-plan/t7-evidence/` | 绿 + 定向 + 全量 + 变异轮日志（含自指 md5）、变异体与原件、`mut-round.sh` |
| 5 | `.superpowers/sdd/2026-09-19-shell-simos-plan/t7-report.md` | 本报告 |

**未改**：工具业务逻辑 / `GuiServer` / `ShellMain` / `ShellConfig` / spec / plan / 台账 / 其它模块。

## 二 实测数字

- **定向**（`-pl simos-app,simos-core -am -Dtest='McpServerTest,AgentLibAvailabilityTest,ShellApprovalTest'`）：**rc=0**。
  - CoreSimos：`AgentLibAvailabilityTest` **18** 条（扩钉前 15，+3 个被钉类各一条参数化实例）。
  - SimosApp：`ShellApprovalTest` **3** 条 + `McpServerTest` **4** 条 = **7** 条。
  - 日志 `t7-evidence/logs/targeted.log`（rc=0，两模块 Results 全绿）。
- **全量** `./mvnw clean verify`：**rc=0**，**822 条 = 170/255/45/131/153/68**，7 个 reactor 项全 SUCCESS，`BugInstance size is 0` ×**6**，`[ERROR]` **0** 行。日志 `t7-evidence/logs/full-verify.log`（Finished 2026-09-19T06:30:49）。
  - 与基线 **815 = 170/255/45/131/150/64** 逐模块对差：前四个模块一字未动；core 150 → **153** 恰 **+3**（`AgentLibAvailabilityTest` 三个新钉类）；app 64 → **68** 恰 **+4**（新 `McpServerTest`）。**Δ = +7**。
- **SpotBugs**：全 6 个带插件的模块 `BugInstance size is 0`（含 simos-app，新代码未引入 `EI_EXPOSE_REP` / `NP_*`）。

## 三 变异自证（九道门禁；m1/m2 同打 `Shell.java`）

| m | 护栏 | 变异（`Shell.java`） | 期望 | 实测 |
|---|---|---|---|---|
| m1 | **R3 via MCP** | `startHttp(..., mcpCaller(), toolAuthorizer)` → 末参换 `ToolCallAuthorizer.standard()`（绕过审批） | MCP 写调用不再进审批 | **杀死**：`McpServerTest.writeToolBlocksOnApprovalThenCommitsWithConfiguredInitiator:232`，红在「R3 via MCP：写工具必须先进审批（MCP 客户端调它不得绕过审批闸）——未出现待审批项即为闸门/身份桶失效」 |
| m2 | **caller 桶** | `mcpCaller()` 的 `AccessToken.DEFAULT` → `AccessToken.GUEST`（低于写工具要求的 `DEFAULT`） | 硬拒先于审批 ⇒ 同样进不了审批 | **杀死**：同一用例同一条断言 `:232`（写调用被 `PermissionChecker` 硬拒，`pending` 不出现） |

**九道门禁逐条**（日志 `t7-evidence/logs/m1.log` / `m2.log`，均通过装置尾部断言）：

1. 干净世界：`orig_md5 = worktree_before`（m1/m2 均 `6633a34dfc7ea9f1cad49fc67f0d0c2f`）；
2. 字节不同：m1 `mutant_md5=51aa9f44…`、m2 `mutant_md5=6a8142fa…`；
3. 白名单推成**目标类名**：`cp m1.Shell.java → …/app/Shell.java`（按目标路径，非变异体文件名）；
4. 删陈旧 `Shell.class` 与旧 `McpServerTest.txt` 逼本轮重编重跑；
5. `COMPILATION ERROR` = **0** 且 `Tests run:` ≥ 1（实测 2 行、1 条用例）；
6. surefire 报告 mtime 落在本轮：m1 `1789770595 ≥ 1789770591`、m2 `1789770603 ≥ 1789770599`；
7. 红点落位：见上表，正是被保护的 R3-via-MCP 待审批断言；
8. 逐字节还原用 `cp`（**绝不** `git checkout --`）：两轮 `worktree_restored = 6633a34d… = orig_md5`；
9. 装置补记：三处 md5 **追加进 m1.log / m2.log 本身**（日志自指）。

**为什么两条都红在同一条断言**：`awaitPendingId` 的判据是"写调用**阻塞在审批闸**上"。m1 下 `standard()` 的 `coordinator==null`，`ToolCallAuthorizer.askForApproval`（AgentLib 源码）对 `ToolGate.Ask` **fail-closed** 直接返回 `APPROVAL_DENIED`，调用立即结束、不登记 ⇒ `pending` 空；m2 下 `PermissionChecker.denialReason`（AgentLib 源码）在 `grantedToken.atLeast(requiredLevel)` 处对 `GUEST < DEFAULT` 返回「身份级别不足」⇒ `PERMISSION_DENIED`，调用在**审批之前**就终局 ⇒ `pending` 空。两条机制不同，但都由"呼叫者的桶/authorizer 选择"这一被保护的行决定；wire 层错误码本身**未在日志中取证**（见 §五）。

## 四 偏离 / 取代说明候选

1. **MCP caller 桶取 `AccessToken.DEFAULT`，不是 spec §3.2 字面的 `GUEST`**。三条写工具的 `ToolSpec` 是 `ToolSpec.level(DEFAULT, sensitive=true, destructive=false)`，`PermissionChecker` 对级别不足**硬拒**（不进审批）⇒ `GUEST` 桶下 12 条工具里 3 条写工具全部不可达，MCP 只能读、不能写，与 S3/S4 的工具面设计矛盾。`DEFAULT` 是**满足全部 12 条工具的最小桶**；权限集用 `AgentPermissionSet.unrestricted(DEFAULT)`（显式开 `sensitive`）。已在 `Shell.mcpCaller()` javadoc 逐条记明。**放行 ≠ 免审批**：authorizer 仍是带 `ApprovalCoordinator` 的那个。
2. **caller 身份 `AgentIdentity.external()`**（`external-mcp` + `FULL`），与 spec §3.2 一致。注意 AgentLib 审批 `callerKey` 取的是**桶名**（`DEFAULT`），不是 identity 实例 id——T6 裁定 63 已实测，本报告沿用。
3. **R2 存档（M5 无内部工具）**：M5 的 12 条工具 `noExport()` 全为 `false`（T5 `SimosToolsTest.writesAreSensitiveAndAskWithTheToolNameAsClassKey` 已钉）。按派单指示**不发明**一个内部工具；`McpServerTest` 用 `tools/list` 的 **exact-set == 12** 断言；**R2 记为「M5 无内部工具 ⇒ 不适用/未自证」**（spec §11 允许存档）。
4. **装配次序照 spec §3.2（tools → MCP(6) → GUI(7)）**：MCP 排在 GUI 之前，故 GUI 绑定失败的回滚新增 `mcpServer.close()`；MCP 绑定失败的回滚关审批端点/通道。`close()` 新增 MCP 一项，次序 = GUI → MCP → 工具桥 → 审批端点 → 审批通道 → CoreSimos（工具桥是 app 内注册表卸载，插在 MCP 与审批之间不改端口/线程释放次序）。
5. **`ShellMain` 未改**（不在派单文件集）：启动日志打印 `config.mcpPort()` 而非 `shell.boundMcpPort()`；缺省 `5715` 与实际一致，`port=0` 时不一致（记录在案，同 T6 对 approvalPort 的处置）。
6. **`AgentToMcpServer.startHttp` 的 caller-owned 形态（注入 `HttpServer`）未用**：spec §7.2 点名 owned 形态，本任务照用（owned `close()` 会优雅收会话 + 停 server + 关自建 executor）。

## 五 我未能核实的

- **R2 未经"去掉 `noExport`"变异自证**：M5 无内部工具，本任务按派单指示存档（exact-set 断言是它当前的 M5 形态，不是 AgentLib `noExport` 机制的判别力证明；后者由 AgentLib 自己的 `AgentToMcpServerNoExportTest` 钉）。
- **m1/m2 下写调用返回的 wire 错误码未直接取证**：两轮的红点落在"未出现待审批项"断言（在读取 `CallToolResult` 之前）；`APPROVAL_DENIED`（m1）与 `PERMISSION_DENIED`（m2）系据 AgentLib 源码（`ToolCallAuthorizer.askForApproval` / `PermissionChecker.denialReason`）判定，**不是**从日志读到的。
- **R9 生命周期**（`close()` 后三端口释放、无停驻线程）未在本任务断言：`@AfterEach` 关了 client + shell 使用例可重复，但没有"端口释放/线程停驻"的判别性断言——归 T11。
- **MCP 协议面未验**：resumability 关（spec §7.2 明记不做）、协议版本协商未断言（实测协商为 `2025-11-25`，只出现在日志）、`tools.listChanged` 广播未验。
- **并发**：每个用例只有一条在飞的工具调用；`McpSyncClient` 并发调用、多条写并发触发审批未测。
- **GUI 侧身份**（`player:gui`，R4 的另一半）不在本任务范围（T8/T11）。
- **`initialize` 之后的 GET 监听流行为**未单独断言（客户端 `resumableStreams(false)`，SDK 自行退化）。

## 六 证据索引

| 路径 | 内容 |
|---|---|
| `t7-evidence/logs/targeted.log` | 定向 3 类（core 18 + app 7）绿（rc=0） |
| `t7-evidence/logs/full-verify.log` | `clean verify` 全绿（rc=0、822、BugInstance 0 ×6、ERROR 0） |
| `t7-evidence/logs/m1.log` | 变异轮 m1：红点 + 九道门禁 + 装置自指 md5 |
| `t7-evidence/logs/m2.log` | 变异轮 m2：红点 + 九道门禁 + 装置自指 md5 |
| `t7-evidence/mutants/mut-round.sh` | 变异装置（九道门禁，ROOT=m5t7，EV=t7-evidence） |
| `t7-evidence/mutants/orig/Shell.java` | 原件备份（`6633a34d…`） |
| `t7-evidence/mutants/m1.Shell.java` | 变异体①（`51aa9f44…`，`standard()`） |
| `t7-evidence/mutants/m2.Shell.java` | 变异体②（`6a8142fa…`，`GUEST`） |
