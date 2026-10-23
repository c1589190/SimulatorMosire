# 2026-10-17 agentlib-dock 状态恢复 + 待裁定核对

> **状态：历史快照（文档系统重写于 2026-10-23）** · 当前实时状态以 [`docs/agents/STATUS.md`](../../agents/STATUS.md) 为准；本文只作当时事实留痕，勿据此开工。

> **★★ 2026-10-17 后续更正（覆盖本文旧结论）**：本文当时把“下一步”写成“只剩验收/合并时序”，但未审计 AgentLib 0.2.1 的 **`io.mosire.agentlib.decision` 包（22 类）在 Simos 生产代码中零引用**，也未审计 `SuperiorJudgeGate` 等上级判定族。实际迁移面并未闭合，旧结论“只需验收”作废。
> 覆盖报告：`docs/superpowers/reports/2026-10-17-agentlib-021-migration-coverage-audit.md`（只读审计，未改生产代码）。
> 本文其余历史事实保留；与报告冲突处，以报告为准。

> **工作树**：`.claude/worktrees/agentlib-dock` · **分支**：`agentlib/dock-0.2.1` · **基点**：`03faf0a3`（main）
> **HEAD**：`0c377129`（docs 补记）· **核对时间**：2026-10-17 15:2x
> **用途**：按 `docs/agents/02-一-并发.md` §一.12 做开工前的口径溯源；**不是终态存档**。
> **本文件未提交**（遵守"不擅自 `git commit`"）。

## 一、状态恢复（实测）

| 项 | 实测值 | 命令 |
|---|---|---|
| HEAD / 分支 | `0c377129` / `agentlib/dock-0.2.1` | `git rev-parse --short HEAD`；`git branch --show-current` |
| 相对 main 的规模 | **235 文件，+1799 / −585** | `git diff --shortstat 03faf0a3..HEAD` |
| 工作区 | 仅 `?? WORKTREE.md`（有意不入库） | `git status --porcelain` |
| 三个提交 | `e26d49a4`（A+B 对接，225 文件）· `6547ffa1`（S+R 生产 WIP，15 文件 +704/−22）· `0c377129`（进度补记） | `git log --oneline -3` |
| 整树复编 | **BUILD SUCCESS**，17 模块全 SUCCESS，22.346 s，15:23:20 | `tools/mvn-lock.sh clean compile -DskipTests -pl simos-app -am` |
| 测试 | **未跑**（S/R 均未写、未跑；见 §五） | —— |

- S/R 两批生产账本（本机留痕、不进 git）：`.superpowers/sdd/2026-10-17-single-space-system/impl-ledger.md`、`.superpowers/sdd/2026-10-17-batch-R/impl-ledger.md`。
- 两批的证据日志均在对应 `evidence/` 目录；本次复编为**两批合并后的新证据**（`clean compile`，不是增量跳过）。
- 15:13:44 还原事故已在 `docs/superpowers/status/2026-10-17-agentlib-dock-progress.md` §三结案：非恶意，但违反"一个文件一个 owner"；处置与四条教训已记录。

## 二、口径溯源表（§一.12 四步）

| # | 候选裁定 | 结论 | 出处 + 日期 | 原文 / 条款 | 是否被取代 |
|---|---|---|---|---|---|
| 1 | AgentLib 0.2.1 对接目标 | **已有用户原话** | `specs/2026-10-17-agentlib-021-ga-agent-rewrite-design.md` §0 第 3 条（2026-10-17） | 「agentlib更新了许多可以复用的标准化Agent结构，你应该把目前ga模块的相关Agent实现，秉持着lib里能复用就复用的原则来进行重写和对接」 | 否 |
| 2 | 新分支保存进度、日后合并 | **已有用户原话** | 同上 §0 第 3 条（2026-10-17） | 「可以开一个新分支方便保存开发进度和日后合并」 | 否 |
| 3 | `timeBudget` / `quotaMaxTokens` 是否开判 | **已有裁定：先不开**（只填不判） | 同上 §0 第 5 条（2026-10-17）；`status/2026-10-17-agentlib-dock-progress.md` §五 | 「等等等，可以不开是吧？**先不开**」 | 否；第 4 条的"开"已被第 5 条撤回 |
| 4 | `deniedTools` 是否开判 | **无独立用户原话；设计列为批 D，当前只填 `Set.of()`** | 同上 §1.2、§3.4 表、§5 批 D 行 | 「timeBudget / quotaMaxTokens / deniedTools 本批**只填不判**……开判需用户裁定」 | 不属于本批；不阻塞当前验收 |
| 5 | GM 代打（`simos.gm.actAs`）语义 | **已有裁定，不改** | `plans/2026-10-14-gm-dm-interaction.md` §3.7（2026-10-14 第二轮裁定）；`reports/2026-10-17-batch-R-role-dimension-design-review.md` §6.3 | 「该咋整咋整，不要动现有架构，GM想干啥干啥」；外层仍是 GM，内层经目标 DM 的 caller 自然取得 `{DM}` | 否 |
| 6 | 跨角色收紧 / 系统上下文全角色 | **审核已核：不需要** | 同上 review §6.1、§6.2 | C1/C2 + 桶边界下正常生产调用已有相交角色；C3–C7 走裸 `execute`/资源上下文，保持现状不是本批新增绕过 | 否 |
| 7 | LLM system 消息内容 | **已有用户原话（无条件硬约束）** | `specs/2026-10-17-single-space-system-message.md` §0（2026-10-17） | 「系统上下文应该直接传一个空格……所以应当硬要求只传一个空格」 | 否；该 spec 明确只在本项上扩展旧设计"不动 `LlmDecisionAdjudicator`"的范围 |
| 8 | GUI「测试连接」补 `SINGLE_SPACE`（spec §4 允许面之外） | **控制方已裁定接受；不需用户裁定，除非用户反对该 hunk** | 进度文档 §二 / §五；S 账本 §5 偏离 1 | 该路径也是真实出站 LLM 请求，原来**没有** system 消息，违反 §2.1「不能……省略消息」；回退只需还原 `GuiServer.java` 两处 hunk | 否；spec §1/§4 的现场描述待补（见 §五） |
| 9 | 分支合并回 main 的**时机与方式** | **确实无裁定** | 进度文档 §四第 5 条（2026-10-17 15:18） | 「终态提交 + 合并决策（`agentlib/dock-0.2.1` → **待定**）」；用户原话只有「日后合并」，未定先后/方式 | ——（唯一需要用户裁定的项，见 §三） |
| 10 | 旧《给 AgentLib 侧的需求书》N1（称 `AgentTool` 无 `roles()`） | **事实已过时，属文档维护** | `plans/2026-10-16-agentlib-boundary-needs.md` §4 N1；0.2.1 实际已有 `roles()`（批 R 已消费） | 该文原文带「★ 未核实是否别有机制」；批 R 已实测 0.2.1 的 `AgentTool.roles()` 存在并可派生 | 不冒充用户裁定；是否整篇标"部分满足"由文档维护决定 |

