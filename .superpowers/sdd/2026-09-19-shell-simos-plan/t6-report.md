# M5 T6 报告：审批装配（R3 载体）+ 5711 审批代理落地

> 分支 `m5/t6`（worktree `.claude/worktrees/m5t6`），基线 `4197048`（Batch E-1 关账）。
> 交付：把 AgentLib 审批链接进 `Shell`，并把 T8 留在 `GuiServer` 的 503/501 接缝换成真代理。
> 范围：仅 `simos-app/**`（`Shell` / `GuiServer` / 新测试 / `GuiApiTest` 调整 / 证据与报告）。

## 一 交付物

| # | 文件 | 内容 |
|---|---|---|
| 1 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | 审批链装配（spec §3.2 第 3~4 步）：`new PendingApprovals()` → `new HttpApprovalChannel(pending)` → `new ApprovalCoordinator(List.of(new AutoApproveGate(pending), new ConfirmGate()), List.of(channel), pending, 5min, null)` → `ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator)`（**非** `standard()`）→ `ApprovalHttpEndpoint.start(config.approvalPort(), pending, coordinator)` 成功后才 `channel.markUp()`；GUI 的审批 base URL 注入为 `http://127.0.0.1:<endpoint.boundPort()>`；`close()` 次序 = GUI → 工具桥 → **审批端点 → 审批通道** → CoreSimos（MCP 归 T7）；新增 `toolAuthorizer()` / `pendingApprovals()` / `boundApprovalPort()` |
| 2 | `simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java` | 删 503/501 接缝；`/api/approvals` 与 `/api/approvals/{id}` 在路由最前面**原样透传**给 AgentLib 端点（方法/路径/体照转，状态码/体/`Content-Type`/`Allow` 照回），端点不可达 ⇒ 502。不复制任何审批语义 |
| 3 | `simos-app/src/test/java/io/mosire/simos/app/ShellApprovalTest.java` | **新**：R3 守卫（DENY ⇒ `APPROVAL_DENIED` 且无 revision；APPROVE_ONCE ⇒ 提交、revision 前进到 2、独立 store 读回 initiator）+ 代理透传（列表 200 / 决议 200+scope 收窄 / 重复 409 / 未知 404 / 错方法 405+Allow）+ 502（直连不可达端点） |
| 4 | `simos-app/src/test/java/io/mosire/simos/app/gui/GuiApiTest.java` | 原 `approvalsReturn503WhenT6NotWired` → `approvalsProxyForwardsToTheLiveAgentLibEndpoint`（真代理结果：空列表 200、未知 id 404、错方法 405+Allow） |
| 5 | `.superpowers/sdd/2026-09-19-shell-simos-plan/t6-evidence/` | 绿 + 定向 + 全量 + 变异轮日志（含自指 md5）、变异体与原件、`mut-round.sh` |
| 6 | `.superpowers/sdd/2026-09-19-shell-simos-plan/t6-report.md` | 本报告 |

**未改**：`ShellMain` / `ShellConfig` / spec / plan / 台账 / 其它模块 / 工具业务逻辑。

## 二 实测数字

- **定向**（`ShellApprovalTest,GuiApiTest,SimosToolsTest,ShellSmokeTest`）：**30** 条全绿
  （ShellSmokeTest 4 + ShellApprovalTest 3 + SimosToolsTest 11 + GuiApiTest 12）。日志 `t6-evidence/logs/targeted.log`，rc=0。
- **全量** `./mvnw clean verify`：**rc=0**，**815 条 = 170/255/45/131/150/64**，7 个 reactor 项全 SUCCESS，`BugInstance size is 0` ×**6**，`[ERROR]` **0** 行。日志 `t6-evidence/logs/full-verify.log`。
  - 与基线 **812 = 170/255/45/131/150/61** 逐模块对差：前五个模块一字未动，app 61 → 64 恰 **+3** = 新增 `ShellApprovalTest`（`GuiApiTest` 是改写既有用例，计数不变）。
- **SpotBugs**：全模块 0。过程中唯一两次红皆在 `simos-app`，均当场修正（见 §四 偏离 2、3）。

## 三 变异自证（九道门禁）

| m | 护栏 | 变异 | 期望 | 实测 |
|---|---|---|---|---|
| m1 | **R3 审批闸承重** | `CommandSubmitTool.gate(...)` 由 `Ask` 改为 `ToolGate.ALLOW` | 未审批的写不得执行 | **杀死**：`ShellApprovalTest.unapprovedWriteIsDeniedAndApprovedWriteCommits:141`，红在 R3 断言「R3：写工具必须先进审批（不得未经审批就执行）——未出现待审批项即为闸门失效」；连带 `approvalProxyForwardsListDecisionConflictAndUnknown:178` 亦红（同因：写调用不再登记） |

**九道门禁逐条**（`t6-evidence/logs/m1.log`）：

1. 干净世界：`orig_md5 = worktree_before = 477f05fc…`；
2. 字节不同：`mutant_md5 = a5dbe15b…`；
3. 白名单推成**目标类名**：`cp m1.CommandSubmitTool.java → …/write/CommandSubmitTool.java`（按目标路径，非按变异体文件名）；
4. 删陈旧 `.class` 与旧 surefire 报告逼本轮重编重跑；
5. `COMPILATION ERROR` = **0** 且 `Tests run:` ≥ 1（实测 2 行，3 条用例）；
6. surefire 报告 mtime `1789770120` ≥ round_start `1789770116`（落在本轮内）；
7. 红点落位：见上表，正是被保护的 R3 断言；
8. 逐字节还原用 `cp`（**绝不** `git checkout --`）：`worktree_restored = 477f05fc… = orig_md5`，且 `git diff` 对目标文件无输出；
9. 装置补记：三处 md5 **追加进 m1.log 本身**（日志自指）。

