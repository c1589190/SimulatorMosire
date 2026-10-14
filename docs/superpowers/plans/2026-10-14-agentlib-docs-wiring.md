# 2026-10-14 AgentLib Docs/Workspace 接入 —— 约束设计书

> **本文是派实现 Agent 的前置**（`AGENTS.md` §五.3 → `docs/agents/02-一-并发.md` §一.8）。
> **上游**：隔壁产物 `~/ProjectMosire/AgentLibMosire` 的《接入指南-三个库给消费方.md》（commit `7618cb9`）
> 与我们那份《AgentLib Docs 系统 —— Simos 侧对接需求书》（`docs/superpowers/specs/2026-10-13-agentlib-docs-requirements.md`）。
> **来源**：指南全读 + **两个 jar 反编译核实**（`javap`，非转抄指南结论）。**未跑 Maven**。
> **基线**：`main` @ `b82535e3`（急停协议/拆 Skill/分段等价 四提交已入库，工作树干净）。

---

## 1. 需求来源（用户原话，原样照录）

> 「**工作区由gm维护**，**doc系列在指定工作区的情况下给dm自用**，**批准不必向gm返回完整信息只给gm看路径**，可以写计划并实现了，
> 哦对了，**本地8080有gemma4，可以跑真llm测试，敞开了用，确保项目稳定性**」

★ 四条逐条落点：

| # | 用户裁定 | 本文落点 |
|---|---|---|
| **U1** | **工作区由 GM 维护** | `mount_workspace` / `list_workspaces` ⇒ `roles()={GM}`；§4.1 |
| **U2** | **doc 系列在指定工作区下给 DM 自用** | `doc_*` ⇒ `roles()={GM,DM}`，且**可见性靠"挂载"隔离**（DM 只看得见自己那个工作区）§4.1 / §5 |
| **U3** | ★★ **批准不向 GM 返回完整信息，只给路径** | §4.3 —— **关键发现：这是审批 `summary` 的脱敏，而 `summary` 由工具自报 ⇒ 适配层必须自己合成** |
| **U4** | **本地 8080 有 gemma4，可跑真 LLM 测试，确保稳定性** | §6.3 真 LLM 验收（provider 配置见 §4.4） |

---

## 2. 现状事实（反编译核实，非转抄指南）

### 2.1 工件与工具面

- 坐标：`io.mosire:{docs-mosire, workspace-mosire}:0.1.0-SNAPSHOT`，已在 `~/.m2`（00:39，`docs` 100,259 B / `workspace` 48,259 B）；
  依赖链 `docs → workspace → agentlib`（**agentlib 传递带过来**，不必显式声明）。
- 工具面 **10 个**：`doc_list` / `doc_read` / `doc_query` / `doc_append` / `doc_void` / `doc_create` / `doc_edit`
  （`DocsTools.all(registry)`）+ `WorkspaceTools.all(registry)` 的 3 个（含 `mount_workspace` / `list_workspaces`）。
- 两个生成器：`public static List<AgentTool> all(WorkspaceRegistry)`；另有 `NAMES` 常量集，**全仓无人读**（反编译核实）⇒ **不是授权面**。

### 2.2 ★★ 硬冲突：他们的工具没有角色概念

```
DocsTools$DocTool             implements io.mosire.agentlib.tool.AgentTool   ← 字段只有 name/description/schema/manifest/spec/body
WorkspaceTools$WorkspaceTool  implements io.mosire.agentlib.tool.AgentTool   ← 字段只有 name/description/schema/spec/body
我们的契约  SimosTool extends AgentTool { Set<ToolRole> roles(); }
```
⇒ **`AgentTool` 里没有"角色"这个概念，`ToolRole` 在 agentlib jar 里 0 命中**（反编译核实）⇒ `roles()` 是**Simos 特有**。

而我们的装配面**要求每条工具都是 `SimosTool`**：
```java
// SimosToolSource.java:549-554（我们自己的 fail-closed 校验）
ToolRoleAudit.requireFace(built, role == Role.GM ? ToolRole.GM : ToolRole.DM);
```
⇒ **原样塞进桶 = 装配当场抛**。**适配层是必须的，不是优化。**

### 2.3 他们的授权是"运行时按调用者"，我们是"装配期按工具声明"

两者**正交**，必须一致：
- 他们：`WorkspacePermissions` / `DefaultWorkspaceRegistry`（13 处权限引用）+ `DocVisibility.canSee`（全仓唯一一处可见性判据）。
- 我们：`SimosTool.roles()` → 两桶 → `ToolRoleAudit.requireFaces` 双向钉死。

### 2.4 ★ **审批口在适配层手里**（决定 U3 的可行性）

