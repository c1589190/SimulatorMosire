# AgentLib 0.2.1 对接工作区 · 进度存档（2026-10-17 15:18 更新）

> **状态：历史快照（文档系统重写于 2026-10-23）** · 当前实时状态以 [`docs/agents/STATUS.md`](../../agents/STATUS.md) 为准；本文只作当时事实留痕，勿据此开工。

> **本文件是工作进度保存点，非终态。** 工作树 `.claude/worktrees/agentlib-dock`，分支 `agentlib/dock-0.2.1`（自 `03faf0a3` 开树）。
> 生产改动保存点：`6547ffa1`（wip，15 文件 +704/−22）；本文件随其后的 docs 补记提交落盘（`git log` 可见）。
> 下次开工入口：本文件 + `.superpowers/sdd/2026-10-17-single-space-system/impl-ledger.md` + `.superpowers/sdd/2026-10-17-batch-R/impl-ledger.md`（后者不进 git，本机）。

## 一、已落盘（提交 `e26d49a4`，225 文件）

批 A+B：ga 的 Agent 实现改为**复用 AgentLib 0.2.1 标准件**（`AgentLoop` / `ContextWindow` / `AgentConfig` / `StopPolicy` / `TurnListener` / `ToolCallDispatcher`）。

- 设计书：`docs/superpowers/specs/2026-10-17-agentlib-021-ga-agent-rewrite-design.md`
- 验证证据：全 reactor test-compile SUCCESS；`DecisionAgentRunnerTest` 27/27；相关测试族全绿；变异 M1（轮次通知误持久化）/ M2（图片提前 flush）/ M3（AgentConfig 默认值泄漏）均先红后还原。
- 行为保持：预算中止仍抛 `TurnBudgetExceeded`；LLM 异常仍 `onLlmError` 重抛；每轮通知不持久化（只作首轮 `turnInput`）；图片在全部工具消息后 flush；批内无中途终止。

## 二、生产改动保存点 `6547ffa1`（WIP，非终态）

> 2026-10-17 15:17 两实现代理**均已交账**：生产代码全部完成、定向编译 rc=0、**未跑任何测试**。全部已提交在 `6547ffa1`；工作树 clean（只剩 `WORKTREE.md` 未跟踪，有意不入库）。

### 批 S：系统上下文硬约束为单空格【已交账 15:16】

- 新增 `simos-ga/src/main/java/io/mosire/simos/ga/llm/SystemPromptPolicy.java`（`SINGLE_SPACE = " "`；`requireSingleSpace` 只接受单空格，拒绝 null/空串/多空格/换行/长文本，异常只带落点与形状、**不回显内容**）
- 生产接线 —— 全仓 `src/main` 出站构造点共 **4 处**，全部回落 `SINGLE_SPACE`：
  - `DecisionAgentRunner`（`AgentConfig.systemPrompt` 与出站 system 唯一来源）
  - `LlmRequest`（compact 构造器边界校验）
  - `LlmDecisionAdjudicator`（**控制方交的改动只改一半**：旧 `systemPrompt(breakpoint)` 未删、两参 `userPrompt` 不存在 ⇒ 原始状态编译必失败；S 代理补完——删旧方法，原 system 指令逐字前置进两参 `userPrompt`，原简报/schema/命令白名单/弃权约定逐字保留）
  - `LlmProviderResolver`（出站 `LlmMessage.system(SystemPromptPolicy.SINGLE_SPACE)`，不再透传 `request.systemPrompt()`）
  - `GuiServer.testLlmProviderReply`（GUI「测试连接」**原来没有任何 system 消息**，违反 §2.1 无条件硬约束 ⇒ 补 `SINGLE_SPACE`；该文件在 spec §4 允许面之外，**控制方裁定接受**，spec §1/§4 待补此路径；如用户反对只需回退这一个 hunk，其余三条路径不受影响）
- 约束设计书：`docs/superpowers/specs/2026-10-17-single-space-system-message.md`（S1–S5）
- 编译证据：`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` ⇒ **rc=0**，17 模块全 SUCCESS，15:14:15（`.superpowers/sdd/2026-10-17-single-space-system/evidence/compile-2.log`）
- ★ 注意：该编译完成 **早于**批 R 重做（15:14:51+），**整树复编必须在验收时重跑**

### 批 R：Agent 角色维度（复用 lib `AgentTool.roles()` + `RoleCatalogAudit`）【已交账 15:17】

