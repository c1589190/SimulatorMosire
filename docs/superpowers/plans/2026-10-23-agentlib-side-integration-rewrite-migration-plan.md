# AgentLib × parallel/side-module 集成重写与迁移计划（2026-10-23）

> **状态：执行基线。** 本文是 `integration/agentlib-side` 的迁移约束设计，后续实现与测试按本文拆分。
> **目标分支**：`integration/agentlib-side`，验证通过后快进 `main`。
> **决策权威**：`agentlib/dock-0.2.1`（已推送 `c8208b76`）中的 AgentLib 0.2.3 决策包/工具面规范。
> **领域成果来源**：`parallel/side-module`（`36e4c9a9`）的经济、生产、文化、地图与长跑成果。
> **旧主树快照**：`refs/backup/main-d031-p0-before-agentlib-side-merge-20261023`
> （commit `ff41838e`），只作备份，不参与最终树。

## 0. 用户原话附录（逐字）

**2026-10-23**：

> 先把agentlib分支的最终状态提交并推送到远程的对应分支一下，然后是决策包/工具面，这些东西正是agentlib所复写的，所以应该把相关内容在parallel的内容大部废弃，然后搞专门的重写、迁移计划——按照最新的agentlib分支规范
> 也就是说你说的是对的，现在开始干吧，其中有需要裁决的内容，直接告诉我，我立刻裁

本文按该裁定执行：**决策包与工具面以 AgentLib 分支为唯一规范；parallel 侧相关旧实现废弃并迁移，不保留双权威。**

## 1. 当前事实

| 对象 | 事实 |
|---|---|
| `agentlib/dock-0.2.1` | HEAD `c8208b76`；依赖 `agentlib-mosire 0.2.3-SNAPSHOT`；已推送；最终门禁 3328/0/0/7、前端 412/412、17 模块全绿 |
| `parallel/side-module` | HEAD `36e4c9a9`；97 个提交；工作区干净（仅本机 `extensions.idx`）；最新文档记录门禁 3779/0/0/7、前端 412/412、360 tick PASS |
| `parallel/culture-module` | 5 个提交，已完整包含在 `parallel/side-module` 历史中；不再单独合并 |
| 主树旧 D-031 P0 | 未提交快照已固化到 `ff41838e`；其中 aptitude/科技等 P0-only 面已被 side 的 D-031 P1/D-032 设计淘汰，不进入最终树 |
| 当前 integration | 从 `main 03faf0a3` 建树并已快进到 side `36e4c9a9`；尚未合 AgentLib |
| 预计文本冲突 | 8 个：`Shell.java` + `GmActAsToolTest`、`McpCoverageTest`、`McpServerTest`、`ShellAssemblySmokeTest`、`SimosToolsTest`、`ToolClassAuditAssemblyTest`、`AdjudicateTickToolTest` |
| 预计语义冲突 | side 仍引用 `ProposalCatalog`、`ProposalDeclarationAudit`、`DecisionProposable`、旧 packet handler 与旧 `Gm*Packet*` 工具；这些在 AgentLib 分支已删除或重写 |

## 2. 唯一权威与模块所有权

| 责任面 | 唯一权威 | 说明 |
|---|---|---|
| DM 回合/Agent 循环 | `simos-ga` + AgentLib `AgentLoop` / `ToolCallDispatcher` | 不恢复自造 Agent 循环 |
| 决策包状态机 | AgentLib `DecisionPackageSnapshot` / `DecisionPackageTransitions` / `DecisionPackageTools` | `DecisionPacket` 只能是只读领域投影 |
| 世界持久化 | `GaState.decisionPackets -> DecisionRecord`，经 `Command → ChangeSet → Revision` | Core 提供读写/submission 端口，不建进程内第二表 |
| 工具角色与可见面 | AgentLib `AgentTool.roles()` + side 仅保留必要业务工具 | 旧 `DecisionProposable` / `ProposalCatalog` 退役 |
| 经济/生产/文化/地图领域 | `parallel/side-module` 的 D-032 成果 | 数量、结算、land/lot、市场、文化状态等保留 |
| 命令目录与载荷提示 | `Shell` 注册面 + `CatalogTool` + `SimosToolSource` | 以最终真实注册面重新计算集合与计数 |

## 3. 保留 / 重写 / 废弃

### 3.1 整体保留（side 优先）