反编译核实：`DocsTools$DocTool` **没有覆写 `gate()`**（只有 `spec()` / `resources()` / `execute()`），
构造用的是 `ToolSpec.level(AccessToken.DEFAULT, …)` ⇒ **他们的工具默认不要求审批**。

⇒ 审批由**我们的适配层**决定（`AbstractNarrowWriteTool.gate()` 恒返回 `Ask(name, summary, AskKind.SENSITIVE)`）⇒
**"只给路径"这件事我们做得成**：适配层的 `gate()` **自己合成 summary**，不转发他们的（他们没有）。

---

## 3. 目标 / 非目标

**目标**：把 10 个外来工具**合规地**接进我们的两桶（守住"角色唯一拼写点"），并让
① 工作区 GM-only ② doc 系列 DM 自用 ③ 审批只给路径 ④ 真 LLM 可测。

**非目标**：
- ❌ 不改他们的库（**零改动**；他们的 `AgentTool` 不该为我们的角色模型改）；
- ❌ 不重建 Docs 读口——**我们已有的 `sd.DecisionDocs` / `sd.DecisionResults` / `simos.sd.reports` 不动**
  （它们建在 `GaState.info` 上；本批是**新增第二条通路**，不是替换）；
- ❌ 不做"旧文档迁移"（Alpha 不兼容旧档；且两者存储不同源）；
- ❌ 不做 GUI（用户已裁定 GUI 要重写，本批不做增量改）。

---

## 4. 设计

### 4.1 `simos-ga-spi`：`SimosToolAdapter`（新，1 个类）

**落 `simos-ga-spi`** 的理由：该模块已依赖 `agentlib-mosire`（看得见 `AgentTool`）且已声明 `SimosTool` / `ToolRole`；
★ 而它**不需要**依赖 `docs-mosire`（**适配类对文档零知识**）。

```java
public final class SimosToolAdapter implements SimosTool {
  private final AgentTool delegate;
  private final Set<ToolRole> roles;
  private final Function<ToolContext, ToolGate> gateOf;   // ★ 审批策略由装配方注入（见 §4.3）

  // 转发：name / description / jsonSchema / spec / resources / ledgerArgs / execute  —— 逐条委托，**不改语义**
  // 覆盖：roles() → roles；gate(ctx) → gateOf.apply(ctx)
}
```

★ **三条不可破坏的点**（不变量）：
1. **`execute` 逐字委托** —— 适配层**不得**吞结果、改参数、加白名单。
2. **`resources()` 必须原样转发** —— 那是他们的资源声明（`READ_MANIFEST`/`WRITE_MANIFEST`），我们的权限判定靠它。
3. ★★ **`gate()` 由装配方注入、不得丢弃** —— 吞掉审批 = 绕过审批链。

### 4.2 `simos-app`：角色表（我们单独维护的**唯一**东西）

```java
// 名字 → 角色。★ 逐条，不用"整库默认"（见 §5 不变量 I6）
// ★★ 本文初稿漏了 `unmount_workspace`（WorkspaceTools 实为 3 条，反编译核实：MOUNT/UNMOUNT/LIST）
//    ⇒ 实现方按 U1 补 {GM}（偏差 D3，控制方已核并采纳）
private static final Map<String, Set<ToolRole>> EXTERNAL_TOOL_ROLES = Map.ofEntries(
    entry(DocsTools.DOC_LIST,   ROLES_GM_DM),  entry(DocsTools.DOC_READ,  ROLES_GM_DM),
    entry(DocsTools.DOC_QUERY,  ROLES_GM_DM),  entry(DocsTools.DOC_APPEND, ROLES_GM_DM),
    entry(DocsTools.DOC_CREATE, ROLES_GM_DM),  entry(DocsTools.DOC_EDIT,  ROLES_GM_DM),
    entry(DocsTools.DOC_VOID,   ROLES_GM),     // 作废是 GM 动作
    entry(WorkspaceTools.MOUNT_WORKSPACE,   ROLES_GM),   // U1：工作区由 GM 维护
    entry(WorkspaceTools.UNMOUNT_WORKSPACE, ROLES_GM),   // ★ 初稿漏项，补
    entry(WorkspaceTools.LIST_WORKSPACES,   ROLES_GM));
```