- `simos-ga-spi/.../SimosTool.java:64`：新增 `@Override default Set<String> roles()`（`TreeSet` 收 `toolRoles()` 的 `ToolRole.name()` + `Set.copyOf`；183 个实现类零改动）
- `simos-ga/.../tools/RecordingToolSource.java:128`：`RecordingTool.roles()` 转发 `delegate.roles()`（审核 P1-1）
- `simos-ga/.../tools/ToolRoleAudit.java:160`：新增 `requireFacesOnAgentTools(gmFace, decisionFace)`（读 `AgentTool.roles()` 字符串、不强转 SimosTool；每面逐实例 + 双向对照）；`:194` 新增 `declaredRoleCatalog`（输入 A 收集器）；既有 `requireFaces`/`requireFace`/`rolesOf` 一字未动
- `simos-app/.../Shell.java:1425-1452`：装配审计接线（在 `McpSourceBridge.bind(RecordingToolSource.record(...), toolRegistry)` 之后、MCP 起服务之前）；`:1803` `gmPermissionSet()` 加 `.roles(RoleSet.of(ToolRole.GM.name()))`
- `simos-app/.../access/DecisionCallerFactory.java:316`：`permissionSetOf` 加 `.roles(RoleSet.of(ToolRole.DM.name()))`
- 审计双输入：**A** = `withDerivation(registry -> ToolRoleAudit.declaredRoleCatalog(registry.list(), assembledDecisionFace))`（逐实例读 `AgentTool.roles()`，不读桶名单）；**B** = `Map.of(GM.name(), ToolBuckets.namesOf(wrappedGmFace), DM.name(), ToolBuckets.namesOf(assembledDecisionFace))`（实际桶成员投影）。两桶同名时逐实例各验自己那面、不按名字合并；`hasFailures()` ⇒ 装配期 `IllegalStateException`
- rev2 计划：`docs/superpowers/plans/2026-10-17-batch-R-agentlib-role-dimension-plan.md`
- 设计评审（5 项 must-fix 已吸收）：`docs/superpowers/reports/2026-10-17-batch-R-role-dimension-design-review.md`
- 编译证据：`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` ⇒ **rc=0**，17 模块 SUCCESS（重做后真重编 ga-spi 14 / ga 157 / app 180 源文件，0 Checkstyle 违规；`.superpowers/sdd/2026-10-17-batch-R/evidence/compile-1.log`）
- 边界：C3–C7 / GM actAs 语义不改；timeBudget/quota 只填不判（用户裁定"先不开"）
- 提醒测试代理：`ProposalDeclarationAudit.java:71` 的 `"GM"` 是错误消息的**面标签**、不是角色名，R-I0 扫描器勿误判

## 三、15:13:44 还原事故（已结案，2026-10-17）

- **事实**：15:13:44 批 R 的 5 个文件被 `git checkout --` 整批还原成 HEAD（5 文件 mtime 精确相同 `15:13:44.063166058`）；R 代理 15:14:51–15:15:31 **原样重做**，本保存点已含重做后内容，另有 R 侧备份 `/tmp/batch-R-backup/`（md5 与 HEAD 一致）。
- **根因（S 代理自报）**：`spotless:apply`（即使带 `-DspotlessFiles`）误重排了 5 个批 R 文件，S 代理用 `git checkout --` 还原被误格式化的路径，**连带覆盖了 R 代理当时未落盘的编辑**。非恶意，但违反"一个文件一个 owner"。
- **处置**：已向两代理下发禁止一切 git 写操作（checkout/restore/stash/reset/clean）的协调令；控制方已在窗口期立即提交 `6547ffa1` 固化。
- **教训（写进后续任务书）**：① 同一工作树并行多代理禁止任何 git 写操作（只读 status/diff/log）；② 共享树禁止 `spotless:apply`，只能 `spotless:check`（本仓 `-DspotlessFiles` 会误伤他文件）；③ 每代理每完成一批立刻落补丁备份；④ 控制方验收即提交固化，不留长窗口。

## 四、下一步（按序）

1. 控制方**验收** S（S1–S5 口径）与 R（R-I0–R-I6 口径）交账；把 `GuiServer` 路径补进 spec §1/§4
2. **整树复编**（两批合并后唯一编译证据，rc=0 才算）
3. 各派**测试代理**（测试最后统一做）：
   - S：S1–S5 + 负向变异（system 非单空格 ⇒ 红；`requireSingleSpace` 四种非法输入运行验证）
   - R：R-I0–R-I6（两层角色判据、wrapper 丢失变异、`RoleCatalogAudit` 双独立输入、缺调用者角色 fail-closed、可见面探针只限真实工具面）