## 三、需要用户裁定的（仅 1 项）

### 合并 / 入账时序（分支 ↔ 主树未提交的 D-031）

**事实（快照）**：

- 本分支基于 `03faf0a3`；主树 `main` 当前仍在该基点，且有 **57 条未提交路径**（含未跟踪），核心是 D-031（`simos-culture/`、`simos-production/` 等新模块与 `pom.xml` 模块表）。
- 分支与主树未提交改动**共同触碰 5 个文件**：
  `pom.xml` · `simos-app/.../Shell.java` · `simos-app/.../tools/read/CatalogTool.java` · `simos-app/src/test/.../ShellAssemblySmokeTest.java` · `simos-app/src/test/.../tools/SimosToolsTest.java`。
- 主树 `pom.xml` 的未提交改动新增 `<module>simos-production</module>` / `<module>simos-culture</module>`；本分支的 `pom.xml` 改的是 `agentlib-mosire.version 0.1.0 → 0.2.1`。两边是不同区域，但需要一次合并/变基。

**可选口径**（建议按甲）：

| 选项 | 做法 | 代价 / 风险 |
|---|---|---|
| **甲（建议）** | 本树先跑完测试代理 + 全量门禁（S1–S5、R-I0–R-I6、真 LLM、前端），绿后再由主树协调：D-031 先提交入账，然后把本分支合并/变基回 `main` | 合并窗口晚；5 个重叠文件的冲突在最后处理 |
| 乙 | 先把本分支生产代码合回 `main`，测试与门禁在 `main` 上做 | 未验收代码进主树；主树 D-031 未提交，需先 stash/提交，违反"不留长窗口"的教训 |
| 丙 | 本分支继续独立，暂不合并，等 D-031 写者收手后再定 | 分支与主树继续分叉，冲突面只会变大 |

**这项目前不阻塞"派测试代理 / 跑门禁"**；只需在你打算合并前给一个口径。

## 四、不需要裁定的（已有口径 / 控制方已决）

1. `timeBudget` / `quotaMaxTokens`：**先不开**（用户 2026-10-17 原话第 5 条）。
2. `deniedTools`：本批只填 `Set.of()`，不判；批 D 再议。
3. GM 代打：语义已有裁定（2026-10-14 §3.7），不改。
4. 跨角色收紧 / 系统全角色：审核 §6 已核，不需要。
5. 单空格 system：无条件硬约束；GUI「测试连接」补单空格是执行该约束，不是新设计。
6. 新分支保存进度：用户已明确同意；主树仍是最终入账口。
7. 「整树复编」：本次已实跑（`clean compile`，17 模块全 SUCCESS，15:23:20）。

## 五、文档待修（不需用户裁定）

1. `specs/2026-10-17-single-space-system-message.md` §1/§4：补上 `GuiServer.testLlmProviderReply` 这条出站路径与允许面说明（控制方已在进度文档裁定接受）。
2. `specs/2026-10-17-agentlib-021-ga-agent-rewrite-design.md:137` 写「逐字沿用用户 **2026-10-22** 裁定」，而单空格 spec §0 记录的用户原话日期是 **2026-10-17**；按 `docs/AGENTS.md` §五.2 应两存并标注日期疑点，或核后改正。
3. `plans/2026-10-16-agentlib-boundary-needs.md` §4 N1：0.2.1 已有 `roles()`，该条"无 `roles()`"的现场描述已过时；是否在文首标"部分满足/被 0.2.1 取代"待文档维护。

## 六、未验证 / 没做（如实列）

- 测试仍**全部未跑**：S1–S5、R-I0–R-I6、变异自证、test-compile、verify、package、`tools/gate.sh`、真实 LLM 门、前端门。
- 装配审计"真实启动为绿"未实测；决策人/GM 角色判定与 GM 代打内层 `{DM}` 未端到端验证；`requireSingleSpace` 的拒绝行为仅代码审查。
- 本次只做了整树 **compile**；没有跑任何测试或门禁。
- 合并口径未定（§三）；主树 D-031 的 57 条未提交路径仍是快照值，可能随其他写者变化。