**装配处（`SimosToolSource`）★ 必须按角色表过滤，不能无条件 `addAll`**：
```java
// ★★ 实测教训（2026-10-14，实现方 N1 探针先复现）：`doc_void` 声明 {GM}，
//   而无条件 addAll 进决策人桶 ⇒ requireFace 当场抛（"roles() 不含本桶角色"）。
//   所以：按角色表把工具分派到**各自的桶**，不是两桶都收全部。
for (AgentTool t : DocsTools.all(registry))      { addToBuckets(built, t, rolesOf(t.name())); }
for (AgentTool t : WorkspaceTools.all(registry)) { addToBuckets(built, t, rolesOf(t.name())); }
```
★ `rolesOf(name)` 在表里查不到 ⇒ **当场抛**（不许有缺省角色）—— 这样**他们将来加工具时我们这边当场红**，而不是静默给错角色。

### 4.3 ★★ U3：审批只给路径（适配层合成 summary）

**现状**：`ApprovalRequest` 有 `summary` 字段，`/api/approvals` 把它发给前端（`GuiServer:2057`）；
**我们的 `AbstractNarrowWriteTool.gate()` 是唯一填 `summary` 的地方**（`Ask(name, summary(context.arguments()), SENSITIVE)`）。

**新约束**：对外来文档工具，`summary` **只许含路径**（`mount` 参数的值），**不许含正文 / 不许含 tag / owner / 查询条件**。

```java
// 适配层注入的 gate 策略
ctx -> new ToolGate.Ask(toolName, pathOnlySummary(ctx.arguments()), AskKind.SENSITIVE)

// pathOnlySummary：只取 schema 里的路径键（mount），其余字段一律不出现
//   —— 白名单式取键，**不是黑名单式剔除**（黑名单会随他们的 schema 演进而漏）
```

★ **必须写成"白名单取键"**：只认 `mount`（路径）键；将来他们 schema 加字段，**不会**自动泄漏进 summary。
★ 写类工具（`doc_append`/`doc_create`/`doc_edit`/`doc_void`）**都**走这个 gate；读类（`doc_read`/`doc_query`/`doc_list`）**GATE 为 ALLOW**（读不需要审批）。
★ **诚实的代价（要写进交付说明）**：GM 审批时**只看到路径 ⇒ 看不到正文**，因此 GM 只能按"路径 + 谁在写"判断，
不能按内容判断。**这是用户的有意裁定（U3），不是缺陷** —— 但必须让 GM 知道它看不到什么。

### 4.4 U4：本地 gemma4 作为真 LLM 测试 provider

**实测（控制方亲测，两轮，2026-10-14）**：
```
GET http://127.0.0.1:8080/v1/models → 200
  gemma4-local / gguf / n_ctx=256000 / n_ctx_train=262144 / owned_by=llamacpp
  ★ capabilities 字段只报 ["completion"] —— 见下方更正
```

★★ **更正（同日）：`capabilities` 字段不可信，实测 tool calling 完全可用。**
第一轮我据该字段写了"没有 toolCalling"—— 那是**转抄字段、没实测**，属本仓 §9.4 明令禁止的形态。实测两轮：

| 轮 | 请求 | 实测返回 |
|---|---|---|
| **① 单轮** | `messages=[user]` + 一个 `tools=[get_weather]` + `tool_choice:auto` | **`finish_reason: "tool_calls"`**，`tool_calls=[{type:"function", function:{name:"get_weather", arguments:"{\"city\":\"北京\"}"}, id:"ogNkLe…"}]`，且带 `reasoning_content`；`usage.completion_tokens=108` |
| **② 多轮闭环** | 追加 `assistant.tool_calls` + `role:"tool"` 的结果 `{"city":"北京","tempC":21,"sky":"晴"}` | **`finish_reason: "stop"`**，正文「北京现在的天气是晴天，气温为 21°C。」 |

⇒ **① 它真的会调工具；② 它吃工具结果并收口** —— **正是决策人回合要的那条闭环**。
⇒ **所以它可用于"决策人调结构工具"那一路的真 LLM 测试**（原判"只能用 mosire-flash"**作废**）。
★ **残余不确定（要求实测，不许推断）**：它在**我们真实决策人回合**（20+ 工具、长上下文、多轮）下的表现未测 ——
必须真跑一轮才知道（§6.3 P8'）。

**落点**：`config/llm-providers.json`（仓库兜底种子，`AgentLibLlmConfig.LEGACY_FILE_NAME`）追加一条
`{"id":"gemma4-local","protocol":"openai-compatible","baseUrl":"http://127.0.0.1:8080/v1","model":"gemma4-local","capabilities":{"toolCalling":true},…}`。
★ `capabilities.toolCalling=true` **有实测支撑**（上表两轮），不是照抄端点自述。
★ **权威在 AgentLib 的 ConfigStore**，仓库那份只是"种子/迁移来源"（`AgentLibLlmConfig:74-75` 逐字：**只作兜底种子**）
⇒ 只改种子**不等于**运行时就生效；要生效得把 store 里的路由也更新（或空 store 首启迁移）。
★ **不许把 apiKey 写进日志**（§五.7）；本地这条**无 key**，更要注意别把 `Authorization` 打出来。

