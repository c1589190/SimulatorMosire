# AgentLibMosire 扩展需求书（SimulatorMosire M5 输入）

> **用途**：SimulatorMosire M5（CoreSimos 外壳：AgentBinding / AgentLib 集成 / 5711 GUI / 5715 MCP）对 `AgentLibMosire` 的改动需求。
> **提出**：simos 控制器，2026-09-19。**基线实测**：`AgentLibMosire` `main @ 15b00b8`；MCP SDK **2.0.1**（`mcp-core` + `mcp-json-jackson2`，Jackson 2 线）；Java 21。
> **形态**：每条需求 = 现状（实测出处）→ 期望 API/语义 → 验收 → M5 侧兜底。★ 所有"现状"均来自对源码/构件的**当场查阅**，不是转述设计文档。

---

## 〇 结论摘要

**P0（阻塞）：无。** M5 可以用今天的 AgentLib 零改动跑通（stdio MCP + simos 自写审批渠道）。
下面按"消除已记录风险 / 降低集成成本 / 保持总纲拓扑"排序：

| # | 需求 | 建议 | 主要理由 |
|---|---|---|---|
| **P1-A** | 公开 transport 注入口 | **做** | 现在唯一的非 stdio 注入口是包私有的测试钩子 |
| **P1-B** | **JDK-HttpServer 版 Streamable HTTP server transport** | **做**（若 5715 要网络服务） | 总纲把 5715 列为独立服务；SDK 现成 HTTP 服务端全是 servlet 系，与两仓"零框架"形态不符 |
| **P1-C** | 通用 HTTP 审批渠道（或最小等价件） | **做**（成本可控时；simos 是第三个消费方） | 渠道接口在 AgentLib，实现全在两个下游仓里各写一遍 |
| P2-D | cap 硬上限语义（`ResourcePolicy` 升格） | **暂不做** | M5 场景下缺口不可达（装配期定死调用方权限即可）；真做需连 `isSubset` 一起设计 |
| P2-E | `Operation` 扩展（CONTROL/OBSERVE/OWN） | **暂不做** | M5 无消费者；扩值须先回答"哪种默认策略算读"（其 javadoc 自陈） |
| P2-F | MCP 按会话身份（per-session `ToolContext`） | **暂不做** | M5 v1 单身份够用；有消费者时再说 |

★ **唯一有实质实现量的是 P1-B**。它与 P1-A 是"5715 是网络服务还是 stdio 进程"这个二选一的两条腿：做 P1-B 则 M5 保持总纲拓扑；不做则 M5 退 stdio launcher 并记取代说明。

---

## 一 M5 要什么（自用速览）

1. 把 simos 操作包装为 `AgentTool`（只读查询 + 命令提交），**全部**经 `ToolCallAuthorizer` 单一入口执行；
2. 起 MCP 服务（5715）暴露这些工具，使 MCP 客户端**能达到任何合法状态**（M5 判据②）；
3. GUI（5711）与 Agent 改状态走**同一条** `Command → ChangeSet → Revision` 路径（M5 判据①），审批交互在 GUI 里完成；
4. AgentBinding（决策人绑定）与查询层（Facet）由 simos 自行定义 —— **不涉 AgentLib**。

---

## 二 现状实测盘点（需求基线）

| # | 事实（逐条查过） | 出处 |
|---|---|---|
| 1 | `AgentToMcpServer` 的**公开**工厂只有 stdio 形态：默认 `StdioServerTransportProvider`，或注入 `InputStream`/`OutputStream`（仍是 stdio 管道） | `src/main/java/io/mosire/agentlib/mcp/AgentToMcpServer.java` |
| 2 | 注入任意 `McpServerTransportProvider` 的 `startWith(...)` 三重载是**包私有**，javadoc 明写"供测试注入自定义 transport" | 同上（约 L187–222） |
| 3 | SDK 2.0.1 的**服务端** transport 清单：`StdioServerTransportProvider` + 三个 **servlet 系**（`HttpServletStreamableServerTransportProvider` / `HttpServletSseServerTransportProvider` / `HttpServletStatelessServerTransport`）——**没有** JDK `HttpServer` 形态 | `mcp-core-2.0.1.jar` 实测 |
| 4 | `ApprovalChannel` 在 AgentLib **无生产实现**（只有测试桩）；生产实现（`TtyApprovalChannel` / `HttpApprovalChannel` / `ApprovalHttpServer`，loopback、无鉴权）全在 **MainMosire** | AgentLib 全树 + `MainMosire/src/main/java/io/mosire/main/approval/` |
| 5 | `ResourcePolicy` 是**默认值不是封顶**：调用方显式声明 `ResourceScopeMap` 会覆盖工具声明 | `permission/ResourcePolicy.java` javadoc |
| 6 | `Operation` 只有 `READ`/`WRITE`；javadoc 明确：扩值必须同时回答"哪种默认策略算读" | `permission/Operation.java` |
| 7 | `AgentToMcpServer` 的调用者身份是**固定模板**（`ToolContext caller` 构造期绑定，客户端不可改） | `mcp/AgentToMcpServer.java` javadoc |
| 8 | `ToolSource extends ExtensionPoint`，但 `McpSourceBridge.bind(ToolSource, ToolRegistry)` 可把任意 `ToolSource` 桥进注册表 ⇒ simos **不需要** PF4J | `plugin/ToolSource.java`、`mcp/McpSourceBridge.java` |
| 9 | AgentLib 的 AGENTS.md 红线第 3 条原文：「**MCP 新代码禁用**（2026-07-28 弃用清单）：Roots、Sampling、Logging、legacy HTTP+SSE。」⇒ **Streamable HTTP 不在弃用清单里** | `~/ProjectMosire/AGENTS.md` L48 |