## 四 偏离 / 取代说明候选

1. **`callerKey` 是 `AccessToken` 桶名，不是 spec §九 的 initiator 串**。派单文字写「`APPROVE_SESSION` for our caller (`player:gui`) is downgraded」，但 AgentLib `ToolCallAuthorizer.askForApproval` 取的是 `context.caller().name()` = `GUEST`/`DEFAULT`/`SYSTEM`，**给不出 `player:gui`**。本用例调用者桶为 `DEFAULT`，代理回执 `scope=="once"` 即其收窄实测。已在测试类 javadoc 写清；不构成本仓代码偏离，但 spec/计划若有「审批按 `player:gui` 归类」的措辞需校正。
2. **`pendingApprovals()` 的 `EI_EXPOSE_REP` 抑制是不必要的**（SpotBugs 实测：加了反被 `US_USELESS_SUPPRESSION_ON_METHOD` 判红）⇒ 已撤，并在方法 javadoc 记下这条实测。**不能**据此推断「`PendingApprovals` 是不可变类」——只能说 SpotBugs 在当前类集下不判它 `EI_EXPOSE_REP`（形态 6：分析器判定不是被分析文件的纯函数）。
3. **GUI 绑定失败的审批端点回滚不能写 `catch (RuntimeException e) { … throw e; }`**：显式 `throw` 触发 SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`（`start` 第 234 行）⇒ 改为 `boolean guiUp` + `finally { if (!guiUp) { endpoint.close(); channel.close(); } }`，异常自然外抛、无显式 `throw`。
4. **审批等待上限取 5 分钟**：spec 未定值；`APPROVAL_TIMEOUT = Duration.ofMinutes(5)`。到点 fail-closed 归 AgentLib（不在本仓实现）。
5. **代理在路由最前拦截 `/api/approvals*`**，`GET_ROUTES` 去掉 `/api/approvals`，删 `APPROVAL_DECISION_PREFIX` / `isApprovalDecision` / `approvalSeam`。审批面的 404/405/409 全由 AgentLib 端点判定（透传）；代理**不**用 Jackson 重序列化响应体，走原始字节 + 复制 `Content-Type`/`Allow`，以兑现「状态码/体透传」。
6. **额外暴露 `pendingApprovals()` 与 `boundApprovalPort()`**（派单只点名 `toolAuthorizer()`）：前者是 R3 用例「`pending.pending()`/`pending.decide(...)`」的必需入口，后者是 spec §3.1 的端口读回口径。`ShellMain` **未改**（不在派单列出的文件集内）——其启动日志仍打印 `config.approvalPort()`；缺省 `5713` 与实际绑定一致，`port=0` 时不一致（记录在案）。
7. **审批事件不桥接**：`ApprovalCoordinator` 的 bus 传 `null`（spec §九 已定「M5 先不桥接」）。

## 五 我未能核实的

- **R3 守卫走的是 `shell.toolAuthorizer().execute(...)` 直调**，不是 T7 的 MCP 传输路径（`AgentToMcpServer.startHttp` + 官方 SDK 客户端）；「MCP 入口经同一 authorizer」要到 T7 才验。
- **`toolAuthorizer()` 尚未被 T7 消费**，`startHttp` 端到端未跑。
- **GUI 绑定失败的 `finally` 回滚分支无专项用例**（无测试构造一次失败的 `guiServer.start`）；该分支经编译与全量门禁，但未行为自证。
- **`GuiServer` base URL 为空时的 503 分支无用例**：`Shell` 恒注入真 URL，503 只在直接构造 `GuiServer(..., null)` 时可达（T8 遗留分支，未清理）。
- **5 分钟超时的实际到点 DENY 未跑**（用例都在窗口内作答）；超时语义归 AgentLib 既有测试。
- **`APPROVE_SESSION` 真正落会话键的路径未跑**（需 `callerKey=="SYSTEM"`）；本用例只证「DEFAULT 桶被收窄」这一半。
- **SpotBugs 对 `PendingApprovals` 不报 `EI_EXPOSE_REP`** 的具体判据未查（只观测到结果）。
- **代理对 502 的判定是 `IOException`/`InterruptedException` 捕获**；连接超时未设（`HttpClient` 缺省无超时），回环拒连即时返回，跨机不可达的慢超时形态未测。

## 六 证据索引

| 路径 | 内容 |
|---|---|
| `t6-evidence/logs/targeted.log` | 定向 4 类 30 条绿（rc=0） |
| `t6-evidence/logs/full-verify.log` | `clean verify` 全绿（rc=0、815、BugInstance 0 ×6、ERROR 0） |
| `t6-evidence/logs/m1.log` | 变异轮 m1：红点 + 九道门禁 + 装置自指 md5 |
| `t6-evidence/mutants/mut-round.sh` | 变异装置（九道门禁，ROOT=m5t6，EV=t6-evidence） |
| `t6-evidence/mutants/orig/CommandSubmitTool.java` | 原件备份（`477f05fc…`） |
| `t6-evidence/mutants/m1.CommandSubmitTool.java` | 变异体（`a5dbe15b…`） |