---

## 5. 不变量（不可破坏）

| # | 不变量 |
|---|---|
| **I1** | **`roles()` 仍是"谁能用"的唯一拼写点**：适配层是那 10 条工具的**唯一**角色声明处；`ToolRoleAudit.requireFaces` 双向钉死不许绕 |
| **I2** | **零改动隔壁库**：不改 `docs-mosire` / `workspace-mosire` / `agentlib-mosire` 任何文件 |
| **I3** | **`execute` / `resources` 逐字委托**（不吞、不改、不加白名单） |
| **I4** | **`gate()` 不得静默丢弃**：写类工具必须仍然进审批链 |
| **I5** | ★ **审批 `summary` 只含路径**：白名单取键（只认 `mount`），**不得**含正文/owner/tags/查询条件 |
| **I6** | ★ **角色表逐条、无缺省**：查不到 ⇒ 当场抛（他们新增工具时我们这边**红**，不许静默给默认角色） |
| **I7** | **命名保持他们的原名**（`doc_*` / `workspace_*`）：改名会让他们文档/日志/清单对不上 |
| **I8** | **既有的 `sd.DecisionDocs` / `sd.DecisionResults` / `simos.sd.reports` 不动**（本批是新增通路，不是替换） |
| **I9** | **决策人身份映射**：他们的可见性靠 `identity().instanceId()`；我们已有 `decision-maker:<id>`（`DecisionCallerFactory:215/338-348`）⇒ **挂载的 `user` 必须与之一致**，否则 `WORKSPACE_NOT_FOUND`（"不存在"与"看不见"刻意同码） |

---

## 6. 验收判据与负向用例

### 6.1 编译与既有测试

```bash
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile   # rc=0
tools/mvn-lock.sh clean verify                                # rc=0（本仓关账口径）
```
★ 基线：`b82535e3` 时 `clean verify` = **rc=0 / Tests=3218 / Failures=0 / Errors=0 / Skipped=5 / 前端 412/412 / SpotBugs 16 模块 0 bug**。
本批**不许让这个数字变差**。

### 6.2 正向

| # | 判据 |
|---|---|
| **P1** | **适配后两桶装配通过**：`ToolRoleAudit.requireFaces` 不抛；GM 桶与决策人桶各含应有的 `doc_*` |
| **P2** | **GM 能挂工作区**：`mount_workspace` 经 MCP 调用成功（照指南 §3.1 的 `registry.mount` 装配） |
| **P3** | **DM 在自己的工作区里能读写文档**：`doc_create` → `doc_read` 往返内容一致 |
| **P4** | ★ **审批 summary 只含路径**：触发 `doc_create` 的审批 ⇒ 检查 `ApprovalRequest.summary` **只出现路径**，**不出现正文** |
| **P5** | **角色表有效**：`doc_*` 在决策人桶里；`mount_workspace` **不在**决策人桶里（GM-only） |
| **P6** | **既有 Docs 通路未受影响**：`sd.DecisionDocs` / `sd.DecisionResults` 的既有用例全绿 |

### 6.3 ★ 真 LLM 验收（U4）

| # | 判据 |
|---|---|
| **P7** | `config/llm-providers.json` 有 `gemma4-local`（含 `capabilities.toolCalling=true`，**有实测支撑**）；provider 解析能列到它（`GET /api/llm/providers`） |
| **P8** | **自然语言回合**真跑通：用 gemma4-local 让某决策人跑一轮，产出 `intentInfo` 非空。★ **本文初稿写的 `simos.sd.run-decision` 不存在** —— 真名是 **`ga.RunDecision`**（反编译+代码核实；provider 切换用 `ga.SetDecisionMakerProvider`），偏差 D7 |
| **P8'** | ★★ **结构工具回合真跑通**（原判"做不到"**已作废**）：用 gemma4-local 让决策人**调真工具**。端点单轮/多轮 tool calling 已实测可用（§4.4 两轮），但**我们真实回合（20+ 工具、长上下文、多轮）下的表现未测** ⇒ **必须真跑一轮**：`ga.RunDecision` 返回的轨迹里出现至少一次**成功的工具调用**（非 `TOOL_NOT_FOUND`、非预算耗尽） |
| **P9** | ★ **如实记**：若 P8' 失败，**要分清**是"模型不支持"还是"我们回合太重 / 预算太小（`DEFAULT_MAX_LLM_CALLS=20`）"—— 前者改判 provider，后者是**被测出来的真问题**（要记账，不许含糊成"模型不行"） |

### 6.4 负向（★ 每条：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