---

## 三 需求

### P1-A 公开 transport 注入口（建议：做；成本低）

- **现状**：事实 #1/#2。M5 想换传输（网络、或测试用管道之外的形态）唯一的路径是改 AgentLib 源码。
- **需求**：把 transport 注入升为**公开稳定 API**。二选一：
  - a) 直接把 `startWith(...)` 三重载公开（并更新 javadoc：从"供测试"改为正式 API，补 `@since`）；
  - b) 新增公名工厂，如 `AgentToMcpServer.startWithTransport(ToolRegistry, String, String, ToolContext, McpServerTransportProvider[, Predicate<String>, ToolCallAuthorizer])`。
- **验收**：一条公开 API 用例：自定义 `McpServerTransportProvider`（如既有 `PipeMcpClientTransport` 的服务端对偶）起服务，完成 `initialize → tools/list → tools/call`；`registry.onChange` 的动态同步行为与 stdio 版本一致。
- **M5 兜底**：不公开也能跑（stdio 足）。

### P1-B 【重点】JDK-HttpServer 版 Streamable HTTP server transport（建议：做）

- **现状**：事实 #3 + 总纲 §10.4 把 5715 列为"独立服务"（端口）。SDK 的 HTTP 服务端实现全是 servlet 系（要拖 servlet-api + 容器），与两个仓"JDK `com.sun.net.httpserver.HttpServer` + 零框架"的既有形态不符——旧 GSimulator 的 `McpHttpServer`（`~/DevMosire/GSimulator/gsim-agentsmanager/.../mcp/McpHttpServer.java`，JDK HttpServer + JSON-RPC）就是这条哲学的前例（传输层可参考，协议层已过时）。
- **需求**：在 AgentLib 提供 **JDK HttpServer 版 Streamable HTTP server transport**：
  - 实现 SDK 的 `io.modelcontextprotocol.spec.McpServerTransportProviderBase` / `McpStreamableServerTransport` 族接口（`spec/McpStreamableServerSession` 等在 2.0.1 已具备）；
  - 建议形态二选一：独立类 `JdkHttpServerMcpTransport`，或 `AgentToMcpServer.startHttp(HttpServer server, String path, ...)` 便捷工厂（后者对下游最省事）；
  - host 绑定默认 `127.0.0.1`（可配）；路径默认 `/mcp`；session 语义（有状态 Streamable / 或 stateless）写进 javadoc。
- **验收**：官方 MCP 客户端（或 Inspector）经 `http://127.0.0.1:5715/mcp` 完成 `initialize → tools/list → tools/call`；错误码仍走 `McpWireCode` 的 `[mosire:code=…]` 约定；`ToolSpec.noExport` 的工具不出现；`tools/call` 仍经 `ToolCallAuthorizer`（含审批路径）。
- **M5 兜底**：不做 ⇒ M5 走 **stdio 独立进程**（5715 落成 launcher），总纲拓扑改记**取代说明**。

### P1-C 通用 HTTP 审批渠道（建议：做，若成本可控）

- **现状**：事实 #4。渠道**接口**在 AgentLib、**实现**在 MainMosire；simos 若自写就是第三份。
- **需求**：把 MainMosire 的 `HttpApprovalChannel` + 最小 HTTP 端点提炼进 AgentLib（或等价可复用件）：`GET /pending`（列出待批）、`POST /decide`（人类决定）。**只做传输不做 UI**——UI 归各消费方。
- **语义要求**（照现实现固化）：`publish` 可 no-op（人类轮询模式）；`decide` **幂等**；超时 ⇒ `DENY`（`SOURCE_TIMEOUT`）；`APPROVE_SESSION` 的会话键仍受 `sessionGrantable` 约束（默认 `"SYSTEM"`）；审计事件（`approval.requested`/`approval.decided`）行为不变。
- **验收**：`pending → decide → await` 走通；重复 decide 不炸；超时 DENY；无渠道时 fail-closed 不变。
- **M5 兜底**：自写一个（~百行量级：实现 `ApprovalChannel` + 挂在 5711 的两个 JSON 端点）。

### P2-D cap 硬上限语义（建议：暂不做）

- 事实 #5。**M5 场景下缺口不可达**：MCP 服务端在**装配期**就定死调用方的 `AgentPermissionSet`/`ResourceScopeMap`（simos 自己装配），没有"调用方显式放宽"的对手方 ⇒ 工具默认值被覆盖的风险不存在。
- 若将来要做：语义（"默认"与"上限"如何区分、effective = caller ∩ toolCap 还是别的）必须与 `PermissionChecker.isSubset` **一起设计**（其 javadoc 已点名），并配故意违规自证。M5 不依赖它。