4. 全量门禁 `clean verify` 一次 + 真实 LLM 门 + 前端门（关账统一跑）
5. 终态提交 + 合并决策（`agentlib/dock-0.2.1` → 待定）

## 五、测试与门禁状态（一律未跑，不许把"没跑"写成"通过"）

- S1–S5、R-I0–R-I6、变异自证：**未跑**
- test-compile / verify / package / `tools/gate.sh` / 真实 LLM / 前端：**未跑**
- 装配审计"真实启动为绿"未实测；决策人/GM 角色判定与 GM 代打内层 `{DM}` 未端到端验证；`requireSingleSpace` 拒绝行为仅代码审查未运行
- 批 D（timeBudget/quota 判定）：用户裁定"先不开"，本批只填不判

## 六、2026-10-17 后续审计：暂停“直接验收”，迁移面未闭合

- 复核发现 AgentLib 0.2.1 的 `io.mosire.agentlib.decision` 包 **22 个类**在当前 `simos-*` 生产代码中引用数为 **0**；`PendingDecisionQueue` / `DecisionTurnScope` / `DecisionPackages` / `DecisionCommitPort` 均未接。
- `SuperiorJudgeGate` / `LlmSuperiorJudgement` 等上级判定族也未接；Simos 现有 `LlmDecisionAdjudicator` 是 D1–D8 领域 schema 判决，不是同一机制。
- 因此原先“下一步只剩 S/R 测试 + clean verify”的口径过窄：**S/R 只能作为局部批次验收，不能作为 ga 的 Agent 实现整体迁移验收**。
- 详细证据与建议：`docs/superpowers/reports/2026-10-17-agentlib-021-migration-coverage-audit.md`。
- 当前处置：**不启动 S/R 最终验收/门禁**；先补“统一决策包迁移”的约束设计书，明确包权威、生命周期、提交口与 `DecisionProposable`/`ProposalCatalog` 的去留，再决定下一批。

## 七、2026-10-17 决策包迁移：实现与门禁均完成（未提交）

- 设计书：`docs/superpowers/specs/2026-10-17-dm-gm-agentlib-decision-package-design.md`。
- 上游增量需求书：`docs/superpowers/plans/2026-10-17-agentlib-decision-package-read-port-needs.md`。
- 实现结论：**GM 五工具直接复用 AgentLib `DecisionPackageTools`；包状态只由 `DecisionPackageSnapshot`/`DecisionPackageTransitions` 驱动；`DecisionRecord` 随 `GaState` 由 core 持久化；`DecisionPacket` 只读投影。**
- 上游 `0.2.3-SNAPSHOT`：读端口 + 顶层 `DecisionItemVerdict`；U1b 修正 `DECIDING + decision == null` 的 `DecisionSubmission` 与 itemDecided 回执；AgentLib 聚焦 64/64、全模块 681/681，本机 `~/.m2` 已刷新。
- Simos 生产已落地：`GaState` 值类型 `DecisionRecord`、`ga.ApplyDecisionPackage`、DM Ask 全量入包、`request`/`intent`/`revise`/`drop`/`confirm`/`reopen`、abort 队列载体、五工具 GM 面、core 读写端口、CONFLICTED 持久化、HTTP 审批面退役。
- `tools/mvn-lock.sh -q clean verify` → **rc=0**；**3328 tests / 0 failures / 0 errors / 7 skipped**；前端门禁 **412/412**；Spotless/SpotBugs/Shaded guard 全过。
- `tools/gate.sh guards` → **rc=0**；5 条护栏逐条真跑（Architecture/Ga/Gov/Army roundtrip + ShellAssemblySmoke）全绿，前端门禁随跑 412/412。
- 定向 16 类 **161 tests / 0**；7 个变异先红后还原并 md5 复核；T 报告：`docs/superpowers/reports/2026-10-17-t-agentlib-decision-package-test-report.md`。
- 未做：提交/推送/合并；真实 LLM 门未跑；`RealLlmGovScenarioTest` 仍保留旧 propose/DRAFT 提示，开真 LLM 门之前需迁移。主树 D-031、`parallel-side-module`、`ga-opt`、`d031-p1-baseline` 未触碰。