| # | 负向用例 | 期望 |
|---|---|---|
| **N1** | 故意把外来工具**原样**塞进桶（不套适配层） | **装配当场抛**（`requireFace`）⇒ 证明适配层是必须的 |
| **N2** | 故意让 `rolesOf()` 对未知名字返回 `{GM,DM}`（去掉 fail-closed） | 加一个不存在的工具名 ⇒ 应当**红**；不红 = I6 破了 |
| **N3** | 故意把 `pathOnlySummary` 改成"返回整个 arguments" | **P4 当场红**（正文进 summary） |
| **N4** | 故意让适配层 `gate()` 恒返回 `ALLOW` | 写类工具的审批**消失** ⇒ P4 那条链测不到 ⇒ 红 |
| **N5** | 故意让 `execute` 不委托（吞掉结果） | P3 往返**红** |

### 6.5 真机验收（控制方执行）

照 `run-small-world.sh` 起独立实例（独立 store/端口），用 MCP 调：
① GM 挂一个工作区 → ② DM 在它里面 `doc_create`/`doc_read` 往返 → ③ **抓审批请求体，确认 summary 只有路径** →
④ 用 gemma4-local 跑一轮自然语言决策 → ⑤ 全量 `clean verify` 关账。

---

## 7. 文件所有权（一个责任区一个 owner）

### 7.1 实现 Agent A：适配层 + 角色表 + 装配

✅ 允许改/建：
- **建** `simos-ga-spi/src/main/java/io/mosire/simos/ga/spi/SimosToolAdapter.java`
- **改** `simos-app/pom.xml`（加 `docs-mosire` + `workspace-mosire` 两个依赖）
- **改** `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`（两行注册 + 角色表 + gate 策略）
- **改** `config/llm-providers.json`（加 `gemma4-local` 种子）
- **建** `.superpowers/sdd/2026-10-14-agentlib-docs-wiring/impl-ledger.md`

❌ 禁止：任何 `src/test/**`、`docs/**`、`AGENTS.md`、`simos-util/**`、`simos-core/**`、
以及隔壁三个仓的任何文件（I2：零改动）。

### 7.2 测试 Agent（A 交账后）

✅ 允许改：`simos-app/src/test/**`、`simos-ga-spi/src/test/**`、
`.superpowers/sdd/2026-10-14-agentlib-docs-wiring/`（`test-` 前缀）。
❌ 禁止：任何生产文件（发现残留 ⇒ 上报）。

### 7.3 控制方（我）

派单 / 验收 / **真机实测（§6.5）** / 落盘文档 / 提交。**不写代码**。

---

## 8. 现场提醒

1. **一次只跑一个 Maven**：`tools/mvn-lock.sh`；跑测试前 `rm -rf */target/surefire-reports`（§七）。
2. 工作树应干净（`b82535e3` 后全部已提交）—— 动手前 `git status --porcelain` 复核。
3. **不 `git add -A`**；本批按批次提交。
4. ★ **指南 §4 的"必踩清单"要当验收的一部分**：权限面三个命名空间（`workspaces`/`docs`/`docs-edit`）都要显式表态、
   `.destructiveAllowed(true)`、`identity.instanceId()` 必须等于挂载 `user`、工具必须经 `ToolCallAuthorizer` 执行。
   **这四条不落实，全线 fail-closed**（不是"少个功能"）。

---

## 9. 未核 / 如实记（写本文时的诚实边界）

1. **未跑 Maven**：所有"会不会红/绿"的判断均为静态推导；§6.1 的基线数字来自上一次实测（`b82535e3` 那轮）。
2. **未核**：`DocsApi.list` 的 **FILE 分支缺口**（隔壁指南 §7 自己标了"工具门有、库门没有"）——
   我们**只用工具门**（`doc_*`）故不阻塞，但若将来要用库门列举 FILE，**要先找隔壁补**。
3. **未核**：他们的**写入通道能否带"世界坐标"**（`branch`/`revision`/`tick`）并原样读回 ——
   我们需求书 R-S5 那条活依赖（`at(RevisionId)`）**仍是未答项**；`QuerySpec.tick` 是**他们的 tick 轴**，是否等同世界日**未核**。
4. **未核**：自定义字段的**开放度**（`owner`/`tags`/`affiliations` 之外能否塞 `worldid`）—— 需求书 R-C1 **仍是未答项**。
5. **未核**：`doc_append` 与 `doc_create`/`doc_edit` 的**纪律差别**（LOG vs FILE）在**同一个工作区**里能否共存。
6. **未核**：gemma4-local 的 **tool calling 实际能力**（capabilities 只报 `completion`；§6.3 P9 要求实测改判）。
7. **未核**：本批**不动**既有 `sd.DecisionDocs` 通路 ⇒ 会出现"两套文档系统并存"；是否合并/何时合并**待用户裁定**（本批不做）。

