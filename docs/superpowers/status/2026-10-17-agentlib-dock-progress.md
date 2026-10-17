# AgentLib 0.2.1 对接工作区 · 进度存档（2026-10-17 15:16）

> **本文件是工作进度保存点，非终态。** 工作树 `.claude/worktrees/agentlib-dock`，分支 `agentlib/dock-0.2.1`（自 `03faf0a3` 开树）。
> 下次开工入口：本文件 + `.superpowers/sdd/2026-10-17-single-space-system/impl-ledger.md` + 各代理交账。

## 一、已落盘（提交 `e26d49a4`，225 文件）

批 A+B：ga 的 Agent 实现改为**复用 AgentLib 0.2.1 标准件**（`AgentLoop` / `ContextWindow` / `AgentConfig` / `StopPolicy` / `TurnListener` / `ToolCallDispatcher`）。

- 设计书：`docs/superpowers/specs/2026-10-17-agentlib-021-ga-agent-rewrite-design.md`
- 验证证据：全 reactor test-compile SUCCESS；`DecisionAgentRunnerTest` 27/27；相关测试族全绿；变异 M1（轮次通知误持久化）/ M2（图片提前 flush）/ M3（AgentConfig 默认值泄漏）均先红后还原。
- 行为保持：预算中止仍抛 `TurnBudgetExceeded`；LLM 异常仍 `onLlmError` 重抛；每轮通知不持久化（只作首轮 `turnInput`）；图片在全部工具消息后 flush；批内无中途终止。

## 二、本保存点未提交改动（WIP，非终态）

### 批 S：系统上下文硬约束为单空格

- 新增 `simos-ga/src/main/java/io/mosire/simos/ga/llm/SystemPromptPolicy.java`（`SINGLE_SPACE = " "` + `requireSingleSpace` 不回显校验）
- 生产接线：`DecisionAgentRunner`、`LlmRequest`、`LlmDecisionAdjudicator`、`LlmProviderResolver`、`GuiServer`
- 约束设计书：`docs/superpowers/specs/2026-10-17-single-space-system-message.md`（S1–S5）
- 状态：生产改动已落树，**未跑测试**；由批 S 实现代理收尾/自检中

### 批 R：Agent 角色维度（复用 lib `AgentTool.roles()` + `RoleCatalogAudit`）

- `SimosTool.roles()` 由 `toolRoles()` 派生（`ToolRole.name()`）；`RecordingToolSource.RecordingTool.roles()` 转发；`ToolRoleAudit` post-wrap 面校验；`Shell` / `DecisionCallerFactory` 调用者挂角色（C1 DM→`{DM}`、C2 GM/MCP→`{GM}`）
- rev2 计划：`docs/superpowers/plans/2026-10-17-batch-R-agentlib-role-dimension-plan.md`
- 设计评审（5 项 must-fix 已吸收）：`docs/superpowers/reports/2026-10-17-batch-R-role-dimension-design-review.md`
- 状态：5 文件生产改动**已全部重做**（15:13:44 还原事故后），**未跑测试**；由批 R 实现代理收尾中
- 边界：C3–C7 / GM actAs 语义不改；timeBudget/quota 只填不判（用户裁定"先不开"）

## 三、15:13:44 还原事故（未结）

- **事实**：15:13:44 批 R 的 5 个文件（`SimosTool` / `RecordingToolSource` / `ToolRoleAudit` / `Shell` / `DecisionCallerFactory`）被 `git checkout` 类操作整批还原为 HEAD，5 文件 mtime 精确相同（`15:13:44.063166058`）；批 R 代理在 15:14:51–15:15:31 已全部重做。
- **疑似**：同工作树另一代理（批 S）做"清理基线"时误还原（其账本写"生产文件无已有未提交改动"，evidence 在 15:12–15:14 有 spotless/compile 日志）——**待其自报确认**，不排除其他来源。
- **处置**：控制方已向两代理下发禁止一切 git 写操作（`checkout/restore/stash/reset/clean`）的协调令；控制方之后**每次验收即按文件白名单提交固化**，不再留长窗口。
- **教训候选**：同一工作树并行两个实现代理时，任务书必须写死"禁止 git 写操作"，并要求每个代理每完成一批立刻落补丁备份。

## 四、下一步（按序）

1. 两代理交账 → 控制方**验收**（S：S1–S5；R：R-I0–R-I6）→ 立即提交固化（只 add 责任区文件）
2. 各派**测试代理**（测试最后统一做）：
   - S：S1–S5 + 负向变异（system 非单空格 ⇒ 红）
   - R：R-I0–R-I6（两层角色判据、wrapper 丢失变异、`RoleCatalogAudit` 双独立输入、缺调用者角色 fail-closed、可见面探针只限真实工具面）
3. 全量门禁 `clean verify` 一次 + 真实 LLM 门 + 前端门（关账时统一跑）
4. 合并决策（分支 `agentlib/dock-0.2.1` → 待定）

## 五、未做 / 不做

- **批 D**（timeBudget/quota 判定）：用户裁定"先不开"，本批只填不判
- 测试未跑（按纪律留到最后统一做，不许把"没跑"写成"通过"）
- 批 R 的编译 rc 以代理交账为准；控制方验收时复跑一次定向编译
