# 台账：simos 对接 AgentLib 新 LLM 设施（2026-09-22）

> worktree `.claude/worktrees/llmint`，分支 `llm/integrate`，基线 `1b0b4d9`。
> 计划：`docs/superpowers/plans/2026-09-22-agentlib-llm-integration-plan.md`。

## §〇 开工前只读实测（只记结论）

- `~/.m2` 的 `agentlib-mosire-0.1.0-SNAPSHOT.jar` = **134 个 `.class`**（安装报告写 131；差额是
  `LlmRouteAssembler$1` / `OpenAICompatibleLlmClient$1` / `ToolCallBuffer` / `Accumulator` 等匿名/内部类）。
  新增类逐条在场：`Sampling` / `LlmTransport` / `LlmProtocol` / `LlmRouteAssembler` /
  `LlmResponse$ReasoningDisposition` / `LlmException$Kind` / `tool/ToolDefs`。
- **provider 来源（实测）**：本机 env 有 `ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic`、
  `ANTHROPIC_AUTH_TOKEN`（35 字符）、`ANTHROPIC_MODEL=deepseek-flash`。
  `curl https://api.deepseek.com/v1/models` + 该 token ⇒ **200 / `{"data":[{"id":"deepseek-flash",…},
  {"id":"deepseek-v4-pro",…}]}`** ⇒ **OpenAI 兼容根 = `https://api.deepseek.com/v1`**（AgentLib 约定
  的 `/v1` 在此根内）。`config/llm-providers.json` **本机不存在**（用迁移路径不需要它）。
- `bcae097`（llm/provider WIP）**不是** HEAD 祖先：`git merge-base HEAD bcae097` = `998332a` ⇒
  它是旁支。它同时含 sd 侧 `providerId`（已由 `1b0b4d9` 落地）与 app 侧自造轮子 ⇒ **按本任务要求删轮子**，
  只借它的 GUI CRUD / 掩码视图 / fail-closed 解析**语义**，实现改走 AgentLib。
- 门禁下界（前端）：`run-gate.cjs` 与 `gate-contract.test.cjs` **两处**都是 `188`（本机 `nproc=8`、
  内存 11G/可用 1G ⇒ 全量 verify 需前台独占）。

## §一 续做/收口裁定（2026-09-22，控制器）

- **崩溃恢复**：`git stash pop` 成功，无工作丢失。
- **provider 以实测为准**：`config/llm-providers.json` **重建**为 `mosire-flash`（`http://121.40.130.178:3000/v1`，
  AgentLib 约定含 `/v1`），直连复测 HTTP 200；**不采用** §〇 里由 `ANTHROPIC_*` env 推的 `api.deepseek.com/v1`（那是本机 Claude Code 的 env）。
- **三处对 D 阶段的补缺**（详见 `task-final-report.md` §一 ★2/★3/★4）：提示词补 schema、简报由 `"{}"` 改为事实性、请求加采样。
- **门禁**：最终绿轮 `logs/clean-verify-final.log`（md5 `a8f602a8…`）；`1405 = 170/369/45/259/179/141/242`，基线 `1376`，delta +29，前端 201/201。
- **变异**：9 轮 9 KILLED / 0 存活（`mutants/summary.txt`）。
- **端到端**：真 provider 经 app 跑通「开始决策」→ `Verdict` 落 `main@9`（`e2e/e2e-output.txt`）。