---

## 10. ★★ 追加裁定（2026-10-14，用户原话逐字）

> 「**在1的基础上给每个决策人一个默认工作区**」（回答"DM 的工作区从哪来"时，指选 1=「GM 用 MCP 给 DM 挂」）
> 另两条一并授权：「**把 gemma4-local 写进运行 store**」「**两条 runaway 用例改成不真跑 10000 轮**」

⇒ **本批目标升级**：① GM 侧必须**能**替 DM 挂（血缘路径）② **每个决策人开跑即有**一个默认工作区（无需 GM 逐次手工）。

### 10.1 血缘规则（控制方反编译 `DefaultWorkspaceRegistry` 逐条读出，**非转抄指南**）

```
M1  "不能以他人的名义创建工作区（holder 恒为创建者本人）"
        ⇒ mount.holder 必须 == 创建者的身份键
M2  "只能为自己或自己的后代创建工作区（user 必须落在自己的血缘子树内）"
        ⇒ mount.user 必须 ∈ 创建者血缘子树
    "声明的派生来源挂载不在创建者自己名下（derivedFrom 只能指向你自己切片里已有的挂载）"
        ⇒ 派生挂载的父挂载必须在创建者**自己的切片**里
    "子工作区必须是自己的目录下的真子目录（等于自己的工作区根 = 把权限整份复制）"
        ⇒ dir 必须是父挂载根下的**真**子目录
```
★ `Mount` 有**独立**的 `holder` / `user` 字段 ⇒ **替别人建在 API 层是允许的**（满足 M1+M2 即可）。
★★ 但 `mount_workspace` **工具**的 schema 只有 `{name, parent, subdir, kind}` —— **没有 `user`** ⇒
**工具门永远只能建 `user==调用者自己`**。⇒ **"GM 替 DM 挂"只能由装配期经 `WorkspaceRegistry.mount(...)` 建立。**

### 10.2 配方（两条同时成立）

**① GM 替 DM 挂（血缘）**：装配期建
`mount(holder=GMCaller, user="decision-maker:<id>", derivedFrom=GM 自己的根挂载, dir=GM根下真子目录, kind=FILE, caps={DocsCapability.ID}, origin=ASSEMBLY)`
—— M1 ✓（holder=创建者）、M2 ✓（user 在 GM 子树内：由 `derivedFrom` 的血缘推出）。

**② 每个决策人的默认工作区**：**同一个配方，装配期逐 DM 循环执行一次**（`<id>` 取自决策人注册表），
命名约定如 `workspace` + 子目录 `dm/<id>/`。⇒ DM 开跑即有一块自己的地，无需 GM 逐次手工。

★ **不需要新增 GM 工具**（工具门的 schema 表达不了 `user`，为它加参数会改隔壁代码 = 破 I2）；
装配期挂载是**宿主**的职责，正是"GM 维护工作区"的落点。

### 10.3 随之必做的三件（否则 P2–P8' 不可达）

| # | 事项 | 落点 |
|---|---|---|
| **W1** | ★★ **权限面接线**：GM 加 `workspaces`/`docs`/`docs-edit`；DM 加 `docs`/`docs-edit` **+ `.destructiveAllowed(true)`** | `Shell.java:1563-1576`（GM）、`DecisionCallerFactory.java:275-279`（DM） |
| | ★ 用户在多选里**没有勾这一条**；但**不接 ⇒ 全部 `doc_*` 与 `mount_*` fail-closed**（指南 §4 第 1/2/3 条实测原始报错）， | |
| | **「测试 DM 自由记录文档」这件事本身不可达** ⇒ 控制方按"用户明确要求测这件事"判定为**必要条件**，一并授权并在此标注 | |
| **W2** | 装配期**根挂载**（`mount_workspace` 只做派生形态 ⇒ 没有根挂载无从起步）+ §10.2 的逐 DM 循环 | `Shell.java` 装配段 |
| **W3** | `gemma4-local` **落运行 store**：现成 store **非空** ⇒ 兜底种子不自动迁移。 | `POST /api/llm/providers`（`GuiServer:217`）或 `ga.SetDecisionMakerProvider` |

### 10.4 runaway 用例（用户授权改）

`RunDecisionEndToEndTest:474-490` 与 `ResetConversationEndToEndTest:427` 在 `DEFAULT_MAX_LLM_CALLS=10000` 下会**真跑 10000 轮**
（逻辑自洽但墙钟暴涨）⇒ 这两条改用**显式给上限**的构造器（`DecisionAgentRunner` 本就有那个构造器，正是为这种用例准备的），
**不许**靠把常量改回去来"修"。