- `simos-economy-api`、`simos-economy` 的 D-032 经济、工资、债务、土地、市场、lot 库存成果；
- `simos-production`、`simos-culture` 两个模块及其 POM/enforcer 边界；
- `simos-map` 的 LAND component / 撮合与真实地图成果；
- side 的 32hex 单市场区/单地方政府 fixture、360 tick 入口和对应报告；
- side 的生产/文化 codec、ChangeSet、命令 handler、GM 窄写工具；
- side 中不触碰决策包语义的 world/seeder/participant 增量。

### 3.2 AgentLib 优先（side 旧实现废弃）

以下文件/符号不得以 side 版本继续保留为第二权威：

- `simos-app/.../decision/ProposalCatalog.java`
- `simos-app/.../decision/ProposalDeclarationAudit.java`
- `simos-ga-spi/.../spi/DecisionProposable.java`
- `simos-ga/.../spi/UpsertDecisionPacketHandler.java`
- `simos-ga/.../spi/SubmitDecisionPacketHandler.java`
- `simos-ga/.../spi/DecideDecisionPacketHandler.java`
- 旧 GM 决策工具：`GmApprovalsTool`、`GmApproveTool`、`GmDecidePacketTool`、`GmPacketExecuteTool`
- 旧拟稿支撑：`PacketDraftSupport`、`ProposeCallTool`
- 只验证旧 ProposalCatalog/旧 packet 状态机的测试：
  `ProposalCatalog*`、`ProposalDeclarationAuditTest`、`ProposeWriteThenThrowGuardTest`、
  `DecisionPacketEndToEndTest`、`RunDecisionEndToEndTest`
- side 测试里对旧工具名、旧 handler 数、旧 ProposalCatalog 目标/预览的期望。

`DecisionPacket` / `FormattedCall` / `MergedEffectPlan` 可以保留，但只能按 AgentLib 设计作为只读投影或领域扩展；状态转移必须经 `DecisionRecord.packageState`。

### 3.3 手工合并（两边都保留语义）

- `pom.xml`：side 的 18 模块结构 + AgentLib `0.2.3-SNAPSHOT` 版本。
- `Shell.java`：AgentLib 的决策包/AgentLib 五工具/角色装配 + side 的 economy/production/culture codec 与 world seed 注册。
- `SimosToolSource.java`：以 AgentLib 工具源为骨架；把 side 新增的生产/文化/经济工具加入正确桶；不得恢复旧 ProposalCatalog 字段。
- `CatalogTool.java`：保留 side 的 production/culture/economy 命令载荷提示；AgentLib GM 决策工具不冒充命令 handler。
- `GuiServer.java` / `DecisionCallerFactory.java` / world/seeder/participant：side 领域接线优先，决策调用面按 AgentLib 新 API。
- 测试：决策包测试以 AgentLib 为基线；生产/文化/经济 fixture 与断言从 side 重新嫁接。

## 4. 迁移步骤（严格顺序）

### M0 — 机械合并与失败面固化

1. 在 `integration/agentlib-side` 合 `agentlib/dock-0.2.1`，记录全部冲突与编译错误。
2. 对 8 个文本冲突只做“保留双方语义”的初解，不用整文件 ours/theirs。
3. 立即跑 `compile`，把语义不兼容点固化成清单。

### M1 — 移除第二决策权威

1. 删除 §3.2 的旧类和只测旧权威的测试。
2. 清除 `Shell`、`SimosToolSource`、`DecisionCallerFactory`、world fixture 与工具测试中的旧符号引用。
3. 确保 GM 决策工具只来自 AgentLib `DecisionPackageTools`，DM 回合只走 `PendingDecisionQueue → DecisionTurnFinalizer`。

### M2 — 把 side 领域能力接回新工具面

1. 在 `SimosToolSource`/`Shell` 恢复 side 的 production/culture/economy 命令与 GM 窄写工具。
2. 恢复 side codec 注册、world slices、seeder 与 participant 接线。
3. 恢复 Catalog 载荷提示；命令 type 集合必须来自真实注册面。
4. 对每个被保留的业务工具核对 `roles()`、GM/DM 桶与 gate，不按旧 ProposalCatalog 名单恢复。

### M3 — 测试迁移

1. `GmActAsToolTest` 以 AgentLib 决策包测试为基线，补 side 的生产/家庭目标 fixture。
2. `SimosToolsTest`、`McpCoverageTest`、`McpServerTest`、`ShellAssemblySmokeTest`、`ToolClassAuditAssemblyTest` 从最终 registry 重新计算工具集合、命令集合与桶计数。
3. `AdjudicateTickToolTest` 保留 side world/economy 期望，改走 AgentLib 新决策面。
4. 任何计数变化必须写出来源，不把“测试红了就改小期望”当修复。

### M4 — 验证与收口

