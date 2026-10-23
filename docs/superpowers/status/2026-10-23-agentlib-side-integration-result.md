# AgentLib × D-032 侧线集成结果（2026-10-23）

> **状态：integration 分支已验证，准备快进到 `main`；未推送 `main`。**
> 目标分支：`integration/agentlib-side`
> 计划：`docs/superpowers/plans/2026-10-23-agentlib-side-integration-rewrite-migration-plan.md`
> AgentLib 规范来源：`agentlib/dock-0.2.1`（已推送 `c8208b76`）
> D-032 成果来源：`parallel/side-module`（`36e4c9a9`）

## 1. 结果摘要

- 合并顺序：先把 `parallel/side-module` 快进到 integration，再合并已推送的
  `agentlib/dock-0.2.1`。
- merge commit：`5487377f`，双亲 `f71916ac` 与 `c8208b76`。
- 决策包/工具面唯一权威已收口到 AgentLib 0.2.3：
  - `DecisionRecord` / `DecisionPackageSnapshot` / `DecisionPackageTransitions` 为包状态权威；
  - GM 决策面只有 AgentLib 五工具；
  - 旧 `ProposalCatalog`、`ProposalDeclarationAudit`、`DecisionProposable`、旧
    `Upsert/Submit/DecideDecisionPacketHandler` 与旧 `Gm*Packet*` 工具在 `src/main` 零存活；
  - `DecisionPacket` 只保留只读投影/领域视图语义。
- D-032 侧线的经济、工资、债务、land/lot、运输、文化、地图和 360 tick 入口保留。

## 2. 实际装配读数

| 项 | 实测值 |
|---|---|
| Maven reactor 模块 | 19 |
| codec | 10 |
| CommandHandler | 150 |
| 工具面（GM/MCP union） | 182 |
| 磁盘 `*Tool.java` 扫描面 | 178（含 AgentLib 五工具在具名外来清单中） |
| GM 桶 | 182 |
| 决策人桶 | 61 |
| 工具三分类并集 | 195（GM-only 134 / DM_APPROVAL 25 / DM_FREE 36） |
| 决策命令样本 | 100 |

## 3. 验收

### 定向测试

```text
14 个测试类，148 tests / 0 failures / 0 errors
```

覆盖：AgentLib 决策包（runner / GM port / persistence / turn queue / commit conflict）、
GmActAs、SimosTools、McpCoverage、McpServer、McpPortTopology、ShellAssembly、
ToolClassAudit、AdjudicateTick、D031CommandSurface。

### 全量门禁

```bash
tools/gate.sh full
```

结果：**PASS**，耗时 386s。

- 全仓 surefire：**3751 tests / 0 failures / 0 errors / 7 skipped（504 份报告）**；
- 前端：**412/412**；
- Spotless / Checkstyle / SpotBugs：通过；
- shaded jar：**29,451,438 B**；
- validate：rc=0；7 条关键护栏均真跑且为绿。

### 独立长跑

```bash
SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d
```

结果：**PASS**；77.5s；`MULTITICK_SIM_RESULT=PASS cases=1 failed=0`。

## 4. 未验 / 边界

1. 7 条 skipped 为既有 `RealLlm*` 环境门控；本轮未跑真实 LLM 外部门。
2. 360 tick 仍显示 `PRODUCTION_OPPORTUNITY_BOOK_BUILT lots≈327 opportunities=0`：这是 side
   既有 legacy staged-empty 方案边界，不冒充“已接自动 IDLE 机会”。
3. side 文档已列出的 L2 边界继续有效：自动 IDLE 启动、多 tick 逐日折叠、租佃/主导者选择、
   LAND 参与 labor/market 预期、投入不足缩规模、specific-lot matcher 等未在本批补齐。
4. 当前只完成 integration 分支；`main` 快进与最终 main 树门禁是紧随其后的步骤，未推送主分支。

## 5. 备份与工作树

- 合并前主树旧 D-031 P0 快照：
  `refs/backup/main-d031-p0-before-agentlib-side-merge-20261023`
  （commit `ff41838e`）。只作回退，不参与最终树。
- AgentLib 分支最终状态：`c8208b76`，已推送 `origin/agentlib/dock-0.2.1`。
- integration 工作树：`.claude/worktrees/integration-agentlib-side`。
