# ① `tools/list` —— 5715 端口真实挂的工具清单

**连接方式**：官方 MCP Java SDK 客户端（`io.modelcontextprotocol.client.McpClient` +
`HttpClientStreamableHttpTransport`），连 `http://127.0.0.1:5715/mcp`。原始输出见
`evidence/01-tools-list-5715.txt`。

- `SERVER=simos-shell v0.1.0-SNAPSHOT`
- `PROTOCOL=2025-11-25`
- `TOOL_COUNT=16`

| # | 工具名 | 类 | 说明 |
|---|--------|----|------|
| 1 | `simos.command.catalog` | 读 | 已注册命令类型 + 载荷提示 |
| 2 | `simos.state.resolve` | 读 | 地址 → 实体 |
| 3 | `simos.state.facets` | 读 | 地址 → facet |
| 4 | `simos.timeline.branches` | 读 | 分支列表 |
| 5 | `simos.map.overview` | 读 | 地图总览 |
| 6 | `simos.map.hex` | 读 | 单格详情 |
| 7 | `simos.unit.list` | 读 | 单位列表 |
| 8 | `simos.unit.get` | 读 | 单位详情 |
| 9 | `simos.social.population` | 读 | 人口 |
| 10 | `simos.command.submit` | 通用写 | 任意已注册命令（sensitive ⇒ 走审批） |
| 11 | `simos.advance` | 通用写 | 推进时间 |
| 12 | `simos.fork` | 通用写 | 分岔 |
| 13 | `sd.IssueDirective` | GM 窄写 | 出令 |
| 14 | `sd.SubmitVerdict` | GM 窄写 | 提交判决 |
| 15 | `sd.SetViewScope` | GM 窄写 | 配可见范围 |
| 16 | `sd.StartDecision` | GM 窄写 | 「开始决策」（T10） |

**核对结论**：与源码 `SimosToolSource.Role.EXTERNAL_WITH_GM`（9 读 + 3 通用写 + 4 GM 窄写）**逐条相等**，
也与运行日志 `i5817.log:1`「外发工具 16 个 [ … ]」逐条相同。

> 另注：决策人口 5717 是**另一个桶**（`DECISION_AGENT`）。日志 `i5817.log:2` 显示它挂 **11 个**工具
> （9 读 + `sd.IssueDirective` + `sd.SubmitVerdict`），**无**通用写、**无** `sd.SetViewScope`、**无** `sd.StartDecision`
> —— 与源码 `addDecisionAgentWrites` 一致（本任务未连 5717，仅以日志/源码为据）。