---

## 11. 关账（2026-10-14）与两条更正

### 11.1 验收（控制方亲跑，非转抄）
```
rm -rf */target/surefire-reports && tools/mvn-lock.sh clean verify
→ rc=0 / BUILD SUCCESS
→ Tests=3225  Failures=0  Errors=0  Skipped=5（基线 3218/0/0，+7 新用例）
→ 前端门禁 412/412 全绿 ；SpotBugs 16 模块 "BugInstance size is 0"
```
真回归修复前后对照（同一命令）：
| | 修复前 | 修复后 | 基线 `b82535e3` |
|---|---|---|---|
| Errors | **330** | **0** | 0 |
| Failures | 4 | **0** | 0 |

### 11.2 ★ 更正一：顺序约束有**两条**（控制方原只说了一条）
把工作区装配挪到 codec 注册之后 ⇒ **立刻被第二条炸出来**：
| # | 约束 | 违反时的原始报错 |
|---|---|---|
| **O1** | replay 要求信封里每个切片都有已装配 codec | `checkpoint 信封里有未装配 codec 的模块（信封与本实例的 codec 表不同源）: actor` |
| **O2** | **replay 会封存 core** ⇒ 之后再 `register` 抛 | `装配已封存（seal）：register 必须在第一次 submit/replay 之前完成` |
⇒ **正确定位只有一处：最后一条 `register` 之后。往上挪必炸、往下挪安全。** 不变式已钉在两处注释（装配处 + `currentDecisionMakerIds` 的 Javadoc）。

★ **教训（已入账本 §13.4）**：**空库探针测不到"已有世界"的路径**（`head` 为空 ⇒ 提前 `return` ⇒ 根本没走到 replay）
⇒ 实现方的空库探针漏了这个必炸路径。已补：非空 store 反复起壳 + 端到端"世界里真有 DM"两个探针。

### 11.3 ★ 更正二：`sensitive()` / `noExport()` **在 agentlib 内部有读者**（实现方的"零读者"判断是错的）
实现方（测试侧）上报时写「`spec().sensitive()` 在本仓 main 侧零读者」，并自己标注**未核**（没反编译 agentlib jar）。
控制方反编译核实 —— **jar 内部有两处读者**：

| 标志 | 读者 | 语义 |
|---|---|---|
| `sensitive()` | `io.mosire.agentlib.permission.PermissionChecker.sensitiveOrDestroyed` | `sensitive==true` ⇒ 要求 `AgentPermissionSet.sensitiveAllowed()`（即实测撞过的 `PERMISSION_DENIED: 工具 doc_create 为破坏性操作，需显式放行`） |
| `noExport()` | `io.mosire.agentlib.mcp.AgentToMcpServer.exportable` | `noExport==true` ⇒ **不外发 MCP** |

⇒ **结论（处置不变、理由更正）**：外来工具带的是 **vendor 的 spec**，而适配层按 I3 **逐字委托 `spec()`**，
故这两条旗标**保持 vendor 值**，不改、不另造 spec（那会破 I3）。
- `sensitive=false` ⇒ 跳过 `sensitiveAllowed` 检查 —— ★ **不构成安全缺口**：审批由**我们的 `gate()`** 管，
  而测试侧对 gate 的三条断言（`Ask` + `classKey==工具名` + `SENSITIVE`）**一字未减**；
- `noExport=false` ⇒ 仍外发 MCP —— ★ **正是本批要的**（`doc_*` 必须让 DM 用得上）。
★ **如实记**：这两条旗标的语义差异**未被独立用例覆盖**（既有用例只核 gate）；如需严格对齐 vendor 与宿主口径，**另开一条**.

### 11.4 未做（真机验收，归控制方）
**P2**（GM 经 MCP 挂工作区）· **P8/P8'/P9**（gemma4 真 LLM 回合，见 §6.3）—— 设计书 §6.5 明确真机验收归控制方。

---

## 12. ★ 真机验收（2026-10-14 03:00–03:08，控制方亲跑 · 独立 store `/tmp/docs-wiring-live`，端口 5811/5813/5815）

装置：`SIMOS_SMALL_WORLD_STORE=/tmp/docs-wiring-live ./run-small-world.sh`（shaded jar，19 hex 小世界，空库首启 bootstrap）。
所有 MCP 调用经真 `initialize` 握手 + `Mcp-Session-Id` + `tools/call`。

### 12.1 通过项