### P2-E `Operation` 扩展（建议：暂不做）

- 事实 #6。M5 全部工具用 READ（查询）/WRITE（命令提交）即可表达；`CONTROL`/`OBSERVE`/`OWN` **没有消费者**——按两仓同源纪律"无消费者的枚举是死代码"。真要做时，先按 `Operation` javadoc 回答"哪种默认策略算读"，并同步 `ResourceAuthorizer` 判定表 + 用例。

### P2-F MCP 按会话身份（建议：暂不做）

- 事实 #7。M5 v1 只有一个 MCP 身份（如 `agent:external-mcp`，装配期固定）。多客户端、各自权限面等需求出现时再设计（形态大概率是"会话 → `ToolContext` 解析函数"）。

---

## 四 兼容性硬约束（M5 依赖面不许破）

1. **只做新增，不改现有 public API 的签名与包名**——M5 直接消费的清单见附录 A。特别地：`AgentTool` / `AgentToMcpServer` 现有 `start(...)` 重载 / `ToolCallAuthorizer.execute` / `ApprovalCoordinator.decide` / `PendingApprovals` 的全部公开方法。
2. **继续用 `mcp-core` + `mcp-json-jackson2`**（Jackson 2 线）。**别切** aggregate `mcp` 构件（它带 Jackson 3，与 simos 的 Jackson 2.22 / jackson-bom 冲突）。
3. 改完请在 ProjectMosire 侧 `./mvnw -pl AgentLibMosire -am install`（SNAPSHOT 语义；simos 暂不升固定版本——用户已裁决）。simos 的 `AgentLibAvailabilityTest` 钉「13 个类可加载 + JAR 类数 **≥118**」——是**下限**，新增类不受影响；**被钉类若改名/挪包会红**（这是特性）。
4. 新 API 请附用例；若动了判定逻辑（如将来做 P2-D），按同源纪律配**故意违规自证**（G13：删被保护行 ⇒ 用例必须红）。

---

## 五 附录 A：M5 将直接消费的 public API（兼容锚点）

| 包 | 类型 |
|---|---|
| `io.mosire.agentlib.tool` | `AgentTool`、`ToolRegistry`、`ToolContext`、`ToolResult`、`ToolCallAuthorizer`、`ToolExecutionGuard`、`Digest` |
| `io.mosire.agentlib.plugin` | `ToolSource`、`HostServices`、`PluginListener`（可选）、`BuiltinToolSource`（可选） |
| `io.mosire.agentlib.permission` | `Operation`、`ResourceId`、`ResourceScope`、`ResourceScopeMap`、`ResourcePolicy`、`ResourceManifest`、`ResourceAuthorizer`、`AgentPermissionSet`、`ToolSpec`、`AccessToken`、`AgentIdentity`、`CommandMode`、`ResourceDeniedException` |
| `io.mosire.agentlib.approval` | `ApprovalCoordinator`、`ApprovalChannel`、`ApprovalRequest`、`ApprovalDecision`、`AskKind`、`ToolGate`、`ApprovalGate`、`PendingApprovals`、`ApprovalConfig`、`ApprovalConfigLoader`、`AutoApproveGate`、`ConfirmGate`、`SuperiorJudgeGate`（可选）、`SuperiorJudgement`/`LlmSuperiorJudgement`（可选） |
| `io.mosire.agentlib.mcp` | `AgentToMcpServer`、`McpSourceBridge`（可选，用于把 simos 的 `ToolSource` 桥进注册表） |
| `io.mosire.agentlib.event` | `EventBus`、`SqliteEventStore`（可选，审计） |

## 六 附录 B：本需求书的证据来源（均为当场查阅）

- `AgentLibMosire` 源码：`mcp/AgentToMcpServer.java`、`mcp/McpSourceBridge.java`、`plugin/ToolSource.java`、`permission/Operation.java`、`permission/ResourcePolicy.java`、`permission/ResourceScope.java`、`tool/ToolCallAuthorizer.java`、`tool/ToolContext.java`、`tool/ToolResult.java`、`approval/*`（接口族）——`main @ 15b00b8`。
- `~/ProjectMosire/AGENTS.md`（红线第 3 条原文）。
- 构件实测：`~/.m2/repository/io/modelcontextprotocol/sdk/mcp-bom/2.0.1/mcp-bom-2.0.1.pom`（artifact 清单）、`mcp-core-2.0.1.jar`（server transport 清单）、`mcp-json-jackson2-2.0.1.jar`。
- `MainMosire`：`io/mosire/main/approval/{HttpApprovalChannel,TtyApprovalChannel,ApprovalHttpServer}.java`（审批实现所在）。
- 参考前例：`~/DevMosire/GSimulator/gsim-agentsmanager/.../mcp/McpHttpServer.java`（JDK HttpServer 承载 MCP 的旧形态）。
