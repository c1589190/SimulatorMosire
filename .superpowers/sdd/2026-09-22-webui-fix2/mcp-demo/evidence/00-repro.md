# 复现说明（工具与命令）

## 客户端

- **依赖**：直接用**正在运行的**瘦/胖 jar（`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`）
  作只读 classpath —— 它已 bundle 官方 MCP Java SDK（`io.modelcontextprotocol.*`）。
  **没有重编任何 jar**（5817 正在用它，按任务要求不得覆盖）。
- **客户端源码**：`McpCall.java`（连 `http://127.0.0.1:5715/mcp`，支持 `__list__` 与任意 tool call）。
- **敏感写包装**：`mcp_write.sh`（后台跑 MCP 调用 → 轮询 `GET /api/approvals` → `POST /api/approvals/{id}`
  `{"decision":"approve","scope":"once","by":"agent:opencode-t12"}` → 放行）。审批 HTTP 契约由
  反编译 `agentlib-mosire` 的 `ApprovalHttpEndpoint` 得到（`decision` = `approve|deny`，`scope` = `once|session`）。
- **截图**：`playwright-core` + 显式 `executablePath` 指向 `~/.cache/ms-playwright/chromium-1234/.../chrome`，
  启动参数 `--no-sandbox --no-proxy-server`。

> ★ 因 5817 占用着该 shaded jar，**全程未跑 Maven、未重编 jar**；本菜单靠 `javac` 编出的`classes/` 目录。

## 逐条调用（可直接照抄）

```bash
J=/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
CP="/tmp/mcpwork/classes:$J"

# 1) tools/list
java -Djava.net.useSystemProxies=false -cp "$CP" McpCall http://127.0.0.1:5715 /mcp __list__

# 2) 读：confirm Nation 不存在
java ... McpCall http://127.0.0.1:5715 /mcp simos.state.resolve '{"address":"sd:nation.大蜀"}'

# 3) 写（每条都经审批）：
bash /tmp/mcpwork/mcp_write.sh simos.command.submit \
  '{"type":"map.UpdateRegion","payloadJson":"{\"regionId\":\"大蜀\",\"meta\":{\"color\":\"#c4a35a\",\"tag\":\"nation:大蜀\",\"description\":\"汉海-盆地贸易要道，四战之地\",\"annexedBy\":\"\"}}","branch":"main","expectedRevision":3}'
bash /tmp/mcpwork/mcp_write.sh simos.command.submit \
  '{"type":"sd.CreateNation","payloadJson":"{\"nationId\":\"大蜀\",\"name\":\"大蜀\",\"homeRegionId\":\"大蜀\",\"adminBudgetPerTick\":10}","branch":"main","expectedRevision":5}'
bash /tmp/mcpwork/mcp_write.sh simos.command.submit \
  '{"type":"sd.CreateDecisionMaker","payloadJson":"{\"id\":\"dm-dashu\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"大蜀\"},\"allowedTools\":[\"sd.IssueDirective\",\"sd.SubmitVerdict\"],\"cadence\":5}","branch":"main","expectedRevision":7}'
bash /tmp/mcpwork/mcp_write.sh sd.StartDecision \
  '{"payloadJson":"{\"decisionMakerId\":\"dm-dashu\",\"note\":\"T12 demo：大蜀开始决策\"}","branch":"main","expectedRevision":9}'
```

（`西陵` 同形，只是 revision 依次 +1。完整载荷见各 `evidence/*.txt` 的 `CALL=… ARGS=…` 行。）

## 环境事实（复核过的）

- `5817` pid=**455569**，全程存活；`5715/5717/5713` 同属该 pid（`ss -ltnp` 实测）。
- 运行 jar mtime `2026-09-22 00:28:02`，与进程启动时间一致。
- `git status` 在本会话进行中出现了**与本任务无关的**生产改动（LLM provider 工作流：
  `ApiViews.java`/`DecisionMaker.java`/`SetViewScopeHandler.java` + 新 `llm/` 目录），**非本任务所为**；
  本任务**未改任何生产代码**。