| # | 判据 | 真机原文 |
|---|---|---|
| **P2** | GM 经 MCP 挂工作区 | `list_workspaces` → `虚拟路径=workspace 纪律=FILE 能力=[docs] 归属=external-mcp 使用者=external-mcp **来源=ASSEMBLY**`；`可用作 parent 的挂载名：[workspace]`；`mount_workspace{name:gm-notes,parent:workspace,subdir:notes}` → `已挂载 … 派生自=workspace 来源=运行期` |
| **P2b** | GM 经 MCP 写文档 | `doc_create{mount:gm-notes/hello.md}` → `已创建 虚拟路径=gm-notes/hello.md 行数=1 字节=13` |
| **W3** | gemma4-local 落运行 store | `simos.llm.providers` → `{"id":"gemma4-local","valid":true,"baseUrl":"http://127.0.0.1:8080/v1","model":"gemma4-local"}` |
| **U2 前置** | ★ **每个决策人一个默认工作区** | DM 的可达面原文：`docs=/tmp/docs-wiring-live/workspaces/dm/dm-alpha; docs-edit=…/dm/dm-alpha`；盘上实测 `workspaces/dm/dm-alpha`、`workspaces/dm/dm-gamma` 两目录都在（含**装配期之后**才建的 DM ⇒ 懒补生效） |
| **P4** | ★ **审批只给路径** | `GET /api/approvals` → `{"tool":"doc_create","classKey":"doc_create","**summary":"doc_create mount=simos_sd_packet_intent**"}` —— **只有工具名 + 挂载（路径）**，**无正文 / 无 owner / 无 tags** |
| — | DM 真的一轮跑了真 LLM | `ga.RunDecision{dm-gamma}`：`DECISION_RUN_START` → 模型**真的调了 `doc_create`** → `APPROVAL_PENDING tool=doc_create caller=DEFAULT`（进审批链，符合 DM 语义） |

### 12.2 ★★ 发现的真缺口：DM **不知道**自己的工作区挂载名

真机证据两条：
```
WARN 工作区找不到: holder=decision-maker:dm-gamma mount=simos-command-catalog 虚拟路径=simos-command-catalog
APPROVAL_PENDING summary="doc_create mount=simos_sd_packet_intent"
```
⇒ 模型**把工具名当挂载名**用（`simos_sd_packet_intent` 是 packet-intent 工具的线名、`simos-command-catalog` 是 catalog 工具的线名）。
⇒ 正确挂载名是 **`workspace`**（`mountsOf(decision-maker:<id>) = [workspace …]`），但模型无从得知。

**根因**（`DecisionAgentRunner.openingUserMessage:713-790` 逐行核实）：开场消息**逐条点名**
`catalog` / `branches` / `packetIntent` / `propose` / `packetSubmit` / `myPacket`，最后说"以你实际 allowedTools 为准"，
**全程只字未提文档工具、也没提"你有一块自己的工作区"**。
★ 而 `catalog` 只列**命令类型**，**不含 `doc_*`**（它们是 `AgentTool` 不是命令）⇒ 模型**没有任何渠道**得知自己有文档能力。

⇒ **「DM 自由记录文档」在真机上"管道全通、但模型不知道"**：工具在面（DM 面 48 条含 6 条 doc）、工作区在盘、审批链在等，
**但模型只能猜挂载名 ⇒ 每次都失败**（上面那条 WARN 就是一次失败）。

### 12.3 另两条如实记

| # | 发现 | 性质 |
|---|---|---|
| **D-a** | `ga.CreateDecisionMaker` 的**工具描述**写「载荷 `{id, affiliation, allowedTools, cadence}`（四者全必填）」，真行为却 **拒收 `affiliation`**（`ga.CreateDecisionMaker 不再接受 affiliation：…用户裁定「归属肯定归模块」`） | **描述与行为漂移**（按描述写会 `REJECTED`） |
| **D-b** | `simos.state.info` / `simos.ga.decision-makers` 这两个名字**不存在**（真名 `simos.timeline.revisions` / `simos.sd.decision-makers`） | 控制方首次调用时猜错，非缺陷 |

### 12.4 未达（本轮没做，如实记）

- **P8/P8'（gemma4 真回合的"结构工具成功"判据）未达**：dm-gamma 那轮**卡在审批**（`APPROVAL_PENDING`，deadline 5 分钟）
  ⇒ 我的 `curl --max-time 300` 先超时；**没有观察到"审批通过后 doc_create 成功"**。
  ★ 且该轮即使批准**也会失败**（挂载名是猜的）⇒ 必须**先补 §12.2 的缺口**再验 P8'。
- **P3（DM 文档往返）**：测试侧在**进程内**已证（`AgentLibDocsWiringTest`，正文逐字一致）；
  **真机未达** —— 同上，被 §12.2 挡住。