顺序：

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
tools/mvn-lock.sh -pl simos-app -am -DskipTests test-compile
# 定向：AgentLib 决策包、工具面、side 经济/生产/文化
tools/mvn-lock.sh -pl simos-app -am \
  -Dtest='DecisionAgentRunnerTest,DecisionPackage*,GmActAsToolTest,SimosToolsTest,McpCoverageTest,McpServerTest,ShellAssemblySmokeTest,ToolClassAuditAssemblyTest,AdjudicateTickToolTest' \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
# side 经济/生产/文化全量
tools/mvn-lock.sh -pl simos-app -am test
# 独立长跑
SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d
# 最终门禁
tools/gate.sh full
```

通过后：
1. 把 integration 快进到 `main`；
2. 在最终 main 树再跑 `tools/gate.sh full`；
3. 更新 status/handoff，写清实际测试计数、未验项与 side 的既有边界；
4. 推送按用户后续授权执行。

## 5. 验收判据

- **A1 单一权威**：`ProposalCatalog` / `ProposalDeclarationAudit` / `DecisionProposable` / 旧 packet handler 在最终 `src/main` 中零存活。
- **A2 包状态机**：GM 决策面只注册 AgentLib 五工具；包状态变化只经 `DecisionPackageTransitions`/`DecisionPackageTools`；重启后 list/get 与 commit 语义有测试载体。
- **A3 持久化**：`GaState` 只存 `DecisionRecord`；旧 `DecisionPacket` 不作为状态机权威；round-trip/ChangeSet/Codec 全绿。
- **A4 领域保留**：side 的 D-032 经济/生产/文化命令、codec、GM 工具和 land/lot 能力仍可从真实 Shell 装配面找到。
- **A5 工具面同源**：工具名/命令 type/桶计数测试从最终注册面生成，并与 catalog、MCP 工具面一致。
- **A6 经济回归**：side 定向测试、全仓测试、`rural-32hex-360d` 长跑均绿。
- **A7 门禁**：`tools/gate.sh full` PASS，报告实际 surefire / 前端 / SpotBugs / shaded jar 读数。
- **A8 诚实边界**：真实 LLM 门、side 文档已列的未做项（例如 L2 自动 IDLE/多 tick/租佃）不得写成完成。

## 6. 当前待裁决

**无。** 用户已裁定 AgentLib 决策包/工具面为唯一规范、parallel 旧相关内容废弃。若迁移中出现新的用户可见业务语义冲突，立即停下上报，不代替用户裁定。

## 7. 已知风险

1. side 的旧 ProposalCatalog 引用分布在 Shell、SimosToolSource 和多个测试中，必须用编译错误驱动清扫，不能只删类文件。
2. side 与 AgentLib 的工具计数锚点不同；最终数字只能从实际 registry 测出，不能把 181/179 相加或任选其一。
3. AgentLib 分支基线只有 15 个业务模块；side 的 production/culture 会改变 codec 数与模块数，旧 8/10 模块锚点都要重新核。
4. 两边门禁证据都早于合成树；合并后的全量门禁必须重跑。
5. 合并前快照 `ff41838e` 中旧 D-031 P0 的 aptitude/科技代码只作回退备份，不因“文件存在”重新接回。

## 8. 执行结果（2026-10-23）

- merge commit `5487377f`：双亲为 `f71916ac`（integration 计划）与 `c8208b76`（AgentLib 分支）。
- 8 个文本冲突已解决；`GmActAsToolTest` 采用 AgentLib 新决策包版本，其余工具面测试按最终真实 registry 重新校准。
- 旧 `ProposalCatalog` / `ProposalDeclarationAudit` / `DecisionProposable` / 旧 packet handler 在 `src/main` 零存活；旧相关测试随 AgentLib 分支删除。
- 真实装配读数：`codec=10`、`handler=150`、`tool=182`；磁盘 `*Tool.java` 扫描面 178（含 AgentLib 五决策工具的外来面按具名清单处理）。
- 定向 14 个测试类 148 tests / 0 failures / 0 errors 全绿。
- `tools/gate.sh full` PASS：3751 tests / 0 failures / 0 errors / 7 skipped；前端 412/412；shaded jar 29,451,438 B；耗时 386s。
- `SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d` PASS：77.5s，`MULTITICK_SIM_RESULT=PASS cases=1 failed=0`。
- 360 tick 的 `PRODUCTION_OPPORTUNITY_BOOK_BUILT` 仍为 `lots≈327 opportunities=0`：这是 side 已知“legacy staged-empty 方案”边界，不是本集成新回归。
